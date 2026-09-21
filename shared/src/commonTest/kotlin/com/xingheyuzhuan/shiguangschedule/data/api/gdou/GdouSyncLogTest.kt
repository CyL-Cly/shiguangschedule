package com.xingheyuzhuan.shiguangschedule.data.api.gdou

import com.xingheyuzhuan.shiguangschedule.data.model.CourseImportExport
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GdouSyncLogEntryCompatTest {

    @Test
    fun decode_legacyJsonWithoutDetail() {
        // 旧版本落库的 JSON 没有 detail 字段，必须能正常反序列化
        val legacy = """
            [
              {"atMillis":1727001083000,"success":true,"message":"已导入 3 门课","source":"manual"},
              {"atMillis":1727000000000,"success":false,"message":"网络错误","source":"auto"}
            ]
        """.trimIndent()
        val entries = CourseImportExport.json.decodeFromString<List<GdouSyncLogEntry>>(legacy)
        assertEquals(2, entries.size)
        assertNull(entries[0].detail)
        assertEquals("已导入 3 门课", entries[0].message)
        assertNull(entries[1].detail)
        assertEquals("auto", entries[1].source)
    }

    @Test
    fun decode_newJsonWithDetail() {
        val raw = """
            [
              {
                "atMillis":1727001083000,
                "success":true,
                "message":"已导入 3 门课",
                "source":"manual",
                "detail":"09:51:20 [info] 打开教务 jw.gdou.edu.cn\n09:51:21 [error] 课表接口返回异常"
              }
            ]
        """.trimIndent()
        val entries = CourseImportExport.json.decodeFromString<List<GdouSyncLogEntry>>(raw)
        assertEquals(
            "09:51:20 [info] 打开教务 jw.gdou.edu.cn\n09:51:21 [error] 课表接口返回异常",
            entries[0].detail
        )
    }

    @Test
    fun roundTrip_keepsDetail() {
        val entry = GdouSyncLogEntry(
            atMillis = 1727001083000L,
            success = false,
            message = "同步失败",
            source = "auto",
            detail = "09:51:23 [error] 同步失败"
        )
        val encoded = CourseImportExport.json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(GdouSyncLogEntry.serializer()),
            listOf(entry)
        )
        val decoded = CourseImportExport.json.decodeFromString<List<GdouSyncLogEntry>>(encoded)
        assertEquals(entry, decoded.single())
    }
}

class GdouSyncLogCollectorTest {

    private val utc = TimeZone.UTC

    @Test
    fun formatLine_usesHhmmssAndLevelTag() {
        // UTC 1970-01-01T09:51:23Z
        assertEquals(
            "09:51:23 [info] 打开教务 jw.gdou.edu.cn",
            GdouSyncLogCollector.formatLine(35483000L, GdouSyncLogLevel.INFO, "打开教务 jw.gdou.edu.cn", utc)
        )
        assertEquals(
            "00:00:01 [error] 同步失败",
            GdouSyncLogCollector.formatLine(1000L, GdouSyncLogLevel.ERROR, "同步失败", utc)
        )
    }

    @Test
    fun collector_joinsLinesWithNewlineInOrder() {
        var now = 0L
        val collector = GdouSyncLogCollector(utc) { now }
        now = 0L
        collector.info("打开教务 jw.gdou.edu.cn")
        now = 1000L
        collector.error("同步失败")
        assertEquals(
            "00:00:00 [info] 打开教务 jw.gdou.edu.cn\n00:00:01 [error] 同步失败",
            collector.detailOrNull()
        )
    }

    @Test
    fun collector_emptyReturnsNull() {
        val collector = GdouSyncLogCollector(utc) { 0L }
        assertNull(collector.detailOrNull())
    }

    @Test
    fun collector_ignoresBlankText() {
        val collector = GdouSyncLogCollector(utc) { 0L }
        collector.info("   ")
        assertNull(collector.detailOrNull())
    }
}
