package com.kachat.app.util

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * The binary media frame (iOS MESSAGING.md "Photos and voice notes: the binary media frame",
 * iOS `ContextualMediaFrame` in KaChatCipher.swift): a 1:1 photo or voice note travels as its RAW
 * bytes inside the encryption, not as base64 text inside JSON - the media-file JSON was base64'd
 * twice (once into its data URL, then the whole ciphertext into the payload), so about 40% of
 * what went on chain was encoding.
 *
 *   0xFF 'K' 'C' 'M' 0x01 | u32 big-endian header length | header JSON | raw file bytes
 *   header JSON: {"mimeType":"image/jpeg","name":"photo.jpg","size":12345}
 *
 * 0xFF can never begin UTF-8 text, so a frame is told apart from every text message without
 * ambiguity. On receipt it becomes exactly the media-file JSON the text form carried (iOS
 * `MediaFileEnvelope.json`), so storage, display, dedupe and backups are unchanged.
 */
object ContextualMediaFrame {
    val MAGIC = byteArrayOf(0xFF.toByte(), 0x4B, 0x43, 0x4D, 0x01)
    private const val LENGTH_BYTES = 4

    private val jsonElementAdapter = Gson().getAdapter(JsonElement::class.java)

    /**
     * The frame for a media-file envelope whose content is a base64 data URL of an image or
     * audio type; null for anything else, which is sent as UTF-8 text as before. Mirrors iOS
     * `ContextualMediaFrame.frame(forEnvelope:)` - including its header, which iOS writes with
     * JSONSerialization's sorted keys (`mimeType, name, size`, "/" as "\/").
     */
    fun frameForEnvelope(text: String): ByteArray? {
        if (!text.startsWith("{") || !text.contains("\"type\":\"file\"")) return null
        val envelope = parseObject(text) ?: return null
        if (envelope.stringField("type") != "file") return null
        val content = envelope.stringField("content") ?: return null
        if (!content.startsWith("data:")) return null
        val comma = content.indexOf(',').takeIf { it >= 0 } ?: return null
        val meta = content.substring(5, comma)
        if (!meta.endsWith(";base64")) return null
        val mimeType = meta.dropLast(";base64".length)
        val lowered = mimeType.lowercase()
        if (!lowered.startsWith("image/") && !lowered.startsWith("audio/")) return null
        val base64 = content.substring(comma + 1)
        // Foundation's Data(base64Encoded:) wants the padding; java.util.Base64 would let it go.
        if (base64.length % 4 != 0) return null
        val raw = try { Base64.getDecoder().decode(base64) } catch (e: IllegalArgumentException) { return null }
        if (raw.isEmpty()) return null
        val name = envelope.stringField("name") ?: "file"
        val header = "{\"mimeType\":${jsonString(mimeType)},\"name\":${jsonString(name)},\"size\":${raw.size}}"
            .toByteArray(Charsets.UTF_8)
        val length = header.size
        val lengthBytes = byteArrayOf((length ushr 24).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte())
        return MAGIC + lengthBytes + header + raw
    }

    /** A JSON string the way Foundation's JSONSerialization writes one: `"`, `\` and `/`
     *  escaped, control characters as their short or `\u00xx` form, everything else as is. */
    private fun jsonString(value: String): String {
        val out = StringBuilder(value.length + 2).append('"')
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '/' -> out.append("\\/")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        return out.append('"').toString()
    }

    /** True when [data] begins with 0xFF - a frame, or bytes no UTF-8 text can start with. */
    fun startsWithFrameByte(data: ByteArray): Boolean = data.isNotEmpty() && data[0] == MAGIC[0]

    /**
     * The media-file JSON a frame stands for; null when [data] is not a well-formed frame
     * (no magic, a header length past the end, a header that is not a JSON object with a
     * `mimeType`, or no file bytes). Mirrors iOS `ContextualMediaFrame.envelopeText(from:)`.
     */
    fun envelopeText(data: ByteArray): String? {
        val headerStart = MAGIC.size + LENGTH_BYTES
        if (data.size <= headerStart) return null
        for (i in MAGIC.indices) if (data[i] != MAGIC[i]) return null
        // Unsigned: a u32 can exceed Int.MAX_VALUE, so it is read and bounds-checked as a Long.
        var headerLength = 0L
        for (i in MAGIC.size until headerStart) headerLength = (headerLength shl 8) or (data[i].toLong() and 0xFF)
        if (headerLength <= 0L || headerStart + headerLength > data.size) return null
        val headerEnd = headerStart + headerLength.toInt()
        val header = parseHeader(data, headerStart, headerEnd) ?: return null
        val mimeType = header.stringField("mimeType")?.takeIf { it.isNotEmpty() } ?: return null
        if (headerEnd >= data.size) return null
        val raw = data.copyOfRange(headerEnd, data.size)
        return mediaFileJson(
            name = header.stringField("name") ?: "file",
            size = raw.size,
            mimeType = mimeType,
            dataUrlContent = "data:$mimeType;base64,${Base64.getEncoder().encodeToString(raw)}",
        )
    }

    /**
     * iOS `MediaFileEnvelope.json`, byte for byte: `type, name, size, mimeType, content` in that
     * order, only `\` and `"` escaped (no "\/", non-ASCII written as is), so a message that came
     * as a frame is the same string as one that came as text.
     */
    fun mediaFileJson(name: String, size: Int, mimeType: String, dataUrlContent: String): String =
        "{\"type\":\"file\",\"name\":\"${escape(name)}\",\"size\":$size,\"mimeType\":\"${escape(mimeType)}\",\"content\":\"${escape(dataUrlContent)}\"}"

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    /** The header as a JSON object - strict UTF-8 and strict JSON (one object, nothing after
     *  it), like iOS's JSONSerialization; null for anything else. [parseObject] alike for text. */
    private fun parseHeader(data: ByteArray, start: Int, end: Int): Map<String, JsonElement>? = try {
        parseObject(
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data, start, end - start))
                .toString()
        )
    } catch (e: Exception) {
        null
    }

    private fun parseObject(text: String): Map<String, JsonElement>? = try {
        val reader = JsonReader(StringReader(text)).apply { isLenient = false }
        val element = jsonElementAdapter.read(reader)
        if (reader.peek() != JsonToken.END_DOCUMENT || element == null || !element.isJsonObject) null
        else element.asJsonObject.entrySet().associate { it.key to it.value }
    } catch (e: Exception) {
        null
    }

    private fun Map<String, JsonElement>.stringField(key: String): String? =
        this[key]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}
