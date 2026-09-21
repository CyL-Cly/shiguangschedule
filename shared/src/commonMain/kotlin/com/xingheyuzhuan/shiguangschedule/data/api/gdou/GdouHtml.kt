package com.xingheyuzhuan.shiguangschedule.data.api.gdou

internal object GdouHtml {
    private val JW_SESSION_MARKERS = listOf(
        "sessionUserKey",
        "index_initMenu",
        "gnmkdm",
        "cdNav",
        "xtgl/index",
        "xskbcx",
        "kbList"
    )

    fun attrValue(html: String, elemId: String): String {
        val byIdFirst = Regex(
            """id=["']${Regex.escape(elemId)}["'][^>]*value=["']([^"']*)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)
        if (byIdFirst != null) return byIdFirst.groupValues[1]
        val byValueFirst = Regex(
            """value=["']([^"']*)["'][^>]*id=["']${Regex.escape(elemId)}["']""",
            RegexOption.IGNORE_CASE
        ).find(html)
        return byValueFirst?.groupValues?.get(1).orEmpty()
    }

    fun pageError(html: String): String {
        val match = Regex(
            """id="showErrorTip"[^>]*>(.*?)</span>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(html) ?: return ""
        return match.groupValues[1].replace(Regex("<[^>]+>"), "").trim()
    }

    fun parseReauthParams(html: String): GdouReauthParams {
        val block = Regex(
            """var reAuthParams\s*=\s*\{(.*?)\n\s*\};""",
            RegexOption.DOT_MATCHES_ALL
        ).find(html)?.groupValues?.get(1).orEmpty()

        fun field(key: String): String {
            val match = Regex(""""${Regex.escape(key)}"\s*:\s*(null|"((?:\\.|[^"\\])*)")""").find(block)
                ?: return ""
            if (match.groupValues[1] == "null") return ""
            return match.groupValues[2]
                .replace("\\/", "/")
                .replace("\\n", "\n")
        }

        return GdouReauthParams(
            reAuthType = field("reAuthType").ifBlank { "4" },
            isMultifactor = field("isMultifactor").ifBlank { "true" },
            service = field("service").ifBlank { GDOU_SERVICE_URL },
            reAuthUserId = field("reAuthUserId"),
            pwdEncryptSalt = field("pwdEncryptSalt")
        )
    }

    fun hostOf(url: String): String {
        val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
        if (afterScheme.isEmpty()) return ""
        val hostPort = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        return hostPort.substringBefore(':').lowercase()
    }

    fun isJwHost(host: String): Boolean {
        val normalized = host.trimStart('.').lowercase()
        return normalized == GDOU_JW_HOST
    }

    fun isOfficialJwUrl(url: String): Boolean = hostOf(url) == GDOU_JW_HOST

    fun isGatewayJwUrl(url: String): Boolean =
        hostOf(url) == GDOU_WFW_HOST && url.removePrefix(GDOU_WFW_ORIGIN).startsWith("/https://$GDOU_JW_HOST")

    fun isJwUrl(url: String): Boolean = isJwHost(hostOf(url)) || isGatewayJwUrl(url)

    fun rewriteOfficialJwToGateway(url: String): String {
        if (!isOfficialJwUrl(url)) return url
        return "$GDOU_WFW_ORIGIN/${forceHttps(url)}"
    }

    fun pathOf(url: String): String {
        val afterScheme = url.substringAfter("://", missingDelimiterValue = url)
        val pathAndQuery = afterScheme.substringAfter("/", missingDelimiterValue = "")
        val path = pathAndQuery.substringBefore('?').substringBefore('#')
        return if (path.isEmpty()) "/" else "/$path"
    }

    fun isSsoPath(url: String): Boolean {
        val path = pathOf(url).removePrefix("/https://$GDOU_JW_HOST")
        return path == "/sso" || path.startsWith("/sso/")
    }

    fun isJwLoggedIn(url: String, html: String = ""): Boolean {
        if (!isJwUrl(url) || isSsoPath(url) || isReauth(url, html)) return false
        if (url.contains("/authserver/")) return false
        if (html.isBlank() || html.contains("pwdEncryptSalt")) return false
        return hasJwSessionMarker(html)
    }

    private fun hasJwSessionMarker(html: String): Boolean {
        if (html.isBlank()) return false
        return JW_SESSION_MARKERS.any { html.contains(it, ignoreCase = true) }
    }

    fun isReauth(url: String, html: String): Boolean {
        if (url.contains("reAuthLoginView") || url.contains("reAuthCheck")) return true
        return html.contains("reAuthParams") && html.contains("reAuthSubmit")
    }

    fun looksLikeLoginPage(url: String, html: String): Boolean {
        if (isReauth(url, html)) return true
        if (html.contains("pwdEncryptSalt") || html.contains("authserver/login")) return true
        if (url.contains("/authserver/login")) return true
        return isSsoPath(url)
    }

    fun htmlRedirect(html: String): String? {
        val meta = Regex(
            """http-equiv\s*=\s*["']refresh["'][^>]*content\s*=\s*["'][^"']*url\s*=\s*([^"';\s]+)""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)?.trim()
            ?: Regex(
                """content\s*=\s*["'][^"']*url\s*=\s*([^"';\s]+)["'][^>]*http-equiv\s*=\s*["']refresh["']""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.get(1)?.trim()
        if (!meta.isNullOrBlank()) return meta
        val js = Regex(
            """(?:window\.)?location(?:\.href)?\s*=\s*['"]([^'"]+)['"]"""
        ).find(html)?.groupValues?.get(1)?.trim()
        if (!js.isNullOrBlank() && !js.startsWith("javascript:", ignoreCase = true)) return js
        return null
    }

    fun forceHttps(url: String): String {
        listOf(GDOU_AUTH_HOST, GDOU_JW_HOST, GDOU_WFW_HOST).forEach { host ->
            val http = "http://$host"
            if (url.startsWith(http)) return "https://$host" + url.removePrefix(http)
        }
        return url
    }

    private fun gatewayEmbedPrefix(base: String): String? {
        if (!isGatewayJwUrl(base)) return null
        val originEnd = base.indexOf('/', base.indexOf("://") + 3)
        val origin = if (originEnd == -1) base else base.substring(0, originEnd)
        return "$origin/https://$GDOU_JW_HOST"
    }

    fun resolveUrl(base: String, location: String): String {
        if (location.startsWith("http://") || location.startsWith("https://")) return forceHttps(location)
        val originEnd = base.indexOf('/', base.indexOf("://") + 3)
        val origin = if (originEnd == -1) base else base.substring(0, originEnd)
        return if (location.startsWith("/") && !location.startsWith("//")) {
            (gatewayEmbedPrefix(base) ?: origin) + location
        } else if (location.startsWith("/")) {
            origin + location
        } else {
            val slash = base.lastIndexOf('/')
            forceHttps(base.substring(0, slash + 1) + location)
        }
    }
}
