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
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Continuous automatic Nextcloud chat-history sync — the Nextcloud sibling of
 * [GoogleDriveSyncService], with one crucial difference: the Nextcloud backup file
 * (`kachat-backup.json`, shared byte-for-byte with iOS and desktop) is a MULTI-DEVICE file, so
 * every automatic upload here goes through [NextcloudService.runBackup] — download what's on the
 * server, MERGE this device's history into it ([ChatHistoryExportImportService.buildBackupJson]),
 * upload the union — never a raw overwrite. A raw upload could erase another device's messages;
 * this path can only ever add.
 *
 * Triggers, all funneled through one wallet-snapshotted, mutex-serialized upload path:
 *
 *   1. **Debounced activity upload** — [ChatRepository.insertMessage] calls [noteMessageActivity]
 *      (right next to the Drive sibling call). That snapshots the ACTIVE wallet, marks its
 *      archive dirty (persisted, so a killed process can't lose the fact that a sync is owed),
 *      and restarts a quiet-time timer ([DEBOUNCE_MS]).
 *   2. **App lifecycle** — the historical `autoBackupIfDue` cadence (hourly on background, daily
 *      catch-up on foreground) is folded in here as [autoBackupIfDue]; it now uploads only when
 *      the dirty flag says something is owed (or the wallet never synced), and clears that flag
 *      on success like every other path.
 *   3. **Periodic WorkManager fallback** ([NextcloudAutoSyncWorker], every 6h, network connected
 *      + battery not low) — catches uploads the in-process paths missed because the process died
 *      first. Enqueued exactly while an account is connected AND the wallet's Automatic Sync
 *      toggle is on; disconnect/sign-out/toggle-off cancels it.
 *   4. **Automatic restore** — when a wallet becomes active with Nextcloud connected (app start,
 *      wallet switch) and when an account is first connected, the shared file (if it exists) is
 *      imported silently through the same additive, txId-deduped import the manual restore uses.
 *      Once per wallet ([restoreDoneKey], set only on a successful import; a missing file leaves
 *      it unset so a backup appearing later still restores). Skipped while any manual
 *      [BackupRestoreCoordinator] flow (restore or resync) is running, and serialized against
 *      uploads on the same mutex.
 *
 * The per-wallet Automatic Sync toggle IS the pre-existing per-account auto-backup switch
 * ([NextcloudService.autoBackupEnabled], stored scoped key unchanged) — upgraded in meaning and
 * relabeled in the UI, not duplicated. Everything else here (dirty flag, last-synced stamp,
 * restored-once marker) is a DataStore key carrying the wallet's 8-byte SHA256 hash suffix
 * ([NextcloudService.walletHashSuffix]), so account deletion can purge exactly one wallet's
 * state ([purgeStoredState]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class NextcloudSyncService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val walletManager: WalletManager,
    private val nextcloudService: NextcloudService,
    private val backupRestoreCoordinator: BackupRestoreCoordinator,
    // Lazy: ChatHistoryExportImportService depends on ChatRepository, which depends (lazily)
    // back on this service for noteMessageActivity — same cycle-break as GoogleDriveSyncService.
    private val chatHistoryExportImportServiceLazy: dagger.Lazy<ChatHistoryExportImportService>
) {
    companion object {
        private const val TAG = "NextcloudSync"

        /** Quiet time after the last message before the automatic upload runs. */
        const val DEBOUNCE_MS = 2 * 60 * 1000L

        /** Periodic WorkManager fallback cadence for uploads the in-process paths missed. */
        const val PERIODIC_INTERVAL_HOURS = 6L

        const val WORK_NAME = "kachat_nextcloud_auto_sync"

        /** On-background lifecycle trigger throttle: at most once per hour (historical cadence). */
        const val AUTO_BACKUP_MIN_INTERVAL_MS = 3_600_000L
        /** Launch/foreground catch-up threshold — covers users who never background cleanly. */
        const val AUTO_BACKUP_CATCH_UP_INTERVAL_MS = 86_400_000L

        // Per-wallet DataStore key bases; the wallet's hash suffix is appended.
        private const val KEY_LAST_SYNC_MS = "nextcloud_auto_sync_last_ms"
        private const val KEY_PENDING_CHANGES = "nextcloud_auto_sync_pending"
        private const val KEY_RESTORE_DONE = "nextcloud_auto_restore_done"

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

    /** When the ACTIVE wallet's archive last synced automatically (epoch ms), null = never. */
    val lastAutoSyncMs: StateFlow<Long?> = walletManager.activeAddressFlow
        .flatMapLatest { address ->
            if (address == null) flowOf(null)
            else dataStore.data.map { it[lastSyncKey(address)]?.takeIf { ms -> ms > 0 } }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        // Keep the periodic fallback job enqueued exactly while it can do anything: an account
        // connected for the active wallet AND that wallet's Automatic Sync toggle on.
        // Disconnect, wallet logout, or toggle-off cancels the scheduled work.
        scope.launch {
            combine(nextcloudService.account, nextcloudService.autoBackupEnabled) { account, auto ->
                account != null && auto
            }.distinctUntilChanged().collect { active ->
                if (active) ensurePeriodicWork() else cancelScheduledWork()
            }
        }

        // Automatic restore. The account flow (not the raw address flow) is the primary trigger
        // because NextcloudService has already finished loading the wallet's scoped credentials
        // by the time it emits — connecting an account, app start with a connected wallet, and
        // wallet switches between connected accounts all land here. A null emission means
        // disconnect/logout: drop any pending debounced upload with it.
        scope.launch {
            nextcloudService.account.collect { account ->
                if (account == null) {
                    debounceJob?.cancel()
                    debounceJob = null
                    debounceWallet = null
                } else {
                    val address = walletManager.activeAddressFlow.value
                    if (address != null) launch { maybeAutoRestore(address) }
                }
            }
        }

        // A wallet switch cancels the previous wallet's pending debounce outright (its upload
        // path would drop the work anyway via re-checks, but there is no reason to keep the
        // timer alive), and gives the incoming wallet a restore chance even when the account
        // flow happens not to re-emit (two wallets with byte-identical stored accounts).
        scope.launch {
            walletManager.activeAddressFlow.collect { address ->
                val pending = debounceWallet
                if (pending != null && pending != address) cancelPendingDebounce(pending)
                if (address != null && nextcloudService.isConnected) {
                    launch { maybeAutoRestore(address) }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Upload triggers
    // -------------------------------------------------------------------------

    /**
     * Message activity signal — called from [ChatRepository.insertMessage] for every message
     * that lands, right next to the Google Drive sibling. Snapshots the active wallet, marks its
     * archive dirty (persisted), and restarts the quiet-time timer. Cheap at any call rate.
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
     * The app-lifecycle trigger (KaChatApplication: hourly on background, daily catch-up on
     * foreground) — the historical `NextcloudService.autoBackupIfDue` cadence folded into the
     * one shared upload path. Uploads only when the persisted dirty flag says a sync is owed or
     * the wallet has never synced, and clears that flag on success. Never throws.
     */
    suspend fun autoBackupIfDue(minIntervalMs: Long = AUTO_BACKUP_MIN_INTERVAL_MS) {
        val address = walletManager.activeAddressFlow.value ?: return
        try {
            if (!isAutoSyncActive(address)) return
            val prefs = dataStore.data.first()
            val last = prefs[lastSyncKey(address)] ?: 0L
            if (last > 0L && System.currentTimeMillis() - last < minIntervalMs) return
            val pending = prefs[pendingKey(address)] ?: false
            if (pending || last == 0L) uploadIfDirty(address)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Nextcloud lifecycle sync check failed (next trigger retries)", e)
        }
    }

    /**
     * The periodic WorkManager fallback body — uploads only when the persisted dirty flag says
     * a change is still owed (the in-process paths missed it, e.g. the process died first), or
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
            Log.w(TAG, "Periodic Nextcloud sync check failed (next cycle retries)", e)
        }
    }

    /**
     * The one automatic upload path — always [NextcloudService.runBackup], never a raw upload:
     * the server file is shared with the user's other devices, so the body must be the MERGE of
     * what's already there and this device's history (a raw overwrite could erase another
     * device's messages; the merge can only add). [address] is the wallet snapshotted at
     * schedule time; the active wallet is re-checked before building the archive and again
     * right before the upload, so a wallet switch mid-flight drops the work instead of writing
     * one account's history into another's file. [ChatHistoryExportImportService.buildBackupJson]
     * additionally rejects a server file belonging to a different wallet before anything uploads.
     */
    private suspend fun uploadIfDirty(address: String) {
        try {
            syncMutex.withLock {
                if (walletManager.activeAddressFlow.value != address) return
                if (!isAutoSyncActive(address)) return

                // Clear the dirty flag BEFORE building: a message arriving during the upload
                // re-marks it, so nothing is lost; clearing after would swallow that signal.
                dataStore.edit { it[pendingKey(address)] = false }

                nextcloudService.runBackup { remote ->
                    // Before building the merged archive (the remote copy just downloaded)...
                    if (walletManager.activeAddressFlow.value != address) {
                        throw IOException("The active account changed during the sync.")
                    }
                    val json = chatHistoryExportImportServiceLazy.get().buildBackupJson(remote)
                    // ...and again right before runBackup PUTs the result.
                    if (walletManager.activeAddressFlow.value != address) {
                        throw IOException("The active account changed during the sync.")
                    }
                    json
                }
                dataStore.edit { it[lastSyncKey(address)] = System.currentTimeMillis() }
                Log.i(TAG, "Automatic Nextcloud sync uploaded the merged archive")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Re-mark so the fallback worker (or the next trigger) retries what this attempt
            // could not finish. runBackup aborts BEFORE uploading whenever the server copy
            // can't be read or merged, so the existing file is never at risk here.
            runCatching { dataStore.edit { it[pendingKey(address)] = true } }
            Log.w(TAG, "Automatic Nextcloud sync upload failed (a later trigger retries)", e)
        }
    }

    // -------------------------------------------------------------------------
    // Auto-restore
    // -------------------------------------------------------------------------

    /**
     * Silent background restore of the shared backup file through the same additive,
     * txId-deduped import the manual restore uses ([ChatHistoryExportImportService.importChatHistory]),
     * so racing the chain sync or an upload is harmless by construction. Guards:
     *
     *   * once per wallet ([restoreDoneKey], set only after a successful import). A missing
     *     file does NOT set it — if a backup appears later (first sync from another device),
     *     the next wallet activation picks it up;
     *   * skipped while any manual [BackupRestoreCoordinator] flow (restore OR resync) runs;
     *   * serialized behind [syncMutex] so it can't interleave with an automatic upload;
     *   * the active wallet is re-checked after the download, before importing, and the file's
     *     own walletAddress field must match this wallet (or be absent) — the shared file
     *     belongs to one wallet, and a foreign file is skipped, never imported.
     *
     * No modal. One log line and a short toast with counts on completion; failures stay silent
     * (the flag stays unset, so a later activation retries).
     */
    private suspend fun maybeAutoRestore(address: String) {
        try {
            if (walletManager.activeAddressFlow.value != address) return
            if (!nextcloudService.isConnected) return
            if (dataStore.data.first()[restoreDoneKey(address)] == true) return
            if (backupRestoreCoordinator.isRunning) return

            syncMutex.withLock {
                if (walletManager.activeAddressFlow.value != address) return
                if (!nextcloudService.isConnected) return
                if (backupRestoreCoordinator.isRunning) return

                // No file yet is not an error, and does NOT mark restore done.
                nextcloudService.fetchBackupInfo() ?: return
                val json = nextcloudService.downloadBackup()
                if (walletManager.activeAddressFlow.value != address) return

                // The shared file carries its wallet: never import another wallet's history.
                val remoteWallet = runCatching {
                    org.json.JSONObject(json).optString("walletAddress").trim()
                }.getOrNull()
                if (!remoteWallet.isNullOrEmpty() && remoteWallet != address) {
                    Log.w(TAG, "Nextcloud backup belongs to a different wallet; automatic restore skipped")
                    return
                }

                val result = chatHistoryExportImportServiceLazy.get().importChatHistory(json)
                dataStore.edit { it[restoreDoneKey(address)] = true }
                Log.i(
                    TAG,
                    "Automatic Nextcloud restore finished: ${result.importedMessageCount} messages " +
                        "in ${result.conversationCount} chats"
                )
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Restored ${result.importedMessageCount} messages from Nextcloud",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Automatic Nextcloud restore failed (will retry on next wallet activation)", e)
        }
    }

    // -------------------------------------------------------------------------
    // Settings + lifecycle
    // -------------------------------------------------------------------------

    /**
     * Flips the ACTIVE wallet's Automatic Sync toggle — the pre-existing per-account
     * auto-backup switch ([NextcloudService.setAutoBackupEnabled], same stored scoped key).
     * Turning it on marks the archive dirty so the first sync happens promptly (and the
     * worker-scheduling observer reacts to the flow); turning it off drops the pending debounce.
     */
    fun setAutoSyncEnabled(enabled: Boolean) {
        nextcloudService.setAutoBackupEnabled(enabled)
        val address = walletManager.activeAddressFlow.value ?: return
        if (enabled) {
            scope.launch {
                dataStore.edit { it[pendingKey(address)] = true }
                noteMessageActivity()
            }
        } else {
            cancelPendingDebounce(address)
        }
    }

    /**
     * Account deletion: removes [walletAddress]'s sync keys (last-synced stamp, dirty flag,
     * restored marker) and its pending debounced upload — the sibling of
     * [NextcloudService.purgeStoredState], which drops the credentials and toggle themselves.
     * Other wallets' state is untouched; the shared periodic job is cancelled by the init
     * observer when the purge disconnects the active wallet.
     */
    fun purgeStoredState(walletAddress: String) {
        cancelPendingDebounce(walletAddress)
        scope.launch {
            dataStore.edit {
                it.remove(lastSyncKey(walletAddress))
                it.remove(pendingKey(walletAddress))
                it.remove(restoreDoneKey(walletAddress))
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

    /** Connected for the snapshotted (still-active) wallet AND its Automatic Sync toggle on. */
    private fun isAutoSyncActive(address: String): Boolean =
        walletManager.activeAddressFlow.value == address &&
            nextcloudService.isConnected &&
            nextcloudService.autoBackupEnabled.value

    private fun ensurePeriodicWork() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<NextcloudAutoSyncWorker>(PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
        )
    }

    private fun cancelScheduledWork() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
