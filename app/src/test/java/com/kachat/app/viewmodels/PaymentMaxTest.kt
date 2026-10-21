package com.kachat.app.viewmodels

import com.kachat.app.services.Outpoint
import com.kachat.app.services.ScriptPublicKey
import com.kachat.app.services.UtxoData
import com.kachat.app.services.UtxoEntry
import com.kachat.app.util.KaspaUtxoSelector
import com.kachat.app.util.MessageProtocol
import com.kachat.app.util.SendFeeModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit AND-010: the chat Send KAS sheet's Max leaves room for the Fast / Priority / custom
 *  extra, worked out from Max's own base - not from the fee estimate, which is null (so the
 *  extra 0) while the amount is empty. */
class PaymentMaxTest {

    private val kas = 100_000_000L
    private val rate = 100L

    private fun utxo(amount: Long, index: Int) = UtxoEntry(
        address = "kaspa:test",
        outpoint = Outpoint(transactionId = "tx$index", index = index),
        utxoEntry = UtxoData(amount = amount, scriptPublicKey = ScriptPublicKey("aa"), blockDaaScore = 0, isCoinbase = false)
    )

    private val coins = listOf(utxo(4 * kas, 0), utxo(3 * kas, 1), utxo(3 * kas, 2))
    private val balance = coins.sumOf { it.utxoEntry.amount }

    /** A chat payment always carries its kchat:1:pay: payload, an empty memo too (AND-035, iOS
     *  estimateMaxPaymentAmount prices buildPaymentPayload whatever the memo). */
    private val emptyMemoPayload = MessageProtocol.estimatedPaymentPayloadSize("", balance)

    private fun send(amount: Long, extraFeeSompi: Long) = KaspaUtxoSelector.selectUtxosAndCalculateFee(
        utxos = coins,
        amountSompi = amount,
        feeRateSompiPerGram = rate,
        payloadBytes = null,
        recipientScriptLen = 34,
        changeScriptLen = 34,
        extraFeeSompi = extraFeeSompi,
    )

    /** iOS df4aba6's rule for a speed above Normal with no amount yet: the Normal max first
     *  (provisional), the fee preview's base for sending that much, extra = base x (m - 1), then
     *  the max with that extra. The provisional amount has a digit fewer than the balance here,
     *  so its payload - and base - is a byte smaller than the max's own. */
    private fun iosRuleMax(note: String, multiplier: Long): Long {
        val base = SendFeeModel.baseFee(3, rate, payloadSize = MessageProtocol.estimatedPaymentPayloadSize(note, balance))
        val provisional = balance - base
        val provisionalBase = SendFeeModel.baseFee(3, rate, payloadSize = MessageProtocol.estimatedPaymentPayloadSize(note, provisional))
        return balance - base - provisionalBase * (multiplier - 1)
    }

    @Test
    fun `Max with Priority and an empty amount leaves room for Priority's extra`() {
        val base = SendFeeModel.baseFee(3, rate, payloadSize = emptyMemoPayload)
        val max = ChatViewModel.computePaymentMaxSompi(balance, 3, "", rate.toDouble(), feeMultiplier = 5, customExtraFeeSompi = null)
        assertEquals(iosRuleMax("", 5), max)

        // Once the amount is filled, the sheet's extra is 4x the preview's base for it: covered.
        val filledBase = SendFeeModel.baseFee(3, rate, payloadSize = MessageProtocol.estimatedPaymentPayloadSize("", max))
        val sent = send(max, extraFeeSompi = 4 * filledBase)
        assertTrue(sent.totalSelected >= sent.requiredAmount)
        assertEquals(max, sent.finalAmount)

        // What Max used to fill in (the extra read as 0 with the amount empty) is not.
        val old = send(balance - base, extraFeeSompi = 4 * base)
        assertTrue(old.totalSelected < old.requiredAmount)
    }

    @Test
    fun `Max with Fast leaves room for one more base`() {
        assertEquals(iosRuleMax("", 2), ChatViewModel.computePaymentMaxSompi(balance, 3, "", rate.toDouble(), 2, null))
    }

    @Test
    fun `the speed's base is the provisional max send's, from its own coins`() {
        // iOS df4aba6: estimatePaymentFee(provisional) - the preview's selection for that amount,
        // here two of the coins, prices the base the speed multiplies.
        val base = SendFeeModel.baseFee(3, rate, payloadSize = emptyMemoPayload)
        var asked: Long? = null
        val max = ChatViewModel.computePaymentMaxSompi(
            balance, 3, "", rate.toDouble(), 5, null,
            provisionalShape = { provisional -> asked = provisional; 2 to emptyMemoPayload },
        )
        assertEquals(balance - base, asked)
        assertEquals(balance - base - 4 * SendFeeModel.baseFee(2, rate, payloadSize = emptyMemoPayload), max)
    }

    @Test
    fun `a custom extra counts before any amount, and Normal asks for no provisional send`() {
        var asked = false
        val base = SendFeeModel.baseFee(3, rate, payloadSize = emptyMemoPayload)
        assertEquals(
            balance - base - 777L,
            ChatViewModel.computePaymentMaxSompi(balance, 3, "", rate.toDouble(), 5, 777L, provisionalShape = { asked = true; 1 to 0 })
        )
        assertEquals(
            balance - base,
            ChatViewModel.computePaymentMaxSompi(balance, 3, "", rate.toDouble(), 1, null, provisionalShape = { asked = true; 1 to 0 })
        )
        assertTrue(!asked)
    }

    @Test
    fun `Max subtracts a custom extra flat, whatever the speed`() {
        val base = SendFeeModel.baseFee(3, rate, payloadSize = emptyMemoPayload)
        assertEquals(
            balance - base - 1_234_567L,
            ChatViewModel.computePaymentMaxSompi(balance, 3, "", rate.toDouble(), 5, customExtraFeeSompi = 1_234_567L)
        )
    }

    @Test
    fun `Normal is unchanged - balance less the base`() {
        val base = SendFeeModel.baseFee(3, rate, payloadSize = emptyMemoPayload)
        assertEquals(balance - base, ChatViewModel.computePaymentMaxSompi(balance, 3, "", rate.toDouble(), 1, null))
    }

    @Test
    fun `a memo's payload is priced into the base and the extra`() {
        val note = "for the pizza"
        assertEquals(iosRuleMax(note, 5), ChatViewModel.computePaymentMaxSompi(balance, 3, note, rate.toDouble(), 5, null))
        // and a longer memo costs more on both
        assertTrue(iosRuleMax(note, 5) < iosRuleMax("", 5))
    }

    @Test
    fun `the rate is rounded up and never under the network minimum`() {
        assertEquals(
            balance - SendFeeModel.baseFee(3, 101L, payloadSize = emptyMemoPayload),
            ChatViewModel.computePaymentMaxSompi(balance, 3, "", 100.2, 1, null)
        )
        assertEquals(
            balance - SendFeeModel.baseFee(3, 100L, payloadSize = emptyMemoPayload),
            ChatViewModel.computePaymentMaxSompi(balance, 3, "", 1.0, 1, null)
        )
    }

    @Test
    fun `Max is never negative`() {
        assertEquals(0L, ChatViewModel.computePaymentMaxSompi(1_000L, 3, "", rate.toDouble(), 5, null))
    }
}
