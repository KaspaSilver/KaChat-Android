package com.kachat.app.services

import android.util.Log
import com.kachat.app.repository.GroupRepository
import com.kachat.app.services.grpc.KaspadConnection
import com.kachat.app.util.GroupCipher
import com.kachat.app.util.KaspaAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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

private fun hexPrefix(prefix: String): String = prefix.toByteArray(Charsets.US_ASCII).joinToString("") { "%02x".format(it) }

/**
 * Block-scan discovery for group chat's two on-chain payload types (`gcomm`/`gctl`) - mirrors
 * [BroadcastScanningService]'s subscription pattern. This is the real-time path; indexer catch-up
 * (`GroupRepository.syncGroups()` -> `/group-control/by-recipient`, `/group-messages/by-blinded-group-id`)
 * covers the gap for anything mined while the app wasn't alive to see it live - see `syncGroups()`'s
 * own doc comment, and its call sites in `ChatViewModel.refreshChats()` and `KaChatApplication`'s
 * app-foreground observer.
 *
 * Runs only while the wallet actually HAS at least one group AND the app is foregrounded AND
 * the network is unmetered. The block stream pushes every mined block (roughly 1-4 GB/day at
 * Kaspa's block rate), so subscribing "just in case" is by far the app's biggest data cost:
 *
 *  - **Zero groups**: nothing live to scan for. A `gctl_root` direct-add ("you were added to a
 *    group you've never heard of") does NOT need this stream — [GroupRepository.syncGroups] runs
 *    `GET /group-control/by-recipient` (cursored) unconditionally, before it looks at any local
 *    group state, so the invite arrives via the on-foreground catch-up in `KaChatApplication`,
 *    the 15-minute `SyncWorker`, or a manual refresh. The moment that invite creates the first
 *    local group record, the group-count flow below flips and live scanning starts.
 *  - **Backgrounded**: the stream would pull full blocks for hours on battery-exempted devices
 *    with nobody watching; the 15-minute `SyncWorker` catch-up is delivery there, exactly as it
 *    is for a closed app. Re-foregrounding restarts the stream (with a catch-up sync first).
 *  - **Metered (cellular)**: never stream, even with groups and foregrounded — full blocks over
 *    mobile data is the wrong trade. Group messages/invites still arrive via the same cursored
 *    indexer catch-ups (foreground sync, per-chat refresh, 15-min worker); the cost is latency
 *    (seconds-to-minutes instead of instant), not lost delivery.
 *
 * `gcomm` matches remain cheap no-ops when irrelevant, since they're filtered against known
 * groups downstream in [GroupRepository] regardless.
 *
 * Deliberately no invite-beacon (`ginv`) scanning - see [GroupRepository]'s class doc for why
 * that publicly-joinable join path was removed.
 */
