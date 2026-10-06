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

    private fun send(amount: Long, extraFeeSompi: Long) = KaspaUtxoSelector.selectUtxosAndCalculateFee(
        utxos = coins,
        amountSompi = amount,
        feeRateSompiPerGram = rate,
        payloadBytes = null,
        recipientScriptLen = 34,
        changeScriptLen = 34,
        extraFeeSompi = extraFeeSompi,
    )

    @Test
    fun `Max with Priority and an empty amount leaves room for Priority's extra`() {
        val base = SendFeeModel.baseFee(3, rate)
        val max = ChatViewModel.computePaymentMaxSompi(balance, 3, "", rate.toDouble(), feeMultiplier = 5, customExtraFeeSompi = null)
        assertEquals(balance - 5 * base, max)

        // Once the amount is filled, the sheet's extra is base * 4: the send is covered.
        val sent = send(max, extraFeeSompi = 4 * base)
        assertTrue(sent.totalSelected >= sent.requiredAmount)
        assertEquals(max, sent.finalAmount)

        // What Max used to fill in (the extra read as 0 with the amount empty) is not.
        val old = send(balance - base, extraFeeSompi = 4 * base)
        assertTrue(old.totalSelected < old.requiredAmount)
    }

    @Test
    fun `Max with Fast leaves room for one more base`() {
        val base = SendFeeModel.baseFee(3, rate)
        assertEquals(balance - 2 * base, ChatViewModel.computePaymentMaxSompi(balance, 3, "", rate.toDouble(), 2, null))
    }

    @Test
    fun `Max subtracts a custom extra flat, whatever the speed`() {
        val base = SendFeeModel.baseFee(3, rate)
        assertEquals(
            balance - base - 1_234_567L,
            ChatViewModel.computePaymentMaxSompi(balance, 3, "", rate.toDouble(), 5, customExtraFeeSompi = 1_234_567L)
        )
    }

    @Test
    fun `Normal is unchanged - balance less the base`() {
        val base = SendFeeModel.baseFee(3, rate)
        assertEquals(balance - base, ChatViewModel.computePaymentMaxSompi(balance, 3, "", rate.toDouble(), 1, null))
    }

    @Test
    fun `a memo's payload is priced into the base and the extra`() {
        val note = "for the pizza"
        val payload = MessageProtocol.estimatedPaymentPayloadSize(note, balance)
        val base = SendFeeModel.baseFee(3, rate, payloadSize = payload)
        assertEquals(balance - 5 * base, ChatViewModel.computePaymentMaxSompi(balance, 3, note, rate.toDouble(), 5, null))
    }

    @Test
    fun `the rate is rounded up and never under the network minimum`() {
        assertEquals(
            balance - SendFeeModel.baseFee(3, 101L),
            ChatViewModel.computePaymentMaxSompi(balance, 3, "", 100.2, 1, null)
        )
        assertEquals(
            balance - SendFeeModel.baseFee(3, 100L),
            ChatViewModel.computePaymentMaxSompi(balance, 3, "", 1.0, 1, null)
        )
    }

    @Test
    fun `Max is never negative`() {
        assertEquals(0L, ChatViewModel.computePaymentMaxSompi(1_000L, 3, "", rate.toDouble(), 5, null))
    }
}
