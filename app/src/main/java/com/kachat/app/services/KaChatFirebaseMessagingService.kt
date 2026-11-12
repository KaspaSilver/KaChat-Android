package com.kachat.app.services

import com.kachat.app.R
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.kachat.app.models.ContactNotificationMode
import com.kachat.app.repository.ChatRepository
import com.kachat.app.repository.GroupRepository.GroupControlPush
import com.kachat.app.repository.GroupRepository.GroupMessagePush
import com.kachat.app.util.KasiaCipher
import com.kachat.app.util.MessageProtocol
import com.kachat.app.util.MessageReaction
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import java.util.Base64
import javax.inject.Inject

/**
 * Receives FCM messages and turns them into notifications via [NotificationHelper].
 *
 * Two shapes arrive from the server (`kasia-indexer` `push.rs`):
 *  - **Public content** (broadcast / KaPosts) is sent WITH a `notification` block, so the OS shows
 *    it even when the app is dead. Both features left the app (iOS e2f6732) and the device
 *    registers for neither, so one that still arrives here is dropped; a tap on one the OS drew
 *    lands on the Chats list (MainActivity).
 *  - **Encrypted DM/group content** is sent **data-only** (no `notification` block) so this handler
 *    runs and can DECRYPT the body locally with the wallet key — mirroring iOS's Notification
 *    Service Extension. (Data-only needs the app wake-able; a force-closed app under OEM battery
 *    optimization may not fire — set the app's battery usage to Unrestricted.)
 *
 * Data schema:
 *   chat/DM   : type(contextual|payment|handshake|group_message|group_control), sender, title,
 *               body, tx_id, timestamp, daa_score, [amount], [enc_payload], [blinded_group_id]
 */
@AndroidEntryPoint
class KaChatFirebaseMessagingService : FirebaseMessagingService() {

    // All through Lazy: each needs the wallet store, directly or not, and Hilt injects in
    // onCreate - so with the account stores unreadable every push would end the process in the
    // background. Nothing is built until a handler has checked the stores open.
    @Inject lateinit var notificationHelperLazy: dagger.Lazy<NotificationHelper>
    @Inject lateinit var pushRegistrationManagerLazy: dagger.Lazy<PushRegistrationManager>
    @Inject lateinit var walletManagerLazy: dagger.Lazy<WalletManager>
    @Inject lateinit var chatRepositoryLazy: dagger.Lazy<ChatRepository>
    @Inject lateinit var groupRepositoryLazy: dagger.Lazy<com.kachat.app.repository.GroupRepository>
    @Inject lateinit var settingsRepositoryLazy: dagger.Lazy<com.kachat.app.repository.AppSettingsRepository>
    private val notificationHelper: NotificationHelper get() = notificationHelperLazy.get()
    private val pushRegistrationManager: PushRegistrationManager get() = pushRegistrationManagerLazy.get()
    private val walletManager: WalletManager get() = walletManagerLazy.get()
    private val chatRepository: ChatRepository get() = chatRepositoryLazy.get()
    private val groupRepository: com.kachat.app.repository.GroupRepository get() = groupRepositoryLazy.get()
    private val settingsRepository: com.kachat.app.repository.AppSettingsRepository get() = settingsRepositoryLazy.get()

    /** When the push being handled arrived: its chain reads retry until [CHAIN_READ_DEADLINE_MS]
     *  after it. Pushes are handled one at a time. */
    @Volatile private var pushReceivedAtMs = 0L

    /** The account stores open: the app's problem screen covers the rest; a push or a token
     *  rotation meanwhile is dropped rather than building the wallet and ending the process. */
    private fun accountStoresOpen(what: String): Boolean {
        if (com.kachat.app.util.SecureStores.accountStoresOpen(applicationContext)) return true
        Log.w(TAG, "Account stores unreadable, $what ignored")
        return false
    }

