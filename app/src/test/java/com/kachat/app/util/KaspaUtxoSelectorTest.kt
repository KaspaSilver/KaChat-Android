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
     * not the 203,600 sompi a phantom 2nd output used to charge - plus iOS's 3-sompi buffer.
     */
    @Test
    fun `a zero-amount send prices only the single change output actually built, matching iOS`() {
        val utxos = listOf(utxo(100_000_000L))
        val result = KaspaUtxoSelector.selectUtxosAndCalculateFee(
            utxos, amountSompi = 0L, feeRateSompiPerGram = 100L,
            payloadBytes = null, recipientScriptLen = 34, changeScriptLen = 34
        )
        assertEquals(162_400L + KaspaUtxoSelector.FEE_BUFFER_SOMPI, result.estimatedFee)
    }

    @Test
    fun `a real payment still prices both recipient and change outputs`() {
        val utxos = listOf(utxo(100_000_000L))
        val result = KaspaUtxoSelector.selectUtxosAndCalculateFee(
            utxos, amountSompi = 10_000_000L, feeRateSompiPerGram = 100L,
            payloadBytes = null, recipientScriptLen = 34, changeScriptLen = 34
        )
        assertEquals(203_600L + KaspaUtxoSelector.FEE_BUFFER_SOMPI, result.estimatedFee)
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

    // --- refusals (iOS selectUtxosForPayment / selectManualUtxosForPayment) ----------------

    private fun payment(utxos: List<com.kachat.app.services.UtxoEntry>, amount: Long) = KaspaUtxoSelector.selectUtxosAndCalculateFee(
        utxos, amountSompi = amount, feeRateSompiPerGram = 100L,
        payloadBytes = null, recipientScriptLen = 34, changeScriptLen = 34, allowNoChangeOutput = true
    )

    // iOS 4f8e14bb: change is kept before it is folded into the fee.

    @Test
    fun `another coin keeps change the first coin would have folded into the fee`() {
        // 10.05 and 5 KAS sending 10: the first coin alone would give the 0.05 KAS to the miners.
        val result = payment(listOf(utxo(1_005_000_000L, "a"), utxo(500_000_000L, "b")), 1_000_000_000L)
        assertEquals(null, result.refusal)
        assertEquals(2, result.selectedUtxos.size)
        assertTrue(!result.noChangeOutput)
        assertEquals(0L, result.foldedChangeSompi)
    }

    @Test
    fun `a coin that would cost more than the change it keeps is not added`() {
        // 0.0025 KAS left over is less than a second input and a change output cost at this rate.
        val result = payment(listOf(utxo(100_000_000L, "a"), utxo(50_000_000L, "b")), 100_000_000L - 250_000L)
        assertEquals(null, result.refusal)
        assertEquals(1, result.selectedUtxos.size)
        assertTrue(result.noChangeOutput)
    }

    @Test
    fun `a payment just short of the fee is refused, never trimmed to fit`() {
        val amount = 100_000_000L - (162_403L - 1_000L)
        val result = payment(listOf(utxo(100_000_000L)), amount)
        assertEquals(KaspaUtxoSelector.Refusal.InsufficientFunds, result.refusal)
        assertEquals(amount, result.finalAmount)
    }

    @Test
    fun `leftover too small to keep goes without a change output, its fee priced for one output`() {
        val result = payment(listOf(utxo(100_000_000L)), 100_000_000L - 250_000L)
        assertEquals(null, result.refusal)
        assertTrue(result.noChangeOutput)
        assertEquals(162_403L, result.estimatedFee)
        assertEquals(87_597L, result.foldedChangeSompi)
    }

    @Test
    fun `real change that won't fit storage mass is refused in iOS's words`() {
        val result = payment(listOf(utxo(1_010_000_000L, "big")), 9_990_000L)
        assertEquals(KaspaUtxoSelector.Refusal.StorageMass, result.refusal)
        assertEquals(KaspaUtxoSelector.SMALL_SEND_MASS_MESSAGE, result.refusal?.message)
    }

    // iOS fitsStorageMass on a no-change send: the recipient's output alone must fit too.

    @Test
    fun `a small send carved from one bigger coin is refused even with no change`() {
        // 0.03 KAS from one 0.1 KAS coin: the leftover is small enough to fold, but the recipient's
        // output alone costs about 233,000 grams of a 100,000 budget, and the node refuses it.
        assertTrue(!KaspaMass.fitsStorageMass(listOf(10_000_000L), listOf(3_000_000L)))
        val result = payment(listOf(utxo(10_000_000L)), 3_000_000L)
        assertEquals(KaspaUtxoSelector.Refusal.StorageMass, result.refusal)
        assertTrue(!result.noChangeOutput)
    }

    @Test
    fun `another coin brings the recipient's output under the budget`() {
        val result = payment(listOf(utxo(10_000_000L, "a"), utxo(3_000_000L, "b")), 3_000_000L)
        assertEquals(null, result.refusal)
        assertEquals(2, result.selectedUtxos.size)
        assertTrue(!result.noChangeOutput)
        assertEquals(0L, result.foldedChangeSompi)
        assertTrue(
            KaspaMass.fitsStorageMass(result.selectedUtxos.map { it.utxoEntry.amount }, listOf(3_000_000L, result.changeAmount))
        )
    }

    @Test
    fun `coin control refuses a no-change send whose recipient output breaks the budget`() {
        val result = KaspaUtxoSelector.selectManualUtxosAndCalculateFee(
            listOf(utxo(10_000_000L)), amountSompi = 3_000_000L, feeRateSompiPerGram = 100L,
            recipientScriptLen = 34, changeScriptLen = 34, allowNoChangeOutput = true
        )
        assertEquals(KaspaUtxoSelector.Refusal.StorageMass, result.refusal)
        assertTrue(result.storageMassBlocked)
    }

    @Test
    fun `automatic selection stops at the input cap with iOS's words`() {
        val coins = (0 until 100).map { utxo(100_000_000L, "c", it) }
        val result = payment(coins, 90 * 100_000_000L)
        assertEquals(KaspaUtxoSelector.MAX_INPUTS_PER_TRANSACTION, result.selectedUtxos.size)
        assertEquals(KaspaUtxoSelector.Refusal.TooManyInputs, result.refusal)
        assertEquals(
            "This send needs more than 80 inputs. Compound (consolidate) this address's UTXOs first, then try again.",
            result.refusal?.message
        )
    }

    @Test
    fun `coin control refuses a pick over the input cap`() {
        val coins = (0 until 81).map { utxo(100_000_000L, "c", it) }
        val result = KaspaUtxoSelector.selectManualUtxosAndCalculateFee(
            coins, amountSompi = 100_000_000L, feeRateSompiPerGram = 100L,
            recipientScriptLen = 34, changeScriptLen = 34, allowNoChangeOutput = true
        )
        assertEquals(KaspaUtxoSelector.Refusal.TooManyInputsSelected(81), result.refusal)
    }

    // --- extraFeeSompi and coin control (the Send KAS sheet's fee card, iOS 62c2773) -------

    @Test
    fun `an extra fee is paid exactly on top of the mass-priced fee, by every selector`() {
        val utxos = listOf(utxo(100_000_000L, "a"), utxo(50_000_000L, "b"))
        val extra = 12_345L
        fun greedy(x: Long) = KaspaUtxoSelector.selectUtxosAndCalculateFee(
            utxos, amountSompi = 30_000_000L, feeRateSompiPerGram = 100L,
            payloadBytes = null, recipientScriptLen = 34, changeScriptLen = 34, extraFeeSompi = x
        )
        fun manual(x: Long) = KaspaUtxoSelector.selectManualUtxosAndCalculateFee(
            utxos, amountSompi = 30_000_000L, feeRateSompiPerGram = 100L,
            recipientScriptLen = 34, changeScriptLen = 34, extraFeeSompi = x
        )
        for ((base, withExtra) in listOf(greedy(0) to greedy(extra), manual(0) to manual(extra))) {
            assertEquals(base.selectedUtxos, withExtra.selectedUtxos)
            assertEquals(base.estimatedFee + extra, withExtra.estimatedFee)
            assertEquals(base.finalAmount, withExtra.finalAmount)
            assertEquals(base.changeAmount - extra, withExtra.changeAmount)
        }
    }

    @Test
    fun `coin control spends exactly the picked coins and prices a memo payload`() {
        val picked = listOf(utxo(50_000_000L, "b"), utxo(10_000_000L, "c"))
        val plain = KaspaUtxoSelector.selectManualUtxosAndCalculateFee(
            picked, amountSompi = 30_000_000L, feeRateSompiPerGram = 100L,
            recipientScriptLen = 34, changeScriptLen = 34
        )
        val withMemo = KaspaUtxoSelector.selectManualUtxosAndCalculateFee(
            picked, amountSompi = 30_000_000L, feeRateSompiPerGram = 100L,
            recipientScriptLen = 34, changeScriptLen = 34, payloadBytes = ByteArray(200)
        )
        assertEquals(picked, withMemo.selectedUtxos)
        assertEquals(60_000_000L, withMemo.totalSelected)
        assertTrue(withMemo.estimatedFee > plain.estimatedFee)
        assertEquals(withMemo.totalSelected - withMemo.finalAmount - withMemo.estimatedFee, withMemo.changeAmount)
    }
}
