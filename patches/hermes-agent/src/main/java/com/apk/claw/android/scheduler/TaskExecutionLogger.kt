package com.apk.claw.android.scheduler

import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 定时任务执行日志
 *
 * 记录每次任务的执行情况，方便用户查看历史结果。
 */
object TaskExecutionLogger {

    private const val TAG = "TaskLogger"
    private val GSON = Gson()
    private const val STORAGE_KEY = "cici_task_execution_logs"
    private const val MAX_ENTRIES = 200 // 最多保留200条

    data class LogEntry(
        val taskId: String,
        val taskName: String,
        val success: Boolean,
        val summary: String,
        val executedAt: Long = System.currentTimeMillis()
    )

    private fun load(): MutableList<LogEntry> {
        val json = KVUtils.getString(STORAGE_KEY, "[]")
        return try {
            GSON.fromJson(json, object : TypeToken<MutableList<LogEntry>>() {}.type)
                ?: mutableListOf()
        } catch (_: Exception) { mutableListOf() }
    }

    private fun save(entries: MutableList<LogEntry>) {
        KVUtils.putString(STORAGE_KEY, GSON.toJson(entries))
    }

    fun log(taskId: String, success: Boolean, summary: String) {
        val task = TaskScheduler.getTaskById(taskId)
        val entry = LogEntry(
            taskId = taskId,
            taskName = task?.name ?: taskId.take(20),
            success = success,
            summary = summary
        )

        val entries = load()
        entries.add(0, entry) // 最新放前面

        // 限制数量
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(entries.lastIndex)
        }

        save(entries)
        XLog.d(TAG, "记录执行: ${entry.taskName} ${if (success) "✅" else "❌"}")
    }

    fun getRecent(limit: Int = 20): List<LogEntry> {
        return load().take(limit)
    }

    fun getTaskLogs(taskId: String, limit: Int = 10): List<LogEntry> {
        return load().filter { it.taskId == taskId }.take(limit)
    }

    fun clear() {
        save(mutableListOf())
        XLog.i(TAG, "已清空执行日志")
    }

    fun formatRecent(limit: Int = 10): String {
        val entries = getRecent(limit)
        if (entries.isEmpty()) return "暂无执行记录"

        val sb = StringBuilder("【最近执行记录】\n")
        val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        entries.forEach { entry ->
            val icon = if (entry.success) "✅" else "❌"
            val time = sdf.format(java.util.Date(entry.executedAt))
            sb.append("$icon [$time] ${entry.taskName}: ${entry.summary}\n")
        }
        return sb.toString()
    }
}
