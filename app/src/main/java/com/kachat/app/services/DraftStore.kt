package com.kachat.app.services

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What was typed and not sent, per conversation - left in the composer when the reader leaves,
 * put back when they return, cleared by sending. Mirrors iOS's ChatService drafts: 1:1 chats
 * are keyed by the contact's address, group chats "group:<id>", public rooms "room:<name>"
 * (iOS 360e5d2).
 *
 * Reads and writes hit the in-memory map, so every keystroke is cheap; the disk write is
 * debounced off the typing path, as iOS does.
 */
@Singleton
class DraftStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("kachat_drafts", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    private val drafts = ConcurrentHashMap<String, String>().apply {
        runCatching {
            val json = JSONObject(prefs.getString(KEY, null) ?: "{}")
            json.keys().forEach { key -> put(key, json.getString(key)) }
        }
    }

    fun draft(key: String): String = drafts[key].orEmpty()

    fun setDraft(key: String, text: String) {
        if (text.isBlank()) {
            if (drafts.remove(key) == null) return
        } else {
            if (drafts[key] == text) return
            drafts[key] = text
        }
        scheduleSave()
    }

    @Synchronized
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            val json = JSONObject()
            drafts.forEach { (key, text) -> json.put(key, text) }
            prefs.edit().putString(KEY, json.toString()).apply()
        }
    }

    companion object {
        private const val KEY = "drafts"
        private const val SAVE_DEBOUNCE_MS = 500L

        fun groupKey(groupId: String) = "group:$groupId"
        fun roomKey(channelName: String) = "room:${channelName.trim().lowercase()}"
    }
}
