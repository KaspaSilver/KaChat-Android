package com.kachat.app.util

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

/**
 * Calls ring through the chat itself: `call_invite`, `call_response` and `call_end` are
 * ordinary encrypted 1:1 messages sharing a `callId`, JSON like the chess envelopes. Clients
 * render them as call-history bubbles ("Voice call started", "Missed call", "Call · 4:12") and
 * never as raw JSON. Byte-compatible with iOS's `CallCodec` - see MESSAGING.md "Calls".
 */
sealed class CallEnvelope {
    abstract val callId: String

    /** A phone WITHOUT Nextcloud starting a call: it asks the contact to host. If the contact's
     *  KaChat can host and their "Allow calls" switch is on for us, it opens the room and answers
     *  with an [Invite] carrying this callId (`viaRequest`). */
    data class Request(override val callId: String, val video: Boolean) : CallEnvelope()

    /** The host has opened a Nextcloud Talk conversation and invites the other side into it.
     *  `server` + `token` are everything needed to join as a Talk guest. [viaRequest] is true
     *  when this answers the other side's [Request] - they join at once instead of ringing, and
     *  both chats render it as a neutral "call ready" line. */
    data class Invite(override val callId: String, val server: String, val token: String, val video: Boolean, val viaRequest: Boolean = false) : CallEnvelope()

    /** The callee's answer; `accepted == false` is a decline (or busy). [reason] "no_host"
     *  answers a [Request] the contact cannot host either. */
    data class Response(override val callId: String, val accepted: Boolean, val reason: String? = null) : CallEnvelope()

    /** Either side hung up, or the caller gave up ringing. `reason`: hangup, cancelled,
     *  no_answer, failed. `durationSeconds` when the call connected. */
    data class End(override val callId: String, val reason: String?, val durationSeconds: Int?) : CallEnvelope()
}

object CallCodec {
    private val gson = Gson()

    fun encode(request: CallEnvelope.Request): String = JsonObject().apply {
        addProperty("type", "call_request")
        addProperty("callId", request.callId)
        addProperty("video", request.video)
    }.let { gson.toJson(it) }

    fun encode(invite: CallEnvelope.Invite): String = JsonObject().apply {
        addProperty("type", "call_invite")
        addProperty("callId", invite.callId)
        addProperty("server", invite.server)
        addProperty("token", invite.token)
        addProperty("video", invite.video)
        if (invite.viaRequest) addProperty("viaRequest", true)
    }.let { gson.toJson(it) }

    fun encode(response: CallEnvelope.Response): String = JsonObject().apply {
        addProperty("type", "call_response")
        addProperty("callId", response.callId)
        addProperty("accepted", response.accepted)
        response.reason?.let { addProperty("reason", it) }
    }.let { gson.toJson(it) }

    fun encode(end: CallEnvelope.End): String = JsonObject().apply {
        addProperty("type", "call_end")
        addProperty("callId", end.callId)
        end.reason?.let { addProperty("reason", it) }
        end.durationSeconds?.let { addProperty("durationSeconds", it) }
    }.let { gson.toJson(it) }

