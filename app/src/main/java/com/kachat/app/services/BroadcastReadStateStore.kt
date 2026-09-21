package com.kachat.app.services

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What has been read in each public room, per wallet - the Public Chats list's unread counts.
 *
 * A room's marker is the block time up to which the reader has seen it: messages from other people
 * newer than that are unread. A room seen for the first time counts from now rather than from the
 * start of its history, so joining #kaspa does not arrive with thirty days of badge. Rooms can also
 * be marked unread by hand, which shows a badge with nothing new in them. Mirrors iOS's
 * BroadcastService read state.
 */
@Singleton
class BroadcastReadStateStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class State(
        val lastReadByChannel: Map<String, Long> = emptyMap(),
        val manuallyUnread: Set<String> = emptySet(),
        /** Default rooms switched off in Public Chats settings: out of the list, never
         *  notifying, never counted. */
        val hiddenCurated: Set<String> = emptySet(),
    )

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()
    private var wallet: String? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Points the store at [address]'s read state, loading it. Safe to call on every emission. */
    fun setCurrentWallet(address: String?) = synchronized(lock) {
        val key = address?.lowercase()?.takeIf { it.isNotEmpty() }
        if (key == wallet) return@synchronized
        wallet = key
        _state.value = if (key == null) State() else load(key)
    }

    /** Starts a marker at [nowMs] for every room that has none - "counts from now". */
    fun seedIfMissing(channels: Collection<String>, nowMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        val current = _state.value
        val missing = channels.filter { it !in current.lastReadByChannel }
        if (missing.isEmpty() || wallet == null) return@synchronized
        save(current.copy(lastReadByChannel = current.lastReadByChannel + missing.associateWith { nowMs }))
    }

    /** Everything in [channel] up to [upToMs] has been seen, and any hand-set unread is cleared. */
    fun markRead(channel: String, upToMs: Long) = synchronized(lock) {
        if (wallet == null) return@synchronized
        val current = _state.value
        val marker = maxOf(upToMs, current.lastReadByChannel[channel] ?: 0L)
        save(
            current.copy(
                lastReadByChannel = current.lastReadByChannel + (channel to marker),
                manuallyUnread = current.manuallyUnread - channel,
            )
        )
    }

    /** Puts a badge back on [channel] so it is come across again. */
    fun markUnread(channel: String) = synchronized(lock) {
        if (wallet == null) return@synchronized
        val current = _state.value
        save(current.copy(manuallyUnread = current.manuallyUnread + channel))
    }

    /** Shows or hides one of the default rooms in Public Chats. */
    fun setCuratedShown(channel: String, shown: Boolean) = synchronized(lock) {
        if (wallet == null) return@synchronized
        val current = _state.value
        save(current.copy(hiddenCurated = if (shown) current.hiddenCurated - channel else current.hiddenCurated + channel))
    }

    /** A room left for good takes its read state with it. */
    fun forget(channel: String) = synchronized(lock) {
        if (wallet == null) return@synchronized
        val current = _state.value
        save(current.copy(lastReadByChannel = current.lastReadByChannel - channel, manuallyUnread = current.manuallyUnread - channel))
    }

    /** Whether the one-time "curated rooms notify by default" has been applied for this wallet. */
    fun featuredNotifyDefaultApplied(): Boolean = synchronized(lock) {
        val key = wallet ?: return@synchronized true
        prefs.getBoolean("featured_notify_default_$key", false)
    }

    fun markFeaturedNotifyDefaultApplied() = synchronized(lock) {
        val key = wallet ?: return@synchronized
        prefs.edit().putBoolean("featured_notify_default_$key", true).apply()
    }

    private fun load(key: String): State {
        val markers = runCatching {
            val json = JSONObject(prefs.getString("last_read_$key", null) ?: "{}")
            json.keys().asSequence().associateWith { json.getLong(it) }
        }.getOrDefault(emptyMap())
        val unread = prefs.getStringSet("manual_unread_$key", emptySet())?.toSet().orEmpty()
        val hidden = prefs.getStringSet("hidden_curated_$key", emptySet())?.toSet().orEmpty()
        return State(markers, unread, hidden)
    }

    private fun save(state: State) {
        val key = wallet ?: return
        _state.value = state
        val json = JSONObject().apply { state.lastReadByChannel.forEach { (channel, ms) -> put(channel, ms) } }
        prefs.edit()
            .putString("last_read_$key", json.toString())
            .putStringSet("manual_unread_$key", state.manuallyUnread)
            .putStringSet("hidden_curated_$key", state.hiddenCurated)
            .apply()
    }

    private companion object {
        const val PREFS = "kachat_broadcast_read_state"
    }
}
