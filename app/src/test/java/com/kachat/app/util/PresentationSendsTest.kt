package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iOS's send sheets each own their send: Cancel closes one mid-send, the send carries on, and
 * what it comes to is never shown on the next send sheet.
 */
class PresentationSendsTest {

    @Test
    fun `a send's outcome reaches the screen that started it`() {
        val sends = PresentationSends<String>()
        sends.open("a")
        sends.start("a")
        assertTrue("a" in sends.inFlight.value)
        sends.finish("a", Result.success("tx1"))
        assertFalse("a" in sends.inFlight.value)
        assertEquals("tx1", sends.results.value["a"]?.getOrNull())
        sends.consume("a")
        assertNull(sends.results.value["a"])
    }

    @Test
    fun `a screen closed mid-send never sees its outcome, nor does the next one`() {
        val sends = PresentationSends<String>()
        sends.open("first")
        sends.start("first")
        sends.close("first")
        // The next send screen comes up while the first send is still out.
        sends.open("second")
        assertFalse("second" in sends.inFlight.value)
        sends.finish("first", Result.success("tx1"))
        assertTrue(sends.results.value.isEmpty())
        assertFalse("first" in sends.inFlight.value)
    }

    @Test
    fun `a failure is kept to its own screen too`() {
        val sends = PresentationSends<String>()
        sends.open("a")
        sends.open("b")
        sends.start("a")
        sends.finish("a", Result.failure(IllegalStateException("no funds")))
        assertTrue(sends.results.value["a"]!!.isFailure)
        assertNull(sends.results.value["b"])
    }

    @Test
    fun `closing drops an outcome that was never shown`() {
        val sends = PresentationSends<String>()
        sends.open("a")
        sends.start("a")
        sends.finish("a", Result.success("tx1"))
        sends.close("a")
        assertTrue(sends.results.value.isEmpty())
    }
}
