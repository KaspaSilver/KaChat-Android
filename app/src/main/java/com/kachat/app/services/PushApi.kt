package com.kachat.app.services

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.HTTP
import retrofit2.http.POST

/**
 * Retrofit client for the KaChat indexer's push-registration API (the same host that serves
 * KaPosts — `AppSettingsRepository.kapostIndexerUrl`, default https://kachat.duckdns.org).
 *
 * Wire-compatible with the iOS client and the Rust server (`kasia-indexer` `/v1/push/...`). The
 * registration is authenticated by a BIP-340 Schnorr signature over a canonical preimage; see
 * [PushRegistrationManager] for how the signed body is built.
 */
interface PushApi {
    /** Obtain a single-use nonce + validity window for a signed mutation. */
    @POST("v1/push/challenge")
    suspend fun challenge(): PushChallengeResponse

    @POST("v1/push/register")
    suspend fun register(@Body body: PushRegistrationRequest): PushResponse

    /**
     * Ring the callee's devices for a call this device is placing (PUSH_EXTENSIONS.md §5).
     * A closed app cannot watch the chain, so the caller asks the service to wake the other
     * phone; the ring carries the same opening message that went on chain.
     */
    @POST("v1/push/ring")
    suspend fun ring(@Body body: PushRingRequest): PushResponse

    // DELETE with a body — Retrofit needs the explicit @HTTP form for that.
    @HTTP(method = "DELETE", path = "v1/push/unregister", hasBody = true)
    suspend fun unregister(@Body body: PushUnregisterRequest): PushResponse
}

data class PushChallengeResponse(
    @SerializedName("nonce") val nonce: String,
    @SerializedName("issued_at_ms") val issuedAtMs: Long,
    @SerializedName("expires_at_ms") val expiresAtMs: Long,
)

data class PushResponse(
    @SerializedName("status") val status: String? = null,
)

/**
 * `watched_group_ids`: when non-null, the server selects the `TransitionalGroups` auth-preimage
 * format (adds the group-hash line and forces the `group_v1` capability, requiring
 * primary_address == wallet_address) — [PushRegistrationManager.buildAuthPreimage] signs the
 * matching shape. When null it's omitted, so the server falls back to `LegacyV1` (DM/broadcast
 * only). Each entry is a lowercase 64-hex blinded group id (per group, per watched member).
 */
data class PushRegistrationRequest(
    @SerializedName("device_token") val deviceToken: String,
    @SerializedName("platform") val platform: String,
    @SerializedName("watched_addresses") val watchedAddresses: List<String>,
    @SerializedName("watched_group_ids") val watchedGroupIds: List<String>? = null,
    @SerializedName("capabilities") val capabilities: List<String> = emptyList(),
    @SerializedName("primary_address") val primaryAddress: String? = null,
    @SerializedName("aliases") val aliases: List<String> = emptyList(),
    @SerializedName("watched_broadcast_channels") val watchedBroadcastChannels: List<String> = emptyList(),
    @SerializedName("hidden_broadcast_senders") val hiddenBroadcastSenders: Map<String, List<String>> = emptyMap(),
    @SerializedName("kaposts_pubkey") val kaPostsPubkey: String? = null,
    /**
     * The reader's per-kind KaPosts switches, so the server can skip a push at the source
     * (PUSH_EXTENSIONS.md §3). A KaPosts push carries a `notification` block, so while the app
     * is in the background the OS shows it without this client ever running - registering the
     * kinds is the only way a switched-off kind stops arriving there. Mentions are deliberately
     * not switchable. Same five fields iOS sends.
     */
    @SerializedName("kaposts_notify_likes") val kaPostsNotifyLikes: Boolean = true,
    @SerializedName("kaposts_notify_dislikes") val kaPostsNotifyDislikes: Boolean = true,
    @SerializedName("kaposts_notify_comments") val kaPostsNotifyComments: Boolean = true,
    @SerializedName("kaposts_notify_reposts") val kaPostsNotifyReposts: Boolean = true,
    @SerializedName("kaposts_notify_follows") val kaPostsNotifyFollows: Boolean = true,
    @SerializedName("auth") val auth: PushAuthRequest? = null,
)

/** The five switchable KaPosts kinds, as sent at registration. Order: likes, dislikes,
 *  comments, reposts, follows. */
data class KaPostsNotifyKinds(
    val likes: Boolean = true,
    val dislikes: Boolean = true,
    val comments: Boolean = true,
    val reposts: Boolean = true,
    val follows: Boolean = true,
) {
    /** Stable text for the registration fingerprint. */
    fun fingerprint(): String = listOf(likes, dislikes, comments, reposts, follows).joinToString(",") { if (it) "1" else "0" }
}

/**
 * `POST /v1/push/ring` — ring [toAddress]'s devices for a call this device is placing
 * (PUSH_EXTENSIONS.md §5, same body iOS sends).
 *
 * The service trusts [auth] for the sender it names in the push, so the callee can show who is
 * calling without decrypting anything. [payload] is the opening call message encrypted to the
 * callee exactly as it went on chain, hex — opaque to the service, and the only way a phone that
 * was asleep learns the Talk room before the chain message reaches it.
 */
data class PushRingRequest(
    @SerializedName("device_token") val deviceToken: String,
    @SerializedName("to_address") val toAddress: String,
    @SerializedName("call_id") val callId: String,
    @SerializedName("video") val video: Boolean,
    /** "invite" (the caller hosts the Talk room) or "request" (the caller asks the callee to host). */
    @SerializedName("kind") val kind: String,
    @SerializedName("payload") val payload: String,
    @SerializedName("timestamp") val timestampMs: Long,
    @SerializedName("auth") val auth: PushAuthRequest? = null,
)

data class PushUnregisterRequest(
    @SerializedName("device_token") val deviceToken: String,
    @SerializedName("auth") val auth: PushAuthRequest? = null,
)

data class PushAuthRequest(
    @SerializedName("auth_version") val authVersion: Int = 1,
    @SerializedName("wallet_pubkey") val walletPubkey: String,
    @SerializedName("wallet_address") val walletAddress: String,
    @SerializedName("nonce") val nonce: String,
    @SerializedName("timestamp_ms") val timestampMs: Long,
    @SerializedName("expires_at_ms") val expiresAtMs: Long,
    @SerializedName("signature") val signature: String,
)