@Singleton
class GroupScanningService @Inject constructor(
    private val nodePoolManager: NodePoolManager,
    private val groupRepository: GroupRepository,
    private val notificationHelper: NotificationHelper,
    private val meteredNetwork: MeteredNetwork
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null

    // ---- Live delivery from the indexer (iOS a56fb98) -------------------------------------
    //
    // While the app is open and the wallet has groups, the indexer's `since` reads are asked for
    // the open group every 5 s and for every group plus control every 30 s (slower on a metered
    // network). From the first answer on, the block stream is NOT registered for groups - it was
    // the largest data and battery cost in the app, for a few seconds' immediacy that a poll gives
    // up nothing anyone notices. An indexer without the reads (404/405/501) puts the stream back
    // for the session. The push server still covers the closed app, and catch-up still runs.

    /** The indexer answered a `since` read this session: the block stream stands down. */
    private val indexerLiveDelivery = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** The indexer said it has no `since` reads: asked no more this session. */
    @Volatile private var sinceUnsupported = false
    private var livePollJob: Job? = null
    /** Two cursors: the open group's fast poll must not move the one the all-groups pass reads
     *  from, or messages in other groups between the two would be skipped. */
    private var openGroupCursor = 0L
    private var allGroupsCursor = 0L
    // True once a live block scan has ever been established this process. Any scan started
    // after that point follows a gap (stream error, node failover, empty-pool window) during
    // which group txs may have been mined unseen - the block scan is purely live, so without
    // an indexer catch-up those messages would not appear until the next app foreground,
    // 15-min SyncWorker run, or manual pull-to-refresh (iOS runs the same catch-up on its
    // .rpcSubscriptionsRestored signal).
    private var hadLiveScan = false

    // Dual-read: write the new `kchat:` root, still scan for the legacy `ciph_msg:` root too.
    private val gcommPrefixHex = hexPrefix("kchat:1:gcomm:")
    private val gctlPrefixHex = hexPrefix("kchat:1:gctl:")
    private val legacyGcommPrefixHex = hexPrefix("ciph_msg:1:gcomm:")
    private val legacyGctlPrefixHex = hexPrefix("ciph_msg:1:gctl:")

    init {
        // Gated on the pool already having a proven-active node, not just hasActiveWallet alone -
        // starting the instant the wallet loads (right at cold app launch) forced
        // getBroadcastConnection() down its "nothing active yet" fallback path, opening a brand
        // new gRPC connection to an unproven seed at the exact moment the main pool is doing its
        // own cold-start discovery/probing - real contention that visibly delayed the app
        // connecting to any nodes at all. Waiting for at least one active node means this reuses
        // an already-established, already-healthy connection instead.
        //
        // The group-count / foreground / metered gates are the data-cost side — see the class
        // doc for why each is safe for invite and message delivery.
        scope.launch {
            combine(
                combine(
                    groupRepository.hasActiveWallet,
                    nodePoolManager.activeNodes,
                    groupRepository.getGroupCount(),
                    notificationHelper.appForegroundFlow,
                    meteredNetwork.isMeteredFlow
                ) { active, nodes, groupCount, foreground, metered ->
                    active && nodes.isNotEmpty() && groupCount > 0 && foreground && !metered
                },
                indexerLiveDelivery,
            ) { streamWanted, indexerLive ->
                // The stream is the fallback for an indexer without the `since` reads, not the
                // primary path.
                streamWanted && !indexerLive
            }.distinctUntilChanged().collectLatest { shouldRun ->
                onWalletActiveChanged(shouldRun)
            }
        }
        // The live poll runs while the app is in front with an account that has groups. Keyed on
        // the account too: a switch starts the new account's cursors from scratch.
        scope.launch {
            combine(
                groupRepository.activeWalletAddress,
                groupRepository.getGroupCount(),
                notificationHelper.appForegroundFlow,
            ) { address, groupCount, foreground ->
                if (address != null && groupCount > 0 && foreground) address else null
            }.distinctUntilChanged().collectLatest { address ->
                livePollJob?.cancel()
                if (address == null || sinceUnsupported) return@collectLatest
                openGroupCursor = 0L
                allGroupsCursor = 0L
                livePollJob = scope.launch { runLivePoll() }
            }
        }
    }

    private suspend fun runLivePoll() {
        var lastFullPass = 0L
        while (true) {
            val metered = meteredNetwork.isMetered
            val fullEvery = LIVE_POLL_ALL_GROUPS_MS * (if (metered) 2 else 1)
            val now = System.currentTimeMillis()
            if (now - lastFullPass >= fullEvery) {
                lastFullPass = now
                if (!applyLivePoll(onlyGroupId = null, includeControl = true, openGroup = false)) return
            } else {
                val open = notificationHelper.viewingGroupId
                if (open != null && !applyLivePoll(onlyGroupId = open, includeControl = false, openGroup = true)) return
            }
            delay(LIVE_POLL_OPEN_GROUP_MS * (if (metered) 3 else 1))
        }
    }

    /** One pass on the right cursor. False once the indexer turns out not to serve the reads. */
    private suspend fun applyLivePoll(onlyGroupId: String?, includeControl: Boolean, openGroup: Boolean): Boolean {
        val nowMs = System.currentTimeMillis()
        val cursor = (if (openGroup) openGroupCursor else allGroupsCursor)
            .takeIf { it > 0 } ?: (nowMs - LIVE_POLL_START_LOOKBACK_MS)
        when (val result = groupRepository.pollLiveGroupTraffic(onlyGroupId, includeControl, cursor)) {
            is GroupRepository.LiveGroupPoll.Answered -> {
                if (openGroup) openGroupCursor = result.newest else allGroupsCursor = result.newest
                if (!indexerLiveDelivery.value) {
                    Log.i("GroupScanningService", "Indexer serves group 'since' reads; live delivery moves off the block stream")
                    indexerLiveDelivery.value = true
                }
            }
            GroupRepository.LiveGroupPoll.Unsupported -> {
                Log.i("GroupScanningService", "Indexer has no group 'since' reads; groups stay on the block stream")
                sinceUnsupported = true
                indexerLiveDelivery.value = false
                return false
            }
            // The next tick tries again; the cursor stays where it was.
            GroupRepository.LiveGroupPoll.Failed -> Unit
        }
        return true
    }

    val isRunning: Boolean get() = scanJob?.isActive == true

    @Synchronized
    private fun onWalletActiveChanged(active: Boolean) {
        if (active) startInternal() else stopInternal()
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
                    val blocks = conn.subscribeToBlockAdded(owner = "groups")
                    subscribedConnection = conn
                    // Re-establishing after a gap: backfill from the indexer what the dead
                    // stream missed, then go live again - see hadLiveScan's doc comment.
                    if (hadLiveScan) {
                        try {
                            groupRepository.syncGroups()
                        } catch (e: Exception) {
                            Log.w("GroupScanningService", "Post-reconnect group catch-up failed", e)
                        }
                    }
                    hadLiveScan = true
                    coroutineScope {
                        val collector = launch { blocks.collect { block -> processBlock(block) } }
                        // The blocks Flow is a SharedFlow — its collector NEVER completes, so
                        // "collect returned" can never signal stream death. The real signal is
                        // the connection's generation counter, bumped on every stream death,
                        // reconnect, and close: when it moves past the value captured at
                        // subscribe time, the server-side NOTIFY_START state is gone and the
                        // loop must re-send it on whatever connection is best now.
                        conn.connectionGeneration.first { it != subscribedGeneration }
                        collector.cancel()
                    }
                } catch (e: Exception) {
                    Log.w("GroupScanningService", "Block scanning interrupted, retrying", e)
                } finally {
                    if (subscribedConnection === conn) subscribedConnection = null
                }
                delay(RETRY_DELAY_MS)
            }
        }
    }

    private fun stopInternal() {
        // Read the subscribed connection BEFORE cancelling — the job's finally block clears it.
        val conn = subscribedConnection
        subscribedConnection = null
        scanJob?.cancel()
        scanJob = null
        if (conn != null) {
            scope.launch {
                try {
                    conn.unsubscribeFromBlockAdded(owner = "groups")
                } catch (e: Exception) {
                    Log.w("GroupScanningService", "Failed to send NOTIFY_STOP", e)
                }
            }
        }
    }

    private suspend fun processBlock(block: Rpc.RpcBlock) {
        for (tx in block.transactionsList) {
            if (!tx.hasVerboseData()) continue
            val txId = tx.verboseData.transactionId
            if (txId.isBlank()) continue

            val payloadHex = tx.payload
            val matchesGcomm = payloadHex.startsWith(gcommPrefixHex) || payloadHex.startsWith(legacyGcommPrefixHex)
            val matchesGctl = payloadHex.startsWith(gctlPrefixHex) || payloadHex.startsWith(legacyGctlPrefixHex)
            if (!matchesGcomm && !matchesGctl) continue

            val payloadBytes = payloadHex.hexToBytesOrNull() ?: continue
            val payloadString = try { String(payloadBytes, Charsets.UTF_8) } catch (e: Exception) { continue }

            val blockTimestampMillis = if (block.hasHeader()) block.header.timestamp else System.currentTimeMillis()

            when {
                matchesGcomm -> {
                    val parsed = GroupCipher.parseGroupMessagePayload(payloadString) ?: continue
                    groupRepository.handleIncomingGroupMessage(parsed, txId, blockTimestampMillis)
                }
                matchesGctl -> {
                    // A control message is a self-stash transaction - its own output's
                    // scriptPublicKey directly encodes the sender's address, same as broadcast.
                    val senderAddress = tx.outputsList.firstOrNull()
                        ?.let { KaspaAddress.addressFromScriptPublicKey(it.scriptPublicKey.scriptPublicKey, groupRepository.addressPrefix()) }
                        ?: continue
                    groupRepository.handleIncomingControlMessage(GroupCipher.normalizeControlPayload(payloadString), senderAddress)
                }
            }
        }
    }

    companion object {
        private const val RETRY_DELAY_MS = 5_000L
    }
}

/** The open group's live poll interval (tripled on a metered network) - iOS livePollOpenGroupSeconds. */
private const val LIVE_POLL_OPEN_GROUP_MS = 5_000L

/** Every group plus control (doubled on a metered network) - iOS livePollAllGroupsSeconds. */
private const val LIVE_POLL_ALL_GROUPS_MS = 30_000L

/** A cursor's first pass looks back this far - iOS livePollStartLookbackMs. */
private const val LIVE_POLL_START_LOOKBACK_MS = 2 * 60 * 1000L
