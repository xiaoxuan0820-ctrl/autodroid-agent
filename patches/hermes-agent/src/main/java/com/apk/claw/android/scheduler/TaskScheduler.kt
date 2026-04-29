package com.apk.claw.android.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.channel.Channel
import com.apk.claw.android.channel.ChannelManager
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog
import com.apk.claw.android.appViewModel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/**
 * 定时任务调度器（强化版）
 *
 * 支持：
 * - 多时段：一个任务可在多个时间点执行
 * - 工作日模式：仅工作日执行
 * - 执行结果记录
 * - 失败重试（最多3次，间隔5分钟）
 * - 自动亮屏（通过 ScreenManager）
 */
object TaskScheduler {

    private const val TAG = "TaskScheduler"
    private val GSON = Gson()
    private val TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())

    const val ACTION_EXECUTE_SCHEDULED_TASK = "com.apk.claw.android.SCHEDULED_TASK"
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_TIME_INDEX = "time_index"

    // ==================== 数据模型 ====================

    /** 时间槽 */
    data class TimeSlot(
        val hour: Int,      // 0-23
        val minute: Int     // 0-59
    ) {
        fun toTimeString(): String = String.format("%02d:%02d", hour, minute)
    }

    /** 执行结果 */
    data class ExecutionResult(
        val success: Boolean,
        val summary: String,
        val executedAt: Long = System.currentTimeMillis()
    )

    /** 定时任务 */
    data class ScheduledTask(
        val id: String,
        val name: String,                         // 任务名，如"早上打卡"
        val prompt: String,                       // 给Agent的指令
        val timeSlots: List<TimeSlot>,            // ★ 多时段 [9:00, 18:00]
        val repeat: Boolean = true,               // 是否每天重复
        val workdaysOnly: Boolean = false,        // ★ 仅工作日
        val channel: Channel? = null,
        val targetUserId: String? = null,
        val enabled: Boolean = true,
        val lastResult: ExecutionResult? = null,  // ★ 上次执行结果
        val createdAt: Long = System.currentTimeMillis()
    ) {
        /** 获取格式化的时间列表 */
        fun getTimeString(): String =
            timeSlots.joinToString(", ") { it.toTimeString() }

        /** 最近的一个未过期时间 */
        fun getNextExecutionTime(timeIndex: Int = 0): Long {
            if (timeIndex >= timeSlots.size) return 0L
            val slot = timeSlots[timeIndex]
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, slot.hour)
                set(Calendar.MINUTE, slot.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (calendar.timeInMillis <= System.currentTimeMillis()) {
                calendar.add(Calendar.DAY_OF_MONTH, 1)
            }
            return calendar.timeInMillis
        }

        /** 是否为工作日 */
        fun isWorkday(): Boolean {
            if (!workdaysOnly) return true
            val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
            return today in Calendar.MONDAY..Calendar.FRIDAY
        }
    }

    private const val SCHEDULED_TASKS_KEY = "cici_scheduled_tasks"

    // ==================== 任务管理 ====================

    fun getAllTasks(): List<ScheduledTask> {
        val json = KVUtils.getString(SCHEDULED_TASKS_KEY, "[]")
        return if (json.isNotEmpty()) {
            try { GSON.fromJson(json, object : TypeToken<List<ScheduledTask>>() {}.type) }
            catch (_: Exception) { emptyList() }
        } else {
            emptyList()
        }
    }

    private fun saveTasks(tasks: List<ScheduledTask>) {
        KVUtils.putString(SCHEDULED_TASKS_KEY, GSON.toJson(tasks))
    }

    /**
     * 添加定时任务（新版：多时段）
     */
    fun addTask(
        name: String,
        prompt: String,
        timeSlots: List<TimeSlot>,
        repeat: Boolean = true,
        workdaysOnly: Boolean = false,
        channel: Channel? = null,
        targetUserId: String? = null
    ): ScheduledTask {
        val task = ScheduledTask(
            id = UUID.randomUUID().toString(),
            name = name,
            prompt = prompt,
            timeSlots = timeSlots,
            repeat = repeat,
            workdaysOnly = workdaysOnly,
            channel = channel,
            targetUserId = targetUserId
        )

        val tasks = getAllTasks().toMutableList()
        tasks.add(task)
        saveTasks(tasks)

        // 为每个时间槽注册闹钟
        task.timeSlots.forEachIndexed { index, _ ->
            scheduleAlarm(task, index)
        }

        XLog.i(TAG, "已添加定时任务: ${task.name} @ ${task.getTimeString()}" +
                if (workdaysOnly) " (工作日)" else " (每天)")
        return task
    }

    /**
     * 添加定时任务（兼容旧版：单个时间）
     */
    fun addTask(
        prompt: String,
        hour: Int,
        minute: Int,
        repeat: Boolean,
        channel: Channel? = null,
        targetUserId: String? = null
    ): ScheduledTask {
        return addTask(
            name = prompt.take(20),
            prompt = prompt,
            timeSlots = listOf(TimeSlot(hour, minute)),
            repeat = repeat,
            workdaysOnly = false,
            channel = channel,
            targetUserId = targetUserId
        )
    }

    fun deleteTask(taskId: String): Boolean {
        val tasks = getAllTasks().toMutableList()
        val task = tasks.find { it.id == taskId }

        if (task != null) {
            task.timeSlots.indices.forEach { cancelAlarm(task, it) }
            tasks.removeAll { it.id == taskId }
            saveTasks(tasks)
            XLog.i(TAG, "已删除定时任务: ${task.name}")
            return true
        }
        return false
    }

    fun toggleTask(taskId: String, enabled: Boolean): Boolean {
        val tasks = getAllTasks().toMutableList()
        val index = tasks.indexOfFirst { it.id == taskId }

        if (index >= 0) {
            val task = tasks[index].copy(enabled = enabled)
            tasks[index] = task
            saveTasks(tasks)

            if (enabled) {
                task.timeSlots.indices.forEach { scheduleAlarm(task, it) }
            } else {
                task.timeSlots.indices.forEach { cancelAlarm(task, it) }
            }
            return true
        }
        return false
    }

    fun getTaskById(taskId: String): ScheduledTask? {
        return getAllTasks().find { it.id == taskId }
    }

    // ==================== 闹钟调度 ====================

    private fun scheduleAlarm(task: ScheduledTask, timeIndex: Int) {
        if (!task.enabled) return
        if (timeIndex >= task.timeSlots.size) return

        val context = ClawApplication.instance
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val slot = task.timeSlots[timeIndex]

        val intent = Intent(context, ScheduledTaskReceiver::class.java).apply {
            action = ACTION_EXECUTE_SCHEDULED_TASK
            putExtra(EXTRA_TASK_ID, task.id)
            putExtra(EXTRA_TIME_INDEX, timeIndex)
        }

        // 使用 taskId+timeIndex 作为唯一请求码，确保每个时间槽有独立闹钟
        val requestCode = (task.id.hashCode() * 31 + timeIndex).also {
            // 确保为正数
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 计算触发时间
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, slot.hour)
            set(Calendar.MINUTE, slot.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        }
        val triggerTime = calendar.timeInMillis

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent
                    )
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent
                )
            }

            val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(triggerTime)
            XLog.i(TAG, "⏰ 注册闹钟: ${task.name} [${slot.toTimeString()}] → $timeStr")
        } catch (e: Exception) {
            XLog.e(TAG, "注册闹钟失败", e)
        }
    }

    private fun cancelAlarm(task: ScheduledTask, timeIndex: Int) {
        val context = ClawApplication.instance
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, ScheduledTaskReceiver::class.java).apply {
            action = ACTION_EXECUTE_SCHEDULED_TASK
        }
        val requestCode = task.id.hashCode() * 31 + timeIndex
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.cancel(pendingIntent)
    }

    fun rescheduleAllTasks() {
        val tasks = getAllTasks().filter { it.enabled }
        tasks.forEach { task ->
            task.timeSlots.indices.forEach { scheduleAlarm(task, it) }
        }
        XLog.i(TAG, "已重新调度 ${tasks.size} 个定时任务")
    }

    // ==================== 任务执行 ====================

    /** 重试计数 */
    private val retryCounts = mutableMapOf<String, Int>()
    private const val MAX_RETRIES = 3
    private const val RETRY_INTERVAL_MS = 5 * 60 * 1000L // 5分钟

    /**
     * 执行定时任务（由 ScheduledTaskReceiver 调用）
     */
    fun executeTask(taskId: String, timeIndex: Int = 0) {
        val task = getTaskById(taskId)
        if (task == null) {
            XLog.w(TAG, "任务不存在: $taskId")
            return
        }

        // 检查工作日模式
        if (task.workdaysOnly && !task.isWorkday()) {
            XLog.i(TAG, "⏭️ 今天是周末，跳过任务: ${task.name}")
            rescheduleForNextWorkday(task, timeIndex)
            return
        }

        XLog.i(TAG, "⏰ 定时任务触发: ${task.name} [${task.timeSlots.getOrNull(timeIndex)?.toTimeString()}]")

        // 1. 唤醒屏幕
        val screenOk = ScreenManager.wakeUp(taskId)
        if (!screenOk) {
            handleTaskFailure(task, "亮屏失败")
            return
        }

        // 2. 获取渠道信息并执行
        val channel = task.channel
        val targetUserId = task.targetUserId

        if (channel != null && targetUserId != null) {
            ChannelManager.restoreRoutingContext(channel, targetUserId)
            ChannelManager.sendMessageToUser(channel, targetUserId, "⏰ 开始执行定时任务: ${task.name}")
        }

        // 3. 执行 Agent 任务
        try {
            ScreenManager.keepOn(taskId)
            appViewModel.executeScheduledTask(task.prompt, channel, targetUserId)
            XLog.i(TAG, "✅ Agent 已开始执行: ${task.name}")
        } catch (e: Exception) {
            XLog.e(TAG, "Agent 启动失败", e)
            handleTaskFailure(task, e.message ?: "Agent 启动失败")
            ScreenManager.release(taskId)
        }

        // 4. 如果是重复任务且还有下一个时间槽，注册明天的闹钟
        if (task.repeat) {
            task.timeSlots.forEachIndexed { index, _ ->
                scheduleAlarm(task, index)
            }
        } else {
            // 一次性任务，执行后删除
            deleteTask(taskId)
        }
    }

    /** 记录任务结果 */
    fun recordResult(taskId: String, success: Boolean, summary: String) {
        val tasks = getAllTasks().toMutableList()
        val index = tasks.indexOfFirst { it.id == taskId }
        if (index < 0) return

        val result = ExecutionResult(success = success, summary = summary)
        tasks[index] = tasks[index].copy(lastResult = result)
        saveTasks(tasks)

        // 记录到日志
        TaskExecutionLogger.log(taskId, success, summary)
        ScreenManager.release(taskId)

        // 通知用户结果
        val task = tasks[index]
        if (task.channel != null && task.targetUserId != null) {
            val icon = if (success) "✅" else "❌"
            ChannelManager.sendMessageToUser(
                task.channel, task.targetUserId,
                "$icon 定时任务「${task.name}」${if (success) "完成" else "失败"}：$summary"
            )
        }
    }

    // ==================== 失败处理 ====================

    private fun handleTaskFailure(task: ScheduledTask, reason: String) {
        val key = task.id
        val retryCount = retryCounts.getOrDefault(key, 0)

        if (retryCount < MAX_RETRIES) {
            retryCounts[key] = retryCount + 1
            // 5分钟后重试
            scheduleRetry(task, key)
            XLog.w(TAG, "⏳ 任务失败 (${retryCount + 1}/$MAX_RETRIES)，5分钟后重试: $reason")

            if (task.channel != null && task.targetUserId != null) {
                ChannelManager.sendMessageToUser(
                    task.channel, task.targetUserId,
                    "⏳ 定时任务「${task.name}」执行失败（${retryCount + 1}/$MAX_RETRIES），5分钟后重试：$reason"
                )
            }
        } else {
            // 重试耗尽，标记最终失败
            retryCounts.remove(key)
            recordResult(task.id, false, "重试$MAX_RETRIES次后仍然失败: $reason")
        }
    }

    private fun scheduleRetry(task: ScheduledTask, retryKey: String) {
        val context = ClawApplication.instance
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, ScheduledTaskReceiver::class.java).apply {
            action = ACTION_EXECUTE_SCHEDULED_TASK
            putExtra(EXTRA_TASK_ID, task.id)
            putExtra(EXTRA_TIME_INDEX, 0)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context, retryKey.hashCode() + 9999, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.set(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + RETRY_INTERVAL_MS,
            pendingIntent
        )
        XLog.i(TAG, "已安排重试: ${task.name} 在5分钟后")
    }

    /** 周末过后重新调度 */
    private fun rescheduleForNextWorkday(task: ScheduledTask, timeIndex: Int) {
        // 直接安排到下周一
        val calendar = Calendar.getInstance()
        var daysUntilMonday = (Calendar.MONDAY - calendar.get(Calendar.DAY_OF_WEEK))
        if (daysUntilMonday <= 0) daysUntilMonday += 7

        if (timeIndex < task.timeSlots.size) {
            val slot = task.timeSlots[timeIndex]
            calendar.add(Calendar.DAY_OF_MONTH, daysUntilMonday)
            calendar.set(Calendar.HOUR_OF_DAY, slot.hour)
            calendar.set(Calendar.MINUTE, slot.minute)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)

            val context = ClawApplication.instance
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ScheduledTaskReceiver::class.java).apply {
                action = ACTION_EXECUTE_SCHEDULED_TASK
                putExtra(EXTRA_TASK_ID, task.id)
                putExtra(EXTRA_TIME_INDEX, timeIndex)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, task.id.hashCode() * 31 + timeIndex, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent
            )
            XLog.i(TAG, "周末跳过，下周一重新调度: ${task.name}")
        }
    }

    // ==================== 工具方法 ====================

    fun parseTime(timeStr: String): Pair<Int, Int>? {
        return try {
            val parts = timeStr.split(":")
            if (parts.size == 2) {
                val hour = parts[0].toIntOrNull() ?: return null
                val minute = parts[1].toIntOrNull() ?: return null
                if (hour in 0..23 && minute in 0..59) Pair(hour, minute)
                else null
            } else null
        } catch (_: Exception) { null }
    }

    /** 解析多时段：支持 "9:00,18:00" 或 ["9:00", "18:00"] */
    fun parseTimeSlots(timeStr: String): List<TimeSlot> {
        return timeStr.split(",", "，", "、")
            .mapNotNull { parseTime(it.trim()) }
            .map { TimeSlot(it.first, it.second) }
    }

    fun formatTaskList(): String {
        val tasks = getAllTasks()
        if (tasks.isEmpty()) return "暂无定时任务"

        val sb = StringBuilder("【定时任务列表】\n")
        tasks.forEachIndexed { index, task ->
            val status = if (task.enabled) "✓" else "✗"
            val repeatStr = if (task.workdaysOnly) "工作日" else if (task.repeat) "每天" else "一次"
            val timeStr = task.getTimeString()
            val resultStr = task.lastResult?.let {
                if (it.success) " ✅上次成功" else " ❌上次失败"
            } ?: ""
            sb.append("${index + 1}. [$status] $timeStr ($repeatStr) - ${task.name}$resultStr\n")
        }
        return sb.toString()
    }
}
