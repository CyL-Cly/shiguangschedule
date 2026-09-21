package com.xingheyuzhuan.shiguangschedule.tool.update

import android.content.Context
import org.koin.core.annotation.Single
import java.io.File

/** [AppUpdateController] 的 Android 实现：内部持有唯一 UpdateKit 实例并转发。 */
@Single
class AndroidUpdateController(private val context: Context) : AppUpdateController {

    private val config = UpdateKitConfig(
        manifestUrl = "https://refree.rth1.xyz/shiguangschedule.json",
        apkFileName = "shiguangschedule.apk",
    )

    private val kit = UpdateKit(context, config)

    override val state = kit.state

    override fun checkOnStartup() = kit.checkOnStartup()

    override fun checkManual() = kit.checkUpdate()

    override fun download(manifest: UpdateManifest) = kit.download(manifest)

    override fun downloadLatest(): Boolean = kit.downloadLatest()

    override fun dismiss() = kit.dismissCheck()

    override fun resetDownload() = kit.resetDownload()

    override fun canRequestInstall(): Boolean = ApkInstaller.canRequestInstall(context)

    override fun launchInstall(filePath: String): Boolean =
        ApkInstaller.launchInstall(context, File(filePath), config.fileProviderAuthority)
}
