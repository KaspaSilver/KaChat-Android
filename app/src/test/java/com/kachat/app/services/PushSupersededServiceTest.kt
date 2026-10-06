package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Audit AND-006: when to leave the push service this device last registered with (port of iOS
 *  `unregisterFromSupersededServiceIfNeeded`). */
class PushSupersededServiceTest {
    private val mainnet = "https://kachat.duckdns.org"
    private val testnet = "https://tnkachat.duckdns.org:7443"

    @Test
    fun `a different stored base is left`() {
        assertEquals(mainnet, PushRegistrationManager.supersededBase(mainnet, testnet))
        assertEquals(testnet, PushRegistrationManager.supersededBase(testnet, mainnet))
    }

    @Test
    fun `the same service is not left`() {
        assertNull(PushRegistrationManager.supersededBase(mainnet, mainnet))
        assertNull(PushRegistrationManager.supersededBase("$mainnet/", mainnet))
        assertNull(PushRegistrationManager.supersededBase("HTTPS://KACHAT.DUCKDNS.ORG", "$mainnet/"))
        assertNull(PushRegistrationManager.supersededBase(" $mainnet ", mainnet))
    }

    @Test
    fun `nothing stored means nothing to leave`() {
        assertNull(PushRegistrationManager.supersededBase(null, testnet))
        assertNull(PushRegistrationManager.supersededBase("", testnet))
        assertNull(PushRegistrationManager.supersededBase("   ", testnet))
    }

    @Test
    fun `no current base is the no-service path's job`() {
        assertNull(PushRegistrationManager.supersededBase(mainnet, ""))
    }
}
