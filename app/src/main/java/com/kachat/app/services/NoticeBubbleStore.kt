package com.kachat.app.services

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bubbles a received payment_notice filed (iOS `isNoticeBubble` / `markNoticeBubble` /
 * `unmarkNoticeBubble`, IOS-083 run 11).
 *
 * A received payment a payment_notice filed in its sender's chat is that notice's to confirm (its
 * chain check, with the own-address and other-contact rules). The generic incoming-payment code
 * (the REST payment sync) knows nothing of notices, and confirming or moving such a bubble would
 * confirm a claim nobody checked - so it leaves the txids listed here alone. Per wallet (its
 * chatting address), lowercased, the newest [MAX_ENTRIES].
 */
@Singleton
class NoticeBubbleStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("kachat_notice_bubbles", Context.MODE_PRIVATE)

    private fun key(wallet: String) = "bubbles." + wallet.trim().lowercase()

    @Synchronized
    fun isNoticeBubble(wallet: String, txId: String): Boolean = txId.trim().lowercase() in ids(wallet)

    @Synchronized
    fun mark(wallet: String, txId: String) {
        val id = txId.trim().lowercase()
        if (id.isEmpty()) return
        val ids = ids(wallet)
        if (id in ids) return
        save(wallet, (ids + id).takeLast(MAX_ENTRIES))
    }

    @Synchronized
    fun unmark(wallet: String, txId: String) {
        val id = txId.trim().lowercase()
        val ids = ids(wallet)
        if (id !in ids) return
        save(wallet, ids.filter { it != id })
    }

    private fun ids(wallet: String): List<String> {
        val raw = prefs.getString(key(wallet), null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(wallet: String, ids: List<String>) {
        if (ids.isEmpty()) {
            prefs.edit().remove(key(wallet)).apply()
            return
        }
        val array = JSONArray()
        ids.forEach { array.put(it) }
        prefs.edit().putString(key(wallet), array.toString()).apply()
    }

    companion object {
        private const val MAX_ENTRIES = 2_000
    }
}
