package com.kachat.app.util

import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/**
 * The group message a member sends to answer their invite (MESSAGING.md, "Group invites"; iOS
 * `GroupMemberStatusCodec`, bfe9f022): `{"type":"member_status","status":"joined"}`. Sealed and
 * signed like any group message, so it reads like one on chain, and never a bubble.
 */
object GroupMemberStatus {
    const val INVITED = "invited"
    const val JOINED = "joined"
    const val DECLINED = "declined"

    private const val TYPE = "member_status"

    private data class Envelope(val type: String? = null, val status: String? = null)

    private val gson = Gson()

    fun encode(status: String): String = gson.toJson(Envelope(type = TYPE, status = status))

    /** Only an answer: nobody can send themselves back to "invited". */
    fun parseOrNull(text: String?): String? {
        if (text == null || text.length > 4096 || text.firstOrNull() != '{') return null
        val envelope = try { gson.fromJson(text, Envelope::class.java) } catch (e: Exception) { null } ?: return null
        if (envelope.type != TYPE) return null
        return envelope.status?.takeIf { it == JOINED || it == DECLINED }
    }

    /**
     * A member's answer, never a bubble (MESSAGING.md). Only this type, as on iOS: someone typing
     * other JSON into a group still sees their message. A streaming read, because this also sees
     * every photo and voice message (megabytes of base64 after their `type`).
     */
    fun isHiddenEnvelope(text: String?): Boolean = envelopeType(text) == TYPE

    private fun envelopeType(text: String?): String? {
        if (text == null || text.firstOrNull { !it.isWhitespace() } != '{') return null
        return try {
            JsonReader(StringReader(text)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "type") {
                        return@use if (reader.peek() == JsonToken.STRING) reader.nextString() else null
                    }
                    reader.skipValue()
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
