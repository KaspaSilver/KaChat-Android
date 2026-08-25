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
    @Inject lateinit var groupRepository: com.kachat.app.repository.GroupRepository

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
                            // Collapses with the live block scan's banner when the app is
                            // foregrounded and both paths see the same message.
                            dedupeTxId = data["tx_id"]?.takeIf { it.isNotBlank() },
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
                        // fetch), so a push can't be decrypted inline the way a DM's enc_payload
                        // can. Instead, run the normal indexer catch-up sync: it fetches the
                        // ciphertext, decrypts it, and posts the PRECISE banner itself through
                        // NotificationHelper ("Alice: hi", "Alice reacted 👍 to your message") —
                        // or deliberately stays silent (e.g. a reaction to someone else's
                        // message, a muted member). Only when the sync could not ingest the tx
                        // at all (indexer lag, no network) does the generic fallback fire.
                        val groupId = data["blinded_group_id"] ?: return@runBlocking
                        val txId = data["tx_id"].orEmpty()
                        val ingested = try {
                            groupRepository.syncGroups()
                            txId.isNotBlank() && groupRepository.isGroupTxIngested(txId)
                        } catch (e: Exception) {
                            Log.w(TAG, "Push-triggered group sync failed: ${e.message}")
                            false
                        }
                        if (!ingested) {
                            // The generic fallback can't know whether the un-ingested message
                            // mentions the user, so in a group with "Only Notify if I'm
                            // Mentioned" on it can't be posted correctly. Resolve the per-sender
                            // blinded id back to the local group and consult the toggle:
                            // mentions-only means suppress. Tradeoff: a missed banner for a
                            // non-mention is exactly what the toggle asks for, while a missed
                            // banner for an actual mention is the cost of the ingest failure;
                            // the message itself still lands on the next successful sync, and if
                            // this tx ingests later the precise local path still banners the
                            // mention (which is why the txId is deliberately NOT claimed here).
                            // When the blinded id matches no local group the toggle can't be
                            // consulted, so the generic banner fires as before.
                            val resolvedGroup = try {
                                groupRepository.findGroupByBlindedId(groupId)
                            } catch (e: Exception) {
                                null
                            }
                            if (resolvedGroup != null && groupRepository.isGroupMentionsOnly(resolvedGroup.groupId)) {
                                Log.i(TAG, "Generic group fallback suppressed: mentions-only group ${resolvedGroup.groupId.take(12)}")
                            } else {
                                notificationHelper.showGroup(
                                    // Prefer the resolved real group id/name: the tap intent
                                    // then opens the actual thread and the open-thread
                                    // suppression keys correctly.
                                    groupId = resolvedGroup?.groupId ?: groupId,
                                    title = resolvedGroup?.name ?: title.ifEmpty { "Group" },
                                    text = body.ifEmpty { "New group message" },
                                    dedupeTxId = txId.takeIf { it.isNotBlank() },
                                )
                            }
                        }
                    }

                    "group_control" -> {
                        // "You were added to a group" / group update. These carry no
                        // blinded_group_id, so key the notification on the tx id instead.
                        val key = data["blinded_group_id"] ?: data["tx_id"] ?: "group"
                        notificationHelper.showGroup(
                            groupId = key,
                            title = title.ifEmpty { "Group" },
                            text = body.ifEmpty { "Group update" },
                            dedupeTxId = data["tx_id"]?.takeIf { it.isNotBlank() },
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
                            dedupeTxId = data["tx_id"]?.takeIf { it.isNotBlank() },
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
            // Collapses with the in-app poll's banner when the app is foregrounded and both
            // paths see the same message.
            dedupeTxId = data["tx_id"]?.takeIf { it.isNotBlank() },
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
