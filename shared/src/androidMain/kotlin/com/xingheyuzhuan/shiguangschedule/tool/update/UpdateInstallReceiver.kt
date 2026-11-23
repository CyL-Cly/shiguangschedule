package com.xingheyuzhuan.shiguangschedule.tool.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS || !intent.hasExtra(PackageInstaller.EXTRA_STATUS)) return
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (sessionId < 0) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val failure = when (status) {
            PackageInstaller.STATUS_SUCCESS -> null
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Keep this path confirmation-free even if the process has been recreated.
                ApkInstaller.abandonSession(context, sessionId)
                InstallFailure.ConfirmationRequired
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w("ApkInstaller", "Update failed ($status): $message")
                InstallFailure.Other
            }
        }
        ApkInstaller.finishSession(sessionId, failure)
    }

    companion object {
        internal const val ACTION_INSTALL_STATUS = "com.xingheyuzhuan.shiguangschedule.action.UPDATE_INSTALL_STATUS"
    }
}
