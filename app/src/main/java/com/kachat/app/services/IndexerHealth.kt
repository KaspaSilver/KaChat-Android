package com.kachat.app.services

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the indexer is answering (iOS 4f8e14bb, KaChatAPIClient's `IndexerHealth`). It only
 * counts as down after three failures in a row, and failures within five seconds of each other
 * count once, since a dropped connection fails everything in flight together: one timeout or
 * network handoff changes nothing. While it's down, one request a minute checks whether it's
 * back, and the answer that ends the outage is announced with when it began, for the sync
 * cursors to rewind so the indexer re-serves that window.
 *
 * Every indexer request reports here (the interceptor in [IndexerApiResilience]), so nothing
 * else needs to call [reportFailure] or [reportSuccess].
 */
object IndexerHealth {
    private const val PREFS = "indexer_health"
    private const val KEY_OUTAGE_SINCE = "down_since_ms"
    private const val FAILURE_SPACING_MS = 5_000L
    private const val FAILURES_TO_GO_DOWN = 3
    /** Failures further apart than this aren't a run. */
    private const val FAILURE_WINDOW_MS = 120_000L
    private const val RETRY_INTERVAL_MS = 60_000L

    private val _isDown = MutableStateFlow(false)
    /** True while the indexer has stopped answering. */
    val isDown: StateFlow<Boolean> = _isDown.asStateFlow()

    private val _recovered = MutableSharedFlow<Long>(replay = 1, extraBufferCapacity = 8)
    /** When an outage that just ended began (ms): the sync cursors rewind to it. */
    val recovered: SharedFlow<Long> = _recovered.asSharedFlow()

    private var prefs: SharedPreferences? = null
    private var downSinceMs: Long? = null
    /** An outage the app was killed during: the first answer after launch still closes it, so
     *  the window read from the chain is read again from the indexer. */
    private var unfinishedOutageMs: Long? = null
    private var indexerUrl: String? = null
    private var failuresInARow = 0
    private var lastFailureAtMs = 0L
    private var retryAtMs = 0L

    /** Called once at app start, for an outage the app was killed during. */
    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = store
        unfinishedOutageMs = store.getLong(KEY_OUTAGE_SINCE, 0L).takeIf { it > 0L }
    }

    /** Counts toward an outage: 3 in a row at least 5 seconds apart. */
    fun reportFailure() {
        recordFailure(System.currentTimeMillis())
    }

    fun reportSuccess() {
        val wentDownAt = recordAnswer() ?: return
        _recovered.tryEmit(wentDownAt)
    }

    @Synchronized
    internal fun recordFailure(nowMs: Long): Boolean {
        val sinceLast = nowMs - lastFailureAtMs
        if (sinceLast > FAILURE_WINDOW_MS) failuresInARow = 0
        if (failuresInARow == 0 || sinceLast >= FAILURE_SPACING_MS) {
            lastFailureAtMs = nowMs
            failuresInARow += 1
        }
        if (downSinceMs != null || failuresInARow < FAILURES_TO_GO_DOWN) return false
        // still the outage the app was killed during: it began back then
        val started = unfinishedOutageMs ?: nowMs
        downSinceMs = started
        unfinishedOutageMs = null
        retryAtMs = nowMs + RETRY_INTERVAL_MS
        prefs?.edit()?.putLong(KEY_OUTAGE_SINCE, started)?.apply()
        _isDown.value = true
        return true
    }

    /** When it went down, if this answer is the moment it came back. */
    @Synchronized
    internal fun recordAnswer(): Long? {
        failuresInARow = 0
        val wentDownAt = downSinceMs ?: unfinishedOutageMs ?: return null
        downSinceMs = null
        unfinishedOutageMs = null
        prefs?.edit()?.remove(KEY_OUTAGE_SINCE)?.apply()
        _isDown.value = false
        return wentDownAt
    }

    /** True at most once a minute, while it's down: the caller sends the one cheap request. */
    @Synchronized
    internal fun claimRecoveryCheck(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (downSinceMs == null || nowMs < retryAtMs) return false
        retryAtMs = nowMs + RETRY_INTERVAL_MS
        return true
    }

    /** A different indexer in Settings (or none) ends what was known about the old one. */
    @Synchronized
    fun indexerChanged(url: String) {
        val previous = indexerUrl
        indexerUrl = url
        if (previous == null || previous == url) return
        failuresInARow = 0
        unfinishedOutageMs = null
        prefs?.edit()?.remove(KEY_OUTAGE_SINCE)?.apply()
        downSinceMs = null
        _isDown.value = false
    }

    /** Tests only: forget everything, as a fresh process would. */
    @Synchronized
    internal fun resetForTest() {
        prefs = null
        downSinceMs = null
        unfinishedOutageMs = null
        indexerUrl = null
        failuresInARow = 0
        lastFailureAtMs = 0L
        retryAtMs = 0L
        _isDown.value = false
    }
}
