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
    // Actor naming (iOS parity): your saved contact name wins, then their KNS domain,
    // then the shortened address — never a bare address when a better name exists.
    private val chatRepository: com.kachat.app.repository.ChatRepository,
    private val knsService: KnsService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    /** Session cache: one alias/KNS resolution per actor per process, not one per poll. */
    private val actorNameCache = mutableMapOf<String, String>()

    private suspend fun actorDisplayName(address: String): String {
        actorNameCache[address]?.let { return it }
        val contact = try { chatRepository.getContacts().first().find { it.id == address } } catch (_: Exception) { null }
        val alias = contact?.alias?.trim().orEmpty()
        val fallback = address.takeLast(10)
        val name = if (alias.isNotEmpty()) {
            alias.removeSuffix(".kas")
        } else {
            val domain = contact?.knsName?.trim().orEmpty().ifEmpty {
                try { knsService.getExplicitPrimaryDomain(address) ?: knsService.reverseResolve(address) ?: "" }
                catch (_: Exception) { "" }
            }
            if (domain.isNotEmpty()) domain.removeSuffix(".kas") else fallback
        }
        // Only cache real resolutions — a network miss must not pin the short-address
        // fallback for the rest of the session. Bounded: clear wholesale past 500 entries.
        if (actorNameCache.size > 500) actorNameCache.clear()
        if (name != fallback) actorNameCache[address] = name
        return name
    }

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
                title = "${actorDisplayName(actor)} ${actionText(n.contentType, n.voteType, text)}",
                body = text.take(90),
                timestampMs = n.timestamp,
                // Same per-kind target rule as the banner + in-app overlay. A reply targets
                // its PARENT post (contentId = the post replied to): opening the reply's own
                // txid as a thread root showed the comment with no parent above it.
                targetId = when (n.contentType) {
                    "reply" -> n.contentId?.takeIf { it.isNotEmpty() } ?: n.id
                    "quote" -> if (text.isEmpty()) n.contentId else n.id
                    "follow" -> null
                    // A mention's acting content IS the post/comment mentioning you — fall back
                    // to the notification's own txid when contentId is empty, else no target.
                    "mention" -> n.contentId?.takeIf { it.isNotEmpty() } ?: n.id
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
        // Foreground policy: this poller only runs while the app is on screen, and in-app pings
        // must fire there too — so remote-push mode no longer silences it wholesale. The
        // actionTxId dedupe inside NotificationHelper.showKaPosts collapses a racing push for
        // the same action into one banner.
        if (pushState.isActive && !notificationHelper.isAppInForeground) return
        // Oldest first, capped so a viral post can't fire fifty pings at once.
        for (n in fresh.sortedBy { it.timestamp }.takeLast(5)) {
            val actor = KaPostsService.kaspaAddressFromPubkey(n.userPublicKey) ?: continue
            if (actor == address) continue
            val text = KaPostsProtocol.stripMarker(n.decodedContent ?: "").trim()
            val name = actorDisplayName(actor)
            notificationHelper.showKaPosts(
                text = "$name ${actionText(n.contentType, n.voteType, text)}" + if (text.isEmpty()) "" else ": ${text.take(120)}",
                actionTxId = n.id,
                // Same per-kind target rule as the in-app notifications overlay: a reply opens
                // its PARENT post's thread (parent on top, the new reply underneath);
                // quote-with-text opens the quote itself; vote/mention open the acted-on post.
                postTxId = when (n.contentType) {
                    "reply" -> n.contentId?.takeIf { it.isNotEmpty() } ?: n.id
                    "quote" -> if (text.isEmpty()) n.contentId else n.id
                    "follow" -> null
                    // Same mention fallback as the bell targetId above.
                    "mention" -> n.contentId?.takeIf { it.isNotEmpty() } ?: n.id
                    else -> n.contentId
                },
                // The reply's own txid: the opened parent thread scrolls to this comment.
                focusTxId = if (n.contentType == "reply") n.id else null,
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
