package com.boxleits.vikunjaandroid.ui.outline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boxleits.vikunjaandroid.data.sync.EditResult
import com.boxleits.vikunjaandroid.data.sync.ProjectOutline
import com.boxleits.vikunjaandroid.data.sync.SyncRepository
import com.boxleits.vikunjaandroid.data.sync.SyncResult
import com.boxleits.vikunjaandroid.data.sync.TaskEditRepository
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
     * immediately and reverts by itself if the server rejects the change.
     */
    fun setDone(taskId: Long, done: Boolean) {
        viewModelScope.launch {
            when (val result = taskEditRepository.setDone(taskId, done)) {
                is EditResult.Error -> _errorMessage.value = result.message
                EditResult.Success -> Unit
            }
        }
    }

    fun dismissError() {
        _errorMessage.value = null
    }
}
