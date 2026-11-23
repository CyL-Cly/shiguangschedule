package com.xingheyuzhuan.shiguangschedule.tool.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.annotation.Single

/** [AppUpdateController] 的 JVM 占位实现：更新功能仅 Android 可用。 */
@Single
class StubUpdateController : AppUpdateController {

    private val _state = MutableStateFlow<UpdateKitState>(UpdateKitState.Idle)
    override val state: StateFlow<UpdateKitState> = _state

    override fun checkOnStartup() = Unit

    override fun checkManual() {
        _state.value = UpdateKitState.Error
    }

    override fun download(manifest: UpdateManifest) = Unit

    override fun downloadLatest(): Boolean = false

    override fun dismiss() = Unit

    override fun resetDownload() = Unit

    override fun requestInstall(): Boolean = true
}
