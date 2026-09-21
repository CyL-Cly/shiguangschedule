package com.xingheyuzhuan.shiguangschedule.data.api.gdou

actual object GdouSyncNotifier {
    @Volatile
    var handler: ((String, String) -> Unit)? = null

    actual fun notifyNeedsAttention(title: String, message: String) {
        handler?.invoke(title, message)
    }
}
