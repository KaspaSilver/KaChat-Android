package com.kachat.app.services.grpc

import io.grpc.Server
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import protowire.Messages
import protowire.RPCGrpcKt
import protowire.getInfoRequestMessage
import protowire.getInfoResponseMessage
import protowire.getMempoolEntryResponseMessage
import protowire.rPCError
import protowire.rpcMempoolEntry
import protowire.rpcTransaction
import protowire.kaspadRequest
import protowire.kaspadResponse

/**
 * Exercises the REAL id-multiplexing/timeout-cleanup logic in [KaspadConnection]
 * against a real (in-process) gRPC bidi stream — not a mock — using grpc's
 * InProcess transport, which is the standard way to test gRPC clients offline.
 */
class KaspadConnectionTest {

    private lateinit var server: Server
    private val serverName = InProcessServerBuilder.generateName()

    @After
    fun tearDown() {
        server.shutdownNow()
    }

    private fun startServer(impl: RPCGrpcKt.RPCCoroutineImplBase) {
        server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(impl)
            .build()
            .start()
    }

    private fun newConnection(scope: CoroutineScope): KaspadConnection {
        val channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
        return KaspadConnection(scope, channel)
    }

    private suspend fun callGetInfo(conn: KaspadConnection, timeoutMs: Long = 5000): kotlin.String =
        conn.call(
            timeoutMs = timeoutMs,
            build = { id -> kaspadRequest { this.id = id; getInfoRequest = getInfoRequestMessage {} } },
            extract = { it.getInfoResponse.serverVersion }
        )

    @Test
    fun `concurrent calls each receive their own response, not a mixed-up one`() = runBlocking {
        startServer(object : RPCGrpcKt.RPCCoroutineImplBase() {
            override fun messageStream(requests: Flow<Messages.KaspadRequest>): Flow<Messages.KaspadResponse> =
                requests.map { req ->
                    kaspadResponse {
                        id = req.id
                        getInfoResponse = getInfoResponseMessage { serverVersion = "resp-${req.id}" }
                    }
                }
        })

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val conn = newConnection(scope)
        conn.connect()

        val results = (1..5).map { async { callGetInfo(conn) } }.awaitAll()

        conn.close()
        assertEquals(setOf("resp-1", "resp-2", "resp-3", "resp-4", "resp-5"), results.toSet())
    }

    @Test
    fun `a request that never gets a response times out and cleans up the pending map`() = runBlocking {
        startServer(object : RPCGrpcKt.RPCCoroutineImplBase() {
            override fun messageStream(requests: Flow<Messages.KaspadRequest>): Flow<Messages.KaspadResponse> =
                flow { requests.collect { /* never respond */ } }
        })

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val conn = newConnection(scope)
        conn.connect()

        try {
            callGetInfo(conn, timeoutMs = 200)
            fail("Expected a timeout")
        } catch (e: TimeoutCancellationException) {
            // expected
        }

        assertEquals(0, conn.pendingCount)
        conn.close()
    }

    @Test
    fun `a failing stream completes pending calls exceptionally instead of hanging`() = runBlocking {
        startServer(object : RPCGrpcKt.RPCCoroutineImplBase() {
            override fun messageStream(requests: Flow<Messages.KaspadRequest>): Flow<Messages.KaspadResponse> =
                flow<Messages.KaspadResponse> { throw RuntimeException("boom") }
        })

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val conn = newConnection(scope)
        conn.connect()

        try {
            callGetInfo(conn)
            fail("Expected the call to fail when the stream fails")
        } catch (e: Exception) {
            // expected — any exception is fine, we're only proving it doesn't hang forever
        }

        conn.close()
    }

    @Test
    fun `hasMempoolEntry is true only for a transaction the node holds (audit IOS-014)`() = runBlocking {
        val known = "aa".repeat(32)
        startServer(object : RPCGrpcKt.RPCCoroutineImplBase() {
            override fun messageStream(requests: Flow<Messages.KaspadRequest>): Flow<Messages.KaspadResponse> =
                requests.map { req ->
                    val asked = req.getMempoolEntryRequest
                    kaspadResponse {
                        id = req.id
                        getMempoolEntryResponse = if (asked.txId == known && asked.includeOrphanPool) {
                            getMempoolEntryResponseMessage { entry = rpcMempoolEntry { transaction = rpcTransaction { version = 0 } } }
                        } else {
                            getMempoolEntryResponseMessage { error = rPCError { message = "transaction not found" } }
                        }
                    }
                }
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val conn = newConnection(scope)
        conn.connect()
        assertTrue(conn.hasMempoolEntry(known))
        assertFalse(conn.hasMempoolEntry("bb".repeat(32)))
        conn.close()
    }

    @Test
    fun `hasMempoolEntry answers false when the node never answers`() = runBlocking {
        startServer(object : RPCGrpcKt.RPCCoroutineImplBase() {
            override fun messageStream(requests: Flow<Messages.KaspadRequest>): Flow<Messages.KaspadResponse> =
                flow { requests.collect { /* never respond */ } }
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val conn = newConnection(scope)
        conn.connect()
        assertFalse(conn.hasMempoolEntry("aa".repeat(32), timeoutMs = 200))
        conn.close()
    }
}
