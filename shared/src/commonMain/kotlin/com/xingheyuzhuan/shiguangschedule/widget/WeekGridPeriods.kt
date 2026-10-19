package com.xingheyuzhuan.shiguangschedule.widget

/**
 * 周网格小组件的时间轴行合并。
 *
 * 行集合按本周课程的原始 (start, end) 投票得出：出现次数最多的时段视为标准节次，
 * 只与其中一行重叠的「早上课 / 早下课」并入该行，不再单独占一行。
 * 与任何标准行都不重叠的课间课、以及同时跨两行及以上的真跨节课，保留原时段。
 *
 * 时间均为 "HH:mm" 文本，字典序即时间序，比较前不做解析。
 */
object WeekGridPeriods {

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
