package com.kachat.app.services

import android.content.Context
import android.net.Uri
import android.util.Log
import android.util.Xml
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A connected Nextcloud account (server + login + app password). */
data class NextcloudAccount(
    val server: String,
    val username: String,
    val appPassword: String,
    /** Where "Send from Nextcloud" starts browsing — null means the files root. */
    val startFolder: String? = null,
    /** Where message backups upload — null means the default "KaChat" folder at the files root. */
    val backupFolder: String? = null
) {
    val displayName: String
        get() {
            val host = server.toHttpUrlOrNull()?.host ?: server
            return "$username@$host"
        }
}

/** One entry from a WebDAV folder listing. [path] is relative to the user's files root, e.g. "Photos/cat.jpg". */
data class NextcloudFile(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val contentType: String?,
    val size: Long?,
    val modifiedMs: Long?
) {
    /** Content-Type first, file extension as fallback — servers without a mimetype mapping for
     *  HEIC/MOV and friends report `application/octet-stream`, which would otherwise hide real
     *  media from the picker entirely. */
    val isImage: Boolean
        get() = contentType?.startsWith("image/") == true || extension in IMAGE_EXTENSIONS

    val isVideo: Boolean
        get() = contentType?.startsWith("video/") == true || extension in VIDEO_EXTENSIONS

    private val extension: String get() = path.substringAfterLast('.', "").lowercase()

    private companion object {
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "heic", "heif", "bmp", "tiff")
        val VIDEO_EXTENSIONS = setOf("mov", "mp4", "m4v", "webm", "mkv", "avi")
    }
}

/**
 * The one ordering every Nextcloud listing surface uses: phone-gallery order.
 *
 * Folders stay grouped ahead of files (so a folder never lands in the middle of the thumbnail
 * grid), and within each group entries run newest-first by `getlastmodified`. Entries whose date
 * the server omitted or that failed to parse sort last rather than interleaving randomly, and name
 * is the tiebreak so equal timestamps stay deterministic.
 *
 * Applied once in [NextcloudService.listFolder]; screens only filter, never re-sort.
 */
private val NEWEST_FIRST: Comparator<NextcloudFile> =
    compareByDescending<NextcloudFile> { it.isDirectory }
        .thenByDescending { it.modifiedMs ?: Long.MIN_VALUE }
        .thenBy { it.name.lowercase() }

fun List<NextcloudFile>.sortedNewestFirst(): List<NextcloudFile> = sortedWith(NEWEST_FIRST)

/** URL + Authorization header value for a server-generated thumbnail, ready to hand to Coil. */
data class NextcloudThumbnailRequest(val url: String, val authorization: String)

/**
 * The backup PUT finished but the server stored a different number of bytes than were sent:
 * something between this device and Nextcloud (a relay, a proxy) ended the upload early
 * (NEXTCLOUD_SYNC.md §4.6 and §7, iOS d57019a `NextcloudError.uploadCutOff`). Deliberately NOT an
 * [IOException]: it is not a transfer hiccup worth "check your connection" advice, and
 * [com.kachat.app.util.UserFacingError] shows an app-authored exception's own message, so the
 * person reads plainly what happened. The automatic sync re-marks the archive dirty and stops -
 * the next trigger tries again; nothing retries in a loop.
 */
class NextcloudUploadCutOffException(val sentBytes: Long, val storedBytes: Long) : Exception(
    "The backup upload was cut off: the server stored $storedBytes of $sentBytes bytes. " +
        "Something between this device and Nextcloud is ending large uploads early."
)

/**
 * Talks to the user's own Nextcloud server (mirrors iOS's `NextcloudService.swift`): connect with
 * an app password, browse files over WebDAV, and mint public `/s/TOKEN` share links via the OCS
 * API — so chats carry a small link the recipient's link-preview feature renders, instead of
 * pushing file bytes through the on-chain payload. Credentials live in their own
 * `EncryptedSharedPreferences` file (same secure-prefs pattern as [ColdStorageManager]) — a
 * completely separate trust domain from the wallet's own storage.
 *
 * Everything here is scoped to the ACTIVE WALLET ACCOUNT, matching iOS and desktop: every stored
 * key (credentials, folders, toggles, throttle stamp) carries the active wallet's 8-byte SHA256
 * hash suffix (same scheme as iOS's `KeychainService.walletHashSuffix`), and the service follows
 * [WalletManager.activeAddressFlow] so an account switch/logout/delete swaps the whole state and
 * one account's login never leaks into another. A single legacy (pre-per-account) global entry
 * migrates once to the first active wallet that sees it, then the global copy is deleted.
 */
