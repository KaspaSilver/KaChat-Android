package com.kachat.app.util

import java.util.Base64

/**
 * Turns the body a public-room push arrives with into something a person can read.
 *
 * The push server builds that body from the room message, and has been seen sending it on raw:
 * a reply's whole JSON envelope, the same envelope cut off at the server's preview length (or
 * re-serialized with spaces) so it no longer parses, the whole `kchat:1:bcast:<room>:` payload,
 * or the message still base64-encoded. Each of those reached the notification as gibberish. This
 * undoes all of them, and no JSON ever reaches a lock screen: an envelope this cannot read still
 * says what it is. Mirrors iOS's notification service extension (iOS 8c5af1a, b8c49c7, 111eb6f).
 */
object BroadcastPushPreview {

    /** The on-chain payload prefixes a room message can arrive with, room name included. */
    private val PAYLOAD_PREFIXES = listOf("kchat:1:bcast:", "ciph_msg:1:bcast:")

    /**
     * Whether [body] is (probably) an edit envelope - including one the server cut off or
     * re-spaced. An edit is never announced: it changes an earlier message in place, so every
     * push path drops it rather than bannering anything (MESSAGING.md "Message Edits";
     * iOS `isEditEnvelope`).
     */
    fun isEditEnvelope(body: String): Boolean {
        val text = body.trim()
        if (MessageEdit.parseOrNull(text) != null) return true
        return text.startsWith("{") && text.replace(" ", "").contains("\"type\":\"edit\"")
    }

    fun clean(body: String): String {
        var text = body.trim()

        // The whole on-chain payload, prefix and room name included: keep what follows them.
        for (prefix in PAYLOAD_PREFIXES) {
            if (text.startsWith(prefix)) {
                val rest = text.removePrefix(prefix)
                val colon = rest.indexOf(':')
                if (colon >= 0) text = rest.substring(colon + 1).trim()
            }
        }

        // One unbroken base64 run is the message sent on undecoded: decode it when that yields
        // readable text, and carry on with the result.
        if (!text.startsWith("{") && text.length >= 16 && text.all { it.isLetterOrDigit() && it.code < 128 || it in "+/=-_" }) {
            decodeBase64Text(text)?.let { text = it }
        }

        if (!text.startsWith("{")) return text.ifEmpty { body }

        MessageReaction.parseOrNull(text)?.let { return "Reacted ${it.emoji}" }
        if (MessageEdit.parseOrNull(text) != null) return "Edited a message"
        if (ChessMessage.parseOrNull(text) != null) return "♟️ Chess game"
        CallCodec.parseOrNull(text)?.let { return CallCodec.notificationPreview(it) }
        MessageReply.parseOrNull(text)?.let { return it.text }

        // Still JSON: most likely a reply envelope cut off at the server's preview length (or
        // re-serialized with spaces), so it no longer parses. Pull the reply's own text out of
        // what is there.
        val compact = text.replace(" ", "")
        if (compact.contains("\"type\":\"reply\"")) {
            looseJsonString("text", text)?.let { return it }
            // The cut came before the reply's own text (the target's txid and sender alone fill
            // most of a 150-character preview): its words are not in this push at all.
            return "Replied to a message"
        }
        // Any other envelope that reached here: never the raw JSON on a lock screen.
        if (compact.contains("\"mimeType\":\"audio")) return "🎤 Audio message"
        if (compact.contains("\"mimeType\":\"image")) return "📷 Photo"
        if (compact.contains("\"mimeType\":\"video")) return "🎬 Video"
        if (compact.contains("\"type\":\"edit\"")) return "Edited a message"
        looseJsonString("text", text)?.let { return it }
        return "New message"
    }

    private fun decodeBase64Text(run: String): String? = runCatching {
        var candidate = run.replace('-', '+').replace('_', '/')
        while (candidate.length % 4 != 0) candidate += "="
        val decoded = String(Base64.getDecoder().decode(candidate), Charsets.UTF_8)
        // Only take it if it reads as text: no control characters beyond line breaks and tabs,
        // and no replacement characters from bytes that were never UTF-8.
        if (decoded.isEmpty() || decoded.any { (it < ' ' && it != '\n' && it != '\t') || it == '�' }) {
            null
        } else {
            decoded.replace("⁠", "").trim().ifEmpty { null }
        }
    }.getOrNull()

    /**
     * The value of a string field in JSON that may be truncated: everything after `"name":"` up to
     * the closing quote, or to the end when the cut came first. Common escapes are undone.
     */
    private fun looseJsonString(name: String, json: String): String? {
        // `"text":"` with or without whitespace around the colon - the server has been seen
        // re-serializing the envelope with spaces.
        val match = Regex("\"" + Regex.escape(name) + "\"\\s*:\\s*\"").find(json) ?: return null
        val out = StringBuilder()
        var escaped = false
        for (c in json.substring(match.range.last + 1)) {
            when {
                escaped -> {
                    out.append(
                        when (c) {
                            'n' -> '\n'
                            't' -> '\t'
                            else -> c
                        }
                    )
                    escaped = false
                }
                c == '\\' -> escaped = true
                c == '"' -> break
                else -> out.append(c)
            }
        }
        return out.toString().trim().ifEmpty { null }
    }
}
