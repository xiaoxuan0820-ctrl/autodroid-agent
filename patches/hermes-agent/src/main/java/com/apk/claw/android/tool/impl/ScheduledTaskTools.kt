package com.apk.claw.android.tool.impl

import com.apk.claw.android.scheduler.TaskScheduler
import com.apk.claw.android.scheduler.ScheduledTaskTemplates
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * 创建定时任务工具
 * 支持：多时段、工作日模式、模板
 */
class ScheduleTaskTool : BaseTool() {

    override fun getName(): String = "schedule_task"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("time", "string", "执行时间，格式 HH:mm。支持多个时间用逗号分隔，如 '9:00,18:00'", true),
        ToolParameter("prompt", "string", "要执行的任务描述，如 '打开钉钉打卡'", true),
        ToolParameter("name", "string", "任务名称（可选），如 '早上打卡'，不传则用 prompt 前20字", false),
        ToolParameter("repeat", "boolean", "是否每天重复执行，默认 true", false),
        ToolParameter("workdays_only", "boolean", "是否仅工作日执行，默认 false", false),
        ToolParameter("template", "string", "使用预设模板（可选）：dingtalk_checkin / feishu_checkin / douyin_browse / taobao_sign 等", false)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        // 支持通过模板创建
        val templateId = params["template"]?.toString()?.takeIf { it.isNotBlank() }
        if (templateId != null) {
            val timeStr = params["time"]?.toString()?.takeIf { it.isNotBlank() }
            val timeSlots = if (timeStr != null) TaskScheduler.parseTimeSlots(timeStr) else null

            val task = ScheduledTaskTemplates.createTaskFromTemplate(templateId, timeSlots)
            if (task != null) {
                return formatResult(task)
            }
            return ToolResult.error("模板不存在或参数错误，可用模板：${ScheduledTaskTemplates.getAll().joinToString(", ") { it.id }}")
        }

        // 普通创建
        val timeStr = requireString(params, "time")
        val prompt = requireString(params, "prompt")
        val name = params["name"]?.toString()?.takeIf { it.isNotBlank() } ?: prompt.take(20)
        val repeat = optionalBoolean(params, "repeat", true)
        val workdaysOnly = optionalBoolean(params, "workdays_only", false)

        // 解析多时段
        val timeSlots = TaskScheduler.parseTimeSlots(timeStr)
        if (timeSlots.isEmpty()) {
            return ToolResult.error("时间格式错误，请使用 HH:mm 格式，多个时间用逗号分隔，如 '9:00,18:00'")
        }

        val task = TaskScheduler.addTask(
            name = name,
            prompt = prompt,
            timeSlots = timeSlots,
            repeat = repeat,
            workdaysOnly = workdaysOnly
        )

        return formatResult(task)
    }

    private fun formatResult(task: TaskScheduler.ScheduledTask): ToolResult {
        val modeStr = when {
            task.workdaysOnly -> "工作日"
            task.repeat -> "每天"
            else -> "一次"
        }
        val nextTimes = task.timeSlots.mapIndexed { index, _ ->
            val next = task.getNextExecutionTime(index)
            if (next > 0) {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                sdf.format(java.util.Date(next))
            } else ""
        }.filter { it.isNotEmpty() }

        return ToolResult.success(
            "已创建定时任务「${task.name}」\n" +
            "时间: ${task.getTimeString()} ($modeStr)\n" +
            "任务: ${task.prompt}\n" +
            "下次执行: ${nextTimes.joinToString(", ")}"
        )
    }

    override fun getDescriptionEN(): String =
        "Schedule a task. Time supports multiple slots like '9:00,18:00'. " +
        "Use workdays_only=true for workdays only. " +
        "Or use template=xxx for preset templates (dingtalk_checkin, feishu_checkin, etc)."

    override fun getDescriptionCN(): String =
        "创建定时任务。time 支持多时段，如 '9:00,18:00'。workdays_only=true 仅工作日执行。" +
        "也可用 template 参数使用预设模板，如 dingtalk_checkin、douyin_browse、taobao_sign 等。"
}

/**
 * 列出定时任务工具
 */
class ListScheduledTasksTool : BaseTool() {

    override fun getName(): String = "list_scheduled_tasks"

    override fun getParameters(): List<ToolParameter> = emptyList()

    override fun execute(params: Map<String, Any>): ToolResult {
        return ToolResult.success(TaskScheduler.formatTaskList())
    }

    override fun getDescriptionEN(): String = "List all scheduled tasks."

    override fun getDescriptionCN(): String = "列出所有定时任务"
}

/**
 * 取消定时任务工具
 */
class CancelScheduledTaskTool : BaseTool() {

    override fun getName(): String = "cancel_scheduled_task"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("index", "integer", "任务序号（从 list_scheduled_tasks 获取），从1开始", true)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val index = requireInt(params, "index")
        return if (index < 1) {
            ToolResult.error("任务序号必须大于0")
        } else {
            val tasks = TaskScheduler.getAllTasks()
            if (index > tasks.size) {
                ToolResult.error("任务序号超出范围，当前共有 ${tasks.size} 个任务")
            } else {
                val task = tasks[index - 1]
                if (TaskScheduler.deleteTask(task.id)) {
                    ToolResult.success("已取消定时任务「${task.name}」")
                } else {
                    ToolResult.error("取消任务失败")
                }
            }
        }
    }

    override fun getDescriptionEN(): String =
        "Cancel a scheduled task by its index (get index from list_scheduled_tasks)."

    override fun getDescriptionCN(): String =
        "取消定时任务。需要先调用 list_scheduled_tasks 获取任务序号，index 从1开始。"
}
