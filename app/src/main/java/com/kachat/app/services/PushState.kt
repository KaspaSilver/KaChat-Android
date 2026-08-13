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
}
