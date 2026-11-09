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
    private val pushRegistrationManager: com.kachat.app.services.PushRegistrationManager,
) : ViewModel() {

    val notificationsEnabled = settings.notificationsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val notificationSoundEnabled = settings.notificationSoundEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val notificationVibrationEnabled = settings.notificationVibrationEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val showFeeEstimate = settings.showFeeEstimate
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    /** Settings > Chats > Higher quality photos (iOS, off by default). */
    val higherQualityPhotos = settings.higherQualityPhotos
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val quickReactionEmojis = settings.quickReactionEmojis
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettingsRepository.DEFAULT_QUICK_REACTION_EMOJIS)

    fun setNotificationsEnabled(value: Boolean) = viewModelScope.launch { settings.setNotificationsEnabled(value) }

    /**
     * The Push Notifications switch turning on (iOS NotificationsHubPage.enablePushNotifications):
     * on straight away, then waits for the push service, and back off if it didn't take the
     * registration. False when it failed.
     */
    suspend fun enablePushNotifications(): Boolean {
        settings.setNotificationsEnabled(true)
        // This switch also gates local banners, where iOS's notification mode falls back to its
        // previous one: with no push possible here at all, turning it off for good would leave
        // no banners and no way back.
        if (!pushRegistrationManager.canRegister()) return true
        return try {
            pushRegistrationManager.registerAndWait()
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("SettingsViewModel", "Failed to enable push: ${e.message}")
            settings.setNotificationsEnabled(false)
            false
        }
    }

    fun setNotificationSoundEnabled(value: Boolean) = viewModelScope.launch { settings.setNotificationSoundEnabled(value) }
    fun setNotificationVibrationEnabled(value: Boolean) = viewModelScope.launch { settings.setNotificationVibrationEnabled(value) }
    fun setShowFeeEstimate(value: Boolean) = viewModelScope.launch { settings.setShowFeeEstimate(value) }
    fun setHigherQualityPhotos(value: Boolean) = viewModelScope.launch { settings.setHigherQualityPhotos(value) }
    fun setQuickReactionEmojis(value: List<String>) = viewModelScope.launch { settings.setQuickReactionEmojis(value) }

    // ------------------------------------------------------------------
    // Notifications hub subpages (Settings > Notifications)
    // ------------------------------------------------------------------

    val addressActivityNotificationsEnabled = settings.addressActivityNotificationsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Receive notifications for the wallet's own spending addresses - a cold-storage account
     *  has its own switch in its menu (iOS ba352a2). */
    val spendingReceiveNotifications = settings.spendingReceiveNotifications
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setSpendingReceiveNotifications(enabled: Boolean) {
        viewModelScope.launch { settings.setSpendingReceiveNotifications(enabled) }
    }

    fun setAddressActivityNotificationsEnabled(value: Boolean) = viewModelScope.launch { settings.setAddressActivityNotificationsEnabled(value) }
}
