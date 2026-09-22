package com.xingheyuzhuan.shiguangschedule.widget.week_grid

import android.content.Context
import android.widget.RemoteViews
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.model.schedule_style.ScheduleGridStyleProto
import com.xingheyuzhuan.shiguangschedule.widget.WidgetCourseProto
import java.time.LocalDate

/**
 * 周网格小组件的行构建器。
 *
 * 表头行由 [WeekGridNativeRenderer]（固定区域）渲染，
 * 数据行由 [WeekGridRemoteViewsService]（ListView 列表项）渲染，
 * 两者共享本对象以保证列对齐与配色逻辑一致。
 */
internal object WeekGridRowBuilder {

    // 时间轴最多渲染的时间段行数
    const val MAX_PERIODS = 10

    /**
     * 构建表头行：空时间标签 + 5 个日期单元格（第 0 列恒为今天，施加高亮背景）。
     */
    fun buildHeaderRow(
        context: Context,
        weekDays: List<LocalDate>,
    ): RemoteViews {
        val weekDaysArray = context.resources.getStringArray(R.array.week_days_full_names)
        val headerRow = RemoteViews(context.packageName, R.layout.widget_week_grid_row)
        headerRow.setTextViewText(R.id.tv_time_label, "")
        // ListView 会回收重用视图并 reapply，addView 前必须清空旧子视图避免累积重复
        headerRow.removeAllViews(R.id.container_row_cells)
        weekDays.forEachIndexed { index, day ->
            // 第 0 列恒为今天：使用 2 倍宽表头单元格并施加今日高亮背景
            val cellRv = RemoteViews(
                context.packageName,
                if (index == 0) R.layout.widget_week_grid_header_cell_today
                else R.layout.widget_week_grid_header_cell
            )
            cellRv.setTextViewText(R.id.tv_day_name, weekDaysArray[day.dayOfWeek.value - 1])
            cellRv.setTextViewText(R.id.tv_day_date, "${day.monthValue}/${day.dayOfMonth}")
            if (index == 0) {
                cellRv.setInt(
                    R.id.tv_day_name, "setBackgroundResource",
                    R.drawable.widget_day_header_bg_today
                )
            }
            headerRow.addView(R.id.container_row_cells, cellRv)
        }
        return headerRow
    }

    /**
     * 构建单条数据行：时间标签 + 5 个课程单元格。
     *
     * @param period 时间段 (start_time, end_time)，已去重排序
     * @param weekCourses 已按窗口与 is_skipped 过滤的课程列表
     */
    fun buildDataRow(
        context: Context,
        period: Pair<String, String>,
        weekDays: List<LocalDate>,
        today: LocalDate,
        weekCourses: List<WidgetCourseProto>,
        style: ScheduleGridStyleProto?,
        highlightCourseIds: Set<String> = emptySet(),
    ): RemoteViews {
        val (startTime, endTime) = period
        val rowRv = RemoteViews(context.packageName, R.layout.widget_week_grid_row)
        rowRv.setTextViewText(R.id.tv_time_label, startTime.take(5))
        // ListView 回收重用：清空旧行子视图，避免 addView 累积
        rowRv.removeAllViews(R.id.container_row_cells)

        weekDays.forEach { day ->
            val dayStr = day.toString()
            // 今天列使用 2 倍宽单元格布局，便于教室文本换行展示
            val cellRv = RemoteViews(
                context.packageName,
                if (day == today) R.layout.widget_week_grid_cell_today
                else R.layout.widget_week_grid_cell
            )

            weekCourses.filter { course ->
                it_dateMatches(course, dayStr, startTime, endTime)
            }.forEach { course ->
                // 清空格子内旧卡片，防止回收重用时累积
                cellRv.removeAllViews(R.id.cell_content)
                // 课程起始于更早时段时，本行仅渲染延续色条，避免留白
                val isContinuation = course.start_time < startTime
                val cardRv = RemoteViews(
                    context.packageName,
                    if (isContinuation) R.layout.widget_week_grid_card_continuation
                    else R.layout.widget_week_grid_card
                )
                if (!isContinuation) {
                    cardRv.setTextViewText(R.id.tv_card_name, course.name)
                    cardRv.setTextViewText(R.id.tv_card_position, course.position)

                    if (course.id in highlightCourseIds) {
                        // 当前课/下一节课：整卡描边高亮
                        cardRv.setInt(
                            R.id.course_card_root, "setBackgroundResource",
                            R.drawable.widget_course_card_highlight
                        )
                    }
                }

                if (style != null && course.color_int < style.course_color_maps.size) {
                    val colorPair = style.course_color_maps[course.color_int]
                    cardRv.setInt(R.id.course_indicator, "setColorFilter",
                        colorPair.light_color.toInt()
                    )
                    cardRv.setInt(R.id.course_indicator_dark, "setColorFilter",
                        colorPair.dark_color.toInt()
                    )
                }
                cellRv.addView(R.id.cell_content, cardRv)
            }
            rowRv.addView(R.id.container_row_cells, cellRv)
        }
        return rowRv
    }

    /**
     * 课程落在本行的判定：日期匹配，且课程时段与行时段重叠
     * （重叠而非相等，跨时段课程在后续行以延续色条呈现，不留空白）。
     */
    private fun it_dateMatches(
        course: WidgetCourseProto,
        dayStr: String,
        rowStart: String,
        rowEnd: String,
    ): Boolean =
        course.date == dayStr &&
            course.start_time < rowEnd &&
            course.end_time > rowStart

    /**
     * 与渲染器一致的数据行过滤规则：
     * 排除 is_skipped、排除日期为空的课程，仅保留落在 [today, today+5) 窗口内的课程。
     */
    fun filterWeekCourses(
        courses: List<WidgetCourseProto>,
        today: LocalDate,
    ): List<WidgetCourseProto> = courses.filter { course ->
        !course.is_skipped && course.date.isNotBlank() && try {
            val date = LocalDate.parse(course.date)
            !date.isBefore(today) && date.isBefore(today.plusDays(5))
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 时间轴：提取去重后的 (start_time, end_time) 组合并按开始时间排序，最多 [MAX_PERIODS] 条。
     */
    fun extractPeriods(weekCourses: List<WidgetCourseProto>): List<Pair<String, String>> =
        weekCourses
            .map { it.start_time to it.end_time }
            .distinct()
            .sortedBy { it.first }
            .take(MAX_PERIODS)

    /**
     * 计算高亮课程 id 集合：
     * - 今天当前时刻有课在上的，高亮该课；
     * - 否则高亮今天下一个未开始的课（含进行中的判定以内不存在时）。
     * 时间比较基于 start_time/end_time 的 "HH:mm[:ss]" 文本前 5 位。
     */
    fun computeHighlightCourseIds(
        weekCourses: List<WidgetCourseProto>,
        now: java.time.LocalDateTime,
    ): Set<String> {
        val todayStr = now.toLocalDate().toString()
        val nowTime = now.toLocalTime().toString().take(5)
        val todayCourses = weekCourses.filter {
            it.date == todayStr && !it.is_skipped
        }
        if (todayCourses.isEmpty()) return emptySet()

        val current = todayCourses.filter { course ->
            course.start_time.take(5) <= nowTime && nowTime < course.end_time.take(5)
        }
        if (current.isNotEmpty()) return current.map { it.id }.toSet()

        return todayCourses
            .filter { it.start_time.take(5) > nowTime }
            .minByOrNull { it.start_time }
            ?.let { setOf(it.id) } ?: emptySet()
    }
}
