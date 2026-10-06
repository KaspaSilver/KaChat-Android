package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * The push ring is signed the way the push service verifies it (iOS 7182b4a + 82eb7f7, audit
 * XP-010): LegacyV1, no watched_group_ids_hash line, wallet address as primary_address.
 */
class PushAuthPreimageTest {

    private val emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private val token = "fcm-token-123"
    private val pubkey = "a".repeat(64)
    private val wallet = "kaspa:qypwallet"
    private val nonce = "nonce-xyz"

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** iOS buildAuthPreimage(includeWatchedGroupIds: false), as requestRing calls it: lines built
     *  in a var, the group line skipped, joined once with "\n". */
    private fun iosRingPreimage(): String {
        val lines = mutableListOf(
            "domain=kchat-push-auth:v1",
            "nonce=$nonce",
            "method=POST",
            "path=/v1/push/ring",
            "device_token_hash=${sha(token)}",
            "watched_addresses_hash=$emptyHash",
        )
        lines += listOf(
            "primary_address=$wallet",
            "aliases_hash=$emptyHash",
            "wallet_pubkey=$pubkey",
            "wallet_address=$wallet",
            "timestamp_ms=1790300000000",
            "expires_at_ms=1790300060000",
        )
        return lines.joinToString("\n")
    }

    @Test
    fun `ring preimage matches iOS exactly`() {
        val ring = PushAuthPreimage.ring(token, pubkey, wallet, " $nonce\n", 1_790_300_000_000L, 1_790_300_060_000L)
        assertEquals(iosRingPreimage(), ring)
        assertFalse(ring.contains("watched_group_ids_hash"))
        assertEquals(12, ring.split("\n").size)
    }

    @Test
    fun `requestRing's arguments produce the same preimage as ring`() {
        // The exact argument set PushRegistrationManager.requestRing hands to buildAuth.
        val viaBuild = PushAuthPreimage.build(
            method = "POST",
            path = PushAuthPreimage.RING_PATH,
            deviceToken = token,
            watchedAddresses = emptyList(),
            watchedGroupIds = emptyList(),
            primaryAddress = wallet,
            aliases = emptyList(),
            walletPubkey = pubkey,
            walletAddress = wallet,
            nonce = nonce,
            timestampMs = 1_790_300_000_000L,
            expiresAtMs = 1_790_300_060_000L,
            includeWatchedGroupIds = false,
        )
        assertEquals(iosRingPreimage(), viaBuild)
    }

    @Test
    fun `registration with groups carries the group line right after watched addresses`() {
        val pre = PushAuthPreimage.build(
            method = "POST", path = "/v1/push/register", deviceToken = token,
            watchedAddresses = listOf(" Kaspa:B ", "kaspa:a", "kaspa:a"),
            watchedGroupIds = listOf("FF", "aa"),
            primaryAddress = wallet, aliases = listOf("b1", " a1"),
            walletPubkey = pubkey, walletAddress = wallet, nonce = nonce,
            timestampMs = 1L, expiresAtMs = 2L,
        )
        val lines = pre.split("\n")
        assertEquals("watched_addresses_hash=${sha("kaspa:a\nkaspa:b")}", lines[5])
        assertEquals("watched_group_ids_hash=${sha("aa\nff")}", lines[6])
        assertEquals("aliases_hash=${sha("a1\nb1")}", lines[8])
        assertEquals(13, lines.size)
    }
}
