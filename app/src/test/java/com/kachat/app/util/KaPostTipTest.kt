package com.kachat.app.util

import com.kachat.app.services.Outpoint
import com.kachat.app.services.ScriptPublicKey
import com.kachat.app.services.UtxoData
import com.kachat.app.services.UtxoEntry
import com.kachat.app.ui.screens.MAX_PAYMENT_MEMO_LENGTH
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom

/** The KaPosts tip (iOS 0e08006): a chat payment's memo payload. */
class KaPostTipTest {

    private fun randomScalarBytes(): ByteArray {
        val random = SecureRandom()
        while (true) {
            val candidate = ByteArray(32).also { random.nextBytes(it) }
            val d = BigInteger(1, candidate)
            if (d != BigInteger.ZERO && d < Secp256k1.N) return candidate
        }
    }

    @Test
    fun `the memo limit is a chat payment's 140 characters`() {
        assertEquals(140, MAX_PAYMENT_MEMO_LENGTH)
    }

    /** Since AND-035 every chat payment carries its payload, memo or not (iOS buildPaymentPayload). */
    @Test
    fun `a payment without a memo still carries its kchat 1 pay payload`() {
        val priv = randomScalarBytes()
        val pub = Schnorr.publicKeyXOnly(priv)
        val prefix = "kchat:1:pay:".toByteArray(Charsets.US_ASCII)
        for (memo in listOf("", "  \n\t ")) {
            val payload = MessageProtocol.paymentMemoPayload(memo, 100_000_000L, pub)
            assertNotNull(payload)
            assertArrayEquals(prefix, payload.copyOfRange(0, prefix.size))
            assertEquals(MessageProtocol.estimatedPaymentPayloadSize("", 100_000_000L), payload.size)
            val opened = MessageProtocol.decryptPaymentPayload(payload, priv)!!
            assertEquals("", opened.message)
            assertEquals(100_000_000L, opened.amount)
        }
    }

    @Test
    fun `a tip memo is the kchat 1 pay sealed payload of the trimmed memo`() {
        val priv = randomScalarBytes()
        val pub = Schnorr.publicKeyXOnly(priv)
        val payload = MessageProtocol.paymentMemoPayload("  great post!  ", 123_456_789L, pub)
        assertNotNull(payload)
        val prefix = "kchat:1:pay:".toByteArray(Charsets.US_ASCII)
        assertArrayEquals(prefix, payload!!.copyOfRange(0, prefix.size))

        val opened = MessageProtocol.decryptPaymentPayload(payload, priv)!!
        assertEquals("payment", opened.type)
        assertEquals("great post!", opened.message)
        assertEquals(123_456_789L, opened.amount)
        assertEquals(1, opened.version)
    }

    @Test
    fun `the payload JSON keeps iOS's field order`() {
        val priv = randomScalarBytes()
        val pub = Schnorr.publicKeyXOnly(priv)
        val payload = MessageProtocol.paymentMemoPayload("hi", 5L, pub)!!
        val prefixLen = "kchat:1:pay:".length
        val sealed = KasiaCipher.EncryptedMessage.fromBytes(payload.copyOfRange(prefixLen, payload.size))!!
        val json = MessageProtocol.decrypt(sealed, priv)
        val regex = Regex("""^\{"type":"payment","message":"hi","amount":5,"timestamp":\d+,"version":1}$""")
        assert(regex.matches(json)) { json }
    }

    @Test
    fun `the tip payload is the same size the fee preview prices`() {
        val pub = Schnorr.publicKeyXOnly(randomScalarBytes())
        val payload = MessageProtocol.paymentMemoPayload("thanks", 1_000_000_000L, pub)!!
        assertEquals(payload.size, MessageProtocol.estimatedPaymentPayloadSize("thanks", 1_000_000_000L))
    }
}
