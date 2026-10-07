package com.kachat.app.util

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The fee preview's typing gate (iOS 0977a5b `scheduleFeeEstimate`): every price waits for a
 * pause - the first one too - and nothing is re-priced within 24 bytes of the size already
 * priced; a forced change waits the shorter pause; emptying the composer clears at once. While a
 * pause is waited out the pill shows the placeholder.
 */
class TypingFeeGateTest {

    private val pause = TypingFeeGate.Decision.AfterPause(TypingFeeGate.PAUSE_MS)
    private val forcedPause = TypingFeeGate.Decision.AfterPause(TypingFeeGate.FORCED_PAUSE_MS)

    @Test
    fun `the first text waits for the 0_6 s pause, as iOS's first price does`() {
        val gate = TypingFeeGate()
        assertEquals(600L, TypingFeeGate.PAUSE_MS)
        assertEquals(pause, gate.onText(1, forced = false))
        assertNull(gate.pricedBytes)
        // Still typing before the pause runs out: still waiting, still nothing priced.
        assertEquals(pause, gate.onText(2, forced = false))
        assertNull(gate.pricedBytes)
        assertEquals(2, gate.onPause(2))
        assertEquals(2, gate.pricedBytes)
    }

    @Test
    fun `emptying the composer clears at once, and the next text waits again`() {
        val gate = TypingFeeGate()
        gate.onText(5, forced = false)
        gate.onPause(5)
        assertEquals(TypingFeeGate.Decision.Now(0), gate.onText(0, forced = false))
        assertNull(gate.pricedBytes)
        assertEquals(pause, gate.onText(3, forced = false))
    }

    @Test
    fun `typing within 24 bytes of the priced size keeps the shown fee`() {
        val gate = TypingFeeGate()
        gate.onText(10, forced = false)
        gate.onPause(10)
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
        gate.onPause(10)
        assertEquals(pause, gate.onText(34, forced = false))
        assertEquals(pause, gate.onText(40, forced = false))
        // Nothing is priced until the pause runs out.
        assertEquals(10, gate.pricedBytes)
        assertEquals(41, gate.onPause(41))
        assertEquals(41, gate.pricedBytes)
        // Measured from the newly priced size now.
        assertEquals(TypingFeeGate.Decision.Keep, gate.onText(60, forced = false))
        assertEquals(pause, gate.onText(65, forced = false))
    }

    @Test
    fun `a forced change waits the shorter 0_2 s pause, whatever the size`() {
        val gate = TypingFeeGate()
        gate.onText(10, forced = false)
        gate.onPause(10)
        assertEquals(200L, TypingFeeGate.FORCED_PAUSE_MS)
        assertEquals(forcedPause, gate.onText(12, forced = true))
        assertEquals(forcedPause, gate.onText(200, forced = true))
    }

    @Test
    fun `the flow shows the placeholder until the first pause, then the price`() = runBlocking {
        val a = "a"
        val emitted = TypingFeeGate.preview(
            flowOf(
                TypingFeeGate.Input("", 0),
                TypingFeeGate.Input(a, 0),
                TypingFeeGate.Input(a.repeat(10), 0), // still before the pause: waits again
            ),
            pauseMs = 50,
        ).toList()
        assertEquals(
            listOf(
                TypingFeeGate.Preview(0, estimating = false),
                TypingFeeGate.Preview(0, estimating = true),
                TypingFeeGate.Preview(10, estimating = false),
            ),
            emitted,
        )
    }

    @Test
    fun `the flow keeps the price within 24 bytes and re-prices after a pause beyond`() = runBlocking {
        val a = "a"
        val inputs = flow {
            emit(TypingFeeGate.Input("", 0))
            emit(TypingFeeGate.Input(a.repeat(5), 0))
            delay(150) // past the pause: 5 is priced
            emit(TypingFeeGate.Input(a.repeat(20), 0)) // within 24 of 5: kept, no placeholder
            emit(TypingFeeGate.Input(a.repeat(30), 0)) // 25 away: waits, placeholder
        }
        val emitted = TypingFeeGate.preview(inputs, pauseMs = 50).toList()
        assertEquals(
            listOf(
                TypingFeeGate.Preview(0, estimating = false),
                TypingFeeGate.Preview(0, estimating = true),
                TypingFeeGate.Preview(5, estimating = false),
                TypingFeeGate.Preview(5, estimating = true),
                TypingFeeGate.Preview(30, estimating = false),
            ),
            emitted,
        )
        assertEquals(listOf(0, 5, 30), TypingFeeGate.payloadBytes(flowOf(*emitted.toTypedArray())).toList())
        assertEquals(listOf(false, true, false, true, false), TypingFeeGate.estimating(flowOf(*emitted.toTypedArray())).toList())
    }

    @Test
    fun `the flow re-prices after the short pause when the force key changes, and clears at once`() = runBlocking {
        val inputs = flow {
            emit(TypingFeeGate.Input("hello", 0))
            delay(150)
            emit(TypingFeeGate.Input("hello!", 1)) // e.g. a reply was set
            delay(150)
            emit(TypingFeeGate.Input("", 1))
        }
        val bytes = TypingFeeGate.payloadBytes(TypingFeeGate.preview(inputs, pauseMs = 50)).toList()
        assertEquals(listOf(5, 6, 0), bytes)
    }

    @Test
    fun `sizes are UTF-8 bytes of the trimmed text`() = runBlocking {
        val bytes = TypingFeeGate.payloadBytes(TypingFeeGate.preview(flowOf(TypingFeeGate.Input("  é \n", 0)), pauseMs = 10)).toList()
        assertEquals(listOf(2), bytes)
        // Only whitespace is nothing to price.
        val blank = TypingFeeGate.payloadBytes(TypingFeeGate.preview(flowOf(TypingFeeGate.Input("   ", 0)), pauseMs = 10)).toList()
        assertEquals(listOf(0), blank)
    }
}
