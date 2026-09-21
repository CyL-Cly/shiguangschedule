package com.xingheyuzhuan.shiguangschedule.tool.update

import kotlinx.coroutines.flow.StateFlow

/**
 * 检查更新门面：Android 由 [AndroidUpdateController] 持有 UpdateKit 实现全部能力；
 * iOS/JVM 由 Stub 实现占位（更新功能仅 Android 可用）。
 */
interface AppUpdateController {
    val state: StateFlow<UpdateKitState>
    fun checkOnStartup()
    fun checkManual()
    fun download(manifest: UpdateManifest)
    fun downloadLatest(): Boolean
    fun dismiss()
    fun resetDownload()

    /** 是否已授权安装未知应用（仅 Android 有效，Stub 恒 false）。 */
    fun canRequestInstall(): Boolean

    /** 用 FileProvider 拉起系统安装界面；返回是否成功启动（仅 Android 有效）。 */
    fun launchInstall(filePath: String): Boolean
}
