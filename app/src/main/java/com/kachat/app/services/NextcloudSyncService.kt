package com.kachat.app.services

import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
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
 * Automatic Nextcloud sync of the contacts, and nothing else: messages and groups come back from
 * the seed, off the chain (iOS 4314e17e, NEXTCLOUD_SYNC.md "Contacts only"). The file is
 * `kachat-contacts.json`, one encrypted file shared by the account's devices, so every sync is a
 * read, merge, write through [NextcloudService.runBackup], written only when the file is missing
 * something this device has. The old `kachat-backup.json` is never read or written.
 *
 * Triggers, all funneled through one wallet-snapshotted, mutex-serialized path:
 *
 *   1. **After a contact changes** - [AddressBookManager] calls [noteContactActivity], which marks
 *      the ACTIVE wallet's contacts dirty (persisted, so a killed process can't lose the fact that
 *      a sync is owed) and restarts a [DEBOUNCE_MS] quiet-time timer.
 *   2. **App lifecycle** - hourly on background, daily catch-up on foreground ([autoBackupIfDue]);
 *      it syncs when the last sync is that old OR the dirty flag says one is owed
 *      (NEXTCLOUD_SYNC.md §6), and clears that flag on success like every other path.
 *   3. **Periodic WorkManager fallback** ([NextcloudAutoSyncWorker], every 6h, network connected
 *      + battery not low) - catches syncs the in-process paths missed because the process died
 *      first. Enqueued exactly while an account is connected AND the wallet's Automatic Sync
 *      toggle is on; disconnect/sign-out/toggle-off cancels it.
 *   4. **Remote change watcher** - while the app is FOREGROUND with an account connected and the
 *      toggle on, a Depth-0 PROPFIND polls the file's ETag (headers only, no body) every
 *      [WATCHER_POLL_MS]. When the ETag differs from the last one this device merged or wrote, the
 *      file is downloaded, decrypted and merged, silently, with one log line. A new device's first
 *      poll sees an ETag it doesn't know, so that is also how it gets its contacts: there is no
 *      separate restore.
 *
 * The per-wallet Automatic Sync toggle IS the pre-existing per-account auto-backup switch
 * ([NextcloudService.autoBackupEnabled], stored scoped key unchanged). Everything else here (dirty
 * flag, last-synced stamp, ETag) is a DataStore key carrying the wallet's 8-byte SHA256 hash suffix
 * ([NextcloudService.walletHashSuffix]), so account deletion can purge exactly one wallet's state
 * ([purgeStoredState]).
 *
 * KaPay uses this same file directly (NEXTCLOUD_SYNC.md §9), so nothing here reads or writes its old
 * plain `KaChat/KaPay Contacts.json`, which kept contact names, notes and photos readable on the
 * server.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class NextcloudSyncService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val walletManager: WalletManager,
    private val nextcloudService: NextcloudService,
    // Foreground flag source for the remote change watcher (KaChatApplication's process
    // lifecycle observer drives it) - the watcher polls only while the app is on screen.
    private val notificationHelper: NotificationHelper,
    // Metered tiers: a slower watcher, and a longer floor between automatic syncs - see the
    // companion constants.
    private val meteredNetwork: MeteredNetwork,
    // Lazy, as it reaches back to this service for noteContactActivity.
    private val addressBookManagerLazy: dagger.Lazy<AddressBookManager>,
) {
    companion object {
        private const val TAG = "NextcloudSync"

        /** Quiet time after the last contact change before the automatic sync runs. */
        const val DEBOUNCE_MS = 15_000L

        /** Remote change watcher cadence (foreground only): one Depth-0 PROPFIND per tick, relaxed
         *  on metered networks. Re-resolved every tick, so a WiFi/cellular flip takes effect on the
         *  next one. */
        const val WATCHER_POLL_MS = 30_000L
        const val WATCHER_POLL_METERED_MS = 60_000L

        /** Floor between AUTOMATIC syncs. Every automatic path (debounce, lifecycle catch-up,
         *  periodic worker) that comes due earlier re-arms to the earliest allowed time rather than
         *  dropping the work (NEXTCLOUD_SYNC.md §6). Manual Back Up Now is not floored. */
        const val MIN_UPLOAD_INTERVAL_MS = 90_000L
        const val MIN_UPLOAD_INTERVAL_METERED_MS = 300_000L

        /** Watcher backoff after consecutive failed polls: the base times 3 per failure, capped at
         *  60s, reset on the first success. */
        private const val WATCHER_BACKOFF_MULTIPLIER = 3L
        private const val WATCHER_BACKOFF_CAP_MS = 60_000L

        /** Periodic WorkManager fallback cadence for syncs the in-process paths missed. */
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

        /** From when a new device restored the chat backup once. Nothing sets it now; it's only
         *  still here to be cleared with the rest of a wallet's state. */
        private fun restoreDoneKey(address: String) =
            booleanPreferencesKey("${KEY_RESTORE_DONE}_${NextcloudService.walletHashSuffix(address)}")

        /** The contacts file's last-known WebDAV ETag for this wallet — the watcher's change
         *  detector and own-write guard: updated after every sync and merge, AND when the watcher
         *  judged a version it could not read, so that version isn't downloaded again on every
         *  poll (NEXTCLOUD_SYNC.md §6). */
        private fun etagKey(address: String) =
            stringPreferencesKey("${KEY_LAST_ETAG}_${NextcloudService.walletHashSuffix(address)}")

        /** From the chat backup's ETag short-cut, which the contacts file doesn't have. Only still
         *  here to be cleared with the rest of a wallet's state. */
        private fun mergedEtagKey(address: String) =
            stringPreferencesKey("${KEY_MERGED_ETAG}_${NextcloudService.walletHashSuffix(address)}")

        /**
         * Opens the file already at the contacts path, per NEXTCLOUD_SYNC.md "Contacts only" and §7.
         * Pure, so the cases are unit-tested. Returns the decrypted content, or null when it is
         * THIS wallet's own file that fails to decrypt: a sync replaces that one in place, since
         * Nextcloud's version history keeps the old content. Anything else that can't be opened
         * THROWS, so a sync writes nothing: a plain file or a newer envelope version, another
         * wallet's file, and an envelope with no walletHint that won't decrypt. [decrypt] opens a
         * v1 envelope with this wallet's key and throws on failure.
         */
        internal fun openContactsFile(remote: String, myAddress: String, decrypt: (String) -> String): String? {
            if (!BackupCrypto.isEnvelope(remote)) {
                throw IOException("The file at this path isn't a KaChat contacts file, so it was left as it is.")
            }
            val hint = BackupCrypto.envelopeWalletHint(remote)
            if (hint != null && hint != BackupCrypto.walletHint(myAddress)) {
                throw IOException("The contacts file on the server belongs to a different account, so it was left as it is.")
            }
            return try {
                decrypt(remote)
            } catch (e: Exception) {
                if (hint == null) throw e
                null
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.kachat.app.util.StartupSafety.logOnlyHandler("NextcloudSyncService"))

    /** Serializes syncs and merges so they can never interleave half-written state. */
    private val syncMutex = Mutex()

    private var debounceJob: Job? = null

    /** The wallet the pending debounced sync belongs to — re-checked before every step. */
    @Volatile
    private var debounceWallet: String? = null

    /** When the ACTIVE wallet's contacts last synced automatically (epoch ms), null = never. */
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

        // A null account emission means disconnect/logout: drop any pending debounced sync with it.
        scope.launch {
            nextcloudService.account.collect { account ->
                if (account == null) {
                    debounceJob?.cancel()
                    debounceJob = null
                    debounceWallet = null
                }
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

        // A wallet switch cancels the previous wallet's pending debounce outright (its sync path
        // would drop the work anyway via re-checks, but there is no reason to keep the timer alive).
        scope.launch {
            walletManager.activeAddressFlow.collect { address ->
                val pending = debounceWallet
                if (pending != null && pending != address) cancelPendingDebounce(pending)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Sync triggers
    // -------------------------------------------------------------------------

    /**
     * A contact changed. Snapshots the active wallet, marks its contacts dirty (persisted) and
     * restarts the quiet-time timer, so a run of edits becomes one sync. Cheap at any call rate.
     */
    fun noteContactActivity() {
        val address = walletManager.activeAddressFlow.value ?: return
        scope.launch {
            if (!isAutoSyncActive(address)) return@launch
            dataStore.edit { it[pendingKey(address)] = true }
            armUpload(address, DEBOUNCE_MS)
        }
    }

    /**
     * (Re)arms the one pending automatic sync for [address] to run after [delayMs]: the contact
     * debounce, and the re-arm when a sync comes due inside the floor (iOS armSyncDebounce). A new
     * arm replaces the previous one; the persisted dirty flag is what guarantees the work is owed,
     * so replacing a timer never loses it.
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
     * sync path. NEXTCLOUD_SYNC.md §6 (iOS autoBackupIfDue): a catch-up runs when the last sync is
     * at least [minIntervalMs] old (or there never was one) OR the persisted dirty flag says a
     * contact change is still owed one - either alone is enough. The floor still applies (see
     * [uploadIfDirty]). Never throws.
     */
    suspend fun autoBackupIfDue(minIntervalMs: Long = AUTO_BACKUP_MIN_INTERVAL_MS) {
        if (walletManager.isLoggedOut) return
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
     * The periodic WorkManager fallback body — syncs only when the persisted dirty flag says a
     * change is still owed (the in-process paths missed it, e.g. the process died first), or when
     * this wallet has never synced at all. Never throws.
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
     * The one automatic sync path. [address] is the wallet snapshotted at schedule time; the active
     * wallet is re-checked around every step, so a wallet switch mid-flight drops the work instead
     * of writing one account's contacts into another's file.
     */
    private suspend fun uploadIfDirty(address: String) {
        try {
            // Floor (NEXTCLOUD_SYNC.md §6, iOS performAutomaticSync): at most one automatic sync
            // per MIN_UPLOAD_INTERVAL_MS (longer on metered networks), whatever the trigger. One
            // due earlier re-arms for the earliest allowed time - the dirty flag is still set, so
            // the work is deferred, never dropped.
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

                // Clear the dirty flag BEFORE the sync: a contact changing during it re-marks the
                // flag, so nothing is lost; clearing after would swallow that signal.
                dataStore.edit { it[pendingKey(address)] = false }

                val newEtag = syncContactsFile(address)
                dataStore.edit {
                    it[lastSyncKey(address)] = System.currentTimeMillis()
                    // Own-write guard: the watcher compares against this, so this device's own
                    // write never reads as "another device changed the file". A null ETag (rare
                    // server) just means one harmless merge of our own write.
                    if (newEtag != null) it[etagKey(address)] = newEtag
                }
                Log.i(TAG, "Automatic Nextcloud sync merged the contacts")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Re-mark so the fallback worker (or the next trigger) retries what this attempt could
            // not finish - no retry loop here, including for a cut-off upload
            // (NextcloudUploadCutOffException, §4.6).
            runCatching { dataStore.edit { it[pendingKey(address)] = true } }
            Log.w(TAG, "Automatic Nextcloud sync failed (a later trigger retries)", e)
        }
    }

    /** Back Up Contacts Now: the same sync, with no floor and its failure left to the caller. */
    suspend fun backUpContactsNow() {
        val address = walletManager.activeAddressFlow.value ?: throw IOException("Open a wallet account before backing up.")
        syncMutex.withLock {
            val newEtag = syncContactsFile(address)
            if (newEtag != null) dataStore.edit { it[etagKey(address)] = newEtag }
        }
    }

    /** One read, merge, write of the contacts file. Returns the file's ETag. Throws, with nothing
     *  written, on anything [contactsFileBody] won't touch. */
    private suspend fun syncContactsFile(address: String): String? {
        val key = BackupCrypto.deriveKey(walletManager.getPrivateKeyBytes())
        return nextcloudService.runBackup { remote -> withContext(Dispatchers.IO) { contactsFileBody(address, remote, key) } }
    }

    /**
     * Merges the file at the contacts path into this device's contacts (newest edit or deletion
     * wins per address, so no device can undo another's newer change) and returns what to write, or
     * null when the file already holds everything this device has. [remote] is null when there is
     * no file yet, the one case besides this wallet's own unreadable file that writes without
     * merging. A file that decrypts but isn't an Address Book throws here, so it is left alone.
     */
    private fun contactsFileBody(address: String, remote: String?, key: ByteArray): String? {
        val book = addressBookManagerLazy.get()
        if (walletManager.activeAddressFlow.value != address || !book.holdsWallet(address)) {
            throw IOException("The active account changed during the sync.")
        }
        val serverIsBehind = if (remote == null) {
            book.hasBackupData
        } else {
            val plaintext = openContactsFile(remote, address) { BackupCrypto.decrypt(it, key) }
            if (plaintext == null) {
                Log.w(TAG, "This account's contacts file can't be decrypted; replacing it in place")
                true
            } else {
                book.mergeBackup(plaintext.toByteArray(Charsets.UTF_8)).serverIsBehind
            }
        }
        if (!serverIsBehind) return null
        return BackupCrypto.encrypt(String(book.exportData(includingDeleted = true), Charsets.UTF_8), key, address)
    }

    // -------------------------------------------------------------------------
    // Remote change watcher
    // -------------------------------------------------------------------------

    /**
     * Every tick a Depth-0 PROPFIND asks the server for the contacts file's ETag — headers and a few
     * hundred XML bytes, never the file. A different ETag means another device wrote the file, or
     * this device has never seen it; [mergeRemoteChange] then downloads and merges it. Runs only
     * while the init observer's conditions hold (foreground + connected + toggle on + this wallet
     * active) and is cancelled by collectLatest the moment any of them breaks.
     *
     * Failed polls back off (times [WATCHER_BACKOFF_MULTIPLIER] per consecutive failure, capped at
     * [WATCHER_BACKOFF_CAP_MS]) and recover to the normal cadence on the first success.
     */
    private suspend fun watchRemoteChanges(address: String) {
        var consecutiveFailures = 0
        while (true) {
            var delayMs = if (meteredNetwork.isMetered) WATCHER_POLL_METERED_MS else WATCHER_POLL_MS
            repeat(minOf(consecutiveFailures, 3)) {
                delayMs = minOf(delayMs * WATCHER_BACKOFF_MULTIPLIER, WATCHER_BACKOFF_CAP_MS)
            }
            delay(delayMs)
            try {
                if (!isAutoSyncActive(address)) continue

                // ETag BEFORE the download: if the file is replaced between the two requests, the
                // stored ETag is the older one and the next poll simply merges again - the safe
                // direction (a merge is idempotent).
                val etag = nextcloudService.fetchBackupEtag()
                consecutiveFailures = 0
                if (etag == null) continue // No file yet — nothing to merge.

                val known = dataStore.data.first()[etagKey(address)]
                if (etag != known) mergeRemoteChange(address, etag)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                consecutiveFailures++
                Log.w(TAG, "Nextcloud change watcher poll failed (backing off)", e)
            }
        }
    }

    /**
     * Downloads, decrypts and merges the contacts file, silently - one log line, no UI. Serialized
     * behind [syncMutex] so it can never interleave with a sync, with the same wallet-snapshot
     * re-checks around the merge.
     *
     * NEXTCLOUD_SYNC.md: a file this wallet can't read still has its ETag recorded, so it isn't
     * downloaded on every poll; a replacement changes the ETag and gets a fresh look. A failed or
     * early-ended DOWNLOAD is different - it says nothing about the file - so it throws, the caller
     * backs off and the ETag stays put for the next poll to retry.
     */
    private suspend fun mergeRemoteChange(address: String, etag: String) {
        syncMutex.withLock {
            if (walletManager.activeAddressFlow.value != address) return
            if (!isAutoSyncActive(address)) return
            // The poll compared ETags outside the lock: one taken while our own sync was uploading
            // is that upload's, which the sync records once it has written. Asked again here, it
            // is no longer news, not another device's change.
            if (dataStore.data.first()[etagKey(address)] == etag) return

            val raw = nextcloudService.downloadBackup()
            if (walletManager.activeAddressFlow.value != address) return

            val book = addressBookManagerLazy.get()
            if (!book.holdsWallet(address)) return
            val serverIsBehind = try {
                val key = BackupCrypto.deriveKey(walletManager.getPrivateKeyBytes())
                val plaintext = openContactsFile(raw, address) { BackupCrypto.decrypt(it, key) }
                    ?: throw IOException("It can't be decrypted.")
                book.mergeBackup(plaintext.toByteArray(Charsets.UTF_8)).serverIsBehind
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Nextcloud change watcher skipped an unreadable contacts file: ${e.message}")
                dataStore.edit { it[etagKey(address)] = etag }
                return
            }
            dataStore.edit { it[etagKey(address)] = etag }
            if (serverIsBehind) noteContactActivity()
            Log.i(TAG, "Nextcloud change watcher merged another device's contacts")
        }
    }

    // -------------------------------------------------------------------------
    // Settings + lifecycle
    // -------------------------------------------------------------------------

    /**
     * Flips the ACTIVE wallet's Automatic Sync toggle — the pre-existing per-account
     * auto-backup switch ([NextcloudService.setAutoBackupEnabled], same stored scoped key).
     * Turning it on marks the contacts dirty so the first sync happens promptly (and the
     * worker-scheduling observer reacts to the flow); turning it off drops the pending debounce.
     */
    fun setAutoSyncEnabled(enabled: Boolean) {
        nextcloudService.setAutoBackupEnabled(enabled)
        val address = walletManager.activeAddressFlow.value ?: return
        if (enabled) {
            scope.launch {
                dataStore.edit { it[pendingKey(address)] = true }
                noteContactActivity()
            }
        } else {
            cancelPendingDebounce(address)
        }
    }

    /**
     * Account deletion: removes [walletAddress]'s sync keys (last-synced stamp, dirty flag, ETags)
     * and its pending debounced sync — the sibling of [NextcloudService.purgeStoredState], which
     * drops the credentials and toggle themselves. Other wallets' state is untouched; the shared
     * periodic job is cancelled by the init observer when the purge disconnects the active wallet.
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

    /** Signed in, connected for the snapshotted (still-active) wallet AND its Automatic Sync toggle
     *  on. iOS clears NextcloudService's wallet at logout, which stops every automatic path; the
     *  active address here survives logout. */
    private fun isAutoSyncActive(address: String): Boolean =
        !walletManager.isLoggedOut &&
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
