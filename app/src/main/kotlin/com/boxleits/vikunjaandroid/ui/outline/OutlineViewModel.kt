package com.boxleits.vikunjaandroid.ui.outline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boxleits.vikunjaandroid.data.sync.EditResult
import com.boxleits.vikunjaandroid.data.sync.ProjectOutline
import com.boxleits.vikunjaandroid.data.sync.SyncRepository
import com.boxleits.vikunjaandroid.data.sync.SyncResult
import com.boxleits.vikunjaandroid.data.sync.TaskEditRepository
import com.boxleits.vikunjaandroid.data.sync.TaskConflict
import com.boxleits.vikunjaandroid.data.sync.TaskQueryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OutlineViewModel @Inject constructor(
    taskQueryRepository: TaskQueryRepository,
    private val syncRepository: SyncRepository,
    private val taskEditRepository: TaskEditRepository,
) : ViewModel() {

    val outline: StateFlow<List<ProjectOutline>> = taskQueryRepository.observeOutline()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * Changes that were undone because the server had a newer version. Read
     * from the database rather than kept in memory: the flush that finds a
     * conflict often runs in a background worker, so the news has to survive
     * until the user actually opens the app.
     */
    val conflicts: StateFlow<List<TaskConflict>> = taskEditRepository.observeConflicts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun acknowledgeConflicts() {
        viewModelScope.launch { taskEditRepository.acknowledgeConflicts() }
    }

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
     * The repository flips the row locally first, so the checkbox reacts
     * immediately. A change that can't reach the server right now stays
     * applied and queued; only an outright rejection rolls it back.
     */
    fun setDone(taskId: Long, done: Boolean) {
        viewModelScope.launch {
            when (val result = taskEditRepository.setDone(taskId, done)) {
                EditResult.Synced -> Unit
                // Deliberately silent: the conflict dialog says it better, and
                // says it again later if the app isn't open when it happens.
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
