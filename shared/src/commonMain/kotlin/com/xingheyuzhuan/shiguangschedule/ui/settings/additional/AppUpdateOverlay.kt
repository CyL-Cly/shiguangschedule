package com.xingheyuzhuan.shiguangschedule.ui.settings.additional

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xingheyuzhuan.shiguangschedule.tool.update.InstallFailure
import com.xingheyuzhuan.shiguangschedule.tool.update.UpdateKitState
import com.xingheyuzhuan.shiguangschedule.tool.update.UpdateManifest
import com.xingheyuzhuan.shiguangschedule.tool.update.rememberRequestUnknownSources
import com.xingheyuzhuan.shiguangschedule.ui.components.ToastManager
import org.jetbrains.compose.resources.stringResource
import shiguangschedule.shared.generated.resources.Res
import shiguangschedule.shared.generated.resources.action_cancel
import shiguangschedule.shared.generated.resources.action_close
import shiguangschedule.shared.generated.resources.action_grant_unknown_sources
import shiguangschedule.shared.generated.resources.action_later
import shiguangschedule.shared.generated.resources.action_retry
import shiguangschedule.shared.generated.resources.btn_update_now
import shiguangschedule.shared.generated.resources.dialog_current_version_latest
import shiguangschedule.shared.generated.resources.dialog_download_failed
import shiguangschedule.shared.generated.resources.dialog_download_failed_msg
import shiguangschedule.shared.generated.resources.dialog_downloading_update
import shiguangschedule.shared.generated.resources.dialog_need_unknown_sources
import shiguangschedule.shared.generated.resources.dialog_need_unknown_sources_msg
import shiguangschedule.shared.generated.resources.dialog_new_version_found
import shiguangschedule.shared.generated.resources.toast_install_confirmation_required
import shiguangschedule.shared.generated.resources.toast_install_failed
import shiguangschedule.shared.generated.resources.toast_install_unsupported
import shiguangschedule.shared.generated.resources.update_auto_install_notice
import shiguangschedule.shared.generated.resources.update_downloaded_bytes
import shiguangschedule.shared.generated.resources.update_downloaded_bytes_of
import shiguangschedule.shared.generated.resources.update_fallback_notes
import shiguangschedule.shared.generated.resources.update_retrying
import shiguangschedule.shared.generated.resources.update_server_unreachable

@Composable
fun AppUpdateOverlay(
    state: UpdateKitState,
    onStartDownload: (UpdateManifest) -> Unit,
    onRetryDownload: () -> Unit,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
    onInstallRequest: () -> Boolean,
) {
    // 检查结果和安装失败用 Toast 提示后复位
    val toastLatest = stringResource(Res.string.dialog_current_version_latest)
    val toastError = stringResource(Res.string.update_server_unreachable)
    val toastInstallFailed = stringResource(Res.string.toast_install_failed)
    val toastConfirmationRequired = stringResource(Res.string.toast_install_confirmation_required)
    val toastInstallUnsupported = stringResource(Res.string.toast_install_unsupported)
    LaunchedEffect(state) {
        when (state) {
            UpdateKitState.UpToDate -> {
                ToastManager.show(toastLatest)
                onDismiss()
            }
            UpdateKitState.Error -> {
                ToastManager.show(toastError)
                onDismiss()
            }
            is UpdateKitState.InstallFailed -> {
                val message = when (state.reason) {
                    InstallFailure.ConfirmationRequired -> toastConfirmationRequired
                    InstallFailure.Unsupported -> toastInstallUnsupported
                    InstallFailure.Other -> toastInstallFailed
                }
                ToastManager.show(message)
                onReset()
            }
            else -> Unit
        }
    }

    // 授权成功后从系统设置返回，使用同一个已校验 APK，无需重新下载或确认。
    val requestUnknownSources = rememberRequestUnknownSources {
        onInstallRequest()
    }

    when (val update = state) {
        UpdateKitState.Checking, UpdateKitState.Idle, UpdateKitState.UpToDate,
        UpdateKitState.Error, UpdateKitState.Installing, is UpdateKitState.InstallFailed -> Unit

        is UpdateKitState.Available -> AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(stringResource(Res.string.dialog_new_version_found, update.manifest.versionName))
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 350.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = update.manifest.releaseNotes.ifBlank {
                            stringResource(Res.string.update_fallback_notes)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(Res.string.update_auto_install_notice),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // 先关闭更新弹窗，避免与下载进度弹窗叠加
                    onDismiss()
                    onStartDownload(update.manifest)
                }) {
                    Text(stringResource(Res.string.btn_update_now))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.action_later))
                }
            },
        )

        is UpdateKitState.Downloading -> {
            val progress = remember(update.doneBytes, update.totalBytes) {
                val total = update.totalBytes
                if (total != null && total > 0) update.doneBytes / total.toFloat() else null
            }
            AlertDialog(
                onDismissRequest = { },  // 下载中不可关闭
                title = { Text(stringResource(Res.string.dialog_downloading_update)) },
                text = {
                    Column {
                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(8.dp))
                        val doneText = formatBytes(update.doneBytes)
                        Text(
                            text = update.totalBytes?.let {
                                stringResource(
                                    Res.string.update_downloaded_bytes_of,
                                    doneText,
                                    formatBytes(it),
                                )
                            } ?: stringResource(Res.string.update_downloaded_bytes, doneText)
                        )
                        if (update.attempt > 1) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(
                                    Res.string.update_retrying,
                                    update.attempt,
                                    update.maxAttempts,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                confirmButton = {},
            )
        }

        UpdateKitState.DownloadFailed -> AlertDialog(
            onDismissRequest = onReset,
            title = { Text(stringResource(Res.string.dialog_download_failed)) },
            text = { Text(stringResource(Res.string.dialog_download_failed_msg)) },
            confirmButton = {
                TextButton(onClick = onRetryDownload) {
                    Text(stringResource(Res.string.action_retry))
                }
            },
            dismissButton = {
                TextButton(onClick = onReset) {
                    Text(stringResource(Res.string.action_close))
                }
            },
        )

        is UpdateKitState.AwaitingInstallPermission -> AlertDialog(
            onDismissRequest = onReset,
            title = { Text(stringResource(Res.string.dialog_need_unknown_sources)) },
            text = { Text(stringResource(Res.string.dialog_need_unknown_sources_msg)) },
            confirmButton = {
                TextButton(onClick = requestUnknownSources) {
                    Text(stringResource(Res.string.action_grant_unknown_sources))
                }
            },
            dismissButton = {
                TextButton(onClick = onReset) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes >= 1024 * 1024) {
        val tenths = (bytes * 10 / (1024 * 1024)).toInt()
        return "${tenths / 10}.${tenths % 10} MB"
    }
    if (bytes >= 1024) return "${bytes / 1024} KB"
    return "$bytes B"
}
