package com.kachat.app.services.grpc

import io.grpc.Server
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import protowire.Messages
import protowire.RPCGrpcKt
import protowire.Rpc
import protowire.getMempoolEntryResponseMessage
import protowire.kaspadResponse
import protowire.rpcMempoolEntry
import protowire.rpcOutpoint
import protowire.rpcScriptPublicKey
import protowire.rpcTransaction
import protowire.rpcTransactionInput
import protowire.rpcTransactionOutput

/**
 * A "did it go out" check counts a node's mempool answer only for a non-orphan transaction whose
 * recomputed id is the one asked about (iOS 83806b3d `hasVerifiedMempoolEntry`).
 */
class VerifiedMempoolEntryTest {

    /** A real KaChat self-send read back off the chain (KaspaTransactionIdTest's first), and its id. */
    private val realId = "f28587d7ac7ba1f8545e3b4f18dfc24f03160fa596feccbfb3da964272ca054b"
    private val realTx: Rpc.RpcTransaction = rpcTransaction {
        version = 0
        inputs.add(rpcTransactionInput {
            previousOutpoint = rpcOutpoint { transactionId = "debcc13f410fd3facac1f91978941d223ef05861c5e8470c1144b0598c820d13"; index = 0 }
            signatureScript = "41aa"
            sequence = 0
            sigOpCount = 1
        })
        outputs.add(rpcTransactionOutput {
            amount = 999033585L
            scriptPublicKey = rpcScriptPublicKey { version = 0; scriptPublicKey = "20f75c1cb050d94291daa70c338be91073b2f1cee3a44ac690050ee8a96812658dac" }
        })
        lockTime = 0
        subnetworkId = "0000000000000000000000000000000000000000"
        gas = 0
        payload = "6b3a313a71756f74653a3032663735633163623035306439343239316461613730633333386265393130373362326631636565336134346163363930303530656538613936383132363538643a64316233303039616337383039353639663263393531336231306335383366656634343031373233396165346562366534663838323966613361316466393236353063313435393662303836613537653735326162666263313130313136313264636335626435376131363034626163373564366238623366393062633633383a636236306565613633643133616336363837303436373061306538343362303733336265326132313233663462326138363463633836303566653765626462393a346f47675647567a64476c755a773d3d3a303261663130343466386337616335323362373836326163366436643564393139303964323264393330303137373561653635646365376633353431653439353064"
    }

    private lateinit var server: Server
    private val serverName = InProcessServerBuilder.generateName()

    @After
    fun tearDown() {
        server.shutdownNow()
    }

    private fun serve(answer: (Messages.KaspadRequest) -> Messages.KaspadResponse) {
        server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(object : RPCGrpcKt.RPCCoroutineImplBase() {
                override fun messageStream(requests: Flow<Messages.KaspadRequest>): Flow<Messages.KaspadResponse> =
                    requests.map(answer)
            })
            .build()
            .start()
    }

    private fun connection(): KaspadConnection {
        val channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
        return KaspadConnection(CoroutineScope(SupervisorJob() + Dispatchers.Default), channel).also { it.connect() }
    }

    @Test
    fun `the recomputed id of a node's transaction is the chain's id, signature scripts aside`() {
        assertEquals(realId, recomputedTransactionId(realTx))
    }

    @Test
    fun `a transaction that can't be read has no recomputed id`() {
        assertNull(recomputedTransactionId(realTx.toBuilder().setPayload("zz").build()))
        val p2shScriptVersion = realTx.toBuilder()
            .setOutputs(0, realTx.getOutputs(0).toBuilder().setScriptPublicKey(rpcScriptPublicKey { version = 1; scriptPublicKey = "aa" }))
            .build()
        assertNull(recomputedTransactionId(p2shScriptVersion))
    }

    @Test
    fun `a node holding the very transaction, not as an orphan, counts`() = runBlocking {
        serve { req ->
            kaspadResponse {
                id = req.id
                getMempoolEntryResponse = getMempoolEntryResponseMessage { entry = rpcMempoolEntry { transaction = realTx } }
            }
        }
        val conn = connection()
        assertTrue(conn.hasVerifiedMempoolEntry(realId))
        conn.close()
    }

    @Test
    fun `an orphan, or another transaction under that id, doesn't count`() = runBlocking {
        var orphan = true
        serve { req ->
            kaspadResponse {
                id = req.id
                getMempoolEntryResponse = getMempoolEntryResponseMessage {
                    entry = if (orphan) {
                        rpcMempoolEntry { transaction = realTx; isOrphan = true }
                    } else {
                        rpcMempoolEntry { transaction = realTx.toBuilder().setGas(1).build() }
                    }
                }
            }
        }
        val conn = connection()
        assertFalse(conn.hasVerifiedMempoolEntry(realId))
        orphan = false
        assertFalse(conn.hasVerifiedMempoolEntry(realId))
        conn.close()
    }

    @Test
    fun `the request leaves the orphan pool out`() = runBlocking {
        var askedOrphans: Boolean? = null
        serve { req ->
            askedOrphans = req.getMempoolEntryRequest.includeOrphanPool
            kaspadResponse {
                id = req.id
                getMempoolEntryResponse = getMempoolEntryResponseMessage { entry = rpcMempoolEntry { transaction = realTx } }
            }
        }
        val conn = connection()
        conn.hasVerifiedMempoolEntry(realId)
        assertEquals(false, askedOrphans)
        conn.close()
    }
}
