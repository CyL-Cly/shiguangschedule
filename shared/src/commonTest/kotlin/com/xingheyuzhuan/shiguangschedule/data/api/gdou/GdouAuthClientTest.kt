package com.xingheyuzhuan.shiguangschedule.data.api.gdou

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GdouAuthClientTest {
    @Test
    fun signsSeparateWfwTicketAndKeepsGatewaySessionAcrossCookieRedirect() = runBlocking {
        val client = client(
            *initialLogin(),
            Step("/", body = PORTAL, cookie = "WFWSESSID=guest; Path=/; Secure"),
            Step(CAS, service = GDOU_WFW_HOME, location = WFW_TICKET, requestCookie = CAS_COOKIE),
            Step("/", location = GDOU_WFW_HOME, ticket = "wfw-ticket", requestCookie = WFW_COOKIE),
            Step("/", location = GDOU_WFW_HOME, cookie = "urlss=ready; Path=/; Secure", requestCookie = WFW_COOKIE),
            Step("/", body = PORTAL, requestCookie = "urlss=ready"),
            Step(MENU, body = MENU_PAGE, requestCookie = WFW_COOKIE)
        )
        assertEquals(GdouAuthResult.LoggedIn, client.login())
        assertEquals(GdouRoute.Gateway, client.http.jwRoute)
        client.finish()
    }

    @Test
    fun gatewayFailureUsesUnspentDirectJwTicket() = runBlocking {
        val client = client(
            *initialLogin(),
            Step("/", body = PORTAL, cookie = "WFWSESSID=guest; Path=/; Secure"),
            Step(CAS, service = GDOU_WFW_HOME, location = WFW_TICKET, requestCookie = CAS_COOKIE),
            Step("/", location = "https://$GDOU_AUTH_HOST$CAS", ticket = "wfw-ticket", requestCookie = WFW_COOKIE),
            *directExchange("synthetic")
        )
        assertEquals(GdouAuthResult.LoggedIn, client.login())
        assertEquals(GdouRoute.DirectJw, client.http.jwRoute)
        client.finish()
    }

    @Test
    fun gatewayDataFailureFallsBackWithFreshCasTicket() = runBlocking {
        val client = client(
            Step(CAS, service = GDOU_SERVICE_URL, location = "$GDOU_SERVICE_URL?ticket=fresh", requestCookie = CAS_COOKIE),
            Step("/sso/jziotlogin", host = GDOU_JW_HOST, ticket = "fresh", location = GDOU_JW_MENU,
                cookie = "JSESSIONID=direct; Path=/; Secure"),
            Step("/xtgl/index_initMenu.html", host = GDOU_JW_HOST, body = MENU_PAGE),
            Step("/xtgl/index_initMenu.html", host = GDOU_JW_HOST, body = MENU_PAGE)
        )
        client.http.cookieStore.restore(listOf(GdouCookie("CASTGC", "synthetic", GDOU_AUTH_HOST, "/authserver")))
        val attempted = mutableListOf<GdouRoute>()
        val result = GdouAuthClient(client.http).readJw {
            attempted.add(client.http.jwRoute)
            check(client.http.jwRoute == GdouRoute.DirectJw) { "教务会话已过期" }
            "course data"
        }
        assertEquals("course data", result)
        assertEquals(listOf(GdouRoute.Gateway, GdouRoute.DirectJw), attempted)
        client.finish()
    }

    @Test
    fun rejectsTicketForWrongServiceWithoutSendingIt() = runBlocking {
        val client = client(
            *initialLogin(),
            Step("/", body = PORTAL),
            Step(CAS, service = GDOU_WFW_HOME, location = "$GDOU_SERVICE_URL?ticket=wrong-service"),
            *directExchange("synthetic")
        )
        assertEquals(GdouAuthResult.LoggedIn, client.login())
        assertEquals(GdouRoute.DirectJw, client.http.jwRoute)
        client.finish()
    }

    @Test
    fun completesAdditionalMfaForTheWfwService() = runBlocking {
        val client = client(
            *initialLogin(),
            Step("/", body = PORTAL, cookie = "WFWSESSID=guest; Path=/; Secure"),
            Step(CAS, service = GDOU_WFW_HOME, location = MFA, requestCookie = CAS_COOKIE),
            Step(MFA, body = MFA_PAGE),
            Step(MFA_SUBMIT, body = """{"code":"reAuth_success"}""", method = HttpMethod.Post,
                formService = GDOU_WFW_HOME, referer = MFA),
            Step(CAS, service = GDOU_WFW_HOME, location = WFW_TICKET, requestCookie = CAS_COOKIE),
            Step("/", body = PORTAL, ticket = "wfw-ticket", requestCookie = WFW_COOKIE),
            Step(MENU, body = MENU_PAGE, requestCookie = WFW_COOKIE)
        )
        assertIs<GdouAuthResult.NeedMfa>(client.login())
        assertEquals(GdouAuthResult.LoggedIn, client.auth.submitMfaCode("123456"))
        assertEquals(GdouRoute.Gateway, client.http.jwRoute)
        client.finish()
    }

    @Test
    fun captchaStaysOnOfficialAuthenticationOrigin() = runBlocking {
        val client = client(
            Step(CAS, body = LOGIN_FORM),
            Step(CAPTCHA_CHECK, body = """{"isNeed":true}""", referer = CAS),
            Step("/authserver/getCaptcha.htl", body = "synthetic-image", referer = CAS)
        )
        val result = assertIs<GdouAuthResult.NeedCaptcha>(client.login())
        assertEquals("synthetic-image", result.imagePng.decodeToString())
        client.finish()
    }

    @Test
    fun casFormWithoutErrorDoesNotClaimSuccessOrBadPassword() = runBlocking {
        val client = client(
            Step(CAS, body = LOGIN_FORM),
            Step(CAPTCHA_CHECK, body = """{"isNeed":false}"""),
            Step(BFP),
            Step(CAS, body = LOGIN_FORM, method = HttpMethod.Post)
        )
        assertIs<GdouAuthResult.Failed>(client.login())
        client.finish()
    }

    @Test
    fun followsCasReauthOnOfficialAuthenticationOrigin() = runBlocking {
        val mfa = "/authserver/reAuthCheck/reAuthLoginView.do"
        val client = client(
            Step(CAS, body = LOGIN_FORM),
            Step(CAPTCHA_CHECK, body = """{"isNeed":false}"""),
            Step(BFP),
            Step(CAS, method = HttpMethod.Post, location = mfa),
            Step(mfa, body = """<script>
                var reAuthParams = {
                    "reAuthType": "4",
                    "reAuthUserId": "test-user"
                };
                </script>""".trimIndent())
        )
        assertIs<GdouAuthResult.NeedMfa>(client.login())
        client.finish()
    }

    @Test
    fun rewrittenSsoSelfRedirectDoesNotRepeatRequest() = runBlocking {
        val client = client(Step(SSO, location = GDOU_SERVICE_URL))
        assertEquals(302, client.http.followRedirects(GDOU_SERVICE_URL).status)
        client.finish()
    }

    @Test
    fun externalRedirectIsRejectedBeforeAnyRequestToIt() = runBlocking {
        val client = client(Step("$JW_ENTRY/", location = "//outside.example/cas"))
        assertFailsWith<IllegalArgumentException> { client.http.followRedirects("$GDOU_JW_BASE/") }
        client.finish()
    }

    @Test
    fun gatewayGuestCookieDoesNotProveJwSession() = runBlocking {
        val client = client(
            *initialLogin(),
            Step("/", body = PORTAL, cookie = "WFWSESSID=guest; Path=/; Secure"),
            Step(CAS, service = GDOU_WFW_HOME, location = WFW_TICKET),
            Step("/", body = PORTAL, ticket = "wfw-ticket", requestCookie = WFW_COOKIE),
            Step(MENU, body = LOGIN_FORM, requestCookie = WFW_COOKIE),
            Step("/sso/jziotlogin", body = LOGIN_FORM, host = GDOU_JW_HOST, ticket = "synthetic")
        )
        assertIs<GdouAuthResult.Failed>(client.login())
        client.finish()
    }

    @Test
    fun gatewayRootDoesNotRepeatWithoutCookie() = runBlocking {
        val client = client(Step("/", location = GDOU_WFW_HOME))
        assertEquals(302, client.http.followRedirects(GDOU_WFW_HOME).status)
        client.finish()
    }

    @Test
    fun gatewayRootMayRepeatOnlyOnceEvenIfServerKeepsSettingCookies() = runBlocking {
        val client = client(
            Step("/", location = GDOU_WFW_HOME, cookie = "urlss=one; Path=/"),
            Step("/", location = GDOU_WFW_HOME, cookie = "urlss=two; Path=/", requestCookie = "urlss=one")
        )
        assertEquals(302, client.http.followRedirects(GDOU_WFW_HOME).status)
        client.finish()
    }

    private fun initialLogin() = arrayOf(
        Step(CAS, body = LOGIN_FORM, service = GDOU_SERVICE_URL),
        Step(CAPTCHA_CHECK, body = """{"isNeed":false}""", referer = CAS),
        Step(BFP, referer = CAS),
        Step(CAS, location = TICKET, method = HttpMethod.Post, referer = CAS,
            service = GDOU_SERVICE_URL, cookie = "$CAS_COOKIE; Path=/authserver; Secure")
    )

    private fun directExchange(ticket: String) = arrayOf(
        Step("/sso/jziotlogin", host = GDOU_JW_HOST, ticket = ticket, location = GDOU_JW_MENU,
            cookie = "JSESSIONID=direct; Path=/; Secure"),
        Step("/xtgl/index_initMenu.html", host = GDOU_JW_HOST, body = MENU_PAGE, requestCookie = "JSESSIONID=direct"),
        Step("/xtgl/index_initMenu.html", host = GDOU_JW_HOST, body = MENU_PAGE, requestCookie = "JSESSIONID=direct")
    )

    private data class Step(
        val path: String,
        val body: String = "",
        val location: String? = null,
        val method: HttpMethod = HttpMethod.Get,
        val referer: String? = null,
        val service: String? = null,
        val cookie: String? = null,
        val host: String = if (path.startsWith("/authserver/")) GDOU_AUTH_HOST else GDOU_WFW_HOST,
        val ticket: String? = null,
        val requestCookie: String? = null,
        val formService: String? = null
    )

    private class ScriptedClient(private val steps: List<Step>) {
        private var index = 0
        private val engine = MockEngine { request ->
            val step = steps[index++]
            assertEquals(step.host, request.url.host)
            assertEquals(step.ticket, request.url.parameters["ticket"])
            val requestCookies = request.headers.getAll(HttpHeaders.Cookie).orEmpty().joinToString(";")
            step.requestCookie?.let { assertTrue(it in requestCookies) }
            if (step.host != GDOU_AUTH_HOST) assertTrue("CASTGC=" !in requestCookies)
            if (step.host != GDOU_WFW_HOST) assertTrue("WFWSESSID=" !in requestCookies)
            assertEquals(URLProtocol.HTTPS, request.url.protocol)
            assertEquals(step.path, request.url.encodedPath)
            assertEquals(step.method, request.method)
            step.service?.let { assertEquals(it, request.url.parameters["service"]) }
            step.referer?.let {
                val actual = Url(checkNotNull(request.headers[HttpHeaders.Referrer]))
                assertEquals(it, actual.encodedPath)
                assertEquals(step.host, actual.host)
            }
            if (step.method == HttpMethod.Post) {
                assertEquals(GdouRoute.Auth.origin, request.headers[HttpHeaders.Origin])
                val fields = parseQueryString((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                if (step.path == MFA_SUBMIT) {
                    assertEquals(step.formService, fields["service"])
                    assertEquals("123456", fields["dynamicCode"])
                } else {
                    assertEquals("e1s1", fields["execution"])
                    assertEquals("submit", fields["_eventId"])
                    assertEquals(USER, fields["username"])
                    assertTrue(fields["password"].orEmpty().isNotEmpty())
                    assertTrue(fields["password"] != PASSWORD)
                    assertEquals(null, fields["passwordText"])
                }
            }
            val status = if (step.location == null) HttpStatusCode.OK else HttpStatusCode.Found
            val headers = headersOf(*buildList {
                step.location?.let { add(HttpHeaders.Location to listOf(it)) }
                step.cookie?.let { add(HttpHeaders.SetCookie to listOf(it)) }
            }.toTypedArray())
            respond(step.body, status, headers)
        }
        val http = GdouHttpClient(engine)

        val auth = GdouAuthClient(http)

        suspend fun login(): GdouAuthResult = auth.login(USER, PASSWORD, "test-fingerprint")

        fun finish() {
            http.client.close()
            engine.close()
            assertEquals(steps.size, index, "expected request count")
        }
    }

    private fun client(vararg steps: Step) = ScriptedClient(steps.toList())

    private companion object {
        const val USER = "test-user"
        const val PASSWORD = "test-password"
        const val JW_ENTRY = "/https://$GDOU_JW_HOST"
        const val CAS = "/authserver/login"
        const val MFA = "/authserver/reAuthCheck/reAuthLoginView.do"
        const val MFA_SUBMIT = "/authserver/reAuthCheck/reAuthSubmit.do"
        const val MFA_PAGE = """<script>var reAuthParams = {
            "reAuthType":"4","reAuthUserId":"test-user"
        };</script>"""
        const val CAS_COOKIE = "CASTGC=synthetic"
        const val WFW_COOKIE = "WFWSESSID=guest"
        const val WFW_TICKET = "${GDOU_WFW_HOME}?ticket=wfw-ticket"
        const val PORTAL = """<title>Portal</title><a href="https://authserver.gdou.edu.cn/authserver/login">CAS</a>"""
        const val TICKET = "$GDOU_SERVICE_URL?ticket=synthetic"
        const val CAPTCHA_CHECK = "/authserver/checkNeedCaptcha.htl"
        const val BFP = "/authserver/bfp/info"
        const val SSO = "$JW_ENTRY/sso/jziotlogin"
        const val MENU = "$JW_ENTRY/xtgl/index_initMenu.html"
        const val LOGIN_FORM = """<input id="pwdEncryptSalt" value="0123456789ABCDEF"><input id="execution" value="e1s1">"""
        const val MENU_PAGE = """<div id="cdNav"></div>"""
    }
}