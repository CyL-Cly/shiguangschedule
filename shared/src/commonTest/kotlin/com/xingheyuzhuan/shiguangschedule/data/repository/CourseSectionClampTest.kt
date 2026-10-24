package com.xingheyuzhuan.shiguangschedule.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals

class CourseSectionClampTest {

    private val bounds = 1 to 10

    @Test
    fun withinRange_isUnchanged() {
        assertEquals(5 to 6, CourseSectionClamp.clamp(5, 6, bounds))
        assertEquals(1 to 2, CourseSectionClamp.clamp(1, 2, bounds))
        assertEquals(9 to 10, CourseSectionClamp.clamp(9, 10, bounds))
    }

    /**
     * 越界节次原本会在渲染层被静默丢弃。
     * 收敛后必须落在 [1,10] 内，且保持课程跨度（11-12 不应被压成单节）。
     */
    @Test
    fun aboveUpperBound_shiftsIntoRangeKeepingSpan() {
        assertEquals(9 to 10, CourseSectionClamp.clamp(11, 12, bounds))
        assertEquals(10 to 10, CourseSectionClamp.clamp(11, 11, bounds))
        // 跨度超过容量时退化为整段覆盖
        assertEquals(1 to 10, CourseSectionClamp.clamp(11, 25, bounds))
    }

    @Test
    fun belowLowerBound_shiftsIntoRangeKeepingSpan() {
        assertEquals(1 to 2, CourseSectionClamp.clamp(-2, -1, bounds))
        assertEquals(1 to 1, CourseSectionClamp.clamp(0, 0, bounds))
    }

    @Test
    fun partiallyOverlapping_clampsEachEdge() {
        // 起点合法、终点越界
        assertEquals(9 to 10, CourseSectionClamp.clamp(9, 14, bounds))
        // 起点越界、终点合法
        assertEquals(1 to 3, CourseSectionClamp.clamp(-4, 3, bounds))
    }

    /** 夹取后反转时必须退化，不能产生 end < start 的非法数据。 */
    @Test
    fun invertedAfterClamp_degradesToSingleSection() {
        val (s, e) = CourseSectionClamp.clamp(12, 11, bounds)
        assertEquals(s, e)
    }

    /** 时间段表为空时不应擅自改动，交由渲染层兜底。 */
    @Test
    fun nullBounds_passesThrough() {
        assertEquals(11 to 12, CourseSectionClamp.clamp(11, 12, null))
    }

    @Test
    fun nullSection_passesThrough() {
        assertEquals(null to 6, CourseSectionClamp.clamp(null, 6, bounds))
        assertEquals(5 to null, CourseSectionClamp.clamp(5, null, bounds))
    }

    /** 非 1 起始的时间段表也要正确处理。 */
    @Test
    fun nonOneBasedBounds_areRespected() {
        val b = 3 to 8
        assertEquals(3 to 4, CourseSectionClamp.clamp(1, 2, b))
        assertEquals(7 to 8, CourseSectionClamp.clamp(11, 12, b))
        assertEquals(5 to 6, CourseSectionClamp.clamp(5, 6, b))
    }
}
