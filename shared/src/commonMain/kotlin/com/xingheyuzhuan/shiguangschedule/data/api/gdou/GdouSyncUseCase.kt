package com.xingheyuzhuan.shiguangschedule.data.api.gdou

import com.xingheyuzhuan.shiguangschedule.data.repository.AppSettingsRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.CourseConversionRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.GdouJwxtRepository
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.koin.core.annotation.Single
import kotlin.time.Clock

@Single
class GdouSyncUseCase(
    private val auth: GdouAuthClient,
    private val schedule: GdouScheduleClient,
    private val repo: GdouJwxtRepository,
    private val conversion: CourseConversionRepository,
    private val appSettings: AppSettingsRepository,
    private val http: GdouHttpClient
) {
    suspend fun ensureSession(
        captcha: String = "",
        allowInteractive: Boolean = true,
        log: GdouSyncLogCollector? = null
    ): GdouAuthResult {
        repo.restoreCookiesIfEmpty()
        val fingerprint = repo.fingerprint()
        log?.info("打开教务网关 $GDOU_WFW_HOST")
        log?.info("校验本地教务会话")
        if (auth.isJwSessionAlive()) {
            log?.info("教务本地会话仍有效，跳过登录")
            repo.persistCookies()
            return GdouAuthResult.LoggedIn
        }
        log?.info("本地会话不可用，尝试用本地 CASTGC 恢复统一认证会话")
        val resumed = auth.tryResumeWithCastgc(fingerprint)
        if (resumed is GdouAuthResult.LoggedIn) {
            log?.info("已用本地 CASTGC 恢复统一认证会话")
            repo.persistCookies()
            return resumed
        }
        if (resumed is GdouAuthResult.NeedMfa) {
            repo.persistCookies()
            return if (allowInteractive) resumed else GdouAuthResult.Failed("需要二次认证")
        }
        repo.clearCookies()
        val creds = repo.loadCredentials() ?: return GdouAuthResult.Failed("未保存学号密码")
        log?.info("开始统一身份认证登录")
        val logged = auth.login(
            username = creds.username,
            password = creds.password,
            fingerprint = fingerprint,
            captcha = captcha,
            fetchCaptchaIfNeeded = allowInteractive && captcha.isBlank()
        )
        if (logged is GdouAuthResult.LoggedIn) {
            log?.info("统一身份认证登录成功，已获得凭证")
        }
        repo.persistCookies()
        return logged
    }

    suspend fun sendMfa(channel: GdouMfaChannel): GdouAuthResult {
        repo.restoreCookiesIfEmpty()
        val result = auth.sendMfaCode(channel)
        repo.persistCookies()
        return result
    }

    suspend fun submitMfa(code: String): GdouAuthResult {
        repo.restoreCookiesIfEmpty()
        val result = auth.submitMfaCode(code)
        repo.persistCookies()
        return result
    }

    suspend fun syncNow(
        tableId: String? = null,
        captcha: String = "",
        allowInteractive: Boolean = true,
        source: String = "manual"
    ): GdouSyncResult {
        val log = GdouSyncLogCollector()
        http.requestFailureSink = { requestUrl, error ->
            val text = GdouHttpClient.connectivityText(error)
            if (text != null) {
                log.info("请求 ${GdouHtml.hostOf(requestUrl)} 连接失败（$text），属校外访问预期或网络波动")
            }
        }
        try {
            return syncNowLogged(log, tableId, captcha, allowInteractive, source)
        } finally {
            http.requestFailureSink = null
        }
    }

    private suspend fun syncNowLogged(
        log: GdouSyncLogCollector,
        tableId: String?,
        captcha: String,
        allowInteractive: Boolean,
        source: String
    ): GdouSyncResult {
        val snapshot = repo.snapshot()
        val targetTable = tableId?.ifBlank { null }
            ?: snapshot.tableId.ifBlank { null }
            ?: appSettings.getAppSettingsOnce().currentCourseTableId
        if (targetTable.isBlank()) {
            return fail(log, "未选择课表", retryable = false, source)
        }
        repo.setTableId(targetTable)

        val session = ensureSession(captcha = captcha, allowInteractive = allowInteractive, log = log)
        val mapped = mapAuth(log, session, source)
        if (mapped != null) return mapped

        val term = resolveTerm(snapshot.xnm, snapshot.xqm)
        suspend fun importSchedule(): GdouSyncResult {
            val fetched = schedule.fetch(term)
            log.info("课表接口返回 ${fetched.courses.size} 条记录")
            if (fetched.courses.isEmpty()) {
                return fail(log, "未查询到课表，请核对学年学期或登录状态", retryable = false, source)
            }
            conversion.importCoursesFromList(targetTable, fetched.courses)
            conversion.importCourseConfig(targetTable, GdouKbListParser.configFrom(fetched.semester, fetched.courses))
            conversion.importTimeSlots(targetTable, GdouYangjiangTimetable.timeSlots)
            repo.persistCookies()
            val message = buildString {
                append("已导入 ${fetched.courses.size} 门课")
                fetched.semester?.startDate?.let { append("，开学 $it") }
            }
            repo.recordSync(true, message, Clock.System.now().toEpochMilliseconds(), source, log.detailOrNull())
            return GdouSyncResult.Success(
                courseCount = fetched.courses.size,
                startDate = fetched.semester?.startDate,
                totalWeeks = GdouKbListParser.configFrom(fetched.semester, fetched.courses).semesterTotalWeeks
            )
        }
        return runCatching {
            importSchedule()
        }.getOrElse { e ->
            val msg = GdouHttpClient.connectivityText(e)?.let { "网络连接失败：$it" } ?: (e.message ?: "同步失败")
            val expired = msg.contains("过期") || msg.contains("登录") || msg.contains("901")
            if (!expired || !allowInteractive) {
                return fail(log, msg, retryable = true, source)
            }
            val again = ensureSession(allowInteractive = true, log = log)
            mapAuth(log, again, source) ?: runCatching { importSchedule() }.getOrElse { fail(log, it.message ?: msg, retryable = true, source) }
        }
    }

    private suspend fun mapAuth(
        log: GdouSyncLogCollector,
        result: GdouAuthResult,
        source: String
    ): GdouSyncResult? = when (result) {
        GdouAuthResult.LoggedIn -> null
        is GdouAuthResult.NeedCaptcha -> GdouSyncResult.NeedCaptcha(result.imagePng)
        is GdouAuthResult.NeedMfa -> GdouSyncResult.NeedMfa(result.channel, result.hint)
        is GdouAuthResult.BadPassword -> fail(log, result.message, retryable = false, source)
        is GdouAuthResult.Network -> fail(log, result.message, retryable = true, source)
        is GdouAuthResult.Failed -> fail(log, result.message, retryable = false, source)
    }

    private suspend fun fail(
        log: GdouSyncLogCollector,
        message: String,
        retryable: Boolean,
        source: String = "manual"
    ): GdouSyncResult {
        log.error(message)
        repo.recordSync(false, message, Clock.System.now().toEpochMilliseconds(), source, log.detailOrNull())
        return GdouSyncResult.Failed(message, retryable)
    }

    private fun resolveTerm(xnm: String, xqm: String): GdouTerm {
        if (xnm.isNotBlank() && xqm.isNotBlank()) return GdouTerm(xnm, xqm)
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        return GdouKbListParser.inferTerm(now.year, now.month.number)
    }
}
