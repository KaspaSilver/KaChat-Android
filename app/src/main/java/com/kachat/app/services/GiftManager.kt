package com.kachat.app.services

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityServiceException
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.android.play.core.integrity.model.IntegrityErrorCode
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * State machine for the one-per-device Kaspa "welcome gift" claim - mirrors iOS's
 * `GiftService.GiftClaimState`. The gift is a server-funded faucet (kachatgift.duckdns.org): the client
 * proves the device is genuine + unclaimed, and the server sends KAS on-chain to [claimGift]'s
 * wallet address. The client never signs or sweeps anything; it just receives a txId.
 */
sealed class GiftClaimState {
    object Checking : GiftClaimState()
    object Eligible : GiftClaimState()
    object Claiming : GiftClaimState()
    data class Claimed(val txId: String) : GiftClaimState()
    object AlreadyClaimed : GiftClaimState()
    data class Unavailable(val reason: String) : GiftClaimState()
}

/** Gift faucet REST API (base url https://kachatgift.duckdns.org/ - see AppModule.provideGiftApi). */
interface GiftApi {
    @GET("gift/challenge")
    suspend fun getChallenge(): GiftChallengeResponse

    @POST("gift/claim")
    suspend fun claim(@Body body: GiftClaimRequest): Response<GiftClaimResponse>
}

data class GiftChallengeResponse(val challenge: String)

/**
 * Android claim payload. Unlike iOS (Apple DeviceCheck + App Attest -> `deviceToken`/`attestation`/
 * `keyId`), Android sends a single Play Integrity [integrityToken]. `platform = "android"` lets the
 * server route to the Play Integrity verifier.
 *
 * The live gift server already accepts this shape: `POST /gift/claim` with `platform = "android"`
 * routes to the Play Integrity verifier and reaches the token-decode step (a deliberately malformed
 * token comes back as HTTP 403 `{"error":"Could not decode integrity token: ..."}`), so what remains
 * is a configuration question about which Cloud project the token is linked to, not a missing
 * server endpoint.
 */
data class GiftClaimRequest(
    val platform: String = "android",
    val integrityToken: String,
    val walletAddress: String,
    val challenge: String,
    /**
     * Stable per-device pseudonym: base64url(sha256(ANDROID_ID)). Folded into the Play Integrity
     * nonce (see [claimGift]) so the server can trust it came from the genuine app and enforce
     * one-claim-per-device. Survives reinstalls; resets on factory reset / new user profile.
     */
    val deviceId: String
)

data class GiftClaimResponse(val txId: String? = null, val error: String? = null)

