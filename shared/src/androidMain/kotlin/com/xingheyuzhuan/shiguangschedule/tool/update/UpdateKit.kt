package com.xingheyuzhuan.shiguangschedule.tool.update

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 检查更新 Kit 统一入口：清单拉取 → 版本比对 → APK 下载（含重试）→ 拉起安装。
 *
 * ## 快速接入（其他产品）
 *
 * 1. 依赖本模块：`implementation(project(":core:updatekit"))`；
 * 2. 构造实例（无需 DI 框架，非 Android 组件内传 applicationContext）：
 * ```kotlin
 * val kit = UpdateKit(
 *     context,
 *     UpdateKitConfig(manifestUrl = "https://your.host/update.json"),
 * )
 * ```
 * 3. 观察并渲染 [state]（建议 Dialog 挂页面顶层）：
 *    - 手动检查：`kit.checkUpdate()` → `Available` / `UpToDate` / `Error`；
 *    - 启动静默检查：`kit.checkOnStartup()`（进程内只查一次，无更新/失败静默回 Idle）；
 *    - 下载：`kit.download(manifest)` 或 `kit.downloadLatest()`（复用检查结果，自动按
 *      [UpdateKitConfig.downloadMaxRetries] 重试）→ `ReadyToInstall` / `DownloadFailed`；
 *    - 安装：`ApkInstaller.canRequestInstall` → `launchInstall`（未授权先
 *      `intentToUnknownAppSources` 引导授权），完成后 `resetDownload()` 复位。
 * 4. 接入要求：manifest 注册 FileProvider（authority 默认 "${packageName}.fileprovider"），
 *    paths 覆盖 external-files-path 下 APK 下载目录。
 *
 * ## 状态互斥
 * 实例自带状态机，多入口（如设置页手动检查 + 启动强制更新）请各自持有实例以免状态串扰；
 * 底层 client 与逻辑无状态，多实例开销可忽略。不再使用时调用 [destroy] 释放协程。
 *
 * @param client 可选自定义 client；null → 按 [UpdateKitConfig] 内建（伪装 UA + 短超时）
 */
