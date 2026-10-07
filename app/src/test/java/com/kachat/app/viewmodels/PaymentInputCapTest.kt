package com.kachat.app.viewmodels

import com.kachat.app.services.Outpoint
import com.kachat.app.services.ScriptPublicKey
import com.kachat.app.services.UtxoData
import com.kachat.app.services.UtxoEntry
import com.kachat.app.util.KaspaUtxoSelector
import com.kachat.app.util.SendFeeModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit AND-012: a chat payment's Max, fee preview and sweep only count what one transaction can
 *  spend - the 80 largest coins - instead of every coin on a busy address. */
class PaymentInputCapTest {

    private val kas = 100_000_000L
    private val rate = 100L
    private val cap = KaspaUtxoSelector.MAX_INPUTS_PER_TRANSACTION

    private fun utxo(amount: Long, index: Int, coinbase: Boolean = false) = UtxoEntry(
        address = "kaspa:test",
        outpoint = Outpoint(transactionId = "tx$index", index = index),
        utxoEntry = UtxoData(amount = amount, scriptPublicKey = ScriptPublicKey("aa"), blockDaaScore = 0, isCoinbase = coinbase)
    )

    /** 150 coins of 0.2 .. 30 KAS, in no particular order. */
    private val busy = (1..150).map { utxo(it * 20_000_000L, it) }.shuffled(java.util.Random(7))
    private val busyTotal = busy.sumOf { it.utxoEntry.amount }
    private val top80Total = (71..150).sumOf { it * 20_000_000L }

    @Test
    fun `Max over a busy source counts only its 80 largest coins`() {
        assertEquals(top80Total to cap, ChatViewModel.paymentMaxCoins(busyTotal, busy, manual = null))
    }

    @Test
    fun `Max over a coin control pick of more than 80 counts its 80 largest`() {
        val picked = busy.take(100)
        val expected = picked.map { it.utxoEntry.amount }.sortedDescending().take(cap).sum()
        assertEquals(expected to cap, ChatViewModel.paymentMaxCoins(busyTotal, busy, manual = picked))
    }

    @Test
    fun `under the cap Max is unchanged`() {
        val few = busy.take(5)
        assertEquals(123L to 5, ChatViewModel.paymentMaxCoins(123L, few, manual = null))
        val picked = few.take(2)
        assertEquals(picked.sumOf { it.utxoEntry.amount } to 2, ChatViewModel.paymentMaxCoins(123L, few, manual = picked))
    }

    @Test
    fun `privacy-on Max is a sweep of the 80 largest that covers itself, Priority included`() {
        val (balance, inputs) = ChatViewModel.paymentMaxCoins(busyTotal, busy, manual = null)
        val max = ChatViewModel.computePaymentMaxSompi(balance, inputs, "", rate.toDouble(), 5, null)
        // KaspaWalletEngine.sendSpendingPayment: sweep the 80 largest (sweepMaxInputs).
        val swept = KaspaUtxoSelector.selectAllUtxosAndCalculateFee(
            utxos = SendFeeModel.largestSpendable(busy, cap),
            amountSompi = max,
            feeRateSompiPerGram = rate,
            payloadBytes = null,
            recipientScriptLen = 34,
            changeScriptLen = 34,
            extraFeeSompi = 4 * SendFeeModel.baseFee(cap, rate),
        )
        assertEquals(cap, swept.selectedUtxos.size)
        assertTrue(swept.totalSelected >= swept.requiredAmount)
        assertEquals(max, swept.finalAmount)
    }

    @Test
    fun `privacy-off Max is covered by at most 80 inputs`() {
        val (balance, inputs) = ChatViewModel.paymentMaxCoins(busyTotal, busy, manual = null)
        val max = ChatViewModel.computePaymentMaxSompi(balance, inputs, "", rate.toDouble(), 1, null)
        val sent = KaspaUtxoSelector.selectUtxosAndCalculateFee(
            utxos = busy, amountSompi = max, feeRateSompiPerGram = rate,
            payloadBytes = null, recipientScriptLen = 34, changeScriptLen = 34,
        )
        assertTrue(sent.selectedUtxos.size <= cap)
        assertTrue(sent.totalSelected >= sent.requiredAmount)
    }

    @Test
    fun `the sweep fee preview prices the 80 largest coins`() {
        assertEquals(top80Total to cap, ChatViewModel.paymentFeeInputs(busy, manual = null, sweeps = true, sompiNeeded = kas))
    }

    @Test
    fun `the payment fee preview takes the largest coins first, as the send does`() {
        val coins = listOf(utxo(1 * kas, 0), utxo(5 * kas, 1), utxo(3 * kas, 2))
        // 4.5 KAS: the 5 KAS coin alone, not 1 + 5 in fetch order.
        assertEquals(5 * kas to 1, ChatViewModel.paymentFeeInputs(coins, manual = null, sweeps = false, sompiNeeded = 45 * kas / 10))
    }

    @Test
    fun `the payment fee preview stops at 80 inputs`() {
        // More than the 80 largest hold: not coverable by one send, so the preview comes up short.
        val (total, count) = ChatViewModel.paymentFeeInputs(busy, manual = null, sweeps = false, sompiNeeded = top80Total + kas)
        assertEquals(cap, count)
        assertEquals(top80Total, total)
    }

    @Test
    fun `coin control's pick is priced exactly`() {
        val picked = busy.take(3)
        assertEquals(picked.sumOf { it.utxoEntry.amount } to 3, ChatViewModel.paymentFeeInputs(busy, picked, sweeps = true, sompiNeeded = kas))
    }

    // iOS estimateMaxPaymentAmount: Max is worked out over the non-coinbase coins only.

    @Test
    fun `Max leaves coinbase coins out of the source's balance and count`() {
        val coins = listOf(utxo(2 * kas, 0), utxo(5 * kas, 1, coinbase = true), utxo(3 * kas, 2))
        val balance = coins.sumOf { it.utxoEntry.amount }
        assertEquals(5 * kas to 2, ChatViewModel.paymentMaxCoins(balance, coins, manual = null))
    }

    @Test
    fun `Max leaves coinbase coins out of a coin control pick`() {
        val coins = listOf(utxo(2 * kas, 0), utxo(5 * kas, 1, coinbase = true), utxo(3 * kas, 2))
        assertEquals(2 * kas to 1, ChatViewModel.paymentMaxCoins(10 * kas, coins, manual = coins.take(2)))
    }

    @Test
    fun `only coinbase coins leave nothing for Max`() {
        val coins = listOf(utxo(5 * kas, 0, coinbase = true))
        assertEquals(0L to 0, ChatViewModel.paymentMaxCoins(5 * kas, coins, manual = null))
        assertEquals(0L, ChatViewModel.computePaymentMaxSompi(0L, 0, "", rate.toDouble(), 1, null))
    }

    @Test
    fun `the 80-coin cap counts only non-coinbase coins`() {
        // 150 ordinary coins plus 10 coinbase coins bigger than any of them.
        val coinbase = (1..10).map { utxo(100 * kas + it, 1000 + it, coinbase = true) }
        val all = busy + coinbase
        val balance = all.sumOf { it.utxoEntry.amount }
        assertEquals(top80Total to cap, ChatViewModel.paymentMaxCoins(balance, all, manual = null))
    }
}
