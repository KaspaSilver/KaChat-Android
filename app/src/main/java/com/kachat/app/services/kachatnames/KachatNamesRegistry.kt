package com.kachat.app.services.kachatnames

import android.content.Context
import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.services.kachatnames.KachatNames.Codec
import com.kachat.app.services.kachatnames.KachatNames.hex
import com.kachat.app.util.KaspaAddress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The `.kachat` registry for the screens: lookups, an owner's names, listings, lapsed names,
 * offers, history and identities, from one of two sources -
 *
 * - **the names indexer** (KACHAT_NAMES_INDEXER.md Part D) at the indexer URL (Connection
 *   Settings), used when that field is set and `GET /names/status` answers 200 for this manifest's
 *   registry id;
 * - **the chain walker** otherwise: the registry's live UTXO set (gaps and names, plus the offers
 *   this device made) kept from the manifest's genesis gap forward. A refresh asks a node which
 *   tracked UTXOs are still unspent, finds each spent one's spending transaction through the Kaspa
 *   REST API (`GET /addresses/{p2sh}/full-transactions`), decodes the spend like the indexer does
 *   (B3), verifies every new state against its output script and moves on. Cached per network in
 *   `filesDir/KachatNames/testnet-10/` (iOS Application Support).
 *
 * Records from either source are only read here; every action re-reads its UTXOs from a node
 * ([KachatNamesService.liveRegistryUtxo]) before it builds anything. Testnet-10 only. A port of
 * iOS KaChat/Services/KachatNames/KachatNamesRegistry.swift (KaChat 27edcd5, 25cc2c9).
 */
