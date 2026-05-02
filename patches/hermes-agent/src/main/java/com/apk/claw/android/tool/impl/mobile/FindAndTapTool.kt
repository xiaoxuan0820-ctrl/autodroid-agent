package com.apk.claw.android.tool.impl.mobile

import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.XLog
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 按文本/ID 查找节点并点击的工具。
 *
 * 替代纯坐标点击，支持：
 * - text 参数：通过 ClawAccessibilityService.findNodesByText() 找到匹配元素
 * - id 参数：通过 findNodesById() 找到匹配元素
 * - index 参数（可选）：当有多个匹配时选第几个
 * - fallback 参数（可选，默认 true）：如果 clickNode() 失败，回退到坐标点击
 */
class FindAndTapTool : BaseTool() {

    private val TAG = "FindAndTapTool"

    override fun getName(): String = "find_and_tap"

    override fun getDescriptionEN(): String =
        "Find a UI element by text or resource-id and tap it. " +
        "Provide 'text' to search by visible text, or 'id' to search by resource-id. " +
        "If multiple elements match, use 'index' to specify which one (0-based)."

    override fun getDescriptionCN(): String =
        "根据文本或资源ID查找UI元素并点击。提供 text 按可见文本搜索，或 id 按资源ID搜索。" +
        "多个匹配时可用 index 指定点第几个（从0开始）。如果不指定 index 则点击第一个。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("text", "string", "要查找的文本内容（可见文本或 contentDescription）", false),
        ToolParameter("id", "string", "要查找的资源 ID（如 com.example:id/button）", false),
        ToolParameter("index", "integer", "当有多个匹配时选择第几个（0-based），默认0", false),
        ToolParameter("fallback", "boolean", "如果 clickNode 失败是否回退到坐标点击，默认 true", false)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val service = ClawAccessibilityService.getInstance()
            ?: return ToolResult.error("Accessibility service is not running")

        val text = params["text"]?.toString()?.takeIf { it.isNotBlank() }
        val id = params["id"]?.toString()?.takeIf { it.isNotBlank() }
        val index = optionalInt(params, "index", 0)
        val fallback = optionalBoolean(params, "fallback", true)

        // 必须提供 text 或 id
        if (text == null && id == null) {
            return ToolResult.error("必须提供 text 或 id 参数之一")
        }

        // 查找节点
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        try {
            if (text != null) {
                val found = service.findNodesByText(text)
                if (found != null) nodes.addAll(found)
                XLog.d(TAG, "findNodesByText('$text') found ${nodes.size} nodes")
            }
            if (id != null) {
                val found = service.findNodesById(id)
                if (found != null) nodes.addAll(found)
                XLog.d(TAG, "findNodesById('$id') found ${nodes.size} nodes")
            }
        } catch (e: Exception) {
            return ToolResult.error("查找节点失败: ${e.message}")
        }

        // 去重（同一个节点可能同时匹配 text 和 id）
        val distinctNodes = nodes.distinct()

        if (distinctNodes.isEmpty()) {
            val searchTerm = text ?: id!!
            // 尝试 fallback 坐标点击？没有坐标信息，直接返回错误
            return ToolResult.error("未找到匹配「$searchTerm」的节点")
        }

        if (index >= distinctNodes.size) {
            return ToolResult.error("索引越界：共 ${distinctNodes.size} 个匹配，但请求 index=$index")
        }

        val targetNode = distinctNodes[index]

        // 尝试 clickNode
        val clicked = service.clickNode(targetNode)
        if (clicked) {
            // 获取节点信息用于日志
            val nodeInfo = service.getNodeDetail(targetNode)
            XLog.i(TAG, "成功点击节点: $nodeInfo")
            return ToolResult.success("已点击「${text ?: id}」(${if (index > 0) "第${index + 1}个" else "第一个"})")
        }

