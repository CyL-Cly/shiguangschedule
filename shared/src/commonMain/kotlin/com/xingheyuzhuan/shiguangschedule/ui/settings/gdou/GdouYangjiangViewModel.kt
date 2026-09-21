package com.xingheyuzhuan.shiguangschedule.ui.settings.gdou

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.data.api.gdou.GdouAuthResult
import com.xingheyuzhuan.shiguangschedule.data.api.gdou.GdouKbListParser
import com.xingheyuzhuan.shiguangschedule.data.api.gdou.GdouMfaChannel
import com.xingheyuzhuan.shiguangschedule.data.api.gdou.GdouSyncResult
import com.xingheyuzhuan.shiguangschedule.data.api.gdou.GdouSyncScheduler
import com.xingheyuzhuan.shiguangschedule.data.api.gdou.GdouSyncUseCase
import com.xingheyuzhuan.shiguangschedule.data.api.gdou.GdouTerm
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseTable
import com.xingheyuzhuan.shiguangschedule.data.repository.AppSettingsRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.CourseTableRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.GdouJwxtRepository
import com.xingheyuzhuan.shiguangschedule.ui.components.ToastManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.koin.core.annotation.KoinViewModel
import kotlin.time.Clock

data class GdouYangjiangUiState(
    val username: String = "",
    val password: String = "",
    val passwordSaved: Boolean = false,
    val autoSyncEnabled: Boolean = false,
    val tableId: String = "",
    val tableName: String = "",
    val tables: List<CourseTable> = emptyList(),
    val xnm: String = "",
    val xqm: String = "",
    val mfaChannel: GdouMfaChannel = GdouMfaChannel.WECOM,
    val lastSyncMessage: String = "",
    val lastSyncAtMillis: Long = 0L,
    val lastSyncSuccess: Boolean = false,
    val hasSession: Boolean = false,
    val isBusy: Boolean = false,
    val captchaImage: ByteArray? = null,
    val captchaInput: String = "",
    val mfaHint: String? = null,
    val mfaCode: String = "",
    val needsMfa: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GdouYangjiangUiState) return false
        return username == other.username &&
            password == other.password &&
            passwordSaved == other.passwordSaved &&
            autoSyncEnabled == other.autoSyncEnabled &&
            tableId == other.tableId &&
            tableName == other.tableName &&
            tables == other.tables &&
            xnm == other.xnm &&
            xqm == other.xqm &&
            mfaChannel == other.mfaChannel &&
            lastSyncMessage == other.lastSyncMessage &&
            lastSyncAtMillis == other.lastSyncAtMillis &&
            lastSyncSuccess == other.lastSyncSuccess &&
            hasSession == other.hasSession &&
            isBusy == other.isBusy &&
            captchaImage.contentEquals(other.captchaImage) &&
            captchaInput == other.captchaInput &&
            mfaHint == other.mfaHint &&
            mfaCode == other.mfaCode &&
            needsMfa == other.needsMfa
    }

    override fun hashCode(): Int {
        var result = username.hashCode()
        result = 31 * result + password.hashCode()
        result = 31 * result + autoSyncEnabled.hashCode()
        result = 31 * result + tableId.hashCode()
        result = 31 * result + (captchaImage?.contentHashCode() ?: 0)
        return result
    }
}

