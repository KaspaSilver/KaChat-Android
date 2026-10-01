package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class InboxTagTest {

    @Test
    fun `inbox tag is the first 16 bytes of the SHA-256 of the prefixed lowercased address`() {
        // SHA-256("kachat-inbox:v1:kaspa:qypq8ef5...") - the same bytes iOS InboxTag.compute hashes.
        val address = "kaspa:qypq8ef5nxdjgklkj9rxn2yd4k8xnzlszavqpzysnjkrjq3qj0ynq6cnlxwwsmu"
        assertEquals("2882bb4f8cba4243521ca18e3e0e2a56", InboxTag.compute(address))
        assertEquals("2882bb4f8cba4243521ca18e3e0e2a56", InboxTag.compute("  " + address.uppercase() + "\n"))
    }

    @Test
    fun `a dm payload reads as comm`() {
        val dm = "kchat:1:dm:2882bb4f8cba4243521ca18e3e0e2a56:abcdef012345:QUJD"
        assertEquals("kchat:1:comm:abcdef012345:QUJD", MessageProtocol.normalizeFirstContact(dm))
        val comm = "kchat:1:comm:abcdef012345:QUJD"
        assertEquals(comm, MessageProtocol.normalizeFirstContact(comm))
    }
}