        // clickNode 失败，尝试 fallback 坐标点击
        if (fallback) {
            val bounds = android.graphics.Rect()
            targetNode.getBoundsInScreen(bounds)
            if (!bounds.isEmpty() && bounds.width() > 0 && bounds.height() > 0) {
                val centerX = bounds.centerX()
                val centerY = bounds.centerY()
                val tapped = service.performTap(centerX, centerY)
                if (tapped) {
                    XLog.i(TAG, "坐标回退点击成功: ($centerX, $centerY)")
                    return ToolResult.success("已点击「${text ?: id}」的坐标 ($centerX, $centerY)（回退模式）")
                }
            }
        }

        return ToolResult.error("点击「${text ?: id}」失败，节点不可点击且坐标回退失败")
    }
}

/**
 * 先查找再点击的工具，找不到时返回友好提示而非错误。
 */
class TapIfExistsTool : BaseTool() {

    override fun getName(): String = "tap_if_exists"

    override fun getDescriptionEN(): String =
        "Try to find a UI element by text or resource-id and tap it. " +
        "Unlike find_and_tap, this tool returns a friendly message instead of an error if the element is not found. " +
        "Useful for optional elements that may or may not be on screen."

    override fun getDescriptionCN(): String =
        "尝试根据文本或资源ID查找UI元素并点击。" +
        "与 find_and_tap 不同，如果找不到元素，此工具返回友好提示而非错误。" +
        "适用于可选元素（如弹窗关闭按钮），找不到也正常。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("text", "string", "要查找的文本内容", false),
        ToolParameter("id", "string", "要查找的资源 ID", false),
        ToolParameter("index", "integer", "当有多个匹配时选择第几个（0-based），默认0", false),
        ToolParameter("fallback", "boolean", "是否回退到坐标点击，默认 true", false)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val service = ClawAccessibilityService.getInstance()
            ?: return ToolResult.error("Accessibility service is not running")

        val text = params["text"]?.toString()?.takeIf { it.isNotBlank() }
        val id = params["id"]?.toString()?.takeIf { it.isNotBlank() }
        val index = optionalInt(params, "index", 0)
        val fallback = optionalBoolean(params, "fallback", true)

        if (text == null && id == null) {
            return ToolResult.error("必须提供 text 或 id 参数之一")
        }

        val nodes = mutableListOf<AccessibilityNodeInfo>()
        try {
            if (text != null) {
                val found = service.findNodesByText(text)
                if (found != null) nodes.addAll(found)
            }
            if (id != null) {
                val found = service.findNodesById(id)
                if (found != null) nodes.addAll(found)
            }
        } catch (e: Exception) {
            // 查找异常也视为"不存在"
            val searchTerm = text ?: id!!
            return ToolResult.success("未找到「$searchTerm」，跳过此操作（查找异常: ${e.message}）")
        }

        val distinctNodes = nodes.distinct()
        if (distinctNodes.isEmpty()) {
            val searchTerm = text ?: id!!
            return ToolResult.success("未找到「$searchTerm」，跳过此操作")
        }

        if (index >= distinctNodes.size) {
            return ToolResult.success("未找到第 ${index + 1} 个「${text ?: id}」（共 ${distinctNodes.size} 个），跳过此操作")
        }

        val targetNode = distinctNodes[index]
        val clicked = service.clickNode(targetNode)
        if (clicked) {
            return ToolResult.success("已点击「${text ?: id}」")
        }

        if (fallback) {
            val bounds = android.graphics.Rect()
            targetNode.getBoundsInScreen(bounds)
            if (!bounds.isEmpty() && bounds.width() > 0 && bounds.height() > 0) {
                val tapped = service.performTap(bounds.centerX(), bounds.centerY())
                if (tapped) {
                    return ToolResult.success("已点击「${text ?: id}」的坐标 (${bounds.centerX()}, ${bounds.centerY()})（回退模式）")
                }
            }
        }

        // 即便失败也返回友好提示
        return ToolResult.success("尝试点击「${text ?: id}」但未成功（元素不可点击），继续执行")
    }
}