    override fun onNewToken(token: String) {
        if (!accountStoresOpen("FCM token rotation")) return
        // FCM rotated the token — re-register it with the indexer (signed with the wallet key).
        Log.i(TAG, "FCM token rotated, re-registering with the push service")
        pushRegistrationManager.onTokenRefreshed(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!accountStoresOpen("push")) return
        val data = message.data
        if (data.isEmpty()) {
            Log.d(TAG, "push received with empty data payload, ignoring")
            return
        }

        val type = data["type"].orEmpty()
        val body = data["body"].orEmpty()
        pushReceivedAtMs = System.currentTimeMillis()
        Log.i(TAG, "push received: type=$type txId=${data["tx_id"].orEmpty().take(16)}")

        // NotificationHelper.show* are suspend and quick; block so the work completes before the
        // service is torn down.
        runBlocking {
            try {
                // Any push about the other network - the service this device left when it moved
                // between mainnet and testnet still pushing it - stays silent, whatever its type:
                // DMs, payments and handshakes as before, and now group and .kachat name pushes
                // too (iOS d946a65 rule, audit AND-006). Not filed either: the
                // same key would decrypt it into the wrong account (iOS df52425).
                if (com.kachat.app.util.PushNetworkFilter.isOtherNetwork(data, com.kachat.app.util.KaspaNetwork.launch)) {
                    data["tx_id"]?.takeIf { it.isNotBlank() }?.let { notificationHelper.claimWithoutNotifying(it) }
                    Log.i(TAG, "Silenced a $type push from the other network")
                    return@runBlocking
                }
                // Signed out: the key stays on the device for signing back in, but this phone
                // shows nothing until then. A failed unregister at logout can keep pushes coming
                // (iOS NotificationService, bfe9f022).
                if (walletManager.isLoggedOut || walletManager.getActiveAccount() == null) {
                    data["tx_id"]?.takeIf { it.isNotBlank() }?.let { notificationHelper.claimWithoutNotifying(it) }
                    Log.i(TAG, "Silenced a $type push while signed out")
                    return@runBlocking
                }
                // Our own transaction, sent from another device of this seed: never a banner (iOS
                // NotificationService). A message is read back with the seed and stored
                // (iOS addOutgoingMessageFromPush, bfe9f022).
                val ownAddress = runCatching { walletManager.getAddress() }.getOrNull()
                if (type in OWN_SEND_PUSH_TYPES && data["sender"].orEmpty().equals(ownAddress, ignoreCase = true)) {
                    data["tx_id"]?.takeIf { it.isNotBlank() }?.let { txId ->
                        notificationHelper.claimWithoutNotifying(txId)
                        if (type == "contextual") chatRepository.noteOwnSentPush(txId)
                    }
                    return@runBlocking
                }
                when (type) {
                    // .kachat name events left with the .kachat market (iOS d54ea87): one that
                    // still arrives is never shown; KaChat Wallet carries them.
                    "name_event" -> {
                        data["tx_id"]?.takeIf { it.isNotBlank() }?.let { notificationHelper.claimWithoutNotifying(it) }
                        Log.i(TAG, "Dropped a name_event push: names live in KaChat Wallet")
                    }

                    // KaPosts and Public Chats left the app (iOS e2f6732): one that races the
                    // re-registration is never shown.
                    "broadcast", "kaposts" -> {
                        data["tx_id"]?.takeIf { it.isNotBlank() }?.let { notificationHelper.claimWithoutNotifying(it) }
                        Log.i(TAG, "Dropped a $type push: the feature left the app")
                    }

                    "group_message" -> handleGroupMessagePush(data)

                    "group_control" -> handleGroupControlPush(data)

                    "contextual" -> handleDirectMessage(data)

                    // Payment / handshake bodies are already meaningful ("Payment received",
                    // "Started a conversation") — no decryption needed.
                    "payment", "handshake" -> {
                        val sender = data["sender"] ?: return@runBlocking
                        // Payments land in your own chat and ring as before; a handshake is
                        // first contact like any other (Message Requests).
                        if (type == "handshake" && applyMessageRequestRule(sender, data)) return@runBlocking
                        notificationHelper.show(
                            contactId = sender,
                            title = contactTitle(sender, sender),
                            // A payment: "<who> - Sent you 1.5 KAS" (iOS dcb329a).
                            text = if (type == "payment") paymentPushBody(data["amount"]) else body.ifEmpty { getString(R.string.dm_notif_new_message) },
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
     * A group message. While push is active this is the only banner it gets, and it has to come
     * from here whatever the sync does: the sync may run out of time, or have stored this very
     * message on an earlier pass. So it is read straight off the push with the group secrets held
     * here, as iOS's notification service extension does (iOS handleGroupPushResolved), and the
     * banner goes up before the catch-up starts. A push with no payload (a photo: over the push's
     * 3.5 KB) is read off chain first.
     */
    private suspend fun handleGroupMessagePush(data: Map<String, String>) {
        val groupId = data["blinded_group_id"] ?: return
        val txId = data["tx_id"].orEmpty()
        // Read off chain (a photo or voice note part is over the push's size), retried while the
        // API catches up with the transaction (iOS ca013fc8 fetchTransactionPayload).
        val readOffChain = data["enc_payload"].isNullOrBlank()
        val payload = data["enc_payload"]?.takeIf { it.isNotBlank() }?.let { "kchat:1:gcomm:$it" }
            ?: txId.takeIf { it.isNotBlank() }?.let { id ->
                readOffChainWithRetry({ groupRepository.readChainPayload(id) }) { it != null }
            }
        // Back-to-back parts read off chain from one group ring once (iOS ca013fc8
        // `part_push_bursts`, by blinded group id).
        val burstOwner = "group:${groupId.lowercase()}"
        val read = groupRepository.readGroupMessagePush(payload)
        if (read is GroupMessagePush.Banner) postGroupBanner(read, txId, burstOwner.takeIf { readOffChain && read.partKey != null })
        // Stores the message, and brings the keys of a group this device has only just heard of.
        // Bounded: syncGroups() is a full catch-up (every group, backfill sends included) and FCM
        // gives this handler about 20 s. A sync still running at 8 s is left behind and the
        // generic fallback below posts in time (audit AND-008).
        try {
            kotlinx.coroutines.withTimeoutOrNull(GROUP_SYNC_BUDGET_MS) { groupRepository.syncGroups() }
        } catch (e: Exception) {
            Log.w(TAG, "Push-triggered group sync failed: ${e.message}")
        }
        if (read !is GroupMessagePush.Unreadable) return
        // The catch-up may have just brought the group this message belongs to.
        when (val reread = groupRepository.readGroupMessagePush(payload)) {
            is GroupMessagePush.Banner -> {
                postGroupBanner(reread, txId, burstOwner.takeIf { readOffChain && reread.partKey != null })
                return
            }
            GroupMessagePush.Silent -> return
            GroupMessagePush.Unreadable -> Unit
        }
        // The generic fallback can't know whether the unread message mentions the user, so in a
        // group with "Only Notify if I'm Mentioned" on it can't be posted correctly. Resolve the
        // per-sender blinded id back to the local group and consult the toggle: mentions-only means
        // suppress. Tradeoff: a missed banner for a non-mention is exactly what the toggle asks
        // for, while a missed banner for an actual mention is the cost of the read failure; the
        // message itself still lands on the next successful sync, and with no push active that
        // path still banners the mention (which is why the txId is deliberately NOT claimed here).
        // When the blinded id matches no local group the toggle can't be consulted, so the generic
        // banner fires as before.
        val resolvedGroup = try {
            groupRepository.findGroupByBlindedId(groupId)
        } catch (e: Exception) {
            null
        }
        // When the blinded id resolves, silent and mentions-only both suppress. When it does NOT
        // resolve we cannot tell which group it is, so the only honest question is whether the
        // answer would be the same for all of them - if every known group is silenced or
        // mentions-only, no group here would have allowed this banner.
        val suppressed = if (resolvedGroup != null) {
            groupRepository.isGroupSilent(resolvedGroup.groupId) ||
                groupRepository.isGroupMentionsOnly(resolvedGroup.groupId)
        } else {
            groupRepository.allGroupsSuppressUnidentifiedPushes()
        }
        if (suppressed) {
            Log.i(TAG, "Generic group fallback suppressed by notification settings")
            return
        }
        // Read off chain and still unreadable (most often: not on the API yet after every
        // retry): back-to-back ones in this group ring once, as parts would (iOS ca013fc8).
        val quiet = readOffChain && !notificationHelper.claimPartBurst(burstOwner)
        notificationHelper.showGroup(
            // Prefer the resolved real group id/name: the tap intent then opens the actual thread
            // and the open-thread suppression keys correctly.
            groupId = resolvedGroup?.groupId ?: groupId,
            // Never the push's own title: the service sends the sender's raw address there.
            title = resolvedGroup?.name ?: getString(R.string.group_chat),
            text = getString(R.string.new_group_message),
            dedupeTxId = txId.takeIf { it.isNotBlank() },
            // Unresolved: `groupId` here is the per-sender BLINDED id, which is not a route
            // argument any thread can be found by. Send the tap to the Chats list instead.
            targetGroupId = resolvedGroup?.groupId,
            quiet = quiet,
        )
    }

    /** [burstOwner]: set for a part read off chain, which also joins its group's burst. A later
     *  part of a long voice note or multi-part photo, or of a burst, is delivered quietly - the
     *  note already rang (iOS 7c5c22e3 / ca013fc8 `isLaterPartPush`). */
    private suspend fun postGroupBanner(banner: GroupMessagePush.Banner, txId: String, burstOwner: String? = null) {
        notificationHelper.showGroup(
            groupId = banner.groupId,
            title = banner.title,
            text = banner.text,
            dedupeTxId = txId.takeIf { it.isNotBlank() },
            quiet = isLaterPartPush(banner.partKey, txId, burstOwner),
        )
    }

    /** True when this push is a later part of a note or burst that already rang: a tagged part
     *  whose note was announced, or a payload-less push that follows another from the same owner
     *  within a minute. Claims both ledgers, so call it once per push (iOS ca013fc8). */
    private fun isLaterPartPush(partKey: String?, txId: String, burstOwner: String?): Boolean {
        val firstOfNote = partKey?.let { notificationHelper.claimPartBanner(it, txId) } ?: true
        val firstOfBurst = burstOwner?.let { notificationHelper.claimPartBurst(it) } ?: true
        return !firstOfNote || !firstOfBurst
    }

    /**
     * A transaction read off chain, retried until [CHAIN_READ_DEADLINE_MS] after the push
     * arrived (iOS ca013fc8 `fetchTransactionPayload`): the push comes the moment the
     * transaction is sent, often before the API has it, and a photo's or voice note's parts go
     * back to back. With one try every part could come back unreadable and ring as its own "New
     * message". [done] says when a result is final; the last result is returned at the deadline.
     */
    private suspend fun <T> readOffChainWithRetry(read: suspend () -> T?, done: (T?) -> Boolean): T? {
        var attempt = 0
        var result: T? = null
        while (true) {
            val remaining = CHAIN_READ_DEADLINE_MS - (System.currentTimeMillis() - pushReceivedAtMs)
            if (remaining <= 1_000L) return result
            result = runCatching { kotlinx.coroutines.withTimeoutOrNull(minOf(CHAIN_READ_BUDGET_MS, remaining)) { read() } }.getOrNull()
            if (done(result)) return result
            // Not on the API yet, busy, or no answer: try again shortly, while there's time.
            val delayMs = minOf(1_000L + attempt * 750L, 3_000L)
            val left = CHAIN_READ_DEADLINE_MS - (System.currentTimeMillis() - pushReceivedAtMs)
            if (left <= delayMs + 1_000L) return result
            kotlinx.coroutines.delay(delayMs)
            attempt++
        }
    }

    /**
     * A group control: usually "you were added to a group". Read straight off the push with the
     * wallet key, needing no network, as iOS's extension does (iOS handleGroupPushResolved): being
     * added is only a request, announced once, so nothing for a group you're already in (a new root
     * only rotates its keys) and otherwise "You were invited", whose tap opens Message Requests.
     * Every other control, and one that can't be read, is "Group update". A root too big for the
     * push (a long member list) is read off chain first; iOS has only the generic banner for those.
     */
    private suspend fun handleGroupControlPush(data: Map<String, String>) {
        val txId = data["tx_id"]?.takeIf { it.isNotBlank() }
        val payload = data["enc_payload"]?.takeIf { it.isNotBlank() }
            ?: txId?.let { id ->
                runCatching {
                    kotlinx.coroutines.withTimeoutOrNull(CHAIN_READ_BUDGET_MS) { groupRepository.readChainControlPayload(id) }
                }.getOrNull()
            }
        val read = groupRepository.readGroupControlPush(payload, data["sender"].orEmpty(), pushSentAtMs(data), txId)
        when (read) {
            is GroupControlPush.Invite -> notificationHelper.showGroupInvite(read.inviteKey, read.groupName, dedupeTxId = txId)
            GroupControlPush.Update -> postGroupUpdate(data, txId, dedupeTxId = txId)
            GroupControlPush.Quiet, GroupControlPush.Unreadable -> Unit
        }
        // Brings in whatever else it can see. Bounded: a push handler has seconds, not minutes.
        runCatching {
            kotlinx.coroutines.withTimeoutOrNull(GROUP_SYNC_BUDGET_MS) { groupRepository.syncGroups() }
        }
        if (read !is GroupControlPush.Unreadable) return
        // Couldn't be read from here, but the sync may have: an invite it held waits for this
        // push to announce it, and a root for a group held here only rotated its keys.
        if (txId != null) {
            groupRepository.inviteHeldByTx(txId)?.let { invite ->
                notificationHelper.showGroupInvite(invite.inviteKey, invite.groupName, dedupeTxId = txId)
                return
            }
            if (groupRepository.wasRootTx(txId)) return
        }
        // The txId is not claimed here: with no push active the sync's real banner can still
        // follow this one once the control is readable.
        postGroupUpdate(data, txId, dedupeTxId = null)
    }

    /**
     * The generic control banner (iOS deliverGroupFallback): "Group chat", the sender's name under
     * it, "Group update". Never the push's own title, which the service sets to the sender's raw
     * address. There is no group to open, so the tap goes to the Chats list.
     */
    private suspend fun postGroupUpdate(data: Map<String, String>, txId: String?, dedupeTxId: String?) {
        val sender = data["sender"]?.takeIf { it.isNotBlank() }
        notificationHelper.showGroup(
            groupId = txId ?: "group",
            title = getString(R.string.group_chat),
            text = getString(R.string.group_update),
            dedupeTxId = dedupeTxId,
            targetGroupId = null,
            subtitle = sender?.let { address ->
                val alias = runCatching { chatRepository.getContact(address)?.alias }.getOrNull()
                com.kachat.app.models.addressDisplayName(address, alias = alias)
            },
        )
    }

    /**
     * 1:1 message: decrypt `enc_payload` locally (stateless ECDH — only needs our wallet key and
     * the ephemeral key embedded in the sealed blob) and show the real sender + text. Falls back to
     * the server's generic text on any failure (no wallet, tag mismatch, unknown format).
     */
    private suspend fun handleDirectMessage(data: Map<String, String>) {
        val sender = data["sender"] ?: return
        // Always hand the message to the app's ingest, whatever the banner below ends up being
        // (iOS 60c9fd0): it is stored now, in the background, instead of waiting for a sync to
        // stumble on it - a photo, voice or long message stayed off the chat list until that
        // chat was opened. Queued, so duplicates are ignored; blocked senders are dropped there.
        data["tx_id"]?.takeIf { it.isNotBlank() }?.let { chatRepository.notePushedMessage(it, sender) }
        // Media/large messages exceed FCM's 4KB cap, so the server can't attach the encrypted body
        // (enc_payload absent) — the server's generic body is used for those. Small text messages
        // carry enc_payload and are decrypted here for the real preview.
        val fallback = data["body"].orEmpty().ifEmpty { getString(R.string.dm_notif_new_message) }

        // No enc_payload means the message was too big for FCM - which is EVERY photo and voice
        // message, since those run to tens of kilobytes. Read it off chain so the preview below
        // can say what it is instead of falling back to the server's generic wording.
        // Bounded so the fallback text still posts when the REST API is slow (audit AND-008).
        // Retried while the API catches up with the transaction (iOS ca013fc8).
        val pushedPlaintext = decryptDirectMessage(data["enc_payload"])
        val chainRead = if (pushedPlaintext != null) null else data["tx_id"]?.takeIf { it.isNotBlank() }?.let { txId ->
            readOffChainWithRetry({ chatRepository.readChainMessage(txId) }) { it != null && it !is ChatRepository.ContextualRead.Unreadable }
        }
        // The indexer picks devices by who sent a message, not who it's for, so a contact's
        // message to someone else can reach this phone: it isn't ours, so it says nothing and
        // isn't queued. Being unable to read one (no key, no network) still rings (iOS bfe9f022).
        if (chainRead is ChatRepository.ContextualRead.NotForThisAccount) {
            data["tx_id"]?.takeIf { it.isNotBlank() }?.let { txId ->
                notificationHelper.claimWithoutNotifying(txId)
                chatRepository.forgetPushedMessage(txId)
            }
            return
        }
        val plaintext = pushedPlaintext ?: (chainRead as? ChatRepository.ContextualRead.Message)?.text
        if (plaintext != null && MessageReaction.parseOrNull(plaintext) != null) {
            // Reactions are never shown as their own notification (matches ChatRepository).
            return
        }
        // Nor is an edit: it changes an earlier bubble in place (MESSAGING.md "Message Edits").
        if (plaintext != null && com.kachat.app.util.MessageEdit.isEditEnvelope(plaintext)) {
            data["tx_id"]?.takeIf { it.isNotBlank() }?.let { notificationHelper.claimWithoutNotifying(it) }
            return
        }
        // Payment-pool envelopes are invisible protocol traffic - no banner at all, as on iOS's
        // notification service extension (isSilentPoolEnvelope, 927322b): the retired addr_pool /
        // addr_pool_request, and a payment_notice naming anything but our chatting address (an
        // old app's payment to one of our former pool addresses, which the app drops - XP-023).
        // A payment_notice for a payment to our chatting address (a chat payment sent From the
        // contact's spending address) IS a payment the reader should see, so it falls through and
        // reads "Received payment" below (dmNotificationText).
        val silentPoolEnvelope = when (val pool = com.kachat.app.util.PaymentPoolProtocol.parse(plaintext)) {
            null -> false
            is com.kachat.app.util.PaymentPoolProtocol.Envelope.Notice -> {
                val myAddress = runCatching { walletManager.getAddress() }.getOrNull()
                myAddress.isNullOrBlank() || !pool.content.address.trim().equals(myAddress, ignoreCase = true)
            }
            else -> true
        }
        if (silentPoolEnvelope) {
            Log.i(TAG, "Payment pool envelope push kept silent")
            data["tx_id"]?.takeIf { it.isNotBlank() }?.let { notificationHelper.claimWithoutNotifying(it) }
            return
        }
        // KaChat has no calls: another client's call message is dropped silently, as the app
        // drops it (iOS 7c5c22e3 NotificationService.isCallSignal).
        if (plaintext != null && com.kachat.app.util.CallSignal.isCallSignal(plaintext)) {
            data["tx_id"]?.takeIf { it.isNotBlank() }?.let { notificationHelper.claimWithoutNotifying(it) }
            return
        }

        // Message Requests (NO_HANDSHAKE_MESSAGING.md §4): one "New message request" per new
        // sender, then nothing from them until accepted; nothing at all from a blocked address.
        if (applyMessageRequestRule(sender, data)) return

        val text = plaintext?.let { notificationPreview(it) } ?: fallback
        // A long voice note or multi-part photo is ONE message: the first of its parts to arrive
        // rings, a later one is delivered quietly (iOS 7c5c22e3). A payload-less push read off
        // chain that turned out to be a part, or couldn't be read in time, joins its sender's
        // burst: back-to-back ones ring once (iOS ca013fc8).
        val txId = data["tx_id"].orEmpty()
        val partKey = com.kachat.app.util.VoicePartGrouping.partKey(plaintext, sender)
        val readOffChain = pushedPlaintext == null && txId.isNotBlank()
        val burstOwner = "dm:${sender.lowercase()}".takeIf { readOffChain && (plaintext == null || partKey != null) }
        notificationHelper.show(
            contactId = sender,
            title = contactTitle(sender, data["title"].orEmpty().ifEmpty { sender }),
            text = text,
            notificationOverride = contactOverride(sender),
            // Collapses with the in-app poll's banner when the app is foregrounded and both
            // paths see the same message.
            dedupeTxId = txId.takeIf { it.isNotBlank() },
            quiet = isLaterPartPush(partKey, txId, burstOwner),
        )
    }

    /** Sompi as KAS text: up to 8 decimals, trailing zeros dropped (iOS kasText). */
    private fun kasText(sompi: Long): String =
        String.format(java.util.Locale.US, "%.8f", sompi / 100_000_000.0).trimEnd('0').trimEnd('.')

    private suspend fun applyMessageRequestRule(sender: String, data: Map<String, String>): Boolean {
        val myAddress = runCatching { walletManager.getAddress() }.getOrNull() ?: return false
        if (sender.equals(myAddress, ignoreCase = true)) return false
        val txId = data["tx_id"]?.takeIf { it.isNotBlank() }
        val silent = when {
            chatRepository.isChatBlocked(sender) -> true
            else -> {
                val contact = runCatching { chatRepository.getContact(sender) }.getOrNull()
                if (contact != null) {
                    if (!chatRepository.isMessageRequest(contact, myAddress)) return false
                    true
                } else if (chatRepository.markRequestNotified(sender)) {
                    notificationHelper.showMessageRequest(sender, dedupeTxId = txId)
                    runCatching { kotlinx.coroutines.withTimeoutOrNull(8_000) { chatRepository.syncInbox() } }
                    return true
                } else {
                    true
                }
            }
        }
        if (silent) txId?.let { notificationHelper.claimWithoutNotifying(it) }
        return silent
    }

    /**
     * When the message this push is about was sent, in milliseconds: the server's timestamp is
     * the honest answer, and arrival time is the fallback when it is missing or unreadable.
     * Seconds are accepted too - the field has been written both ways.
     */
    private fun pushSentAtMs(data: Map<String, String>): Long {
        val raw = data["timestamp"]?.trim()?.toLongOrNull() ?: return System.currentTimeMillis()
        if (raw <= 0) return System.currentTimeMillis()
        return if (raw < 100_000_000_000L) raw * 1000 else raw
    }

    /** Base64 → EncryptedMessage → ChaCha20-Poly1305 decrypt with the wallet key. Null on failure
     * (no encrypted body attached, e.g. an oversized/media message; or a tag/key mismatch). An
     * iPhone photo or voice note in the binary media frame opens as its media-file JSON here too
     * (KasiaCipher.decrypt, iOS NotificationService.mediaFrameEnvelope), so its banner is "Sent a
     * photo" / "Sent a voice message" as before. */
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

    /** The banner body for a decrypted 1:1 message - the same rule the in-app poller uses
     *  (ChatRepository), so both paths word one message alike (audit AND-007). */
    private fun notificationPreview(plaintext: String): String =
        com.kachat.app.util.dmNotificationText(plaintext, notificationHelper.dmNotificationLabels())

    private suspend fun contactTitle(senderId: String, default: String): String {
        val contact = runCatching { chatRepository.getContact(senderId) }.getOrNull()
        // Who they are in the app: a name you gave them (chat or Address Book), else their
        // .kachat name, else the short address - so a push from someone with a .kachat name says
        // it, not their address (iOS dcb329a, ContactsManager.displayName).
        if (contact != null) {
            return com.kachat.app.models.addressDisplayName(
                senderId,
                alias = contact.alias,
                knsName = contact.knsName?.takeIf { com.kachat.app.services.KnsService.SHOWS_DOMAIN_NAMES_AS_IDENTITY },
            )
        }
        return AddressBookManager.nameFor(senderId) ?: default.takeLast(12)
    }

    /**
     * A chat payment's body under the sender's name: "Sent you 1.5 KAS", the amount without
     * trailing zeros; with no amount on the push, "Sent you a payment" (iOS dcb329a
     * NotificationService.handlePayment). TKAS on testnet.
     */
    private fun paymentPushBody(amount: String?): String {
        val text = amount?.trim().orEmpty()
        val sompi = text.toLongOrNull() ?: text.toDoubleOrNull()?.toLong()
        if (sompi == null || sompi <= 0) return getString(com.kachat.app.R.string.push_sent_you_payment)
        return com.kachat.app.util.KaspaUnit.label(
            getString(com.kachat.app.R.string.push_sent_you_kas, com.kachat.app.util.KaspaUnit.plain(sompi))
        )
    }

    private suspend fun contactOverride(senderId: String): ContactNotificationMode? {
        val contact = runCatching { chatRepository.getContact(senderId) }.getOrNull()
        return ContactNotificationMode.fromName(contact?.notificationOverride)
    }

    companion object {
        // Same tag as PushRegistrationManager: `adb logcat -s KaChatPush` shows registrations,
        // token rotations, and every received push in one stream.
        private const val TAG = PushRegistrationManager.TAG

        /** How long a push may spend on a group catch-up before the generic banner posts. */
        private const val GROUP_SYNC_BUDGET_MS = 8_000L

        /** How long one read off chain may take (iOS NotificationService fetchChainPreview,
         *  timeoutInterval 8). */
        private const val CHAIN_READ_BUDGET_MS = 8_000L

        /** How long a payload-less push's chain reads keep trying, from the push's arrival (iOS
         *  ca013fc8 `chainReadDeadline`). */
        private const val CHAIN_READ_DEADLINE_MS = 20_000L

        /** The 1:1 push types whose sender can be this wallet itself. */
        private val OWN_SEND_PUSH_TYPES = setOf("contextual", "payment", "handshake")
    }
}
