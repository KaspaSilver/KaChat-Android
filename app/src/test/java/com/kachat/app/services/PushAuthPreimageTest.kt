package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.security.MessageDigest

/**
 * The push auth preimage, as the push service verifies it: LegacyV1 with no group ids, the group
 * line right after watched addresses once group ids are registered. The call ring and its own
 * preimage left with calls (iOS 7c5c22e3).
 */
class PushAuthPreimageTest {

    private val emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private val token = "fcm-token-123"
    private val pubkey = "a".repeat(64)
    private val wallet = "kaspa:qypwallet"
    private val nonce = "nonce-xyz"

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun `no group ids gives the LegacyV1 shape`() {
        val pre = PushAuthPreimage.build(
            method = "DELETE", path = "/v1/push/unregister", deviceToken = token,
            watchedAddresses = emptyList(), watchedGroupIds = emptyList(),
            primaryAddress = "", aliases = emptyList(),
            walletPubkey = pubkey, walletAddress = wallet, nonce = " $nonce\n",
            timestampMs = 1_790_300_000_000L, expiresAtMs = 1_790_300_060_000L,
        )
        val expected = listOf(
            "domain=kchat-push-auth:v1",
            "nonce=$nonce",
            "method=DELETE",
            "path=/v1/push/unregister",
            "device_token_hash=${sha(token)}",
            "watched_addresses_hash=$emptyHash",
            "primary_address=",
            "aliases_hash=$emptyHash",
            "wallet_pubkey=$pubkey",
            "wallet_address=$wallet",
            "timestamp_ms=1790300000000",
            "expires_at_ms=1790300060000",
        ).joinToString("\n")
        assertEquals(expected, pre)
        assertFalse(pre.contains("watched_group_ids_hash"))
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
