package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** iOS's `String.addressToastShortened` (Toast.swift). */
class AddressCopiedDisplayTest {
    @Test
    fun `a kaspa address keeps its prefix and shows three four-character segments`() {
        val address = "kaspa:qz0s9lkt2x8fwq3ynn7jpdlqpx0shzvkrexr2wqn2m6l4g3zdk8wm2aj"
        val body = address.substringAfter(':')
        val mid = body.length / 2 - 2
        assertEquals(
            "kaspa:${body.take(4)}...${body.substring(mid, mid + 4)}...${body.takeLast(4)}",
            addressCopiedDisplay(address),
        )
    }

    @Test
    fun `a value without a prefix is shortened the same way`() {
        assertEquals("0123...ghij...wxyz", addressCopiedDisplay("0123456789abcdefghijklmnopqrstuvwxyz"))
    }

    @Test
    fun `short values and surrounding spaces`() {
        assertEquals("kaspa:qz0s9lkt2x8fwq3ynn7jpdl", addressCopiedDisplay("  kaspa:qz0s9lkt2x8fwq3ynn7jpdl \n"))
        assertEquals("bc1qshort", addressCopiedDisplay("bc1qshort"))
        // 24 payload characters is the first length that shortens.
        assertEquals("kaspa:abcd...klmn...uvwx", addressCopiedDisplay("kaspa:abcdefghijklmnopqrstuvwx"))
    }
}
