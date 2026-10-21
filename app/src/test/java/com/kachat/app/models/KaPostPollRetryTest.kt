package com.kachat.app.models

import org.junit.Assert.assertEquals
import org.junit.Test

/** iOS df4aba6: a failed poll's retry re-opens it for the length chosen at compose time, so
 *  repeated retries never lengthen it; a poll read from the network keeps the old derivation. */
class KaPostPollRetryTest {

    private val hour = 3_600_000L
    private val posted = 1_000_000_000_000L

    private fun poll(closesAtMs: Long, lengthMs: Long? = null) =
        KaPostDraft.KaPostPoll(options = listOf("a", "b"), counts = listOf(0, 0), closesAtMs = closesAtMs, lengthMs = lengthMs)

    @Test
    fun `a retry keeps the composed length though closesAt moved`() {
        // composed for 1 hour; an earlier retry moved closesAt 3 hours past the post's time
        val moved = poll(closesAtMs = posted + 3 * hour, lengthMs = hour)
        assertEquals(hour, moved.retryLengthMs(posted))
    }

    @Test
    fun `a poll with no stored length derives it from closesAt and the post's time`() {
        assertEquals(2 * hour, poll(closesAtMs = posted + 2 * hour).retryLengthMs(posted))
    }

    @Test
    fun `the retry length is at least a minute`() {
        assertEquals(60_000L, poll(closesAtMs = posted + 5_000L, lengthMs = 5_000L).retryLengthMs(posted))
        assertEquals(60_000L, poll(closesAtMs = posted - hour).retryLengthMs(posted))
    }
}