@KoinViewModel
class GdouYangjiangViewModel(
    private val repo: GdouJwxtRepository,
    private val useCase: GdouSyncUseCase,
    private val courseTableRepository: CourseTableRepository,
    private val appSettingsRepository: AppSettingsRepository
) : ViewModel() {

    private val _form = MutableStateFlow(GdouYangjiangUiState())
    val uiState = combine(
        _form,
        repo.snapshotFlow,
        courseTableRepository.getAllCourseTables(),
        appSettingsRepository.getAppSettings()
    ) { form, snapshot, tables, settings ->
        val inferred = GdouKbListParser.inferTerm(
            Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).year,
            Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).month.number
        )
        val tableId = form.tableId.ifBlank { snapshot.tableId.ifBlank { settings.currentCourseTableId } }
        val tableName = tables.find { it.id == tableId }?.name.orEmpty()
        form.copy(
            username = form.username.ifBlank { snapshot.username },
            passwordSaved = snapshot.hasPassword,
            autoSyncEnabled = snapshot.autoSyncEnabled,
            tableId = tableId,
            tableName = tableName,
            tables = tables,
            xnm = form.xnm.ifBlank { snapshot.xnm.ifBlank { inferred.xnm } },
            xqm = form.xqm.ifBlank { snapshot.xqm.ifBlank { inferred.xqm } },
            mfaChannel = if (form.needsMfa) form.mfaChannel else GdouMfaChannel.fromReAuthType(snapshot.mfaChannel),
            lastSyncMessage = snapshot.lastSyncMessage,
            lastSyncAtMillis = snapshot.lastSyncAtMillis,
            lastSyncSuccess = snapshot.lastSyncSuccess,
            hasSession = snapshot.hasSession
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GdouYangjiangUiState())

    fun onUsernameChange(value: String) = _form.update { it.copy(username = value) }
    fun onPasswordChange(value: String) = _form.update { it.copy(password = value) }
    fun onCaptchaChange(value: String) = _form.update { it.copy(captchaInput = value) }
    fun onMfaCodeChange(value: String) = _form.update { it.copy(mfaCode = value) }
    fun onXnmChange(value: String) = _form.update { it.copy(xnm = value.filter { ch -> ch.isDigit() }.take(4) ) }
    fun onXqmChange(value: String) = _form.update { it.copy(xqm = value) }
    fun onChannelChange(channel: GdouMfaChannel) {
        _form.update { it.copy(mfaChannel = channel) }
        viewModelScope.launch { repo.setMfaChannel(channel) }
    }

    fun onTableSelected(table: CourseTable) {
        _form.update { it.copy(tableId = table.id, tableName = table.name) }
        viewModelScope.launch { repo.setTableId(table.id) }
    }

    fun onAutoSyncChange(enabled: Boolean) {
        viewModelScope.launch {
            repo.setAutoSyncEnabled(enabled)
            GdouSyncScheduler.setEnabled(enabled)
            if (enabled) ToastManager.show("已开启每日自动更新")
        }
    }

    fun loginAndSync() {
        viewModelScope.launch {
            val state = uiState.value
            val username = state.username.trim()
            if (username.isBlank()) {
                ToastManager.show("请输入学号")
                return@launch
            }
            if (state.password.isNotBlank()) {
                repo.saveCredentials(username, state.password)
            } else if (!state.passwordSaved) {
                ToastManager.show("请输入密码")
                return@launch
            }
            repo.setTerm(GdouTerm(state.xnm, state.xqm))
            repo.setTableId(state.tableId)
            repo.setMfaChannel(state.mfaChannel)
            runSync(state.captchaInput)
        }
    }

    fun submitCaptchaAndSync() = loginAndSync()

    fun sendMfa() {
        viewModelScope.launch {
            busy {
                when (val result = useCase.sendMfa(uiState.value.mfaChannel)) {
                    is GdouAuthResult.NeedMfa -> {
                        _form.update { it.copy(needsMfa = true, mfaHint = result.hint ?: "验证码已发送") }
                        ToastManager.show(result.hint ?: "验证码已发送")
                    }
                    is GdouAuthResult.Failed -> ToastManager.show(result.message)
                    is GdouAuthResult.Network -> ToastManager.show(result.message)
                    else -> ToastManager.show("验证码已发送")
                }
            }
        }
    }

    fun submitMfaAndSync() {
        viewModelScope.launch {
            val code = uiState.value.mfaCode.trim()
            if (code.isBlank()) {
                ToastManager.show("请输入验证码")
                return@launch
            }
            busy {
                when (val result = useCase.submitMfa(code)) {
                    GdouAuthResult.LoggedIn -> {
                        _form.update { it.copy(needsMfa = false, mfaCode = "", mfaHint = null) }
                        runSync()
                    }
                    is GdouAuthResult.NeedMfa -> {
                        _form.update { it.copy(needsMfa = true, mfaHint = result.hint) }
                        ToastManager.show("仍需二次认证")
                    }
                    is GdouAuthResult.Failed -> ToastManager.show(result.message)
                    is GdouAuthResult.BadPassword -> ToastManager.show(result.message)
                    is GdouAuthResult.Network -> ToastManager.show(result.message)
                    is GdouAuthResult.NeedCaptcha -> _form.update { it.copy(captchaImage = result.imagePng) }
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            repo.logout()
            GdouSyncScheduler.setEnabled(false)
            _form.update {
                it.copy(
                    password = "",
                    captchaImage = null,
                    captchaInput = "",
                    needsMfa = false,
                    mfaCode = ""
                )
            }
            ToastManager.show("已退出登录")
        }
    }

    private suspend fun runSync(captcha: String = "") {
        busy {
            when (val result = useCase.syncNow(uiState.value.tableId, captcha = captcha, allowInteractive = true)) {
                is GdouSyncResult.Success -> {
                    _form.update {
                        it.copy(
                            captchaImage = null,
                            captchaInput = "",
                            needsMfa = false,
                            password = ""
                        )
                    }
                    if (uiState.value.autoSyncEnabled) GdouSyncScheduler.setEnabled(true)
                    ToastManager.show("导入成功，共 ${result.courseCount} 门课")
                }
                is GdouSyncResult.NeedCaptcha -> {
                    _form.update { it.copy(captchaImage = result.imagePng, captchaInput = "") }
                    ToastManager.show("请输入图形验证码")
                }
                is GdouSyncResult.NeedMfa -> {
                    _form.update { it.copy(needsMfa = true, mfaHint = result.hint, mfaChannel = result.channel) }
                    ToastManager.show("需要二次认证")
                }
                is GdouSyncResult.Failed -> ToastManager.show(result.message)
            }
        }
    }

    private suspend fun busy(block: suspend () -> Unit) {
        _form.update { it.copy(isBusy = true) }
        try {
            block()
        } finally {
            _form.update { it.copy(isBusy = false) }
        }
    }
}