class UpdateKit(
    context: Context,
    private val config: UpdateKitConfig,
    client: OkHttpClient? = null,
) {

    private val appContext: Context = context.applicationContext
    private val httpClient: OkHttpClient = client ?: UpdateKitHttp.createClient(config)
    private val fetcher = UpdateManifestFetcher(config, httpClient)
    private val downloader = ApkDownloader(httpClient)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<UpdateKitState>(UpdateKitState.Idle)

    /** 当前状态机状态（Dialog 渲染依据）。 */
    val state: StateFlow<UpdateKitState> = _state.asStateFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    /** 启动静默检查幂等标志：进程内只检查一次。 */
    private var startupChecked = false

    /** 最近一次确认可用的清单（downloadLatest 复用）。 */
    private var lastManifest: UpdateManifest? = null

    /** 下载取消标志：cancel 后抑制迟到的进度/结果回调覆盖状态。 */
    private val downloadCancelled = AtomicBoolean(false)

    // ---- 检查 ----

    /**
     * 手动检查更新（设置页入口）：Checking → Available / UpToDate / Error。
     * 结果态展示后调 [dismissCheck] 复位。Checking 中重复调用被忽略。
     */
    fun checkUpdate() {
        if (_state.value is UpdateKitState.Checking) return
        checkJob?.cancel()
        _state.value = UpdateKitState.Checking
        checkJob = scope.launch {
            _state.value = when (val decision = evaluate()) {
                is UpdateDecision.Available -> {
                    lastManifest = decision.manifest
                    UpdateKitState.Available(decision.manifest)
                }
                is UpdateDecision.UpToDate -> UpdateKitState.UpToDate
                is UpdateDecision.Error -> UpdateKitState.Error
            }
        }
    }

    /**
     * 启动静默检查：联网失败 / 清单无效 / 已最新 → 静默回 Idle（不打扰用户）；
     * 发现新版本 → Available（由接入方渲染强制更新弹窗）。
     * 进程内只检查一次（幂等，重复调用忽略）。
     */
    fun checkOnStartup() {
        if (startupChecked) return
        startupChecked = true
        if (_state.value is UpdateKitState.Checking) return
        _state.value = UpdateKitState.Checking
        checkJob = scope.launch {
            _state.value = when (val decision = evaluate()) {
                is UpdateDecision.Available -> {
                    lastManifest = decision.manifest
                    UpdateKitState.Available(decision.manifest)
                }
                // Error（联网失败/解析失败/清单无效）与 UpToDate → 静默
                else -> UpdateKitState.Idle
            }
        }
    }

    /** 关闭检查结果对话框（Available/UpToDate/Error → Idle；Checking 不复位）。 */
    fun dismissCheck() {
        if (_state.value !is UpdateKitState.Checking) _state.value = UpdateKitState.Idle
    }

    // ---- 下载 / 安装 ----

    /**
     * 下载指定清单的 APK：Downloading → ReadyToInstall / DownloadFailed。
     *
     * @param maxRetries 失败自动重试次数（0 = 不重试，共 1 次尝试）；
     *   重试语义同 [ApkDownloader.downloadWithRetry]，进度与重试次数由 [UpdateKitState.Downloading] 呈现。
     */
    fun download(manifest: UpdateManifest, maxRetries: Int = 0) {
        if (_state.value is UpdateKitState.Downloading) return
        if (!manifest.isValid()) {
            _state.value = UpdateKitState.DownloadFailed
            return
        }
        lastManifest = manifest
        startDownloadJob(manifest, maxRetries)
    }

    /**
     * 用最近一次检查确认可用的清单下载（启动强制更新场景，含 [UpdateKitConfig.downloadMaxRetries] 次重试）。
     * @return 是否成功启动下载（无可用清单时 false，如 DownloadFailed 后重试）。
     */
    fun downloadLatest(): Boolean {
        if (_state.value is UpdateKitState.Downloading) return true
        val manifest = lastManifest ?: return false
        startDownloadJob(manifest, config.downloadMaxRetries)
        return true
    }

    /**
     * 复位下载状态：Downloading 中调用会取消下载并清理半成品；
     * ReadyToInstall / DownloadFailed → Idle。
     */
    fun resetDownload() {
        downloadCancelled.set(true)
        downloadJob?.cancel()
        downloadJob = null
        if (_state.value !is UpdateKitState.Idle) _state.value = UpdateKitState.Idle
    }

    /** 释放内部协程作用域（实例废弃时调用；通常单例持有则无需调用）。 */
    fun destroy() {
        scope.cancel()
    }

    // ---- 内部 ----

    private fun startDownloadJob(manifest: UpdateManifest, maxRetries: Int) {
        downloadCancelled.set(false)
        var attempt = 1
        val maxAttempts = (maxRetries + 1).coerceAtLeast(1)
        _state.value = UpdateKitState.Downloading(0L, null, attempt, maxAttempts)
        downloadJob = scope.launch {
            val result = downloader.downloadWithRetry(
                url = manifest.downloadUrl,
                destFile = destFile(),
                onProgress = { done, total ->
                    if (!downloadCancelled.get()) {
                        _state.value = UpdateKitState.Downloading(done, total, attempt, maxAttempts)
                    }
                },
                maxRetries = maxRetries,
                retryDelayMs = config.downloadRetryDelayMs,
                onRetry = { next ->
                    attempt = next
                    if (!downloadCancelled.get()) {
                        _state.value = UpdateKitState.Downloading(0L, null, attempt, maxAttempts)
                    }
                },
                // 清单声明的完整性信息：sha256 非空时强校验，fileSize>0 时校验字节数
                expectedSha256 = manifest.sha256,
                expectedFileSize = manifest.fileSize,
            )
            if (downloadCancelled.get()) return@launch
            _state.value = result.fold(
                onSuccess = { UpdateKitState.ReadyToInstall(it.absolutePath) },
                onFailure = { UpdateKitState.DownloadFailed },
            )
        }
    }

    private suspend fun evaluate(): UpdateDecision =
        UpdateVersionPolicy.evaluate(fetcher.fetch(), localVersionCode())

    /** APK 下载目标文件：外部缓存目录 / [UpdateKitConfig.apkDownloadDir] / [UpdateKitConfig.apkFileName]。 */
    private fun destFile(): File = File(
        File(appContext.getExternalFilesDir(null), config.apkDownloadDir).apply { mkdirs() },
        config.apkFileName,
    )

    private fun localVersionCode(): Long = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).longVersionCode
    }.getOrDefault(0L)
}
