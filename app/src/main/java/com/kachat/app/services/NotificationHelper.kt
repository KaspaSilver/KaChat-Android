package com.kachat.app.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kachat.app.MainActivity
import com.kachat.app.R
import com.kachat.app.models.ContactNotificationMode
import com.kachat.app.repository.AppSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local notifications for new messages/handshakes/payments — fired directly from
 * ChatRepository's existing poll loop. There's no server-side push infrastructure yet
 * (see the commented-out FCM service in AndroidManifest), so these only fire while the
 * app process is alive (foreground or recently backgrounded), not after the OS has
 * fully killed it. Settings > Notifications controls whether these fire at all, and
 * whether they carry sound/vibration.
 */
@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettingsRepository
) {
    // Suppresses a notification for whichever contact's thread is currently on screen —
    // set by ChatViewModel as ChatThreadScreen opens/closes.
    private val activeContactId = MutableStateFlow<String?>(null)

    // Same idea for broadcast channels — set by BroadcastViewModel's startLiveViewing/stopLiveViewing.
    private val activeChannelName = MutableStateFlow<String?>(null)

    // Same idea for group chats — set by GroupChatViewModel as GroupChatThreadScreen opens/closes.
    private val activeGroupId = MutableStateFlow<String?>(null)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // A channel's sound/vibration is fixed by the OS once created — the app can't
            // flip it later, only the user can (in system notification settings). The only
            // way an in-app Sound/Vibration toggle can actually do anything on API 26+ is
            // to pre-create one channel per combination and post through whichever one
            // matches the current settings.
            val manager = context.getSystemService(NotificationManager::class.java)
            CHANNELS.forEach { (channelId, soundOn, vibrationOn) ->
                val channel = NotificationChannel(channelId, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "New messages, connection requests, and payments"
                    enableVibration(vibrationOn)
                    if (!soundOn) setSound(null, null)
                }
                manager?.createNotificationChannel(channel)
            }
        }
    }

    fun setActiveContact(contactId: String?) {
        activeContactId.value = contactId
    }

    fun setActiveChannel(channelName: String?) {
        activeChannelName.value = channelName
    }

    fun setActiveGroup(groupId: String?) {
        activeGroupId.value = groupId
    }

    /** Lets `GroupRepository` check this without needing its own copy of the state - used to keep
     *  a group marked read in real time while its thread is on screen, not just once on open. */
    fun isViewingGroup(groupId: String): Boolean = activeGroupId.value == groupId

    /** Same for 1:1 threads: a message that lands WHILE its conversation is on screen inserts as
     *  already-read, so leaving the chat doesn't show an unread badge for messages you watched arrive. */
    fun isViewingContact(contactId: String): Boolean = activeContactId.value == contactId

    suspend fun show(contactId: String, title: String, text: String, notificationOverride: ContactNotificationMode? = null) {
        if (activeContactId.value == contactId) return // already looking at this conversation
        if (!settings.notificationsEnabled.first()) return
        if (notificationOverride == ContactNotificationMode.OFF) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CONTACT_ID, contactId)
        }
        // Stable per-contact request code so a burst of updates for the same
        // conversation replaces the pending intent instead of leaking a new one each time.
        val notificationId = contactId.hashCode()
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Off/No Sound/Sound override wins over the global sound setting; vibration always
        // follows the global setting regardless — the override is specifically about sound.
        val soundEnabled = when (notificationOverride) {
            ContactNotificationMode.SOUND -> true
            ContactNotificationMode.NO_SOUND -> false
            else -> settings.notificationSoundEnabled.first()
        }
        val vibrationEnabled = settings.notificationVibrationEnabled.first()
        val channelId = channelFor(soundEnabled, vibrationEnabled)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_kachat_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Below API 26 there are no channels — these are what actually apply there.
            .setSilent(!soundEnabled && !vibrationEnabled)
            .setVibrate(if (vibrationEnabled) longArrayOf(0, 250, 250, 250) else longArrayOf(0))
            .apply { if (!soundEnabled) setSound(null) }
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted — skip rather than crash.
        }
    }

    /** Per-channel opt-in notification for a new broadcast message — see [EXTRA_CHANNEL_NAME]/BroadcastScanningService. */
    /** KaPosts social ping ("alice liked your post") - a tap deep-opens the exact
     *  post/comment when [postTxId] is known, else just the KaPosts tab. */
    suspend fun showKaPosts(text: String, actionTxId: String, postTxId: String? = null) {
        if (!settings.notificationsEnabled.first()) return
        // Child Mode removes KaPosts entirely - no notification pings for it either. Guarding
        // here covers every source at once: the foreground poller AND the FCM receive handler
        // (push registration already drops kaposts_pubkey, but a push can race re-registration).
        if (settings.childModeEnabled.first()) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_KAPOSTS, true)
            if (!postTxId.isNullOrBlank()) putExtra(EXTRA_KAPOST_TXID, postTxId)
        }
        val notificationId = "kaposts_$actionTxId".hashCode()
        val pendingIntent = PendingIntent.getActivity(
            context, notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val soundEnabled = settings.notificationSoundEnabled.first()
        val vibrationEnabled = settings.notificationVibrationEnabled.first()
        val notification = NotificationCompat.Builder(context, channelFor(soundEnabled, vibrationEnabled))
            .setSmallIcon(R.drawable.ic_kachat_logo)
            .setContentTitle("KaPosts")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(!soundEnabled && !vibrationEnabled)
            .setVibrate(if (vibrationEnabled) longArrayOf(0, 250, 250, 250) else longArrayOf(0))
            .apply { if (!soundEnabled) setSound(null) }
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {}
    }

    /** Wallet address-activity notification ("Received X KAS" on a spending/cold address) — see
     *  [AddressActivityNotifier]. Gated only by the global notifications toggle here; the
     *  Address Activity setting itself is checked by the notifier before calling. */
    suspend fun showAddressActivity(title: String, text: String, dedupeKey: String) {
        if (!settings.notificationsEnabled.first()) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val notificationId = "addr_activity_$dedupeKey".hashCode()
        val pendingIntent = PendingIntent.getActivity(
            context, notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val soundEnabled = settings.notificationSoundEnabled.first()
        val vibrationEnabled = settings.notificationVibrationEnabled.first()
        val notification = NotificationCompat.Builder(context, channelFor(soundEnabled, vibrationEnabled))
            .setSmallIcon(R.drawable.ic_kachat_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(!soundEnabled && !vibrationEnabled)
            .setVibrate(if (vibrationEnabled) longArrayOf(0, 250, 250, 250) else longArrayOf(0))
            .apply { if (!soundEnabled) setSound(null) }
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {}
    }

    suspend fun showBroadcast(channelName: String, title: String, text: String) {
        if (activeChannelName.value == channelName) return // already looking at this channel
        if (!settings.notificationsEnabled.first()) return
        // Child Mode removes Broadcasts entirely - no local banners for them either. Covers the
        // scan-driven path (BroadcastScanningService) AND the FCM receive handler in one place
        // (registration already drops watched_broadcast_channels, but a push can race it).
        if (settings.childModeEnabled.first()) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CHANNEL_NAME, channelName)
        }
        // Stable per-channel request code, same reasoning as the per-contact one above.
        val notificationId = channelName.hashCode()
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundEnabled = settings.notificationSoundEnabled.first()
        val vibrationEnabled = settings.notificationVibrationEnabled.first()
        val channelId = channelFor(soundEnabled, vibrationEnabled)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_kachat_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(!soundEnabled && !vibrationEnabled)
            .setVibrate(if (vibrationEnabled) longArrayOf(0, 250, 250, 250) else longArrayOf(0))
            .apply { if (!soundEnabled) setSound(null) }
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted — skip rather than crash.
        }
    }

    /** Per-group opt-in notification for a new `gcomm` message or a "you were added" `gctl_root` join — see [EXTRA_GROUP_ID]/GroupRepository. */
    suspend fun showGroup(groupId: String, title: String, text: String) {
        if (activeGroupId.value == groupId) return // already looking at this group's thread
        if (!settings.notificationsEnabled.first()) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_GROUP_ID, groupId)
        }
        // Stable per-group request code, same reasoning as the per-contact/per-channel ones above.
        val notificationId = groupId.hashCode()
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundEnabled = settings.notificationSoundEnabled.first()
        val vibrationEnabled = settings.notificationVibrationEnabled.first()
        val channelId = channelFor(soundEnabled, vibrationEnabled)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_kachat_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(!soundEnabled && !vibrationEnabled)
            .setVibrate(if (vibrationEnabled) longArrayOf(0, 250, 250, 250) else longArrayOf(0))
            .apply { if (!soundEnabled) setSound(null) }
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted — skip rather than crash.
        }
    }

    companion object {
        const val CHANNEL_ID = "kachat_messages_sound_vibrate"
        const val EXTRA_CONTACT_ID = "contact_id"
        const val EXTRA_CHANNEL_NAME = "channel_name"
        const val EXTRA_GROUP_ID = "group_id"
        const val EXTRA_OPEN_KAPOSTS = "open_kaposts"
        const val EXTRA_KAPOST_TXID = "kapost_txid"

        // (channelId, soundEnabled, vibrationEnabled)
        private val CHANNELS = listOf(
            Triple("kachat_messages_sound_vibrate", true, true),
            Triple("kachat_messages_sound_only", true, false),
            Triple("kachat_messages_vibrate_only", false, true),
            Triple("kachat_messages_silent", false, false)
        )

        internal fun channelFor(soundEnabled: Boolean, vibrationEnabled: Boolean) =
            CHANNELS.first { it.second == soundEnabled && it.third == vibrationEnabled }.first
    }
}
