package com.kachat.app.util

import com.kachat.app.services.Outpoint
import com.kachat.app.services.ScriptPublicKey
import com.kachat.app.services.UtxoData
import com.kachat.app.services.UtxoEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit AND-009 / AND-014: the Send screens' fee is a base at the quoted rate on the real inputs
 *  plus a flat extra, so the fee shown is the fee paid whatever the input count. */
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
        // The screen shows the Normal preview, the user types a 0.1 KAS total: the custom extra is
        // that total minus the base shown (commitCustomFee).
        val normal = SendFeeModel.previewAutomaticSelection(twentyCoins, amountNeedingAll, rate)
        assertNotNull(normal)
        assertEquals(20, normal!!.utxos.size)
        val typedTotal = kas / 10
        val customExtra = typedTotal - normal.baseFeeSompi

        val custom = SendFeeModel.previewAutomaticSelection(twentyCoins, amountNeedingAll, rate, customExtraFeeSompi = customExtra)!!
        assertEquals(typedTotal, custom.totalFeeSompi)

        // The send: no rate override (the quote), the preview's extra flat on top.
        val paid = enginePays(twentyCoins, amountNeedingAll, rate, custom.extraFeeSompi)
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
        val preview = SendFeeModel.previewAutomaticSelection(twentyCoins, amountNeedingAll, rate, feeMultiplier = 5)!!
        val twentyInputBase = SendFeeModel.baseFee(20, rate)
        assertEquals(twentyInputBase, preview.baseFeeSompi)
        assertEquals(4 * twentyInputBase, preview.extraFeeSompi)

        val paid = enginePays(twentyCoins, amountNeedingAll, rate, preview.extraFeeSompi)
        assertEquals(preview.utxos.size, paid.selectedUtxos.size)
        assertEquals(preview.totalFeeSompi, paid.estimatedFee)
    }

    @Test
    fun `the preview settles an extra that pulls in another input`() {
        // 2 coins: 1 KAS covers the amount at Normal, but not with Priority's extra on top.
        val coins = listOf(utxo(kas, 0), utxo(kas, 1))
        val amount = kas - SendFeeModel.baseFee(1, rate) - 1_000L
        val preview = SendFeeModel.previewAutomaticSelection(coins, amount, rate, feeMultiplier = 5)!!
        assertEquals(2, preview.utxos.size)
        assertEquals(SendFeeModel.baseFee(2, rate), preview.baseFeeSompi)
        // The send with that extra picks the same coins and pays the total shown.
        val paid = enginePays(coins, amount, rate, preview.extraFeeSompi)
        assertEquals(2, paid.selectedUtxos.size)
        assertEquals(preview.totalFeeSompi, paid.estimatedFee)
    }

    @Test
    fun `Max leaves room for the flat extra, and a send of Max is covered`() {
        for (multiplier in listOf(1L, 2L, 5L)) {
            val max = SendFeeModel.maxAfterFees(twentyCoins.sumOf { it.utxoEntry.amount }, 20, rate, multiplier)
            val base = SendFeeModel.baseFee(20, rate)
            assertEquals(100 * kas - base * multiplier, max)
            val preview = SendFeeModel.previewAutomaticSelection(twentyCoins, max, rate, feeMultiplier = multiplier)
            assertNotNull("multiplier $multiplier", preview)
            val paid = enginePays(twentyCoins, max, rate, preview!!.extraFeeSompi)
            assertTrue(paid.totalSelected >= paid.requiredAmount)
            assertEquals(max, paid.finalAmount)
        }
    }

    @Test
    fun `Max subtracts a custom extra flat`() {
        val base = SendFeeModel.baseFee(3, rate)
        assertEquals(10 * kas - base - 5_000_000L, SendFeeModel.maxAfterFees(10 * kas, 3, rate, feeMultiplier = 5, customExtraFeeSompi = 5_000_000L))
        assertEquals(0L, SendFeeModel.maxAfterFees(base, 3, rate))
    }

    @Test
    fun `only the 80 largest coins count when there are more`() {
        val coins = (0 until 150).map { utxo((it + 1) * 1_000_000L, it) }
        val capped = SendFeeModel.largestSpendable(coins)
        assertEquals(KaspaUtxoSelector.MAX_INPUTS_PER_TRANSACTION, capped.size)
        assertEquals((71..150).sumOf { it * 1_000_000L }, capped.sumOf { it.utxoEntry.amount })
        assertEquals(coins.take(10), SendFeeModel.largestSpendable(coins.take(10)))
    }

    @Test
    fun `extra is the custom extra, else the speed's share of the base`() {
        assertEquals(0L, SendFeeModel.extraFee(1_000L, 1L, null))
        assertEquals(1_000L, SendFeeModel.extraFee(1_000L, 2L, null))
        assertEquals(4_000L, SendFeeModel.extraFee(1_000L, 5L, null))
        assertEquals(7L, SendFeeModel.extraFee(1_000L, 5L, 7L))
    }
}
