package com.boxleits.vikunjaandroid.ui.agenda

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boxleits.vikunjaandroid.core.agenda.AgendaSections
import com.boxleits.vikunjaandroid.data.sync.SyncRepository
import com.boxleits.vikunjaandroid.data.sync.SyncResult
import com.boxleits.vikunjaandroid.data.sync.TaskQueryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private val EMPTY_AGENDA = AgendaSections(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

@HiltViewModel
class AgendaViewModel @Inject constructor(
    taskQueryRepository: TaskQueryRepository,
    private val syncRepository: SyncRepository,
) : ViewModel() {

    val agenda: StateFlow<AgendaSections> = taskQueryRepository.observeAgenda()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), EMPTY_AGENDA)

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

    fun dismissError() {
        _errorMessage.value = null
    }
}
