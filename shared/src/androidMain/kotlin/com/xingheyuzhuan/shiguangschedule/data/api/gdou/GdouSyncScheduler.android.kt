package com.xingheyuzhuan.shiguangschedule.data.api.gdou

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import org.koin.java.KoinJavaComponent.getKoin
import java.util.concurrent.TimeUnit

actual object GdouSyncScheduler {
    const val PERIODIC_WORK_NAME = "GdouScheduleSync_Periodic"
    const val ONE_SHOT_WORK_NAME = "GdouScheduleSync_OneShot"

    @Volatile
    var workerClass: Class<out ListenableWorker>? = null

    private val constraints: Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    actual fun setEnabled(enabled: Boolean) {
        val context = appContext() ?: return
        val clazz = workerClass ?: return
        val wm = WorkManager.getInstance(context)
        if (enabled) {
            val request = PeriodicWorkRequest.Builder(clazz, 1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()
            wm.enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        } else {
            wm.cancelUniqueWork(PERIODIC_WORK_NAME)
        }
    }

    actual fun requestImmediate() {
        val context = appContext() ?: return
        val clazz = workerClass ?: return
        val request = OneTimeWorkRequest.Builder(clazz)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_SHOT_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun appContext(): Context? = runCatching { getKoin().get<Context>() }.getOrNull()
}
