package com.xingheyuzhuan.shiguangschedule.data.db.widget

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Room 数据访问对象 (DAO)，用于操作 WidgetTimeSlot 数据表。
 */
@Dao
interface WidgetTimeSlotDao {
    /**
     * 获取所有节次（按节次序号升序），返回数据流以便 Widget 自动更新。
     */
    @Query("SELECT * FROM widget_time_slots ORDER BY number ASC")
    fun getTimeSlots(): Flow<List<WidgetTimeSlot>>

    /**
     * 插入一个或多个节次。
     * 同步器会整体重写节次表，使用 REPLACE 策略在冲突时替换旧数据。
     */
    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun insertAll(slots: List<WidgetTimeSlot>)

    /**
     * 删除所有节次。
     */
    @Query("DELETE FROM widget_time_slots")
    suspend fun deleteAll()

    /**
     * 整体重写节次表：清空 + 批量插入在同一事务内完成。
     * 分开执行时若进程在两步之间被杀，会留下空节次表，
     * 小组件时间轴会退回按课程时间投票的兜底模式。
     */
    @Transaction
    suspend fun replaceAll(slots: List<WidgetTimeSlot>) {
        deleteAll()
        if (slots.isNotEmpty()) insertAll(slots)
    }
}
