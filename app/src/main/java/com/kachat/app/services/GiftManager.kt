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
import retrofit2.http.POST
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * State machine for the one-per-device Kaspa "welcome gift" claim - mirrors iOS's
 * `GiftService.GiftClaimState`. The gift is a server-funded faucet (gift.kachat.duckdns.org): the client
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

/** Gift faucet REST API (base url https://gift.kachat.duckdns.org/ - see AppModule.provideGiftApi). */
interface GiftApi {
    @POST("v1/claim")
    suspend fun claim(@Body body: GiftClaimRequest): Response<GiftClaimResponse>
}

/**
 * Android claim payload. Unlike iOS (Apple DeviceCheck -> `deviceToken`), Android sends a single
 * Play Integrity [integrityToken]. `platform = "android"` routes the server to its Play Integrity
 * verifier, which it does carry - the field names and the path here are read back from the live
 * server's own rejections, not assumed.
 *
 * The field is `address`, not `walletAddress`, and there is no `challenge`: the server exposes no
 * challenge endpoint at all (`/v1/challenge` is a 404), so the one-time value the nonce used to
 * bind to is simply gone.
 */
data class GiftClaimRequest(
    val platform: String = "android",
    val integrityToken: String,
    val address: String,
    /**
     * Stable per-device pseudonym: base64url(sha256(ANDROID_ID)). Folded into the Play Integrity
     * nonce (see [claimGift]) so the server can trust it came from the genuine app and enforce
     * one-claim-per-device. Survives reinstalls; resets on factory reset / new user profile.
     */
    val deviceId: String
)

/**
 * `{"ok":true,"sent":false}` while the service is in record-only mode; [sent] flips true and a
 * transaction id appears once it is paying out. [reason] carries the server's own wording on
 * every failure shape. The id's field name is read tolerantly because only the record-only shape
 * has been observed from the client side.
 */
