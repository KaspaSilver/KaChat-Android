package com.kachat.app.viewmodels

import com.kachat.app.services.Outpoint
import com.kachat.app.services.ScriptPublicKey
import com.kachat.app.services.UtxoData
import com.kachat.app.services.UtxoEntry
import com.kachat.app.util.KaspaUtxoSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A chat payment's Max, as iOS's estimateMaxPaymentAmount (d23e6f7a): with more coins than one
 *  transaction can spend, it is what the 80 largest carry, and says it is capped; a coin control
 *  pick is never cut. The fee preview's inputs stop at the 80 largest coins. */
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
    private val top80Total = (71..150).sumOf { it * 20_000_000L }

    @Test
    fun `Max over a busy source is the 80 largest coins, capped`() {
        assertEquals(
            ChatViewModel.PaymentMaxCoins(top80Total, cap, cappedAtInputLimit = true),
            ChatViewModel.paymentMaxCoins(busy, manual = null),
        )
    }

    @Test
    fun `Max over a coin control pick of more than 80 counts all of it`() {
        val picked = busy.take(100)
        assertEquals(
            ChatViewModel.PaymentMaxCoins(picked.sumOf { it.utxoEntry.amount }, 100, cappedAtInputLimit = false),
            ChatViewModel.paymentMaxCoins(busy, manual = picked),
        )
    }

    @Test
    fun `under the cap Max is every coin, not capped`() {
        val few = busy.take(5)
        assertEquals(
            ChatViewModel.PaymentMaxCoins(few.sumOf { it.utxoEntry.amount }, 5, cappedAtInputLimit = false),
            ChatViewModel.paymentMaxCoins(few, manual = null),
        )
        val picked = few.take(2)
        assertEquals(
            ChatViewModel.PaymentMaxCoins(picked.sumOf { it.utxoEntry.amount }, 2, cappedAtInputLimit = false),
            ChatViewModel.paymentMaxCoins(few, manual = picked),
        )
    }

    @Test
    fun `a capped Max is under what the 80 largest coins hold`() {
        val (balance, inputs, capped) = ChatViewModel.paymentMaxCoins(busy, manual = null)
        val max = ChatViewModel.computePaymentMaxSompi(balance, inputs, "", rate.toDouble(), 1, null)
        assertTrue(capped)
        assertTrue(max > 0L && max < top80Total)
    }

    @Test
    fun `the payment fee preview takes the largest coins first, as the send does`() {
        val coins = listOf(utxo(1 * kas, 0), utxo(5 * kas, 1), utxo(3 * kas, 2))
        // 4.5 KAS: the 5 KAS coin alone, not 1 + 5 in fetch order.
        assertEquals(5 * kas to 1, ChatViewModel.paymentFeeInputs(coins, manual = null, sompiNeeded = 45 * kas / 10))
    }

    @Test
    fun `the payment fee preview stops at 80 inputs`() {
        // More than the 80 largest hold: not coverable by one send, so the preview comes up short.
        val (total, count) = ChatViewModel.paymentFeeInputs(busy, manual = null, sompiNeeded = top80Total + kas)
        assertEquals(cap, count)
        assertEquals(top80Total, total)
    }

    @Test
    fun `the fee preview picks greedily among coin control's pick`() {
        val picked = listOf(utxo(1 * kas, 0), utxo(5 * kas, 1), utxo(3 * kas, 2))
        assertEquals(5 * kas to 1, ChatViewModel.paymentFeeInputs(busy, picked, sompiNeeded = 4 * kas))
    }

    // iOS estimateMaxPaymentAmount: Max is worked out over the non-coinbase coins only.

    @Test
    fun `Max leaves coinbase coins out of the source's balance and count`() {
        val coins = listOf(utxo(2 * kas, 0), utxo(5 * kas, 1, coinbase = true), utxo(3 * kas, 2))
        assertEquals(ChatViewModel.PaymentMaxCoins(5 * kas, 2, cappedAtInputLimit = false), ChatViewModel.paymentMaxCoins(coins, manual = null))
    }

    @Test
    fun `Max leaves coinbase coins out of a coin control pick`() {
        val coins = listOf(utxo(2 * kas, 0), utxo(5 * kas, 1, coinbase = true), utxo(3 * kas, 2))
        assertEquals(ChatViewModel.PaymentMaxCoins(2 * kas, 1, cappedAtInputLimit = false), ChatViewModel.paymentMaxCoins(coins, manual = coins.take(2)))
    }

    @Test
    fun `only coinbase coins leave nothing for Max`() {
        val coins = listOf(utxo(5 * kas, 0, coinbase = true))
        assertEquals(ChatViewModel.PaymentMaxCoins(0L, 0, cappedAtInputLimit = false), ChatViewModel.paymentMaxCoins(coins, manual = null))
        assertEquals(0L, ChatViewModel.computePaymentMaxSompi(0L, 0, "", rate.toDouble(), 1, null))
    }

    @Test
    fun `Max over a busy source leaves its coinbase coins out`() {
        // 150 ordinary coins plus 10 coinbase coins bigger than any of them: the cap takes the 80
        // largest ordinary ones, never a coinbase coin.
        val coinbase = (1..10).map { utxo(100 * kas + it, 1000 + it, coinbase = true) }
        val all = busy + coinbase
        assertEquals(ChatViewModel.PaymentMaxCoins(top80Total, cap, cappedAtInputLimit = true), ChatViewModel.paymentMaxCoins(all, manual = null))
    }
}
