package com.xingheyuzhuan.shiguangschedule.ui.settings.gdou

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xingheyuzhuan.shiguangschedule.data.api.gdou.GDOU_XQM_FIRST
import com.xingheyuzhuan.shiguangschedule.data.api.gdou.GDOU_XQM_SECOND
import com.xingheyuzhuan.shiguangschedule.data.api.gdou.GdouMfaChannel
import com.xingheyuzhuan.shiguangschedule.ui.components.CourseTablePickerDialog
import coil3.compose.AsyncImage
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import org.koin.compose.viewmodel.koinViewModel
import shiguangschedule.shared.generated.resources.Res
import shiguangschedule.shared.generated.resources.a11y_back
import shiguangschedule.shared.generated.resources.arrow_back_24px
import shiguangschedule.shared.generated.resources.gdou_action_login_import
import shiguangschedule.shared.generated.resources.gdou_action_logout
import shiguangschedule.shared.generated.resources.gdou_action_send_mfa
import shiguangschedule.shared.generated.resources.gdou_action_submit_mfa
import shiguangschedule.shared.generated.resources.gdou_auto_sync
import shiguangschedule.shared.generated.resources.gdou_auto_sync_desc
import shiguangschedule.shared.generated.resources.gdou_label_captcha
import shiguangschedule.shared.generated.resources.gdou_label_mfa
import shiguangschedule.shared.generated.resources.gdou_label_password
import shiguangschedule.shared.generated.resources.gdou_label_password_saved
import shiguangschedule.shared.generated.resources.gdou_label_table
import shiguangschedule.shared.generated.resources.gdou_label_term
import shiguangschedule.shared.generated.resources.gdou_label_username
import shiguangschedule.shared.generated.resources.gdou_label_xnm
import shiguangschedule.shared.generated.resources.gdou_mfa_sms
import shiguangschedule.shared.generated.resources.gdou_mfa_wecom
import shiguangschedule.shared.generated.resources.gdou_pick_table
import shiguangschedule.shared.generated.resources.gdou_term_first
import shiguangschedule.shared.generated.resources.gdou_term_second
import shiguangschedule.shared.generated.resources.gdou_title
import shiguangschedule.shared.generated.resources.gdou_never_synced
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GdouYangjiangScreen(
    onBack: () -> Unit,
    viewModel: GdouYangjiangViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showTablePicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(Res.string.gdou_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                vectorResource(Res.drawable.arrow_back_24px),
                                contentDescription = stringResource(Res.string.a11y_back)
                            )
                        }
                    }
                )
                if (state.isBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = state.username,
                onValueChange = viewModel::onUsernameChange,
                label = { Text(stringResource(Res.string.gdou_label_username)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isBusy
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = {
                    val label = if (state.passwordSaved) Res.string.gdou_label_password_saved
                    else Res.string.gdou_label_password
                    Text(stringResource(label))
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isBusy
            )

            OutlinedTextField(
                value = state.xnm,
                onValueChange = viewModel::onXnmChange,
                label = { Text(stringResource(Res.string.gdou_label_xnm)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isBusy
            )
            Text(stringResource(Res.string.gdou_label_term), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.xqm == GDOU_XQM_FIRST,
                    onClick = { viewModel.onXqmChange(GDOU_XQM_FIRST) },
                    label = { Text(stringResource(Res.string.gdou_term_first)) }
                )
                FilterChip(
                    selected = state.xqm == GDOU_XQM_SECOND,
                    onClick = { viewModel.onXqmChange(GDOU_XQM_SECOND) },
                    label = { Text(stringResource(Res.string.gdou_term_second)) }
                )
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(Res.string.gdou_label_table), style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = state.tableName.ifBlank { state.tableId },
                        style = MaterialTheme.typography.bodyLarge
                    )
                    TextButton(onClick = { showTablePicker = true }, enabled = !state.isBusy) {
                        Text(stringResource(Res.string.gdou_pick_table))
                    }
                }
            }

            state.captchaImage?.let { bytes ->
                AsyncImage(
                    model = bytes,
                    contentDescription = stringResource(Res.string.gdou_label_captcha),
                    modifier = Modifier.fillMaxWidth().height(80.dp)
                )
                OutlinedTextField(
                    value = state.captchaInput,
                    onValueChange = viewModel::onCaptchaChange,
                    label = { Text(stringResource(Res.string.gdou_label_captcha)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (state.needsMfa) {
                Text(stringResource(Res.string.gdou_label_mfa), style = MaterialTheme.typography.labelLarge)
                state.mfaHint?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.mfaChannel == GdouMfaChannel.WECOM,
                        onClick = { viewModel.onChannelChange(GdouMfaChannel.WECOM) },
                        label = { Text(stringResource(Res.string.gdou_mfa_wecom)) }
                    )
                    FilterChip(
                        selected = state.mfaChannel == GdouMfaChannel.SMS,
                        onClick = { viewModel.onChannelChange(GdouMfaChannel.SMS) },
                        label = { Text(stringResource(Res.string.gdou_mfa_sms)) }
                    )
                }
                OutlinedTextField(
                    value = state.mfaCode,
                    onValueChange = viewModel::onMfaCodeChange,
                    label = { Text(stringResource(Res.string.gdou_label_mfa)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::sendMfa, enabled = !state.isBusy) {
                        Text(stringResource(Res.string.gdou_action_send_mfa))
                    }
                    Button(onClick = viewModel::submitMfaAndSync, enabled = !state.isBusy) {
                        Text(stringResource(Res.string.gdou_action_submit_mfa))
                    }
                }
            } else {
                Button(
                    onClick = viewModel::loginAndSync,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(Res.string.gdou_action_login_import))
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(Res.string.gdou_auto_sync), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(Res.string.gdou_auto_sync_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.autoSyncEnabled,
                        onCheckedChange = viewModel::onAutoSyncChange,
                        enabled = !state.isBusy
                    )
                }
            }

            val lastSync = if (state.lastSyncAtMillis > 0L) {
                val dt = Instant.fromEpochMilliseconds(state.lastSyncAtMillis)
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                "${dt.date} ${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}  ${state.lastSyncMessage}"
            } else {
                stringResource(Res.string.gdou_never_synced)
            }
            Text(lastSync, style = MaterialTheme.typography.bodyMedium)

            if (state.hasSession || state.passwordSaved) {
                TextButton(onClick = viewModel::logout, enabled = !state.isBusy) {
                    Text(stringResource(Res.string.gdou_action_logout))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showTablePicker) {
        CourseTablePickerDialog(
            title = stringResource(Res.string.gdou_pick_table),
            onDismissRequest = { showTablePicker = false },
            onTableSelected = {
                viewModel.onTableSelected(it)
                showTablePicker = false
            }
        )
    }
}
