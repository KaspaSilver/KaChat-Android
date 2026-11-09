package com.kachat.app.util

import com.kachat.app.services.UtxoEntry

/**
 * Pure UTXO selection + mass-based fee estimation, shared by every transaction builder
 * (regular sends in `KaspaWalletEngine`, KNS commit transactions in `KnsInscriptionEngine`) —
 * extracted so the logic exists in exactly one place rather than being duplicated per builder.
 */
object KaspaUtxoSelector {
    /**
     * Max inputs in a single transaction. Kaspa caps a transaction's mass (~100,000 grams); each
     * input costs ~1,118 grams (dominated by 1,000g sig-op mass), so ~89 inputs is the hard ceiling
     * — 80 leaves headroom for outputs/payload. Consolidating an address with more UTXOs than this
     * must be done one mass-safe transaction at a time (see WalletViewModel.maxConsolidatableChunk).
     * Matches iOS's KasiaTransactionBuilder.maxInputsPerTransaction.
     */
    const val MAX_INPUTS_PER_TRANSACTION = 80

    /**
     * The most a payment may fold into its fee when its change cannot stand as an output (iOS
     * dd4d977). A change output is only ever too small for KIP-9 storage mass below about
     * 0.1 KAS (its own harmonic term is C / change), so a larger remainder that does not fit
     * means the RECIPIENT amount is what breaks the budget - and folding then paid the whole
     * change to the miners: a 0.0999 KAS send from a 10.1 KAS coin built a ~10 KAS fee.
     */
    const val MAX_FOLDED_CHANGE_SOMPI = 10_000_000L // 0.1 KAS

    const val SMALL_SEND_MASS_MESSAGE =
        "This amount can't be sent from these coins without giving most of the change away as a network fee. Try a slightly larger amount, or compound this address first."

    /** iOS's `+ 3` on every estimate in KaChatTransactionBuilder: a small buffer over the network
     *  minimum against an under-fee rejection. Paid, never shown in a fee estimate. */
    const val FEE_BUFFER_SOMPI = 3L

    /**
     * Why a payment can't be built, in iOS's words: what `selectUtxosForPayment` and
     * `selectManualUtxosForPayment` throw ([SelectionResult.refusal]). The estimate says it before
     * the swipe and the send refuses with the same words.
     */
    sealed class Refusal(val message: String) {
        data object InsufficientFunds : Refusal("Insufficient funds for payment")

        /** Funds were enough, but no input set fit storage mass without paying real change away
         *  as fee - see [MAX_FOLDED_CHANGE_SOMPI]. */
        data object StorageMass : Refusal(SMALL_SEND_MASS_MESSAGE)

        /** Automatic selection reached the input cap and still had coins to add. */
        data object TooManyInputs : Refusal(
            "This send needs more than $MAX_INPUTS_PER_TRANSACTION inputs. Compound (consolidate) this address's UTXOs first, then try again."
        )

        /** Coin control picked more coins than one transaction can spend. */
        data class TooManyInputsSelected(val count: Int) : Refusal(
            "Too many inputs selected ($count). Kaspa transactions are limited to about $MAX_INPUTS_PER_TRANSACTION inputs - select fewer, or compound this address first."
        )
    }

    /** A [Refusal] as the error a fee estimate or a send fails with. */
    class SendRefusedException(val refusal: Refusal) : IllegalStateException(refusal.message)

    /**
     * Whether a payment of [recipientAmounts] with [changeAmount] left over may be built from
     * [inputAmounts]: the change stands as its own output, or it is dust small enough to fold
     * into the fee. False means folding would give real change away.
     */
    fun changeIsKeptOrFoldable(inputAmounts: List<Long>, recipientAmounts: List<Long>, changeAmount: Long): Boolean =
        changeAmount <= MAX_FOLDED_CHANGE_SOMPI ||
            KaspaMass.fitsStorageMass(inputAmounts, recipientAmounts + changeAmount)

    // Every selector throws IllegalStateException(UtxoMath.INVALID_AMOUNT_MESSAGE) when a coin's
    // amount, or the total, is not a real value (a broken or malicious node - audit IOS-020).

