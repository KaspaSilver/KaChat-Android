package com.kachat.app.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A submit the network accepted is never reported as failed (iOS ad29da9, audit IOS-014). */
class SubmitConfirmationTest {

    private val txId = "ab".repeat(32)

    @Test
    fun `a successful submit returns the node's id without any lookup`() = runBlocking {
        var lookups = 0
        val id = SubmitConfirmation.submitOrConfirmKnown(txId, isKnown = { _, _ -> lookups++; true }) { "node-id" }
        assertEquals("node-id", id)
        assertEquals(0, lookups)
    }

    @Test
    fun `an already-in-mempool rejection of a transaction the network has is a sent transaction`() = runBlocking {
        var recovered: Exception? = null
        val id = SubmitConfirmation.submitOrConfirmKnown(
            txId,
            isKnown = { candidate, _ -> candidate == txId },
            onRecovered = { recovered = it },
        ) { throw IllegalStateException("transaction $txId is already in the mempool") }
        assertEquals(txId, id)
        assertTrue(recovered is IllegalStateException)
    }

    @Test
    fun `a timeout followed by a successful re-check is a sent transaction`() = runBlocking {
        val id = SubmitConfirmation.submitOrConfirmKnown(txId, isKnown = { _, _ -> true }) {
            withTimeout(1) { kotlinx.coroutines.delay(1_000); "never" }
        }
        assertEquals(txId, id)
    }

    @Test
    fun `a rejection of a transaction the network does not have is still a failure`() = runBlocking {
        val error = IllegalStateException("fee too low")
        try {
            SubmitConfirmation.submitOrConfirmKnown(txId, isKnown = { _, _ -> false }) { throw error }
            fail("expected the submit's own error")
        } catch (e: IllegalStateException) {
            assertSame(error, e)
        }
    }

    @Test
    fun `without a local id there is no lookup and the error stands`() = runBlocking {
        var lookups = 0
        try {
            SubmitConfirmation.submitOrConfirmKnown("", isKnown = { _, _ -> lookups++; true }) { throw IllegalStateException("x") }
            fail("expected the error")
        } catch (e: IllegalStateException) {
            assertEquals(0, lookups)
        }
    }

    @Test
    fun `a cancelled send stays cancelled`() = runBlocking {
        var lookups = 0
        try {
            SubmitConfirmation.submitOrConfirmKnown(txId, isKnown = { _, _ -> lookups++; true }) { throw CancellationException("gone") }
            fail("expected cancellation")
        } catch (e: CancellationException) {
            assertEquals(0, lookups)
        }
    }

    @Test
    fun `the lookup asks the mempool then the REST API, twice`() = runBlocking {
        val asked = mutableListOf<String>()
        val known = SubmitConfirmation.isKnown(
            txId,
            inMempool = { asked += "mempool"; false },
            acceptedViaRest = { asked += "rest"; false },
            delayMs = 0,
        )
        assertFalse(known)
        assertEquals(listOf("mempool", "rest", "mempool", "rest"), asked)
    }

    @Test
    fun `a transaction that shows up on the second look is known`() = runBlocking {
        var looks = 0
        val known = SubmitConfirmation.isKnown(
            txId,
            inMempool = { looks++; looks > 1 },
            acceptedViaRest = { false },
            delayMs = 0,
        )
        assertTrue(known)
    }

    @Test
    fun `accepted via REST alone is known`() = runBlocking {
        assertTrue(SubmitConfirmation.isKnown(txId, inMempool = { false }, acceptedViaRest = { true }, delayMs = 0))
    }

    @Test
    fun `a lost answer the network never confirms is an unknown outcome, not a failure`() = runBlocking {
        val lost = java.io.IOException("stream closed")
        var lookups = 0
        try {
            SubmitConfirmation.submitOrConfirmKnown(txId, isKnown = { _, _ -> lookups++; false }, checkDelayMs = 0) { throw lost }
            fail("expected an unknown outcome")
        } catch (e: SendOutcomeUnknownException) {
            assertEquals(txId, e.txId)
            assertSame(lost, e.cause)
        }
        assertEquals(1 + SubmitConfirmation.UNKNOWN_OUTCOME_CHECKS, lookups)
    }

    @Test
    fun `a lost answer the network confirms on a later check is a sent transaction`() = runBlocking {
        var lookups = 0
        val id = SubmitConfirmation.submitOrConfirmKnown(txId, isKnown = { _, _ -> ++lookups == 3 }, checkDelayMs = 0) {
            throw java.io.IOException("stream closed")
        }
        assertEquals(txId, id)
    }

    @Test
    fun `a connection that never opened sent nothing, so it is a plain failure`() = runBlocking {
        val refused = java.net.ConnectException("refused")
        var lookups = 0
        try {
            SubmitConfirmation.submitOrConfirmKnown(txId, isKnown = { _, _ -> lookups++; false }, checkDelayMs = 0) { throw refused }
            fail("expected the submit's own error")
        } catch (e: java.net.ConnectException) {
            assertSame(refused, e)
        }
        assertEquals(1, lookups)
    }

    @Test
    fun `an offline gRPC submit whose host can't be resolved is a plain failure, not Not confirmed`() = runBlocking {
        val offline = io.grpc.Status.UNAVAILABLE.withCause(java.net.UnknownHostException("n.kaspa.example")).asRuntimeException()
        var lookups = 0
        try {
            SubmitConfirmation.submitOrConfirmKnown(txId, isKnown = { _, _ -> lookups++; false }, checkDelayMs = 0) { throw offline }
            fail("expected the submit's own error")
        } catch (e: io.grpc.StatusRuntimeException) {
            assertSame(offline, e)
        }
        assertEquals(1, lookups)
    }

