package com.apk.claw.android.tool.impl

import com.apk.claw.android.memory.MemoryManager
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.XLog

/**
 * 保存记忆工具
 *
 * Agent 在对话中了解到用户信息时调用此工具保存。
 */
class SaveMemoryTool : BaseTool() {

    override fun getName(): String = "save_memory"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("content", "string", "要记住的内容，建议格式 key:value，如 '姓名:张三'、'工号:A001'", true),
        ToolParameter(
            "category", "string",
            "记忆分类：profile(用户信息) / preference(偏好) / app(应用上下文) / fact(事实)",
            true
        ),
        ToolParameter("app", "string", "关联的应用名称（如'钉钉'），不传则为全局记忆", false),
        ToolParameter("tags", "string", "标签，逗号分隔，便于搜索（如 '工作,打卡'）", false)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val content = requireString(params, "content")
        val categoryStr = requireString(params, "category")

        // 解析分类
        val category = when (categoryStr.lowercase()) {
            "profile", "user_profile", "用户信息" -> MemoryManager.Category.USER_PROFILE
            "preference", "pref", "偏好" -> MemoryManager.Category.PREFERENCE
            "app", "app_context", "应用上下文" -> MemoryManager.Category.APP_CONTEXT
            "fact", "事实" -> MemoryManager.Category.FACT
            "cred", "credential", "凭证" -> MemoryManager.Category.CREDENTIAL
            else -> return ToolResult.error("未知分类：$categoryStr，可选：profile / preference / app / fact")
        }

        val appName = params["app"]?.toString()?.takeIf { it.isNotBlank() }
        val tagsStr = params["tags"]?.toString()?.takeIf { it.isNotBlank() }
        val tags = tagsStr?.split(",", "，")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        // 解析 content 为 key:value
        val colonIdx = content.indexOf(':')
        val (key, value) = if (colonIdx > 0) {
            content.substring(0, colonIdx).trim() to content.substring(colonIdx + 1).trim()
        } else {
            content to ""
        }

        val memory = MemoryManager.save(category, key, value, appName, tags)
        XLog.i(TAG, "💾 保存记忆: [${category.label}] $key=${value.take(50)} app=$appName")

        return ToolResult.success("已记住${if (appName != null) "「$appName」的" else ""}「$key」")
    }

    override fun getDescriptionEN(): String =
        "Save a memory. Category: profile/user_info, preference/pref, app/app_context, fact. " +
        "Content format: key:value. Use 'app' param to scope memory to a specific app."

    override fun getDescriptionCN(): String =
        "保存一条记忆。content 格式建议 key:value。category 可选：profile(用户信息) / preference(偏好) / app(应用上下文) / fact(事实)。" +
        "用 app 参数将记忆关联到某个应用，无关任务不会看到它。"
}

/**
 * 回忆记忆工具
 */
class RecallMemoryTool : BaseTool() {

    override fun getName(): String = "recall_memory"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("query", "string", "搜索关键词，不传则列出所有记忆", false),
        ToolParameter(
            "category", "string",
            "按分类筛选：profile / preference / app / fact（可选）",
            false
        )
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val query = params["query"]?.toString()?.takeIf { it.isNotBlank() }
        val categoryStr = params["category"]?.toString()?.takeIf { it.isNotBlank() }

        // 分类筛选
        val category = when (categoryStr?.lowercase()) {
            "profile", "user_profile", "用户信息" -> MemoryManager.Category.USER_PROFILE
            "preference", "pref", "偏好" -> MemoryManager.Category.PREFERENCE
            "app", "app_context", "应用上下文" -> MemoryManager.Category.APP_CONTEXT
            "fact", "事实" -> MemoryManager.Category.FACT
            "cred", "credential", "凭证" -> MemoryManager.Category.CREDENTIAL
            null -> null
            else -> return ToolResult.error("未知分类：$categoryStr")
        }

        val memories = MemoryManager.search(query)
            .let { if (category != null) it.filter { m -> m.category == category } else it }

        if (memories.isEmpty()) {
            return ToolResult.success(if (query != null) "没有找到与「$query」相关的记忆" else "暂无记忆")
        }

        val sb = StringBuilder()
        sb.append("【记忆列表】\n")
        memories.forEachIndexed { index, m ->
            val scope = if (m.appName != null) "[${m.appName}] " else ""
            val tags = if (m.tags.isNotEmpty()) " (${m.tags.joinToString(", ")})" else ""
            sb.append("${index + 1}. ${m.category.label}$scope${m.key}: ${m.value}$tags\n")
        }

        return ToolResult.success(sb.toString())
    }

    override fun getDescriptionEN(): String =
        "Recall memories. Optional query to search, optional category to filter."

    override fun getDescriptionCN(): String =
        "回忆已保存的记忆。可传 query 关键词搜索，可传 category 按分类筛选。不传参数则列出所有记忆。"
}
