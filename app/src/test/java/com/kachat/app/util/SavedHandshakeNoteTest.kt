package com.kachat.app.util

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom

/**
 * The `saved_handshake` self-stash note matches iOS KasiaTransactionBuilder.buildHandshakeSelfStashTx
 * / buildContactSelfStashTx field for field (audit XP-003).
 */
class SavedHandshakeNoteTest {

    private fun randomScalarBytes(): ByteArray {
        val random = SecureRandom()
        while (true) {
            val candidate = ByteArray(32).also { random.nextBytes(it) }
            val d = BigInteger(1, candidate)
            if (d != BigInteger.ZERO && d < Secp256k1.N) return candidate
        }
    }

    private val partner = "kaspa:qypartner"

    @Test
    fun `handshake note carries exactly the iOS fields`() {
        val json = SavedHandshakeNote.handshakeJson("0123456789ab", "fedcba987654", partner, isResponse = true, timestampMs = 1_790_300_000_000L)
        val obj = JsonParser.parseString(json).asJsonObject
        assertEquals(
            setOf("type", "alias", "timestamp", "version", "theirAlias", "partnerAddress", "recipientAddress", "isResponse"),
            obj.keySet()
        )
        assertEquals("handshake", obj.get("type").asString)
        assertEquals("0123456789ab", obj.get("alias").asString)
        assertEquals(1_790_300_000_000L, obj.get("timestamp").asLong)
        assertEquals(1, obj.get("version").asInt)
        assertEquals("fedcba987654", obj.get("theirAlias").asString)
        assertEquals(partner, obj.get("partnerAddress").asString)
        assertEquals(partner, obj.get("recipientAddress").asString)
        assertTrue(obj.get("isResponse").asBoolean)
        assertEquals(
            """{"type":"handshake","alias":"0123456789ab","timestamp":1790300000000,"version":1,"theirAlias":"fedcba987654","partnerAddress":"kaspa:qypartner","recipientAddress":"kaspa:qypartner","isResponse":true}""",
            json
        )
    }

    @Test
    fun `nil values are left out like iOS compactMapValues`() {
        val obj = JsonParser.parseString(
            SavedHandshakeNote.handshakeJson("0123456789ab", null, partner, isResponse = false, timestampMs = 1L)
        ).asJsonObject
        assertFalse(obj.has("theirAlias"))
        assertFalse(obj.has("isResponse"))
        assertEquals(setOf("type", "alias", "timestamp", "version", "partnerAddress", "recipientAddress"), obj.keySet())
    }

    @Test
    fun `contact note has no alias fields`() {
        val obj = JsonParser.parseString(SavedHandshakeNote.contactJson(partner, 5L)).asJsonObject
        assertEquals(setOf("type", "timestamp", "version", "partnerAddress", "recipientAddress"), obj.keySet())
        assertEquals("contact", obj.get("type").asString)
    }

    @Test
    fun `note is sealed to ourselves under the saved_handshake scope and reads back`() {
        val ownPriv = randomScalarBytes()
        val json = SavedHandshakeNote.handshakeJson("0123456789ab", "fedcba987654", partner, isResponse = false, timestampMs = 7L)
        val payload = SavedHandshakeNote.payload(json, Schnorr.publicKeyXOnly(ownPriv))

        val prefix = "kchat:1:self_stash:saved_handshake:"
        assertEquals(prefix, String(payload.copyOfRange(0, prefix.length), Charsets.US_ASCII))
        val sealed = KasiaCipher.EncryptedMessage.fromBytes(payload.copyOfRange(prefix.length, payload.size))!!
        val content = SavedHandshakeNote.parse(KasiaCipher.decrypt(sealed, ownPriv))!!
        assertEquals("0123456789ab", content.alias)
        assertEquals("fedcba987654", content.theirAlias)
        assertEquals(partner, content.contactAddress)
    }

    @Test
    fun `reader accepts the iOS alternate field names and contact notes`() {
        val alt = SavedHandshakeNote.parse("""{"alias":"a","their_alias":"b","recipient_address":"kaspa:q"}""")!!
        assertEquals("b", alt.theirAlias)
        assertEquals("kaspa:q", alt.contactAddress)
        val contact = SavedHandshakeNote.parse(SavedHandshakeNote.contactJson(partner, 1L))!!
        assertNull(contact.alias)
        assertEquals(partner, contact.contactAddress)
    }
}
