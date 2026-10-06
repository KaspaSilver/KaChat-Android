package com.kachat.app.util

import com.kachat.app.util.KaspaNetwork.Type.MAINNET
import com.kachat.app.util.KaspaNetwork.Type.TESTNET
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit AND-006 / iOS d946a65: the other network's pushes stay silent, on every push type. */
class PushNetworkFilterTest {
    private val mainnetSender = "kaspa:qqmainnetsenderaddress"
    private val testnetSender = "kaspatest:qqtestnetsenderaddress"

    @Test
    fun `a sender of the other network is the other network, for any push type`() {
        for (type in listOf("contextual", "payment", "handshake", "group_message", "group_control", "broadcast", "kaposts", "name_event")) {
            assertTrue(type, PushNetworkFilter.isOtherNetwork(mapOf("type" to type, "sender" to mainnetSender), TESTNET))
            assertTrue(type, PushNetworkFilter.isOtherNetwork(mapOf("type" to type, "sender" to testnetSender), MAINNET))
        }
    }

    @Test
    fun `a sender of this network is kept`() {
        assertFalse(PushNetworkFilter.isOtherNetwork(mapOf("type" to "group_message", "sender" to mainnetSender), MAINNET))
        assertFalse(PushNetworkFilter.isOtherNetwork(mapOf("type" to "group_message", "sender" to testnetSender), TESTNET))
        // kaspatest: also starts with "kaspa" - it must not read as mainnet
        assertFalse(PushNetworkFilter.isOtherNetwork(mapOf("sender" to "KASPATEST:QQUPPER"), TESTNET))
    }

    @Test
    fun `no sender or an unprefixed one is not the other network`() {
        assertFalse(PushNetworkFilter.isOtherNetwork(mapOf("type" to "broadcast", "channel" to "kaspa"), TESTNET))
        assertFalse(PushNetworkFilter.isOtherNetwork(mapOf("type" to "group_control", "sender" to "qqnoprefix"), TESTNET))
        assertFalse(PushNetworkFilter.isOtherNetwork(mapOf("type" to "kaposts", "sender" to ""), MAINNET))
        assertFalse(PushNetworkFilter.isOtherNetwork(emptyMap(), MAINNET))
    }

    @Test
    fun `a declared network field is honoured when present`() {
        assertTrue(PushNetworkFilter.isOtherNetwork(mapOf("type" to "kaposts", "network" to "mainnet"), TESTNET))
        assertTrue(PushNetworkFilter.isOtherNetwork(mapOf("type" to "name_event", "network" to "kaspatest"), MAINNET))
        assertFalse(PushNetworkFilter.isOtherNetwork(mapOf("type" to "broadcast", "network" to "Testnet"), TESTNET))
        assertFalse(PushNetworkFilter.isOtherNetwork(mapOf("type" to "broadcast", "network" to "devnet"), MAINNET))
    }

    @Test
    fun `network names map to networks`() {
        assertEquals(MAINNET, PushNetworkFilter.networkNamed("mainnet"))
        assertEquals(MAINNET, PushNetworkFilter.networkNamed(" kaspa "))
        assertEquals(TESTNET, PushNetworkFilter.networkNamed("testnet"))
        assertEquals(TESTNET, PushNetworkFilter.networkNamed("kaspatest"))
        assertNull(PushNetworkFilter.networkNamed(null))
        assertNull(PushNetworkFilter.networkNamed(""))
        assertNull(PushNetworkFilter.networkNamed("simnet"))
    }
}
