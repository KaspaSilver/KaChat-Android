package com.kachat.app.services.grpc

import android.util.Log
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import com.kachat.app.services.RawTransaction
import protowire.Messages
import protowire.RPCGrpcKt
import protowire.Rpc
import protowire.getBlockDagInfoRequestMessage
import protowire.getFeeEstimateRequestMessage
import protowire.getInfoRequestMessage
import protowire.getMempoolEntryRequestMessage
import protowire.getPeerAddressesRequestMessage
import protowire.getUtxosByAddressesRequestMessage
import protowire.kaspadRequest
import protowire.notifyBlockAddedRequestMessage
import protowire.rpcOutpoint
import protowire.rpcScriptPublicKey
import protowire.rpcTransaction
import protowire.rpcTransactionInput
import protowire.rpcTransactionOutput
import protowire.submitTransactionRequestMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A parsed node address: "host:port" (plaintext), or "grpcs://host[:port]"/"https://host[:port]"
 * (TLS, defaulting to port 443 when omitted) - mirrors iOS's Endpoint(url:) and Kaspium's own
 * port inference (GrpcService.url in grpc_service.dart), needed for nodes like Kaspium's own
 * that terminate TLS at the gRPC port itself and reject plaintext HTTP/2 entirely.
 */
data class ParsedNodeAddress(val host: String, val port: Int, val secure: Boolean)

fun parseNodeAddress(raw: String): ParsedNodeAddress? {
    var clean = raw.trim()
    var secure = false
    when {
        clean.startsWith("grpcs://") -> { secure = true; clean = clean.removePrefix("grpcs://") }
        clean.startsWith("https://") -> { secure = true; clean = clean.removePrefix("https://") }
        clean.startsWith("grpc://") -> clean = clean.removePrefix("grpc://")
    }
    val lastColon = clean.lastIndexOf(':')
    val port = if (lastColon >= 0) clean.substring(lastColon + 1).toIntOrNull() else null
    return when {
        port != null -> ParsedNodeAddress(clean.substring(0, lastColon), port, secure)
        secure && clean.isNotBlank() -> ParsedNodeAddress(clean, 443, true)
        else -> null
    }
}

/**
 * Whether [raw], an address another node gossiped ([KaspadConnection.getPeerAddresses]), is a plain
 * `host:port` a channel can be built for: an IPv4 address, a bracketed IPv6 address or a host
 * name, and a port from 1 to 65535. Anything else is dropped: a malformed target (a space, an
 * unbracketed IPv6 address) makes gRPC throw while building the channel, and any node can send one.
 */
fun isGossipedNodeAddress(raw: String): Boolean {
    if (raw.length > 270 || raw.any { it.isWhitespace() }) return false
    val lastColon = raw.lastIndexOf(':')
    if (lastColon <= 0) return false
    val port = raw.substring(lastColon + 1).toIntOrNull() ?: return false
    if (port !in 1..65535 || raw.substring(lastColon + 1).any { it !in '0'..'9' }) return false
    val host = raw.substring(0, lastColon)
    if (host.startsWith("[")) {
        if (!host.endsWith("]") || host.length < 4) return false
        val inner = host.substring(1, host.length - 1)
        return inner.contains(':') && inner.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
    }
    if (host.length > 253) return false
    val labels = host.split('.')
    return labels.all { label ->
        label.isNotEmpty() && label.length <= 63 && !label.startsWith('-') && !label.endsWith('-') &&
            label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
    }
}

/**
 * A single gRPC connection to one Kaspa node ("ip:port", plaintext by default; see
 * [parseNodeAddress] for the TLS-secured "grpcs://"/"https://" link format), wrapping the
 * node's single bidirectional-streaming `MessageStream` RPC. Requests/responses
 * are multiplexed over that one stream by a client-assigned request id, matching
 * the real Kaspa RPC protocol (`protowire.RPC` service, see rpc.proto/messages.proto).
 *
 * One connection is opened per probe and closed immediately after — see
 * NodeProfiler.probeNode(). This trades a little per-cycle connection setup cost
 * for much simpler state management, acceptable for a status-display's call volume.
 */
