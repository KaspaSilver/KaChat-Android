package com.kachat.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom

/** iOS isCurrentAccountKey (dfaba68): a routing key is used only for the account it belongs to. */
class AccountKeyTest {

    private fun randomScalarBytes(): ByteArray {
        val random = SecureRandom()
        while (true) {
            val candidate = ByteArray(32).also { random.nextBytes(it) }
            val d = BigInteger(1, candidate)
            if (d != BigInteger.ZERO && d < Secp256k1.N) return candidate
        }
    }

    private fun schnorrAddress(priv: ByteArray) = KaspaAddress.encode("kaspa", 0, Schnorr.publicKeyXOnly(priv))

    @Test
    fun `an account's own key belongs to it`() {
        val priv = randomScalarBytes()
        assertTrue(AccountKey.belongsTo(priv, schnorrAddress(priv)))
    }

    @Test
    fun `another account's key is refused, whichever was checked last`() {
        val alice = randomScalarBytes()
        val bob = randomScalarBytes()
        assertFalse(AccountKey.belongsTo(bob, schnorrAddress(alice)))
        assertTrue(AccountKey.belongsTo(alice, schnorrAddress(alice)))
        assertFalse(AccountKey.belongsTo(alice, schnorrAddress(bob)))
        assertTrue(AccountKey.belongsTo(bob, schnorrAddress(bob)))
    }

    @Test
    fun `a non-Schnorr chatting address can't be checked and passes`() {
        val ecdsa = KaspaAddress.encode("kaspa", 1, ByteArray(33) { 2 })
        assertTrue(AccountKey.belongsTo(randomScalarBytes(), ecdsa))
    }

    @Test
    fun `an address that doesn't decode fails`() {
        assertFalse(AccountKey.belongsTo(randomScalarBytes(), "not-an-address"))
        assertFalse(AccountKey.belongsTo(randomScalarBytes(), ""))
    }
}
