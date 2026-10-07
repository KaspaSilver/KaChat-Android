package com.kachat.app.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A send rejected as an orphan waits for its parent instead of failing (iOS 4eb492f): 1.5 s,
 * one more plain submit, then the node is asked to hold it as an orphan.
 */
class SubmitWaitingForParentTest {

    private val orphan = IllegalStateException("transaction ab12 is an orphan where orphan is disallowed")

    /** Records each submit's allowOrphan and answers from [answers] in turn. */
    private class Node(vararg val answers: () -> String) {
        val calls = mutableListOf<Boolean>()
        suspend fun submit(allowOrphan: Boolean): String {
            calls += allowOrphan
            return answers[calls.size - 1]()
        }
    }

    @Test
    fun `a submit that goes through is sent once`() = runBlocking {
        val node = Node({ "tx" })
        assertEquals("tx", SubmitConfirmation.submitWaitingForParent(false, delayMs = 0, submit = node::submit))
        assertEquals(listOf(false), node.calls)
    }

    @Test
    fun `an orphan rejection waits and submits once more`() = runBlocking {
        val node = Node({ throw orphan }, { "tx" })
        var held: String? = null
        val id = SubmitConfirmation.submitWaitingForParent(false, delayMs = 0, onHeldAsOrphan = { held = it }, submit = node::submit)
        assertEquals("tx", id)
        assertEquals(listOf(false, false), node.calls)
        assertNull(held)
    }

    @Test
    fun `a second orphan rejection lets the node hold it as an orphan`() = runBlocking {
        val node = Node({ throw orphan }, { throw orphan }, { "tx" })
        var held: String? = null
        val id = SubmitConfirmation.submitWaitingForParent(false, delayMs = 0, onHeldAsOrphan = { held = it }, submit = node::submit)
        assertEquals("tx", id)
        assertEquals(listOf(false, false, true), node.calls)
        assertEquals("tx", held)
    }

    @Test
    fun `when nothing goes through the first rejection is thrown`() = runBlocking {
        val node = Node({ throw orphan }, { throw IllegalStateException("timeout") }, { throw IllegalStateException("fee too low") })
        try {
            SubmitConfirmation.submitWaitingForParent(false, delayMs = 0, submit = node::submit)
            fail("expected the orphan rejection")
        } catch (e: IllegalStateException) {
            assertSame(orphan, e)
        }
        assertEquals(listOf(false, false, true), node.calls)
    }

    @Test
    fun `any other rejection is thrown at once`() = runBlocking {
        val error = IllegalStateException("transaction ab12 is already in the mempool")
        val node = Node({ throw error })
        try {
            SubmitConfirmation.submitWaitingForParent(false, delayMs = 0, submit = node::submit)
            fail("expected the rejection")
        } catch (e: IllegalStateException) {
            assertSame(error, e)
        }
        assertEquals(listOf(false), node.calls)
    }

    @Test
    fun `a submit that already allowed orphans is not retried`() = runBlocking {
        val node = Node({ throw orphan })
        try {
            SubmitConfirmation.submitWaitingForParent(true, delayMs = 0, submit = node::submit)
            fail("expected the rejection")
        } catch (e: IllegalStateException) {
            assertSame(orphan, e)
        }
        assertEquals(listOf(true), node.calls)
    }

    @Test
    fun `a timed out retry counts as refused, not as a cancelled send`() = runBlocking {
        val node = Node({ throw orphan }, { runBlocking { withTimeout(1) { kotlinx.coroutines.delay(1_000); "never" } } }, { "tx" })
        assertEquals("tx", SubmitConfirmation.submitWaitingForParent(false, delayMs = 0, submit = node::submit))
        assertEquals(listOf(false, false, true), node.calls)
    }

    @Test
    fun `a cancelled send stays cancelled`() = runBlocking {
        val node = Node({ throw CancellationException("closed") })
        try {
            SubmitConfirmation.submitWaitingForParent(false, delayMs = 0, submit = node::submit)
            fail("expected the cancellation")
        } catch (e: CancellationException) {
            assertEquals("closed", e.message)
        }
        assertEquals(listOf(false), node.calls)
    }

    @Test
    fun `the orphan rejection is recognised in any case`() {
        assertTrue(SubmitConfirmation.isOrphanRejection(IllegalStateException("Transaction X is an ORPHAN where orphan is disallowed")))
        assertTrue(!SubmitConfirmation.isOrphanRejection(IllegalStateException("fee too low")))
    }
}
