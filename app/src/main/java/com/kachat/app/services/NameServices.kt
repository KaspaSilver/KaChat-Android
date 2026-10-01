package com.kachat.app.services

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.kachat.app.util.KaspaAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Kaspa name services besides KNS, which [KnsService] covers (`.kas`) - ported from iOS
 * NameServices.swift (df23b6f, a0dbc15):
 *
 * - `.k` - dotk (dotk.name). Read API `https://api.dotk.name/v1`, reference SDK `@dotk/sdk`.
 *   `GET /addresses/{kaspa address}` lists every live name an owner holds.
 * - `.kaspa` - Kaspa Names (kaspaname.com), covenant-backed names on L1. Read API
 *   `https://kaspaname.com/v1`, reference SDK `@kronsdk/kaspa-names`.
 *   `GET /addresses/{owner identifier}/names` lists an owner's names, where the identifier is
 *   the 64-hex x-only public key inside a P2PK address - not the `kaspa:` string itself.
 * - `.kachat` - KaChat's own names. Not live yet; listed so the app already has its place.
 *
 * Read-only, like both SDKs: nothing here needs a wallet key.
 */
enum class NameServiceTLD(val raw: String) {
    // Declaration order is the tab order: KaChat's own names first.
    KACHAT("kachat"),
    KAS("kas"),
    K("k"),
    KASPA("kaspa");

    /** ".kas", ".k" ... - the tab label and the display suffix. */
    val suffix: String get() = ".$raw"

    /** Who runs it, for empty states and links. */
    val serviceName: String
        get() = when (this) {
            KAS -> "KNS"
            K -> "dotk"
            KASPA -> "Kaspa Names"
            KACHAT -> "KaChat Names"
        }

    /** Where a person gets one of these names today. */
    val websiteUrl: String?
        get() = when (this) {
            KAS -> "https://app.knsdomains.org"
            K -> "https://dotk.name"
            KASPA -> "https://kaspaname.com"
            KACHAT -> null
        }

    /** The site's name as people know it, for "Get a .kas domain at knsdomains.org". */
    val websiteName: String?
        get() = when (this) {
            KAS -> "knsdomains.org"
            K -> "dotk.name"
            KASPA -> "kaspaname.com"
            KACHAT -> null
        }

    /** The read API this app calls for the service, shown in Connection Settings > Domains.
     *  Null for `.kas` (KNS has its own setting there) and `.kachat` (not live). */
    fun apiBaseUrl(mainnet: Boolean): String? = when (this) {
        K -> if (mainnet) "https://api.dotk.name/v1" else "https://api-tn10.dotk.name/v1"
        KASPA -> if (mainnet) "https://kaspaname.com/v1" else null
        KAS, KACHAT -> null
    }

    /** Whether the app can read this service yet. */
    val isLive: Boolean get() = this != KACHAT

    companion object {
        /** The tab Your Domains opens on: `.kachat` once it is live, KNS until then. */
        val defaultTab: NameServiceTLD get() = if (KACHAT.isLive) KACHAT else KAS

        /** The order a bare name ("bob") is tried in: KaChat's own .kachat always first, then
         *  KNS, dotk and Kaspa Names. The first that resolves is the answer; the rest are
         *  offered as "Other domains". */
        val resolutionOrder = listOf(KACHAT, KAS, K, KASPA)

        /** Splits typed input into its label and the ending the person typed, if any. Longest
         *  endings first, so "bob.kaspa" is not read as "bob.kas" + "pa". */
        fun splitTypedName(input: String): Pair<String, NameServiceTLD?> {
            val trimmed = input.trim()
            val lowered = trimmed.lowercase()
            for (tld in listOf(KACHAT, KASPA, KAS, K)) {
                if (lowered.endsWith(tld.suffix)) return trimmed.dropLast(tld.suffix.length) to tld
            }
            return trimmed to null
        }
    }
}

/**
 * Name normalization for the outside name services - adjudication-critical: a normalizer that
 * disagrees with a service's own on one byte can resolve a typed name to the wrong owner. Each
 * is a port of iOS's port of that service's SDK, which was checked against its published vectors.
 */
object NameNormalization {
    private fun isUnicodeWhitespace(cp: Int) = Character.isWhitespace(cp) || Character.isSpaceChar(cp) ||
        cp == 0x85 || cp == 0xA0 || cp == 0x2007 || cp == 0x202F

