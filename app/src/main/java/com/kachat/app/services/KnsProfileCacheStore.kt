package com.kachat.app.services

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Disk cache for what KNS says about an address: its active domain, avatar, banner and bio.
 *
 * These lived only in [com.kachat.app.viewmodels.KaPostsViewModel]'s StateFlows, so they died
 * with the process. Every cold start re-asked KNS for every author in the feed and every contact
 * in the composer's mention list - each one an owned-domains call, a reverse resolve and a
 * profile fetch - to arrive back at answers the app had already had the last time it ran.
 *
 * What is stored is the METADATA, not the pictures. The image bytes are the image loader's job
 * and it has its own disk cache; what was actually costing the requests is knowing WHICH url
 * belongs to an address, which is what this remembers.
 *
 * A null field means "asked, and there is none" - a real answer worth caching, since re-asking
 * for an address with no KNS domain is the most common wasted call of the lot. [fetchedAtMs] is
 * what makes that distinguishable from "never asked", which is the absence of a row.
 */
@Singleton
class KnsProfileCacheStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    data class Entry(
        val knsName: String? = null,
        val avatarUrl: String? = null,
        val bannerUrl: String? = null,
        val bio: String? = null,
        val fetchedAtMs: Long = 0L,
    ) {
        /** Nothing was found for this address - kept, but retried sooner. See [isFresh]. */
        val isEmpty: Boolean
            get() = knsName == null && avatarUrl == null && bannerUrl == null && bio == null
    }

    private val prefs = context.getSharedPreferences("kns_profile_cache", Context.MODE_PRIVATE)
    private val gson = Gson()
    // Writing is coalesced and done off the caller's thread. Every lookup that resolved used to
    // re-encode the WHOLE cache where it stood, and a contact sweep (opening the post composer)
    // or a long thread (one lookup per poster) resolves hundreds in a row - the app stopped for
    // as long as all those encodes took.
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var persistJob: Job? = null
    private var persistReverseJob: Job? = null
    private val entryMapType = object : TypeToken<Map<String, Entry>>() {}.type
    private val reverseMapType = object : TypeToken<Map<String, ReverseEntry>>() {}.type

    /**
     * Held in memory and written through, because every feed row reads this on composition - a
     * SharedPreferences read and a JSON parse per row would be worse than the network calls it
     * is replacing.
     */
    private var entries: MutableMap<String, Entry> = load()

    /** A good answer holds for a day: a domain, avatar or bio changes rarely, and the owner's
     *  own edits refresh through the explicit paths rather than waiting for this to expire. */
    private val freshMs = 24L * 60 * 60 * 1000

    /** An address with nothing on it is re-asked sooner than a good answer - they can inscribe a
     *  domain any time - but not much sooner: "still no domain" for a few hundred domainless
     *  contacts every hour was the single largest source of KNS traffic, for answers that
     *  essentially never change (iOS negativeRefreshInterval, six hours). A lookup that FAILED
     *  is a different thing and rests on [failureFreshMs]. */
    private val emptyFreshMs = 6L * 60 * 60 * 1000

    /** How long a lookup that could not be completed is trusted before it is re-asked. */
    private val failureFreshMs = 10L * 60 * 1000

    /** Oldest-first cap, matching the in-memory maps this backs. */
    private val maxEntries = 800
    private val trimTo = 600

    // ------------------------------------------------------------------
    // Reverse resolution (address -> display domain)
    //
    // Its own section rather than reusing Entry.knsName: that field is what a KaPosts probe
    // picked as the ACTIVE domain, which is a different question from what reverseResolve
    // answers, and conflating them would let one path's answer silently become the other's.
    // ------------------------------------------------------------------

    private var reverseEntries: MutableMap<String, ReverseEntry> = loadReverse().also { healPoisonedNegativesOnce(it) }

    /**
     * A resolved display domain for an address. `domain` null means "asked, owns none";
     * [failed] marks a null that came from a lookup that could not be completed, which rests
     * only on the short failure window rather than the negative one.
     */
    data class ReverseEntry(val domain: String?, val fetchedAtMs: Long = 0L, val failed: Boolean = false)

    /** The cached display domain, or null when there is none recent enough to use. Returns a
     *  wrapper rather than the string so "cached as no-domain" is distinguishable from "miss". */
    @Synchronized
    fun cachedReverse(address: String): ReverseEntry? {
        val entry = reverseEntries[address] ?: return null
        val age = System.currentTimeMillis() - entry.fetchedAtMs
        val limit = when {
            entry.failed -> failureFreshMs
            entry.domain == null -> emptyFreshMs
            else -> freshMs
        }
        return if (age < limit) entry else null
    }

    @Synchronized
    fun putReverse(address: String, domain: String?, failed: Boolean = false) {
        if (address.isEmpty()) return
        reverseEntries[address] = ReverseEntry(domain, System.currentTimeMillis(), failed = failed && domain == null)
        if (reverseEntries.size > maxEntries) {
            reverseEntries = reverseEntries.entries
                .sortedByDescending { it.value.fetchedAtMs }
                .take(trimTo)
                .associate { it.key to it.value }
                .toMutableMap()
        }
        persistReverse()
    }

    /** Drops the cached answer for one address, so the next lookup goes to the network. For the
     *  paths that just CHANGED something and must not read their own stale answer back. */
    @Synchronized
    fun invalidate(address: String) {
        val hadEntry = entries.remove(address) != null
        val hadReverse = reverseEntries.remove(address) != null
        if (hadEntry) persist()
        if (hadReverse) persistReverse()
    }

    @Synchronized
    fun snapshot(): Map<String, Entry> = entries.toMap()

    @Synchronized
    fun entry(address: String): Entry? = entries[address]

    /** True when [address] has an answer recent enough that asking again would learn nothing. */
    @Synchronized
    fun isFresh(address: String): Boolean {
        val entry = entries[address] ?: return false
        val age = System.currentTimeMillis() - entry.fetchedAtMs
        return age < if (entry.isEmpty) emptyFreshMs else freshMs
    }

    /**
     * Records what a probe found. Called once per address at the END of a probe, with everything
     * it learned, rather than per field - a write per field would be four disk writes for one
     * answer.
     */
    @Synchronized
    fun put(address: String, knsName: String?, avatarUrl: String?, bannerUrl: String?, bio: String?) {
        if (address.isEmpty()) return
        entries[address] = Entry(
            knsName = knsName,
            avatarUrl = avatarUrl,
            bannerUrl = bannerUrl,
            bio = bio,
            fetchedAtMs = System.currentTimeMillis(),
        )
        if (entries.size > maxEntries) {
            val keep = entries.entries
                .sortedByDescending { it.value.fetchedAtMs }
                .take(trimTo)
                .associate { it.key to it.value }
            entries = keep.toMutableMap()
        }
        persist()
    }

    /** Drops everything - wired to Settings > Storage > Cache. */
    @Synchronized
    fun clear() {
        entries = mutableMapOf()
        reverseEntries = mutableMapOf()
        prefs.edit().remove(KEY).remove(REVERSE_KEY).apply()
    }

    /** Bytes on disk, for the Cache screen's per-category size. */
    @Synchronized
    fun approximateSizeBytes(): Long =
        ((prefs.getString(KEY, null)?.length ?: 0) + (prefs.getString(REVERSE_KEY, null)?.length ?: 0)).toLong()

    private fun load(): MutableMap<String, Entry> = try {
        val json = prefs.getString(KEY, null) ?: return mutableMapOf()
        (gson.fromJson<Map<String, Entry>>(json, entryMapType) ?: emptyMap()).toMutableMap()
    } catch (e: Exception) {
        Log.w("KnsProfileCacheStore", "Could not read cache, starting empty", e)
        mutableMapOf()
    }

    @Synchronized
    private fun persist() {
        persistJob?.cancel()
        val snapshot = entries.toMap()
        persistJob = writeScope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            try {
                prefs.edit().putString(KEY, gson.toJson(snapshot, entryMapType)).apply()
            } catch (e: Exception) {
                Log.w("KnsProfileCacheStore", "Could not write cache", e)
            }
        }
    }

    private fun loadReverse(): MutableMap<String, ReverseEntry> = try {
        val json = prefs.getString(REVERSE_KEY, null) ?: return mutableMapOf()
        (gson.fromJson<Map<String, ReverseEntry>>(json, reverseMapType) ?: emptyMap()).toMutableMap()
    } catch (e: Exception) {
        Log.w("KnsProfileCacheStore", "Could not read reverse cache, starting empty", e)
        mutableMapOf()
    }

    @Synchronized
    private fun persistReverse() {
        persistReverseJob?.cancel()
        val snapshot = reverseEntries.toMap()
        persistReverseJob = writeScope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            try {
                prefs.edit().putString(REVERSE_KEY, gson.toJson(snapshot, reverseMapType)).apply()
            } catch (e: Exception) {
                Log.w("KnsProfileCacheStore", "Could not write reverse cache", e)
            }
        }
    }

    /**
     * One-time repair for negative entries written by builds that cached a failed lookup as
     * "this address has no domain" (getOwnedDomains swallowed every error into an empty list).
     * Dropping every no-domain entry makes each refetch exactly once; a real negative is then
     * written with a fresh timestamp and rests on the long negative window, a failed one on the
     * short failure window. Runs once per install (iOS healPoisonedNegativeEntriesOnce).
     */
    private fun healPoisonedNegativesOnce(reverse: MutableMap<String, ReverseEntry>) {
        if (prefs.getBoolean(NEGATIVE_HEAL_KEY, false)) return
        val poisoned = reverse.filterValues { it.domain == null }.keys
        if (poisoned.isNotEmpty()) {
            poisoned.forEach { reverse.remove(it) }
            try {
                prefs.edit().putString(REVERSE_KEY, gson.toJson(reverse, reverseMapType)).apply()
            } catch (e: Exception) {
                Log.w("KnsProfileCacheStore", "Could not write healed reverse cache", e)
            }
            Log.i("KnsProfileCacheStore", "Dropped ${poisoned.size} no-domain reverse entries for a one-time refetch")
        }
        prefs.edit().putBoolean(NEGATIVE_HEAL_KEY, true).apply()
    }

    private companion object {
        const val KEY = "entries"
        const val REVERSE_KEY = "reverse"
        const val NEGATIVE_HEAL_KEY = "reverse_negative_heal_v1"

        /** Long enough for a burst of lookups to settle into one write, short enough that the
         *  cache is on disk well before the app is closed. */
        const val PERSIST_DEBOUNCE_MS = 750L
    }
}
