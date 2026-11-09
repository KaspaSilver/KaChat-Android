package com.kachat.app.util

import com.kachat.app.services.Outpoint
import com.kachat.app.services.ScriptPublicKey
import com.kachat.app.services.UtxoData
import com.kachat.app.services.UtxoEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit AND-009 / AND-014: the Send screens' fee is a base at the network minimum on the real
 *  inputs plus a flat extra, so the fee shown is the fee paid whatever the input count. */
class SendFeeModelTest {

    private fun utxo(amount: Long, index: Int) = UtxoEntry(
        address = "kaspa:test",
        outpoint = Outpoint(transactionId = "tx$index", index = index),
        utxoEntry = UtxoData(amount = amount, scriptPublicKey = ScriptPublicKey("aa"), blockDaaScore = 0, isCoinbase = false)
    )

    private val rate = KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM
    private val kas = 100_000_000L

    /** Twenty 5 KAS coins; 96 KAS needs all twenty. */
    private val twentyCoins = (0 until 20).map { utxo(5 * kas, it) }
    private val amountNeedingAll = 96 * kas

    /** What the engine charges for a send: the same selector KaspaWalletEngine.sendKaspa runs. */
    private fun enginePays(utxos: List<UtxoEntry>, amount: Long, rateSompiPerGram: Long, extraFeeSompi: Long) =
        KaspaUtxoSelector.selectUtxosAndCalculateFee(
            utxos = utxos,
            amountSompi = amount,
            feeRateSompiPerGram = rateSompiPerGram,
            payloadBytes = null,
            recipientScriptLen = 34,
            changeScriptLen = 34,
            extraFeeSompi = extraFeeSompi,
        )

    @Test
    fun `20 inputs with a custom 0_1 KAS total fee pays 0_1 KAS, not 1_14`() {
        // The screen shows the Normal estimate, the user types a 0.1 KAS total: the custom extra is
        // that total minus the base shown (commitCustomFee).
        val normal = SendFeeModel.estimate(twentyCoins, null, amountNeedingAll, rate)
        // the selector's buffer is part of what the send pays, so of the fee shown (iOS d23e6f7a)
        assertEquals(SendFeeModel.baseFee(20, rate) + KaspaUtxoSelector.FEE_BUFFER_SOMPI, normal.networkFeeSompi)
        val typedTotal = kas / 10
        val customExtra = typedTotal - normal.networkFeeSompi
        assertEquals(typedTotal, normal.total(customExtra))

        // The send: no rate override, the extra flat on top - exactly the total typed.
        val paid = enginePays(twentyCoins, amountNeedingAll, rate, customExtra)
        assertEquals(20, paid.selectedUtxos.size)
        assertEquals(typedTotal, paid.estimatedFee)

        // The old model: the total folded into a rate sized on one input, charged on all twenty.
        val oneInputMass = KaspaMass.calculateMass(numInputs = 1, outputScriptLens = listOf(34, 34), payloadSize = 0)
        val oldRate = kotlin.math.ceil(typedTotal.toDouble() / oneInputMass).toLong()
        val oldPaid = enginePays(twentyCoins, amountNeedingAll, oldRate, 0L)
        assertTrue("old model paid ${oldPaid.estimatedFee}", oldPaid.estimatedFee > 110_000_000L)
    }

    @Test
    fun `Priority is five times the base priced on the real inputs, and the send pays exactly that`() {
        val estimate = SendFeeModel.estimate(twentyCoins, null, amountNeedingAll, rate)
        val twentyInputFee = SendFeeModel.baseFee(20, rate) + KaspaUtxoSelector.FEE_BUFFER_SOMPI
        assertEquals(twentyInputFee, estimate.networkFeeSompi)
        val extra = SendFeeModel.extraFee(estimate.networkFeeSompi, 5, null)
        assertEquals(4 * twentyInputFee, extra)

        val paid = enginePays(twentyCoins, amountNeedingAll, rate, extra)
        assertEquals(20, paid.selectedUtxos.size)
        assertEquals(estimate.total(extra), paid.estimatedFee)
    }

    @Test
    fun `a send that can't go throws a typed refusal`() {
        fun refusal(block: () -> Unit) = try {
            block()
            null
        } catch (e: KaspaUtxoSelector.SendRefusedException) {
            e.refusal
        }
        assertEquals(
            KaspaUtxoSelector.Refusal.InsufficientFunds,
            refusal { SendFeeModel.estimate(listOf(utxo(kas, 0)), null, 2 * kas, rate) }
        )
        // coin control's exact coins too
        assertEquals(
            KaspaUtxoSelector.Refusal.InsufficientFunds,
            refusal { SendFeeModel.estimate(emptyList(), listOf(utxo(kas, 0)), kas, rate) }
        )
        assertEquals(
            KaspaUtxoSelector.Refusal.StorageMass,
            refusal { SendFeeModel.estimate(listOf(utxo(1_010_000_000L, 0)), null, 9_990_000L, rate) }
        )
    }

