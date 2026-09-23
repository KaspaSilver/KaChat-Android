package com.kachat.app.repository

import com.kachat.app.models.BroadcastChannelEntity
import com.kachat.app.models.BroadcastMessageEntity
import com.kachat.app.models.BroadcastRetention
import com.kachat.app.models.HiddenBroadcastSenderEntity
import com.kachat.app.models.FeaturedBroadcastChannels
import com.kachat.app.models.ReactionEntity
import com.kachat.app.services.NetworkService
import com.kachat.app.services.WalletManager
import com.kachat.app.services.WalletService
import com.kachat.app.services.database.KaChatDatabase
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import com.kachat.app.util.MessageProtocol
import com.kachat.app.util.MessageReaction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Broadcast rooms — public, unencrypted, one-to-many channels identified by a plaintext channel
 * name (see MessageProtocol's bcast payload functions). Kept separate from [ChatRepository]:
 * channels/messages here have no contact concept, no per-account message scoping (the message
 * cache is a raw capture of public chain data, shared across whichever account is active), and no
 * encryption, so folding this into ChatRepository would mostly add conditionals rather than reuse.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class BroadcastRepository @Inject constructor(
    private val database: KaChatDatabase,
    private val walletManager: WalletManager,
    private val walletService: WalletService,
    private val networkService: NetworkService,
    private val settings: com.kachat.app.repository.AppSettingsRepository,
) {
    /** Channels joined by whichever account is currently active — re-emits automatically on account switch. */
    fun getJoinedChannels(): Flow<List<BroadcastChannelEntity>> {
        return walletManager.activeAddressFlow.flatMapLatest { address ->
            if (address == null) flowOf(emptyList()) else database.broadcastDao().getJoinedChannels(address)
        }
    }

    /** "Joining" and "creating" a channel are the same action — there's no ownership/membership protocol. */
    suspend fun joinChannel(rawName: String) {
        val name = MessageProtocol.normalizeChannelName(rawName)
        require(MessageProtocol.isValidChannelName(name)) { "Invalid channel name" }
        database.broadcastDao().joinChannel(
            BroadcastChannelEntity(channelName = name, walletAddress = walletManager.getAddress())
        )
    }

    /** Removes the channel from the joined list AND permanently deletes every cached message for it — the UI must confirm this with the user first, since it's destructive and can't be undone (rejoining later starts with no history). Featured rooms can't be left (matches iOS). */
    suspend fun leaveChannel(channelName: String) {
        // Curated rooms (Popular and the language rooms alike) are permanent fixtures of the
        // list screen, so no UI offers Leave; guard against stray paths.
        if (channelName in FeaturedBroadcastChannels.INDEXED_NAMES) return
        database.broadcastDao().leaveChannel(channelName, walletManager.getAddress())
        database.broadcastDao().deleteMessagesForChannel(channelName)
    }

    /**
     * The curated #kaspa/#kachat-bugs rooms are always present for every account (4.0, matches
     * iOS): auto-joined with the FIXED 3-day retention their indexer backfill serves. Idempotent
     * - joinChannel inserts with IGNORE, so an already-joined row (and its bell/listen toggles)
     * is left completely untouched.
     */
    suspend fun ensureFeaturedChannelsJoined() {
        val address = try { walletManager.getAddress() } catch (_: Exception) { return }
        for (name in FeaturedBroadcastChannels.NAMES) {
            database.broadcastDao().joinChannel(
                BroadcastChannelEntity(
                    channelName = name,
                    walletAddress = address,
                    retentionMillis = BroadcastRetention.MAX_MILLIS,
                )
            )
        }
    }

    /**
     * Per-channel opt-in to background scanning — the user chooses exactly which channels stay
     * live while the app is backgrounded, rather than one all-or-nothing setting. Turning this
     * off also turns notifications off for the channel (a channel that isn't being scanned has
     * no way to know about new messages to notify about).
     */
    suspend fun setAlwaysListen(channelName: String, alwaysListen: Boolean) {
        if (alwaysListen) {
            database.broadcastDao().setAlwaysListen(channelName, walletManager.getAddress(), true)
        } else {
            database.broadcastDao().disableAlwaysListenAndNotify(channelName, walletManager.getAddress())
        }
    }

    /** Per-channel opt-in to a notification for new messages — enabling this also turns always-listen on for the channel, since notifications depend on it actually being scanned. */
    suspend fun setNotifyEnabled(channelName: String, notifyEnabled: Boolean) {
        val address = walletManager.getAddress()
        if (notifyEnabled) {
            database.broadcastDao().enableNotifyAndAlwaysListen(channelName, address)
        } else {
            database.broadcastDao().disableNotify(channelName, address)
        }
    }

    /** Per-channel override of local message retention, set via the settings icon next to a channel — clamped to [1 second, BroadcastRetention.MAX_MILLIS] so the UI's 3-day cap can't be bypassed by a bad input. */
    suspend fun setRetentionMillis(channelName: String, retentionMillis: Long) {
        // Featured rooms have a FIXED 3-day retention (their indexer serves 3 days of history
        // and the room shows a permanent banner saying so) - no per-room override.
        if (channelName in FeaturedBroadcastChannels.INDEXED_NAMES) return
        val clamped = retentionMillis.coerceIn(1_000L, BroadcastRetention.MAX_MILLIS)
        database.broadcastDao().setRetentionMillis(channelName, walletManager.getAddress(), clamped)
    }

    /** The active account's always-listen channel names — non-empty drives whether background scanning runs at all, and membership decides which channels' messages actually get cached while it's running. */
    fun getAlwaysListenChannelNames(): Flow<Set<String>> {
        return walletManager.activeAddressFlow.flatMapLatest { address ->
            if (address == null) flowOf(emptySet()) else database.broadcastDao().getAlwaysListenChannelNames(address).map { it.toSet() }
        }
    }

    /** The active account's notify-enabled channel names — drives which channels' new messages fire a system notification. */
    fun getNotifyEnabledChannelNames(): Flow<Set<String>> {
        return walletManager.activeAddressFlow.flatMapLatest { address ->
            if (address == null) flowOf(emptySet()) else database.broadcastDao().getNotifyEnabledChannelNames(address).map { it.toSet() }
        }
    }

    /**
     * What the Public Chats list shows for one room: its newest message and how many messages
     * from other people are newer than the read marker. [lastMessage] is null for a room with
     * nothing in it yet. Counting stops at the bounded window it looks at, which is far more
     * than a badge ever needs to say.
     */
    data class RoomSummary(
        val lastMessage: BroadcastMessageEntity?,
        val unreadCount: Int,
    )

    /**
     * Newest message and unread count for [channelName], respecting the senders hidden in this
     * room and never counting reactions - they are not messages anyone reads. [lastReadMs] null
     * means the room has no marker yet, so nothing in it counts as unread.
     */
    fun roomSummary(channelName: String, myAddress: String?, lastReadMs: Long?): Flow<RoomSummary> {
        return combine(
            database.broadcastDao().getLatestMessagesForChannel(channelName, SUMMARY_WINDOW),
            getHiddenSenders(),
        ) { newestFirst, hidden ->
            val hiddenHere = hiddenAddressesIn(channelName, hidden)
            val readable = newestFirst.filterNot {
                it.senderAddress in hiddenHere || MessageReaction.parseOrNull(it.content) != null
            }
            val unread = if (lastReadMs == null) 0 else readable.count {
                it.blockTimestamp > lastReadMs && it.senderAddress != myAddress
            }
            RoomSummary(lastMessage = readable.firstOrNull(), unreadCount = unread)
        }
    }

    /** Never includes messages from a sender hidden IN THIS ROOM (or via a legacy every-room hide) — including ones already cached from before the hide (see BroadcastScanningService for the future-side enforcement). Reaction messages (see [getReactions]) never render as a message row, so they're filtered out here too. */
    fun getMessages(channelName: String): Flow<List<BroadcastMessageEntity>> {
        return combine(database.broadcastDao().getMessagesForChannel(channelName), getHiddenSenders()) { messages, hidden ->
            val hiddenHere = hiddenAddressesIn(channelName, hidden)
            messages.filterNot { it.senderAddress in hiddenHere || MessageReaction.parseOrNull(it.content) != null }
        }
    }

    /**
     * Reactions in [channelName], derived from the cached broadcast message rows themselves —
     * a reaction is just a broadcast whose content is the [MessageReaction] JSON (same wire
     * format as 1:1/group chats), so the rows the block scanner / indexer backfill already
     * persist ARE the reaction storage: they survive restarts, load with the channel's history,
     * and dedupe by txId, which keeps re-processing the indexer's repeated 200-row pages
     * harmless (unlike replaying add/remove events into a separate table would be).
     *
     * Aggregation matches group chat's semantics exactly: reactor = sender address, one reaction
     * per (target, reactor) with the newest blockTime winning, and a "remove" deleting the chip —
     * except a FAILED remove, which is restored marked failed (so it isn't silently lost and the
     * Retry under the message can re-attempt it), mirroring GroupRepository.sendGroupReaction's
     * failure handling. Emitted as in-memory [ReactionEntity] values (never persisted to the
     * `reactions` table — walletAddress is left blank) purely so the existing ReactionPill UI is
     * reused as-is; `reactionTxId` carries the reaction message row's own id for [retryReactionMessage].
     */
    fun getReactions(channelName: String): Flow<List<ReactionEntity>> {
        return combine(database.broadcastDao().getMessagesForChannel(channelName), getHiddenSenders()) { messages, hidden ->
            val hiddenHere = hiddenAddressesIn(channelName, hidden)
            val newestPerReactor = LinkedHashMap<Pair<String, String>, Pair<BroadcastMessageEntity, com.kachat.app.util.MessageReactionContent>>()
            for (row in messages) {
                if (row.senderAddress in hiddenHere) continue
                val parsed = MessageReaction.parseOrNull(row.content) ?: continue
                val key = parsed.targetTxId to row.senderAddress
                val existing = newestPerReactor[key]
                // >= so a tie is broken by row order (DAO orders by blockTimestamp ASC).
                if (existing == null || row.blockTimestamp >= existing.first.blockTimestamp) {
                    newestPerReactor[key] = row to parsed
                }
            }
            newestPerReactor.values.mapNotNull { (row, parsed) ->
                if (parsed.action != "add" && row.deliveryStatus != "failed") return@mapNotNull null
                ReactionEntity(
                    targetTxId = parsed.targetTxId,
                    walletAddress = "", // in-memory value object only, never inserted into the reactions table
                    reactorAddress = row.senderAddress,
                    emoji = parsed.emoji,
                    reactionTxId = row.id,
                    blockTimestamp = row.blockTimestamp,
                    deliveryStatus = row.deliveryStatus,
                    failedAction = if (row.deliveryStatus == "failed") parsed.action else null
                )
            }
        }
    }

    /** Re-attempts a reaction message whose send previously failed — [reactionMessageId] is the reaction's own broadcast message row id (see [getReactions]'s `reactionTxId`). */
    suspend fun retryReactionMessage(reactionMessageId: String?) {
        val row = reactionMessageId?.let { database.broadcastDao().getMessage(it) } ?: return
        retryBroadcast(row)
    }

    /** The active account's hidden-sender rows (per-room since 4.0; channelName "" = every room) — re-emits on account switch, same as everything else here. */
    fun getHiddenSenders(): Flow<List<HiddenBroadcastSenderEntity>> {
        return walletManager.activeAddressFlow.flatMapLatest { address ->
            if (address == null) flowOf(emptyList()) else database.broadcastDao().getHiddenSenders(address)
        }
    }

    companion object {
        /** How far back the Public Chats list looks in each room. A badge past this reads the
         *  same to anyone, and it keeps a busy curated room cheap to summarise. */
        const val SUMMARY_WINDOW = 200

        /** Which senders are hidden in [channelName]: room-scoped rows plus legacy every-room ("") rows. */
        fun hiddenAddressesIn(channelName: String, rows: List<HiddenBroadcastSenderEntity>): Set<String> =
            rows.filter { it.channelName.isEmpty() || it.channelName == channelName }
                .map { it.senderAddress }
                .toSet()
    }

    /** Hides a sender in ONE room - their messages and notifications from that room disappear; other rooms are unaffected. */
    suspend fun hideSender(senderAddress: String, channelName: String) {
        database.broadcastDao().hideSender(
            HiddenBroadcastSenderEntity(senderAddress, walletManager.getAddress(), channelName)
        )
    }

    suspend fun unhideSender(senderAddress: String, channelName: String) {
        database.broadcastDao().unhideSender(senderAddress, walletManager.getAddress(), channelName)
    }

    // MARK: - Indexer backfill (4.0): the featured rooms are backed by the KaChat broadcast
    // indexer, so history sent while the app was closed appears on room open. Merge is
    // dedupe-by-txId via the DAO's REPLACE insert; hidden-sender filtering happens at read.

    /** Channels whose full 30-day history was already paged in this process — the deep backfill
     *  runs once per room per launch; the 8s poll then only needs the newest page. */
    private val deepBackfilledChannels = mutableSetOf<String>()

    /** Where an interrupted deep backfill picks up: the `before` cursor of the next page to ask
     *  for, and how many pages of the safety valve are left. Session-only, like the completed
     *  set above. Without this a single thrown page - one timeout on page 30 of a busy room -
     *  restarted the whole pager from page 1 on the next 8s tick, and kept doing so until every
     *  page happened to succeed in one go (iOS deepBackfillResume). */
    private val deepBackfillResume = mutableMapOf<String, Pair<Long, Int>>()

    /** Fetches history for [channelName] and merges it into the local cache. The FIRST call per
     *  channel per launch pages backwards (`before` = oldest blockTime seen) through the
     *  indexer's whole 30-day window — a single newest page (200 rows) meant busy rooms never
     *  loaded anywhere near what the indexer holds. Later calls fetch just the newest page.
     *  Returns the number of rows fetched, or -1 when the indexer is unreachable (callers treat
     *  that as "no backfill", nothing user-facing breaks). */
    /** This wallet's own address, or null before there is one. */
    fun myAddress(): String? = runCatching { walletManager.getAddress() }.getOrNull()

    /**
     * One small newest-page fetch for a room nobody is looking at, merged like any other indexer
     * page. Returns the rows that were NEW to the store, so the caller can banner what just
     * arrived (iOS 3c14b46). Reactions never render as rows, so they are left out.
     */
    suspend fun fetchNewestFromIndexer(channelName: String, limit: Int = 40): List<BroadcastMessageEntity> {
        val override = settings.broadcastIndexerOverrides.first()[channelName.trim().lowercase()]
        val api = (override?.let { networkService.broadcastIndexerApiFor(it) }
            ?: networkService.broadcastIndexerApi.value) ?: return emptyList()
        val hidden = hiddenAddressesIn(channelName, getHiddenSenders().first())
        val page = try {
            api.getBroadcasts(channel = channelName, limit = limit)
        } catch (e: Exception) {
            android.util.Log.w("BroadcastRepository", "Sweep fetch failed for $channelName", e)
            return emptyList()
        }
        val fresh = mutableListOf<BroadcastMessageEntity>()
        for (message in page.messages.orEmpty()) {
            val txId = message.txId ?: continue
            val sender = message.senderAddress ?: continue
            val content = message.content ?: continue
            if (sender in hidden) continue
            if (MessageReaction.parseOrNull(content) != null) continue
            val existing = database.broadcastDao().getMessage(txId)
            if (existing != null) {
                // A row this phone sent carries its own clock until the chain's time reaches it,
                // and the block scan may never have seen the block. The indexer's time is what
                // every other phone holds, and the chess arena orders seats and runs its clocks
                // by it, so take it (iOS 29bf054). Not "fresh": nothing to banner.
                val chainTime = message.blockTime
                if (chainTime != null && chainTime != existing.blockTimestamp) {
                    database.broadcastDao().insertMessage(existing.copy(blockTimestamp = chainTime, deliveryStatus = "sent"))
                }
                continue
            }
            val row = BroadcastMessageEntity(
                id = txId,
                channelName = channelName,
                senderAddress = sender,
                content = content,
                blockTimestamp = message.blockTime ?: System.currentTimeMillis(),
            )
            database.broadcastDao().insertMessage(row)
            fresh += row
        }
        return fresh
    }

    suspend fun backfillFromIndexer(channelName: String): Int {
        // This room's own indexer where it has one, the app-wide client otherwise. A broadcast is
        // on-chain, so any indexer watching the same network serves the same room.
        val override = settings.broadcastIndexerOverrides.first()[channelName.trim().lowercase()]
        val api = (override?.let { networkService.broadcastIndexerApiFor(it) }
            ?: networkService.broadcastIndexerApi.value) ?: return -1
        var fetched = 0
        val deep = channelName !in deepBackfilledChannels
        // Picking up an interrupted pager: everything newer than the recorded cursor is already
        // in the store from the attempt that recorded it.
        val resume = if (deep) deepBackfillResume[channelName] else null
        var before: Long? = resume?.first
        var pagesLeft = when {
            !deep -> 1
            resume != null -> resume.second
            else -> 50 // 50 × 200 = 10k rows, far beyond any real room
        }
        return try {
            val cutoff = System.currentTimeMillis() - BroadcastRetention.INDEXER_MILLIS
            // The one-time deep backfill pages at 200; the recurring 8s poll only needs the
            // newest sliver (dedupe-by-txId makes overlap harmless), so its page is small —
            // 200 rows every 8s was the single biggest steady-state indexer cost per open room.
            val pageLimit = if (deep) 200 else 30
            while (pagesLeft > 0) {
                pagesLeft -= 1
                val response = api.getBroadcasts(channel = channelName, limit = pageLimit, before = before)
                val messages = response.messages.orEmpty()
                for (row in messages) {
                    if (row.txId.isNullOrBlank() || row.senderAddress.isNullOrBlank() || row.content == null) continue
                    database.broadcastDao().insertMessage(
                        BroadcastMessageEntity(
                            id = row.txId,
                            channelName = channelName,
                            senderAddress = row.senderAddress,
                            content = row.content,
                            blockTimestamp = row.blockTime ?: System.currentTimeMillis(),
                            deliveryStatus = "sent"
                        )
                    )
                }
                fetched += messages.size
                if (!deep || response.hasMore != true || messages.isEmpty()) break
                val oldest = messages.mapNotNull { it.blockTime }.minOrNull() ?: break
                if (oldest < cutoff) break // older pages would be pruned anyway
                before = oldest
                // Recorded after every landed page: a thrown page resumes from here, not page 1.
                deepBackfillResume[channelName] = oldest to pagesLeft
            }
            // Marked done only once the pager actually finishes; a failed one keeps its resume
            // cursor (see the catch) and the next 8s poll continues from there.
            if (deep) {
                deepBackfilledChannels.add(channelName)
                deepBackfillResume.remove(channelName)
            }
            fetched
        } catch (e: Exception) {
            android.util.Log.w("BroadcastRepository", "Indexer backfill paused for $channelName at before=$before", e)
            -1
        }
    }

    /**
     * Inserts an optimistic "pending" placeholder immediately (before the network call) so the
     * message shows up in the room right away and stays visible even if the send fails — matches
     * ChatViewModel.sendMessage's exact pattern for 1:1 chats. On success the placeholder is
     * swapped for the real message (real txId, "sent"); on failure it flips to "failed" in place
     * with a Retry option, rather than disappearing silently.
     */
    suspend fun sendBroadcast(channelName: String, content: String, feeRateOverride: Long? = null): String {
        val myAddress = walletManager.getAddress()
        val pendingId = "pending_${java.util.UUID.randomUUID()}"
        database.broadcastDao().insertMessage(
            BroadcastMessageEntity(
                id = pendingId,
                channelName = channelName,
                senderAddress = myAddress,
                content = content,
                blockTimestamp = System.currentTimeMillis(),
                deliveryStatus = "pending"
            )
        )
        try {
            val txId = walletService.sendBroadcast(channelName, content, feeRateOverride = feeRateOverride).txId
            database.broadcastDao().deleteMessage(pendingId)
            database.broadcastDao().insertMessage(
                BroadcastMessageEntity(
                    id = txId,
                    channelName = channelName,
                    senderAddress = myAddress,
                    content = content,
                    blockTimestamp = System.currentTimeMillis(),
                    deliveryStatus = "sent"
                )
            )
            return txId
        } catch (e: Exception) {
            database.broadcastDao().updateMessageStatus(pendingId, "failed")
            throw e
        }
    }

    /** Re-attempts a failed broadcast, reusing its same "pending_<uuid>" id/content — the same placeholder resurrected, not a new message, matching ChatViewModel.retrySendMessage. */
    suspend fun retryBroadcast(message: BroadcastMessageEntity) {
        database.broadcastDao().updateMessageStatus(message.id, "pending")
        try {
            val txId = walletService.sendBroadcast(message.channelName, message.content).txId
            database.broadcastDao().deleteMessage(message.id)
            database.broadcastDao().insertMessage(
                BroadcastMessageEntity(
                    id = txId,
                    channelName = message.channelName,
                    senderAddress = message.senderAddress,
                    content = message.content,
                    blockTimestamp = System.currentTimeMillis(),
                    deliveryStatus = "sent"
                )
            )
        } catch (e: Exception) {
            database.broadcastDao().updateMessageStatus(message.id, "failed")
        }
    }
}
