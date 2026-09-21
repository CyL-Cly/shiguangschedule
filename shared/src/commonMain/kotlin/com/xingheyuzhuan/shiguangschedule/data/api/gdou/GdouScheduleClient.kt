package com.xingheyuzhuan.shiguangschedule.data.api.gdou

import com.xingheyuzhuan.shiguangschedule.data.model.CourseImportExport.ImportCourseJsonModel
import org.koin.core.annotation.Single

data class GdouFetchedSchedule(
    val courses: List<ImportCourseJsonModel>,
    val semester: GdouSemesterInfo?
)

@Single
class GdouScheduleClient(
    private val http: GdouHttpClient
) {
    suspend fun fetch(term: GdouTerm): GdouFetchedSchedule {
        val index = http.get(GDOU_KB_INDEX, referer = GDOU_KB_INDEX)
        val indexLoc = index.location
        val indexPage = if (index.status in 301..308 && !indexLoc.isNullOrBlank()) {
            http.followRedirects(indexLoc, GDOU_KB_INDEX)
        } else {
            index
        }
        if (GdouHtml.looksLikeLoginPage(indexPage.url, indexPage.body) ||
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
            referer = GDOU_KB_INDEX,
            ajax = true
        )
        if (courseResp.status != 200) {
            val loginLost = GdouHtml.looksLikeLoginPage(courseResp.url, courseResp.body)
            error(
                buildString {
                    append("课表请求失败：HTTP ${courseResp.status}")
                    append("（${courseResp.url}）")
                    if (loginLost || courseResp.status == 901) append("，登录已失效")
                }
            )
        }
        if (GdouHtml.looksLikeLoginPage(courseResp.url, courseResp.body)) {
            error("教务会话已过期")
        }
        val courses = GdouKbListParser.parseCourses(courseResp.body)
        val semester = runCatching { fetchSemester(term) }.getOrNull()
        return GdouFetchedSchedule(courses, semester)
    }

    private suspend fun fetchSemester(term: GdouTerm): GdouSemesterInfo? {
        val body = "xnm=${encode(term.xnm)}&xqm=${encode(term.xqm)}"
        val resp = http.postRaw(
            url = "$GDOU_JW_BASE/kbcx/xskbcxZccx_cxZcByXnxq.html?gnmkdm=N2154",
            body = body,
            contentType = "application/x-www-form-urlencoded;charset=UTF-8",
            referer = GDOU_KB_INDEX,
            ajax = true
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