    /** Same `{`-prefixed fast path as the chess codec - this runs from bubble bodies and
     *  previews on every render, and almost every message is not a call. */
    fun parseOrNull(text: String?): CallEnvelope? {
        if (text == null || text.length > 4_096) return null
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") || !trimmed.contains("\"call_")) return null
        return try {
            val obj = JsonParser.parseString(trimmed).asJsonObject
            val callId = obj.get("callId")?.asString ?: return null
            when (obj.get("type")?.asString) {
                "call_request" -> CallEnvelope.Request(callId, video = obj.get("video")?.asBoolean ?: false)
                "call_invite" -> CallEnvelope.Invite(
                    callId = callId,
                    server = obj.get("server")?.asString ?: return null,
                    token = obj.get("token")?.asString ?: return null,
                    video = obj.get("video")?.asBoolean ?: false,
                    viaRequest = obj.get("viaRequest")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                )
                "call_response" -> CallEnvelope.Response(
                    callId,
                    accepted = obj.get("accepted")?.asBoolean ?: false,
                    reason = obj.get("reason")?.takeIf { !it.isJsonNull }?.asString,
                )
                "call_end" -> CallEnvelope.End(
                    callId,
                    reason = obj.get("reason")?.takeIf { !it.isJsonNull }?.asString,
                    durationSeconds = obj.get("durationSeconds")?.takeIf { !it.isJsonNull }?.asInt,
                )
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun clock(seconds: Int): String = String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)

    /** The chat list row (iOS ChatListView.formatPreview). */
    fun listPreview(envelope: CallEnvelope): String = when (envelope) {
        is CallEnvelope.Request -> if (envelope.video) "📹 Video call" else "📞 Voice call"
        is CallEnvelope.Invite -> if (envelope.video) "📹 Video call" else "📞 Voice call"
        is CallEnvelope.Response -> when {
            envelope.reason == "no_host" -> "📞 Calls need Nextcloud Talk"
            envelope.accepted -> "📞 Call answered"
            else -> "📞 Call declined"
        }
        is CallEnvelope.End -> {
            val seconds = envelope.durationSeconds
            if (seconds != null && seconds > 0) "📞 Call · ${clock(seconds)}"
            else if (envelope.reason == "no_answer" || envelope.reason == "cancelled") "📞 Missed call"
            else "📞 Call ended"
        }
    }

    /** The notification body (iOS formatNotificationBody / the NSE's callPreviewText). */
    fun notificationPreview(envelope: CallEnvelope): String = when (envelope) {
        is CallEnvelope.Request -> if (envelope.video) "📹 Incoming video call" else "📞 Incoming voice call"
        is CallEnvelope.Invite -> when {
            envelope.viaRequest -> if (envelope.video) "📹 Video call ready" else "📞 Voice call ready"
            envelope.video -> "📹 Incoming video call"
            else -> "📞 Incoming voice call"
        }
        is CallEnvelope.Response -> when {
            envelope.reason == "no_host" -> "📞 Calls need Nextcloud Talk on one side"
            envelope.accepted -> "📞 Answered your call"
            else -> "📞 Declined your call"
        }
        is CallEnvelope.End -> {
            val seconds = envelope.durationSeconds
            if (seconds != null && seconds > 0) "📞 Call · ${clock(seconds)}"
            else if (envelope.reason == "no_answer" || envelope.reason == "cancelled") "📞 Missed call"
            else "📞 Call ended"
        }
    }

    /** The bubble's one line, and whether it reads as a missed / ended call (a "phone down"
     *  glyph) rather than a live one (iOS MessageBubbleView.callBubble). */
    fun bubbleText(envelope: CallEnvelope, isOutgoing: Boolean): Pair<String, Boolean> = when (envelope) {
        is CallEnvelope.Request -> {
            val kind = if (envelope.video) "Video call" else "Voice call"
            (if (isOutgoing) "$kind started" else "Incoming ${kind.lowercase(Locale.US)}") to false
        }
        is CallEnvelope.Invite -> {
            val kind = if (envelope.video) "Video call" else "Voice call"
            // The host's answer to the other side's request: the call already has its
            // "started" / "incoming" line from the request itself.
            if (envelope.viaRequest) "$kind ready" to false
            else (if (isOutgoing) "$kind started" else "Incoming ${kind.lowercase(Locale.US)}") to false
        }
        is CallEnvelope.Response -> when {
            envelope.reason == "no_host" -> "Calls need Nextcloud Talk on one side" to true
            envelope.accepted -> "Call answered" to false
            else -> "Call declined" to true
        }
        is CallEnvelope.End -> {
            val seconds = envelope.durationSeconds
            if (seconds != null && seconds > 0) "Call · ${clock(seconds)}" to false
            else when (envelope.reason) {
                "no_answer" -> (if (isOutgoing) "No answer" else "Missed call") to true
                "cancelled" -> (if (isOutgoing) "Call cancelled" else "Missed call") to true
                "failed" -> "Call failed" to true
                else -> "Call ended" to true
            }
        }
    }
}
