package com.kachat.app.services

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 1:1 messages a push told us about, waiting to be stored (iOS SharedDataManager
 * `pending_messages`, which its notification extension now fills for EVERY 1:1 push, iOS 60c9fd0).
 *
 * The push only shows a banner; the message itself still has to reach the database. Every DM
 * push's tx id is queued here and [com.kachat.app.repository.ChatRepository.ingestPushedMessages]
 * fetches the sender's conversation until the tx is stored - so a photo, voice or long message
 * (a payload-less push) lands in the chat list without the chat being opened. Per wallet,
 * persisted, so a push handled just before the process is frozen is still picked up by the next
 * sync. Duplicates are ignored.
 */
@Singleton
class PushedMessageQueue @Inject constructor(
    @ApplicationContext context: Context,
) {
    data class Entry(val txId: String, val sender: String, val queuedAt: Long)

    private val prefs = context.getSharedPreferences("kachat_pushed_messages", Context.MODE_PRIVATE)

    private fun key(wallet: String) = "pending." + wallet.lowercase()

    @Synchronized
    fun entries(wallet: String): List<Entry> {
        val raw = prefs.getString(key(wallet), null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val txId = o.optString("txId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val sender = o.optString("sender").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Entry(txId, sender, o.optLong("queuedAt"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Queues [txId] from [sender]; false when it was already queued. */
    @Synchronized
    fun add(wallet: String, txId: String, sender: String): Boolean {
        val current = entries(wallet)
        if (current.any { it.txId == txId }) return false
        // Bounded: the oldest entries go first if a flood of pushes is never resolved.
        save(wallet, (current + Entry(txId, sender, System.currentTimeMillis())).takeLast(MAX_ENTRIES))
        return true
    }

    /** Drops [txIds] (stored, or given up on). */
    @Synchronized
    fun remove(wallet: String, txIds: Set<String>) {
        if (txIds.isEmpty()) return
        val current = entries(wallet)
        val kept = current.filterNot { it.txId in txIds }
        if (kept.size != current.size) save(wallet, kept)
    }

    private fun save(wallet: String, entries: List<Entry>) {
        if (entries.isEmpty()) {
            prefs.edit().remove(key(wallet)).apply()
            return
        }
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("txId", it.txId).put("sender", it.sender).put("queuedAt", it.queuedAt)) }
        prefs.edit().putString(key(wallet), array.toString()).apply()
    }

    companion object {
        private const val MAX_ENTRIES = 200
        /** An entry still not stored after this long is given up on (the regular sync keeps
         *  covering the sender's conversation regardless). */
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    }
}
