package com.kachat.app.services

import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.kachat.app.util.KaspaSignatureVerifier
import com.kachat.app.util.MessageProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.Response
import retrofit2.HttpException
import java.io.IOException

// -------------------------------------------------------------------------
// With the indexer down (iOS 4f8e14bb, KasiaAPIClient "When the indexer is down"). The indexer
// client reports every answer to [IndexerHealth]; history reads answer from the address's history
// on the Kaspa REST API, in the indexer's own shapes, so callers work the same either way.
// -------------------------------------------------------------------------

internal enum class IndexerOutcome { ANSWERED, DOWN }

/** One HTTP answer from the indexer. A body it couldn't decode is still the indexer answering: one
 *  odd row isn't an outage. Anything but JSON on a success is something else answering for it. */
internal fun classifyIndexerAnswer(code: Int, contentType: String?): IndexerOutcome {
    if (code == 429 || code >= 500) return IndexerOutcome.DOWN
    if (code in 200..299 && !contentType.isNullOrEmpty() &&
        !contentType.contains("application/json") && !contentType.contains("text/json")
    ) return IndexerOutcome.DOWN
    return IndexerOutcome.ANSWERED
}

/**
 * Every indexer request reports here (iOS `noteIndexerResult`). A cancelled call says nothing, and
 * a weak signal looks like a dead server, so only an online phone's failures count.
 */
class IndexerHealthInterceptor(private val isOnline: () -> Boolean) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = try {
            chain.proceed(chain.request())
        } catch (e: IOException) {
            if (!chain.call().isCanceled() && isOnline()) IndexerHealth.reportFailure()
            throw e
        }
        val contentType = response.header("Content-Type")
        when (classifyIndexerAnswer(response.code, contentType)) {
            IndexerOutcome.ANSWERED -> IndexerHealth.reportSuccess()
            IndexerOutcome.DOWN -> {
                if (isOnline()) IndexerHealth.reportFailure()
                if (response.isSuccessful) {
                    response.close()
                    throw IOException("Unexpected Content-Type: $contentType")
                }
            }
        }
        return response
    }
}

/**
 * The indexer's answer or, once it's confirmed down, the same thing read from the chain's history
 * (iOS `fromIndexerOrChain`). A one-off failure with the indexer up is thrown as it always was,
 * for the caller's own retry to ask the indexer again. Everything not overridden here (the group
 * reads) goes straight to the indexer.
 */
