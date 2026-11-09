package com.kachat.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupRootSenderTest {
    private val adminPub = "af1044f8c7ac523b7862ac6d6d5d91909d22d93001775ae65dce7f3541e4950d"
    private val memberPub = "884f54ac6b599bda71ce433ba67078043831b6ebab58d8277a1973490ddc922a"

    private fun address(pubHex: String) =
        KaspaAddress.encode("kaspa", 0x00, pubHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())

    @Test
    fun `the admin's address may send its root`() {
        assertTrue(GroupCipher.rootSenderAllowed(address(adminPub), adminPub, "kaspa"))
        assertTrue(GroupCipher.rootSenderAllowed(address(adminPub).uppercase(), adminPub, "kaspa"))
    }

    @Test
    fun `a member's address may not send the admin's root`() {
        assertFalse(GroupCipher.rootSenderAllowed(address(memberPub), adminPub, "kaspa"))
    }

    @Test
    fun `a sender the indexer hasn't resolved yet is let through`() {
        assertTrue(GroupCipher.rootSenderAllowed("", adminPub, "kaspa"))
    }

    @Test
    fun `a root naming an unreadable admin key is refused`() {
        assertFalse(GroupCipher.rootSenderAllowed(address(memberPub), "not hex", "kaspa"))
    }
}
