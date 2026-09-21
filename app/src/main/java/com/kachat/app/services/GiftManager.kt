package com.kachat.app.services

import android.content.Context
import com.kachat.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the welcome gift stands on this device - mirrors iOS's `GiftService.GiftClaimState`.
 * [Claimed] is kept for the screens that switch over it; the email flow never produces a txid.
 * [AlreadyClaimed] means the request has been sent from this device, and is final.
 */
sealed class GiftClaimState {
    object Checking : GiftClaimState()
    object Eligible : GiftClaimState()
    object Claiming : GiftClaimState()
    data class Claimed(val txId: String) : GiftClaimState()
    object AlreadyClaimed : GiftClaimState()
    data class Unavailable(val reason: String) : GiftClaimState()
}

/**
 * The welcome gift, by email (iOS 851a348, 0ff1cf0, 50a7a78).
 *
 * There is no gift server any more: no claim endpoint, no Play Integrity, no network call. "Claim
 * Gift" opens an email to the person who hands the gifts out, with the request written and the
 * chatting address filled in, and a person sends the Kaspa by hand.
 *
 * ONE request per device. iOS can tell whether the email was actually sent, because its mail
 * composer reports Send. Android cannot - handing an email to another app reports nothing back -
 * so Android takes the route iOS uses for a phone without its Mail app: the screen asks first,
 * and opening the mail app counts as the request. The flag is kept in this app's own storage,
 * which uninstalling clears; unlike iOS's Keychain copy it does not survive a reinstall.
 *
 * Debug builds never hold the gift back and never record it, so the flow can be tried again and
 * again. Release builds enforce the one request.
 */
@Singleton
class GiftManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<GiftClaimState>(GiftClaimState.Checking)
    val state: StateFlow<GiftClaimState> = _state.asStateFlow()

    init {
        removeLegacyFlags()
        _state.value = initialState()
    }

    private fun initialState(): GiftClaimState = when {
        BuildConfig.DEBUG -> GiftClaimState.Eligible
        prefs.getBoolean(REQUEST_SENT_KEY, false) -> GiftClaimState.AlreadyClaimed
        else -> GiftClaimState.Eligible
    }

    /** Refreshes from storage, but never overwrites a request already on its way or finished. */
    fun checkEligibility() {
        val current = _state.value
        if (current != GiftClaimState.Checking && current != GiftClaimState.Eligible) return
        _state.value = initialState()
    }

    /** The mail app opened with the request in it: that is the one request, for good. */
    fun markRequested() {
        if (!BuildConfig.DEBUG) prefs.edit().putBoolean(REQUEST_SENT_KEY, true).apply()
        _state.value = GiftClaimState.AlreadyClaimed
    }

    /** No app on this phone could take the email. The request text has already been copied. */
    fun markNoMailApp() {
        _state.value = GiftClaimState.Unavailable(
            "No mail app is set up on this device. The request was copied - paste it into an email to $REQUEST_EMAIL."
        )
    }

    /**
     * Kept so existing callers compile. A sent request is final, and there is no longer any way
     * to reset it - the hidden tap-to-reset gesture is gone with the server.
     */
    fun resetClaimStateForRetry() {}

    /**
     * 5.0 starts everyone fresh. Whatever an older build recorded - a gift claimed through the
     * retired server, or the cooldown between its attempts - is not read, and is removed once. To
     * start everyone fresh again in a later release, bump the suffix on [REQUEST_SENT_KEY].
     */
    private fun removeLegacyFlags() {
        prefs.edit().remove(LEGACY_CLAIMED_KEY).remove(LEGACY_LAST_ATTEMPT_KEY).apply()
    }

    companion object {
        const val REQUEST_EMAIL = "kaspasilver@gmail.com"
        const val REQUEST_SUBJECT = "KaChat gift request"

        /** The email, word for word as iOS writes it. */
        fun requestBody(walletAddress: String): String = """
            |To claim a gift of 2 Kaspa to get started, fill out these fields.
            |
            |Your chatting address must have 0 Kaspa and never have been used before.
            |
            |Please share your chatting address:
            |$walletAddress
            |
            |Please share at least 1-2 sentences describing how you found Kaspa and how you found KaChat:
            |
        """.trimMargin()

        private const val PREFS_NAME = "gift_prefs"
        private const val REQUEST_SENT_KEY = "kachat_gift_request_sent_v2"
        private const val LEGACY_CLAIMED_KEY = "kachat_gift_claimed"
        private const val LEGACY_LAST_ATTEMPT_KEY = "kachat_gift_last_attempt_at"
    }
}
