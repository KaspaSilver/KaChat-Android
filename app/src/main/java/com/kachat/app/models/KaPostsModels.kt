package com.kachat.app.models

import androidx.compose.runtime.Immutable
import java.security.MessageDigest
import java.util.UUID

/**
 * UI model for a KaPost - mirrors iOS KaPostsView.DraftPost. Immutable: every engagement
 * change produces a new copy via [mutatePostIn], so Compose recomposition stays cheap and
 * list row identity is stable.
 *
 * `@Immutable` is a promise the class already keeps (all vals; [comments] is never mutated in
 * place, copies only). It matters for feed scroll: without it the `List` field makes the class
 * unstable, so strong skipping falls back to instance identity - and a refresh/mutateEverywhere
 * pass that rebuilds equal-content instances recomposed every visible KaPostCell. With it,
 * data-class equality lets unchanged rows skip.
 */
@Immutable
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

    @Immutable
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
 * Applies [transform] to EVERY occurrence of the post with [id] in the tree - top level or
 * nested in comments at any depth. Returns the resulting list and whether any hit occurred.
 *
 * Every occurrence, not just the first: ids are stable txid hashes, so the same post
 * legitimately lives in several places at once - a reply is a feed row AND a comment nested
 * under its parent's thread, a post sits in the global feed AND a profile tab. The displayed
 * copy (the one nested under whatever thread root is open) is not necessarily the copy a
 * first-hit walk finds first, so a first-hit-only mutation left the open thread rendering a
 * stale instance while some other copy took the like (the "thread doesn't update until
 * reopened" bug). Untouched subtrees keep their ORIGINAL instances - a list with no
 * occurrence comes back reference-equal, so its StateFlow never ticks and Compose skipping
 * stays effective.
 */
fun mutatePostIn(list: List<KaPostDraft>, id: String, transform: (KaPostDraft) -> KaPostDraft): Pair<List<KaPostDraft>, Boolean> {
    var hit = false
    fun walk(items: List<KaPostDraft>): List<KaPostDraft> {
        var changed = false
        val out = items.map { post ->
            val newComments = walk(post.comments)
            val withComments = if (newComments !== post.comments) post.copy(comments = newComments) else post
            val result = if (post.id == id) {
                hit = true
                transform(withComments)
            } else {
                withComments
            }
            if (result !== post) changed = true
            result
        }
        return if (changed) out else items
    }
    return walk(list) to hit
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
