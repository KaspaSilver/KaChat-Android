package com.kachat.app.services

import android.content.Context
import android.content.SharedPreferences
import com.kachat.app.util.KaspaNetwork
import com.kachat.app.util.MessageProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/**
 * The rooms an indexer tracks beyond the curated ones, so a room the operator adds on the indexer
 * gets everything a default room gets - its 30 days of history kept on the phone, remote push,
 * the cellular savings - with no app update (iOS 21c480d, `IndexerTrackedRooms`). Learned two ways:
 *  - **Reported**: the app-wide indexer's `GET /broadcast-channels` (replaced on every answer).
 *  - **Served**: a room whose `/get-broadcasts` returned rows - an indexer stores only rooms it
 *    tracks. Counts for 30 days after the last rows came back (the indexer's own window), so this
 *    also works with an indexer that predates the endpoint, and with a room's own indexer.
 *
 * Per network, persisted. Thread-safe: the scanner's prune and the block loop read it off the main
 * thread. [init] runs from KaChatApplication.attachBaseContext; before it (or in unit tests that
 * never call it) the set lives in memory only.
 */
object IndexerTrackedRooms {
    private const val PREFS = "kachat_indexer_tracked_rooms"
    private const val SERVED_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
    /** A served room's timestamp is written at most once an hour: every 8 s poll lands here. */
    private const val SERVED_REWRITE_MS = 60L * 60 * 1000

    private val lock = Any()
    @Volatile private var prefs: SharedPreferences? = null
    private var loadedSuffix: String? = null
    private var reported: Set<String> = emptySet()
    private val served = HashMap<String, Long>()

    private val _changes = MutableStateFlow(0L)
    /**
     * Bumped whenever the set changes (iOS `didChange`), so the scanner's metered gate and the
     * rooms' wording follow at once. Screens collect it to recompose. Push needs no nudge here:
     * Android registers every room with its bell on, and the push service sends for the ones it
     * tracks.
     */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        prefs = (context.applicationContext ?: context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Anything learned in memory before the prefs were reachable is reloaded from disk.
        synchronized(lock) { loadedSuffix = null }
    }

    private val suffix: String get() = if (KaspaNetwork.isTestnet) ".testnet" else ""
    private fun reportedKey(suffix: String) = "reported$suffix"
    private fun servedKey(suffix: String) = "served$suffix"

    /** Call with [lock] held: loads this network's sets once (again after a network switch). */
    private fun loadIfNeeded() {
        val current = suffix
        if (loadedSuffix == current) return
        val p = prefs ?: return
        loadedSuffix = current
        reported = p.getStringSet(reportedKey(current), null)?.toSet() ?: emptySet()
        served.clear()
        val raw = p.getString(servedKey(current), null) ?: return
        runCatching {
            val json = JSONObject(raw)
            for (key in json.keys()) served[key] = json.optLong(key, 0L)
        }
    }

    fun contains(normalized: String): Boolean = synchronized(lock) {
        loadIfNeeded()
        if (normalized in reported) return@synchronized true
        val at = served[normalized] ?: return@synchronized false
        System.currentTimeMillis() - at < SERVED_WINDOW_MS
    }

    fun all(): Set<String> = synchronized(lock) {
        loadIfNeeded()
        val now = System.currentTimeMillis()
        reported + served.filterValues { now - it < SERVED_WINDOW_MS }.keys
    }

    /** The app-wide indexer's list, as `/broadcast-channels` answered. */
    fun setReported(channels: List<String>) {
        val names = channels.map(MessageProtocol::normalizeChannelName).filter { it.isNotEmpty() }.toSet()
        val changed = synchronized(lock) {
            loadIfNeeded()
            val changed = names != reported
            if (changed) {
                reported = names
                prefs?.edit()?.putStringSet(reportedKey(loadedSuffix ?: suffix), names)?.apply()
            }
            changed
        }
        if (changed) postChange()
    }

    /** [channel]'s indexer just returned rows for it. */
    fun markServed(channel: String) {
        val name = MessageProtocol.normalizeChannelName(channel)
        if (name.isEmpty()) return
        val now = System.currentTimeMillis()
        val wasTracked = synchronized(lock) {
            loadIfNeeded()
            val previous = served[name]
            val wasTracked = name in reported || (previous != null && now - previous < SERVED_WINDOW_MS)
            val stale = previous == null || now - previous > SERVED_REWRITE_MS
            if (stale) {
                served[name] = now
                prefs?.edit()?.putString(servedKey(loadedSuffix ?: suffix), JSONObject(served.toMap()).toString())?.apply()
            }
            wasTracked
        }
        if (!wasTracked) postChange()
    }

    private fun postChange() {
        _changes.update { it + 1 }
    }
}
