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

    /** 全角括号 / 后置单双周等写法必须同样生效，否则课程会退化为每周都上并相互覆盖。 */
    @Test
    fun parseWeeks_oddEvenVariantsAreHonored() {
        val expectedOdd = (1..15).filter { it % 2 != 0 }
        listOf(
            "1-15周(单)",
            "1-15周（单）",
            "1-15单周",
            "1-15周单",
            "1-15周(单周)"
        ).forEach { raw ->
            assertEquals(expectedOdd, GdouKbListParser.parseWeeks(raw), "单周写法解析错误: $raw")
        }
        val expectedEven = (1..15).filter { it % 2 == 0 }
        listOf("1-15周(双)", "1-15周（双）", "1-15双周").forEach { raw ->
            assertEquals(expectedEven, GdouKbListParser.parseWeeks(raw), "双周写法解析错误: $raw")
        }
    }

    /** 分号、顿号、空格分隔的多段周次不应被截断。 */
    @Test
    fun parseWeeks_mixedSeparatorsAreNotTruncated() {
        val expected = (1..8).toList() + (10..16).toList()
        listOf("1-8周;10-16周", "1-8周；10-16周", "1-8周 10-16周", "1-8周、10-16周").forEach { raw ->
            assertEquals(expected, GdouKbListParser.parseWeeks(raw), "多段周次解析错误: $raw")
        }
    }

    /**
     * 教务系统对单周课程返回「第8周」这类带「第」前缀的写法。
     * 旧正则要求数字开头，会整行解析失败并静默丢弃课程（线上实际丢了「劳动教育2」）。
     */
    @Test
    fun parseWeeks_leadingDiPrefixIsAccepted() {
        assertEquals(listOf(8), GdouKbListParser.parseWeeks("第8周"))
        assertEquals(listOf(1), GdouKbListParser.parseWeeks("第1周"))
        assertEquals(listOf(15), GdouKbListParser.parseWeeks("第15周"))
        assertEquals((1..16).toList(), GdouKbListParser.parseWeeks("第1-16周"))
        assertEquals((9..10).toList(), GdouKbListParser.parseWeeks("第9-10周"))
        assertEquals(listOf(1, 2, 3, 10, 11), GdouKbListParser.parseWeeks("第1-3周,第10-11周"))
        assertEquals(
            (1..15).filter { it % 2 != 0 },
            GdouKbListParser.parseWeeks("第1-15周(单)")
        )
    }

    /** 真实教务返回样本：带「第」前缀的单周课程必须能完整导入。 */
    @Test
    fun parseCourses_realGdouSample_laborCourseIsKept() {
        val json = """
            {"kbList":[
              {"kcmc":"劳动教育2","xm":"王五","cdmc":"实训中心","xqj":"3","jcs":"3-4","zcd":"第8周"},
              {"kcmc":"羽毛球","xm":"李四","cdmc":"体育馆","xqj":"2","jcs":"7-8","zcd":"第6周"}
            ]}
        """.trimIndent()
        val (courses, skipped) = GdouKbListParser.parseCoursesWithDiagnostics(json)
        assertTrue(skipped.isEmpty(), "不应再有被丢弃的记录: $skipped")
        assertEquals(2, courses.size)
        val labor = courses.first { it.name == "劳动教育2" }
        assertEquals(3, labor.day)
        assertEquals(3, labor.startSection)
        assertEquals(4, labor.endSection)
        assertEquals(listOf(8), labor.weeks)
    }

    @Test
    fun parseSectionRange_plainAndChinese() {
        assertEquals(1 to 2, GdouKbListParser.parseSectionRange("1-2"))
        assertEquals(3 to 3, GdouKbListParser.parseSectionRange("3"))
        assertEquals(1 to 2, GdouKbListParser.parseSectionRange("第1-2节"))
        assertNull(GdouKbListParser.parseSectionRange(""))
    }

    /** 逗号连写的多段节次不能被判为非法，否则整行课程会被丢弃（表现为少一节）。 */
    @Test
    fun parseSectionSegments_multiSegmentForms() {
        assertEquals(listOf(5 to 6, 7 to 8), GdouKbListParser.parseSectionSegments("5-6,7-8"))
        assertEquals(listOf(5 to 6, 7 to 8), GdouKbListParser.parseSectionSegments("第5-6节、第7-8节"))
        assertEquals(listOf(1 to 2), GdouKbListParser.parseSectionSegments("1-2"))
        assertTrue(GdouKbListParser.parseSectionSegments("").isEmpty())
    }

    /** 多段节次被拆成多条后，合并阶段应重新拼成连续区间，而不是丢课或拆成多块。 */
    @Test
    fun parseCourses_multiSegmentSectionsAreMergedNotDropped() {
        val json = """
            {"kbList":[
              {"kcmc":"劳动教育","xm":"王五","cdmc":"实训中心","xqj":"3","jcs":"5-6,7-8","zcd":"1-16周"}
            ]}
        """.trimIndent()
        val courses = GdouKbListParser.parseCourses(json)
        assertEquals(1, courses.size, "多段节次被误拆或丢弃")
        val labor = courses.single()
        assertEquals("劳动教育", labor.name)
        assertEquals(5, labor.startSection)
        assertEquals(8, labor.endSection)
        assertEquals((1..16).toList(), labor.weeks)
    }

    /** 诊断信息应指出被跳过的记录及原因。 */
    @Test
    fun parseCoursesWithDiagnostics_reportsSkippedRows() {
        val json = """
            {"kbList":[
              {"kcmc":"正常课","xm":"张三","cdmc":"慎思楼101","xqj":"1","jcs":"1-2","zcd":"1-8周"},
              {"kcmc":"缺周次","xm":"张三","cdmc":"慎思楼101","xqj":"1","jcs":"3-4","zcd":""}
            ]}
        """.trimIndent()
        val (courses, skipped) = GdouKbListParser.parseCoursesWithDiagnostics(json)
        assertEquals(1, courses.size)
        assertEquals(1, skipped.size)
        assertTrue(skipped.single().contains("缺周次"), "诊断信息应包含课程名: ${skipped.single()}")
        assertTrue(skipped.single().contains("周次"), "诊断信息应说明原因: ${skipped.single()}")
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
