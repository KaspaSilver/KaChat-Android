package com.kachat.app.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kachat.app.repository.AppSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Settings screen.
 * Reads/writes app settings via AppSettingsRepository.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: AppSettingsRepository,
    pushState: com.kachat.app.services.PushState
) : ViewModel() {

    /** Read-only push diagnostics for Settings > Notifications (see PushState.PushDiagnostics). */
    val pushActive = pushState.pushActive
    val pushDiagnostics = pushState.diagnostics

    val network = settings.network
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettingsRepository.DEFAULT_NETWORK)

    val indexerUrl = settings.indexerUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettingsRepository.DEFAULT_INDEXER_URL)

    val knsApiUrl = settings.knsApiUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettingsRepository.DEFAULT_KNS_API_URL)

    val kaspaRestUrl = settings.kaspaRestUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettingsRepository.DEFAULT_KASPA_REST_URL)

    val notificationsEnabled = settings.notificationsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val notificationSoundEnabled = settings.notificationSoundEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val notificationVibrationEnabled = settings.notificationVibrationEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val showFeeEstimate = settings.showFeeEstimate
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val quickReactionEmojis = settings.quickReactionEmojis
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettingsRepository.DEFAULT_QUICK_REACTION_EMOJIS)

    fun saveNetwork(value: String) = viewModelScope.launch { settings.setNetwork(value) }
    fun saveIndexerUrl(value: String) = viewModelScope.launch { settings.setIndexerUrl(value) }
    fun saveKnsApiUrl(value: String) = viewModelScope.launch { settings.setKnsApiUrl(value) }
    fun saveKaspaRestUrl(value: String) = viewModelScope.launch { settings.setKaspaRestUrl(value) }
    fun setNotificationsEnabled(value: Boolean) = viewModelScope.launch { settings.setNotificationsEnabled(value) }
    fun setNotificationSoundEnabled(value: Boolean) = viewModelScope.launch { settings.setNotificationSoundEnabled(value) }
    fun setNotificationVibrationEnabled(value: Boolean) = viewModelScope.launch { settings.setNotificationVibrationEnabled(value) }
    fun setShowFeeEstimate(value: Boolean) = viewModelScope.launch { settings.setShowFeeEstimate(value) }
    fun setQuickReactionEmojis(value: List<String>) = viewModelScope.launch { settings.setQuickReactionEmojis(value) }
}
