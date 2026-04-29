package com.apk.claw.android.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.apk.claw.android.utils.XLog

/**
 * 定时任务广播接收器
 *
 * 接收 AlarmManager 触发的定时任务广播，调用 TaskScheduler.executeTask() 执行。
 * 传递 EXTRA_TASK_ID 和 EXTRA_TIME_INDEX 确保正确执行指定时间槽。
 */
class ScheduledTaskReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScheduledTaskReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(TaskScheduler.EXTRA_TASK_ID) ?: run {
            XLog.w(TAG, "收到定时任务广播但没有 taskId")
            return
        }

        val timeIndex = intent.getIntExtra(TaskScheduler.EXTRA_TIME_INDEX, 0)

        XLog.i(TAG, "⏰ 收到定时任务广播: taskId=$taskId timeIndex=$timeIndex")

        // 执行定时任务（带时间槽索引）
        TaskScheduler.executeTask(taskId, timeIndex)
    }
}
