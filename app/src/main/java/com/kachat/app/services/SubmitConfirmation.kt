package com.kachat.app.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * The submit's answer was lost and the network can't be asked: the transaction may have gone
 * through. Never a reason to offer a resend, which could pay twice.
 */
class SendOutcomeUnknownException(val txId: String, cause: Exception) : IllegalStateException(
    "This may have gone through, but the network didn't confirm it. Check your balance before sending again.",
    cause,
)

/**
 * A send built at the swipe would pay more than the Review showed (its coins changed since):
 * nothing was sent, and the Review shows [feeSompi], the new fee (iOS d23e6f7a
 * `SendFeeChangedError`, [KaspaWalletEngine.sendKaspa]'s `expectedFeeSompi`).
 */
class SendFeeChangedException(val feeSompi: Long) : IllegalStateException("The network fee changed")

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

    /** After a lost answer, how many more times the network is asked, and how far apart. */
    const val UNKNOWN_OUTCOME_CHECKS = 6
    const val UNKNOWN_OUTCOME_CHECK_DELAY_MS = 3_000L

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
     * stays cancelled (a timeout is not a cancellation of the send). [escalateOrphan] false never
     * asks the node to hold it: for sends that move money and must not sit in a node's orphan pool
     * (iOS 4f8e14bb).
     */
    suspend fun submitWaitingForParent(
        allowOrphan: Boolean,
        delayMs: Long = PARENT_WAIT_MS,
        escalateOrphan: Boolean = true,
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
            if (escalateOrphan) {
                attempt { submit(true) }?.let { id ->
                    onHeldAsOrphan(id)
                    return id
                }
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
     * cancellation of the send). When the error is a lost answer rather than the node's verdict
     * ([isLostAnswer]), the network is asked a few more times, and if it still doesn't have the
     * transaction the outcome is reported as unknown ([SendOutcomeUnknownException]).
     *
     * [isKnown]'s second argument, `afterRejection`, is true when the error is a node's refusal
     * rather than a lost answer: then only the network's built-in REST server may say it was
     * accepted (iOS d23e6f7a `isTransactionKnown(afterRejection:)`), so a custom one set in
     * Settings can't turn a refused send into a sent one.
     */
    suspend fun submitOrConfirmKnown(
        txId: String,
        isKnown: suspend (txId: String, afterRejection: Boolean) -> Boolean,
        onRecovered: (Exception) -> Unit = {},
        checkDelayMs: Long = UNKNOWN_OUTCOME_CHECK_DELAY_MS,
        submit: suspend () -> String,
    ): String {
        try {
            return submit()
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            currentCoroutineContext().ensureActive()
            val lostAnswer = isLostAnswer(e)
            // A refusal a node also gives for a transaction the network already has isn't a verdict
            // on it, so any REST server may answer for it (iOS `rejectionMayMeanAlreadyKnown`).
            val afterRejection = !lostAnswer && !rejectionMayMeanAlreadyKnown(e.message)
            if (txId.isNotEmpty() && isKnown(txId, afterRejection)) {
                onRecovered(e)
                return txId
            }
            if (txId.isEmpty() || !lostAnswer) throw e
            repeat(UNKNOWN_OUTCOME_CHECKS) {
                delay(checkDelayMs)
                if (isKnown(txId, false)) {
                    onRecovered(e)
                    return txId
                }
            }
            throw SendOutcomeUnknownException(txId, e)
        }
    }

    /**
     * The connection failed once the transaction may already have reached a node, as opposed to the
     * node's own refusal, or a connection that never opened and so can't have sent anything.
     *
     * iOS marks an answer lost only around the request itself (`SubmitAnswerLostFlag.set()` once
     * `conn.sendRequest` was reached). The shared gRPC stream here can't tell when its request was
     * written, so the error says it instead: a gRPC status (typically UNAVAILABLE) or I/O error
     * caused by a host that couldn't be resolved, reached or connected to is a stream that never
     * opened - an offline send - and is not a lost answer, wherever it sits on the cause chain.
     */
    fun isLostAnswer(e: Throwable): Boolean {
        if (neverConnected(e)) return false
        return when (e) {
            is TimeoutCancellationException, is java.io.IOException,
            is io.grpc.StatusException, is io.grpc.StatusRuntimeException -> true
            is retrofit2.HttpException -> e.code() >= 500
            else -> false
        }
    }

    /**
     * A rejection a node also gives for a transaction the network already has (sent through
     * another node): in a mempool, accepted, its inputs spent by it, or its parents not yet seen.
     * A bad signature, a low fee or too much mass is a verdict on the transaction itself (iOS).
     */
    fun rejectionMayMeanAlreadyKnown(message: String?): Boolean {
        val lower = message?.lowercase() ?: return false
        return "already" in lower || "orphan" in lower || "double spend" in lower ||
            "spent" in lower || "missing outpoint" in lower || "mempool" in lower
    }

    /** Whether [e], or anything on its cause chain, is a failure to resolve, reach or connect to a
     *  host: nothing was sent. */
    private fun neverConnected(e: Throwable): Boolean {
        var current: Throwable? = e
        var depth = 0
        while (current != null && depth < 16) {
            if (current is java.net.UnknownHostException || current is java.net.ConnectException ||
                current is java.net.NoRouteToHostException
            ) return true
            if (current.cause === current) break
            current = current.cause
            depth++
        }
        return false
    }

    /**
     * Which of two failed attempts at the same submit to pass on (iOS `answerLost = answerLost ||
     * ...` across the attempts in NodePoolService.submitTransaction): once [first] may have
     * reached a node and lost its answer, a [later] retry failing some other way - no connection,
     * node connections blocked - doesn't make the send a failure, so [first] is passed on (with
     * [later] suppressed) and the outcome stays unknown. [later] otherwise.
     */
    fun keepLostAnswer(first: Exception, later: Exception): Exception {
        if (!isLostAnswer(first) || isLostAnswer(later)) return later
        if (first !== later) first.addSuppressed(later)
        return first
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
