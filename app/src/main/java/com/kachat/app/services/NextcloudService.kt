package com.kachat.app.services

import android.content.Context
import android.net.Uri
import android.util.Log
import android.util.Xml
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * Talks to the user's own Nextcloud server (mirrors iOS's `NextcloudService.swift`): connect with
 * an app password, browse files over WebDAV, and mint public `/s/TOKEN` share links via the OCS
 * API — so chats carry a small link the recipient's link-preview feature renders, instead of
 * pushing file bytes through the on-chain payload. Credentials live in their own
 * `EncryptedSharedPreferences` file (same secure-prefs pattern as [ColdStorageManager]) — a
 * completely separate trust domain from the wallet's own storage.
 */
@Singleton
class NextcloudService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "NextcloudService"
        private const val SECURE_PREFS_NAME = "nextcloud_secure_prefs"
        private const val PREF_SERVER = "server"
        private const val PREF_USERNAME = "username"
        private const val PREF_APP_PASSWORD = "app_password"
        private const val PREF_START_FOLDER = "start_folder"
        private const val PREF_BACKUP_FOLDER = "backup_folder"
        private const val PREF_AUTO_BACKUP_ENABLED = "auto_backup_enabled"
        private const val PREF_LAST_AUTO_BACKUP_MS = "last_auto_backup_ms"
        private const val PREF_MEDIA_SEND_ENABLED = "media_send_enabled"

        const val BACKUP_FOLDER_NAME = "KaChat"
        /** Where "Send Media via Nextcloud" uploads land: a fixed KaChat/Media folder at the files root. */
        const val MEDIA_FOLDER_PATH = "$BACKUP_FOLDER_NAME/Media"
        /** FIXED filename, identical to iOS/desktop — same archive schema, so a backup written by
         *  one platform restores cleanly on any other. */
        const val BACKUP_FILE_NAME = "kachat-backup.json"

        /** On-background auto-backup throttle: at most once per hour. */
        const val AUTO_BACKUP_MIN_INTERVAL_MS = 3_600_000L
        /** Launch/foreground catch-up threshold: if the last automatic backup is older than this
         *  (e.g. the app was force-killed for days and never got a clean backgrounding moment),
         *  back up on becoming active instead of waiting for the next background. */
        const val AUTO_BACKUP_CATCH_UP_INTERVAL_MS = 86_400_000L

        /** Normalizes user input ("mycloud.duckdns.org", trailing slashes, an accidental
         *  "/index.php" suffix) into a clean base URL, defaulting to https. Null if it doesn't
         *  parse as a URL at all. */
        fun normalizeServer(input: String): String? {
            var raw = input.trim()
            if (raw.isEmpty()) return null
            if (!raw.lowercase().startsWith("http://") && !raw.lowercase().startsWith("https://")) {
                raw = "https://$raw"
            }
            raw = raw.trimEnd('/')
            if (raw.lowercase().endsWith("/index.php")) raw = raw.dropLast("/index.php".length)
            val url = raw.toHttpUrlOrNull() ?: return null
            return if (url.host.isEmpty()) null else raw
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

    private val _account = MutableStateFlow(loadAccount())
    val account: StateFlow<NextcloudAccount?> = _account.asStateFlow()

    private val _autoBackupEnabled = MutableStateFlow(prefs.getBoolean(PREF_AUTO_BACKUP_ENABLED, false))
    val autoBackupEnabled: StateFlow<Boolean> = _autoBackupEnabled.asStateFlow()

    private val _mediaSendEnabled = MutableStateFlow(prefs.getBoolean(PREF_MEDIA_SEND_ENABLED, false))
    /** "Send Media via Nextcloud": photos/voice notes upload to the server and the chat message is the share link. */
    val mediaSendEnabled: StateFlow<Boolean> = _mediaSendEnabled.asStateFlow()

    val isConnected: Boolean get() = _account.value != null

    /** The folder backups actually go to — the user's chosen folder, or "KaChat" by default. */
    val backupFolderPath: String get() = _account.value?.backupFolder ?: BACKUP_FOLDER_NAME

    private fun loadAccount(): NextcloudAccount? {
        val server = prefs.getString(PREF_SERVER, null) ?: return null
        val username = prefs.getString(PREF_USERNAME, null) ?: return null
        val appPassword = prefs.getString(PREF_APP_PASSWORD, null) ?: return null
        return NextcloudAccount(
            server = server,
            username = username,
            appPassword = appPassword,
            startFolder = prefs.getString(PREF_START_FOLDER, null),
            backupFolder = prefs.getString(PREF_BACKUP_FOLDER, null)
        )
    }

    private fun persistAccount(account: NextcloudAccount) {
        prefs.edit()
            .putString(PREF_SERVER, account.server)
            .putString(PREF_USERNAME, account.username)
            .putString(PREF_APP_PASSWORD, account.appPassword)
            .apply {
                if (account.startFolder != null) putString(PREF_START_FOLDER, account.startFolder) else remove(PREF_START_FOLDER)
                if (account.backupFolder != null) putString(PREF_BACKUP_FOLDER, account.backupFolder) else remove(PREF_BACKUP_FOLDER)
            }
            .apply()
        _account.value = account
    }

    /**
     * Verifies the credentials against the OCS user endpoint (the cheapest authenticated call),
     * then persists them. Throws with a user-facing message — a 401 says exactly what's wrong.
     */
    suspend fun connect(serverInput: String, username: String, appPassword: String) {
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
    }

    fun disconnect() {
        prefs.edit().clear().apply()
        _account.value = null
        _autoBackupEnabled.value = false
        _mediaSendEnabled.value = false
    }

    /** Persists the picker's start folder (null/"" = files root). */
    fun setStartFolder(path: String?) {
        val current = _account.value ?: return
        persistAccount(current.copy(startFolder = path?.trim()?.takeIf { it.isNotEmpty() }))
    }

    /** Persists the backup destination folder (null/"" = the default "KaChat" folder). */
    fun setBackupFolder(path: String?) {
        val current = _account.value ?: return
        persistAccount(current.copy(backupFolder = path?.trim()?.takeIf { it.isNotEmpty() }))
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_AUTO_BACKUP_ENABLED, enabled).apply()
        _autoBackupEnabled.value = enabled
    }

    fun setMediaSendEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_MEDIA_SEND_ENABLED, enabled).apply()
        _mediaSendEnabled.value = enabled
    }

    /**
     * Runs the automatic backup when enabled, connected, and at least [minIntervalMs] past the
     * last one (hourly for on-background, daily for the launch catch-up). Failures are silent
     * by design (the next trigger retries); success stamps the throttle clock. Goes through
     * [runBackup], so an automatic backup merges with the server's copy exactly like a manual
     * one — and aborts without uploading anything if that copy can't be read.
     */
    suspend fun autoBackupIfDue(
        minIntervalMs: Long = AUTO_BACKUP_MIN_INTERVAL_MS,
        buildJson: suspend (String?) -> String
    ) {
        if (!_autoBackupEnabled.value || !isConnected) return
        val last = prefs.getLong(PREF_LAST_AUTO_BACKUP_MS, 0L)
        if (System.currentTimeMillis() - last < minIntervalMs) return
        try {
            runBackup(buildJson)
            prefs.edit().putLong(PREF_LAST_AUTO_BACKUP_MS, System.currentTimeMillis()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Automatic Nextcloud backup failed (will retry on the next trigger)", e)
        }
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

            val mediaType = contentType.toMediaTypeOrNull() ?: "application/octet-stream".toMediaType()
            val put = Request.Builder()
                .url(davUrl(account, path))
                .put(bytes.toRequestBody(mediaType))
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
     * The whole backup: read whatever the server already holds, hand it to [buildJson] to be
     * MERGED with this device's history, and upload the union — so a backup can only ever ADD to
     * `kachat-backup.json` (desktop, iOS and Android all write that same file) and no device can
     * delete another's chat history.
     *
     * The only "just upload" case is a genuine 404 (no backup yet). Every other failure — an
     * unreadable server response, or a [buildJson] that rejects the remote file as foreign,
     * corrupt or a different wallet — throws BEFORE the PUT, leaving the existing file untouched.
     */
    suspend fun runBackup(buildJson: suspend (String?) -> String) {
        val existingRemoteJson = downloadExistingBackup()
        uploadBackup(buildJson(existingRemoteJson))
    }

    /**
     * The backup file's current contents, or null when there is none yet (404 — file or folder).
     * Any OTHER failure throws, because "couldn't read it" must abort the backup rather than let
     * the caller overwrite a file whose contents are unknown.
     */
    private suspend fun downloadExistingBackup(): String? = withContext(Dispatchers.IO) {
        val account = requireAccount()
        val request = Request.Builder()
            .url(davUrl(account, "$backupFolderPath/$BACKUP_FILE_NAME"))
            .header("Authorization", basicAuth(account))
            .build()
        client.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> null
                response.code == 401 -> throw IOException("Nextcloud rejected the username or app password — nothing was uploaded.")
                !response.isSuccessful -> throw IOException(
                    "Could not read the backup already on the server (HTTP ${response.code}) — nothing was uploaded and that file was left untouched."
                )
                else -> response.body?.string()?.takeIf { it.isNotBlank() }
            }
        }
    }

    /**
     * Uploads the archive to `<backup folder>/kachat-backup.json`, creating the folder first
     * (MKCOL answers 405 when it already exists — fine; a user-picked folder always already
     * exists since it was chosen through the folder browser). Overwrites in place: callers that
     * back chat history up must go through [runBackup] so the body is a merge, not a replacement.
     */
    suspend fun uploadBackup(archiveJson: String) = withContext(Dispatchers.IO) {
        val account = requireAccount()
        val folderUrl = davUrl(account, backupFolderPath)

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
            .put(archiveJson.toRequestBody("application/json".toMediaType()))
            .header("Authorization", basicAuth(account))
            .build()
        client.newCall(put).execute().use { response ->
            if (response.code == 401) throw IOException("Nextcloud rejected the username or app password.")
            if (!response.isSuccessful) throw IOException("Nextcloud returned HTTP ${response.code}.")
        }
    }

    /** The backup file's server-side metadata (null = no backup yet). A missing folder lists as a 404, which also just means "no backup yet". */
    suspend fun fetchBackupInfo(): NextcloudFile? {
        val listing = runCatching { listFolder(backupFolderPath) }.getOrNull() ?: return null
        return listing.firstOrNull { it.name == BACKUP_FILE_NAME && !it.isDirectory }
    }

    /** Downloads the backup archive JSON. 404 -> "no backup was found". */
    suspend fun downloadBackup(): String = withContext(Dispatchers.IO) {
        val account = requireAccount()
        val request = Request.Builder()
            .url(davUrl(account, "$backupFolderPath/$BACKUP_FILE_NAME"))
            .header("Authorization", basicAuth(account))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401) throw IOException("Nextcloud rejected the username or app password.")
            if (response.code == 404) throw IOException("No KaChat backup was found on this Nextcloud server.")
            if (!response.isSuccessful) throw IOException("Nextcloud returned HTTP ${response.code}.")
            response.body?.string()?.takeIf { it.isNotEmpty() }
                ?: throw IOException("Unexpected response from the Nextcloud server.")
        }
    }
}
