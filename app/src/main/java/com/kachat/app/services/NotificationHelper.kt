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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    /** The localized words a 1:1 banner body uses (dmNotificationText), in the app's language. */
    fun dmNotificationLabels(): com.kachat.app.util.DmNotificationLabels =
        com.kachat.app.util.DmNotificationLabels.from(context)

    // Suppresses a notification for whichever contact's thread is currently on screen —
    // set by ChatViewModel as ChatThreadScreen opens/closes.
    private val activeContactId = MutableStateFlow<String?>(null)

    // Same idea for group chats — set by GroupChatViewModel as GroupChatThreadScreen opens/closes.
    private val activeGroupId = MutableStateFlow<String?>(null)

    // Whether any conversation thread (1:1 or group) is on screen right now — kept in lockstep
    // with the two flows above by their setters, so the existing screen open/close calls feed it
    // without any screen changes.
    private val openChat = MutableStateFlow(false)

    // Whether the app is on screen right now — set by KaChatApplication's process lifecycle
    // observer. Foreground notification policy: banners DO fire while the user is elsewhere in
    // the app (chat list, another thread, Settings); only the conversation currently open on
    // screen is suppressed, via the activeContactId/activeGroupId checks above.
    private val appForeground = MutableStateFlow(false)

    // Recently-notified transaction ids, so the same message reaching two delivery paths at once
    // (in-app poll/scan AND an FCM push, both live while the app is foregrounded) posts exactly
    // one banner. LRU-capped; access is synchronized because the claimants run on IO coroutines
    // and the FCM service thread.
    private val notifiedTxIds = object : LinkedHashMap<String, Boolean>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 200
    }

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
            // The pre-4.0 background-sync foreground service ("KaChat - Checking for new
            // messages") is gone — remote push is the closed-app delivery path now. Deleting its
            // channel removes the stale "Background sync" entry from the system's notification
            // settings on devices that upgraded, and guarantees nothing can post through it.
            manager?.deleteNotificationChannel("kachat_sync_service")
        }
    }

    /** See [appForeground] — driven by KaChatApplication's ProcessLifecycleOwner observer. */
    fun setAppForeground(foreground: Boolean) {
        appForeground.value = foreground
    }

    /** Whether the app is on screen. No longer a banner gate: while push is active the remote
     *  push is the only banner source, foreground included (see [PushState]); the txId ledger
     *  in [claimTxId] stays so the push's own two paths (FCM handler and a push-triggered sync)
     *  cannot double-post. */
    val isAppInForeground: Boolean get() = appForeground.value

    /** Reactive form of [isAppInForeground] — drives lifecycle-scoped loops that must start on
     *  foreground and stop on background (e.g. NextcloudSyncService's remote change watcher). */
    val appForegroundFlow: StateFlow<Boolean> = appForeground.asStateFlow()

    /** Reactive form of "a conversation thread is open on screen" — mirrors [appForegroundFlow].
     *  Drives NextcloudSyncService's adaptive mirror cadence: fast tier while the user is inside
     *  a chat (where a mirrored message is visible the moment it lands), relaxed tier elsewhere. */
    val openChatFlow: StateFlow<Boolean> = openChat.asStateFlow()

    /** Snapshot form of [openChatFlow] for one-shot reads (e.g. picking a debounce tier). */
    val isChatOpen: Boolean get() = openChat.value

    /**
     * Claims [txId] for notification purposes. Returns true when this caller is the first to
     * claim it (post the banner); false when another path already did (skip — it's a duplicate).
     * A null/blank id always claims successfully (nothing to dedupe on).
     */
    @Synchronized
    private fun claimTxId(txId: String?): Boolean {
        if (txId.isNullOrBlank()) return true
        return notifiedTxIds.put(txId, true) == null
    }

    /**
     * Records [txId] as notified WITHOUT posting a banner. Called by a local delivery path that
     * decrypted the tx and deliberately chose silence (mentions-only group without a mention,
     * muted member, reaction to someone else's message in a mentions-only group): the silence is
     * a final decision, so claiming here guarantees a racing FCM push for the same tx can never
     * post the banner the local path just suppressed.
     */
    @Synchronized
    fun claimWithoutNotifying(txId: String?) {
        if (!txId.isNullOrBlank()) notifiedTxIds[txId] = true
    }

    /** Claims [txId] for a banner another path posts itself: false when one was already shown
     *  for it. A plain payment to the chatting address can banner from the payment sync and from
     *  [AddressActivityNotifier] (iOS 56b75c8) - whichever comes first is the only one. */
    fun claimForBanner(txId: String): Boolean = claimTxId(txId)

    /** Screens showing a 1:1 conversation, newest last. The chat thread and the chess board over
     *  it both hold the contact, and Compose Navigation disposes the thread only after the board
     *  has entered - a plain "set null on dispose" then cleared the board's hold, which stopped
     *  the open-chat fast poll while a game was on screen (iOS c1ee033). */
    private val contactHolds = mutableListOf<String>()

    fun enterContact(contactId: String) {
        synchronized(contactHolds) {
            contactHolds.add(contactId)
            activeContactId.value = contactId
        }
        refreshOpenChat()
    }

    fun leaveContact(contactId: String) {
        synchronized(contactHolds) {
            val index = contactHolds.lastIndexOf(contactId)
            if (index >= 0) contactHolds.removeAt(index)
            activeContactId.value = contactHolds.lastOrNull()
        }
        refreshOpenChat()
    }

    fun setActiveGroup(groupId: String?) {
        activeGroupId.value = groupId
        refreshOpenChat()
    }

    private fun refreshOpenChat() {
        openChat.value = activeContactId.value != null || activeGroupId.value != null
    }

    /** Lets `GroupRepository` check this without needing its own copy of the state - used to keep
     *  a group marked read in real time while its thread is on screen, not just once on open. */
    fun isViewingGroup(groupId: String): Boolean = activeGroupId.value == groupId

    /** The group whose thread is on screen, if any - the live group poll reads it every few
     *  seconds (see GroupScanningService). */
    val viewingGroupId: String? get() = activeGroupId.value

    /** Same for 1:1 threads: a message that lands WHILE its conversation is on screen inserts as
     *  already-read, so leaving the chat doesn't show an unread badge for messages you watched arrive. */
    fun isViewingContact(contactId: String): Boolean = activeContactId.value == contactId

    /** The contact whose 1:1 thread is on screen right now (null when none) — lets
     *  ChatRepository's poll loop keep just the open conversation on the fast tick while the
     *  full contact sweep is throttled. */
    val currentContactId: String? get() = activeContactId.value

    /** The one notification a new Message Request gets: "New message request - <address> wants
     *  to chat with you." (NO_HANDSHAKE_MESSAGING.md §4). The caller decides it is the first. */
    suspend fun showMessageRequest(contactId: String, dedupeTxId: String? = null) {
        show(
            contactId = contactId,
            title = context.getString(com.kachat.app.R.string.new_message_request),
            text = context.getString(
                com.kachat.app.R.string.wants_to_chat_with_you,
                com.kachat.app.util.KaspaAddress.shortDisplay(contactId)
            ),
            dedupeTxId = dedupeTxId,
        )
    }

    suspend fun show(contactId: String, title: String, text: String, notificationOverride: ContactNotificationMode? = null, dedupeTxId: String? = null) {
        if (activeContactId.value == contactId) return // already looking at this conversation
        if (!settings.notificationsEnabled.first()) return
        if (notificationOverride == ContactNotificationMode.OFF) return
        if (!claimTxId(dedupeTxId)) return // the other delivery path (poll vs push) got here first

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CONTACT_ID, contactId)
        }
        // Stable per-contact request code so a burst of updates for the same
        // conversation replaces the pending intent instead of leaking a new one each time.
        // Namespaced ("chat:"), like every other family below: the request code doubles as the
        // PendingIntent key, so two families sharing one raw hash would have made a tap on one
        // notification open the other one's destination.
        val notificationId = "chat:$contactId".hashCode()
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

    /** Wallet address-activity notification ("Received X KAS" on a spending or the chatting
     *  address) — see [AddressActivityNotifier]. Gated only by the global notifications toggle
     *  here; the Address Activity setting itself is checked by the notifier before calling.
     *
     *  [kind] is [KIND_SPENDING] or [KIND_CHATTING] (iOS sends the same string in the
     *  notification's `userInfo["kind"]`): a tap with no one address opens Profile, which shows
     *  the wallet's addresses (iOS d54ea87).
     *
     *  [address] names the one address the Kaspa landed on (iOS 13046ad, `userInfo["address"]`):
     *  the tap then opens that address's own History, in a sheet over the current tab, and
     *  [kind] is only the fallback for a receipt that spans several addresses. */
    suspend fun showAddressActivity(
        title: String,
        text: String,
        dedupeKey: String,
        kind: String = KIND_SPENDING,
        address: String? = null,
    ) {
        if (!settings.notificationsEnabled.first()) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_WALLET_ACTIVITY_KIND, kind)
            if (!address.isNullOrEmpty()) putExtra(EXTRA_WALLET_ACTIVITY_ADDRESS, address)
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

    /**
     * Per-group opt-in notification for a new `gcomm` message or a "you were added" `gctl_root`
     * join — see [EXTRA_GROUP_ID]/GroupRepository.
     *
     * [groupId] is the notification's identity (dedupe + open-thread suppression + request code).
     * [targetGroupId] is what the TAP opens, and defaults to the same thing. A remote push whose
     * only group handle is a blinded id or a tx id (see KaChatFirebaseMessagingService's
     * `group_control` / un-ingested `group_message` fallbacks) must pass null instead: those
     * strings are not local group ids, and routing `group_chat/<blinded id>` landed the user on a
     * permanently empty thread with no title and a composer that could not send. Null routes the
     * tap to the Chats list, whose circles row is the closest destination that actually exists.
     */
    suspend fun showGroup(
        groupId: String,
        title: String,
        text: String,
        dedupeTxId: String? = null,
        targetGroupId: String? = groupId,
    ) {
        if (activeGroupId.value == groupId) return // already looking at this group's thread
        if (!settings.notificationsEnabled.first()) return
        if (!claimTxId(dedupeTxId)) return // the other delivery path (scan/sync vs push) got here first

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (targetGroupId != null) {
                putExtra(EXTRA_GROUP_ID, targetGroupId)
            } else {
                putExtra(EXTRA_OPEN_GROUPS, true)
            }
        }
        // Stable per-group request code, same reasoning as the per-contact one above.
        val notificationId = "group:$groupId".hashCode()
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
        const val EXTRA_GROUP_ID = "group_id"
        /** A `.kachat` name notification an older build posted: its tap lands on Chats now (the
         *  .kachat market left the app, iOS d54ea87). */
        const val EXTRA_KACHAT_NAME = "kachat_name"

        /** Set instead of [EXTRA_GROUP_ID] when the notification has no resolvable local group —
         *  the tap opens the Chats list rather than a `group_chat/<id>` route that would resolve
         *  to nothing. See [showGroup]'s `targetGroupId`. */
        const val EXTRA_OPEN_GROUPS = "open_groups"

        /** [KIND_SPENDING] / [KIND_CHATTING] — what an address-activity notification is about. */
        const val EXTRA_WALLET_ACTIVITY_KIND = "wallet_activity_kind"
        const val KIND_SPENDING = "spending"
        /** The chatting address (iOS 56b75c8): its receipt always names the address, so the tap
         *  opens its History; as a fallback it lands where a spending receipt does. */
        const val KIND_CHATTING = "chatting"
        /** The single own address an address-activity notification is about; its tap opens
         *  that address's History (iOS 13046ad). Absent for a receipt across several addresses. */
        const val EXTRA_WALLET_ACTIVITY_ADDRESS = "wallet_activity_address"

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
