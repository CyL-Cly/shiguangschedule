package com.xingheyuzhuan.shiguangschedule.data.api.gdou

import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.annotation.Single

@Single
class GdouAuthClient(
    private val http: GdouHttpClient
) {
    private data class PendingReauth(val params: GdouReauthParams, val route: GdouRoute)

    private var pendingReauth: PendingReauth? = null

    private fun toNetworkResult(error: Throwable): GdouAuthResult {
        if (error is CancellationException) throw error
        return GdouHttpClient.connectivityText(error)
            ?.let { GdouAuthResult.Network("网络连接失败：$it") }
            ?: GdouAuthResult.Network("教务网关请求失败")
    }

    suspend fun isJwSessionAlive(): Boolean {
        if (warmUp(GdouRoute.Gateway)) return true
        return reuseDirectSession()
    }

    private suspend fun reuseDirectSession(): Boolean =
        http.cookieStore.get(Url(GDOU_JW_MENU)).any { it.name == "JSESSIONID" } &&
            warmUp(GdouRoute.DirectJw)

    /** 仅对读取教务数据的操作回退一次，不重发账号密码。 */
    internal suspend fun <T> readJw(read: suspend () -> T): T {
        val initialRoute = http.jwRoute
        try {
            return read()
        } catch (error: CancellationException) {
            throw error
        } catch (gatewayError: Exception) {
            if (initialRoute != GdouRoute.Gateway || !recoverDirectSession()) throw gatewayError
            return read()
        }
    }

    private suspend fun recoverDirectSession(): Boolean = try {
        reuseDirectSession() || (
            http.cookieStore.get(Url(loginUrl())).any { it.name == "CASTGC" } &&
                freshDirectSession()
            )
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    private suspend fun freshDirectSession(): Boolean {
        val refreshed = http.followRedirects(loginUrl(GdouRoute.DirectJw), route = GdouRoute.Auth)
        val ticket = refreshed.serviceTicket(GdouRoute.DirectJw) ?: return false
        return exchangeTicket(ticket, GdouRoute.DirectJw)
    }

    private suspend fun registerFingerprint(fingerprint: String, referer: String) {
        http.get("$GDOU_AUTH_BASE/bfp/info", referer, GdouRoute.Auth) {
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
            pendingReauth = null
            if (captcha.isBlank()) http.cookieStore.removeMatching { it.domain == GDOU_AUTH_HOST }
            val page = http.followRedirects(loginUrl(), route = GdouRoute.Auth)
            page.serviceTicket(GdouRoute.DirectJw)?.let { return establishJwSession(it) }
            if (page.status == 429 || GdouHtml.isReauth(page.url, page.body)) return classifyAuth(page)

            val salt = GdouHtml.attrValue(page.body, "pwdEncryptSalt")
            val execution = GdouHtml.attrValue(page.body, "execution")
            if (page.status != 200 || salt.isBlank() || execution.isBlank()) {
                return GdouAuthResult.Failed("未取得统一认证表单（HTTP ${page.status}）")
            }
            if (fetchCaptchaIfNeeded && captcha.isBlank() && needCaptcha(username, page.url)) {
                val image = fetchCaptchaImage(page.url)
                return GdouAuthResult.NeedCaptcha(image)
            }
            registerFingerprint(fingerprint, page.url)
            val payload = mapOf(
                "username" to username,
                "password" to GdouPasswordEncryptor.encrypt(password, salt),
                "captcha" to captcha,
                "_eventId" to "submit",
                "cllt" to "userNameLogin",
                "dllt" to "generalLogin",
                "lt" to GdouHtml.attrValue(page.body, "lt"),
                "execution" to execution
            )
            val first = http.postForm(
                url = loginUrl(),
                fields = payload,
                referer = page.url,
                origin = GdouRoute.Auth.origin,
                route = GdouRoute.Auth
            )
            classifyAuth(http.followRedirects(first, route = GdouRoute.Auth))
        }.getOrElse { toNetworkResult(it) }
    }

    suspend fun sendMfaCode(channel: GdouMfaChannel): GdouAuthResult {
        val pending = pendingReauth ?: return GdouAuthResult.Failed("没有待处理的二次认证会话")
        val params = pending.params
        return runCatching {
            val svc = casService(pending.route)
            val referer = params.referer.ifBlank { "$GDOU_AUTH_BASE/reAuthCheck/reAuthLoginView.do" }
            if (channel.reAuthType != params.reAuthType) {
                http.postForm(
                    url = "$GDOU_AUTH_BASE/reAuthCheck/changeReAuthType.do",
                    fields = mapOf(
                        "isMultifactor" to params.isMultifactor,
                        "reAuthType" to channel.reAuthType,
                        "service" to svc
                    ),
                    referer = referer,
                    origin = GdouRoute.Auth.origin,
                    route = GdouRoute.Auth,
                    ajax = true
                )
                pendingReauth = pending.copy(params = params.copy(reAuthType = channel.reAuthType))
            }
            val current = pendingReauth?.params ?: params
            val sent = http.postForm(
                url = "$GDOU_AUTH_BASE/dynamicCode/getDynamicCodeByReauth.do",
                fields = mapOf(
                    "userName" to current.reAuthUserId,
                    "authCodeTypeName" to channel.authCodeTypeName
                ),
                referer = referer,
                origin = GdouRoute.Auth.origin,
                route = GdouRoute.Auth,
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
        val pending = pendingReauth ?: return GdouAuthResult.Failed("没有待处理的二次认证会话")
        val params = pending.params
        return runCatching {
            val svc = casService(pending.route)
            val referer = params.referer.ifBlank { "$GDOU_AUTH_BASE/reAuthCheck/reAuthLoginView.do" }
            val result = http.postForm(
                url = "$GDOU_AUTH_BASE/reAuthCheck/reAuthSubmit.do",
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
                origin = GdouRoute.Auth.origin,
                route = GdouRoute.Auth,
                ajax = true
            )
            val obj = runCatching { http.parseJson(result.body).jsonObject }.getOrNull()
            val status = obj?.get("code")?.jsonPrimitive?.contentOrNull.orEmpty()
            if (status in setOf("reAuth_failed", "reAuth_unauthorized")) {
                return GdouAuthResult.Failed(obj?.get("msg")?.jsonPrimitive?.contentOrNull ?: status)
            }
            classifyAuth(http.followRedirects(loginUrl(pending.route), route = GdouRoute.Auth), pending.route)
        }.getOrElse { toNetworkResult(it) }
    }

    private fun GdouHttpExchange.serviceTicket(route: GdouRoute): String? {
        val target = location?.let { runCatching { Url(it) }.getOrNull() } ?: return null
        val service = Url(casService(route))
        return location.takeIf {
            isRedirect && target.protocol == URLProtocol.HTTPS && target.port == 443 && target.user == null &&
                target.host == service.host && target.encodedPath.ifEmpty { "/" } == service.encodedPath.ifEmpty { "/" } &&
                !target.parameters["ticket"].isNullOrBlank()
        }
    }

    private suspend fun establishJwSession(directTicket: String): GdouAuthResult {
        pendingReauth = null
        // 和 WFW 门户的「一键登录」一致：先启动网关会话，再用已有 CAS 登录申请 WFW 自己的票据。
        val gatewayCas = try {
            http.followRedirects(GDOU_WFW_HOME, route = GdouRoute.Gateway)
            http.followRedirects(loginUrl(GdouRoute.Gateway), route = GdouRoute.Auth)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (gatewayCas != null) {
            val gatewayTicket = gatewayCas.serviceTicket(GdouRoute.Gateway)
            if (gatewayTicket != null && exchangeTicket(gatewayTicket, GdouRoute.Gateway)) {
                return GdouAuthResult.LoggedIn
            }
            if (GdouHtml.isReauth(gatewayCas.url, gatewayCas.body)) {
                val mfa = rememberReauth(gatewayCas, GdouRoute.Gateway)
                if (mfa is GdouAuthResult.NeedMfa) return mfa
            }
        }
        // JW 票据从未发送给 WFW。只在校园网回退时用它一次。
        return if (exchangeTicket(directTicket, GdouRoute.DirectJw)) GdouAuthResult.LoggedIn
        else gatewayLoginFailed()
    }

    private fun gatewayLoginFailed() =
        GdouAuthResult.Failed("统一认证已通过，wfw 与校园网直连均未建立教务会话")

    private suspend fun exchangeTicket(ticket: String, route: GdouRoute): Boolean = try {
        val landing = http.followRedirects(ticket, route = route)
        // WFW 门户返回的是网关主页；是否有教务授权以实际菜单为准，不以门户 HTML 或 Cookie 名判断。
        landing.status == 200 && (route == GdouRoute.Gateway || !GdouHtml.looksLikeLoginPage(landing.url, landing.body)) &&
            warmUp(route)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    private suspend fun needCaptcha(username: String, referer: String): Boolean {
        val resp = http.get("$GDOU_AUTH_BASE/checkNeedCaptcha.htl", referer, GdouRoute.Auth) {
            parameter("username", username)
            header("X-Requested-With", "XMLHttpRequest")
            header(HttpHeaders.Accept, "application/json, text/plain, */*")
        }
        val obj = runCatching { http.parseJson(resp.body).jsonObject }.getOrNull() ?: return false
        val need = obj["isNeed"]?.jsonPrimitive ?: return false
        return need.contentOrNull.equals("true", ignoreCase = true) || need.contentOrNull == "1"
    }

    private suspend fun fetchCaptchaImage(referer: String): ByteArray {
        val ts = GdouPasswordEncryptor.randomString(8)
        val resp = http.getBytes("$GDOU_AUTH_BASE/getCaptcha.htl?ts=$ts", referer, GdouRoute.Auth)
        return resp.bytes ?: ByteArray(0)
    }

    private suspend fun classifyAuth(
        current: GdouHttpExchange,
        route: GdouRoute = GdouRoute.DirectJw
    ): GdouAuthResult {
        current.serviceTicket(route)?.let { ticket ->
            if (route == GdouRoute.DirectJw) return establishJwSession(ticket)
            pendingReauth = null
            return if (exchangeTicket(ticket, GdouRoute.Gateway) || recoverDirectSession()) GdouAuthResult.LoggedIn
            else gatewayLoginFailed()
        }
        if (current.status == 429) {
            return GdouAuthResult.Failed("登录请求过于频繁，请稍后再试")
        }
        if (GdouHtml.isReauth(current.url, current.body)) return rememberReauth(current, route)
        val pageError = GdouHtml.pageError(current.body)
        if (pageError.isNotBlank()) return GdouAuthResult.BadPassword(pageError)
        val isCasLogin = GdouHtml.pathOf(current.url) == "/authserver/login"
        if (current.body.contains("pwdEncryptSalt") || (isCasLogin && current.status == 200)) {
            return GdouAuthResult.Failed("统一认证返回登录页，未建立教务会话")
        }
        return GdouAuthResult.Failed("统一认证未签发教务票据（HTTP ${current.status}）")
    }

    private fun rememberReauth(current: GdouHttpExchange, route: GdouRoute): GdouAuthResult {
        val parsed = GdouHtml.parseReauthParams(current.body)
        if (parsed.reAuthUserId.isBlank()) return GdouAuthResult.Failed("二次认证页解析失败")
        pendingReauth = PendingReauth(parsed.copy(referer = current.url), route)
        return GdouAuthResult.NeedMfa(GdouMfaChannel.fromReAuthType(parsed.reAuthType))
    }

    private suspend fun warmUp(route: GdouRoute): Boolean = try {
        val resp = http.followRedirects(GDOU_JW_MENU, route = route)
        val valid = resp.status == 200 && GdouHtml.isJwLoggedIn(resp.url, resp.body)
        if (valid) http.jwRoute = route
        valid
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    private fun casService(route: GdouRoute): String = when (route) {
        GdouRoute.Gateway -> GDOU_WFW_HOME
        GdouRoute.DirectJw -> GDOU_SERVICE_URL
        GdouRoute.Auth -> error("CAS 票据必须指定教务服务")
    }

    private fun loginUrl(route: GdouRoute = GdouRoute.DirectJw): String =
        "$GDOU_AUTH_BASE/login?service=${casService(route).encodeURLParameter()}"
}
