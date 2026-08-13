package com.kachat.app.services

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.kachat.app.models.ContactNotificationMode
import com.kachat.app.repository.ChatRepository
import com.kachat.app.util.ChessMessage
import com.kachat.app.util.ImageMessage
import com.kachat.app.util.KasiaCipher
import com.kachat.app.util.MessageProtocol
import com.kachat.app.util.MessageReaction
import com.kachat.app.util.MessageReply
import com.kachat.app.util.VoiceMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import java.util.Base64
import javax.inject.Inject

/**
 * Receives FCM messages and turns them into notifications via [NotificationHelper].
 *
 * Two shapes arrive from the server (`kasia-indexer` `push.rs`):
 *  - **Public content** (broadcast / KaPosts) is sent WITH a `notification` block, so the OS shows
 *    it even when the app is dead; `onMessageReceived` also runs when the app is alive.
 *  - **Encrypted DM/group content** is sent **data-only** (no `notification` block) so this handler
 *    runs and can DECRYPT the body locally with the wallet key — mirroring iOS's Notification
 *    Service Extension. (Data-only needs the app wake-able; a force-closed app under OEM battery
 *    optimization may not fire — set the app's battery usage to Unrestricted.)
 *
 * Data schema:
 *   broadcast : type, channel, title, subtitle, body, thread_id, tx_id
 *   kaposts   : type, title, subtitle, body, thread_id, tx_id, [post_id]
 *   chat/DM   : type(contextual|payment|handshake|group_message|group_control), sender, title,
 *               body, tx_id, timestamp, daa_score, [amount], [enc_payload], [blinded_group_id]
 */
@AndroidEntryPoint
class KaChatFirebaseMessagingService : FirebaseMessagingService() {

    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var pushRegistrationManager: PushRegistrationManager
    @Inject lateinit var walletManager: WalletManager
    @Inject lateinit var chatRepository: ChatRepository

    override fun onNewToken(token: String) {
        // FCM rotated the token — re-register it with the indexer (signed with the wallet key).
        Log.i(TAG, "FCM token rotated, re-registering with the push service")
        pushRegistrationManager.onTokenRefreshed(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data.isEmpty()) {
            Log.d(TAG, "push received with empty data payload, ignoring")
            return
        }

        val type = data["type"].orEmpty()
        val title = data["title"].orEmpty()
        val body = data["body"].orEmpty()
        Log.i(TAG, "push received: type=$type txId=${data["tx_id"].orEmpty().take(16)}")

        // NotificationHelper.show* are suspend and quick; block so the work completes before the
        // service is torn down.
        runBlocking {
            try {
                when (type) {
                    "broadcast" -> {
                        val channel = data["channel"] ?: return@runBlocking
                        notificationHelper.showBroadcast(
                            channelName = channel,
                            title = title.ifEmpty { "#$channel" },
                            text = body,
                        )
                    }

                    "kaposts" -> {
                        val txId = data["tx_id"].orEmpty()
                        notificationHelper.showKaPosts(text = body.ifEmpty { title }, actionTxId = txId)
                    }

                    "group_message", "group_control" -> {
                        // Group decryption is stateful (needs the local group seed), so keep the
                        // generic text for now.
                        val groupId = data["blinded_group_id"] ?: return@runBlocking
                        notificationHelper.showGroup(
                            groupId = groupId,
                            title = title.ifEmpty { "Group" },
                            text = body.ifEmpty { "New group message" },
                        )
                    }

                    "contextual" -> handleDirectMessage(data)

                    // Payment / handshake bodies are already meaningful ("Payment received",
                    // "Started a conversation") — no decryption needed.
                    "payment", "handshake" -> {
                        val sender = data["sender"] ?: return@runBlocking
                        notificationHelper.show(
                            contactId = sender,
                            title = contactTitle(sender, sender),
                            text = body.ifEmpty { "New message" },
                        )
                    }

                    else -> Log.d(TAG, "Ignoring push of unknown type: $type")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to handle push (type=$type): ${e.message}")
            }
        }
    }

    /**
     * 1:1 message: decrypt `enc_payload` locally (stateless ECDH — only needs our wallet key and
     * the ephemeral key embedded in the sealed blob) and show the real sender + text. Falls back to
     * the server's generic text on any failure (no wallet, tag mismatch, unknown format).
     */
    private suspend fun handleDirectMessage(data: Map<String, String>) {
        val sender = data["sender"] ?: return
        val fallback = data["body"].orEmpty().ifEmpty { "New message" }

        val plaintext = decryptDirectMessage(data["enc_payload"])
        if (plaintext != null && MessageReaction.parseOrNull(plaintext) != null) {
            // Reactions are never shown as their own notification (matches ChatRepository).
            return
        }

        val text = plaintext?.let { notificationPreview(it) } ?: fallback
        notificationHelper.show(
            contactId = sender,
            title = contactTitle(sender, data["title"].orEmpty().ifEmpty { sender }),
            text = text,
            notificationOverride = contactOverride(sender),
        )
    }

    /** Base64 → EncryptedMessage → ChaCha20-Poly1305 decrypt with the wallet key. Null on failure. */
    private fun decryptDirectMessage(encPayload: String?): String? {
        if (encPayload.isNullOrEmpty()) return null
        return try {
            // `enc_payload` is the base64 body of the sealed EncryptedMessage (the same base64 that
            // the on-chain `ciph_msg:1:comm:<alias>:<base64>` carries); the ephemeral pubkey + nonce
            // live inside those bytes, so no sender pubkey / handshake state is needed.
            val sealed = Base64.getDecoder().decode(encPayload)
            val encrypted = KasiaCipher.EncryptedMessage.fromBytes(sealed) ?: return null
            MessageProtocol.decrypt(encrypted, walletManager.getPrivateKeyBytes())
        } catch (e: Exception) {
            Log.d(TAG, "DM decrypt failed, using fallback text: ${e.message}")
            null
        }
    }

    /** Same preview mapping the in-app poller uses (ChatRepository) so text matches iOS wording. */
    private fun notificationPreview(plaintext: String): String {
        MessageReply.parseOrNull(plaintext)?.let { return "Replied to \"${it.replyToPreview}\"" }
        if (VoiceMessage.parseOrNull(plaintext) != null) return "Sent a voice message"
        if (ImageMessage.parseOrNull(plaintext) != null) return "Sent a photo"
        if (ChessMessage.parseOrNull(plaintext) != null) return "♟️ Chess game"
        return plaintext
    }

    private suspend fun contactTitle(senderId: String, default: String): String {
        val contact = runCatching { chatRepository.getContact(senderId) }.getOrNull()
        return contact?.alias ?: contact?.knsName ?: default.takeLast(12)
    }

    private suspend fun contactOverride(senderId: String): ContactNotificationMode? {
        val contact = runCatching { chatRepository.getContact(senderId) }.getOrNull()
        return ContactNotificationMode.fromName(contact?.notificationOverride)
    }

    companion object {
        // Same tag as PushRegistrationManager: `adb logcat -s KaChatPush` shows registrations,
        // token rotations, and every received push in one stream.
        private const val TAG = PushRegistrationManager.TAG
    }
}
