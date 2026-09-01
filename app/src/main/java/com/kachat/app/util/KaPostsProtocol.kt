package com.kachat.app.util

import java.util.Base64

/**
 * Builds K protocol payloads exactly as the indexer's parser/verifier expects
 * (K-transaction-processor/k_protocol.rs). Every write is a colon-delimited payload on a Kaspa
 * self-send transaction; the signature is Kaspa personal-message signing
 * (schnorr(blake2b256(key: "PersonalMessageSigningHash", msg))) over the action's canonical
 * field string. Mirrors iOS KaPostsProtocol in KaPostsAPIClient.swift byte-for-byte.
 */
object KaPostsProtocol {
    // `kchat:` migration: KaPosts now writes the `kchat:1:<action>:` root (was `k:1:`). Reads come
    // pre-parsed from the K indexer (dual-reads server-side), so only the write shape changes.
    const val PREFIX = "kchat:1:"

    /**
     * U+2060 WORD JOINER - the KaChat exclusivity marker. Invisible everywhere, survives base64
     * round-trips, and comes back in postContent so feeds can filter on it (the raw tx payload
     * is NOT exposed by the read API, which is why the marker must live inside the message).
     */
    const val KACHAT_MARKER = "\u2060"

    fun isKaChatContent(text: String): Boolean = text.startsWith(KACHAT_MARKER)

    fun stripMarker(text: String): String =
        if (text.startsWith(KACHAT_MARKER)) text.removePrefix(KACHAT_MARKER) else text

    // java.util.Base64 (minSdk 26+) rather than android.util.Base64 so this stays testable in
    // plain JVM unit tests. The encoder never wraps lines, matching iOS's base64EncodedString().
    fun b64(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    fun decodeB64(encoded: String): String? = try {
        String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }

    // Signed strings (canonical field joins, verified server-side):
    fun postSigningString(b64Message: String, mentionsJson: String) =
        "$b64Message:$mentionsJson"

    fun replySigningString(postId: String, b64Message: String, mentionsJson: String) =
        "$postId:$b64Message:$mentionsJson"

    fun voteSigningString(postId: String, vote: String, authorPubkey: String) =
        "$postId:$vote:$authorPubkey"

    fun followSigningString(action: String, followedPubkey: String) =
        "$action:$followedPubkey"

    fun quoteSigningString(contentId: String, b64Message: String, quotedAuthorPubkey: String) =
        "$contentId:$b64Message:$quotedAuthorPubkey"

    fun unquoteSigningString(contentId: String) = contentId

    /**
     * The on-chain record behind one post id, read straight off the transaction payload.
     *
     * The K indexer has no single-post lookup (`get-post?id=` is still a NEEDED item in
     * KAPOSTS_INDEXER.md), so a post outside the feed window - which is most posts someone shares
     * into a chat - cannot be fetched from the API at all. The chain always has it: a post IS a
     * transaction, and its id IS the transaction id. Mirrors iOS's `ChainPost`.
     *
     * [message] is decoded with the exclusivity marker stripped, and may be empty: a quote with
     * no added comment is a repost.
     */
    data class ChainPost(val action: String, val authorPubkey: String, val message: String)

    /**
     * Parses a decoded transaction payload, or null for anything that is not a KaPosts message -
     * votes, follows and unquotes carry no text, and other apps' payloads share the chain.
     *
     * Reads the legacy `k:1:` root as well as today's `kchat:1:`, matching the indexer's own
     * dual-read: posts written before the migration are still perfectly good posts.
     */
    fun parseChainPayload(payload: String): ChainPost? {
        val root = listOf(PREFIX, "k:1:").firstOrNull { payload.startsWith(it) } ?: return null
        // Base64 has no ":" in its alphabet and neither do pubkeys, signatures or ids, so the
        // fields split cleanly however long the message is.
        val fields = payload.removePrefix(root).split(":")
        if (fields.size < 4) return null
        val action = fields[0]
        // post:  <pubkey>:<signature>:<b64message>:<mentions>
        // reply: <pubkey>:<signature>:<postId>:<b64message>:<mentions>
        // quote: <pubkey>:<signature>:<contentId>:<b64message>:<quotedAuthorPubkey>
        val messageIndex = when (action) {
            "post" -> 3
            "reply", "quote" -> 4
            else -> return null
        }
        if (fields.size <= messageIndex) return null
        val decoded = decodeB64(fields[messageIndex]) ?: return null
        return ChainPost(action, fields[1], stripMarker(decoded).trim())
    }

    // Full payloads:
    fun postPayload(pubkey: String, signature: String, b64Message: String, mentionsJson: String) =
        "${PREFIX}post:$pubkey:$signature:$b64Message:$mentionsJson"

    fun replyPayload(pubkey: String, signature: String, postId: String, b64Message: String, mentionsJson: String) =
        "${PREFIX}reply:$pubkey:$signature:$postId:$b64Message:$mentionsJson"

    fun votePayload(pubkey: String, signature: String, postId: String, vote: String, authorPubkey: String) =
        "${PREFIX}vote:$pubkey:$signature:$postId:$vote:$authorPubkey"

    fun followPayload(pubkey: String, signature: String, action: String, followedPubkey: String) =
        "${PREFIX}follow:$pubkey:$signature:$action:$followedPubkey"

    fun quotePayload(pubkey: String, signature: String, contentId: String, b64Message: String, quotedAuthorPubkey: String) =
        "${PREFIX}quote:$pubkey:$signature:$contentId:$b64Message:$quotedAuthorPubkey"

    fun unquotePayload(pubkey: String, signature: String, contentId: String) =
        "${PREFIX}unquote:$pubkey:$signature:$contentId"
}
