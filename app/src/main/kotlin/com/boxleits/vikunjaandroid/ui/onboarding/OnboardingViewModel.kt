package com.boxleits.vikunjaandroid.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boxleits.vikunjaandroid.data.settings.SettingsRepository
import com.boxleits.vikunjaandroid.data.settings.VikunjaSettings
import com.boxleits.vikunjaandroid.data.sync.SyncRepository
import com.boxleits.vikunjaandroid.data.sync.SyncResult
import com.boxleits.vikunjaandroid.data.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingUiState(
    val baseUrl: String = "",
    val apiToken: String = "",
    val isConnecting: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val syncRepository: SyncRepository,
    private val syncScheduler: SyncScheduler,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun onBaseUrlChanged(value: String) {
        _uiState.update { it.copy(baseUrl = value, errorMessage = null) }
    }

    fun onApiTokenChanged(value: String) {
        _uiState.update { it.copy(apiToken = value, errorMessage = null) }
    }

    fun connect() {
        val state = _uiState.value
        if (state.baseUrl.isBlank() || state.apiToken.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Enter both the server URL and an API token.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isConnecting = true, errorMessage = null) }
            settingsRepository.save(VikunjaSettings(baseUrl = state.baseUrl.trim(), apiToken = state.apiToken.trim()))

            when (val result = syncRepository.sync()) {
                SyncResult.Success -> {
                    syncScheduler.ensurePeriodicSyncScheduled()
                    _uiState.update { it.copy(isConnecting = false) }
                }
                is SyncResult.Error -> {
                    settingsRepository.clear()
                    _uiState.update { it.copy(isConnecting = false, errorMessage = result.exception.message) }
                }
                SyncResult.NotConfigured -> {
                    settingsRepository.clear()
                    _uiState.update { it.copy(isConnecting = false, errorMessage = "Something went wrong, please try again.") }
                }
            }
        }
    }
}
