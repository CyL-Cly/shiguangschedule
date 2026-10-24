package com.xingheyuzhuan.shiguangschedule.data.repository

/**
 * 课程节次越界收敛工具。
 *
 * 背景：渲染层 `WeeklyScheduleViewModel.mergeCourses` 通过
 * `timeSlots.find { it.number == course.startSection }` 精确定位时间段，
 * 一旦匹配不到就 `return@mapNotNull null`，该课程会被**静默丢弃**、不产生任何提示。
 *
 * 因此导入阶段必须保证入库的节次落在时间段表覆盖范围内，否则用户会看到
 * 「导入成功，但课表上少了一门课」这种极难排查的现象。
 */
object CourseSectionClamp {

    /**
     * 将 [start]..[end] 收敛到 [bounds] 覆盖的节次区间。
     *
     * 处理策略：
     * - [bounds] 为空（时间段表尚未建立）时原样返回，交由渲染层兜底；
     * - 整段高于上界时整体平移到最后一段，保留课程跨度而不是压成单节；
     * - 整段低于下界时整体前移到第一段，同理保留跨度；
     * - 部分重叠时逐端夹取，若夹取后反转则退化为单节。
     *
     * @param bounds 合法节次范围（最小节次, 最大节次）
     * @return 收敛后的 (start, end)
     */
    fun clamp(
        start: Int?,
        end: Int?,
        bounds: Pair<Int, Int>?
    ): Pair<Int?, Int?> {
        if (bounds == null) return start to end
        if (start == null || end == null) return start to end
        val (minSlot, maxSlot) = bounds
        if (minSlot > maxSlot) return start to end

        // 整段高于上界：整体平移到最后一段，保留跨度
        if (start > maxSlot) {
            val span = (end - start).coerceAtLeast(0)
            val newEnd = maxSlot
            val newStart = (maxSlot - span).coerceAtLeast(minSlot)
            return newStart to newEnd
        }
        // 整段低于下界：整体前移到第一段，保留跨度
        if (end < minSlot) {
            val span = (end - start).coerceAtLeast(0)
            val newStart = minSlot
            val newEnd = (minSlot + span).coerceAtMost(maxSlot)
            return newStart to newEnd
        }

        val clampedStart = start.coerceIn(minSlot, maxSlot)
        val clampedEnd = end.coerceIn(minSlot, maxSlot)
        return if (clampedEnd < clampedStart) clampedStart to clampedStart
        else clampedStart to clampedEnd
    }
}
