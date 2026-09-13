package com.kachat.app.services

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which KaPosts have been probed for "is this a thread root" (a first reply page fetched to see
 * whether the author continued their own post), and which of them turned out to be one.
 *
 * Persisted across launches: a probe answers a question about history that does not change, so
 * relaunching the app and re-asking it for every commented post in the feed was pure waste.
 * FIFO-capped so the store cannot grow without bound; the oldest claims fall off and simply get
 * probed again someday. Mirrors iOS KaPostsView.ThreadProbeClaims.
 */
@Singleton
class KaPostsThreadProbeStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("kaposts_thread_probes", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val order = ArrayDeque<String>()
    private val claimed = HashSet<String>()
    private val roots = HashSet<String>()
    private var loaded = false
    private var saveJob: Job? = null

    @Synchronized
    private fun loadIfNeeded() {
        if (loaded) return
        loaded = true
        readArray(CLAIMED_KEY).forEach { id ->
            if (claimed.add(id)) order.addLast(id)
        }
        roots.addAll(readArray(ROOTS_KEY))
    }

    private fun readArray(key: String): List<String> = try {
        val array = JSONArray(prefs.getString(key, null) ?: return emptyList())
        (0 until array.length()).map { array.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }

    /** True when this post has never been probed (and is now claimed for one). */
    @Synchronized
    fun claim(remoteId: String): Boolean {
        loadIfNeeded()
        if (!claimed.add(remoteId)) return false
        order.addLast(remoteId)
        while (order.size > CAPACITY) {
            val dropped = order.removeFirst()
            claimed.remove(dropped)
            roots.remove(dropped)
        }
        scheduleSave()
        return true
    }

    /** A probe that never got to ask its question should be asked again later. */
    @Synchronized
    fun unclaim(remoteId: String) {
        loadIfNeeded()
        if (!claimed.remove(remoteId)) return
        order.remove(remoteId)
        scheduleSave()
    }

    @Synchronized
    fun markRoot(remoteId: String) {
        loadIfNeeded()
        roots.add(remoteId)
        scheduleSave()
    }

    /** Persisted positive results - what the feed's thread-root flags start from. */
    @Synchronized
    fun persistedRoots(): Set<String> {
        loadIfNeeded()
        return roots.toSet()
    }

    /** One write per burst of claims, not one per revealed row. */
    private fun scheduleSave() {
        if (saveJob?.isActive == true) return
        saveJob = scope.launch {
            delay(1_000)
            val (claimedSnapshot, rootsSnapshot) = synchronized(this@KaPostsThreadProbeStore) {
                order.toList() to roots.toList()
            }
            prefs.edit()
                .putString(CLAIMED_KEY, JSONArray(claimedSnapshot).toString())
                .putString(ROOTS_KEY, JSONArray(rootsSnapshot).toString())
                .apply()
        }
    }

    private companion object {
        const val CLAIMED_KEY = "claimed"
        const val ROOTS_KEY = "roots"
        const val CAPACITY = 2048
    }
}
