package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Share shortcut ids name their account (iOS d23e6f7a: share targets bound to their account). */
class ShareShortcutIdTest {

    private val owner = "kaspa:qqowneraddress0000000000000000000000000000000000000"
    private val contact = "kaspa:qqcontactaddress00000000000000000000000000000000000"

    @Test
    fun `an id carries its account and its chat`() {
        val id = ShareShortcutsManager.shortcutId(owner, contact)
        assertEquals("$owner|$contact", id)
        assertEquals(ShareShortcutsManager.ShortcutTarget(owner, contact), ShareShortcutsManager.parseShortcutId(id))
    }

    @Test
    fun `an old bare-address id is still read, with no account`() {
        assertEquals(ShareShortcutsManager.ShortcutTarget(null, contact), ShareShortcutsManager.parseShortcutId(contact))
    }

    @Test
    fun `empty or chat-less ids are nothing`() {
        assertNull(ShareShortcutsManager.parseShortcutId(null))
        assertNull(ShareShortcutsManager.parseShortcutId(""))
        assertNull(ShareShortcutsManager.parseShortcutId("$owner|"))
    }

    @Test
    fun `an id with an empty account reads as no account`() {
        assertEquals(ShareShortcutsManager.ShortcutTarget(null, contact), ShareShortcutsManager.parseShortcutId("|$contact"))
    }
}