@Singleton
class GiftManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val giftApi: GiftApi
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _state = MutableStateFlow<GiftClaimState>(GiftClaimState.Checking)
    val state: StateFlow<GiftClaimState> = _state.asStateFlow()

    /** Local UX cache only (NOT a security boundary - the server's Play Integrity check is the
     *  real one-per-device enforcement, exactly as iOS relies on server-side DeviceCheck). */
    fun checkEligibility() {
        _state.value = if (prefs.getBoolean(CLAIMED_KEY, false)) {
            GiftClaimState.AlreadyClaimed
        } else {
            GiftClaimState.Eligible
        }
    }

    suspend fun claimGift(walletAddress: String) {
        if (_state.value != GiftClaimState.Eligible) return
        _state.value = GiftClaimState.Claiming
        try {
            Log.i(TAG, "Gift claim starting (installer=${installerPackageName()}, cloudProject=$CLOUD_PROJECT_NUMBER)")

            // 1. One-time challenge from the server.
            val challenge = giftApi.getChallenge().challenge
            Log.i(TAG, "Gift challenge received (${challenge.length} chars)")

            // 2. Stable per-device id = base64url(sha256(ANDROID_ID)). Hashing keeps the raw
            //    ANDROID_ID on the device; the server only ever sees the pseudonym.
            @Suppress("HardwareIds")
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
            val deviceId = base64UrlNoPadding(sha256(androidId.toByteArray(Charsets.UTF_8)))

            // 3. Play Integrity token, bound to BOTH the challenge and the deviceId via the nonce.
            //    Binding deviceId here makes it tamper-proof: the server recomputes
            //    sha256("$challenge:$deviceId") and compares it to the nonce inside the signed token,
            //    so a repackaged app can't swap in a different deviceId to re-claim.
            val nonce = base64UrlNoPadding(sha256("$challenge:$deviceId".toByteArray(Charsets.UTF_8)))
            val integrityManager = IntegrityManagerFactory.create(context)
            val tokenResponse = integrityManager.requestIntegrityToken(
                IntegrityTokenRequest.builder()
                    .setNonce(nonce)
                    .setCloudProjectNumber(CLOUD_PROJECT_NUMBER)
                    .build()
            ).await()
            val integrityToken = tokenResponse.token()
            // Never log the token itself; its length is enough to prove attestation produced one.
            Log.i(TAG, "Play Integrity token obtained (${integrityToken.length} chars, nonce ${nonce.length} chars)")

            // 4. Submit the claim. The server verifies the token, enforces one-per-device, and sends KAS.
            val response = giftApi.claim(
                GiftClaimRequest(
                    integrityToken = integrityToken,
                    walletAddress = walletAddress,
                    challenge = challenge,
                    deviceId = deviceId
                )
            )
            when {
                response.isSuccessful -> {
                    val txId = response.body()?.txId
                    if (txId.isNullOrEmpty()) {
                        Log.e(TAG, "Gift server returned HTTP ${response.code()} with no txId")
                        _state.value = GiftClaimState.Unavailable(
                            "Gift server replied without a transaction (HTTP ${response.code()})."
                        )
                    } else {
                        Log.i(TAG, "Gift claimed, tx ${txId.take(12)}")
                        prefs.edit().putBoolean(CLAIMED_KEY, true).apply()
                        _state.value = GiftClaimState.Claimed(txId)
                    }
                }
                response.code() == 409 -> {
                    Log.i(TAG, "Gift server reports this device already claimed (HTTP 409)")
                    prefs.edit().putBoolean(CLAIMED_KEY, true).apply()
                    _state.value = GiftClaimState.AlreadyClaimed
                }
                else -> {
                    // Server rejection, not a device problem. Keep the server's own wording and
                    // label it so a screenshot makes clear which side said no.
                    val serverText = response.errorBody()?.string()?.let { parseError(it) }
                    Log.e(TAG, "Gift server rejected the claim: HTTP ${response.code()} ${serverText ?: "(no error text)"}")
                    _state.value = GiftClaimState.Unavailable(
                        if (serverText.isNullOrBlank()) "Gift server refused the claim (HTTP ${response.code()})."
                        else "Gift server refused the claim (HTTP ${response.code()}): $serverText"
                    )
                }
            }
        } catch (e: IntegrityServiceException) {
            // Device attestation never produced a token, so the server was never asked. This is the
            // failure that used to disappear into a generic "Device verification failed."
            val code = e.errorCode
            val name = integrityErrorName(code)
            val explanation = integrityErrorExplanation(code)
            Log.e(TAG, "Play Integrity request failed: code=$code ($name) installer=${installerPackageName()} cloudProject=$CLOUD_PROJECT_NUMBER", e)
            _state.value = GiftClaimState.Unavailable(
                "Device check could not complete. Code $code $name: $explanation"
            )
        } catch (e: HttpException) {
            Log.e(TAG, "Gift server HTTP error before the claim step: ${e.code()}", e)
            _state.value = GiftClaimState.Unavailable("Gift server returned an error (HTTP ${e.code()}).")
        } catch (e: IOException) {
            Log.e(TAG, "Gift claim network failure", e)
            _state.value = GiftClaimState.Unavailable("Could not reach the gift server. Check your connection and try again.")
        } catch (e: Exception) {
            Log.e(TAG, "Gift claim failed unexpectedly", e)
            val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            _state.value = GiftClaimState.Unavailable("Gift claim could not be completed: $detail")
        }
    }

    /** Who installed this build. `com.android.vending` means Google Play, which is the only source
     *  the gift is designed to work from, so it is the first thing to check in a bug report. */
    private fun installerPackageName(): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName ?: "unknown"
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName) ?: "unknown"
        }
    } catch (e: Exception) {
        "unavailable"
    }

    /** Symbolic name for a Play Integrity error code, so a screenshot names the exact failure. */
    private fun integrityErrorName(code: Int): String = when (code) {
        IntegrityErrorCode.NO_ERROR -> "NO_ERROR"
        IntegrityErrorCode.API_NOT_AVAILABLE -> "API_NOT_AVAILABLE"
        IntegrityErrorCode.PLAY_STORE_NOT_FOUND -> "PLAY_STORE_NOT_FOUND"
        IntegrityErrorCode.NETWORK_ERROR -> "NETWORK_ERROR"
        IntegrityErrorCode.PLAY_STORE_ACCOUNT_NOT_FOUND -> "PLAY_STORE_ACCOUNT_NOT_FOUND"
        IntegrityErrorCode.APP_NOT_INSTALLED -> "APP_NOT_INSTALLED"
        IntegrityErrorCode.PLAY_SERVICES_NOT_FOUND -> "PLAY_SERVICES_NOT_FOUND"
        IntegrityErrorCode.APP_UID_MISMATCH -> "APP_UID_MISMATCH"
        IntegrityErrorCode.TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
        IntegrityErrorCode.CANNOT_BIND_TO_SERVICE -> "CANNOT_BIND_TO_SERVICE"
        IntegrityErrorCode.NONCE_TOO_SHORT -> "NONCE_TOO_SHORT"
        IntegrityErrorCode.NONCE_TOO_LONG -> "NONCE_TOO_LONG"
        IntegrityErrorCode.GOOGLE_SERVER_UNAVAILABLE -> "GOOGLE_SERVER_UNAVAILABLE"
        IntegrityErrorCode.NONCE_IS_NOT_BASE64 -> "NONCE_IS_NOT_BASE64"
        IntegrityErrorCode.PLAY_STORE_VERSION_OUTDATED -> "PLAY_STORE_VERSION_OUTDATED"
        IntegrityErrorCode.PLAY_SERVICES_VERSION_OUTDATED -> "PLAY_SERVICES_VERSION_OUTDATED"
        IntegrityErrorCode.CLOUD_PROJECT_NUMBER_IS_INVALID -> "CLOUD_PROJECT_NUMBER_IS_INVALID"
        IntegrityErrorCode.CLIENT_TRANSIENT_ERROR -> "CLIENT_TRANSIENT_ERROR"
        IntegrityErrorCode.INTERNAL_ERROR -> "INTERNAL_ERROR"
        else -> "UNKNOWN"
    }

    /** Plain-language version of the same code, kept short because the gift card shows it at 12sp. */
    private fun integrityErrorExplanation(code: Int): String = when (code) {
        IntegrityErrorCode.API_NOT_AVAILABLE ->
            "device checks are not available here, the Play Store may need an update"
        IntegrityErrorCode.PLAY_STORE_NOT_FOUND -> "the Google Play Store app was not found"
        IntegrityErrorCode.NETWORK_ERROR -> "this phone could not reach Google, check your connection"
        IntegrityErrorCode.PLAY_STORE_ACCOUNT_NOT_FOUND -> "no Google account is signed in to the Play Store"
        IntegrityErrorCode.APP_NOT_INSTALLED -> "Google does not see this app as installed"
        IntegrityErrorCode.PLAY_SERVICES_NOT_FOUND -> "Google Play services is missing or turned off"
        IntegrityErrorCode.APP_UID_MISMATCH -> "this app does not match what the system has on record"
        IntegrityErrorCode.TOO_MANY_REQUESTS -> "too many checks right now, please try again later"
        IntegrityErrorCode.CANNOT_BIND_TO_SERVICE -> "the Google Play Store app needs an update"
        IntegrityErrorCode.NONCE_TOO_SHORT, IntegrityErrorCode.NONCE_TOO_LONG,
        IntegrityErrorCode.NONCE_IS_NOT_BASE64 -> "the app sent the check value in the wrong form"
        IntegrityErrorCode.GOOGLE_SERVER_UNAVAILABLE -> "Google's servers are busy, please try again later"
        IntegrityErrorCode.PLAY_STORE_VERSION_OUTDATED -> "the Google Play Store app needs an update"
        IntegrityErrorCode.PLAY_SERVICES_VERSION_OUTDATED -> "Google Play services needs an update"
        IntegrityErrorCode.CLOUD_PROJECT_NUMBER_IS_INVALID ->
            "this app's Google project is not set up for device checks, please report this code"
        IntegrityErrorCode.CLIENT_TRANSIENT_ERROR -> "a temporary problem on this phone, please try again"
        IntegrityErrorCode.INTERNAL_ERROR -> "an unexpected internal error, please try again"
        else -> "an unrecognised device check error, please report this code"
    }

    /** Hidden support tool (Profile 10-tap on "already claimed") - clears the local claimed cache so
     *  the gift can be requested again. Real enforcement is still server-side. */
    fun resetClaimStateForRetry() {
        prefs.edit().putBoolean(CLAIMED_KEY, false).apply()
        checkEligibility()
    }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun base64UrlNoPadding(data: ByteArray): String =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun parseError(body: String): String? =
        try { gson.fromJson(body, GiftClaimResponse::class.java)?.error } catch (e: Exception) { null }

    companion object {
        private const val TAG = "GiftManager"
        private const val PREFS_NAME = "gift_prefs"
        private const val CLAIMED_KEY = "kachat_gift_claimed"

        /**
         * This app's Google Cloud project number. The gift server must verify Play Integrity tokens
         * against this same project (Play Integrity API enabled there + the app linked in Play Console).
         */
        const val CLOUD_PROJECT_NUMBER = 1037094663882L
    }
}