    data class SelectionResult(
        val selectedUtxos: List<UtxoEntry>,
        val totalSelected: Long,
        val estimatedFee: Long,
        val finalAmount: Long,
        val changeAmount: Long,
        val requiredAmount: Long,
        /** Funds were enough, but no input set fit storage mass without paying real change away
         *  as fee - see [MAX_FOLDED_CHANGE_SOMPI]. The send must be refused. */
        val storageMassBlocked: Boolean = false,
        /** This is priced for the recipient alone (iOS `emitChange` false): there is no change
         *  output, what is left ([changeAmount]) is fee, and the caller must not add one. Only with
         *  `allowNoChangeOutput`. */
        val noChangeOutput: Boolean = false,
        /** Why this payment can't go ([Refusal]); null when it can. Only ever set for a payment
         *  (an amount above 0). */
        val refusal: Refusal? = null
    ) {
        /**
         * Change too small to keep: there is no output for it, so it goes to the fee, and the
         * wallet loses it like any other fee (iOS bfe9f022 `SendFeeEstimate.foldedChange`). The
         * same rule the send uses to decide whether a change output stands.
         */
        val foldedChangeSompi: Long
            get() {
                if (changeAmount <= 0) return 0L
                if (noChangeOutput) return changeAmount
                val recipient = if (finalAmount > 0) listOf(finalAmount) else emptyList()
                return if (KaspaMass.fitsStorageMass(selectedUtxos.map { it.utxoEntry.amount }, recipient + changeAmount)) 0L else changeAmount
            }
    }

    /** The mass-priced fee of a payment with no change output, plus the flat extra. */
    private fun feeWithoutChange(
        inputCount: Int,
        feeRateSompiPerGram: Long,
        payloadSize: Int,
        recipientScriptLen: Int,
        extraFeeSompi: Long,
        signatureScriptSize: Int,
    ): Long = KaspaMass.calculateFee(
        KaspaMass.calculateMass(
            numInputs = inputCount,
            outputScriptLens = listOf(recipientScriptLen),
            payloadSize = payloadSize,
            sigScriptLens = KaspaMass.sigScriptLensFor(inputCount, signatureScriptSize)
        ),
        feeRateSompiPerGram
    ) + FEE_BUFFER_SOMPI + extraFeeSompi

