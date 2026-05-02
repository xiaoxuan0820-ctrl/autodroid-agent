package com.apk.claw.android.agent

import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.agent.langchain.LangChain4jToolBridge
import com.apk.claw.android.agent.llm.LlmClient
import com.apk.claw.android.agent.llm.LlmClientFactory
import com.apk.claw.android.agent.llm.LlmResponse
import com.apk.claw.android.agent.llm.StreamingListener
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.memory.MemoryManager
import com.apk.claw.android.tool.impl.GetScreenInfoTool
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.XLog
import com.apk.claw.android.workflow.WorkflowTemplateManager
import com.apk.claw.android.workflow.WorkflowTemplateManager.ToolCallRecord
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.Content
import dev.langchain4j.data.message.ImageContent
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.TextContent
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.agent.tool.ToolExecutionRequest
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.LinkedList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DefaultAgentService : AgentService {

    companion object {
        private const val TAG = "AgentService"
        private val GSON = Gson()

        /** LLM API 调用失败时的最大重试次数 */
        private const val MAX_API_RETRIES = 3
        /** 死循环检测：滑动窗口大小 */
        private const val LOOP_DETECT_WINDOW = 4

        /** 是否将网络请求/响应原始数据输出到沙盒缓存文件，方便调试 */
        @JvmField
        var FILE_LOGGING_ENABLED = false
        @JvmField
        var FILE_LOGGING_CACHE_DIR: File? = null
    }

    private lateinit var config: AgentConfig
    private lateinit var llmClient: LlmClient
    private lateinit var toolSpecs: List<dev.langchain4j.agent.tool.ToolSpecification>
    private var executor: ExecutorService? = null
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    /** 视觉 fallback 模式：无障碍服务不可用时使用 screencap+input 命令 */
    private var useAdbFallback = false

    /** 当前任务的工具调用记录，用于自动学习模板 */
    private val currentToolCallRecords = mutableListOf<ToolCallRecord>()

    override fun initialize(config: AgentConfig) {
        this.config = config
        this.llmClient = LlmClientFactory.create(config)
        this.toolSpecs = LangChain4jToolBridge.buildToolSpecifications()
        this.executor = Executors.newSingleThreadExecutor()
        XLog.i(TAG, "Agent initialized: provider=${config.provider}, model=${config.modelName}, streaming=${config.streaming}")
    }

    override fun updateConfig(config: AgentConfig) {
        if (running.get()) {
            cancel()
            XLog.w(TAG, "Task was running during config update, cancelled")
        }
        executor?.shutdownNow()
        initialize(config)
        XLog.i(TAG, "Agent config updated, new model: ${config.modelName}")
    }

    override fun executeTask(userPrompt: String, callback: AgentCallback) {
        if (running.get()) {
            callback.onError(0, IllegalStateException("Agent is already running a task"), 0)
            return
        }

        running.set(true)
        cancelled.set(false)

        executor?.submit {
            try {
                runAgentLoop(userPrompt, callback)
            } catch (e: Exception) {
                XLog.e(TAG, "Agent execution error", e)
                callback.onError(0, e, 0)
            } finally {
                running.set(false)
            }
        }
    }

    // ==================== 环境预检 ====================

    private fun preCheck(): String? {
        if (ClawAccessibilityService.getInstance() == null) {
            // 视觉 fallback: 尝试使用 screencap + input 命令
            try {
                val procScreen = Runtime.getRuntime().exec(arrayOf("sh", "-c", "which screencap"))
                val procInput = Runtime.getRuntime().exec(arrayOf("sh", "-c", "which input"))
                if (procScreen.waitFor() == 0 && procInput.waitFor() == 0) {
                    useAdbFallback = true
                    XLog.i(TAG, "Accessibility unavailable, switching to visual fallback mode")
                    return null
                }
            } catch (e: Exception) {
                XLog.w(TAG, "Visual fallback check failed: ${e.message}")
            }
            useAdbFallback = false
            return ClawApplication.instance.getString(R.string.agent_accessibility_not_enabled)
        }
        useAdbFallback = false
        return null
    }

    // ==================== 设备上下文 ====================

    private fun buildDeviceContext(): String {
        val app = ClawApplication.instance
        val sb = StringBuilder()
        sb.append("\n\n## 设备信息\n")
        sb.append("- 品牌: ").append(Build.BRAND).append("\n")
        sb.append("- 型号: ").append(Build.MODEL).append("\n")
        sb.append("- Android 版本: ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")

        try {
            val wm = app
                .getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            sb.append("- 屏幕分辨率: ").append(dm.widthPixels).append("x").append(dm.heightPixels).append("\n")
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to get display metrics", e)
        }

        sb.append("- 已注册工具数: ").append(ToolRegistry.getAllTools().size).append("\n")
        sb.append("- 控制模式: ").append(if (useAdbFallback) "ADB Fallback" else "Accessibility Service").append("\n")

        val appName = try {
            val appInfo = app.packageManager.getApplicationInfo(app.packageName, 0)
            app.packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) { "CoPaw" }
        sb.append("\n## 本应用信息\n")
        sb.append("- 应用名: ").append(appName).append("\n")
        sb.append("- 包名: ").append(app.packageName).append("\n")
        sb.append("- 当用户提到'自己/本应用/这个应用'时，指的就是上述应用\n")

        return sb.toString()
    }

    // ==================== LLM 调用（带重试） ====================

    private fun chatWithRetry(messages: List<ChatMessage>, callback: AgentCallback, iteration: Int): LlmResponse {
        var lastException: Exception? = null
        for (attempt in 0 until MAX_API_RETRIES) {
            if (cancelled.get()) throw RuntimeException(ClawApplication.instance.getString(R.string.agent_task_cancelled))
            try {
                return if (config.streaming) {
                    val textBuilder = StringBuilder()
                    llmClient.chatStreaming(messages, toolSpecs, object : StreamingListener {
                        override fun onPartialText(token: String) {
                            textBuilder.append(token)
                            callback.onContent(iteration, token)
                        }
                        override fun onComplete(response: LlmResponse) {}
                        override fun onError(error: Throwable) {}
                    })
                } else {
                    llmClient.chat(messages, toolSpecs)
                }
            } catch (e: Exception) {
                lastException = e
                val msg = e.message ?: ""
                // Token 耗尽或认证失败不重试
                if (msg.contains("401") || msg.contains("403") || msg.contains("insufficient")) {
                    throw e
                }
                val delay = (Math.pow(2.0, attempt.toDouble()) * 1000).toLong()
                XLog.w(TAG, "LLM API call failed (attempt ${attempt + 1}/$MAX_API_RETRIES), retrying in ${delay}ms: $msg")
                try {
                    Thread.sleep(delay)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
        throw lastException!!
    }

    // ==================== 死循环检测 ====================

    private data class RoundFingerprint(val screenHash: Int, val toolCall: String)

    private fun isStuckInLoop(history: LinkedList<RoundFingerprint>): Boolean {
        if (history.size < LOOP_DETECT_WINDOW) return false
        val first = history.first()
        return history.all { it == first }
    }

    // ==================== 上下文压缩 ====================

    /** 保护区：最近 N 轮完整保留 */
    private val KEEP_RECENT_ROUNDS = 3

    /** 大输出观察类工具 → 压缩后占位符 */
    private val OBSERVATION_PLACEHOLDERS = mapOf(
        "get_screen_info" to "[屏幕信息已省略]",
        "take_screenshot" to "[截图结果已省略]",
        "find_node_info" to "[节点查找结果已省略]",
        "get_installed_apps" to "[应用列表已省略]",
        "scroll_to_find" to "[滚动查找结果已省略]"
    )

    /**
     * 发送前压缩历史消息，节省 input token：
     * - get_screen_info：全局只保留最新一条完整结果
     * - 保护区（最近 KEEP_RECENT_ROUNDS 轮）：完整保留
     * - 保护区外：AI thinking 不动，tool result 压缩为一行摘要
     */
    private fun compressHistoryForSend(messages: MutableList<ChatMessage>) {
        // 压缩前统计总字符数
        val charsBefore = messages.sumOf { msg ->
            when (msg) {
                is AiMessage -> (msg.text()?.length ?: 0) + (msg.toolExecutionRequests()?.sumOf { it.arguments()?.length ?: 0 } ?: 0)
                is ToolExecutionResultMessage -> msg.text().length
                is UserMessage -> msg.singleText().length
                is SystemMessage -> msg.text().length
                else -> 0
            }
        }
        val msgCountBefore = messages.size

        // 0. get_screen_info 特殊处理：无视分级，全局只保留最新一条完整结果
        val screenPlaceholder = OBSERVATION_PLACEHOLDERS["get_screen_info"]!!
        val lastScreenIdx = messages.indexOfLast {
            it is ToolExecutionResultMessage && it.toolName() == "get_screen_info"
        }
        for (i in messages.indices) {
            val msg = messages[i]
            if (msg is ToolExecutionResultMessage
                && msg.toolName() == "get_screen_info"
                && i != lastScreenIdx
                && msg.text() != screenPlaceholder
            ) {
                messages[i] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), screenPlaceholder)
            }
        }

        // 1. 找出所有 AiMessage 的索引，每个代表一轮
        val aiIndices = messages.indices.filter { messages[it] is AiMessage }
        if (aiIndices.size <= KEEP_RECENT_ROUNDS) return

        val totalRounds = aiIndices.size

        for (roundIdx in aiIndices.indices) {
            val roundFromEnd = totalRounds - roundIdx
            if (roundFromEnd <= KEEP_RECENT_ROUNDS) break // 保护区

            val aiIndex = aiIndices[roundIdx]

            // 收集本轮的 ToolExecutionResultMessage 索引
            var j = aiIndex + 1
            while (j < messages.size && messages[j] is ToolExecutionResultMessage) {
                compressToolResultMessage(messages, j)
                j++
            }
        }

        // 压缩后统计
        val charsAfter = messages.sumOf { msg ->
            when (msg) {
                is AiMessage -> (msg.text()?.length ?: 0) + (msg.toolExecutionRequests()?.sumOf { it.arguments()?.length ?: 0 } ?: 0)
                is ToolExecutionResultMessage -> msg.text().length
                is UserMessage -> msg.singleText().length
                is SystemMessage -> msg.text().length
                else -> 0
            }
        }
        val saved = charsBefore - charsAfter
        if (saved > 0) {
            XLog.i(TAG, "上下文压缩: ${charsBefore}→${charsAfter}字符, 节省${saved}字符(${saved * 100 / charsBefore}%), 轮数=${aiIndices.size}")
        }
    }

    /** 压缩 Tool Result：观察类工具用占位符，其他工具截取摘要 */
    private fun compressToolResultMessage(messages: MutableList<ChatMessage>, index: Int) {
        val msg = messages[index] as ToolExecutionResultMessage
        val text = msg.text()
        if (text.length <= 100) return // 已足够简短，无需压缩

        val placeholder = OBSERVATION_PLACEHOLDERS[msg.toolName()]
        if (placeholder != null) {
            messages[index] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), placeholder)
            return
        }

        // 其他工具：解析 JSON 提取摘要
        val compressed = summarizeToolResult(text)
        messages[index] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), compressed)
    }

    /** 将 ToolResult JSON 压缩为一行摘要 */
    private fun summarizeToolResult(resultJson: String): String {
        return try {
            val mapType = object : TypeToken<Map<String, Any?>>() {}.type
            val map: Map<String, Any?> = GSON.fromJson(resultJson, mapType)
            val isSuccess = map["isSuccess"] as? Boolean ?: false
            if (isSuccess) {
                val data = map["data"]?.toString() ?: "ok"
                "✓ " + if (data.length > 80) data.take(80) + "..." else data
            } else {
                val error = map["error"]?.toString() ?: "failed"
                "✗ " + if (error.length > 80) error.take(80) + "..." else error
            }
        } catch (_: Exception) {
            if (resultJson.length > 80) resultJson.take(80) + "..." else resultJson
        }
    }

    // ==================== 截图辅助（视觉输入 & 死循环自救） ====================

    /** 截图任务名称 */
    private val SCREENSHOT_TOOL = "take_screenshot"

    /**
     * 截图并转为 Base64 JPEG（50% quality），返回 data URI 格式字符串。
     * 失败时返回 null。
     */
    private fun captureScreenshotAsBase64(): String? {
        return try {
            val service = ClawAccessibilityService.getInstance() ?: return null
            val bitmap = service.takeScreenshot(5000) ?: return null
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 50, outputStream)
            val byteArray = outputStream.toByteArray()
            bitmap.recycle()
            val base64 = Base64.getEncoder().encodeToString(byteArray)
            "data:image/jpeg;base64,$base64"
        } catch (e: Exception) {
            XLog.w(TAG, "截图转 Base64 失败: ${e.message}")
            null
        }
    }

    /**
     * 在 LLM 调用前附加截图视觉信息到最新的 UserMessage。
     * 将最新的 UserMessage 替换为包含文本 + 图片的复合消息。
     */
    private fun attachScreenshotToUserMessage(messages: MutableList<ChatMessage>) {
        val base64Uri = captureScreenshotAsBase64() ?: return

        // 找到最后一个 UserMessage 替换
        val lastUserIdx = messages.indexOfLast { it is UserMessage }
        if (lastUserIdx < 0) return

        val originalUserMessage = messages[lastUserIdx] as UserMessage
        val originalText = originalUserMessage.singleText() ?: ""

        // 构建包含文本和图片的新 UserMessage
        val contents = mutableListOf<Content>()
        contents.add(TextContent(originalText))
        try {
            contents.add(ImageContent(base64Uri))
        } catch (e: Exception) {
            // 如果 ImageContent 构造失败，保持原样
            XLog.w(TAG, "添加 ImageContent 失败: ${e.message}")
            return
        }
        messages[lastUserIdx] = UserMessage(contents)
        XLog.d(TAG, "已附加截图视觉信息到 UserMessage")
    }

    // ==================== 死循环自救机制 ====================

    /** 工具调用历史，每轮记录 toolName + 参数摘要 */
    private data class ToolCallSnapshot(
        val toolName: String,
        val argsSummary: String
    )

    /** 连续相同调用的最大次数（触发截图自救） */
    private val MAX_CONSECUTIVE_IDENTICAL = 5

    /** 自救阶段最多轮数（3 轮 * 5 次 = 15 次连续相同） */
    private val MAX_RESCUE_ROUNDS = 3

    /**
     * 检查工具调用是否陷入死循环（同一工具+相同参数连续重复）。
     * 返回自救计数（0 = 正常，1-3 = 第几次自救，-1 = 需要强制终止）。
     */
    private data class LoopCheckResult(
        val isStuck: Boolean,
        val consecutiveCount: Int,
        val shouldTerminate: Boolean
    )

    private fun checkToolLoop(
        history: MutableList<ToolCallSnapshot>,
        toolName: String,
        argsSummary: String
    ): LoopCheckResult {
        history.add(ToolCallSnapshot(toolName, argsSummary))

        // 计算最后连续相同调用的次数
        var consecutiveCount = 0
        for (i in history.indices.reversed()) {
            val entry = history[i]
            if (entry.toolName == toolName && entry.argsSummary == argsSummary) {
                consecutiveCount++
            } else {
                break
            }
        }

        if (consecutiveCount >= MAX_CONSECUTIVE_IDENTICAL * MAX_RESCUE_ROUNDS) {
            return LoopCheckResult(true, consecutiveCount, true)
        }

        if (consecutiveCount >= MAX_CONSECUTIVE_IDENTICAL) {
            return LoopCheckResult(true, consecutiveCount, false)
        }

        // 不在死循环状态时，清理太旧的历史（只保留最近 20 条）
        if (history.size > 20) {
            val toRemove = history.size - 20
            repeat(toRemove) { history.removeFirstOrNull() }
        }

        return LoopCheckResult(false, consecutiveCount, false)
    }

    // ==================== 主执行循环 ====================

    private fun runAgentLoop(userPrompt: String, callback: AgentCallback) {
        // 清空上次的工具调用记录
        currentToolCallRecords.clear()

        // 环境预检
        preCheck()?.let {
            callback.onError(0, RuntimeException(it), 0)
            return
        }

        // 构建 System Prompt（原始 + 设备上下文 + 记忆上下文 + 人格后缀）
        val memoryContext = MemoryManager.buildMemoryContext(
            currentApp = null,
            taskPrompt = userPrompt
        )
        val personaSuffix = Persona.getActive().getSystemPromptSuffix()
        val pdcaInstruction = "\n\n## PDCA 执行原则\n- 每次调用操作工具后，系统会自动截图验证执行效果\n- 通过分析截图判断操作是否生效\n- 如果操作未生效，尝试重试或改用其他方法\n- 确认操作成功后，再执行下一步"
        val fullSystemPrompt = config.systemPrompt + buildDeviceContext() + memoryContext + "\n\n" + personaSuffix + pdcaInstruction

        val messages = mutableListOf<ChatMessage>()
        messages.add(SystemMessage.from(fullSystemPrompt))
        messages.add(UserMessage.from(userPrompt))

        var iterations = 0
        var totalTokens = 0
        val maxIterations = config.maxIterations
        val loopHistory = LinkedList<RoundFingerprint>()
        var lastScreenHash = 0
        // 死循环自救：记录每轮的工具名称+参数摘要
        val toolCallHistory = mutableListOf<ToolCallSnapshot>()
        var rescueStage = 0  // 0=正常, 1-3=第几次自救
        var lastRescueIteration = 0

        while (iterations < maxIterations && !cancelled.get()) {
            iterations++
            callback.onLoopStart(iterations)

            // [视觉输入] 在 LLM 调用前附加截图（每轮一次）
            attachScreenshotToUserMessage(messages)

            // 发送前分级压缩历史消息，节省 token
            compressHistoryForSend(messages)

            // LLM 调用（带重试）
            val llmResponse: LlmResponse
            try {
                llmResponse = chatWithRetry(messages, callback, iterations)
            } catch (e: Exception) {
                XLog.e(TAG, "LLM API call failed after retries", e)
                callback.onError(iterations, RuntimeException(ClawApplication.instance.getString(R.string.agent_api_call_failed, e.message)), totalTokens)
                return
            }

            // 累加 token 用量
            llmResponse.tokenUsage?.totalTokenCount()?.let { totalTokens += it }

            // 将 AI 消息添加到历史（需要构造 AiMessage）
            val aiMessage = if (llmResponse.hasToolExecutionRequests()) {
                if (llmResponse.text.isNullOrEmpty()) {
                    AiMessage.from(llmResponse.toolExecutionRequests)
                } else {
                    AiMessage.from(llmResponse.text, llmResponse.toolExecutionRequests)
                }
            } else {
                AiMessage.from(llmResponse.text ?: "")
            }
            messages.add(aiMessage)

            // 非流式模式下推送思考内容
            if (!config.streaming && !llmResponse.text.isNullOrEmpty()) {
                callback.onContent(iterations, llmResponse.text)
            }

            // 如果没有工具调用，Agent 认为完成了
            if (!llmResponse.hasToolExecutionRequests()) {
                val finalAnswer = llmResponse.text ?: ClawApplication.instance.getString(R.string.agent_task_completed)
                // 自动学习：从成功执行中生成模板
                learnFromCurrentExecution(userPrompt)
                callback.onComplete(iterations, finalAnswer, totalTokens)
                return
            }

            // 执行工具调用
            for (toolRequest in llmResponse.toolExecutionRequests) {
                if (cancelled.get()) {
                    callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens)
                    return
                }

                val toolName = toolRequest.name() ?: ""
                val displayName = ToolRegistry.getInstance().getDisplayName(toolName)
                val toolArgs = toolRequest.arguments() ?: "{}"
                callback.onToolCall(iterations, toolName, displayName, toolArgs)

                // 解析参数
                val mapType = object : TypeToken<Map<String, Any>>() {}.type
                var params: Map<String, Any>? = try {
                    GSON.fromJson(toolArgs, mapType)
                } catch (e: Exception) {
                    HashMap()
                }
                if (params == null) params = HashMap()

                val result = ToolRegistry.getInstance().executeTool(toolName, params)
                val paramsString = if (params.isEmpty()) "" else params.toString()
                callback.onToolResult(iterations, toolName, displayName, paramsString, result)

                // 记录工具调用（用于模板学习）
                // 只记录操作类工具，跳过 get_screen_info 等观察类工具
                if (toolName != "get_screen_info" && toolName != "take_screenshot" && toolName != "find_node_info") {
                    val waitFor = when (toolName) {
                        "open_app" -> 3000
                        "input_text" -> 1000
                        else -> 500
                    }
                    currentToolCallRecords.add(ToolCallRecord(
                        toolName = toolName,
                        params = params,
                        waitFor = waitFor,
                        isVerification = toolName == "get_screen_info"
                    ))
                }

                // 检测到系统弹窗阻塞 → 截图通知用户并结束任务
                if (!result.isSuccess && result.error == GetScreenInfoTool.SYSTEM_DIALOG_BLOCKED) {
                    XLog.w(TAG, "System dialog blocked, notifying user and stopping task")
                    callback.onSystemDialogBlocked(iterations, totalTokens)
                    return
                }

                // finish 工具 → 任务完成
                if (toolName == "finish" && result.isSuccess) {
                    val finishData = result.data
                    // 自动学习：从成功执行中生成模板
                    learnFromCurrentExecution(userPrompt)
                    callback.onComplete(iterations, finishData ?: ClawApplication.instance.getString(R.string.agent_task_completed), totalTokens)
                    return
                }

                // 记录指纹用于死循环检测
                if (toolName == "get_screen_info" && result.isSuccess && result.data != null) {
                    lastScreenHash = result.data.hashCode()
                } else if (toolName.isNotEmpty() && toolName != "get_screen_info") {
                    loopHistory.addLast(RoundFingerprint(lastScreenHash, "$toolName:$toolArgs"))
                    if (loopHistory.size > LOOP_DETECT_WINDOW) {
                        loopHistory.removeFirst()
                    }
                }

                // 添加工具结果到消息
                val resultJson = GSON.toJson(result)
                messages.add(ToolExecutionResultMessage.from(toolRequest, resultJson))
                XLog.d(TAG, "displayName:$displayName toolName:$toolName")
            }

            // PDCA: 本轮工具执行完毕，自动截图供下一轮 LLM 验证
            if (!cancelled.get() && llmResponse.toolExecutionRequests.isNotEmpty()) {
                try {
                    Thread.sleep(500)
                    val pdcaResult = ToolRegistry.getInstance().executeTool("get_screen_info", emptyMap())
                    if (pdcaResult.isSuccess && pdcaResult.data != null) {
                        lastScreenHash = pdcaResult.data.hashCode()
                        messages.add(ToolExecutionResultMessage.from("pdca", "verify_screenshot",
                            "[PDCA验证] 本轮执行后的屏幕状态:\n${pdcaResult.data.take(3000)}"))
                        XLog.d(TAG, "PDCA: post-round screenshot captured")
                    }
                } catch (e: Exception) {
                    XLog.w(TAG, "PDCA screenshot failed: ${e.message}")
                }
            }

            // ==================== 死循环自救机制 ====================
            // 在工具执行完毕后，对最后一个工具执行死循环检查
            val lastToolRequest = llmResponse.toolExecutionRequests.lastOrNull()
            if (lastToolRequest != null && lastToolRequest.name() !in listOf("get_screen_info", "take_screenshot", "find_node_info")) {
                val lastToolName = lastToolRequest.name() ?: ""
                val lastArgs = lastToolRequest.arguments() ?: "{}"
                val loopResult = checkToolLoop(toolCallHistory, lastToolName, lastArgs)

                if (loopResult.shouldTerminate) {
                    XLog.w(TAG, "死循环自救失败，强制终止任务: $lastToolName 已连续重复 ${loopResult.consecutiveCount} 次")
                    val errMsg = "任务因死循环强制终止：工具「$lastToolName」已连续重复 ${loopResult.consecutiveCount} 次，自救失败。"
                    callback.onError(iterations, RuntimeException(errMsg), totalTokens)
                    return
                }

                if (loopResult.isStuck && iterations != lastRescueIteration) {
                    rescueStage++
                    lastRescueIteration = iterations
                    XLog.w(TAG, "检测到疑似死循环（自救第 ${rescueStage}/${MAX_RESCUE_ROUNDS} 轮）: $lastToolName 已重复 ${loopResult.consecutiveCount} 次")

                    // 自动截图并附加视觉信息
                    val rescueScreenshot = captureScreenshotAsBase64()
                    val rescueContents = mutableListOf<Content>()
                    rescueContents.add(TextContent("⚠️ 检测到可能死循环：$lastToolName 已重复 ${loopResult.consecutiveCount} 次。请分析截图寻找新方案。"))
                    if (rescueScreenshot != null) {
                        try {
                            rescueContents.add(ImageContent(rescueScreenshot))
                        } catch (e: Exception) {
                            XLog.w(TAG, "自救截图添加 ImageContent 失败: ${e.message}")
                        }
                    }
                    messages.add(UserMessage(rescueContents))
                    XLog.i(TAG, "死循环自救第${rescueStage}轮：已注入截图和提示")
                }
            }
            XLog.d(TAG, "轮数:$iterations all=$totalTokens 本轮=${llmResponse.tokenUsage?.totalTokenCount()}")
        }

        if (cancelled.get()) {
            callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens)
        } else {
            callback.onError(iterations, RuntimeException(ClawApplication.instance.getString(R.string.agent_max_iterations, maxIterations)), totalTokens)
        }
    }

    /**
     * 从当前执行结果中学习模板
     * 只在有实际工具调用（超过观察类工具）且成功时保存
     */
    private fun learnFromCurrentExecution(userPrompt: String) {
        if (currentToolCallRecords.size < 2) return  // 太简单的操作不学

        // 提取目标应用名（从 open_app 调用中获取）
        val appName = currentToolCallRecords.firstOrNull { it.toolName == "open_app" }
            ?.params?.get("package_name")?.toString()

        try {
            val template = WorkflowTemplateManager.learnFromExecution(
                userPrompt = userPrompt,
                toolCalls = currentToolCallRecords.toList(),
                appName = appName,
                success = true
            )
            if (template != null) {
                XLog.i(TAG, "🧠 自动学习模板: ${template.name} (${currentToolCallRecords.size}步)")
            }
        } catch (e: Exception) {
            XLog.w(TAG, "模板自动学习失败: ${e.message}")
        }
    }

    override fun cancel() {
        cancelled.set(true)
    }

    override fun shutdown() {
        cancel()
        executor?.shutdownNow()
    }

    override fun isRunning(): Boolean = running.get()
}
