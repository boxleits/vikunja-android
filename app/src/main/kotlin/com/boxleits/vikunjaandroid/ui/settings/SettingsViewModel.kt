package com.boxleits.vikunjaandroid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boxleits.vikunjaandroid.core.agenda.AgendaHorizon
import com.boxleits.vikunjaandroid.core.agenda.AgendaSort
import com.boxleits.vikunjaandroid.data.settings.SettingsRepository
import com.boxleits.vikunjaandroid.data.settings.VikunjaSettings
import com.boxleits.vikunjaandroid.data.settings.AgendaSettings
import com.boxleits.vikunjaandroid.data.sync.SyncRepository
import com.boxleits.vikunjaandroid.data.sync.SyncResult
import com.boxleits.vikunjaandroid.data.sync.SyncScheduler
import com.boxleits.vikunjaandroid.data.sync.TaskEditRepository
import com.boxleits.vikunjaandroid.data.sync.WidgetRefresher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val syncRepository: SyncRepository,
    private val syncScheduler: SyncScheduler,
    private val widgetRefresher: WidgetRefresher,
    taskEditRepository: TaskEditRepository,
) : ViewModel() {

    val settings: StateFlow<VikunjaSettings?> = settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val lastSyncedAt: StateFlow<Instant?> = settingsRepository.lastSyncedAtFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Edits applied on this device that the server hasn't accepted yet. */
    val pendingEditCount: StateFlow<Int> = taskEditRepository.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val agendaSettings: StateFlow<AgendaSettings> = settingsRepository.agendaSettingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AgendaSettings.DEFAULT)

    fun setAgendaHorizon(horizon: AgendaHorizon) {
        viewModelScope.launch {
            settingsRepository.setAgendaHorizon(horizon)
            // The widget only redraws when asked, so a setting change has to
            // push it rather than wait for the next sync.
            widgetRefresher.refresh()
        }
    }

    fun setAgendaSort(sort: AgendaSort) {
        viewModelScope.launch {
            settingsRepository.setAgendaSort(sort)
            widgetRefresher.refresh()
        }
    }

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun syncNow() {
        if (_isSyncing.value) return
        viewModelScope.launch {
            _isSyncing.value = true
            when (val result = syncRepository.sync()) {
                is SyncResult.Error -> _errorMessage.value = result.exception.message
                else -> _errorMessage.value = null
            }
            _isSyncing.value = false
        }
    }

    fun dismissError() {
        _errorMessage.value = null
    }

    /**
     * No callback to leave the screen: clearing the settings makes
     * AppViewModel.isConfigured false, and the app swaps the whole scaffold for
     * onboarding on its own.
     */
    fun logOut() {
        viewModelScope.launch {
            syncScheduler.cancelPeriodicSync()
            syncRepository.logOut()
        }
    }
}
