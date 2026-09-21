package com.xingheyuzhuan.shiguangschedule.data.api.gdou

import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.Url
import io.ktor.http.hostIsIp
import io.ktor.http.isSecure
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 按 (name, domain, path) 区分 Cookie，避免把 jw 上 path=/ 与 path=/sso 的两个 JSESSIONID 合成一条。
 */
class GdouCookieStore : CookiesStorage {
    private val mutex = Mutex()
    private val cookies = LinkedHashMap<CookieKey, Cookie>()

    data class CookieKey(val name: String, val domain: String, val path: String)

    suspend fun snapshot(): List<GdouCookie> = mutex.withLock {
        cookies.values.map {
            GdouCookie(
                name = it.name,
                value = it.value,
                domain = (it.domain ?: "").trimStart('.'),
                path = it.path ?: "/"
            )
        }
    }

    suspend fun restore(saved: List<GdouCookie>) = mutex.withLock {
        cookies.clear()
        saved.forEach { item ->
            val domain = item.domain.trimStart('.')
            val path = item.path.ifBlank { "/" }
            cookies[CookieKey(item.name, domain, path)] = Cookie(
                name = item.name,
                value = item.value,
                domain = domain,
                path = path,
                httpOnly = false,
                secure = false
            )
        }
    }

    suspend fun clear() = mutex.withLock { cookies.clear() }

    suspend fun isEmpty(): Boolean = mutex.withLock { cookies.isEmpty() }

    suspend fun removeByName(name: String) = removeMatching { it.name.equals(name, ignoreCase = true) }

    suspend fun removeMatching(predicate: (Cookie) -> Boolean) = mutex.withLock {
        cookies.filter { predicate(it.value) }.keys.toList().forEach { cookies.remove(it) }
    }

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
        mutex.withLock {
            val domain = (cookie.domain ?: requestUrl.host).trimStart('.')
            val path = cookie.path?.ifBlank { null } ?: pathFromUrl(requestUrl)
            val key = CookieKey(cookie.name, domain, path)
            if (cookie.value.isEmpty() || cookie.maxAge == 0 || cookie.expires == GMTDate.START) {
                cookies.remove(key)
            } else {
                cookies[key] = cookie.copy(domain = domain, path = path)
            }
        }
    }

    override suspend fun get(requestUrl: Url): List<Cookie> = mutex.withLock {
        val now = GMTDate()
        cookies.values.filter { cookie ->
            matchesDomain(cookie.domain.orEmpty(), requestUrl.host) &&
                pathMatches(cookie.path ?: "/", requestUrl.encodedPath) &&
                (!cookie.secure || requestUrl.protocol.isSecure()) &&
                (cookie.expires == null || (cookie.expires?.timestamp ?: Long.MAX_VALUE) > now.timestamp)
        }
            .sortedByDescending { (it.path ?: "/").length }
            .distinctBy { it.name }
    }

    override fun close() {}

    private fun pathFromUrl(url: Url): String {
        val path = url.encodedPath.ifBlank { "/" }
        val slash = path.lastIndexOf('/')
        return if (slash <= 0) "/" else path.substring(0, slash)
    }

    private fun matchesDomain(cookieDomain: String, host: String): Boolean {
        val domain = cookieDomain.trimStart('.').lowercase()
        val requestHost = host.lowercase()
        if (domain.isEmpty()) return true
        if (domain == requestHost) return true
        if (hostIsIp(requestHost)) return domain == requestHost
        return requestHost.endsWith(".$domain")
    }

    private fun pathMatches(cookiePath: String, requestPath: String): Boolean {
        val path = if (cookiePath.startsWith("/")) cookiePath else "/$cookiePath"
        val req = if (requestPath.startsWith("/")) requestPath else "/$requestPath"
        if (path == "/") return true
        if (req == path) return true
        return req.startsWith(if (path.endsWith("/")) path else "$path/")
    }
}
