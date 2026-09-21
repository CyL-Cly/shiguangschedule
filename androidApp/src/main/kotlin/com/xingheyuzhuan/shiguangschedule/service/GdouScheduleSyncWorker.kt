package com.xingheyuzhuan.shiguangschedule.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.xingheyuzhuan.shiguangschedule.data.api.gdou.GdouSyncResult
import com.xingheyuzhuan.shiguangschedule.data.api.gdou.GdouSyncUseCase
import com.xingheyuzhuan.shiguangschedule.data.repository.GdouJwxtRepository
import org.koin.android.annotation.KoinWorker

@KoinWorker
class GdouScheduleSyncWorker(
    appContext: Context,
    params: WorkerParameters,
    private val useCase: GdouSyncUseCase,
    private val repo: GdouJwxtRepository
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val snapshot = repo.snapshot()
        if (!snapshot.autoSyncEnabled) {
            GdouWorkScheduler.setEnabled(false)
            return Result.success()
        }
        if (!snapshot.hasPassword) {
            GdouSyncNotification.show("广海课表同步", "未保存学号密码，已停止自动更新")
            repo.setAutoSyncEnabled(false)
            GdouWorkScheduler.setEnabled(false)
            return Result.success()
        }
        return when (val result = useCase.syncNow(allowInteractive = false, source = "auto")) {
            is GdouSyncResult.Success -> Result.success()
            is GdouSyncResult.NeedCaptcha -> {
                GdouSyncNotification.show("广海课表需要验证码", "打开应用完成图形验证码后继续自动更新")
                Result.success()
            }
            is GdouSyncResult.NeedMfa -> {
                GdouSyncNotification.show("广海课表需要二次认证", "打开应用发送并填写企业微信/短信验证码")
                Result.success()
            }
            is GdouSyncResult.Failed -> {
                if (result.retryable) {
                    Result.retry()
                } else {
                    GdouSyncNotification.show("广海课表同步失败", result.message)
                    if (result.message.contains("密码")) {
                        repo.setAutoSyncEnabled(false)
                        GdouWorkScheduler.setEnabled(false)
                    }
                    Result.success()
                }
            }
        }
    }
}
