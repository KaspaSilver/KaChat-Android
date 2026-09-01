package com.kachat.app.services

import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

// -------------------------------------------------------------------------
// Data transfer objects (DTOs) — match Kaspa REST API response shapes
// Phase 3 will expand these as needed
// -------------------------------------------------------------------------

data class BalanceResponse(
    val address: String,
    val balance: Long   // in sompi (1 KAS = 100_000_000 sompi)
)

/** Request body for the batched `POST addresses/balances` endpoint. */
data class BalancesRequest(
    val addresses: List<String>
)

// Field names verified live against api.kaspa.org's real response (snake_case) —
// a bare Gson converter (no naming policy) needs explicit @SerializedName for these.
data class TransactionResponse(
    @SerializedName("transaction_id") val transactionId: String,
    val inputs: List<TransactionInput>,
    val outputs: List<TransactionOutput>,
    @SerializedName("block_time") val blockTime: Long?,
    val payload: String?  // hex-encoded — contains ciph_msg:1:* for KaChat messages
)

data class TransactionInput(
    @SerializedName("previous_outpoint_hash") val previousOutpointHash: String,
    @SerializedName("previous_outpoint_index") val previousOutpointIndex: Int,
    // Only populated when the request passes resolve_previous_outpoints — the address
    // that owned the spent UTXO, i.e. the real sender of this transaction.
    @SerializedName("previous_outpoint_address") val previousOutpointAddress: String?,
    @SerializedName("signature_script") val signatureScript: String
)

data class TransactionOutput(
    val amount: Long,
    @SerializedName("script_public_key") val scriptPublicKey: String,
    @SerializedName("script_public_key_address") val scriptPublicKeyAddress: String?
)

/** One row of `/info/hashrate/history`. Only the two fields the chart needs are declared. */
data class HashrateSample(
    val timestamp: Long,
    @SerializedName("hashrate_kh") val hashrateKh: Double
)

/** Only the fields KaPost chain reads need, so an unrelated shape change cannot break them. */
data class TransactionPayloadResponse(
    val payload: String?,
    @SerializedName("block_time") val blockTime: Long?,
)

data class Outpoint(
    val transactionId: String,
    val index: Int
)

data class ScriptPublicKey(
    val scriptPublicKey: String
)

data class UtxoEntry(
    val address: String,
    val outpoint: Outpoint,
    val utxoEntry: UtxoData
)

data class UtxoData(
    val amount: Long,
    val scriptPublicKey: ScriptPublicKey,
    val blockDaaScore: Long,
    val isCoinbase: Boolean
)

// -------------------------------------------------------------------------
// Retrofit interface — Kaspa REST API
// -------------------------------------------------------------------------

interface KaspaRestApi {

    @GET("addresses/{address}/balance")
    suspend fun getBalance(
        @Path("address") address: String
    ): BalanceResponse

    /**
     * Batched balances for many addresses in ONE round trip (api.kaspa.org's
     * `POST /addresses/balances`) — used by the import wizard's chatting-address scanner, which
     * checks 50 derived addresses per pass and must never fan that out into 50 raw requests.
     * Callers fall back to a bounded-concurrency [getBalance] sweep if a given REST host doesn't
     * expose this endpoint.
     */
    @POST("addresses/balances")
    suspend fun getBalances(
        @Body request: BalancesRequest
    ): List<BalanceResponse>

    /**
     * Network hashrate over time. `resolution` is one of 15m/1h/3h/1d/7d; the chart uses 1d
     * because the finer ones return tens of thousands of samples for the full chain history.
     */
    @GET("info/hashrate/history")
    suspend fun getHashrateHistory(
        @Query("resolution") resolution: String = "1d"
    ): List<HashrateSample>

    @GET("addresses/{address}/utxos")
    suspend fun getUtxos(
        @Path("address") address: String
    ): List<UtxoEntry>

    @GET("transactions/{txId}")
    suspend fun getTransaction(
        @Path("txId") txId: String,
        @Query("inputs") inputs: Boolean = true
    ): TransactionResponse

    /**
     * Just the payload of one transaction. A separate call from [getTransaction] because that
     * one's DTO declares inputs/outputs non-null, so it cannot be asked to skip them - and the
     * KaPost link preview wants nothing but the payload.
     */
    @GET("transactions/{txId}")
    suspend fun getTransactionPayload(
        @Path("txId") txId: String,
        @Query("inputs") inputs: Boolean = false,
        @Query("outputs") outputs: Boolean = false,
        @Query("resolve_previous_outpoints") resolvePreviousOutpoints: String = "no"
    ): TransactionPayloadResponse

