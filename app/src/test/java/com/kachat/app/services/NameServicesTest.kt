package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class NameServicesTest {
    @Test
    fun `dotk normalizes like its SDK`() {
        assertEquals("bob", NameNormalization.dotkCanonical("  Bob.K "))
        assertEquals("bob-1", NameNormalization.dotkCanonical("BOB-1"))
        assertEquals("béb", NameNormalization.dotkNormalize("béb")) // only A-Z is lowered
        assertNull(NameNormalization.dotkCanonical("béb"))
        assertNull(NameNormalization.dotkCanonical("-bob"))
        assertNull(NameNormalization.dotkCanonical(""))
        assertNull(NameNormalization.dotkCanonical("a".repeat(33)))
    }

    @Test
    fun `kaspa names normalizes like its SDK`() {
        assertEquals("bob", NameNormalization.kaspaNamesCanonical("Ｂob.kaspa")) // fullwidth B, NFKC
        assertEquals("bob", NameNormalization.kaspaNamesCanonical("BOB"))
        assertNull(NameNormalization.kaspaNamesCanonical("bob "))
        assertNull(NameNormalization.kaspaNamesCanonical("bob_1"))
        assertNull(NameNormalization.kaspaNamesCanonical("bob-"))
    }

    @Test
    fun `typed endings split longest first`() {
        assertEquals("bob" to NameServiceTLD.KASPA, NameServiceTLD.splitTypedName("bob.kaspa"))
        assertEquals("bob" to NameServiceTLD.KAS, NameServiceTLD.splitTypedName("bob.kas"))
        assertEquals("Bob" to NameServiceTLD.K, NameServiceTLD.splitTypedName("Bob.K")) // case kept, as on iOS
        assertEquals("bob" to null, NameServiceTLD.splitTypedName("bob"))
    }

    @Test
    fun `primary prefers the typed ending, else resolution order`() {
        val results = listOf(
            NameResolution(NameServiceTLD.KAS, "bob.kas", null, false),
            NameResolution(NameServiceTLD.K, "bob.k", "kaspa:k", false),
            NameResolution(NameServiceTLD.KASPA, "bob.kaspa", "kaspa:kaspa", false),
        )
        assertEquals(NameServiceTLD.K, NameServicesClient.primary(results, "bob")?.tld)
        assertEquals(NameServiceTLD.KASPA, NameServicesClient.primary(results, "bob.kaspa")?.tld)
        assertNull(NameServicesClient.primary(results, "bob.kas"))
        assertTrue(NameServicesClient.looksLikeName("bob.k"))
        assertFalse(NameServicesClient.looksLikeName("kaspa:qqq"))
    }
}
