package com.kachat.app.util

import java.util.Base64

/**
 * Turns the body a public-room push arrives with into something a person can read.
 *
 * The push server builds that body from the room message, and has been seen sending it on raw:
 * a reply's whole JSON envelope, the same envelope cut off at the server's preview length so it
 * no longer parses, or the message still base64-encoded. Each of those reached the notification
 * as gibberish. This undoes all three and otherwise leaves the text alone. Mirrors iOS's
 * notification service extension (iOS 8c5af1a).
 */
object BroadcastPushPreview {

    fun clean(body: String): String {
        var text = body.trim()

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

        // Still JSON: most likely a reply envelope cut off at the server's preview length, so it
        // no longer parses. Pull the reply's own text out of what is there.
        if (text.contains("\"type\":\"reply\"")) looseJsonString("text", text)?.let { return it }
        return text
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
        val marker = "\"$name\":\""
        val start = json.indexOf(marker).takeIf { it >= 0 } ?: return null
        val out = StringBuilder()
        var escaped = false
        for (c in json.substring(start + marker.length)) {
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
