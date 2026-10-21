package com.kachat.app.services

import com.google.gson.Gson
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS d194a38: an address's history lists a block reward only once the chain accepted it. */
class TransactionHistoryFilterTest {

    private val coinbase = TransactionResponse.COINBASE_SUBNETWORK_ID
    private val native = "0000000000000000000000000000000000000000"

    private val input = TransactionInput(
        previousOutpointHash = "aa".repeat(32),
        previousOutpointIndex = 0,
        previousOutpointAddress = "kaspa:sender",
        previousOutpointAmount = 1_000L,
        signatureScript = "41" + "cd".repeat(64) + "01",
    )

    private fun tx(subnetworkId: String?, isAccepted: Boolean?, inputs: List<TransactionInput> = emptyList()) =
        TransactionResponse(
            transactionId = "ff".repeat(32),
            inputsOrNull = inputs,
            outputs = listOf(TransactionOutput(500L, "20" + "ab".repeat(32) + "ac", "kaspa:miner")),
            blockTime = 0L,
            payload = null,
            subnetworkId = subnetworkId,
            isAccepted = isAccepted,
        )

    @Test
    fun `an accepted coinbase is kept`() {
        val t = tx(coinbase, isAccepted = true)
        assertTrue(t.isCoinbase)
        assertTrue(t.belongsInHistory)
    }

    @Test
    fun `an unaccepted coinbase is dropped`() {
        val t = tx(coinbase, isAccepted = false)
        assertTrue(t.isCoinbase)
        assertFalse(t.belongsInHistory)
    }

    @Test
    fun `a coinbase without an acceptance answer is dropped`() {
        val t = tx(coinbase, isAccepted = null)
        assertTrue(t.isCoinbase)
        assertFalse(t.belongsInHistory)
    }

    @Test
    fun `the coinbase subnetwork matches in upper case too`() {
        assertTrue(tx(coinbase.uppercase(), isAccepted = false).isCoinbase)
    }

    @Test
    fun `an ordinary unaccepted transaction is kept`() {
        val t = tx(native, isAccepted = false, inputs = listOf(input))
        assertFalse(t.isCoinbase)
        assertTrue(t.belongsInHistory)
        // the subnetwork decides, not the inputs
        assertFalse(tx(native, isAccepted = null).isCoinbase)
    }

    @Test
    fun `no subnetwork field and no inputs is a coinbase`() {
        assertTrue(tx(null, isAccepted = false).isCoinbase)
        assertTrue(tx("", isAccepted = false).isCoinbase)
        assertFalse(tx(null, isAccepted = false, inputs = listOf(input)).isCoinbase)
    }

    @Test
    fun `decoded from the REST shape`() {
        val gson = Gson()
        // no subnetwork_id and no inputs key at all: Gson leaves inputs null
        val bare = gson.fromJson(
            """{"transaction_id":"${"ff".repeat(32)}","outputs":[],"is_accepted":false}""",
            TransactionResponse::class.java,
        )
        assertTrue(bare.isCoinbase)
        assertFalse(bare.belongsInHistory)

        val accepted = gson.fromJson(
            """{"transaction_id":"${"ff".repeat(32)}","inputs":[],"outputs":[],"subnetwork_id":"$coinbase","is_accepted":true}""",
            TransactionResponse::class.java,
        )
        assertTrue(accepted.belongsInHistory)
    }

    @Test
    fun `an accepted reward decoded with null inputs reads as no inputs, never a crash`() {
        // what api.kaspa.org returns for a block reward: the history lists keep it now (d194a38),
        // and every reader calls inputs.any{} / inputs.isEmpty() on it
        val reward = Gson().fromJson(
            """{"transaction_id":"${"ff".repeat(32)}","inputs":null,"outputs":[{"amount":500,"script_public_key":"20${"ab".repeat(32)}ac","script_public_key_address":"kaspa:miner"}],"subnetwork_id":"$coinbase","is_accepted":true}""",
            TransactionResponse::class.java,
        )
        assertTrue(reward.inputs.isEmpty())
        assertFalse(reward.inputs.any { it.previousOutpointAddress == "kaspa:miner" })
        assertNull(reward.feeSompi)
        assertTrue(reward.isCoinbase)
        assertTrue(reward.belongsInHistory)
    }
}
