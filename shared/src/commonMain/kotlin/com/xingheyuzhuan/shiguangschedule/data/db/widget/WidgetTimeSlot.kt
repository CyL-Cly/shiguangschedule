package com.xingheyuzhuan.shiguangschedule.data.db.widget

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * Room 实体类，专为桌面 Widget 设计的节次表数据表。
 * 与应用内课表网格共用同一行基准：小组件的时间轴行由节次表驱动，
 * 自定义时间课程按开始时间吸附到对应节次行（见 WeekGridPeriods）。
 * 数据由主数据库同步器预处理写入。
 *
 * 刻意不带主库 [com.xingheyuzhuan.shiguangschedule.data.db.main.TimeSlot] 的 `alias`：
 * 小组件时间轴列只显示时间，节次别名没有展示位；带上它需要再升一次库版本并重建整个 widget 库。
 */
@Entity(tableName = "widget_time_slots")
data class WidgetTimeSlot(
    @PrimaryKey
    val number: Int, // 节次序号，例如 1、2、3
    val startTime: String, // 节次开始时间，例如 "08:10"
    val endTime: String, // 节次结束时间，例如 "08:55"
)
