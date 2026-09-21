package com.xingheyuzhuan.shiguangschedule.data.api.gdou

import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.koin.core.annotation.Single

data class GdouHttpExchange(
    val status: Int,
    val url: String,
    val location: String?,
    val body: String,
    val bytes: ByteArray? = null
)

@Single
class GdouHttpClient {
    val cookieStore = GdouCookieStore()
    val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    internal var hopSink: ((GdouHttpExchange, List<GdouCookie>) -> Unit)? = null

    /** 请求连接类失败通知钩子：(requestUrl, error)，覆盖所有 host（jw/authserver/wfw），供同步日志记录明细。 */
    internal var requestFailureSink: ((String, Throwable) -> Unit)? = null

    val client: HttpClient = HttpClient {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 20_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
        install(HttpCookies) {
            storage = cookieStore
        }
        defaultRequest {
            header(HttpHeaders.UserAgent, GDOU_USER_AGENT)
            header(HttpHeaders.AcceptLanguage, "zh-CN,zh;q=0.9,en;q=0.8")
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        }
    }

    suspend fun get(
        url: String,
        referer: String? = null,
        extra: HttpRequestBuilder.() -> Unit = {}
    ): GdouHttpExchange {
        val requestUrl = outboundUrl(url)
        val refererUrl = referer?.let { outboundReferer(it) }
        try {
            val response = client.get(requestUrl) {
                if (refererUrl != null) header(HttpHeaders.Referrer, refererUrl)
                extra()
            }
            return toExchange(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            requestFailureSink?.invoke(requestUrl, e)
            throw e
        }
    }

    suspend fun getBytes(
        url: String,
        referer: String? = null,
        extra: HttpRequestBuilder.() -> Unit = {}
    ): GdouHttpExchange {
        val requestUrl = outboundUrl(url)
        val refererUrl = referer?.let { outboundReferer(it) }
        try {
            val response = client.get(requestUrl) {
                if (refererUrl != null) header(HttpHeaders.Referrer, refererUrl)
                extra()
            }
            return toExchange(response, asBytes = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            requestFailureSink?.invoke(requestUrl, e)
            throw e
        }
    }

    suspend fun postForm(
        url: String,
        fields: Map<String, String>,
        referer: String? = null,
        origin: String? = null,
        ajax: Boolean = false
    ): GdouHttpExchange {
        val requestUrl = outboundUrl(url)
        val refererUrl = referer?.let { outboundReferer(it) }
        try {
            val response = client.submitForm(
                url = requestUrl,
                formParameters = Parameters.build {
                    fields.forEach { (k, v) -> append(k, v) }
                }
            ) {
                if (refererUrl != null) header(HttpHeaders.Referrer, refererUrl)
                if (origin != null) header(HttpHeaders.Origin, origin)
                if (ajax) {
                    header("X-Requested-With", "XMLHttpRequest")
                    header(HttpHeaders.Accept, "application/json, text/javascript, */*; q=0.01")
                }
            }
            return toExchange(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            requestFailureSink?.invoke(requestUrl, e)
            throw e
        }
    }

    suspend fun postRaw(
        url: String,
        body: String,
        contentType: String,
        referer: String? = null,
        ajax: Boolean = false
    ): GdouHttpExchange {
        val requestUrl = outboundUrl(url)
        val refererUrl = referer?.let { outboundReferer(it) }
        try {
            val response = client.post(requestUrl) {
                header(HttpHeaders.ContentType, contentType)
                setBody(body)
                if (refererUrl != null) header(HttpHeaders.Referrer, refererUrl)
                if (ajax) {
                    header("X-Requested-With", "XMLHttpRequest")
                    header(HttpHeaders.Accept, "application/json, text/javascript, */*; q=0.01")
                }
            }
            return toExchange(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            requestFailureSink?.invoke(requestUrl, e)
            throw e
        }
    }

    suspend fun followRedirects(startUrl: String, referer: String? = null, maxHops: Int = 16): GdouHttpExchange {
        var current = outboundUrl(startUrl)
        var currentReferer = referer?.let { outboundUrl(it) }
        var last = get(current, currentReferer)
        var hops = 0
        while (hops < maxHops) {
            val headerLoc = last.location
            val rawNext = when {
                last.status in REDIRECTS && !headerLoc.isNullOrBlank() ->
                    GdouHtml.resolveUrl(current, headerLoc)
                GdouHtml.isSsoPath(current) ->
                    GdouHtml.htmlRedirect(last.body)?.let { GdouHtml.resolveUrl(current, it) }
                else -> null
            }
            if (rawNext.isNullOrBlank()) {
                return last.copy(url = current)
            }
            val next = outboundUrl(rawNext)
            if (next == current) {
                return last.copy(url = current)
            }
            currentReferer = current
            current = next
            last = get(current, currentReferer)
            hops++
        }
        error("跳转次数过多")
    }

    fun parseJson(text: String): JsonElement = json.parseToJsonElement(text)

    /**
     * 出站 URL 改写（校外实证协议，唯一权威 E:/code/py/gdoulogin/wfw_login.py）：
     * - 官方 jw → 嵌入 wfw 网关前缀（https://wfw.gdou.edu.cn/https://jw.gdou.edu.cn/...）；
     * - authserver.gdou.edu.cn → wfw 网关根同路径（防绝对 Location 直连被拒）；
     * - 其余（wfw 自身等）原样。全程禁止直接访问 jw/authserver 官方域。
     */
    private fun outboundUrl(url: String): String {
        val https = GdouHtml.forceHttps(url)
        return when (GdouHtml.hostOf(https)) {
            GDOU_JW_HOST -> GdouHtml.rewriteOfficialJwToGateway(https)
            GDOU_AUTH_HOST -> GDOU_WFW_ORIGIN + pathAndQueryOf(https)
            else -> https
        }
    }

    private fun pathAndQueryOf(url: String): String {
        val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
        val pathAndQuery = afterScheme.substringAfter('/', missingDelimiterValue = "")
        return if (pathAndQuery.isEmpty()) "/" else "/$pathAndQuery"
    }

    private fun outboundReferer(url: String): String = outboundUrl(url)

    private suspend fun toExchange(response: HttpResponse, asBytes: Boolean = false): GdouHttpExchange {
        val location = response.headers[HttpHeaders.Location]
        return GdouHttpExchange(
            status = response.status.value,
            url = response.request.url.toString(),
            location = location?.let { GdouHtml.resolveUrl(response.request.url.toString(), it) },
            body = if (asBytes) "" else response.bodyAsText(),
            bytes = if (asBytes) response.bodyAsBytes() else null
        ).also { notifyHop(it) }
    }

    private suspend fun notifyHop(exchange: GdouHttpExchange) {
        hopSink?.invoke(exchange, cookieStore.snapshot())
    }

    companion object {
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)

        private val CONNECTIVITY_HINTS = listOf(
            "connection refused",
            "connect timed out",
            "connection reset",
            "connection aborted",
            "network is unreachable",
            "unable to resolve",
            "unknownhost",
            "econnrefused",
            "could not connect to the server",
            "failed to connect"
        )

        /**
         * 连接类失败 → 用户可读的中文描述；非连接类返回 null。
         * 日志与错误消息严禁直接输出异常原文（英文 refused 等会暴露给用户）。
         */
        internal fun connectivityText(error: Throwable): String? {
            if (!isConnectivityError(error)) return null
            return when (describeConnectivity(error)) {
                "refused" -> "连接被拒"
                "timeout" -> "连接超时"
                else -> "网络不可达"
            }
        }

        private fun describeConnectivity(error: Throwable): String {
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < 8) {
                if (current is ConnectTimeoutException ||
                    current is SocketTimeoutException ||
                    current is HttpRequestTimeoutException
                ) {
                    return "timeout"
                }
                val message = current.message?.lowercase() ?: ""
                if (message.contains("connection refused") || message.contains("econnrefused")) return "refused"
                if (message.contains("timed out")) return "timeout"
                current = current.cause
                depth++
            }
            return "unreachable"
        }

        internal fun isConnectivityError(error: Throwable): Boolean {
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < 8) {
                if (current is ConnectTimeoutException ||
                    current is SocketTimeoutException ||
                    current is HttpRequestTimeoutException
                ) {
                    return true
                }
                val message = current.message?.lowercase() ?: ""
                if (CONNECTIVITY_HINTS.any { message.contains(it) }) return true
                current = current.cause
                depth++
            }
            return false
        }
    }
}
