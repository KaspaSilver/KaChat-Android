package com.kachat.app.services

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Continuous automatic Nextcloud chat-history sync. The Nextcloud backup file
 * (`kachat-backup.json`, shared byte-for-byte with iOS and desktop) is a MULTI-DEVICE file, so
 * every automatic upload here goes through [NextcloudService.runBackup] — download what's on the
 * server, MERGE this device's history into it ([ChatHistoryExportImportService.buildBackupJson]),
 * upload the union — never a raw overwrite. A raw upload could erase another device's messages;
 * this path can only ever add.
 *
 * Triggers, all funneled through one wallet-snapshotted, mutex-serialized upload path:
 *
 *   1. **Debounced activity upload** — [ChatRepository.insertMessage] calls [noteMessageActivity]
 *      That snapshots the ACTIVE wallet, marks its
 *      archive dirty (persisted, so a killed process can't lose the fact that a sync is owed),
 *      and restarts a quiet-time timer ([DEBOUNCE_IN_CHAT_MS] with a conversation on screen,
 *      [DEBOUNCE_IDLE_MS] otherwise; the tier is read fresh each time the timer re-arms).
 *   2. **App lifecycle** — the historical `autoBackupIfDue` cadence (hourly on background, daily
 *      catch-up on foreground) is folded in here as [autoBackupIfDue]; it uploads when the last
 *      upload is that old OR the dirty flag says something is owed (NEXTCLOUD_SYNC.md §6), and
 *      clears that flag on success like every other path.
 *   3. **Periodic WorkManager fallback** ([NextcloudAutoSyncWorker], every 6h, network connected
 *      + battery not low) — catches uploads the in-process paths missed because the process died
 *      first. Enqueued exactly while an account is connected AND the wallet's Automatic Sync
 *      toggle is on; disconnect/sign-out/toggle-off cancels it.
 *   4. **Remote change watcher** — while the app is FOREGROUND with an account connected and the
 *      toggle on, a Depth-0 PROPFIND polls the shared file's ETag (headers only, no body) at an
 *      adaptive cadence: every [WATCHER_POLL_IN_CHAT_MS] while a conversation thread is open on
 *      screen, every [WATCHER_POLL_IDLE_MS] elsewhere in the app.
 *      When the ETag moves, another device wrote the file: download, decrypt, and merge-import
 *      through the same additive txId-deduped import the restore paths use — silently, one log
 *      line. The last-known ETag persists per wallet and updates after every import AND every
 *      upload this device makes (automatic or manual), so a device never re-imports its own
 *      write. Together with the short upload debounce this makes two phones with their chats
 *      open a near-live mirror: a message sent on one appears on the other within seconds.
 *   5. **Automatic restore** — only while Automatic Sync is on (as on iOS): when a wallet becomes
 *      active with Nextcloud connected (app start, wallet switch), when an account is first
 *      connected, and when the toggle is turned on, the shared file (if it exists) is
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
    // Foreground flag source for the remote change watcher (KaChatApplication's process
    // lifecycle observer drives it) — the watcher polls only while the app is on screen — and
    // open-chat state source (the thread screens drive it) for the adaptive cadence tiers.
    private val notificationHelper: NotificationHelper,
    // Metered tiers: idle-cadence watcher/debounce even in-chat, and a longer floor between
    // automatic uploads — see the companion constants.
    private val meteredNetwork: MeteredNetwork,
    // Lazy: ChatHistoryExportImportService depends on ChatRepository, which depends (lazily)
    // back on this service for noteMessageActivity — a cycle Dagger cannot resolve directly.
    private val chatHistoryExportImportServiceLazy: dagger.Lazy<ChatHistoryExportImportService>
) {
    companion object {
        private const val TAG = "NextcloudSync"

        /**
         * Adaptive cadence tiers, resolved from whether a conversation thread (1:1 or group) is
         * open on screen ([NotificationHelper.openChatFlow]) — iOS implements the identical
         * tiers. Inside a chat the mirror runs at full speed (a message mirrored from another
         * device is visible the instant it lands); everywhere else (chat list, Settings) the
         * watcher polls and the upload debounce relax to save battery and server round-trips.
         * Merge semantics make frequent uploads safe; the debounce only coalesces bursts.
         */
        const val DEBOUNCE_IN_CHAT_MS = 5_000L
        const val DEBOUNCE_IDLE_MS = 15_000L

        /** Remote change watcher cadence per tier (foreground only): one Depth-0 PROPFIND per
         *  tick. The tier is re-resolved every tick, and opening a thread wakes the loop
         *  immediately, so a residual idle sleep never delays the first in-chat poll.
         *  On METERED networks the watcher has its own relaxed pair (NEXTCLOUD_SYNC.md §6: 30 s
         *  in a chat, 60 s elsewhere - iOS's `...ChangeWatchIntervalExpensive`): the PROPFIND is
         *  tiny, but a moved ETag costs a full archive download. The upload debounce is NOT
         *  metered-dependent; the metered upload floor below covers cellular data. */
        const val WATCHER_POLL_IN_CHAT_MS = 5_000L
        const val WATCHER_POLL_IDLE_MS = 30_000L
        const val WATCHER_POLL_IN_CHAT_METERED_MS = 30_000L
        const val WATCHER_POLL_IDLE_METERED_MS = 60_000L

        /** Floor between AUTOMATIC uploads: each upload is a PROPFIND + (usually skipped)
         *  download + full-archive PUT, so even merge-safe uploads shouldn't ride every message
         *  burst. Every automatic path (debounce, lifecycle catch-up, periodic worker) that comes
         *  due earlier re-arms to the earliest allowed time rather than dropping the work
         *  (NEXTCLOUD_SYNC.md §6, iOS performAutomaticSync). Manual Back Up Now is not floored. */
        const val MIN_UPLOAD_INTERVAL_MS = 90_000L
        const val MIN_UPLOAD_INTERVAL_METERED_MS = 300_000L

        /** Watcher backoff after consecutive failed polls: the current tier's base times 3 per
         *  failure, capped at 60s (in-chat 15s/45s/60s, idle 60s), reset on the first success. */
        private const val WATCHER_BACKOFF_MULTIPLIER = 3L
        private const val WATCHER_BACKOFF_CAP_MS = 60_000L

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
        private const val KEY_LAST_ETAG = "nextcloud_auto_sync_etag"
        private const val KEY_MERGED_ETAG = "nextcloud_auto_sync_merged_etag"

        private fun lastSyncKey(address: String) =
            longPreferencesKey("${KEY_LAST_SYNC_MS}_${NextcloudService.walletHashSuffix(address)}")

        private fun pendingKey(address: String) =
            booleanPreferencesKey("${KEY_PENDING_CHANGES}_${NextcloudService.walletHashSuffix(address)}")

        private fun restoreDoneKey(address: String) =
            booleanPreferencesKey("${KEY_RESTORE_DONE}_${NextcloudService.walletHashSuffix(address)}")

        /** The backup file's last-known WebDAV ETag for this wallet — the watcher's change
         *  detector and own-write guard: updated after every upload and import, AND when the
         *  watcher judged a version it could not import (another wallet's, unreadable), so that
         *  version isn't downloaded again on every poll (NEXTCLOUD_SYNC.md §6). */
        private fun etagKey(address: String) =
            stringPreferencesKey("${KEY_LAST_ETAG}_${NextcloudService.walletHashSuffix(address)}")

        /**
         * The ETag of the last server version this device actually HOLDS merged — its own upload,
         * or a successful import — and nothing else. Only this one licenses the upload's ETag
         * short-cut (skip the download, write the local archive as is; §4.2).
         *
         * Kept apart from [etagKey] on purpose. The watcher also records the ETag of a file it
         * merely LOOKED at and skipped; if that ETag licensed the short-cut, the next automatic
         * upload would skip the read and write straight over that file - another wallet's backup
         * included, which §7 says is never touched, or a newer device's content never merged.
         */
        private fun mergedEtagKey(address: String) =
            stringPreferencesKey("${KEY_MERGED_ETAG}_${NextcloudService.walletHashSuffix(address)}")
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

        // A null account emission means disconnect/logout: drop any pending debounced upload
        // with it.
        scope.launch {
            nextcloudService.account.collect { account ->
                if (account == null) {
                    debounceJob?.cancel()
                    debounceJob = null
                    debounceWallet = null
                }
            }
        }

        // Automatic restore - only while Automatic Sync is on, as on iOS (NextcloudService
        // scheduleAutoRestoreIfNeeded: connected + autoBackupEnabled; it is armed at wallet
        // activation and when the toggle turns on). Armed whenever a wallet is active with an
        // account connected AND its toggle on: app start, connecting an account (the toggle
        // resolves just after the account, so this waits for it), a wallet switch, and turning
        // the toggle on. The account is part of the key so a wallet switch re-arms once the new
        // wallet's account has loaded, even when the address emitted first. A manual restore
        // (Settings) never comes through here and works whatever the toggle says.
        scope.launch {
            combine(
                nextcloudService.account,
                nextcloudService.autoBackupEnabled,
                walletManager.activeAddressFlow
            ) { account, auto, address ->
                if (account != null && auto && address != null) address to account else null
            }.distinctUntilChanged().collect { armed ->
                if (armed != null) launch { maybeAutoRestore(armed.first) }
            }
        }

        // Remote change watcher lifecycle: runs exactly while the app is FOREGROUND, an account
        // is connected, the Automatic Sync toggle is on, and a wallet is active. Backgrounding,
        // disconnecting, toggling off (which goes
        // through the real setter and so flips this flow), and wallet switches all cancel the
        // loop via collectLatest; a wallet switch restarts it for the new wallet.
        scope.launch {
            combine(
                notificationHelper.appForegroundFlow,
                nextcloudService.account,
                nextcloudService.autoBackupEnabled,
                walletManager.activeAddressFlow
            ) { foreground, account, auto, address ->
                if (foreground && account != null && auto) address else null
            }.distinctUntilChanged().collectLatest { address ->
                if (address != null) watchRemoteChanges(address)
            }
        }

        // A wallet switch cancels the previous wallet's pending debounce outright (its upload
        // path would drop the work anyway via re-checks, but there is no reason to keep the
        // timer alive). The incoming wallet's restore chance comes from the observer above,
        // whose key carries the address.
        scope.launch {
            walletManager.activeAddressFlow.collect { address ->
                val pending = debounceWallet
                if (pending != null && pending != address) cancelPendingDebounce(pending)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Upload triggers
    // -------------------------------------------------------------------------

    /**
     * Message activity signal — called from [ChatRepository.insertMessage] for every message
     * that lands. Snapshots the active wallet, marks its
     * archive dirty (persisted), and restarts the quiet-time timer. Cheap at any call rate.
     */
    fun noteMessageActivity() {
        val address = walletManager.activeAddressFlow.value ?: return
        scope.launch {
            if (!isAutoSyncActive(address)) return@launch
            dataStore.edit { it[pendingKey(address)] = true }
            // Tier chosen at arm time; every re-arm (each new message restarts the timer)
            // re-reads the open-chat state, so leaving or entering a chat mid-burst takes
            // effect on the very next message. 5 s / 15 s on every network (NEXTCLOUD_SYNC.md
            // §6, iOS currentSyncDebounceInterval): metered data is spared by the longer upload
            // floor that uploadIfDirty applies, not by a slower debounce.
            val quietMs = if (notificationHelper.isChatOpen) DEBOUNCE_IN_CHAT_MS else DEBOUNCE_IDLE_MS
            armUpload(address, quietMs)
        }
    }

    /**
     * (Re)arms the one pending automatic upload for [address] to run after [delayMs]: the
     * message-activity debounce, and the re-arm when an upload comes due inside the floor
     * (iOS armSyncDebounce). A new arm replaces the previous one; the persisted dirty flag is
     * what guarantees the work is owed, so replacing a timer never loses it.
     */
    private fun armUpload(address: String, delayMs: Long) {
        debounceWallet = address
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(delayMs)
            if (debounceWallet == address) uploadIfDirty(address)
        }
    }

    /**
     * The app-lifecycle trigger (KaChatApplication: on background with [AUTO_BACKUP_MIN_INTERVAL_MS],
     * on launch/foreground with [AUTO_BACKUP_CATCH_UP_INTERVAL_MS]) folded into the one shared
     * upload path. NEXTCLOUD_SYNC.md §6 (iOS autoBackupIfDue): a catch-up upload runs when the
     * last one is at least [minIntervalMs] old (or there never was one) OR the persisted dirty
     * flag says message activity is still owed an upload - either alone is enough. It used to
     * need both, so a dirty archive waited out the hour and a clean but stale one never caught
     * up. The upload floor still applies (see [uploadIfDirty]). Never throws.
     */
    suspend fun autoBackupIfDue(minIntervalMs: Long = AUTO_BACKUP_MIN_INTERVAL_MS) {
        val address = walletManager.activeAddressFlow.value ?: return
        try {
            if (!isAutoSyncActive(address)) return
            val prefs = dataStore.data.first()
            val last = prefs[lastSyncKey(address)] ?: 0L
            val due = last == 0L || System.currentTimeMillis() - last >= minIntervalMs
            val pending = prefs[pendingKey(address)] ?: false
            if (due || pending) uploadIfDirty(address)
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
            // Upload floor (NEXTCLOUD_SYNC.md §6, iOS performAutomaticSync): at most one
            // automatic upload per MIN_UPLOAD_INTERVAL_MS (longer on metered networks), whatever
            // the trigger. One due earlier re-arms for the earliest allowed time - the dirty
            // flag is still set, so the work is deferred, never dropped.
            val minIntervalMs = if (meteredNetwork.isMetered) MIN_UPLOAD_INTERVAL_METERED_MS else MIN_UPLOAD_INTERVAL_MS
            val lastUpload = dataStore.data.first()[lastSyncKey(address)] ?: 0L
            val waitMs = lastUpload + minIntervalMs - System.currentTimeMillis()
            if (lastUpload > 0L && waitMs > 0L) {
                armUpload(address, waitMs)
                return
            }
            syncMutex.withLock {
                if (walletManager.activeAddressFlow.value != address) return
                if (!isAutoSyncActive(address)) return

                // Clear the dirty flag BEFORE building: a message arriving during the upload
                // re-marks it, so nothing is lost; clearing after would swallow that signal.
                dataStore.edit { it[pendingKey(address)] = false }

                // ETag short-circuit (§4.2): a cheap Depth-0 PROPFIND first. When the server
                // file's ETag still equals the last version THIS wallet wrote or imported
                // ([mergedEtagKey] - never one the watcher merely skipped), that server copy is
                // already merged locally, and the watcher has been merging every other device's
                // write into local since, so local is a superset and the pre-merge download is
                // pure waste. Skip it and PUT the fresh local archive directly. Any doubt (no
                // stored ETag, PROPFIND failed, ETag moved) falls through to the full
                // download+merge.
                val storedEtag = dataStore.data.first()[mergedEtagKey(address)]
                val serverEtag = if (storedEtag != null) {
                    runCatching { nextcloudService.fetchBackupEtag() }.getOrNull()
                } else null
                val buildJson: suspend (String?) -> String = { remote ->
                    // Before building the merged archive (the remote copy just downloaded)...
                    if (walletManager.activeAddressFlow.value != address) {
                        throw IOException("The active account changed during the sync.")
                    }
                    val json = chatHistoryExportImportServiceLazy.get().buildBackupJson(remote)
                    // ...and again right before the PUT.
                    if (walletManager.activeAddressFlow.value != address) {
                        throw IOException("The active account changed during the sync.")
                    }
                    json
                }
                val newEtag = if (serverEtag != null && serverEtag == storedEtag) {
                    nextcloudService.runBackupWithoutDownload { buildJson(null) }
                } else {
                    nextcloudService.runBackup(buildJson)
                }
                dataStore.edit {
                    it[lastSyncKey(address)] = System.currentTimeMillis()
                    // Own-write guard: the watcher compares against this, so this device's own
                    // upload never reads as "another device changed the file". A null ETag (rare
                    // server) just means one harmless re-import of our own merge.
                    if (newEtag != null) {
                        it[etagKey(address)] = newEtag
                        it[mergedEtagKey(address)] = newEtag
                    }
                }
                Log.i(TAG, "Automatic Nextcloud sync uploaded the merged archive")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Re-mark so the fallback worker (or the next trigger) retries what this attempt
            // could not finish - no retry loop here, including for a cut-off upload
            // (NextcloudUploadCutOffException, §4.6). runBackup aborts BEFORE uploading on a
            // transfer problem, another wallet's file, a hint-less envelope or a newer schema;
            // only this wallet's own unreadable file is ever replaced (§7).
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
     *   * only while the wallet's Automatic Sync toggle is on (iOS); the manual restore in
     *     Settings goes through [BackupRestoreCoordinator] instead and ignores the toggle;
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
            // Automatic Sync on (iOS scheduleAutoRestoreIfNeeded / runAutoRestore guard
            // isConnected + autoBackupEnabled). The stored read covers a wallet switch whose
            // toggle state has not swapped in yet.
            if (!isAutoSyncActive(address)) return
            if (!nextcloudService.isAutoBackupEnabledFor(address)) return
            if (dataStore.data.first()[restoreDoneKey(address)] == true) return
            if (backupRestoreCoordinator.isRunning) return

            syncMutex.withLock {
                if (!isAutoSyncActive(address)) return
                if (backupRestoreCoordinator.isRunning) return

                // No file yet is not an error, and does NOT mark restore done.
                nextcloudService.fetchBackupInfo() ?: return
                // Baseline the watcher BEFORE downloading: if the file changes mid-restore, the
                // stored ETag is the older one and the next watcher poll imports the newer write.
                val etag = runCatching { nextcloudService.fetchBackupEtag() }.getOrNull()
                val json = nextcloudService.downloadBackup()
                if (walletManager.activeAddressFlow.value != address) return

                if (!backupBelongsToWallet(address, json)) {
                    Log.w(TAG, "Nextcloud backup belongs to a different wallet; automatic restore skipped")
                    return
                }

                val result = chatHistoryExportImportServiceLazy.get().importChatHistory(json)
                dataStore.edit {
                    it[restoreDoneKey(address)] = true
                    if (etag != null) {
                        it[etagKey(address)] = etag
                        it[mergedEtagKey(address)] = etag
                    }
                }
                Log.i(
                    TAG,
                    "Automatic Nextcloud restore finished: ${result.importedMessageCount} messages " +
                        "in ${result.conversationCount} chats"
                )
                withContext(Dispatchers.Main) {
                    // Fully silent by design: sync is invisible background plumbing like
                    // iCloud; the log line is the only trace.
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Automatic Nextcloud restore failed (will retry on next wallet activation)", e)
        }
    }

    // -------------------------------------------------------------------------
    // Remote change watcher (the near-live cross-device mirror)
    // -------------------------------------------------------------------------

    /**
     * The continuous half of the mirror (the once-per-wallet auto-restore is only the bootstrap):
     * every tick a Depth-0 PROPFIND asks the server for the shared file's ETag — headers and a
     * few hundred XML bytes, never the file. A moved ETag means another device wrote the file;
     * [importRemoteChange] then downloads and merge-imports it. Runs only while the init
     * observer's conditions hold (foreground + connected + toggle on + this wallet active) and
     * is cancelled by collectLatest the moment any of them breaks.
     *
     * The cadence is adaptive for battery, resolved fresh EVERY tick from whether a conversation
     * thread is on screen: [WATCHER_POLL_IN_CHAT_MS] inside a chat, [WATCHER_POLL_IDLE_MS]
     * elsewhere. Opening a thread additionally WAKES the loop at once — the sleep races a
     * conflated wake channel fed by [NotificationHelper.openChatFlow]'s false-to-true
     * transitions — so entering a chat triggers an immediate poll instead of waiting out a
     * residual idle sleep.
     *
     * Failed polls back off from the current tier's base (times [WATCHER_BACKOFF_MULTIPLIER] per
     * consecutive failure, capped at [WATCHER_BACKOFF_CAP_MS]) and recover to the normal cadence
     * on the first success. A tick that would race a manual restore/resync
     * ([BackupRestoreCoordinator.isRunning]) is skipped, not queued.
     */
    private suspend fun watchRemoteChanges(address: String) = coroutineScope {
        // Wake-on-chat-entry signal. Conflated: a transition landing mid-poll is remembered and
        // shortens the very next sleep to zero, which is exactly the wanted immediate tick.
        val wake = Channel<Unit>(Channel.CONFLATED)
        launch {
            notificationHelper.openChatFlow
                .drop(1) // The current value is no transition; tiers read it per tick below.
                .collect { open -> if (open) wake.trySend(Unit) }
        }
        var consecutiveFailures = 0
        while (true) {
            // Metered networks get the relaxed tier pair — see the tier constants.
            val chatOpen = notificationHelper.isChatOpen
            val baseMs = if (meteredNetwork.isMetered) {
                if (chatOpen) WATCHER_POLL_IN_CHAT_METERED_MS else WATCHER_POLL_IDLE_METERED_MS
            } else {
                if (chatOpen) WATCHER_POLL_IN_CHAT_MS else WATCHER_POLL_IDLE_MS
            }
            var delayMs = baseMs
            repeat(minOf(consecutiveFailures, 3)) {
                delayMs = minOf(delayMs * WATCHER_BACKOFF_MULTIPLIER, WATCHER_BACKOFF_CAP_MS)
            }
            // Sleep for the tier's interval, unless a chat opens first — then poll right away.
            withTimeoutOrNull(delayMs) { wake.receive() }
            try {
                if (!isAutoSyncActive(address)) continue
                if (backupRestoreCoordinator.isRunning) continue

                val etag = nextcloudService.fetchBackupEtag()
                consecutiveFailures = 0
                if (etag == null) continue // No backup file yet — nothing to mirror.

                // Any ETag other than the last known one - including none known yet - is imported
                // (NEXTCLOUD_SYNC.md §6, iOS checkForRemoteChangeAndImport). The import is
                // additive and txId-deduped, so overlapping the auto-restore costs one download.
                // It used to record a first-seen ETag without importing, which also let the
                // upload short-cut skip a merge of content never read.
                val known = dataStore.data.first()[etagKey(address)]
                if (etag != known) importRemoteChange(address, etag)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                consecutiveFailures++
                Log.w(TAG, "Nextcloud change watcher poll failed (backing off)", e)
            }
        }
    }

    /**
     * Another device wrote the shared file: download, decrypt, and merge-import it through the
     * same additive txId-deduped [ChatHistoryExportImportService.importChatHistory] the restore
     * paths use (imported messages land read), silently — one log line, no UI. Serialized behind
     * [syncMutex] so it can never interleave with an upload or the auto-restore, with the same
     * wallet-snapshot re-checks around the import.
     *
     * NEXTCLOUD_SYNC.md §6: a file that can't be read still has its ETag recorded, so it isn't
     * downloaded on every poll - a foreign wallet's file, and any version the import judges and
     * refuses ([UnreadableBackupException]: undecryptable, not an archive, cut off at rest,
     * incompatible schema). A replacement changes the ETag and gets a fresh look. Only the
     * watcher's change detector advances then, never [mergedEtagKey]: this device does not hold
     * that content merged, so the next upload must read it (and, if it is this wallet's damaged
     * file, replace it in place - §7). A failed or early-ended DOWNLOAD is different - it says
     * nothing about the file - so it throws, the caller backs off and the ETag stays put for the
     * next poll to retry.
     */
    private suspend fun importRemoteChange(address: String, etag: String) {
        syncMutex.withLock {
            if (walletManager.activeAddressFlow.value != address) return
            if (!isAutoSyncActive(address)) return
            if (backupRestoreCoordinator.isRunning) return

            val json = nextcloudService.downloadBackup()
            if (walletManager.activeAddressFlow.value != address) return

            if (!backupBelongsToWallet(address, json)) {
                Log.w(TAG, "Nextcloud backup belongs to a different wallet; watcher import skipped")
                dataStore.edit { it[etagKey(address)] = etag }
                return
            }
            val result = try {
                chatHistoryExportImportServiceLazy.get().importChatHistory(json)
            } catch (e: UnreadableBackupException) {
                Log.w(TAG, "Nextcloud change watcher skipped an unreadable server backup: ${e.message}")
                dataStore.edit { it[etagKey(address)] = etag }
                return
            }
            if (walletManager.activeAddressFlow.value != address) return
            dataStore.edit {
                it[etagKey(address)] = etag
                it[mergedEtagKey(address)] = etag
            }
            Log.i(
                TAG,
                "Nextcloud change watcher merged another device's update: " +
                    "${result.importedMessageCount} new messages in ${result.conversationCount} chats"
            )
        }
    }

    /**
     * Records the ETag of a backup THIS device just wrote outside the automatic path (the manual
     * Back Up Now button) so the change watcher never mistakes the device's own write for
     * another device's change. Null (server sent no ETag) is a no-op — worst case is one
     * harmless re-import of our own merge.
     */
    fun noteOwnUpload(etag: String?) {
        if (etag == null) return
        val address = walletManager.activeAddressFlow.value ?: return
        scope.launch {
            dataStore.edit {
                it[etagKey(address)] = etag
                it[mergedEtagKey(address)] = etag
            }
        }
    }

    /** True when the shared file's contents belong to [address] (or carry no wallet marker).
     *  Encrypted envelopes expose only the walletHint (checked without decrypting;
     *  importChatHistory decrypts and re-checks); legacy plaintext files carry walletAddress
     *  in the clear. Shared by the auto-restore and the change watcher. */
    private fun backupBelongsToWallet(address: String, json: String): Boolean {
        if (BackupCrypto.isEnvelope(json)) {
            val hint = BackupCrypto.envelopeWalletHint(json)
            return hint == null || hint == BackupCrypto.walletHint(address)
        }
        val remoteWallet = runCatching {
            org.json.JSONObject(json).optString("walletAddress").trim()
        }.getOrNull()
        return remoteWallet.isNullOrEmpty() || remoteWallet == address
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
                it.remove(etagKey(walletAddress))
                it.remove(mergedEtagKey(walletAddress))
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
