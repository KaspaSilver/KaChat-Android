package com.kachat.app.services.kachatnames

import android.content.Context
import android.util.Log
import com.google.gson.JsonParser
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.services.KaspaWalletEngine
import com.kachat.app.services.NetworkService
import com.kachat.app.services.NodePoolManager
import com.kachat.app.services.grpc.KaspadConnection
import com.kachat.app.services.kachatnames.KachatNames.Codec
import com.kachat.app.services.kachatnames.KachatNames.hex
import com.kachat.app.services.kachatnames.KachatNames.unhex
import com.kachat.app.services.kachatnames.KachatNames.unhex32
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaNetwork
import com.kachat.app.util.Schnorr
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import protowire.Rpc
import protowire.rpcCovenantBinding
import protowire.rpcOutpoint
import protowire.rpcScriptPublicKey
import protowire.rpcTransaction
import protowire.rpcTransactionInput
import protowire.rpcTransactionOutput
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `.kachat` names: the app side of the transaction core (`KachatNames`, `Builder`..., pure and
 * checked against the kachat-domains vectors). This service adds what needs the app: the testnet
 * gate, loading and verifying the manifest, the node's DAG point, the wallet's and the registry's
 * live UTXOs, Schnorr signing with the wallet key (BIP-340, SIGHASH_ALL over the version-1
 * sighash), the protowire conversion with the Toccata fields, and submission through the node
 * pool. A port of iOS KaChat/Services/KachatNames/KachatNamesService.swift (KaChat ede9417).
 *
 * Testnet-10 only: every entry point refuses unless this launch runs on testnet
 * ([KaspaNetwork.isTestnet], iOS `AppSettings.networkType == .testnet`), and the manifest itself
 * must be for testnet-10. Mainnet stays off until the contracts are audited.
 *
 * Callers: `KachatNamesRegistry` (reads) and `KachatNamesActions` (every operation).
 */
