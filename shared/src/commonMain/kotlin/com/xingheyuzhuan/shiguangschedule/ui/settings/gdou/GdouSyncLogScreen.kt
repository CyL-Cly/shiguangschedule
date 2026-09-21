package com.xingheyuzhuan.shiguangschedule.ui.settings.gdou

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xingheyuzhuan.shiguangschedule.data.api.gdou.GdouSyncLogEntry
import com.xingheyuzhuan.shiguangschedule.ui.components.ToastManager
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import org.koin.compose.viewmodel.koinViewModel
import shiguangschedule.shared.generated.resources.Res
import shiguangschedule.shared.generated.resources.action_cancel
import shiguangschedule.shared.generated.resources.action_confirm
import shiguangschedule.shared.generated.resources.a11y_back
import shiguangschedule.shared.generated.resources.arrow_back_24px
import shiguangschedule.shared.generated.resources.check_circle_24px
import shiguangschedule.shared.generated.resources.close_24px
import shiguangschedule.shared.generated.resources.delete_24px
import shiguangschedule.shared.generated.resources.dialog_title_clear_confirmation
import shiguangschedule.shared.generated.resources.gdou_sync_log_clear
import shiguangschedule.shared.generated.resources.gdou_sync_log_clear_confirm
import shiguangschedule.shared.generated.resources.gdou_sync_log_copied
import shiguangschedule.shared.generated.resources.gdou_sync_log_copy
import shiguangschedule.shared.generated.resources.gdou_sync_log_empty
import shiguangschedule.shared.generated.resources.gdou_sync_source_auto
import shiguangschedule.shared.generated.resources.gdou_sync_source_manual
import shiguangschedule.shared.generated.resources.title_gdou_sync_log
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GdouSyncLogScreen(
    onBack: () -> Unit,
    viewModel: GdouSyncLogViewModel = koinViewModel()
) {
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.title_gdou_sync_log)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            vectorResource(Res.drawable.arrow_back_24px),
                            contentDescription = stringResource(Res.string.a11y_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(
                            vectorResource(Res.drawable.delete_24px),
                            contentDescription = stringResource(Res.string.gdou_sync_log_clear)
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(Res.string.gdou_sync_log_empty))
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    logs,
                    key = { it.atMillis * 31 + it.message.hashCode() }
                ) { entry ->
                    SyncLogRow(entry)
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(Res.string.dialog_title_clear_confirmation)) },
            text = { Text(stringResource(Res.string.gdou_sync_log_clear_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        viewModel.clearLog()
                    }
                ) {
                    Text(stringResource(Res.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun SyncLogRow(entry: GdouSyncLogEntry) {
    val dt = Instant.fromEpochMilliseconds(entry.atMillis)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    fun two(v: Int) = v.toString().padStart(2, '0')
    val formatted = "${dt.date} ${two(dt.hour)}:${two(dt.minute)}"
    val formattedFull = "${dt.date} ${two(dt.hour)}:${two(dt.minute)}:${two(dt.second)}"

    val hasDetail = !entry.detail.isNullOrBlank()
    var expanded by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val copiedTip = stringResource(Res.string.gdou_sync_log_copied)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // 左侧色条：失败红色 / 成功中性灰
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(
                        if (entry.success) MaterialTheme.colorScheme.outlineVariant
                        else MaterialTheme.colorScheme.error
                    )
            )
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (hasDetail) Modifier.clickable { expanded = !expanded } else Modifier)
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    Icon(
                        vectorResource(if (entry.success) Res.drawable.check_circle_24px else Res.drawable.close_24px),
                        contentDescription = null,
                        tint = if (entry.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = entry.message,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = formatted,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = stringResource(
                            if (entry.source == "auto") Res.string.gdou_sync_source_auto
                            else Res.string.gdou_sync_source_manual
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (expanded && hasDetail) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = {
                                    clipboard.setText(
                                        AnnotatedString(
                                            buildString {
                                                append(formattedFull)
                                                append(' ')
                                                append(entry.message)
                                                append('\n')
                                                append(entry.detail)
                                            }
                                        )
                                    )
                                    ToastManager.show(copiedTip)
                                }
                            ) {
                                Text(
                                    text = stringResource(Res.string.gdou_sync_log_copy),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                        Text(
                            text = entry.detail.orEmpty(),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .background(
                                    MaterialTheme.colorScheme.surface,
                                    RoundedCornerShape(8.dp)
                                )
                                .verticalScroll(rememberScrollState())
                                .padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}
