package com.xingheyuzhuan.shiguangschedule.widget.week_grid

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.xingheyuzhuan.shiguangschedule.MainActivity
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.widget.WidgetSnapshot
import java.time.LocalDate

object WeekGridNativeRenderer {

    fun render(context: Context, snapshot: WidgetSnapshot): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_week_grid)

        // 点击整个小组件打开主界面
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        rv.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

        // 假期状态：显示全尺寸遮罩
        if (snapshot.current_week <= 0) {
            rv.setViewVisibility(R.id.inner_content_card, View.GONE)
            rv.setViewVisibility(R.id.container_full_status, View.VISIBLE)
            rv.setTextViewText(
                R.id.tv_full_status_title,
                context.getString(R.string.title_vacation)
            )
            rv.setTextViewText(
                R.id.tv_full_status_msg,
                context.getString(R.string.widget_vacation_expecting)
            )
            return rv
        }
        rv.setViewVisibility(R.id.container_full_status, View.GONE)
        rv.setViewVisibility(R.id.inner_content_card, View.VISIBLE)

        // 滚动窗口：以今天为起点连续 5 天，今天恒为第一列
        val today = LocalDate.now()
        val weekDays = (0..4).map { today.plusDays(it.toLong()) }

        // 标题与本周日期范围
        rv.setTextViewText(
            R.id.tv_header_title,
            context.getString(R.string.title_current_week, snapshot.current_week.toString())
        )
        val rangeEnd = today.plusDays(4)
        rv.setTextViewText(
            R.id.tv_header_range,
            "${today.monthValue}/${today.dayOfMonth} - ${rangeEnd.monthValue}/${rangeEnd.dayOfMonth}"
        )

        // 固定表头行（不随列表滚动），复用行布局以保证与数据行的时间轴对齐
        rv.removeAllViews(R.id.container_header)
        rv.addView(R.id.container_header, WeekGridRowBuilder.buildHeaderRow(context, weekDays))

        // 数据行经由 RemoteViewsService 提供，支持垂直滚动；
        // 列表为空时自动显示 tv_empty_status
        rv.setTextViewText(
            R.id.tv_empty_status,
            context.getString(R.string.no_courses_this_week)
        )
        val serviceIntent = Intent(context, WeekGridRemoteViewsService::class.java)
        rv.setRemoteAdapter(R.id.container_rows_list, serviceIntent)
        rv.setEmptyView(R.id.container_rows_list, R.id.tv_empty_status)

        return rv
    }
}
