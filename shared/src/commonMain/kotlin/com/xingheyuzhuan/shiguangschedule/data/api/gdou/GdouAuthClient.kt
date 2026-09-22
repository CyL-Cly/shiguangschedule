package com.xingheyuzhuan.shiguangschedule.data.api.gdou

import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.annotation.Single

@Single
class GdouAuthClient(
    private val http: GdouHttpClient
) {
    var pendingReauth: GdouReauthParams? = null
        private set

    /** 连接类异常映射为中文消息，其余保留原文兜底；日志/消息严禁直接输出英文异常串。 */
    private fun toNetworkResult(error: Throwable): GdouAuthResult =
        GdouHttpClient.connectivityText(error)
            ?.let { GdouAuthResult.Network("网络连接失败：$it") }
            ?: GdouAuthResult.Network(error.message ?: "网络错误")

    suspend fun isJwSessionAlive(): Boolean = warmUp()

    suspend fun registerFingerprint(fingerprint: String, referer: String? = null) {
        val effectiveReferer = referer ?: GdouHtml.rewriteOfficialJwToGateway(GDOU_JW_BASE)
        http.get("$GDOU_JW_BASE/authserver/bfp/info", effectiveReferer) {
            parameter("bfp", fingerprint)
            parameter("_", fingerprint.take(12))
            header("X-Requested-With", "XMLHttpRequest")
            header(HttpHeaders.Accept, "application/json, text/javascript, */*; q=0.01")
        }
    }

    suspend fun login(
        username: String,
        password: String,
        fingerprint: String,
        captcha: String = "",
        fetchCaptchaIfNeeded: Boolean = true
    ): GdouAuthResult {
        return runCatching {
            // 登录入口 = 网关嵌入形态的 jw 根；outbound 后即 https://wfw.gdou.edu.cn/https://jw.gdou.edu.cn，
            // 未登录时 URL 不变、body 就是 CAS 登录页（就地含 salt/execution/lt）。
            val entry = GdouHtml.rewriteOfficialJwToGateway(GDOU_JW_BASE)
            var page = http.get(GDOU_JW_BASE)
            var salt = GdouHtml.attrValue(page.body, "pwdEncryptSalt")
            var execution = GdouHtml.attrValue(page.body, "execution")
            var lt = GdouHtml.attrValue(page.body, "lt")
            if (salt.isBlank() || execution.isBlank()) {
                dropSessionCookies()
                page = http.get(GDOU_JW_BASE)
                salt = GdouHtml.attrValue(page.body, "pwdEncryptSalt")
                execution = GdouHtml.attrValue(page.body, "execution")
                lt = GdouHtml.attrValue(page.body, "lt")
            }
            if (salt.isBlank() || execution.isBlank()) {
                return GdouAuthResult.Failed("登录页缺少 salt/execution")
            }
            if (fetchCaptchaIfNeeded && captcha.isBlank() && needCaptcha(username)) {
                val image = fetchCaptchaImage()
                return GdouAuthResult.NeedCaptcha(image)
            }
            registerFingerprint(fingerprint, entry)
            val payload = mapOf(
                "username" to username,
                "password" to GdouPasswordEncryptor.encrypt(password, salt),
                "captcha" to captcha,
                "_eventId" to "submit",
                "cllt" to "userNameLogin",
                "dllt" to "generalLogin",
                "lt" to lt,
                "execution" to execution
            )
            // 登录 POST 走网关根（非嵌套路径），与 wfw_login.py 实证一致。
            val first = http.postForm(
                url = GDOU_WFW_PROXY_AUTH_LOGIN + "service=" + encodeService(GDOU_SERVICE_URL),
                fields = payload,
                referer = entry,
                origin = GDOU_WFW_ORIGIN
            )
            val landed = if (first.status in 301..308 && !first.location.isNullOrBlank()) {
                http.followRedirects(first.location, entry)
            } else {
                first
            }
            classifyLanding(landed)
        }.getOrElse { toNetworkResult(it) }
    }

    suspend fun sendMfaCode(channel: GdouMfaChannel): GdouAuthResult {
        val params = pendingReauth ?: return GdouAuthResult.Failed("没有待处理的二次认证会话")
        return runCatching {
            val svc = params.service.ifBlank { GDOU_SERVICE_URL }
            val referer = params.referer.ifBlank { "$GDOU_WFW_ORIGIN/authserver/reAuthCheck/reAuthLoginView.do" }
            if (channel.reAuthType != params.reAuthType) {
                http.postForm(
                    url = "$GDOU_WFW_ORIGIN/authserver/reAuthCheck/changeReAuthType.do",
                    fields = mapOf(
                        "isMultifactor" to params.isMultifactor,
                        "reAuthType" to channel.reAuthType,
                        "service" to svc
                    ),
                    referer = referer,
                    origin = GDOU_WFW_ORIGIN,
                    ajax = true
                )
                pendingReauth = params.copy(reAuthType = channel.reAuthType)
            }
            val current = pendingReauth ?: params
            val sent = http.postForm(
                url = "$GDOU_WFW_ORIGIN/authserver/dynamicCode/getDynamicCodeByReauth.do",
                fields = mapOf(
                    "userName" to current.reAuthUserId,
                    "authCodeTypeName" to channel.authCodeTypeName
                ),
                referer = referer,
                origin = GDOU_WFW_ORIGIN,
                ajax = true
            )
            val obj = runCatching { http.parseJson(sent.body).jsonObject }.getOrNull()
            val res = obj?.get("res")?.jsonPrimitive?.contentOrNull
                ?: obj?.get("code")?.jsonPrimitive?.contentOrNull
                ?: ""
            if (res !in setOf("success", "wechat_success", "cpdaily_success", "1")) {
                val msg = obj?.get("returnMessage")?.jsonPrimitive?.contentOrNull
                    ?: obj?.get("msg")?.jsonPrimitive?.contentOrNull
                    ?: "发送验证码失败"
                return GdouAuthResult.Failed(msg)
            }
            val mobile = obj?.get("mobile")?.jsonPrimitive?.contentOrNull
                ?: obj?.get("data")?.jsonObject?.get("mobile")?.jsonPrimitive?.contentOrNull
            GdouAuthResult.NeedMfa(channel, mobile)
        }.getOrElse { toNetworkResult(it) }
    }

    suspend fun submitMfaCode(code: String): GdouAuthResult {
        val params = pendingReauth ?: return GdouAuthResult.Failed("没有待处理的二次认证会话")
        return runCatching {
            val svc = params.service.ifBlank { GDOU_SERVICE_URL }
            val referer = params.referer.ifBlank { "$GDOU_WFW_ORIGIN/authserver/reAuthCheck/reAuthLoginView.do" }
            val result = http.postForm(
                url = "$GDOU_WFW_ORIGIN/authserver/reAuthCheck/reAuthSubmit.do",
                fields = mapOf(
                    "service" to svc,
                    "reAuthType" to params.reAuthType,
                    "isMultifactor" to params.isMultifactor,
                    "password" to "",
                    "dynamicCode" to code.trim(),
                    "uuid" to "",
                    "answer1" to "",
                    "answer2" to "",
                    "otpCode" to "",
                    "skipTmpReAuth" to "true"
                ),
                referer = referer,
                origin = GDOU_WFW_ORIGIN,
                ajax = true
            )
            val obj = runCatching { http.parseJson(result.body).jsonObject }.getOrNull()
            val status = obj?.get("code")?.jsonPrimitive?.contentOrNull.orEmpty()
            if (status in setOf("reAuth_failed", "reAuth_unauthorized")) {
                return GdouAuthResult.Failed(obj?.get("msg")?.jsonPrimitive?.contentOrNull ?: status)
            }
            val sso = http.followRedirects(loginUrl(svc))
            classifyLanding(sso)
        }.getOrElse { toNetworkResult(it) }
    }

    private suspend fun dropSessionCookies() {
        http.cookieStore.removeByName("CASTGC")
        http.cookieStore.removeMatching { cookie ->
            cookie.name.equals("JSESSIONID", ignoreCase = true) &&
                GdouHtml.isJwHost(cookie.domain.orEmpty())
        }
        http.cookieStore.removeMatching { cookie ->
            cookie.domain.orEmpty().trimStart('.') == GDOU_WFW_HOST &&
                cookie.name in setOf("WFWSESSID", "urlss")
        }
    }

    private suspend fun needCaptcha(username: String): Boolean {
        val resp = http.get("$GDOU_JW_BASE/authserver/checkNeedCaptcha.htl", GdouHtml.rewriteOfficialJwToGateway(GDOU_JW_BASE)) {
            parameter("username", username)
            header(HttpHeaders.Accept, "application/json, text/plain, */*")
        }
        val obj = runCatching { http.parseJson(resp.body).jsonObject }.getOrNull() ?: return false
        val need = obj["isNeed"]?.jsonPrimitive ?: return false
        return need.contentOrNull.equals("true", ignoreCase = true) || need.contentOrNull == "1"
    }

    private suspend fun fetchCaptchaImage(): ByteArray {
        val ts = GdouPasswordEncryptor.randomString(8)
        val resp = http.getBytes("$GDOU_JW_BASE/authserver/getCaptcha.htl?ts=$ts", GdouHtml.rewriteOfficialJwToGateway(GDOU_JW_BASE))
        return resp.bytes ?: ByteArray(0)
    }

    private suspend fun classifyLanding(resp: GdouHttpExchange): GdouAuthResult {
        val current = leaveSso(resp)
        val url = current.location ?: current.url
        // a. 二次认证页
        if (GdouHtml.isReauth(url, current.body) || GdouHtml.isReauth(current.url, current.body)) {
            val parsed = GdouHtml.parseReauthParams(current.body)
            if (parsed.reAuthUserId.isBlank()) {
                return GdouAuthResult.Failed("二次认证页解析失败")
            }
            pendingReauth = parsed.copy(referer = GdouHtml.forceHttps(url))
            return GdouAuthResult.NeedMfa(GdouMfaChannel.fromReAuthType(parsed.reAuthType))
        }
        // b. 已进入教务页面（网关嵌入 URL 且带会话标记），warmUp 二次确认
        if (GdouHtml.isJwLoggedIn(current.url, current.body) && warmUp()) {
            pendingReauth = null
            return GdouAuthResult.LoggedIn
        }
        // c. 落地为 wfw 门户（非网关嵌入路径）且已建立 wfw 会话
        if (GdouHtml.hostOf(url) == GDOU_WFW_HOST && !GdouHtml.isGatewayJwUrl(url) &&
            http.cookieStore.get(Url(GDOU_WFW_HOME)).any { it.name.equals("WFWSESSID", ignoreCase = true) }
        ) {
            return if (warmUp()) {
                pendingReauth = null
                GdouAuthResult.LoggedIn
            } else {
                GdouAuthResult.Failed("统一认证已通过，但教务网关会话未建立")
            }
        }
        // d. CAS 登录页 / 错误页
        val pageError = GdouHtml.pageError(current.body)
        if (pageError.isNotBlank() ||
            current.body.contains("pwdEncryptSalt") ||
            current.url.contains("/authserver/login")
        ) {
            return GdouAuthResult.BadPassword(pageError.ifBlank { "账号或密码错误" })
        }
        // e. 其余
        if (GdouHtml.isSsoPath(current.url) || GdouHtml.isSsoPath(url)) {
            return GdouAuthResult.Failed("未完成教务 SSO（停在 ${GdouHtml.pathOf(current.url)}，HTTP ${current.status}）")
        }
        return GdouAuthResult.Failed("未进入教务（HTTP ${current.status}）")
    }

    /**
     * 仅当停在 sso 路径（含网关嵌入形态）且存在 30x/页面 meta 跳转时继续跟随；
     * outboundUrl 会保持网关路径。严禁再访问官方 jw 菜单（统一走网关，禁止直连官方域）。
     */
    private suspend fun leaveSso(resp: GdouHttpExchange): GdouHttpExchange {
        var current = resp
        if (GdouHtml.isSsoPath(current.url)) {
            val next = current.location ?: GdouHtml.htmlRedirect(current.body)
            if (!next.isNullOrBlank()) {
                current = http.followRedirects(GdouHtml.resolveUrl(current.url, next), current.url)
            }
        }
        return current
    }

    /** 网关会话探测：未登录时网关就地返回 CAS 登录页，isJwLoggedIn=false，无副作用。 */
    private suspend fun warmUp(): Boolean {
        return runCatching {
            val resp = http.followRedirects(GdouHtml.rewriteOfficialJwToGateway(GDOU_JW_MENU))
            resp.status == 200 && GdouHtml.isJwLoggedIn(resp.url, resp.body)
        }.getOrDefault(false)
    }

    private fun encodeService(service: String): String {
        val encoded = buildString {
            service.forEach { ch ->
                when (ch) {
                    ':' -> append("%3A")
                    '/' -> append("%2F")
                    else -> append(ch)
                }
            }
        }
        return encoded
    }

    /** CAS 登录 URL 直接构造在 wfw 网关根上，全程不向 authserver 官方域发请求。 */
    private fun loginUrl(service: String = GDOU_SERVICE_URL): String =
        GDOU_WFW_PROXY_AUTH_LOGIN + "service=" + encodeService(service)
}
