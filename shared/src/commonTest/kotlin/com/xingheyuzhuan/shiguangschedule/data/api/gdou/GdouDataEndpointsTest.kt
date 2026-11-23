package com.xingheyuzhuan.shiguangschedule.data.api.gdou

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GdouDataEndpointsTest {
    @Test
    fun scheduleUsesObservedGatewayIndexAndAjaxRequest() = runBlocking {
        var requests = 0
        val http = GdouHttpClient(MockEngine { request ->
            assertEquals(GDOU_WFW_HOST, request.url.host)
            when (++requests) {
                1 -> {
                    assertEquals(GDOU_KB_INDEX, request.url.toString())
                    assertEquals(HttpMethod.Get, request.method)
                    respond("<script>var sessionUserKey = '';</script>")
                }
                2 -> {
                    assertEquals("$GDOU_WFW_GATEWAY_PREFIX/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151", request.url.toString())
                    assertAjax(request, GDOU_KB_INDEX)
                    val form = parseQueryString((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                    assertEquals("2026", form["xnm"])
                    assertEquals("3", form["xqm"])
                    assertEquals("ck", form["kzlx"])
                    respond("""{"kbList":[]}""")
                }
                3 -> {
                    assertEquals("N2154", request.url.parameters["gnmkdm"])
                    respond("{}", HttpStatusCode.NotFound)
                }
                else -> error("Unexpected request")
            }
        })
        try {
            assertEquals(emptyList(), GdouScheduleClient(http).fetch(GdouTerm("2026", "3")).courses)
            assertEquals(3, requests)
        } finally {
            http.client.close()
        }
    }

    @Test
    fun examsFollowObservedPaginationFieldsAndRequestShape() = runBlocking {
        var requests = 0
        val http = GdouHttpClient(MockEngine { request ->
            assertEquals(GDOU_WFW_HOST, request.url.host)
            if (++requests == 1) {
                assertEquals(GdouHtml.rewriteOfficialJwToGateway(GDOU_EXAM_INDEX), request.url.toString())
                assertEquals(HttpMethod.Get, request.method)
                respond("<title>Exam page</title>")
            } else {
                assertEquals(GdouHtml.rewriteOfficialJwToGateway(GDOU_EXAM_QUERY), request.url.toString())
                assertAjax(request, GdouHtml.rewriteOfficialJwToGateway(GDOU_EXAM_INDEX))
                val form = parseQueryString((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                assertEquals("2026", form["xnm"])
                assertEquals("3", form["xqm"])
                assertEquals("15", form["queryModel.showCount"])
                val page = requests - 1
                assertEquals(page.toString(), form["queryModel.currentPage"])
                respond("""{"totalCount":2,"totalResult":2,"totalPage":2,"items":[{"kcmc":"Course $page","kssj":"2026-12-0$page 09:00-11:00"}]}""")
            }
        })
        try {
            val exams = GdouExamClient(http).fetch(GdouTerm("2026", "3"))
            assertEquals(listOf("Course 1", "Course 2"), exams.map { it.courseName })
            assertEquals(3, requests)
        } finally {
            http.client.close()
        }
    }

    @Test
    fun examsDoNotQueryWhenIndexRedirectsToAuthentication() = runBlocking {
        var requests = 0
        val http = GdouHttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            when (++requests) {
                1 -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/authserver/login"))
                2 -> respond("<input id='pwdEncryptSalt'>")
                else -> error("Exam POST must be skipped")
            }
        })
        try {
            assertFailsWith<IllegalStateException> { GdouExamClient(http).fetch(GdouTerm("2026", "3")) }
            assertEquals(2, requests)
        } finally {
            http.client.close()
        }
    }

    private fun assertAjax(request: HttpRequestData, referer: String) {
        assertEquals(HttpMethod.Post, request.method)
        assertEquals(referer, request.headers[HttpHeaders.Referrer])
        assertEquals(GDOU_WFW_ORIGIN, request.headers[HttpHeaders.Origin])
        assertEquals("XMLHttpRequest", request.headers["X-Requested-With"])
    }
}