internal class ResilientIndexerApi(
    private val indexer: KasiaIndexerApi,
    private val chain: ChainHistoryReader,
    private val scope: CoroutineScope,
) : KasiaIndexerApi by indexer {

    /** True while it's down. Once a minute a single cheap request goes out to find out whether
     *  it's back, so an outage never costs a real sync run against a dead server. */
    private fun shouldSkipIndexer(): Boolean {
        if (!IndexerHealth.isDown.value) return false
        if (IndexerHealth.claimRecoveryCheck()) {
            scope.launch {
                try {
                    val response = indexer.getMetrics()
                    response.body()?.close()
                    response.errorBody()?.close()
                } catch (_: Exception) {
                    // the interceptor has already counted it
                }
            }
        }
        return true
    }

    private fun isIndexerFailure(e: Exception): Boolean = when (e) {
        is CancellationException -> false
        is IOException -> true
        is HttpException -> classifyIndexerAnswer(e.code(), null) == IndexerOutcome.DOWN
        else -> false
    }

    private suspend fun <T> fromIndexerOrChain(fromIndexer: suspend () -> T, fromChain: suspend () -> T): T {
        if (shouldSkipIndexer()) return fromChain()
        return try {
            fromIndexer()
        } catch (e: Exception) {
            if (!isIndexerFailure(e) || !IndexerHealth.isDown.value) throw e
            fromChain()
        }
    }

    override suspend fun getHandshakesByReceiver(address: String, limit: Int, blockTime: Long?): List<HandshakeIndexerResponse> =
        fromIndexerOrChain({ indexer.getHandshakesByReceiver(address, limit, blockTime) }) {
            chain.handshakes(address, sent = false, since = blockTime ?: 0L)
        }

    override suspend fun getHandshakesBySender(address: String, limit: Int, blockTime: Long?): List<HandshakeIndexerResponse> =
        fromIndexerOrChain({ indexer.getHandshakesBySender(address, limit, blockTime) }) {
            chain.handshakes(address, sent = true, since = blockTime ?: 0L)
        }

    override suspend fun getContextualMessagesBySender(
        address: String,
        aliasHex: String,
        limit: Int,
        blockTime: Long?,
    ): List<ContextualMessageIndexerResponse> =
        fromIndexerOrChain({ indexer.getContextualMessagesBySender(address, aliasHex, limit, blockTime) }) {
            chain.contextualMessages(address, ChainHistoryRows.textFromHex(aliasHex), since = blockTime ?: 0L)
        }

    override suspend fun getSelfStashByOwner(owner: String, scopeHex: String, limit: Int, blockTime: Long): List<SelfStashIndexerResponse> =
        fromIndexerOrChain({ indexer.getSelfStashByOwner(owner, scopeHex, limit, blockTime) }) {
            chain.selfStash(owner, ChainHistoryRows.textFromHex(scopeHex), since = blockTime)
        }

    /** Only the indexer can look a tag up. While it's down, new ones arrive from the block stream
     *  instead ([com.kachat.app.repository.ChatRepository]'s inbox scan). */
    override suspend fun getContextualMessagesByInbox(tag: String, limit: Int, blockTime: Long?): List<ContextualMessageIndexerResponse> {
        if (shouldSkipIndexer()) throw IOException(NOT_ANSWERING)
        return indexer.getContextualMessagesByInbox(tag, limit, blockTime)
    }

    override suspend fun probeContextualMessagesByInbox(tag: String, limit: Int): retrofit2.Response<okhttp3.ResponseBody> {
        if (shouldSkipIndexer()) throw IOException(NOT_ANSWERING)
        return indexer.probeContextualMessagesByInbox(tag, limit)
    }

    /** The indexer's own row or nothing, never the chain's: this check exists to be a second
     *  source the REST server can't speak for. An outage leaves the payment held. */
    override suspend fun getPaymentsByReceiver(address: String, limit: Int, blockTime: Long): List<PaymentIndexerResponse> {
        if (shouldSkipIndexer()) throw IOException(NOT_ANSWERING)
        return indexer.getPaymentsByReceiver(address, limit, blockTime)
    }

    private companion object {
        const val NOT_ANSWERING = "The indexer isn't answering"
    }
}

/** One transaction from an address's history on the Kaspa REST API (iOS `ChainHistoryTx`). */
data class ChainHistoryTx(
    val txId: String,
    val blockTime: Long,
    val payloadHex: String,
    /** The addresses whose coins it spent, lowercased (the REST API's word for it). */
    val senders: Set<String>,
    val outputs: List<Output>,
) {
    data class Output(val address: String, val amount: Long)
}

/** Whether a transaction's input signature proves who wrote it (iOS `SenderProof`). */
enum class SenderProof { PROVEN, FORGED, UNKNOWN }

/** The REST rows and the indexer's shapes, without any network (iOS `parseChainRows`, `chain*`). */
internal object ChainHistoryRows {
    private val HANDSHAKE_PREFIXES = listOf("kchat:1:handshake:", "ciph_msg:1:handshake:").map { hex(it) }

    fun hex(text: String): String =
        text.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

    fun textFromHex(hexText: String): String =
        bytesFromHex(hexText)?.let { String(it, Charsets.UTF_8) }.orEmpty()

