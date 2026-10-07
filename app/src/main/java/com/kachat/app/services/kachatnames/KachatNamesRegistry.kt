package com.kachat.app.services.kachatnames

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.services.kachatnames.KachatNames.Codec
import com.kachat.app.services.kachatnames.KachatNames.hex
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaNetwork
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
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
    okHttpClient: OkHttpClient,
    /** The Profile bell's .kachat news, run after every good refresh (iOS 86471dd). Lazy: it
     *  reads the registry itself. */
    private val notifier: dagger.Lazy<KachatNamesNotifier>
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
    /** Addresses with no saved profile file: [ownProfile] is read while names render, so a miss
     *  is remembered instead of touching the disk again (until [noteOwnProfile]). */
    private val ownProfileMisses = ConcurrentHashMap.newKeySet<String>()

    /**
     * `.kachat` identities by lowercased address, for the app's display rules (names, avatars,
     * banners, bios everywhere - see [cachedIdentity]; iOS e52357d). Compose snapshot state, so a
     * composable that read a name or avatar through it re-renders when an answer lands - iOS
     * publishes the cache and tells ContactsManager. Written under [identityLock].
     */
    private var identities by mutableStateOf<Map<String, Identity>>(emptyMap())
    /** When each identity was last asked: the registry revision and unix ms. Not state - a
     *  re-asked answer that is unchanged re-renders nothing. */
    private val identityAsked = ConcurrentHashMap<String, Pair<Int, Long>>()
    private val identityLookups = ConcurrentHashMap.newKeySet<String>()
    /** When an address's lookup last failed (unix ms): it isn't asked again for five minutes, so a
     *  composable that reads [cachedIdentity] can't turn an unreachable indexer into a request
     *  loop (iOS d36fc42). */
    private val identityMisses = ConcurrentHashMap<String, Long>()
    /** Set when the indexer said it doesn't serve profiles (503) - see [profileOnlyIdentity]. */
    @Volatile private var profilesUnavailableUntil: Long = 0L
    private val identityLock = Any()
    private val prepareMutex = Mutex()
    /** Serializes walker state changes (walk, offer tracking) - iOS's @MainActor. */
    private val stateMutex = Mutex()

    private fun bump() = _revision.update { it + 1 }

    init {
        // the display rules (ContactEntity.displayName, ContactAvatar) reach the cache through
        // the companion, from code Hilt does not inject (built at startup on testnet, see
        // KaChatApplication)
        instance = this
    }

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
        ownProfileMisses.clear()
        synchronized(identityLock) { identities = emptyMap() }
        identityAsked.clear()
        identityMisses.clear()
        profilesUnavailableUntil = 0L
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
        // the indexer must follow this manifest's registry (registry v4 has no price covenant; iOS c8f1086)
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
        // Launched networks only (iOS 7227d69): mainnet never reads a registry.
        if (!KachatNamesService.isLaunched) return
        if (!_isRefreshing.compareAndSet(expect = false, update = true)) return
        val previousError = _lastError.value
        try {
            val m = prepare(forceSourceCheck)
            if (_source.value == Source.Chain) walk(m)
            _lastError.value = null
            _refreshedAt.value = System.currentTimeMillis()
            bump()
            // what changed for this wallet's names and offers, into the Profile bell (iOS 86471dd)
            scope.launch { notifier.get().check() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // nothing changed: no bump (a screen that refreshes on `revision` and is recomposed
            // by the bump would cancel and restart itself)
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e.toString()
            _lastError.value = message
            // A failed refresh counts as an attempt too: `refreshIfStale` waits `maxAge` before
            // the next one, and screens that reload on `revision` (and refresh from there) are
            // only nudged when the error changed - a refusal can't turn into a refresh loop
            // (iOS d2e0673).
            _refreshedAt.value = System.currentTimeMillis()
            if (message != previousError) {
                if (!KachatNamesService.isRegistryUpgrading(e)) Log.w(TAG, "registry refresh failed: $message")
                bump()
            }
        } finally {
            _isRefreshing.value = false
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
                        // carrying another id is not the registry's (registry v4 has no price
                        // record, iOS c8f1086).
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

    /**
     * Which of [addresses] hold at least one .kachat name (active or in grace - the same set Your
     * Domains lists, [heldNames]; iOS aa36d2a). Drives the "Contains domain" tag (and the funded-first sort) on Manage
     * Addresses and KasSigner. Empty where the registry isn't launched; an address whose lookup
     * fails just isn't tagged (iOS 881ada6 `ownersOfNames(among:)`, gated by 7227d69).
     */
    suspend fun ownersOfNames(addresses: List<String>): Set<String> {
        if (!KachatNamesService.isLaunched || addresses.isEmpty()) return emptySet()
        if (refreshedAt.value == null) refresh()
        val owners = mutableSetOf<String>()
        for (address in addresses) {
            val key = keyOf(address) ?: continue
            val owned = try {
                heldNames(key)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            if (owned.isNotEmpty()) owners.add(address)
        }
        return owners
    }

    /**
     * The names an owner still holds, oldest first: active ones and expired ones in grace (still
     * renewable). A lapsed name is no longer theirs - it's in the marketplace's Available tab.
     * Your Domains, its count on Profile and the "Contains domain" tag all show this set (iOS aa36d2a).
     */
    suspend fun heldNames(owner: ByteArray): List<NameInfo> = held(names(owner, includeInactive = true), graceMs)

    /**
     * Keeps a [heldNames] answer true as time passes: waits until the next of [names] lapses, then
     * hands back the ones still held, until none is left to lapse (or the caller is cancelled). A
     * lapse is just the clock running out, so no registry change announces it (iOS aa36d2a).
     */
    suspend fun dropLapsed(names: List<NameInfo>, update: (List<NameInfo>) -> Unit) {
        var still = names
        while (true) {
            val now = KachatNames.nowMs()
            val next = nextLapse(still, graceMs, now) ?: return
            delay(next - now + 500)
            still = held(still, graceMs)
            update(still)
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
            // an expired name's old listing is not for sale, whatever the indexer kept (iOS ba1a734)
            is Source.Indexer ->
                forSale(IndexerApi.NameJson.parseListings(get(src.base, "/market/listings?sort=recent")).mapNotNull { it.info(::keyOf) }, graceMs)
            else -> {
                val grace = graceMs
                (_chainState.value?.names ?: emptyList()).map { RegistryState.info(it) }
                    .filter { it.isListed && it.status(grace) == Status.ACTIVE }
                    .sortedByDescending { it.updatedAt ?: 0L }
            }
        }
    }

    /** Lapsed names anyone may claim, oldest expiry first (the Available tab, iOS eea52b2). */
    suspend fun lapsed(): List<NameInfo> {
        prepare()
        return when (val src = _source.value) {
            is Source.Indexer ->
                IndexerApi.NameJson.parseNames(get(src.base, "/names/expiring")).mapNotNull { it.info(::keyOf) }
            else -> {
                val grace = graceMs
                reclaimable((_chainState.value?.names ?: emptyList()).map { RegistryState.info(it) }, grace)
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

    /**
     * Recent registry activity, newest first: every registration, renewal, extension, listing,
     * sale, offer, transfer, release and reclaim. An indexer serves it at `GET /names/activity`;
     * one without that endpoint yet answers only market events (`/market/activity`, iOS 0765ce0).
     */
    suspend fun activity(): List<Event> {
        prepare()
        return when (val src = _source.value) {
            is Source.Indexer -> {
                val all = try {
                    IndexerApi.EventJson.parseEvents(get(src.base, "/names/activity"))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                all ?: IndexerApi.EventJson.parseEvents(get(src.base, "/market/activity"))
            }
            // registry v4 has no price changes: every event is a name's (iOS c8f1086)
            else -> (_chainState.value?.events ?: emptyList()).reversed().take(200)
        }
    }

    /**
     * The fixed prices (registry v4, baked into the pinned templates; iOS c8f1086): sompi for a
     * name's first period, and for every further one, by length 1, 2, 3, 4, 5+ bytes. Null until a
     * manifest loads.
     */
    val registerPrices: List<Long>? get() = service.manifest.value?.params?.registerPrices
    val renewPrices: List<Long>? get() = service.manifest.value?.params?.renewPrices

    /**
     * The free gap a lapsed name's reclaim reopens - the two gaps around it, merged: where a claim
     * of it registers. The claim sheet prices with it; the registration driver reclaims the old
     * record first and then looks the gap up again (iOS eea52b2).
     */
    suspend fun claimGap(n: NameInfo): GapInfo {
        val (below, above) = exitGaps(n)
        return mergedGap(below, above)
    }

    /**
     * [lookup] as the app shows a name to someone who wants it: a lapsed name is free to claim
     * (claiming it frees the old record and registers it in one go - see the registration driver),
     * in the gap its reclaim reopens (iOS eea52b2).
     */
    suspend fun claimLookup(raw: String): Lookup {
        val found = lookup(raw)
        val lapsed = lapsedRecord(found, graceMs) ?: return found
        val gap = try {
            claimGap(lapsed)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return Lookup.Free(lapsed.name, gap)
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
        val a = address.lowercase()
        // No registry on this network yet (mainnet): no names or label, only the profile (iOS d36fc42).
        if (!KachatNamesService.isLaunched) return profileOnlyIdentity(a)
        prepare()
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

    /**
     * An address's profile where the network has no registry yet (mainnet): the indexer's
     * `GET /profiles/{address}` (the KaChat indexer at Connection Settings' indexer URL, the same
     * base the names reads use), falling back to the record this device last wrote for its own
     * address (iOS d36fc42). The indexer answers 503 until it follows profiles on this network
     * (kachat-indexer docs/KACHAT_PROFILES.md); then only this wallet's own profile shows.
     */
    private suspend fun profileOnlyIdentity(address: String): Identity {
        ownProfile(address)?.profile?.let { return Identity(address, null, emptyList(), it) }
        // An indexer without profiles on this network answers 503 for every address: one such
        // answer pauses all profile lookups for ten minutes instead of one request per contact.
        val base = indexerBase()
        if (base == null || System.currentTimeMillis() < profilesUnavailableUntil) {
            throw KachatNames.Failure("profiles are not indexed on this network yet")
        }
        try {
            val j = IndexerApi.ProfileJson.parse(get(base, "/profiles/$address"))
            return Identity(address, null, emptyList(), j.profile?.sanitized())
        } catch (e: KachatNames.Failure) {
            if (e.message?.endsWith("answered 503") == true) {
                profilesUnavailableUntil = System.currentTimeMillis() + 600_000L
            }
            throw e
        }
    }

    /**
     * The address's `.kachat` identity as the app shows it, from a cache that fills in the
     * background: callable from any composable or thread (on mainnet, profile only - iOS
     * d36fc42). An answer is re-asked once the registry moved on or after five minutes, a failed
     * address not for five minutes, and this wallet's own saved profile always wins for its own
     * address. When an answer lands, composables that read it re-render (iOS e52357d
     * `cachedIdentity(for:)`).
     */
    fun cachedIdentity(address: String): Identity? {
        // Every network since iOS d36fc42: profiles need no registry. Mainnet has no names yet,
        // so there an address keeps its short form and only its profile (avatar...) shows.
        if (!KachatNamesService.profilesEnabled) return null
        val key = address.trim().lowercase()
        if (KaspaNetwork.ofAddress(key) == null || !KaspaNetwork.isOnActiveNetwork(key)) return null
        val known = identities[key]
        val rev = _revision.value
        val now = System.currentTimeMillis()
        val asked = identityAsked[key]
        val stale = asked == null || asked.first != rev || now - asked.second > (if (known == null) 60_000L else 300_000L)
        val missedRecently = identityMisses[key]?.let { now - it < 300_000L } == true
        if (stale && !missedRecently && identityLookups.add(key)) {
            identityAsked[key] = rev to now
            scope.launch {
                try {
                    val found = identity(key)
                    identityMisses.remove(key)
                    synchronized(identityLock) {
                        if (identities[key] != found) identities = identities + (key to found)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // the registry or profiles are not there (being upgraded, offline, a 503):
                    // this address is asked again in five minutes
                    identityMisses[key] = System.currentTimeMillis()
                } finally {
                    identityLookups.remove(key)
                }
            }
        }
        val own = ownProfile(key)?.profile ?: return known
        return (known ?: Identity(key, null, emptyList(), null)).copy(profile = own)
    }

    /** The profile record this device last wrote for [address]. */
    fun ownProfile(address: String): OwnProfile? {
        val a = address.lowercase()
        ownProfiles[a]?.let { return it }
        if (a in ownProfileMisses) return null
        val data = readFile(profileFile(a), profileNetwork(a)) ?: run { ownProfileMisses.add(a); return null }
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
        ownProfileMisses.remove(record.address)
        writeFile(profileFile(record.address), gson.toJson(record).toByteArray(Charsets.UTF_8), profileNetwork(record.address))
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

    fun readFile(name: String, network: String = Manifest.SUPPORTED_NETWORK): ByteArray? =
        runCatching { directory(network)?.let { File(it, name).readBytes() } }.getOrNull()

    /** Atomic: a temporary file renamed over the old one. */
    fun writeFile(name: String, data: ByteArray, network: String = Manifest.SUPPORTED_NETWORK) {
        runCatching {
            val dir = directory(network) ?: return
            val tmp = File(dir, "$name.tmp")
            tmp.writeBytes(data)
            if (!tmp.renameTo(File(dir, name))) {
                File(dir, name).writeBytes(data)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "could not write $name: ${it.message}") }
    }

    private fun profileFile(address: String): String = "profile-${walletSuffix(address)}.json"

    /** The cache folder an address's own profile lives in: its network's (testnet keeps the
     *  registry's folder, so profiles saved before mainnet profiles existed are still found;
     *  iOS d36fc42). */
    private fun profileNetwork(address: String): String =
        if (KaspaNetwork.ofAddress(address) == KaspaNetwork.Type.MAINNET) "mainnet" else Manifest.SUPPORTED_NETWORK

    private fun loadCache(m: Manifest): RegistryState? {
        val data = readFile(CACHE_FILE) ?: return null
        val st = runCatching { gson.fromJson(String(data, Charsets.UTF_8), RegistryState::class.java) }.getOrNull() ?: return null
        // Gson bypasses Kotlin's null checks: a file missing a list is not a cache
        @Suppress("SENSELESS_COMPARISON")
        if (st.network == null || st.registryCovenantId == null ||
            st.gaps == null || st.names == null || st.offers == null || st.applied == null || st.events == null
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

        @Volatile private var instance: KachatNamesRegistry? = null

        /** The app's registry once built (at startup on every network since iOS d36fc42 - for
         *  profiles; see KaChatApplication), for
         *  screens outside the .kachat hub that show an address's identity (User Info). */
        val shared: KachatNamesRegistry? get() = instance

        /** The names an owner still holds: active ones and expired ones in grace (still renewable).
         *  A lapsed name is no longer theirs - it's in the marketplace's Available tab (iOS e26562e, eea52b2). */
        fun held(names: List<NameInfo>, graceMs: Long, nowMs: Long = KachatNames.nowMs()): List<NameInfo> =
            names.filter { it.status(graceMs, nowMs) != Status.LAPSED }

        /** The gap a reclaim of the name between [below] and [above] reopens: the two merged,
         *  at the lower one's outpoint (iOS eea52b2 `claimGap`). */
        fun mergedGap(below: GapInfo, above: GapInfo): GapInfo = GapInfo(below.lo, above.hi, below.outpoint)

        /** The registered record [l] holds when it has lapsed (expired past grace): that name is
         *  free to claim (iOS eea52b2 `claimLookup`); null for a live record or a free name. */
        fun lapsedRecord(l: Lookup, graceMs: Long, nowMs: Long = KachatNames.nowMs()): NameInfo? =
            (l as? Lookup.Registered)?.info?.takeIf { it.status(graceMs, nowMs) == Status.LAPSED }

        /** When the next of [names] lapses (unix ms), null when none is left to lapse ([dropLapsed]). */
        fun nextLapse(names: List<NameInfo>, graceMs: Long, nowMs: Long = KachatNames.nowMs()): Long? =
            names.map { it.expiresAt + graceMs }.filter { it > nowMs }.minOrNull()

        /** Lapsed names anyone may claim, oldest expiry first (the Available tab, iOS eea52b2). */
        fun reclaimable(names: List<NameInfo>, graceMs: Long, nowMs: Long = KachatNames.nowMs()): List<NameInfo> =
            names.filter { it.status(graceMs, nowMs) == Status.LAPSED }.sortedBy { it.expiresAt }

        /** Listings that still stand: a listing only means something while the name is active
         *  (an expired name can't be bought, only renewed or reclaimed; iOS ba1a734). */
        fun forSale(listings: List<NameInfo>, graceMs: Long, nowMs: Long = KachatNames.nowMs()): List<NameInfo> =
            listings.filter { it.isListed && it.status(graceMs, nowMs) == Status.ACTIVE }

        // The display rules (iOS e52357d): testnet identity is .kachat everywhere

        /** The address's cached `.kachat` identity ([cachedIdentity]); profile only on mainnet. */
        fun cachedIdentityOf(address: String): Identity? = instance?.cachedIdentity(address)

        /** Testnet: `<label>.kachat` for an address with an active name (its primary one), null
         *  otherwise or while it is looked up; always null on mainnet. */
        fun kachatName(address: String): String? = cachedIdentityOf(address)?.label?.let { "$it.kachat" }

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

        /**
         * The network prefix plus both ends of the address on one line: `kaspatest:qr4x7k...a9z2pq`.
         * Used where the full address doesn't fit (the name detail's Owner card, iOS 71448d8).
         */
        fun compactAddress(address: String): String {
            val colon = address.indexOf(':')
            if (colon < 0) return address
            val prefix = address.substring(0, colon + 1)
            val body = address.substring(colon + 1)
            if (body.length <= 14) return address
            return "$prefix${body.take(6)}...${body.takeLast(6)}"
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

// Social profile: avatar, banner and bio (looked up on the device)

/**
 * Turns a profile's social link ([SocialSource]) into what that platform shows right now - avatar,
 * banner, bio - and caches the answer on this device; no indexer involved. iOS
 * `KachatSocialImageResolver` (KachatNamesRegistry.swift, ad32798 / 1322216).
 *
 * The cache holds the picture URLs and the bio; Coil downloads and keeps the images. An answer is
 * fresh for 24 hours; a stale one is still shown while it is looked up again. When the platform
 * answers but no longer shows something (taken down, account gone), it is dropped at once, so the
 * platform's moderation carries over. When the platform can't be reached, the last answer stays.
 *
 * One cache for every network, as on iOS (UserDefaults `kachat_social_profile_cache`): it is keyed
 * by the normalized social link, which means the same thing on any network.
 */
@Singleton
class KachatSocialImageResolver @Inject constructor(
    @ApplicationContext context: Context,
    okHttpClient: OkHttpClient
) {
    data class Entry(val profile: SocialProfile, val checkedAt: Long)

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * No request outlives 8 s (iOS c124cb3's ephemeral session); each step sets its own shorter
     * timeout on its call ([fetch], iOS 683d311). The app's client keeps no cookies and no HTTP
     * cache, so nothing is written to a shared store either. Redirects are followed, as URLSession
     * does.
     */
    private val http: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // ContactAvatar reads .kachat avatars through the companion (iOS e52357d)
        instance = this
    }

    private val _entries = MutableStateFlow(load())
    /** The cache by normalized link: screens collect it so a lookup that lands re-renders them
     *  (iOS `@Published entries`). */
    val entries: StateFlow<Map<String, Entry>> = _entries.asStateFlow()

    /** What a lookup came back with (iOS `KachatSocialImageResolver.Lookup`, c124cb3). */
    sealed class Lookup {
        abstract val profile: SocialProfile?

        /** The platform answered (possibly with nothing: taken down, account gone). */
        data class Answered(override val profile: SocialProfile) : Lookup()

        /** It couldn't be reached in time; the last answer this device had, if any. */
        data class Unreachable(override val profile: SocialProfile?) : Lookup()
    }

    private val inFlight = HashMap<String, Deferred<Lookup>>()
    private val lock = Any()

    /** The cached profile for [link] in [entries] (a snapshot screens collected), null when
     *  there is none or the link isn't a supported one. */
    fun cached(link: String?, entries: Map<String, Entry> = _entries.value): SocialProfile? =
        key(link)?.let { entries[it]?.profile }

    /** Starts a lookup for [link] when there is no answer or it is stale (iOS `profile(for:)`'s
     *  side; screens call it when the link appears and read [cached]). */
    fun refreshIfStale(link: String?) {
        val source = link?.let { SocialSource.from(it, SocialSource.Kind.AVATAR) } ?: return
        val entry = _entries.value[source.link]
        if (entry == null || System.currentTimeMillis() - entry.checkedAt > FRESH_FOR_MS) {
            scope.launch { resolve(source) }
        }
    }

    /**
     * Looks the profile up now (the editor's preview), sharing a lookup in flight. An answer under
     * [maxAgeMs] old is the answer, so the three fields of one account cost one request (iOS
     * 0f44a07). A lookup never takes longer than 20 s, every request and fallback included, and
     * each step has its own shorter timeout so a slow first source can't use up the time the next
     * one needs (c124cb3, 683d311).
     */
    suspend fun resolve(source: SocialSource, maxAgeMs: Long = 300_000): Lookup {
        val key = source.link
        _entries.value[key]?.let { if (System.currentTimeMillis() - it.checkedAt < maxAgeMs) return Lookup.Answered(it.profile) }
        val deferred = synchronized(lock) {
            inFlight[key] ?: scope.async {
                val started = System.currentTimeMillis()
                // Cancelling at the deadline cancels the OkHttp call in progress (see fetch).
                val answered = withTimeoutOrNull(DEADLINE_MS) { lookUp(source) }
                Log.i(TAG, "${source.platform.name.lowercase()} ${if (answered == null) "unreachable" else "answered"} " +
                    "in ${"%.1f".format(java.util.Locale.US, (System.currentTimeMillis() - started) / 1000.0)}s")
                if (answered == null) return@async Lookup.Unreachable(_entries.value[key]?.profile) // keep the last answer
                // The platform answered - with something, or with nothing (taken down, account gone).
                _entries.update { it + (key to Entry(answered, System.currentTimeMillis())) }
                persist()
                Lookup.Answered(answered)
            }.also { d ->
                inFlight[key] = d
                d.invokeOnCompletion { synchronized(lock) { if (inFlight[key] === d) inFlight.remove(key) } }
            }
        }
        return deferred.await()
    }

    private fun load(): Map<String, Entry> = runCatching {
        val raw = prefs.getString(CACHE_KEY, null) ?: return emptyMap()
        val root = JsonParser.parseString(raw).asJsonObject
        val out = HashMap<String, Entry>()
        for ((k, v) in root.entrySet()) {
            if (!v.isJsonObject) continue
            val o = v.asJsonObject
            fun s(name: String): String? = o.get(name)?.takeIf { it.isJsonPrimitive }?.asString
            val at = o.get("checkedAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: continue
            out[k] = Entry(SocialProfile(s("avatar"), s("banner"), s("bio")), at)
        }
        out
    }.getOrElse { emptyMap() }

    private fun persist() {
        synchronized(lock) {
            var all = _entries.value
            if (all.size > MAX_ENTRIES) {
                val oldest = all.entries.sortedBy { it.value.checkedAt }.take(all.size - MAX_ENTRIES).map { it.key }.toSet()
                all = all - oldest
                _entries.value = all
            }
            val root = com.google.gson.JsonObject()
            for ((k, e) in all) {
                val o = com.google.gson.JsonObject()
                e.profile.avatar?.let { o.addProperty("avatar", it) }
                e.profile.banner?.let { o.addProperty("banner", it) }
                e.profile.bio?.let { o.addProperty("bio", it) }
                o.addProperty("checkedAt", e.checkedAt)
                root.add(k, o)
            }
            prefs.edit().putString(CACHE_KEY, root.toString()).apply()
        }
    }

    /**
     * The platform's answer (possibly empty: taken down, account gone), or null when it couldn't
     * be reached or answered with an error - nothing is known then.
     */
    private suspend fun lookUp(source: SocialSource): SocialProfile? {
        when (source.platform) {
            SocialSource.Platform.DISCORD -> {
                val (body, status) = fetch("https://discord.com/api/v10/invites/${source.handle}", BROWSER_AGENT) ?: return null
                if (status == 404) return SocialProfile()
                if (status != 200) return null
                return SocialProfile(
                    avatar = SocialSource.discordImage(body, SocialSource.Kind.AVATAR),
                    banner = SocialSource.discordImage(body, SocialSource.Kind.BANNER),
                    bio = SocialSource.discordDescription(body)
                )
            }
            SocialSource.Platform.X -> {
                // FxTwitter first: one small JSON answer with avatar, banner and bio. X's own page
                // (served to link-preview crawlers) is the fallback, and unavatar.io the last
                // resort for the avatar alone (iOS c124cb3, 683d311). Each step's outcome is
                // logged: a phone network can be challenged or rate-limited where a desktop is not.
                val answer = fetch("https://api.fxtwitter.com/${source.handle}", BROWSER_AGENT, timeoutSec = 5)
                answer?.takeIf { it.second == 200 || it.second == 404 }
                    ?.let { SocialSource.fxTwitterProfile(it.first) }
                    ?.let { return it }
                Log.i(TAG, "x ${source.handle}: FxTwitter ${answer?.let { "HTTP ${it.second}" } ?: "no answer"}")
            }
            SocialSource.Platform.GITHUB -> {
                val (body, status) = fetch("https://api.github.com/users/${source.handle}", BROWSER_AGENT) ?: return null
                if (status == 404) return SocialProfile()
                if (status != 200) return null
                val (avatar, bio) = SocialSource.githubProfile(body)
                return SocialProfile(avatar = avatar, banner = null, bio = bio)
            }
            else -> Unit
        }
        val page = fetch(source.link, CRAWLER_AGENT)
        when (val verdict = pageVerdict(page?.second, page?.first)) {
            PageVerdict.Gone -> return SocialProfile()
            is PageVerdict.NotRead -> {
                // A login wall, a challenge or a script shell is "couldn't look it up", never
                // cached as an empty answer that would read as "this account has no avatar".
                Log.i(TAG, "${source.platform.name.lowercase()} ${source.handle}: ${verdict.reason}")
                return xAvatarOnly(source)
            }
            PageVerdict.Profile -> Unit
        }
        val html = page!!.first
        val image = SocialSource.openGraphImage(html)
        val banner = when (source.platform) {
            SocialSource.Platform.X -> SocialSource.xBanner(html)
            SocialSource.Platform.YOUTUBE -> fetch(source.link, BROWSER_AGENT, cookie = "CONSENT=YES+1")
                ?.takeIf { it.second == 200 }?.let { SocialSource.youtubeBanner(it.first) }
            else -> null
        }
        return SocialProfile(
            avatar = image?.let { if (source.platform == SocialSource.Platform.X) SocialSource.xAvatar(it) else it },
            banner = banner,
            bio = SocialSource.bio(source.platform, SocialSource.openGraphDescription(html))
        )
    }

    /**
     * X only, when FxTwitter and X's page both failed: the avatar from unavatar.io, which answers
     * with the image itself (404 when the account has none). null = still unreachable (iOS
     * `xAvatarOnly`, 683d311).
     */
    private suspend fun xAvatarOnly(source: SocialSource): SocialProfile? {
        if (source.platform != SocialSource.Platform.X) return null
        val url = "https://unavatar.io/x/${source.handle}?fallback=false"
        val (body, status) = fetch(url, BROWSER_AGENT, timeoutSec = 5) ?: return null
        if (status != 200 || body.isEmpty()) {
            Log.i(TAG, "x ${source.handle}: unavatar HTTP $status")
            return null
        }
        return SocialProfile(avatar = url)
    }

    /**
     * The body (first 3 MB, as text) and status, or null when nothing came back within
     * [timeoutSec] (iOS 683d311: 6 s by default, 5 s for FxTwitter and unavatar.io). Suspends on
     * an enqueued call so that cancelling the lookup (its 20 s deadline) cancels the request too.
     */
    private suspend fun fetch(url: String, agent: String, cookie: String? = null, timeoutSec: Long = 6): Pair<String, Int>? {
        val request = runCatching {
            Request.Builder().url(url)
                .header("User-Agent", agent)
                .header("Accept-Language", "en-US,en;q=0.8")
                .apply { if (cookie != null) header("Cookie", cookie) }
                .build()
        }.getOrNull() ?: return null
        val call = http.newCall(request)
        call.timeout().timeout(timeoutSec, TimeUnit.SECONDS)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val result = try {
                        response.use {
                            val source = it.body?.source()
                            val buffer = okio.Buffer()
                            if (source != null) {
                                while (buffer.size < MAX_BODY_BYTES) {
                                    if (source.read(buffer, MAX_BODY_BYTES - buffer.size) == -1L) break
                                }
                            }
                            buffer.readString(Charsets.UTF_8) to it.code
                        }
                    } catch (_: Exception) {
                        null
                    }
                    if (cont.isActive) cont.resume(result)
                }
            })
        }
    }

    companion object {
        /** The app's resolver once built (at startup on testnet, see KaChatApplication), for the
         *  avatar every list draws (ContactAvatar) - code Hilt does not inject. */
        @Volatile var instance: KachatSocialImageResolver? = null
            private set

        private const val TAG = "KachatSocial"
        private const val PREFS = "kachat_prefs"
        private const val CACHE_KEY = "kachat_social_profile_cache"
        private const val FRESH_FOR_MS = 24L * 3600 * 1000
        private const val MAX_ENTRIES = 500
        private const val MAX_BODY_BYTES = 3_000_000L
        /** Hard limit for one lookup, every step included: a preview never spins longer than
         *  this (iOS c124cb3; 20 s since 683d311, each step having its own shorter timeout). */
        private const val DEADLINE_MS = 20_000L
        /** The link-preview crawler user agent: X, TikTok and others serve their Open Graph tags to it. */
        private const val CRAWLER_AGENT = "facebookexternalhit/1.1"
        /** A desktop browser: YouTube's desktop channel page carries the banner in plain form (the
         *  mobile page escapes it); GitHub's API wants a User-Agent. iOS's exact string. */
        private const val BROWSER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"

        /** The cache key of a social link: its normalized form; null for an unsupported link. */
        fun key(link: String?): String? = link?.let { SocialSource.from(it, SocialSource.Kind.AVATAR)?.link }

        /**
         * What a profile page's answer ([status] and [html], both null when nothing came back)
         * says (iOS 683d311): the account is gone (404/410, an empty answer), it is a profile, or
         * the page couldn't be read - no answer, an error status, or a page with no profile tags
         * at all (a login wall, a challenge or a script shell), which is "couldn't look it up",
         * never "this account has no avatar".
         */
        fun pageVerdict(status: Int?, html: String?): PageVerdict = when {
            status == null || html == null -> PageVerdict.NotRead("page no answer")
            status == 404 || status == 410 -> PageVerdict.Gone
            status != 200 -> PageVerdict.NotRead("page HTTP $status")
            SocialSource.openGraphImage(html) == null && SocialSource.openGraphDescription(html) == null ->
                PageVerdict.NotRead("page has no profile tags (${html.toByteArray(Charsets.UTF_8).size} bytes)")
            else -> PageVerdict.Profile
        }
    }

    /** See [pageVerdict]. */
    sealed class PageVerdict {
        object Gone : PageVerdict()
        object Profile : PageVerdict()
        /** [reason] is what the log says. */
        data class NotRead(val reason: String) : PageVerdict()
    }
}
