package com.xingheyuzhuan.shiguangschedule.data.api.gdou

import com.xingheyuzhuan.shiguangschedule.data.repository.AppSettingsRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.CourseConversionRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.GdouJwxtRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Single
import kotlin.time.Clock

@Single
class GdouSyncUseCase(
    private val auth: GdouAuthClient,
    private val schedule: GdouScheduleClient,
    private val exams: GdouExamClient,
    private val repo: GdouJwxtRepository,
    private val conversion: CourseConversionRepository,
    private val appSettings: AppSettingsRepository,
    private val http: GdouHttpClient
) {
    // 同步、手动刷新和认证共享路由及 Cookie；只有同一批的两个只读查询并行。
    private val sessionMutex = Mutex()

    suspend fun saveCredentials(username: String, password: String): Boolean =
        sessionMutex.withLock { repo.saveCredentials(username, password) }

    suspend fun logout() = sessionMutex.withLock { repo.logout() }

    private suspend fun hasActiveSession(): Boolean {
        repo.restoreCookiesIfEmpty()
        return !http.cookieStore.isEmpty() && auth.isJwSessionAlive()
    }

    private suspend fun ensureSession(
        captcha: String = "",
        allowInteractive: Boolean = true,
        log: GdouSyncLogCollector? = null
    ): GdouAuthResult {
        repo.restoreCookiesIfEmpty()
        val fingerprint = repo.fingerprint()
        log?.info("校验本地教务会话")
        if (auth.isJwSessionAlive()) {
            log?.info("教务本地会话仍有效，使用 ${http.jwRoute.host}")
            repo.persistCookies()
            return GdouAuthResult.LoggedIn
        }
        log?.info("本地会话不可用，开始统一身份认证登录")
        repo.clearCookies()
        val creds = repo.loadCredentials() ?: return GdouAuthResult.Failed("未保存学号密码")
        val logged = auth.login(
            username = creds.username,
            password = creds.password,
            fingerprint = fingerprint,
            captcha = captcha,
            fetchCaptchaIfNeeded = allowInteractive && captcha.isBlank()
        )
        if (logged is GdouAuthResult.LoggedIn) {
            log?.info("统一身份认证登录成功，使用 ${http.jwRoute.host}")
        }
        repo.persistCookies()
        return logged
    }

    internal suspend fun refreshExams(key: GdouExamKey) = sessionMutex.withLock {
        // 请求排队期间如果已切换账号/学期，不再查询原来选中的学期。
        if (repo.snapshot().examKey() != key) return@withLock
        check(hasActiveSession()) { "教务会话已过期" }
        val result = auth.readJw { exams.fetch(key.term) }
        repo.persistExams(key, result)
        repo.persistCookies()
    }

    suspend fun sendMfa(channel: GdouMfaChannel): GdouAuthResult = sessionMutex.withLock {
        repo.restoreCookiesIfEmpty()
        val result = auth.sendMfaCode(channel)
        repo.persistCookies()
        result
    }

    suspend fun submitMfa(code: String): GdouAuthResult = sessionMutex.withLock {
        repo.restoreCookiesIfEmpty()
        val result = auth.submitMfaCode(code)
        repo.persistCookies()
        result
    }

    suspend fun syncNow(
        tableId: String? = null,
        captcha: String = "",
        allowInteractive: Boolean = true,
        source: String = "manual"
    ): GdouSyncResult = sessionMutex.withLock {
        val log = GdouSyncLogCollector()
        http.requestFailureSink = { requestUrl, error ->
            val text = GdouHttpClient.connectivityText(error)
            if (text != null) {
                log.info("请求 ${GdouHtml.hostOf(requestUrl)} 连接失败（$text），可能是网络波动")
            }
        }
        try {
            syncNowLogged(log, tableId, captcha, allowInteractive, source)
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

        val term = snapshot.resolvedTerm()
        suspend fun importSchedule(): GdouSyncResult {
            val (fetched, fetchedExams) = auth.readJw {
                fetchGdouTermData({ schedule.fetch(term) }, { exams.fetch(term) })
            }
            val examCount = fetchedExams.fold(
                onSuccess = { rows ->
                    repo.persistExams(GdouExamKey(snapshot.username, term), rows)
                    log.info("${http.jwRoute.host} 考试接口返回 ${rows.size} 条记录，已缓存")
                    rows.size
                },
                onFailure = { error ->
                    val reason = GdouHttpClient.connectivityText(error)?.let { "（$it）" }.orEmpty()
                    log.info("考试同步失败${reason}，保留已有缓存")
                    null
                }
            )
            log.info("${http.jwRoute.host} 课表接口返回 ${fetched.courses.size} 条记录")
            // 被跳过的记录只是解析时丢弃的个别行，整体同步仍然成功，用 INFO 而非 ERROR，
            // 否则日志面板会把「有一条课没导入」误读成「同步失败」
            fetched.skipped.forEach { log.info(it) }
            if (fetched.courses.isEmpty()) {
                return fail(log, "未查询到课表，请核对学年学期或登录状态", retryable = false, source)
            }
            // 必须先写入时间段，再导入课程：导入时按时间段范围夹取节次，
            // 若先导入课程，首次同步时时间段表为空，越界节次无法被收敛，
            // 渲染层会因匹配不到时间段而静默丢弃该课程。
            conversion.importTimeSlots(targetTable, GdouYangjiangTimetable.timeSlots)
            conversion.importCoursesFromList(targetTable, fetched.courses).forEach { log.info(it) }
            conversion.importCourseConfig(targetTable, GdouKbListParser.configFrom(fetched.semester, fetched.courses))
            repo.persistCookies()
            val message = buildString {
                append("已导入 ${fetched.courses.size} 门课")
                fetched.semester?.startDate?.let { append("，开学 $it") }
                if (examCount != null) append("；已同步 $examCount 场考试") else append("；考试同步失败")
            }
            repo.recordSync(true, message, Clock.System.now().toEpochMilliseconds(), source, log.detailOrNull())
            return GdouSyncResult.Success(
                courseCount = fetched.courses.size,
                startDate = fetched.semester?.startDate,
                totalWeeks = GdouKbListParser.configFrom(fetched.semester, fetched.courses).semesterTotalWeeks,
                examCount = examCount
            )
        }
        return runCatching {
            importSchedule()
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            val msg = GdouHttpClient.connectivityText(e)?.let { "网络连接失败：$it" } ?: (e.message ?: "同步失败")
            val expired = msg.contains("过期") || msg.contains("登录") || msg.contains("901")
            if (!expired || !allowInteractive) {
                return fail(log, msg, retryable = true, source)
            }
            val again = ensureSession(allowInteractive = true, log = log)
            mapAuth(log, again, source) ?: runCatching { importSchedule() }.getOrElse {
                if (it is CancellationException) throw it
                fail(log, it.message ?: msg, retryable = true, source)
            }
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
}

/** 在同一教务路由并行读取；考试失败不阻止课表写入。换路由重试前会取消旧的查询。 */
internal suspend fun fetchGdouTermData(
    fetchSchedule: suspend () -> GdouFetchedSchedule,
    fetchExams: suspend () -> List<GdouExam>
): Pair<GdouFetchedSchedule, Result<List<GdouExam>>> = coroutineScope {
    val exams = async {
        try {
            Result.success(fetchExams())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
    fetchSchedule() to exams.await()
}
