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
