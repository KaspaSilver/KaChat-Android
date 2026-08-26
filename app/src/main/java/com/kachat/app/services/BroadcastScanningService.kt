package com.kachat.app.services

import android.util.Log
import com.kachat.app.models.BroadcastMessageEntity
import com.kachat.app.models.BroadcastRetention
import com.kachat.app.models.FeaturedBroadcastChannels
import com.kachat.app.repository.BroadcastRepository
import com.kachat.app.services.database.KaChatDatabase
import com.kachat.app.services.grpc.KaspadConnection
import com.kachat.app.util.MessageReaction
import com.kachat.app.util.MessageReply
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.MessageProtocol
import com.kachat.app.util.VoiceMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import protowire.Rpc
import javax.inject.Inject
import javax.inject.Singleton

private fun String.hexToBytesOrNull(): ByteArray? {
    return try {
        if (isEmpty()) ByteArray(0) else chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    } catch (e: Exception) {
        null
    }
}

/**
 * Scanning for broadcast messages, on-demand from two independent sources that share one
 * underlying subscription via a simple reference count — but each source only causes messages for
 * *its own* channel(s) to actually be cached, not every joined channel:
 *  1. Any channel marked "always listen" (see [BroadcastRepository.setAlwaysListen], toggled via
 *     the speaker icon next to a channel in the broadcast list) — per-channel opt-in, off by
 *     default, keeps scanning in the background for as long as at least one channel wants it and
 *     the app process stays alive. There's no separate global setting: the user picks exactly
 *     which channels stay live rather than an all-or-nothing toggle.
 *  2. [startLiveViewing] — held open by a broadcast channel screen for as long as it's on
 *     screen, so messages appear live while actively viewing that specific room even if it isn't
 *     marked always-listen. Bounded to only while that specific screen is visible.
 *
 * There's no per-address query for a public "bcast" channel — unlike normal messages, which are
 * found via the indexer's by-address lookups — so this inspects every transaction in every new
 * block via the node's block-added notification stream (see
 * [com.kachat.app.services.grpc.KaspadConnection.subscribeToBlockAdded]), matching Kasia's own
 * approach. The subscription itself can't be scoped to one channel (Kaspa transactions have no
 * concept of a "channel" at the network level, only in how the app parses payloads) — every new
 * block is inspected regardless — but [processBlock] only *inserts* a message if its channel is in
 * the always-listen set or is currently being live-viewed, so toggling one channel's speaker icon
 * doesn't silently start caching every other joined channel's messages too.
 *
 * Builds a rolling local cache of wanted channels' messages, retained per channel for up to
 * BroadcastRetention.MAX_MILLIS (user configurable per channel via the settings icon) — there is
 * no way to retroactively query the blockchain for past broadcasts (no indexer supports it, and a
 * node's block-added subscription only pushes forward from the moment of subscription), so
 * history only exists for whatever window a given channel was actually wanted (always-listen or
 * live-viewed).
 */
