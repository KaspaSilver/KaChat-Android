package com.kachat.app.services

import android.content.Context
import androidx.work.WorkManager
import com.kachat.app.repository.KasPriceRepository
import java.io.File

/**
 * What the features that left the app on 2026-10-24 kept on this phone outside the database is
 * deleted once (iOS `RetiredFeatureCleanup`, e2f6732 / d54ea87):
 * - KaPosts, Public Chats and Chess Online (iOS e2f6732): KaPosts drafts, scheduled posts, thread
 *   probes, unseen counts and bells, the public room read markers, verified senders and
 *   indexer-tracked rooms;
 * - Portfolio, Cold Storage and the .kachat market (iOS d54ea87): Portfolio's fees, tombstones and
 *   price history (the current KAS price stays: the Send screens read it), the KasSigner accounts
 *   and the .kachat name notifier's and reminders' state;
 * - Simple Mode (iOS eb2ccac): its password record and attempt counters;
 * - voice and video calls (iOS 7c5c22e3): the handled call ids and the call notification channels.
 * Their DataStore keys go with [com.kachat.app.repository.AppSettingsRepository.removeRetiredFeatureKeys],
 * and their database tables with the Room migrations that dropped them.
 */
object RetiredFeatureCleanup {
    private const val MARKER_PREFS = "kachat_prefs"

    private class Pass(val doneKey: String, val prefsFiles: List<String>, val extra: (Context) -> Unit = {})

    private val PASSES = listOf(
        Pass(
            "retired_feature_files_cleaned_v1",
            listOf(
                "kaposts_scheduled", "kaposts_thread_probes", "kaposts_unseen", "kaposts_watch",
                "kachat_kaposts_drafts", "kachat_broadcast_read_state", "broadcast_sender_verifier",
                "kachat_indexer_tracked_rooms",
            ),
        ),
        Pass(
            "retired_feature_files_cleaned_v2",
            listOf(
                "kachat_portfolio_fees", "kachat_portfolio_tombstones", "cold_storage_secure_prefs",
                "kachat_names_notifier", "kachat_name_reminders",
            ),
        ) { context ->
            // Portfolio's chart history: its files, and every entry of the shared prefs file but
            // the current prices.
            File(context.noBackupFilesDir, "price_history").deleteRecursively()
            val prices = context.getSharedPreferences(KasPriceRepository.PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prices.edit()
            prices.all.keys
                .filterNot { it.startsWith(KasPriceRepository.CURRENT_PRICE_KEY_PREFIX) }
                .forEach { editor.remove(it) }
            editor.apply()
            // the .kachat expiry reminders still queued: their worker left with them
            WorkManager.getInstance(context).cancelAllWorkByTag("kachat-reminder-")
        },
        // Simple Mode (iOS eb2ccac): its salted-hash password record and attempt counters
        Pass("retired_feature_files_cleaned_v3", listOf("kachat_child_mode_prefs")),
        // Voice and video calls (iOS 7c5c22e3 RetiredFeatureCleanup.runOnceV4): the handled call
        // ids, and the two call notification channels. Each account's Nextcloud Talk check goes
        // with NextcloudService's next load of that account.
        Pass("retired_feature_files_cleaned_v4", listOf("kachat_calls")) { context ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.getSystemService(android.app.NotificationManager::class.java)?.let { manager ->
                    manager.deleteNotificationChannel("kachat_incoming_call")
                    manager.deleteNotificationChannel("kachat_calls")
                }
            }
        },
    )

    fun deleteLocalFiles(context: Context) {
        val marker = context.getSharedPreferences(MARKER_PREFS, Context.MODE_PRIVATE)
        for (pass in PASSES) {
            if (marker.getBoolean(pass.doneKey, false)) continue
            for (name in pass.prefsFiles) {
                context.deleteSharedPreferences(name)
            }
            runCatching { pass.extra(context) }
            marker.edit().putBoolean(pass.doneKey, true).apply()
        }
    }
}
