package com.xingheyuzhuan.shiguangschedule.data.api.gdou

expect object GdouSyncNotifier {
    fun notifyNeedsAttention(title: String, message: String)
}
