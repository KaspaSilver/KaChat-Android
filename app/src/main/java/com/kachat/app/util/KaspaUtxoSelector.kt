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

    /**
     * Whether a payment of [recipientAmounts] with [changeAmount] left over may be built from
     * [inputAmounts]: the change stands as its own output, or it is dust small enough to fold
     * into the fee. False means folding would give real change away.
     */
    fun changeIsKeptOrFoldable(inputAmounts: List<Long>, recipientAmounts: List<Long>, changeAmount: Long): Boolean =
        changeAmount <= MAX_FOLDED_CHANGE_SOMPI ||
            KaspaMass.fitsStorageMass(inputAmounts, recipientAmounts + changeAmount)

    data class SelectionResult(
        val selectedUtxos: List<UtxoEntry>,
        val totalSelected: Long,
        val estimatedFee: Long,
        val finalAmount: Long,
        val changeAmount: Long,
        val requiredAmount: Long,
        /** Funds were enough, but no input set fit storage mass without paying real change away
         *  as fee - see [MAX_FOLDED_CHANGE_SOMPI]. The send must be refused. */
        val storageMassBlocked: Boolean = false
    )

    fun selectUtxosAndCalculateFee(
        utxos: List<UtxoEntry>,
        amountSompi: Long,
        feeRateSompiPerGram: Long,
        payloadBytes: ByteArray?,
        recipientScriptLen: Int,
        changeScriptLen: Int,
        /** A flat extra on top of the mass-priced fee - the Send KAS sheet's Fast / Priority /
         *  custom fee (iOS extraFeeSompi, 62c2773). Paid exactly: it is part of [SelectionResult.estimatedFee]. */
        extraFeeSompi: Long = 0L
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
        for (utxo in utxos.sortedByDescending { it.utxoEntry.amount }) {
            selectedUtxos.add(utxo)
            totalSelected += utxo.utxoEntry.amount

            val mass = KaspaMass.calculateMass(
                numInputs = selectedUtxos.size,
                outputScriptLens = outputScriptLens,
                payloadSize = payloadSize
            )
            estimatedFee = KaspaMass.calculateFee(mass, feeRateSompiPerGram) + extraFeeSompi

            if (totalSelected >= (amountSompi + estimatedFee)) {
                val change = totalSelected - amountSompi - estimatedFee
                if (amountSompi <= 0 || change <= 0 ||
                    changeIsKeptOrFoldable(selectedUtxos.map { it.utxoEntry.amount }, listOf(amountSompi), change)
                ) {
                    storageMassBlocked = false
                    break
                }
                storageMassBlocked = true
            }
        }

        var finalAmount = amountSompi
        var requiredAmount = amountSompi + estimatedFee

        // Check if we can fulfill the request
        if (totalSelected < requiredAmount) {
            // "Max Send" logic: if we are close (within 2000 sompi), adjust the amount
            if (totalSelected > estimatedFee && (requiredAmount - totalSelected) < 2000) {
                finalAmount = totalSelected - estimatedFee
                requiredAmount = totalSelected
            }
        }

        val changeAmount = totalSelected - finalAmount - estimatedFee

        return SelectionResult(
            selectedUtxos = selectedUtxos,
            totalSelected = totalSelected,
            estimatedFee = estimatedFee,
            finalAmount = finalAmount,
            changeAmount = changeAmount,
            requiredAmount = requiredAmount,
            storageMassBlocked = storageMassBlocked
        )
    }

    /**
     * Coin control: prices and validates a user-picked, fixed set of UTXOs instead of greedily
     * growing one. Same "close enough" leeway and always-price-a-change-output policy as
     * [selectUtxosAndCalculateFee].
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
        extraFeeSompi: Long = 0L
    ): SelectionResult {
        val totalSelected = utxos.sumOf { it.utxoEntry.amount }
        val outputScriptLens = if (amountSompi > 0) listOf(recipientScriptLen, changeScriptLen) else listOf(changeScriptLen)
        val mass = KaspaMass.calculateMass(numInputs = utxos.size, outputScriptLens = outputScriptLens, payloadSize = payloadBytes?.size ?: 0)
        val estimatedFee = KaspaMass.calculateFee(mass, feeRateSompiPerGram) + extraFeeSompi

        var finalAmount = amountSompi
        var requiredAmount = amountSompi + estimatedFee
        if (totalSelected < requiredAmount) {
            if (totalSelected > estimatedFee && (requiredAmount - totalSelected) < 2000) {
                finalAmount = totalSelected - estimatedFee
                requiredAmount = totalSelected
            }
        }
        val changeAmount = totalSelected - finalAmount - estimatedFee

        return SelectionResult(
            selectedUtxos = utxos,
            totalSelected = totalSelected,
            estimatedFee = estimatedFee,
            finalAmount = finalAmount,
            changeAmount = changeAmount,
            requiredAmount = requiredAmount
        )
    }

    /**
     * For the spending-address "sweep everything on every send" design (see
     * KaspaWalletEngine.sendSpendingPayment): unlike [selectUtxosAndCalculateFee], which stops
     * as soon as it's picked *enough* large UTXOs to cover the amount+fee, this always spends
     * every given UTXO — so a spending address never ends a send holding a leftover fragment
     * behind. In steady state this address only ever holds the single change UTXO from its own
     * last spend (or a top-up), so this doesn't cost anything extra in practice — it only
     * differs from the greedy selector when the address happens to hold more than one UTXO
     * (e.g. multiple top-ups before a spend).
     */
    fun selectAllUtxosAndCalculateFee(
        utxos: List<UtxoEntry>,
        amountSompi: Long,
        feeRateSompiPerGram: Long,
        payloadBytes: ByteArray?,
        recipientScriptLen: Int,
        changeScriptLen: Int,
        /** Flat extra fee on top - see [selectUtxosAndCalculateFee]. */
        extraFeeSompi: Long = 0L
    ): SelectionResult {
        val selectedUtxos = utxos.toList()
        val totalSelected = selectedUtxos.sumOf { it.utxoEntry.amount }
        val payloadSize = payloadBytes?.size ?: 0
        val outputScriptLens = if (amountSompi > 0) listOf(recipientScriptLen, changeScriptLen) else listOf(changeScriptLen)

        val mass = KaspaMass.calculateMass(
            numInputs = selectedUtxos.size,
            outputScriptLens = outputScriptLens,
            payloadSize = payloadSize
        )
        val estimatedFee = KaspaMass.calculateFee(mass, feeRateSompiPerGram) + extraFeeSompi

        var finalAmount = amountSompi
        var requiredAmount = amountSompi + estimatedFee

        // Same "max send" dust tolerance as the greedy selector — if the sweep is short by a
        // negligible amount, take the fee out of the payment rather than failing the send.
        if (totalSelected < requiredAmount) {
            if (totalSelected > estimatedFee && (requiredAmount - totalSelected) < 2000) {
                finalAmount = totalSelected - estimatedFee
                requiredAmount = totalSelected
            }
        }

        val changeAmount = totalSelected - finalAmount - estimatedFee

        return SelectionResult(
            selectedUtxos = selectedUtxos,
            totalSelected = totalSelected,
            estimatedFee = estimatedFee,
            finalAmount = finalAmount,
            changeAmount = changeAmount,
            requiredAmount = requiredAmount
        )
    }
}