    /** `.k` (dotk, `@dotk/sdk` names.ts): trim Unicode White_Space, lowercase A-Z only, drop one
     *  trailing ".k". */
    fun dotkNormalize(input: String): String {
        val cps = input.codePoints().toArray().toMutableList()
        while (cps.isNotEmpty() && isUnicodeWhitespace(cps.first())) cps.removeAt(0)
        while (cps.isNotEmpty() && isUnicodeWhitespace(cps.last())) cps.removeAt(cps.lastIndex)
        val sb = StringBuilder()
        for (cp in cps) sb.appendCodePoint(if (cp in 0x41..0x5A) cp + 0x20 else cp)
        val s = sb.toString()
        return if (s.endsWith(".k")) s.dropLast(2) else s
    }

    /** Why [name] is not a valid `.k` label, or null when it is: 1...32 bytes of a-z, 0-9 and
     *  hyphen with no hyphen at either end. */
    fun dotkInvalidReason(name: String): String? {
        val allowed = name.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
        if (!allowed) return "allowed characters: a-z, 0-9 and hyphen"
        val bytes = name.toByteArray(Charsets.UTF_8).size
        if (bytes == 0 || bytes > 32) return "name must be 1..=32 bytes on-chain"
        if (name.startsWith("-") || name.endsWith("-")) return "name cannot start or end with a hyphen"
        return null
    }

    /** The canonical `.k` name for typed input, or null when it is not one. */
    fun dotkCanonical(input: String): String? {
        val n = dotkNormalize(input)
        return if (dotkInvalidReason(n) == null) n else null
    }

    /** `.kaspa` (Kaspa Names, `@kronsdk/kaspa-names` normalize.ts): NFKC, printable ASCII only,
     *  lowercase, drop one trailing ".kaspa"; valid when 1...32 of a-z, 0-9 and hyphen with no
     *  hyphen at either end. */
    fun kaspaNamesCanonical(input: String): String? {
        val nfkc = Normalizer.normalize(input, Normalizer.Form.NFKC)
        if (nfkc.any { it.code > 0x7E || it.code < 0x21 }) return null
        var s = nfkc.lowercase()
        if (s.endsWith(".kaspa")) s = s.dropLast(6)
        if (s.length !in 1..32) return null
        if (!s.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) return null
        if (s.startsWith("-") || s.endsWith("-")) return null
        return s
    }
}

/** What a typed name points to on one service. */
data class NameResolution(
    val tld: NameServiceTLD,
    /** The canonical name with its suffix, e.g. "bob.k". */
    val display: String,
    /** Where it points; null when the name is not registered there (or has no address to pay). */
    val address: String?,
    /** The service could not be asked (network or server failure), so "not registered" is unknown. */
    val failed: Boolean,
)

/** One name an address owns on a service other than KNS. */
data class OwnedServiceName(
    /** The bare canonical name, without the suffix. */
    val name: String,
    /** The display form, e.g. "shawn.kaspa". */
    val display: String,
    val tld: NameServiceTLD,
    /** A `.kaspa` name still inside its settling window (~1 h after registration), when an
     *  earlier hidden commit could still outrank it - shown, but marked as not final yet. */
    val isProvisional: Boolean,
)