class KaspadConnection internal constructor(
    private val scope: CoroutineScope,
    private val channel: ManagedChannel,
    private val address: String = "unknown"
) {
    constructor(address: String, scope: CoroutineScope) : this(
        scope,
        buildChannel(address),
        address
    )

    companion object {
        /** How many self-reconnects a connection that isn't pinned gets (iOS `attempt <= 3`). */
        const val MAX_UNPINNED_RECONNECTS = 3

        /** A request within this long counts as "in use lately" (iOS 10 minutes). */
        const val RECENT_USE_MS = 10 * 60 * 1000L

        private fun buildChannel(address: String): ManagedChannel {
            val parsed = parseNodeAddress(address)
            val target = if (parsed != null) "${parsed.host}:${parsed.port}" else address
            val builder = ManagedChannelBuilder.forTarget(target)
            if (parsed?.secure == true) builder.useTransportSecurity() else builder.usePlaintext()
            return builder
                // Connections (especially the trusted-node pin, which holds one connection open
                // for its entire lifetime across ~30s idle gaps between probes) can go half-open
                // - the OS drops the underlying socket (NAT/carrier idle timeout, Doze, network
                // handoff) without the app finding out until the next RPC silently hangs for a
                // full 5s timeout. HTTP/2 keepalive pings detect that proactively and force gRPC
                // to fail fast and reconnect instead.
                .keepAliveTime(20, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .build()
        }
    }

    /** True once [connect] has (re)launched the stream-collecting coroutine and it hasn't since
     * died - see the doc comment on the `catch` block in [connect] for what clears this. */
    @Volatile
    var isConnected: Boolean = false
        private set

    /**
     * Bumped on every stream-lifecycle transition: each (re)connect in [connect], each
     * unexpected stream death, and [close]. Server-side subscription state (e.g. a block-added
     * NOTIFY_START — see [subscribeToBlockAdded]) lives on the underlying stream and dies with
     * it, so a subscriber that must survive failover watches this flow: any change from the
     * value observed at subscribe time means "your subscription is gone — re-send NOTIFY_START
     * on whatever connection is best now". This exists because the returned notification Flow
     * is a SharedFlow whose collectors never complete on their own, so "collect returned" can
     * never be the death signal (it never returns).
     */
    private val generation = MutableStateFlow(0L)
    val connectionGeneration: StateFlow<Long> = generation.asStateFlow()

    /** Set by [close] before cancelling the stream job, so the job's own cancellation doesn't
     * get mistaken for an unexpected drop and trigger [scheduleAutoReconnect]. */
    @Volatile
    private var closed = false

    private val stub = RPCGrpcKt.RPCCoroutineStub(channel)

    private val outbound = Channel<Messages.KaspadRequest>(Channel.UNLIMITED)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Messages.KaspadResponse>>()
    private val nextId = AtomicLong(1)

    // Block-added notifications are unsolicited (no matching pending request id) once the
    // subscription is active — routed here instead of dropped, for broadcast-message scanning.
    // extraBufferCapacity keeps a slow/no collector from blocking the shared stream-reading
    // coroutine; a resumed collector just misses whatever fell off the buffer, which is fine
    // since broadcast scanning is a best-effort rolling cache, not a delivery guarantee.
    private val blockAddedNotifications = MutableSharedFlow<Rpc.RpcBlock>(extraBufferCapacity = 64)

    private var streamJob: Job? = null

    /** Who wants block-added notifications on the current stream - see [unsubscribeFromBlockAdded]. */
    private val blockSubscribers = mutableSetOf<String>()
    private var blockSubscribersGeneration = -1L

    /** Consecutive auto-reconnect attempts since the node last really answered on this stream -
     * drives backoff so a node that's actively rejecting new streams (e.g. it logged "reached
     * connection capacity") gets breathing room instead of a retry every ~100ms-1s indefinitely,
     * which just keeps re-triggering the same rejection. [connect] leaves it alone: a stream that
     * opens says nothing about one the node then refuses (iOS 4f8e14bb). */
    @Volatile
    private var reconnectAttempts = 0

    /** The sleeping self-reconnect, which [close] calls off (iOS 4f8e14bb). */
    private var autoReconnectJob: Job? = null

    /**
     * The node pool's primary or standby ([com.kachat.app.services.NodePoolManager] pins them,
     * iOS 83806b3d `GRPCConnectionPool.setPinned`): reconnects for as long as it takes. Any other
     * connection - a probe's, a node used once - tries a few times while it was in use lately, then
     * stays down until a request needs it.
     */
    @Volatile
    var isPinned: Boolean = false

    /** When a caller last sent a request on it (epoch millis), null before the first. Unlike the
     *  stream's own traffic, a reconnect doesn't count (iOS `lastRequestAt`). */
    @Volatile
    var lastRequestAtMs: Long? = null
        private set

    /** A request is waiting for its answer: closing now would fail it. */
    val hasPendingRequests: Boolean get() = pending.isNotEmpty()

    /** The node this connects to, as the pool keys it. */
    val nodeAddress: String get() = address

    /** Whether a request went out on this connection within [withinMs]. */
    fun usedWithin(withinMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        lastRequestAtMs?.let { nowMs - it < withinMs } ?: false

    fun connect() {
        // [closed] too: a connect after close() would open a stream on a shut-down channel.
        if (isConnected || closed) return
        isConnected = true
        generation.value++
        streamJob = scope.launch {
            try {
                stub.messageStream(outbound.receiveAsFlow()).collect { response ->
                    // The node really is talking on this stream, so the backoff can start over.
                    reconnectAttempts = 0
                    val matched = pending.remove(response.id)
                    if (matched != null) {
                        matched.complete(response)
                    } else if (response.hasBlockAddedNotification()) {
                        blockAddedNotifications.tryEmit(response.blockAddedNotification.block)
                    }
                    // Any other unsolicited response is still ignored.
                }
            } catch (e: CancellationException) {
                // A deliberate close() cancels this job itself - `closed`/`isConnected` are
                // already set there, so this must not be treated as an unexpected drop.
                throw e
            } catch (e: Exception) {
                isConnected = false
                // Death is its own generation bump (not just the later reconnect's): a watcher
                // must learn its subscription is gone even if this connection never reconnects
                // (e.g. NodePoolManager closes it and hands out a different node next).
                generation.value++
                pending.values.forEach { it.completeExceptionally(e) }
                pending.clear()
                Log.w("KaspadConnection", "Stream died unexpectedly on $address: ${e.javaClass.simpleName}: ${e.message}")
                scheduleAutoReconnect()
            }
        }
    }

    /**
     * Self-heal from an unexpected stream death (e.g. the OS killing sockets on sleep/wake, or a
     * brief network hiccup) instead of passively leaving this connection dead until the next
     * scheduled probe cycle (up to 30s later, see NodePoolManager.startProbing) happens to notice
     * and replace it. Small random jitter in case many connections die in the same instant, so
     * reconnects don't all slam the network at once.
     */
    private fun scheduleAutoReconnect() {
        reconnectAttempts++
        val attempt = reconnectAttempts
        autoReconnectJob?.cancel()
        autoReconnectJob = scope.launch {
            // First attempt: small jitter only. Later attempts: capped exponential backoff
            // (1, 2, 4, 8, 16, 30, 30...s) - a node that's actively rejecting new streams needs
            // breathing room, not a retry every ~100ms-1s forever.
            val delayMs = if (attempt <= 1) {
                (100L..1000L).random()
            } else {
                val backoffSeconds = minOf(30.0, Math.pow(2.0, (attempt - 2).toDouble()))
                ((backoffSeconds + Math.random() * 0.3 * backoffSeconds) * 1000).toLong()
            }
            delay(delayMs)
            // Only the pool's pinned nodes reconnect without end. Any other connection tries a few
            // times while it was in use lately, then stays down until a request needs it ([call]
            // reconnects then) - iOS 83806b3d `shouldKeepReconnecting`.
            if (!isConnected && !closed && shouldKeepReconnecting(attempt)) {
                connect()
            }
        }
    }

    private fun shouldKeepReconnecting(attempt: Int): Boolean {
        if (isPinned) return true
        return attempt <= MAX_UNPINNED_RECONNECTS && usedWithin(RECENT_USE_MS)
    }

    /** Exposed for testing the id-multiplexing/timeout-cleanup behavior directly. */
    internal val pendingCount: Int get() = pending.size

    internal suspend fun <T> call(
        timeoutMs: Long = 5000,
        build: (id: Long) -> Messages.KaspadRequest,
        extract: (Messages.KaspadResponse) -> T
    ): T {
        lastRequestAtMs = System.currentTimeMillis()
        // A connection that stopped reconnecting on its own comes back when a request needs it
        // (iOS `sendRequest` connects first).
        if (!isConnected && !closed) connect()
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<Messages.KaspadResponse>()
        pending[id] = deferred
        try {
            outbound.send(build(id))
            return withTimeout(timeoutMs) { extract(deferred.await()) }
        } finally {
            // Must clean up on timeout/cancellation too, not just on success — otherwise a
            // node that never responds leaks a CompletableDeferred into `pending` forever.
            pending.remove(id)
        }
    }

    suspend fun getInfo(timeoutMs: Long = 5000): Rpc.GetInfoResponseMessage = call(
        timeoutMs = timeoutMs,
        build = { id -> kaspadRequest { this.id = id; getInfoRequest = getInfoRequestMessage {} } },
        extract = { it.getInfoResponse }
    )

    suspend fun getBlockDagInfo(timeoutMs: Long = 5000): Rpc.GetBlockDagInfoResponseMessage = call(
        timeoutMs = timeoutMs,
        build = { id -> kaspadRequest { this.id = id; getBlockDagInfoRequest = getBlockDagInfoRequestMessage {} } },
        extract = { it.getBlockDagInfoResponse }
    )

    /** The node's fee estimate (`GetFeeEstimate`): the priority bucket and the normal buckets, in
     *  sompi per gram with the seconds each is expected to wait (iOS e426432). */
    suspend fun getFeeEstimate(timeoutMs: Long = 10_000): Rpc.GetFeeEstimateResponseMessage = call(
        timeoutMs = timeoutMs,
        build = { id -> kaspadRequest { this.id = id; getFeeEstimateRequest = getFeeEstimateRequestMessage {} } },
        extract = { it.getFeeEstimateResponse }
    )

    suspend fun getPeerAddresses(): Rpc.GetPeerAddressesResponseMessage = call(
        build = { id -> kaspadRequest { this.id = id; getPeerAddressesRequest = getPeerAddressesRequestMessage {} } },
        extract = { it.getPeerAddressesResponse }
    )

    /**
     * Every current UTXO at [addresses], straight from the node (`GetUtxosByAddresses`). The
     * spend path used to read these from the REST gateway alone, and api.kaspa.org rate-limits
     * a burst of sends with HTTP 429 - a node has no such limit, and it is what iOS asks. Only
     * answered by a node started with `--utxoindex`; any other node returns an RPC error, which
     * is thrown here so the caller can fall back.
     */
    suspend fun getUtxosByAddresses(addresses: List<String>, timeoutMs: Long = 8000): List<Rpc.RpcUtxosByAddressesEntry> {
        val response = call(
            timeoutMs = timeoutMs,
            build = { id ->
                kaspadRequest {
                    this.id = id
                    getUtxosByAddressesRequest = getUtxosByAddressesRequestMessage { this.addresses.addAll(addresses) }
                }
            },
            extract = { it.getUtxosByAddressesResponse }
        )
        if (response.hasError()) throw IllegalStateException(response.error.message)
        // Coins for addresses not asked about are a crossed or broken answer: refused, and the
        // caller asks elsewhere (iOS 4f8e14bb).
        val requested = addresses.map { it.lowercase() }.toSet()
        if (response.entriesList.any { it.address.isNotEmpty() && it.address.lowercase() !in requested }) {
            throw IllegalStateException("UTXO response for addresses not requested")
        }
        // more than the Kaspa supply (a coin, or all of them) is a broken or hostile node's:
        // refused here, where it's decoded (iOS 283cd28, IOS-020)
        return com.kachat.app.util.UtxoMath.checkedFromNetwork(response.entriesList) { it.utxoEntry.amount }
    }

    /**
     * Subscribes this connection to block-added notifications — used for broadcast-message
     * scanning, since there's no per-address query for a public "bcast" channel; every new
     * block's transactions have to be inspected client-side instead. Sends one NOTIFY_START
     * request and waits for its ack; the returned [Flow] then emits every subsequently-added
     * [Rpc.RpcBlock] for as long as this connection stays open. The caller is responsible for
     * calling [unsubscribeFromBlockAdded] when scanning should stop.
     */
    suspend fun subscribeToBlockAdded(owner: String): Flow<Rpc.RpcBlock> {
        synchronized(blockSubscribers) {
            if (blockSubscribersGeneration != generation.value) {
                // A new stream: the node has no subscription from the old one to count.
                blockSubscribers.clear()
                blockSubscribersGeneration = generation.value
            }
            blockSubscribers.add(owner)
        }
        // Sent on every subscribe, not only the first: NOTIFY_START is idempotent on the node,
        // and a second subscriber must not rely on a first one's START still in flight.
        val response = call(
            build = { id ->
                kaspadRequest {
                    this.id = id
                    notifyBlockAddedRequest = notifyBlockAddedRequestMessage { command = Rpc.RpcNotifyCommand.NOTIFY_START }
                }
            },
            extract = { it.notifyBlockAddedResponse }
        )
        if (response.hasError()) {
            throw IllegalStateException(response.error.message)
        }
        return blockAddedNotifications.asSharedFlow()
    }

    /**
     * [owner] no longer wants block-added notifications. The NOTIFY_STOP goes to the node only
     * when nobody else on this connection still does: the notification is per CONNECTION, and the
     * public-room and group scanners share one. Each used to send its own stop, so the room
     * scanner going idle silenced the group scanner too (and the other way round), until some
     * reconnect happened to restart it. Closing/dropping the Flow collector alone does not stop
     * the node from sending them, so the last one out must still call this.
     */
    suspend fun unsubscribeFromBlockAdded(owner: String) {
        val lastOut = synchronized(blockSubscribers) {
            if (blockSubscribersGeneration != generation.value) {
                // The stream this owner subscribed on is gone, and the node's state with it.
                false
            } else {
                blockSubscribers.remove(owner)
                blockSubscribers.isEmpty()
            }
        }
        if (!lastOut) return
        call(
            build = { id ->
                kaspadRequest {
                    this.id = id
                    notifyBlockAddedRequest = notifyBlockAddedRequestMessage { command = Rpc.RpcNotifyCommand.NOTIFY_STOP }
                }
            },
            extract = { it.notifyBlockAddedResponse }
        )
    }

    /**
     * Broadcasts a signed transaction via direct gRPC SubmitTransaction, bypassing the
     * REST gateway entirely. The REST `POST /transactions` endpoint (api.kaspa.org)
     * works fine for plain payments but rejects payload-carrying transactions with a
     * false "signature script" failure — the real signature is cryptographically valid
     * (verified against official rusty-kaspa test vectors), so the bug is somewhere in
     * the REST gateway's JSON-to-RPC translation of the payload field, not in the
     * transaction itself. The iOS reference app (KaChat) never uses REST for broadcast
     * either — it submits exclusively over gRPC, so this matches the proven-working path.
     */
    suspend fun submitTransaction(tx: RawTransaction, allowOrphan: Boolean = false): String {
        val rpcTx = rpcTransaction {
            version = tx.version
            inputs.addAll(
                tx.inputs.map { input ->
                    rpcTransactionInput {
                        previousOutpoint = rpcOutpoint {
                            transactionId = input.previousOutpoint.transactionId
                            index = input.previousOutpoint.index
                        }
                        signatureScript = input.signatureScript
                        sequence = input.sequence
                        sigOpCount = input.sigOpCount
                    }
                }
            )
            outputs.addAll(
                tx.outputs.map { output ->
                    rpcTransactionOutput {
                        amount = output.amount
                        scriptPublicKey = rpcScriptPublicKey {
                            version = output.scriptPublicKey.version
                            scriptPublicKey = output.scriptPublicKey.scriptPublicKey
                        }
                    }
                }
            )
            lockTime = tx.lockTime
            subnetworkId = tx.subnetworkId
            gas = tx.gas
            tx.payload?.let { payload = it }
        }
        return submitRpcTransaction(rpcTx, allowOrphan)
    }

    /**
     * Submits an already-converted transaction (iOS `NodePoolService.submitRpcTransaction`, KaChat
     * ede9417): [submitTransaction] forwards here unchanged. The version-1 (Toccata) `.kachat`
     * builders fill `computeBudget`, `covenant` and `storageMass` themselves
     * (`KachatNamesService.rpcTransaction`), which the v0 [RawTransaction] has no place for.
     */
    suspend fun submitRpcTransaction(rpcTx: Rpc.RpcTransaction, allowOrphan: Boolean = false): String {
        val response = call(
            timeoutMs = 15000,
            build = { id ->
                kaspadRequest {
                    this.id = id
                    submitTransactionRequest = submitTransactionRequestMessage {
                        transaction = rpcTx
                        this.allowOrphan = allowOrphan
                    }
                }
            },
            extract = { it.submitTransactionResponse }
        )

        if (response.hasError()) {
            // Noted for the "did it go out anyway" look-up that follows: this node's own mempool
            // answer is then not taken as proof, and a verdict against the transaction itself asks
            // two other nodes (iOS 83806b3d `SubmitAnswers`).
            val verdict = !com.kachat.app.services.SubmitConfirmation.rejectionMayMeanAlreadyKnown(response.error.message)
            currentCoroutineContext()[com.kachat.app.services.SubmitAnswers]?.note(address, verdict)
            throw IllegalStateException(response.error.message)
        }
        return response.transactionId
    }

    /**
     * Whether this node holds [transactionId] in its mempool, orphan pool included (iOS
     * `queryMempoolEntryFromConnection`, 2 s). False for "not in the mempool" (an RPC error) and
     * for any failure - this only ever answers "known here", never "unknown everywhere".
     */
    suspend fun hasMempoolEntry(transactionId: String, timeoutMs: Long = 2000): Boolean {
        return try {
            val response = call(
                timeoutMs = timeoutMs,
                build = { id ->
                    kaspadRequest {
                        this.id = id
                        getMempoolEntryRequest = getMempoolEntryRequestMessage {
                            txId = transactionId
                            includeOrphanPool = true
                            filterTransactionPool = false
                        }
                    }
                },
                extract = { it.getMempoolEntryResponse }
            )
            !response.hasError() && response.hasEntry() && response.entry.hasTransaction()
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Whether this node holds [transactionId] in its mempool as a transaction that really is that
     * one: not as an orphan, and with an id recomputed from the fields it sent back that matches
     * (iOS 83806b3d `hasVerifiedMempoolEntry`). What a "did it go out" check counts: one bad node
     * must not turn a refused send into Sent. False for any failure.
     */
    suspend fun hasVerifiedMempoolEntry(transactionId: String, timeoutMs: Long = 2000): Boolean {
        return try {
            val response = call(
                timeoutMs = timeoutMs,
                build = { id ->
                    kaspadRequest {
                        this.id = id
                        getMempoolEntryRequest = getMempoolEntryRequestMessage {
                            txId = transactionId
                            includeOrphanPool = false
                            filterTransactionPool = false
                        }
                    }
                },
                extract = { it.getMempoolEntryResponse }
            )
            if (response.hasError() && response.error.message.isNotEmpty()) return false
            if (!response.hasEntry() || response.entry.isOrphan || !response.entry.hasTransaction()) return false
            val recomputed = recomputedTransactionId(response.entry.transaction) ?: return false
            if (recomputed != transactionId.lowercase()) {
                Log.w("KaspadConnection", "$address answered for ${transactionId.take(12)} with a transaction that hashes to ${recomputed.take(12)}: ignored")
                return false
            }
            true
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    fun close() {
        closed = true
        isConnected = false
        generation.value++
        autoReconnectJob?.cancel()
        streamJob?.cancel()
        // A call still waiting fails as a lost answer, not a cancellation: a submit cut off here
        // may already be with the node, so its sender must ask the network before calling it
        // failed (SubmitConfirmation treats an IOException as a lost answer; a plain
        // CancellationException would skip that check and invite a resend that pays twice).
        val closedError = java.io.IOException("Connection to $address closed")
        pending.values.forEach { it.completeExceptionally(closedError) }
        pending.clear()
        channel.shutdownNow()
    }
}

/**
 * The id the chain gives a transaction a node returned, recomputed from its fields (v0 and v1,
 * [com.kachat.app.util.KaspaSignatureVerifier.restTransactionId]); iOS 83806b3d
 * `recomputedTransactionId`. Null when a field can't be read or an output script isn't version 0
 * (no transaction this app sends has one).
 */
internal fun recomputedTransactionId(tx: Rpc.RpcTransaction): String? {
    val version = tx.version
    if (version < 0 || version > 0xFFFF) return null
    val inputs = tx.inputsList.map { input ->
        Triple(input.previousOutpoint.transactionId, input.previousOutpoint.index, input.sequence)
    }
    val outputs = ArrayList<Triple<Long, ByteArray, com.kachat.app.util.KaspaTransactionSigner.OutputCovenant?>>(tx.outputsCount)
    for (output in tx.outputsList) {
        if (output.scriptPublicKey.version != 0) return null
        val script = hexOrNull(output.scriptPublicKey.scriptPublicKey) ?: return null
        val covenant = if (output.hasCovenant()) {
            val authorizing = output.covenant.authorizingInput
            if (authorizing < 0 || authorizing > 0xFFFF) return null
            val id = hexOrNull(output.covenant.covenantId) ?: return null
            com.kachat.app.util.KaspaTransactionSigner.OutputCovenant(authorizing, id)
        } else null
        outputs += Triple(output.amount, script, covenant)
    }
    val subnetwork = hexOrNull(tx.subnetworkId) ?: return null
    val payload = hexOrNull(tx.payload) ?: return null
    return com.kachat.app.util.KaspaSignatureVerifier.restTransactionId(
        version = version,
        inputs = inputs,
        outputs = outputs,
        lockTime = tx.lockTime,
        subnetworkId = subnetwork,
        gas = tx.gas,
        payload = payload,
    )?.lowercase()
}

/** [hex] as bytes ("" is none), or null when it isn't hex. */
private fun hexOrNull(hex: String): ByteArray? {
    if (hex.length % 2 != 0) return null
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        val hi = Character.digit(hex[i * 2], 16)
        val lo = Character.digit(hex[i * 2 + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}
