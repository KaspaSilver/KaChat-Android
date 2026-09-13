package com.kachat.app.ui.screens

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A post saved for later: the whole composer state, not just its text.
 *
 * Stored per wallet and never sent anywhere - a draft is a private note to yourself until you
 * deliberately post it, so it has no on-chain footprint and stays out of backups. Mirrors iOS's
 * `KaPostSavedDraft` / `KaPostsDraftStore`.
 */
data class KaPostSavedDraft(
    val id: String,
    val text: String,
    val threadSegments: List<String>,
    /**
     * The post this draft REPLIES to, when it was written as a reply. Only the id is kept - the
     * post itself is resolved on open, so a draft never carries a stale copy of someone else's
     * post. Null for a plain post or a thread.
     */
    val replyRemoteId: String? = null,
    val savedAt: Long,
) {
    /** One line for the drafts list: the first segment that has anything in it. */
    val preview: String
        get() = (listOf(text) + threadSegments).firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    /** Everything this draft would post, for the "3 posts" count on its row. */
    val segmentCount: Int
        get() = (listOf(text) + threadSegments).count { it.isNotBlank() }
}

object KaPostDraftStore {
    private const val PREFS = "kachat_kaposts_drafts"

    private fun key(walletAddress: String) = "drafts_" + walletAddress.replace(":", "_")

    fun load(context: Context, walletAddress: String): List<KaPostSavedDraft> {
        if (walletAddress.isEmpty()) return emptyList()
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(walletAddress), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val segments = o.optJSONArray("threadSegments")
                KaPostSavedDraft(
                    id = o.getString("id"),
                    text = o.optString("text"),
                    threadSegments = if (segments == null) emptyList() else {
                        (0 until segments.length()).map { segments.getString(it) }
                    },
                    // Absent in drafts saved before replies had a parent - decodes to null, so
                    // nothing stored earlier is disturbed.
                    replyRemoteId = o.optString("replyRemoteId").takeIf { it.isNotEmpty() },
                    savedAt = o.optLong("savedAt"),
                )
            }
        }.getOrDefault(emptyList()).sortedByDescending { it.savedAt }
    }

    /** Saves a new draft, or updates [id] when re-saving one that was opened for editing. */
    fun save(
        context: Context,
        walletAddress: String,
        id: String?,
        text: String,
        threadSegments: List<String>,
        replyRemoteId: String? = null,
    ) {
        if (walletAddress.isEmpty()) return
        if ((listOf(text) + threadSegments).all { it.isBlank() }) return
        val draft = KaPostSavedDraft(
            id = id ?: UUID.randomUUID().toString(),
            text = text,
            threadSegments = threadSegments,
            replyRemoteId = replyRemoteId,
            savedAt = System.currentTimeMillis(),
        )
        val updated = listOf(draft) + load(context, walletAddress).filter { it.id != draft.id }
        persist(context, walletAddress, updated)
    }

    fun delete(context: Context, walletAddress: String, id: String) {
        persist(context, walletAddress, load(context, walletAddress).filter { it.id != id })
    }

    private fun persist(context: Context, walletAddress: String, drafts: List<KaPostSavedDraft>) {
        val array = JSONArray()
        drafts.forEach { d ->
            array.put(
                JSONObject()
                    .put("id", d.id)
                    .put("text", d.text)
                    .put("threadSegments", JSONArray(d.threadSegments))
                    .put("replyRemoteId", d.replyRemoteId ?: "")
                    .put("savedAt", d.savedAt)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key(walletAddress), array.toString())
            .apply()
    }
}
