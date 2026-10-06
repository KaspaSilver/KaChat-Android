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
        assertEquals(address.takeLast(10), KaPostsViewModel.posterName(address, null, null, "alice.kas", showsKnsNames = false))
        assertEquals(address.takeLast(10), KaPostsViewModel.posterName(address, null, null, "alice.kas"))
    }

    @Test
    fun `the kns switch-back still reads the domain`() {
        assertEquals("alice.kas", KaPostsViewModel.posterName(address, null, null, " alice.kas ", showsKnsNames = true))
    }

    @Test
    fun `no address is Unknown`() {
        assertEquals("Unknown", KaPostsViewModel.posterName("", "Bob", "alice.kachat", null, showsKnsNames = false))
    }
}
