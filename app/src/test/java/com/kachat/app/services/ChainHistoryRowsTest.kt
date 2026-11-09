package com.kachat.app.services

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Kaspa REST API's rows in the indexer's own shapes (iOS 4f8e14bb `chainHandshakes` and co.). */
class ChainHistoryRowsTest {
    private val me = "kaspa:me"
    private val them = "kaspa:them"

    private fun hex(text: String) = ChainHistoryRows.hex(text)

    private fun tx(payload: String, senders: Set<String>, outputs: List<String> = emptyList()) = ChainHistoryTx(
        txId = "tx1",
        blockTime = 100L,
        payloadHex = payload,
        senders = senders,
        outputs = outputs.map { ChainHistoryTx.Output(it, 1L) },
    )

    private fun row(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    @Test
    fun `a row parses with numbers as text and lowercased addresses`() {
        val parsed = ChainHistoryRows.parse(
            listOf(
                row(
                    """{"transaction_id":"abc","is_accepted":true,"block_time":"123","payload":"AB",
                        "inputs":[{"previous_outpoint_address":"KASPA:X"}],
                        "outputs":[{"script_public_key_address":"KASPA:Y","amount":"7"}]}"""
                )
            )
        )
        assertEquals(1, parsed.size)
        assertEquals(123L, parsed[0].blockTime)
        assertEquals("ab", parsed[0].payloadHex)
        assertEquals(setOf("kaspa:x"), parsed[0].senders)
        assertEquals(ChainHistoryTx.Output("kaspa:y", 7L), parsed[0].outputs.single())
    }

    @Test
    fun `an unaccepted row and a row with null inputs are handled`() {
        val parsed = ChainHistoryRows.parse(
            listOf(
                row("""{"transaction_id":"a","is_accepted":false,"block_time":1}"""),
                row("""{"transaction_id":"b","block_time":1,"inputs":null,"outputs":null}"""),
                row("""{"transaction_id":"c"}"""),
            )
        )
        assertEquals(listOf("b"), parsed.map { it.txId })
        assertTrue(parsed[0].senders.isEmpty())
    }

    @Test
    fun `a handshake comes back without its prefix, by direction`() {
        val payload = hex("kchat:1:handshake:") + "abcd"
        val sent = ChainHistoryRows.handshakes(listOf(tx(payload, setOf(me), listOf(me, them))), me, sent = true)
        assertEquals("abcd", sent.single().messagePayload)
        assertEquals(them, sent.single().receiver)
        val received = ChainHistoryRows.handshakes(listOf(tx(payload, setOf(them), listOf(me))), me, sent = false)
        assertEquals(them, received.single().sender)
        assertEquals(me, received.single().receiver)
        assertTrue(ChainHistoryRows.handshakes(listOf(tx(payload, setOf(them))), me, sent = true).isEmpty())
    }

    @Test
    fun `a message is matched by alias and given as hex of its base64`() {
        val payload = hex("kchat:1:comm:alias1:QUJD")
        val found = ChainHistoryRows.contextualMessages(listOf(tx(payload, setOf(them))), them, "alias1")
        assertEquals(hex("QUJD"), found.single().messagePayload)
        assertEquals(hex("alias1"), found.single().alias)
        assertTrue(ChainHistoryRows.contextualMessages(listOf(tx(payload, setOf(them))), them, "other").isEmpty())
        assertTrue(ChainHistoryRows.contextualMessages(listOf(tx(payload, setOf(me))), them, "alias1").isEmpty())
    }

    @Test
    fun `a first-contact message reads as an ordinary one`() {
        val payload = hex("kchat:1:dm:0011:alias1:QUJD")
        assertEquals(1, ChainHistoryRows.contextualMessages(listOf(tx(payload, setOf(them))), them, "alias1").size)
    }

    @Test
    fun `a self stash keeps only its own scope`() {
        val payload = hex("kchat:1:self_stash:saved_handshake:") + "beef"
        val found = ChainHistoryRows.selfStash(listOf(tx(payload, setOf(me))), me, "saved_handshake")
        assertEquals("beef", found.single().stashedData)
        assertTrue(ChainHistoryRows.selfStash(listOf(tx(payload, setOf(me))), me, "other").isEmpty())
    }

    @Test
    fun `text round-trips through hex`() {
        assertEquals("saved_handshake", ChainHistoryRows.textFromHex(hex("saved_handshake")))
    }
}
