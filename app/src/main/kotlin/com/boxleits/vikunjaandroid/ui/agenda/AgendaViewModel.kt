package com.boxleits.vikunjaandroid.ui.agenda

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boxleits.vikunjaandroid.core.agenda.AgendaSections
import com.boxleits.vikunjaandroid.core.agenda.limitedTo
import com.boxleits.vikunjaandroid.core.repository.TaskEdits
import com.boxleits.vikunjaandroid.data.settings.SettingsRepository
import com.boxleits.vikunjaandroid.data.sync.EditResult
import com.boxleits.vikunjaandroid.data.sync.SyncRepository
import com.boxleits.vikunjaandroid.data.sync.SyncResult
import com.boxleits.vikunjaandroid.data.sync.TaskConflict
import com.boxleits.vikunjaandroid.data.sync.TaskEditRepository
import com.boxleits.vikunjaandroid.data.sync.TaskQueryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AgendaViewModel @Inject constructor(
    taskQueryRepository: TaskQueryRepository,
    settingsRepository: SettingsRepository,
    private val syncRepository: SyncRepository,
    private val taskEditRepository: TaskEditRepository,
) : ViewModel() {

    /**
     * The agenda as configured in Settings — the same range and order the
     * widget uses, so the two can't disagree about what "my agenda" means.
     */
    val agenda: StateFlow<AgendaSections> = combine(
        taskQueryRepository.observeAgenda(),
        settingsRepository.agendaSettingsFlow,
    ) { sections, settings ->
        sections.limitedTo(settings.horizon, settings.sort)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AgendaSections.EMPTY)

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun refresh() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            when (val result = syncRepository.sync()) {
                is SyncResult.Error -> _errorMessage.value = result.exception.message
                else -> _errorMessage.value = null
            }
            _isRefreshing.value = false
        }
    }

    /**
     * The same conflict notices the outline shows. Read here too because the
     * agenda is where a due date gets edited, and the news has to reach
     * whichever screen the user is on when it arrives.
     */
    val conflicts: StateFlow<List<TaskConflict>> = taskEditRepository.observeConflicts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun acknowledgeConflicts() {
        viewModelScope.launch { taskEditRepository.acknowledgeConflicts() }
    }

    fun updateTask(taskId: Long, edits: TaskEdits) {
        viewModelScope.launch {
            when (val result = taskEditRepository.updateTask(taskId, edits)) {
                EditResult.Synced -> Unit
                EditResult.Conflicted -> Unit
                is EditResult.Queued ->
                    _errorMessage.value = "Saved on this device — will sync when possible (${result.reason})"
                is EditResult.Rejected -> _errorMessage.value = result.message
            }
        }
    }

    fun dismissError() {
        _errorMessage.value = null
    }
}
