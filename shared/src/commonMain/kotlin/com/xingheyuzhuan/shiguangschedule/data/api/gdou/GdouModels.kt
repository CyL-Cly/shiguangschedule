package com.xingheyuzhuan.shiguangschedule.data.api.gdou

import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable

const val GDOU_AUTH_HOST = "authserver.gdou.edu.cn"
const val GDOU_JW_HOST = "jw.gdou.edu.cn"
const val GDOU_WFW_HOST = "wfw.gdou.edu.cn"
const val GDOU_WFW_ORIGIN = "https://$GDOU_WFW_HOST"
const val GDOU_WFW_HOME = "$GDOU_WFW_ORIGIN/"
const val GDOU_WFW_GATEWAY_PREFIX = "$GDOU_WFW_ORIGIN/https://$GDOU_JW_HOST"

const val GDOU_AUTH_BASE = "https://$GDOU_AUTH_HOST/authserver"
const val GDOU_JW_BASE = "https://$GDOU_JW_HOST"
const val GDOU_SERVICE_URL = "$GDOU_JW_BASE/sso/jziotlogin"
const val GDOU_JW_MENU = "$GDOU_JW_BASE/xtgl/index_initMenu.html?jsdm=xs"
const val GDOU_KB_INDEX = "$GDOU_WFW_ORIGIN/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default"
const val GDOU_KB_JSON = "$GDOU_JW_BASE/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151"
const val GDOU_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36 Edg/140.0.0.0"

const val GDOU_XQM_FIRST = "3"
const val GDOU_XQM_SECOND = "12"

@Serializable
data class GdouCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/"
)

@Serializable
data class GdouReauthParams(
    val reAuthType: String = "4",
    val isMultifactor: String = "true",
    val service: String = GDOU_SERVICE_URL,
    val reAuthUserId: String = "",
    val pwdEncryptSalt: String = "",
    val referer: String = ""
)

enum class GdouMfaChannel(val reAuthType: String, val authCodeTypeName: String) {
    WECOM("4", "reAuthWChatDynamicCodeType"),
    SMS("3", "reAuthDynamicCodeType");

    companion object {
        fun fromReAuthType(value: String?): GdouMfaChannel =
            entries.find { it.reAuthType == value } ?: WECOM
    }
}

data class GdouCredentials(
    val username: String,
    val password: String
)

@Serializable
data class GdouTerm(
    val xnm: String,
    val xqm: String
)

@Serializable
data class GdouAccountSnapshot(
    val username: String = "",
    val hasPassword: Boolean = false,
    val autoSyncEnabled: Boolean = false,
    val tableId: String = "",
    val xnm: String = "",
    val xqm: String = "",
    val mfaChannel: String = GdouMfaChannel.WECOM.reAuthType,
    val lastSyncAtMillis: Long = 0L,
    val lastSyncMessage: String = "",
    val lastSyncSuccess: Boolean = false,
    val hasSession: Boolean = false
)

internal fun GdouAccountSnapshot.resolvedTerm(): GdouTerm {
    if (xnm.isNotBlank() && xqm.isNotBlank()) return GdouTerm(xnm, xqm)
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    return GdouKbListParser.inferTerm(now.year, now.month.number)
}

internal data class GdouExamKey(val username: String, val term: GdouTerm)

internal fun GdouAccountSnapshot.examKey(): GdouExamKey? =
    username.takeIf { it.isNotBlank() }?.let { GdouExamKey(it, resolvedTerm()) }

@Serializable
internal data class GdouCachedExams(val term: GdouTerm, val exams: List<GdouExam>)

internal data class GdouExamCacheState(val key: GdouExamKey?, val exams: List<GdouExam>?)

@Serializable
data class GdouSyncLogEntry(
    val atMillis: Long,
    val success: Boolean,
    val message: String,
    val source: String = "manual",
    /** 过程化阶段日志，多行文本，\n 分隔，每行形如 HH:mm:ss [info|error] xxx。旧数据无此字段，反序列化得 null。 */
    val detail: String? = null
)

enum class GdouSyncLogLevel(val tag: String) {
    INFO("info"),
    ERROR("error")
}

/**
 * 一次同步过程的阶段日志收集器。
 * 行格式：HH:mm:ss [info|error] 文本；最终随结果一次性写入 GdouSyncLogEntry.detail。
 * 时间源与时区可注入，便于单测。
 */
class GdouSyncLogCollector(
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() }
) {
    private val lines = MutableStateFlow<List<String>>(emptyList())

    fun info(text: String) = append(GdouSyncLogLevel.INFO, text)

    fun error(text: String) = append(GdouSyncLogLevel.ERROR, text)

    fun append(level: GdouSyncLogLevel, text: String) {
        if (text.isBlank()) return
        val line = formatLine(nowMillis(), level, text, timeZone)
        lines.update { it + line }
    }

    /** 无任何行时返回 null，避免落库空串。 */
    fun detailOrNull(): String? = lines.value.takeIf { it.isNotEmpty() }?.joinToString("\n")

    companion object {
        fun formatLine(
            atMillis: Long,
            level: GdouSyncLogLevel,
            text: String,
            timeZone: TimeZone
        ): String {
            val dt = Instant.fromEpochMilliseconds(atMillis).toLocalDateTime(timeZone)
            val hh = dt.hour.toString().padStart(2, '0')
            val mm = dt.minute.toString().padStart(2, '0')
            val ss = dt.second.toString().padStart(2, '0')
            return "$hh:$mm:$ss [${level.tag}] $text"
        }
    }
}

sealed interface GdouAuthResult {
    data object LoggedIn : GdouAuthResult
    data class NeedCaptcha(val imagePng: ByteArray) : GdouAuthResult {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is NeedCaptcha) return false
            return imagePng.contentEquals(other.imagePng)
        }

        override fun hashCode(): Int = imagePng.contentHashCode()
    }

    data class NeedMfa(
        val channel: GdouMfaChannel,
        val hint: String? = null
    ) : GdouAuthResult

    data class BadPassword(val message: String) : GdouAuthResult
    data class Network(val message: String) : GdouAuthResult
    data class Failed(val message: String) : GdouAuthResult
}

sealed interface GdouSyncResult {
    data class Success(
        val courseCount: Int,
        val startDate: String?,
        val totalWeeks: Int,
        val examCount: Int? = null
    ) : GdouSyncResult
    data class NeedCaptcha(val imagePng: ByteArray) : GdouSyncResult {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is NeedCaptcha) return false
            return imagePng.contentEquals(other.imagePng)
        }

        override fun hashCode(): Int = imagePng.contentHashCode()
    }

    data class NeedMfa(val channel: GdouMfaChannel, val hint: String? = null) : GdouSyncResult
    data class Failed(val message: String, val retryable: Boolean = false) : GdouSyncResult
}

data class GdouSemesterInfo(
    val startDate: String?,
    val totalWeeks: Int
)
