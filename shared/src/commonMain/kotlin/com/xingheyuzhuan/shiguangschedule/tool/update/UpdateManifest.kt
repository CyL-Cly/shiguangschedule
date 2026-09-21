package com.xingheyuzhuan.shiguangschedule.tool.update

import kotlinx.serialization.Serializable

/** 远端更新清单（对应 update.json），字段缺失容忍，非法清单由 [isValid] 兜底。 */
@Serializable
data class UpdateManifest(
    val versionCode: Long = 0L,
    val versionName: String = "",
    val downloadUrl: String = "",
    val releaseNotes: String = "",
    val fileSize: Long = 0L,
    /** APK 文件 SHA-256（64 位十六进制）。可选：非空时下载完成后强校验，不匹配即拒绝安装。 */
    val sha256: String = "",
) {
    /** 远端版本号大于本地才算有更新（versionCode<=0 视为无效远端，恒 false）。 */
    fun isNewerThan(localVersionCode: Long): Boolean =
        versionCode > 0L && versionCode > localVersionCode

    /** 清单可用性：版本号>0 且下载地址非空；sha256 若填写必须是 64 位十六进制。 */
    fun isValid(): Boolean =
        versionCode > 0L &&
            downloadUrl.isNotBlank() &&
            (sha256.isBlank() || SHA256_REGEX.matches(sha256))

    companion object {
        private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")
    }
}
