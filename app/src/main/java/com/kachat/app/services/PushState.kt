package com.kachat.app.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether native FCM push is currently ACTIVE for this device — meaning the server, not the
 * in-app pollers, is the notification source for the push-covered surfaces (1:1 DMs, broadcast
 * channels, KaPosts pings), per PUSH_EXTENSIONS.md §4's "the app SUPPRESSES its own local/
 * scan-driven banners while in remote-push mode".
 *
 * Set true by [PushRegistrationManager] only after a registration round-trip SUCCEEDS while
 * system notifications are actually deliverable (POST_NOTIFICATIONS granted); flipped false on
 * any registration failure, on unregister, and whenever FCM itself is unavailable (no
 * google-services.json / no Play services — registration can't succeed then, so it never turns
 * on). Consumers ([ChatRepository], [BroadcastScanningService], [KaPostsNotificationPoller])
 * consult it ONLY at their notification-posting sites — data sync itself is never gated on it.
 * Group notifications are deliberately NOT gated anywhere: group push doesn't exist (the
 * registration is the LegacyV1 shape with no watched_group_ids), so the scanners stay the only
 * source for those.
 *
 * A separate dependency-free holder (rather than a flag on PushRegistrationManager) because the
 * manager depends on ChatRepository/BroadcastRepository — the very classes that need to read the
 * flag — and Dagger can't resolve that cycle.
 */
@Singleton
class PushState @Inject constructor() {
    private val _pushActive = MutableStateFlow(false)

    /** Observable form, for anything that wants to react to the mode changing. */
    val pushActive: StateFlow<Boolean> = _pushActive

    /** Cheap synchronous read for the notification-posting guard sites. */
    val isActive: Boolean get() = _pushActive.value

    internal fun setActive(active: Boolean) {
        _pushActive.value = active
    }

    /**
     * Read-only debugging surface for Settings > Notifications ("why aren't pushes arriving?").
     * Updated by [PushRegistrationManager] around every registration/unregistration attempt;
     * everything here is also logged under the `KaChatPush` logcat tag, so
     * `adb logcat -s KaChatPush` tells the same story with history.
     */
    data class PushDiagnostics(
        /** Epoch ms of the most recent register/unregister attempt; null = none this process. */
        val lastAttemptAtMs: Long? = null,
        /** Outcome of that attempt; null = still none / in flight. */
        val lastAttemptSucceeded: Boolean? = null,
        /** Human-readable failure reason from the last FAILED attempt; null after a success. */
        val lastError: String? = null,
        /** Whether an FCM token could be obtained (false = no google-services/Play services). */
        val fcmTokenPresent: Boolean = false,
        /** What the last attempt was, e.g. "register", "unregister". */
        val lastAction: String? = null,
    )

    private val _diagnostics = MutableStateFlow(PushDiagnostics())
    val diagnostics: StateFlow<PushDiagnostics> = _diagnostics

    internal fun recordAttempt(
        action: String,
        succeeded: Boolean,
        error: String?,
        fcmTokenPresent: Boolean,
    ) {
        _diagnostics.value = PushDiagnostics(
            lastAttemptAtMs = System.currentTimeMillis(),
            lastAttemptSucceeded = succeeded,
            lastError = error,
            fcmTokenPresent = fcmTokenPresent,
            lastAction = action,
        )
    }
}
