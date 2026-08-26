package com.kachat.app.services

import android.util.Log
import com.kachat.app.util.KaspaMessageSigner
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KnsService @Inject constructor(
    private val networkService: NetworkService
) {
    /**
     * NetworkService.knsApi starts out null and only becomes non-null once the
     * DataStore-backed URL setting has been read and the Retrofit client built — both
     * async. A plain `.value` snapshot here loses that race almost every time a caller
     * runs right at startup (e.g. ChatsScreen's `LaunchedEffect(Unit)` KNS refresh),
     * silently no-op'ing every lookup forever since there's no retry after that. Once
     * set, it never goes back to null (NetworkService keeps the previous working client
     * on any later failure), so waiting here costs at most the one-time startup delay.
     */
    private suspend fun api() = networkService.knsApi.filterNotNull().first()

    /** "alice.kas" -> the Kaspa address that owns it, or null if unresolvable/not found. */
    suspend fun resolve(domain: String): String? {
        return try {
            val response = api().resolveDomain(normalizeDomain(domain))
            if (response.success) response.data?.owner else null
        } catch (e: Exception) {
            null // Includes the real API's 404 for "domain has no owner" — not an error.
        }
    }

    /**
     * Kaspa address -> the domain to display for it, or null if it owns none at all.
     * An address can own a domain without ever explicitly marking it "primary" (a
     * separate off-chain action, see [setPrimaryDomain]) — the primary-name endpoint alone
     * 404s for those, even though the owner very much has a real, verified domain. Falls
     * back to the first verified domain they own, matching iOS KNSService's exact rule:
     * `finalPrimary = primaryDomain ?? allDomains.first?.fullName`.
     */
    suspend fun reverseResolve(address: String): String? {
        return getExplicitPrimaryDomain(address) ?: getOwnedDomains(address).firstOrNull()?.asset
    }

    /** The address's explicitly-set primary domain, or null if none has ever been set — unlike [reverseResolve], this does NOT fall back to "first owned domain". */
    suspend fun getExplicitPrimaryDomain(address: String): String? {
        return try {
            val response = api().getPrimaryName(address)
            if (response.success) response.data?.domain?.fullName else null
        } catch (e: Exception) {
            null // Includes the real API's 404 for "no primary name set" — not an error.
        }
    }

    /** Every verified domain an address owns — a contact may have more than one. */
    suspend fun getOwnedDomains(address: String): List<KnsAsset> {
        return try {
            val response = api().getAssetsByOwner(address)
            response.data?.assets
                ?.filter { it.isDomain == true && it.isVerifiedDomain == true && it.asset != null && it.assetId != null }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** address -> (fetchedAtMs, domains) cache for [getOwnedDomainsCached] — keeps the address
     *  lists' "Contains domain" tags from re-hitting the KNS API on every screen load. */
    private val ownedDomainsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<KnsAsset>>>()

    /** Cached variant of [getOwnedDomains] — a fresh fetch at most every [maxAgeMs] per address. */
    suspend fun getOwnedDomainsCached(address: String, maxAgeMs: Long = 10 * 60_000L): List<KnsAsset> {
        val now = System.currentTimeMillis()
        ownedDomainsCache[address]?.let { (fetchedAt, domains) ->
            if (now - fetchedAt < maxAgeMs) return domains
        }
        val domains = getOwnedDomains(address)
        ownedDomainsCache[address] = now to domains
        return domains
    }

    /**
     * Which of [addresses] own at least one verified KNS domain — batched cached lookups in
     * slices of 4 concurrent requests (mirrors iOS KNSService.refreshIfNeeded's concurrency
     * cap), powering the "Contains domain" tags on the spending/cold address lists.
     */
    suspend fun domainOwningAddresses(addresses: List<String>): Set<String> = coroutineScope {
        val owners = mutableSetOf<String>()
        for (chunk in addresses.chunked(4)) {
            val results: List<Pair<String, List<KnsAsset>>> = chunk.map { address ->
                async {
                    address to (try { getOwnedDomainsCached(address) } catch (e: Exception) { emptyList<KnsAsset>() })
                }
            }.awaitAll()
            for ((address, domains) in results) {
                if (domains.isNotEmpty()) owners.add(address)
            }
        }
        owners
    }

    /** Avatar/bio/social-links profile attached to a specific owned domain (by its assetId, not its name). */
    suspend fun getProfile(assetId: String): KnsProfileFields? {
        return try {
            val response = api().getDomainProfile(assetId)
            if (response.success) response.data?.profile else null
        } catch (e: Exception) {
            null
        }
    }

    /** Inscription fee tiers in KAS, keyed by label-length tier 1-5 (5 = "5+ characters"). Throws on failure — unlike reads, a failed fee lookup must stop an inscription, not silently no-op. */
    suspend fun fetchInscribeFeeTiers(): Map<Int, Double> {
        val response = api().getInscribeFee()
        if (!response.success) throw IllegalStateException(response.error ?: response.message ?: "KNS fee fetch failed")
        val rawFeeMap = response.data?.fee?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("KNS fee response is missing tier data")
        val mapped = rawFeeMap.mapNotNull { (key, value) -> key.toIntOrNull()?.takeIf { it > 0 }?.let { it to value } }.toMap()
        if (mapped.isEmpty()) throw IllegalStateException("KNS fee response has invalid tier data")
        return mapped
    }

    /** Whether a domain is available to register. Throws on failure, same reasoning as [fetchInscribeFeeTiers]. */
    suspend fun checkDomainAvailability(address: String, domainName: String): DomainAvailability {
        val normalized = normalizeDomain(domainName)
        val response = api().checkDomainAvailability(KnsDomainCheckRequest(address = address, domainNames = listOf(normalized)))
        if (!response.success) throw IllegalStateException(response.error ?: response.message ?: "KNS domain check failed")
        val domains = response.data?.domains?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("KNS domain check response is empty")
        val matched = domains.firstOrNull { it.domain.lowercase() == normalized.lowercase() } ?: domains[0]
        return DomainAvailability(domain = matched.domain.lowercase(), available = matched.available, isReservedDomain = matched.isReservedDomain)
    }

    /**
     * Uploads a profile avatar/banner image, signing the auth message with each
     * [KaspaMessageSigner.SigningMode] in turn until the server accepts one — matches iOS's
     * `uploadProfileImageWithSignatureFallback` exactly, including its crude but real
     * server-response-driven fallback rule (only retry the NEXT mode when the failure looks like
     * a signature/auth rejection, not any other error).
     */
    suspend fun uploadProfileImageWithFallback(
        assetId: String,
        uploadType: String,
        imageBytes: ByteArray,
        walletPrivateKey: ByteArray
    ): String {
        val signMessage = """{"assetId":"$assetId","uploadType":"$uploadType"}"""
        return withSigningFallback(signMessage, walletPrivateKey) { signature ->
            val response = api().uploadImage(
                signMessage = signMessage.toRequestBody("text/plain".toMediaTypeOrNull()),
                signature = signature.toRequestBody("text/plain".toMediaTypeOrNull()),
                image = MultipartBody.Part.createFormData(
                    "image",
                    "$uploadType-$assetId.png",
                    imageBytes.toRequestBody("image/png".toMediaTypeOrNull())
                )
            )
            if (!response.success || response.data?.success == false) {
                throw IllegalStateException(response.error ?: response.data?.message ?: response.message ?: "KNS image upload failed")
            }
            response.data?.data?.imageUrl?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("KNS upload response missing image URL")
        }
    }

    /**
     * Marks [domainId] as the wallet's primary (reverse-resolution) domain — purely off-chain,
     * a wallet-signed REST call with no on-chain transaction. Matches iOS's
     * `submitSetPrimaryDomainWithSignatureFallback` (`ContactsView.swift:1157-1191`), including
     * reusing the exact same 3-mode signing fallback as image upload.
     */
    suspend fun setPrimaryDomain(domainId: String, walletPrivateKey: ByteArray) {
        val signMessage = """{"domainId":"$domainId","timestamp":${System.currentTimeMillis()}}"""
        withSigningFallback(signMessage, walletPrivateKey) { signature ->
            val response = api().setPrimaryDomain(KnsSetPrimaryNameRequest(signMessage = signMessage, signature = signature))
            if (!response.success) throw IllegalStateException(response.error ?: response.message ?: "Failed to set primary domain")
        }
    }

    /**
     * Signs [signMessage] with each [KaspaMessageSigner.SigningMode] in turn and calls [attempt]
     * until one succeeds — the shared retry shape behind every wallet-signed (non-transaction)
     * KNS API call. Retries only when the failure looks like a signature/auth rejection (matches
     * iOS's crude-but-real substring check); any other error aborts immediately.
     */
    private suspend fun <T> withSigningFallback(signMessage: String, walletPrivateKey: ByteArray, attempt: suspend (signature: String) -> T): T {
        var lastError: Exception? = null
        for (mode in KaspaMessageSigner.SigningMode.entries) {
            val signature = KaspaMessageSigner.sign(signMessage, walletPrivateKey, mode)
            try {
                return attempt(signature)
            } catch (e: Exception) {
                // Retrofit throws a generic HttpException with no real detail on non-2xx
                // responses (e.g. "HTTP 413 ") — the server's actual rejection reason lives in
                // the error body, which has to be read out explicitly.
                val serverMessage = (e as? HttpException)?.response()?.errorBody()?.string()
                val effective = serverMessage?.takeIf { it.isNotBlank() } ?: e.message
                Log.w("KnsService", "Signed KNS request failed mode=$mode", e)
                lastError = IllegalStateException(effective, e)
                if (!isSignatureVerificationFailure(effective)) throw lastError as Exception
                // else fall through and retry with the next signing mode
            }
        }
        throw lastError ?: IllegalStateException("Signed KNS request failed")
    }

    companion object {
        /** Matches iOS's crude-but-real fallback trigger: only advance to the next signing mode on what looks like an auth/signature rejection, not any other error. */
        internal fun isSignatureVerificationFailure(message: String?): Boolean {
            val lower = message?.lowercase() ?: return false
            return lower.contains("signature verification failed") || lower.contains("unauthorized")
        }

        /**
         * A contact may own several domains; picks which one to treat as "theirs" for
         * this chat. Prefers whatever's already selected (as long as they still own it),
         * then their on-chain primary domain, then just the first they own — mirrors iOS
         * KNSService's fallback chain (primaryInscriptionId -> primaryDomain -> most recent).
         */
        internal fun pickActiveDomain(owned: List<String>, currentSelection: String?, primary: String?): String? {
            if (currentSelection != null && owned.contains(currentSelection)) return currentSelection
            if (primary != null && owned.contains(primary)) return primary
            return owned.firstOrNull()
        }

        /**
         * Matches both real clients' heuristic for "this looks like a KNS domain, not a
         * raw address" — reject Kaspa address prefixes, accept anything else that's
         * either explicitly ".kas"-suffixed or looks like a bare domain label.
         */
        internal fun looksLikeDomain(input: String): Boolean {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return false
            if (trimmed.startsWith("kaspa:") || trimmed.startsWith("kaspatest:")) return false
            if (trimmed.endsWith(".kas")) return true
            return trimmed.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        }

        /** The real API expects the ".kas" suffix on every lookup — append it if the user typed a bare label. */
        internal fun normalizeDomain(input: String): String {
            val trimmed = input.trim()
            return if (trimmed.endsWith(".kas")) trimmed else "$trimmed.kas"
        }

        /**
         * Validates and normalizes a user-typed domain LABEL (no ".kas" suffix) for registration —
         * matches iOS's `normalizeDomainLabel` exactly: lowercase, strip a typed ".kas" suffix,
         * reject empty/leading-or-trailing-hyphen/non [a-z0-9-] input.
         */
        internal fun normalizeDomainLabel(raw: String): String? {
            var value = raw.trim().lowercase()
            if (value.isEmpty()) return null
            if (value.endsWith(".kas")) value = value.dropLast(4)
            if (value.isEmpty()) return null
            if (value.startsWith("-") || value.endsWith("-")) return null
            if (!value.all { it.isLetterOrDigit() || it == '-' }) return null
            return value
        }

        /** Fee-tier number for a label's length: 1-4 are exact, 5 means "5 or more characters". */
        internal fun feeTierForLabel(label: String): Int = label.length.coerceIn(1, 5)

        /** Tier fee lookup with iOS's exact fallback: use tier 5's fee if the specific tier is missing. */
        internal fun feeForTier(tier: Int, feeTiers: Map<Int, Double>): Double =
            feeTiers[tier] ?: feeTiers[5] ?: throw IllegalStateException("KNS fee tier data is missing")

        /** The amount actually paid for the domain (0 for server-flagged reserved domains). */
        internal fun revealAmountKas(tierFee: Double, isReservedDomain: Boolean): Double =
            if (isReservedDomain) 0.0 else tierFee

        /** Funds the reveal output + network fees: reveal*1.05 rounded, floored at 2 KAS when reveal<=1. */
        internal fun commitAmountKas(revealKas: Double): Double {
            if (revealKas <= 1.0) return 2.0
            return Math.round(revealKas * 1.05).toDouble()
        }
    }
}

data class DomainAvailability(val domain: String, val available: Boolean, val isReservedDomain: Boolean)
