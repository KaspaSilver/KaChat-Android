package com.kachat.app.util

import com.kachat.app.services.Outpoint
import com.kachat.app.services.ScriptPublicKey
import com.kachat.app.services.UtxoData
import com.kachat.app.services.UtxoEntry
import com.kachat.app.util.UtxoMath.checkedTotalAmount
import com.kachat.app.util.UtxoMath.totalAmount
import com.kachat.app.viewmodels.ChatViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Node-supplied UTXO values can't wrap into plausible totals (iOS 5040628, audit IOS-020). */
class UtxoMathTest {

    private fun utxo(amount: Long, index: Int = 0, daa: Long = 1, coinbase: Boolean = false) = UtxoEntry(
        address = "kaspa:test",
        outpoint = Outpoint(transactionId = "tx$index", index = index),
        utxoEntry = UtxoData(amount = amount, scriptPublicKey = ScriptPublicKey("aa"), blockDaaScore = daa, isCoinbase = coinbase)
    )

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("expected invalid UTXO data to be refused")
        } catch (e: IllegalStateException) {
            assertEquals(UtxoMath.INVALID_AMOUNT_MESSAGE, e.message)
        }
    }

    @Test
    fun `real amounts sum exactly`() {
        assertEquals(350_000_000L, listOf(utxo(100_000_000L, 0), utxo(250_000_000L, 1)).totalAmount())
        assertEquals(0L, emptyList<UtxoEntry>().totalAmount())
        assertEquals(UtxoMath.MAX_SOMPI, UtxoMath.checkedTotal(listOf(UtxoMath.MAX_SOMPI)))
    }

    @Test
    fun `negative amounts - a uint64 past Long_MAX - are invalid`() {
        assertNull(listOf(utxo(-1L)).checkedTotalAmount())
        assertNull(listOf(utxo(100L, 0), utxo(Long.MIN_VALUE, 1)).checkedTotalAmount())
        assertInvalid { listOf(utxo(-5L)).totalAmount() }
    }

    @Test
    fun `amounts above the supply, alone or summed, are invalid - never a wrapped total`() {
        assertNull(listOf(utxo(Long.MAX_VALUE)).checkedTotalAmount())
        assertNull(listOf(utxo(UtxoMath.MAX_SOMPI + 1)).checkedTotalAmount())
        // Each under the supply, together past it: plain Long math would wrap to a negative or a
        // small positive total.
        val big = UtxoMath.MAX_SOMPI - 1
        assertNull(listOf(utxo(big, 0), utxo(big, 1), utxo(big, 2), utxo(big, 3)).checkedTotalAmount())
        assertInvalid { UtxoMath.add(UtxoMath.MAX_SOMPI, 1L) }
        assertInvalid { UtxoMath.add(-1L, 1L) }
    }

    @Test
    fun `coinbase maturity treats overflow and negative scores as not yet mature`() {
        val m = 1000L
        assertTrue(UtxoMath.isMatureCoinbase(blockDaaScore = 5_000, maturity = m, virtualDaaScore = 6_001))
        assertFalse(UtxoMath.isMatureCoinbase(blockDaaScore = 5_000, maturity = m, virtualDaaScore = 6_000))
        // Long.MAX - 10 + 1000 wraps negative in plain math and read as mature.
        assertFalse(UtxoMath.isMatureCoinbase(blockDaaScore = Long.MAX_VALUE - 10, maturity = m, virtualDaaScore = 6_000))
        assertFalse(UtxoMath.isMatureCoinbase(blockDaaScore = -1, maturity = m, virtualDaaScore = 6_000))
    }

    @Test
    fun `the greedy selector refuses a nonsense coin instead of wrapping`() {
        val utxos = listOf(utxo(Long.MAX_VALUE - 5, 0), utxo(100_000_000L, 1))
        assertInvalid {
            KaspaUtxoSelector.selectUtxosAndCalculateFee(
                utxos, amountSompi = 10_000_000L, feeRateSompiPerGram = 1L,
                payloadBytes = null, recipientScriptLen = 34, changeScriptLen = 34
            )
        }
    }

    @Test
    fun `the manual and sweep selectors refuse a total past the supply`() {
        val big = UtxoMath.MAX_SOMPI - 1
        val utxos = (0 until 4).map { utxo(big, it) }
        assertInvalid {
            KaspaUtxoSelector.selectManualUtxosAndCalculateFee(utxos, 10_000_000L, 1L, 34, 34)
        }
        assertInvalid {
            KaspaUtxoSelector.selectAllUtxosAndCalculateFee(utxos, 10_000_000L, 1L, null, 34, 34)
        }
    }

    @Test
    fun `the fee preview gives no answer for nonsense coins, rather than throwing`() {
        val utxos = listOf(utxo(-100L, 0), utxo(100_000_000L, 1))
        assertNull(SendFeeModel.previewAutomaticSelection(utxos, amountSompi = 10_000_000L, feeRateSompiPerGram = 1L))
    }

    @Test
    fun `the chat sheet's Max and fee inputs never sum nonsense coins`() {
        val bad = listOf(utxo(Long.MAX_VALUE, 0), utxo(Long.MAX_VALUE, 1))
        assertEquals(0L, ChatViewModel.paymentMaxCoins(0L, bad, bad).first)
        assertEquals(0L, ChatViewModel.paymentFeeInputs(bad, bad, sweeps = false, sompiNeeded = 1L).first)
        assertEquals(0L, ChatViewModel.paymentFeeInputs(bad, null, sweeps = true, sompiNeeded = 1L).first)
        assertEquals(0L, ChatViewModel.paymentFeeInputs(bad, null, sweeps = false, sompiNeeded = 1L).first)
        // Real coins are untouched.
        val good = listOf(utxo(100_000_000L, 0), utxo(50_000_000L, 1))
        assertEquals(150_000_000L to 2, ChatViewModel.paymentMaxCoins(0L, good, good))
    }
}
