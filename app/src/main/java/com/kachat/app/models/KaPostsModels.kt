package com.kachat.app.models

import java.security.MessageDigest
import java.util.UUID

/**
 * UI model for a KaPost - mirrors iOS KaPostsView.DraftPost. Immutable: every engagement
 * change produces a new copy via [mutatePostIn], so Compose recomposition stays cheap and
 * list row identity is stable.
 */
data class KaPostDraft(
    /**
     * Local session posts get a random id; indexer-fetched posts get a STABLE id derived from
     * their txid (see [stableId]) - refreshing a feed must not hand every row a new identity,
     * or LazyColumn rebuilds the whole feed (scroll jumps, seen on iOS resume).
     */
    val id: String = UUID.randomUUID().toString(),
    /** Post body, marker already stripped - PLAIN TEXT only. */
    val text: String,
    /** Milliseconds since epoch. */
    val timestamp: Long,
    /** Kaspa address of the author - drives KNS avatar/name resolution and follow state. */
    val posterAddress: String,
    /** K transaction id when this post came from the indexer (null = local session post). */
    val remoteId: String? = null,
    /** Author's K pubkey (66-hex compressed) - needed for vote/quote/follow targeting. */
    val posterPubkey: String? = null,
    val likes: Int = 0,
    val dislikes: Int = 0,
    val reposts: Int = 0,
    val likedByMe: Boolean = false,
    val dislikedByMe: Boolean = false,
    val repostedByMe: Boolean = false,
    val bookmarkedByMe: Boolean = false,
    /** On-chain delivery state, mirroring chat messages. Remote-fetched posts are SENT. */
    val deliveryStatus: Delivery = Delivery.SENT,
    /** The indexer's reply count - shown before any replies have actually been fetched. */
    val remoteReplyCount: Int = 0,
    /**
     * Replies, X-style. Comments are themselves [KaPostDraft]s so the cell is reused wholesale,
     * and they nest - a comment carries its own comments, expandable inline.
     */
    val comments: List<KaPostDraft> = emptyList(),
    /** X-style quote embed: set when this post quotes another. */
    val quoted: QuotedRef? = null,
    /** Set for replies fetched from the indexer - splits profile feeds into Posts/Replies. */
    val parentRemoteId: String? = null,
) {
    enum class Delivery { PENDING, SENT, FAILED }

    data class QuotedRef(
        val remoteId: String?,
        val text: String,
        val posterAddress: String,
        val timestamp: Long?,
    )

    companion object {
        /** X-matching post/reply length cap (matches iOS KaPostsView.postCharacterLimit). */
        const val POST_CHARACTER_LIMIT = 25_000

        /** Deterministic id from a txid so re-fetches keep row identity stable. */
        fun stableId(txId: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(txId.toByteArray(Charsets.UTF_8))
            return digest.take(16).joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Applies [transform] to the post with [id] ANYWHERE in the tree - top level or nested comments
 * at any depth. Returns the new list and whether a hit occurred (so callers can chain across
 * multiple lists exactly like iOS mutatePost does).
 */
fun mutatePostIn(list: List<KaPostDraft>, id: String, transform: (KaPostDraft) -> KaPostDraft): Pair<List<KaPostDraft>, Boolean> {
    var hit = false
    fun walk(items: List<KaPostDraft>): List<KaPostDraft> = items.map { post ->
        if (hit) return@map post
        if (post.id == id) {
            hit = true
            transform(post)
        } else {
            val newComments = walk(post.comments)
            // hit was false when we entered this post, so a true here means the target lives
            // somewhere inside newComments - keep the rewritten subtree.
            if (hit) post.copy(comments = newComments) else post
        }
    }
    val result = walk(list)
    return if (hit) result to true else list to false
}

/** Recursive lookup by local id, mirroring [mutatePostIn]'s coverage. */
fun findPostIn(list: List<KaPostDraft>, id: String): KaPostDraft? {
    for (post in list) {
        if (post.id == id) return post
        findPostIn(post.comments, id)?.let { return it }
    }
    return null
}

/** Recursive lookup by on-chain txid. */
fun findPostByRemoteIdIn(list: List<KaPostDraft>, remoteId: String): KaPostDraft? {
    for (post in list) {
        if (post.remoteId == remoteId) return post
        findPostByRemoteIdIn(post.comments, remoteId)?.let { return it }
    }
    return null
}
