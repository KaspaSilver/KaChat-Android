package com.kachat.app.services

import android.content.Context

/**
 * KaPosts, Public Chats and Chess Online left the app on 2026-10-24 (iOS e2f6732
 * `RetiredFeatureCleanup`). What they kept on this phone outside the database - their
 * SharedPreferences files: KaPosts drafts, scheduled posts, thread probes, unseen counts and
 * bells, the public room read markers, verified senders and indexer-tracked rooms - is deleted
 * once. Their DataStore keys go with [com.kachat.app.repository.AppSettingsRepository.removeRetiredFeatureKeys],
 * and their database tables with the Room migration that dropped them.
 */
object RetiredFeatureCleanup {
    private const val MARKER_PREFS = "kachat_prefs"
    private const val DONE_KEY = "retired_feature_files_cleaned_v1"

    private val RETIRED_PREFS_FILES = listOf(
        "kaposts_scheduled", "kaposts_thread_probes", "kaposts_unseen", "kaposts_watch",
        "kachat_kaposts_drafts", "kachat_broadcast_read_state", "broadcast_sender_verifier",
        "kachat_indexer_tracked_rooms",
    )

    fun deleteLocalFiles(context: Context) {
        val marker = context.getSharedPreferences(MARKER_PREFS, Context.MODE_PRIVATE)
        if (marker.getBoolean(DONE_KEY, false)) return
        for (name in RETIRED_PREFS_FILES) {
            context.deleteSharedPreferences(name)
        }
        marker.edit().putBoolean(DONE_KEY, true).apply()
    }
}
