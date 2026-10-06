package com.kachat.app.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatRepositoryTest {

    @Test
    fun `fresh incoming handshake from an unknown sender is pending`() {
        assertEquals(
            "pending",
            ChatRepository.deriveIncomingHandshakeStatus(existingStatus = null, existingHandshakeComplete = false)
        )
    }

    @Test
    fun `incoming handshake is a reply if we already sent one to this contact`() {
        assertEquals(
            "active",
            ChatRepository.deriveIncomingHandshakeStatus(existingStatus = "pending", existingHandshakeComplete = true)
        )
    }

    @Test
    fun `already-active conversation stays active on a repeat handshake`() {
        assertEquals(
            "active",
            ChatRepository.deriveIncomingHandshakeStatus(existingStatus = "active", existingHandshakeComplete = false)
        )
    }

    @Test
    fun `a rejected contact re-requesting is treated as a fresh pending request`() {
        assertEquals(
            "pending",
            ChatRepository.deriveIncomingHandshakeStatus(existingStatus = "rejected", existingHandshakeComplete = false)
        )
    }

    @Test
    fun `their handshake marked as a response activates the conversation even if we never sent one`() {
        // e.g. we manually added them (or messaged them without a formal handshake) and they
        // themselves send a handshake back marking it as a reply — that should clear our
        // pending/request-to-connect state even though existingHandshakeComplete is false.
        assertEquals(
            "active",
            ChatRepository.deriveIncomingHandshakeStatus(existingStatus = "pending", existingHandshakeComplete = false, incomingIsResponse = true)
        )
    }

    @Test
    fun `a fresh incoming handshake not marked as a response stays pending`() {
        assertEquals(
            "pending",
            ChatRepository.deriveIncomingHandshakeStatus(existingStatus = null, existingHandshakeComplete = false, incomingIsResponse = false)
        )
    }

    @Test
    fun `contextual message payload decodes through the hex-then-base64 double encoding`() {
        // Real payload captured live from indexer.kasia.fyi's contextual-messages/by-sender —
        // decodes to a 65-byte EncryptedMessage (12-byte nonce + 33-byte ephemeral pubkey +
        // 20-byte ciphertext+tag), confirming comm payloads are hex(base64(bytes)), unlike
        // handshake payloads which are hex(bytes) directly.
        val hexPayload = "557a36566f3558644144555a7948304b41317a556f6d5a6e584976783562746a496d74" +
            "5176686670343938675464565a556e2f4c4b56327a6944716d74384841764472724c634d3934" +
            "4f325164597a624457504e536d383d"

        val decoded = ChatRepository.decodeContextualMessagePayload(hexPayload)

        assertEquals(65, decoded.size)
    }

    @Test
    fun `formats a whole KAS amount without trailing zeros`() {
        assertEquals("1", ChatRepository.formatKas(100_000_000L))
    }

    @Test
    fun `formats a fractional KAS amount trimmed to significant digits`() {
        assertEquals("3.98962", ChatRepository.formatKas(398_962_000L))
    }

    @Test
    fun `formats a small dust amount correctly`() {
        assertEquals("0.00000001", ChatRepository.formatKas(1L))
    }

    @Test
    fun `hex-encodes the ASCII bytes of an alias string, not its raw hex bytes`() {
        // 'a'=0x61, 'b'=0x62, '1'=0x31, '2'=0x32 — the indexer expects the alias's ASCII
        // bytes hex-encoded, not the 6 raw bytes the 12-char hex string itself represents.
        assertEquals("61623132", ChatRepository.hexEncodeAscii("ab12"))
    }

    @Test
    fun `hex-encodes a real 12-char alias`() {
        assertEquals(24, ChatRepository.hexEncodeAscii("f0313b15fd24").length)
    }

    @Test
    fun `script (P2SH) addresses are contracts, key addresses are not`() {
        val payload = ByteArray(32) { it.toByte() }
        val p2sh = com.kachat.app.util.KaspaAddress.encode("kaspatest", 0x08, payload)
        val key = com.kachat.app.util.KaspaAddress.encode("kaspatest", 0x00, payload)
        org.junit.Assert.assertTrue(ChatRepository.isScriptAddress(p2sh))
        org.junit.Assert.assertTrue(ChatRepository.isScriptAddress(com.kachat.app.util.KaspaAddress.encode("kaspa", 0x08, payload)))
        org.junit.Assert.assertFalse(ChatRepository.isScriptAddress(key))
        org.junit.Assert.assertFalse(ChatRepository.isScriptAddress(null))
        org.junit.Assert.assertFalse(ChatRepository.isScriptAddress("not an address"))
    }

    // MARK: first contact, whatever the address case (iOS 1e07e72, audit IOS-008)

    private val me = "kaspa:qrme0000000000000000000000000000000000000000000000000000000"
    private val them = "kaspa:qpthem00000000000000000000000000000000000000000000000000000"

    /** The messages table as SQLite would answer it: stored contactIds lowered in SQL. */
    private fun receivedFrom(vararg storedContactIds: String): suspend (String) -> Boolean = { lowered ->
        storedContactIds.any { it.lowercase() == lowered }
    }

    @Test
    fun `an established contact typed in upper case gets no inbox tag`() = kotlinx.coroutines.runBlocking {
        var askedWith: String? = null
        val tag = ChatRepository.firstContactInboxTagFor(
            address = them.uppercase(),
            me = me,
            state = ChatRequestState(),
            hasReceivedFrom = { askedWith = it; receivedFrom(them)(it) },
            inboxSupported = { true },
        )
        org.junit.Assert.assertNull(tag)
        assertEquals(them, askedWith) // the lookup is handed the lowercased address
    }

    @Test
    fun `a stored mixed-case conversation is found from a lower-case address`() = kotlinx.coroutines.runBlocking {
        val tag = ChatRepository.firstContactInboxTagFor(
            address = them,
            me = me,
            state = ChatRequestState(),
            hasReceivedFrom = receivedFrom("KASPA:QPTHEM00000000000000000000000000000000000000000000000000000"),
            inboxSupported = { true },
        )
        org.junit.Assert.assertNull(tag)
    }

    @Test
    fun `a true first contact still carries the tag, computed on the lowercased address`() = kotlinx.coroutines.runBlocking {
        val tag = ChatRepository.firstContactInboxTagFor(
            address = them.uppercase(),
            me = me,
            state = ChatRequestState(),
            hasReceivedFrom = receivedFrom("kaspa:qpsomeoneelse"),
            inboxSupported = { true },
        )
        assertEquals(com.kachat.app.util.InboxTag.compute(them), tag)
    }

    @Test
    fun `self, private and already-tagged addresses are untagged whatever the case`() = kotlinx.coroutines.runBlocking {
        val none = receivedFrom()
        org.junit.Assert.assertNull(ChatRepository.firstContactInboxTagFor(me.uppercase(), me, ChatRequestState(), none) { true })
        org.junit.Assert.assertNull(
            ChatRepository.firstContactInboxTagFor(them.uppercase(), me, ChatRequestState(privateChats = setOf(them)), none) { true }
        )
        org.junit.Assert.assertNull(
            ChatRepository.firstContactInboxTagFor(them.uppercase(), me, ChatRequestState(inboxTagged = setOf(them)), none) { true }
        )
    }
}
