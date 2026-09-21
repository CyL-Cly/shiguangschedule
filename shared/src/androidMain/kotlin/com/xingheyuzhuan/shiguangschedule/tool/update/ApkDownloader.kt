package com.xingheyuzhuan.shiguangschedule.tool.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * APK 下载器：流式下载到目标文件（覆盖写），下载中增量计算 SHA-256。
 *
 * - 失败或中断时删除半成品文件；
 * - [expectedSha256] 非空时下载完成后强校验，不匹配视为失败（半成品已清理），
 *   防止下载链路被劫持 / CDN 缓存污染（配合 UpdateManifest.sha256 字段）；
 * - [expectedFileSize] >0 时校验实际字节数一致（清单 fileSize 参与校验）；
 * - [onProgress] 回调 (doneBytes, totalBytes)，totalBytes 为 null 表示响应未声明长度（不确定进度）。
 */
class ApkDownloader(
    private val client: OkHttpClient,
) {

    /**
     * @param url 下载地址
     * @param destFile 目标文件（覆盖写）
     * @param onProgress 进度回调，可能从任意线程回调
     * @param expectedSha256 期望的 SHA-256（十六进制，忽略大小写）；空串表示不校验
     * @param expectedFileSize 期望的字节数；<=0 表示不校验
     * @return 成功携带已完成的文件；失败为失败 Result（半成品已清理）
     */
    suspend fun download(
        url: String,
        destFile: File,
        onProgress: (Long, Long?) -> Unit,
        expectedSha256: String = "",
        expectedFileSize: Long = 0L,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            destFile.delete()
            destFile.parentFile?.mkdirs()
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val total = resp.body?.contentLength()?.takeIf { it > 0 }
                val digest = MessageDigest.getInstance("SHA-256")
                resp.body?.byteStream()?.use { input ->
                    destFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var done = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            done += read
                            onProgress(done, total)
                        }
                        // 完整性校验：字节数与哈希任一不匹配即失败（onFailure 统一清理半成品）
                        if (expectedFileSize > 0 && done != expectedFileSize) {
                            error("APK 大小不匹配: 期望 $expectedFileSize 实际 $done")
                        }
                        val expected = expectedSha256.trim().lowercase()
                        if (expected.isNotEmpty()) {
                            val actual = digest.digest().joinToString("") { "%02x".format(it) }
                            if (actual != expected) error("APK SHA-256 校验失败: 期望 $expected 实际 $actual")
                        }
                    }
                } ?: error("empty body")
                destFile
            }
        }.onFailure { runCatching { destFile.delete() } }
    }

    /**
     * 带自动重试的下载：失败后自动重试 [maxRetries] 次（共 maxRetries+1 次尝试），
     * 每次重试前等待 [retryDelayMs]，全部尝试失败才返回失败（半成品已清理）。
     *
     * @param onRetry 第 attempt 次重试即将开始时回调（attempt 从 1 起），用于 UI 展示重试进度。
     */
    suspend fun downloadWithRetry(
        url: String,
        destFile: File,
        onProgress: (Long, Long?) -> Unit,
        maxRetries: Int = 2,
        retryDelayMs: Long = 2_000L,
        onRetry: (attempt: Int) -> Unit = {},
        expectedSha256: String = "",
        expectedFileSize: Long = 0L,
    ): Result<File> {
        var attempt = 0
        while (true) {
            val result = download(url, destFile, onProgress, expectedSha256, expectedFileSize)
            if (result.isSuccess || attempt >= maxRetries) return result
            attempt++
            onRetry(attempt)
            delay(retryDelayMs)
        }
    }
}
