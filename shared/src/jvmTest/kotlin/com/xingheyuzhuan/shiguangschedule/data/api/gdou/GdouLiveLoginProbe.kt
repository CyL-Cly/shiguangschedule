package com.xingheyuzhuan.shiguangschedule.data.api.gdou

import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock

class GdouLiveLoginProbe {
    @Test
    fun loginAndFetchSchedule() = runBlocking {
        val username = env("GDOU_USERNAME")
        val password = env("GDOU_PASSWORD")
        if (username.isBlank() || password.isBlank()) {
            check(env("GDOU_REQUIRE_LIVE_LOGIN") != "1") { "missing live probe credentials" }
            println("skip: set GDOU_USERNAME and GDOU_PASSWORD to run live probe")
            return@runBlocking
        }
        val fingerprint = env("GDOU_FINGERPRINT").ifBlank {
            GdouPasswordEncryptor.randomString(32).uppercase()
        }
        val http = GdouHttpClient()
        val actualUrls = mutableListOf<Url>()
        http.hopSink = { exchange, cookies ->
            actualUrls += Url(exchange.url)
            val cookieMeta = cookies.joinToString { "${it.name} domain=${it.domain} path=${it.path}" }
            println(
                "hop ${exchange.status} ${redact(exchange.url)} loc=${redact(exchange.location.orEmpty())} cookies=[$cookieMeta]"
            )
        }
        try {
            val auth = GdouAuthClient(http)
            var result = auth.login(username, password, fingerprint)
            result = completeMfaIfNeeded(auth, result)
            assertOfficialRoutes(actualUrls)
            when (result) {
                GdouAuthResult.LoggedIn -> println("auth=LoggedIn")
                is GdouAuthResult.NeedCaptcha -> fail("auth=NeedCaptcha")
                is GdouAuthResult.NeedMfa -> fail("auth=NeedMfa")
                is GdouAuthResult.BadPassword -> fail("auth=BadPassword")
                is GdouAuthResult.Network -> fail("auth=Network")
                is GdouAuthResult.Failed -> fail("auth=Failed")
            }
            assertTrue(auth.isJwSessionAlive(), "jw session should be alive after login")
            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            val term = GdouKbListParser.inferTerm(now.year, now.month.number)
            val fetched = auth.readJw { GdouScheduleClient(http).fetch(term) }
            println("courses=${fetched.courses.size} start=${fetched.semester?.startDate}")
            assertTrue(fetched.courses.isNotEmpty(), "expected kbList courses")
            val exams = auth.readJw { GdouExamClient(http).fetch(term) }
            println("exams=${exams.size} route=${http.jwRoute}")
            assertOfficialRoutes(actualUrls)
        } finally {
            http.client.close()
        }
    }

    private suspend fun completeMfaIfNeeded(
        auth: GdouAuthClient,
        first: GdouAuthResult
    ): GdouAuthResult {
        if (first !is GdouAuthResult.NeedMfa) return first
        val code = env("GDOU_REAUTH_CODE")
        if (code.isBlank()) return first
        val sent = auth.sendMfaCode(first.channel)
        if (sent is GdouAuthResult.Failed || sent is GdouAuthResult.Network) return sent
        return auth.submitMfaCode(code)
    }

    private fun env(name: String): String = System.getenv(name).orEmpty().trim()

    private fun assertOfficialRoutes(urls: List<Url>) {
        assertTrue(urls.isNotEmpty(), "expected live HTTP hops")
        assertTrue(urls.all {
            it.protocol == URLProtocol.HTTPS && it.port == 443 &&
                it.host in setOf(GDOU_AUTH_HOST, GDOU_WFW_HOST, GDOU_JW_HOST)
        }, "all outgoing HTTP hops must use official HTTPS endpoints")
    }

    private fun redact(value: String): String = if (value.isBlank()) "" else runCatching {
        val url = Url(value)
        "${url.protocol.name}://${url.host}${url.encodedPath}"
    }.getOrDefault("[unparseable URL]")
}
