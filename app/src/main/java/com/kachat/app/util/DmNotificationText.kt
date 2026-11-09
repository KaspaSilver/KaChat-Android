package com.kachat.app.util

import android.content.Context
import com.google.gson.JsonParser
import com.kachat.app.R

/**
 * The words [dmNotificationText] uses that come from string resources. [ENGLISH] is what tests use.
 */
data class DmNotificationLabels(
    val sentVideo: String,
    val sentFile: String,
    val newMessage: String,
    val receivedPayment: String = "Received payment",
    val paymentRequest: String = "Requested for payment",
    val sentVoice: String = "Sent a voice message",
    val sentPhoto: String = "Sent a photo",
    val chessGame: String = "♟️ Chess game",
) {
    companion object {
        val ENGLISH = DmNotificationLabels(sentVideo = "Sent a video", sentFile = "Sent a file", newMessage = "New message")

        fun from(context: Context) = DmNotificationLabels(
            sentVideo = context.getString(R.string.dm_notif_sent_video),
            sentFile = context.getString(R.string.dm_notif_sent_file),
            newMessage = context.getString(R.string.dm_notif_new_message),
            receivedPayment = context.getString(R.string.dm_notif_received_payment),
            paymentRequest = context.getString(R.string.dm_notif_payment_request),
            sentVoice = context.getString(R.string.dm_notif_sent_voice),
            sentPhoto = context.getString(R.string.dm_notif_sent_photo),
            chessGame = context.getString(R.string.chess_game_2),
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
    // A reply says its own words, or "Sent a photo" when it is one, never what it quotes (iOS
    // MessageReplyCodec.unwrappedText in formatNotificationBody, NotificationService.unwrapReplyText).
    MessageReply.parseOrNull(plaintext)?.let { reply -> return dmNotificationText(reply.text, labels) }
    if (VoiceMessage.parseOrNull(plaintext) != null) return labels.sentVoice
    if (ImageMessage.parseOrNull(plaintext) != null) return labels.sentPhoto
    if (ChessMessage.parseOrNull(plaintext) != null) return labels.chessGame
    VoiceMessage.parseAnyFileOrNull(plaintext)?.let { file ->
        val mime = file.mimeType.lowercase()
        return when {
            mime.startsWith("video/") -> labels.sentVideo
            mime.startsWith("image/") -> labels.sentPhoto
            mime.startsWith("audio/") -> labels.sentVoice
            else -> labels.sentFile
        }
    }
    if (looksLikeJsonEnvelope(plaintext)) return labels.newMessage
    // The chat shows a payment request link as a card, so the banner says what it is instead of
    // printing the link (iOS bfe9f022 NotificationService.paymentRequestPreviewText).
    if (holdsPaymentRequestLink(plaintext)) return labels.paymentRequest
    return plaintext
}

/** A message holding a payment request link, in either of its forms (kachat:// and kachat.app). */
internal fun holdsPaymentRequestLink(text: String): Boolean {
    val lowered = text.lowercase()
    return (lowered.contains("kachat://pay/") || lowered.contains("kachat.app/pay/")) && lowered.contains("amount=")
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
