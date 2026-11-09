package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** iOS 4f8e14bb's outage rule: three failures in a row at least five seconds apart. */
class IndexerHealthTest {
    private val start = 1_000_000L

    @Before
    fun reset() = IndexerHealth.resetForTest()

    private fun fail(atSeconds: Long) = IndexerHealth.recordFailure(start + atSeconds * 1000)

    @Test
    fun `three spaced failures take it down`() {
        assertFalse(fail(0))
        assertFalse(fail(5))
        assertTrue(fail(10))
        assertTrue(IndexerHealth.isDown.value)
    }

    @Test
    fun `failures within five seconds count once`() {
        fail(0)
        fail(1)
        fail(2)
        fail(3)
        assertFalse(IndexerHealth.isDown.value)
    }

    @Test
    fun `an answer breaks the run`() {
        fail(0)
        fail(5)
        assertNull(IndexerHealth.recordAnswer())
        assertFalse(fail(10))
        assertFalse(IndexerHealth.isDown.value)
    }

    @Test
    fun `failures two minutes apart are not a run`() {
        fail(0)
        fail(121)
        fail(242)
        assertFalse(IndexerHealth.isDown.value)
    }

    @Test
    fun `the answer ending an outage says when it began`() {
        fail(0)
        fail(5)
        fail(10)
        assertEquals(start + 10_000, IndexerHealth.recordAnswer())
        assertFalse(IndexerHealth.isDown.value)
        assertNull(IndexerHealth.recordAnswer())
    }

    @Test
    fun `recovery is checked once a minute while down`() {
        fail(0)
        fail(5)
        fail(10)
        assertFalse(IndexerHealth.claimRecoveryCheck(start + 30_000))
        assertTrue(IndexerHealth.claimRecoveryCheck(start + 71_000))
        assertFalse(IndexerHealth.claimRecoveryCheck(start + 72_000))
    }

    @Test
    fun `a different indexer ends the outage`() {
        IndexerHealth.indexerChanged("https://one.example")
        fail(0)
        fail(5)
        fail(10)
        IndexerHealth.indexerChanged("https://two.example")
        assertFalse(IndexerHealth.isDown.value)
    }

    @Test
    fun `no connection and odd bodies are told apart from a dead server`() {
        assertEquals(IndexerOutcome.DOWN, classifyIndexerAnswer(503, "application/json"))
        assertEquals(IndexerOutcome.DOWN, classifyIndexerAnswer(429, null))
        assertEquals(IndexerOutcome.ANSWERED, classifyIndexerAnswer(404, "text/html"))
        assertEquals(IndexerOutcome.DOWN, classifyIndexerAnswer(200, "text/html"))
        assertEquals(IndexerOutcome.ANSWERED, classifyIndexerAnswer(200, "application/json; charset=utf-8"))
        assertEquals(IndexerOutcome.ANSWERED, classifyIndexerAnswer(200, null))
    }
}
