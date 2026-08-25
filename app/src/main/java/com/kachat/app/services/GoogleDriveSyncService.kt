package com.kachat.app.services

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.repository.ChatRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Automatic Google Drive chat-history persistence — the Android counterpart of what iCloud /
 * CloudKit does invisibly on iOS ([MessageStore]'s `NSPersistentCloudKitContainer` with
 * per-wallet zones): once the user is signed into Google Drive backup, messages flow to their
 * own cloud account automatically and restore automatically on a fresh install or new wallet
 * activation, with no modal and no manual step.
 *
 * Three cooperating triggers, all funneled through one wallet-snapshotted, mutex-serialized
 * upload path:
 *
 *   1. **Debounced activity upload** — [ChatRepository.insertMessage] (the single choke point
 *      every sent/received/imported message goes through) calls [noteMessageActivity]. That
 *      snapshots the ACTIVE wallet, marks its archive dirty (persisted, so a killed process
 *      can't lose the fact that a backup is owed), and restarts a quiet-time timer
 *      ([DEBOUNCE_MS]); a rapid exchange coalesces into one upload after things settle.
 *   2. **Periodic WorkManager fallback** ([DriveAutoSyncWorker], every 6h, network connected +
 *      battery not low) — catches uploads the debounce path missed because the process died
 *      first. It only uploads when the persisted dirty flag says something actually changed,
 *      so an idle device costs zero Drive quota.
 *   3. **Automatic restore** — whenever a wallet becomes active (app start, wallet
 *      import/creation, account switch) and once per explicit Drive sign-in, the wallet's own
 *      Drive file (if any) is imported silently in the background through the same
 *      txId-deduped import path the manual restore uses. One log line and a small toast with
 *      counts; the blocking modal remains exclusive to the MANUAL restore flow.
 *
 * Everything is scoped per wallet, mirroring iOS's per-wallet CloudKit zones: the Drive file
 * is per-address ([GoogleDriveBackupService.backupFileNameFor]), and every setting here (the
 * Automatic Drive Sync toggle, last-synced stamp, dirty flag, restored-once marker) is a
 * DataStore key carrying the wallet's 8-byte SHA256 hash suffix (same scheme as
 * [NextcloudService.walletHashSuffix]). A wallet switch mid-debounce can never cross-pollinate:
 * the upload re-checks that the snapshotted wallet is still active immediately before building
 * the archive and again before uploading, and drops the work otherwise (the NextcloudService
 * auto-backup pattern).
 *
 * Lifecycle: an init observer keeps the periodic work enqueued exactly while Drive backup is
 * signed in AND the active wallet's auto-sync toggle is on, and cancels it otherwise — so
 * sign-out (or flipping the toggle off) cancels scheduled work. Account deletion goes through
 * [purgeStoredState], which drops that wallet's keys and pending debounce and touches nothing
 * belonging to other wallets.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class GoogleDriveSyncService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val walletManager: WalletManager,
    private val googleDriveBackupService: GoogleDriveBackupService,
    private val settingsRepository: AppSettingsRepository,
    private val backupRestoreCoordinator: BackupRestoreCoordinator,
    // Lazy: ChatHistoryExportImportService depends on ChatRepository, which depends (lazily)
    // back on this service for noteMessageActivity — same cycle-break as ChatRepository's own
    // chatHistoryExportImportServiceLazy.
    private val chatHistoryExportImportServiceLazy: dagger.Lazy<ChatHistoryExportImportService>,
    private val chatRepositoryLazy: dagger.Lazy<ChatRepository>
) {
    companion object {
        private const val TAG = "GoogleDriveSync"

        /** Quiet time after the last message before the automatic upload runs. */
        const val DEBOUNCE_MS = 2 * 60 * 1000L

        /** Periodic WorkManager fallback cadence for uploads the debounce path missed. */
        const val PERIODIC_INTERVAL_HOURS = 6L

        const val WORK_NAME = "kachat_drive_auto_sync"

        // Per-wallet DataStore key bases; the wallet's hash suffix is appended.
        private const val KEY_AUTO_SYNC_ENABLED = "drive_auto_sync_enabled"
        private const val KEY_LAST_SYNC_MS = "drive_auto_sync_last_ms"
        private const val KEY_PENDING_CHANGES = "drive_auto_sync_pending"
        private const val KEY_RESTORE_DONE = "drive_auto_restore_done"

        private fun enabledKey(address: String) =
            booleanPreferencesKey("${KEY_AUTO_SYNC_ENABLED}_${NextcloudService.walletHashSuffix(address)}")

        private fun lastSyncKey(address: String) =
            longPreferencesKey("${KEY_LAST_SYNC_MS}_${NextcloudService.walletHashSuffix(address)}")

        private fun pendingKey(address: String) =
            booleanPreferencesKey("${KEY_PENDING_CHANGES}_${NextcloudService.walletHashSuffix(address)}")

        private fun restoreDoneKey(address: String) =
            booleanPreferencesKey("${KEY_RESTORE_DONE}_${NextcloudService.walletHashSuffix(address)}")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serializes uploads and restores so they can never interleave half-written state. */
    private val syncMutex = Mutex()

    private var debounceJob: Job? = null

    /** The wallet the pending debounced upload belongs to — re-checked before every step. */
    @Volatile
    private var debounceWallet: String? = null

    /**
     * The ACTIVE wallet's "Automatic Drive Sync" toggle. Defaults ON: once the user signs into
     * Drive backup, automatic sync is the expected behavior (iCloud parity), and the master
     * "Back up to Google Drive" switch still gates everything.
     */
    val autoSyncEnabled: StateFlow<Boolean> = walletManager.activeAddressFlow
        .flatMapLatest { address ->
            if (address == null) flowOf(false)
            else dataStore.data.map { it[enabledKey(address)] ?: true }
        }
        .stateIn(scope, SharingStarted.Eagerly, walletManager.activeAddressFlow.value != null)

    /** When the ACTIVE wallet's archive last uploaded automatically (epoch ms), null = never. */
    val lastAutoSyncMs: StateFlow<Long?> = walletManager.activeAddressFlow
        .flatMapLatest { address ->
            if (address == null) flowOf(null)
            else dataStore.data.map { it[lastSyncKey(address)]?.takeIf { ms -> ms > 0 } }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        // Keep the periodic fallback job enqueued exactly while it can do anything: Drive
        // backup signed in (master toggle) AND the active wallet's auto-sync on. Sign-out or
        // toggle-off cancels the scheduled work, per-wallet purge is handled separately.
        scope.launch {
            combine(settingsRepository.googleBackupEnabled, autoSyncEnabled) { master, auto ->
                master && auto
            }.distinctUntilChanged().collect { active ->
                if (active) ensurePeriodicWork() else cancelScheduledWork()
            }
        }

        // Automatic restore whenever a wallet becomes active: app start with a wallet loaded,
        // wallet import/creation, and account switches all re-emit activeAddressFlow.
        scope.launch {
            walletManager.activeAddressFlow.collect { address ->
                if (address != null) {
                    launch { maybeAutoRestore(address, force = false) }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Auto-sync (upload) triggers
    // -------------------------------------------------------------------------

    /**
     * Message activity signal — called from [ChatRepository.insertMessage] for every message
     * that lands, whatever the path. Snapshots the active wallet, marks its archive dirty
     * (persisted), and restarts the quiet-time timer. Cheap and safe to call at any rate.
     */
    fun noteMessageActivity() {
        val address = walletManager.activeAddressFlow.value ?: return
        scope.launch {
            if (!isAutoSyncActive(address)) return@launch
            dataStore.edit { it[pendingKey(address)] = true }
            debounceWallet = address
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(DEBOUNCE_MS)
                if (debounceWallet == address) uploadIfDirty(address)
            }
        }
    }

    /**
     * The periodic WorkManager fallback body — uploads only when the persisted dirty flag says
     * a change is still owed (the debounce path missed it, e.g. the process died first), or
     * when this wallet has never synced at all. Never throws.
     */
    suspend fun periodicUploadIfNeeded() {
        val address = walletManager.activeAddressFlow.value ?: return
        try {
            if (!isAutoSyncActive(address)) return
            val prefs = dataStore.data.first()
            val pending = prefs[pendingKey(address)] ?: false
            val neverSynced = (prefs[lastSyncKey(address)] ?: 0L) == 0L
            if (pending || neverSynced) uploadIfDirty(address)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Periodic Drive sync check failed (next cycle retries)", e)
        }
    }

    /**
     * The one automatic upload path. [address] is the wallet snapshotted at schedule time; the
     * active wallet is re-checked before building the archive and again before uploading, so a
     * wallet switch mid-flight drops the work instead of writing one account's history into
     * another's file. The Drive write itself is find-file-then-replace-content
     * ([GoogleDriveBackupService.uploadBackup]): the old content stays intact unless the new
     * body lands, never truncate-then-fail.
     */
    private suspend fun uploadIfDirty(address: String) {
        try {
            syncMutex.withLock {
                if (walletManager.activeAddressFlow.value != address) return
                if (!isAutoSyncActive(address)) return
                if (!googleDriveBackupService.ensureAccessTokenSilently()) return

                // Clear the dirty flag BEFORE building: a message arriving during the upload
                // re-marks it, so nothing is lost; clearing after would swallow that signal.
                dataStore.edit { it[pendingKey(address)] = false }

                val json = chatHistoryExportImportServiceLazy.get().buildArchiveJson()
                if (walletManager.activeAddressFlow.value != address) return

                val success = googleDriveBackupService.uploadBackup(address, json)
                if (success) {
                    dataStore.edit { it[lastSyncKey(address)] = System.currentTimeMillis() }
                    chatRepositoryLazy.get().pruneOldMessages()
                    Log.i(TAG, "Automatic Drive sync uploaded the archive")
                } else {
                    // Re-mark so the periodic fallback retries what this attempt could not do.
                    dataStore.edit { it[pendingKey(address)] = true }
                    Log.w(TAG, "Automatic Drive sync upload failed (fallback worker retries)")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { dataStore.edit { it[pendingKey(address)] = true } }
            Log.w(TAG, "Automatic Drive sync upload failed (fallback worker retries)", e)
        }
    }

    // -------------------------------------------------------------------------
    // Auto-restore
    // -------------------------------------------------------------------------

    /**
     * Silent background restore of [address]'s own Drive file through the same additive,
     * txId-deduped import the manual restore uses — so racing the initial chain sync is
     * harmless by construction: both paths insert through [ChatRepository]'s per-txId
     * existence checks, and this import never deletes or overwrites anything. Guards:
     *
     *   * runs once per wallet ([KEY_RESTORE_DONE], set only after a successful import), so a
     *     routine app start doesn't re-download the archive every launch; [force] (explicit
     *     Drive sign-in) re-runs it regardless;
     *   * skipped while a MANUAL restore is running (the modal owns that flow);
     *   * serialized behind [syncMutex] so it can't interleave with an automatic upload;
     *   * the active wallet is re-checked after the download, before importing.
     *
     * No modal. One log line and a subtle toast with counts on completion.
     */
    private suspend fun maybeAutoRestore(address: String, force: Boolean) {
        try {
            if (!isAutoSyncActive(address)) return
            if (!force && (dataStore.data.first()[restoreDoneKey(address)] == true)) return
            if (backupRestoreCoordinator.isRunning) return
            if (!googleDriveBackupService.ensureAccessTokenSilently()) return

            syncMutex.withLock {
                if (walletManager.activeAddressFlow.value != address) return
                if (backupRestoreCoordinator.isRunning) return

                // No file yet is not an error, and does NOT mark restore done: if a backup
                // appears later (first sync from another device), the next activation picks
                // it up.
                val json = googleDriveBackupService.downloadBackup(address) ?: return
                if (walletManager.activeAddressFlow.value != address) return

                val result = chatHistoryExportImportServiceLazy.get().importChatHistory(json)
                dataStore.edit { it[restoreDoneKey(address)] = true }
                Log.i(
                    TAG,
                    "Automatic Drive restore finished: ${result.importedMessageCount} messages " +
                        "in ${result.conversationCount} chats"
                )
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Restored ${result.importedMessageCount} messages from Google Drive",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Includes "archive invalid" and transient network failures: stay silent (the flag
            // stays unset, so a later activation retries) and never surface a modal.
            Log.w(TAG, "Automatic Drive restore failed (will retry on next wallet activation)", e)
        }
    }

    // -------------------------------------------------------------------------
    // Settings + lifecycle
    // -------------------------------------------------------------------------

    /** Flips the ACTIVE wallet's Automatic Drive Sync toggle. Turning it on marks the archive dirty so the first upload happens promptly. */
    fun setAutoSyncEnabled(enabled: Boolean) {
        val address = walletManager.activeAddressFlow.value ?: return
        scope.launch {
            dataStore.edit {
                it[enabledKey(address)] = enabled
                if (enabled) it[pendingKey(address)] = true
            }
            if (!enabled) {
                cancelPendingDebounce(address)
            } else {
                noteMessageActivity()
            }
        }
    }

    /** Called after a successful explicit Drive sign-in: force a restore pass for the active wallet (dedupe makes it additive) and make sure the fallback work is scheduled. */
    fun onSignedIn() {
        val address = walletManager.activeAddressFlow.value ?: return
        ensurePeriodicWork()
        scope.launch { maybeAutoRestore(address, force = true) }
    }

    /** Called on Drive sign-out: drop any pending debounced upload and the scheduled fallback work. Per-wallet settings stay, so signing back in resumes where things were. */
    fun onSignedOut() {
        debounceJob?.cancel()
        debounceJob = null
        debounceWallet = null
        cancelScheduledWork()
    }

    /**
     * Account deletion: removes [walletAddress]'s keys (toggle, stamps, dirty flag, restored
     * marker) and its pending debounced upload. Other wallets' state and work are untouched;
     * the shared periodic job stays only if the init observer still sees an eligible active
     * wallet.
     */
    fun purgeStoredState(walletAddress: String) {
        cancelPendingDebounce(walletAddress)
        scope.launch {
            dataStore.edit {
                it.remove(enabledKey(walletAddress))
                it.remove(lastSyncKey(walletAddress))
                it.remove(pendingKey(walletAddress))
                it.remove(restoreDoneKey(walletAddress))
            }
        }
    }

    /** Clears the last-synced stamp — used when the Drive backup file itself is deleted. */
    fun clearLastSyncStamp(walletAddress: String) {
        scope.launch {
            dataStore.edit {
                it.remove(lastSyncKey(walletAddress))
                it[pendingKey(walletAddress)] = true
            }
        }
    }

    private fun cancelPendingDebounce(walletAddress: String) {
        if (debounceWallet == walletAddress) {
            debounceJob?.cancel()
            debounceJob = null
            debounceWallet = null
        }
    }

    /** Master switch (signed into Drive backup) AND this wallet's own auto-sync toggle. */
    private suspend fun isAutoSyncActive(address: String): Boolean {
        if (!settingsRepository.googleBackupEnabled.first()) return false
        if (!googleDriveBackupService.isSignedIn) return false
        return dataStore.data.first()[enabledKey(address)] ?: true
    }

    private fun ensurePeriodicWork() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DriveAutoSyncWorker>(PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
        )
    }

    private fun cancelScheduledWork() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
