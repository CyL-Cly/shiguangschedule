package com.xingheyuzhuan.shiguangschedule.ui.settings.notification

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
actual fun PlatformGeneralSettingsSection(
    uiState: NotificationSettingsUiState,
    viewModel: NotificationSettingsViewModel
) {
    Text("Course reminders and auto DND are not supported on iOS.")
}

@Composable
actual fun PlatformNotificationDialogDispatcher(
    uiState: NotificationSettingsUiState,
    viewModel: NotificationSettingsViewModel
) {
    // iOS 暂不提供通知/自动模式弹窗。
}
