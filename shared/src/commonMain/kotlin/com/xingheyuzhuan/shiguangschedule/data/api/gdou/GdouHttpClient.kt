package com.xingheyuzhuan.shiguangschedule.data.api.gdou

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
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
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.koin.core.annotation.Single

data class GdouHttpExchange(
    val status: Int,
    val url: String,
    val location: String?,
    val body: String,
    val bytes: ByteArray? = null,
    internal val setsCookie: Boolean = false
) {
    internal val isRedirect: Boolean
        get() = status in REDIRECT_STATUSES && !location.isNullOrBlank()
}

private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

internal enum class GdouRoute(val host: String) {
    Gateway(GDOU_WFW_HOST),
    Auth(GDOU_AUTH_HOST),
    DirectJw(GDOU_JW_HOST);

    val origin: String get() = "https://$host"
}

@Single
class GdouHttpClient internal constructor(engine: HttpClientEngine? = null) {
    val cookieStore = GdouCookieStore()
    internal var jwRoute = GdouRoute.Gateway
    val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    internal var hopSink: ((GdouHttpExchange, List<GdouCookie>) -> Unit)? = null

    /** 请求连接类失败通知钩子：(requestUrl, error)，覆盖网关请求，供同步日志记录明细。 */
    internal var requestFailureSink: ((String, Throwable) -> Unit)? = null

    val client: HttpClient = HttpClient(engine ?: gdouHttpEngine()) {
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

    internal suspend fun get(
        url: String,
        referer: String? = null,
        route: GdouRoute = jwRoute,
        extra: HttpRequestBuilder.() -> Unit = {}
    ): GdouHttpExchange {
        val requestUrl = outboundUrl(url, route)
        val refererUrl = referer?.let { outboundUrl(it, route) }
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

    internal suspend fun getBytes(
        url: String,
        referer: String? = null,
        route: GdouRoute = jwRoute,
        extra: HttpRequestBuilder.() -> Unit = {}
    ): GdouHttpExchange {
        val requestUrl = outboundUrl(url, route)
        val refererUrl = referer?.let { outboundUrl(it, route) }
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

    internal suspend fun postForm(
        url: String,
        fields: Map<String, String>,
        referer: String? = null,
        origin: String? = null,
        ajax: Boolean = false,
        route: GdouRoute = jwRoute
    ): GdouHttpExchange {
        val requestUrl = outboundUrl(url, route)
        val refererUrl = referer?.let { outboundUrl(it, route) }
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

    internal suspend fun postRaw(
        url: String,
        body: String,
        contentType: String,
        referer: String? = null,
        ajax: Boolean = false,
        origin: String? = null,
        route: GdouRoute = jwRoute
    ): GdouHttpExchange {
        val requestUrl = outboundUrl(url, route)
        val refererUrl = referer?.let { outboundUrl(it, route) }
        try {
            val response = client.post(requestUrl) {
                header(HttpHeaders.ContentType, contentType)
                setBody(body)
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

    internal suspend fun followRedirects(
        startUrl: String,
        referer: String? = null,
        maxHops: Int = 16,
        route: GdouRoute = jwRoute
    ): GdouHttpExchange = followRedirects(get(startUrl, referer, route), maxHops, route)

    internal suspend fun followRedirects(
        first: GdouHttpExchange,
        maxHops: Int = 16,
        route: GdouRoute = jwRoute
    ): GdouHttpExchange {
        var last = first
        val visited = mutableSetOf(outboundUrl(first.url, route))
        var hops = 0
        var followedWfwRootSelfRedirect = false
        while (true) {
            val location = when {
                last.isRedirect -> last.location
                GdouHtml.isSsoPath(last.url) -> GdouHtml.htmlRedirect(last.body)
                else -> null
            } ?: return last
            val resolved = GdouHtml.resolveUrl(last.url, location)
            val nextHost = Url(resolved).host
            // 跨服务的跳转交给认证客户端选择传输路径。
            if (route == GdouRoute.Auth && (nextHost == GDOU_JW_HOST || nextHost == GDOU_WFW_HOST)) return last
            if (route != GdouRoute.Auth && nextHost == GDOU_AUTH_HOST) return last
            val next = outboundUrl(resolved, route)
            if (!visited.add(next)) {
                // WFW 兑换后会在无票据的根 URL 设置 Cookie，再 302 到同一 URL；仅允许跟随一次。
                val sameWfwRootAfterSetCookie = route == GdouRoute.Gateway && last.isRedirect && last.setsCookie &&
                    !followedWfwRootSelfRedirect && Url(next) == Url(GDOU_WFW_HOME) && Url(last.url) == Url(next)
                if (!sameWfwRootAfterSetCookie) return last
                followedWfwRootSelfRedirect = true
            }
            check(hops++ < maxHops) { "跳转次数过多" }
            last = get(next, last.url, route)
        }
    }

    fun parseJson(text: String): JsonElement = json.parseToJsonElement(text)

    private fun outboundUrl(url: String, route: GdouRoute): String {
        val parsed = Url(url)
        require(parsed.protocol == URLProtocol.HTTPS && parsed.port == 443 && parsed.user == null) {
            "教务请求必须使用 HTTPS"
        }
        val target = when (route) {
            GdouRoute.Gateway -> when (parsed.host) {
                GDOU_WFW_HOST -> url
                GDOU_JW_HOST -> GdouHtml.rewriteOfficialJwToGateway(url)
                else -> null
            }
            GdouRoute.Auth -> url.takeIf { parsed.host == GDOU_AUTH_HOST && parsed.encodedPath.startsWith("/authserver/") }
            GdouRoute.DirectJw -> url.takeIf { parsed.host == GDOU_JW_HOST }
        }
        return requireNotNull(target) { "教务请求目标与当前登录路径不匹配" }
    }

    private suspend fun toExchange(response: HttpResponse, asBytes: Boolean = false): GdouHttpExchange {
        val location = response.headers[HttpHeaders.Location]
        return GdouHttpExchange(
            status = response.status.value,
            url = response.request.url.toString(),
            location = location?.let { GdouHtml.resolveUrl(response.request.url.toString(), it) },
            body = if (asBytes) "" else response.bodyAsText(),
            bytes = if (asBytes) response.bodyAsBytes() else null,
            setsCookie = !response.headers.getAll(HttpHeaders.SetCookie).isNullOrEmpty()
        ).also { notifyHop(it) }
    }

    private suspend fun notifyHop(exchange: GdouHttpExchange) {
        hopSink?.invoke(exchange, cookieStore.snapshot())
    }

    companion object {
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
