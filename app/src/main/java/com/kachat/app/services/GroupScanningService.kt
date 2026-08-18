package com.kachat.app.services

import android.util.Log
import com.kachat.app.repository.GroupRepository
import com.kachat.app.util.GroupCipher
import com.kachat.app.util.KaspaAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
 * Runs whenever a wallet is active - NOT gated on already having a joined group. A `gctl_root`
 * direct-add (from [GroupRepository.createGroup]/`addMember` on the *admin's* device) is a push
 * from someone who may be adding us to a group we've never heard of before, so there's no local
 * state to gate discovery on: if scanning only started once a group already existed locally, a
 * brand-new member could never receive the very message that creates that first local record.
 * `gcomm` matches remain cheap no-ops when irrelevant, since they're filtered against known
 * groups downstream in [GroupRepository] regardless.
 *
 * Deliberately no invite-beacon (`ginv`) scanning - see [GroupRepository]'s class doc for why
 * that publicly-joinable join path was removed.
 */
@Singleton
class GroupScanningService @Inject constructor(
    private val nodePoolManager: NodePoolManager,
    private val groupRepository: GroupRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null

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
        scope.launch {
            combine(groupRepository.hasActiveWallet, nodePoolManager.activeNodes) { active, nodes ->
                active && nodes.isNotEmpty()
            }.distinctUntilChanged().collectLatest { shouldRun ->
                onWalletActiveChanged(shouldRun)
            }
        }
    }

    val isRunning: Boolean get() = scanJob?.isActive == true

    @Synchronized
    private fun onWalletActiveChanged(active: Boolean) {
        if (active) startInternal() else stopInternal()
    }

    private fun startInternal() {
        if (isRunning) return
        scanJob = scope.launch {
            while (true) {
                try {
                    val blocks = nodePoolManager.getBroadcastConnection().subscribeToBlockAdded()
                    blocks.collect { block -> processBlock(block) }
                } catch (e: Exception) {
                    Log.w("GroupScanningService", "Block scanning interrupted, retrying", e)
                }
                delay(RETRY_DELAY_MS)
            }
        }
    }

    private fun stopInternal() {
        scanJob?.cancel()
        scanJob = null
        scope.launch {
            try {
                nodePoolManager.getBroadcastConnection().unsubscribeFromBlockAdded()
            } catch (e: Exception) {
                Log.w("GroupScanningService", "Failed to send NOTIFY_STOP", e)
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
