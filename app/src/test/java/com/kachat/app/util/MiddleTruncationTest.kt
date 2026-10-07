package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiddleTruncationTest {

    private val address = "kaspa:qypr0qj7luv26laqlqp4qrgz9vmyqj0ytpsy3mfwqvcm3zu8udlhk5cyc2qnf2y"

    @Test
    fun `keeps the start and the end around an ellipsis`() {
        assertEquals("kasp…nf2y", MiddleTruncation.truncated(address, 8))
        // An odd count gives the start the extra character.
        assertEquals("kaspa…nf2y", MiddleTruncation.truncated(address, 9))
    }

    @Test
    fun `a string short enough is left alone`() {
        assertEquals("abc", MiddleTruncation.truncated("abc", 3))
        assertEquals("abc", MiddleTruncation.truncated("abc", 10))
        assertEquals("abc", MiddleTruncation.fit("abc") { true })
    }

    @Test
    fun `nothing kept is just the ellipsis`() {
        assertEquals("…", MiddleTruncation.truncated("abcdef", 0))
        assertEquals("…", MiddleTruncation.fit("abcdef") { it.length <= 1 })
    }

    @Test
    fun `fit keeps as much as the width allows`() {
        // A fixed-width "font": each character is one unit; 21 units fit.
        val fitted = MiddleTruncation.fit(address) { it.length <= 21 }
        assertEquals(21, fitted.length)
        assertEquals("kaspa:qypr…5cyc2qnf2y", fitted)
        assertTrue(fitted.startsWith("kaspa:"))
        assertTrue(fitted.endsWith("c2qnf2y"))
    }

    @Test
    fun `fit never splits an emoji`() {
        val name = "😀😀😀😀😀😀"
        val fitted = MiddleTruncation.fit(name) { it.codePointCount(0, it.length) <= 5 }
        assertEquals("😀😀…😀😀", fitted)
    }
}
