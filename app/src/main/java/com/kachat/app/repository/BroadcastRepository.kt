package com.kachat.app.repository

import com.kachat.app.models.BroadcastChannelEntity
import com.kachat.app.models.BroadcastMessageEntity
import com.kachat.app.models.BroadcastRetention
import com.kachat.app.models.HiddenBroadcastSenderEntity
import com.kachat.app.models.FeaturedBroadcastChannels
import com.kachat.app.services.NetworkService
import com.kachat.app.services.WalletManager
import com.kachat.app.services.WalletService
import com.kachat.app.services.database.KaChatDatabase
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import com.kachat.app.util.MessageProtocol
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
    private val networkService: NetworkService
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
        if (channelName in FeaturedBroadcastChannels.NAMES) return
        database.broadcastDao().leaveChannel(channelName, walletManager.getAddress())
        database.broadcastDao().deleteMessagesForChannel(channelName)
    }

    /**
     * The curated #kaspa/#kachat-bugs rooms are always present for every account (4.0, matches
     * iOS): auto-joined with the FIXED 3-day retention their indexer backfill serves. Idempotent
     * - joinChannel's insert strategy keeps an already-joined row's toggles.
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
        if (channelName in FeaturedBroadcastChannels.NAMES) return
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

    /** Never includes messages from a sender hidden IN THIS ROOM (or via a legacy every-room hide) — including ones already cached from before the hide (see BroadcastScanningService for the future-side enforcement). */
    fun getMessages(channelName: String): Flow<List<BroadcastMessageEntity>> {
        return combine(database.broadcastDao().getMessagesForChannel(channelName), getHiddenSenders()) { messages, hidden ->
            val hiddenHere = hiddenAddressesIn(channelName, hidden)
            messages.filterNot { it.senderAddress in hiddenHere }
        }
    }

    /** The active account's hidden-sender rows (per-room since 4.0; channelName "" = every room) — re-emits on account switch, same as everything else here. */
    fun getHiddenSenders(): Flow<List<HiddenBroadcastSenderEntity>> {
        return walletManager.activeAddressFlow.flatMapLatest { address ->
            if (address == null) flowOf(emptyList()) else database.broadcastDao().getHiddenSenders(address)
        }
    }

    companion object {
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

    /** Fetches a page of history for [channelName] and merges it into the local cache. Returns the number of rows fetched, or -1 when the indexer is unreachable (callers treat that as "no backfill", nothing user-facing breaks). */
    suspend fun backfillFromIndexer(channelName: String): Int {
        val api = networkService.broadcastIndexerApi.value ?: return -1
        return try {
            val response = api.getBroadcasts(channel = channelName, limit = 200)
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
            messages.size
        } catch (e: Exception) {
            android.util.Log.w("BroadcastRepository", "Indexer backfill failed for $channelName", e)
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
