package com.kachat.app.util

import android.content.Context
import com.google.gson.JsonParser
import com.kachat.app.R

/**
 * The words [dmNotificationText] uses that come from string resources. The rest of a 1:1
 * banner's wording is still English, as before. [ENGLISH] is what tests use.
 */
data class DmNotificationLabels(
    val sentVideo: String,
    val sentFile: String,
    val newMessage: String,
    val receivedPayment: String = "Received payment",
) {
    companion object {
        val ENGLISH = DmNotificationLabels(sentVideo = "Sent a video", sentFile = "Sent a file", newMessage = "New message")

        fun from(context: Context) = DmNotificationLabels(
            sentVideo = context.getString(R.string.dm_notif_sent_video),
            sentFile = context.getString(R.string.dm_notif_sent_file),
            newMessage = context.getString(R.string.dm_notif_new_message),
            receivedPayment = context.getString(R.string.dm_notif_received_payment),
        )
    }
}

/**
 * The body of a 1:1 notification for a decrypted message - one rule for both banner sources: the
 * FCM push handler and the in-app poller (ChatRepository.processContextualMessage). The title is
 * already the sender's name, so none of these repeat it (iOS `ChatService.formatNotificationBody`).
 *
 * Every known envelope gets its placeholder. Any other file - a video, a PDF, anything - says
 * "Sent a video" / "Sent a file" as on iOS instead of showing its JSON with the base64 data in it
 * (audit AND-007). And whatever still looks like JSON after that is "New message": never the raw
 * envelope on a lock screen (iOS NotificationService).
 */
fun dmNotificationText(plaintext: String, labels: DmNotificationLabels = DmNotificationLabels.ENGLISH): String {
    // A payment_notice reads like a payment, not like its JSON envelope - and without an amount:
    // it is only what the sender claims, and the app checks it against the chain before the chat
    // shows one (iOS paymentNoticePreviewText, IOS-080). One naming a payment to anything but our
    // chatting address never gets this far: both callers keep it silent (XP-023).
    if (PaymentPoolProtocol.parse(plaintext) is PaymentPoolProtocol.Envelope.Notice) return labels.receivedPayment
    CallCodec.parseOrNull(plaintext)?.let { return CallCodec.notificationPreview(it) }
    MessageReply.parseOrNull(plaintext)?.let { return "Replied to \"${it.replyToPreview}\"" }
    if (VoiceMessage.parseOrNull(plaintext) != null) return "Sent a voice message"
    if (ImageMessage.parseOrNull(plaintext) != null) return "Sent a photo"
    if (ChessMessage.parseOrNull(plaintext) != null) return "♟️ Chess game"
    VoiceMessage.parseAnyFileOrNull(plaintext)?.let { file ->
        val mime = file.mimeType.lowercase()
        return when {
            mime.startsWith("video/") -> labels.sentVideo
            mime.startsWith("image/") -> "Sent a photo"
            mime.startsWith("audio/") -> "Sent a voice message"
            else -> labels.sentFile
        }
    }
    if (looksLikeJsonEnvelope(plaintext)) return labels.newMessage
    return plaintext
}

/**
 * Whether [text] is a JSON envelope rather than words someone typed: it starts `{"` (an envelope,
 * even one cut short), or it is a whole JSON object. `{hi}` or `{ not json` stay text.
 */
internal fun looksLikeJsonEnvelope(text: String): Boolean {
    val trimmed = text.trim()
    if (!trimmed.startsWith("{")) return false
    if (trimmed.startsWith("{\"")) return true
    return try {
        JsonParser.parseString(trimmed).isJsonObject && trimmed.endsWith("}")
    } catch (e: Exception) {
        false
    }
}
