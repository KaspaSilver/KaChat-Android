package com.kachat.app.services

import android.util.Log
import com.kachat.app.util.KaPostsProtocol
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaMessageSigner
import com.kachat.app.util.Secp256k1
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import retrofit2.HttpException
import retrofit2.http.GET
import retrofit2.http.Query
import java.math.BigInteger
import javax.inject.Inject
import javax.inject.Singleton

// MARK: - Wire models (verbatim K field names; identity fields deliberately ignored - KNS owns
// ALL identity via the pubkey -> Kaspa address bridge, so userNickname/avatar fields never map)

/** Embedded reference the indexer attaches to quote posts. */
data class KQuoteRef(
    val referencedContentId: String?,
    val referencedMessage: String?,
    val referencedSenderPubkey: String?,
) {
    val decodedMessage: String? get() = referencedMessage?.let { KaPostsProtocol.decodeB64(it) }
}

data class KPost(
    val id: String,
    val userPublicKey: String,
    val postContent: String,
    val timestamp: Long,
    val repliesCount: Int?,
    val quotesCount: Int?,
    val upVotesCount: Int?,
    val downVotesCount: Int?,
    val parentPostId: String?,
    val mentionedPubkeys: List<String>?,
    val isUpvoted: Boolean?,
    val isDownvoted: Boolean?,
    val blockedUser: Boolean?,
    val contentType: String?,
    val isQuote: Boolean?,
    val quote: KQuoteRef?,
) {
    /** Base64 -> plain text (K encodes all content fields). */
    val decodedContent: String? get() = KaPostsProtocol.decodeB64(postContent)
}

data class KPagination(val hasMore: Boolean?, val nextCursor: String?, val prevCursor: String?)

data class KPostsResponse(val posts: List<KPost>?, val pagination: KPagination?)

data class KRepliesResponse(val replies: List<KPost>?, val pagination: KPagination?)

/**
 * One fetched page, keeping the RAW page's paging facts alongside the filtered rows.
 *
 * The KaChat-marker filter (and the viewer's mute/block lists further up) can shrink a 25-item
 * server page down to two visible rows, so "did we reach the end?" can only ever be answered from
 * the untouched response: [hasMore]/[cursor] come from the server's pagination block, and [rawIds]
 * are every id the page carried, filtered or not. Callers use [rawIds] to detect a deployment that
 * ignores `before` (a second page repeating the same ids) and stop instead of looping.
 */
data class KPage<T>(
    val items: List<T>,
    val rawIds: List<String>,
    val cursor: String?,
    val hasMore: Boolean,
) {
    companion object {
        fun <T> empty(): KPage<T> = KPage(emptyList(), emptyList(), null, false)
    }
}

data class KNotification(
    val id: String,
    val userPublicKey: String,
    val postContent: String?,
    val timestamp: Long,
    val contentType: String?,
    val voteType: String?,
    val contentId: String?,
) {
    val decodedContent: String? get() = postContent?.let { KaPostsProtocol.decodeB64(it) }
}

data class KNotificationsResponse(val notifications: List<KNotification>?, val pagination: KPagination?)

/** One actor row from get-post-engagement (KaChat indexer fork). */
data class KEngagementEntry(
    val actorPubkey: String,
    val actionTxId: String,
    val timestamp: Long,
    val kind: String, // upvote | downvote | repost | quote
)

data class KEngagementResponse(val engagement: List<KEngagementEntry>?, val pagination: KPagination?)

data class KFollowUser(val userPublicKey: String, val timestamp: Long?)

/**
 * The users-list endpoints wrap their items under "posts" (verified live against the fork) -
 * keep the other plausible keys as fallbacks in case deployments differ.
 */
data class KFollowListResponse(
    val posts: List<KFollowUser>?,
    val users: List<KFollowUser>?,
    val following: List<KFollowUser>?,
    val followers: List<KFollowUser>?,
    val pagination: KPagination? = null,
) {
    val items: List<KFollowUser> get() = posts ?: users ?: following ?: followers ?: emptyList()
}

data class KUserDetails(
    val userPublicKey: String?,
    val followersCount: Int?,
    val followingCount: Int?,
    val followedUser: Boolean?,
)

