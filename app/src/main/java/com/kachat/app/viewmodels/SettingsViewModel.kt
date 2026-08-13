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
    pushState: com.kachat.app.services.PushState,
    private val childModeService: com.kachat.app.services.ChildModeService
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

    // ------------------------------------------------------------------
    // Child Mode (Settings > Security > Child Mode + the Welcome Guide's
    // "Who will use KaChat?" step). NEVER biometrics anywhere in these
    // flows — only the password counts. See ChildModeService.
    // ------------------------------------------------------------------

    val childModeEnabled = settings.childModeEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Synchronous (EncryptedSharedPreferences-backed) — safe to call from composition. */
    fun hasChildModePassword(): Boolean = childModeService.hasPassword()

    fun verifyChildModePassword(password: String): Boolean = childModeService.verifyPassword(password)

    /** Stores the salted hash. Returns false if storing failed (degenerate empty password / crypto error). */
    fun setChildModePassword(password: String): Boolean =
        runCatching { childModeService.setPassword(password) }.isSuccess

    /** Wrong current password → false, nothing changes. */
    fun changeChildModePassword(current: String, newPassword: String): Boolean =
        runCatching { childModeService.changePassword(current, newPassword) }.getOrDefault(false)

    /** Turning ON with a password already set needs no password; turning OFF must go through
     *  [turnOffChildMode] so the flag can never be flipped off without the password verifying. */
    fun enableChildMode() = viewModelScope.launch { settings.setChildModeEnabled(true) }

    /** Verifies first; only then flips the persisted flag off. Wrong password → false, stays on. */
    fun turnOffChildMode(password: String): Boolean {
        if (!childModeService.verifyPassword(password)) return false
        viewModelScope.launch { settings.setChildModeEnabled(false) }
        return true
    }

    /** Full reset to never-configured: deletes the stored record and turns Child Mode off.
     *  Wrong password → false, nothing changes. */
    suspend fun clearChildModeConfiguration(password: String): Boolean =
        runCatching { childModeService.clearConfiguration(password) }.getOrDefault(false)

    /** The wizard's Adult/Child step was answered — persist the marker so relaunches stop
     *  re-presenting the guide. */
    fun markUserTypeChosen() = viewModelScope.launch { settings.markUserTypeChosen() }
}
