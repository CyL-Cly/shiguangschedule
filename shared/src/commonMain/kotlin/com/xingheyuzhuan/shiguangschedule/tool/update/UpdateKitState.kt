package com.xingheyuzhuan.shiguangschedule.tool.update

/**
 * UpdateKit 统一状态机：覆盖「手动检查更新」与「启动强制更新」两类场景。
 *
 * - 手动检查（设置页）：Checking → Available / UpToDate / Error（结果展示后 dismissCheck 复位）；
 * - 启动强制更新：checkOnStartup 静默检查，Available → downloadLatest（含重试）→
 *   ReadyToInstall；多次尝试均失败 → DownloadFailed（给退路，下次启动再检查）。
 */
sealed interface UpdateKitState {
    /** 空闲（无弹窗）。 */
    data object Idle : UpdateKitState

    /** 检查中。 */
    data object Checking : UpdateKitState

    /** 发现新版本。 */
    data class Available(val manifest: UpdateManifest) : UpdateKitState

    /** 已是最新版本（手动检查场景展示）。 */
    data object UpToDate : UpdateKitState

    /** 检查失败：联网失败 / 清单无效（手动检查场景展示）。 */
    data object Error : UpdateKitState

    /**
     * 下载中。
     * @param attempt 当前第几次尝试（从 1 起）
     * @param maxAttempts 总尝试次数（1 + 重试次数）；UI 可据此在 attempt > 1 时提示自动重试
     */
    data class Downloading(
        val doneBytes: Long,
        val totalBytes: Long?,
        val attempt: Int = 1,
        val maxAttempts: Int = 1,
    ) : UpdateKitState

    /** 下载完成，待安装（ApkInstaller.launchInstall）。 */
    data class ReadyToInstall(val filePath: String) : UpdateKitState

    /** 下载失败（含全部重试耗尽）。 */
    data object DownloadFailed : UpdateKitState
}
