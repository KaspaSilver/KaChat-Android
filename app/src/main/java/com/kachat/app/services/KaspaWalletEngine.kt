package com.kachat.app.services

import android.util.Log
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaMass
import com.kachat.app.util.KaspaTransactionSigner
import com.kachat.app.util.KaspaUtxoSelector
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

/**
 * KaspaWalletEngine — handles low-level transaction construction and broadcasting.
 * Follows the required send flow: Fetch UTXOs -> Build -> Sign -> Broadcast.
 */
@Singleton
class KaspaWalletEngine @Inject constructor(
    private val networkService: NetworkService,
    private val walletManager: WalletManager,
    private val nodePoolManager: NodePoolManager
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
        return reconcilePendingUtxos(freshUtxos, spent, change)
    }

    /** Call after a successful broadcast so the very next send (before this one confirms) doesn't reuse or miss these UTXOs. */
    private fun recordSpend(spentAddress: String, changeAddress: String, spentUtxos: List<UtxoEntry>, changeUtxo: UtxoEntry?) {
        val spent = pendingSpentOutpoints.getOrPut(spentAddress) { mutableSetOf() }
        val change = pendingChangeUtxos.getOrPut(changeAddress) { mutableListOf() }
        applySpend(spent, change, spentUtxos, changeUtxo)
    }

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
     * instead, so a spend never leaves anything behind at the address it came from.
     * @param sweepAll Selects every fetched UTXO unconditionally instead of just enough to
     * cover amount+fee — see [KaspaUtxoSelector.selectAllUtxosAndCalculateFee].
     * @param feeRateOverride Sompi-per-mass-gram rate to use instead of the live network
     * estimate — e.g. from the Withdraw dialog's manual fee bump for a busy fee market. Still
     * floored at [KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM] like the live estimate is.
     * @param manualUtxos Coin control: spend exactly this fixed set instead of greedily picking
     * enough large UTXOs — resolved by outpoint against the freshly-reconciled `utxos` fetched
     * below, since the caller's selection may be stale (spent by another device, aged out) by
     * the time this actually runs. Ignored when [sweepAll] is set.
     * @return Result containing the transaction ID or an error.
     */
    suspend fun sendKaspa(
        toAddress: String,
        amountSompi: Long,
        payloadBytes: ByteArray? = null,
        fromAddress: String = walletManager.getAddress(),
        signingPrivateKey: ByteArray = walletManager.getPrivateKeyBytes(),
        changeAddress: String = fromAddress,
        sweepAll: Boolean = false,
        feeRateOverride: Long? = null,
        manualUtxos: List<UtxoEntry>? = null
    ): Result<String> = sendMutex.withLock {
        try {
            // 1. Validate address
            if (!isValidAddress(toAddress)) {
                return Result.failure(IllegalArgumentException("Invalid recipient address: $toAddress"))
            }

            val api = networkService.kaspaRestApi.value ?: return Result.failure(IllegalStateException("Network service unavailable"))

            // 2. Fetch UTXOs from node, reconciled against our own not-yet-indexed sends, then drop
            //    immature coinbase (mining rewards can't be spent until matured — see
            //    filterSpendableCoinbase). Mature coinbase and all non-coinbase UTXOs are kept.
            val reconciled = reconcileUtxos(fromAddress, api.getUtxos(fromAddress))
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

            // 4. UTXO selection and fee calculation using Kaspa's real mass model
            val selectionResult = if (sweepAll) {
                KaspaUtxoSelector.selectAllUtxosAndCalculateFee(
                    utxos = utxos,
                    amountSompi = amountSompi,
                    feeRateSompiPerGram = feeRateSompiPerGram,
                    payloadBytes = payloadBytes,
                    recipientScriptLen = recipientScriptHex.length / 2,
                    changeScriptLen = changeScriptHex.length / 2
                )
            } else if (!manualUtxos.isNullOrEmpty()) {
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
                    changeScriptLen = changeScriptHex.length / 2
                )
            } else {
                selectUtxosAndCalculateFee(
                    utxos = utxos,
                    amountSompi = amountSompi,
                    feeRateSompiPerGram = feeRateSompiPerGram,
                    payloadBytes = payloadBytes,
                    recipientScriptLen = recipientScriptHex.length / 2,
                    changeScriptLen = changeScriptHex.length / 2
                )
            }
            if (selectionResult.totalSelected < selectionResult.requiredAmount) {
                return Result.failure(IllegalStateException("Insufficient funds: Needed ${selectionResult.requiredAmount}, have ${selectionResult.totalSelected}"))
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
            if (selectionResult.finalAmount > 0) {
                outputs.add(
                    RawOutputWithVersion(
                        amount = selectionResult.finalAmount,
                        scriptPublicKey = ScriptPublicKeyWithVersion(recipientScriptHex, 0)
                    )
                )
            }
            var changeOutputIndex = -1
            if (selectionResult.changeAmount > 500) { // Minimum dust threshold
                changeOutputIndex = outputs.size
                outputs.add(
                    RawOutputWithVersion(
                        amount = selectionResult.changeAmount,
                        scriptPublicKey = ScriptPublicKeyWithVersion(changeScriptHex, 0)
                    )
                )
            }
            if (outputs.isEmpty()) {
                return Result.failure(IllegalStateException("Insufficient funds to cover network fee"))
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

            // 6. Sign transaction with private key (locally)
            val signedTx = KaspaTransactionSigner.signTransaction(
                rawTx = rawTx,
                utxos = selectionResult.selectedUtxos,
                privateKey = signingPrivateKey
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
            // that served the UTXO snapshot — retry that rejection once tolerating orphan
            // (same recovery KnsInscriptionEngine's reveal step uses) instead of failing.
            val usesUnconfirmedInputs = selectionResult.selectedUtxos.any { it.utxoEntry.blockDaaScore == 0L }
            val transactionId = if (payloadBytes != null) {
                try {
                    nodePoolManager.getBroadcastConnection().submitTransaction(signedTx, allowOrphan = usesUnconfirmedInputs)
                } catch (e: Exception) {
                    val isOrphanRejection = e.message?.contains("orphan", ignoreCase = true) == true
                    // Transport-shaped failure (timeout / dead gRPC stream) — NOT a node verdict.
                    // The cached connection can die silently and only gets reaped by the 30s probe
                    // cycle; every send in that window failed after the full timeout while the app
                    // looked connected. Reconnect fresh and retry ONCE. Definitive node rejections
                    // (mass/fee/double-spend) are rethrown untouched — blind-retrying those is wrong.
                    val isTransportFailure = e is kotlinx.coroutines.TimeoutCancellationException ||
                        e is io.grpc.StatusException || e is io.grpc.StatusRuntimeException
                    when {
                        !usesUnconfirmedInputs && isOrphanRejection -> {
                            Log.w("KaspaWalletEngine", "Submit rejected as orphan (node behind), retrying with allowOrphan=true", e)
                            nodePoolManager.getBroadcastConnection().submitTransaction(signedTx, allowOrphan = true)
                        }
                        isTransportFailure -> {
                            Log.w("KaspaWalletEngine", "Submit transport failure, reconnecting and retrying once", e)
                            nodePoolManager.refreshBroadcastConnection()
                            nodePoolManager.getBroadcastConnection().submitTransaction(signedTx, allowOrphan = usesUnconfirmedInputs)
                        }
                        else -> throw e
                    }
                }
            } else {
                api.postTransaction(PostTransactionRequest(signedTx)).transactionId
            }

            val changeUtxo = if (changeOutputIndex >= 0) {
                UtxoEntry(
                    address = changeAddress,
                    outpoint = Outpoint(transactionId = transactionId, index = changeOutputIndex),
                    utxoEntry = UtxoData(
                        amount = selectionResult.changeAmount,
                        scriptPublicKey = ScriptPublicKey(changeScriptHex),
                        blockDaaScore = 0,
                        isCoinbase = false
                    )
                )
            } else null
            recordSpend(fromAddress, changeAddress, selectionResult.selectedUtxos, changeUtxo)

            Result.success(transactionId)
        } catch (e: Exception) {
            Log.e("KaspaWalletEngine", "Error sending Kaspa", e)
            Result.failure(e)
        }
    }

    /**
     * "Pay in Kaspa" — the only entry point that spends from the spending-address chain instead
     * of the identity address, for payment privacy (see [WalletManager]'s spending-address doc
     * comment). Sweeps the current spending address's entire balance: payment to [toAddress] +
     * change to a freshly derived *next* spending address, which becomes the new current one.
     * The stored index only advances after the send actually succeeds — a failed/rejected send
     * leaves the current spending address exactly as it was, safe to retry.
     */
    suspend fun sendSpendingPayment(toAddress: String, amountSompi: Long, feeRateOverride: Long? = null): Result<String> {
        val identityAddress = walletManager.getAddress()
        val currentIndex = walletManager.getActiveAccount()?.spendingAddressIndex
            ?: return Result.failure(IllegalStateException("No active account"))
        val currentSpendingAddress = walletManager.deriveSpendingAddress(currentIndex)
        val spendingPrivateKey = walletManager.getSpendingPrivateKeyBytes(currentIndex)
        // Change goes one past the ALL-TIME max index (not just currentIndex + 1) — guarantees it
        // never lands on an address that's already been used, offered as a payment-pool
        // reservation, or manually generated from Manage Addresses. The allocation bumps the max
        // atomically, so a concurrent pool reservation can't collide either (matches iOS's
        // `sendPaymentInternal` fresh-change-index rule). On failure the allocated index is
        // simply burned unused — revealing an index is always safe, reusing one is not.
        val (nextIndex, nextSpendingAddress) = walletManager.allocateFreshSpendingIndices(1).firstOrNull()
            ?: return Result.failure(IllegalStateException("Could not derive change address"))

        val result = sendKaspa(
            toAddress = toAddress,
            amountSompi = amountSompi,
            fromAddress = currentSpendingAddress,
            signingPrivateKey = spendingPrivateKey,
            changeAddress = nextSpendingAddress,
            sweepAll = true,
            feeRateOverride = feeRateOverride
        )
        if (result.isSuccess) {
            walletManager.setSpendingAddressIndex(identityAddress, nextIndex)
        }
        return result
    }

    /**
     * Moves an old spending-chain address's entire balance to another spending-chain address —
     * used when the user manually activates a different address from the Manage Addresses
     * screen, so KAS left behind on the previously-active one follows along automatically
     * rather than sitting stranded. `amountSompi = 0` means [sendKaspa] skips the recipient
     * output entirely and routes the whole swept balance out through [changeAddress] instead.
     */
    suspend fun sweepSpendingAddress(fromIndex: Int, toAddress: String): Result<String> {
        val fromAddress = walletManager.deriveSpendingAddress(fromIndex)
        val fromPrivateKey = walletManager.getSpendingPrivateKeyBytes(fromIndex)
        return sendKaspa(
            toAddress = toAddress,
            amountSompi = 0,
            fromAddress = fromAddress,
            signingPrivateKey = fromPrivateKey,
            changeAddress = toAddress,
            sweepAll = true
        )
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
    suspend fun fetchUtxos(address: String): List<UtxoEntry> {
        val api = networkService.kaspaRestApi.value ?: return emptyList()
        return try {
            filterSpendableCoinbase(reconcileUtxos(address, api.getUtxos(address)))
        } catch (e: Exception) {
            emptyList()
        }
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
            !u.utxoEntry.isCoinbase || (u.utxoEntry.blockDaaScore + COINBASE_MATURITY < virtualDaaScore)
        }
    }

    private fun isValidAddress(address: String): Boolean {
        return try {
            // Basic validation using KaspaAddress utility
            KaspaAddress.getScriptPublicKey(address).isNotEmpty()
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

        /**
         * Pure reconciliation logic (no network/DI dependencies) — mutates [pendingSpentKeys]/
         * [pendingChange] in place to drop entries the fresh fetch confirms are caught up,
         * and returns the fresh list with our own not-yet-indexed spend/change applied.
         */
        internal fun reconcilePendingUtxos(
            freshUtxos: List<UtxoEntry>,
            pendingSpentKeys: MutableSet<String>,
            pendingChange: MutableList<UtxoEntry>
        ): List<UtxoEntry> {
            val freshKeys = freshUtxos.map { outpointKey(it.outpoint) }.toSet()
            pendingSpentKeys.retainAll(freshKeys)
            pendingChange.removeAll { outpointKey(it.outpoint) in freshKeys }
            return freshUtxos.filter { outpointKey(it.outpoint) !in pendingSpentKeys } + pendingChange
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
