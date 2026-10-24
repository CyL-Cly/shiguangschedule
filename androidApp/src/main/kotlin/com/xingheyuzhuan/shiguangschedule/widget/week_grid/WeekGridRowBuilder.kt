package com.xingheyuzhuan.shiguangschedule.widget.week_grid

import android.content.Context
import android.widget.RemoteViews
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.db.widget.WidgetTimeSlot
import com.xingheyuzhuan.shiguangschedule.data.model.schedule_style.ScheduleGridStyleProto
import com.xingheyuzhuan.shiguangschedule.widget.WeekGridPeriods
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
     * 课程落位与应用内网格对齐：归属行（见 [WeekGridPeriods.mapRowStart]）等于本行的课程
     * 显示完整卡片（名称 + 教室，含高亮）；归属行早于本行且尚未下课的课程以色条延续；
     * 其余课程不在本行渲染。早上课 / 早下课课程已在归属计算中就近并入标准行。
     *
     * @param period 时间段 (start_time, end_time)，已去重排序
     * @param weekCourses 已按窗口与 is_skipped 过滤的课程列表
     * @param rowStartByCourseId 课程 id → 归属行开始时间，由 Factory 一次性算好缓存
     */
    fun buildDataRow(
        context: Context,
        period: Pair<String, String>,
        weekDays: List<LocalDate>,
        today: LocalDate,
        weekCourses: List<WidgetCourseProto>,
        style: ScheduleGridStyleProto?,
        highlightCourseIds: Set<String> = emptySet(),
        rowStartByCourseId: Map<String, String> = emptyMap(),
    ): RemoteViews {
        val startTime = period.first
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

            // 清空格子内旧卡片必须在本日所有课程渲染之前做一次，
            // 放到循环里会让同格多门课互相覆盖，只剩最后一门（多节连排的同一行会丢课）
            cellRv.removeAllViews(R.id.cell_content)

            weekCourses.mapNotNull { course ->
                if (course.date != dayStr) return@mapNotNull null
                when (val courseRowStart = rowStartByCourseId[course.id] ?: course.start_time) {
                    startTime -> course to false
                    else -> if (courseRowStart < startTime && course.end_time > startTime) {
                        course to true
                    } else {
                        null
                    }
                }
            }.forEach { (course, isContinuation) ->
                // 归属行渲染完整卡；跨行课程在后续行以色条延续，同课合并为一列，避免留白或重复
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
     * 时间轴：优先以节次表为行基准（早上课/早下课就近并入标准行），
     * 节次表缺失时按出现次数投票兜底，最多 [MAX_PERIODS] 条。见 [WeekGridPeriods]。
     */
    fun extractPeriods(
        weekCourses: List<WidgetCourseProto>,
        slots: List<WidgetTimeSlot>,
    ): List<Pair<String, String>> =
        WeekGridPeriods.buildRows(
            weekCourses.map { it.start_time to it.end_time },
            slots,
            MAX_PERIODS
        )

    /**
     * 课程 id → 归属行开始时间，供 [buildDataRow] 判定整卡 / 延续色条。
     */
    fun computeRowStartByCourseId(
        weekCourses: List<WidgetCourseProto>,
        periods: List<Pair<String, String>>,
        slots: List<WidgetTimeSlot>,
    ): Map<String, String> = weekCourses.associate { course ->
        course.id to WeekGridPeriods.mapRowStart(
            course.start_time to course.end_time,
            periods,
            slots
        )
    }

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