/** REST read API of the K social indexer (Settings > Connection Settings > KaPost Indexer). */
interface KaPostApi {
    @GET("get-posts-watching")
    suspend fun getPostsWatching(
        @Query("requesterPubkey") requesterPubkey: String,
        @Query("limit") limit: Int = 50,
        @Query("before") before: String? = null,
    ): KPostsResponse

    @GET("get-contents-following")
    suspend fun getContentsFollowing(
        @Query("requesterPubkey") requesterPubkey: String,
        @Query("limit") limit: Int = 50,
        @Query("before") before: String? = null,
    ): KPostsResponse

    @GET("get-posts")
    suspend fun getUserPosts(
        @Query("user") user: String,
        @Query("requesterPubkey") requesterPubkey: String,
        @Query("limit") limit: Int = 50,
        @Query("includeReplies") includeReplies: String? = null,
        @Query("before") before: String? = null,
    ): KPostsResponse

    @GET("get-replies")
    suspend fun getReplies(
        @Query("post") postId: String,
        @Query("requesterPubkey") requesterPubkey: String,
        @Query("limit") limit: Int = 100,
        @Query("before") before: String? = null,
    ): KRepliesResponse

    /** get-replies dual mode: `user` instead of `post` returns all replies MADE BY that user. */
    @GET("get-replies")
    suspend fun getUserReplies(
        @Query("user") user: String,
        @Query("requesterPubkey") requesterPubkey: String,
        @Query("limit") limit: Int = 50,
        @Query("before") before: String? = null,
    ): KRepliesResponse

    @GET("get-notifications")
    suspend fun getNotifications(
        @Query("requesterPubkey") requesterPubkey: String,
        @Query("limit") limit: Int = 100,
        @Query("before") before: String? = null,
    ): KNotificationsResponse

    @GET("get-post-engagement")
    suspend fun getPostEngagement(
        @Query("postId") postId: String,
        @Query("type") type: String = "all",
        @Query("requesterPubkey") requesterPubkey: String,
        @Query("limit") limit: Int = 100,
        @Query("before") before: String? = null,
    ): KEngagementResponse

    /**
     * The follow-list endpoints are not documented as cursored; `before` is sent anyway so a
     * deployment that supports it paginates, and one that ignores it simply replays page one -
     * which the caller detects (no new ids) and treats as "end of list" instead of looping.
     */
    @GET("get-users-following")
    suspend fun getUsersFollowing(
        @Query("userPubkey") userPubkey: String,
        @Query("requesterPubkey") requesterPubkey: String,
        @Query("limit") limit: Int = 100,
        @Query("before") before: String? = null,
    ): KFollowListResponse

    @GET("get-users-followers")
    suspend fun getUsersFollowers(
        @Query("userPubkey") userPubkey: String,
        @Query("requesterPubkey") requesterPubkey: String,
        @Query("limit") limit: Int = 100,
        @Query("before") before: String? = null,
    ): KFollowListResponse

    @GET("get-user-details")
    suspend fun getUserDetails(
        @Query("user") user: String,
        @Query("requesterPubkey") requesterPubkey: String,
    ): KUserDetails
}

/**
 * KaPosts client - mirrors iOS KaPostsAPIClient. Reads come from the K indexer REST API
 * (KaChat-marker-filtered); writes never touch REST - every K action is an on-chain Kaspa
 * self-send transaction whose payload the indexer ingests from the chain.
 *
 * Read methods THROW on network/API failure (the ViewModel catches into a feed error state,
 * matching iOS); this deliberately differs from the app's null-swallowing read services
 * because the feed UI surfaces errors.
 */