    private fun bytesFromHex(hexText: String): ByteArray? {
        if (hexText.length % 2 != 0) return null
        val out = ByteArray(hexText.length / 2)
        for (i in out.indices) {
            val high = Character.digit(hexText[i * 2], 16)
            val low = Character.digit(hexText[i * 2 + 1], 16)
            if (high < 0 || low < 0) return null
            out[i] = ((high shl 4) or low).toByte()
        }
        return out
    }

    private fun number(value: JsonElement?): Long? =
        value?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }

    private fun text(value: JsonElement?): String? =
        value?.takeIf { it.isJsonPrimitive }?.asString

    private fun objects(value: JsonElement?): List<JsonObject> =
        value?.takeIf { it.isJsonArray }?.asJsonArray?.filter { it.isJsonObject }?.map { it.asJsonObject }.orEmpty()

    /** A rejected transaction, or a row without an id or a block time, isn't history. */
    fun parse(rows: List<JsonObject>): List<ChainHistoryTx> = rows.mapNotNull { row ->
        val accepted = row.get("is_accepted")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
        if (accepted == false) return@mapNotNull null
        val txId = text(row.get("transaction_id")) ?: return@mapNotNull null
        val blockTime = number(row.get("block_time")) ?: return@mapNotNull null
        ChainHistoryTx(
            txId = txId,
            blockTime = blockTime,
            payloadHex = text(row.get("payload"))?.lowercase().orEmpty(),
            senders = objects(row.get("inputs")).mapNotNull { text(it.get("previous_outpoint_address"))?.lowercase() }.toSet(),
            outputs = objects(row.get("outputs")).mapNotNull { output ->
                val address = text(output.get("script_public_key_address"))?.lowercase() ?: return@mapNotNull null
                val amount = number(output.get("amount")) ?: return@mapNotNull null
                ChainHistoryTx.Output(address, amount)
            },
        )
    }

    /** The indexer's form: the encrypted hex, with no protocol prefix. */
    fun handshakes(transactions: List<ChainHistoryTx>, address: String, sent: Boolean): List<HandshakeIndexerResponse> {
        val me = address.lowercase()
        return transactions.mapNotNull { tx ->
            val prefix = HANDSHAKE_PREFIXES.firstOrNull { tx.payloadHex.startsWith(it) } ?: return@mapNotNull null
            if (tx.senders.contains(me) != sent) return@mapNotNull null
            val sender = if (sent) address else tx.senders.minOrNull().orEmpty()
            val receiver = if (sent) tx.outputs.firstOrNull { it.address != me }?.address.orEmpty() else address
            if (sender.isEmpty() || receiver.isEmpty()) return@mapNotNull null
            HandshakeIndexerResponse(
                txId = tx.txId,
                sender = sender,
                receiver = receiver,
                blockTime = tx.blockTime,
                messagePayload = tx.payloadHex.removePrefix(prefix),
            )
        }
    }

    /** `kchat:1:comm:ALIAS:BASE64` becomes the indexer's form, hex(BASE64). Only what [address]
     *  itself spent into: a message is a transaction from its author to themselves. */
    fun contextualMessages(transactions: List<ChainHistoryTx>, address: String, alias: String): List<ContextualMessageIndexerResponse> {
        val me = address.lowercase()
        return transactions.mapNotNull { tx ->
            if (!tx.senders.contains(me) || tx.payloadHex.isEmpty()) return@mapNotNull null
            val bytes = bytesFromHex(tx.payloadHex) ?: return@mapNotNull null
            val normalized = MessageProtocol.normalizeFirstContact(String(bytes, Charsets.UTF_8))
            if (!normalized.startsWith("kchat:1:comm:") && !normalized.startsWith("ciph_msg:1:comm:")) return@mapNotNull null
            val parts = normalized.split(":", limit = 5)
            if (parts.size != 5 || parts[3] != alias || parts[4].isEmpty()) return@mapNotNull null
            ContextualMessageIndexerResponse(
                txId = tx.txId,
                sender = address,
                alias = hex(alias),
                blockTime = tx.blockTime,
                messagePayload = hex(parts[4]),
            )
        }
    }

    fun selfStash(transactions: List<ChainHistoryTx>, owner: String, scope: String): List<SelfStashIndexerResponse> {
        val me = owner.lowercase()
        val prefixes = listOf("kchat:1:self_stash:", "ciph_msg:1:self_stash:").map { hex("$it$scope:") }
        return transactions.mapNotNull { tx ->
            if (!tx.senders.contains(me)) return@mapNotNull null
            val prefix = prefixes.firstOrNull { tx.payloadHex.startsWith(it) } ?: return@mapNotNull null
            SelfStashIndexerResponse(
                txId = tx.txId,
                owner = owner,
                scope = scope,
                blockTime = tx.blockTime,
                stashedData = tx.payloadHex.removePrefix(prefix),
            )
        }
    }
}

