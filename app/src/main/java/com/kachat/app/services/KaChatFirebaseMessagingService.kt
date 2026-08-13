package com.kachat.app.services

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Receives FCM **data-only** messages and turns them into notifications via [NotificationHelper]
 * — the Android analogue of iOS's Notification Service Extension. The server (`kasia-indexer`)
 * always sends data messages (never a `notification` block) so this handler runs for every push
 * and keeps full control over channels, grouping, and tap-routing, exactly like the in-app poller.
 *
 * The data schema mirrors the server payloads built in `push.rs`:
 *   broadcast : type, channel, title, subtitle, body, thread_id, tx_id
 *   kaposts   : type, title, subtitle, body, thread_id, tx_id, [post_id]
 *   chat/DM   : type(contextual|payment|handshake|group_message|group_control), sender, title,
 *               body, tx_id, timestamp, daa_score, [amount], [enc_payload], [blinded_group_id]
 */
@AndroidEntryPoint
class KaChatFirebaseMessagingService : FirebaseMessagingService() {

    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var pushRegistrationManager: PushRegistrationManager

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

        // NotificationHelper.show* are suspend and quick (settings read + notify); block so the
        // work completes before the service is torn down.
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
                        val groupId = data["blinded_group_id"] ?: return@runBlocking
                        notificationHelper.showGroup(
                            groupId = groupId,
                            title = title.ifEmpty { "Group" },
                            text = body.ifEmpty { "New group message" },
                        )
                    }

                    // 1:1 DM classes. The body is a server-side fallback ("New message", etc.);
                    // the real content is end-to-end encrypted (data["enc_payload"]) and would be
                    // decrypted here in a later iteration, matching iOS's NSE.
                    "contextual", "payment", "handshake" -> {
                        val sender = data["sender"] ?: return@runBlocking
                        notificationHelper.show(
                            contactId = sender,
                            title = title.ifEmpty { sender },
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

    companion object {
        // Same tag as PushRegistrationManager: `adb logcat -s KaChatPush` shows registrations,
        // token rotations, and every received push in one stream.
        private const val TAG = PushRegistrationManager.TAG
    }
}
