package com.xingheyuzhuan.shiguangschedule.tool.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * APK 安装辅助：未知来源权限检查 / 跳系统设置 / 拉起系统安装界面。无状态。
 *
 * 接入要求：产品需在 manifest 注册 FileProvider（authority 默认 "${packageName}.fileprovider"，
 * 可通过 [UpdateKitConfig.fileProviderAuthority] 覆盖），paths 覆盖 external-files-path 下的下载目录。
 */
object ApkInstaller {

    /**
     * 是否已授权安装未知应用。
     *
     * 签名兼容：compileSdk 37 中 [PackageManager.canRequestPackageInstalls] 已改为无参版本，
     * 而 API 26-36 运行时是带包名参数版本——直接调用会在低版本设备抛 NoSuchMethodError，
     * 故用反射按签名探测（无参 → 带参），两种都不可用时保守返回 true（放行尝试安装）。
     */
    fun canRequestInstall(context: Context): Boolean {
        val pm = context.packageManager
        return runCatching {
            PackageManager::class.java.getMethod("canRequestPackageInstalls").invoke(pm) as Boolean
        }.getOrElse {
            runCatching {
                PackageManager::class.java.getMethod("canRequestPackageInstalls", String::class.java)
                    .invoke(pm, context.packageName) as Boolean
            }.getOrDefault(true)
        }
    }

    /** 跳系统「安装未知应用」设置页。 */
    fun intentToUnknownAppSources(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    )

    /**
     * 用 FileProvider 暴露文件并拉起系统安装界面。
     * @param authority FileProvider authority；null → 默认 "${packageName}.fileprovider" 约定
     * @return 是否成功启动安装 intent（失败多为无默认安装器/被禁用）。
     */
    fun launchInstall(context: Context, file: File, authority: String? = null): Boolean {
        return runCatching {
            val uri = FileProvider.getUriForFile(
                context, authority ?: "${context.packageName}.fileprovider", file,
            )
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.isSuccess
    }
}
