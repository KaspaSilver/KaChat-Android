package com.kachat.app.services

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.kachat.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Drive backup of chat history — auth via Credential Manager (identity) +
 * `Identity.getAuthorizationClient` (Drive `appdata` scope, separate from sign-in per Google's
 * current recommended split), storage via plain Drive API v3 REST calls (no Google Java client
 * library — reuses the app's existing Retrofit/OkHttp stack). Scoped to `drive.appdata`: a
 * hidden per-app folder, never visible in the user's regular Drive UI, so this never touches
 * anything else in their Drive.
 *
 * The access token obtained by [requestAuthorization]/[completeAuthorization] is held only in
 * memory (not persisted) — a fresh app process re-authorizes silently via
 * [ensureAccessTokenSilently] (Play Services remembers the granted `drive.appdata` consent),
 * matching how short-lived OAuth access tokens are meant to be handled. Only the signed-in
 * account's email survives restarts (plain SharedPreferences — it is the sign-in marker the
 * automatic sync service checks, not a credential).
 */
@Singleton
class GoogleDriveBackupService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val api: GoogleDriveApi = Retrofit.Builder()
        .baseUrl("https://www.googleapis.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(GoogleDriveApi::class.java)

    private val credentialManager = CredentialManager.create(context)

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var signedInEmail: String? = prefs.getString(PREF_SIGNED_IN_EMAIL, null)
    private var cachedAccessToken: String? = null

    val isSignedIn: Boolean get() = signedInEmail != null
    val signedInAccountEmail: String? get() = signedInEmail

    sealed class AuthOutcome {
        object Success : AuthOutcome()
        data class NeedsConsent(val pendingIntent: PendingIntent) : AuthOutcome()
        data class Failed(val message: String) : AuthOutcome()
    }

    /** Step 1: Google account sign-in (identity only — no Drive access yet). */
    suspend fun signIn(activity: Activity): Boolean {
        val webClientId = context.getString(R.string.google_oauth_web_client_id)
        val googleIdOption = GetGoogleIdOption.Builder()
            .setServerClientId(webClientId)
            .setFilterByAuthorizedAccounts(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()
        return try {
            val result = credentialManager.getCredential(activity, request)
            val credential = result.credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                signedInEmail = GoogleIdTokenCredential.createFrom(credential.data).id
                // Persisted so a fresh process still knows Drive backup is signed in (the
                // automatic sync/restore paths check this before silently re-authorizing).
                prefs.edit().putString(PREF_SIGNED_IN_EMAIL, signedInEmail).apply()
                true
            } else {
                false
            }
        } catch (e: GetCredentialException) {
            false
        }
    }

    /**
     * Step 2: authorize the narrow `drive.appdata` scope. May return [AuthOutcome.NeedsConsent]
     * on first use — the caller (UI layer) launches that `PendingIntent` via
     * `ActivityResultContracts.StartIntentSenderForResult()`, then calls [completeAuthorization]
     * with the result `Intent` to finish. Silent (no UI) on subsequent calls once granted.
     */
    suspend fun requestAuthorization(activity: Activity): AuthOutcome {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
            .build()
        return try {
            val result = Identity.getAuthorizationClient(activity).authorize(request).await()
            applyAuthorizationResult(result) ?: AuthOutcome.Failed("Authorization did not return an access token")
        } catch (e: Exception) {
            AuthOutcome.Failed(e.message ?: "Authorization failed")
        }
    }

    /** Finishes an authorization that returned [AuthOutcome.NeedsConsent], after the UI layer launches its pendingIntent and gets a result back. */
    fun completeAuthorization(intent: Intent): Boolean {
        return try {
            val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(intent)
            applyAuthorizationResult(result) is AuthOutcome.Success
        } catch (e: Exception) {
            false
        }
    }

    private fun applyAuthorizationResult(result: AuthorizationResult): AuthOutcome? {
        return when {
            result.hasResolution() -> result.pendingIntent?.let { AuthOutcome.NeedsConsent(it) }
            result.accessToken != null -> {
                cachedAccessToken = result.accessToken
                AuthOutcome.Success
            }
            else -> null
        }
    }

    fun signOut() {
        signedInEmail = null
        cachedAccessToken = null
        prefs.edit().remove(PREF_SIGNED_IN_EMAIL).apply()
    }

    /**
     * Refreshes the in-memory access token WITHOUT any UI — usable from background work
     * (automatic sync, WorkManager). Play Services remembers the `drive.appdata` consent the
     * user granted during [requestAuthorization], so `authorize()` from an application context
     * returns a fresh token silently; if consent would be needed (revoked, never granted) this
     * just returns false rather than trying to show anything.
     */
    suspend fun ensureAccessTokenSilently(): Boolean {
        if (cachedAccessToken != null) return true
        if (signedInEmail == null) return false
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
            .build()
        return try {
            val result = Identity.getAuthorizationClient(context).authorize(request).await()
            applyAuthorizationResult(result) is AuthOutcome.Success
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Finds-or-creates [walletAddress]'s own backup file and overwrites its content in place —
     * never accumulates duplicate copies. Each account gets its own separate file (see
     * [backupFileNameFor]) so multiple accounts on the same device, each with backup enabled,
     * never clobber each other's history. Returns false (never throws) on any failure, including
     * "not authorized yet".
     */
    suspend fun uploadBackup(walletAddress: String, archiveJson: String): Boolean {
        val fileName = backupFileNameFor(walletAddress)
        return try {
            withAuthRetry { auth ->
                val existingFileId = findBackupFileId(auth, fileName)
                val contentType = "application/json".toMediaTypeOrNull()
                if (existingFileId != null) {
                    api.updateFileContent(auth, existingFileId, archiveJson.toRequestBody(contentType))
                } else {
                    val metadata = """{"name":"$fileName","parents":["appDataFolder"]}"""
                        .toRequestBody("application/json".toMediaTypeOrNull())
                    val content = archiveJson.toRequestBody(contentType)
                    api.createFile(
                        auth,
                        MultipartBody.Part.createFormData("metadata", null, metadata),
                        MultipartBody.Part.createFormData("file", fileName, content)
                    )
                }
            } != null
        } catch (e: Exception) {
            false
        }
    }

    /** Fetches [walletAddress]'s own backup file content, or null if none exists yet, isn't authorized, or the request fails. */
    suspend fun downloadBackup(walletAddress: String): String? {
        return try {
            withAuthRetry { auth ->
                val fileId = findBackupFileId(auth, backupFileNameFor(walletAddress))
                if (fileId == null) null else api.downloadFile(auth, fileId).string()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Permanently deletes [walletAddress]'s own backup file from Drive — used by the "wipe account & Cloud" danger-zone action and the storage screen's "Delete Drive Backup". Treats "not signed in" or "no backup exists" as trivially successful, since there's nothing to delete either way. */
    suspend fun deleteBackup(walletAddress: String): Boolean {
        if (bearerToken() == null) return true
        return try {
            withAuthRetry { auth ->
                val fileId = findBackupFileId(auth, backupFileNameFor(walletAddress))
                if (fileId != null) api.deleteFile(auth, fileId)
            } != null
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun findBackupFileId(auth: String, fileName: String): String? {
        return api.listFiles(auth, query = "name='$fileName' and 'appDataFolder' in parents")
            .files?.firstOrNull()?.id
    }

    /** Current size in bytes of [walletAddress]'s backup file already sitting in Drive, or null
     * if not signed in, no backup exists yet, or the request fails. Drive's API returns file size
     * as a decimal string, hence the parse. */
    suspend fun currentBackupSizeBytes(walletAddress: String): Long? {
        return try {
            withAuthRetry { auth ->
                api.listFiles(auth, query = "name='${backupFileNameFor(walletAddress)}' and 'appDataFolder' in parents")
                    .files?.firstOrNull()?.size?.toLongOrNull()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** In-memory token first, silent refresh if the process restarted since authorization. */
    private suspend fun bearerToken(): String? {
        if (cachedAccessToken == null) ensureAccessTokenSilently()
        return cachedAccessToken?.let { "Bearer $it" }
    }

    /**
     * Runs [block] with a bearer token, retrying exactly once on HTTP 401 after silently
     * refreshing — Drive access tokens live about an hour, and the automatic sync's debounced
     * uploads routinely run long after the token that authorized the session expired. Returns
     * null when no token can be obtained at all.
     */
    private suspend fun <T> withAuthRetry(block: suspend (auth: String) -> T): T? {
        val auth = bearerToken() ?: return null
        return try {
            block(auth)
        } catch (e: retrofit2.HttpException) {
            if (e.code() != 401) throw e
            cachedAccessToken = null
            val fresh = bearerToken() ?: return null
            block(fresh)
        }
    }

    companion object {
        private const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        private const val PREFS_NAME = "google_drive_backup_prefs"
        private const val PREF_SIGNED_IN_EMAIL = "signed_in_email"

        /**
         * One backup file per account, not one shared file — otherwise switching between
         * multiple saved accounts on the same device (each with backup enabled) would silently
         * overwrite each other's chat history in Drive. Colons in a raw "kaspa:..." address are
         * replaced since Drive filenames and the `q=name='...'` query string are both simpler to
         * reason about with a plain alphanumeric-ish name.
         */
        internal fun backupFileNameFor(address: String): String = "kachat-backup-${address.replace(":", "_")}.json"
    }
}
