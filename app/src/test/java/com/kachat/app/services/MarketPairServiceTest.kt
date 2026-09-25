package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KAS against a pair is KAS/USD over the pair's own USD price at the same moment - and a pair
 * only trades market hours, so between sessions its last close has to stand (iOS 39adefe).
 */
class MarketPairServiceTest {

    @Test
    fun `each point divides by the pair's last price at or before it`() {
        val kas = listOf(1_000L to 0.10, 2_000L to 0.20, 3_000L to 0.30)
        val pair = listOf(1_000L to 10.0, 3_000L to 20.0)
        val divided = MarketPairService.divide(kas, pair)
        assertEquals(listOf(1_000L to 0.01, 2_000L to 0.02, 3_000L to 0.015), divided)
    }

    @Test
    fun `a close before the market opened again still stands for the points after it`() {
        // Friday's close carries Saturday and Sunday: the line is then KAS's own move.
        val kas = listOf(100L to 1.0, 200L to 2.0, 300L to 4.0)
        val pair = listOf(100L to 2.0)
        val divided = MarketPairService.divide(kas, pair)
        assertEquals(listOf(100L to 0.5, 200L to 1.0, 300L to 2.0), divided)
    }

    @Test
    fun `points from before the pair has any price are dropped, not guessed`() {
        val kas = listOf(100L to 1.0, 500L to 2.0)
        val pair = listOf(400L to 4.0)
        assertEquals(listOf(500L to 0.5), MarketPairService.divide(kas, pair))
    }

    @Test
    fun `no pair prices means no series at all`() {
        assertTrue(MarketPairService.divide(listOf(1L to 1.0), emptyList()).isEmpty())
    }
}
