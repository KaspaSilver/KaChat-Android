package com.kachat.app.util

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom

/**
 * Pool payments carry the payer's note (iOS 6b20d77, audit XP-004): the `kchat:1:pay:` payload is
 * sealed to the contact's CHAT key whichever address is paid, and `payment_notice` carries an
 * optional `memo` (<= 500 chars, omitted when empty) that the recipient shows as
 * "Received X KAS — memo".
 */
class PaymentPoolNoticeMemoTest {

    private fun randomScalarBytes(): ByteArray {
        val random = SecureRandom()
        while (true) {
            val candidate = ByteArray(32).also { random.nextBytes(it) }
            val d = BigInteger(1, candidate)
            if (d != BigInteger.ZERO && d < Secp256k1.N) return candidate
        }
    }

    private fun addressOf(priv: ByteArray): String =
        KaspaAddress.encode("kaspa", 0, Schnorr.publicKeyXOnly(priv))

    @Test
    fun `pool payment memo is sealed to the chat key and opens with it, never the pool key`() {
        val chatPriv = randomScalarBytes()
        val poolPriv = randomScalarBytes()
        val chatAddress = addressOf(chatPriv)
        val poolAddress = addressOf(poolPriv)
        assertTrue(chatAddress != poolAddress)

        val sealKey = PaymentPoolProtocol.paymentPayloadSealKey(chatAddress)
        assertTrue(sealKey.contentEquals(Schnorr.publicKeyXOnly(chatPriv)))

        val payload = MessageProtocol.buildPaymentPayload("rent", 500_000_000L, sealKey)
        val opened = MessageProtocol.decryptPaymentPayload(payload, chatPriv)
        assertNotNull(opened)
        assertEquals("rent", opened!!.message)
        assertEquals(500_000_000L, opened.amount)
        // The pool address's key (the recipient's spending chain) is never tried by a reader.
        assertNull(MessageProtocol.decryptPaymentPayload(payload, poolPriv))
    }

    @Test
    fun `notice with a memo encodes the MESSAGING md shape and parses back`() {
        val json = PaymentPoolProtocol.encode(
            PaymentPoolProtocol.PaymentNoticeContent(
                txId = "a1b2", amountSompi = 123_450_000L, address = "kaspa:qq", memo = "rent"
            )
        )
        val obj = JsonParser.parseString(json).asJsonObject
        assertEquals("payment_notice", obj.get("type").asString)
        assertEquals("a1b2", obj.get("txId").asString)
        assertEquals(123_450_000L, obj.get("amountSompi").asLong)
        assertEquals("kaspa:qq", obj.get("address").asString)
        assertEquals("rent", obj.get("memo").asString)

        val parsed = PaymentPoolProtocol.parse(json) as PaymentPoolProtocol.Envelope.Notice
        assertEquals("rent", parsed.content.memo)
    }

    @Test
    fun `notice without a memo omits the field and older notices still parse`() {
        val json = PaymentPoolProtocol.encode(
            PaymentPoolProtocol.PaymentNoticeContent(
                txId = "a1b2", amountSompi = 1L, address = "kaspa:qq", memo = PaymentPoolProtocol.noticeMemo("   ")
            )
        )
        assertFalse(JsonParser.parseString(json).asJsonObject.has("memo"))

        val old = """{"type":"payment_notice","txId":"ff","amountSompi":5,"address":"kaspa:qq"}"""
        val parsed = PaymentPoolProtocol.parse(old) as PaymentPoolProtocol.Envelope.Notice
        assertNull(parsed.content.memo)
    }

    @Test
    fun `memo is trimmed and capped at 500 characters`() {
        assertNull(PaymentPoolProtocol.noticeMemo(null))
        assertNull(PaymentPoolProtocol.noticeMemo(" \n "))
        assertEquals("rent", PaymentPoolProtocol.noticeMemo("  rent\n"))
        assertEquals(500, PaymentPoolProtocol.noticeMemo("x".repeat(600))!!.length)
        // Counted in user-perceived characters, like Swift's prefix: an emoji is one.
        val emoji = "😀"
        assertEquals(emoji.repeat(500), PaymentPoolProtocol.noticeMemo(emoji.repeat(501)))
    }

    @Test
    fun `recipient bubble reads Received X KAS with the memo after an em dash`() {
        assertEquals("Received 5 KAS", PaymentPoolProtocol.receivedNoticeText("5", "KAS", null))
        assertEquals("Received 5 KAS", PaymentPoolProtocol.receivedNoticeText("5", "KAS", "  "))
        assertEquals("Received 5 KAS — rent", PaymentPoolProtocol.receivedNoticeText("5", "KAS", " rent "))
    }
}
