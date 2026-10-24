package com.xingheyuzhuan.shiguangschedule.widget

import com.xingheyuzhuan.shiguangschedule.data.db.widget.WidgetTimeSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeekGridPeriodsTest {

    private fun slot(number: Int, start: String, end: String) =
        WidgetTimeSlot(number = number, startTime = start, endTime = end)

    // 与应用内默认节次表一致的 8 节
    private val standardSlots = listOf(
        slot(1, "08:10", "08:55"),
        slot(2, "09:05", "09:50"),
        slot(3, "10:10", "10:55"),
        slot(4, "11:15", "12:00"),
        slot(5, "14:30", "15:15"),
        slot(6, "15:20", "16:05"),
        slot(7, "16:20", "17:05"),
        slot(8, "17:10", "17:55"),
    )

    @Test
    fun slots_rowsOnlyOccupiedSlots_andOwnRowAfterLastSlot() {
        val times = List(3) { "08:10" to "09:50" } +
            List(2) { "10:10" to "12:00" } +
            List(2) { "10:10" to "11:40" } +
            listOf("14:30" to "16:05") +
            listOf("19:30" to "21:05")

        assertEquals(
            listOf(
                "08:10" to "08:55",
                "10:10" to "10:55",
                "14:30" to "15:15",
                "19:30" to "21:05",
            ),
            WeekGridPeriods.buildRows(times, standardSlots, maxPeriods = 10)
        )
    }

    @Test
    fun slots_earlyEnd_mapsToStartSlotRow() {
        val rows = WeekGridPeriods.buildRows(
            listOf("10:10" to "12:00", "10:10" to "11:40"), standardSlots, maxPeriods = 10
        )
        // 早下课与标准课同属第 3 节行，不再因平局投票产生半空行
        assertEquals("10:10", WeekGridPeriods.mapRowStart("10:10" to "11:40", rows, standardSlots))
        assertEquals("10:10", WeekGridPeriods.mapRowStart("10:10" to "12:00", rows, standardSlots))
        assertEquals(listOf("10:10" to "10:55"), rows)
    }

    @Test
    fun slots_earlyStartInGap_snapsToNextSlotRow() {
        val rows = WeekGridPeriods.buildRows(listOf("10:10" to "12:00"), standardSlots, maxPeriods = 10)
        // 课间空隙（09:50-10:10）内早上课 → 吸附下一节（第 3 节）行
        assertEquals("10:10", WeekGridPeriods.mapRowStart("10:00" to "11:40", rows, standardSlots))
        assertEquals("10:10", WeekGridPeriods.mapRowStart("09:55" to "10:40", rows, standardSlots))
    }

    @Test
    fun slots_beforeFirstSlot_mapsToFirstSlotRow() {
        val rows = WeekGridPeriods.buildRows(listOf("08:10" to "09:50"), standardSlots, maxPeriods = 10)
        assertEquals("08:10", WeekGridPeriods.mapRowStart("07:00" to "08:00", rows, standardSlots))
    }

    @Test
    fun slots_afterLastSlot_keepsOwnRow() {
        val times = listOf("08:10" to "09:50", "19:30" to "21:05", "19:30" to "21:05")
        val rows = WeekGridPeriods.buildRows(times, standardSlots, maxPeriods = 10)
        assertTrue(("19:30" to "21:05") in rows)
        assertEquals("19:30", WeekGridPeriods.mapRowStart("19:30" to "21:05", rows, standardSlots))
    }

    @Test
    fun slots_crossSlotStart_mapsByStartTimeNotByFirstOverlappingSlot() {
        // (09:00,12:00) 覆盖第 1~4 节，但 09:00 落在第 1 节(08:10-08:55)之后的课间空隙，
        // 按吸附规则归属下一节（第 2 节，09:05），与应用内 timeToGridScale 的 find { start > time } 一致；
        // 若按「第一个重叠节次」则会错落到第 1 节。
        val rows = WeekGridPeriods.buildRows(listOf("09:00" to "12:00"), standardSlots, maxPeriods = 10)
        assertEquals(listOf("09:05" to "09:50"), rows)
        assertEquals("09:05", WeekGridPeriods.mapRowStart("09:00" to "12:00", rows, standardSlots))
    }

    @Test
    fun slots_rowTrimmedByMaxPeriods_fallsBackToLastRetainedRow() {
        // 上界裁剪掉后面行时，吸附结果不在 rows 中。渲染层只认 rows 内的行，
        // 若返回自身开始时间（同样不在 rows 中），整卡会整体消失；
        // 因此必须落到仍保留的、不晚于它的最后一行。
        val times = listOf("08:10" to "08:55", "10:10" to "10:55")
        val rows = WeekGridPeriods.buildRows(times, standardSlots, maxPeriods = 1)
        assertEquals(listOf("08:10" to "08:55"), rows)
        val rowStart = WeekGridPeriods.mapRowStart("10:10" to "10:55", rows, standardSlots)
        assertEquals("08:10", rowStart)
        assertTrue(rows.any { it.first == rowStart }, "归属行必须在 rows 中，否则整卡消失")
    }

    @Test
    fun emptySlots_fallsBackToVoting() {
        val times = List(3) { "10:20" to "12:00" } + listOf("10:10" to "11:40")
        assertEquals(
            WeekGridPeriods.canonicalPeriods(times, maxPeriods = 10),
            WeekGridPeriods.buildRows(times, emptyList(), maxPeriods = 10)
        )
        val rows = WeekGridPeriods.buildRows(times, emptyList(), maxPeriods = 10)
        // 兜底模式下被合并的早到变体归属其重叠的唯一行
        assertEquals("10:20", WeekGridPeriods.mapRowStart("10:10" to "11:40", rows, emptyList()))
    }


    @Test
    fun earlyStart_mergesIntoMajorityPeriod() {
        val times = listOf("10:20" to "12:00", "10:20" to "12:00", "10:20" to "12:00") +
            listOf("10:10" to "11:40", "10:10" to "11:40") +
            listOf("08:10" to "09:50", "14:30" to "16:05")

        val rows = WeekGridPeriods.canonicalPeriods(times, maxPeriods = 10)

        assertEquals(
            listOf("08:10" to "09:50", "10:20" to "12:00", "14:30" to "16:05"),
            rows
        )
        assertTrue(("10:10" to "11:40") !in rows)
    }

    @Test
    fun earlyEnd_mergesIntoMajorityPeriod() {
        val times = List(3) { "10:20" to "12:00" } + listOf("10:20" to "11:50")

        assertEquals(
            listOf("10:20" to "12:00"),
            WeekGridPeriods.canonicalPeriods(times, maxPeriods = 10)
        )
    }

    @Test
    fun gapCourse_keepsOwnRow() {
        val times = List(2) { "10:20" to "12:00" } + listOf("12:30" to "13:30")

        assertEquals(
            listOf("10:20" to "12:00", "12:30" to "13:30"),
            WeekGridPeriods.canonicalPeriods(times, maxPeriods = 10)
        )
    }

    @Test
    fun spansTwoCanonicalRows_keepsOwnRow() {
        val times = List(2) { "08:10" to "09:50" } +
            List(2) { "10:20" to "12:00" } +
            listOf("08:10" to "12:00")

        assertEquals(
            listOf("08:10" to "09:50", "08:10" to "12:00", "10:20" to "12:00"),
            WeekGridPeriods.canonicalPeriods(times, maxPeriods = 10)
        )
    }

    @Test
    fun allSameNonStandardTime_unchanged() {
        val times = List(4) { "10:10" to "11:40" }

        assertEquals(
            listOf("10:10" to "11:40"),
            WeekGridPeriods.canonicalPeriods(times, maxPeriods = 10)
        )
    }

    @Test
    fun empty_returnsEmpty() {
        assertEquals(emptyList(), WeekGridPeriods.canonicalPeriods(emptyList(), maxPeriods = 10))
    }

    @Test
    fun capsAtMaxPeriods() {
        val times = (0 until 12).map { hour ->
            "%02d:00".format(hour + 8) to "%02d:45".format(hour + 8)
        }

        assertEquals(10, WeekGridPeriods.canonicalPeriods(times, maxPeriods = 10).size)
    }
}
