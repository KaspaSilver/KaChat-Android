package com.kachat.app.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * A failed submit is not always a failed send (iOS ad29da9, audit IOS-014).
 *
 * A node can accept a transaction while its answer times out; the retry that follows (a fresh
 * connection, an allowOrphan resubmit, the REST gateway) then gets "already in the mempool",
 * "already accepted", an orphan or a double-spend verdict for the very same transaction.
 * Reporting that as a failure invites a resend that pays twice. So before a submit error is
 * passed on, the transaction's own id - computed locally, before anything was sent - is looked
 * up: found in a mempool or accepted, the send succeeded.
 *
 * Pure apart from the lookups it is handed, so the decision is unit-tested.
 */
object SubmitConfirmation {

    /** Between the two lookups: a just-accepted transaction may not have propagated yet. */
    const val RECHECK_DELAY_MS = 1_500L

    /** How long a send rejected as an orphan gives its parent to reach the node (iOS 4eb492f). */
    const val PARENT_WAIT_MS = 1_500L

    /** Whether a submit error is the node's "orphan where orphan is disallowed" (iOS
     *  `NodePoolService.isOrphanRejection`). */
    fun isOrphanRejection(e: Throwable): Boolean = (e.message ?: e.toString()).lowercase().contains("orphan")

    /**
     * Runs [submit] with [allowOrphan] (iOS 4eb492f, NodePoolService.submitTransaction). A
     * rejection as an orphan "where orphan is disallowed" means the transaction's inputs came from
     * a node that already has their parent transaction (the UTXO query), but the node this submit
     * reached hasn't caught up yet - a reaction or message sent right after the previous one. Then
     * the parent gets [delayMs] to propagate and the submit runs once more; if that is refused too,
     * the node is asked to hold it as an orphan until the parent arrives ([onHeldAsOrphan] is told
     * the id). When neither goes through, the first rejection is what is thrown, as before. Any
     * other error, or a submit that already allowed orphans, is thrown at once. A cancelled send
     * stays cancelled (a timeout is not a cancellation of the send).
     */
    suspend fun submitWaitingForParent(
        allowOrphan: Boolean,
        delayMs: Long = PARENT_WAIT_MS,
        onHeldAsOrphan: (String) -> Unit = {},
        submit: suspend (allowOrphan: Boolean) -> String,
    ): String {
        try {
            return submit(allowOrphan)
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            if (allowOrphan || !isOrphanRejection(e)) throw e
            delay(delayMs)
            attempt { submit(false) }?.let { return it }
            attempt { submit(true) }?.let { id ->
                onHeldAsOrphan(id)
                return id
            }
            throw e
        }
    }

    /** [block]'s id, or null when it failed (iOS `try?`); a cancelled send stays cancelled. */
    private suspend fun attempt(block: suspend () -> String): String? = try {
        block()
    } catch (e: Exception) {
        if (e is CancellationException && e !is TimeoutCancellationException) throw e
        currentCoroutineContext().ensureActive()
        null
    }

    /**
     * Runs [submit]. On any error, [txId] is looked up with [isKnown]; if the network has it,
     * [txId] is returned as the sent transaction's id and [onRecovered] is told why, otherwise
     * the submit's own error is thrown. A cancelled send stays cancelled (a timeout is not a
     * cancellation of the send).
     */
    suspend fun submitOrConfirmKnown(
        txId: String,
        isKnown: suspend (String) -> Boolean,
        onRecovered: (Exception) -> Unit = {},
        submit: suspend () -> String,
    ): String {
        try {
            return submit()
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            currentCoroutineContext().ensureActive()
            if (txId.isNotEmpty() && isKnown(txId)) {
                onRecovered(e)
                return txId
            }
            throw e
        }
    }

    /**
     * Whether the network has [txId]: in some node's mempool ([inMempool]), or accepted
     * ([acceptedViaRest]). Asked twice, [delayMs] apart.
     */
    suspend fun isKnown(
        txId: String,
        inMempool: suspend (String) -> Boolean,
        acceptedViaRest: suspend (String) -> Boolean,
        delayMs: Long = RECHECK_DELAY_MS,
    ): Boolean {
        for (attempt in 0 until 2) {
            if (attempt > 0) delay(delayMs)
            if (inMempool(txId)) return true
            if (acceptedViaRest(txId)) return true
        }
        return false
    }
}
