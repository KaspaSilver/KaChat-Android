package com.kachat.app.util

import com.google.gson.Gson

/**
 * A message that replies to an earlier one — embedded as JSON directly in the same plaintext
 * content used for plain text (no separate wire type), matching the same approach [VoiceMessage]
 * already uses. Shared by both 1:1 chats and broadcast rooms: [replyToSender] is a Kaspa address
 * in both cases (the other party's address in a 1:1 chat, or the original poster's address in a
 * broadcast), and [replyToPreview] is captured at reply-creation time (a short snippet of the
 * original message, or "🎤 Audio message" if that was a voice note) so the quote still renders
 * even if the original message itself has since been deleted/pruned or its sender hidden.
 */
data class MessageReplyContent(
    val type: String = "reply",
    val replyToId: String,
    val replyToSender: String,
    val replyToPreview: String,
    val text: String
)

object MessageReply {
    private val gson = Gson()
    private const val PREVIEW_MAX_LENGTH = 80

    fun encode(replyToId: String, replyToSender: String, replyToPreview: String, text: String): String {
        return gson.toJson(
            MessageReplyContent(
                replyToId = replyToId,
                replyToSender = replyToSender,
                replyToPreview = replyToPreview.take(PREVIEW_MAX_LENGTH),
                text = text
            )
        )
    }

    /**
     * The one-line quote a reply carries of the message it answers (iOS
     * `MessageReplyCodec.previewText`): a reply is unwrapped to its own text, and nothing raw -
     * no reaction, media or chess JSON, and no Nextcloud share URL (the sender's own server,
     * which the quote would put on chain for good) - is quoted. The labels are iOS's own, in
     * English as iOS writes them: the quote is part of the message both phones show.
     */
    fun previewText(content: String?): String {
        val unwrapped = parseOrNull(content)?.text ?: content.orEmpty()
        MessageReaction.parseOrNull(unwrapped)?.let { return "Reacted ${it.emoji}" }
        val mime = InlineMediaSniff.mimeType(unwrapped)?.lowercase()
        if (mime?.startsWith("audio/") == true) return "🎤 Audio message"
        if (mime?.startsWith("image/") == true) return "📷 Photo"
        ChessMessage.parseOrNull(unwrapped)?.let { envelope ->
            if (envelope is ChessEnvelope.Invite) {
                envelope.content.tcMinutes?.let { minutes -> return "♟️ Chess - $minutes | ${envelope.content.tcIncSeconds ?: 0}" }
            }
            return "♟️ Chess"
        }
        if (mime != null) return if (mime.startsWith("video/")) "🎬 Video" else "📎 File"
        if (isFileEnvelope(unwrapped)) return "📎 File"
        NextcloudShareSniff.previewLabel(unwrapped)?.let { return it }
        return unwrapped
    }

    private val FILE_TYPE = Regex("\"type\"\\s*:\\s*\"file\"")

    /** Any `{"type":"file"}` envelope, from its head (iOS `InlineMediaSniff.isFileEnvelope`). */
    private fun isFileEnvelope(text: String): Boolean {
        val head = text.take(2048).trim()
        return head.firstOrNull() == '{' && FILE_TYPE.containsMatchIn(head)
    }

    /**
     * Parses [text] as a reply if it looks like one, else returns null — a plain text message
     * never accidentally renders as a reply just because it happens to start with `{`, since this
     * also requires the explicit "reply" type marker.
     *
     * This runs on every message bubble's composition, for every visible/newly-appearing row,
     * uncached — a hot path when scrolling. A reply envelope is always small (a short reply-to
     * preview plus the reply text itself), but a photo/file message's envelope is ALSO valid JSON
     * starting with `{` (see ImageMessage/VoiceMessage) — without this size guard, every image
     * message's multi-KB-to-multi-MB base64 payload got a real Gson parse attempt here, just to
     * fail the type == "reply" check (or NPE and get caught) afterward. Scrolling fast through a
     * photo-heavy chat's history — revealing many such messages at once — made that add up to a
     * real freeze on the main thread (Compose's own layout/recomposition work competing with it).
     */
    fun parseOrNull(text: String?): MessageReplyContent? {
        if (text.isNullOrBlank() || text.length > 100_000 || text.trimStart().firstOrNull() != '{') return null
        return try {
            // Every field must be there as a string, as iOS's JSONDecoder requires
            // (MessageReplyCodec.parse): Gson leaves a missing key null in a non-null field, and a
            // half-empty reply crashed the chat it sits in on every open.
            val json = com.google.gson.JsonParser.parseString(text)
            if (!json.isJsonObject) return null
            val obj = json.asJsonObject
            fun string(key: String): String? =
                obj.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            if (string("type") != "reply") return null
            MessageReplyContent(
                replyToId = string("replyToId") ?: return null,
                replyToSender = string("replyToSender") ?: return null,
                replyToPreview = string("replyToPreview") ?: return null,
                text = string("text") ?: return null,
            )
        } catch (e: Exception) {
            // Same broad catch as VoiceMessage.parseOrNull — Gson's reflection-based Kotlin
            // deserialization doesn't honor non-null defaults for absent JSON keys, so a
            // non-nullable field can still come back null at runtime and NPE rather than throw
            // a JsonSyntaxException.
            null
        }
    }
}
