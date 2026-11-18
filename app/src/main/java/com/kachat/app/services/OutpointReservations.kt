package com.kachat.app.services

/**
 * Every coin a transaction from this app spent, or is spending right now, from any address and
 * any send path - chat sends, payments, KNS inscriptions, `.kachat` names - keyed by lowercased
 * outpoint, with when it may be picked again. Every coin pick leaves these out until then
 * (iOS `ChatService.reservedMessageOutpoints`, `unreservedUtxos`).
 *
 * Shared by [KaspaWalletEngine] (its picks and its record of what a send spent) and
 * [NodePoolManager] (which holds a transaction's coins from before its submit, iOS 83806b3d
 * `holdOutpointsForSubmit`). One store for the whole process, as iOS's ChatService is.
 *
 * A spent coin stays out for [ttlMs] (10 minutes, iOS 83806b3d `reservedOutpointTTL`): a
 * transaction can wait in the mempool for minutes, and offering its coin again makes the next
 * send a rejected double spend.
 */
internal class OutpointReservations(
    private val ttlMs: Long = TTL_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Lowercased outpoint key -> until when (epoch millis) it stays out of every pick. */
    private val until = HashMap<String, Long>()

    /** What a [hold] changed: each key and what it held before (null: nothing), for [release]. */
    class Hold internal constructor(internal val entries: List<Pair<String, Long?>>)

    /** Whether [outpoint] was spent, or may have been, by a transaction from this app and is
     *  still kept out of picks. */
    @Synchronized
    fun isReserved(outpoint: Outpoint): Boolean {
        val expiry = until[key(outpoint)] ?: return false
        return expiry > now()
    }

    /** Keeps [outpoints] out of every pick for [ttlMs] from now: the inputs of a transaction that
     *  was sent. A coin already held for longer keeps its own time. */
    @Synchronized
    fun reserve(outpoints: List<Outpoint>) {
        if (outpoints.isEmpty()) return
        val current = now()
        until.entries.removeAll { it.value <= current }
        val expiry = current + ttlMs
        outpoints.forEach { outpoint ->
            val k = key(outpoint)
            val existing = until[k]
            if (existing == null || existing < expiry) until[k] = expiry
        }
    }

    /**
     * Called just BEFORE a submit: the transaction's coins leave every pick while its submit runs,
     * so a send running beside it (a name action, a payment) can't build on the same coins. A coin
     * already held at least as long is left as it is. [release] undoes it.
     */
    @Synchronized
    fun hold(outpoints: List<Outpoint>): Hold {
        val expiry = now() + ttlMs
        val held = ArrayList<Pair<String, Long?>>()
        val seen = HashSet<String>()
        for (outpoint in outpoints) {
            val k = key(outpoint)
            if (!seen.add(k)) continue
            val previous = until[k]
            if (previous != null && previous >= expiry) continue
            held += k to previous
            until[k] = expiry
        }
        return Hold(held)
    }

    /** A submit that failed, or whose outcome is unknown: its coins go back to what they were, so
     *  a resend picks the same ones and at most one of the two can land (iOS `releaseSubmitHold`). */
    @Synchronized
    fun release(hold: Hold) {
        for ((k, previous) in hold.entries) {
            if (previous != null) until[k] = previous else until.remove(k)
        }
    }

    companion object {
        /** iOS `reservedOutpointTTL`: 600 s. */
        const val TTL_MS = 10 * 60 * 1000L

        /** The app's one store (iOS's `ChatService.shared`). */
        val shared = OutpointReservations()

        /** The node and REST spell a transaction id in either case. */
        private fun key(outpoint: Outpoint) = "${outpoint.transactionId}:${outpoint.index}".lowercase()
    }
}
