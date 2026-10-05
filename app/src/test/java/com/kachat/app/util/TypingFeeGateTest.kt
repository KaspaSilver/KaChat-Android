package com.kachat.app.util

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The fee preview's typing gate (iOS 0977a5b): a keystroke re-prices only after a pause, and not
 * at all within 24 bytes of the size already priced; empty, the first text and forced changes
 * are priced at once.
 */
class TypingFeeGateTest {

    @Test
    fun `the first text and emptying the composer are priced at once`() {
        val gate = TypingFeeGate()
        assertEquals(TypingFeeGate.Decision.Now(1), gate.onText(1, forced = false))
        assertEquals(1, gate.pricedBytes)
        assertEquals(TypingFeeGate.Decision.Now(0), gate.onText(0, forced = false))
        assertNull(gate.pricedBytes)
        // Empty again: the next character is "the first text" once more.
        assertEquals(TypingFeeGate.Decision.Now(3), gate.onText(3, forced = false))
    }

    @Test
    fun `typing within 24 bytes of the priced size keeps the shown fee`() {
        val gate = TypingFeeGate()
        gate.onText(10, forced = false)
        for (bytes in 11..33) {
            assertEquals("at $bytes bytes", TypingFeeGate.Decision.Keep, gate.onText(bytes, forced = false))
        }
        // Deleting back down is the same rule.
        assertEquals(TypingFeeGate.Decision.Keep, gate.onText(1, forced = false))
        assertEquals(10, gate.pricedBytes)
    }

    @Test
    fun `typing 24 bytes or more away waits for a pause`() {
        val gate = TypingFeeGate()
        gate.onText(10, forced = false)
        assertEquals(TypingFeeGate.Decision.AfterPause, gate.onText(34, forced = false))
        assertEquals(TypingFeeGate.Decision.AfterPause, gate.onText(40, forced = false))
        // Nothing is priced until the pause runs out.
        assertEquals(10, gate.pricedBytes)
        assertEquals(41, gate.onPause(41))
        assertEquals(41, gate.pricedBytes)
        // Measured from the newly priced size now.
        assertEquals(TypingFeeGate.Decision.Keep, gate.onText(60, forced = false))
        assertEquals(TypingFeeGate.Decision.AfterPause, gate.onText(65, forced = false))
    }

    @Test
    fun `a forced change is priced at once whatever the size`() {
        val gate = TypingFeeGate()
        gate.onText(10, forced = false)
        assertEquals(TypingFeeGate.Decision.Now(12), gate.onText(12, forced = true))
        assertEquals(12, gate.pricedBytes)
        assertEquals(TypingFeeGate.Decision.Now(200), gate.onText(200, forced = true))
    }

    @Test
    fun `the flow prices the first text at once and the rest after the pause`() = runBlocking {
        val a = "a"
        val emitted = TypingFeeGate.payloadBytes(
            flowOf(
                TypingFeeGate.Input(a, 0),
                TypingFeeGate.Input(a.repeat(10), 0), // within 24 of 1: kept
                TypingFeeGate.Input(a.repeat(30), 0), // 24+ away: waits
                TypingFeeGate.Input(a.repeat(31), 0), // still typing: waits again
            ),
            pauseMs = 200,
        ).toList()
        assertEquals(listOf(1, 31), emitted)
    }

    @Test
    fun `the flow re-prices at once when the force key changes`() = runBlocking {
        val emitted = TypingFeeGate.payloadBytes(
            flowOf(
                TypingFeeGate.Input("hello", 0),
                TypingFeeGate.Input("hello!", 1), // e.g. a reply was set
                TypingFeeGate.Input("", 1),
            ),
            pauseMs = 10_000,
        ).toList()
        assertEquals(listOf(5, 6, 0), emitted)
    }

    @Test
    fun `sizes are UTF-8 bytes, not characters`() = runBlocking {
        val emitted = TypingFeeGate.payloadBytes(flowOf(TypingFeeGate.Input("é", 0))).toList()
        assertEquals(listOf(2), emitted)
    }
}
