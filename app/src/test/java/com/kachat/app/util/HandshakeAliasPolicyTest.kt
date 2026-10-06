package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom

/** Audit XP-003: a handshake carries the deterministic alias; only legacy chats keep theirs. */
class HandshakeAliasPolicyTest {

    private fun randomScalarBytes(): ByteArray {
        val random = SecureRandom()
        while (true) {
            val candidate = ByteArray(32).also { random.nextBytes(it) }
            val d = BigInteger(1, candidate)
            if (d != BigInteger.ZERO && d < Secp256k1.N) return candidate
        }
    }

    /** WalletManager.theirDeterministicAlias, from raw keys. */
    private fun theirDeterministicAlias(myPriv: ByteArray, theirPub: ByteArray) =
        KasiaCipher.deriveDeterministicAlias(myPriv, theirPub, contextXOnlyPubKey = theirPub)

    private fun myDeterministicAlias(myPriv: ByteArray, theirPub: ByteArray) =
        KasiaCipher.deriveDeterministicAlias(myPriv, theirPub, contextXOnlyPubKey = Schnorr.publicKeyXOnly(myPriv))

    @Test
    fun `a new contact's handshake carries the deterministic alias the peer watches`() {
        val alicePriv = randomScalarBytes()
        val bobPriv = randomScalarBytes()
        val aliceToBob = theirDeterministicAlias(alicePriv, Schnorr.publicKeyXOnly(bobPriv))

        val alias = HandshakeAliasPolicy.handshakeAlias(existingMyAlias = null, deterministicAlias = aliceToBob)
        assertEquals(aliceToBob, alias)
        assertTrue(alias.matches(Regex("^[0-9a-f]{12}$")))
        // Exactly the alias Bob (Desktop, iOS, Android) fetches Alice's messages under.
        assertEquals(myDeterministicAlias(bobPriv, Schnorr.publicKeyXOnly(alicePriv)), alias)
        // A blank stored alias is no alias.
        assertEquals(aliceToBob, HandshakeAliasPolicy.handshakeAlias("  ", aliceToBob))
    }

    @Test
    fun `a legacy contact keeps its random alias for handshakes and messages`() {
        val deterministic = "0123456789ab"
        val legacy = "fedcba987654"
        assertEquals(legacy, HandshakeAliasPolicy.handshakeAlias(legacy, deterministic))
        assertEquals(legacy, HandshakeAliasPolicy.messageAlias(handshakeComplete = true, myAlias = legacy, deterministicAlias = deterministic))
        assertTrue(HandshakeAliasPolicy.isLegacyAlias(legacy, deterministic))
    }

    @Test
    fun `messages use the deterministic alias unless the chat is a pre-existing legacy one`() {
        val deterministic = "0123456789ab"
        // No handshake, or a handshake that stored nothing.
        assertEquals(deterministic, HandshakeAliasPolicy.messageAlias(false, null, deterministic))
        assertEquals(deterministic, HandshakeAliasPolicy.messageAlias(true, null, deterministic))
        // A stray alias on a chat with no handshake is not used.
        assertEquals(deterministic, HandshakeAliasPolicy.messageAlias(false, "fedcba987654", deterministic))
        // A chat whose new handshake stored the deterministic alias.
        assertEquals(deterministic, HandshakeAliasPolicy.messageAlias(true, deterministic, deterministic))
        assertFalse(HandshakeAliasPolicy.isLegacyAlias(deterministic, deterministic))
        assertFalse(HandshakeAliasPolicy.isLegacyAlias(null, deterministic))
    }
}
