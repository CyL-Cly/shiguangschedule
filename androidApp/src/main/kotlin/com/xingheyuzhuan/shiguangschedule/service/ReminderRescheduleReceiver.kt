package com.xingheyuzhuan.shiguangschedule.service

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 提醒闹钟的补排入口。
 *
 * [CourseNotificationWorker] 排的是 AlarmManager 精确闹钟，而闹钟在两种情况下会被清空却
 * 无人补排，导致课程提醒静默失效（Worker 内检测不到权限只是 return success，用户无感知）：
 *  1. 设备重启 —— 系统清空全部闹钟，而 Worker 是一次性任务，不会自己重跑；
 *  2. 用户在系统设置里补授「精确闹钟」权限 —— 授权前 Worker 已跳过排程，授权后没有重排。
 * 因此在这两个时机统一走 [ReminderScheduler.reschedule]。
 */
class ReminderRescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (!isSupportedAction(action)) return

        Log.d(TAG, "收到 $action，重新排队课程提醒")
        ReminderScheduler.reschedule(context)
    }

    private fun isSupportedAction(action: String): Boolean = when {
        action == Intent.ACTION_BOOT_COMPLETED -> true
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> true
        else -> false
    }

    private companion object {
        private const val TAG = "ReminderReschedule"
    }
}