    @Test
    fun `the estimate prices the outputs the transaction really has, the folded change apart`() {
        val estimate = SendFeeModel.estimate(listOf(utxo(kas, 0)), null, kas - 250_000L, rate)
        assertEquals(162_403L, estimate.networkFeeSompi)
        assertEquals(87_597L, estimate.foldedChangeSompi)
        // a tier's extra comes out of the folded change before anything more is spent
        assertEquals(250_000L, estimate.total(1_000L))
    }

    @Test
    fun `Max leaves room for the flat extra, and a send of Max goes without change`() {
        for (multiplier in listOf(1L, 2L, 5L)) {
            val max = SendFeeModel.maxAfterFees(twentyCoins.sumOf { it.utxoEntry.amount }, 20, rate, multiplier)
            // the extra is the shown fee's - the base and the buffer - times the speed's share
            val shown = SendFeeModel.baseFee(20, rate) + KaspaUtxoSelector.FEE_BUFFER_SOMPI
            assertEquals(100 * kas - shown * multiplier, max)
            val paid = KaspaUtxoSelector.selectUtxosAndCalculateFee(
                twentyCoins, max, rate, null, 34, 34,
                extraFeeSompi = shown * (multiplier - 1), allowNoChangeOutput = true,
            )
            assertEquals(null, paid.refusal)
            assertTrue(paid.noChangeOutput)
            assertEquals(max, paid.finalAmount)
        }
    }

    @Test
    fun `Max subtracts a custom extra flat`() {
        val base = SendFeeModel.baseFee(3, rate)
        assertEquals(
            10 * kas - base - KaspaUtxoSelector.FEE_BUFFER_SOMPI - 5_000_000L,
            SendFeeModel.maxAfterFees(10 * kas, 3, rate, feeMultiplier = 5, customExtraFeeSompi = 5_000_000L)
        )
        assertEquals(0L, SendFeeModel.maxAfterFees(base, 3, rate))
    }

    @Test
    fun `an estimate with the extra in prices the shape the send builds at that speed`() {
        // 0.85 KAS from one 1 KAS coin: at Normal the 0.15 KAS change stands as its own output.
        val coin = listOf(utxo(kas, 0))
        val amount = 85_000_000L
        val base = SendFeeModel.estimate(coin, null, amount, rate)
        assertEquals(203_603L, base.networkFeeSompi)
        assertEquals(0L, base.foldedChangeSompi)

        // A 0.06 KAS custom extra leaves change too small to keep, so it goes to the fee as well
        // (iOS estimatePaymentFee(extraFeeSompi:)): the base plus the extra said 0.062 KAS, and the
        // send paid 0.15.
        val extra = 6_000_000L
        val paid = SendFeeModel.estimate(coin, null, amount, rate, extraFeeSompi = extra)
        assertEquals(162_403L + extra, paid.networkFeeSompi)
        assertEquals(8_837_597L, paid.foldedChangeSompi)
        assertEquals(kas - amount, paid.networkFeeSompi + paid.foldedChangeSompi)
        assertEquals(203_603L + extra, base.total(extra))

        // what the send itself pays: every sompi of that, the buffer included
        val sent = KaspaUtxoSelector.selectUtxosAndCalculateFee(
            coin, amount, rate, null, 34, 34, extraFeeSompi = extra, allowNoChangeOutput = true,
        )
        assertTrue(sent.noChangeOutput)
        assertEquals(paid.networkFeeSompi + paid.foldedChangeSompi, sent.estimatedFee + sent.changeAmount)
    }

    @Test
    fun `an extra that leaves the shape alone is the base plus the extra, by coin control too`() {
        val coin = listOf(utxo(kas, 0))
        val extra = 4 * 203_603L
        for (manual in listOf(null, coin)) {
            val paid = SendFeeModel.estimate(coin, manual, 85_000_000L, rate, extraFeeSompi = extra)
            assertEquals(203_603L + extra, paid.networkFeeSompi)
            assertEquals(0L, paid.foldedChangeSompi)
        }
    }

    @Test
    fun `a Review's picks are resolved fresh, and picks that are all gone refuse rather than fall back`() {
        val coins = listOf(utxo(kas, 0), utxo(kas, 1))
        assertEquals(null, SendFeeModel.resolveReviewSelection(null, coins, "gone"))
        assertEquals(null, SendFeeModel.resolveReviewSelection(emptyList(), coins, "gone"))
        assertEquals(listOf(coins[1]), SendFeeModel.resolveReviewSelection(listOf(coins[1], utxo(kas, 7)), coins, "gone"))
        try {
            SendFeeModel.resolveReviewSelection(listOf(utxo(kas, 7)), coins, "gone")
            org.junit.Assert.fail("expected the send's refusal")
        } catch (e: IllegalStateException) {
            assertEquals("gone", e.message)
        }
    }

    @Test
    fun `extra is the custom extra, else the speed's share of the base`() {
        assertEquals(0L, SendFeeModel.extraFee(1_000L, 1L, null))
        assertEquals(1_000L, SendFeeModel.extraFee(1_000L, 2L, null))
        assertEquals(4_000L, SendFeeModel.extraFee(1_000L, 5L, null))
        assertEquals(7L, SendFeeModel.extraFee(1_000L, 5L, 7L))
    }
}
