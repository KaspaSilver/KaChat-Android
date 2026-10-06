package com.kachat.app.util

import com.kachat.app.ui.screens.MAX_PAYMENT_MEMO_LENGTH
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom

/** The KaPosts tip (iOS 0e08006): a chat payment's memo payload and destination. */
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
    fun `the tip goes to a fresh private address only when one is waiting`() {
        assertEquals(KaPostTip.Destination.FRESH_PRIVATE_ADDRESS, KaPostTip.destination(paysViaFreshPoolAddress = true))
        assertEquals(KaPostTip.Destination.PUBLIC_CHATTING_ADDRESS, KaPostTip.destination(paysViaFreshPoolAddress = false))
    }

    @Test
    fun `the memo limit is a chat payment's 140 characters`() {
        assertEquals(140, MAX_PAYMENT_MEMO_LENGTH)
    }

    @Test
    fun `a tip without a memo carries no payload`() {
        val pub = Schnorr.publicKeyXOnly(randomScalarBytes())
        assertNull(MessageProtocol.paymentMemoPayload("", 100_000_000L, pub))
        assertNull(MessageProtocol.paymentMemoPayload("  \n\t ", 100_000_000L, pub))
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
