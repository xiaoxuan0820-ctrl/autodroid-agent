package com.apk.claw.android.scheduler

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.view.WindowManager
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.utils.XLog

/**
 * 屏幕管理器
 *
 * 定时任务触发时自动亮屏+解锁，任务完成后息屏省电。
 * 用于旧手机无人值守场景（打卡、刷视频等）。
 */
object ScreenManager {

    private const val TAG = "ScreenManager"
    private const val WAKE_LOCK_TAG = "CiCi:ScreenLock"

    /** 当前持有的唤醒锁 */
    private var wakeLock: PowerManager.WakeLock? = null

    /** 持有唤醒锁的任务ID */
    private var lockOwnerTaskId: String? = null

    // ==================== 亮屏 ====================

    /**
     * 唤醒屏幕并尝试解锁
     * @param taskId 当前任务ID（用于追踪谁持锁）
     * @return true=亮屏成功, false=失败
     */
    fun wakeUp(taskId: String): Boolean {
        val context = ClawApplication.instance
        XLog.i(TAG, "🟢 唤醒屏幕 (task=$taskId)")

        try {
            // 1. 获取 PowerManager 唤醒锁 + 点亮屏幕
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            releaseWakeLockInternal() // 释放旧锁

            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "$WAKE_LOCK_TAG:$taskId"
            )
            wl.acquire(60_000L) // 最多保持60秒亮屏
            wakeLock = wl
            lockOwnerTaskId = taskId

            // 2. 尝试解锁（仅滑动/无锁屏模式）
            tryDismissKeyguard(context)

            // 3. 等待屏幕稳定
            Thread.sleep(500)

            return true
        } catch (e: Exception) {
            XLog.e(TAG, "亮屏失败", e)
            return false
        }
    }

    /**
     * 任务执行期间保持亮屏
     */
    fun keepOn(taskId: String) {
        // 已经持锁的延长持有时间
        if (lockOwnerTaskId == taskId && wakeLock?.isHeld == true) {
            XLog.d(TAG, "继续持锁: $taskId")
            return
        }
        // 不是自己持锁但需要亮屏，重新获取
        wakeUp(taskId)
    }

    // ==================== 息屏 ====================

    /**
     * 释放屏幕，允许息屏
     * @param taskId 当前任务ID（只有持有者能释放）
     */
    fun release(taskId: String) {
        if (lockOwnerTaskId != taskId) {
            XLog.w(TAG, "不是锁持有者，跳过释放: $taskId (owner=$lockOwnerTaskId)")
            return
        }
        releaseWakeLockInternal()
        XLog.i(TAG, "🔴 释放屏幕 (task=$taskId)")
    }

    private fun releaseWakeLockInternal() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            XLog.w(TAG, "释放唤醒锁异常", e)
        }
        wakeLock = null
        lockOwnerTaskId = null
    }

    // ==================== 状态查询 ====================

    /** 检查屏幕是否亮着 */
    fun isScreenOn(): Boolean {
        return try {
            val pm = ClawApplication.instance.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isInteractive
        } catch (_: Exception) { false }
    }

    /** 检查设备是否设置了锁屏（PIN/密码/图案） */
    fun isDeviceLocked(): Boolean {
        return try {
            val km = ClawApplication.instance.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                km.isDeviceSecure
            } else {
                km.isKeyguardSecure
            }
        } catch (_: Exception) { false }
    }

    /** 获取锁屏类型描述 */
    fun getLockTypeDescription(): String {
        return try {
            val km = ClawApplication.instance.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val isSecure = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                km.isDeviceSecure
            } else {
                km.isKeyguardSecure
            }
            when {
                !isSecure -> "无锁屏"
                else -> "密码/图案/PIN"
            }
        } catch (_: Exception) { "未知" }
    }

    // ==================== 内部 ====================

    /**
     * 尝试解锁屏幕
     * 仅对滑动/无锁屏模式有效，PIN/密码无法自动绕过
     */
    private fun tryDismissKeyguard(context: Context) {
        try {
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                // Android 8.1+ 使用 dismissKeyguard (需要权限)
                km.requestDismissKeyguard(
                    ClawApplication.instance.getActivity() ?: return,
                    object : KeyguardManager.KeyguardDismissCallback {
                        override fun onDismissSucceeded() {
                            XLog.i(TAG, "🔓 锁屏已解除")
                        }
                        override fun onDismissError() {
                            XLog.w(TAG, "锁屏解除失败")
                        }
                    }
                )
            } else {
                // 旧版本使用已废弃的方法
                @Suppress("DEPRECATION")
                km.disableKeyguard()
            }
        } catch (e: Exception) {
            XLog.w(TAG, "解锁失败（可能设置了密码锁）: ${e.message}")
        }
    }
}
