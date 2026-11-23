package com.xingheyuzhuan.shiguangschedule.data.api.gdou

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class GdouExamParserTest {
    @Test
    fun parse_readsJqGridItemsAndNormalizesDateTime() {
        val exams = GdouExamParser.parse(
            """
            {
              "records": 2,
              "total": 1,
              "items": [
                {
                  "kcmc": "高等数学",
                  "ksmc": "2026-2027-1期末统考（阳江校区）",
                  "kssj": "2027-01-12 14:30-16:10",
                  "cdmc": "慎思楼A203",
                  "cdxqmc": "阳江校区",
                  "zwh": "18",
                  "ksbz": "携带学生证"
                },
                {
                  "kcmc": "大学英语",
                  "ksrq": "2027/1/10",
                  "kssj": "09:00-11:00",
                  "cdjc": "A101",
                  "xqmc": "湖光校区"
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(listOf("大学英语", "高等数学"), exams.map { it.courseName })
        assertEquals("2027-01-10", exams[0].date)
        assertEquals("09:00-11:00", exams[0].time)
        assertEquals("A101", exams[0].location)
        assertEquals("2027-01-12", exams[1].date)
        assertEquals("14:30-16:10", exams[1].time)
        assertEquals("慎思楼A203", exams[1].location)
        assertEquals("18", exams[1].seat)
        assertEquals("携带学生证", exams[1].note)
    }

    @Test
    fun parse_supportsRowsAndNestedDataAndSkipsIncompleteRows() {
        val fromRows = GdouExamParser.parse("""{"rows":[{"kch":"A01","ksrq":"2026年12月30日","kssj":"08:10-09:50"}]}""")
        assertEquals("A01", fromRows.single().courseName)
        assertEquals("2026-12-30", fromRows.single().date)

        val fromData = GdouExamParser.parse("""{"data":{"items":[{"kcmc":"操作系统"}]}}""")
        assertEquals("操作系统", fromData.single().courseName)
        assertTrue(fromData.single().date.isEmpty())

        assertTrue(GdouExamParser.parse("""{"items":[null,{"ksmc":"无课程名"}]}""").isEmpty())
    }

    @Test
    fun parse_rejectsMissingList() {
        assertFails { GdouExamParser.parse("""{"records":0}""") }
        assertFails { GdouExamParser.parse("not json") }
    }

    @Test
    fun examEndpoints_defineOfficialTeachingPaths() {
        assertTrue(GDOU_EXAM_INDEX.startsWith(GDOU_JW_BASE))
        assertTrue(GDOU_EXAM_QUERY.startsWith(GDOU_JW_BASE))
        assertEquals(
            "$GDOU_JW_BASE/kwgl/kscx_cxXsksxxIndex.html?doType=query&gnmkdm=N358105",
            GDOU_EXAM_QUERY
        )
    }

    @Test
    fun fetchAllExamPages_requestsEveryPageAndSortsCombinedResult() = runBlocking {
        val requestedPages = mutableListOf<Int>()
        val result = fetchAllExamPages { page ->
            requestedPages += page
            when (page) {
                1 -> GdouExamPage(
                    exams = List(100) { index -> exam("后续课程$index") },
                    rowCount = 100,
                    records = 101,
                    totalPages = 2
                )
                2 -> GdouExamPage(
                    exams = listOf(exam("最早考试", "2026-01-01")),
                    rowCount = 1,
                    records = 101,
                    totalPages = 2
                )
                else -> error("Unexpected page $page")
            }
        }

        assertEquals(listOf(1, 2), requestedPages)
        assertEquals(101, result.size)
        assertEquals("最早考试", result.first().courseName)
    }

    @Test
    fun fetchAllExamPages_usesActualRowCountWhenServerReturnsShortPages() = runBlocking {
        val requestedPages = mutableListOf<Int>()
        val result = fetchAllExamPages { page ->
            requestedPages += page
            val count = if (page == 1) 40 else 20
            GdouExamPage(
                exams = List(count) { index -> exam("课程$page-$index") },
                rowCount = count,
                records = 60,
                totalPages = null
            )
        }

        assertEquals(listOf(1, 2), requestedPages)
        assertEquals(60, result.size)
    }

    @Test
    fun fetchAllExamPages_rejectsIncompleteEmptyPage() {
        assertFails {
            runBlocking {
                fetchAllExamPages {
                    GdouExamPage(
                        exams = emptyList(),
                        rowCount = 0,
                        records = 1,
                        totalPages = 2
                    )
                }
            }
        }
    }

    @Test
    fun fetchAllExamPages_withoutMetadataRequestsUntilShortPage() = runBlocking {
        val requestedPages = mutableListOf<Int>()
        val result = fetchAllExamPages { page ->
            requestedPages += page
            val count = if (page == 1) EXAM_PAGE_SIZE else 0
            GdouExamPage(
                exams = List(count) { index -> exam("课程$index") },
                rowCount = count,
                records = null,
                totalPages = null
            )
        }

        assertEquals(listOf(1, 2), requestedPages)
        assertEquals(EXAM_PAGE_SIZE, result.size)
    }

    @Test
    fun fetchAllExamPages_propagatesLaterPageFailureWithoutPartialResult() = runBlocking {
        val requestedPages = mutableListOf<Int>()
        assertFails {
            runBlocking {
                fetchAllExamPages { page ->
                    requestedPages += page
                    if (page == 2) error("second page failed")
                    GdouExamPage(
                        exams = List(100) { index -> exam("课程$index") },
                        rowCount = 100,
                        records = 101,
                        totalPages = 2
                    )
                }
            }
        }
        assertEquals(listOf(1, 2), requestedPages)
    }

    private fun exam(courseName: String, date: String = "2027-01-01") = GdouExam(
        courseName = courseName,
        examName = "期末",
        date = date,
        time = "09:00-11:00",
        location = "A101",
        campus = "湖光校区",
        seat = "1",
        note = ""
    )
}
