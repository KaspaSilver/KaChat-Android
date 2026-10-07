package com.kachat.app.services

import android.util.Log
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaMass
import com.kachat.app.util.KaspaUtxoSelector
import com.kachat.app.util.KsptCodec
import com.kachat.app.util.SendFeeModel
import com.kachat.app.util.UtxoMath
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

/**
 * Builds unsigned transactions for a Cold Storage (kpub watch-only) address and broadcasts the
 * signed response scanned back from a KasSigner device — the "send" half of Cold Storage
 * ([ColdStorageManager]/[ColdStorageAddressDiscovery] only cover the watch-only half). Deliberately
 * has no dependency on [WalletManager]: this engine never sees a mnemonic or private key, only
 * public addresses and whatever signature KasSigner hands back.
 */
@Singleton
class ColdStorageSendEngine @Inject constructor(
    private val networkService: NetworkService,
    private val nodePoolManager: NodePoolManager
) {
    // Same reasoning as KaspaWalletEngine.sendMutex — one build-then-broadcast sequence at a time.
    private val mutex = Mutex()

    /** Coins this engine has broadcast that a fetch may still list - filtered out of every
     *  build, Max, preview and coin-control list (audit AND-015). */
    private val pendingSpent = PendingSpentOutpoints()

    data class UnsignedColdTx(
        val rawTx: RawTransaction,
        // Same order as rawTx.inputs — KSPT's per-input amount/scriptPublicKey come from here,
        // since RawInput alone (just an outpoint + empty signatureScript) doesn't carry them.
        val inputUtxos: List<UtxoEntry>,
        val feeSompi: Long,
        val changeSompi: Long,
        /** The address every input comes from - where [broadcastSigned] records them as spent. */
        val fromAddress: String = ""
    )

    /**
     * KasSigner's KSPT wire format carries no BIP32 derivation path per input — the device
     * presumably resolves a signing key per input by matching its scriptPublicKey against its own
     * derived address set, but nothing in the format lets KaChat *tell* it which path to use. To
     * stay unambiguous, every input in a single send is sourced from exactly one address (picked
     * by the user from the account's address list), never aggregated across several.
     */
    /** [manualUtxos], if given (coin control), fixes the exact input set instead of letting
     *  [KaspaUtxoSelector.selectUtxosAndCalculateFee] greedily grow one — re-resolved against
     *  this call's own fresh [api.getUtxos] fetch by outpoint, not used as-is, so a UTXO spent
     *  since the coin-control picker was shown can't silently get included. */
    suspend fun buildUnsignedTransaction(
        fromAddress: String,
        toAddress: String,
        amountSompi: Long,
        feeRateOverride: Long? = null,
        manualUtxos: List<UtxoEntry>? = null,
        /** Fast / Priority / custom fee as a flat extra over the base fee, paid exactly - see
         *  [com.kachat.app.util.SendFeeModel]. */
        extraFeeSompi: Long = 0L
    ): Result<UnsignedColdTx> = mutex.withLock {
        try {
            require(amountSompi > 0) { "Amount must be greater than zero" }
            // The script ignores the prefix: a kaspatest: address on mainnet would pay real KAS.
            KaspaAddress.requireActiveNetwork(toAddress)
            require(KaspaAddress.isValid(toAddress)) { "Invalid recipient address" }

            val api = networkService.kaspaRestApi.value
                ?: return@withLock Result.failure(IllegalStateException("Network service unavailable"))

            // Node first, REST second - see NodePoolManager.getUtxosByAddress. Without the coins an
            // earlier send from here already spent: the fetch can still list them (AND-015).
            val utxos = pendingSpent.filter(fromAddress, nodePoolManager.getUtxosByAddress(fromAddress) ?: api.getUtxos(fromAddress))
            if (utxos.isEmpty()) {
                return@withLock Result.failure(IllegalStateException("No spendable UTXOs at this address"))
            }

            val feeRateSompiPerGram = feeRateOverride?.coerceAtLeast(KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM)
                ?: fetchQuotedFeeRateSompiPerGram()

            val recipientScriptHex = KaspaAddress.getScriptPublicKey(toAddress)
            val changeScriptHex = KaspaAddress.getScriptPublicKey(fromAddress)

            val selection = if (!manualUtxos.isNullOrEmpty()) {
                val freshByOutpoint = utxos.associateBy { it.outpoint }
                val resolved = manualUtxos.mapNotNull { freshByOutpoint[it.outpoint] }
                KaspaUtxoSelector.selectManualUtxosAndCalculateFee(
                    utxos = resolved,
                    amountSompi = amountSompi,
                    feeRateSompiPerGram = feeRateSompiPerGram,
                    recipientScriptLen = recipientScriptHex.length / 2,
                    changeScriptLen = changeScriptHex.length / 2,
                    extraFeeSompi = extraFeeSompi
                )
            } else {
                KaspaUtxoSelector.selectUtxosAndCalculateFee(
                    utxos = utxos,
                    amountSompi = amountSompi,
                    feeRateSompiPerGram = feeRateSompiPerGram,
                    payloadBytes = null,
                    recipientScriptLen = recipientScriptHex.length / 2,
                    changeScriptLen = changeScriptHex.length / 2,
                    extraFeeSompi = extraFeeSompi
                )
            }
            if (selection.totalSelected < selection.requiredAmount) {
                return@withLock Result.failure(
                    IllegalStateException("Insufficient funds: needed ${selection.requiredAmount}, have ${selection.totalSelected}")
                )
            }
            if (selection.selectedUtxos.size > KsptCodec.MAX_INPUTS) {
                return@withLock Result.failure(
                    IllegalStateException(
                        "This send would need ${selection.selectedUtxos.size} UTXOs, but KasSigner only supports " +
                            "${KsptCodec.MAX_INPUTS} inputs per transaction. Send a smaller amount or consolidate this address first."
                    )
                )
            }

            val built = coldOutputs(selection, recipientScriptHex, changeScriptHex)
            val outputs = built.outputs

            val rawTx = RawTransaction(
                inputs = selection.selectedUtxos.map { RawInput(previousOutpoint = it.outpoint, signatureScript = "") },
                outputs = outputs
            )

            Result.success(
                UnsignedColdTx(
                    rawTx = rawTx,
                    inputUtxos = selection.selectedUtxos,
                    feeSompi = built.paidFeeSompi,
                    changeSompi = built.changeSompi,
                    fromAddress = fromAddress
                )
            )
        } catch (e: Exception) {
            Log.e("ColdStorageSendEngine", "Failed to build unsigned transaction", e)
            Result.failure(e)
        }
    }

    /**
     * Live max-sendable estimate — matches iOS's `estimateMaxAmount` exactly: a fresh
     * `getUtxos` fetch, not the cached balance shown in the address list. That cached value can
     * be stale (this address's real balance moved since the list was last refreshed), so basing
     * Max on it can offer more than what's actually spendable, and the real build then fails
     * with insufficient funds even though the on-screen "Available" looked fine.
     */
    /** If coin control has fixed a UTXO set ([manualUtxos]), Max reflects only that subset
     *  (re-resolved against this call's own fresh fetch, same as [buildUnsignedTransaction])
     *  rather than the whole address's balance. */
    /** The Fast / Priority / custom extra ([feeMultiplier], [customExtraFeeSompi]) comes off flat,
     *  derived from this same base - see [com.kachat.app.util.SendFeeModel.maxAfterFees]. */
    suspend fun estimateMaxAmount(
        fromAddress: String,
        feeRateOverride: Long? = null,
        manualUtxos: List<UtxoEntry>? = null,
        feeMultiplier: Long = 1L,
        customExtraFeeSompi: Long? = null
    ): Long {
        val api = networkService.kaspaRestApi.value
            ?: throw IllegalStateException("Network service unavailable")
        val fetched = pendingSpent.filter(fromAddress, nodePoolManager.getUtxosByAddress(fromAddress) ?: api.getUtxos(fromAddress))
        if (fetched.isEmpty()) return 0L

        val utxos = if (!manualUtxos.isNullOrEmpty()) {
            val freshByOutpoint = fetched.associateBy { it.outpoint }
            manualUtxos.mapNotNull { freshByOutpoint[it.outpoint] }
        } else {
            // Only as much as ONE transaction can actually spend. Selection takes UTXOs
            // largest-first and stops when the amount is covered, so the most a single send can
            // move is the largest [KsptCodec.MAX_INPUTS] of them. Summing all of them, which is
            // what this used to do, offered a Max that could not be built: the build needs every
            // UTXO to reach it, trips the input cap, and refuses - and the reader only finds that
            // out after pressing Build. Compound is the way to spend the rest, and it is a tap
            // away in the same menu.
            fetched.sortedByDescending { it.utxoEntry.amount }.take(KsptCodec.MAX_INPUTS)
        }
        if (utxos.isEmpty()) return 0L

        val totalBalance = UtxoMath.total(utxos.map { it.utxoEntry.amount }) // throws on invalid node data (IOS-020)
        val feeRateSompiPerGram = feeRateOverride?.coerceAtLeast(KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM)
            ?: fetchQuotedFeeRateSompiPerGram()

        return SendFeeModel.maxAfterFees(totalBalance, utxos.size, feeRateSompiPerGram, feeMultiplier, customExtraFeeSompi)
    }

    /**
     * Live preview of what automatic selection *would* pick for [amountSompi] at
     * [feeRateSompiPerGram] — same selector [buildUnsignedTransaction] itself uses, just without
     * actually building. Lets the send form show a fee that's already exact (not the 1-input
     * reference-mass guess) whenever a fresh preview is available, and — critically — lets the
     * form pass this exact same UTXO set into the real build as `manualUtxos`, so the two numbers
     * can't diverge the way they could when each independently guessed at the input count. Uses
     * standard 34-byte output script lengths (matching the form's own reference-mass constant)
     * since this only needs to be right about *how many inputs*, not the recipient's exact
     * address. The Fast / Priority / custom extra is settled together with the inputs it needs
     * ([SendFeeModel.previewAutomaticSelection]); the build passes the preview's extra.
     */
    suspend fun previewAutomaticSelection(
        fromAddress: String,
        amountSompi: Long,
        feeRateSompiPerGram: Long,
        feeMultiplier: Long = 1L,
        customExtraFeeSompi: Long? = null
    ): SendFeeModel.Preview? {
        if (amountSompi <= 0) return null
        val api = networkService.kaspaRestApi.value ?: return null
        val utxos = try {
            pendingSpent.filter(fromAddress, nodePoolManager.getUtxosByAddress(fromAddress) ?: api.getUtxos(fromAddress))
        } catch (e: Exception) {
            return null
        }
        return SendFeeModel.previewAutomaticSelection(
            utxos = utxos,
            amountSompi = amountSompi,
            feeRateSompiPerGram = feeRateSompiPerGram,
            feeMultiplier = feeMultiplier,
            customExtraFeeSompi = customExtraFeeSompi
        )
    }

    /** Raw UTXOs at [fromAddress] for the coin-control picker — unlike
     *  [com.kachat.app.services.ColdStorageAddressDiscovery.getUtxos] (a simplified display
     *  model), these are the actual [UtxoEntry] objects [buildUnsignedTransaction] and
     *  [estimateMaxAmount] accept as `manualUtxos`. */
    suspend fun fetchUtxos(fromAddress: String): List<UtxoEntry> {
        return try {
            fetchUtxosOrThrow(fromAddress)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** [fetchUtxos], throwing when the node cannot be asked rather than reading as empty - see
     *  [KaspaWalletEngine.fetchUtxosOrThrow]. */
    suspend fun fetchUtxosOrThrow(fromAddress: String): List<UtxoEntry> {
        val api = networkService.kaspaRestApi.value ?: throw IllegalStateException(COIN_FETCH_NOT_CONNECTED)
        return pendingSpent.filter(fromAddress, api.getUtxos(fromAddress))
    }

    data class CompoundInputs(val utxos: List<UtxoEntry>, val hasMore: Boolean)

    /**
     * Cold-storage "Compound UTXOs" is a single self-send that merges as many of this address's
     * UTXOs as one KasSigner-signable transaction can hold. A KSPT transaction is capped at
     * [KsptCodec.MAX_INPUTS] inputs, so this returns the largest up-to-cap spendable UTXOs at
     * [fromAddress] (largest-first, so each round sheds the most value and converges fastest),
     * plus whether more than that many remain. Merging cap -> 1 per round means an address with N
     * UTXOs takes ceil((N-1)/(cap-1)) rounds; the caller repeats Compound until a single UTXO is
     * left.
     */
    suspend fun compoundInputs(fromAddress: String): CompoundInputs {
        val sorted = fetchUtxos(fromAddress).sortedByDescending { it.utxoEntry.amount }
        val capped = sorted.take(KsptCodec.MAX_INPUTS)
        return CompoundInputs(capped, sorted.size > KsptCodec.MAX_INPUTS)
    }

    /** Live quoted fee rate (sompi per mass-gram) — whichever is higher, the network's current
     *  "normal" bucket quote or the protocol minimum. Falls back to the minimum on any request
     *  failure. Shared by [buildUnsignedTransaction]'s default, [estimateMaxAmount]'s default,
     *  and the send form's own fee preview (via [com.kachat.app.viewmodels.ColdStorageViewModel]),
     *  so all three always agree on the same rate instead of each doing its own separate fetch
     *  that could return a slightly different quote and make the fee shown before Build not
     *  match what the real transaction costs. */
    suspend fun fetchQuotedFeeRateSompiPerGram(): Long {
        val api = networkService.kaspaRestApi.value ?: return KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM
        return try {
            val estimate = api.getFeeEstimate()
            val quoted = estimate.normalBuckets.firstOrNull()?.feerate ?: KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM.toDouble()
            ceil(quoted).toLong().coerceAtLeast(KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM)
        } catch (e: Exception) {
            KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM
        }
    }

    /** KSPT-encodes [tx] for display as an (animated) QR sequence — see [com.kachat.app.util.QrFrameChunker]. */
    fun toKspt(tx: UnsignedColdTx): ByteArray {
        return KsptCodec.encodeUnsigned(
            txVersion = tx.rawTx.version,
            lockTime = tx.rawTx.lockTime,
            subnetworkIdHex = tx.rawTx.subnetworkId,
            gas = tx.rawTx.gas,
            payloadHex = tx.rawTx.payload,
            inputs = tx.rawTx.inputs.mapIndexed { index, input ->
                val utxo = tx.inputUtxos[index]
                KsptCodec.UnsignedInput(
                    prevTxId = input.previousOutpoint.transactionId,
                    prevIndex = input.previousOutpoint.index,
                    amountSompi = utxo.utxoEntry.amount,
                    sequence = input.sequence,
                    sigOpCount = input.sigOpCount,
                    spkVersion = 0,
                    spkScriptHex = utxo.utxoEntry.scriptPublicKey.scriptPublicKey
                )
            },
            outputs = tx.rawTx.outputs.map { output ->
                KsptCodec.UnsignedOutput(
                    valueSompi = output.amount,
                    spkVersion = output.scriptPublicKey.version,
                    spkScriptHex = output.scriptPublicKey.scriptPublicKey
                )
            }
        )
    }

    /**
     * Merges a scanned signed-KSPT response's per-input Schnorr signatures back into
     * [unsignedTx]'s inputs and broadcasts. Verifies every input's outpoint AND every output's
     * amount/script against what was actually sent for signing before broadcasting anything — a
     * compromised/malfunctioning device altering the destination or amount must fail loudly here,
     * not get silently broadcast.
     */
    suspend fun broadcastSigned(unsignedTx: UnsignedColdTx, decoded: KsptCodec.Decoded): Result<String> {
        return try {
            require(decoded.signed) { "Scanned transaction is not signed" }
            require(decoded.inputs.size == unsignedTx.rawTx.inputs.size) { "Signed transaction has a different number of inputs" }
            require(decoded.outputs.size == unsignedTx.rawTx.outputs.size) { "Signed transaction has a different number of outputs" }

            decoded.outputs.forEachIndexed { index, decodedOutput ->
                val original = unsignedTx.rawTx.outputs[index]
                require(decodedOutput.valueSompi == original.amount && decodedOutput.spkScriptHex == original.scriptPublicKey.scriptPublicKey) {
                    "Signed transaction's outputs don't match what was sent for signing, so it won't be broadcast"
                }
            }

            val signedInputs = unsignedTx.rawTx.inputs.mapIndexed { index, input ->
                val decodedInput = decoded.inputs[index]
                require(
                    decodedInput.prevTxId == input.previousOutpoint.transactionId &&
                        decodedInput.prevIndex == input.previousOutpoint.index
                ) { "Signed transaction's input $index doesn't match what was sent for signing" }

                val sigHex = decodedInput.signatureHex
                    ?: return Result.failure(IllegalStateException("Input $index wasn't signed"))
                val sigBytes = sigHex.hexToBytesLocal()
                require(sigBytes.size == 64) { "Unexpected signature length for input $index" }

                // Only SIGHASH_ALL: a NONE / ANYONECANPAY signature doesn't commit to the outputs,
                // so anyone seeing it in the mempool could redirect the funds (audit IOS-019).
                val sighashType = requireSighashAll(index, decodedInput.sighashType)

                val sigScript = ByteArray(66)
                sigScript[0] = 0x41
                sigBytes.copyInto(sigScript, 1)
                sigScript[65] = sighashType.toByte()
                input.copy(signatureScript = sigScript.toHexStringLocal())
            }

            val signedTx = unsignedTx.rawTx.copy(inputs = signedInputs)
            // A submit error is checked against the network before it counts: a signed Cold
            // Storage send reported failed would be signed and sent again (audit IOS-014).
            val txId = nodePoolManager.submitConfirmingKnown(signedTx, networkService.kaspaRestApi.value) {
                nodePoolManager.getBroadcastConnection().submitTransaction(signedTx)
            }
            // Spent: a send built before the index catches up must not pick these again (AND-015).
            recordSpent(unsignedTx)
            Result.success(txId)
        } catch (e: Exception) {
            Log.e("ColdStorageSendEngine", "Failed to broadcast signed transaction", e)
            Result.failure(e)
        }
    }

    /** Records [tx]'s inputs as spent from the address they came from. */
    private fun recordSpent(tx: UnsignedColdTx) {
        if (tx.fromAddress.isNotEmpty()) {
            pendingSpent.record(tx.fromAddress, tx.rawTx.inputs.map { it.previousOutpoint })
        } else {
            tx.inputUtxos.groupBy { it.address }.forEach { (address, utxos) ->
                pendingSpent.record(address, utxos.map { it.outpoint })
            }
        }
    }

    /** A Cold Storage send's outputs, and what it really pays - see [coldOutputs]. */
    internal data class ColdOutputs(
        val outputs: List<RawOutputWithVersion>,
        /** The change output's value, 0 when none was emitted. */
        val changeSompi: Long,
        /** Everything the inputs hold that no output carries: the network fee plus any dust
         *  folded into it. What the confirmation screen shows. */
        val paidFeeSompi: Long
    )

    internal companion object {
        /** SIGHASH_ALL - the only signature type a Cold Storage send broadcasts. */
        const val SIGHASH_ALL = 0x01

        /**
         * The sighash type input [index] was signed with, which must be SIGHASH_ALL (an absent
         * byte means the signer's default, SIGHASH_ALL). Anything else - NONE, SINGLE,
         * ANYONECANPAY - does not commit to the whole transaction, so a buggy or tampered signer's
         * signature is refused before broadcast (iOS 5096466, audit IOS-019).
         */
        fun requireSighashAll(index: Int, sighashType: Int?): Int {
            val type = sighashType ?: SIGHASH_ALL
            if (type != SIGHASH_ALL) {
                throw IllegalStateException(
                    "Input $index was signed with a signature type that doesn't cover the whole transaction, so it won't be broadcast"
                )
            }
            return type
        }

        /**
         * The recipient output, plus the change whenever this transaction's KIP-9 storage mass
         * lets it stand (the hot wallet's rule, and KasSigner's own dust test). Only a remainder
         * of at most [KaspaUtxoSelector.MAX_FOLDED_CHANGE_SOMPI] is folded into the fee; a bigger
         * one that cannot stand is refused with the small-send message (iOS dd4d977 / e6f0dfe).
         *
         * The fee reported is inputs - outputs, so a folded remainder is shown as paid rather
         * than hidden behind the network fee alone (iOS e6f0dfe, audit IOS-013).
         */
        fun coldOutputs(
            selection: KaspaUtxoSelector.SelectionResult,
            recipientScriptHex: String,
            changeScriptHex: String
        ): ColdOutputs {
            val outputs = mutableListOf(
                RawOutputWithVersion(amount = selection.finalAmount, scriptPublicKey = ScriptPublicKeyWithVersion(recipientScriptHex, 0))
            )
            val inputAmounts = selection.selectedUtxos.map { it.utxoEntry.amount }
            if (selection.storageMassBlocked || (selection.changeAmount > 0 &&
                    !KaspaUtxoSelector.changeIsKeptOrFoldable(inputAmounts, listOf(selection.finalAmount), selection.changeAmount))
            ) {
                throw IllegalStateException(KaspaUtxoSelector.SMALL_SEND_MASS_MESSAGE)
            }
            val keepsChange = selection.changeAmount > 0 &&
                KaspaMass.fitsStorageMass(inputAmounts, listOf(selection.finalAmount, selection.changeAmount))
            if (keepsChange) {
                outputs.add(
                    RawOutputWithVersion(amount = selection.changeAmount, scriptPublicKey = ScriptPublicKeyWithVersion(changeScriptHex, 0))
                )
            }
            if (!KaspaMass.fitsStorageMass(inputAmounts, outputs.map { it.amount })) {
                throw IllegalStateException(
                    "This amount is too small to send from the coins available (Kaspa storage-mass limit). Try a larger amount, or consolidate this address first."
                )
            }
            if (outputs.size > KsptCodec.MAX_OUTPUTS) {
                throw IllegalStateException("Too many outputs for KSPT")
            }
            val inputTotal = UtxoMath.total(inputAmounts)
            val outputTotal = UtxoMath.total(outputs.map { it.amount })
            val paidFee = if (inputTotal >= outputTotal) inputTotal - outputTotal else selection.estimatedFee
            return ColdOutputs(outputs, if (keepsChange) selection.changeAmount else 0L, paidFee)
        }
    }

    private fun String.hexToBytesLocal(): ByteArray {
        if (isEmpty()) return ByteArray(0)
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun ByteArray.toHexStringLocal(): String = joinToString("") { "%02x".format(it) }
}
