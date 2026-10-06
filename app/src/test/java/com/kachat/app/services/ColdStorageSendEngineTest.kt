package com.kachat.app.services

import com.kachat.app.util.KaspaUtxoSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ColdStorageSendEngineTest {

    private val recipientScript = "20" + "11".repeat(32) + "ac"
    private val changeScript = "20" + "22".repeat(32) + "ac"

    private fun utxo(amount: Long, index: Int = 0) = UtxoEntry(
        address = "kaspa:cold",
        outpoint = Outpoint(transactionId = "tx$index", index = index),
        utxoEntry = UtxoData(amount = amount, scriptPublicKey = ScriptPublicKey(changeScript), blockDaaScore = 1, isCoinbase = false)
    )

    private fun selection(inputs: List<Long>, amount: Long, fee: Long) = KaspaUtxoSelector.SelectionResult(
        selectedUtxos = inputs.mapIndexed { i, a -> utxo(a, i) },
        totalSelected = inputs.sum(),
        estimatedFee = fee,
        finalAmount = amount,
        changeAmount = inputs.sum() - amount - fee,
        requiredAmount = amount + fee
    )

    // MARK: change / dust (iOS e6f0dfe, audit IOS-013)

    @Test
    fun `change the storage mass allows is kept, and the fee shown is the network fee`() {
        val sel = selection(listOf(100_000_000L), amount = 30_000_000L, fee = 3_000L)
        val built = ColdStorageSendEngine.coldOutputs(sel, recipientScript, changeScript)
        assertEquals(2, built.outputs.size)
        assertEquals(sel.changeAmount, built.changeSompi)
        assertEquals(3_000L, built.paidFeeSompi)
    }

    @Test
    fun `change between 0_1 and 0_2 KAS stands as an output - no flat 0_2 KAS floor`() {
        // 0.15 KAS of change beside a 1 KAS payment from a 1.15 KAS coin.
        val sel = selection(listOf(115_003_000L), amount = 100_000_000L, fee = 3_000L)
        val built = ColdStorageSendEngine.coldOutputs(sel, recipientScript, changeScript)
        assertEquals(2, built.outputs.size)
        assertEquals(15_000_000L, built.changeSompi)
        assertEquals(3_000L, built.paidFeeSompi)
    }

    @Test
    fun `dust change is folded, and the fee shown includes it`() {
        // 5,000 sompi of change cannot stand (its own storage mass is C / 5000), so it is folded.
        val sel = selection(listOf(100_000_000L), amount = 99_992_000L, fee = 3_000L)
        assertEquals(5_000L, sel.changeAmount)
        val built = ColdStorageSendEngine.coldOutputs(sel, recipientScript, changeScript)
        assertEquals(1, built.outputs.size)
        assertEquals(0L, built.changeSompi)
        // inputs - outputs: the network fee AND the folded dust, which is what is really paid.
        assertEquals(8_000L, built.paidFeeSompi)
        assertEquals(sel.totalSelected - built.outputs.sumOf { it.amount }, built.paidFeeSompi)
    }

    @Test
    fun `real change that cannot stand is refused, never paid to the miners`() {
        // 0.0999 KAS from a 10.1 KAS coin: the recipient output breaks the budget, and ~10 KAS of
        // change would have been folded into the fee.
        val sel = selection(listOf(1_010_000_000L), amount = 9_990_000L, fee = 3_000L)
        assertTrue(sel.changeAmount > KaspaUtxoSelector.MAX_FOLDED_CHANGE_SOMPI)
        try {
            ColdStorageSendEngine.coldOutputs(sel, recipientScript, changeScript)
            fail("expected the small-send refusal")
        } catch (e: IllegalStateException) {
            assertEquals(KaspaUtxoSelector.SMALL_SEND_MASS_MESSAGE, e.message)
        }
    }

    @Test
    fun `a selection the selector already marked blocked is refused`() {
        val sel = selection(listOf(100_000_000L), amount = 30_000_000L, fee = 3_000L).copy(storageMassBlocked = true)
        try {
            ColdStorageSendEngine.coldOutputs(sel, recipientScript, changeScript)
            fail("expected the small-send refusal")
        } catch (e: IllegalStateException) {
            assertEquals(KaspaUtxoSelector.SMALL_SEND_MASS_MESSAGE, e.message)
        }
    }
}
