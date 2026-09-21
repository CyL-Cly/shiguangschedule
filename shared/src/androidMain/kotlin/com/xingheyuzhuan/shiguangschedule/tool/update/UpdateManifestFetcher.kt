package com.xingheyuzhuan.shiguangschedule.tool.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 更新清单拉取器：GET [UpdateKitConfig.manifestUrl] 并解析为 [UpdateManifest]。
 *
 * 网络失败 / 解析失败 / 清单无效 → 返回 null（调用方统一按 Error 处理）。
 * 序列化用 kit 私有 [kitJson]（ignoreUnknownKeys=true，schema 演进不炸）。
 */
class UpdateManifestFetcher(
    private val config: UpdateKitConfig,
    private val client: OkHttpClient,
) {

    /** 默认真实网络实现；测试通过 [setApiForTest] 替换。 */
    private var api: ManifestApi = ManifestApi { requestRemote() }

    fun interface ManifestApi {
        suspend fun fetch(): UpdateManifest?
    }

    fun setApiForTest(api: ManifestApi) {
        this.api = api
    }

    /** 拉取更新清单；网络失败/解析失败/清单无效 → null。 */
    suspend fun fetch(): UpdateManifest? = api.fetch()

    private suspend fun requestRemote(): UpdateManifest? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(config.manifestUrl)
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                kitJson.decodeFromString<UpdateManifest>(body)
            }
        }.getOrNull()?.takeIf { it.isValid() }
    }
}

/** Kit 内部序列化实例：与业务 JsonProvider 解耦，仅保留清单解析所需的宽松配置。 */
internal val kitJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}