    fun selectUtxosAndCalculateFee(
        utxos: List<UtxoEntry>,
        amountSompi: Long,
        feeRateSompiPerGram: Long,
        payloadBytes: ByteArray?,
        recipientScriptLen: Int,
        changeScriptLen: Int,
        /** A flat extra on top of the mass-priced fee - the Send KAS sheet's Fast / Priority /
         *  custom fee (iOS extraFeeSompi, 62c2773). Paid exactly: it is part of [SelectionResult.estimatedFee]. */
        extraFeeSompi: Long = 0L,
        /** Each input's signature-script size: [KaspaMass.KIP22_SIGNATURE_SCRIPT_LEN] for a
         *  quantum-safe (KIP-22) source, whose bigger inputs every estimate must price (iOS
         *  `signatureScriptSize`). */
        signatureScriptSize: Int = KaspaMass.SCHNORR_SIGNATURE_SCRIPT_LEN,
        /** May answer with [SelectionResult.noChangeOutput]: only for a caller that honours it. */
        allowNoChangeOutput: Boolean = false
    ): SelectionResult {
        var totalSelected = 0L
        val selectedUtxos = mutableListOf<UtxoEntry>()
        var estimatedFee = 0L

        val payloadSize = payloadBytes?.size ?: 0

        // A zero-amount send (every 1:1/broadcast message — a self-stash) never gets a recipient
        // output at all: KaspaWalletEngine.sendKaspa skips it outright since a 0-value output is
        // non-standard and gets rejected, leaving only the change output. Pricing those against 2
        // outputs (as this used to do unconditionally) silently overpaid every message's real
        // on-chain fee by a phantom output's mass (~412 mass, ~0.0004 KAS at the network minimum
        // rate) — matches iOS's selectUtxosForContextualMessage, which prices real message sends
        // off a single output. A real payment (amountSompi > 0) keeps the 2-output assumption:
        // if change ends up being dust and gets dropped, the real required fee is only lower, so
        // that direction never underpays the network.
        val outputScriptLens = if (amountSompi > 0) listOf(recipientScriptLen, changeScriptLen) else listOf(changeScriptLen)

        // Iterate and select UTXOs until amount + fee is covered - and, for a payment, until the
        // change either fits storage mass as its own output or is dust. A covered set whose real
        // change does not fit keeps adding inputs (a smaller coin brings the mass back under)
        // rather than stopping there and later folding the change into the fee.
        var storageMassBlocked = false
        var tooManyInputs = false
        var settled = false
        // The first set that pays by folding its change into the fee, and that total fee. Held
        // back while the remaining coins are tried: another coin can keep the change instead
        // (iOS 4f8e14bb).
        var folded: SelectionResult? = null
        var foldedTotalFee = 0L
        for (utxo in utxos.sortedByDescending { it.utxoEntry.amount }) {
            // A payment refuses to build an over-mass transaction: at the input cap with the
            // amount still not settled, more inputs would exceed Kaspa's mass limit (iOS
            // selectUtxosForPayment).
            if (amountSompi > 0 && selectedUtxos.size >= MAX_INPUTS_PER_TRANSACTION) {
                folded?.let { return it }
                tooManyInputs = true
                break
            }
            selectedUtxos.add(utxo)
            // Node-supplied: a nonsense amount is refused, never wrapped into a total (IOS-020).
            totalSelected = UtxoMath.add(totalSelected, utxo.utxoEntry.amount)

            val mass = KaspaMass.calculateMass(
                numInputs = selectedUtxos.size,
                outputScriptLens = outputScriptLens,
                payloadSize = payloadSize,
                sigScriptLens = KaspaMass.sigScriptLensFor(selectedUtxos.size, signatureScriptSize)
            )
            estimatedFee = KaspaMass.calculateFee(mass, feeRateSompiPerGram) + FEE_BUFFER_SOMPI + extraFeeSompi

            if (amountSompi > 0 && allowNoChangeOutput) {
                // iOS selectUtxosForPayment, line by line. Keep the change whenever this
                // transaction's own storage mass allows it.
                if (totalSelected <= amountSompi) continue
                val inputAmounts = selectedUtxos.map { it.utxoEntry.amount }
                if (totalSelected - amountSompi >= estimatedFee &&
                    KaspaMass.fitsStorageMass(inputAmounts, listOf(amountSompi, totalSelected - amountSompi - estimatedFee))
                ) {
                    // a coin added just to keep a few sompi of change must not cost more than them
                    folded?.let { if (foldedTotalFee <= estimatedFee) return it }
                    settled = true
                    break
                }
                // Without change (a Max send, or dust too small to keep) - only when the leftover
                // really is dust. A bigger leftover that will not fit means the send itself is too
                // small for this coin set: keep adding inputs (a smaller coin lowers the mass),
                // and refuse if none does, rather than paying the change away as fee.
                val feeNoChange = feeWithoutChange(
                    selectedUtxos.size, feeRateSompiPerGram, payloadSize, recipientScriptLen, extraFeeSompi, signatureScriptSize
                )
                if (totalSelected - amountSompi >= feeNoChange) {
                    // The recipient's output alone must fit too: a small send carved from a bigger
                    // coin breaks the budget without any change (0.03 KAS from one 0.1 KAS coin),
                    // and the node would refuse it after the Review had shown the folded change as
                    // fee. Another coin lowers that mass, so keep adding (iOS fitsStorageMass).
                    if (totalSelected - amountSompi - feeNoChange <= MAX_FOLDED_CHANGE_SOMPI &&
                        KaspaMass.fitsStorageMass(inputAmounts, listOf(amountSompi))
                    ) {
                        // Not yet: coins of 10.05 and 5 KAS sending 10 would hand 0.05 KAS to the
                        // miners here, where adding the 5 KAS coin keeps it as change.
                        if (folded == null) {
                            foldedTotalFee = totalSelected - amountSompi
                            folded = SelectionResult(
                                selectedUtxos = selectedUtxos.toList(),
                                totalSelected = totalSelected,
                                estimatedFee = feeNoChange,
                                finalAmount = amountSompi,
                                changeAmount = totalSelected - amountSompi - feeNoChange,
                                requiredAmount = amountSompi + feeNoChange,
                                noChangeOutput = true,
                            )
                        }
                    } else {
                        storageMassBlocked = true
                    }
                }
            } else if (totalSelected >= (amountSompi + estimatedFee)) {
                val change = totalSelected - amountSompi - estimatedFee
                if (amountSompi <= 0 || change <= 0 ||
                    changeIsKeptOrFoldable(selectedUtxos.map { it.utxoEntry.amount }, listOf(amountSompi), change)
                ) {
                    storageMassBlocked = false
                    settled = true
                    break
                }
                storageMassBlocked = true
            }
        }

        // Only when no later set kept the change: iOS returns that set from inside its loop.
        if (!settled) folded?.let { return it }

        val requiredAmount = amountSompi + estimatedFee
        val changeAmount = totalSelected - amountSompi - estimatedFee

        val refusal = when {
            settled || amountSompi <= 0 -> null
            tooManyInputs -> Refusal.TooManyInputs
            storageMassBlocked -> Refusal.StorageMass
            else -> Refusal.InsufficientFunds
        }

        return SelectionResult(
            selectedUtxos = selectedUtxos,
            totalSelected = totalSelected,
            estimatedFee = estimatedFee,
            finalAmount = amountSompi,
            changeAmount = changeAmount,
            requiredAmount = requiredAmount,
            storageMassBlocked = !settled && storageMassBlocked,
            refusal = refusal
        )
    }

