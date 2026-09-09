package com.kachat.app.util

import com.google.gson.Gson

/**
 * A chat photo's on-chain representation — reuses [VoiceMessageContent] as-is rather than a
 * duplicate data class, since its shape (`type`, `name`, `size`, `mimeType`, `content` data-URI)
 * is already a generic media envelope with nothing audio-specific except the default `mimeType`.
 * Mirrors [VoiceMessage] exactly, just filtering on an "image" mimeType prefix instead of "audio" —
 * same embed-directly-in-the-encrypted-comm-payload approach, no upload endpoint, no new wire type.
 */
object ImageMessage {
    private val gson = Gson()

    fun encode(fileName: String, sizeBytes: Long, base64Image: String, mimeType: String = "image/jpeg"): String {
        return gson.toJson(
            VoiceMessageContent(
                name = fileName,
                size = sizeBytes,
                mimeType = mimeType,
                content = "data:$mimeType;base64,$base64Image"
            )
        )
    }

    /** Parses [text] as an image message if it looks like one, else null — same broad-catch shape as [VoiceMessage.parseOrNull]. */
    fun parseOrNull(text: String?): VoiceMessageContent? {
        if (text.isNullOrBlank() || text.trimStart().firstOrNull() != '{') return null
        return try {
            val parsed = gson.fromJson(text, VoiceMessageContent::class.java) ?: return null
            if (parsed.mimeType.startsWith("image/") && parsed.content.startsWith("data:")) parsed else null
        } catch (e: Exception) {
            // Same reflection/non-null-default caveat as VoiceMessage.parseOrNull. Logs the real
            // exception + a shape summary (never the full payload — could be tens of KB) so a
            // real-world parse failure (e.g. a truncated/corrupted message from another client)
            // is actually diagnosable instead of just falling back to a raw-text bubble with no
            // trace of why. In particular, whether the tail looks like valid JSON (ends `"}`) is
            // the fastest way to tell a truncated payload from a genuine structural mismatch.
            // try/catch around the log call itself: android.util.Log isn't mocked in plain JUnit
            // tests (throws instead of no-op'ing), and this same catch block above is legitimately
            // hit for perfectly ordinary non-file JSON text (e.g. a user just typing `{"a":"b"}`),
            // so it has to stay harmless there too, not just in tests.
            try {
                android.util.Log.w(
                    "ImageMessage",
                    "parseOrNull failed: ${e.javaClass.simpleName}: ${e.message} | len=${text.length} tail=${text.takeLast(20)}"
                )
            } catch (loggingFailure: Throwable) {
                // Ignored — see comment above.
            }
            null
        }
    }

    /** The raw base64 image payload, stripped of its "data:<mime>;base64," prefix. */
    fun base64Payload(imageContent: VoiceMessageContent): String = VoiceMessage.base64Payload(imageContent)
}

/**
 * Extracts an inline-media payload's `mimeType` by scanning only the payload HEAD.
 *
 * The parsers above deserialize the ENTIRE payload, and for an inline photo or voice message that
 * is a `data:` URL holding tens of kilobytes of base64. The chat list called five of them in a
 * row - reply, voice, image, chess, file - for every visible row, on every recomposition, just to
 * decide what one line of preview text should say. On an account with a hundred and thirty chats
 * that is the difference between a list that scrolls and one that does not.
 *
 * App-generated media JSON always carries `mimeType` near the front; 2KB comfortably covers it.
 * Mirrors iOS's `InlineMediaSniff`, including its two hard-won cases: a sender that built the
 * envelope from a map may serialize `content` BEFORE `mimeType`, pushing the mime past the head
 * window - but the `data:` URL names the mime up front anyway - and JSON escapes "/" as "\/", so
 * the extracted value has to be unescaped before any `startsWith("image/")` check can match.
 */
object InlineMediaSniff {
    private const val HEAD = 2048

    fun mimeType(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        val head = text.take(HEAD).trimStart()
        if (head.firstOrNull() != '{') return null
        valueAfterKey(head, "\"mimeType\"")?.let { return it }
        // Fallback: read it off the data: URL, which names the mime before the payload begins.
        val dataUrl = head.indexOf("data:")
        if (dataUrl >= 0) {
            val semicolon = head.indexOf(';', dataUrl)
            if (semicolon > dataUrl) return unescape(head.substring(dataUrl + 5, semicolon))
        }
        return null
    }

    private fun valueAfterKey(head: String, key: String): String? {
        val keyAt = head.indexOf(key)
        if (keyAt < 0) return null
        val colon = head.indexOf(':', keyAt + key.length)
        if (colon < 0) return null
        var i = colon + 1
        while (i < head.length && head[i] == ' ') i++
        if (i >= head.length || head[i] != '"') return null
        val end = head.indexOf('"', i + 1)
        if (end < 0) return null
        return unescape(head.substring(i + 1, end))
    }

    private fun unescape(value: String): String = value.replace("\\/", "/")
}
