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
                        // Prefer the containing post's id when the server sends one; fall back to
                        // the action txid so the tap still deep-opens something relevant.
                        notificationHelper.showKaPosts(
                            text = body.ifEmpty { title },
                            actionTxId = txId,
                            postTxId = data["content_id"]?.takeIf { it.isNotBlank() } ?: txId.takeIf { it.isNotBlank() },
                        )
                    }

                    "group_message" -> {
                        // Group decryption is stateful (needs the local group seed + a ciphertext
                        // fetch), so keep generic text for now — the notification still fires.
                        val groupId = data["blinded_group_id"] ?: return@runBlocking
                        notificationHelper.showGroup(
                            groupId = groupId,
                            title = title.ifEmpty { "Group" },
                            text = body.ifEmpty { "New group message" },
                        )
                    }

                    "group_control" -> {
                        // "You were added to a group" / group update. These carry no
                        // blinded_group_id, so key the notification on the tx id instead.
                        val key = data["blinded_group_id"] ?: data["tx_id"] ?: "group"
                        notificationHelper.showGroup(
                            groupId = key,
                            title = title.ifEmpty { "Group" },
                            text = body.ifEmpty { "Group update" },
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
        // Media/large messages exceed FCM's 4KB cap, so the server can't attach the encrypted body
        // (enc_payload absent) — the server's generic body is used for those. Small text messages
        // carry enc_payload and are decrypted here for the real preview.
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

    /** Base64 → EncryptedMessage → ChaCha20-Poly1305 decrypt with the wallet key. Null on failure
     * (no encrypted body attached, e.g. an oversized/media message; or a tag/key mismatch). */
    private fun decryptDirectMessage(encPayload: String?): String? {
        if (encPayload.isNullOrEmpty()) return null
        return try {
            val sealed = Base64.getDecoder().decode(encPayload)
            val encrypted = KasiaCipher.EncryptedMessage.fromBytes(sealed) ?: return null
            MessageProtocol.decrypt(encrypted, walletManager.getPrivateKeyBytes())
        } catch (e: Exception) {
            Log.d(TAG, "DM decrypt failed, using fallback: ${e.message}")
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
