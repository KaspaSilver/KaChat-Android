package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KaspaNetworkTest {
    private val payload = ByteArray(32) { (it * 7 + 3).toByte() }
    private val mainnet = KaspaAddress.encode("kaspa", 0x00, payload)
    private val testnet = KaspaAddress.encode("kaspatest", 0x00, payload)

    @Test
    fun `one key re-encodes between kaspa and kaspatest`() {
        assertEquals(testnet, KaspaNetwork.reencode(mainnet, "kaspatest"))
        assertEquals(mainnet, KaspaNetwork.reencode(testnet, "kaspa"))
        assertEquals(mainnet, KaspaNetwork.reencode(mainnet, "kaspa"))
        assertEquals(listOf(mainnet, testnet), KaspaNetwork.accountAddressVariants(mainnet))
    }

    @Test
    fun `the same key is the same account on either network`() {
        assertTrue(KaspaNetwork.isSameAccount(mainnet, testnet))
        val other = KaspaAddress.encode("kaspa", 0x00, ByteArray(32) { 1 })
        assertFalse(KaspaNetwork.isSameAccount(mainnet, other))
    }

    @Test
    fun `addresses are told apart by prefix, testnet first`() {
        assertEquals(KaspaNetwork.Type.TESTNET, KaspaNetwork.ofAddress(testnet))
        assertEquals(KaspaNetwork.Type.MAINNET, KaspaNetwork.ofAddress(mainnet))
        // Unit tests run on mainnet (KaspaNetwork.init is never called).
        assertTrue(KaspaNetwork.isOnActiveNetwork(mainnet))
        assertFalse(KaspaNetwork.isOnActiveNetwork(testnet))
        assertTrue(KaspaNetwork.isOnActiveNetwork("no-prefix"))
    }
}
