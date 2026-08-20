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
 * In-app KaPosts notification pings, mirroring iOS's KaPostsNotificationService: while the app is
 * in the FOREGROUND (KaChatApplication's process lifecycle observer calls [start]/[stop] on
 * foreground/background transitions), polls the indexer's notification stream every 60s and posts
 * a local notification for new actions on your content ("alice liked your post"). Once the app is
 * backgrounded or closed, the push service is the only KaPosts notification source — deliberately
 * no background continuation here, so push failures stay visible. Last-seen is stored per wallet
 * so nothing replays, and opening the Notifications screen marks everything seen.
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
    // Settings > Notifications > KaPosts: per-kind toggles filtered here at the poll source
    // (never at display), mirroring iOS — a toggled-off kind is dropped permanently: the
    // last-seen watermark advances regardless, so it never re-fires if re-enabled later.
    private val settingsRepository: com.kachat.app.repository.AppSettingsRepository,
    // Global notification center (bell on the Profile screen): every fresh KaPosts action is
    // listed there, independent of the per-kind OS-banner gates below.
    private val notificationCenter: GlobalNotificationCenterStore,
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

    /** Called when the app leaves the foreground — background KaPosts pings are push's job. */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
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
        val freshAll = notifications.filter { it.timestamp > lastSeen }
        dataStore.edit { it[key] = maxOf(newest, lastSeen) }
        // The global notification center lists EVERY fresh action (mentions included),
        // regardless of the per-kind banner gates or remote-push mode below.
        for (n in freshAll.sortedBy { it.timestamp }) {
            val actor = KaPostsService.kaspaAddressFromPubkey(n.userPublicKey) ?: continue
            if (actor == address) continue
            val text = KaPostsProtocol.stripMarker(n.decodedContent ?: "").trim()
            notificationCenter.record(
                id = "kaposts-${n.id}",
                source = "kaposts",
                title = "${actor.takeLast(10)} ${actionText(n.contentType, n.voteType, text)}",
                body = text.take(90),
                timestampMs = n.timestamp,
                // Same per-kind target rule as the banner + in-app overlay, so a bell-row
                // tap opens the reply itself for replies and the containing post otherwise.
                targetId = when (n.contentType) {
                    "reply" -> n.id
                    "quote" -> if (text.isEmpty()) n.contentId else n.id
                    "follow" -> null
                    else -> n.contentId
                },
            )
        }
        // Per-kind toggle filter (Likes/Reposts/Follows/Dislikes/Comments) applied at the
        // source, BEFORE the burst cap, so a disabled kind neither notifies nor consumes a
        // slot. The watermark above already advanced over filtered items.
        val fresh = freshAll.filter {
            settingsRepository.shouldNotifyKaPostsAction(it.contentType, it.voteType)
        }
        if (fresh.isEmpty()) return
        // Remote-push mode: the server already pushed these (PUSH_EXTENSIONS.md §4) — advance
        // last-seen as usual, but don't post duplicate local pings.
        if (pushState.isActive) return
        // Oldest first, capped so a viral post can't fire fifty pings at once.
        for (n in fresh.sortedBy { it.timestamp }.takeLast(5)) {
            val actor = KaPostsService.kaspaAddressFromPubkey(n.userPublicKey) ?: continue
            if (actor == address) continue
            val text = KaPostsProtocol.stripMarker(n.decodedContent ?: "").trim()
            val name = actor.takeLast(10)
            notificationHelper.showKaPosts(
                text = "$name ${actionText(n.contentType, n.voteType, text)}" + if (text.isEmpty()) "" else ": ${text.take(120)}",
                actionTxId = n.id,
                // Same per-kind target rule as the in-app notifications overlay: reply/quote-with-
                // text open the reply itself; vote/mention open the containing post.
                postTxId = when (n.contentType) {
                    "reply" -> n.id
                    "quote" -> if (text.isEmpty()) n.contentId else n.id
                    "follow" -> null
                    else -> n.contentId
                },
            )
        }
    }

    private fun actionText(contentType: String?, voteType: String?, text: String): String = when (contentType) {
        "vote" -> if (voteType == "downvote") "disliked your post" else "liked your post"
        "reply" -> "replied to your post"
        "quote" -> if (text.isEmpty()) "reposted your post" else "quoted your post"
        "follow" -> "followed you"
        "mention" -> "mentioned you in a post"
        else -> "interacted with your post"
    }
}
