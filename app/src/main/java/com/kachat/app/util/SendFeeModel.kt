package com.kachat.app.util

import com.kachat.app.services.UtxoEntry

/**
 * The Send screens' fee model, as on iOS (SendKaspaComponents / SpendingAddressWithdrawView) and
 * the chat Send KAS sheet: a base fee priced at the network minimum on the REAL input count,
 * plus Fast / Priority / a custom fee as a flat extra on top, paid exactly
 * ([com.kachat.app.services.KaspaWalletEngine.sendKaspa]'s `extraFeeSompi`).
 *
 * The screens used to turn the chosen total fee into a rate (total / one-input mass) and pass
 * that rate to the engine, which charges `rate * realMass`. Every extra input then multiplied the
 * fee: a 0.1 KAS custom fee on a 20-input send paid about 1.14 KAS. A flat extra cannot scale
 * with the input count, so the fee shown is the fee paid.
 *
 * Pure, so the screens, the view models and the unit tests all price a send the same way.
 */
object SendFeeModel {
    /** Standard P2PK scriptPublicKey length, for a recipient or change output whose exact script
     *  is not known yet. */
    const val STANDARD_SCRIPT_LEN = 34

    /** The extra over [baseFeeSompi]: the custom extra when one is set, else what the speed adds
     *  (Fast 2x, Priority 5x the base). */
    fun extraFee(baseFeeSompi: Long, feeMultiplier: Long, customExtraFeeSompi: Long?): Long =
        customExtraFeeSompi?.coerceAtLeast(0L) ?: (baseFeeSompi * (feeMultiplier - 1)).coerceAtLeast(0L)

    /** Base fee for [inputCount] inputs paying a recipient plus change. */
    fun baseFee(
        inputCount: Int,
        feeRateSompiPerGram: Long,
        payloadSize: Int = 0,
        recipientScriptLen: Int = STANDARD_SCRIPT_LEN,
        changeScriptLen: Int = STANDARD_SCRIPT_LEN,
        /** Per-input signature-script size: a quantum-safe (KIP-22) source's inputs are bigger
         *  ([KaspaMass.KIP22_SIGNATURE_SCRIPT_LEN]). */
        signatureScriptSize: Int = KaspaMass.SCHNORR_SIGNATURE_SCRIPT_LEN,
    ): Long =
        KaspaMass.calculateFee(
            KaspaMass.calculateMass(
                numInputs = inputCount.coerceAtLeast(1),
                outputScriptLens = listOf(recipientScriptLen, changeScriptLen),
                payloadSize = payloadSize,
                sigScriptLens = KaspaMass.sigScriptLensFor(inputCount.coerceAtLeast(1), signatureScriptSize),
            ),
            feeRateSompiPerGram,
        )

    /**
     * Max for a send that spends [totalSompi] over [inputCount] inputs: what is left after the
     * base fee, the buffer the send pays over it ([KaspaUtxoSelector.FEE_BUFFER_SOMPI], iOS
     * `estimateSendAllFee`) and the extra. The extra is derived from this same base with that
     * buffer, the fee the Review shows ([estimate], iOS d23e6f7a), or is the custom extra - never
     * from a fee estimated for some other amount - so Max always leaves room for exactly the extra
     * a Fast / Priority send of that amount adds. 0 when that is everything.
     */
    fun maxAfterFees(
        totalSompi: Long,
        inputCount: Int,
        feeRateSompiPerGram: Long,
        feeMultiplier: Long = 1L,
        customExtraFeeSompi: Long? = null,
        payloadSize: Int = 0,
        /** See [baseFee]. */
        signatureScriptSize: Int = KaspaMass.SCHNORR_SIGNATURE_SCRIPT_LEN,
        recipientScriptLen: Int = STANDARD_SCRIPT_LEN,
        changeScriptLen: Int = STANDARD_SCRIPT_LEN,
    ): Long {
        val base = baseFee(inputCount, feeRateSompiPerGram, payloadSize, recipientScriptLen, changeScriptLen, signatureScriptSize)
        val extra = extraFee(base + KaspaUtxoSelector.FEE_BUFFER_SOMPI, feeMultiplier, customExtraFeeSompi)
        return (totalSompi - base - KaspaUtxoSelector.FEE_BUFFER_SOMPI - extra).coerceAtLeast(0L)
    }