    @GET("addresses/{address}/full-transactions")
    suspend fun getTransactions(
        @Path("address") address: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
        @Query("resolve_previous_outpoints") resolvePreviousOutpoints: String = "light"
    ): List<TransactionResponse>

    @POST("transactions")
    suspend fun postTransaction(
        @Body transaction: PostTransactionRequest
    ): PostTransactionResponse

    @GET("info/fee-estimate")
    suspend fun getFeeEstimate(): FeeEstimateResponse
}

// Real shape confirmed against api.kaspa.org (matches rusty-kaspa's FeerateEstimate JSON):
// {"priorityBucket":{"feerate":100,"estimatedSeconds":...},"normalBuckets":[...],"lowBuckets":[...]}
data class FeeEstimateResponse(
    val priorityBucket: FeeBucket,
    val normalBuckets: List<FeeBucket>,
    val lowBuckets: List<FeeBucket>
)

data class FeeBucket(
    val feerate: Double,
    val estimatedSeconds: Double
)

data class PostTransactionRequest(
    val transaction: RawTransaction
)

data class ScriptPublicKeyWithVersion(
    val scriptPublicKey: String,
    val version: Int = 0
)

data class RawOutputWithVersion(
    val amount: Long,
    val scriptPublicKey: ScriptPublicKeyWithVersion
)

data class RawTransaction(
    val version: Int = 0,
    val inputs: List<RawInput>,
    val outputs: List<RawOutputWithVersion>,
    val lockTime: Long = 0,
    val subnetworkId: String = "0000000000000000000000000000000000000000",
    val gas: Long = 0,
    val payload: String? = null // hex-encoded
)

data class RawInput(
    val previousOutpoint: Outpoint,
    val signatureScript: String,
    val sequence: Long = 0,
    val sigOpCount: Int = 1
)

data class PostTransactionResponse(
    val transactionId: String
)

// -------------------------------------------------------------------------
// Retrofit interface — Kasia Indexer API (indexer.kasia.wtf)
// Verified against the real indexer response shape and the Kasia web client's
// actual query usage — payments/self-stash/bcast endpoints are out of scope
// for now, only handshake + contextual-message (comm) receive is implemented.
// -------------------------------------------------------------------------

interface KasiaIndexerApi {

    /** [blockTime], when given, returns only handshakes at or after that block time — the indexer's own cursor param, letting sync fetch just what's new instead of the same recent window every time. */
    @GET("handshakes/by-receiver")
    suspend fun getHandshakesByReceiver(
        @Query("address") address: String,
        @Query("limit") limit: Int = 50,
        @Query("block_time") blockTime: Long? = null
    ): List<HandshakeIndexerResponse>

    /** Handshakes YOU sent (requests you initiated + acceptances of others' requests) — the
     *  restore-parity pass: after a fresh import these prove which conversations were mutual so
     *  they don't resurface as stranger requests (matches iOS's getHandshakesBySender). */
    @GET("handshakes/by-sender")
    suspend fun getHandshakesBySender(
        @Query("address") address: String,
        @Query("limit") limit: Int = 50,
        @Query("block_time") blockTime: Long? = null
    ): List<HandshakeIndexerResponse>

    /**
     * [aliasHex] is the SENDER's own alias (from their handshake), UTF-8-encoded then hex-encoded.
     * [blockTime], when given, returns only messages at or after that block time (same cursor
     * param as [getHandshakesByReceiver]) — safe even if the boundary item comes back again, since
     * callers already dedup by txId against local storage.
     */
    @GET("contextual-messages/by-sender")
    suspend fun getContextualMessagesBySender(
        @Query("address") address: String,
        @Query("alias") aliasHex: String,
        @Query("limit") limit: Int = 50,
        @Query("block_time") blockTime: Long? = null
    ): List<ContextualMessageIndexerResponse>

    /**
     * [blindedGroupId] is the sender-specific blinded group id (hex-encoded 32 bytes) - callers
     * must query once per known group member, since each member sends under their own blinded id
     * (see GroupCipher's protocol notes). Pass [cursor] from a previous response's last item to
     * resume losslessly (`block_time` alone can collide across items) - omit only for a
     * first-ever sync. See docs/GROUP_CHAT_API.md.
     */
    @GET("group-messages/by-blinded-group-id")
    suspend fun getGroupMessagesByBlindedGroupId(
        @Query("blinded_group_id") blindedGroupId: String,
        @Query("limit") limit: Int = 50,
        @Query("cursor") cursor: String? = null
    ): List<GroupMessageIndexerResponse>

