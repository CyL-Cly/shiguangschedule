package com.xingheyuzhuan.shiguangschedule.data.api.gdou

import com.xingheyuzhuan.shiguangschedule.data.model.CourseImportExport.ImportCourseJsonModel
import org.koin.core.annotation.Single

data class GdouFetchedSchedule(
    val courses: List<ImportCourseJsonModel>,
    val semester: GdouSemesterInfo?,
    /** 解析阶段被丢弃的记录说明，用于同步日志诊断课程缺失 */
    val skipped: List<String> = emptyList()
)

@Single
class GdouScheduleClient(
    private val http: GdouHttpClient
) {
    suspend fun fetch(term: GdouTerm): GdouFetchedSchedule {
        val route = http.jwRoute
        val indexUrl = if (route == GdouRoute.Gateway) GDOU_KB_INDEX
            else GDOU_JW_BASE + GDOU_KB_INDEX.removePrefix(GDOU_WFW_ORIGIN)
        val indexPage = http.followRedirects(indexUrl, route = route)
        if (indexPage.status != 200 || GdouHtml.looksLikeLoginPage(indexPage.url, indexPage.body) ||
            !GdouHtml.isJwLoggedIn(indexPage.url, indexPage.body)
        ) {
            error("教务会话已过期")
        }
        val courseBody = buildString {
            append("xnm=").append(encode(term.xnm))
            append("&xqm=").append(encode(term.xqm))
            append("&kzlx=ck&xsdm=&kclbdm=&kclxdm=")
        }
        val courseResp = http.postRaw(
            url = GDOU_KB_JSON,
            body = courseBody,
            contentType = "application/x-www-form-urlencoded;charset=UTF-8",
            referer = indexPage.url,
            ajax = true,
            origin = route.origin,
            route = route
        )
        if (courseResp.status != 200) {
            val loginLost = GdouHtml.looksLikeLoginPage(courseResp.url, courseResp.body)
            error(
                buildString {
                    append("课表请求失败：HTTP ${courseResp.status}")
                    if (loginLost || courseResp.status == 901) append("，登录已失效")
                }
            )
        }
        if (GdouHtml.looksLikeLoginPage(courseResp.url, courseResp.body)) {
            error("教务会话已过期")
        }
        val (courses, skipped) = GdouKbListParser.parseCoursesWithDiagnostics(courseResp.body)
        val semester = try {
            fetchSemester(term, indexPage.url, route)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        return GdouFetchedSchedule(courses, semester, skipped)
    }

    private suspend fun fetchSemester(term: GdouTerm, referer: String, route: GdouRoute): GdouSemesterInfo? {
        val body = "xnm=${encode(term.xnm)}&xqm=${encode(term.xqm)}"
        val resp = http.postRaw(
            url = "$GDOU_JW_BASE/kbcx/xskbcxZccx_cxZcByXnxq.html?gnmkdm=N2154",
            body = body,
            contentType = "application/x-www-form-urlencoded;charset=UTF-8",
            referer = referer,
            ajax = true,
            route = route
        )
        if (resp.status != 200) return null
        return GdouKbListParser.parseSemesterInfo(resp.body)
    }

    private fun encode(value: String): String = buildString {
        value.forEach { ch ->
            when {
                ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-._~" -> append(ch)
                else -> append('%').append(ch.code.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
}
