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