    /**
     * [sender] is the group admin's Kaspa address - `gctl` is always sent as a self-stash tx from
     * the admin's own address. Only meaningful for a group already known locally - see
     * [getGroupControlByRecipient] for first-invite discovery.
     */
    @GET("group-control/by-sender")
    suspend fun getGroupControlBySender(
        @Query("sender") sender: String,
        @Query("limit") limit: Int = 50,
        @Query("cursor") cursor: String? = null
    ): List<GroupControlIndexerResponse>

    /**
     * [recipient] is our own wallet address. Recipient-addressed `gctl` controls carry the
     * recipient's x-only pubkey on-chain, so this discovers "you were added to a group" before
     * the device knows any admin address at all - the fix for first-invite catch-up/push that a
     * global device-fanout fallback used to paper over. See docs/GROUP_CHAT_API.md.
     */
    @GET("group-control/by-recipient")
    suspend fun getGroupControlByRecipient(
        @Query("recipient") recipient: String,
        @Query("limit") limit: Int = 50,
        @Query("cursor") cursor: String? = null
    ): List<GroupControlIndexerResponse>
}

data class HandshakeIndexerResponse(
    @SerializedName("tx_id") val txId: String,
    val sender: String,
    val receiver: String,
    @SerializedName("block_time") val blockTime: Long,
    @SerializedName("message_payload") val messagePayload: String
)

data class ContextualMessageIndexerResponse(
    @SerializedName("tx_id") val txId: String,
    val sender: String,
    val alias: String? = null,
    @SerializedName("block_time") val blockTime: Long,
    @SerializedName("message_payload") val messagePayload: String
)

data class GroupMessageIndexerResponse(
    @SerializedName("tx_id") val txId: String,
    val sender: String? = null,
    @SerializedName("blinded_group_id") val blindedGroupId: String,
    @SerializedName("block_time") val blockTime: Long,
    // Opaque lossless pagination cursor - block_time alone can collide across items, so catch-up
    // sync should persist and resume from this instead. Optional for source compatibility with an
    // older indexer that doesn't send it yet. See docs/GROUP_CHAT_API.md.
    val cursor: String? = null,
    @SerializedName("message_payload") val messagePayload: String
)

data class GroupControlIndexerResponse(
    @SerializedName("tx_id") val txId: String,
    val sender: String,
    // Present for recipient-addressed controls (GET /group-control/by-recipient); null for legacy
    // unaddressed ones. See docs/GROUP_CHAT_API.md.
    val recipient: String? = null,
    @SerializedName("block_time") val blockTime: Long,
    // Opaque lossless pagination cursor - see GroupMessageIndexerResponse.cursor.
    val cursor: String? = null,
    @SerializedName("message_payload") val messagePayload: String
)

// -------------------------------------------------------------------------
// Retrofit interface — KNS (Kaspa Name Service) API, api.knsdomains.org
// Verified live against the real API — {success, data, message?, error?}
// envelope, 404 for "not found" (a domain with no owner, or an address with
// no primary name set), not an error condition.
// -------------------------------------------------------------------------

data class KnsOwnerResponse(
    val success: Boolean,
    val data: KnsOwnerData?,
    val message: String? = null,
    val error: String? = null
)

data class KnsOwnerData(
    val id: String?,
    val assetId: String?,
    val asset: String?,
    val owner: String?
)

data class KnsPrimaryNameResponse(
    val success: Boolean,
    val data: KnsPrimaryNameData?,
    val message: String? = null,
    val error: String? = null
)

data class KnsPrimaryNameData(
    val ownerAddress: String?,
    val domain: KnsDomainInfo?
)

data class KnsDomainInfo(
    val fullName: String?
)

data class KnsAssetsResponse(
    val success: Boolean,
    val data: KnsAssetsData?,
    val message: String? = null,
    val error: String? = null
)

data class KnsAssetsData(
    val assets: List<KnsAsset>?
)

data class KnsAsset(
    val assetId: String?,
    val asset: String?,
    val owner: String?,
    val isDomain: Boolean?,
    val isVerifiedDomain: Boolean?,
    val creationBlockTime: String?
)