@Singleton
class BroadcastScanningService @Inject constructor(
    private val nodePoolManager: NodePoolManager,
    private val database: KaChatDatabase,
    private val broadcastRepository: BroadcastRepository,
    private val notificationHelper: NotificationHelper,
    // Consulted ONLY at the notification-posting site: while native FCM push is active, the
    // server pushes bell-enabled channels' messages (PUSH_EXTENSIONS.md §2/§4) and a local
    // banner here too would be a duplicate. Scanning/caching itself is never gated on it.
    private val pushState: PushState,
    // Global notification center (Profile bell): live incoming channel rows are listed there,
    // session-gated and deduped inside the store.
    private val notificationCenter: GlobalNotificationCenterStore,
    // Metered gate — see reevaluate(): on cellular the full-block stream only runs for wanted
    // channels that have NO other delivery path, and only while the app is foregrounded.
    private val meteredNetwork: MeteredNetwork,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null
    private var lastPruneAt = 0L
    private var wantCount = 0
    private var alwaysListenWasWanted = false

    // Union of alwaysListenChannelNames/liveViewedChannelNames is exactly which channels'
    // messages get cached while scanning runs. notifyEnabledChannelNames is a further filter on
    // top of that for which of those also fire a system notification (see BroadcastRepository —
    // notify-enabled always implies always-listen, so this is always a subset of the union
    // above). Plain fields guarded by @Synchronized alongside acquire()/release(), since they're
    // mutated from whatever thread calls startLiveViewing()/its close() as well as this class's
    // own settings-observing coroutines.
    private var alwaysListenChannelNames: Set<String> = emptySet()
    private var liveViewedChannelNames: Set<String> = emptySet()
    private var notifyEnabledChannelNames: Set<String> = emptySet()
        private var hiddenSenderRows: List<com.kachat.app.models.HiddenBroadcastSenderEntity> = emptyList()

    init {
        // Self-observing rather than wired externally per-screen: this needs to hold/release its
        // "want" for the whole app's lifetime based purely on whether any channel is marked
        // always-listen, regardless of which screen (if any) is open — KaChatApplication
        // field-injects this class just to force Hilt to instantiate it (and so run this
        // observer) at app startup, since a @Singleton is otherwise only created lazily the first
        // time something actually requests it.
        scope.launch {
            broadcastRepository.getAlwaysListenChannelNames().collectLatest { names ->
                onAlwaysListenChannelsChanged(names)
            }
        }
        scope.launch {
            broadcastRepository.getNotifyEnabledChannelNames().collectLatest { names ->
                setNotifyEnabledChannelNames(names)
            }
        }
        scope.launch {
            broadcastRepository.getHiddenSenders().collectLatest { rows ->
                setHiddenSenderRows(rows)
            }
        }
        // The metered gate depends on foreground state and the network type — both can change
        // while demand (wantCount) is unchanged, so each change re-runs the start/stop decision.
        scope.launch {
            notificationHelper.appForegroundFlow.collect { reevaluate() }
        }
        scope.launch {
            meteredNetwork.isMeteredFlow.collect { reevaluate() }
        }
    }

    @Synchronized
    private fun setNotifyEnabledChannelNames(names: Set<String>) {
        notifyEnabledChannelNames = names
    }

    @Synchronized
    private fun isChannelNotifyEnabled(channelName: String): Boolean = channelName in notifyEnabledChannelNames

    @Synchronized
    private fun setHiddenSenderRows(rows: List<com.kachat.app.models.HiddenBroadcastSenderEntity>) {
        hiddenSenderRows = rows
    }

    /** Skips storing a sender's messages for a room they're hidden in (per-room since 4.0; "" rows hide everywhere) — see BroadcastRepository.getMessages for the display-side filter covering already-cached ones too. */
    @Synchronized
    private fun isSenderHidden(address: String, channelName: String): Boolean =
        hiddenSenderRows.any { it.senderAddress == address && (it.channelName.isEmpty() || it.channelName == channelName) }

    val isRunning: Boolean get() = scanJob?.isActive == true

    @Synchronized
    private fun onAlwaysListenChannelsChanged(names: Set<String>) {
        alwaysListenChannelNames = names
        val wanted = names.isNotEmpty()
        if (wanted != alwaysListenWasWanted) {
            alwaysListenWasWanted = wanted
            if (wanted) acquire() else release()
        } else {
            // Same demand, different channel mix — the metered gate's "any wanted channel
            // without another delivery path?" answer may have flipped.
            reevaluate()
        }
    }

    /**
     * Keeps scanning active — and messages for [channelName] specifically cached — only while the
     * caller holds onto the returned handle. Used by a broadcast channel screen so live messages
     * appear while it's open, without requiring that channel to be marked always-listen. Close the
     * handle (e.g. from a DisposableEffect) when the screen goes away; scanning only actually
     * stops once nothing else — including any always-listen channel — still wants it.
     */
    fun startLiveViewing(channelName: String): AutoCloseable {
        acquire()
        addLiveViewedChannel(channelName)
        var released = false
        return AutoCloseable {
            if (!released) {
                released = true
                removeLiveViewedChannel(channelName)
                release()
            }
        }
    }

    @Synchronized
    private fun addLiveViewedChannel(channelName: String) {
        liveViewedChannelNames = liveViewedChannelNames + channelName
        reevaluate()
    }

    @Synchronized
    private fun removeLiveViewedChannel(channelName: String) {
        liveViewedChannelNames = liveViewedChannelNames - channelName
        reevaluate()
    }

    @Synchronized
    private fun isChannelWanted(channelName: String): Boolean {
        return channelName in alwaysListenChannelNames || channelName in liveViewedChannelNames
    }

    @Synchronized
    private fun acquire() {
        wantCount++
        reevaluate()
    }

    @Synchronized
    private fun release() {
        wantCount = (wantCount - 1).coerceAtLeast(0)
        reevaluate()
    }

    /**
     * One start/stop decision for every input change (demand, foreground, network type,
     * channel mix). Unmetered (WiFi) keeps the original behavior exactly: stream whenever
     * anything wants it, foreground or background. On METERED networks the full-block stream
     * (roughly 1-4 GB/day if left running) is only justified for a wanted channel with NO
     * other delivery path — the featured indexer-backed rooms already get an 8s indexer poll
     * while open (BroadcastViewModel.startIndexerBackfill) plus a 30-day history backfill on
     * open, and their bell notifications ride remote push, so only non-indexed always-listen /
     * live-viewed channels need the stream — and even then only while the app is foregrounded
     * (backgrounded metered streaming trades the whole data budget for messages nobody is
     * watching; the retention cache just has a gap for that window, same as an app restart).
     */
    @Synchronized
    private fun reevaluate() {
        val demand = wantCount > 0
        val shouldRun = demand && (
            !meteredNetwork.isMetered ||
                (
                    notificationHelper.isAppInForeground &&
                        (alwaysListenChannelNames + liveViewedChannelNames)
                            .any { it !in FeaturedBroadcastChannels.INDEXED_NAMES }
                    )
            )
        if (shouldRun) startInternal() else stopInternal()
    }

    /** The exact connection the live NOTIFY_START went to — [stopInternal] must send its
     *  NOTIFY_STOP to THIS node. A fresh getBroadcastConnection() there could hand back a
     *  different (or newly dialed) node, leaving the actually-subscribed one streaming forever. */
    @Volatile
    private var subscribedConnection: KaspadConnection? = null

    private fun startInternal() {
        if (isRunning) return
        scanJob = scope.launch {
            while (true) {
                var conn: KaspadConnection? = null
                try {
                    conn = nodePoolManager.getBroadcastConnection()
                    // Captured BEFORE subscribing: a death/reconnect racing the NOTIFY_START
                    // must read as "generation moved" below, not get swallowed.
                    val subscribedGeneration = conn.connectionGeneration.value
                    val blocks = conn.subscribeToBlockAdded()
                    subscribedConnection = conn
                    coroutineScope {
                        val collector = launch { blocks.collect { block -> processBlock(block) } }
                        // The blocks Flow is a SharedFlow — its collector NEVER completes, so
                        // "collect returned" can never signal stream death (the old code waited
                        // on exactly that and so could never resubscribe after a failover). The
                        // real signal is the connection's generation counter, bumped on every
                        // stream death, reconnect, and close: when it moves past the value
                        // captured at subscribe time, the server-side NOTIFY_START state is
                        // gone and the loop must re-send it on whatever connection is best now.
                        conn.connectionGeneration.first { it != subscribedGeneration }
                        collector.cancel()
                    }
                } catch (e: Exception) {
                    Log.w("BroadcastScanningService", "Block scanning interrupted, retrying", e)
                } finally {
                    if (subscribedConnection === conn) subscribedConnection = null
                }
                delay(RETRY_DELAY_MS)
            }
        }
    }

    /** Must genuinely halt network/battery usage, not just stop writing to the DB — sends NOTIFY_STOP (to the exact node that was subscribed) before cancelling the collector. */
    private fun stopInternal() {
        // Read the subscribed connection BEFORE cancelling — the job's finally block clears it.
        val conn = subscribedConnection
        subscribedConnection = null
        scanJob?.cancel()
        scanJob = null
        if (conn != null) {
            scope.launch {
                try {
                    conn.unsubscribeFromBlockAdded()
                } catch (e: Exception) {
                    Log.w("BroadcastScanningService", "Failed to send NOTIFY_STOP", e)
                }
            }
        }
    }

    private suspend fun processBlock(block: Rpc.RpcBlock) {
        for (tx in block.transactionsList) {
            if (!tx.hasVerboseData()) continue
            val txId = tx.verboseData.transactionId
            if (txId.isBlank()) continue

            val payloadBytes = tx.payload.hexToBytesOrNull() ?: continue
            if (!MessageProtocol.isKaChatPayload(payloadBytes)) continue
            val parsed = MessageProtocol.parseBcastPayload(payloadBytes) ?: continue
            if (!isChannelWanted(parsed.channel)) continue

            // A broadcast is a self-stash transaction — its own output's scriptPublicKey
            // directly encodes the sender's address, no separate lookup needed.
            val senderAddress = tx.outputsList.firstOrNull()
                ?.let { KaspaAddress.addressFromScriptPublicKey(it.scriptPublicKey.scriptPublicKey) }
                ?: continue
            if (isSenderHidden(senderAddress, parsed.channel)) continue

            val blockTimestampMillis = if (block.hasHeader()) block.header.timestamp else System.currentTimeMillis()

            database.broadcastDao().insertMessage(
                BroadcastMessageEntity(
                    id = txId,
                    channelName = parsed.channel,
                    senderAddress = senderAddress,
                    content = parsed.content,
                    blockTimestamp = blockTimestampMillis
                )
            )

            // Global notification center (Profile bell): reactions never surface as rows.
            if (MessageReaction.parseOrNull(parsed.content) == null) {
                notificationCenter.recordBroadcastIfLive(
                    channel = parsed.channel,
                    senderAddress = senderAddress,
                    senderName = KaspaAddress.shortDisplay(senderAddress),
                    content = MessageReply.parseOrNull(parsed.content)?.text ?: parsed.content,
                    txId = txId,
                    blockTimeMs = blockTimestampMillis,
                )
            }

            // Foreground policy: while the app is on screen the scan posts the banner itself
            // (the channel open on screen is suppressed inside NotificationHelper); backgrounded
            // with push active, the server is the source. txId-deduped against a racing push.
            if (isChannelNotifyEnabled(parsed.channel) && (notificationHelper.isAppInForeground || !pushState.isActive)) {
                // A reaction's raw JSON must never surface in a notification — humanize it.
                // Otherwise unwrap a reply first so a voice reply's notification says "🎤 Audio
                // message" too, rather than showing the raw reply JSON (see MessageReply).
                val reaction = MessageReaction.parseOrNull(parsed.content)
                val displayContent = MessageReply.parseOrNull(parsed.content)?.text ?: parsed.content
                val notificationText = when {
                    reaction != null -> "Reacted ${reaction.emoji}"
                    VoiceMessage.parseOrNull(displayContent) != null -> "🎤 Audio message"
                    else -> displayContent
                }
                notificationHelper.showBroadcast(
                    channelName = parsed.channel,
                    title = "#${parsed.channel}",
                    text = notificationText,
                    dedupeTxId = txId
                )
            }
        }

        // Pruning on every single block would hammer the DB — Kaspa blocks arrive fast. Gate it
        // instead to roughly half the shortest retention any joined channel is currently
        // configured for (floor/ceiling below) — a fixed hourly gate would silently ignore a
        // channel someone deliberately set to a much shorter retention (e.g. to verify pruning
        // actually works) until the next hourly sweep finally caught up.
        val now = System.currentTimeMillis()
        val retentions = database.broadcastDao().getChannelRetentions()
        val pruneInterval = retentions.minOfOrNull { it.retentionMillis / 2 }
            ?.coerceIn(MIN_PRUNE_INTERVAL_MILLIS, MAX_PRUNE_INTERVAL_MILLIS)
            ?: MAX_PRUNE_INTERVAL_MILLIS
        if (now - lastPruneAt > pruneInterval) {
            lastPruneAt = now
            retentions.forEach { retention ->
                // Featured indexer-backed rooms keep the indexer's FULL 30-day window regardless
                // of the stored per-channel value (their retention gear is hidden in the UI) —
                // the 3-day cap was pruning history the backfill had just fetched.
                val effective = if (retention.channelName in FeaturedBroadcastChannels.INDEXED_NAMES) {
                    BroadcastRetention.INDEXER_MILLIS
                } else {
                    retention.retentionMillis
                }
                database.broadcastDao().deleteOlderThan(retention.channelName, now - effective)
            }
        }
    }

    companion object {
        private const val RETRY_DELAY_MS = 5_000L
        // Floor: never sweep more often than this even if every channel is set to a tiny retention.
        private const val MIN_PRUNE_INTERVAL_MILLIS = 5_000L
        // Ceiling: never wait longer than this between sweeps, matching the old fixed cadence for
        // channels at (or near) the 3-day default.
        private const val MAX_PRUNE_INTERVAL_MILLIS = 60L * 60 * 1000
    }
}
