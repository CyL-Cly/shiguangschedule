package com.xingheyuzhuan.shiguangschedule.service

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * 课程提醒与上课自动模式的统一重排入口。
 *
 * 同步完成、设备重启、精确闹钟权限变更等场景都要把闹钟重新排一遍，收敛到一处，
 * 避免各处重复拼 WorkManager 的唯一任务名（重名会导致互相 REPLACE 覆盖）。
 */
object ReminderScheduler {

    private const val NOTIFICATION_WORK_NAME = "CourseNotificationWorker_Sync_Update"

    /** 重排课程提醒闹钟 + 自动模式闹钟。内部自行判断开关，关闭时只做清理。 */
    fun reschedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOTIFICATION_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<CourseNotificationWorker>().build()
        )
        DndSchedulerWorker.enqueueWork(context)
    }
}