@Singleton
class KaPostsService @Inject constructor(
    private val networkService: NetworkService,
    private val walletManager: WalletManager,
    private val walletService: WalletService,
) {
    companion object {
        private const val TAG = "KaPostsService"

        /**
         * Compressed (02/03 + x) or raw x-only pubkey hex -> Kaspa address. THE bridge that
         * keeps identity in KNS: every K pubkey maps to an address and resolves through the
         * app's normal contacts/KNS chain.
         */
        fun kaspaAddressFromPubkey(pubkeyHex: String): String? {
            val raw = decodeHex(pubkeyHex) ?: return null
            val xOnly = when (raw.size) {
                33 -> raw.sliceArray(1..32)
                32 -> raw
                else -> return null
            }
            return KaspaAddress.encode("kaspa", 0x00, xOnly)
        }

        /**
         * The inverse bridge for @mentions: a Kaspa address' 32-byte x-only payload as the
         * compressed KaPost pubkey (BIP-340 even-Y convention, matching desktop/iOS). Used to
         * fill mentioned_pubkeys from a KNS-domain contact's address alone.
         */
        fun kapostPubkeyFromAddress(address: String): String? = try {
            val (version, payload) = KaspaAddress.decode(address)
            if (version == 0x00.toByte() && payload.size == 32) {
                "02" + payload.joinToString("") { "%02x".format(it) }
            } else null
        } catch (_: Exception) {
            null
        }

        private fun decodeHex(hex: String): ByteArray? {
            if (hex.length % 2 != 0 || hex.isEmpty()) return null
            return try {
                ByteArray(hex.length / 2) { i ->
                    hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            } catch (_: NumberFormatException) {
                null
            }
        }
    }

    private suspend fun api(): KaPostApi = networkService.kapostApi.filterNotNull().first()

    // MARK: - Requester identity (own compressed pubkey, derived once per wallet)

    private var cachedRequesterPubkey: String? = null
    private var cachedRequesterAddress: String? = null

    /**
     * K identifies users by 66-hex COMPRESSED secp256k1 pubkey. The wallet stores x-only, so
     * derive compressed from the private key and cache per wallet address.
     */
    fun requesterPubkey(): String {
        val address = walletManager.getAddress()
        cachedRequesterPubkey?.let { if (cachedRequesterAddress == address) return it }
        val priv = BigInteger(1, walletManager.getPrivateKeyBytes())
        val compressed = Secp256k1.G.multiply(priv).normalize().getEncoded(true)
        val hex = compressed.joinToString("") { "%02x".format(it) }
        cachedRequesterPubkey = hex
        cachedRequesterAddress = address
        return hex
    }

    // MARK: - Reads (KaChat-filtered, page-shaped)

    /**
     * Wraps one raw response into a [KPage]. `hasMore` prefers the server's own flag and falls
     * back to "the page came back full"; the cursor prefers the server's opaque `nextCursor` and
     * falls back to the last raw id, which the caller's no-new-ids guard makes safe on
     * deployments where that is not what `before` means.
     */
    private fun <T> pageOf(
        raw: List<T>,
        filtered: List<T>,
        pagination: KPagination?,
        limit: Int,
        idOf: (T) -> String,
    ): KPage<T> {
        val rawIds = raw.map(idOf)
        return KPage(
            items = filtered,
            rawIds = rawIds,
            cursor = pagination?.nextCursor ?: rawIds.lastOrNull(),
            hasMore = pagination?.hasMore ?: (raw.size >= limit),
        )
    }

    /** Global feed (K "watching" = all posts). Filtered to KaChat-marked posts. */
    suspend fun fetchGlobalFeedPage(limit: Int = 25, before: String? = null): KPage<KPost> =
        rethrowingApiError {
            val response = api().getPostsWatching(requesterPubkey(), limit, before)
            val raw = response.posts.orEmpty()
            pageOf(raw, filterKaChat(raw), response.pagination, limit) { it.id }
        }

    /** Content from accounts the requester follows. Top-level content only - replies live under threads. */
    suspend fun fetchFollowingFeedPage(limit: Int = 25, before: String? = null): KPage<KPost> =
        rethrowingApiError {
            val response = api().getContentsFollowing(requesterPubkey(), limit, before)
            val raw = response.posts.orEmpty()
            val filtered = filterKaChat(raw).filter { (it.contentType ?: "post") != "reply" }
            pageOf(raw, filtered, response.pagination, limit) { it.id }
        }

    /** One user's posts (by K pubkey). NOTE: the deployed indexer ignores includeReplies
     *  (get-posts serves content_type post/quote only) - use fetchUserReplies for replies. */
    suspend fun fetchUserPostsPage(
        pubkey: String,
        limit: Int = 25,
        before: String? = null,
        includeReplies: Boolean = false,
    ): KPage<KPost> = rethrowingApiError {
        val response = api().getUserPosts(
            pubkey, requesterPubkey(), limit, if (includeReplies) "true" else null, before,
        )
        val raw = response.posts.orEmpty()
        pageOf(raw, filterKaChat(raw), response.pagination, limit) { it.id }
    }

    /** One user's replies across ALL threads: get-replies with `user` instead of `post`
     *  (verified live + against the fork's handle_get_replies). Items carry parentPostId.
     *  The profile Replies tab must read this - get-posts never returns replies. */
    suspend fun fetchUserRepliesPage(pubkey: String, limit: Int = 25, before: String? = null): KPage<KPost> =
        rethrowingApiError {
            val response = api().getUserReplies(pubkey, requesterPubkey(), limit, before)
            val raw = response.replies.orEmpty()
            pageOf(raw, filterKaChat(raw), response.pagination, limit) { it.id }
        }

    suspend fun fetchRepliesPage(postId: String, limit: Int = 25, before: String? = null): KPage<KPost> =
        rethrowingApiError {
            val response = api().getReplies(postId, requesterPubkey(), limit, before)
            val raw = response.replies.orEmpty()
            pageOf(raw, filterKaChat(raw), response.pagination, limit) { it.id }
        }

    /** The requester's notification stream - actions on OUR content. */
    suspend fun fetchNotificationsPage(limit: Int = 50, before: String? = null): KPage<KNotification> =
        rethrowingApiError {
            val response = api().getNotifications(requesterPubkey(), limit, before)
            val raw = response.notifications.orEmpty()
            pageOf(raw, raw, response.pagination, limit) { it.id }
        }

    /** Per-post actor lists (KaChat indexer fork) - works for ANY post. */
    suspend fun fetchPostEngagementPage(
        postId: String,
        type: String = "all",
        limit: Int = 50,
        before: String? = null,
    ): KPage<KEngagementEntry> = rethrowingApiError {
        val response = api().getPostEngagement(postId, type, requesterPubkey(), limit, before)
        val raw = response.engagement.orEmpty()
        pageOf(raw, raw, response.pagination, limit) { it.actionTxId }
    }

    /** Who [pubkey] follows (followers=false) or who follows them (followers=true). */
    suspend fun fetchFollowListPage(
        pubkey: String,
        followers: Boolean,
        limit: Int = 50,
        before: String? = null,
    ): KPage<KFollowUser> = rethrowingApiError {
        val response = if (followers) {
            api().getUsersFollowers(pubkey, requesterPubkey(), limit, before)
        } else {
            api().getUsersFollowing(pubkey, requesterPubkey(), limit, before)
        }
        val raw = response.items
        pageOf(raw, raw, response.pagination, limit) { it.userPublicKey }
    }

    /** Single-page convenience for callers that never paginate (the notification poller). */
    suspend fun fetchNotifications(limit: Int = 100, before: String? = null): List<KNotification> =
        fetchNotificationsPage(limit, before).items

    suspend fun fetchUserDetails(pubkey: String): KUserDetails =
        rethrowingApiError { api().getUserDetails(pubkey, requesterPubkey()) }

    /**
     * KaChat-only filter: keep posts whose decoded content carries the invisible marker, and
     * drop content the indexer masked for blocked users.
     */
    private fun filterKaChat(posts: List<KPost>): List<KPost> = posts.filter { post ->
        if (post.blockedUser == true) return@filter false
        val content = post.decodedContent ?: return@filter false
        KaPostsProtocol.isKaChatContent(content)
    }

    /** Surfaces the indexer's JSON error body message instead of Retrofit's opaque HTTP line. */
    private inline fun <T> rethrowingApiError(block: () -> T): T = try {
        block()
    } catch (e: HttpException) {
        val body = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
        val message = body
            ?.let { Regex("\"error\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
            ?: "KaPost indexer error (HTTP ${e.code()})"
        throw IllegalStateException(message, e)
    }

    // MARK: - Writes (on-chain self-send transactions; txid = content id)

    /**
     * Publishes a KaChat post on-chain. The exclusivity marker is prepended INSIDE the message
     * (the only channel the read API surfaces). Returns the transaction id = post id.
     *
     * `mentionedPubkeys` (compressed hex) are client-resolved @mentions: they go into the signed
     * mentioned_pubkeys array and the indexer turns each into a `contentType: "mention"`
     * notification for that user. Deduped; malformed entries and the author's own key dropped.
     */
    suspend fun submitPost(text: String, mentionedPubkeys: List<String> = emptyList()): String {
        val b64 = KaPostsProtocol.b64(KaPostsProtocol.KACHAT_MARKER + text)
        val pubkey = requesterPubkey()
        val me = pubkey.lowercase()
        val clean = mentionedPubkeys
            .map { it.lowercase() }
            .filter { it.matches(Regex("^0[23][0-9a-f]{64}$")) && it != me }
            .distinct()
        val mentions = "[" + clean.joinToString(",") { "\"$it\"" } + "]"
        val signature = sign(KaPostsProtocol.postSigningString(b64, mentions))
        return submitPayloadTx(KaPostsProtocol.postPayload(pubkey, signature, b64, mentions))
    }

    /** Replies to a post (its K txid). Mention rule per spec: parent author, deduped. */
    suspend fun submitReply(text: String, postId: String, parentAuthorPubkey: String?): String {
        val b64 = KaPostsProtocol.b64(KaPostsProtocol.KACHAT_MARKER + text)
        val mentions = if (!parentAuthorPubkey.isNullOrEmpty()) "[\"$parentAuthorPubkey\"]" else "[]"
        val signature = sign(KaPostsProtocol.replySigningString(postId, b64, mentions))
        return submitPayloadTx(KaPostsProtocol.replyPayload(requesterPubkey(), signature, postId, b64, mentions))
    }

    /** Casts an upvote/downvote on a post. */
    suspend fun submitVote(postId: String, upvote: Boolean, authorPubkey: String): String =
        submitVoteAction(postId, if (upvote) "upvote" else "downvote", authorPubkey)

    /** Removal counter-action (fork): withdraws our existing up/down vote on a post. */
    suspend fun submitUnvote(postId: String, authorPubkey: String): String =
        submitVoteAction(postId, "unvote", authorPubkey)

    private suspend fun submitVoteAction(postId: String, vote: String, authorPubkey: String): String {
        val signature = sign(KaPostsProtocol.voteSigningString(postId, vote, authorPubkey))
        return submitPayloadTx(KaPostsProtocol.votePayload(requesterPubkey(), signature, postId, vote, authorPubkey))
    }

    /** Follows/unfollows a K identity (66-hex compressed pubkey). */
    suspend fun submitFollow(follow: Boolean, followedPubkey: String): String {
        val action = if (follow) "follow" else "unfollow"
        val signature = sign(KaPostsProtocol.followSigningString(action, followedPubkey))
        return submitPayloadTx(KaPostsProtocol.followPayload(requesterPubkey(), signature, action, followedPubkey))
    }

    /**
     * Quotes a post - K's repost mechanism (no separate repost action; quotesCount is the live
     * counter). A PLAIN repost is a quote whose message is just the KaChat marker; a
     * quote-with-commentary carries marker + text.
     */
    suspend fun submitQuote(text: String?, contentId: String, quotedAuthorPubkey: String): String {
        val b64 = KaPostsProtocol.b64(KaPostsProtocol.KACHAT_MARKER + (text ?: ""))
        val signature = sign(KaPostsProtocol.quoteSigningString(contentId, b64, quotedAuthorPubkey))
        return submitPayloadTx(KaPostsProtocol.quotePayload(requesterPubkey(), signature, contentId, b64, quotedAuthorPubkey))
    }

    /** Removal counter-action: withdraws our quote/repost of [contentId]. */
    suspend fun submitUnquote(contentId: String): String {
        val signature = sign(KaPostsProtocol.unquoteSigningString(contentId))
        return submitPayloadTx(KaPostsProtocol.unquotePayload(requesterPubkey(), signature, contentId))
    }

    private fun sign(message: String): String =
        KaspaMessageSigner.sign(message, walletManager.getPrivateKeyBytes(), KaspaMessageSigner.SigningMode.KASPA_PERSONAL_MESSAGE)

    /**
     * Shared write core: zero-amount self-send from the identity address with the K payload
     * attached. WalletService routes payload txs over gRPC (the REST gateway rejects them) and
     * refreshes the balance after submit.
     */
    private suspend fun submitPayloadTx(payload: String): String {
        val txId = walletService.sendKaspa(
            toAddress = walletManager.getAddress(),
            amountSompi = 0,
            payloadBytes = payload.toByteArray(Charsets.UTF_8),
        )
        Log.d(TAG, "Submitted ${payload.take(12)} action tx ${txId.take(12)}")
        return txId
    }
}