@Singleton
class NextcloudService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val walletManager: WalletManager
) {
    companion object {
        private const val TAG = "NextcloudService"

        /** Pauses between PUT attempts while the archive is locked - half a minute in all. */
        private val LOCK_RETRY_DELAYS_SECONDS = listOf(1L, 2L, 4L, 8L, 15L)
        private const val SECURE_PREFS_NAME = "nextcloud_secure_prefs"
        // Base names only: the active wallet's hash suffix is appended via `scopedKey`. The bare
        // names are the LEGACY pre-per-account entries, read once by the migration then deleted.
        private const val PREF_SERVER = "server"
        private const val PREF_USERNAME = "username"
        private const val PREF_APP_PASSWORD = "app_password"
        private const val PREF_START_FOLDER = "start_folder"
        private const val PREF_BACKUP_FOLDER = "backup_folder"
        // The per-account "Automatic Sync" toggle (historically "Automatic Backup" — the stored
        // key is unchanged for continuity). NextcloudSyncService gates every automatic path on it.
        private const val PREF_AUTO_BACKUP_ENABLED = "auto_backup_enabled"
        // Legacy hourly-throttle stamp from the pre-continuous-sync autoBackupIfDue path; kept in
        // ALL_PREF_BASES so disconnect/purge/migration still clean it up. NextcloudSyncService
        // owns the last-synced stamp now (DataStore).
        private const val PREF_LAST_AUTO_BACKUP_MS = "last_auto_backup_ms"
        // The retired "Send Media via Nextcloud" switch (iOS 8b13460 replaced it with an on chain
        // or via Nextcloud choice per send). Never read any more; kept in ALL_PREF_BASES so
        // disconnect/purge still clean up a value stored by an older build.
        private const val PREF_MEDIA_SEND_ENABLED = "media_send_enabled"
        /** The last capabilities probe's answer, per wallet, so a launch knows at once whether
         *  this account can host a call - a push that starts the app cannot wait for the network. */
        private const val PREF_TALK_CALLS = "talk_calls_available"

        /** Every per-wallet key base — the unit `disconnect`/`purgeStoredState`/migration act on. */
        private val ALL_PREF_BASES = listOf(
            PREF_SERVER, PREF_USERNAME, PREF_APP_PASSWORD, PREF_START_FOLDER, PREF_BACKUP_FOLDER,
            PREF_AUTO_BACKUP_ENABLED, PREF_LAST_AUTO_BACKUP_MS, PREF_MEDIA_SEND_ENABLED,
            PREF_TALK_CALLS
        )

        /** First 8 bytes of SHA256(walletAddress) as hex — byte-identical to iOS's
         *  `KeychainService.walletHashSuffix` and desktop's per-account scheme. */
        fun walletHashSuffix(walletAddress: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(walletAddress.toByteArray(Charsets.UTF_8))
                .take(8)
                .joinToString("") { "%02x".format(it) }

        const val BACKUP_FOLDER_NAME = "KaChat"
        /** Where "Send Media via Nextcloud" uploads land: a fixed KaChat/Media folder at the files root. */
        const val MEDIA_FOLDER_PATH = "$BACKUP_FOLDER_NAME/Media"
        /** FIXED filename, identical to iOS/desktop — same archive schema, so a backup written by
         *  one platform restores cleanly on any other. */
        const val BACKUP_FILE_NAME = "kachat-backup.json"

        /** Bounds for [discoverExistingBackupFolder] - a connect must not become a full crawl. */
        private const val FOLDER_DISCOVERY_MAX_FOLDERS = 40
        private const val FOLDER_DISCOVERY_MAX_DEPTH = 3

        /** Normalizes user input ("mycloud.duckdns.org", trailing slashes, an accidental
         *  "/index.php" suffix) into a clean base URL, defaulting to https. Null if it doesn't
         *  parse as a URL at all, or if it explicitly asks for http:// — credentials and chat
         *  history must never travel unencrypted, so plaintext HTTP is rejected outright (the
         *  settings form shows the same rule inline before this is ever reached). */
        fun normalizeServer(input: String): String? {
            var raw = input.trim()
            if (raw.isEmpty()) return null
            if (raw.lowercase().startsWith("http://")) return null
            if (!raw.lowercase().startsWith("https://")) {
                raw = "https://$raw"
            }
            raw = raw.trimEnd('/')
            if (raw.lowercase().endsWith("/index.php")) raw = raw.dropLast("/index.php".length)
            val url = raw.toHttpUrlOrNull() ?: return null
            return if (url.host.isEmpty()) null else raw
        }

        /**
         * The verify step after every backup PUT (NEXTCLOUD_SYNC.md §4.6, iOS d57019a): true when
         * the upload must be reported as cut off. [storedBytes] and [currentEtag] come from one
         * Depth-0 PROPFIND made right after the PUT; [putEtag] is the ETag the PUT response
         * carried (null when a proxy stripped it).
         *
         * Only our own write counts: when the server's ETag has moved on from the one our PUT
         * returned, another device replaced the file in between and its size says nothing about
         * our upload. Without a PUT ETag there is nothing to tell the two apart, so a size
         * mismatch is taken at face value - as iOS does. An unknown stored size (the PROPFIND
         * failed or omitted getcontentlength) is no evidence either way.
         */
        internal fun isUploadCutOff(sentBytes: Long, storedBytes: Long?, putEtag: String?, currentEtag: String?): Boolean {
            if (storedBytes == null || storedBytes == sentBytes) return false
            return putEtag == null || currentEtag == putEtag
        }

        /** WebDAV's getlastmodified is RFC 1123 ("Mon, 11 Aug 2026 20:14:07 GMT"). */
        private fun rfc1123Formatter() = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        SECURE_PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // Longer write timeout than LinkPreviewService's scrape client — backup PUTs can be multi-MB
    // uploads to a home server on a slow uplink.
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * The backup PUT's client: [client] with the read timeout raised to 120 s. Once the body is
     * sent, Nextcloud can sit silent for a long time while it stores a large archive, and the
     * 60 s read timeout would give up on a write that is still succeeding. NEXTCLOUD_SYNC.md §7
     * asks anything between a client and Nextcloud to give writes at least 120 s; this device's
     * own wait is held to the same floor. Shares [client]'s connection pool.
     */
    private val backupWriteClient = client.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val _account = MutableStateFlow<NextcloudAccount?>(null)
    val account: StateFlow<NextcloudAccount?> = _account.asStateFlow()

    private val _autoBackupEnabled = MutableStateFlow(false)
    val autoBackupEnabled: StateFlow<Boolean> = _autoBackupEnabled.asStateFlow()

    /** Whether the connected server has Nextcloud Talk with calls enabled - what makes the call
     *  button appear in 1:1 chats (see CallService.canCall). Read from the server's capabilities
     *  on connect and on every wallet activation; false until known. */
    private val _talkCallsAvailable = MutableStateFlow(false)
    val talkCallsAvailable: StateFlow<Boolean> = _talkCallsAvailable.asStateFlow()

    /** Why the last Talk probe answered what it did - "Talk with calls enabled", "no spreed
     *  capability", "HTTP 401", ... - for the diagnostics archive and the log, so a missing call
     *  button can be explained rather than guessed at. */
    private val _talkAvailabilityReason = MutableStateFlow("not probed yet")
    val talkAvailabilityReason: StateFlow<String> = _talkAvailabilityReason.asStateFlow()

    /** The active wallet's address — every credential/settings read and write is scoped to it.
     *  Null (signed out / no wallet yet) presents as disconnected and persists nothing. */
    @Volatile
    private var currentWalletAddress: String? = null

    /** Cached per-wallet suffix (8-byte SHA256 hex, see [walletHashSuffix]) for the pref keys. */
    @Volatile
    private var currentSuffix: String? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        // Load the current wallet's state synchronously (the settings screens may compose before
        // the collector's first dispatch), then follow every switch/logout/delete after that.
        setCurrentWallet(walletManager.activeAddressFlow.value, force = true)
        serviceScope.launch {
            walletManager.activeAddressFlow.collect { setCurrentWallet(it) }
        }
    }

    val isConnected: Boolean get() = _account.value != null

    /**
     * Off for the whole testnet launch (iOS af8ec68): the backup archive, media folder and Talk
     * calls are one per account across both networks, so testnet could otherwise write testnet
     * history over the mainnet backup or import mainnet history into testnet. With no account
     * nothing syncs, restores, watches, uploads or rings, and every Nextcloud option hides as if
     * disconnected. The mainnet login is untouched and comes back on the next mainnet launch.
     */
    val isOffForTestnet: Boolean get() = com.kachat.app.util.KaspaNetwork.isTestnet

    /** The folder backups actually go to — the user's chosen folder, or "KaChat" by default. */
    val backupFolderPath: String get() = _account.value?.backupFolder ?: BACKUP_FOLDER_NAME

    // -------------------------------------------------------------------------
    // Wallet scoping
    // -------------------------------------------------------------------------

    /** The active wallet's pref key for [base], or null when signed out (in which case nothing
     *  is read or written). */
    private fun scopedKey(base: String): String? = currentSuffix?.let { "${base}_$it" }

    private fun scopedKey(base: String, suffix: String): String = "${base}_$suffix"

    /**
     * Points the service at [walletAddress]'s stored Nextcloud state, or clears everything for
     * null. Driven by [WalletManager.activeAddressFlow], which fires on every wallet load,
     * account switch, logout and delete. Cancels any in-flight automatic backup first.
     */
    private fun setCurrentWallet(requestedAddress: String?, force: Boolean = false) {
        val walletAddress = if (isOffForTestnet) null else requestedAddress
        if (!force && walletAddress == currentWalletAddress) return

        currentWalletAddress = walletAddress
        currentSuffix = walletAddress?.let { walletHashSuffix(it) }

        if (walletAddress == null) {
            _account.value = null
            _autoBackupEnabled.value = false
            _talkCallsAvailable.value = false
            return
        }

        migrateLegacyGlobalStateIfNeeded()

        _account.value = loadAccount()
        _autoBackupEnabled.value = resolveAutoBackupEnabled(currentSuffix ?: return, connected = _account.value != null)
        // What the last probe said, until this one answers. A call arriving seconds after the
        // app was woken by a push would otherwise be turned away as "cannot host" simply because
        // the capabilities lookup had not come back yet.
        _talkCallsAvailable.value = _account.value != null &&
            (scopedKey(PREF_TALK_CALLS)?.let { prefs.getBoolean(it, false) } ?: false)
        refreshTalkAvailability()
    }

    /** Asks the server's capabilities whether Talk is installed with calls on, and publishes
     *  the answer. Best effort: a failed lookup leaves the button hidden until the next try. */
    fun refreshTalkAvailability() {
        val account = _account.value ?: run { _talkCallsAvailable.value = false; _talkAvailabilityReason.value = "no Nextcloud account connected"; return }
        val owner = currentWalletAddress
        serviceScope.launch {
            val (available, reason) = withContext(Dispatchers.IO) { probeTalkCalls(account) }
            if (currentWalletAddress != owner || _account.value?.server != account.server) return@launch
            _talkAvailabilityReason.value = reason
            if (_talkCallsAvailable.value != available) _talkCallsAvailable.value = available
            scopedKey(PREF_TALK_CALLS)?.let { prefs.edit().putBoolean(it, available).apply() }
            Log.i("NextcloudService", "Talk calls ${if (available) "available" else "NOT available"} on ${account.server}: $reason")
        }
    }

    /** Whether the server has Talk with calls enabled, and the reason in words. */
    private fun probeTalkCalls(account: NextcloudAccount): Pair<Boolean, String> {
        return try {
            val request = Request.Builder()
                .url("${account.server.trimEnd('/')}/ocs/v2.php/cloud/capabilities?format=json")
                .header("Authorization", basicAuth(account))
                .header("OCS-APIRequest", "true")
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false to "capabilities answered HTTP ${response.code}"
                val body = response.body?.string() ?: return false to "capabilities answered an empty body"
                val capabilities = runCatching {
                    JSONObject(body).getJSONObject("ocs").getJSONObject("data").getJSONObject("capabilities")
                }.getOrNull() ?: return false to "capabilities answer was not the OCS shape"
                val spreed = capabilities.optJSONObject("spreed") ?: return false to "Nextcloud Talk (spreed) is not installed or not enabled for this user"
                val features = spreed.optJSONArray("features")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList()
                val missing = listOf("conversation-v4", "signaling-v3").filter { it !in features }
                if (missing.isNotEmpty()) return false to "Talk is too old: missing ${missing.joinToString()}"
                val call = spreed.optJSONObject("config")?.optJSONObject("call")
                // Absent means an older Talk that never had the switch - calls are on.
                val enabled = when (val raw = call?.opt("enabled")) {
                    null -> true
                    is Boolean -> raw
                    is Number -> raw.toInt() != 0
                    is String -> raw != "0" && raw != "false"
                    else -> true
                }
                if (enabled) true to "Talk with calls enabled" else false to "Talk is installed but calls are switched off in its settings"
            }
        } catch (e: Exception) {
            false to "capabilities request failed: ${e.message}"
        }
    }

    /**
     * The wallet's Automatic Sync toggle. A choice on record wins. With none, the connected
     * default is ON: Nextcloud is the only cross-device sync the app has, so a connected server
     * syncs unless told not to (iOS resolveAndMigrateAutoSyncEnabled). The resolved default is
     * written back, so every later read - here, the sync service's activation read, the
     * settings toggle - agrees.
     */
    private fun resolveAutoBackupEnabled(suffix: String, connected: Boolean): Boolean {
        val key = scopedKey(PREF_AUTO_BACKUP_ENABLED, suffix)
        if (prefs.contains(key)) return prefs.getBoolean(key, false)
        if (!connected) return false
        prefs.edit().putBoolean(key, true).apply()
        return true
    }

    /**
     * One-time migration off the pre-per-wallet storage: the single global credential entry and
     * the global toggles/throttle stamp move to the active wallet's scoped keys — the account
     * that was actually using the login keeps it — then the global entries are deleted so no
     * other account ever sees them again.
     */
    private fun migrateLegacyGlobalStateIfNeeded() {
        val suffix = currentSuffix ?: return
        var migratedAnything = false
        val editor = prefs.edit()

        val legacyServer = prefs.getString(PREF_SERVER, null)
        val legacyUsername = prefs.getString(PREF_USERNAME, null)
        val legacyPassword = prefs.getString(PREF_APP_PASSWORD, null)
        if (legacyServer != null && legacyUsername != null && legacyPassword != null) {
            if (prefs.getString(scopedKey(PREF_SERVER, suffix), null) == null) {
                editor.putString(scopedKey(PREF_SERVER, suffix), legacyServer)
                editor.putString(scopedKey(PREF_USERNAME, suffix), legacyUsername)
                editor.putString(scopedKey(PREF_APP_PASSWORD, suffix), legacyPassword)
                prefs.getString(PREF_START_FOLDER, null)?.let { editor.putString(scopedKey(PREF_START_FOLDER, suffix), it) }
                prefs.getString(PREF_BACKUP_FOLDER, null)?.let { editor.putString(scopedKey(PREF_BACKUP_FOLDER, suffix), it) }
            }
            migratedAnything = true
        }

        if (prefs.contains(PREF_AUTO_BACKUP_ENABLED)) {
            if (!prefs.contains(scopedKey(PREF_AUTO_BACKUP_ENABLED, suffix))) {
                editor.putBoolean(scopedKey(PREF_AUTO_BACKUP_ENABLED, suffix), prefs.getBoolean(PREF_AUTO_BACKUP_ENABLED, false))
            }
            migratedAnything = true
        }
        if (prefs.contains(PREF_MEDIA_SEND_ENABLED)) {
            // The retired media-send switch: nothing to carry over, only to clear (below).
            migratedAnything = true
        }
        if (prefs.contains(PREF_LAST_AUTO_BACKUP_MS)) {
            if (!prefs.contains(scopedKey(PREF_LAST_AUTO_BACKUP_MS, suffix))) {
                editor.putLong(scopedKey(PREF_LAST_AUTO_BACKUP_MS, suffix), prefs.getLong(PREF_LAST_AUTO_BACKUP_MS, 0L))
            }
            migratedAnything = true
        }

        if (migratedAnything) {
            for (base in ALL_PREF_BASES) editor.remove(base)
            editor.apply()
            Log.i(TAG, "Migrated the global Nextcloud login/settings to the active wallet's per-account storage")
        }
    }

    /**
     * Deletes a wallet's stored Nextcloud login and settings outright — used when that account
     * is removed from this device entirely (the danger-zone wipe-account flow). Storage only;
     * if the wallet is still the active one, the in-memory state clears too (the account switch
     * that follows deletion reloads state for whichever wallet becomes active).
     */
    fun purgeStoredState(walletAddress: String) {
        val suffix = walletHashSuffix(walletAddress)
        val editor = prefs.edit()
        for (base in ALL_PREF_BASES) editor.remove(scopedKey(base, suffix))
        editor.apply()
        if (walletAddress == currentWalletAddress) {
            _account.value = null
            _autoBackupEnabled.value = false
        }
    }

    private fun loadAccount(): NextcloudAccount? {
        val server = scopedKey(PREF_SERVER)?.let { prefs.getString(it, null) } ?: return null
        val username = scopedKey(PREF_USERNAME)?.let { prefs.getString(it, null) } ?: return null
        val appPassword = scopedKey(PREF_APP_PASSWORD)?.let { prefs.getString(it, null) } ?: return null
        return NextcloudAccount(
            server = server,
            username = username,
            appPassword = appPassword,
            startFolder = scopedKey(PREF_START_FOLDER)?.let { prefs.getString(it, null) },
            backupFolder = scopedKey(PREF_BACKUP_FOLDER)?.let { prefs.getString(it, null) }
        )
    }

    private fun persistAccount(account: NextcloudAccount) {
        val suffix = currentSuffix ?: return
        prefs.edit()
            .putString(scopedKey(PREF_SERVER, suffix), account.server)
            .putString(scopedKey(PREF_USERNAME, suffix), account.username)
            .putString(scopedKey(PREF_APP_PASSWORD, suffix), account.appPassword)
            .apply {
                if (account.startFolder != null) putString(scopedKey(PREF_START_FOLDER, suffix), account.startFolder) else remove(scopedKey(PREF_START_FOLDER, suffix))
                if (account.backupFolder != null) putString(scopedKey(PREF_BACKUP_FOLDER, suffix), account.backupFolder) else remove(scopedKey(PREF_BACKUP_FOLDER, suffix))
            }
            .apply()
        _account.value = account
    }

    /**
     * Verifies the credentials against the OCS user endpoint (the cheapest authenticated call),
     * then persists them. Throws with a user-facing message — a 401 says exactly what's wrong.
     */
    suspend fun connect(serverInput: String, username: String, appPassword: String) {
        if (isOffForTestnet) throw IOException(context.getString(com.kachat.app.R.string.nextcloud_off_on_testnet))
        if (currentWalletAddress == null) throw IOException("Open a wallet account before connecting to Nextcloud.")
        val server = normalizeServer(serverInput)
            ?: throw IOException("That doesn't look like a valid server URL.")
        val user = username.trim()
        val password = appPassword.trim()
        if (user.isEmpty() || password.isEmpty()) {
            throw IOException("Nextcloud rejected the username or app password.")
        }
        val candidate = NextcloudAccount(server = server, username = user, appPassword = password)

        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$server/ocs/v2.php/cloud/user?format=json")
                .header("Authorization", basicAuth(candidate))
                .header("OCS-APIRequest", "true")
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 401) throw IOException("Nextcloud rejected the username or app password.")
                if (!response.isSuccessful) throw IOException("Nextcloud returned HTTP ${response.code}.")
                val body = response.body?.string() ?: throw IOException("Unexpected response from the Nextcloud server.")
                val ocsData = runCatching { JSONObject(body).getJSONObject("ocs").optJSONObject("data") }.getOrNull()
                    ?: throw IOException("Unexpected response from the Nextcloud server.")
                // ocs.data existing at all is the success signal; its contents aren't needed.
                ocsData
            }
        }
        persistAccount(candidate)
        // Connected: Automatic Sync defaults ON unless a choice is already on record.
        _autoBackupEnabled.value = resolveAutoBackupEnabled(currentSuffix ?: return, connected = true)
        refreshTalkAvailability()

        // Point at the backup this account already has, before anything reads or writes one.
        // Only when the user has not chosen a folder themselves - an explicit choice outranks
        // whatever a search turns up.
        if (candidate.backupFolder == null) {
            discoverExistingBackupFolder()?.let { found ->
                Log.i("NextcloudService", "Linked existing backup folder: $found")
                setBackupFolder(found)
            }
        }
    }

    fun disconnect() {
        val editor = prefs.edit()
        // Only the active wallet's entries — other accounts' logins stay untouched.
        currentSuffix?.let { suffix ->
            for (base in ALL_PREF_BASES) editor.remove(scopedKey(base, suffix))
        }
        // Belt and braces: if a legacy global entry somehow still exists, remove it too so
        // disconnect can never appear to "come back" via migration.
        for (base in ALL_PREF_BASES) editor.remove(base)
        editor.apply()
        _account.value = null
        _autoBackupEnabled.value = false
        _talkCallsAvailable.value = false
    }

    /** Persists the picker's start folder (null/"" = files root). */
    fun setStartFolder(path: String?) {
        val current = _account.value ?: return
        persistAccount(current.copy(startFolder = path?.trim()?.takeIf { it.isNotEmpty() }))
    }

    /**
     * Finds the KaChat folder this account already has, instead of assuming there isn't one.
     *
     * The backup destination defaults to "KaChat" at the files root, so an account whose folder
     * lives anywhere else - moved, nested under a Documents/Apps folder, made on another device
     * pointed elsewhere - looked to a fresh connection like an account with no backup at all, and
     * the app would happily start a second one beside it.
     *
     * Prefers a folder that actually holds [BACKUP_FILE_NAME]; a folder merely NAMED KaChat is the
     * fallback. Breadth-first and bounded - a WebDAV walk of someone's whole drive is not a thing
     * to do on connect.
     */
    suspend fun discoverExistingBackupFolder(): String? {
        if (_account.value == null) return null
        val queue = ArrayDeque<String>().apply { add("") }
        var visited = 0
        var namedCandidate: String? = null

        while (queue.isNotEmpty() && visited < FOLDER_DISCOVERY_MAX_FOLDERS) {
            val path = queue.removeFirst()
            visited++
            val entries = runCatching { listFolder(path) }.getOrNull() ?: continue

            // A folder holding the backup file IS the answer - stop looking.
            if (entries.any { !it.isDirectory && it.name == BACKUP_FILE_NAME }) {
                return path.ifEmpty { null }
            }
            for (entry in entries.filter { it.isDirectory }) {
                if (namedCandidate == null && entry.name.equals(BACKUP_FOLDER_NAME, ignoreCase = true)) {
                    namedCandidate = entry.path
                }
                // Depth cap: a backup folder buried deeper than this is not something the app put
                // there, and each extra level multiplies the requests.
                if (entry.path.split("/").filter { it.isNotEmpty() }.size < FOLDER_DISCOVERY_MAX_DEPTH) {
                    queue.add(entry.path)
                }
            }
        }
        return namedCandidate
    }

    /** Persists the backup destination folder (null/"" = the default "KaChat" folder). */
    fun setBackupFolder(path: String?) {
        val current = _account.value ?: return
        persistAccount(current.copy(backupFolder = path?.trim()?.takeIf { it.isNotEmpty() }))
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        val key = scopedKey(PREF_AUTO_BACKUP_ENABLED) ?: return
        prefs.edit().putBoolean(key, enabled).apply()
        _autoBackupEnabled.value = enabled
    }

    /** Reads [walletAddress]'s persisted Automatic Sync toggle straight from storage, without
     *  waiting for the active-wallet state swap — the timing-independent read the
     *  reconciliation the sync service uses at wallet activation. */
    fun isAutoBackupEnabledFor(walletAddress: String): Boolean {
        val suffix = walletHashSuffix(walletAddress)
        val connected = prefs.contains(scopedKey(PREF_SERVER, suffix)) && prefs.contains(scopedKey(PREF_APP_PASSWORD, suffix))
        return resolveAutoBackupEnabled(suffix, connected)
    }

    private fun basicAuth(account: NextcloudAccount): String =
        "Basic " + android.util.Base64.encodeToString(
            "${account.username}:${account.appPassword}".toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )

    private fun requireAccount(): NextcloudAccount =
        _account.value ?: throw IOException("Not connected to a Nextcloud server.")

    /** WebDAV URL for a path relative to the user's files root, each segment percent-encoded. */
    private fun davUrl(account: NextcloudAccount, relativePath: String): HttpUrl {
        val base = account.server.toHttpUrlOrNull()
            ?: throw IOException("That doesn't look like a valid server URL.")
        val builder = base.newBuilder()
        for (part in "remote.php/dav/files/${account.username}/$relativePath".split("/")) {
            if (part.isNotEmpty()) builder.addPathSegment(part)
        }
        return builder.build()
    }

    // -------------------------------------------------------------------------
    // Plain files in the KaChat folder (Portfolio CSV export / import, iOS aa5d783)
    // -------------------------------------------------------------------------

    /**
     * Uploads [bytes] as [filename] into the KaChat folder ([backupFolderPath] - the same folder
     * the chat backup lives in), creating the folder chain if it isn't there yet, and returns the
     * stored path. A file of the same name is replaced - exports carry a timestamp in their name.
     * [keepSpaces] keeps a name the user gave (a portfolio's, "KaChat Address Book") readable in
     * Nextcloud instead of turning its spaces into underscores (iOS 87b2a0b).
     */
    suspend fun uploadToKaChatFolder(
        bytes: ByteArray,
        filename: String,
        contentType: String,
        keepSpaces: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val account = requireAccount()
        val folder = backupFolderPath.trim('/')
        // Level by level: MKCOL is not recursive, and 405 means the level already exists.
        var level = ""
        for (part in folder.split("/").filter { it.isNotEmpty() }) {
            level = if (level.isEmpty()) part else "$level/$part"
            val mkcol = Request.Builder().url(davUrl(account, level)).method("MKCOL", null)
                .header("Authorization", basicAuth(account)).build()
            client.newCall(mkcol).execute().use { response ->
                if (response.code == 401) throw IOException("Nextcloud rejected the username or app password.")
                if (!response.isSuccessful && response.code != 405) throw IOException("Nextcloud returned HTTP ${response.code}.")
            }
        }
        val disallowed = if (keepSpaces) Regex("[^A-Za-z0-9._ -]") else Regex("[^A-Za-z0-9._-]")
        val storedName = filename.replace(disallowed, "_").takeIf { it.isNotBlank() } ?: "file"
        val path = if (folder.isEmpty()) storedName else "$folder/$storedName"
        val mediaType = contentType.toMediaTypeOrNull() ?: "application/octet-stream".toMediaType()
        val put = Request.Builder().url(davUrl(account, path)).put(bytes.toRequestBody(mediaType))
            .header("Authorization", basicAuth(account)).build()
        client.newCall(put).execute().use { response ->
            if (response.code == 401) throw IOException("Nextcloud rejected the username or app password.")
            if (!response.isSuccessful) throw IOException("Nextcloud returned HTTP ${response.code}.")
        }
        path
    }

    /** A file's bytes, for importing it. Every failure throws so the caller can say what went
     *  wrong; capped at [maxBytes]. */
    suspend fun downloadFile(relativePath: String, maxBytes: Long = 10_000_000L): ByteArray = withContext(Dispatchers.IO) {
        val account = requireAccount()
        val get = Request.Builder().url(davUrl(account, relativePath)).header("Authorization", basicAuth(account)).build()
        client.newCall(get).execute().use { response ->
            if (response.code == 401) throw IOException("Nextcloud rejected the username or app password.")
            if (!response.isSuccessful) throw IOException("Nextcloud returned HTTP ${response.code}.")
            val body = response.body ?: throw IOException("Nextcloud sent an empty file.")
            if (body.contentLength() > maxBytes) throw IOException("That file is too large to import.")
            val bytes = body.bytes()
            if (bytes.size > maxBytes) throw IOException("That file is too large to import.")
            bytes
        }
    }

    // -------------------------------------------------------------------------
    // WebDAV browsing (the chat attach picker's data source)
    // -------------------------------------------------------------------------

    /** Lists one folder (non-recursive) of the connected account's files via a Depth-1 PROPFIND. */
    suspend fun listFolder(relativePath: String = ""): List<NextcloudFile> = withContext(Dispatchers.IO) {
        val account = requireAccount()
        val davBasePath = "/remote.php/dav/files/${account.username}"
        // Normalized form of the listed folder, for the parser's self-entry exclusion below.
        val listedPath = relativePath.split("/").filter { it.isNotEmpty() }.joinToString("/")

        val body = """
            <?xml version="1.0"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop><d:displayname/><d:resourcetype/><d:getcontenttype/><d:getcontentlength/><d:getlastmodified/></d:prop>
            </d:propfind>
        """.trimIndent().toRequestBody("application/xml".toMediaType())

        val request = Request.Builder()
            .url(davUrl(account, relativePath))
            .method("PROPFIND", body)
            .header("Depth", "1")
            .header("Authorization", basicAuth(account))
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 401) throw IOException("Nextcloud rejected the username or app password.")
            if (response.code != 207) throw IOException("Nextcloud returned HTTP ${response.code}.")
            val xml = response.body?.string() ?: throw IOException("Unexpected response from the Nextcloud server.")
            parseMultistatus(xml, davBasePath, listedPath)
        }
    }

    /**
     * Minimal WebDAV `multistatus` parser for folder listings. Namespace-aware, matching on local
     * names only (servers vary between `d:` and `D:` prefixes).
     *
     * A Depth-1 PROPFIND's multistatus includes the listed folder ITSELF as one of its responses —
     * without excluding it, every folder appears to contain itself (an infinite "Photos inside
     * Photos" loop when browsing). The exclusion must compare full relative paths, not just check
     * "is this the root": listing "a/b" includes an entry whose path is "a/b" at any depth.
     */
    private fun parseMultistatus(xml: String, davBasePath: String, listedPath: String): List<NextcloudFile> {
        val results = mutableListOf<NextcloudFile>()
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(xml.reader())
        }
        val dateFormatter = rfc1123Formatter()

        var inResponse = false
        var href = ""
        var displayName: String? = null
        var contentType: String? = null
        var contentLength: Long? = null
        var modifiedMs: Long? = null
        var isCollection = false
        var text = StringBuilder()

        fun appendCurrent() {
            val decoded = Uri.decode(href)
            val baseIndex = decoded.indexOf(davBasePath)
            if (baseIndex < 0) return
            val relative = decoded.substring(baseIndex + davBasePath.length).trim('/')
            if (relative.isEmpty() || relative == listedPath) return // the listed folder itself
            val fallbackName = relative.substringAfterLast('/')
            results.add(
                NextcloudFile(
                    path = relative,
                    name = displayName ?: fallbackName,
                    isDirectory = isCollection,
                    contentType = contentType,
                    size = contentLength,
                    modifiedMs = modifiedMs
                )
            )
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    when (parser.name) {
                        "response" -> {
                            inResponse = true
                            href = ""
                            displayName = null
                            contentType = null
                            contentLength = null
                            modifiedMs = null
                            isCollection = false
                        }
                        "collection" -> isCollection = true
                    }
                }
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val trimmed = text.toString().trim()
                    when (parser.name) {
                        "href" -> if (inResponse && href.isEmpty()) href = trimmed
                        "displayname" -> if (inResponse && displayName == null && trimmed.isNotEmpty()) displayName = trimmed
                        "getcontenttype" -> if (inResponse && trimmed.isNotEmpty()) contentType = trimmed
                        "getcontentlength" -> if (inResponse) contentLength = trimmed.toLongOrNull()
                        "getlastmodified" -> if (inResponse && trimmed.isNotEmpty()) {
                            modifiedMs = runCatching { dateFormatter.parse(trimmed)?.time }.getOrNull()
                        }
                        "response" -> {
                            inResponse = false
                            appendCurrent()
                        }
                    }
                }
            }
            event = parser.next()
        }
        return results.sortedNewestFirst()
    }

    // -------------------------------------------------------------------------
    // Thumbnails (the picker's photo grid)
    // -------------------------------------------------------------------------

    /**
     * Server-generated square thumbnail via Nextcloud's authenticated `core/preview` endpoint
     * (`a=1` keeps aspect by cropping), as a URL + Authorization header for Coil's own loader/cache
     * to fetch. Works for images everywhere and for videos when the server has a video preview
     * provider; the grid shows an icon placeholder on failure.
     */
    fun thumbnailRequest(path: String, size: Int = 256): NextcloudThumbnailRequest? {
        val account = _account.value ?: return null
        val base = account.server.toHttpUrlOrNull() ?: return null
        val url = base.newBuilder()
            .addPathSegments("index.php/core/preview.png")
            .addQueryParameter("file", "/$path")
            .addQueryParameter("x", size.toString())
            .addQueryParameter("y", size.toString())
            .addQueryParameter("a", "1")
            .build()
        return NextcloudThumbnailRequest(url = url.toString(), authorization = basicAuth(account))
    }

    // -------------------------------------------------------------------------
    // Public share links (OCS files_sharing API)
    // -------------------------------------------------------------------------

    /**
     * Creates a public link share (shareType 3) for [relativePath] and returns its `/s/TOKEN`
     * URL — the exact form the link-preview feature renders. If the file already has a public
     * link (creating again can fail on some configs), the existing link is reused.
     */
    suspend fun createPublicShareLink(relativePath: String): String = withContext(Dispatchers.IO) {
        val account = requireAccount()
        val request = Request.Builder()
            .url("${account.server}/ocs/v2.php/apps/files_sharing/api/v1/shares?format=json")
            .post(
                FormBody.Builder()
                    .add("path", "/$relativePath")
                    .add("shareType", "3")
                    .build()
            )
            .header("Authorization", basicAuth(account))
            .header("OCS-APIRequest", "true")
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 401) throw IOException("Nextcloud rejected the username or app password.")
            if (response.isSuccessful) {
                val body = response.body?.string()
                val url = body?.let { shareUrlFromOcsObject(it) }
                if (url != null) return@withContext url
            }
        }
        existingPublicShareLink(account, relativePath)
            ?: throw IOException("Unexpected response from the Nextcloud server.")
    }

    private fun existingPublicShareLink(account: NextcloudAccount, relativePath: String): String? {
        val base = account.server.toHttpUrlOrNull() ?: return null
        val url = base.newBuilder()
            .addPathSegments("ocs/v2.php/apps/files_sharing/api/v1/shares")
            .addQueryParameter("format", "json")
            .addQueryParameter("path", "/$relativePath")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", basicAuth(account))
            .header("OCS-APIRequest", "true")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val list = JSONObject(body).getJSONObject("ocs").optJSONArray("data") ?: return null
                for (i in 0 until list.length()) {
                    val share = list.optJSONObject(i) ?: continue
                    if (share.optInt("share_type", -1) == 3) {
                        val shareUrl = share.optString("url").takeIf { it.isNotEmpty() }
                        if (shareUrl != null) return shareUrl
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun shareUrlFromOcsObject(body: String): String? = runCatching {
        JSONObject(body).getJSONObject("ocs").getJSONObject("data").optString("url").takeIf { it.isNotEmpty() }
    }.getOrNull()

    // -------------------------------------------------------------------------
    // Media sending ("Send Media via Nextcloud" — photos/voice notes as share links)
    // -------------------------------------------------------------------------

    /**
     * Uploads one media file to `KaChat/Media/` and returns a public `/s/TOKEN` share link for it —
     * the whole "send a photo as a link instead of on-chain bytes" flow in one call. The stored
     * name is prefixed with 8 random hex chars so two `photo_<ts>.jpg` sends can never collide
     * (a PUT to an existing WebDAV path silently overwrites). Throws on any failure; callers fall
     * back to the embedded on-chain envelope.
     */
    suspend fun uploadMediaAndShare(bytes: ByteArray, filename: String, contentType: String): String {
        val mediaType = contentType.toMediaTypeOrNull() ?: "application/octet-stream".toMediaType()
        return uploadMediaAndShare(bytes.toRequestBody(mediaType), filename)
    }

    /**
     * The same upload-and-share for a file too large to hold in memory - a video picked from the
     * library with "Send Photo or Video via Nextcloud" (iOS a890102). [body] streams the bytes
     * straight from wherever they live into the PUT.
     */
    suspend fun uploadMediaAndShare(body: okhttp3.RequestBody, filename: String): String {
        val relativePath = withContext(Dispatchers.IO) {
            val account = requireAccount()

            // Ensure KaChat/ then KaChat/Media/ exist. MKCOL answers 405 when the folder is
            // already there, which is the common case after the first send.
            for (folder in listOf(BACKUP_FOLDER_NAME, MEDIA_FOLDER_PATH)) {
                val mkcol = Request.Builder()
                    .url(davUrl(account, folder))
                    .method("MKCOL", null)
                    .header("Authorization", basicAuth(account))
                    .build()
                client.newCall(mkcol).execute().use { response ->
                    if (response.code == 401) throw IOException("Nextcloud rejected the username or app password.")
                    if (!response.isSuccessful && response.code != 405) {
                        throw IOException("Nextcloud returned HTTP ${response.code}.")
                    }
                }
            }

            val sanitized = filename.replace(Regex("[^A-Za-z0-9._-]"), "_").takeIf { it.isNotBlank() } ?: "file"
            val uniqueName = "${java.util.UUID.randomUUID().toString().replace("-", "").take(8)}_$sanitized"
            val path = "$MEDIA_FOLDER_PATH/$uniqueName"

            val put = Request.Builder()
                .url(davUrl(account, path))
                .put(body)
                .header("Authorization", basicAuth(account))
                .build()
            client.newCall(put).execute().use { response ->
                if (response.code == 401) throw IOException("Nextcloud rejected the username or app password.")
                if (!response.isSuccessful) throw IOException("Nextcloud returned HTTP ${response.code}.")
            }
            path
        }
        return createPublicShareLink(relativePath)
    }

    // -------------------------------------------------------------------------
    // Chat-history backup (WebDAV PUT/GET of the archive JSON)
    // -------------------------------------------------------------------------

    /**
     * The whole backup (NEXTCLOUD_SYNC.md §4): read whatever the server already holds, hand it to
     * [buildJson] to be MERGED with this device's history, write the union with one PUT to the
     * same path, then verify the stored size - so a backup can only ever ADD to
     * `kachat-backup.json` (desktop, iOS and Android all write that same file) and no device can
     * delete another's chat history.
     *
     * [buildJson] receives the raw server content: null for a genuine 404 (no backup yet - the
     * only case that writes without merging), otherwise the file exactly as downloaded, which
     * [ChatHistoryExportImportService.buildBackupJson] judges per §7 - another wallet's file, a
     * hint-less envelope and a newer schema throw (nothing is written), while THIS wallet's
     * unreadable file is replaced in place. A download that failed or stopped early throws here,
     * before [buildJson] ever sees it: a transfer problem says nothing about the file, so it is
     * never a reason to overwrite.
     *
     * The active wallet is re-checked after the download and again right before the PUT (§4.7):
     * a switch mid-sync aborts instead of writing one account's history into another's file -
     * for the manual Back Up Now as much as for the automatic sync.
     *
     * Returns the uploaded file's WebDAV ETag (`OC-ETag` then `ETag` on the PUT response, with a
     * Depth-0 PROPFIND fallback for proxies that strip both), or null when nothing yielded one.
     * [NextcloudSyncService] records it so its remote change watcher never mistakes this
     * device's own write for another device's change. Throws [NextcloudUploadCutOffException]
     * when the server kept fewer bytes than were sent (§4.6).
     */
    suspend fun runBackup(buildJson: suspend (String?) -> String): String? = backupMutex.withLock {
        // Snapshot the account/folder/wallet so a wallet switch mid-backup can't redirect the upload.
        val account = requireAccount()
        val folder = backupFolderPath
        val walletAtStart = currentWalletAddress ?: throw IOException("Open a wallet account before backing up.")
        val existingRemoteJson = readBackupBody(account, folder, onProgress = null)
        ensureWalletUnchanged(walletAtStart)
        val body = buildJson(existingRemoteJson)
        ensureWalletUnchanged(walletAtStart)
        return@withLock writeAndVerifyBackup(body, account, folder)
    }

    /**
     * [runBackup] minus the pre-merge download, for the ONE case where skipping it is provably
     * safe (§4.2): the caller verified (by ETag — see NextcloudSyncService.uploadIfDirty) that the
     * server file is still content this device has already merged, i.e. bytes we wrote or
     * imported and have kept merged since via the change watcher. Anything short of that
     * certainty must use [runBackup] — the merge-on-upload rule is what guarantees no device can
     * erase another's history. Same wallet re-check, verify step and ETag return contract as
     * [runBackup].
     */
    suspend fun runBackupWithoutDownload(buildJson: suspend () -> String): String? = backupMutex.withLock {
        val account = requireAccount()
        val folder = backupFolderPath
        val walletAtStart = currentWalletAddress ?: throw IOException("Open a wallet account before backing up.")
        val body = buildJson()
        ensureWalletUnchanged(walletAtStart)
        return@withLock writeAndVerifyBackup(body, account, folder)
    }

    /**
     * Backups run one at a time on this device. Back Up Now and the automatic sync both come
     * through here, and two uploads of the same file at once had Nextcloud answering the second
     * with HTTP 423 - its write lock on the file. A caller arriving while one runs waits for it,
     * then does its own read-merge-upload on top of what that one wrote (iOS 6551904).
     */
    private val backupMutex = kotlinx.coroutines.sync.Mutex()

    /** NEXTCLOUD_SYNC.md §4.7: one wallet's history must never land in another's file. */
    private fun ensureWalletUnchanged(walletAtStart: String) {
        if (currentWalletAddress != walletAtStart) throw IOException("The active account changed during the sync.")
    }

    /**
     * Steps 5 and 6 of a sync (NEXTCLOUD_SYNC.md §4, iOS d57019a `performBackup`): PUT the body,
     * then read back the stored size with one Depth-0 PROPFIND and compare it with the bytes
     * sent. A relay that cuts a large PUT leaves a short file, which the next sync would read as
     * damaged - so a mismatch on our own write ([isUploadCutOff]) is reported plainly as
     * [NextcloudUploadCutOffException] rather than letting sync go round in circles. A failed
     * PROPFIND skips the check (no evidence either way), like iOS.
     *
     * The same PROPFIND's ETag doubles as the fallback when a proxy stripped the PUT response's
     * ETag headers (§4.5), so the common case still costs one request.
     */
    private suspend fun writeAndVerifyBackup(archiveJson: String, account: NextcloudAccount, folder: String): String? {
        // The exact bytes the PUT carries - the size the server must report back.
        val bytes = archiveJson.toByteArray(Charsets.UTF_8)
        val putEtag = uploadBackup(bytes, account, folder)
        val stat = runCatching { statBackup(account, folder) }.getOrNull()
        if (isUploadCutOff(bytes.size.toLong(), stat?.size, putEtag, stat?.etag)) {
            Log.w(TAG, "Backup upload cut off: server stored ${stat?.size} of ${bytes.size} bytes")
            throw NextcloudUploadCutOffException(sentBytes = bytes.size.toLong(), storedBytes = stat?.size ?: 0L)
        }
        return putEtag ?: stat?.etag ?: runCatching { fetchBackupEtag(account, folder) }.getOrNull()
    }

    /**
     * Deletes the encrypted archive from the user's Nextcloud.
     *
     * Only ever on the user's say-so - the "Delete and Remove Nextcloud Backup" choice when an
     * account is deleted names it outright - because that archive is what carries their history to
     * their other devices, and a deletion here is not recoverable from the app. Mirrors iOS's
     * `deleteRemoteBackup` (78152d3).
     *
     * Takes [backupMutex] like the two backup entry points: a delete racing an upload would leave
     * either the file or the caller confused about which won. A 404 is the state being asked for,
     * not a failure.
     */
    suspend fun deleteRemoteBackup(): Unit = backupMutex.withLock {
        withContext(Dispatchers.IO) {
            val account = requireAccount()
            val request = Request.Builder()
                .url(davUrl(account, "$backupFolderPath/$BACKUP_FILE_NAME"))
                .delete()
                .header("Authorization", basicAuth(account))
                .build()
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> Log.i(TAG, "Remote archive was already gone")
                    response.code == 401 -> throw IOException(
                        "Nextcloud rejected the username or app password - the backup was left in place."
                    )
                    !response.isSuccessful -> throw IOException(
                        "Could not delete the backup on the server (HTTP ${response.code}) - it was left in place."
                    )
                    else -> Log.i(TAG, "Remote archive deleted")
                }
            }
        }
    }

    /**
     * Uploads the archive to `<backup folder>/kachat-backup.json`, creating the folder first
     * (MKCOL answers 405 when it already exists — fine; a user-picked folder always already
     * exists since it was chosen through the folder browser). Overwrites in place: callers that
     * back chat history up must go through [runBackup] so the body is a merge, not a replacement.
     * Returns the new file's ETag when the server sends one on the PUT response, else null:
     * `OC-ETag` first - Nextcloud's canonical header - then `ETag` (NEXTCLOUD_SYNC.md §4.5, same
     * order as iOS).
     */
    private suspend fun uploadBackup(archiveBytes: ByteArray, account: NextcloudAccount, folder: String): String? = withContext(Dispatchers.IO) {
        val folderUrl = davUrl(account, folder)

        val mkcol = Request.Builder()
            .url(folderUrl)
            .method("MKCOL", null)
            .header("Authorization", basicAuth(account))
            .build()
        client.newCall(mkcol).execute().use { response ->
            if (response.code == 401) throw IOException("Nextcloud rejected the username or app password.")
            if (!response.isSuccessful && response.code != 405) {
                throw IOException("Nextcloud returned HTTP ${response.code}.")
            }
        }

        val put = Request.Builder()
            .url(folderUrl.newBuilder().addPathSegment(BACKUP_FILE_NAME).build())
            .put(archiveBytes.toRequestBody("application/json".toMediaType()))
            .header("Authorization", basicAuth(account))
            .build()
        var attempt = 0
        while (true) {
            val outcome = backupWriteClient.newCall(put).execute().use { response ->
                if (response.code == 401) throw IOException("Nextcloud rejected the username or app password.")
                // 423 Locked: another device, or this one's own sync, is reading or writing the
                // archive. The lock clears in seconds, so wait it out rather than failing the
                // backup - only a lock that never clears is worth telling the user about.
                if (response.code == 423) return@use null
                if (!response.isSuccessful) throw IOException("Nextcloud returned HTTP ${response.code}.")
                normalizeEtag(response.header("OC-ETag") ?: response.header("ETag")) to true
            }
            if (outcome != null) return@withContext outcome.first
            if (attempt >= LOCK_RETRY_DELAYS_SECONDS.size) {
                throw IOException(
                    "Nextcloud has the backup file locked (HTTP 423): another device or sync is reading " +
                        "or writing it right now. Try again in a minute. If it keeps happening, the lock is " +
                        "stale on the server - clear it with occ (maintenance mode on, empty the file locks, off).",
                )
            }
            android.util.Log.i(TAG, "Backup file locked (HTTP 423), retrying in ${LOCK_RETRY_DELAYS_SECONDS[attempt]}s")
            kotlinx.coroutines.delay(LOCK_RETRY_DELAYS_SECONDS[attempt] * 1000L)
            attempt++
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    /**
     * The backup file's current WebDAV ETag via a Depth-0 PROPFIND asking for `getetag` only —
     * headers and a tiny multistatus body, never the file itself. This is the change watcher's
     * ~10s poll ([NextcloudSyncService]), so it must stay this cheap. Null means no backup file
     * exists yet (404 on the file or its folder); any other failure throws so the watcher can
     * back off instead of mistaking an outage for "no change".
     */
    suspend fun fetchBackupEtag(): String? {
        val account = requireAccount()
        return fetchBackupEtag(account, backupFolderPath)
    }

    private suspend fun fetchBackupEtag(account: NextcloudAccount, folder: String): String? = withContext(Dispatchers.IO) {
        val body = """
            <?xml version="1.0"?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:getetag/></d:prop></d:propfind>
        """.trimIndent().toRequestBody("application/xml".toMediaType())
        val request = Request.Builder()
            .url(davUrl(account, "$folder/$BACKUP_FILE_NAME"))
            .method("PROPFIND", body)
            .header("Depth", "0")
            .header("Authorization", basicAuth(account))
            .build()
        client.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> null
                response.code == 401 -> throw IOException("Nextcloud rejected the username or app password.")
                response.code != 207 -> throw IOException("Nextcloud returned HTTP ${response.code}.")
                else -> {
                    val xml = response.body?.string() ?: throw IOException("Unexpected response from the Nextcloud server.")
                    parseEtagFromMultistatus(xml)
                        ?: throw IOException("Unexpected response from the Nextcloud server.")
                }
            }
        }
    }

    /** The backup file's ETag and stored byte count, as one Depth-0 PROPFIND reports them. */
    private data class BackupStat(val etag: String?, val size: Long?)

    /**
     * The verify step's read-back (NEXTCLOUD_SYNC.md §4.6): one Depth-0 PROPFIND asking for
     * `getetag` and `getcontentlength` together, so the size and the ETag that says whose write
     * it is describe the same version of the file. Null on 404; any other failure throws.
     */
    private suspend fun statBackup(account: NextcloudAccount, folder: String): BackupStat? = withContext(Dispatchers.IO) {
        val body = """
            <?xml version="1.0"?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:getetag/><d:getcontentlength/></d:prop></d:propfind>
        """.trimIndent().toRequestBody("application/xml".toMediaType())
        val request = Request.Builder()
            .url(davUrl(account, "$folder/$BACKUP_FILE_NAME"))
            .method("PROPFIND", body)
            .header("Depth", "0")
            .header("Authorization", basicAuth(account))
            .build()
        client.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> null
                response.code == 401 -> throw IOException("Nextcloud rejected the username or app password.")
                response.code != 207 -> throw IOException("Nextcloud returned HTTP ${response.code}.")
                else -> parseBackupStat(
                    response.body?.string() ?: throw IOException("Unexpected response from the Nextcloud server.")
                )
            }
        }
    }

    /** Pulls the first `getetag` value out of a PROPFIND multistatus (namespace-agnostic). */
    private fun parseEtagFromMultistatus(xml: String): String? = parseBackupStat(xml).etag

    /** The first `getetag` and `getcontentlength` values in a PROPFIND multistatus
     *  (namespace-agnostic; either is null when absent or unparseable). */
    private fun parseBackupStat(xml: String): BackupStat {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(xml.reader())
        }
        var etag: String? = null
        var size: Long? = null
        var current: String? = null
        val text = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> if (parser.name == "getetag" || parser.name == "getcontentlength") {
                    current = parser.name
                    text.setLength(0)
                }
                XmlPullParser.TEXT -> if (current != null) text.append(parser.text)
                XmlPullParser.END_TAG -> if (parser.name == current) {
                    if (current == "getetag" && etag == null) etag = normalizeEtag(text.toString())
                    if (current == "getcontentlength" && size == null) size = text.toString().trim().toLongOrNull()
                    current = null
                }
            }
            event = parser.next()
        }
        return BackupStat(etag, size)
    }

    /** Strips the weak-validator prefix and surrounding quotes so PUT-header and PROPFIND forms
     *  of the same ETag compare equal. */
    private fun normalizeEtag(raw: String?): String? =
        raw?.trim()?.removePrefix("W/")?.trim('"')?.takeIf { it.isNotEmpty() }

    /** The backup file's server-side metadata (null = no backup yet). A missing folder lists as a 404, which also just means "no backup yet". */
    suspend fun fetchBackupInfo(): NextcloudFile? {
        val listing = runCatching { listFolder(backupFolderPath) }.getOrNull() ?: return null
        return listing.firstOrNull { it.name == BACKUP_FILE_NAME && !it.isDirectory }
    }

    /**
     * Downloads the backup archive JSON. 404 -> "no backup was found". [onProgress] (optional)
     * streams (receivedBytes, totalBytes) as the body downloads — totalBytes is null when the
     * server sends no Content-Length. Drives the restore modal's download stage; the change
     * watcher and the silent restore read through here too.
     */
    suspend fun downloadBackup(onProgress: ((receivedBytes: Long, totalBytes: Long?) -> Unit)? = null): String {
        val account = requireAccount()
        val body = readBackupBody(account, backupFolderPath, onProgress)
            ?: throw IOException("No KaChat backup was found on this Nextcloud server.")
        return body.takeIf { it.isNotEmpty() } ?: throw IOException("Unexpected response from the Nextcloud server.")
    }

    /**
     * GETs `<folder>/kachat-backup.json`: null on 404 (file or folder - no backup yet), the body
     * otherwise (possibly empty, when the file on the server is). The one download every reader
     * shares - the sync's read step ([runBackup]), the restore and the change watcher - so they
     * all treat a bad transfer the same way: every failure here is an [IOException] that
     * aborts the caller BEFORE any PUT (NEXTCLOUD_SYNC.md §4.3, §7). In particular:
     *   * a 2xx HTML body is a reverse-proxy, login, or maintenance page standing in for the
     *     server, never the backup - rejected so it retries as a transient error instead of
     *     reaching the merge, which would now take it for a damaged file and replace it;
     *   * a download that stopped early (fewer bytes than Content-Length) is a transfer problem,
     *     not a damaged file: the file on the server may be fine, so it must never be
     *     overwritten on the strength of half of it (§7, iOS d57019a).
     */
    private suspend fun readBackupBody(
        account: NextcloudAccount,
        folder: String,
        onProgress: ((receivedBytes: Long, totalBytes: Long?) -> Unit)?
    ): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(davUrl(account, "$folder/$BACKUP_FILE_NAME"))
            .header("Authorization", basicAuth(account))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null
            if (response.code == 401) throw IOException("Nextcloud rejected the username or app password.")
            if (!response.isSuccessful) throw IOException("Nextcloud returned HTTP ${response.code}.")
            val contentType = response.header("Content-Type")?.lowercase() ?: ""
            if ("html" in contentType) throw IOException("Unexpected response from the Nextcloud server.")
            val body = response.body ?: throw IOException("Unexpected response from the Nextcloud server.")
            val totalBytes = body.contentLength().takeIf { it > 0 }
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            body.byteStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    onProgress?.invoke(out.size().toLong(), totalBytes)
                }
            }
            // A stream that ends early does not always throw - a connection closed gracefully
            // mid-body just returns -1 from read() - so short of the advertised length has to be
            // caught here. Silently accepting it would hand half an archive to the merge, which
            // reads it as this wallet's damaged file and replaces it in place: a transfer
            // problem must never cost the server copy.
            if (totalBytes != null && out.size().toLong() < totalBytes) {
                throw IOException(
                    "The backup download stopped early (${out.size()} of $totalBytes bytes). " +
                        "Nothing on the server was changed, so trying again is safe."
                )
            }
            out.toString("UTF-8")
        }
    }
}
