package com.kachat.app.util

import com.kachat.app.services.UtxoEntry

/**
 * The Send screens' fee model, as on iOS (SendKaspaComponents / SpendingAddressWithdrawView) and
 * the chat Send KAS sheet: a base fee priced at the quoted network rate on the REAL input count,
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

    /** Passes of [previewAutomaticSelection]'s fixed point. Each pass that does not settle adds at
     *  least one input, so this only bounds pathological coin sets. */
    private const val MAX_PREVIEW_PASSES = 16

    /** The extra over [baseFeeSompi]: the custom extra when one is set, else what the speed adds
     *  (Fast 2x, Priority 5x the base). */
    fun extraFee(baseFeeSompi: Long, feeMultiplier: Long, customExtraFeeSompi: Long?): Long =
        customExtraFeeSompi?.coerceAtLeast(0L) ?: (baseFeeSompi * (feeMultiplier - 1)).coerceAtLeast(0L)

    /** The coins one transaction can spend: the [maxInputs] largest when there are more, else all
     *  of them (order kept). Mirrors the engine's refusal of anything over
     *  [KaspaUtxoSelector.MAX_INPUTS_PER_TRANSACTION] inputs. */
    fun largestSpendable(
        utxos: List<UtxoEntry>,
        maxInputs: Int = KaspaUtxoSelector.MAX_INPUTS_PER_TRANSACTION,
    ): List<UtxoEntry> =
        if (utxos.size > maxInputs) utxos.sortedByDescending { it.utxoEntry.amount }.take(maxInputs) else utxos

    /** Base fee for [inputCount] inputs paying a recipient plus change. */
    fun baseFee(
        inputCount: Int,
        feeRateSompiPerGram: Long,
        payloadSize: Int = 0,
        recipientScriptLen: Int = STANDARD_SCRIPT_LEN,
        changeScriptLen: Int = STANDARD_SCRIPT_LEN,
    ): Long =
        KaspaMass.calculateFee(
            KaspaMass.calculateMass(
                numInputs = inputCount.coerceAtLeast(1),
                outputScriptLens = listOf(recipientScriptLen, changeScriptLen),
                payloadSize = payloadSize,
            ),
            feeRateSompiPerGram,
        )

    /**
     * Max for a send that spends [totalSompi] over [inputCount] inputs: what is left after the
     * base fee and the extra. The extra is derived from this same base (or is the custom extra),
     * never from a fee estimated for some other amount - so Max always leaves room for exactly the
     * extra a Fast / Priority send of that amount adds.
     */
    fun maxAfterFees(
        totalSompi: Long,
        inputCount: Int,
        feeRateSompiPerGram: Long,
        feeMultiplier: Long = 1L,
        customExtraFeeSompi: Long? = null,
        payloadSize: Int = 0,
    ): Long {
        val base = baseFee(inputCount, feeRateSompiPerGram, payloadSize)
        val extra = extraFee(base, feeMultiplier, customExtraFeeSompi)
        return (totalSompi - base - extra).coerceAtLeast(0L)
    }

    /** What an automatic-selection send of a given amount spends, and what it pays. Passing
     *  [utxos] as coin control and [extraFeeSompi] as the extra reproduces exactly this fee. */
    data class Preview(val utxos: List<UtxoEntry>, val baseFeeSompi: Long, val extraFeeSompi: Long) {
        val totalFeeSompi: Long get() = baseFeeSompi + extraFeeSompi
    }

    /**
     * What automatic selection (largest-first, [KaspaUtxoSelector.selectUtxosAndCalculateFee] - the
     * selector the real send runs) picks for [amountSompi], with the base fee priced on those
     * inputs and the extra on top. Mirrors ColdStorageSendEngine.previewAutomaticSelection, which
     * the Send screen's display used to lack: it assumed one input.
     *
     * The extra changes how many inputs are needed (they must cover amount + base + extra), and a
     * tier's extra depends on the base, so this settles the two together. Whatever pass it ends
     * on, [Preview.baseFeeSompi] is priced on the inputs selected WITH [Preview.extraFeeSompi], so
     * a send passing that extra pays [Preview.totalFeeSompi]. Null when the coins cannot cover it.
     */
    fun previewAutomaticSelection(
        utxos: List<UtxoEntry>,
        amountSompi: Long,
        feeRateSompiPerGram: Long,
        feeMultiplier: Long = 1L,
        customExtraFeeSompi: Long? = null,
        recipientScriptLen: Int = STANDARD_SCRIPT_LEN,
        changeScriptLen: Int = STANDARD_SCRIPT_LEN,
    ): Preview? {
        if (amountSompi <= 0 || utxos.isEmpty()) return null
        var extra = customExtraFeeSompi?.coerceAtLeast(0L) ?: 0L
        var preview: Preview? = null
        repeat(MAX_PREVIEW_PASSES) {
            val selection = KaspaUtxoSelector.selectUtxosAndCalculateFee(
                utxos = utxos,
                amountSompi = amountSompi,
                feeRateSompiPerGram = feeRateSompiPerGram,
                payloadBytes = null,
                recipientScriptLen = recipientScriptLen,
                changeScriptLen = changeScriptLen,
                extraFeeSompi = extra,
            )
            if (selection.totalSelected < selection.requiredAmount) return null
            val base = selection.estimatedFee - extra
            val settled = Preview(selection.selectedUtxos, base, extra)
            preview = settled
            val next = extraFee(base, feeMultiplier, customExtraFeeSompi)
            if (next == extra) return settled
            extra = next
        }
        return preview
    }
}