@Singleton
class KachatNamesRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettingsRepository,
    private val service: KachatNamesService,
    okHttpClient: OkHttpClient
) {
    sealed class Source {
        data class Indexer(val base: String) : Source()
        object Chain : Source()

        val isIndexer: Boolean get() = this is Indexer
    }

    /** The address profile this wallet last wrote (the walker cannot read anyone's profile). */
    data class OwnProfile(val address: String, val profile: Profile, val txId: String, val at: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val http: OkHttpClient = okHttpClient.newBuilder()
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    private val _source = MutableStateFlow<Source?>(null)
    /** Where reads come from; null until the first [prepare]. */
    val source: StateFlow<Source?> = _source.asStateFlow()

    private val _chainState = MutableStateFlow<RegistryState?>(null)
    /** The walker's registry (chain source only). */
    val chainState: StateFlow<RegistryState?> = _chainState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    /** Why the last refresh failed, null after a good one. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _refreshedAt = MutableStateFlow<Long?>(null)
    /** Unix ms of the last good refresh. */
    val refreshedAt: StateFlow<Long?> = _refreshedAt.asStateFlow()

    private val _revision = MutableStateFlow(0)
    /** Bumped whenever registry data may have changed, so screens reload. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    @Volatile private var cacheNetwork: String? = null
    private val ownProfiles = ConcurrentHashMap<String, OwnProfile>()
    private val prepareMutex = Mutex()
    /** Serializes walker state changes (walk, offer tracking) - iOS's @MainActor. */
    private val stateMutex = Mutex()

    private fun bump() = _revision.update { it + 1 }

    // Setup

    /** The verified manifest, with the source picked and the walker's cache loaded. */
    suspend fun prepare(forceSourceCheck: Boolean = false): Manifest = withContext(Dispatchers.IO) {
        prepareMutex.withLock {
            val m = service.loadManifest()
            if (_source.value == null || forceSourceCheck) {
                _source.value = chooseSource(m)
            }
            if (_source.value == Source.Chain && (_chainState.value == null || cacheNetwork != m.network)) {
                _chainState.value = loadCache(m) ?: RegistryState.atGenesis(m)
                cacheNetwork = m.network
            }
            m
        }
    }

    /** Forget everything in memory (logout). */
    fun reset() {
        _source.value = null
        _chainState.value = null
        cacheNetwork = null
        ownProfiles.clear()
        _lastError.value = null
        _refreshedAt.value = null
        bump()
    }

    val graceMs: Long get() = service.manifest.value?.params?.graceMs ?: 864_000_000L

    private suspend fun chooseSource(m: Manifest): Source {
        val base = indexerBase() ?: return Source.Chain
        val status = try {
            IndexerApi.StatusJson.parse(get(base, "/names/status"))
        } catch (e: Exception) {
            return Source.Chain
        }
        return if (status.registryCovenantId?.lowercase() == hex(m.registryCovenantId)) Source.Indexer(base) else Source.Chain
    }

    private suspend fun indexerBase(): String? {
        val raw = settings.indexerUrl.first().trim()
        if (raw.isEmpty()) return null
        return raw.removeSuffix("/")
    }

    // Refresh

    /** Walks the chain forward (no indexer) or just marks fresh data (indexer). Safe to call often. */
    suspend fun refresh(forceSourceCheck: Boolean = false) {
        if (!KachatNamesService.isEnabled) return
        if (!_isRefreshing.compareAndSet(expect = false, update = true)) return
        try {
            val m = prepare(forceSourceCheck)
            if (_source.value == Source.Chain) walk(m)
            _lastError.value = null
            _refreshedAt.value = System.currentTimeMillis()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _lastError.value = e.message ?: e.toString()
            Log.w(TAG, "registry refresh failed: ${e.message}")
        } finally {
            _isRefreshing.value = false
            bump()
        }
    }

    /** [refresh] unless the last one is younger than [maxAgeMs] (lookups from typed names). */
    suspend fun refreshIfStale(maxAgeMs: Long = 60_000) {
        val at = _refreshedAt.value
        if (at != null && System.currentTimeMillis() - at < maxAgeMs) return
        refresh()
    }

    private suspend fun walk(m: Manifest) = withContext(Dispatchers.IO) { stateMutex.withLock {
        // a copy: the walk only replaces the published state once it succeeded
        val state = (_chainState.value ?: RegistryState.atGenesis(m)).copy()
        val registryId = hex(m.registryCovenantId)
        val report = state.walk(
            manifest = m,
            address = { KachatNamesService.p2shAddress(it) },
            live = { addresses ->
                val out = HashSet<String>()
                for (chunk in addresses.chunked(50)) {
                    for (u in service.utxosByAddresses(chunk)) {
                        // A node reports the covenant id; the REST fallback cannot (null). A UTXO
                        // carrying another id is not the registry's.
                        val c = u.entry.covenantId
                        if (c != null && hex(c) != registryId) continue
                        out.add("${hex(u.outpoint.txid)}:${u.outpoint.index}")
                    }
                }
                out
            },
            transactions = { address -> restTransactions(address) }
        )
        state.verifiedAt = System.currentTimeMillis()
        if (report.applied.isNotEmpty()) {
            Log.i(TAG, "walked ${report.applied.size} registry transaction(s) in ${report.rounds} round(s)")
        }
        if (report.unresolved.isNotEmpty()) {
            Log.i(TAG, "${report.unresolved.size} spent registry UTXO(s) wait for the REST API to index their spend")
        }
        _chainState.value = state
        saveCache(state)
    } }

    private suspend fun restBase(): String = settings.kaspaRestUrl.first().trim().removeSuffix("/")

    /** Accepted transactions touching [address], newest first (kaspa-rest-server). */
    suspend fun restTransactions(address: String): List<TxView> = withContext(Dispatchers.IO) {
        val url = "${restBase()}/addresses/$address/full-transactions?limit=50&offset=0&resolve_previous_outpoints=no"
        val request = runCatching { Request.Builder().url(url).build() }.getOrNull()
            ?: throw KachatNames.Failure("bad Kaspa REST API URL")
        http.newCall(request).execute().use { response ->
            if (response.code != 200) throw KachatNames.Failure("the Kaspa REST API answered ${response.code} for $address")
            val body = response.body?.string() ?: return@use emptyList()
            val root = JsonParser.parseString(body)
            if (!root.isJsonArray) return@use emptyList()
            root.asJsonArray.mapNotNull { e -> if (e.isJsonObject) TxView.fromREST(e.asJsonObject) else null }
        }
    }

    /** Whether the REST API has seen [txId] accepted. */
    suspend fun isAccepted(txId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "${restBase()}/transactions/$txId?inputs=false&outputs=false&resolve_previous_outpoints=no"
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.code != 200) return@use false
                val j = JsonParser.parseString(response.body?.string() ?: "")
                j.isJsonObject && j.asJsonObject.get("is_accepted")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } == true
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** After a submit: wait (up to ~2 minutes) for the transaction to be accepted, then refresh. */
    fun refreshAfter(txId: String) {
        scope.launch {
            for (attempt in 0 until 40) {
                delay(if (attempt < 5) 2_000 else 3_000)
                if (isAccepted(txId)) break
            }
            refresh()
        }
    }

    // Reads

    suspend fun lookup(raw: String): Lookup {
        prepare()
        val name = Codec.normalize(raw)
        Codec.validate(name)
        return when (val src = _source.value) {
            is Source.Indexer -> {
                val j = IndexerApi.NameJson.parse(get(src.base, "/names/$name"))
                j.info(::keyOf)?.let { Lookup.Registered(it) } ?: Lookup.Free(name, j.gap?.info)
            }
            else -> {
                val st = _chainState.value ?: throw KachatNames.Failure("the registry is not loaded")
                st.name(name)?.let { Lookup.Registered(RegistryState.info(it)) }
                    ?: Lookup.Free(name, st.gap(Codec.key(name))?.let { RegistryState.info(it) })
            }
        }
    }

    /** The names an owner holds, oldest first; [includeInactive] adds grace and lapsed ones. */
    suspend fun names(owner: ByteArray, includeInactive: Boolean): List<NameInfo> {
        prepare()
        val all: List<NameInfo> = when (val src = _source.value) {
            is Source.Indexer -> {
                val address = address(owner) ?: return emptyList()
                IndexerApi.NameJson.parseNames(get(src.base, "/names/by-owner/$address?includeInactive=$includeInactive"))
                    .mapNotNull { it.info(::keyOf) }
            }
            else -> {
                val ownerHex = hex(owner)
                (_chainState.value?.names ?: emptyList()).filter { it.owner == ownerHex }.map { RegistryState.info(it) }
            }
        }
        val grace = graceMs
        return all
            .filter { includeInactive || it.status(grace) == Status.ACTIVE }
            .sortedWith(compareBy<NameInfo>({ it.registeredAt ?: Long.MAX_VALUE }, { it.name }))
    }

    /** Active names listed for sale, most recently changed first. */
    suspend fun listings(): List<NameInfo> {
        prepare()
        return when (val src = _source.value) {
            is Source.Indexer ->
                IndexerApi.NameJson.parseListings(get(src.base, "/market/listings?sort=recent")).mapNotNull { it.info(::keyOf) }
            else -> {
                val grace = graceMs
                (_chainState.value?.names ?: emptyList()).map { RegistryState.info(it) }
                    .filter { it.isListed && it.status(grace) == Status.ACTIVE }
                    .sortedByDescending { it.updatedAt ?: 0L }
            }
        }
    }

    /** Lapsed names anyone may reclaim, oldest expiry first. */
    suspend fun lapsed(): List<NameInfo> {
        prepare()
        return when (val src = _source.value) {
            is Source.Indexer ->
                IndexerApi.NameJson.parseNames(get(src.base, "/names/expiring")).mapNotNull { it.info(::keyOf) }
            else -> {
                val grace = graceMs
                (_chainState.value?.names ?: emptyList()).map { RegistryState.info(it) }
                    .filter { it.status(grace) == Status.LAPSED }
                    .sortedBy { it.expiresAt }
            }
        }
    }

    /** Open offers on a name. Without an indexer only the offers this device made are known. */
    suspend fun offers(name: String): List<OfferInfo> {
        prepare()
        return when (val src = _source.value) {
            is Source.Indexer ->
                IndexerApi.OfferJson.parseOffers(get(src.base, "/names/$name/offers"))
                    .mapNotNull { it.info(name, ::keyOf) }.sortedByDescending { it.amount }
            else -> {
                val key = hex(Codec.key(name))
                (_chainState.value?.offers ?: emptyList()).filter { it.key == key }.map { RegistryState.info(it) }
                    .sortedByDescending { it.amount }
            }
        }
    }

    /** The offers [buyer] made that are still open. */
    suspend fun myOffers(buyer: ByteArray): List<OfferInfo> {
        prepare()
        return when (val src = _source.value) {
            is Source.Indexer -> {
                val address = address(buyer) ?: return emptyList()
                IndexerApi.OfferJson.parseOffers(get(src.base, "/offers/by-buyer/$address")).mapNotNull { it.info(null, ::keyOf) }
            }
            else -> {
                val me = hex(buyer)
                (_chainState.value?.offers ?: emptyList()).filter { it.buyer == me }.map { RegistryState.info(it) }
            }
        }
    }

    /** A name's history, newest first. The walker knows every registry transition it walked. */
    suspend fun history(name: String): List<Event> {
        prepare()
        return when (val src = _source.value) {
            is Source.Indexer -> IndexerApi.EventJson.parseEvents(get(src.base, "/names/$name/history"))
            else -> (_chainState.value?.events ?: emptyList()).filter { it.name == name }.reversed()
        }
    }

    /** Recent registry activity, newest first. */
    suspend fun activity(): List<Event> {
        prepare()
        return when (val src = _source.value) {
            is Source.Indexer -> IndexerApi.EventJson.parseEvents(get(src.base, "/market/activity"))
            else -> (_chainState.value?.events ?: emptyList()).reversed().take(200)
        }
    }

    /** The two gaps around a registered name (what release and reclaim spend): below, above. */
    suspend fun exitGaps(n: NameInfo): Pair<GapInfo, GapInfo> {
        prepare()
        return when (val src = _source.value) {
            is Source.Indexer -> {
                val down = KachatNames.step(n.key, -1)
                val up = KachatNames.step(n.key, 1)
                if (down == null || up == null) throw KachatNames.Failure("no gaps around ${n.name}")
                val below = IndexerApi.GapJson.parse(get(src.base, "/names/gap/${hex(down)}")).info
                val above = IndexerApi.GapJson.parse(get(src.base, "/names/gap/${hex(up)}")).info
                if (below == null || above == null || !below.hi.contentEquals(n.key) || !above.lo.contentEquals(n.key)) {
                    throw KachatNames.Failure("the indexer has no gaps around ${n.name}")
                }
                below to above
            }
            else -> {
                val nb = _chainState.value?.neighbours(n.key) ?: throw KachatNames.Failure("no gaps around ${n.name} yet - refresh")
                RegistryState.info(nb.first) to RegistryState.info(nb.second)
            }
        }
    }

    // Identity and profiles

    /**
     * The label and profile of an address (KACHAT_NAMES.md section 7). Without an indexer the
     * label comes from the walked names, and the profile is known only for this wallet's own
     * address (the record it last wrote).
     */
    suspend fun identity(address: String): Identity {
        prepare()
        val a = address.lowercase()
        return when (val src = _source.value) {
            is Source.Indexer -> IndexerApi.IdentityJson.parse(get(src.base, "/identity/$a")).identity
            else -> {
                val key = keyOf(a) ?: return Identity(a, null, emptyList(), null)
                val owned = names(key, includeInactive = false)
                val profile = ownProfile(a)?.profile
                val label = KachatNames.label(owned, profile?.primaryName, graceMs)
                Identity(a, label, owned.map { it.name }, profile)
            }
        }
    }

    /** The profile record this device last wrote for [address]. */
    fun ownProfile(address: String): OwnProfile? {
        val a = address.lowercase()
        ownProfiles[a]?.let { return it }
        val data = readFile(profileFile(a)) ?: return null
        val p = runCatching { gson.fromJson(String(data, Charsets.UTF_8), OwnProfile::class.java) }.getOrNull() ?: return null
        // Gson leaves absent fields null whatever their Kotlin type: a damaged file is no record
        @Suppress("SENSELESS_COMPARISON")
        if (p.address == null || p.profile == null || p.txId == null) return null
        ownProfiles[a] = p
        return p
    }

    fun noteOwnProfile(profile: Profile, address: String, txId: String) {
        val record = OwnProfile(address.lowercase(), profile.sanitized(), txId, System.currentTimeMillis())
        ownProfiles[record.address] = record
        writeFile(profileFile(record.address), gson.toJson(record).toByteArray(Charsets.UTF_8))
        bump()
    }

    /** An offer this wallet just created: tracked by the walker from now on. */
    suspend fun trackOffer(offer: OfferInfo) {
        withContext(Dispatchers.IO) { stateMutex.withLock {
            val st = _chainState.value?.copy() ?: return@withLock
            st.trackOffer(offer, System.currentTimeMillis())
            _chainState.value = st
            saveCache(st)
        } }
        bump()
    }

    // HTTP

    private suspend fun get(base: String, path: String): JsonElement = withContext(Dispatchers.IO) {
        val request = runCatching {
            Request.Builder().url(base + path).header("Accept", "application/json").build()
        }.getOrNull() ?: throw KachatNames.Failure("bad indexer URL")
        http.newCall(request).execute().use { response ->
            val code = response.code
            if (code != 200) {
                if (code == 404) throw KachatNames.Failure("not found")
                throw KachatNames.Failure("the names indexer answered $code")
            }
            JsonParser.parseString(response.body?.string() ?: "")
        }
    }

    // Cache files (filesDir/KachatNames/<network>/; iOS Application Support/KachatNames/<network>/)

    private fun directory(network: String = Manifest.SUPPORTED_NETWORK): File? = runCatching {
        File(File(context.filesDir, "KachatNames"), network).apply { mkdirs() }
    }.getOrNull()

    fun readFile(name: String): ByteArray? = runCatching { directory()?.let { File(it, name).readBytes() } }.getOrNull()

    /** Atomic: a temporary file renamed over the old one. */
    fun writeFile(name: String, data: ByteArray) {
        runCatching {
            val dir = directory() ?: return
            val tmp = File(dir, "$name.tmp")
            tmp.writeBytes(data)
            if (!tmp.renameTo(File(dir, name))) {
                File(dir, name).writeBytes(data)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "could not write $name: ${it.message}") }
    }

    private fun profileFile(address: String): String = "profile-${walletSuffix(address)}.json"

    private fun loadCache(m: Manifest): RegistryState? {
        val data = readFile(CACHE_FILE) ?: return null
        val st = runCatching { gson.fromJson(String(data, Charsets.UTF_8), RegistryState::class.java) }.getOrNull() ?: return null
        // Gson bypasses Kotlin's null checks: a file missing a list is not a cache
        @Suppress("SENSELESS_COMPARISON")
        if (st.network == null || st.registryCovenantId == null || st.gaps == null || st.names == null ||
            st.offers == null || st.applied == null || st.events == null
        ) return null
        if (!st.matches(m) || runCatching { st.checkInvariants() }.isFailure) return null
        return st
    }

    private fun saveCache(st: RegistryState) {
        writeFile(CACHE_FILE, gson.toJson(st).toByteArray(Charsets.UTF_8))
    }

    companion object {
        private const val TAG = "KachatNames"
        private const val CACHE_FILE = "registry.json"

        // Addresses

        /** The `kaspatest:` Schnorr address of an x-only key. */
        fun address(xonly: ByteArray): String? {
            if (xonly.size != 32) return null
            return KaspaAddress.encode("kaspatest", 0x00, xonly)
        }

        /** The x-only key of a `kaspatest:` Schnorr address. */
        fun keyOf(address: String): ByteArray? {
            val a = address.trim().lowercase()
            if (!a.startsWith("kaspatest:")) return null
            val (version, payload) = runCatching { KaspaAddress.decode(a) }.getOrNull() ?: return null
            if (version.toInt() != 0 || payload.size != 32) return null
            return payload
        }

        /** `kaspatest:qr...xyz4`. */
        fun shortAddress(address: String): String {
            if (address.length <= 20) return address
            return "${address.take(14)}...${address.takeLast(6)}"
        }

        /** First 8 bytes of SHA-256 of the lowercased address, hex (iOS `KeychainService.walletHashSuffix`). */
        fun walletSuffix(address: String): String =
            MessageDigest.getInstance("SHA-256").digest(address.lowercase().toByteArray(Charsets.UTF_8))
                .take(8).joinToString("") { "%02x".format(it) }
    }
}
