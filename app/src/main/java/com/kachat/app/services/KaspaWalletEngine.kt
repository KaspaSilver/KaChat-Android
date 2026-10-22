package com.kachat.app.services

import android.util.Log
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaMass
import com.kachat.app.util.KaspaTransactionSigner
import com.kachat.app.util.KaspaUtxoSelector
import com.kachat.app.util.UtxoMath
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

/** Why a coin fetch could not even be asked: the node connection is not up yet. */
internal const val COIN_FETCH_NOT_CONNECTED = "Not connected to a Kaspa node yet. Try again in a moment."

/**
 * KaspaWalletEngine — handles low-level transaction construction and broadcasting.
 * Follows the required send flow: Fetch UTXOs -> Build -> Sign -> Broadcast.
 */

@Singleton
class KaspaWalletEngine @Inject constructor(
    private val networkService: NetworkService,
    private val walletManager: WalletManager,
    private val nodePoolManager: NodePoolManager,
    /** For a send's localized refusal (iOS da2c8e2, "Couldn't prepare the next address"). */
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) {
    // The REST UTXO endpoint lags behind what we've actually broadcast — sending twice
    // in quick succession (e.g. two chat messages back to back) would otherwise select
    // the same still-unconfirmed input for both, and the node rejects the second
    // transaction as an orphan. Track what we've locally already spent/created, keyed
    // by address (multi-account safe), and reconcile against every fresh fetch — an
    // entry is dropped once the REST endpoint's own list confirms it caught up, so this
    // never grows unbounded and never diverges from on-chain reality for long.
    private val pendingSpentOutpoints = mutableMapOf<String, MutableSet<String>>()
    private val pendingChangeUtxos = mutableMapOf<String, MutableList<UtxoEntry>>()

    /** When each pending spent key / pending change outpoint was recorded. An entry the chain
     *  never confirms (a chained send whose parent was dropped, an orphan that never found its
     *  parent) would otherwise sit in these caches forever and be re-selected by every later
     *  send, each rejected the same way. iOS expires both after 120s (pendingMessageUtxoTTL). */
    private val pendingRecordedAtMs = mutableMapOf<String, Long>()

    // Rapid-fire sends (e.g. several chat messages tapped in quick succession) each
    // spawn their own coroutine — without this, two could fetch the same UTXO snapshot
    // before either records its spend, both select the same input, and every one after
    // the first gets rejected by the node as a double-spend/orphan. Retrying doesn't
    // help on its own since a retry can race the same way. This mutex makes the whole
    // fetch -> select -> broadcast -> record sequence one atomic unit per send, so every
    // send observes the true state left by the one before it.
    private val sendMutex = Mutex()

    /**
     * Fresh UTXOs reconciled against our own not-yet-indexed sends: excludes anything we
     * know we've already spent, and adds back our own pending change output(s) so a
     * second send doesn't need to wait for the first to confirm.
     */
    private fun reconcileUtxos(address: String, freshUtxos: List<UtxoEntry>): List<UtxoEntry> {
        val spent = pendingSpentOutpoints.getOrPut(address) { mutableSetOf() }
        val change = pendingChangeUtxos.getOrPut(address) { mutableListOf() }
        val cutoff = System.currentTimeMillis() - PENDING_UTXO_TTL_MS
        spent.removeAll { key -> (pendingRecordedAtMs[key] ?: 0L) < cutoff }
        change.removeAll { (pendingRecordedAtMs[outpointKey(it.outpoint)] ?: 0L) < cutoff }
        pendingRecordedAtMs.entries.removeAll { it.value < cutoff }
        // A coin a scheduled post is waiting to spend is not available to anything else, or the
        // signed transaction would be invalid by the time its moment came (iOS's
        // KaPostsScheduledStore.filterReserved, applied at the same choke point).
        val available = KaPostsScheduledStore.filterReserved(freshUtxos)
        val ownScriptHex = try { KaspaAddress.getScriptPublicKey(address) } catch (_: Exception) { null }
        return reconcilePendingUtxos(available, spent, change, ownScriptHex)
    }

    /** Drops these pending change coins of [address] (iOS consumePendingUtxos): a chained coin
     *  whose recorded amount or script is wrong would fail every retry until it expires. */
    private fun dropPendingChange(address: String, coins: List<UtxoEntry>) {
        val keys = coins.map { outpointKey(it.outpoint) }.toSet()
        if (keys.isEmpty()) return
        pendingChangeUtxos[address]?.removeAll { outpointKey(it.outpoint) in keys }
    }

    /** Every input of a signature-rejected send, so the failure names its coin: amount, whether it
     *  was pending (a chained change output) or confirmed, and whether its script is the sender's
     *  own (iOS logSignatureRejection). */
    private fun logSignatureRejection(inputs: List<UtxoEntry>, senderScriptHex: String?) {
        Log.w("KaspaWalletEngine", "Signature rejected - ${inputs.size} input(s):")
        inputs.forEachIndexed { index, utxo ->
            val script = utxo.utxoEntry.scriptPublicKey.scriptPublicKey
            Log.w(
                "KaspaWalletEngine",
                "  input $index ${utxo.outpoint.transactionId.take(16)}:${utxo.outpoint.index} amount=${utxo.utxoEntry.amount} " +
                    "${if (utxo.utxoEntry.blockDaaScore == 0L) "PENDING" else "confirmed"} " +
                    "script=${if (senderScriptHex != null && script.equals(senderScriptHex, ignoreCase = true)) "sender" else "OTHER(${script.length / 2}B)"} " +
                    "coinbase=${if (utxo.utxoEntry.isCoinbase) "yes" else "no"}"
            )
        }
    }

    /** Call after a successful broadcast so the very next send (before this one confirms) doesn't reuse or miss these UTXOs. */
    private fun recordSpend(spentAddress: String, changeAddress: String, spentUtxos: List<UtxoEntry>, changeUtxo: UtxoEntry?) {
        val spent = pendingSpentOutpoints.getOrPut(spentAddress) { mutableSetOf() }
        val change = pendingChangeUtxos.getOrPut(changeAddress) { mutableListOf() }
        applySpend(spent, change, spentUtxos, changeUtxo)
        val now = System.currentTimeMillis()
        spentUtxos.forEach { pendingRecordedAtMs[outpointKey(it.outpoint)] = now }
        changeUtxo?.let { pendingRecordedAtMs[outpointKey(it.outpoint)] = now }
    }

    /** One accepted submit: what it spent and the change it left, for [recordSpend]. */
    private data class SubmittedSend(val txId: String, val spentUtxos: List<UtxoEntry>, val changeUtxo: UtxoEntry?)

    /** The node rejections that mean "state mismatch between nodes", not "bad transaction":
     *  the submit node is behind the one that served the UTXO snapshot, or behind a just-
     *  accepted send whose change was chained. Same test as iOS's shouldRetrySendError. */
    private fun isTransientNodeRejection(e: Exception): Boolean {
        val message = e.message?.lowercase() ?: return false
        return message.contains("orphan") || message.contains("already spent")
    }

    /** The node refused an input's signature (kaspad "failed to verify the signature script"),
     *  iOS isSignatureRejection. */
    private fun isSignatureRejection(e: Exception): Boolean =
        e.message?.lowercase()?.contains("signature script") == true

    /**
     * Sends Kaspa to a given address.
     * @param toAddress Recipient Kaspa address.
     * @param amountSompi Amount to send in sompi (1 KAS = 100,000,000 sompi).
     * @param fromAddress Address to source UTXOs/change from — defaults to the identity address
     * (every existing call site keeps working unchanged). [sendSpendingPayment] is the only
     * caller that passes a different value (the current spending address).
     * @param signingPrivateKey Key matching [fromAddress] — must be supplied together whenever
     * [fromAddress] is overridden, since the default [WalletManager.getPrivateKeyBytes] only
     * matches the default identity [fromAddress].
     * @param changeAddress Where leftover change goes — defaults to [fromAddress] (existing
     * behavior). The spending-address flow routes this to a freshly derived *next* address
     * instead, so a payment's change never lands on an address that has been used before.
     * @param feeRateOverride Sompi-per-mass-gram rate to use instead of the live network
     * estimate — e.g. from the Withdraw dialog's manual fee bump for a busy fee market. Still
     * floored at [KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM] like the live estimate is.
     * @param manualUtxos Coin control: spend exactly this fixed set instead of greedily picking
     * enough large UTXOs — resolved by outpoint against the freshly-reconciled `utxos` fetched
     * below, since the caller's selection may be stale (spent by another device, aged out) by
     * the time this actually runs.
     * @param extraFeeSompi A flat extra on top of the mass-priced fee (the Send KAS sheet's Fast /
     * Priority / custom fee, iOS 62c2773) - paid exactly, whichever selector runs.
     * @param senderRedeemScript A quantum-safe (KIP-22) [fromAddress]'s redeem script: pushed
     * after each signature and priced into the fee (iOS `buildPlainTransferTx(senderRedeemScript:)`).
     * Null for every P2PK source.
     * @return Result containing the transaction ID or an error.
     */
    suspend fun sendKaspa(
        toAddress: String,
        amountSompi: Long,
        payloadBytes: ByteArray? = null,
        fromAddress: String = walletManager.getAddress(),
        signingPrivateKey: ByteArray = walletManager.getPrivateKeyBytes(),
        changeAddress: String = fromAddress,
        feeRateOverride: Long? = null,
        manualUtxos: List<UtxoEntry>? = null,
        /**
         * When the wallet holds the amount but no fee on top, send the amount reduced by the
         * fee instead of failing. For handshakes: a handshake is recognised by its payload, not
         * its amount, so one carrying a little under 0.2 KAS opens the conversation just as well -
         * and it is the only way an account whose only coin IS a received handshake can ever
         * answer it (iOS buildHandshakeTx's third shape).
         */
        allowReducedAmount: Boolean = false,
        extraFeeSompi: Long = 0L,
        senderRedeemScript: ByteArray? = null
    ): Result<String> = sendMutex.withLock {
        try {
            // A quantum-safe input carries its redeem script after the signature, so every input
            // is priced at that bigger size.
            val signatureScriptSize = senderRedeemScript
                ?.let { KaspaMass.SCHNORR_SIGNATURE_SCRIPT_LEN + com.kachat.app.util.KnsInscriptionScript.canonicalPushSize(it.size) }
                ?: KaspaMass.SCHNORR_SIGNATURE_SCRIPT_LEN
            // 1. Validate address: well formed, and of the network the app runs on - the script
            //    is built from the payload alone, so a kaspatest: address on mainnet would pay real
            //    KAS to that key's mainnet script (iOS ce20e87, AND-011).
            KaspaAddress.otherNetworkReason(toAddress)?.let { return Result.failure(IllegalArgumentException(it)) }
            if (!isValidAddress(toAddress)) {
                return Result.failure(IllegalArgumentException("Invalid recipient address: $toAddress"))
            }

            val api = networkService.kaspaRestApi.value ?: return Result.failure(IllegalStateException("Network service unavailable"))

            // 2. Fetch UTXOs from node, reconciled against our own not-yet-indexed sends, then drop
            //    immature coinbase (mining rewards can't be spent until matured — see
            //    filterSpendableCoinbase). Mature coinbase and all non-coinbase UTXOs are kept.
            // Node first, REST second: api.kaspa.org rate-limits a burst of sends (HTTP 429) and
            // a node does not - see NodePoolManager.getUtxosByAddress.
            val fetched = nodePoolManager.getUtxosByAddress(fromAddress) ?: api.getUtxos(fromAddress)
            val reconciled = reconcileUtxos(fromAddress, fetched)
            val utxos = filterSpendableCoinbase(reconciled)
            if (utxos.isEmpty()) {
                val msg = if (reconciled.any { it.utxoEntry.isCoinbase }) {
                    "These are mining (coinbase) rewards that haven't matured yet. Each becomes spendable a short time (about 1–2 minutes) after it's mined — try again shortly."
                } else {
                    "Insufficient funds: No UTXOs found"
                }
                return Result.failure(IllegalStateException(msg))
            }

            // 3. Fetch network fee rate (sompi per mass-gram) — always at least the
            // network-enforced minimum, since a quoted rate below that would still
            // get rejected on broadcast. A caller-supplied override skips the live estimate
            // entirely (still floored the same way).
            val feeRateSompiPerGram = feeRateOverride?.coerceAtLeast(KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM)
                ?: fetchQuotedFeeRateSompiPerGram()

            val recipientScriptHex = KaspaAddress.getScriptPublicKey(toAddress)
            val changeScriptHex = KaspaAddress.getScriptPublicKey(changeAddress)
            val fromScriptHex = try { KaspaAddress.getScriptPublicKey(fromAddress) } catch (_: Exception) { null }

            // One build -> sign -> submit attempt against a fixed candidate set. Split out so
            // the transient-rejection fallback below can rerun the whole attempt against a
            // freshly fetched, confirmed-only input set (iOS buildSignSubmitBroadcast /
            // the 1:1 confirmed-only fallback).
            // The inputs the latest attempt signed, so a signature rejection can name and drop them.
            var attemptInputs: List<UtxoEntry> = emptyList()
            suspend fun attempt(candidates: List<UtxoEntry>): Result<SubmittedSend> {
            val utxos = candidates
            // 4. UTXO selection and fee calculation using Kaspa's real mass model
            val selectionResult = if (!manualUtxos.isNullOrEmpty()) {
                val freshByOutpoint = utxos.associateBy { outpointKey(it.outpoint) }
                val resolved = manualUtxos.mapNotNull { freshByOutpoint[outpointKey(it.outpoint)] }
                if (resolved.isEmpty()) {
                    return Result.failure(IllegalStateException("Selected UTXOs are no longer available - please reselect"))
                }
                KaspaUtxoSelector.selectManualUtxosAndCalculateFee(
                    utxos = resolved,
                    amountSompi = amountSompi,
                    feeRateSompiPerGram = feeRateSompiPerGram,
                    recipientScriptLen = recipientScriptHex.length / 2,
                    changeScriptLen = changeScriptHex.length / 2,
                    payloadBytes = payloadBytes,
                    extraFeeSompi = extraFeeSompi,
                    signatureScriptSize = signatureScriptSize
                )
            } else {
                KaspaUtxoSelector.selectUtxosAndCalculateFee(
                    utxos = utxos,
                    amountSompi = amountSompi,
                    feeRateSompiPerGram = feeRateSompiPerGram,
                    payloadBytes = payloadBytes,
                    recipientScriptLen = recipientScriptHex.length / 2,
                    changeScriptLen = changeScriptHex.length / 2,
                    extraFeeSompi = extraFeeSompi,
                    signatureScriptSize = signatureScriptSize
                )
            }
            var finalAmount = selectionResult.finalAmount
            var changeAmount = selectionResult.changeAmount
            if (selectionResult.totalSelected < selectionResult.requiredAmount) {
                val reduced = selectionResult.totalSelected - selectionResult.estimatedFee
                if (allowReducedAmount && amountSompi > 0 && reduced > 0) {
                    finalAmount = reduced
                    changeAmount = 0L
                } else {
                    return Result.failure(IllegalStateException("Insufficient funds: Needed ${selectionResult.requiredAmount}, have ${selectionResult.totalSelected}"))
                }
            }
            // A transaction over Kaspa's mass cap gets rejected by the node. Refuse an over-cap input
            // set up front with an actionable message instead of building a doomed transaction. The
            // compound flow feeds inputs in chunks of MAX_INPUTS_PER_TRANSACTION, so it always passes.
            if (selectionResult.selectedUtxos.size > KaspaUtxoSelector.MAX_INPUTS_PER_TRANSACTION) {
                return Result.failure(IllegalStateException(
                    "This send needs more than ${KaspaUtxoSelector.MAX_INPUTS_PER_TRANSACTION} inputs. Compound (consolidate) this address's UTXOs first, then try again."
                ))
            }

            // 5. Create transaction outputs (recipient + change).
            // Skip a zero-amount recipient output (e.g. self-stash messages where
            // amountSompi=0) — a 0-value output is non-standard and gets rejected;
            // the full remaining balance goes out via the change output instead.
            val outputs = mutableListOf<RawOutputWithVersion>()
            if (finalAmount > 0) {
                outputs.add(
                    RawOutputWithVersion(
                        amount = finalAmount,
                        scriptPublicKey = ScriptPublicKeyWithVersion(recipientScriptHex, 0)
                    )
                )
            }
            // Whether the change stands as its own output is decided by this transaction's KIP-9
            // storage mass, not a flat floor (see KaspaMass.storageMass). The old 500-sompi rule
            // cut both ways: it emitted change the network rejects (a 0.01 KAS remainder carved
            // from a large coin), and it would have refused nothing that mass allows. Change that
            // does not fit is folded into the fee, as before.
            val inputAmounts = selectionResult.selectedUtxos.map { it.utxoEntry.amount }
            val recipientAmounts = outputs.map { it.amount }
            // Only dust is ever folded (iOS dd4d977): real change that cannot stand as an output
            // means this amount does not fit these coins, and the send is refused rather than
            // paying that change to the network as fee.
            if (selectionResult.storageMassBlocked ||
                (changeAmount > 0 && !KaspaUtxoSelector.changeIsKeptOrFoldable(inputAmounts, recipientAmounts, changeAmount))
            ) {
                return Result.failure(IllegalStateException(KaspaUtxoSelector.SMALL_SEND_MASS_MESSAGE))
            }
            var changeOutputIndex = -1
            if (changeAmount > 0 && KaspaMass.fitsStorageMass(inputAmounts, recipientAmounts + changeAmount)) {
                changeOutputIndex = outputs.size
                outputs.add(
                    RawOutputWithVersion(
                        amount = changeAmount,
                        scriptPublicKey = ScriptPublicKeyWithVersion(changeScriptHex, 0)
                    )
                )
            }
            if (outputs.isEmpty()) {
                return Result.failure(IllegalStateException("Insufficient funds to cover network fee"))
            }
            if (!KaspaMass.fitsStorageMass(inputAmounts, outputs.map { it.amount })) {
                // Consensus would reject this shape outright ("storage mass larger than max
                // allowed"); say so instead of broadcasting a doomed transaction.
                return Result.failure(IllegalStateException(
                    "This amount is too small to send from the coins available (Kaspa storage-mass limit). Try a larger amount, or consolidate this address first."
                ))
            }

            val payloadHex = payloadBytes?.joinToString("") { "%02x".format(it) }

            val rawTx = RawTransaction(
                inputs = selectionResult.selectedUtxos.map { utxo ->
                    RawInput(previousOutpoint = utxo.outpoint, signatureScript = "")
                },
                outputs = outputs,
                gas = 0,
                payload = payloadHex
            )

            attemptInputs = selectionResult.selectedUtxos
            // 6. Sign transaction with private key (locally)
            val signedTx = KaspaTransactionSigner.signTransaction(
                rawTx = rawTx,
                utxos = selectionResult.selectedUtxos,
                privateKey = signingPrivateKey,
                redeemScript = senderRedeemScript
            )

            // 7. Broadcast transaction.
            // The REST gateway (api.kaspa.org POST /transactions) works fine for plain
            // payments but rejects payload-carrying transactions with a false
            // "signature script" failure — the signature is cryptographically valid
            // (verified against official rusty-kaspa test vectors), so the bug is in
            // the REST gateway's JSON-to-RPC payload translation, not the transaction
            // itself. The iOS reference app never uses REST for broadcast either — it
            // submits exclusively over gRPC — so payload-carrying sends go straight to
            // a node via gRPC here, bypassing the REST gateway entirely. Plain payments
            // (no payload) keep using REST since that path is already proven working.
            //
            // allowOrphan: when this send chains onto our own not-yet-confirmed change (the
            // reconciled pending UTXOs above have blockDaaScore == 0), the pooled node this
            // lands on may not have seen the parent transaction yet — with allowOrphan=false
            // it would reject with kaspad's "transaction ... is an orphan, where orphan is
            // disallowed" (the exact failure users saw sending broadcast messages/reactions
            // back-to-back). allowOrphan=true parks it in the node's orphan pool until the
            // parent propagates (sub-second), matching what iOS's broadcast/1:1 send does.
            // And even with confirmed-only inputs, the submit node can briefly lag the node
            // that served the UTXO snapshot: that rejection waits 1.5 s for the parent, submits
            // once more, then lets the node hold it as an orphan (iOS 4eb492f,
            // NodePoolManager.submitWaitingForParent) instead of failing.
            val usesUnconfirmedInputs = selectionResult.selectedUtxos.any { it.utxoEntry.blockDaaScore == 0L }
            // EVERY send goes to a node over gRPC first, plain payments included - as iOS does.
            // Plain payments used to POST to the REST gateway alone, and api.kaspa.org
            // rate-limits that too (HTTP 429), so a wallet send failed for the same reason a
            // burst of messages did. The gateway is now only the fallback for a plain payment
            // when no node can be reached at all; a payload send has no REST fallback (the
            // gateway rejects those, see above).
            //
            // Whatever the path, a submit error is only final once the network is asked whether it
            // has this transaction anyway (iOS ad29da9, audit IOS-014): the first submit can be
            // accepted while its answer times out, and the retry below then hears "already in the
            // mempool" for the same transaction - reported as a failure, that invites a resend that
            // pays twice. The id is computed locally, before anything is sent.
            val transactionId = nodePoolManager.submitConfirmingKnown(signedTx, api) { try {
                try {
                    nodePoolManager.submitWaitingForParent(signedTx, allowOrphan = usesUnconfirmedInputs)
                } catch (e: Exception) {
                    // Transport-shaped failure (timeout / dead gRPC stream) — NOT a node verdict.
                    // The cached connection can die silently and only gets reaped by the 30s probe
                    // cycle; every send in that window failed after the full timeout while the app
                    // looked connected. Reconnect fresh and retry ONCE. Definitive node rejections
                    // (mass/fee/double-spend) are rethrown untouched — blind-retrying those is wrong.
                    val isTransportFailure = e is kotlinx.coroutines.TimeoutCancellationException ||
                        e is io.grpc.StatusException || e is io.grpc.StatusRuntimeException
                    when {
                        isTransportFailure -> {
                            Log.w("KaspaWalletEngine", "Submit transport failure, reconnecting and retrying once", e)
                            nodePoolManager.refreshBroadcastConnection()
                            try {
                                nodePoolManager.submitWaitingForParent(signedTx, allowOrphan = usesUnconfirmedInputs)
                            } catch (e2: Exception) {
                                // Payload-carrying sends have no REST fallback (the REST gateway
                                // rejects them, see the broadcast comment above), so when the pool
                                // has already concluded the whole network blocks gRPC, surface that
                                // honestly instead of a raw DEADLINE_EXCEEDED string.
                                if (payloadBytes != null && nodePoolManager.nodeConnectionsBlocked.value) {
                                    throw IllegalStateException(
                                        "No Kaspa node is reachable on this network. Node connections appear blocked, so the message could not be sent.",
                                        e2
                                    )
                                }
                                throw e2
                            }
                        }
                        else -> throw e
                    }
                }
            } catch (e: Exception) {
                // A plain payment can still reach the network through the REST gateway when
                // every node attempt was transport-shaped (nothing answered). A node's verdict
                // on the transaction itself is final and is not second-guessed over REST.
                val transportShaped = e is kotlinx.coroutines.TimeoutCancellationException ||
                    e is io.grpc.StatusException || e is io.grpc.StatusRuntimeException
                if (payloadBytes == null && transportShaped) {
                    Log.w("KaspaWalletEngine", "No node answered the submit, falling back to the REST gateway", e)
                    api.postTransaction(PostTransactionRequest(signedTx)).transactionId
                } else {
                    throw e
                }
            } }

            val changeUtxo = if (changeOutputIndex >= 0) {
                UtxoEntry(
                    address = changeAddress,
                    outpoint = Outpoint(transactionId = transactionId, index = changeOutputIndex),
                    utxoEntry = UtxoData(
                        amount = changeAmount,
                        scriptPublicKey = ScriptPublicKey(changeScriptHex),
                        blockDaaScore = 0,
                        isCoinbase = false
                    )
                )
            } else null
            return Result.success(SubmittedSend(transactionId, selectionResult.selectedUtxos, changeUtxo))
            }

            val submitted = try {
                attempt(utxos)
            } catch (e: Exception) {
                // The node pool's submit node can be a few seconds behind the node that served
                // the UTXO snapshot, or behind a just-accepted send whose change was chained.
                // It then rejects with kaspad's raw "orphan" / "already spent" text - a
                // transient state mismatch, not a user error. Retry ONCE with a freshly fetched,
                // confirmed-only input set before giving up, exactly as iOS does. Coin control
                // fixed the inputs on purpose, so it is left to its own verdict.
                // A signature rejection (kaspad "failed to verify the signature script") runs the
                // same fallback: the pending coins that attempt signed are dropped first, and
                // every input is logged (iOS ca53cd1).
                val signatureRejected = isSignatureRejection(e)
                if (signatureRejected) {
                    logSignatureRejection(attemptInputs, fromScriptHex)
                    dropPendingChange(fromAddress, attemptInputs.filter { it.utxoEntry.blockDaaScore == 0L })
                }
                if (!(isTransientNodeRejection(e) || signatureRejected) || !manualUtxos.isNullOrEmpty()) throw e
                val refetched = nodePoolManager.getUtxosByAddress(fromAddress) ?: api.getUtxos(fromAddress)
                val confirmedOnly = filterSpendableCoinbase(reconcileUtxos(fromAddress, refetched))
                    .filter { it.utxoEntry.blockDaaScore > 0L }
                if (confirmedOnly.isEmpty()) throw e
                Log.w("KaspaWalletEngine", "Submit rejected (${e.message}), retrying once with confirmed-only inputs")
                attempt(confirmedOnly)
            }.getOrElse { return Result.failure(it) }

            recordSpend(fromAddress, changeAddress, submitted.spentUtxos, submitted.changeUtxo)
            Result.success(submitted.txId)
        } catch (e: Exception) {
            Log.e("KaspaWalletEngine", "Error sending Kaspa", e)
            Result.failure(e)
        }
    }

    /**
     * A chat payment whose From is one of the spending addresses (iOS 6333dd3
     * `sendPaymentInternal(sourceSpendingIndex:)`): spent from [sourceSpendingIndex]'s address and
     * signed with that address's own key. The coins are picked the normal way - the largest
     * first, just enough for the amount and fee, at most
     * [KaspaUtxoSelector.MAX_INPUTS_PER_TRANSACTION] - or, with coin control, exactly the picked
     * ones; whatever is not spent stays on the source address. The change goes to a never-used
     * quantum-safe (KIP-22) address, checked on chain first ([WalletManager.verifyFreshQuantumIndex]
     * with [isAddressUsed]); when it can't be derived a legacy source takes the change back, a
     * quantum source fails the send (IOS-104 / AND-052). It is
     * revealed (listed in Manage Addresses) once the send succeeds; it is never made the primary - nothing rotates a primary any more. A quantum source
     * spends with its redeem script after each signature, and is marked spent (its shield comes
     * off) once the network takes it. A failed/rejected send moves nothing, safe to retry.
     */
    suspend fun sendSpendingPayment(
        toAddress: String,
        amountSompi: Long,
        sourceSpendingIndex: Int,
        feeRateOverride: Long? = null,
        /** The payment's `kchat:1:pay:` payload when it carries a memo (see
         *  [com.kachat.app.util.MessageProtocol.buildPaymentPayload]); null for a plain payment. */
        payloadBytes: ByteArray? = null,
        /** Coin control: spend exactly these coins of the source address (resolved against the
         *  fresh fetch in [sendKaspa]). null = automatic selection. */
        manualUtxos: List<UtxoEntry>? = null,
        /** Fast / Priority / custom extra on top of the base fee, paid exactly. */
        extraFeeSompi: Long = 0L,
        /** Whether an address already has on-chain history (WalletService.spendingAddressUsedState;
         *  null = can't tell): the fresh change slot is checked with it first (iOS da2c8e2). */
        isAddressUsed: suspend (String) -> Boolean?,
    ): Result<String> {
        // The account the source is derived from: its change slot is probed and picked there too
        // (iOS fb2323e, IOS-109 / AND-056 follow-up).
        val owner = walletManager.getActiveAccount()?.address
        val sourceSpendingAddress = walletManager.deriveSpendingAddress(sourceSpendingIndex)
        val spendingPrivateKey = walletManager.getSpendingPrivateKeyBytes(sourceSpendingIndex)
        // A quantum-safe source (KIP-22) spends with its redeem script pushed after the signature.
        val isQuantumSource = WalletManager.isQuantumIndex(sourceSpendingIndex)
        val senderRedeemScript = if (isQuantumSource) {
            walletManager.quantumRedeemScript(sourceSpendingIndex)
                ?: return Result.failure(IllegalStateException("Could not derive the quantum address's key"))
        } else null
        // Change always lands on a brand-new quantum-safe slot (iOS `nextFreshQuantumIndex`):
        // never an address used before, and its key stays hidden until IT spends. Picked now,
        // revealed only once the network takes the send - a failed send burns no slot - and held
        // as in flight meanwhile, so a concurrent send or Request Payment reservation can't get
        // the same one. The slot is checked on chain first: one that already has history (used
        // from another device, or past where a restore looked) is skipped (iOS
        // `verifiedFreshQuantumIndex`, IOS-101 / AND-049). Slot and address both come from the
        // account the source was derived from, whichever is active once the probe returns (iOS
        // fb2323e `verifiedFreshQuantumSlot`, IOS-109 / AND-056 follow-up).
        val picked = owner?.let { walletManager.verifiedFreshQuantumChange(owner = it, isUsed = isAddressUsed) }
        val pick = if (picked != null && (picked.index == sourceSpendingIndex || picked.address.equals(sourceSpendingAddress, ignoreCase = true))) {
            walletManager.finishQuantumChangePick(picked, reveal = false)
            null
        } else picked
        // If it can't be derived a legacy source takes its change back (iOS); a quantum source's
        // spend reveals its key, and change back to it would park funds on a revealed key - the
        // send stops instead (iOS da2c8e2, IOS-104 / AND-052).
        if (pick == null && isQuantumSource) {
            return Result.failure(IllegalStateException(appContext.getString(com.kachat.app.R.string.quantum_next_address_failed)))
        }
        val result = try {
            sendKaspa(
                toAddress = toAddress,
                amountSompi = amountSompi,
                payloadBytes = payloadBytes,
                fromAddress = sourceSpendingAddress,
                signingPrivateKey = spendingPrivateKey,
                changeAddress = pick?.address ?: sourceSpendingAddress,
                feeRateOverride = feeRateOverride,
                // Coin control: only the chosen coins are spent (iOS 62c2773). Otherwise the normal
                // largest-first selection, as a chatting-address payment - the fee preview and Max
                // price the same coins (ChatViewModel.paymentFeeInputs / paymentMaxCoins).
                manualUtxos = manualUtxos,
                extraFeeSompi = extraFeeSompi,
                senderRedeemScript = senderRedeemScript,
            )
        } catch (e: Throwable) {
            // Interrupted with the outcome unknown: revealed, as revealing a slot is always safe
            // and handing out one that may hold change is not.
            pick?.let { walletManager.finishQuantumChangePick(it, reveal = true) }
            throw e
        }
        // Revealed, not made primary (iOS revealSpendingAddress), only when the send went
        // through; a failed send gives it back. Both writes go to the account this send started
        // on (the pick's own, and [owner]), even if another is active by now; nothing once it was
        // removed (iOS ca09dd0, IOS-109 / AND-056).
        pick?.let { walletManager.finishQuantumChangePick(it, reveal = result.isSuccess) }
        // a spend reveals the key for good: the shield comes off (KIP-22)
        if (result.isSuccess && isQuantumSource) walletManager.markQuantumAddressSpent(sourceSpendingAddress, owner = owner)
        return result
    }

    /** Live quoted fee rate (sompi per mass-gram), floored at the network minimum - mirrors
     *  [ColdStorageSendEngine.fetchQuotedFeeRateSompiPerGram]. Falls back to the minimum on any
     *  request failure. Extracted out of [sendKaspa] so [WalletViewModel]'s max-amount estimate
     *  can quote the same rate a real send would use. */
    suspend fun fetchQuotedFeeRateSompiPerGram(): Long {
        val api = networkService.kaspaRestApi.value ?: return KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM
        return try {
            val estimate = api.getFeeEstimate()
            val quoted = estimate.normalBuckets.firstOrNull()?.feerate ?: KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM.toDouble()
            ceil(quoted).toLong().coerceAtLeast(KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM)
        } catch (e: Exception) {
            Log.w("KaspaWalletEngine", "Failed to fetch fee estimate, using network minimum", e)
            KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM
        }
    }

    /**
     * Coin control's data source — mirrors [ColdStorageSendEngine.fetchUtxos], but routed
     * through [reconcileUtxos] too so a UTXO this engine already knows is pending-spent (from a
     * send that hasn't hit the REST indexer yet) doesn't show up as selectable in the first
     * place, rather than only being caught later at send time.
     */
    /**
     * Whether the REST client exists yet. It is created when the network service configures, a
     * moment AFTER launch, and until then [fetchUtxos] answers `emptyList()` - which reads
     * identically to "this address holds nothing". Callers that would otherwise present that
     * silence as a fact about the address check this first.
     */
    val isRestApiReady: Boolean
        get() = networkService.kaspaRestApi.value != null

    /**
     * The balance at [address] as the node sees it - the sum of every UTXO there, exactly how
     * iOS's WalletManager.refreshBalance computes it - or null when no node can answer, so the
     * caller can fall back to the REST gateway. The gateway rate-limits a burst (HTTP 429),
     * and a balance refresh that silently fails is a header that never moves after a send.
     */
    suspend fun nodeBalance(address: String): Long? =
        // A total that is not a real value (a broken node, IOS-020) reads as "no answer", so
        // the caller asks the REST gateway instead.
        nodePoolManager.getUtxosByAddress(address)?.let { UtxoMath.checkedTotal(it.map { u -> u.utxoEntry.amount }) }

    suspend fun fetchUtxos(address: String): List<UtxoEntry> {
        return try {
            fetchUtxosOrThrow(address)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * [fetchUtxos], but a node that could not be asked (no REST client yet, or the request
     * failed) throws instead of reading as an empty address - what coin control needs to say
     * "Couldn't load the coins" rather than "No UTXOs found" (iOS `CoinControlView.loadError`).
     */
    suspend fun fetchUtxosOrThrow(address: String): List<UtxoEntry> {
        val api = networkService.kaspaRestApi.value ?: throw IllegalStateException(COIN_FETCH_NOT_CONNECTED)
        val fetched = nodePoolManager.getUtxosByAddress(address) ?: api.getUtxos(address)
        return filterSpendableCoinbase(reconcileUtxos(address, fetched))
    }

    /**
     * Coinbase (mining-reward) UTXOs are only spendable once matured: `blockDaaScore +
     * COINBASE_MATURITY < virtualDaaScore` (matches Kaspa consensus, Kaspium, and the iOS app).
     * Non-coinbase UTXOs are always spendable. The virtual DAA score is only fetched when coinbase
     * is actually present, so ordinary (non-mining) addresses pay no extra round trip. If the score
     * can't be fetched, coinbase is kept and the node arbitrates — no worse than before this filter.
     */
    private suspend fun filterSpendableCoinbase(utxos: List<UtxoEntry>): List<UtxoEntry> {
        if (utxos.none { it.utxoEntry.isCoinbase }) return utxos
        val virtualDaaScore = try {
            nodePoolManager.getBroadcastConnection().getBlockDagInfo().virtualDaaScore
        } catch (e: Exception) {
            Log.w("KaspaWalletEngine", "Could not fetch virtualDaaScore for coinbase maturity; keeping all UTXOs", e)
            return utxos
        }
        return utxos.filter { u ->
            // An overflowing or negative DAA score (a nonsense node value) is not yet mature (IOS-020).
            !u.utxoEntry.isCoinbase || UtxoMath.isMatureCoinbase(u.utxoEntry.blockDaaScore, COINBASE_MATURITY, virtualDaaScore)
        }
    }

    /** A signed transaction that has NOT been submitted: its id, its bytes, and what it spends. */
    data class SignedPayloadTx(
        val txId: String,
        val transaction: RawTransaction,
        val spentOutpoints: List<String>,
    )

    /**
     * Builds and signs a payload-carrying self-send WITHOUT submitting it - what a scheduled post
     * is: signed now, sent later (KAPOSTS_INDEXER.md section 5.10).
     *
     * Confirmed coins only: a transaction that may sit for days must not chain onto an
     * unconfirmed change output, which could be re-organised away under it. The coins it spends
     * are the caller's to reserve (see [KaPostsScheduledStore]); nothing is recorded as pending
     * here, because nothing has been broadcast.
     */
    suspend fun buildSignedPayloadSelfSend(
        payloadBytes: ByteArray,
        fromAddress: String = walletManager.getAddress(),
        signingPrivateKey: ByteArray = walletManager.getPrivateKeyBytes(),
        feeRateOverride: Long? = null,
    ): Result<SignedPayloadTx> = sendMutex.withLock {
        try {
            val api = networkService.kaspaRestApi.value
                ?: return Result.failure(IllegalStateException("Network service unavailable"))
            val fetched = nodePoolManager.getUtxosByAddress(fromAddress) ?: api.getUtxos(fromAddress)
            val confirmed = filterSpendableCoinbase(reconcileUtxos(fromAddress, fetched))
                .filter { it.utxoEntry.blockDaaScore > 0L }
            if (confirmed.isEmpty()) {
                return Result.failure(IllegalStateException("No confirmed coins are available to schedule a post with. Wait for a confirmation and try again."))
            }
            val feeRate = feeRateOverride?.coerceAtLeast(KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM)
                ?: fetchQuotedFeeRateSompiPerGram()
            val scriptHex = KaspaAddress.getScriptPublicKey(fromAddress)
            val selection = selectUtxosAndCalculateFee(
                utxos = confirmed,
                amountSompi = 0,
                feeRateSompiPerGram = feeRate,
                payloadBytes = payloadBytes,
                recipientScriptLen = scriptHex.length / 2,
                changeScriptLen = scriptHex.length / 2,
            )
            if (selection.totalSelected < selection.requiredAmount) {
                return Result.failure(IllegalStateException("Insufficient funds: Needed ${selection.requiredAmount}, have ${selection.totalSelected}"))
            }
            if (selection.selectedUtxos.size > KaspaUtxoSelector.MAX_INPUTS_PER_TRANSACTION) {
                return Result.failure(IllegalStateException(
                    "This needs more than ${KaspaUtxoSelector.MAX_INPUTS_PER_TRANSACTION} inputs. Compound (consolidate) this address's coins first."
                ))
            }
            // A self-send carries its whole value as change: the post is the payload, not a payment.
            val inputAmounts = selection.selectedUtxos.map { it.utxoEntry.amount }
            if (selection.changeAmount <= 0 || !KaspaMass.fitsStorageMass(inputAmounts, listOf(selection.changeAmount))) {
                return Result.failure(IllegalStateException("Insufficient funds to cover the network fee"))
            }
            val rawTx = RawTransaction(
                inputs = selection.selectedUtxos.map { RawInput(previousOutpoint = it.outpoint, signatureScript = "") },
                outputs = listOf(
                    RawOutputWithVersion(
                        amount = selection.changeAmount,
                        scriptPublicKey = ScriptPublicKeyWithVersion(scriptHex, 0),
                    )
                ),
                gas = 0,
                payload = payloadBytes.joinToString("") { "%02x".format(it) },
            )
            val signedTx = KaspaTransactionSigner.signTransaction(
                rawTx = rawTx,
                utxos = selection.selectedUtxos,
                privateKey = signingPrivateKey,
            )
            return Result.success(
                SignedPayloadTx(
                    txId = com.kachat.app.util.KaspaTransactionId.compute(signedTx),
                    transaction = signedTx,
                    spentOutpoints = signedTx.inputs.map { outpointKey(it.previousOutpoint) },
                )
            )
        } catch (e: Exception) {
            Log.e("KaspaWalletEngine", "Could not build a scheduled transaction", e)
            return Result.failure(e)
        }
    }

    /**
     * The network fee a payload-carrying self-send from [fromAddress] would pay right now: the
     * transaction [sendKaspa] would build for `sendKaspa(toAddress = fromAddress, amountSompi = 0,
     * payloadBytes)` - the same coins (reconciled, mature), the same fee rate, the same selection,
     * the same change rule - signed, and never submitted. The fee is what it spends minus what it
     * pays back. The `.kachat` profile record's quote (iOS `KachatNamesActions.profileFee`,
     * KaChat 7e238e5); a send moments later may still pick other coins if the wallet changed.
     */
    suspend fun quotePayloadSelfSendFee(
        payloadBytes: ByteArray,
        fromAddress: String = walletManager.getAddress(),
        signingPrivateKey: ByteArray = walletManager.getPrivateKeyBytes(),
    ): Long = sendMutex.withLock {
        val api = networkService.kaspaRestApi.value ?: throw IllegalStateException("Network service unavailable")
        val fetched = nodePoolManager.getUtxosByAddress(fromAddress) ?: api.getUtxos(fromAddress)
        val utxos = filterSpendableCoinbase(reconcileUtxos(fromAddress, fetched))
        if (utxos.isEmpty()) throw IllegalStateException("Insufficient funds: No UTXOs found")
        val feeRate = fetchQuotedFeeRateSompiPerGram()
        val scriptHex = KaspaAddress.getScriptPublicKey(fromAddress)
        val selection = selectUtxosAndCalculateFee(
            utxos = utxos,
            amountSompi = 0,
            feeRateSompiPerGram = feeRate,
            payloadBytes = payloadBytes,
            recipientScriptLen = scriptHex.length / 2,
            changeScriptLen = scriptHex.length / 2,
        )
        if (selection.totalSelected < selection.requiredAmount) {
            throw IllegalStateException("Insufficient funds: Needed ${selection.requiredAmount}, have ${selection.totalSelected}")
        }
        if (selection.selectedUtxos.size > KaspaUtxoSelector.MAX_INPUTS_PER_TRANSACTION) {
            throw IllegalStateException(
                "This send needs more than ${KaspaUtxoSelector.MAX_INPUTS_PER_TRANSACTION} inputs. Compound (consolidate) this address's UTXOs first, then try again."
            )
        }
        // sendKaspa's output rule for a zero amount: no recipient output, the change kept when
        // storage mass allows it, dust folded into the fee, real change that can't stand refused.
        val inputAmounts = selection.selectedUtxos.map { it.utxoEntry.amount }
        val change = selection.changeAmount
        if (selection.storageMassBlocked ||
            (change > 0 && !KaspaUtxoSelector.changeIsKeptOrFoldable(inputAmounts, emptyList(), change))
        ) {
            throw IllegalStateException(KaspaUtxoSelector.SMALL_SEND_MASS_MESSAGE)
        }
        val outputs = if (change > 0 && KaspaMass.fitsStorageMass(inputAmounts, listOf(change))) {
            listOf(RawOutputWithVersion(amount = change, scriptPublicKey = ScriptPublicKeyWithVersion(scriptHex, 0)))
        } else {
            emptyList()
        }
        if (outputs.isEmpty()) throw IllegalStateException("Insufficient funds to cover network fee")
        val rawTx = RawTransaction(
            inputs = selection.selectedUtxos.map { RawInput(previousOutpoint = it.outpoint, signatureScript = "") },
            outputs = outputs,
            gas = 0,
            payload = payloadBytes.joinToString("") { "%02x".format(it) },
        )
        // Signed as the save signs it, so what is quoted is a transaction that would be valid.
        val signed = KaspaTransactionSigner.signTransaction(rawTx = rawTx, utxos = selection.selectedUtxos, privateKey = signingPrivateKey)
        val spent = UtxoMath.total(selection.selectedUtxos.map { it.utxoEntry.amount })
        val back = UtxoMath.total(signed.outputs.map { it.amount })
        maxOf(0L, spent - back)
    }

    /**
     * Submits a transaction that was signed earlier - the phone's own fallback for a scheduled
     * post the indexer never took. Returns the id the node gives it.
     */
    suspend fun submitSignedTransaction(transaction: RawTransaction): String {
        // A submit error is checked against the network before it counts (audit IOS-014).
        val txId = nodePoolManager.submitConfirmingKnown(transaction, networkService.kaspaRestApi.value) {
            nodePoolManager.submitWaitingForParent(transaction, allowOrphan = false)
        }
        try { refreshAfterSubmit(transaction) } catch (_: Exception) {}
        return txId
    }

    /** After a delayed submit: the coins it spent are gone, so nothing may pick them again. */
    private fun refreshAfterSubmit(transaction: RawTransaction) {
        val address = walletManager.getAddress()
        val spent = pendingSpentOutpoints.getOrPut(address) { mutableSetOf() }
        val now = System.currentTimeMillis()
        transaction.inputs.forEach { input ->
            val key = outpointKey(input.previousOutpoint)
            spent.add(key)
            pendingRecordedAtMs[key] = now
        }
    }

    private fun isValidAddress(address: String): Boolean {
        return try {
            // A known prefix and a payload that fits its version, then a script that builds.
            KaspaAddress.isValid(address) && KaspaAddress.getScriptPublicKey(address).isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    private fun selectUtxosAndCalculateFee(
        utxos: List<UtxoEntry>,
        amountSompi: Long,
        feeRateSompiPerGram: Long,
        payloadBytes: ByteArray?,
        recipientScriptLen: Int,
        changeScriptLen: Int
    ) = KaspaUtxoSelector.selectUtxosAndCalculateFee(
        utxos, amountSompi, feeRateSompiPerGram, payloadBytes, recipientScriptLen, changeScriptLen
    )

    companion object {
        /** Kaspa coinbase (mining-reward) maturity, in DAA-score units. Matches Kaspa consensus,
         *  Kaspium, and the iOS app. A coinbase UTXO is spendable once
         *  `blockDaaScore + COINBASE_MATURITY < virtualDaaScore`. */
        const val COINBASE_MATURITY = 1000L

        internal fun outpointKey(outpoint: Outpoint) = "${outpoint.transactionId}:${outpoint.index}"

        /** How long a pending spend / pending change entry is trusted before the chain has to
         *  confirm it (iOS pendingMessageUtxoTTL). */
        private const val PENDING_UTXO_TTL_MS = 120_000L

        /**
         * Pure reconciliation logic (no network/DI dependencies) — mutates [pendingSpentKeys]/
         * [pendingChange] in place to drop entries the fresh fetch confirms are caught up,
         * and returns the fresh list with our own not-yet-indexed spend/change applied.
         */
        internal fun reconcilePendingUtxos(
            freshUtxos: List<UtxoEntry>,
            pendingSpentKeys: MutableSet<String>,
            pendingChange: MutableList<UtxoEntry>,
            /** The fetched address's own script. A pending coin is signed with that address's key,
             *  so only coins locked to it may be offered - one locked elsewhere is rejected by the
             *  network ("false stack entry at end of script execution", iOS ca53cd1). Confirmed
             *  coins are not filtered. Null = no filter. */
            ownScriptHex: String? = null
        ): List<UtxoEntry> {
            val freshKeys = freshUtxos.map { outpointKey(it.outpoint) }.toSet()
            pendingSpentKeys.retainAll(freshKeys)
            pendingChange.removeAll { outpointKey(it.outpoint) in freshKeys }
            val ownChange = if (ownScriptHex == null) pendingChange else pendingChange.filter {
                !it.utxoEntry.isCoinbase && it.utxoEntry.scriptPublicKey.scriptPublicKey.equals(ownScriptHex, ignoreCase = true)
            }
            return freshUtxos.filter { outpointKey(it.outpoint) !in pendingSpentKeys } + ownChange
        }

        /**
         * Pure spend-recording logic — mutates [pendingSpentKeys]/[pendingChange] in place.
         * Critically, a just-spent input must be dropped from [pendingChange] immediately:
         * otherwise a spent synthetic change UTXO stays "available" forever (it can never
         * naturally disappear via [reconcilePendingUtxos]'s fresh-fetch check, since a UTXO
         * spent before it even confirms never shows up as fresh on its own) and keeps
         * getting greedily re-selected — causing every send after it to fail with the same
         * "already spent in the mempool" rejection, unfixable by retrying.
         */
        internal fun applySpend(
            pendingSpentKeys: MutableSet<String>,
            pendingChange: MutableList<UtxoEntry>,
            spentUtxos: List<UtxoEntry>,
            newChangeUtxo: UtxoEntry?
        ) {
            val spentKeys = spentUtxos.map { outpointKey(it.outpoint) }.toSet()
            pendingSpentKeys.addAll(spentKeys)
            pendingChange.removeAll { outpointKey(it.outpoint) in spentKeys }
            if (newChangeUtxo != null) {
                pendingChange.add(newChangeUtxo)
            }
        }
    }
}