/**
 * Reads an address's history from the Kaspa REST API, since a node keeps no history by address
 * (iOS `chainTransactions` and `ChainHistoryCache`). Shared for a moment, as one sync asks about
 * the same address several times over, and paced across the whole app: an outage hits every phone
 * at once, and they all land on the same REST server.
 */
class ChainHistoryReader internal constructor(
    private val restApi: () -> KaspaRestApi?,
) {
    private class Entry(val since: Long, val atMs: Long, val transactions: List<ChainHistoryTx>)

    private val mutex = Mutex()
    private val entries = HashMap<String, Entry>()
    private val proven = HashSet<String>()
    private var nextRequestAtMs = 0L
    private var blockedUntilMs = 0L

    private suspend fun fresh(address: String, since: Long): List<ChainHistoryTx>? = mutex.withLock {
        entries[address]?.takeIf { it.since <= since && System.currentTimeMillis() - it.atMs < LIFETIME_MS }?.transactions
    }

    private suspend fun store(address: String, since: Long, transactions: List<ChainHistoryTx>) = mutex.withLock {
        if (entries.size > 500) entries.clear()
        entries[address] = Entry(since, System.currentTimeMillis(), transactions)
    }

    /** How long to wait before the next request, or null while backing off. */
    private suspend fun reserveRequestSlot(): Long? = mutex.withLock {
        val now = System.currentTimeMillis()
        if (now < blockedUntilMs) return@withLock null
        val at = maxOf(now, nextRequestAtMs)
        nextRequestAtMs = at + REQUEST_SPACING_MS
        at - now
    }

    private suspend fun backOff() = mutex.withLock {
        blockedUntilMs = System.currentTimeMillis() + BACK_OFF_MS
    }

    /**
     * [address]'s accepted transactions since [since] (ms), oldest first. Throws when the history
     * runs past the page cap before reaching [since]: a partial read would move the caller's
     * cursor past the part it never saw.
     */
    suspend fun chainTransactions(address: String, since: Long): List<ChainHistoryTx> {
        val key = address.lowercase()
        fresh(key, since)?.let { cached -> return cached.filter { it.blockTime >= since } }
        val api = restApi() ?: throw IOException("No Kaspa REST API")
        val found = ArrayList<ChainHistoryTx>()
        var reachedStart = false
        for (page in 0 until MAX_PAGES) {
            val wait = reserveRequestSlot() ?: throw IOException("The Kaspa REST API is busy")
            if (wait > 0) delay(wait)
            val rows = try {
                api.getAddressHistoryRows(key, limit = PAGE_SIZE, offset = page * PAGE_SIZE)
            } catch (e: HttpException) {
                if (e.code() == 429 || e.code() >= 500) backOff()
                throw e
            }
            val transactions = ChainHistoryRows.parse(rows)
            found += transactions
            if (rows.size < PAGE_SIZE || (since > 0 && transactions.any { it.blockTime < since })) {
                reachedStart = true
                break
            }
        }
        if (!reachedStart) throw IOException("Too much history to read from the chain")
        val inOrder = found.sortedWith(compareBy({ it.blockTime }, { it.txId }))
        store(key, since, inOrder)
        return inOrder.filter { it.blockTime >= since }
    }

    /**
     * Whether [txId]'s input signature proves [expected] wrote it (iOS `provenSender`). The REST
     * listing's addresses are only its word. UNKNOWN is the REST server not having the
     * transaction yet, or a field the signature covers missing - not a bad signature.
     */
    suspend fun provenSender(txId: String, expected: String, myAddress: String): SenderProof {
        val api = restApi() ?: return SenderProof.UNKNOWN
        var tx: TransactionResponse? = null
        for (attempt in 0 until PROOF_FETCH_ATTEMPTS) {
            if (attempt > 0) delay(PROOF_FETCH_DELAY_MS)
            tx = try {
                api.getTransactionWithInputAddresses(txId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (tx != null) break
        }
        val fetched = tx ?: return SenderProof.UNKNOWN
        val verdict = KaspaSignatureVerifier.checkReceivedTransaction(fetched, myAddress) { previousTxId, index ->
            val previous = try {
                api.getTransaction(previousTxId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            previous?.let { KaspaSignatureVerifier.outputAt(it, index) }
        }
        return when (verdict.check) {
            is KaspaSignatureVerifier.SignatureCheck.Invalid -> SenderProof.FORGED
            KaspaSignatureVerifier.SignatureCheck.Valid -> when {
                verdict.sender == null -> SenderProof.UNKNOWN
                verdict.sender.equals(expected, ignoreCase = true) -> SenderProof.PROVEN
                else -> SenderProof.FORGED
            }
            KaspaSignatureVerifier.SignatureCheck.Unknown -> SenderProof.UNKNOWN
        }
    }

    /** Only rows whose author's input signature checks out: the rest of the app takes only a
     *  signature as proof of who wrote something. A row that can't be proven now comes back from
     *  the indexer when it recovers (iOS `provenByAuthor`). */
    private suspend fun <T> provenByAuthor(rows: List<T>, txId: (T) -> String, author: (T) -> String): List<T> {
        val kept = ArrayList<T>(rows.size)
        for (row in rows) {
            val id = txId(row)
            if (mutex.withLock { id in proven }) {
                kept += row
            } else if (provenSender(id, author(row), myAddress = "") == SenderProof.PROVEN) {
                mutex.withLock {
                    if (proven.size > 5_000) proven.clear()
                    proven += id
                }
                kept += row
            } else {
                Log.i(TAG, "Left ${id.take(12)} for the indexer: its author can't be proven")
            }
        }
        return kept
    }

    suspend fun handshakes(address: String, sent: Boolean, since: Long): List<HandshakeIndexerResponse> =
        provenByAuthor(
            ChainHistoryRows.handshakes(chainTransactions(address, since), address, sent),
            txId = { it.txId },
            author = { it.sender },
        )

    suspend fun contextualMessages(address: String, alias: String, since: Long): List<ContextualMessageIndexerResponse> =
        provenByAuthor(
            ChainHistoryRows.contextualMessages(chainTransactions(address, since), address, alias),
            txId = { it.txId },
            author = { it.sender },
        )

    suspend fun selfStash(owner: String, scope: String, since: Long): List<SelfStashIndexerResponse> =
        ChainHistoryRows.selfStash(chainTransactions(owner, since), owner, scope)

    private companion object {
        const val TAG = "ChainHistoryReader"
        const val PAGE_SIZE = 100
        /** 2,000 transactions per address; past this a window is left to the indexer when it's back. */
        const val MAX_PAGES = 20
        const val LIFETIME_MS = 20_000L
        const val REQUEST_SPACING_MS = 250L
        const val BACK_OFF_MS = 30_000L
        const val PROOF_FETCH_ATTEMPTS = 3
        const val PROOF_FETCH_DELAY_MS = 800L
    }
}
