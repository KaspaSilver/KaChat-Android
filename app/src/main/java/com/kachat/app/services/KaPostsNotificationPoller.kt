package com.kachat.app.services

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.kachat.app.util.KaPostsProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-app KaPosts notification pings, mirroring iOS's KaPostsNotificationService: while the app
 * process is alive, polls the indexer's notification stream every 60s and posts a local
 * notification for new actions on your content ("alice liked your post"). Last-seen is stored
 * per wallet so nothing replays, and opening the Notifications screen marks everything seen.
 */
@Singleton
class KaPostsNotificationPoller @Inject constructor(
    private val kaPostsService: KaPostsService,
    private val walletManager: WalletManager,
    private val notificationHelper: NotificationHelper,
    private val dataStore: DataStore<Preferences>,
    // While native FCM push is active the server sends the KaPosts pings (PUSH_EXTENSIONS.md
    // §3/§4), so the poller must not post duplicates — but it still polls: last-seen tracking
    // and the Notifications screen's data depend on it.
    private val pushState: PushState,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    private fun lastSeenKey(address: String) = longPreferencesKey("kaposts_notifs_last_seen_$address")

    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                try { pollOnce() } catch (e: Exception) { Log.w("KaPostsPoller", "poll failed", e) }
                delay(60_000)
            }
        }
    }

    /** The Notifications screen calls this with the newest timestamp it displayed. */
    suspend fun markSeen(address: String, upTo: Long) {
        dataStore.edit { prefs ->
            val key = lastSeenKey(address)
            if ((prefs[key] ?: 0L) < upTo) prefs[key] = upTo
        }
    }

    private suspend fun pollOnce() {
        val address = try { walletManager.getAddress() } catch (_: Exception) { return }
        val notifications = kaPostsService.fetchNotifications(limit = 50)
        val newest = notifications.maxOfOrNull { it.timestamp } ?: return
        val key = lastSeenKey(address)
        val lastSeen = dataStore.data.first()[key]
        if (lastSeen == null) {
            // First run for this wallet: baseline silently instead of replaying history.
            dataStore.edit { it[key] = newest }
            return
        }
        val fresh = notifications.filter { it.timestamp > lastSeen }
        if (fresh.isEmpty()) return
        dataStore.edit { it[key] = maxOf(newest, lastSeen) }
        // Remote-push mode: the server already pushed these (PUSH_EXTENSIONS.md §4) — advance
        // last-seen as usual, but don't post duplicate local pings.
        if (pushState.isActive) return
        // Oldest first, capped so a viral post can't fire fifty pings at once.
        for (n in fresh.sortedBy { it.timestamp }.takeLast(5)) {
            val actor = KaPostsService.kaspaAddressFromPubkey(n.userPublicKey) ?: continue
            if (actor == address) continue
            val text = KaPostsProtocol.stripMarker(n.decodedContent ?: "").trim()
            val action = when (n.contentType) {
                "vote" -> if (n.voteType == "downvote") "disliked your post" else "liked your post"
                "reply" -> "replied to your post"
                "quote" -> if (text.isEmpty()) "reposted your post" else "quoted your post"
                "follow" -> "followed you"
                else -> "interacted with your post"
            }
            val name = actor.takeLast(10)
            notificationHelper.showKaPosts(
                text = "$name $action" + if (text.isEmpty()) "" else ": ${text.take(120)}",
                actionTxId = n.id,
            )
        }
    }
}
