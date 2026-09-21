package com.xingheyuzhuan.shiguangschedule.data.api.gdou

expect object GdouSyncScheduler {
    fun setEnabled(enabled: Boolean)
    fun requestImmediate()
}
