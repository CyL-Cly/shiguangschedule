package com.xingheyuzhuan.shiguangschedule.service

import com.xingheyuzhuan.shiguangschedule.data.api.gdou.GdouSyncNotifier
import com.xingheyuzhuan.shiguangschedule.data.api.gdou.GdouSyncScheduler

object GdouWorkScheduler {
    fun install() {
        GdouSyncScheduler.workerClass = GdouScheduleSyncWorker::class.java
        GdouSyncNotifier.handler = { title, message -> GdouSyncNotification.show(title, message) }
    }

    fun setEnabled(enabled: Boolean) {
        install()
        GdouSyncScheduler.setEnabled(enabled)
    }

    fun requestImmediate() {
        install()
        GdouSyncScheduler.requestImmediate()
    }
}
