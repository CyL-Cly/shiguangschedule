package com.xingheyuzhuan.shiguangschedule.ui.settings.gdou

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.data.api.gdou.GdouSyncLogEntry
import com.xingheyuzhuan.shiguangschedule.data.repository.GdouJwxtRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class GdouSyncLogViewModel(
    private val repo: GdouJwxtRepository
) : ViewModel() {

    val logs: StateFlow<List<GdouSyncLogEntry>> = repo.syncLogFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )

    fun clearLog() {
        viewModelScope.launch { repo.clearSyncLog() }
    }
}