@Singleton
class NameServicesClient @Inject constructor(
    private val knsService: KnsService,
) {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Names per service, for the address last refreshed. Empty until the first answer. */
    private val _owned = MutableStateFlow<Map<NameServiceTLD, List<OwnedServiceName>>>(emptyMap())
    val owned: StateFlow<Map<NameServiceTLD, List<OwnedServiceName>>> = _owned.asStateFlow()

    /** Services whose last lookup is still running. */
    private val _loading = MutableStateFlow<Set<NameServiceTLD>>(emptySet())
    val loading: StateFlow<Set<NameServiceTLD>> = _loading.asStateFlow()

    /** Services whose last lookup failed (network, server) - the tab says so instead of claiming
     *  there are no names. */
    private val _failed = MutableStateFlow<Set<NameServiceTLD>>(emptySet())
    val failed: StateFlow<Set<NameServiceTLD>> = _failed.asStateFlow()

    /** Whose names [owned] holds; a different address clears it first. */
    @Volatile private var ownerAddress: String? = null

    /** The network the app runs on. Mainnet unless a testnet launch is in effect. */
    @Volatile var mainnet: Boolean = true

    /** Every name [address] owns on .k and .kaspa. A lookup that fails keeps what the service
     *  last answered and marks it failed. */
    suspend fun refresh(address: String) {
        val normalized = address.trim().lowercase()
        if (normalized.isEmpty()) return
        if (ownerAddress != normalized) {
            ownerAddress = normalized
            _owned.value = emptyMap()
            _failed.value = emptySet()
        }
        _loading.value = _loading.value + setOf(NameServiceTLD.K, NameServiceTLD.KASPA)
        val (k, kaspa) = coroutineScope {
            val dotk = async { fetchDotk(normalized) }
            val names = async { fetchKaspaNames(normalized) }
            dotk.await() to names.await()
        }
        if (ownerAddress != normalized) return
        apply(k, NameServiceTLD.K)
        apply(kaspa, NameServiceTLD.KASPA)
        _loading.value = _loading.value - setOf(NameServiceTLD.K, NameServiceTLD.KASPA)
    }

    /** How many names the address owns across these services (for the "Your Domains" count). */
    val totalOwned: Int get() = _owned.value.values.sumOf { it.size }

    private fun apply(names: List<OwnedServiceName>?, tld: NameServiceTLD) {
        if (names != null) {
            _owned.value = _owned.value + (tld to names)
            _failed.value = _failed.value - tld
        } else {
            _failed.value = _failed.value + tld
        }
    }

    // .k (dotk)

    private suspend fun fetchDotk(address: String): List<OwnedServiceName>? {
        val base = NameServiceTLD.K.apiBaseUrl(mainnet) ?: return null
        // A bech32 address is path-safe as it is (":" is allowed in a path segment).
        val body = getJson("$base/addresses/$address") ?: return null
        val names = body.getAsJsonArray("names") ?: return null
        return names.mapNotNull { runCatching { it.asString.lowercase() }.getOrNull() }
            .sorted()
            .map { OwnedServiceName(name = it, display = "$it.k", tld = NameServiceTLD.K, isProvisional = false) }
    }

    // .kaspa (Kaspa Names)

    private suspend fun fetchKaspaNames(address: String): List<OwnedServiceName>? {
        // Mainnet only: the service publishes no testnet deployment.
        if (!mainnet) return emptyList()
        // The owner identifier is the x-only key a P2PK (Schnorr) address carries. Any other
        // address kind cannot own a name through this lookup.
        val key = runCatching { KaspaAddress.decode(address) }.getOrNull()
            ?.takeIf { it.first.toInt() == 0 && it.second.size == 32 }?.second ?: return emptyList()
        val identifier = key.joinToString("") { "%02x".format(it) }
        val base = NameServiceTLD.KASPA.apiBaseUrl(mainnet) ?: return null
        val body = getJson("$base/addresses/$identifier/names") ?: return null
        val entries = body.getAsJsonArray("names") ?: return null
        return entries.mapNotNull { element ->
            val entry = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            // A losing lineage is a registration that was outranked: not this owner's name.
            if (entry.boolOrNull("isWinner") == false) return@mapNotNull null
            val bare = entry.stringOrNull("name")?.lowercase() ?: return@mapNotNull null
            OwnedServiceName(
                name = bare,
                display = entry.stringOrNull("display") ?: "$bare.kaspa",
                tld = NameServiceTLD.KASPA,
                isProvisional = entry.boolOrNull("settled") == false || entry.stringOrNull("status") == "pending",
            )
        }.sortedBy { it.name }
    }

    // Forward resolution (typed name -> address)

    /** What [input] points to on every live service, in [NameServiceTLD.resolutionOrder]. A
     *  service whose own rules reject the label is left out. `.kachat` is skipped until it is
     *  live. Each service normalizes with its own rule ([NameNormalization]). */
    suspend fun resolveEverywhere(input: String): List<NameResolution> {
        val label = NameServiceTLD.splitTypedName(input).first
        if (label.isEmpty()) return emptyList()
        val results = coroutineScope {
            listOf(
                async { resolveKas(label) },
                async { resolveDotk(label) },
                async { resolveKaspaNames(label) },
            ).map { it.await() }
        }.filterNotNull().associateBy { it.tld }
        return NameServiceTLD.resolutionOrder.mapNotNull { results[it] }
    }

    /** The address a typed name points to, by [primary]'s rule - null when nothing resolves. */
    suspend fun resolvePrimary(input: String): NameResolution? =
        primary(resolveEverywhere(input), input)?.takeIf { it.address != null }

    private suspend fun resolveKas(label: String): NameResolution? {
        val canonical = KnsService.normalizeDomainLabel(label) ?: return null
        val owner = knsService.resolve("$canonical.kas")
        return NameResolution(NameServiceTLD.KAS, "$canonical.kas", owner, failed = false)
    }

    private suspend fun resolveDotk(label: String): NameResolution? {
        val canonical = NameNormalization.dotkCanonical(label) ?: return null
        val base = NameServiceTLD.K.apiBaseUrl(mainnet) ?: return null
        val display = "$canonical.k"
        return when (val outcome = getJsonOrMissing("$base/names/$canonical")) {
            is Lookup.Found -> NameResolution(NameServiceTLD.K, display, outcome.body.stringOrNull("address"), failed = false)
            Lookup.Missing -> NameResolution(NameServiceTLD.K, display, null, failed = false)
            Lookup.Failed -> NameResolution(NameServiceTLD.K, display, null, failed = true)
        }
    }

    private suspend fun resolveKaspaNames(label: String): NameResolution? {
        val canonical = NameNormalization.kaspaNamesCanonical(label) ?: return null
        val base = NameServiceTLD.KASPA.apiBaseUrl(mainnet) ?: return null
        val display = "$canonical.kaspa"
        return when (val outcome = getJsonOrMissing("$base/resolve/$canonical")) {
            is Lookup.Found -> NameResolution(NameServiceTLD.KASPA, display, outcome.body.stringOrNull("address"), failed = false)
            Lookup.Missing -> NameResolution(NameServiceTLD.KASPA, display, null, failed = false)
            Lookup.Failed -> NameResolution(NameServiceTLD.KASPA, display, null, failed = true)
        }
    }

    private sealed class Lookup {
        data class Found(val body: JsonObject) : Lookup()
        object Missing : Lookup()
        object Failed : Lookup()
    }

    /** A 404 is an answer ("not registered"), anything else that is not 2xx is a failure. */
    private suspend fun getJsonOrMissing(url: String): Lookup = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { response ->
                when {
                    response.code == 404 -> Lookup.Missing
                    !response.isSuccessful -> Lookup.Failed
                    else -> gson.fromJson(response.body?.string(), JsonObject::class.java)?.let { Lookup.Found(it) } ?: Lookup.Failed
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "${hostOf(url)} lookup failed: ${e.javaClass.simpleName}")
            Lookup.Failed
        }
    }

    /** Decoded JSON, or null for any failure (network, non-2xx, unexpected body). */
    private suspend fun getJson(url: String): JsonObject? = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "${hostOf(url)} answered ${response.code}")
                    null
                } else {
                    gson.fromJson(response.body?.string(), JsonObject::class.java)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "${hostOf(url)} failed: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun hostOf(url: String) = runCatching { java.net.URI(url).host }.getOrNull() ?: "?"

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.boolOrNull(key: String): Boolean? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    companion object {
        private const val TAG = "NameServices"

        /** Whether typed input could be a name on any service (and is not an address): a bare
         *  label, or a label with one of the known endings. */
        fun looksLikeName(input: String): Boolean {
            val trimmed = input.trim().lowercase()
            if (trimmed.startsWith("kaspa:") || trimmed.startsWith("kaspatest:")) return false
            val label = NameServiceTLD.splitTypedName(trimmed).first
            return label.isNotEmpty() && label.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        }

        /** The answer a typed name gets: the service the person named, if they typed an ending,
         *  else the first in [NameServiceTLD.resolutionOrder] that resolves. */
        fun primary(results: List<NameResolution>, typed: String): NameResolution? {
            val explicit = NameServiceTLD.splitTypedName(typed).second
            if (explicit != null) return results.firstOrNull { it.tld == explicit && it.address != null }
            return results.firstOrNull { it.address != null }
        }
    }
}