data class KnsProfileResponse(
    val success: Boolean,
    val data: KnsProfileData?,
    val message: String? = null,
    val error: String? = null
)

data class KnsProfileData(
    val assetId: String?,
    val name: String?,
    val profile: KnsProfileFields?
)

data class KnsProfileFields(
    val bio: String?,
    val avatarUrl: String?,
    val x: String?,
    val website: String?,
    val telegram: String?,
    val discord: String?,
    val contactEmail: String?,
    val github: String?,
    val redirectUrl: String? = null,
    val bannerUrl: String? = null
)

interface KnsApi {

    /** Forward resolution: "alice.kas" -> owning Kaspa address. */
    @GET("{domain}/owner")
    suspend fun resolveDomain(
        @Path("domain") domain: String
    ): KnsOwnerResponse

    /** Reverse resolution: Kaspa address -> its primary KNS domain, for contact display. */
    @GET("primary-name/{address}")
    suspend fun getPrimaryName(
        @Path("address") address: String
    ): KnsPrimaryNameResponse

    /** All verified domains an address owns — a contact may have more than one. */
    @GET("assets")
    suspend fun getAssetsByOwner(
        @Query("owner") owner: String,
        @Query("type") type: String = "domain",
        @Query("pageSize") pageSize: Int = 100
    ): KnsAssetsResponse

    /** Avatar/bio/social-links profile attached to a specific owned domain. */
    @GET("domain/{assetId}/profile")
    suspend fun getDomainProfile(
        @Path("assetId") assetId: String
    ): KnsProfileResponse

    /** Inscription fee tiers in KAS, keyed by label length 1-5 (5 means "5+ characters"). */
    @GET("fee")
    suspend fun getInscribeFee(): KnsFeeResponse

    /** Checks whether a domain is available to register. */
    @POST("domains/check")
    suspend fun checkDomainAvailability(
        @Body request: KnsDomainCheckRequest
    ): KnsDomainCheckResponse

    /** Uploads a profile avatar/banner image, authenticated by a wallet-signed message rather than the image data itself. */
    @Multipart
    @POST("upload/image")
    suspend fun uploadImage(
        @Part("signMessage") signMessage: okhttp3.RequestBody,
        @Part("signature") signature: okhttp3.RequestBody,
        @Part image: MultipartBody.Part
    ): KnsImageUploadResponse

    /** Marks one of the wallet's owned domains as its primary (reverse-resolution) name — off-chain, authenticated by a wallet-signed message, no on-chain transaction. */
    @POST("domain/primary-name")
    suspend fun setPrimaryDomain(
        @Body request: KnsSetPrimaryNameRequest
    ): KnsBasicResponse
}

data class KnsFeeResponse(
    val success: Boolean,
    val data: KnsFeeData?,
    val message: String? = null,
    val error: String? = null
)

data class KnsFeeData(
    val fee: Map<String, Double>?
)

data class KnsDomainCheckRequest(
    val address: String,
    val domainNames: List<String>
)

data class KnsDomainCheckResponse(
    val success: Boolean,
    val data: KnsDomainCheckData?,
    val message: String? = null,
    val error: String? = null
)

data class KnsDomainCheckData(
    val domains: List<KnsDomainCheckEntry>?
)

data class KnsDomainCheckEntry(
    val domain: String,
    val available: Boolean,
    val isReservedDomain: Boolean = false
)

// Real shape confirmed against api.knsdomains.org/upload/image — doubly-nested, and the URL
// field is "imageUrl", not the "resolvedImageURL" iOS's own decoded model happens to call it:
// {"success":true,"data":{"success":true,"message":"...","data":{"imageUrl":"https://...","assetId":"...","uploadType":"avatar","fileSize":150059,"mimeType":"image/png"}}}
data class KnsImageUploadResponse(
    val success: Boolean,
    val data: KnsImageUploadOuterData?,
    val message: String? = null,
    val error: String? = null
)

data class KnsImageUploadOuterData(
    val success: Boolean,
    val message: String? = null,
    val data: KnsImageUploadInnerData?
)

data class KnsImageUploadInnerData(
    val imageUrl: String?
)

/** {signMessage, signature} — POSTs the signed message string itself plus the signature, matching iOS's KNSSetPrimaryNameRequest exactly. */
data class KnsSetPrimaryNameRequest(val signMessage: String, val signature: String)

data class KnsBasicResponse(
    val success: Boolean,
    val message: String? = null,
    val error: String? = null
)
