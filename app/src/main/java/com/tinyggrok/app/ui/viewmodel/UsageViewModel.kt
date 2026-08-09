package com.tinyggrok.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinyggrok.app.data.local.SettingsRepository
import com.tinyggrok.app.data.repository.ApiUsageSnapshot
import com.tinyggrok.app.data.repository.BillingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UsageUiState(
    val isLoading: Boolean = false,
    val snapshot: ApiUsageSnapshot? = null,
    val errorMessage: String? = null,
    val hasManagementKey: Boolean = false,
    val hasTeamId: Boolean = false,
    val consumerPlan: String = SettingsRepository.CONSUMER_PLAN_FREE,
    val chatModel: String = ""
)

@HiltViewModel
class UsageViewModel @Inject constructor(
    private val billingRepository: BillingRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(UsageUiState())
    val uiState: StateFlow<UsageUiState> = _uiState

    init {
        viewModelScope.launch {
            settingsRepository.managementKey.collect { key ->
                _uiState.value = _uiState.value.copy(hasManagementKey = !key.isNullOrBlank())
            }
        }
        viewModelScope.launch {
            settingsRepository.teamId.collect { id ->
                _uiState.value = _uiState.value.copy(hasTeamId = !id.isNullOrBlank())
            }
        }
        viewModelScope.launch {
            settingsRepository.consumerPlan.collect { plan ->
                _uiState.value = _uiState.value.copy(consumerPlan = plan)
            }
        }
        viewModelScope.launch {
            settingsRepository.chatModel.collect { model ->
                _uiState.value = _uiState.value.copy(chatModel = model)
            }
        }
        refresh()
    }

    fun setConsumerPlan(plan: String) {
        viewModelScope.launch {
            settingsRepository.saveConsumerPlan(plan)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val managementKey = settingsRepository.managementKey.first().orEmpty().trim()
            val teamId = settingsRepository.teamId.first()?.trim()
            val chatModel = settingsRepository.chatModel.first()

            if (managementKey.isBlank()) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    snapshot = null,
                    errorMessage = null,
                    hasManagementKey = false,
                    chatModel = chatModel
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                isLoading = true,
                errorMessage = null,
                hasManagementKey = true,
                chatModel = chatModel
            )

            val result = billingRepository.fetchUsage(
                managementKey = managementKey,
                teamIdHint = teamId,
                chatModel = chatModel
            )

            result.fold(
                onSuccess = { snap ->
                    // Persist resolved team id if user hadn't set one
                    if (teamId.isNullOrBlank() && snap.teamId.isNotBlank()) {
                        settingsRepository.saveTeamId(snap.teamId)
                    }
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        snapshot = snap,
                        errorMessage = null,
                        hasTeamId = true
                    )
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        snapshot = null,
                        errorMessage = err.message ?: "Failed to load usage"
                    )
                }
            )
        }
    }
}
