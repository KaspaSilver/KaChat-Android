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
 * foreground/background transitions), polls the indexer's notification stream every 30s and posts
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
    // KaPosts activity is counted here, not listed in the global center - see recordArrivals.
    private val unseenStore: KaPostsUnseenStore,
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

    /**
     * The open-conversation rule for KaPosts: while the Notifications list is on screen the
     * reader is already looking at the stream, so a banner for it is noise. The count and the
     * watermark still advance underneath (iOS isNotificationsScreenVisible).
     */
    @Volatile
    var isNotificationsScreenVisible: Boolean = false

    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                try { pollOnce() } catch (e: Exception) { Log.w("KaPostsPoller", "poll failed", e) }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Called when the app leaves the foreground — background KaPosts pings are push's job. */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * The Notifications screen calls this with the newest timestamp it displayed: what the user
     * has seen on screen shouldn't ping later, and whatever KaPosts banners are still sitting in
     * the shade come down with it (iOS markSeen + clearDeliveredNotifications).
     */
    suspend fun markSeen(address: String, upTo: Long) {
        notificationHelper.cancelKaPostsNotifications()
        if (upTo <= 0L) return
        dataStore.edit { prefs ->
            val key = lastSeenKey(address)
            if ((prefs[key] ?: 0L) < upTo) prefs[key] = upTo
        }
    }

    private suspend fun pollOnce() {
        val address = try { walletManager.getAddress() } catch (_: Exception) { return }
        // Child Mode removes KaPosts entirely - no polling, no count, no pings (iOS).
        if (settingsRepository.childModeEnabled.first()) return
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
        // Muted and blocked accounts are gone from every KaPosts surface, the bell count and the
        // shade included (iOS drops them from both the unseen ingest and the banner path).
        val hidden = settingsRepository.kapostsMuted.first() + settingsRepository.kapostsBlocked.first()
        // Counted, not listed. The KaPosts notifications screen already serves these rows from
        // the indexer with richer formatting, so keeping a second copy in the global center
        // reported the same like or reply twice and let one busy feed dominate the profile
        // bell's count. What the indexer cannot tell us is how many the user has not looked at,
        // which is what this feeds.
        //
        // Gated by the same per-kind switches as the banner. An earlier revision counted every
        // fresh action regardless, treating the bell as a record of activity and the switches as
        // being only about interruption - but the setting reads "do not tell me about this", and
        // switching Likes off only to find a hundred of them waiting on the badge is the switch
        // not working.
        var arrivals = 0
        for (n in freshAll) {
            val actor = KaPostsService.kaspaAddressFromPubkey(n.userPublicKey)
            if (actor == null || actor == address || actor in hidden) continue
            if (!settingsRepository.shouldNotifyKaPostsAction(n.contentType, n.voteType)) continue
            arrivals++
        }
        unseenStore.recordArrivals(arrivals)
        // Per-kind toggle filter (Likes/Reposts/Follows/Dislikes/Comments) applied at the
        // source, BEFORE the burst cap, so a disabled kind neither notifies nor consumes a
        // slot. The watermark above already advanced over filtered items.
        val fresh = freshAll.filter {
            settingsRepository.shouldNotifyKaPostsAction(it.contentType, it.voteType)
        }
        if (fresh.isEmpty()) return
        // The remote push is the only banner source while push is active - see PushState. The
        // bell count above is fed regardless; only the banner is the push's. A device with no
        // push at all still pings from here.
        if (pushState.isActive) return
        // The list itself is open: the reader sees these land in it, so no banner.
        if (isNotificationsScreenVisible) return
        // Oldest first, capped so a viral post can't fire fifty pings at once.
        for (n in fresh.sortedBy { it.timestamp }.takeLast(5)) {
            val actor = KaPostsService.kaspaAddressFromPubkey(n.userPublicKey) ?: continue
            if (actor == address || actor in hidden) continue
            val text = KaPostsProtocol.stripMarker(n.decodedContent ?: "").trim()
            val name = actorDisplayName(actor)
            notificationHelper.showKaPosts(
                text = "$name ${actionText(n.contentType, n.voteType, text)}" + if (text.isEmpty()) "" else ": ${text.take(120)}",
                actionTxId = n.id,
                // Same per-kind target rule as the in-app notifications overlay and iOS: a
                // reply targets the reply ITSELF, and the landing rule opens its parent's thread
                // with the reply spliced in and scrolled to; quote-with-text opens the quote;
                // vote/mention open the acted-on post.
                postTxId = when (n.contentType) {
                    "reply" -> n.id
                    "quote" -> if (text.isEmpty()) n.contentId else n.id
                    "follow" -> null
                    // Same mention fallback as the bell targetId above.
                    "mention" -> n.contentId?.takeIf { it.isNotEmpty() } ?: n.id
                    else -> n.contentId
                },
            )
        }
    }

    private companion object {
        /** Same cadence as iOS's KaPostsNotificationService. */
        const val POLL_INTERVAL_MS = 30_000L
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
