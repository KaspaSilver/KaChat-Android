package com.kachat.app.util

import com.google.gson.Gson

/**
 * A voice message's on-chain representation — the entire encoded audio is embedded as base64
 * directly in the same encrypted `ciph_msg:1:comm:` payload used for plain text (no upload
 * endpoint, no separate wire type). Field shape matches iOS's `MediaFile`/inline JSON exactly
 * (`ChatService+Conversations.swift:942-972`, `MessageBubbleView.swift:630-738`), so a voice
 * message recorded on one platform decodes and plays on the other.
 */
data class VoiceMessageContent(
    val type: String = "file",
    val name: String,
    val size: Long,
    val mimeType: String = "audio/webm",
    val content: String // "data:audio/webm;base64,<...>"
)

object VoiceMessage {
    private val gson = Gson()

    fun encode(fileName: String, sizeBytes: Long, base64Audio: String, mimeType: String = "audio/webm"): String {
        return gson.toJson(
            VoiceMessageContent(
                name = fileName,
                size = sizeBytes,
                mimeType = mimeType,
                content = "data:$mimeType;base64,$base64Audio"
            )
        )
    }

    /**
     * Any `{type:"file"}` media envelope regardless of mimeType — the chat-list previews'
     * last-resort catch (video, documents, or an envelope from another client whose mime the
     * audio/image parsers don't claim), so raw JSON never leaks into a preview row.
     */
    fun parseAnyFileOrNull(text: String?): VoiceMessageContent? {
        if (text.isNullOrBlank() || text.trimStart().firstOrNull() != '{') return null
        return try {
            val parsed = gson.fromJson(text, VoiceMessageContent::class.java) ?: return null
            if (parsed.type == "file" && parsed.content.startsWith("data:")) parsed else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parses [text] as a voice message if it looks like one, else returns null — a plain text
     * message never accidentally renders as an audio bubble just because it happens to start
     * with `{`, since this also requires an audio `mimeType` and a `data:` URI in `content`.
     */
    fun parseOrNull(text: String?): VoiceMessageContent? {
        if (text.isNullOrBlank() || text.trimStart().firstOrNull() != '{') return null
        return try {
            val parsed = gson.fromJson(text, VoiceMessageContent::class.java) ?: return null
            if (parsed.mimeType.startsWith("audio/") && parsed.content.startsWith("data:")) parsed else null
        } catch (e: Exception) {
            // Gson's reflection-based Kotlin deserialization doesn't honor non-null defaults for
            // JSON keys that are simply absent — a field declared non-null String can still come
            // back null at runtime, throwing an NPE on the checks above rather than a
            // JsonSyntaxException, so this has to catch broadly, not just parse errors. Logs the
            // real exception + a shape summary (never the full payload) so a real-world parse
            // failure is actually diagnosable instead of silently falling back to plain text.
            // try/catch around the log call itself: android.util.Log isn't mocked in plain JUnit
            // tests (throws instead of no-op'ing), and this same catch block above is legitimately
            // hit for perfectly ordinary non-file JSON text, so it has to stay harmless there too.
            try {
                android.util.Log.w(
                    "VoiceMessage",
                    "parseOrNull failed: ${e.javaClass.simpleName}: ${e.message} | len=${text.length} tail=${text.takeLast(20)}"
                )
            } catch (loggingFailure: Throwable) {
                // Ignored — see comment above.
            }
            null
        }
    }

    /** The raw base64 audio payload, stripped of its "data:<mime>;base64," prefix. */
    fun base64Payload(voiceContent: VoiceMessageContent): String {
        val comma = voiceContent.content.indexOf(',')
        return if (comma == -1) "" else voiceContent.content.substring(comma + 1)
    }

    /** "0:07", "1:03" — matches the mm:ss style used by the reveal/inscribe progress rows elsewhere in this app. */
    fun formatDuration(durationMs: Int): String {
        val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }

    /**
     * Rough estimate of the final on-chain wire payload size for a voice message that's still
     * being recorded, given elapsed recording time — used only for a live "fee so far" preview
     * while recording (the real send always measures the actual encoded bytes exactly, this is
     * never used for anything that determines a real fee). Calibrated against a real sent voice
     * message: a recording close to the app's 10-second cap at its fixed 6000bps/48kHz Opus
     * settings produced a 28,729-byte final wire payload (JSON wrapper + base64 audio, encrypted,
     * then base64'd again for the comm payload, plus the "ciph_msg:1:comm:<alias>:" prefix) —
     * roughly 2870 bytes of final payload per second of recording.
     */
    fun estimatedWirePayloadSize(elapsedMs: Long): Int {
        val elapsedSeconds = elapsedMs / 1000.0
        return (BASE_OVERHEAD_BYTES + elapsedSeconds * BYTES_PER_SECOND_OF_RECORDING).toInt()
    }

    private const val BASE_OVERHEAD_BYTES = 150
    private const val BYTES_PER_SECOND_OF_RECORDING = 2870.0
}
