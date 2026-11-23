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

    /** 请求免确认自更新。返回 false 时需引导用户授予安装未知应用权限。 */
    fun requestInstall(): Boolean
}
