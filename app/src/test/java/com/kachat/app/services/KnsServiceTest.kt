package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnsServiceTest {

    @Test
    fun `a kaspa address is not treated as a KNS domain`() {
        assertFalse(KnsService.looksLikeDomain("kaspa:qrjymtgplw754wpk29acmcpp5rfhdctst5xjnsqpdldlzyar2c095uqctm634"))
    }

    @Test
    fun `a testnet address is not treated as a KNS domain`() {
        assertFalse(KnsService.looksLikeDomain("kaspatest:qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"))
    }

    @Test
    fun `an explicit dot-kas suffix is treated as a domain`() {
        assertTrue(KnsService.looksLikeDomain("alice.kas"))
    }

    @Test
    fun `a bare alphanumeric label is treated as a possible domain`() {
        assertTrue(KnsService.looksLikeDomain("alice"))
        assertTrue(KnsService.looksLikeDomain("alice-2000"))
    }

    @Test
    fun `blank input is not a domain`() {
        assertFalse(KnsService.looksLikeDomain(""))
        assertFalse(KnsService.looksLikeDomain("   "))
    }

    @Test
    fun `normalize appends dot-kas when missing`() {
        assertEquals("alice.kas", KnsService.normalizeDomain("alice"))
    }

    @Test
    fun `normalize leaves an already-suffixed domain alone`() {
        assertEquals("alice.kas", KnsService.normalizeDomain("alice.kas"))
    }

    @Test
    fun `normalize trims whitespace`() {
        assertEquals("alice.kas", KnsService.normalizeDomain("  alice  "))
    }

    // --- normalizeDomainLabel ---------------------------------------------------------

    @Test
    fun `label normalization lowercases and strips a typed dot-kas suffix`() {
        assertEquals("alice", KnsService.normalizeDomainLabel("Alice.kas"))
        assertEquals("alice", KnsService.normalizeDomainLabel("ALICE"))
    }

    @Test
    fun `label normalization trims whitespace`() {
        assertEquals("alice", KnsService.normalizeDomainLabel("  alice  "))
    }

    @Test
    fun `label normalization rejects empty or hyphen-only input`() {
        assertNull(KnsService.normalizeDomainLabel(""))
        assertNull(KnsService.normalizeDomainLabel("   "))
        assertNull(KnsService.normalizeDomainLabel(".kas"))
    }

    @Test
    fun `label normalization rejects leading or trailing hyphens`() {
        assertNull(KnsService.normalizeDomainLabel("-alice"))
        assertNull(KnsService.normalizeDomainLabel("alice-"))
    }

    @Test
    fun `label normalization rejects disallowed characters`() {
        assertNull(KnsService.normalizeDomainLabel("alice_smith"))
        assertNull(KnsService.normalizeDomainLabel("alice smith"))
        assertNull(KnsService.normalizeDomainLabel("alice@smith"))
    }

    @Test
    fun `label normalization allows hyphens and digits in the middle`() {
        assertEquals("alice-2000", KnsService.normalizeDomainLabel("alice-2000"))
    }

    // --- fee tier math -----------------------------------------------------------

    @Test
    fun `fee tier is the label length for 1 to 4 characters`() {
        assertEquals(1, KnsService.feeTierForLabel("a"))
        assertEquals(4, KnsService.feeTierForLabel("abcd"))
    }

    @Test
    fun `fee tier caps at 5 for labels 5 characters or longer`() {
        assertEquals(5, KnsService.feeTierForLabel("abcde"))
        assertEquals(5, KnsService.feeTierForLabel("averylongdomainlabel"))
    }

    // --- isSignatureVerificationFailure ---------------------------------------------------

    @Test
    fun `a signature verification failure message triggers the next signing mode`() {
        assertTrue(KnsService.isSignatureVerificationFailure("Signature verification failed"))
        assertTrue(KnsService.isSignatureVerificationFailure("SIGNATURE VERIFICATION FAILED: bad sig"))
    }

    @Test
    fun `an unauthorized message triggers the next signing mode`() {
        assertTrue(KnsService.isSignatureVerificationFailure("401 Unauthorized"))
        assertTrue(KnsService.isSignatureVerificationFailure("unauthorized request"))
    }

    @Test
    fun `an unrelated error does not trigger a retry`() {
        assertFalse(KnsService.isSignatureVerificationFailure("Internal server error"))
        assertFalse(KnsService.isSignatureVerificationFailure("Image too large"))
    }

    @Test
    fun `a null message does not trigger a retry`() {
        assertFalse(KnsService.isSignatureVerificationFailure(null))
    }
}
