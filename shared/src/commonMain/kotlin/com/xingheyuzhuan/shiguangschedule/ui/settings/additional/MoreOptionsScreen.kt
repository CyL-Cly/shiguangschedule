package com.xingheyuzhuan.shiguangschedule.ui.settings.additional

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.tool.update.AppUpdateController
import com.xingheyuzhuan.shiguangschedule.ui.settings.SettingsViewModel
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.qualifier.named
import shiguangschedule.shared.generated.resources.Res
import shiguangschedule.shared.generated.resources.a11y_back
import shiguangschedule.shared.generated.resources.app_name
import shiguangschedule.shared.generated.resources.arrow_back_24px
import shiguangschedule.shared.generated.resources.code_24px
import shiguangschedule.shared.generated.resources.home_24px
import shiguangschedule.shared.generated.resources.item_check_software_update
import shiguangschedule.shared.generated.resources.item_check_update_on_startup
import shiguangschedule.shared.generated.resources.item_contributors
import shiguangschedule.shared.generated.resources.item_gdou_sync_log
import shiguangschedule.shared.generated.resources.item_github_repo
import shiguangschedule.shared.generated.resources.item_language_settings
import shiguangschedule.shared.generated.resources.item_open_source_licenses
import shiguangschedule.shared.generated.resources.item_start_screen_settings
import shiguangschedule.shared.generated.resources.item_upstream_github_repo
import shiguangschedule.shared.generated.resources.item_update_repo
import shiguangschedule.shared.generated.resources.label_version_prefix
import shiguangschedule.shared.generated.resources.language_24px
import shiguangschedule.shared.generated.resources.list_alt_24px
import shiguangschedule.shared.generated.resources.palette_24px
import shiguangschedule.shared.generated.resources.people_alt_24px
import shiguangschedule.shared.generated.resources.sync_alt_24px
import shiguangschedule.shared.generated.resources.theme_settings_title
import shiguangschedule.shared.generated.resources.title_more_options
import shiguangschedule.shared.generated.resources.update_24px

private const val UPSTREAM_GITHUB_REPO_URL = "https://github.com/XingHeYuZhuan/shiguangschedule"
private const val PROJECT_GITHUB_REPO_URL = "https://github.com/CyL-Cly/shiguangschedule"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreOptionsScreen(
    onNavigate: (Destination) -> Unit,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
    updateController: AppUpdateController = koinInject(),
) {
    val scrollState = rememberScrollState()
    val uriHandler = LocalUriHandler.current

    val appVersionName: String = koinInject(named("AppVersionName"))

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isDeveloperModeEnabled = uiState.appSettings.developerModeEnabled

    var showStartScreenDialog by remember { mutableStateOf(false) }
    var appNameClickCount by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(Res.string.title_more_options)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = vectorResource(Res.drawable.arrow_back_24px),
                            contentDescription = stringResource(Res.string.a11y_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 应用信息头部
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                DynamicAppIconHeader(
                    isDeveloperModeEnabled = isDeveloperModeEnabled,
                    onTriggerDeveloperMode = { viewModel.onDeveloperModeChanged(true) }
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(Res.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        appNameClickCount++
                        if (appNameClickCount >= 5) {
                            appNameClickCount = 0
                            viewModel.onDeveloperModeChanged(true)
                        }
                    }
                )
                Text(
                    text = stringResource(Res.string.label_version_prefix, appVersionName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 设置列表卡片
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {

                    // 开发者模式设置项
                    DeveloperModeSettingItem(
                        isDeveloperModeEnabled = isDeveloperModeEnabled,
                        onDeveloperModeChanged = { viewModel.onDeveloperModeChanged(it) }
                    )

                    // 广海阳江同步日志 (仅开发者模式可见)
                    AnimatedVisibility(
                        visible = isDeveloperModeEnabled,
                        enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                        exit = fadeOut(tween(300)) + shrinkVertically(tween(300))
                    ) {
                        SettingListItem(
                            icon = vectorResource(Res.drawable.sync_alt_24px),
                            title = stringResource(Res.string.item_gdou_sync_log),
                            onClick = { onNavigate(Destination.GdouSyncLog) },
                            showDivider = false
                        )
                    }

                    // 检查更新
                    SettingListItem(
                        icon = vectorResource(Res.drawable.update_24px),
                        title = stringResource(Res.string.item_check_software_update),
                        onClick = { updateController.checkManual() }
                    )

                    // 启动时检查更新（可关闭）
                    SettingListItem(
                        icon = vectorResource(Res.drawable.update_24px),
                        title = stringResource(Res.string.item_check_update_on_startup),
                        onClick = {
                            viewModel.onCheckUpdateOnStartupChanged(!uiState.appSettings.checkUpdateOnStartup)
                        },
                        trailingContent = {
                            Switch(
                                checked = uiState.appSettings.checkUpdateOnStartup,
                                onCheckedChange = { viewModel.onCheckUpdateOnStartupChanged(it) }
                            )
                        }
                    )

                    // 语言切换 (导航至独立页面)
                    SettingListItem(
                        icon = vectorResource(Res.drawable.language_24px),
                        title = stringResource(Res.string.item_language_settings),
                        onClick = { onNavigate(Destination.LanguageSettings) }
                    )

                    // 主题设置
                    SettingListItem(
                        icon = vectorResource(Res.drawable.palette_24px),
                        title = stringResource(Res.string.theme_settings_title),
                        onClick = { onNavigate(Destination.ThemeSettings) }
                    )

                    // 启动页面设置
                    SettingListItem(
                        icon = vectorResource(Res.drawable.home_24px),
                        title = stringResource(Res.string.item_start_screen_settings),
                        onClick = { showStartScreenDialog = true },
                        trailingContent = {
                            Text(
                                text = stringResource(uiState.appSettings.startScreen.labelRes),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    )

                    // GitHub 仓库
                    SettingListItem(
                        icon = vectorResource(Res.drawable.code_24px),
                        title = stringResource(Res.string.item_upstream_github_repo),
                        onClick = { uriHandler.openUri(UPSTREAM_GITHUB_REPO_URL) }
                    )

                    SettingListItem(
                        icon = vectorResource(Res.drawable.code_24px),
                        title = stringResource(Res.string.item_github_repo),
                        onClick = { uriHandler.openUri(PROJECT_GITHUB_REPO_URL) }
                    )

                    // 开源许可证
                    SettingListItem(
                        icon = vectorResource(Res.drawable.list_alt_24px),
                        title = stringResource(Res.string.item_open_source_licenses),
                        onClick = { onNavigate(Destination.OpenSourceLicenses) }
                    )

                    // 更新适配仓库
                    SettingListItem(
                        icon = vectorResource(Res.drawable.update_24px),
                        title = stringResource(Res.string.item_update_repo),
                        onClick = { onNavigate(Destination.UpdateRepo) }
                    )

                    // 贡献者
                    SettingListItem(
                        icon = vectorResource(Res.drawable.people_alt_24px),
                        title = stringResource(Res.string.item_contributors),
                        onClick = { onNavigate(Destination.ContributionList) },
                        showDivider = false
                    )

                    // 鸣谢内容
                    AcknowledgmentContent()
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // --- 弹窗逻辑 ---

    // 启动页切换弹窗
    StartScreenSelectionDialog(
        showDialog = showStartScreenDialog,
        currentSelected = uiState.appSettings.startScreen,
        onDismiss = { showStartScreenDialog = false },
        onConfirm = {
            viewModel.onStartScreenChanged(it)
            showStartScreenDialog = false
        }
    )
}