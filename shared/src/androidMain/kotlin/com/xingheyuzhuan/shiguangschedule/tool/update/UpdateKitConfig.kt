package com.xingheyuzhuan.shiguangschedule.tool.update

/**
 * UpdateKit 配置：接入产品唯一需要提供的是 [manifestUrl]。
 *
 * @param manifestUrl 更新清单地址（返回 [UpdateManifest] JSON）
 * @param userAgent 请求 UA；默认伪装成浏览器，绕过服务端按 UA 白名单拦截的 WAF（403）
 * @param apkDownloadDir APK 下载子目录（位于应用外部缓存目录 getExternalFilesDir 下）
 * @param apkFileName 下载的 APK 文件名
 * @param connectTimeoutSec 清单请求连接超时
 * @param readTimeoutSec 清单请求读取超时
 * @param writeTimeoutSec 下载写入超时
 * @param downloadMaxRetries 下载失败自动重试次数（[UpdateKit.downloadLatest] 场景默认使用）
 * @param downloadRetryDelayMs 每次重试前等待
 */
data class UpdateKitConfig(
    val manifestUrl: String,
    val userAgent: String = DEFAULT_USER_AGENT,
    val apkDownloadDir: String = "apk",
    val apkFileName: String = "update.apk",
    val connectTimeoutSec: Long = 10,
    val readTimeoutSec: Long = 15,
    val writeTimeoutSec: Long = 15,
    val downloadMaxRetries: Int = 2,
    val downloadRetryDelayMs: Long = 2_000,
) {
    companion object {
        /** 默认伪装 UA：Edge 移动版浏览器（旧版 SmsForwarder 实测可绕过 UA 白名单 WAF）。 */
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36 EdgA/150.0.0.0"
    }
}
