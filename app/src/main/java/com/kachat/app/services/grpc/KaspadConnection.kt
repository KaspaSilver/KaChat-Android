package com.kachat.app.services.grpc

import android.util.Log
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import com.kachat.app.services.RawTransaction
import protowire.Messages
import protowire.RPCGrpcKt
import protowire.Rpc
import protowire.getBlockDagInfoRequestMessage
import protowire.getInfoRequestMessage
import protowire.getPeerAddressesRequestMessage
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

    /** Consecutive auto-reconnect attempts since the last successful [connect] - drives backoff
     * so a node that's actively rejecting new streams (e.g. it logged "reached connection
     * capacity") gets breathing room instead of a retry every ~100ms-1s indefinitely, which
     * just keeps re-triggering the same rejection. */
    private var reconnectAttempts = 0

    fun connect() {
        if (isConnected) return
        isConnected = true
        reconnectAttempts = 0
        streamJob = scope.launch {
            try {
                stub.messageStream(outbound.receiveAsFlow()).collect { response ->
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
        scope.launch {
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
            if (!isConnected && !closed) {
                connect()
            }
        }
    }

    /** Exposed for testing the id-multiplexing/timeout-cleanup behavior directly. */
    internal val pendingCount: Int get() = pending.size

    internal suspend fun <T> call(
        timeoutMs: Long = 5000,
        build: (id: Long) -> Messages.KaspadRequest,
        extract: (Messages.KaspadResponse) -> T
    ): T {
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

    suspend fun getPeerAddresses(): Rpc.GetPeerAddressesResponseMessage = call(
        build = { id -> kaspadRequest { this.id = id; getPeerAddressesRequest = getPeerAddressesRequestMessage {} } },
        extract = { it.getPeerAddressesResponse }
    )

    /**
     * Subscribes this connection to block-added notifications — used for broadcast-message
     * scanning, since there's no per-address query for a public "bcast" channel; every new
     * block's transactions have to be inspected client-side instead. Sends one NOTIFY_START
     * request and waits for its ack; the returned [Flow] then emits every subsequently-added
     * [Rpc.RpcBlock] for as long as this connection stays open. The caller is responsible for
     * calling [unsubscribeFromBlockAdded] when scanning should stop.
     */
    suspend fun subscribeToBlockAdded(): Flow<Rpc.RpcBlock> {
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

    /** Sends NOTIFY_STOP — must be called to actually halt block-added notifications; closing/dropping the Flow collector alone does not stop the node from sending them. */
    suspend fun unsubscribeFromBlockAdded() {
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
            throw IllegalStateException(response.error.message)
        }
        return response.transactionId
    }

    fun close() {
        closed = true
        isConnected = false
        streamJob?.cancel()
        pending.values.forEach { it.cancel() }
        pending.clear()
        channel.shutdownNow()
    }
}
