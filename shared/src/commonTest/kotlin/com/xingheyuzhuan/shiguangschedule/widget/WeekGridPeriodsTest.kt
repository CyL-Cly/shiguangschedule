package com.xingheyuzhuan.shiguangschedule.widget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeekGridPeriodsTest {

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