data class GiftClaimResponse(
    val ok: Boolean = false,
    val sent: Boolean = false,
    val reason: String? = null,
    val txId: String? = null,
    val txid: String? = null,
    val transactionId: String? = null,
) {
    val resolvedTxId: String? get() = listOfNotNull(txId, txid, transactionId).firstOrNull { it.isNotEmpty() }
}

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
     *  real one-per-device enforcement, exactly as iOS relies on server-side DeviceCheck).
     *
     *  Only ever moves out of Checking or Eligible, matching iOS. It is called from a
     *  `LaunchedEffect` on two different gift screens, and it used to overwrite whatever state it
     *  found - including CLAIMING. Re-entering the screen mid-claim therefore flipped the state
     *  back to Eligible, which is precisely the guard [claimGift] relies on to be single-flight,
     *  so a second tap could start a second concurrent claim: two attestations, two server
     *  calls. */
    fun checkEligibility() {
        val current = _state.value
        if (current != GiftClaimState.Checking && current != GiftClaimState.Eligible) return
        _state.value = if (prefs.getBoolean(CLAIMED_KEY, false)) {
            GiftClaimState.AlreadyClaimed
        } else {
            GiftClaimState.Eligible
        }
    }

    suspend fun claimGift(walletAddress: String) {
        if (_state.value != GiftClaimState.Eligible) return

        // Attempt cooldown, persisted. The in-memory state machine alone bounds nothing across a
        // relaunch: a failed attempt leaves Unavailable, but force-quitting resets that to
        // Eligible, so the gift server could be hit as fast as the app can be restarted - and
        // every attempt costs it a real Play Integrity verification. Persisting the timestamp is
        // what makes the limit survive that.
        val sinceLast = System.currentTimeMillis() - prefs.getLong(LAST_ATTEMPT_KEY, 0L)
        if (sinceLast in 0 until CLAIM_COOLDOWN_MS) {
            val waitSeconds = ((CLAIM_COOLDOWN_MS - sinceLast) / 1000) + 1
            Log.i(TAG, "Gift claim refused locally: ${waitSeconds}s of cooldown left")
            _state.value = GiftClaimState.Unavailable("Just tried that. Give it ${waitSeconds}s.")
            return
        }
        prefs.edit().putLong(LAST_ATTEMPT_KEY, System.currentTimeMillis()).apply()

        _state.value = GiftClaimState.Claiming
        try {
            Log.i(TAG, "Gift claim starting (installer=${installerPackageName()}, cloudProject=$CLOUD_PROJECT_NUMBER)")

            // 1. Stable per-device id = base64url(sha256(ANDROID_ID)). Hashing keeps the raw
            //    ANDROID_ID on the device; the server only ever sees the pseudonym.
            @Suppress("HardwareIds")
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
            val deviceId = base64UrlNoPadding(sha256(androidId.toByteArray(Charsets.UTF_8)))

            // 2. Play Integrity token, with the nonce bound to the destination address and the
            //    deviceId. Binding both makes them tamper-proof: the server recomputes
            //    sha256("$address:$deviceId") and compares it to the nonce inside the signed
            //    token, so a repackaged app cannot swap either one to re-claim or redirect.
            //
            //    This USED to bind a server-issued one-time challenge instead. That endpoint no
            //    longer exists (`/v1/challenge` is a 404), so the address is what takes its place
            //    as the thing worth binding. UNVERIFIED against the server's own recomputation -
            //    it is the only derivation the two sides both hold, but the gift service's
            //    CLIENT_API.md is the authority and this must be checked against it.
            val nonce = base64UrlNoPadding(sha256("$walletAddress:$deviceId".toByteArray(Charsets.UTF_8)))
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

            // 3. Submit the claim. The server verifies the token, enforces one-per-device, and sends KAS.
            val response = giftApi.claim(
                GiftClaimRequest(
                    integrityToken = integrityToken,
                    address = walletAddress,
                    deviceId = deviceId
                )
            )
            val body = response.body()
            when {
                response.isSuccessful && body?.ok == true -> {
                    val txId = body.resolvedTxId
                    if (!body.sent || txId.isNullOrEmpty()) {
                        // Accepted, but nothing was paid - the service is in record-only mode.
                        // Saying "claimed" would be a lie, and marking it claimed locally would
                        // burn the one attempt this device gets for a gift it never received.
                        Log.w(TAG, "Gift claim accepted but not paid (record-only)")
                        _state.value = GiftClaimState.Unavailable(
                            "The gift service isn't paying out right now. Try again later."
                        )
                    } else {
                        Log.i(TAG, "Gift claimed, tx ${txId.take(12)}")
                        prefs.edit().putBoolean(CLAIMED_KEY, true).apply()
                        _state.value = GiftClaimState.Claimed(txId)
                    }
                }
                response.isSuccessful -> {
                    // HTTP 200 with ok=false: the server refused, and said why.
                    val reason = body?.reason
                    Log.e(TAG, "Gift server refused the claim: ${reason ?: "(no reason)"}")
                    _state.value = GiftClaimState.Unavailable(
                        reason ?: "Gift server refused the claim."
                    )
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

    /** The server says why in `reason` on every failure shape it returns. */
    private fun parseError(body: String): String? =
        try { gson.fromJson(body, GiftClaimResponse::class.java)?.reason } catch (e: Exception) { null }

    companion object {
        private const val TAG = "GiftManager"
        private const val PREFS_NAME = "gift_prefs"
        private const val CLAIMED_KEY = "kachat_gift_claimed"
        private const val LAST_ATTEMPT_KEY = "kachat_gift_last_attempt_at"
        /** Minimum gap between claim attempts, across relaunches. */
        private const val CLAIM_COOLDOWN_MS = 60_000L

        /**
         * This app's Google Cloud project number. The gift server must verify Play Integrity tokens
         * against this same project (Play Integrity API enabled there + the app linked in Play Console).
         */
        const val CLOUD_PROJECT_NUMBER = 1037094663882L
    }
}