@Singleton
class KachatNamesService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettingsRepository,
    private val nodePoolManager: NodePoolManager,
    private val networkService: NetworkService,
    private val walletEngine: KaspaWalletEngine,
    okHttpClient: OkHttpClient
) {
    private val http: OkHttpClient = okHttpClient.newBuilder()
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private val _manifest = MutableStateFlow<Manifest?>(null)
    /** The verified registry manifest, once loaded. */
    val manifest: StateFlow<Manifest?> = _manifest.asStateFlow()

    private val _manifestSource = MutableStateFlow<String?>(null)
    /** Where the manifest came from: "bundle" or the indexer URL. */
    val manifestSource: StateFlow<String?> = _manifestSource.asStateFlow()

    private val manifestMutex = Mutex()

    /** The service's errors; messages are English like iOS's (the screens show them as they are). */
    sealed class ServiceError(message: String) : Exception(message) {
        class TestnetOnly : ServiceError(".kachat names run on Testnet only for now")
        class NoManifest(why: String) : ServiceError("No .kachat registry manifest: $why")
        class DryRunManifest : ServiceError("The .kachat manifest is from a dry run; that registry does not exist")
        class WrongNodeNetwork(network: String) : ServiceError("The node is on $network, not testnet-10")
        class KeyMismatch : ServiceError("The signing key is not the key the transaction was built for")
        class NotOnChain(what: String) : ServiceError("$what is not on chain (or not with the registry covenant id)")
        class BadProfile(why: String) : ServiceError("Profile: $why")
        class SubmitMismatch(expected: String, got: String) : ServiceError("The node accepted $got, expected $expected")
    }

    /** The virtual's DAA score and past median time (unix ms) and the node's network name. */
    data class DagPoint(val networkName: String, val virtualDaaScore: Long, val pastMedianTimeMs: Long)

    // Gate

    fun requireTestnet() {
        if (!isEnabled) throw ServiceError.TestnetOnly()
    }

    // Manifest

    /**
     * The verified registry manifest: `kachat-names-testnet-10.json` from the app's assets when it
     * ships one, else the indexer's `GET /names/manifest`. Cached once verified.
     */
    suspend fun loadManifest(allowDryRun: Boolean = false): Manifest = manifestMutex.withLock {
        requireTestnet()
        _manifest.value?.let { if (allowDryRun || !it.isDryRun) return@withLock it }
        val (data, source) = manifestData()
        val m = Manifest.decode(data)
        m.verify()
        if (m.isDryRun && !allowDryRun) throw ServiceError.DryRunManifest()
        _manifest.value = m
        _manifestSource.value = source
        m
    }

    /** Forget the cached manifest (indexer change). */
    fun resetManifest() {
        _manifest.value = null
        _manifestSource.value = null
    }

    private suspend fun manifestData(): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        val bundled = runCatching { context.assets.open(Manifest.ASSET_NAME).use { it.readBytes() } }.getOrNull()
        if (bundled != null) return@withContext bundled to "bundle"
        val base = settings.indexerUrl.first().trim()
        if (base.isEmpty()) throw ServiceError.NoManifest("none in the app and no indexer is configured for Testnet")
        val url = base.removeSuffix("/") + "/names/manifest"
        val request = runCatching { Request.Builder().url(url).build() }.getOrNull()
            ?: throw ServiceError.NoManifest("bad indexer URL")
        http.newCall(request).execute().use { response ->
            if (response.code != 200) throw ServiceError.NoManifest("the indexer answered ${response.code}")
            (response.body?.bytes() ?: ByteArray(0)) to url
        }
    }

    /** The pure builders over the verified manifest. */
    suspend fun builder(): Builder = Builder(loadManifest())

    // Node

    /**
     * Runs [block] against the node the next broadcast would use, once more on a freshly dialled
     * connection when the first try fails in transport (a silently dead stream; iOS's hedged
     * requests try another node). An RPC error from the node is its answer and is not retried.
     */
    private suspend fun <T> withNode(block: suspend (KaspadConnection) -> T): T = withContext(Dispatchers.IO) {
        try {
            block(nodePoolManager.getBroadcastConnection())
        } catch (e: TimeoutCancellationException) {
            nodePoolManager.refreshBroadcastConnection()
            block(nodePoolManager.getBroadcastConnection())
        } catch (e: CancellationException) {
            throw e
        } catch (e: RpcError) {
            throw e
        } catch (e: Exception) {
            nodePoolManager.refreshBroadcastConnection()
            block(nodePoolManager.getBroadcastConnection())
        }
    }

    private class RpcError(message: String) : Exception(message)

    /**
     * Where time-locked transactions are judged: the virtual's DAA score and past median time and
     * the node's network name (GetBlockDagInfo; iOS `NodePoolService.currentDagPoint`).
     */
    suspend fun currentDagPoint(): DagPoint = withNode { conn ->
        val dag = conn.getBlockDagInfo(timeoutMs = 10_000)
        if (dag.hasError() && dag.error.message.isNotEmpty()) throw RpcError(dag.error.message)
        DagPoint(dag.networkName, dag.virtualDaaScore, maxOf(dag.pastMedianTime, 0L))
    }

    /** The virtual DAA score, or null when no node answers. */
    suspend fun currentVirtualDaaScore(): Long? =
        try { currentDagPoint().virtualDaaScore } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    // Environment

    /**
     * Where the next transaction is judged: the virtual's DAA score and past median time from a
     * testnet-10 node, the wall clock, the signer's key.
     */
    suspend fun environment(privateKey: ByteArray, feerate: Double = KachatNames.MIN_FEERATE): Env {
        requireTestnet()
        val dag = currentDagPoint()
        if (!dag.networkName.endsWith("testnet-10")) throw ServiceError.WrongNodeNetwork(dag.networkName)
        return Env(
            me = xonlyKey(privateKey),
            blockDaa = dag.virtualDaaScore,
            blockTimeMs = dag.pastMedianTimeMs,
            wallMs = System.currentTimeMillis(),
            feerate = maxOf(feerate, KachatNames.MIN_FEERATE)
        )
    }

    // UTXOs

    /**
     * Every UTXO at [addresses] with its covenant id, from a node (`GetUtxosByAddresses`, which
     * reports `covenant_id`). When no node can answer, the REST API's list - which cannot report
     * covenant ids (null), so a registry UTXO read that way never passes [liveRegistryUtxo]
     * (iOS `UTXO.covenantId`, nil on the REST fallback).
     */
    suspend fun utxosByAddresses(addresses: List<String>): List<Utxo> {
        if (addresses.isEmpty()) return emptyList()
        val fromNode = try {
            withNode { conn -> conn.getUtxosByAddresses(addresses, timeoutMs = 15_000) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "node UTXO read failed, using the REST API: ${e.message}")
            null
        }
        if (fromNode != null) return fromNode.mapNotNull { convert(it) }
        val api = networkService.kaspaRestApi.value ?: throw KachatNames.Failure("no node and no Kaspa REST API to read UTXOs from")
        return withContext(Dispatchers.IO) {
            addresses.flatMap { a ->
                api.getUtxos(a).mapNotNull { u ->
                    runCatching {
                        Utxo(
                            Outpoint(unhex32(u.outpoint.transactionId.lowercase()), u.outpoint.index),
                            UtxoEntry(
                                amount = u.utxoEntry.amount,
                                script = unhex(u.utxoEntry.scriptPublicKey.scriptPublicKey),
                                blockDaaScore = u.utxoEntry.blockDaaScore,
                                isCoinbase = u.utxoEntry.isCoinbase,
                                covenantId = null
                            )
                        )
                    }.getOrNull()
                }
            }
        }
    }

    /**
     * The live UTXO at [outpoint] holding [script] (a gap, name, offer or commit), read from a node
     * with its covenant id. A registry record from the indexer is trusted only once this confirms
     * it: the P2SH address commits to the whole state, and the covenant id to the registry lineage.
     */
    suspend fun liveUtxo(script: ByteArray, outpoint: Outpoint): Utxo {
        requireTestnet()
        val address = p2shAddress(script) ?: throw ServiceError.NotOnChain("a non-P2SH script")
        val utxos = utxosByAddresses(listOf(address))
        val txidHex = hex(outpoint.txid)
        val live = utxos.firstOrNull { hex(it.outpoint.txid) == txidHex && it.outpoint.index == outpoint.index }
            ?: throw ServiceError.NotOnChain("$txidHex:${outpoint.index}")
        if (!live.entry.script.contentEquals(script)) throw ServiceError.NotOnChain("$txidHex:${outpoint.index} with that state")
        return live
    }

    /** [liveUtxo] for a gap or name, which must also carry the registry covenant id. */
    suspend fun liveRegistryUtxo(script: ByteArray, outpoint: Outpoint): Utxo {
        val m = loadManifest()
        val u = liveUtxo(script, outpoint)
        if (!m.registryCovenantId.contentEquals(u.entry.covenantId)) throw ServiceError.NotOnChain("a registry UTXO")
        return u
    }

    // Signing and submit

    /** Submits a signed version-1 transaction; returns its id. Register and renew carry the price
     *  (35-8,000 TKAS) as fee on purpose - there is no high-fee guard on this path. */
    suspend fun submit(tx: Tx): String {
        requireTestnet()
        val expected = tx.idHex
        val txId = withContext(Dispatchers.IO) {
            nodePoolManager.getBroadcastConnection().submitRpcTransaction(rpcTransaction(tx))
        }
        Log.i(TAG, "submitted $txId")
        if (txId.lowercase() != expected) throw ServiceError.SubmitMismatch(expected, txId)
        return txId
    }

    /** Sign with the wallet key and submit. */
    suspend fun signAndSubmit(plan: Plan, privateKey: ByteArray, env: Env): String {
        requireTestnet()
        val tx = sign(plan, privateKey, env.me)
        return submit(tx)
    }

    // Profile record

    /**
     * Builds, signs and submits the address profile record (KACHAT_NAMES.md section 7): a
     * self-transfer with payload `kchat:1:profile:<json>`, through the existing version-0 payload
     * send ([KaspaWalletEngine.sendKaspa], the path every chat payload takes). [json] is the whole
     * profile (records replace, never patch), a JSON object of at most 2 KB. Returns the txid.
     */
    suspend fun submitProfileRecord(address: String, privateKey: ByteArray, json: ByteArray): String {
        requireTestnet()
        if (!address.lowercase().startsWith("kaspatest:")) throw ServiceError.TestnetOnly()
        if (json.size > Codec.MAX_PROFILE_JSON_BYTES) throw ServiceError.BadProfile("over 2 KB")
        val obj = runCatching { JsonParser.parseString(String(json, Charsets.UTF_8)) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: throw ServiceError.BadProfile("not a JSON object")
        val v = obj.get("v")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
        if (v != 1) throw ServiceError.BadProfile("\"v\" must be 1")
        val payload = Codec.profilePayload(json)
        return walletEngine.sendKaspa(
            toAddress = address,
            amountSompi = 0,
            payloadBytes = payload,
            fromAddress = address,
            signingPrivateKey = privateKey
        ).getOrThrow()
    }

    /**
     * What [submitProfileRecord] would pay in network fee for [json] from [address]: the same
     * checks and the same self-send, built and signed but never sent (iOS 7e238e5).
     */
    suspend fun profileRecordFee(address: String, privateKey: ByteArray, json: ByteArray): Long {
        requireTestnet()
        if (!address.lowercase().startsWith("kaspatest:")) throw ServiceError.TestnetOnly()
        if (json.size > Codec.MAX_PROFILE_JSON_BYTES) throw ServiceError.BadProfile("over 2 KB")
        return walletEngine.quotePayloadSelfSendFee(
            payloadBytes = Codec.profilePayload(json),
            fromAddress = address,
            signingPrivateKey = privateKey
        )
    }

    companion object {
        private const val TAG = "KachatNames"

        /** The only network names may run on until an audit: the network this launch runs on. */
        val isEnabled: Boolean get() = KaspaNetwork.isTestnet

        /** The signer's x-only key for a wallet private key. */
        fun xonlyKey(privateKey: ByteArray): ByteArray = Schnorr.publicKeyXOnly(privateKey)

        /**
         * The wallet's spendable funding UTXOs for the builders: the signer's own Schnorr P2PK
         * outputs only, mature, and never one carrying a covenant id (spending that would drag a
         * covenant into the transaction and change its storage mass).
         */
        fun fundingUtxos(utxos: List<Utxo>, me: ByteArray, virtualDaaScore: Long): List<Utxo> {
            val mine = Codec.p2pkScript(me)
            return utxos.filter { u ->
                val mature = !u.entry.isCoinbase || u.entry.blockDaaScore + KaspaWalletEngine.COINBASE_MATURITY < virtualDaaScore
                mature && u.entry.covenantId == null && u.entry.script.contentEquals(mine)
            }
        }

        /** A node's UTXO entry as the core's [Utxo] (null for one that does not decode). */
        fun convert(e: Rpc.RpcUtxosByAddressesEntry): Utxo? = runCatching {
            val u = e.utxoEntry
            Utxo(
                Outpoint(unhex32(e.outpoint.transactionId.lowercase()), e.outpoint.index),
                UtxoEntry(
                    amount = u.amount,
                    scriptVersion = u.scriptPublicKey.version,
                    script = unhex(u.scriptPublicKey.scriptPublicKey),
                    blockDaaScore = u.blockDaaScore,
                    isCoinbase = u.isCoinbase,
                    covenantId = u.covenantId.takeIf { it.isNotEmpty() }?.let { unhex32(it.lowercase()) }
                )
            )
        }.getOrNull()

        /** The `kaspatest:` P2SH address of a P2SH script (`OP_BLAKE2B <hash> OP_EQUAL`). */
        fun p2shAddress(script: ByteArray): String? {
            if (script.size != 35 || (script[0].toInt() and 0xff) != 0xaa || script[1].toInt() != 0x20 ||
                (script[34].toInt() and 0xff) != 0x87
            ) return null
            return KaspaAddress.encode("kaspatest", 0x08, script.copyOfRange(2, 34))
        }

        /** A fresh 32-byte commit salt. Keep it (with the name) until the registration: without it
         *  the commit cannot be registered. */
        fun newSalt(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

        /**
         * Signs every input that needs it with the wallet key: BIP-340 Schnorr over the version-1
         * SIGHASH_ALL sighash, each signature verified before it is used. [plan] must have been
         * built for this key (`Env.me`).
         */
        fun sign(plan: Plan, privateKey: ByteArray, me: ByteArray): Tx {
            val xonly = xonlyKey(privateKey)
            if (!xonly.contentEquals(me)) throw ServiceError.KeyMismatch()
            return plan.signed { sighash ->
                val message = sighash.copyOf()
                val signature = Schnorr.sign(message, privateKey)
                val ok = Schnorr.verify(message, signature, xonly)
                message.fill(0)
                if (!ok) throw KachatNames.Failure("signature did not verify")
                signature
            }
        }

        /**
         * The SubmitTransaction form of a version-1 transaction: `computeBudget` on every input
         * (`sigOpCount` must stay 0 for version 1), covenant bindings on outputs, the storage mass.
         */
        fun rpcTransaction(tx: Tx): Rpc.RpcTransaction = rpcTransaction {
            version = tx.version
            inputs.addAll(tx.inputs.map { i ->
                rpcTransactionInput {
                    previousOutpoint = rpcOutpoint {
                        transactionId = hex(i.outpoint.txid)
                        index = i.outpoint.index
                    }
                    signatureScript = hex(i.signatureScript)
                    sequence = i.sequence
                    sigOpCount = 0
                    computeBudget = i.computeBudget
                }
            })
            outputs.addAll(tx.outputs.map { o ->
                rpcTransactionOutput {
                    amount = o.value
                    scriptPublicKey = rpcScriptPublicKey {
                        version = o.scriptVersion
                        scriptPublicKey = hex(o.script)
                    }
                    o.covenant?.let { c ->
                        covenant = rpcCovenantBinding {
                            authorizingInput = c.authorizingInput
                            covenantId = hex(c.covenantId)
                        }
                    }
                }
            })
            lockTime = tx.lockTime
            subnetworkId = hex(tx.subnetworkId)
            gas = tx.gas
            payload = hex(tx.payload)
            storageMass = tx.storageMass
        }
    }
}