    @Test
    fun `a gRPC status caused by a connection that never opened is not a lost answer`() {
        assertFalse(SubmitConfirmation.isLostAnswer(io.grpc.Status.UNAVAILABLE.withCause(java.net.ConnectException("refused")).asException()))
        assertFalse(SubmitConfirmation.isLostAnswer(io.grpc.Status.UNAVAILABLE.withCause(java.net.NoRouteToHostException()).asRuntimeException()))
        // deeper on the cause chain too
        val wrapped = java.io.IOException("stream failed", java.net.UnknownHostException("n.kaspa.example"))
        assertFalse(SubmitConfirmation.isLostAnswer(io.grpc.Status.UNAVAILABLE.withCause(wrapped).asRuntimeException()))
        assertFalse(SubmitConfirmation.isLostAnswer(wrapped))
    }

    @Test
    fun `a gRPC stream that died or timed out mid-request is still a lost answer`() {
        assertTrue(SubmitConfirmation.isLostAnswer(io.grpc.Status.UNAVAILABLE.withDescription("connection reset").asRuntimeException()))
        assertTrue(SubmitConfirmation.isLostAnswer(io.grpc.Status.UNAVAILABLE.withCause(java.io.IOException("reset")).asRuntimeException()))
        assertTrue(SubmitConfirmation.isLostAnswer(io.grpc.Status.DEADLINE_EXCEEDED.asException()))
        assertFalse(SubmitConfirmation.isLostAnswer(IllegalStateException("fee too low")))
    }

    @Test
    fun `a retry that can't connect keeps the first attempt's lost answer`() {
        val lost = java.io.IOException("stream closed")
        val refused = io.grpc.Status.UNAVAILABLE.withCause(java.net.ConnectException("refused")).asRuntimeException()
        val passOn = SubmitConfirmation.keepLostAnswer(lost, refused)
        assertSame(lost, passOn)
        assertTrue(passOn.suppressed.contains(refused))
    }

    @Test
    fun `a retry passes its own error on when the first attempt wasn't a lost answer, or it is one too`() {
        val refused = java.net.ConnectException("refused")
        val rejected = IllegalStateException("fee too low")
        assertSame(rejected, SubmitConfirmation.keepLostAnswer(refused, rejected))
        val secondLost = java.io.IOException("again")
        assertSame(secondLost, SubmitConfirmation.keepLostAnswer(java.io.IOException("first"), secondLost))
    }

    @Test
    fun `a lost first attempt then a failed retry ends as an unknown outcome`() = runBlocking {
        val lost = java.io.IOException("stream closed")
        try {
            SubmitConfirmation.submitOrConfirmKnown(txId, isKnown = { _, _ -> false }, checkDelayMs = 0) {
                throw SubmitConfirmation.keepLostAnswer(lost, java.net.ConnectException("refused"))
            }
            fail("expected an unknown outcome")
        } catch (e: SendOutcomeUnknownException) {
            assertSame(lost, e.cause)
        }
    }

    @Test
    fun `after a node's refusal only the built-in REST server may confirm it`() = runBlocking {
        val asked = mutableListOf<Boolean>()
        try {
            SubmitConfirmation.submitOrConfirmKnown(txId, isKnown = { _, afterRejection -> asked += afterRejection; false }, checkDelayMs = 0) {
                throw IllegalStateException("fee too low")
            }
            fail("expected the submit's own error")
        } catch (e: IllegalStateException) {
            assertEquals("fee too low", e.message)
        }
        assertEquals(listOf(true), asked)
    }

    @Test
    fun `after a lost answer every check may ask the REST server in Settings`() = runBlocking {
        val asked = mutableListOf<Boolean>()
        try {
            SubmitConfirmation.submitOrConfirmKnown(txId, isKnown = { _, afterRejection -> asked += afterRejection; false }, checkDelayMs = 0) {
                throw java.io.IOException("stream closed")
            }
            fail("expected an unknown outcome")
        } catch (e: SendOutcomeUnknownException) {
            assertEquals(txId, e.txId)
        }
        assertEquals(List(1 + SubmitConfirmation.UNKNOWN_OUTCOME_CHECKS) { false }, asked)
    }

    @Test
    fun `a changed fee carries the new fee and reads as no rejection the send retries`() {
        val changed = SendFeeChangedException(12_345L)
        assertEquals(12_345L, changed.feeSompi)
        val message = changed.message!!.lowercase()
        assertTrue(!message.contains("orphan") && !message.contains("already spent") && !message.contains("signature script"))
    }

    @Test
    fun `the unknown outcome's message can't read as a rejection the send retries`() {
        val message = SendOutcomeUnknownException(txId, java.io.IOException()).message!!.lowercase()
        assertTrue(!message.contains("orphan") && !message.contains("already spent") && !message.contains("signature script"))
    }

    @Test
    fun `a refusal that may mean already known lets any REST server answer`() = runBlocking {
        var asked: Boolean? = null
        try {
            SubmitConfirmation.submitOrConfirmKnown(txId, isKnown = { _, afterRejection -> asked = afterRejection; false }, checkDelayMs = 0) {
                throw IllegalStateException("transaction is already in the mempool")
            }
            fail("expected the refusal")
        } catch (e: IllegalStateException) {
            assertEquals(false, asked)
        }
    }

    @Test
    fun `a verdict on the transaction itself asks only the built-in REST server`() = runBlocking {
        var asked: Boolean? = null
        try {
            SubmitConfirmation.submitOrConfirmKnown(txId, isKnown = { _, afterRejection -> asked = afterRejection; false }, checkDelayMs = 0) {
                throw IllegalStateException("fee too low")
            }
            fail("expected the refusal")
        } catch (e: IllegalStateException) {
            assertEquals(true, asked)
        }
    }
}
