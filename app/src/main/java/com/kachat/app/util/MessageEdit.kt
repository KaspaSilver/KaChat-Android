package com.kachat.app.util

import com.google.gson.Gson

/**
 * An edit to one of the sender's OWN earlier text messages. Nothing on chain changes: the edit is
 * a new transaction that names the original by [targetTxId] and carries the new [text], and every
 * client shows the newest edit's text in place of the original with a small "edited" mark. Same
 * envelope-in-content pattern as [MessageReactionContent] — embedded in the normal encrypted
 * contextual content (1:1), the group-encrypted message (groups), or the plaintext broadcast row
 * (public chats) — and never rendered as a bubble of its own. Rules, the same on every platform:
 * only the original sender's edits count; the newest by block time wins; text only (a payment,
 * voice message, photo, chess move or call line is never editable). Wire format in MESSAGING.md
 * ("Message Edits"); field-for-field identical to iOS's `MessageEditContent`.
 */
data class MessageEditContent(
    val type: String = "edit",
    val targetTxId: String,
    val text: String
)

object MessageEdit {
    private val gson = Gson()

    /** Matches iOS `MessageEditCodec.maxLength` — an edit longer than this is truncated, not rejected. */
    const val MAX_LENGTH = 4_000

    fun encode(targetTxId: String, text: String): String {
        return gson.toJson(MessageEditContent(targetTxId = targetTxId, text = text.take(MAX_LENGTH)))
    }

    /**
     * Parses [text] as an edit envelope if it looks like one, else null — same {-prefix +
     * size-guard + explicit type check as [MessageReply.parseOrNull], for the same hot-path
     * scrolling reason.
     */
    fun parseOrNull(text: String?): MessageEditContent? {
        if (text.isNullOrBlank() || text.length > 100_000 || text.trimStart().firstOrNull() != '{') return null
        return try {
            val parsed = gson.fromJson(text, MessageEditContent::class.java) ?: return null
            @Suppress("SENSELESS_COMPARISON")
            if (parsed.type == "edit" && parsed.targetTxId != null && parsed.targetTxId.isNotEmpty() && parsed.text != null) parsed else null
        } catch (e: Exception) {
            // Same broad catch as MessageReply.parseOrNull — Gson can hand back a null in a
            // non-null field for an absent key and NPE rather than throw JsonSyntaxException.
            null
        }
    }

    /**
     * The message's content as it reads after the edit: a reply keeps its quote and gets the new
     * text; plain text is simply replaced.
     */
    fun apply(edit: String, to: String?): String {
        val quote = MessageReply.parseOrNull(to)
        if (quote != null) {
            return MessageReply.encode(
                replyToId = quote.replyToId,
                replyToSender = quote.replyToSender,
                replyToPreview = quote.replyToPreview,
                text = edit
            )
        }
        return edit
    }

    /** The text a composer starts from when editing [content] — a reply's own text, else the text itself. */
    fun unwrappedText(content: String?): String {
        return MessageReply.parseOrNull(content)?.text ?: (content ?: "")
    }

    /**
     * Whether an edit may be offered on [content]: plain text, or a reply with text — never an
     * envelope of any kind (payment notice, voice, photo, chess, call, reaction, pool...).
     */
    fun isEditable(content: String?): Boolean {
        val unwrapped = unwrappedText(content).trim()
        return unwrapped.isNotEmpty() && unwrapped.firstOrNull() != '{'
    }
}
