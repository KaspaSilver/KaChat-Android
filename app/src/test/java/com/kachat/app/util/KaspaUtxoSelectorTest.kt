package com.kachat.app.util

import com.kachat.app.services.Outpoint
import com.kachat.app.services.ScriptPublicKey
import com.kachat.app.services.UtxoData
import com.kachat.app.services.UtxoEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KaspaUtxoSelectorTest {

    private fun utxo(amount: Long, txId: String = "a", index: Int = 0) = UtxoEntry(
        address = "kaspa:test",
        outpoint = Outpoint(transactionId = txId + index, index = index),
        utxoEntry = UtxoData(amount = amount, scriptPublicKey = ScriptPublicKey("aa"), blockDaaScore = 0, isCoinbase = false)
    )

    @Test
    fun `selects the fewest large UTXOs needed to cover amount plus fee`() {
        val utxos = listOf(utxo(100_000_000L, "a"), utxo(50_000_000L, "b"), utxo(10_000_000L, "c"))
        // 0.3 KAS: its change fits storage mass beside the 1 KAS coin alone. (0.1 KAS from that
        // coin does not, and now correctly pulls in a second coin rather than folding 0.9 KAS.)
        val result = KaspaUtxoSelector.selectUtxosAndCalculateFee(
            utxos, amountSompi = 30_000_000L, feeRateSompiPerGram = 100L,
            payloadBytes = null, recipientScriptLen = 34, changeScriptLen = 34
        )
        assertEquals(1, result.selectedUtxos.size) // the 100M one alone covers 30M + fee
        assertEquals(100_000_000L, result.totalSelected)
    }

    @Test
    fun `change equals total minus final amount minus fee`() {
        val utxos = listOf(utxo(100_000_000L))
        val result = KaspaUtxoSelector.selectUtxosAndCalculateFee(
            utxos, amountSompi = 10_000_000L, feeRateSompiPerGram = 100L,
            payloadBytes = null, recipientScriptLen = 34, changeScriptLen = 34
        )
        assertEquals(result.totalSelected - result.finalAmount - result.estimatedFee, result.changeAmount)
    }

    @Test
    fun `insufficient funds reports a required amount greater than what's available`() {
        val utxos = listOf(utxo(1_000L))
        val result = KaspaUtxoSelector.selectUtxosAndCalculateFee(
            utxos, amountSompi = 10_000_000L, feeRateSompiPerGram = 100L,
            payloadBytes = null, recipientScriptLen = 34, changeScriptLen = 34
        )
        assertTrue(result.totalSelected < result.requiredAmount)
    }

    @Test
    fun `a payload increases the estimated fee`() {
        val utxos = listOf(utxo(100_000_000L))
        val withoutPayload = KaspaUtxoSelector.selectUtxosAndCalculateFee(
            utxos, amountSompi = 0L, feeRateSompiPerGram = 100L,
            payloadBytes = null, recipientScriptLen = 34, changeScriptLen = 34
        )
        val withPayload = KaspaUtxoSelector.selectUtxosAndCalculateFee(
            utxos, amountSompi = 0L, feeRateSompiPerGram = 100L,
            payloadBytes = ByteArray(500), recipientScriptLen = 34, changeScriptLen = 34
        )
        assertTrue(withPayload.estimatedFee > withoutPayload.estimatedFee)
    }

    /**
     * A zero-amount send is always a self-stash message (1:1 chat, broadcast, voice, photo) —
     * KaspaWalletEngine.sendKaspa always drops the zero-value recipient output for these (a
     * 0-value output is non-standard and gets rejected), leaving only the change output. Pricing
     * against 2 outputs here used to silently overpay every message's real network fee. 162,400
     * sompi matches iOS's own verified baseline for the identical 1-input/1-output/0-payload
     * shape (KaspaFeePolicy's `standardComputeMass = 1_624`, `test_toccata_fee_policy.swift`) —
     * not the 203,600 sompi a phantom 2nd output used to charge.
     */
    @Test
    fun `a zero-amount send prices only the single change output actually built, matching iOS`() {
        val utxos = listOf(utxo(100_000_000L))
        val result = KaspaUtxoSelector.selectUtxosAndCalculateFee(
            utxos, amountSompi = 0L, feeRateSompiPerGram = 100L,
            payloadBytes = null, recipientScriptLen = 34, changeScriptLen = 34
        )
        assertEquals(162_400L, result.estimatedFee)
    }

    @Test
    fun `a real payment still prices both recipient and change outputs`() {
        val utxos = listOf(utxo(100_000_000L))
        val result = KaspaUtxoSelector.selectUtxosAndCalculateFee(
            utxos, amountSompi = 10_000_000L, feeRateSompiPerGram = 100L,
            payloadBytes = null, recipientScriptLen = 34, changeScriptLen = 34
        )
        assertEquals(203_600L, result.estimatedFee)
    }

    // --- selectAllUtxosAndCalculateFee (spending-address full sweep) --------------------

    @Test
    fun `sweep selects every given UTXO regardless of whether fewer would cover the amount`() {
        val utxos = listOf(utxo(100_000_000L, "a"), utxo(50_000_000L, "b"), utxo(10_000_000L, "c"))
        val result = KaspaUtxoSelector.selectAllUtxosAndCalculateFee(
            utxos, amountSompi = 10_000_000L, feeRateSompiPerGram = 100L,
            payloadBytes = null, recipientScriptLen = 34, changeScriptLen = 34
        )
        // The greedy selector would pick just the 100M UTXO; a sweep must take all three.
        assertEquals(3, result.selectedUtxos.size)
        assertEquals(160_000_000L, result.totalSelected)
    }

    @Test
    fun `sweep of a single UTXO matches the greedy selector exactly`() {
        // The steady-state case: after any spend, the spending address holds exactly one
        // UTXO (its own change), so sweeping "all" and "enough" should agree completely.
        val utxos = listOf(utxo(100_000_000L))
        val swept = KaspaUtxoSelector.selectAllUtxosAndCalculateFee(
            utxos, amountSompi = 10_000_000L, feeRateSompiPerGram = 100L,
            payloadBytes = null, recipientScriptLen = 34, changeScriptLen = 34
        )
        val greedy = KaspaUtxoSelector.selectUtxosAndCalculateFee(
            utxos, amountSompi = 10_000_000L, feeRateSompiPerGram = 100L,
            payloadBytes = null, recipientScriptLen = 34, changeScriptLen = 34
        )
        assertEquals(greedy.totalSelected, swept.totalSelected)
        assertEquals(greedy.estimatedFee, swept.estimatedFee)
        assertEquals(greedy.changeAmount, swept.changeAmount)
    }

    @Test
    fun `sweep change equals total minus final amount minus fee`() {
        val utxos = listOf(utxo(100_000_000L, "a"), utxo(50_000_000L, "b"))
        val result = KaspaUtxoSelector.selectAllUtxosAndCalculateFee(
            utxos, amountSompi = 10_000_000L, feeRateSompiPerGram = 100L,
            payloadBytes = null, recipientScriptLen = 34, changeScriptLen = 34
        )
        assertEquals(result.totalSelected - result.finalAmount - result.estimatedFee, result.changeAmount)
    }

    @Test
    fun `sweep with insufficient total reports a required amount greater than what's available`() {
        val utxos = listOf(utxo(1_000L), utxo(500L))
        val result = KaspaUtxoSelector.selectAllUtxosAndCalculateFee(
            utxos, amountSompi = 10_000_000L, feeRateSompiPerGram = 100L,
            payloadBytes = null, recipientScriptLen = 34, changeScriptLen = 34
        )
        assertTrue(result.totalSelected < result.requiredAmount)
    }

    // iOS dd4d977: real change is never folded into the fee to make a shape fit.

    @Test
    fun `a small send from one big coin is refused rather than paying the change as fee`() {
        val result = KaspaUtxoSelector.selectUtxosAndCalculateFee(
            listOf(utxo(1_010_000_000L, "big")), amountSompi = 9_990_000L, feeRateSompiPerGram = 1L,
            payloadBytes = null, recipientScriptLen = 34, changeScriptLen = 34
        )
        assertTrue(result.storageMassBlocked)
        assertTrue(result.changeAmount > KaspaUtxoSelector.MAX_FOLDED_CHANGE_SOMPI)
    }

    @Test
    fun `a smaller second coin brings the mass under and the change is kept`() {
        val result = KaspaUtxoSelector.selectUtxosAndCalculateFee(
            listOf(utxo(1_010_000_000L, "big"), utxo(50_000_000L, "small")), amountSompi = 9_990_000L, feeRateSompiPerGram = 1L,
            payloadBytes = null, recipientScriptLen = 34, changeScriptLen = 34
        )
        assertTrue(!result.storageMassBlocked)
        assertEquals(2, result.selectedUtxos.size)
        assertTrue(
            KaspaMass.fitsStorageMass(result.selectedUtxos.map { it.utxoEntry.amount }, listOf(9_990_000L, result.changeAmount))
        )
    }

    @Test
    fun `an ordinary send is not blocked`() {
        val result = KaspaUtxoSelector.selectUtxosAndCalculateFee(
            listOf(utxo(1_010_000_000L, "big")), amountSompi = 100_000_000L, feeRateSompiPerGram = 1L,
            payloadBytes = null, recipientScriptLen = 34, changeScriptLen = 34
        )
        assertTrue(!result.storageMassBlocked)
        assertEquals(1, result.selectedUtxos.size)
    }
}
