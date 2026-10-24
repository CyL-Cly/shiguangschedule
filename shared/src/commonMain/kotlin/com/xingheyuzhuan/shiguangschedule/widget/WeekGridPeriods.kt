package com.xingheyuzhuan.shiguangschedule.widget

import com.xingheyuzhuan.shiguangschedule.data.db.widget.WidgetTimeSlot

/**
 * 周网格小组件的时间轴行合并。
 *
 * 行基准优先取节次表（与应用内网格 [com.xingheyuzhuan.shiguangschedule.ui.schedule.WeeklyScheduleViewModel]
 * 的 SECTION_MODE 落位规则一致）：课程开始时间落在某节内 → 该节行；落在课间空隙 → 吸附下一节行；
 * 早于首节 → 首节行；晚于末节结束 → 按开始时间归组的独立行。
 * 因此「早上课 / 早下课」的自定义时间课程就近并入标准节次行，不再单独新开一行。
 *
 * 节次表缺失时兜底：行集合按本周课程的原始 (start, end) 投票得出，
 * 出现次数最多的时段视为标准节次，只与其中一行重叠的变体并入该行。
 *
 * 刻意不支持样式的 TIME_24H_MODE：小组件只有 [maxPeriods] 行可见，
 * 24 小时刻度会产生大量空行把课程挤出视口，故小组件的时间轴恒为节次基准。
 *
 * 时间均为 "HH:mm" 文本，字典序即时间序，比较前不做解析。
 */
object WeekGridPeriods {

    /**
     * 时间轴行集合：只保留有课落入的行，按开始时间排序，上限 [maxPeriods]。
     *
     * @param times 本周课程的 (start, end) 列表
     * @param slots 节次表；为空时退化为 [canonicalPeriods] 投票兜底
     */
    fun buildRows(
        times: List<Pair<String, String>>,
        slots: List<WidgetTimeSlot>,
        maxPeriods: Int,
    ): List<Pair<String, String>> {
        if (times.isEmpty() || maxPeriods <= 0) return emptyList()
        if (slots.isEmpty()) return canonicalPeriods(times, maxPeriods)

        val sortedSlots = slots.sortedBy { it.number }
        val slotRows = linkedMapOf<String, Pair<String, String>>()
        val ownRowEnds = mutableMapOf<String, String>()
        for (time in times) {
            val slot = slotForStart(time.first, sortedSlots)
            if (slot != null) {
                slotRows[slot.startTime] = slot.startTime to slot.endTime
            } else {
                // 末节之后的课程（如晚课）：按开始时间归组独立成行
                ownRowEnds[time.first] = maxOf(ownRowEnds[time.first] ?: time.second, time.second)
            }
        }
        val rows = slotRows.values + ownRowEnds.map { (start, end) -> start to end }
        return rows.sortedWith(compareBy({ it.first }, { it.second })).take(maxPeriods)
    }

    /**
     * 课程的时间轴行归属（行开始时间）。
     *
     * 节次表存在时复刻应用内 SECTION_MODE 吸附规则（见 [slotForStart]），
     * 末节之后者归属自身开始时间的独立行；
     * 节次表缺失时取与其时段重叠的唯一投票行，重叠多行或零行则归属自身开始时间。
     *
     * 返回值**必须**是 [rows] 中实际存在的行：渲染层只有「归属行 == 本行」与
     * 「归属行早于本行且未下课」两个出口，返回 rows 之外的时间点会让课程整卡消失。
     * 因此吸附/自身时间被 [maxPeriods] 截断时，退化为仍保留的、不晚于它的最后一行。
     */
    fun mapRowStart(
        time: Pair<String, String>,
        rows: List<Pair<String, String>>,
        slots: List<WidgetTimeSlot>,
    ): String {
        if (rows.isEmpty()) return time.first
        val rowStarts = rows.mapTo(mutableSetOf()) { it.first }
        val snapped = slotForStart(time.first, slots)?.startTime ?: run {
            // 节次表存在但晚于末节结束 → 独立行；节次表缺失 → 重叠的唯一投票行
            if (slots.isNotEmpty()) return@run time.first
            val hits = rows.filter { overlaps(time, it) }
            if (hits.size == 1) hits[0].first else time.first
        }
        if (snapped in rowStarts) return snapped
        // 吸附到的行（或自身时间）被上界裁掉：并入仍保留的最后一行，避免整卡消失
        return rows.lastOrNull { it.first <= snapped }?.first ?: rows.first().first
    }

    /**
     * 开始时间吸附到节次（与应用内 timeToGridScale 的 SECTION_MODE 规则一致）：
     * - 早于或等于首节开始 → 首节；
     * - 落在某节 [startTime, endTime] 内（含端点）→ 该节；
     * - 落在课间空隙 → 下一节；
     * - 晚于或等于末节结束 → null（由调用方分配独立行）。
     */
    fun slotForStart(start: String, slots: List<WidgetTimeSlot>): WidgetTimeSlot? {
        if (slots.isEmpty()) return null
        val sortedSlots = slots.sortedBy { it.number }
        if (start <= sortedSlots.first().startTime) return sortedSlots.first()
        if (start >= sortedSlots.last().endTime) return null
        return sortedSlots.find { start in it.startTime..it.endTime }
            ?: sortedSlots.find { it.startTime > start }
    }

    fun canonicalPeriods(
        times: List<Pair<String, String>>,
        maxPeriods: Int,
    ): List<Pair<String, String>> {
        if (times.isEmpty() || maxPeriods <= 0) return emptyList()

        val counts = times.groupingBy { it }.eachCount()
        // 票数高者优先；并列取开始时间更早、再取结束时间更早，保证稳定
        val ranked = counts.entries.sortedWith(
            compareByDescending<Map.Entry<Pair<String, String>, Int>> { it.value }
                .thenBy { it.key.first }
                .thenBy { it.key.second }
        )

        val canonical = mutableListOf<Pair<String, String>>()
        for ((period, _) in ranked) {
            if (canonical.size >= maxPeriods) break
            // 自身就是被更高票时段覆盖的早到变体，不当作新的标准行
            if (canonical.count { overlaps(period, it) } == 1) continue
            canonical += period
        }

        val merged = times.map { period ->
            val hits = canonical.filter { overlaps(period, it) }
            if (hits.size == 1) hits[0] else period
        }.distinct()

        return merged.sortedWith(compareBy({ it.first }, { it.second })).take(maxPeriods)
    }

    private fun overlaps(a: Pair<String, String>, b: Pair<String, String>): Boolean =
        a.first < b.second && a.second > b.first
}
