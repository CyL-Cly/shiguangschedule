package com.xingheyuzhuan.shiguangschedule.widget.week_grid

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.datastore.core.DataStore
import com.xingheyuzhuan.shiguangschedule.data.model.ScheduleGridStyle
import com.xingheyuzhuan.shiguangschedule.data.model.schedule_style.ScheduleGridStyleProto
import com.xingheyuzhuan.shiguangschedule.data.model.toProto
import com.xingheyuzhuan.shiguangschedule.data.repository.WidgetRepository
import com.xingheyuzhuan.shiguangschedule.widget.WidgetCourseProto
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.LocalDate
import kotlin.time.Duration.Companion.seconds

/**
 * 周网格小组件的滚动列表数据源。
 *
 * 数据行经由 ListView + RemoteViewsFactory 提供以支持垂直滚动；
 * 数据获取与 [com.xingheyuzhuan.shiguangschedule.widget.WidgetUpdateHelper]
 * 一致：直接读取 Widget Room 数据库（无网络），样式取自 DataStore。
 */
class WeekGridRemoteViewsService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        WeekGridFactory(applicationContext)

    /**
     * 局部注入代理，与 WidgetUpdateHelper 中的 WidgetDependencyContainer 同模式。
     * 服务与主应用同进程，Application.onCreate 已先于本组件完成 Koin 初始化。
     */
    private class WeekGridFactory(
        private val context: Context
    ) : RemoteViewsFactory, KoinComponent {

        private val repository: WidgetRepository by inject()
        private val styleDataStore: DataStore<ScheduleGridStyleProto> by inject()

        private var periods: List<Pair<String, String>> = emptyList()
        private var weekCourses: List<WidgetCourseProto> = emptyList()
        private var style: ScheduleGridStyleProto? = null
        private var highlightIds: Set<String> = emptySet()
        private var rowStartByCourseId: Map<String, String> = emptyMap()

        override fun onCreate() {}

        /**
         * 无网络加载：查询 Widget 数据库中 [today, today+5) 窗口的课程，
         * 过滤规则与渲染器一致（排除 is_skipped、排除日期为空/非法的课程）。
         */
        override fun onDataSetChanged() {
            try {
                val today = LocalDate.now()

                // 三个读取在同一 runBlocking 内并发执行，缩短 Factory 首次填充耗时；
                // 节次表读取超时降级为空列表，行生成退化为投票兜底
                val (dbCourses, fetchedStyle, fetchedSlots) = runBlocking {
                    val coursesDeferred = async {
                        withTimeoutOrNull(3.seconds) {
                            repository.getWidgetCoursesByDateRange(
                                today.toString(),
                                today.plusDays(4).toString()
                            ).first()
                        }
                    }
                    val styleDeferred = async {
                        withTimeoutOrNull(2.seconds) { styleDataStore.data.first() }
                    }
                    val slotsDeferred = async {
                        withTimeoutOrNull(2.seconds) { repository.getTimeSlots().first() }
                    }
                    Triple(
                        coursesDeferred.await() ?: emptyList(),
                        styleDeferred.await(),
                        slotsDeferred.await() ?: emptyList()
                    )
                }

                weekCourses = WeekGridRowBuilder.filterWeekCourses(
                    dbCourses.map { course ->
                        WidgetCourseProto(
                            id = course.id,
                            name = course.name,
                            teacher = course.teacher,
                            position = course.position,
                            start_time = course.startTime,
                            end_time = course.endTime,
                            color_int = course.colorInt,
                            is_skipped = course.isSkipped,
                            date = course.date
                        )
                    },
                    today
                )

                style = fetchedStyle
                    ?.takeIf { it.course_color_maps.isNotEmpty() }
                    ?: ScheduleGridStyle.DEFAULT.toProto()

                periods = WeekGridRowBuilder.extractPeriods(weekCourses, fetchedSlots)
                rowStartByCourseId = WeekGridRowBuilder.computeRowStartByCourseId(
                    weekCourses, periods, fetchedSlots
                )
                highlightIds = WeekGridRowBuilder.computeHighlightCourseIds(
                    weekCourses, java.time.LocalDateTime.now()
                )
            } catch (_: Exception) {
                periods = emptyList()
                weekCourses = emptyList()
                rowStartByCourseId = emptyMap()
            }
        }

        override fun onDestroy() {}

        override fun getCount(): Int = periods.size

        override fun getViewAt(position: Int): RemoteViews? {
            val period = periods.getOrNull(position) ?: return null
            return WeekGridRowBuilder.buildDataRow(
                context = context,
                period = period,
                weekDays = weekDays(),
                today = LocalDate.now(),
                weekCourses = weekCourses,
                style = style,
                highlightCourseIds = highlightIds,
                rowStartByCourseId = rowStartByCourseId
            )
        }

        // 滚动窗口：以今天为起点连续 5 天，今天恒为第一列（与渲染器表头一致）
        private fun weekDays(): List<LocalDate> =
            (0..4).map { LocalDate.now().plusDays(it.toLong()) }

        override fun getLoadingView(): RemoteViews? = null

        override fun getViewTypeCount(): Int = 1

        override fun getItemId(position: Int): Long = position.toLong()

        override fun hasStableIds(): Boolean = false
    }
}