    /**
     * Coin control: prices and validates a user-picked, fixed set of UTXOs instead of greedily
     * growing one (iOS selectManualUtxosForPayment): the same "with change, else without change,
     * else refused" rule as [selectUtxosAndCalculateFee], evaluated once against the fixed set.
     */
    fun selectManualUtxosAndCalculateFee(
        utxos: List<UtxoEntry>,
        amountSompi: Long,
        feeRateSompiPerGram: Long,
        recipientScriptLen: Int,
        changeScriptLen: Int,
        /** The transaction's payload (a chat payment's memo) - priced into the mass, so coin
         *  control never underpays a payload-carrying send. */
        payloadBytes: ByteArray? = null,
        /** Flat extra fee on top - see [selectUtxosAndCalculateFee]. */
        extraFeeSompi: Long = 0L,
        /** Per-input signature-script size - see [selectUtxosAndCalculateFee]. */
        signatureScriptSize: Int = KaspaMass.SCHNORR_SIGNATURE_SCRIPT_LEN,
        /** See [selectUtxosAndCalculateFee]. */
        allowNoChangeOutput: Boolean = false
    ): SelectionResult {
        val totalSelected = UtxoMath.total(utxos.map { it.utxoEntry.amount })
        val outputScriptLens = if (amountSompi > 0) listOf(recipientScriptLen, changeScriptLen) else listOf(changeScriptLen)
        val mass = KaspaMass.calculateMass(
            numInputs = utxos.size,
            outputScriptLens = outputScriptLens,
            payloadSize = payloadBytes?.size ?: 0,
            sigScriptLens = KaspaMass.sigScriptLensFor(utxos.size, signatureScriptSize)
        )
        var estimatedFee = KaspaMass.calculateFee(mass, feeRateSompiPerGram) + FEE_BUFFER_SOMPI + extraFeeSompi

        var noChangeOutput = false
        var refusal: Refusal? = null
        if (amountSompi > 0 && allowNoChangeOutput) {
            val inputAmounts = utxos.map { it.utxoEntry.amount }
            when {
                // A fixed input set over the mass cap would be rejected by the node.
                utxos.size > MAX_INPUTS_PER_TRANSACTION -> refusal = Refusal.TooManyInputsSelected(utxos.size)
                // Change stays when the shape fits under KIP-9.
                totalSelected > amountSompi && totalSelected - amountSompi >= estimatedFee &&
                    KaspaMass.fitsStorageMass(inputAmounts, listOf(amountSompi, totalSelected - amountSompi - estimatedFee)) -> Unit
                else -> {
                    // Without change - dust only: never pay real change away to make a shape fit.
                    val feeNoChange = feeWithoutChange(
                        utxos.size, feeRateSompiPerGram, payloadBytes?.size ?: 0, recipientScriptLen, extraFeeSompi, signatureScriptSize
                    )
                    if (totalSelected > amountSompi && totalSelected - amountSompi >= feeNoChange) {
                        // the recipient's output alone must fit the storage-mass budget too (see
                        // the greedy selector)
                        if (totalSelected - amountSompi - feeNoChange <= MAX_FOLDED_CHANGE_SOMPI &&
                            KaspaMass.fitsStorageMass(inputAmounts, listOf(amountSompi))
                        ) {
                            estimatedFee = feeNoChange
                            noChangeOutput = true
                        } else {
                            refusal = Refusal.StorageMass
                        }
                    } else {
                        refusal = Refusal.InsufficientFunds
                    }
                }
            }
        } else if (amountSompi > 0 && totalSelected < amountSompi + estimatedFee) {
            refusal = Refusal.InsufficientFunds
        }

        val requiredAmount = amountSompi + estimatedFee
        val changeAmount = totalSelected - amountSompi - estimatedFee

        return SelectionResult(
            selectedUtxos = utxos,
            totalSelected = totalSelected,
            estimatedFee = estimatedFee,
            finalAmount = amountSompi,
            changeAmount = changeAmount,
            requiredAmount = requiredAmount,
            storageMassBlocked = refusal == Refusal.StorageMass,
            noChangeOutput = noChangeOutput,
            refusal = refusal
        )
    }
}
