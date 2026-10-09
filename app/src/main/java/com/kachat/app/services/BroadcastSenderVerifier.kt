package com.kachat.app.services

import android.content.Context
import android.util.Log
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Who really sent a public-chat post (audit XP-012, Desktop a214bbf's `BroadcastSenderVerifier`).
 *
 * A `kchat:1:bcast:` post carries no signature, so its author has to come from the transaction.
 * The author is whoever signed the inputs: the address input 0 spends from. Output 0 alone proves
 * nothing - anyone can pay 0.2 KAS to someone else's address with a broadcast payload and so
 * "post as" them, and the indexer's `senderAddress` is a guess. So a post is accepted only in the
 * self-send shape every KaChat client writes: output 0 pays the SAME address input 0 spends from,
 * and that address is the sender. Anything else is dropped (logged once, never stored, shown,
 * counted or notified). When input 0's address can't be found out the post is never attributed
 * to output 0: it is retried, then dropped.
 *
 * Where input 0's address comes from, cheapest first:
 *  1. the node's own data, when it attaches the spent UTXO to the input (verbose `utxoEntry`;
 *     block-added notifications from today's nodes usually leave it out),
 *  2. the outputs of a broadcast this process already saw in the block stream (an honest poster's
 *     next post usually spends the previous post's change), and this phone's own sends,
 *  3. the Kaspa REST API: `GET /transactions/{id}` or `POST /transactions/search` with
 *     `resolve_previous_outpoints=light`, whose inputs carry `previous_outpoint_address`.
 *
 * Every path that stores public-chat rows - the live block scan, the indexer history and newest
 * page, and so the edits, reactions, mutes and chess arena built from those rows - goes through
 * here. Final verdicts (VERIFIED / FORGED) are remembered per txid; UNKNOWN never is.
 */
@Singleton
class BroadcastSenderVerifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkService: NetworkService,
) {
    enum class Verdict { VERIFIED, FORGED, UNKNOWN }

    /** [senderAddress] is set only for [Verdict.VERIFIED]. */
    data class Judged(
        val verdict: Verdict,
        val senderAddress: String? = null,
        val inputAddress: String? = null,
        val outputAddress: String? = null,
    )

    /** What the live block scan knows about one post. [inputAddress] is the node's own input-0
     *  data when it sent any; [inputOutpoint] is input 0's (transactionId, index). */
    data class LiveHit(
        val txId: String,
        val channel: String,
        val outputAddress: String?,
        val inputAddress: String?,
        val inputOutpoint: Pair<String, Int>?,
    )

    private val verdicts = BoundedMap<String, Judged>(MAX_REMEMBERED)
    private val outputs = BoundedMap<String, List<String?>>(MAX_REMEMBERED)
    private val rowBackoff = BoundedMap<String, Pair<Int, Long>>(MAX_REMEMBERED)
    private val droppedLogged = BoundedMap<String, Boolean>(MAX_REMEMBERED)
    private val inflight = ConcurrentHashMap<String, CompletableDeferred<Judged>>()

    /** The final verdict for [txId], or null when there is none yet. */
    fun verdictFor(txId: String): Judged? = verdicts[txId.lowercase()]

    private fun remember(txId: String, judged: Judged) {
        if (judged.verdict == Verdict.UNKNOWN) return
        val key = txId.lowercase()
        verdicts[key] = judged
        rowBackoff.remove(key)
    }

    /** A post this phone signed and sent: its sender is known without asking anyone. */
    fun rememberOwnBroadcast(txId: String, address: String) {
        val sender = normalize(address) ?: return
        remember(txId, Judged(Verdict.VERIFIED, sender, sender, sender))
    }

    /** The outputs (address per index) of a broadcast seen in the block stream, so a later post
     *  spending one of them resolves its input 0 without a lookup. */
    fun rememberOutputs(txId: String, addresses: List<String?>) {
        if (addresses.isEmpty()) return
        outputs[txId.lowercase()] = addresses.map { normalize(it) }
    }

    private fun outpointAddress(transactionId: String, index: Int): String? =
        outputs[transactionId.lowercase()]?.getOrNull(index)

    /** Logs a dropped post once per txid. */
    fun logDropped(txId: String, channel: String, judged: Judged) {
        val key = txId.lowercase()
        if (droppedLogged[key] != null) return
        droppedLogged[key] = true
        val reason = if (judged.verdict == Verdict.FORGED) {
            "output 0 pays ${judged.outputAddress} but input 0 spends from ${judged.inputAddress} " +
                "(not a self-send, possible impersonation)"
        } else {
            "the address input 0 spends from could not be found out, so the sender is unverified"
        }
        Log.w(TAG, "Public chat: dropped a #$channel post, tx $txId: $reason.")
    }

    /** Verdict for a live hit from what is already known here - no network. */
    fun settleLive(hit: LiveHit): Judged {
        verdictFor(hit.txId)?.let { return it }
        var judged = judge(hit.inputAddress, hit.outputAddress)
        if (judged.verdict == Verdict.UNKNOWN && hit.inputOutpoint != null) {
            val local = outpointAddress(hit.inputOutpoint.first, hit.inputOutpoint.second)
            if (local != null) judged = judge(local, hit.outputAddress)
        }
        remember(hit.txId, judged)
        return judged
    }

    /**
     * Final verdict for a live hit, asking the REST API with retries (~95 s in all) when nothing
     * local settles it; UNKNOWN after the last attempt, and the caller drops the post. Suspends,
     * so callers launch it off the block loop. One verification per txid at a time: the same
     * transaction arrives once per DAG block that carries it.
     */
    suspend fun verifyLive(hit: LiveHit): Judged {
        val settled = settleLive(hit)
        if (settled.verdict != Verdict.UNKNOWN) return settled
        val key = hit.txId.lowercase()
        val mine = CompletableDeferred<Judged>()
        val running = inflight.putIfAbsent(key, mine)
        if (running != null) return running.await()
        var result = Judged(Verdict.UNKNOWN)
        try {
            for (wait in LIVE_VERIFY_DELAYS_MS) {
                delay(wait)
                val local = settleLive(hit)
                if (local.verdict != Verdict.UNKNOWN) { result = local; break }
                val shape = lookupShapes(listOf(key))?.shapes?.get(key) ?: continue
                // Output 0 as the node streamed it when readable, else as the REST API has it.
                val judged = judge(shape.inputAddress, hit.outputAddress ?: shape.outputAddress)
                if (judged.verdict != Verdict.UNKNOWN) {
                    remember(key, judged)
                    result = judged
                    break
                }
            }
        } finally {
            inflight.remove(key)
            mine.complete(result)
        }
        return result
    }

    /**
     * Verdicts for rows read from an indexer, keyed by lowercase txid. The indexer's own
     * `senderAddress` is ignored: only a VERIFIED verdict's sender may be stored. UNKNOWN rows
     * (the REST API had no answer yet) back off and are asked again on a later call.
     */
    suspend fun verifyRows(txIds: Collection<String>, channel: String): RowVerdicts {
        val out = HashMap<String, Judged>()
        val ask = ArrayList<String>()
        val now = System.currentTimeMillis()
        for (raw in txIds) {
            val key = raw.lowercase()
            if (!TX_ID.matches(key)) continue
            val cached = verdictFor(key)
            if (cached != null) { out[key] = cached; continue }
            val backoff = rowBackoff[key]
            if (backoff != null && backoff.second > now) { out[key] = Judged(Verdict.UNKNOWN); continue }
            ask += key
        }
        var lookupFailed = false
        val unanswered = out.filterValues { it.verdict == Verdict.UNKNOWN }.keys.toMutableSet()
        if (ask.isNotEmpty()) {
            val lookup = lookupShapes(ask)
            if (lookup == null) lookupFailed = true
            for (key in ask) {
                if (lookup == null || key !in lookup.answered) unanswered += key
                val shape = lookup?.shapes?.get(key)
                val judged = if (shape == null) Judged(Verdict.UNKNOWN) else judge(shape.inputAddress, shape.outputAddress)
                when (judged.verdict) {
                    Verdict.VERIFIED -> remember(key, judged)
                    Verdict.FORGED -> { remember(key, judged); logDropped(key, channel, judged) }
                    Verdict.UNKNOWN -> {
                        val attempts = (rowBackoff[key]?.first ?: 0) + 1
                        val wait = minOf(60_000L, 1_000L shl minOf(attempts - 1, 16))
                        rowBackoff[key] = attempts to (now + wait)
                    }
                }
                out[key] = judged
            }
        }
        return RowVerdicts(out, lookupFailed, unanswered)
    }

    /**
     * [lookupFailed]: the REST API could not be reached at all. [unanswered]: txids left UNKNOWN
     * without an answer from it (still backing off, or their lookup failed) - as opposed to ones
     * it answered for and does not know.
     */
    data class RowVerdicts(
        val byTxId: Map<String, Judged>,
        val lookupFailed: Boolean,
        val unanswered: Set<String> = emptySet(),
    ) {
        operator fun get(txId: String): Judged = byTxId[txId.lowercase()] ?: Judged(Verdict.UNKNOWN)
        fun wasAnswered(txId: String): Boolean = txId.lowercase() !in unanswered
    }

    // MARK: - The one-time re-check of rows cached before verification existed

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    /** Which attempt the cached-row re-check is on (0 first), or null once it is done. */
    fun cachedReverifyAttempt(): Int? =
        if (prefs.getBoolean(KEY_REVERIFY_DONE, false)) null else prefs.getInt(KEY_REVERIFY_ATTEMPTS, 0)

    fun recordCachedReverify(complete: Boolean) {
        if (complete) {
            prefs.edit().putBoolean(KEY_REVERIFY_DONE, true).apply()
        } else {
            prefs.edit().putInt(KEY_REVERIFY_ATTEMPTS, prefs.getInt(KEY_REVERIFY_ATTEMPTS, 0) + 1).apply()
        }
    }

    /** Waits up to [timeoutMs] for the REST client to be configured; false if it never was. */
    suspend fun awaitRestApi(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { networkService.kaspaRestApi.filterNotNull().first() } != null

    private data class Shape(val inputAddress: String?, val outputAddress: String?)

    /**
     * Input 0's previous-outpoint address and output 0's address per txid from the Kaspa REST
     * API. One id: GET; several: POST /transactions/search in batches, falling back to one GET
     * each if the search endpoint refuses. A txid the API doesn't know is simply absent. Null when
     * the API answered for none of them (no REST client, or every request failed).
     */
    private suspend fun lookupShapes(txIds: List<String>): Lookup? {
        val api = networkService.kaspaRestApi.value ?: return null
        val shapes = HashMap<String, Shape>()
        val answered = HashSet<String>()
        var unreachable = false
        fun put(tx: SenderShapeTransaction) {
            val id = tx.transactionId?.lowercase() ?: return
            shapes[id] = shapeOf(tx)
        }
        suspend fun getOne(id: String) {
            if (unreachable) return
            try {
                val tx = withTimeoutOrNull(REST_TIMEOUT_MS) { api.getTransactionSenderShape(id) }
                if (tx != null) { put(tx); answered += id } else unreachable = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: retrofit2.HttpException) {
                // 404: the API doesn't know it (yet) - an answer, not an outage.
                if (e.code() == 404) answered += id
            } catch (e: Exception) {
                // Network trouble: stop asking this round rather than time out once per id.
                unreachable = true
            }
        }
        if (txIds.size == 1) {
            getOne(txIds[0])
        } else {
            for (batch in txIds.chunked(REST_BATCH)) {
                if (unreachable) break
                val list = try {
                    withTimeoutOrNull(REST_TIMEOUT_MS) { api.searchTransactionSenderShapes(TransactionSearchRequest(batch)) }
                        .also { if (it == null) unreachable = true }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: retrofit2.HttpException) {
                    null // the search endpoint refused: one GET each below
                } catch (e: Exception) {
                    unreachable = true
                    null
                }
                if (list != null) {
                    list.forEach { put(it) }
                    answered += batch // a txid missing from the answer is one the API doesn't know
                } else {
                    for (id in batch) getOne(id)
                }
            }
        }
        return if (answered.isEmpty()) null else Lookup(shapes, answered)
    }

    /** [answered]: txids the REST API answered for, found ([shapes]) or not. */
    private class Lookup(val shapes: Map<String, Shape>, val answered: Set<String>)

    companion object {
        private const val TAG = "BroadcastSender"
        private const val PREFS_NAME = "broadcast_sender_verifier"
        private const val KEY_REVERIFY_DONE = "cached_rows_reverified_v1"
        private const val KEY_REVERIFY_ATTEMPTS = "cached_rows_reverify_attempts_v1"

        /** Wait before each REST attempt for a live post (~95 s in all): the REST API indexes a
         *  block a moment after the node streams it, so even the first look waits. */
        private val LIVE_VERIFY_DELAYS_MS = listOf(1_500L, 3_000L, 5_000L, 10_000L, 25_000L, 50_000L)
        private const val REST_TIMEOUT_MS = 12_000L
        private const val REST_BATCH = 100
        private const val MAX_REMEMBERED = 20_000
        private val TX_ID = Regex("^[0-9a-f]{64}$")

        /** A valid Kaspa address in this network's encoding, or null. */
        fun normalize(address: String?): String? {
            val trimmed = address?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            if (!KaspaAddress.isValid(trimmed)) return null
            return KaspaNetwork.reencode(trimmed)
        }

        /**
         * The one acceptance rule: VERIFIED only when input 0's and output 0's addresses are both
         * known and equal (the sender is that address); FORGED when they differ; UNKNOWN otherwise.
         */
        fun judge(inputAddress: String?, outputAddress: String?): Judged {
            val input = normalize(inputAddress)
            val output = normalize(outputAddress)
            if (input == null || output == null) return Judged(Verdict.UNKNOWN, null, input, output)
            if (input != output) return Judged(Verdict.FORGED, null, input, output)
            return Judged(Verdict.VERIFIED, input, input, output)
        }

        private fun shapeOf(tx: SenderShapeTransaction): Shape {
            val input = tx.inputs.orEmpty().let { list -> list.firstOrNull { it.index == 0 } ?: list.firstOrNull()?.takeIf { it.index == null } }
            val output = tx.outputs.orEmpty().let { list -> list.firstOrNull { it.index == 0 } ?: list.firstOrNull()?.takeIf { it.index == null } }
            return Shape(input?.previousOutpointAddress, output?.scriptPublicKeyAddress)
        }
    }

    /** A small thread-safe LRU map: the oldest entry goes once [max] is passed. */
    private class BoundedMap<K, V>(private val max: Int) {
        private val map = object : LinkedHashMap<K, V>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > max
        }
        @Synchronized operator fun get(key: K): V? = map[key]
        @Synchronized operator fun set(key: K, value: V) { map[key] = value }
        @Synchronized fun remove(key: K) { map.remove(key) }
    }
}
