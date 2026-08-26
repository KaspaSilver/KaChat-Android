package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom

class MessageProtocolTest {

    private fun randomScalarBytes(): ByteArray {
        val random = SecureRandom()
        while (true) {
            val candidate = ByteArray(32).also { random.nextBytes(it) }
            val d = BigInteger(1, candidate)
            if (d != BigInteger.ZERO && d < Secp256k1.N) return candidate
        }
    }

    @Test
    fun `comm payload builds and parses back to the original alias and message`() {
        val recipientPriv = randomScalarBytes()
        val recipientPub = Schnorr.publicKeyXOnly(recipientPriv)

        val encrypted = MessageProtocol.encrypt("hey there", recipientPub)
        val payloadBytes = MessageProtocol.buildCommPayload("alice", encrypted)

        assertTrue(MessageProtocol.isKaChatPayload(payloadBytes))
        assertTrue(String(payloadBytes, Charsets.UTF_8).startsWith("kchat:1:comm:alice:"))

        val (alias, parsedEncrypted) = MessageProtocol.parseCommPayload(payloadBytes)!!
        assertEquals("alice", alias)
        assertEquals("hey there", MessageProtocol.decrypt(parsedEncrypted, recipientPriv))
    }

    @Test
    fun `a legacy ciph_msg comm payload from an older build is still readable`() {
        // 4.0 writes the kchat prefix but must keep reading ciph_msg, or a message sent by
        // anyone still on 3.0 would be invisible. This is the guarantee that carries live
        // conversations across the rollout, so it is asserted rather than assumed.
        val recipientPriv = randomScalarBytes()
        val recipientPub = Schnorr.publicKeyXOnly(recipientPriv)
        val encrypted = MessageProtocol.encrypt("from an older build", recipientPub)
        val legacyBytes = MessageProtocol.buildCommPayload("alice", encrypted)
            .toString(Charsets.UTF_8)
            .replaceFirst("kchat:", "ciph_msg:")
            .toByteArray(Charsets.UTF_8)

        assertTrue(MessageProtocol.isKaChatPayload(legacyBytes))
        val (alias, parsedEncrypted) = MessageProtocol.parseCommPayload(legacyBytes)!!
        assertEquals("alice", alias)
        assertEquals("from an older build", MessageProtocol.decrypt(parsedEncrypted, recipientPriv))
    }

    @Test
    fun `handshake payload is raw binary after the ascii prefix, not base64`() {
        val recipientPriv = randomScalarBytes()
        val recipientPub = Schnorr.publicKeyXOnly(recipientPriv)

        val json = """{"type":"handshake","alias":"bob"}"""
        val encrypted = MessageProtocol.encrypt(json, recipientPub)
        val payloadBytes = MessageProtocol.buildHandshakePayload(encrypted)

        assertTrue(MessageProtocol.isHandshakePayload(payloadBytes))

        val parsed = MessageProtocol.parseHandshakePayload(payloadBytes)!!
        assertEquals(json, MessageProtocol.decrypt(parsed, recipientPriv))
    }

    @Test
    fun `alias containing a colon is sanitized so parsing stays unambiguous`() {
        val recipientPriv = randomScalarBytes()
        val recipientPub = Schnorr.publicKeyXOnly(recipientPriv)

        val encrypted = MessageProtocol.encrypt("msg", recipientPub)
        val payloadBytes = MessageProtocol.buildCommPayload("bad:alias", encrypted)

        val (alias, _) = MessageProtocol.parseCommPayload(payloadBytes)!!
        assertEquals("bad_alias", alias)
    }

    @Test
    fun `non ciph_msg payload is not recognized`() {
        assertNull(MessageProtocol.parseCommPayload("not a payload".toByteArray()))
        assertTrue(!MessageProtocol.isKaChatPayload("random bytes".toByteArray()))
    }

    @Test
    fun `bcast payload builds and parses back to the original channel and content`() {
        val payloadBytes = MessageProtocol.buildBcastPayload("general", "hey everyone")

        assertTrue(MessageProtocol.isKaChatPayload(payloadBytes))
        assertEquals("kchat:1:bcast:general:hey everyone", String(payloadBytes, Charsets.UTF_8))

        val parsed = MessageProtocol.parseBcastPayload(payloadBytes)!!
        assertEquals("general", parsed.channel)
        assertEquals("hey everyone", parsed.content)
    }

    @Test
    fun `bcast content containing colons round-trips intact`() {
        val payloadBytes = MessageProtocol.buildBcastPayload("general", "time is 10:30:00, right?")
        val parsed = MessageProtocol.parseBcastPayload(payloadBytes)!!
        assertEquals("general", parsed.channel)
        assertEquals("time is 10:30:00, right?", parsed.content)
    }

    @Test
    fun `parseBcastPayload rejects a comm payload and vice versa`() {
        val recipientPriv = randomScalarBytes()
        val recipientPub = Schnorr.publicKeyXOnly(recipientPriv)
        val commPayload = MessageProtocol.buildCommPayload("alice", MessageProtocol.encrypt("hi", recipientPub))
        val bcastPayload = MessageProtocol.buildBcastPayload("general", "hi")

        assertNull(MessageProtocol.parseBcastPayload(commPayload))
        assertNull(MessageProtocol.parseCommPayload(bcastPayload))
    }

    @Test
    fun `normalizeChannelName lowercases and trims`() {
        assertEquals("general", MessageProtocol.normalizeChannelName("  General  "))
    }

    @Test
    fun `isValidChannelName rejects blank, whitespace, colons, and over-length names`() {
        assertTrue(MessageProtocol.isValidChannelName("general"))
        assertTrue(!MessageProtocol.isValidChannelName(""))
        assertTrue(!MessageProtocol.isValidChannelName("has space"))
        assertTrue(!MessageProtocol.isValidChannelName("has:colon"))
        assertTrue(!MessageProtocol.isValidChannelName("a".repeat(37)))
        assertTrue(MessageProtocol.isValidChannelName("a".repeat(36)))
    }
}