    /**
     * What a send costs (iOS bfe9f022 `SendFeeEstimate`). Change too small to keep goes to the fee
     * ([KaspaUtxoSelector.SelectionResult.foldedChangeSompi]), which the wallet loses like any
     * other fee, so it counts in what the Review shows. It isn't part of the base the speed tiers
     * multiply, and a tier's extra comes out of it before anything more is spent.
     */
    data class SendFeeEstimate(val networkFeeSompi: Long, val foldedChangeSompi: Long) {
        fun total(extraSompi: Long): Long = networkFeeSompi + maxOf(extraSompi, foldedChangeSompi)
    }

    /**
     * What a send of [amountSompi] costs (iOS `estimatePlainTransferFee` / `estimatePaymentFee`):
     * coin control's exact [manual] coins, else what automatic selection picks from [utxos]. With no
     * [extraFeeSompi] it is the Normal base the speeds multiply; with one, the screens show this
     * estimate's network fee plus its folded change, the shape the send builds at that speed. The
     * network fee is priced on the outputs that transaction really has, with the selector's
     * [KaspaUtxoSelector.FEE_BUFFER_SOMPI] the send pays over it - part of what it pays, so part of
     * the fee shown (iOS d23e6f7a `estimatePlainTransferFee`). Throws [KaspaUtxoSelector.SendRefusedException] when the send can't go
     * (not enough for the amount and fee, real change that storage mass won't let stand, more
     * inputs than one transaction holds), and an [IllegalStateException] when the coins' amounts
     * are not real values ([UtxoMath]). No estimate means no fee: the Review shows a dash.
     */
    fun estimate(
        utxos: List<UtxoEntry>,
        manual: List<UtxoEntry>?,
        amountSompi: Long,
        feeRateSompiPerGram: Long = KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM,
        payloadSize: Int = 0,
        recipientScriptLen: Int = STANDARD_SCRIPT_LEN,
        changeScriptLen: Int = STANDARD_SCRIPT_LEN,
        /** See [baseFee]. */
        signatureScriptSize: Int = KaspaMass.SCHNORR_SIGNATURE_SCRIPT_LEN,
        /** A Fast / Priority / custom extra: the coins are picked and the change worked out with
         *  it, as the send does, and the network fee returned includes it (iOS
         *  `estimatePaymentFee(extraFeeSompi:)`). Change too small to keep then goes on top. */
        extraFeeSompi: Long = 0L,
    ): SendFeeEstimate {
        // Only its size is priced.
        val payload = if (payloadSize > 0) ByteArray(payloadSize) else null
        val selection = if (!manual.isNullOrEmpty()) {
            KaspaUtxoSelector.selectManualUtxosAndCalculateFee(
                utxos = manual,
                amountSompi = amountSompi,
                feeRateSompiPerGram = feeRateSompiPerGram,
                recipientScriptLen = recipientScriptLen,
                changeScriptLen = changeScriptLen,
                payloadBytes = payload,
                extraFeeSompi = extraFeeSompi,
                signatureScriptSize = signatureScriptSize,
                allowNoChangeOutput = true,
            )
        } else {
            KaspaUtxoSelector.selectUtxosAndCalculateFee(
                utxos = utxos,
                amountSompi = amountSompi,
                feeRateSompiPerGram = feeRateSompiPerGram,
                payloadBytes = payload,
                recipientScriptLen = recipientScriptLen,
                changeScriptLen = changeScriptLen,
                extraFeeSompi = extraFeeSompi,
                signatureScriptSize = signatureScriptSize,
                allowNoChangeOutput = true,
            )
        }
        selection.refusal?.let { throw KaspaUtxoSelector.SendRefusedException(it) }
        return SendFeeEstimate(selection.estimatedFee, selection.foldedChangeSompi)
    }

    /**
     * Coin control for a Review's fee or Max (iOS d23e6f7a `resolveReviewSelection`): the picked
     * coins of [manual] still in [spendable], by outpoint; null for automatic selection (nothing
     * picked). When none of the picks is left it throws [goneMessage] - the send's own refusal,
     * `R.string.selected_utxos_are_no_longer_available_please` - rather than estimating automatic
     * selection for a send that would then refuse to go.
     */
    fun resolveReviewSelection(manual: List<UtxoEntry>?, spendable: List<UtxoEntry>, goneMessage: String): List<UtxoEntry>? {
        if (manual.isNullOrEmpty()) return null
        val freshByOutpoint = spendable.associateBy { it.outpoint }
        return manual.mapNotNull { freshByOutpoint[it.outpoint] }.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException(goneMessage)
    }
}
