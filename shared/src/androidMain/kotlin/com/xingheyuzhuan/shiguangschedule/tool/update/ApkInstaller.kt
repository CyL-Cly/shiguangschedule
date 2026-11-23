package com.xingheyuzhuan.shiguangschedule.tool.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object ApkInstaller {
    private const val TAG = "ApkInstaller"
    private const val STATUS_TIMEOUT_MS = 120_000L
    private val results = ConcurrentHashMap<Int, CompletableDeferred<InstallFailure?>>()

    fun canRequestInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** 跳转到系统「安装未知应用」设置。 */
    fun intentToUnknownAppSources(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    )

    /** 返回 null 表示安装成功。系统要求确认时由接收器取消会话。 */
    @RequiresApi(Build.VERSION_CODES.S)
    internal suspend fun install(
        context: Context,
        file: File,
        expectedVersionCode: Long,
    ): InstallFailure? = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.absolutePath, 0)
        val installed = pm.getPackageInfo(context.packageName, 0)
        require(
            archive != null && archive.packageName == context.packageName &&
                PackageInfoCompat.getLongVersionCode(archive) == expectedVersionCode &&
                expectedVersionCode > PackageInfoCompat.getLongVersionCode(installed),
        ) { "APK must be a newer version of this app matching the update manifest" }

        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val result = CompletableDeferred<InstallFailure?>()
        val sessionId = installer.createSession(params)
        var callback: PendingIntent? = null
        results[sessionId] = result
        try {
            val statusIntent = Intent(context, UpdateInstallReceiver::class.java)
                .setAction(UpdateInstallReceiver.ACTION_INSTALL_STATUS)
            // PackageInstaller supplies the session ID and status as fill-in extras.
            callback = PendingIntent.getBroadcast(context, sessionId, statusIntent, PendingIntent.FLAG_MUTABLE)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                session.commit(callback.intentSender)
            }
            withTimeout(STATUS_TIMEOUT_MS) { result.await() }
        } catch (_: TimeoutCancellationException) {
            Log.w(TAG, "No result for update session $sessionId")
            InstallFailure.Other
        } finally {
            if (!result.isCompleted) abandonSession(context, sessionId)
            results.remove(sessionId)
            callback?.cancel()
        }
    }

    internal fun finishSession(sessionId: Int, failure: InstallFailure?) {
        results[sessionId]?.complete(failure)
    }

    internal fun abandonSession(context: Context, sessionId: Int) {
        runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
            .onFailure { Log.w(TAG, "Could not abandon update session $sessionId", it) }
    }
}
