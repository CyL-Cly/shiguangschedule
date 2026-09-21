package com.xingheyuzhuan.shiguangschedule.data.api.gdou

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
            println("skip: set GDOU_USERNAME and GDOU_PASSWORD to run live probe")
            return@runBlocking
        }
        val fingerprint = env("GDOU_FINGERPRINT").ifBlank {
            GdouPasswordEncryptor.randomString(32).uppercase()
        }
        val http = GdouHttpClient()
        http.hopSink = { exchange, cookies ->
            val cookieMeta = cookies.joinToString { "${it.name} domain=${it.domain} path=${it.path}" }
            println(
                "hop ${exchange.status} ${redact(exchange.url)} loc=${redact(exchange.location.orEmpty())} cookies=[$cookieMeta]"
            )
        }
        try {
            val auth = GdouAuthClient(http)
            var result = auth.login(username, password, fingerprint)
            result = completeMfaIfNeeded(auth, result)
            when (result) {
                GdouAuthResult.LoggedIn -> Unit
                is GdouAuthResult.NeedCaptcha -> {
                    println("skip: captcha required")
                    return@runBlocking
                }
                is GdouAuthResult.NeedMfa -> {
                    println("skip: MFA required, set GDOU_REAUTH_CODE after python login.py --send-only")
                    return@runBlocking
                }
                is GdouAuthResult.BadPassword -> fail(result.message)
                is GdouAuthResult.Network -> fail(result.message)
                is GdouAuthResult.Failed -> fail(result.message)
            }
            assertTrue(auth.isJwSessionAlive(), "jw session should be alive after login")
            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            val term = GdouKbListParser.inferTerm(now.year, now.month.number)
            val fetched = GdouScheduleClient(http).fetch(term)
            println("courses=${fetched.courses.size} start=${fetched.semester?.startDate}")
            assertTrue(fetched.courses.isNotEmpty(), "expected kbList courses")
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

    private fun redact(url: String): String =
        url.replace(Regex("(?i)(ticket|username|uid|verify|password|bfp)=[^&]*"), "$1=***")
}
