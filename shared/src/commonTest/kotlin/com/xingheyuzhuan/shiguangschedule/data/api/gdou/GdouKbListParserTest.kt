package com.xingheyuzhuan.shiguangschedule.data.api.gdou

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GdouKbListParserTest {
    @Test
    fun parseWeeks_rangeOddEvenAndComma() {
        assertEquals((1..16).toList(), GdouKbListParser.parseWeeks("1-16周"))
        assertEquals(listOf(1, 3, 5, 7), GdouKbListParser.parseWeeks("1-8周(单)").filter { it <= 7 })
        assertEquals(listOf(1, 2, 3, 10, 11), GdouKbListParser.parseWeeks("1-3周,10-11周"))
        assertEquals(listOf(6), GdouKbListParser.parseWeeks("6周"))
        assertTrue(GdouKbListParser.parseWeeks("1-4周(双)").all { it % 2 == 0 })
    }

    @Test
    fun parseSectionRange_plainAndChinese() {
        assertEquals(1 to 2, GdouKbListParser.parseSectionRange("1-2"))
        assertEquals(3 to 3, GdouKbListParser.parseSectionRange("3"))
        assertEquals(1 to 2, GdouKbListParser.parseSectionRange("第1-2节"))
        assertNull(GdouKbListParser.parseSectionRange(""))
    }

    @Test
    fun yangjiangCustomTime_onlyNonShensi34() {
        val other = GdouKbListParser.applyYangjiangCustomTime(
            course(position = "教学楼101", start = 3, end = 4)
        )
        assertTrue(other.isCustomTime)
        assertEquals("10:10", other.customStartTime)
        assertEquals("11:40", other.customEndTime)

        val shensi = GdouKbListParser.applyYangjiangCustomTime(
            course(position = "慎思楼A201", start = 3, end = 4)
        )
        assertFalse(shensi.isCustomTime)
        assertNull(shensi.customStartTime)

        val other12 = GdouKbListParser.applyYangjiangCustomTime(
            course(position = "教学楼101", start = 1, end = 2)
        )
        assertFalse(other12.isCustomTime)
    }

    @Test
    fun parseCourses_kbListAndMerge() {
        val json = """
            {"kbList":[
              {"kcmc":"高等数学","xm":"张三","cdmc":"慎思楼101","xqj":"1","jcs":"1-2","zcd":"1-8周"},
              {"kcmc":"高等数学","xm":"张三","cdmc":"慎思楼101","xqj":"1","jcs":"3-4","zcd":"1-8周"},
              {"kcmc":"（公选）摄影","xm":"李四","cdmc":"实验楼201","xqj":"2","jcs":"3-4","zcd":"1-16周"}
            ]}
        """.trimIndent()
        val courses = GdouKbListParser.parseCourses(json)
        assertEquals(2, courses.size)
        val math = courses.first { it.name == "高等数学" }
        assertEquals(1, math.startSection)
        assertEquals(4, math.endSection)
        assertFalse(math.isCustomTime)
        val photo = courses.first { it.name.startsWith("摄影") }
        assertEquals("摄影(公选)", photo.name)
        assertTrue(photo.isCustomTime)
        assertEquals("10:10", photo.customStartTime)
    }

    @Test
    fun inferTerm_septemberIsFirst() {
        val fall = GdouKbListParser.inferTerm(2026, 9)
        assertEquals("2026", fall.xnm)
        assertEquals("3", fall.xqm)
        val spring = GdouKbListParser.inferTerm(2026, 3)
        assertEquals("2025", spring.xnm)
        assertEquals("12", spring.xqm)
    }

    @Test
    fun normalizeStartDate_andSemester() {
        assertEquals("2026-09-01", GdouKbListParser.normalizeStartDate("2026年9月1日"))
        val info = GdouKbListParser.parseSemesterInfo(
            """[{"zs":"1","zrq":"2026-09-07"},{"zs":"2","zrq":"2026-09-14"}]"""
        )
        assertEquals("2026-09-07", info?.startDate)
        assertEquals(2, info?.totalWeeks)
    }

    @Test
    fun htmlHelpers() {
        val html = """<input id="pwdEncryptSalt" value="VZiVkGRaRIWMt7ea"/><input id="execution" value="e1s1"/>"""
        assertEquals("VZiVkGRaRIWMt7ea", GdouHtml.attrValue(html, "pwdEncryptSalt"))
        assertEquals("e1s1", GdouHtml.attrValue(html, "execution"))
        assertEquals(
            "https://authserver.gdou.edu.cn/authserver/login",
            GdouHtml.forceHttps("http://authserver.gdou.edu.cn/authserver/login")
        )
        assertEquals(
            "https://jw.gdou.edu.cn/sso/jziotlogin",
            GdouHtml.forceHttps("http://jw.gdou.edu.cn/sso/jziotlogin")
        )
        assertTrue(GdouHtml.isSsoPath("https://jw.gdou.edu.cn/sso/jziotlogin?ticket=ST-1"))
        assertFalse(GdouHtml.isJwLoggedIn("https://jw.gdou.edu.cn/sso/jziotlogin?ticket=ST-1"))
        assertFalse(GdouHtml.isJwLoggedIn("https://jw.gdou.edu.cn/xtgl/index_initMenu.html?jsdm=xs"))
        assertTrue(
            GdouHtml.isJwLoggedIn(
                "https://jw.gdou.edu.cn/xtgl/index_initMenu.html?jsdm=xs",
                """<script>var sessionUserKey = "u1";</script>"""
            )
        )
        assertEquals(
            "/xtgl/index_initMenu.html",
            GdouHtml.pathOf("https://jw.gdou.edu.cn/xtgl/index_initMenu.html?jsdm=xs")
        )
        assertEquals(
            "https://jw.gdou.edu.cn/xtgl/index_initMenu.html?jsdm=xs",
            GdouHtml.htmlRedirect("""<script>window.location.href='https://jw.gdou.edu.cn/xtgl/index_initMenu.html?jsdm=xs'</script>""")
        )
        assertTrue(GdouHtml.looksLikeLoginPage("https://jw.gdou.edu.cn/sso/jziotlogin", ""))
        assertFalse(GdouHtml.isSsoPath("https://jw.gdou.edu.cn/xtgl/index_initMenu.html"))
    }

    private fun course(position: String, start: Int, end: Int) =
        com.xingheyuzhuan.shiguangschedule.data.model.CourseImportExport.ImportCourseJsonModel(
            name = "课",
            teacher = "师",
            position = position,
            day = 1,
            startSection = start,
            endSection = end,
            weeks = listOf(1)
        )
}
