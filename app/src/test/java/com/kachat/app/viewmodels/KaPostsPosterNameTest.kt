package com.kachat.app.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Test

/** KaPosts poster names: alias, else the .kachat name, else the short address (iOS 3d6fb7c). */
class KaPostsPosterNameTest {
    private val address = "kaspatest:qr0123456789abcdefghijklmnopqrstuvwxyz0123456789abcdef"

    @Test
    fun `your alias wins`() {
        assertEquals("Bob", KaPostsViewModel.posterName(address, " Bob ", "alice.kachat", "alice.kas", showsKnsNames = false))
    }

    @Test
    fun `the kachat name comes next`() {
        assertEquals("alice.kachat", KaPostsViewModel.posterName(address, null, "alice.kachat", "alice.kas", showsKnsNames = false))
        assertEquals("alice.kachat", KaPostsViewModel.posterName(address, "  ", "alice.kachat", null, showsKnsNames = false))
    }

    @Test
    fun `a kns domain is not identity with the kachat UI on`() {
        assertEquals("kaspatest:qr01....cdef", KaPostsViewModel.posterName(address, null, null, "alice.kas", showsKnsNames = false))
        assertEquals("kaspatest:qr01....cdef", KaPostsViewModel.posterName(address, null, null, "alice.kas"))
    }

    @Test
    fun `the kns switch-back still reads the domain`() {
        assertEquals("alice.kas", KaPostsViewModel.posterName(address, null, null, " alice.kas ", showsKnsNames = true))
    }

    @Test
    fun `no address is Unknown`() {
        assertEquals("Unknown", KaPostsViewModel.posterName("", "Bob", "alice.kachat", null, showsKnsNames = false))
    }

    // The key a profile resolves to is the one the indexer's data names, never the probe
    // (iOS 0a0c914, IOS-099 / AND-047).
    @Test
    fun `a profile key is the data's own, checked against the address`() {
        val x = "ab".repeat(32)
        // an 03 user: the data's 03 key, whatever was probed
        assertEquals("03$x", KaPostsViewModel.kaPostsKeyNamed("03" + x.uppercase(), x))
        assertEquals("02$x", KaPostsViewModel.kaPostsKeyNamed("02$x", x))
        // another person's key, a bad prefix, a short or missing key: none
        assertEquals(null, KaPostsViewModel.kaPostsKeyNamed("03" + "cd".repeat(32), x))
        assertEquals(null, KaPostsViewModel.kaPostsKeyNamed("04$x", x))
        assertEquals(null, KaPostsViewModel.kaPostsKeyNamed(x, x))
        assertEquals(null, KaPostsViewModel.kaPostsKeyNamed(null, x))
    }
}
