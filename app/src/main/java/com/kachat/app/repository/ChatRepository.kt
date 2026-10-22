package com.kachat.app.repository

import android.util.Log
import com.google.gson.Gson
import com.kachat.app.models.ContactEntity
import com.kachat.app.models.ContactNotificationMode
import com.kachat.app.models.DeletedContactEntity
import com.kachat.app.models.HandshakePayload
import com.kachat.app.models.MessageEntity
import com.kachat.app.models.MessageSyncCursorEntity
import com.kachat.app.models.PhotoAutoDisplayMode
import com.kachat.app.models.ReactionEntity
import com.kachat.app.models.UnreadCount
import com.kachat.app.models.displayName
import com.kachat.app.services.ContextualMessageIndexerResponse
import com.kachat.app.services.HandshakeIndexerResponse
import com.kachat.app.services.KasiaIndexerApi
import com.kachat.app.services.KaspaRestApi
import com.kachat.app.services.MeteredNetwork
import com.kachat.app.services.NetworkService
import com.kachat.app.services.NotificationHelper
import com.kachat.app.services.PushState
import com.kachat.app.services.TransactionInput
import com.kachat.app.services.TransactionResponse
import com.kachat.app.services.WalletManager
import com.kachat.app.services.database.KaChatDatabase
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KasiaCipher
import com.kachat.app.util.KaspaUnit
import com.kachat.app.util.MessageProtocol
import com.kachat.app.util.MessageReaction
import com.kachat.app.util.MessageReply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

private fun String.hexToBytes(): ByteArray {
    if (isEmpty()) return ByteArray(0)
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ChatRepository @Inject constructor(
    private val database: KaChatDatabase,
    private val networkService: NetworkService,
    private val walletManager: WalletManager,
    private val settingsRepository: AppSettingsRepository,
    private val notificationHelper: NotificationHelper,
    // Consulted at the notification-posting sites below AND by the poll-loop gate in init:
    // while native FCM push is active AND the app is backgrounded, the server is the
    // notification source for 1:1 handshakes/messages/payments (the iOS repo's PUSH_EXTENSIONS.md §4) and the
    // 2s sync loop pauses entirely (see the gate in init). While the app is foregrounded the
    // local poll posts its own banners (only the open thread is suppressed, inside
    // NotificationHelper) — txId dedupe there collapses a racing push for the same message.
    private val pushState: PushState,
    // Drives the poll loop's metered cadence tiers (see the loop in init) — cellular polls
    // slower than WiFi, in-chat and idle alike.
    private val meteredNetwork: MeteredNetwork,
    // Lazy because NextcloudSyncService depends (via the export service) on ChatRepository —
    // a direct circular constructor dependency Dagger can't resolve. Lazy<T> defers
    // instantiation past construction time, breaking the cycle while still letting this class
    // signal message activity into it (the continuous Nextcloud sync debounce).
    private val nextcloudSyncServiceLazy: dagger.Lazy<com.kachat.app.services.NextcloudSyncService>,
    // Lazy for the same reason: CallService sends its envelopes through WalletService, which
    // depends on this repository. Incoming call envelopes are handed to it from the ingest.
    private val callServiceLazy: dagger.Lazy<com.kachat.app.services.CallService>,
    // Lazy: ContactNoteService sends through WalletService, which itself depends on this.
    private val contactNotesLazy: dagger.Lazy<com.kachat.app.services.ContactNoteService>,
    private val onboardingGate: com.kachat.app.services.OnboardingGate,
    private val peerAliasStore: com.kachat.app.services.PeerAliasStore,
    /** Message Requests state - accepted, private, blocked (NO_HANDSHAKE_MESSAGING.md). */
    private val chatRequestStore: ChatRequestStore,
    /** 1:1 messages a push told us about, until they are stored (iOS 60c9fd0). */
    private val pushedMessageQueue: com.kachat.app.services.PushedMessageQueue,
    /** Cold Storage (KasSigner) watch accounts - their addresses are ours to a payment_notice
     *  (IOS-083, [ownAddressesForNotices]). */
    private val coldStorageManager: com.kachat.app.services.ColdStorageManager,
    /** Txids of the payment bubbles received payment_notices filed - only the notice's own chain
     *  check may confirm, move or remove them (IOS-083 run 11, [fileNoticeBubble]). */
    private val noticeBubbleStore: com.kachat.app.services.NoticeBubbleStore,
    /** Domain names for the KNS transfer messages the payment sync files ([ingestKnsTransferMessage]).
     *  Lazy like the services above, so it never joins a construction cycle. */
    private val knsServiceLazy: dagger.Lazy<com.kachat.app.services.KnsService>,
    /** The node a received payment the REST answer can't settle is confirmed with
     *  ([nodeConfirmsIncomingPayment]). Lazy like the services above. */
    private val nodePoolManagerLazy: dagger.Lazy<com.kachat.app.services.NodePoolManager>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()

    init {
        // Real-time-ish receive for the LIVE UI while the app is open: poll the indexer
        // periodically, mirroring NodePoolManager's probe-loop pattern. syncMessages()
        // already no-ops safely if there's no active wallet yet. Each cycle is just a
        // couple of lightweight indexer GETs, so this can run much faster than a typical
        // polling interval — Kaspa's block time is far faster than 15s ever reflected.
        //
        // Backgrounded, this loop normally freezes with the process (nothing keeps it alive,
        // by design) — but on battery-exempted devices the process can live on for hours, and
        // an un-gated loop would keep polling every 2s the whole time. So the gate below
        // pauses it while the app is BACKGROUNDED and native FCM push is active: the push
        // service is the delivery/notification path then, and the message store catches up
        // via the immediate sync the moment the gate reopens (foreground, or push turning
        // unreliable/unregistered — PushRegistrationManager flips PushState.pushActive false
        // on any registration failure, so the loop resumes as the only delivery path exactly
        // as before). Without FCM (no google-services.json) pushActive can never turn true
        // and this gate never engages — behavior is identical to today's.
        scope.launch {
            try {
                pruneOldMessages() // once on startup, matching iOS's on-launch retention prune
            } catch (e: Exception) {
                Log.w("ChatRepository", "Startup prune failed", e)
            }
            // Consecutive cycles in which the indexer errored (see noteIndexerError) — drives
            // the exponential backoff below so a dead indexer doesn't get the full fan-out
            // retried at the fast cadence forever.
            var consecutiveIndexerFailures = 0
            while (true) {
                // Pause while backgrounded with healthy push; resume instantly (suspend on the
                // combined flow, no polling) on foreground or push loss, then sync immediately
                // so the reopened app's chat list is current without waiting a cycle.
                if (!notificationHelper.isAppInForeground && pushState.isActive) {
                    // Payments made while paused are never pushed (a plain KAS payment gets no
                    // push): the first sync back fetches them from here on (iOS 20155aa).
                    requestPaymentCatchUp(lookBackMs = 2 * 60 * 1000L)
                    combine(notificationHelper.appForegroundFlow, pushState.pushActive) { foreground, pushActive ->
                        foreground || !pushActive
                    }.first { it }
                }
                // Guarded: this loop is the ONLY live 1:1 receive path while the app is open,
                // and it runs for the whole process lifetime. syncMessages() catches its own
                // network errors, but one uncaught throw anywhere in it (a DataStore read, a
                // Room call outside the per-item guards) would kill this coroutine and
                // silently stop all live chat refresh until the process is restarted.
                // Nothing syncs while the setup wizard is on screen - an import's from-genesis
                // sync of every contact made typing through it crawl. Suspends here (no polling)
                // until the wizard releases the gate.
                onboardingGate.awaitOpen()
                sawIndexerErrorThisCycle = false
                try {
                    syncMessages(fromPollLoop = true)
                } catch (e: Exception) {
                    sawIndexerErrorThisCycle = true
                    Log.w("ChatRepository", "Poll-loop sync cycle failed", e)
                }
                consecutiveIndexerFailures =
                    if (sawIndexerErrorThisCycle) consecutiveIndexerFailures + 1 else 0

                // Cadence tiers: the fast tick exists solely so a conversation ON SCREEN feels
                // live; with no chat open the chat list only needs to stay fresh-ish, and on
                // metered (cellular) networks both tiers relax further. WiFi in-chat keeps the
                // original 2s exactly.
                val metered = meteredNetwork.isMetered
                val baseDelay = if (notificationHelper.isChatOpen) {
                    if (metered) POLL_INTERVAL_IN_CHAT_METERED_MS else POLL_INTERVAL_MS
                } else {
                    if (metered) POLL_INTERVAL_IDLE_METERED_MS else POLL_INTERVAL_IDLE_MS
                }
                // Exponential backoff while the indexer is erroring: double per consecutive
                // failed cycle, capped, reset by the first clean cycle.
                var delayMs = baseDelay
                repeat(minOf(consecutiveIndexerFailures, 6)) {
                    delayMs = minOf(delayMs * 2, POLL_BACKOFF_CAP_MS)
                }
                delay(delayMs)
            }
        }
    }

    /** Set by the fetch-failure catch blocks each cycle — the poll loop's backoff signal. */
    @Volatile
    private var sawIndexerErrorThisCycle = false

    private fun noteIndexerError() {
        sawIndexerErrorThisCycle = true
    }

    /**
     * Re-scopes to whichever account is active right now, and automatically re-emits if the
     * user switches accounts — every read below is built on top of this so no caller (nor any
     * ViewModel built once at construction time) needs its own account-switch handling.
     */
    private fun <T> scopedToActiveAccount(query: (walletAddress: String) -> Flow<T>, whenNoAccount: T): Flow<T> {
        return walletManager.activeAddressFlow.flatMapLatest { address ->
            if (address == null) flowOf(whenNoAccount) else query(address)
        }
    }

    fun getMessages(contactId: String): Flow<List<MessageEntity>> {
        return scopedToActiveAccount({ address -> database.messageDao().getMessagesForContact(contactId, address) }, emptyList())
    }

    /** The newest [limit] messages of a conversation plus its sticky rows - see the DAO. */
    fun getMessageWindow(contactId: String, limit: Int, unsentLimit: Int): Flow<List<MessageEntity>> {
        return scopedToActiveAccount(
            { address -> database.messageDao().getMessageWindowForContact(contactId, address, limit, unsentLimit) },
            emptyList(),
        )
    }

    /** One page of a conversation's history older than [before], newest first. */
    suspend fun getOlderMessagesPage(contactId: String, before: MessageEntity, limit: Int): List<MessageEntity> {
        val address = try { walletManager.getAddress() } catch (_: Exception) { return emptyList() }
        return database.messageDao().getMessagesForContactBefore(contactId, address, before.blockTimestamp, before.id, limit)
    }

    /** Per contact: first message time and whether you ever sent anything (Message Requests). */
    fun getContactMessageStats(): Flow<List<com.kachat.app.services.database.ContactMessageStats>> =
        scopedToActiveAccount({ address -> database.messageDao().getContactMessageStats(address) }, emptyList())

    /** The active wallet's Message Requests state; re-emits on any change. */
    fun chatRequestStateFlow(): Flow<ChatRequestState> = chatRequestStore.stateFlow()

    fun getLatestMessages(): Flow<List<MessageEntity>> {
        return scopedToActiveAccount({ address -> database.messageDao().getLatestMessagePerContact(address, MessageEntity.HIDDEN_PLACEHOLDERS) }, emptyList())
    }

    /**
     * The chats with a message containing [query] (iOS ChatListView's search: case-insensitive,
     * never a hidden placeholder - cross-device or undecryptable - nothing over 4096 bytes - a
     * media envelope's text is not something you typed).
     */
    suspend fun contactIdsWithMessageContaining(query: String): Set<String> {
        val address = walletManager.getAddress()
        if (address.isEmpty() || query.isEmpty()) return emptySet()
        val rows = database.messageDao().getMessageBodiesForWallet(address)
        return kotlinx.coroutines.withContext(Dispatchers.Default) {
            rows.asSequence()
                .filter { row ->
                    val body = row.plaintextBody ?: return@filter false
                    body.contains(query, ignoreCase = true) &&
                        !MessageEntity.isHiddenPlaceholder(body) &&
                        body.toByteArray(Charsets.UTF_8).size <= 4096
                }
                .map { it.contactId }
                .toSet()
        }
    }

    /** Every message for the active wallet, across all contacts — for chat-history export, not the live UI. */
    suspend fun getAllMessages(): List<MessageEntity> {
        return database.messageDao().getAllMessagesForWallet(walletManager.getAddress())
    }

    fun getUnreadCounts(): Flow<List<UnreadCount>> {
        return scopedToActiveAccount({ address -> database.messageDao().getUnreadCounts(address, MessageEntity.HIDDEN_PLACEHOLDERS) }, emptyList())
    }

    /** One row per contact - their newest reaction across every message in that conversation,
     *  joined to the target message's direction. Backs the chat list's "Reacted to your/their
     *  message" preview. */
    fun getLatestReactions(): Flow<List<com.kachat.app.services.database.LatestReactionRow>> {
        return scopedToActiveAccount({ address -> database.reactionDao().getLatestReactionPerContact(address) }, emptyList())
    }

    suspend fun markAsRead(contactId: String) {
        database.messageDao().markAllAsRead(contactId, walletManager.getAddress())
    }

    /** Chat-list swipe/bulk "Mark as Unread" — see [MessageDao.markLatestAsUnread]. */
    suspend fun markAsUnread(contactId: String) {
        database.messageDao().markLatestAsUnread(contactId, walletManager.getAddress())
    }

    fun getContacts(): Flow<List<ContactEntity>> {
        return scopedToActiveAccount({ address -> database.contactDao().getContacts(address) }, emptyList())
    }

    // MARK: - Message Requests (NO_HANDSHAKE_MESSAGING.md, iOS 9fbcfd7)

    /** Last time the poll path asked the inbox - see [syncMessages]. */
    @Volatile
    private var lastInboxSyncAt = 0L
    private val inboxSyncMutex = kotlinx.coroutines.sync.Mutex()

    /** Whether each indexer answers inbox lookups, by URL. */
    private val inboxSupportByIndexer = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * Whether the configured indexer answers inbox lookups - probed once per indexer URL; an
     * unreachable indexer is asked again next time. An indexer without it would drop a `dm`
     * transaction from its index altogether, so nothing is ever sent tagged to one.
     */
    suspend fun inboxSupported(): Boolean {
        val base = runCatching { settingsRepository.indexerUrl.first() }.getOrNull().orEmpty()
        inboxSupportByIndexer[base]?.let { return it }
        val api = networkService.indexerApi.value ?: return false
        val answer = try {
            val response = api.probeContextualMessagesByInbox(tag = "0".repeat(32))
            response.body()?.close()
            response.errorBody()?.close()
            when {
                response.isSuccessful -> true
                response.code() == 404 -> false
                else -> null
            }
        } catch (e: Exception) {
            null
        } ?: return false
        inboxSupportByIndexer[base] = answer
        return answer
    }

    /**
     * The inbox tag for a message to [address], or null to send it untagged. Only the FIRST
     * message carries it - one tagged message tells the recipient who wrote, and their app
     * fetches everything after it by sender and alias, unlinked. So: not once a tagged message
     * has gone out to them ([ChatRequestState.inboxTagged], set on a successful submit), not once
     * they have written to us, never in a Private chat, and only when the indexer files `dm`.
     *
     * Only a MESSAGE from them counts as "written to us" - a payment does not (iOS 1eb4708).
     * Someone who just sent KAS (a wallet send, or a chat payment) may have no chat with us at
     * all, so an untagged reply would never reach them. Tagging costs nothing there: the payment
     * already links the two addresses on chain.
     */
    suspend fun firstContactInboxTag(address: String): String? {
        val me = runCatching { walletManager.getAddress() }.getOrNull() ?: return null
        return firstContactInboxTagFor(
            address = address,
            me = me,
            state = chatRequestStore.state(me),
            hasReceivedFrom = { lowered -> database.messageDao().hasMessageFromContactIgnoringCase(lowered, me) },
            inboxSupported = { inboxSupported() },
        )
    }

    /** The one tagged first message to [address] is out - nothing after it is tagged. */
    fun noteInboxTagged(address: String) = chatRequestStore.markInboxTagged(address)

    /**
     * Finds people who wrote to this wallet first: asks the indexer for our inbox tag, then pulls
     * each new sender's whole history the normal way (sender + alias). Blocked senders are
     * skipped. Their chat lands in Message Requests ([isMessageRequest]).
     */
    suspend fun syncInbox(
        myAddress: String = runCatching { walletManager.getAddress() }.getOrDefault(""),
        api: KasiaIndexerApi? = networkService.indexerApi.value,
    ) {
        if (myAddress.isEmpty() || api == null) return
        if (!inboxSupported()) return
        if (!inboxSyncMutex.tryLock()) return
        try {
            val since = chatRequestStore.state(myAddress).inboxCursor
            val found = try {
                api.getContextualMessagesByInbox(
                    tag = com.kachat.app.util.InboxTag.compute(myAddress),
                    blockTime = since.takeIf { it > 0 },
                )
            } catch (e: Exception) {
                noteIndexerError()
                Log.w("ChatRepository", "Inbox lookup failed", e)
                return
            }
            if (!myAddress.equals(runCatching { walletManager.getAddress() }.getOrNull(), ignoreCase = true)) return
            // The cursor moves to the newest entry - but never past one whose sender the indexer
            // hasn't resolved yet (it answers with an empty sender until the spent input is
            // known): that entry is asked for again next time, or its sender would never be found
            // (iOS 5d1f724). Gson can leave the field null, hence the nullable reads.
            val (unresolved, resolved) = found.partition { (it.sender as String?).isNullOrBlank() }
            val resolvedNewest = resolved.maxOfOrNull { it.blockTime }
            val nextCursor = unresolved.minOfOrNull { it.blockTime }
                ?.let { minOf((it - 1).coerceAtLeast(0L), resolvedNewest ?: 0L) }
                ?: resolvedNewest
            if (nextCursor != null && nextCursor > since) chatRequestStore.setInboxCursor(myAddress, nextCursor)
            val senders = resolved.map { it.sender }
                .filter { it.isNotBlank() && !it.equals(myAddress, ignoreCase = true) && KaspaAddress.isValid(it) }
                .filterNot { chatRequestStore.isBlocked(it) }
                .distinct()
            // Someone already known is fetched by the ordinary sweep (it has their aliases); only
            // a new sender needs their whole history pulled once.
            for (sender in senders) {
                if (database.contactDao().getContact(sender, myAddress) != null) continue
                val senderPubKeyHex = runCatching {
                    KaspaAddress.decode(sender).second.joinToString("") { "%02x".format(it) }
                }.getOrNull()
                database.contactDao().insert(
                    ContactEntity(
                        id = sender, walletAddress = myAddress, alias = null, knsName = null,
                        publicKeyHex = senderPubKeyHex,
                        // Not accepted yet: out of the push watch list until the user says so.
                        conversationStatus = "pending",
                    )
                )
                syncContextualMessages(myAddress, api, onlyContactIds = setOf(sender))
            }
        } finally {
            inboxSyncMutex.unlock()
        }
    }

    /**
     * A conversation someone else started that the user hasn't accepted - see
     * [ChatRequestStore.isMessageRequest]. Read straight from the database, for the sync and push
     * paths; the chat list derives the same answer from its flows.
     */
    suspend fun isMessageRequest(contact: ContactEntity, myAddress: String = walletManager.getAddress()): Boolean {
        val first = database.messageDao().getFirstMessageForContact(contact.id, myAddress) ?: return false
        return ChatRequestStore.isMessageRequest(
            contactId = contact.id,
            contactAddedAt = contact.addedAt,
            myAddress = myAddress,
            state = chatRequestStore.state(myAddress),
            firstMessageAt = first.blockTimestamp,
            anySent = database.messageDao().hasSentToContact(contact.id, myAddress),
        )
    }

    fun isChatBlocked(address: String): Boolean = chatRequestStore.isBlocked(address)

    /** Push: records [address] as rung with "New message request"; false when it already was. */
    fun markRequestNotified(address: String): Boolean = chatRequestStore.markRequestNotified(address)

    /**
     * Banners from the sync path (a device with no push): a Message Request rings once, "New
     * message request", then stays silent until accepted. True when [contact] is a request, so
     * the caller's ordinary banner is skipped.
     */
    private suspend fun notifyAsMessageRequest(contact: ContactEntity, myAddress: String, txId: String): Boolean {
        if (!isMessageRequest(contact, myAddress)) return false
        if (chatRequestStore.markRequestNotified(contact.id, myAddress)) {
            notificationHelper.showMessageRequest(contact.id, dedupeTxId = txId)
        }
        return true
    }

    /**
     * Accept: the chat joins the chat list. Nothing is sent on chain. Also what writing to or
     * paying someone does - and it lifts a block, since messaging them is consent.
     */
    suspend fun acceptChat(address: String) {
        chatRequestStore.accept(address)
        val myAddress = runCatching { walletManager.getAddress() }.getOrNull() ?: return
        val existing = database.contactDao().getContact(address, myAddress) ?: return
        if (existing.conversationStatus != "active") {
            val activated = existing.copy(conversationStatus = "active")
            database.contactDao().insert(activated)
            noteConversationActivated(existing, activated)
        }
    }

    /**
     * Paying someone watches for their messages (iOS 1eb4708, the routing state
     * `addMessageToConversation` gives the destination of any outgoing payment): the payment
     * opened a chat with them, and their reply must be found even when their app didn't tag it
     * for our inbox (an older client, or Kasia). A contact row is what every sync and sweep
     * covers ([syncContextualMessages]), so one is made when the destination has none yet. The
     * account in use is never one, nor is one of its own spending addresses (iOS skips a move
     * within your own wallet before any routing); another of your own accounts is refused as any
     * auto-add is.
     */
    suspend fun watchPaymentDestination(address: String) {
        val myAddress = runCatching { walletManager.getAddress() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        if (address.isBlank() || address.equals(myAddress, ignoreCase = true)) return
        if (isOwnSpendingAddress(address)) return
        if (database.contactDao().getContact(address, myAddress) != null) return
        addContact(ContactEntity(id = address, walletAddress = myAddress, alias = null, knsName = null, publicKeyHex = null))
    }

    /** Every address this wallet spends from, lowercased: the chatting address and every
     *  revealed spending address, the quantum chain included - the memoized set
     *  ([WalletManager.ownAddressSetLowercased], iOS d80c497). Off the main thread: a miss
     *  costs a seed derivation. */
    private suspend fun ownAddressSet(): Set<String> =
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            runCatching { walletManager.ownAddressSetLowercased() }.getOrDefault(emptySet())
        }

    /** One of the active account's own spending addresses (any revealed index), the chatting
     *  address excluded as before. Off the main thread, like [ownAddressSet]. */
    private suspend fun isOwnSpendingAddress(address: String): Boolean {
        val normalized = address.trim().lowercase()
        if (normalized.isEmpty()) return false
        val chatting = runCatching { walletManager.getActiveAccount()?.address }.getOrNull()?.trim()?.lowercase()
        return normalized != chatting && normalized in ownAddressSet()
    }

    /** An internal move within the own wallet: [sender] is one of the own spending addresses
     *  (the memoized set, iOS d80c497) - but never the chatting address [myAddress] itself, which
     *  keeps its own self-chat handling exactly as before. */
    private fun isOwnMoveSender(sender: String, myAddress: String): Boolean {
        val target = sender.trim().lowercase()
        if (target.isEmpty() || target == myAddress.trim().lowercase()) return false
        return target in runCatching { walletManager.ownAddressSetLowercased() }.getOrDefault(emptySet())
    }

    /**
     * A plain send from the chatting address (Send Kaspa - Chats > + > Send Kaspa, Profile, the
     * Address Book, Donate, a payment request without `chat=1` - From: Chatting Address) is a
     * payment we sent like any other: iOS's payment sync files every outgoing transfer of the
     * chatting address in a chat with its destination ("Sent X KAS"), and `addMessageToConversation`
     * gives that destination routing state (iOS 1eb4708, `isPaymentWeSent`;
     * NO_HANDSHAKE_MESSAGING.md section 4, "We paid B"). So B is watched from now on - their reply
     * arrives even when their app didn't tag it for our inbox - and the send shows in the chat
     * with B. Android's payment sync skips our own outgoing transfers, so it is recorded here, at
     * send time, as a chat payment is.
     *
     * Never for one of your own addresses: the account in use, another account on this device,
     * or one of its spending addresses - moving your own money is not a conversation. A send From
     * a spending address does not come here: iOS's payment sync only sees the chatting address's
     * transfers, so a plain send from a spending address opens no chat and watches no one.
     */
    suspend fun recordPlainSendFromChattingAddress(toAddress: String, txId: String, amountSompi: Long) {
        val myAddress = runCatching { walletManager.getAddress() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        val destination = toAddress.trim()
        if (destination.isBlank() || txId.isBlank()) return
        if (isOwnAccountAddress(destination) || isOwnSpendingAddress(destination)) return
        watchPaymentDestination(destination)
        // Refused (or not added) - no chat to file the send in.
        if (database.contactDao().getContact(destination, myAddress) == null) return
        insertMessage(
            MessageEntity(
                id = txId,
                contactId = destination,
                walletAddress = myAddress,
                type = "pay",
                direction = "sent",
                plaintextBody = "Sent ${KaspaUnit.plain(amountSompi)} ${KaspaUnit.symbol}",
                encryptedPayload = "",
                amountSompi = amountSompi,
                blockTimestamp = System.currentTimeMillis(),
                deliveryStatus = "sent"
            )
        )
    }

    /**
     * Opens (or starts) the 1:1 chat with a KaChat bot, the way Kaspa Hub > KaChat Bots does it
     * (iOS `KaChatBotCard.open`, 82ab31d / 5fcf755). Starting it accepts it, so the bot's replies
     * land in the chat, not in Message Requests; [name] (the bot's) is the contact's name until the
     * person renames it. A chat deleted before starts again: the deletion's block is lifted, but
     * what came before it stays deleted - the tombstone stays as the history floor (iOS
     * `clearDeletionTombstone`), unlike a deliberate [addContact], which drops it.
     * @return false when there is no wallet, or the contact was refused.
     */
    suspend fun openBotChat(address: String, name: String): Boolean {
        val myAddress = runCatching { walletManager.getAddress() }.getOrNull() ?: return false
        database.contactDao().liftDeletedContact(address, myAddress)
        val existing = database.contactDao().getContact(address, myAddress)
        if (existing == null) {
            val created = ContactEntity(id = address, walletAddress = myAddress, alias = name, knsName = null, publicKeyHex = null)
            if (!addContact(created)) return false
        } else if (existing.alias.isNullOrBlank()) {
            addContact(existing.copy(alias = name))
        }
        acceptChat(address)
        return true
    }

    /**
     * Reject: their messages are deleted from this device and the address is blocked - ignored
     * by discovery, fetching and notifications until the user writes to them.
     */
    suspend fun rejectChat(address: String) {
        chatRequestStore.block(address)
        deleteChat(address)
    }

    /**
     * Starting a chat as Private: it never carries the inbox tag, so nothing on chain links the
     * two people. The other side isn't notified; they see it once they start a private chat with
     * this address too (NO_HANDSHAKE_MESSAGING.md §3.1). Starting any chat accepts it.
     */
    fun setPrivateChat(address: String, isPrivate: Boolean) = chatRequestStore.setPrivate(address, isPrivate)

    fun isPrivateChat(address: String): Boolean = chatRequestStore.isPrivate(address)

    /**
     * Permanently deletes [contactId] and every local message with them — replaces the old
     * reversible "archive". Talking to them again requires a fresh handshake, same as a stranger.
     * Records a tombstone first so a future re-handshake's full-history re-sync can't silently
     * resurrect the deleted conversation (see [DeletedContactEntity]'s doc comment).
     *
     * The tombstone row outlives a reopening: a handshake from them that post-dates the deletion
     * lifts it ([DeletedContactEntity.lifted]) and the row stays as its history floor, so what
     * came BEFORE the deletion stays deleted - your own old messages included, which would
     * otherwise make the new request read as a chat you had already accepted (iOS 4b00a5f
     * isDeletedAsOf). An automatic add (a KaPosts tip, chess, names) does not lift it: the chat
     * still counts as deleted for backups. Only a deliberate add ([addContact] with
     * `deliberate`) drops the row and restores everything.
     */
    suspend fun deleteChat(contactId: String) {
        val myAddress = walletManager.getAddress()
        // Your chat with yourself cannot be deleted (iOS ef4f183). A backstop behind the UI,
        // which never offers it.
        if (contactId.equals(myAddress, ignoreCase = true)) return
        // In blockTime clock domain, not wall-clock — see DeletedContactEntity's doc comment for
        // why mixing clocks here previously caused a genuinely new re-handshake to get dropped.
        val lastKnownBlockTime = database.messageDao().getMaxBlockTimestampForContact(contactId, myAddress)
        // Tie-breaker ids for isTombstoned — see DeletedContactEntity.deletedAtTxIds's doc comment.
        val lastKnownTxIds = lastKnownBlockTime
            ?.let { database.messageDao().getMessageIdsAtBlockTimestamp(contactId, myAddress, it) }
            ?: emptyList()
        database.contactDao().markContactDeleted(
            DeletedContactEntity(
                contactId = contactId,
                walletAddress = myAddress,
                deletedAt = lastKnownBlockTime ?: System.currentTimeMillis(),
                deletedAtTxIds = lastKnownTxIds.joinToString(",")
            )
        )
        // A deleted chat is no longer one you accepted: if they start a new one, it's a Message
        // Request like any stranger's. A block stays (iOS 4b00a5f).
        chatRequestStore.forgetChatAcceptance(contactId, myAddress)
        database.messageDao().deleteAllForContact(contactId, myAddress)
        database.reactionDao().deleteAllForContact(contactId, myAddress)
        database.messageEditDao().deleteAllForContact(contactId, myAddress)
        // So a later re-handshake with this same address starts its indexer sync clean instead of
        // resuming from a stale per-contact cursor left over from before the deletion.
        database.messageDao().deleteSyncCursorsForContact(contactId, myAddress)
        database.contactDao().deleteContact(contactId, myAddress)
    }

    /**
     * True if [txId]/[blockTime] is the tombstoned pre-deletion transaction (or predates it) and
     * should be filtered out of a re-sync, rather than a genuinely new interaction that happens to
     * land at the same block_time — see [DeletedContactEntity.deletedAtTxIds]'s doc comment for why
     * a plain `blockTime <= deletedAt` check isn't safe on its own against Kaspa's non-strictly-
     * monotonic per-sender block_time.
     *
     * Mirrors iOS `ContactsManager.isDeletedAsOf` (4b00a5f): a lifted row is still a floor; a
     * [blockTime] of 0 (no time in hand) is suppressed only while the chat is still deleted; and
     * at the deletion instant itself a blank [txId], or a tombstone that recorded no ids, is
     * suppressed.
     */
    private fun isTombstoned(deleted: DeletedContactEntity?, txId: String, blockTime: Long): Boolean {
        if (deleted == null) return false
        if (blockTime <= 0L) return !deleted.lifted
        if (blockTime < deleted.deletedAt) return true
        if (blockTime == deleted.deletedAt) {
            if (txId.isBlank()) return true
            val ids = deleted.deletedAtTxIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (ids.isEmpty()) return true
            return txId in ids
        }
        return false
    }

    /** [contactId]'s deletion tombstone - for a chat reopened after a delete, its history floor
     *  (see [deleteChat]) - or null when it was never deleted. */
    suspend fun deletionFloor(contactId: String): DeletedContactEntity? =
        database.contactDao().getDeletedContact(contactId, walletManager.getAddress())

    /**
     * Whether a backed-up message of a chat that is live again predates its deletion and must
     * stay deleted (iOS 4b00a5f, `isDeletedAsOf` in the Nextcloud import). The same test as
     * [isTombstoned]: the import only gets here for a chat that is not still deleted, so a
     * message with no time in hand is kept, as on iOS.
     */
    fun isBelowDeletionFloor(floor: DeletedContactEntity?, txId: String, blockTime: Long): Boolean =
        isTombstoned(floor, txId, blockTime)

    /**
     * Never one of the user's OWN accounts. Nothing stopped this before: the row is keyed by
     * (id, walletAddress), so a second account of the user's own could sit in the first's contact
     * list reading as a stranger. The auto-add paths make it easy to hit without noticing -
     * tipping or opening a KaPost written from your own other account adds its author silently.
     *
     * Refused at the repository rather than at each caller, because this is the one place every
     * add goes through. Silent: a caller adding a contact it should not is not an error worth
     * interrupting anyone over, and the address is already reachable through the account switcher.
     */
    /**
     * @param deliberate the user chose to add this address themselves - Create Chat, Open Chat on
     *   Chat Info / User Info for someone not yet a contact, a new group's members - where iOS
     *   calls `addContact` with `isAutoAdded: false`. Another of the
     *   user's OWN accounts is refused only for the auto-add paths: tipping or opening a KaPost
     *   written from your second account would otherwise add its author silently. A deliberate
     *   add is different - chatting between your own accounts is a real thing to do (moving
     *   funds, trying the app from a fresh account), and the person typing the address knows
     *   whose it is. The account in use is refused either way: there is no one to talk to.
     * @return false when the add was refused.
     */
    suspend fun addContact(contact: ContactEntity, deliberate: Boolean = false): Boolean {
        val refused = if (deliberate) isActiveWalletAddress(contact.id) else isOwnAccountAddress(contact.id)
        if (refused) {
            Log.w("ChatRepository", "Refusing to add one of the user's own accounts as a contact")
            return false
        }
        // A deliberate add explicitly un-does a prior delete's tombstone, floor included: the
        // tombstone only stops silent resurrection from incoming activity, not the user choosing
        // to message this address again (iOS ContactsManager.addContact). Its cursors go too, so
        // the history the floor kept out is read again on the next sync.
        if (deliberate && database.contactDao().getDeletedContact(contact.id, contact.walletAddress) != null) {
            database.contactDao().deleteDeletedContact(contact.id, contact.walletAddress)
            database.messageDao().deleteSyncCursorsForContact(contact.id, contact.walletAddress)
        }
        val previous = database.contactDao().getContact(contact.id, contact.walletAddress)
        database.contactDao().insert(contact)
        noteConversationActivated(previous, contact)
        return true
    }

    /**
     * The active account's private key, only while it is [address]'s own (iOS dfaba68
     * isCurrentAccountKey) - null once the account has switched. A sync holds its account's
     * address from the start; the key is re-read at the point of use, so without this a sync
     * that spanned a switch derived aliases from, and decrypted with, the NEW account's key and
     * filed the result under the old address. Read it once and use that same key throughout.
     */
    private fun accountKey(address: String): ByteArray? {
        val key = runCatching { walletManager.getPrivateKeyBytes() }.getOrNull() ?: return null
        return key.takeIf { com.kachat.app.util.AccountKey.belongsTo(it, address) }
    }

    /** The account in use right now - the one address that can never be a contact of itself. */
    private fun isActiveWalletAddress(address: String): Boolean {
        val normalized = address.trim().lowercase()
        if (normalized.isEmpty()) return false
        return runCatching { walletManager.getAddress() }.getOrNull()?.trim()?.lowercase() == normalized
    }

    /**
     * True when this address belongs to the user themselves: the wallet in use, or any account
     * saved on this device.
     *
     * Both halves matter. The active wallet is the obvious one; the saved list is the one that
     * leaks - a second account is still "you", and adding it as a contact both clutters the list
     * and invites messaging yourself by a route the app does not otherwise offer.
     */
    private fun isOwnAccountAddress(address: String): Boolean {
        val normalized = address.trim().lowercase()
        if (normalized.isEmpty()) return false
        if (runCatching { walletManager.getAddress() }.getOrNull()?.trim()?.lowercase() == normalized) return true
        return runCatching { walletManager.getAllAccounts() }.getOrDefault(emptyList())
            .any { it.address.trim().lowercase() == normalized }
    }

    /**
     * The moment a conversation becomes mutual, re-read the peer's history from scratch.
     *
     * A contact only exists locally once we have some reason to create one, and
     * [syncContextualMessages] only ever asks the indexer for messages by-sender for addresses
     * that already have a contact row. So everything a peer sent BEFORE that row existed was
     * never requested — not dropped, not undecryptable (incoming messages are ECIES-sealed to our
     * own key with the ephemeral pubkey carried in the message, so our private key alone always
     * opens them; see KasiaCipher.decrypt), just never asked for. Their whole earlier window is
     * still sitting on the indexer.
     *
     * Activation is the trigger because it is the point where that changes:
     *  - accepting their request (`WalletService.acceptHandshake` -> `sendHandshake(isResponse =
     *    true)` -> [addContact] with "active"),
     *  - sending them a handshake yourself,
     *  - their response handshake landing (see [processHandshake]),
     *  - the restore-parity pass finding your own past acceptance (see [syncOutgoingHandshakes]).
     *
     * Dropping the per-(contact, alias) cursors is what makes the re-read possible at all: without
     * it the next fetch resumes from wherever the pending-state sync got to and asks only for
     * what is newer. Re-reading is safe and idempotent - every insert is txId-deduped
     * ([MessageDao.exists]) and a deleted conversation is still protected by [isTombstoned].
     *
     * Only a real transition re-scans. A brand-new contact needs nothing (its cursors are empty,
     * so its first ordinary sync already asks for full history), and the many cosmetic
     * [addContact] calls (renames, avatar/KNS refresh, notification overrides) never move the
     * status and so never trigger this.
     */
    private suspend fun noteConversationActivated(previous: ContactEntity?, updated: ContactEntity) {
        if (updated.conversationStatus != "active") return
        if (previous == null || previous.conversationStatus == "active") return
        rescanContactHistory(updated.id, updated.walletAddress)
    }

    /** Contacts with a [rescanContactHistory] pass in flight, so repeat activations can't stack. */
    private val historyRescansInFlight = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Resets [contactId]'s message sync cursors and immediately re-syncs that one conversation,
     * so the peer's pre-activation history lands without waiting for a sweep. Fire-and-forget on
     * the repository scope: callers are UI actions (accepting a request, sending a handshake) that
     * must not block on a full-history fetch.
     */
    private fun rescanContactHistory(contactId: String, walletAddress: String) {
        val key = "$walletAddress|$contactId"
        if (!historyRescansInFlight.add(key)) return
        scope.launch {
            try {
                database.messageDao().deleteSyncCursorsForContact(contactId, walletAddress)
                // Pinned to the wallet the activation happened under. If the user switched accounts
                // in the meantime, stop: liveBaselineMs below is process-wide and the poll loop
                // owns it for the active account. The cursors are already cleared, so the next
                // sweep under that account finishes the job.
                val active = try { walletManager.getAddress() } catch (e: Exception) { null }
                if (active != walletAddress) return@launch
                val api = networkService.indexerApi.value ?: return@launch
                liveBaselineMs = settingsRepository.liveNotificationBaseline(walletAddress)
                syncContextualMessages(walletAddress, api, onlyContactIds = setOf(contactId))
            } catch (e: Exception) {
                Log.w("ChatRepository", "History re-scan for $contactId failed", e)
            } finally {
                historyRescansInFlight.remove(key)
            }
        }
    }

    // MARK: - Reply-original recovery

    /** Originals this session has already tried to recover, so one that is genuinely gone does
     *  not rewind the contact's cursors on every re-fetch of its reply. */
    private val replyRecoveryAttempted = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * A reply names the txId of the message it answers. When that message is not in the store,
     * the device KNOWS it missed one - the only positive signal of a delivery gap the protocol
     * offers - so it rewinds the contact's cursors to a day before the reply and refetches that
     * one conversation; txId dedupe makes the overlap free. Run on ingest for every new reply,
     * and on demand when a quote is tapped ([force], which retries a known miss). A day rather
     * than genesis because the paged fetch stops at twenty pages; from zero on a long thread it
     * would halt hundreds of messages short of the gap. Mirrors iOS recoverMissingReplyOriginal.
     */
    fun recoverMissingReplyOriginal(contactId: String, replyToId: String, replyBlockTime: Long, force: Boolean) {
        if (!force && !replyRecoveryAttempted.add(replyToId)) return
        replyRecoveryAttempted.add(replyToId)
        scope.launch {
            try {
                val walletAddress = try { walletManager.getAddress() } catch (e: Exception) { return@launch }
                // In the store is not missing (the thread's memory window is a subset of it).
                if (database.messageDao().exists(replyToId, walletAddress)) return@launch
                val floor = (replyBlockTime - REPLY_RECOVERY_REWIND_MS).coerceAtLeast(0L)
                val rewound = database.messageDao().rewindMessageSyncCursors(contactId, walletAddress, floor)
                Log.i(
                    "ChatRepository",
                    "A reply quotes ${replyToId.take(16)}, which this device never received - " +
                        "rewound $rewound cursor(s) for ${contactId.takeLast(10)} to recover it",
                )
                val api = networkService.indexerApi.value ?: return@launch
                syncContextualMessages(walletAddress, api, onlyContactIds = setOf(contactId))
            } catch (e: Exception) {
                Log.w("ChatRepository", "Reply-original recovery for $replyToId failed", e)
            }
        }
    }

    /**
     * Every chat of the active wallet that is still deleted — carried in backups so restores skip
     * deleted chats. A chat whose tombstone was lifted ([DeletedContactEntity.lifted], iOS
     * 4b00a5f) is live again: its row stays only as the history floor (see [deleteChat]), so it
     * is backed up like any other chat instead. A contact an automatic add recreated does not
     * lift it: that chat still counts as deleted, as on iOS.
     */
    suspend fun getAllDeletedContactIds(): List<String> =
        database.contactDao().getAllDeletedContactIds(walletManager.getAddress())

    /** Whether the chat with [contactId] is still deleted (iOS `isAddressDeleted`) — restores
     *  must never resurrect it. A lifted one is not (its tombstone is only a floor now). */
    suspend fun hasDeletionTombstone(contactId: String): Boolean {
        val deleted = database.contactDao().getDeletedContact(contactId, walletManager.getAddress())
        return deleted != null && !deleted.lifted
    }

    /**
     * 5.2 (iOS 509c0fe): a contact whose name is just a .kas domain goes back to unnamed, and
     * stored .kas names are dropped, so everyone without a .kachat name shows as their address
     * (KnsService.SHOWS_DOMAIN_NAMES_AS_IDENTITY). A name linked from the phone's Contacts app is
     * left alone. Every load, not once (iOS ce0b636): a .kas name can come back later - restored
     * from a Nextcloud backup another platform wrote, or by an older build - and it must not stay
     * (a removed one kept reappearing). The person is then shown by their .kachat name or address
     * until named.
     */
    suspend fun clearKasDomainNames(walletAddress: String) {
        for (contact in database.contactDao().getContacts(walletAddress).first()) {
            val aliasIsDomain = com.kachat.app.models.isKasDomainName(contact.alias) &&
                !(contact.systemContactId != null && contact.alias == contact.systemContactName)
            if (!aliasIsDomain && contact.knsName == null) continue
            database.contactDao().insert(
                contact.copy(alias = if (aliasIsDomain) null else contact.alias, knsName = null)
            )
        }
    }

    /** See ContactDao.clearKnsAvatars. */
    suspend fun clearStoredKnsAvatars(): Int = database.contactDao().clearKnsAvatars()

    suspend fun getContact(id: String): ContactEntity? {
        return database.contactDao().getContact(id, walletManager.getAddress())
    }

    suspend fun insertMessage(message: MessageEntity) {
        // Every delivery path ends here: a message from the other network's address (a mainnet
        // push arriving on testnet - the same key decrypts it) is never filed (iOS df52425).
        if (!com.kachat.app.util.KaspaNetwork.isOnActiveNetwork(message.contactId)) {
            Log.i("ChatRepository", "Dropped a message from the other network")
            return
        }
        database.messageDao().insert(message)
        scheduleAutoBackupIfEnabled()
    }

    /** [insertMessage] that never replaces a stored row ([MessageDao.insertIfAbsent]): false when
     *  nothing was filed - the id is stored already, or the row is from the other network. */
    private suspend fun insertMessageIfAbsent(message: MessageEntity): Boolean {
        if (!com.kachat.app.util.KaspaNetwork.isOnActiveNetwork(message.contactId)) {
            Log.i("ChatRepository", "Dropped a message from the other network")
            return false
        }
        if (database.messageDao().insertIfAbsent(message) == -1L) return false
        scheduleAutoBackupIfEnabled()
        return true
    }

    /**
     * Automatic cloud-sync signal — every new message (sent or received, from any insertion
     * path, since all of them funnel through [insertMessage]) is reported to
     * [com.kachat.app.services.NextcloudSyncService]. The service owns its own debounce,
     * per-wallet gating, wallet snapshotting, and WorkManager fallback, and no-ops when no
     * account is connected or the wallet's automatic-sync toggle is off — so the call is cheap.
     */
    private fun scheduleAutoBackupIfEnabled() {
        nextcloudSyncServiceLazy.get().noteMessageActivity()
    }

    /**
     * Message retention pruning — permanently deletes messages older than the configured window
     * for the active account, whenever retention isn't FOREVER. A device-level rule, as the
     * Storage hub presents it, and as iOS's `MessageStore.applyRetention` prunes regardless of
     * cloud sync state.
     */
    suspend fun pruneOldMessages() {
        val retention = settingsRepository.backupRetention.first()
        val cutoff = retention.cutoffMillis(System.currentTimeMillis()) ?: return
        val myAddress = try { walletManager.getAddress() } catch (e: Exception) { return }
        // Before trimming: a chat from before Message Requests must not turn into one because
        // its early messages are gone (iOS 60c9fd0).
        grandfatherPreexistingChats(myAddress)
        database.messageDao().deleteOlderThan(myAddress, cutoff)
    }

    suspend fun messageExists(id: String): Boolean {
        return database.messageDao().exists(id, walletManager.getAddress())
    }

    // -------------------------------------------------------------------------
    // Provisional-send reconciliation
    //
    // The optimistic send flow inserts a placeholder row under a local id
    // ("pending_<uuid>") BEFORE the broadcast returns, then swaps it for the real
    // txId row. If the process dies (or the send coroutine is cancelled, or the
    // broadcast times out locally but lands on-chain) between those two steps, the
    // placeholder is orphaned — and when the same message later arrives under its
    // real txId via a backup mirror import (another device saw it on-chain and
    // uploaded it), the plain txId-exists dedupe can't connect the two: the user
    // sees the delivered bubble AND a stuck "sending…" twin. These three helpers
    // close every side of that hole:
    //   * findProvisionalOutgoingMatch + upgradeProvisionalMessage — import side;
    //   * finalizeProvisionalMessage — send-completion side;
    //   * repairStuckProvisionalMessages — one-time sweep healing pairs that
    //     already exist from before the fix.
    // -------------------------------------------------------------------------

    /** One message row by id for the active account, or null. */
    suspend fun getMessage(id: String): MessageEntity? =
        database.messageDao().getById(id, walletManager.getAddress())

    /** Outcome of matching an incoming outgoing-direction archive row against local in-flight placeholders. */
    sealed class ProvisionalMatch {
        /** A placeholder with identical (whitespace-trimmed) content — certainly the same message. */
        data class Exact(val row: MessageEntity) : ProvisionalMatch()

        /** No content match, but exactly ONE placeholder is in the window and the archive row is
         *  not older than it — treated as the same message with drifted content. */
        data class Sole(val row: MessageEntity) : ProvisionalMatch()

        /** Several placeholders could be it and none matches by content — do NOT collapse. */
        object Ambiguous : ProvisionalMatch()

        /** No placeholder in the window at all. */
        object None : ProvisionalMatch()
    }

    /**
     * Matches an outgoing archive row (real txId, [plaintextBody]/[nearTimestamp] from the
     * archive) against this conversation's still-provisional ("pending_<uuid>"-id, pending or
     * failed) outgoing rows, within [PROVISIONAL_MATCH_WINDOW_MS] of [nearTimestamp]
     * (0/negative skips the window check). Preference order per the dedupe rules:
     * exact trimmed-content equality first (oldest such row), then the sole-candidate fallback
     * ([ProvisionalMatch.Sole]) which additionally requires the archive row to be no older than
     * the placeholder (minus [PROVISIONAL_CLOCK_SKEW_MS] of clock skew). Multiple candidates
     * with no content match report [ProvisionalMatch.Ambiguous] so the caller inserts normally
     * and logs rather than guessing — two genuinely different messages must never collapse.
     */
    suspend fun matchProvisionalOutgoing(contactId: String, plaintextBody: String?, nearTimestamp: Long): ProvisionalMatch {
        val candidates = database.messageDao()
            .getProvisionalOutgoingForContact(contactId, walletManager.getAddress())
            .filter { nearTimestamp <= 0L || kotlin.math.abs(it.blockTimestamp - nearTimestamp) <= PROVISIONAL_MATCH_WINDOW_MS }
        if (candidates.isEmpty()) return ProvisionalMatch.None

        val body = plaintextBody?.trim()
        if (!body.isNullOrEmpty()) {
            candidates.firstOrNull { it.plaintextBody?.trim() == body }?.let { return ProvisionalMatch.Exact(it) }
        }
        val sole = candidates.singleOrNull() ?: return ProvisionalMatch.Ambiguous
        val archiveIsNewer = nearTimestamp <= 0L || nearTimestamp >= sole.blockTimestamp - PROVISIONAL_CLOCK_SKEW_MS
        return if (archiveIsNewer) ProvisionalMatch.Sole(sole) else ProvisionalMatch.None
    }

    /**
     * Import-side collapse: the archive carries the confirmed copy ([imported], id = real txId)
     * of a message this device still holds as [provisional]. The placeholder is upgraded in
     * place — its own richer fields (amountSompi for a payment, the exact local body) survive,
     * the id becomes the real txId, the timestamp becomes the chain's, and the status becomes
     * "sent" — and the placeholder row is removed. Insert-then-delete on purpose: a crash
     * between the two leaves both rows, which [repairStuckProvisionalMessages] heals, whereas
     * the reverse order could lose the message entirely.
     */
    suspend fun upgradeProvisionalMessage(provisional: MessageEntity, imported: MessageEntity) {
        database.messageDao().insert(
            provisional.copy(
                id = imported.id,
                deliveryStatus = "sent",
                blockTimestamp = if (imported.blockTimestamp > 0) imported.blockTimestamp else provisional.blockTimestamp,
                encryptedPayload = provisional.encryptedPayload.ifEmpty { imported.encryptedPayload },
                isRead = true
            )
        )
        database.messageDao().deleteById(provisional.id, provisional.walletAddress)
        scheduleAutoBackupIfEnabled()
        Log.i("ChatRepository", "Upgraded provisional ${provisional.id.take(24)} to txId=${imported.id.take(16)} (exact content match)")
    }

    /**
     * Sole-candidate collapse (see [ProvisionalMatch.Sole]): the archive's delivered row wins as
     * written (its content is the confirmed on-chain form), the lone in-flight placeholder is
     * removed. If the placeholder was actually a different in-flight send, its own finalize
     * re-inserts the real row when the broadcast returns, so no message can be lost.
     */
    suspend fun collapseProvisionalInto(provisional: MessageEntity, imported: MessageEntity) {
        database.messageDao().insert(imported)
        database.messageDao().deleteById(provisional.id, provisional.walletAddress)
        scheduleAutoBackupIfEnabled()
        Log.i("ChatRepository", "Collapsed sole provisional ${provisional.id.take(24)} into imported txId=${imported.id.take(16)}")
    }

    /**
     * Send-completion side: swaps the optimistic placeholder for the confirmed row. If a mirror
     * import already inserted the txId row (the import won the race), the two are MERGED — the
     * imported row's chain blockTimestamp is kept, the local row's richer fields (plaintext
     * body, encrypted payload, payment amount) win — instead of blindly overwriting, and the
     * placeholder is deleted either way so a pending twin can never linger. Insert-then-delete
     * for the same crash-safety reason as [upgradeProvisionalMessage].
     */
    suspend fun finalizeProvisionalMessage(provisionalId: String, final: MessageEntity) {
        val existing = database.messageDao().getById(final.id, final.walletAddress)
        val merged = if (existing == null) final else existing.copy(
            plaintextBody = final.plaintextBody ?: existing.plaintextBody,
            encryptedPayload = final.encryptedPayload.ifEmpty { existing.encryptedPayload },
            amountSompi = final.amountSompi ?: existing.amountSompi,
            deliveryStatus = "sent"
        )
        database.messageDao().insert(merged)
        database.messageDao().deleteById(provisionalId, final.walletAddress)
        scheduleAutoBackupIfEnabled()
        // Delivered in a chat with no handshake: leave a note a fresh import can find it by.
        if (merged.direction == "sent") contactNotesLazy.get().onMessageDelivered(merged.contactId)
        if (existing != null) {
            Log.i("ChatRepository", "Finalize merged into import-inserted row txId=${final.id.take(16)} (import won the race)")
        }
    }

    /**
     * Repair sweep for stuck pairs that already exist: an orphaned provisional outgoing row
     * whose delivered sibling — same conversation, same trimmed content, real txId, status
     * "sent", within [PROVISIONAL_MATCH_WINDOW_MS] — is also present. The placeholder is
     * deleted; the delivered row is the message. Runs on EVERY sync cycle (the query is a cheap
     * indexed-by-wallet scan and almost always returns nothing), so a pair created after
     * startup — e.g. an import that raced this cycle — heals within seconds, not on the next
     * app launch. Placeholders younger than [PROVISIONAL_REPAIR_MIN_AGE_MS] are never touched:
     * a genuinely in-flight send must not be swept while its broadcast is still returning.
     *
     * Deliberately NOT widened to the sole-candidate rule the importer uses: the importer holds
     * the delivered archive row in hand, so collapsing keeps the content either way, while this
     * sweep would be deleting a stuck row that may hold content that never made it on-chain —
     * without a content-equal delivered sibling there is no proof the message survives, so it
     * stays and keeps its Retry affordance.
     */
    private suspend fun repairStuckProvisionalMessages(myAddress: String) {
        try {
            val now = System.currentTimeMillis()
            for (row in database.messageDao().getProvisionalOutgoingForWallet(myAddress)) {
                if (now - row.syncedAt < PROVISIONAL_REPAIR_MIN_AGE_MS) continue
                val body = row.plaintextBody?.trim().takeUnless { it.isNullOrEmpty() } ?: continue
                val hasDelivered = database.messageDao().hasDeliveredDuplicate(
                    row.contactId, myAddress, body, row.blockTimestamp, PROVISIONAL_MATCH_WINDOW_MS
                )
                if (hasDelivered) {
                    database.messageDao().deleteById(row.id, myAddress)
                    Log.i("ChatRepository", "Sweep collapsed stuck provisional ${row.id.take(24)} (delivered sibling with same content exists)")
                }
            }
        } catch (e: Exception) {
            Log.w("ChatRepository", "Stuck-provisional repair sweep failed", e)
        }
    }

    suspend fun updateMessageStatus(id: String, status: String) {
        database.messageDao().updateStatus(id, walletManager.getAddress(), status)
    }

    suspend fun deleteMessage(id: String) {
        database.messageDao().deleteById(id, walletManager.getAddress())
    }

    /** Replaces any previous reaction [reactorAddress] left on [targetTxId] with [emoji] - one reaction per (message, reactor).
     *  [walletAddress] pins which account's row this writes - sync paths MUST pass the address the
     *  sync cycle captured (see processContextualMessage's doc comment on mid-switch stamping);
     *  null (UI send paths) means the currently active account. */
    suspend fun upsertReaction(targetTxId: String, reactorAddress: String, contactId: String, emoji: String, reactionTxId: String?, blockTimestamp: Long, deliveryStatus: String = "sent", failedAction: String? = null, walletAddress: String? = null) {
        database.reactionDao().upsertReaction(
            ReactionEntity(
                targetTxId = targetTxId,
                walletAddress = walletAddress ?: walletManager.getAddress(),
                reactorAddress = reactorAddress,
                emoji = emoji,
                reactionTxId = reactionTxId,
                blockTimestamp = blockTimestamp,
                contactId = contactId,
                deliveryStatus = deliveryStatus,
                failedAction = failedAction
            )
        )
    }

    /** See [upsertReaction]'s [walletAddress] doc — sync paths pass the captured address, UI paths pass null. */
    suspend fun removeReaction(targetTxId: String, reactorAddress: String, walletAddress: String? = null) {
        database.reactionDao().deleteReaction(targetTxId, walletAddress ?: walletManager.getAddress(), reactorAddress)
    }

    fun getReactionsForContact(contactId: String): Flow<List<ReactionEntity>> {
        return database.reactionDao().getReactionsForContact(contactId, walletManager.getAddress())
    }

    // -------------------------------------------------------------------------
    // Message edits — see MessageEdit / MessageEditEntity. An edit never touches the message row:
    // it is stored here and applied when the bubble is drawn, so the original transaction's
    // plaintext stays exactly what went on chain.
    // -------------------------------------------------------------------------

    fun getEditsForContact(contactId: String): Flow<List<com.kachat.app.models.MessageEditEntity>> {
        return scopedToActiveAccount(
            { address -> database.messageEditDao().getEditsForContact(contactId, address) },
            emptyList(),
        )
    }

    /** The local user's own edit, applied at once (pending) and updated with its outcome — the
     *  sibling of [upsertReaction]'s optimistic path. */
    suspend fun upsertOwnEdit(
        targetTxId: String,
        contactId: String,
        text: String,
        editTxId: String?,
        blockTimestamp: Long,
        deliveryStatus: String
    ) {
        val myAddress = walletManager.getAddress()
        database.messageEditDao().upsertEdit(
            com.kachat.app.models.MessageEditEntity(
                targetTxId = targetTxId,
                walletAddress = myAddress,
                editorAddress = myAddress,
                text = text.take(com.kachat.app.util.MessageEdit.MAX_LENGTH),
                editTxId = editTxId,
                blockTimestamp = blockTimestamp,
                contactId = contactId,
                deliveryStatus = deliveryStatus
            )
        )
    }

    /**
     * An edit envelope that arrived as a message (the contact's, or our own echoed back). It
     * counts only if the editor sent the message it names: the contact may edit the contact's
     * messages, this wallet its own — never the other way round. Newest by block time wins,
     * except that the local user's own in-flight edit is always replaced by its own outcome.
     */
    suspend fun applyIncomingEdit(
        edit: com.kachat.app.util.MessageEditContent,
        editorIsMe: Boolean,
        contactId: String,
        editTxId: String,
        blockTime: Long,
        walletAddress: String? = null
    ) {
        val myAddress = walletAddress ?: walletManager.getAddress()
        val target = database.messageDao().getById(edit.targetTxId, myAddress) ?: return
        if ((target.direction == "sent") != editorIsMe) return
        if (!com.kachat.app.util.MessageEdit.isEditable(target.plaintextBody)) return
        val existing = database.messageEditDao().getEdit(edit.targetTxId, myAddress)
        if (existing != null && existing.deliveryStatus == "sent" &&
            existing.blockTimestamp > blockTime && existing.editTxId != editTxId) return
        database.messageEditDao().upsertEdit(
            com.kachat.app.models.MessageEditEntity(
                targetTxId = edit.targetTxId,
                walletAddress = myAddress,
                editorAddress = if (editorIsMe) myAddress else contactId,
                text = edit.text.take(com.kachat.app.util.MessageEdit.MAX_LENGTH),
                editTxId = editTxId,
                blockTimestamp = blockTime,
                contactId = contactId,
                deliveryStatus = "sent"
            )
        )
    }

    /** Chats re-fetched + how many received messages exist for the scope once the resync lands — the success summary of a wipe-and-resync. */
    data class IncomingResyncResult(val chatCount: Int, val messageCount: Int)

    /**
     * The account this destructive flow was started for must still be the active one — a
     * mid-flow account switch would otherwise wipe/decrypt with the NEW account's keys while
     * writing rows pinned to the old address. Thrown as a plain failure the caller surfaces.
     */
    private fun ensureStillActive(address: String) {
        val current = try { walletManager.getAddress() } catch (e: Exception) { null }
        if (current != address) {
            throw IllegalStateException("The active account changed. Switch back to that account and try again.")
        }
    }

    /**
     * Wipe half of "Wipe and re-sync incoming messages" — deletes received messages for
     * [address] (sent messages, contacts, and the wallet itself are untouched) and resets the
     * sync cursors so [resyncIncomingMessages] re-fetches full history instead of picking up
     * where the now-deleted local cache left off. Matches iOS's `wipeIncomingMessagesAndResync`.
     *
     * [contactIds] scopes it: null wipes every 1:1 conversation and resets every cursor
     * (the payment-history scan, handshake block_time cursor, and every per-contact-per-alias
     * message cursor — see [MessageSyncCursorEntity]); a list wipes only those conversations'
     * received messages and resets only their per-contact cursors. The handshake cursor is
     * wallet-global (no per-contact variant exists), so a scoped wipe rewinds it too — the
     * re-scan is purely additive for unselected chats because their handshake rows still exist
     * and every insert is txId-deduped. The re-sync reads the whole payment history either way
     * (iOS `wipeAndResyncIncomingMessages` phase 3, from the retention cutoff), which is just as
     * additive for unselected chats.
     */
    suspend fun wipeIncomingMessages(address: String, contactIds: List<String>?) {
        ensureStillActive(address)
        if (contactIds == null) {
            database.messageDao().deleteReceivedForWallet(address)
            database.messageDao().deleteSyncCursorsForWallet(address)
            settingsRepository.setPaymentHistoryScanned(address, false)
        } else {
            if (contactIds.isEmpty()) return
            database.messageDao().deleteReceivedForContacts(address, contactIds)
            database.messageDao().deleteSyncCursorsForContacts(address, contactIds)
        }
        settingsRepository.setHandshakeSyncCursor(address, 0L)
    }

    /**
     * Re-fetch half of "Wipe and re-sync incoming messages" — the same passes as [syncMessages]
     * (handshakes in/out, contextual messages, payments) but pinned to the [address] the flow
     * started with, scoped to [contactIds] (null = all), and reporting per-chat progress for
     * the blocking modal. Aborts (throws) if the active account changes mid-flow — every write
     * so far is pinned to [address] and the poll loop finishes the job when that account is
     * active again, so stopping is always safe.
     */
    suspend fun resyncIncomingMessages(
        address: String,
        contactIds: List<String>?,
        onChatProgress: suspend (done: Int, total: Int) -> Unit
    ): IncomingResyncResult {
        ensureStillActive(address)
        val api = networkService.indexerApi.value
            ?: throw IllegalStateException("Not connected to the message indexer. Check your connection and try again.")
        liveBaselineMs = settingsRepository.liveNotificationBaseline(address)

        syncHandshakes(address, api)
        syncOutgoingHandshakes(address, api)
        val chatCount = syncContextualMessages(
            address, api,
            onlyContactIds = contactIds?.toSet()
        ) { done, total ->
            ensureStillActive(address)
            onChatProgress(done, total)
        }
        ensureStillActive(address)
        // The whole payment history, back to the retention cutoff (iOS wipeAndResyncIncomingMessages
        // phase 3, `fetchIncomingPayments(blockTime: historyStart)`): the wiped payment bubbles are
        // incoming messages too.
        networkService.kaspaRestApi.value?.let { syncPayments(address, it, fullHistory = true) }

        val messageCount = if (contactIds == null) {
            database.messageDao().countReceivedForWallet(address)
        } else {
            database.messageDao().countReceivedForContacts(address, contactIds)
        }
        return IncomingResyncResult(chatCount = chatCount, messageCount = messageCount)
    }

    /** Deletes every local message and contact for [address] — used when wiping an account entirely. Does not touch the wallet's keys (see WalletManager.deleteAccount) or any cloud backup. */
    suspend fun wipeAllLocalDataForAddress(address: String) {
        database.messageDao().deleteAllForWallet(address)
        database.reactionDao().deleteAllForWallet(address)
        database.messageEditDao().deleteAllForWallet(address)
        database.messageDao().deleteSyncCursorsForWallet(address)
        database.contactDao().deleteAllForWallet(address)
        database.contactDao().deleteTombstonesForWallet(address)
        // Groups too. They are keyed by walletAddress like everything above, and leaving them
        // meant a deleted account's group threads and their messages stayed on the device and
        // came back the moment that account was re-imported (iOS 78152d3).
        database.groupDao().deleteAllGroupMessagesForWallet(address)
        database.groupDao().deleteAllGroupsForWallet(address)
        // The DataStore sync cursors MUST reset with the data: they survived account deletion,
        // so re-importing an account previously held on this device resumed the handshake scan
        // at the old cursor — ZERO historical handshakes came back and no contacts were
        // recreated ("the account doesn't sync anymore"). The live baseline resets too so the
        // re-import runs as a silent read backfill, not a notification storm.
        settingsRepository.setPaymentHistoryScanned(address, false)
        settingsRepository.setHandshakeSyncCursor(address, 0L)
        settingsRepository.setHandshakeOutSyncCursor(address, 0L)
        settingsRepository.clearLiveNotificationBaseline(address)
    }

    /**
     * Real receive pipeline: fetches incoming handshakes (creating pending
     * conversations) and contextual messages from already-active contacts,
     * decrypting both with the same KasiaCipher/MessageProtocol code already
     * built for sending — no separate crypto path for receiving.
     */
    /**
     * See [AppSettingsRepository.liveNotificationBaseline]: anything on-chain older than this
     * is history being backfilled (e.g. right after an account import) — inserted as read and
     * never notified. Long.MAX_VALUE until the first sweep resolves it, so nothing can slip a
     * notification out before the baseline is known.
     */
    private var liveBaselineMs: Long = Long.MAX_VALUE

    /**
     * [fromPollLoop] marks the recurring 2s-30s cycle from init, which carries the traffic
     * shaping (contact-sweep throttle/cap/spacing, payments on their own slower cadence with a
     * small page). Manual refreshes and the resync flows pass false and keep the full,
     * unthrottled behavior.
     */
    /**
     * One conversation's new messages, now - run when a 1:1 chat is entered. The poll loop's
     * next tick could be the idle one, so a chat opened from a tapped notification showed its
     * stored history and nothing newer for up to its interval (iOS 360e5d2).
     */
    suspend fun syncConversationNow(contactId: String) {
        if (onboardingGate.isHeld) return
        val myAddress = try { walletManager.getAddress() } catch (e: Exception) { return }
        val api = networkService.indexerApi.value ?: return
        liveBaselineMs = settingsRepository.liveNotificationBaseline(myAddress)
        syncContextualMessages(myAddress, api, onlyContactIds = setOf(contactId))
    }

    /**
     * Your chat with yourself - notes to self, and where messages from unknown senders land -
     * exists for every account from the start and cannot be deleted (iOS ef4f183): created here
     * if missing, before any sync has anything to show, and any earlier deletion of it is undone.
     * The chat list pins it first.
     */
    /**
     * Chats of the other network already filed under this account are removed, messages and all
     * (iOS dropOtherNetworkConversations / ContactsManager load filter, df52425).
     */
    suspend fun dropOtherNetworkChats(myAddress: String) {
        val all = database.contactDao().getContacts(myAddress).first()
        for (contact in all) {
            if (com.kachat.app.util.KaspaNetwork.isOnActiveNetwork(contact.id)) continue
            database.messageDao().deleteAllForContact(contact.id, myAddress)
            database.reactionDao().deleteAllForContact(contact.id, myAddress)
            database.messageEditDao().deleteAllForContact(contact.id, myAddress)
            database.messageDao().deleteSyncCursorsForContact(contact.id, myAddress)
            database.contactDao().deleteContact(contact.id, myAddress)
        }
    }

    /** Your chat with yourself exists for every account and cannot be deleted: any earlier
     *  deletion of it is lifted - kept as its history floor, as iOS `ensureSelfConversation`
     *  does through `clearDeletionTombstone` (4b00a5f) - and the contact is created if missing. */
    suspend fun ensureSelfConversation(myAddress: String? = runCatching { walletManager.getAddress() }.getOrNull()) {
        val address = myAddress?.takeIf { it.isNotEmpty() } ?: return
        database.contactDao().liftDeletedContact(address, address)
        if (database.contactDao().getContact(address, address) != null) return
        database.contactDao().insert(ContactEntity(id = address, walletAddress = address, alias = null, knsName = null, publicKeyHex = null))
    }

    suspend fun syncMessages(fromPollLoop: Boolean = false) {
        // Every other caller (pull-to-refresh, the sync worker, push handling) is held too - the
        // wizard is exclusive. The post-wizard initial sync releases the gate before running.
        if (onboardingGate.isHeld) return
        val myAddress = try { walletManager.getAddress() } catch (e: Exception) { return }
        // Before the indexer gate on purpose: healing stuck send-placeholders needs no network.
        repairStuckProvisionalMessages(myAddress)
        val api = networkService.indexerApi.value ?: return
        liveBaselineMs = settingsRepository.liveNotificationBaseline(myAddress)

        ensureSelfConversation(myAddress)
        if (!fromPollLoop) dropOtherNetworkChats(myAddress)

        syncHandshakes(myAddress, api)
        syncOutgoingHandshakes(myAddress, api)
        // People writing to us for the first time, without a handshake (Message Requests) - its
        // own cursor, its own phase. On the fast poll tick only every so often.
        val nowForInbox = System.currentTimeMillis()
        if (!fromPollLoop || nowForInbox - lastInboxSyncAt >= INBOX_POLL_INTERVAL_MS) {
            lastInboxSyncAt = nowForInbox
            syncInbox(myAddress, api)
        }
        // Messages a push told us about that are not stored yet (iOS 60c9fd0).
        ingestPushedMessages(myAddress, api)
        // Saved-handshake notes: re-create chats that never had a handshake after a fresh
        // import, and write the notes still missing (iOS fe45704). Not on the fast poll tick.
        if (!fromPollLoop) contactNotesLazy.get().syncNotesInBackground()
        syncContextualMessages(myAddress, api, pollShaped = fromPollLoop)
        // Payments get their own, slower cadence on the poll path: the endpoint is
        // full-transactions (inputs + outputs + payloads resolved server-side — by far the
        // heaviest GET in the cycle) and has no cursor, so polling it every fast tick
        // re-downloaded the same recent window over and over. A payment surfacing within ~20s
        // is still well inside "feels live" for a payment bubble.
        // A payment that may have been missed (the poll paused in the background, a sender not
        // resolved yet - [paymentCatchUpSince]) is fetched now, back to when the gap opened,
        // even on the poll path (iOS 20155aa).
        val now = System.currentTimeMillis()
        val catchUpSince = paymentCatchUpSince
        if (!fromPollLoop || catchUpSince != null || now - lastPaymentPollAt >= PAYMENT_POLL_INTERVAL_MS) {
            networkService.kaspaRestApi.value?.let {
                val caughtUp = syncPayments(
                    myAddress, it,
                    limit = if (fromPollLoop && catchUpSince == null) PAYMENT_POLL_LIMIT else 50,
                    since = catchUpSince,
                    // The one-time whole-history read ([syncPayments]) belongs to a full sync -
                    // the post-import initial sync, launch, pull-to-refresh - never to the
                    // fast poll tick, which would otherwise retry a failing 200-page read every
                    // 20 seconds.
                    allowHistoryScan = !fromPollLoop
                )
                lastPaymentPollAt = now
                // caught up: a gap that opened during this fetch would have set an earlier one
                if (caughtUp && catchUpSince != null) {
                    synchronized(this) { if (paymentCatchUpSince == catchUpSince) paymentCatchUpSince = null }
                }
            }
        }
        // After this launch's first sync: old chats whose first messages are no longer stored
        // stay ordinary chats (iOS 60c9fd0).
        if (grandfatheredWallets.add(myAddress.lowercase())) grandfatherPreexistingChats(myAddress)
    }

    /** Wallets [grandfatherPreexistingChats] has run for after a sync, this launch. */
    private val grandfatheredWallets = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Chats that existed before Message Requests arrived stay ordinary chats for good (iOS
     * 60c9fd0 grandfatherPreexistingChats). Decided from the oldest STORED message and recorded
     * as accepted: [isMessageRequest] reads the first message still in the database, so an old
     * chat whose early messages were removed by retention could otherwise turn into a request -
     * leaving the chat list and the push watch list. Runs after the first sync of a launch and
     * before every retention prune.
     */
    private suspend fun grandfatherPreexistingChats(myAddress: String) {
        try {
            val state = chatRequestStore.state(myAddress)
            val me = myAddress.lowercase()
            val preexisting = database.messageDao().getContactMessageStats(myAddress).first()
                .filter { stats ->
                    val address = stats.contactId.lowercase()
                    val first = stats.firstAt
                    address != me && stats.anySent == 0 && first != null && first < state.startedAt &&
                        address !in state.accepted && address !in state.privateChats && address !in state.blocked
                }
                .map { it.contactId.lowercase() }
                .toSet()
            if (preexisting.isEmpty()) return
            Log.i("ChatRepository", "${preexisting.size} chat(s) from before Message Requests kept as ordinary chats")
            chatRequestStore.update(myAddress) { it.copy(accepted = it.accepted + preexisting) }
        } catch (e: Exception) {
            Log.w("ChatRepository", "Keeping pre-Message-Requests chats failed", e)
        }
    }

    // MARK: - Pushed 1:1 messages (iOS 60c9fd0)

    private val pushedIngestMutex = kotlinx.coroutines.sync.Mutex()
    /** Per queued tx id: how often its sender was fetched for it, and when last (this launch). */
    private val pushedAttempts = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()

    /**
     * A 1:1 push about [txId] from [sender]: always handed to the ingest - not only when the
     * banner could not be previewed - and stored now, in the background. A photo, voice or long
     * message (a payload-less push) otherwise waited for a sync to stumble on it: missing from
     * the chat list until that chat was opened. Queued first, so a process frozen right after
     * the push still stores it on its next sync.
     */
    fun notePushedMessage(txId: String, sender: String) {
        val me = runCatching { walletManager.getAddress() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        if (txId.isBlank() || sender.isBlank() || sender.equals(me, ignoreCase = true)) return
        pushedMessageQueue.add(me, txId, sender)
        scope.launch {
            try {
                ingestPushedMessages(me, networkService.indexerApi.value)
            } catch (e: Exception) {
                Log.w("ChatRepository", "Storing a pushed message failed", e)
            }
        }
    }

    /**
     * Stores the queued pushed messages: each sender's conversation is fetched the normal way
     * (sender + alias) until the tx is in the database. A sender with no chat yet is looked up in
     * the inbox (Message Requests); if that does not bring them in, they get a chat anyway - a
     * pending one, so it lands in Message Requests - once the message is confirmed to be for us.
     * That contact row is their routing state: every sync and sweep covers them from then on
     * (iOS 60c9fd0 gives anyone first seen through a push routing state on their first message).
     * An entry not stored after a few spaced tries (an edit, reaction or pool envelope never
     * becomes a row of its own) or a day is dropped; the regular sync keeps covering the chat.
     */
    suspend fun ingestPushedMessages(
        myAddress: String = runCatching { walletManager.getAddress() }.getOrDefault(""),
        api: KasiaIndexerApi? = networkService.indexerApi.value,
    ) {
        if (myAddress.isEmpty() || api == null || onboardingGate.isHeld) return
        if (pushedMessageQueue.entries(myAddress).isEmpty()) return
        if (!pushedIngestMutex.tryLock()) return
        try {
            val now = System.currentTimeMillis()
            val pending = pushedMessageQueue.entries(myAddress)
            val done = mutableSetOf<String>()
            for ((_, entries) in pending.groupBy { it.sender.lowercase() }) {
                val sender = entries.first().sender
                val open = entries.filter { entry ->
                    when {
                        database.messageDao().exists(entry.txId, myAddress) -> false
                        now - entry.queuedAt > com.kachat.app.services.PushedMessageQueue.MAX_AGE_MS -> false
                        (pushedAttempts[entry.txId]?.first ?: 0) >= PUSHED_MAX_ATTEMPTS -> false
                        else -> true
                    }
                }
                done += entries.filterNot { it in open }.map { it.txId }
                if (open.isEmpty()) continue
                // Spaced tries: a sender fetched moments ago is left for the next pass.
                if (open.all { now - (pushedAttempts[it.txId]?.second ?: 0L) < PUSHED_RETRY_SPACING_MS }) continue
                if (sender.equals(myAddress, ignoreCase = true) || chatRequestStore.isBlocked(sender) ||
                    !KaspaAddress.isValid(sender) || !com.kachat.app.util.KaspaNetwork.isOnActiveNetwork(sender)
                ) {
                    done += open.map { it.txId }
                    continue
                }
                if (database.contactDao().getContact(sender, myAddress) == null) {
                    // First contact: the inbox brings in a tagged first message with its history.
                    syncInbox(myAddress, api)
                }
                if (database.contactDao().getContact(sender, myAddress) == null) {
                    // Not in the inbox (yet): a chat for them anyway, once a queued message is
                    // confirmed to open with our key - so the sync below, and every sync after
                    // it, looks for their messages.
                    val forUs = open.any { decryptChainMessage(it.txId) != null }
                    if (!forUs) {
                        open.forEach { pushedAttempts.merge(it.txId, 1 to now) { old, _ -> (old.first + 1) to now } }
                        continue
                    }
                    if (database.contactDao().getContact(sender, myAddress) == null) {
                        val senderPubKeyHex = runCatching {
                            KaspaAddress.decode(sender).second.joinToString("") { "%02x".format(it) }
                        }.getOrNull()
                        database.contactDao().insert(
                            ContactEntity(
                                id = sender, walletAddress = myAddress, alias = null, knsName = null,
                                publicKeyHex = senderPubKeyHex,
                                // Not accepted yet: a Message Request, out of the push watch list.
                                conversationStatus = "pending",
                            )
                        )
                    }
                }
                open.forEach { pushedAttempts.merge(it.txId, 1 to now) { old, _ -> (old.first + 1) to now } }
                syncContextualMessages(myAddress, api, onlyContactIds = setOf(sender))
                done += open.filter { database.messageDao().exists(it.txId, myAddress) }.map { it.txId }
            }
            pushedMessageQueue.remove(myAddress, done)
            done.forEach { pushedAttempts.remove(it) }
        } finally {
            pushedIngestMutex.unlock()
        }
    }

    /** Last time the poll path actually hit the payments endpoint — see [syncMessages]. */
    @Volatile
    private var lastPaymentPollAt = 0L

    /**
     * Payments may have been missed from this block time (ms) on (iOS 20155aa
     * `paymentCatchUpSince`): the next sync fetches them - right away, past the poll path's
     * slower cadence and small page, back to this time - and clears it once caught up. Set by
     * [requestPaymentCatchUp]; null = nothing missed.
     */
    @Volatile
    private var paymentCatchUpSince: Long? = null

    /** Payments whose unresolved sender already asked for a catch-up ([syncPayments]). */
    private val unresolvedPaymentCatchUps = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Payments may have been missed: the next sync fetches them from [lookBackMs] ago (the
     *  earliest such moment wins). */
    private fun requestPaymentCatchUp(lookBackMs: Long) {
        val since = (System.currentTimeMillis() - lookBackMs).coerceAtLeast(0L)
        synchronized(this) {
            paymentCatchUpSince = minOf(paymentCatchUpSince ?: since, since)
        }
    }

    /** Throttle stamp for the full contact sweep on the poll path — see [syncContextualMessages]. */
    @Volatile
    private var lastContactSweepAt = 0L

    /**
     * Payment tx ids already fetched and handled this process — cheap early diff so the
     * cursorless payments endpoint's repeated window doesn't re-run the DB-exists check and
     * payload decode for the same transactions on every poll. Keys include the wallet so an
     * account switch can't cross-match. Bounded: cleared when it grows past a few thousand
     * (worst case is one round of re-checks through the DB path, which is what happened on
     * every cycle before this set existed).
     */
    private val seenPaymentTxIds = java.util.Collections.synchronizedSet(HashSet<String>())
    private val contractPaymentsPurged = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Restore-parity pass (matches iOS): handshakes YOU sent — requests you initiated AND your
     * acceptances of others' requests. After a fresh import these are the only on-chain proof a
     * conversation was mutual: your acceptance never appears in handshakes/by-receiver, so
     * without this pass every peer-initiated conversation you'd accepted re-surfaced as a
     * stranger request. Creates/promotes the contact to active+handshakeComplete and inserts a
     * "[Handshake sent]" row (the payload is encrypted for the recipient — undecryptable by us).
     */
    private suspend fun syncOutgoingHandshakes(myAddress: String, api: KasiaIndexerApi) {
        val cursor = settingsRepository.handshakeOutSyncCursor(myAddress).first()
        val handshakes = try {
            // Same rewind behind the cursor as the incoming stream (see syncHandshakes).
            api.getHandshakesBySender(myAddress, blockTime = cursor?.let { (it - SYNC_REORG_REWIND_MS).coerceAtLeast(0L) })
        } catch (e: Exception) {
            noteIndexerError()
            Log.w("ChatRepository", "Failed to fetch outgoing handshakes", e)
            return
        }

        for (handshake in handshakes) {
            try {
                val peer = handshake.receiver
                if (peer.isBlank() || peer == myAddress || !KaspaAddress.isValid(peer)) continue
                val deleted = database.contactDao().getDeletedContact(peer, myAddress)
                if (isTombstoned(deleted, handshake.txId, handshake.blockTime)) continue

                val existing = database.contactDao().getContact(peer, myAddress)
                if (existing == null) {
                    database.contactDao().insert(
                        ContactEntity(
                            id = peer, walletAddress = myAddress, alias = null, knsName = null,
                            publicKeyHex = null, conversationStatus = "active", handshakeComplete = true
                        )
                    )
                } else if (existing.conversationStatus != "active" || !existing.handshakeComplete) {
                    val activated = existing.copy(conversationStatus = "active", handshakeComplete = true)
                    database.contactDao().insert(activated)
                    // Your own past acceptance, rediscovered after a restore — see
                    // [noteConversationActivated]: re-read the peer's history from zero, since the
                    // pending-state cursors only ever asked for what was newer than the pending sync.
                    noteConversationActivated(existing, activated)
                }

                if (!database.messageDao().exists(handshake.txId, myAddress)) {
                    insertMessage(
                        MessageEntity(
                            id = handshake.txId,
                            contactId = peer,
                            walletAddress = myAddress,
                            type = MessageProtocol.TYPE_HANDSHAKE,
                            direction = "sent",
                            plaintextBody = "[Handshake sent]",
                            encryptedPayload = handshake.messagePayload,
                            amountSompi = null,
                            blockTimestamp = handshake.blockTime,
                            isRead = true
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w("ChatRepository", "Failed to process outgoing handshake ${handshake.txId}", e)
            }
        }

        val maxBlockTime = handshakes.maxOfOrNull { it.blockTime }
        if (maxBlockTime != null && maxBlockTime > (cursor ?: 0L)) {
            settingsRepository.setHandshakeOutSyncCursor(myAddress, maxBlockTime)
        }
    }

    /** True when [blockTime] predates this account's first sync on this device — backfilled history, not live traffic. */
    private fun isBackfill(blockTime: Long?): Boolean = (blockTime ?: 0L) < liveBaselineMs

    private suspend fun syncHandshakes(myAddress: String, api: KasiaIndexerApi) {
        // block_time cursor — see AppSettingsRepository.handshakeSyncCursor's doc comment. Only
        // fetches what's genuinely new since the last successful sync instead of the same recent
        // window every cycle. Started a rewind window BEHIND the cursor, like every other stream:
        // the indexer does not surface handshakes in block-time order, and a handshake served
        // late would otherwise sit below the cursor forever (iOS syncStartBlockTime).
        val cursor = settingsRepository.handshakeSyncCursor(myAddress).first()
        val handshakes = try {
            api.getHandshakesByReceiver(myAddress, blockTime = cursor?.let { (it - SYNC_REORG_REWIND_MS).coerceAtLeast(0L) })
        } catch (e: Exception) {
            noteIndexerError()
            Log.w("ChatRepository", "Failed to fetch handshakes", e)
            return
        }

        // A handshake whose sender could not be resolved yet (see [resolveHandshakeSender]) must
        // stay inside the next fetch's window: the cursor is held at its block time - the query
        // is inclusive - rather than advancing past it and losing the request for good.
        var unresolvedFloor: Long? = null
        for (handshake in handshakes) {
            try {
                if (database.messageDao().exists(handshake.txId, myAddress)) continue
                if (!processHandshake(myAddress, handshake)) {
                    unresolvedFloor = minOf(unresolvedFloor ?: Long.MAX_VALUE, handshake.blockTime)
                }
            } catch (e: Exception) {
                Log.w("ChatRepository", "Failed to process handshake ${handshake.txId}", e)
            }
        }

        val maxBlockTime = handshakes.maxOfOrNull { it.blockTime }
        val nextCursor = listOfNotNull(maxBlockTime, unresolvedFloor).minOrNull()
        if (nextCursor != null && nextCursor > (cursor ?: 0L)) {
            settingsRepository.setHandshakeSyncCursor(myAddress, nextCursor)
        }
    }

    /**
     * The handshake's sender, resolved. The KaChat indexer serves a handshake it has not yet
     * seen ACCEPTED with an empty `sender` (the field is filled from the spent input once the
     * transaction has an accepting block) - and that is exactly the window a live poll hits
     * first. Dropping it there, as this used to, lost the request: the cursor moved past it and
     * nothing ever asked again. So the sender is read straight off the transaction on the Kaspa
     * REST API instead - any input whose previous outpoint is not our own address (iOS
     * resolveSenderAddress / fetchAnyInputAddress). Null when neither source knows yet.
     */
    private suspend fun resolveHandshakeSender(myAddress: String, handshake: HandshakeIndexerResponse): String? {
        if (KaspaAddress.isValid(handshake.sender)) return handshake.sender
        val restApi = networkService.kaspaRestApi.value ?: return null
        return try {
            restApi.getTransactionWithInputAddresses(handshake.txId).inputs
                .firstNotNullOfOrNull { input ->
                    input.previousOutpointAddress?.takeIf { it.isNotBlank() && it != myAddress && KaspaAddress.isValid(it) }
                }
        } catch (e: Exception) {
            Log.w("ChatRepository", "Could not resolve sender of handshake ${handshake.txId.take(16)} from chain", e)
            null
        }
    }

    /** Returns false only when the handshake's sender is not knowable yet, so the caller keeps it
     *  inside the next fetch window; true when it was handled (or is permanently irrelevant). */
    private suspend fun processHandshake(myAddress: String, handshake: HandshakeIndexerResponse): Boolean {
        val sender = resolveHandshakeSender(myAddress, handshake)
        if (sender == null) {
            Log.i("ChatRepository", "Handshake ${handshake.txId.take(16)} has no resolvable sender yet - will retry")
            return false
        }
        // A deleted contact's tombstone outlives the contact row itself. This still matters even
        // with the block_time sync cursor above: the very first sync for a *newly re-created*
        // contact (e.g. a fresh handshake after deletion) has no cursor yet, so that one fetch can
        // still surface the old pre-deletion handshake transaction if the indexer hasn't pruned it.
        // Only a handshake sent *after* the deletion creates a real contact/conversation.
        val deleted = database.contactDao().getDeletedContact(sender, myAddress)
        if (isTombstoned(deleted, handshake.txId, handshake.blockTime)) return true
        // Past the gate on a post-deletion handshake: they have re-initiated and we let it
        // through, so the chat is live again - the tombstone is lifted, kept only as the history
        // floor (iOS 4b00a5f clearDeletionTombstone, which runs before the block check too).
        if (deleted != null && !deleted.lifted) {
            Log.i("ChatRepository", "New handshake from a deleted contact - reopening ${sender.takeLast(10)}")
            database.contactDao().liftDeletedContact(sender, myAddress)
        }

        // A rejected (blocked) address stays blocked: a handshake from them is ignored, not a way
        // back in. Only the user writing to them lifts it (acceptChat).
        if (chatRequestStore.isBlocked(sender)) return true

        val encryptedBytes = handshake.messagePayload.hexToBytes()
        val encryptedMessage = KasiaCipher.EncryptedMessage.fromBytes(encryptedBytes)
        if (encryptedMessage == null) {
            Log.w("ChatRepository", "Handshake ${handshake.txId.take(16)} payload is not a sealed message - skipped")
            return true
        }
        // The sender lookup above may have spanned an account switch: this account's handshake
        // is never opened with, nor its alias filed by, the next one's key. Kept inside the next
        // fetch window (false) so it is handled when this account syncs again.
        val privateKey = accountKey(myAddress) ?: return false
        val decryptedJson = MessageProtocol.decrypt(encryptedMessage, privateKey)
        val payload = try { gson.fromJson(decryptedJson, HandshakePayload::class.java) } catch (e: Exception) { null }
        val theirAlias = payload?.alias
        // Every alias they announce is kept, not just this latest one - see PeerAliasStore.
        theirAlias?.let { peerAliasStore.add(myAddress, sender, it) }

        val senderPubKeyHex = KaspaAddress.decode(sender).second.joinToString("") { "%02x".format(it) }
        val existing = database.contactDao().getContact(sender, myAddress)
        val newStatus = deriveIncomingHandshakeStatus(existing?.conversationStatus, existing?.handshakeComplete ?: false, payload?.isResponse ?: false)

        val updatedContact =
            (existing ?: ContactEntity(id = sender, walletAddress = myAddress, alias = null, knsName = null, publicKeyHex = null))
                .copy(
                    publicKeyHex = senderPubKeyHex,
                    conversationStatus = newStatus,
                    theirAlias = theirAlias ?: existing?.theirAlias
                )
        database.contactDao().insert(updatedContact)
        // Their response handshake ("I am chatting with you too") flipping a pending/never-
        // handshaked conversation active — the mirror of accepting their request, and the same
        // reason to re-read their earlier window. See [noteConversationActivated].
        noteConversationActivated(existing, updatedContact)

        val backfill = isBackfill(handshake.blockTime)
        insertMessage(
            MessageEntity(
                id = handshake.txId,
                contactId = sender,
                walletAddress = myAddress,
                type = MessageProtocol.TYPE_HANDSHAKE,
                direction = "received",
                plaintextBody = "${theirAlias ?: com.kachat.app.util.KaspaAddress.shortDisplay(sender)} wants to connect",
                encryptedPayload = handshake.messagePayload,
                amountSompi = null,
                blockTimestamp = handshake.blockTime,
                isRead = backfill || notificationHelper.isViewingContact(sender)
            )
        )

        val displayName = theirAlias ?: com.kachat.app.util.KaspaAddress.shortDisplay(sender)
        // The remote push is the only banner source while push is active, foreground or
        // background - see PushState. The sync still lands the handshake; it just no longer
        // announces it. Only a device with no push at all banners from here.
        if (!backfill && !pushState.isActive && !notifyAsMessageRequest(updatedContact, myAddress, handshake.txId)) {
            notificationHelper.show(
                contactId = sender,
                title = if (newStatus == "pending") "Request to communicate" else "Connected",
                text = if (newStatus == "pending") "$displayName wants to connect" else "$displayName accepted your request",
                notificationOverride = ContactNotificationMode.fromName(existing?.notificationOverride),
                dedupeTxId = handshake.txId
            )
        }
        return true
    }

    /** When each contact's transactions were last read for aliases - see [discoverPeerAliases]. */
    private val aliasDiscoveryAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Contacts whose stored handshakes have been read for aliases this process. */
    private val aliasBackfilled = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /**
     * Learns the aliases [contact] sends to us under, without having to be told.
     *
     * A 1:1 message is a self-stash: the sender pays to their own address with the payload
     * `kchat:1:comm:<alias>:<sealed>`, alias in the clear. So their recent transactions show
     * every alias they are using, and the ones whose sealed part opens with OUR key are the ones
     * meant for us - the same decrypt-first rule iOS applies to a contact's transactions. Any new
     * alias found goes into [PeerAliasStore], and the sync that follows fetches its whole history.
     *
     * Also reads, once per contact per process, every handshake of theirs already stored here:
     * those name aliases the single contact field has since forgotten.
     *
     * Throttled: the conversation on screen at most every 30 seconds, anyone else every 5 minutes.
     */
    private suspend fun discoverPeerAliases(myAddress: String, contact: ContactEntity, onScreen: Boolean, privateKey: ByteArray) {
        if (contact.id == myAddress) return
        val key = "$myAddress|${contact.id}"

        if (aliasBackfilled.add(key)) {
            for (row in database.messageDao().getReceivedHandshakes(contact.id, myAddress)) {
                val sealed = row.encryptedPayload?.let { runCatching { KasiaCipher.EncryptedMessage.fromBytes(it.hexToBytes()) }.getOrNull() } ?: continue
                val alias = runCatching {
                    gson.fromJson(MessageProtocol.decrypt(sealed, privateKey), HandshakePayload::class.java)?.alias
                }.getOrNull() ?: continue
                peerAliasStore.add(myAddress, contact.id, alias)
            }
        }

        val now = System.currentTimeMillis()
        val interval = if (onScreen) ALIAS_DISCOVERY_ON_SCREEN_MS else ALIAS_DISCOVERY_INTERVAL_MS
        if (now - (aliasDiscoveryAt[key] ?: 0L) < interval) return
        val restApi = networkService.kaspaRestApi.value ?: return
        aliasDiscoveryAt[key] = now

        val known = peerAliasStore.aliases(myAddress, contact.id) + listOfNotNull(contact.theirAlias)
        val transactions = restApi.getTransactions(contact.id, limit = ALIAS_DISCOVERY_TX_LIMIT, resolvePreviousOutpoints = "no")
        val tried = mutableSetOf<String>()
        for (tx in transactions) {
            val bytes = tx.payload?.takeIf { it.isNotEmpty() }?.let { runCatching { it.hexToBytes() }.getOrNull() } ?: continue
            val (alias, sealed) = MessageProtocol.parseCommPayload(bytes) ?: continue
            if (alias in known || !tried.add(alias)) continue
            // Opens with our key: this alias carries messages to us.
            if (runCatching { MessageProtocol.decrypt(sealed, privateKey) }.isSuccess) {
                if (peerAliasStore.add(myAddress, contact.id, alias)) {
                    Log.i("ChatRepository", "Learned a new alias for ${contact.id.takeLast(8)} from their transactions")
                }
            }
        }
    }

    /**
     * [onlyContactIds] (a wipe-and-resync scope) restricts the fetch to just those contacts;
     * null (every normal sync) syncs them all. [onContactDone] fires after each contact's
     * streams finish — the per-chat progress feed for the blocking resync modal (it may throw
     * to abort the loop, e.g. on an account switch). Returns how many contacts were synced.
     *
     * [pollShaped] (the recurring poll loop only) applies the iOS-shaped traffic model: the
     * conversation on screen is fetched on EVERY tick (that's what makes an open chat feel
     * live), but the fan-out over every other contact — the expensive part, two indexer GETs
     * per contact per alias — runs at most every [CONTACT_SWEEP_MIN_INTERVAL_MS], capped at the
     * [CONTACT_SWEEP_CAP] most recently active contacts, with [CONTACT_SWEEP_SPACING_MS]
     * between contacts so a sweep is a drizzle, not a burst. Mirrors iOS ChatService's
     * startForegroundContactSweep. Manual refresh/resync flows never pass this.
     */
    private suspend fun syncContextualMessages(
        myAddress: String,
        api: KasiaIndexerApi,
        onlyContactIds: Set<String>? = null,
        pollShaped: Boolean = false,
        onContactDone: (suspend (done: Int, total: Int) -> Unit)? = null
    ): Int {
        // Fetch for BOTH active and pending contacts. Gating the FETCH on "active" made a
        // mis-classified conversation unrecoverable: after a fresh import every peer-initiated
        // conversation derives "pending" (the local evidence that you accepted it was wiped),
        // and its history was then never requested at all — the thread stayed empty forever.
        // "Pending" still gates DISPLAY (the stranger banner hides messages until accepted);
        // rejected contacts stay excluded.
        var syncableContacts = database.contactDao().getContactsByStatus("active", myAddress) +
            database.contactDao().getContactsByStatus("pending", myAddress)
        onlyContactIds?.let { ids -> syncableContacts = syncableContacts.filter { it.id in ids } }
        // A chat that is still deleted (its tombstone not lifted) gets nothing fetched at all,
        // even when an automatic add (a KaPosts tip, chess, names, an inbox sender) has
        // recreated its contact row - iOS returns early from its contextual fetches for
        // `isAddressDeleted`. Only a handshake from them that post-dates the deletion lifts it
        // (processHandshake, a phase that runs before this one), and from then on the chat is
        // fetched again; the per-message floor below still keeps what came before the deletion
        // out. A deliberate add drops the row altogether.
        val stillDeleted = database.contactDao().getAllDeletedContactIds(myAddress).toSet()
        if (stillDeleted.isNotEmpty()) syncableContacts = syncableContacts.filter { it.id !in stillDeleted }

        var sweepSpacing = false
        if (pollShaped) {
            val openContactId = notificationHelper.currentContactId
            val now = System.currentTimeMillis()
            if (now - lastContactSweepAt >= CONTACT_SWEEP_MIN_INTERVAL_MS) {
                lastContactSweepAt = now
                sweepSpacing = true
                if (syncableContacts.size > CONTACT_SWEEP_CAP) {
                    // Prioritize by most recent message activity; the open conversation always
                    // makes the cut regardless of where its history ranks.
                    val latestByContact = database.messageDao().getLatestMessagePerContact(myAddress, MessageEntity.HIDDEN_PLACEHOLDERS)
                        .first().associate { it.contactId to it.blockTimestamp }
                    val top = syncableContacts
                        .sortedByDescending { latestByContact[it.id] ?: 0L }
                        .take(CONTACT_SWEEP_CAP)
                    syncableContacts = if (openContactId != null && top.none { it.id == openContactId }) {
                        top + syncableContacts.filter { it.id == openContactId }
                    } else {
                        top
                    }
                }
            } else {
                // Between sweeps only the thread on screen stays on the fast tick.
                syncableContacts = syncableContacts.filter { it.id == openContactId }
            }
        }

        for ((index, contact) in syncableContacts.withIndex()) {
            if (sweepSpacing && index > 0) delay(CONTACT_SWEEP_SPACING_MS)
            // This account's own key for everything below - aliases, alias discovery, decrypting
            // - or nothing: once the account has switched, the rest is the next sync's (iOS
            // dfaba68). Cursors stay where they are, so nothing is skipped for this account.
            val privateKey = accountKey(myAddress) ?: break
            // See processHandshake's identical tombstone check — still needed even with the
            // block_time cursor below, since a newly re-created contact's first-ever sync has no
            // cursor yet and could otherwise surface old pre-deletion messages.
            val deleted = database.contactDao().getDeletedContact(contact.id, myAddress)

            // Legacy: the alias they told us in their handshake reply, if any. Deterministic:
            // derivable purely from both addresses, so it's always tryable even with no
            // handshake at all — see WalletManager.myDeterministicAlias.
            val legacyAliasHex = contact.theirAlias?.let { hexEncodeAscii(it) }
            val deterministicAliasHex = try {
                hexEncodeAscii(walletManager.myDeterministicAlias(contact.id, privateKey, myAddress))
            } catch (e: Exception) {
                null // Non-Schnorr/invalid address — skip the deterministic candidate for this contact.
            }

            // Every other alias they have been seen sending to us under - older handshakes, and
            // any found on their own transactions. iOS sends under the first of ALL its legacy
            // aliases, which need not be the one its last handshake named; missing it meant not
            // one of their messages ever arrived here, while ours reached them fine.
            val knownAliasHexes = try {
                discoverPeerAliases(myAddress, contact, onScreen = contact.id == notificationHelper.currentContactId, privateKey = privateKey)
                peerAliasStore.aliases(myAddress, contact.id).map { hexEncodeAscii(it) }
            } catch (e: Exception) {
                Log.w("ChatRepository", "Alias discovery failed for ${contact.id.takeLast(8)}", e)
                emptyList()
            }

            for (aliasHex in (listOfNotNull(legacyAliasHex, deterministicAliasHex) + knownAliasHexes).distinct()) {
                // Every alias here is one the contact sends to us on, and every one is queried on
                // every sync: anyone in the chat list must always be found, whichever alias their
                // app sends on (an old client, or a reinstall that started over on an old-style
                // alias). The once-a-day lane for old-style aliases (iOS 00d4919) kept such a
                // contact's messages off the list until the chat was opened or a day passed; iOS
                // 60c9fd0 keeps that lane for its OUTGOING copies only, which Android never polls.
                // block_time cursor, tracked per (contact, alias) since each is its own independent
                // stream on the indexer — see MessageSyncCursorEntity's doc comment.
                //
                // PAGINATED. The single un-paged fetch this replaced asked for one page and then
                // advanced the cursor to that page's newest block_time, so anything the indexer
                // could not fit in one page was left permanently BELOW the cursor and could never
                // be requested again. That is invisible for a live conversation (a page easily
                // covers one poll interval) and fatal for the case this method exists to serve:
                // the first fetch after a contact row finally appears, which is meant to pull the
                // peer's WHOLE history — including everything they sent before any handshake
                // existed, which nothing had ever asked the indexer for.
                // The stored cursor is the newest block_time the indexer has RETURNED for this
                // stream - and the indexer does not surface messages in block-time order:
                // acceptance in the DAG is not monotonic, and an indexer catching up serves what
                // it has. A reply can be returned before the message it replied to; a fetch that
                // then started strictly at the cursor would never request the original again.
                // One message missing from a thread for good, with everything after it delivered
                // - which is exactly what a user saw, tapping a quote whose original their device
                // never received. So every fetch starts a rewind window BEHIND the cursor (90s on
                // the fast open-chat tick, ten minutes on a catch-up sweep); the txId dedupe below
                // makes the overlap free. The window bounds how late the indexer may be;
                // recoverMissingReplyOriginal covers anything later than that. Mirrors iOS
                // syncStartBlockTime after its unconditional-rewind fix.
                val storedCursor = database.messageDao().getMessageSyncCursor(contact.id, myAddress, aliasHex)
                val rewindMs = if (pollShaped) LIVE_TAIL_REORG_REWIND_MS else SYNC_REORG_REWIND_MS
                var fetchFrom: Long? = storedCursor?.let { (it - rewindMs).coerceAtLeast(0L) }
                var newest = storedCursor ?: 0L
                var page = 0
                while (page < CONTEXTUAL_MAX_PAGES_PER_SWEEP) {
                    page++
                    val messages = try {
                        api.getContextualMessagesBySender(
                            contact.id, aliasHex,
                            limit = CONTEXTUAL_PAGE_LIMIT,
                            blockTime = fetchFrom,
                        )
                    } catch (e: Exception) {
                        noteIndexerError()
                        Log.w("ChatRepository", "Failed to fetch messages for ${contact.id}", e)
                        break
                    }
                    if (messages.isEmpty()) break

                    for (message in messages) {
                        try {
                            // A stored "[Encrypted message]" doesn't count as had: a successful
                            // decrypt now replaces it and the message appears (iOS c130dc1).
                            if (database.messageDao().existsShown(message.txId, myAddress, MessageEntity.HIDDEN_PLACEHOLDERS)) continue
                            if (isTombstoned(deleted, message.txId, message.blockTime)) continue
                            processContextualMessage(myAddress, contact, message, privateKey)
                        } catch (e: Exception) {
                            Log.w("ChatRepository", "Failed to process message ${message.txId}", e)
                        }
                    }

                    val maxBlockTime = messages.maxOf { it.blockTime }
                    // The stored cursor only ever advances on its own (the recovery path is the
                    // one thing that moves it back).
                    if (maxBlockTime > newest) {
                        newest = maxBlockTime
                        database.messageDao().setMessageSyncCursor(
                            MessageSyncCursorEntity(contactId = contact.id, walletAddress = myAddress, aliasHex = aliasHex, lastBlockTime = newest)
                        )
                    }
                    // Paging walks on the PAGE's newest block_time, separately from the stored
                    // cursor: with a rewound start, a full page can sit entirely at or below the
                    // stored cursor (a chatty contact inside the window) and still have newer rows
                    // behind it. Short page = caught up. `!moved` is the termination guard for a
                    // full page that all shares one block_time: the query is inclusive (`>=`), so
                    // without it the same page would be re-requested forever. The page cap keeps
                    // one sweep bounded; a deep backlog simply finishes on the following sweeps.
                    val moved = fetchFrom == null || maxBlockTime > fetchFrom
                    if (messages.size < CONTEXTUAL_PAGE_LIMIT || !moved) break
                    fetchFrom = maxBlockTime
                }
            }
            onContactDone?.invoke(index + 1, syncableContacts.size)
        }
        return syncableContacts.size
    }

    /**
     * Reads a 1:1 message straight off the chain and returns its plaintext, or null.
     *
     * A push only carries its encrypted payload when that payload is small enough for FCM;
     * anything larger arrives as a bare txId. Photos compress to tens of kilobytes, so the one
     * case where a preview matters most is exactly the case with nothing to preview. The message
     * is public on the blockDAG and this device already holds the key that opens it.
     */
    suspend fun decryptChainMessage(txId: String): String? {
        val api = networkService.kaspaRestApi.value ?: return null
        return try {
            val payloadHex = api.getTransactionPayload(txId).payload?.takeIf { it.isNotEmpty() } ?: return null
            val sealed = decodeOnChainContextualPayload(payloadHex) ?: return null
            val encrypted = KasiaCipher.EncryptedMessage.fromBytes(sealed) ?: return null
            MessageProtocol.decrypt(encrypted, walletManager.getPrivateKeyBytes())
        } catch (e: Exception) {
            Log.d("ChatRepository", "Off-chain read failed for ${txId.take(12)}: ${e.message}")
            null
        }
    }

    /**
     * [myAddress] is passed down from [syncContextualMessages] rather than re-read here via
     * `walletManager.getAddress()` — if the user switched the active account mid-sync, a fresh
     * read here would stamp this message under the NEW account even though [contact] belongs
     * to the account the sync actually started with. [privateKey] is that account's own key
     * (checked by the caller, [accountKey]) for the same reason.
     */
    private suspend fun processContextualMessage(myAddress: String, contact: ContactEntity, message: ContextualMessageIndexerResponse, privateKey: ByteArray) {
        // Rejected in Message Requests: nothing they send is kept, until the user writes to them.
        if (contact.id != myAddress && chatRequestStore.isBlocked(contact.id)) return
        val encryptedBytes = decodeContextualMessagePayload(message.messagePayload)
        val encryptedMessage = KasiaCipher.EncryptedMessage.fromBytes(encryptedBytes) ?: return
        // Decryption only needs our own private key + the ephemeral key embedded in the
        // message itself (ECDH) — the sender's static pubkey is never required here.
        val plaintext = MessageProtocol.decrypt(encryptedMessage, privateKey)

        // An edit is never a bubble either: it changes the message it names (if the editor sent
        // that message) and is otherwise dropped - see [applyIncomingEdit]. Checked before the
        // reaction intercept, exactly like iOS's addMessageToConversation.
        val edit = com.kachat.app.util.MessageEdit.parseOrNull(plaintext)
        if (edit != null) {
            applyIncomingEdit(
                edit = edit,
                // Self-chat aside, an edit arriving on this stream was sent by the contact.
                editorIsMe = contact.id == myAddress,
                contactId = contact.id,
                editTxId = message.txId,
                blockTime = message.blockTime,
                walletAddress = myAddress
            )
            return
        }

        // Reactions are never shown as their own chat bubble - just attached to the message they
        // target - so intercept and route to the reactions table before a MessageEntity is ever
        // created for this tx. The sender of an incoming reaction is always this contact.
        val reaction = MessageReaction.parseOrNull(plaintext)
        if (reaction != null) {
            if (reaction.action == "add") {
                upsertReaction(reaction.targetTxId, contact.id, contact.id, reaction.emoji, message.txId, message.blockTime, walletAddress = myAddress)
            } else {
                removeReaction(reaction.targetTxId, contact.id, walletAddress = myAddress)
            }
            return
        }

        // Payment-pool envelopes are never bubbles. A payment_notice may file the payment it
        // names in this chat (a chat payment sent From a spending address - iOS 927322b);
        // addr_pool and addr_pool_request are retired and dropped (iOS 6333dd3).
        val poolEnvelope = com.kachat.app.util.PaymentPoolProtocol.parse(plaintext)
        if (poolEnvelope != null) {
            if (poolEnvelope is com.kachat.app.util.PaymentPoolProtocol.Envelope.Notice) {
                handlePaymentNotice(
                    myAddress = myAddress,
                    contact = contact,
                    notice = poolEnvelope.content,
                    noticeTxId = message.txId,
                    blockTime = message.blockTime,
                    // Self-chat aside, a notice arriving on this stream was sent by the contact.
                    isOutgoing = contact.id == myAddress,
                    plaintext = plaintext
                )
            }
            return
        }

        val backfill = isBackfill(message.blockTime)
        insertMessage(
            MessageEntity(
                id = message.txId,
                contactId = contact.id,
                walletAddress = myAddress,
                type = MessageProtocol.TYPE_COMM,
                // Self-chat: a note you sent to yourself is outgoing on every device (decryption
                // already scopes the by-sender(self) query to your own self->self messages).
                direction = if (contact.id == myAddress) "sent" else "received",
                plaintextBody = plaintext,
                encryptedPayload = message.messagePayload,
                amountSompi = null,
                blockTimestamp = message.blockTime,
                // Read when it's backfilled history OR the user is looking at this thread
                // right now - watching a message arrive must not leave an unread badge.
                isRead = backfill || notificationHelper.isViewingContact(contact.id)
            )
        )

        // Call events (invite / answer / hang-up) ring through the chat itself. They DO stay as
        // bubbles - the chat shows "Voice call · 4:12" the way a phone's history would - so this
        // only hands the envelope to CallService and carries on.
        val callEnvelope = if (contact.id != myAddress) com.kachat.app.util.CallCodec.parseOrNull(plaintext) else null
        callEnvelope?.let { envelope ->
            callServiceLazy.get().handleIncoming(envelope, contact.id, message.blockTime, isOutgoing = false)
        }
        // A call from someone calls are off for (or never turned on) is ignored silently: no
        // "Incoming call" banner either (MESSAGING.md "Consent before ringing", iOS 88aa3c1).
        val unwantedCall = callEnvelope != null && com.kachat.app.util.CallCodec.isOpeningCall(callEnvelope) &&
            contact.callsEnabled != true

        val replyContent = MessageReply.parseOrNull(plaintext)
        // A reply to a message this device never received is the one signal that a message was
        // missed; act on it rather than leave a quote that opens onto nothing.
        if (replyContent != null && replyContent.replyToId.isNotEmpty()) {
            recoverMissingReplyOriginal(contact.id, replyContent.replyToId, message.blockTime, force = false)
        }
        // Title above is already the contact's name, so the body doesn't repeat it. One rule with
        // the FCM handler (dmNotificationText): a video or other file is "Sent a video" / "Sent a
        // file", never its JSON (audit AND-007).
        val notificationText = com.kachat.app.util.dmNotificationText(plaintext, notificationHelper.dmNotificationLabels())
        // The remote push is the only banner source while push is active, foreground or
        // background - see PushState. Only a device with no push at all banners from here.
        // Nor a banner for one the chat won't show (a hidden placeholder, iOS c130dc1).
        if (!backfill && !unwantedCall && !pushState.isActive && !MessageEntity.isHiddenPlaceholder(plaintext) &&
            !notifyAsMessageRequest(contact, myAddress, message.txId)
        ) {
            notificationHelper.show(
                contactId = contact.id,
                title = contact.displayName,
                text = notificationText,
                notificationOverride = ContactNotificationMode.fromName(contact.notificationOverride),
                dedupeTxId = message.txId
            )
        }
    }

    /**
     * A `payment_notice` in a 1:1 chat (iOS `handlePaymentNotice`, MESSAGING.md "Chat Payments
     * From a Spending Address") - theirs (they paid us From a spending address, a plain transfer
     * we can't place on its own) or our own read back on another device. It names a payment to
     * the receiving side's chatting address; anything else (an old app's payment to one of our
     * former pool addresses) is dropped silently (XP-023). The payment becomes a payment bubble
     * in this chat, with its memo. Deduped by the payment's txId, so a later payment sync finds
     * it present and does nothing.
     *
     * Any contact can send a notice naming any public txid (IOS-083), so a received one is
     * checked on chain BEFORE anything is filed ([checkNoticeOnChain]): the tx must pay our
     * chatting address exactly the amount, carry no payload (a plain transfer) and be paid by
     * none of our own addresses ([ownAddressesForNotices]) and nobody we know as another contact.
     * Only a plain transfer from an unknown address that is none of ours and that landed in the
     * self-chat may move into the chat ([isClaimableSelfChatPayment]); another
     * chat's payment, a message, or anything sent by us is never touched - the filing itself
     * can only add a row ([MessageDao.insertIfAbsent]) or move a self-chat payment
     * ([MessageDao.claimSelfChatPayment]), never replace one.
     */
    private suspend fun handlePaymentNotice(
        myAddress: String,
        contact: ContactEntity,
        notice: com.kachat.app.util.PaymentPoolProtocol.PaymentNoticeContent,
        noticeTxId: String,
        blockTime: Long,
        isOutgoing: Boolean,
        plaintext: String,
    ) {
        val txId = com.kachat.app.util.PaymentPoolProtocol.noticeTxId(notice.txId) ?: return
        if (notice.amountSompi <= 0) return
        val paidAddress = if (isOutgoing) contact.id else myAddress
        if (!notice.address.trim().equals(paidAddress, ignoreCase = true)) {
            Log.i("ChatRepository", "Dropped payment_notice ${txId.take(12)} - not a payment to the chatting address")
            return
        }
        val memo = com.kachat.app.util.PaymentPoolProtocol.noticeMemo(notice.memo)
        // The chain check is a network round trip - in the background, so a sync is never held
        // up by it (iOS runs the whole filing in a Task).
        scope.launch {
            try {
                fileCheckedPaymentNotice(myAddress, contact, notice, txId, memo, noticeTxId, blockTime, isOutgoing, plaintext)
            } catch (e: Exception) {
                Log.d("ChatRepository", "payment_notice ${txId.take(12)} not filed: ${e.message}")
            }
        }
    }

    private suspend fun fileCheckedPaymentNotice(
        myAddress: String,
        contact: ContactEntity,
        notice: com.kachat.app.util.PaymentPoolProtocol.PaymentNoticeContent,
        txId: String,
        memo: String?,
        noticeTxId: String,
        blockTime: Long,
        isOutgoing: Boolean,
        plaintext: String,
    ) {
        if (!isActiveWalletAddress(myAddress)) return
        val dao = database.messageDao()
        val existing = dao.getById(txId, myAddress)
        if (existing != null) {
            if ((existing.direction == "sent") == isOutgoing && existing.contactId.equals(contact.id, ignoreCase = true)) {
                // already in this chat; one filed unverified gets its chain check again
                if (isOutgoing || existing.type != "pay" || existing.deliveryStatus != "warning") return
                when (val check = checkNoticeOnChain(myAddress, txId, notice.amountSompi, contact.id)) {
                    is NoticeCheck.Valid -> if (isActiveWalletAddress(myAddress)) {
                        dao.confirmUnverifiedPayment(
                            id = txId, walletAddress = myAddress, contactId = existing.contactId,
                            body = noticeBubbleText(notice.amountSompi, memo, isOutgoing = false),
                            amountSompi = notice.amountSompi
                        )
                    }
                    is NoticeCheck.Invalid -> if (isActiveWalletAddress(myAddress)) {
                        // the claim was false: its unverified bubble goes (a real payment under
                        // that txid is filed where it belongs by the next payment fetch; iOS 566b4ef)
                        // - only while it is still this notice's unverified bubble after the await
                        // (iOS df1ecfb), and it stops being a notice bubble
                        val again = dao.getById(txId, myAddress)
                        if (again != null && again.contactId.equals(existing.contactId, ignoreCase = true) &&
                            again.type == "pay" && again.direction == "received" && again.deliveryStatus == "warning" &&
                            dao.deleteUnverifiedPayment(txId, myAddress, again.contactId) > 0
                        ) {
                            noticeBubbleStore.unmark(myAddress, txId)
                            Log.i("ChatRepository", "Removed unverified payment_notice bubble ${txId.take(12)} - ${check.reason}")
                        }
                    }
                    NoticeCheck.Unknown -> Unit
                }
                return
            }
            if (isOutgoing || !isClaimableSelfChatPayment(existing, myAddress)) {
                Log.i("ChatRepository", "Dropped payment_notice ${txId.take(12)} - it names a message that isn't an unclaimed self-chat payment")
                return
            }
        }
        // A chat deleted since stays deleted for anything sent before the deletion - the same
        // check every other path makes for this chat (1bf5e4b3), keyed by the payment's txId.
        if (isTombstoned(database.contactDao().getDeletedContact(contact.id, myAddress), txId, blockTime)) return

        if (isOutgoing) {
            // our own notice on another device: the outgoing bubble, if this device has none
            fileNoticeBubble(myAddress, contact, notice, txId, memo, noticeTxId, blockTime, isOutgoing = true,
                status = "sent", moving = null, plaintext = plaintext)
            return
        }
        when (val check = checkNoticeOnChain(myAddress, txId, notice.amountSompi, contact.id)) {
            is NoticeCheck.Valid -> {
                // Placement again, after the await (iOS fca61dc): another notice for the same txid
                // may have claimed it meanwhile (first notice wins), or the record may have appeared.
                if (!isActiveWalletAddress(myAddress)) return
                val current = dao.getById(txId, myAddress)
                if (current != null && !isClaimableSelfChatPayment(current, myAddress)) {
                    Log.i("ChatRepository", "Dropped payment_notice ${txId.take(12)} - claimed or filed while it was checked")
                    return
                }
                fileNoticeBubble(myAddress, contact, notice, txId, memo, noticeTxId, blockTime, isOutgoing = false,
                    status = "sent", moving = current, plaintext = plaintext)
            }
            is NoticeCheck.Invalid ->
                Log.i("ChatRepository", "Dropped payment_notice ${txId.take(12)} - ${check.reason}")
            NoticeCheck.Unknown -> {
                // no chain answer: never move a record on an unchecked claim; with nothing stored
                // yet, show it marked unverified (checked again when the notice is read again).
                // After the await: still this wallet, and still nothing stored under that txid
                // (iOS 364e680, IOS-090).
                if (existing != null || !isActiveWalletAddress(myAddress)) return
                if (dao.getById(txId, myAddress) != null) return
                fileNoticeBubble(myAddress, contact, notice, txId, memo, noticeTxId, blockTime, isOutgoing = false,
                    status = "warning", moving = null, plaintext = plaintext)
            }
        }
    }

    /**
     * A stored row a received notice may claim (iOS `isClaimableSelfChatPayment`): an incoming
     * payment that sits in the self-chat - a plain transfer from an address that is no contact of
     * ours ([processPayment]'s "From: <address>" row) - and none of our own
     * ([ownAddressesForNotices]).
     */
    private suspend fun isClaimableSelfChatPayment(row: MessageEntity, myAddress: String): Boolean {
        if (row.type != "pay" || row.direction != "received") return false
        if (!row.contactId.equals(myAddress, ignoreCase = true)) return false
        val sender = row.plaintextBody?.substringAfterLast("\nFrom: ", "")?.trim().orEmpty()
        if (sender.isEmpty() || sender.lowercase() in ownAddressesForNotices(myAddress)) return false
        return !isKnownContact(sender, myAddress)
    }

    /**
     * Every address this phone knows as the person's own, lowercased (iOS
     * `ownAddressesForNotices`): the chatting address, every revealed spending address, every
     * saved account's chatting address and every Cold Storage receive address (0..maxDerivedIndex
     * of each of this wallet's accounts). A transfer from any of them is the person moving their
     * own money, which no contact's notice may ever claim (IOS-083). Off the main thread: a
     * spending index not derived yet costs a seed derivation, and each cold address a key
     * derivation.
     */
    private suspend fun ownAddressesForNotices(myAddress: String): Set<String> =
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            val own = HashSet<String>()
            runCatching { walletManager.allSpendingAddresses() }.getOrDefault(emptyList())
                .forEach { own.add(it.trim().lowercase()) }
            own.add(myAddress.trim().lowercase())
            runCatching { walletManager.getAllAccounts() }.getOrDefault(emptyList())
                .forEach { own.add(it.address.trim().lowercase()) }
            // every spending address this phone derived for any of its accounts, saved ones
            // included (run 10: B's Address #2 topping up A's chatting address is ours too; iOS 566b4ef)
            runCatching { walletManager.allCachedSpendingAddresses() }.getOrDefault(emptyList())
                .forEach { own.add(it.trim().lowercase()) }
            val coldAccounts = runCatching { coldStorageManager.getAccounts() }.getOrDefault(emptyList())
            for (account in coldAccounts) {
                val rootKey = com.kachat.app.util.KaspaExtendedPublicKey.parse(account.kpub).getOrNull()
                    ?.let { runCatching { com.kachat.app.util.KaspaExtendedPublicKey.toDeterministicKey(it) }.getOrNull() }
                    ?: continue
                for (index in 0..maxOf(account.maxDerivedIndex, 0)) {
                    runCatching {
                        com.kachat.app.util.KaspaExtendedPublicKey.deriveChildAddress(rootKey, chain = 0, index = index)
                    }.getOrNull()?.let { own.add(it.trim().lowercase()) }
                }
            }
            own.remove("")
            own
        }

    /** A contact of this wallet (any status), matched like iOS `getContact(byAddress:)`. */
    private suspend fun isKnownContact(address: String, myAddress: String): Boolean {
        val dao = database.contactDao()
        return dao.getContact(address, myAddress) != null ||
            dao.getContact(address.trim().lowercase(), myAddress) != null
    }

    /** Files a notice's payment bubble (iOS `fileNoticeBubble`): a new row only - an existing one
     *  is never replaced - or, with [moving], that self-chat payment moved into this chat. */
    private suspend fun fileNoticeBubble(
        myAddress: String,
        contact: ContactEntity,
        notice: com.kachat.app.util.PaymentPoolProtocol.PaymentNoticeContent,
        txId: String,
        memo: String?,
        noticeTxId: String,
        blockTime: Long,
        isOutgoing: Boolean,
        status: String,
        moving: MessageEntity?,
        plaintext: String,
    ) {
        if (!com.kachat.app.util.KaspaNetwork.isOnActiveNetwork(contact.id)) {
            Log.i("ChatRepository", "Dropped a message from the other network")
            return
        }
        val text = noticeBubbleText(notice.amountSompi, memo, isOutgoing)
        val backfill = isBackfill(blockTime)
        val isRead = isOutgoing || backfill || moving?.isRead == true || notificationHelper.isViewingContact(contact.id)
        val dao = database.messageDao()
        // A received notice's bubble is that notice's to confirm (its chain check): the generic
        // payment sync leaves it alone ([processPayment]; iOS df1ecfb, IOS-083 run 11).
        if (!isOutgoing) noticeBubbleStore.mark(myAddress, txId)
        if (moving != null) {
            val moved = dao.claimSelfChatPayment(
                id = txId, walletAddress = myAddress, selfChatId = moving.contactId, contactId = contact.id,
                body = text, amountSompi = notice.amountSompi, blockTimestamp = blockTime, isRead = isRead
            )
            if (moved == 0) {
                Log.i("ChatRepository", "Dropped payment_notice ${txId.take(12)} - the payment was claimed already")
                return
            }
            Log.i("ChatRepository", "Moved payment ${txId.take(12)} from the self-chat into its chat")
        } else {
            val inserted = dao.insertIfAbsent(
                MessageEntity(
                    id = txId,
                    contactId = contact.id,
                    walletAddress = myAddress,
                    type = "pay",
                    direction = if (isOutgoing) "sent" else "received",
                    plaintextBody = text,
                    encryptedPayload = "",
                    amountSompi = notice.amountSompi,
                    blockTimestamp = blockTime,
                    isRead = isRead,
                    deliveryStatus = status
                )
            )
            if (inserted == -1L) return
        }
        scheduleAutoBackupIfEnabled()
        Log.i("ChatRepository", "Payment ${txId.take(12)} filed in its chat from a payment_notice ($status)")
        if (isOutgoing) return

        // Only a payment newly shown counts: one the payment sync already put in the self-chat
        // rang then. "Received payment" without the claimed amount, the push's wording (IOS-080);
        // the remote push is the only banner source while push is active (see PushState).
        if (moving == null && !backfill && !pushState.isActive &&
            !notifyAsMessageRequest(contact, myAddress, noticeTxId)
        ) {
            notificationHelper.show(
                contactId = contact.id,
                title = contact.displayName,
                text = com.kachat.app.util.dmNotificationText(plaintext, notificationHelper.dmNotificationLabels()),
                notificationOverride = ContactNotificationMode.fromName(contact.notificationOverride),
                dedupeTxId = noticeTxId
            )
        }
    }

    private fun noticeBubbleText(amountSompi: Long, memo: String?, isOutgoing: Boolean): String {
        val formatted = formatKas(amountSompi)
        return if (isOutgoing) {
            com.kachat.app.util.PaymentPoolProtocol.sentNoticeText(formatted, KaspaUnit.symbol, memo)
        } else {
            com.kachat.app.util.PaymentPoolProtocol.receivedNoticeText(formatted, KaspaUnit.symbol, memo)
        }
    }

    private sealed class NoticeCheck {
        object Valid : NoticeCheck()
        data class Invalid(val reason: String) : NoticeCheck()
        /** the transaction couldn't be read (not indexed yet, or no answer) */
        object Unknown : NoticeCheck()
    }

    /**
     * What the chain says about a received notice (iOS `checkNoticeOnChain`): the tx pays our
     * chatting address ([myAddress]) exactly [claimedSompi], carries no payload (a chat payment
     * or a message is never a notice's payment), and none of its inputs is ours (any of
     * [ownAddressesForNotices]) or another contact's than [payer].
     */
    private suspend fun checkNoticeOnChain(myAddress: String, txId: String, claimedSompi: Long, payer: String): NoticeCheck {
        val api = networkService.kaspaRestApi.value ?: return NoticeCheck.Unknown
        val tx = try { api.getTransactionWithInputAddresses(txId) } catch (e: Exception) { return NoticeCheck.Unknown }
        val paid = tx.outputs
            .filter { it.scriptPublicKeyAddress?.equals(myAddress, ignoreCase = true) == true }
            .sumOf { it.amount }
        if (paid != claimedSompi) return NoticeCheck.Invalid("it pays $paid sompi, not $claimedSompi")
        if (!tx.payload.isNullOrEmpty()) return NoticeCheck.Invalid("the transaction carries a payload")
        // an input without a resolved address could be one of ours: no chain answer yet (iOS 566b4ef)
        val inputs = tx.inputs.map { it.previousOutpointAddress?.trim()?.lowercase().orEmpty() }
        if (inputs.isEmpty() || inputs.any { it.isEmpty() }) return NoticeCheck.Unknown
        val own = ownAddressesForNotices(myAddress)
        for (input in inputs) {
            if (input in own) return NoticeCheck.Invalid("paid by one of our own addresses")
            if (input != payer.trim().lowercase() && isKnownContact(input, myAddress)) {
                return NoticeCheck.Invalid("paid by another contact")
            }
        }
        return NoticeCheck.Valid
    }

    /**
     * The payment sync (iOS `fetchPaymentsFromKaspaAPI` + `processPayments`): reads the chatting
     * address's transactions from the REST API's full-transactions endpoint and files every
     * payment in a chat - one we SENT in a chat with its destination ("Sent X KAS"), one we
     * RECEIVED in the payer's chat, or, from an address we have no contact for, in the self-chat
     * with the payer noted ("Received X KAS\nFrom: <address>"). Messages and handshakes
     * (kchat:1: payloads other than a payment) are the other syncs' job. See [processPayment].
     *
     * The first full sync of an address on this device ([allowHistoryScan], until
     * [AppSettingsRepository.paymentHistoryScanned]) reads its WHOLE history, as iOS's first
     * payment phase after an import does (`lastPollTime == 0`, so `fetchFullTransactionsPaginated`
     * from block time 0, clamped to the message-retention cutoff): a fresh import gets back the
     * same payment chats iOS shows. History older than this device's first sync lands read and
     * silent ([isBackfill], iOS `walletImportBaselineMs`). [fullHistory] forces that read (the
     * wipe-and-re-sync).
     *
     * Otherwise [since] (a payment catch-up, iOS 20155aa) pages further back - up to
     * [PAYMENT_CATCH_UP_MAX_PAGES] pages - until the transactions are older than it, and without
     * it one page of [limit] is read. Returns whether every page needed was fetched.
     */
    private suspend fun syncPayments(
        myAddress: String,
        restApi: KaspaRestApi,
        limit: Int = 50,
        since: Long? = null,
        allowHistoryScan: Boolean = true,
        fullHistory: Boolean = false,
    ): Boolean {
        val historyScan = fullHistory ||
            (allowHistoryScan && !settingsRepository.paymentHistoryScanned(myAddress).first())
        if (!historyScan) return syncPaymentPages(myAddress, restApi, limit, since, history = false)
        // One whole-history read at a time: a second sync arriving meanwhile leaves it to the one
        // already reading (txid dedupe would make the overlap harmless, but not free).
        if (!paymentHistoryScanMutex.tryLock()) return false
        return try {
            val complete = syncPaymentPages(myAddress, restApi, PAYMENT_HISTORY_PAGE_SIZE, since = null, history = true)
            // Only a read that got every page counts: a failed page leaves the flag unset, so the
            // next full sync reads the history again rather than keeping a partial one for good.
            if (complete) settingsRepository.setPaymentHistoryScanned(myAddress, true)
            Log.i("ChatRepository", "Payment history read for …${myAddress.takeLast(8)}: complete=$complete")
            complete
        } finally {
            paymentHistoryScanMutex.unlock()
        }
    }

    /** Held while [syncPayments] reads a whole payment history. */
    private val paymentHistoryScanMutex = kotlinx.coroutines.sync.Mutex()

    /** One full-transactions page. A [history] read retries the same page on iOS's growing pause
     *  (`historyPageRetryDelaysSeconds`: api.kaspa.org is shared and rate-limits, and a single
     *  429 must not end a 200-page read); a 4xx other than 429 is not asked again. */
    private suspend fun fetchPaymentPage(
        restApi: KaspaRestApi, myAddress: String, limit: Int, offset: Int, history: Boolean
    ): List<TransactionResponse> {
        val delays = if (history) PAYMENT_HISTORY_RETRY_DELAYS_MS else listOf(0L)
        var last: Exception? = null
        for (pause in delays) {
            if (pause > 0) delay(pause)
            try {
                return restApi.getTransactions(myAddress, limit = limit, offset = offset)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                val code = (e as? retrofit2.HttpException)?.code()
                if (code != null && code != 429 && code < 500) break
            }
        }
        throw last ?: IllegalStateException("no attempt made")
    }

    private suspend fun syncPaymentPages(
        myAddress: String,
        restApi: KaspaRestApi,
        limit: Int,
        since: Long?,
        history: Boolean,
    ): Boolean {
        // A history read stops at the message-retention cutoff (iOS applyMessageRetention(to: 0)):
        // anything older would be pruned again right away.
        val retentionCutoff = if (history) {
            settingsRepository.backupRetention.first().cutoffMillis(System.currentTimeMillis())
        } else null

        val transactions = mutableListOf<TransactionResponse>()
        var complete = true
        var page = 0
        while (true) {
            val batch = try {
                fetchPaymentPage(restApi, myAddress, limit, page * limit, history)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                noteIndexerError()
                Log.w("ChatRepository", "Failed to fetch transactions", e)
                if (page == 0) return false
                complete = false
                break
            }
            transactions += batch
            page++
            if (batch.size < limit) break
            if (history) {
                if (page >= PAYMENT_HISTORY_MAX_PAGES) break
                if (retentionCutoff != null) {
                    val oldest = batch.mapNotNull { it.blockTime }.filter { it > 0 }.minOrNull()
                    if (oldest != null && oldest <= retentionCutoff) break
                }
                continue
            }
            if (since == null || page >= PAYMENT_CATCH_UP_MAX_PAGES) break
            val oldest = batch.mapNotNull { it.blockTime }.minOrNull() ?: break
            if (oldest < since) break
        }

        // Payments held because their signatures couldn't be checked yet are read again by id -
        // the pages above may already be past them - and checked again below, past the time
        // filter (iOS 5806362 fetchPaymentsFromKaspaAPI). A read that fails just leaves the
        // payment held; it never makes this sync incomplete. Every held txid is due, whether it is
        // on these pages or read by id (iOS 8116ab9, IOS-091).
        val heldTxIds = HashSet<String>()
        val present = transactions.mapTo(HashSet()) { it.transactionId.lowercase() }
        for (txId in heldPaymentTxIds(myAddress)) {
            if (txId in present) {
                heldTxIds += txId
                continue
            }
            val held = try {
                restApi.getTransactionWithInputAddresses(txId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: continue
            transactions += held
            heldTxIds += txId
        }

        // Once per process per wallet: drop the payment bubbles older builds made from .kachat
        // contract transactions (iOS 32fdaa4). processPayment no longer files them.
        if (contractPaymentsPurged.add(myAddress.lowercase())) {
            runCatching { database.messageDao().deleteContractAddressPayments(myAddress) }
                .onSuccess { if (it > 0) Log.i("ChatRepository", "Removed $it contract-address payment bubble(s)") }
        }

        if (seenPaymentTxIds.size > 5_000) seenPaymentTxIds.clear()
        for (tx in transactions) {
            // Stop if the user switched/imported a different wallet mid-read (iOS processPayments
            // isActiveWallet): the rest belong to [myAddress], and the checks below read the
            // ACTIVE wallet's own addresses and key.
            if (!isActiveWalletAddress(myAddress)) return false
            try {
                // Early id diff against what this process already handled — the endpoint has no
                // cursor, so most of every page is transactions seen on the previous poll. Not
                // for a history read: after a wipe, ids handled earlier this process are exactly
                // the bubbles that must come back (the stored-txid check below still dedupes).
                val seenKey = "$myAddress|${tx.transactionId}"
                // a held payment is due whatever this process has seen and however old it is
                val isHeld = tx.transactionId.lowercase() in heldTxIds
                if (!history && !isHeld && seenKey in seenPaymentTxIds) continue
                if (!isHeld && retentionCutoff != null && (tx.blockTime ?: 0L) in 1 until retentionCutoff) {
                    seenPaymentTxIds.add(seenKey)
                    continue
                }
                // A KNS reveal (a domain inscription) is never a payment; a domain TRANSFER among
                // them is a "Sent/Received <domain> domain" message in the chat with the other
                // party (iOS fetchPaymentsFromKaspaAPI -> handleKNSOperationTransactionIfNeeded,
                // before its payment loop). It does its own dedupe - a payment bubble an older
                // build made from it is replaced - but a payment_notice's bubble is the notice's
                // alone (IOS-083 run 11).
                if (isKnsRevealTransaction(tx)) {
                    if (!(noticeBubbleStore.isNoticeBubble(myAddress, tx.transactionId) &&
                            database.messageDao().exists(tx.transactionId, myAddress))
                    ) handleKnsRevealTransaction(myAddress, tx)
                    seenPaymentTxIds.add(seenKey)
                    continue
                }
                // A stored txid is never touched again - above all a payment_notice's bubble,
                // which only that notice's own chain check may confirm or move (IOS-083 run 11).
                if (database.messageDao().exists(tx.transactionId, myAddress)) {
                    if (isHeld) releaseHeldPayment(myAddress, tx.transactionId)
                    seenPaymentTxIds.add(seenKey)
                    continue
                }
                // Only a FINAL outcome marks the id seen — a transient input-resolution gap
                // (processPayment returns false) must stay retryable on the next poll, exactly
                // as it was before the seen-set existed.
                if (processPayment(myAddress, tx, restApi)) {
                    seenPaymentTxIds.add(seenKey)
                } else if (unresolvedPaymentCatchUps.add(seenKey)) {
                    // Sender not resolved yet: the next sync fetches payments from a little
                    // before, past the poll path's small page (iOS 20155aa) - once per payment,
                    // so one that never resolves can't keep the catch-up open.
                    requestPaymentCatchUp(lookBackMs = 10 * 60 * 1000L)
                }
            } catch (e: Exception) {
                Log.w("ChatRepository", "Failed to process transaction ${tx.transactionId}", e)
            }
        }
        if (since != null) Log.i("ChatRepository", "Payment catch-up: ${transactions.size} transaction(s), complete=$complete")
        return complete
    }

    /** Returns true when the outcome is FINAL (recorded, or permanently irrelevant) — false only
     *  for the transient no-input-address-resolved gap, which the next poll must retry.
     *  Both directions, as iOS `processPayments`: a payment we sent goes to [processOutgoingPayment]. */
    private suspend fun processPayment(myAddress: String, tx: TransactionResponse, restApi: KaspaRestApi): Boolean {
        // var: a payment only the node confirmed is filed without its payload (see the check below)
        var payloadBytes = tx.payload?.hexToBytes() ?: ByteArray(0)
        // A KaChat payment carries `kchat:1:pay:` with its memo encrypted to us (iOS always
        // writes one; Android writes one when there is a memo, iOS 8d208b2). It is still a
        // payment - only messages and handshakes are someone else's job.
        var isPaymentPayload = MessageProtocol.isPaymentPayload(payloadBytes)
        if (!isPaymentPayload && MessageProtocol.isKaChatPayload(payloadBytes)) return true // real message/handshake, not a plain payment
        // A .kachat registry or offer transaction (its kchat:1:name: / offer: payload is caught
        // above) moves a covenant's P2SH coin - the commit, the name's 1 KAS bond, an offer's
        // locked KAS and their refunds - and is never a payment chat; it shows in the wallet
        // history (iOS 32fdaa4). A quantum (KIP-22) address is P2SH too, so see
        // [isKachatContractTransaction] for what still counts (iOS 0b4e27e). The own set is the
        // memoized one, and only read when the transaction has a P2SH output (iOS d80c497).
        if (isKachatContractTransaction(tx) { walletManager.ownAddressSetLowercased() }) return true

        // A transaction with no inputs (a block reward) is never a chat payment, and nothing in it
        // can be signature-checked - a lying REST server could otherwise file a made-up payment,
        // memo included, "from" anyone (iOS 5a78d06, IOS-094). Final, not a retry: rewards stay in
        // the address history and the balance; the reward bot's pings are messages of their own.
        if (tx.inputs.isEmpty()) return true

        // Which way the money went (iOS fetchPaymentsFromKaspaAPI's input/output analysis) - see
        // [classifyPaymentTx]. Every input is checked for a resolved address, not just the first:
        // the REST API's resolve_previous_outpoints=light can leave an individual input's address
        // unresolved (e.g. transient lookup gap) even when a later input in the same tx resolved
        // fine. Bailing out on inputs[0] being unresolved used to silently drop an otherwise-valid
        // received payment with no message, no contact, and no notification.
        // Whether an output pays us is read from its script - the signed data the signature check
        // rebuilds - not from the unsigned address label, which a lying API could set to ours on
        // someone else's payment (iOS 5a78d06, IOS-098 / AND-045).
        val direction = classifyPaymentTx(myAddress, tx)
        var sender: String
        val receivedSompi: Long
        when (direction) {
            PaymentTxDirection.Unresolved -> {
                Log.w("ChatRepository", "Dropping payment ${tx.transactionId}: no input address resolved")
                return false // transient — the next poll retries this tx
            }
            PaymentTxDirection.None -> return true
            // A payment we sent from the chatting address. It used to be skipped here as
            // "already recorded locally at send time" - true only on the phone that sent it, so a
            // fresh import never got back a chat it had paid into (iOS restores them).
            is PaymentTxDirection.Outgoing ->
                return processOutgoingPayment(myAddress, tx, direction.recipient, direction.amountSompi)
            is PaymentTxDirection.Incoming -> {
                sender = direction.sender
                receivedSompi = direction.amountSompi
            }
        }
        // A received payment read from the REST API is filed only if its signatures verify (iOS
        // fetchPaymentsFromKaspaAPI, 5806362): the API is a third party, and a fabricated
        // "payment" must not create or change a chat. Versions 0 and 1; an input the API didn't
        // resolve is looked up through its previous transaction. Invalid drops it; a payment that
        // still can't be checked is held and checked again on later syncs - never dropped on the
        // first miss, never shown unverified. See [com.kachat.app.util.KaspaSignatureVerifier].
        val verdict = com.kachat.app.util.KaspaSignatureVerifier.checkReceivedTransaction(tx, myAddress) { previousTxId, index ->
            val previous = try {
                restApi.getTransaction(previousTxId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            previous?.let { com.kachat.app.util.KaspaSignatureVerifier.outputAt(it, index) }
        }
        // Valid but no verified input names a sender: the REST label is all there is, so it goes
        // the Unknown way (node + indexer), never filed under the label (iOS 2343e84, IOS-094).
        when (val check = paymentSignatureCheck(verdict)) {
            com.kachat.app.util.KaspaSignatureVerifier.SignatureCheck.Valid -> {
                releaseHeldPayment(myAddress, tx.transactionId)
                // the sender from the very inputs whose signatures were just verified - never from
                // a second, unchecked read (iOS 5a78d06 / 2343e84, IOS-094)
                verdict.sender?.let { sender = it }
            }
            is com.kachat.app.util.KaspaSignatureVerifier.SignatureCheck.Invalid -> {
                releaseHeldPayment(myAddress, tx.transactionId)
                Log.w("ChatRepository", "Schnorr signature INVALID for input ${check.inputIndex} of a v${tx.version?.trim()?.toIntOrNull() ?: 0} tx")
                Log.w("ChatRepository", "WARNING: Skipping payment ${tx.transactionId.take(16)} - Schnorr signature verification FAILED")
                return true
            }
            com.kachat.app.util.KaspaSignatureVerifier.SignatureCheck.Unknown -> {
                // The REST answer can't settle it (a field the signature covers is missing). Our
                // node can prove the coins: a coin from this txid paying this address the amount
                // means the payment is real (iOS 8116ab9, XP-033). It can't prove WHO paid, so the
                // sender must come from a second source the REST server doesn't speak for - the
                // KaChat indexer, which reads it from its own node (owner's call for IOS-096 /
                // AND-044, iOS 5a78d06). Until both agree the payment stays held. Filed this way
                // it can never replace a message of ours: [insertMessageIfAbsent] never writes
                // over a stored row, and the sync skips any stored txid before it gets here.
                val confirmedSender = if (nodeConfirmsIncomingPayment(tx.transactionId, myAddress, receivedSompi)) {
                    nodeOnlyPaymentSender(sender, indexerPaymentSender(tx.transactionId, myAddress, tx.blockTime ?: 0L))
                } else null
                if (confirmedSender != null) {
                    releaseHeldPayment(myAddress, tx.transactionId)
                    sender = confirmedSender
                    Log.i("ChatRepository", "Payment ${tx.transactionId.take(16)} confirmed by the node, sender by the indexer")
                    // the amount is proven, a memo from the REST answer is not (the payload is
                    // part of what could not be checked): filed as a plain payment
                    payloadBytes = ByteArray(0)
                    isPaymentPayload = false
                } else {
                    holdPayment(myAddress, tx.transactionId)
                    Log.i("ChatRepository", "Payment ${tx.transactionId.take(16)} held - its signatures can't be checked yet")
                    // Final for this read: the held list brings it back on every sync.
                    return true
                }
            }
        }
        // Moving your own money between your own addresses is not a conversation (iOS
        // processPayments). Checked before the contact lookup, as iOS does: an own address CAN
        // also be a saved contact, and it is still not a conversation.
        if (isOwnMoveSender(sender, myAddress)) return true

        // Same tombstone check as processHandshake/syncContextualMessages — an auto-detected
        // payment can just as easily resurrect a deleted contact as a message can. This one
        // matters especially here: syncPayments has no per-contact cursor, so it re-fetches the
        // same recent transactions from the REST API on every ~2s poll — isTombstoned's txId
        // tie-breaker is what stops a just-deleted payment contact from reappearing on the very
        // next cycle, while still letting a genuinely new payment (that happens to land at the
        // exact same block_time as the deleted one — Kaspa's DAG-based block_time isn't strictly
        // monotonic per sender) through instead of being silently dropped forever.
        val blockTime = tx.blockTime ?: System.currentTimeMillis()
        val deleted = database.contactDao().getDeletedContact(sender, myAddress)
        if (isTombstoned(deleted, tx.transactionId, blockTime)) return true

        val existingContact = database.contactDao().getContact(sender, myAddress)
        var conversationId = sender
        // The memo, in the "Received X KAS — memo" form the payment card splits on (iOS
        // paymentContent). A payload that can't be opened just leaves the memo out.
        val memo = if (isPaymentPayload) {
            runCatching { MessageProtocol.decryptPaymentPayload(payloadBytes, walletManager.getPrivateKeyBytes())?.message }
                .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        } else null
        var displayText = "Received ${formatKas(receivedSompi)} ${KaspaUnit.symbol}" + (memo?.let { " — $it" } ?: "")
        if (existingContact == null && isPaymentPayload) {
            // A CHAT payment (one carrying a KaChat payment payload) from someone we have no
            // contact for is different from a plain one (iOS 1eb4708): the sender paid us from a
            // chat with us, so it opens their own chat - a Message Request until accepted - and
            // from then on their messages are fetched like anyone else's (the contact row is
            // their routing state). Internal moves from our own spending chain still surface
            // nowhere in chats, and a sender rejected in Message Requests is kept out. Since iOS
            // 927322b a chat payment From a spending address is a plain transfer plus a
            // payment_notice ([handlePaymentNotice]), so this is an older app's payment.
            if (isOwnMoveSender(sender, myAddress)) return true
            if (chatRequestStore.isBlocked(sender)) return true
            // A chat you deleted stays deleted for anything sent before the deletion.
            if (isTombstoned(database.contactDao().getDeletedContact(sender, myAddress), tx.transactionId, blockTime)) return true
            val senderPubKeyHex = runCatching {
                KaspaAddress.decode(sender).second.joinToString("") { "%02x".format(it) }
            }.getOrNull()
            database.contactDao().insert(
                ContactEntity(
                    id = sender, walletAddress = myAddress, alias = null, knsName = null,
                    publicKeyHex = senderPubKeyHex,
                    // Not accepted yet: a Message Request, out of the push watch list.
                    conversationStatus = "pending",
                )
            )
        } else if (existingContact == null) {
            // A plain payment from an address we have NO contact for must not open a chat
            // with the stranger. Internal moves from our own spending chain surface nowhere
            // in chats; genuinely unknown senders collect in the SELF-chat (the conversation
            // with our own chatting address) with the sender noted in the bubble. The wallet
            // notification below still fires either way. A payment_notice naming it later moves
            // it into the payer's chat ([handlePaymentNotice]).
            if (isOwnMoveSender(sender, myAddress)) return true
            val selfDeleted = database.contactDao().getDeletedContact(myAddress, myAddress)
            if (isTombstoned(selfDeleted, tx.transactionId, blockTime)) return true
            conversationId = myAddress
            displayText += "\nFrom: $sender"
            if (database.contactDao().getContact(myAddress, myAddress) == null) {
                database.contactDao().insert(ContactEntity(id = myAddress, walletAddress = myAddress, alias = null, knsName = null, publicKeyHex = null))
            }
        }

        val backfill = isBackfill(blockTime)
        // A payment_notice's bubble is that notice's to confirm (iOS df1ecfb, IOS-083 run 11): one
        // filed while this ran (the sync's exists check is behind the awaits above) stays as it is.
        if (noticeBubbleStore.isNoticeBubble(myAddress, tx.transactionId) &&
            database.messageDao().exists(tx.transactionId, myAddress)
        ) return true
        // Never over a stored row: a REPLACE would refile it in this conversation as "sent".
        val filed = insertMessageIfAbsent(
            MessageEntity(
                id = tx.transactionId,
                contactId = conversationId,
                walletAddress = myAddress,
                type = "pay",
                direction = "received",
                plaintextBody = displayText,
                encryptedPayload = "",
                amountSompi = receivedSompi,
                blockTimestamp = blockTime,
                isRead = backfill || notificationHelper.isViewingContact(conversationId)
            )
        )
        if (!filed) return true

        // The remote push is the only banner source while push is active, foreground or
        // background - see PushState. Only a device with no push at all banners from here.
        if (!backfill && !pushState.isActive) {
            notificationHelper.show(
                contactId = conversationId,
                title = "Payment received",
                text = displayText,
                notificationOverride = ContactNotificationMode.fromName(existingContact?.notificationOverride),
                dedupeTxId = tx.transactionId,
                // a KaPosts tip: one notification with its "tipped your post" banner (IDX-030)
                tipTxId = tx.transactionId.takeIf { MessageProtocol.isTipPayload(payloadBytes) },
            )
        }
        return true
    }

    /**
     * A payment the chatting address SENT, read back from its history (iOS `processPayments` with
     * `isOutgoing: true`): filed as "Sent X KAS" in a chat with its destination - the chat is
     * created when missing, since you chose that address (iOS `getOrCreateContact`; never a
     * Message Request, a sent payment is in it). It is what brings back, after a fresh import, a
     * chat you only ever paid into. The send path files the same row under the same txid when it
     * happens on this phone ([com.kachat.app.viewmodels.ChatViewModel.sendPayment],
     * [recordPlainSendFromChattingAddress]), so the stored-txid check makes this a no-op there;
     * a send still in flight is merged by [finalizeProvisionalMessage].
     *
     * Never a chat with yourself: the chatting address, one of its spending addresses (iOS
     * `allSpendingAddresses`), or another account saved on this phone (which [addContact]
     * refuses as a contact anyway). A deleted chat stays deleted for anything sent before the
     * deletion. Outgoing, so read, and never notified.
     */
    private suspend fun processOutgoingPayment(
        myAddress: String,
        tx: TransactionResponse,
        recipient: String,
        amountSompi: Long,
    ): Boolean {
        if (amountSompi <= 0 || !KaspaAddress.isValid(recipient)) return true
        if (isOwnAccountAddress(recipient) || isOwnSpendingAddress(recipient)) return true
        if (!com.kachat.app.util.KaspaNetwork.isOnActiveNetwork(recipient)) return true
        val blockTime = tx.blockTime ?: System.currentTimeMillis()
        if (isTombstoned(database.contactDao().getDeletedContact(recipient, myAddress), tx.transactionId, blockTime)) return true
        // A payment_notice's bubble is that notice's alone (IOS-083 run 11), whatever its direction.
        if (noticeBubbleStore.isNoticeBubble(myAddress, tx.transactionId) &&
            database.messageDao().exists(tx.transactionId, myAddress)
        ) return true

        if (database.contactDao().getContact(recipient, myAddress) == null) {
            // Active: a chat you paid into, and from now on watched for their reply like any
            // contact (iOS addMessageToConversation's `isPaymentWeSent` routing state).
            database.contactDao().insert(
                ContactEntity(id = recipient, walletAddress = myAddress, alias = null, knsName = null, publicKeyHex = null)
            )
        }
        val filed = insertMessageIfAbsent(
            MessageEntity(
                id = tx.transactionId,
                contactId = recipient,
                walletAddress = myAddress,
                type = "pay",
                direction = "sent",
                // The send path's own wording, so its row and this one read the same.
                plaintextBody = "Sent ${KaspaUnit.plain(amountSompi)} ${KaspaUnit.symbol}",
                encryptedPayload = "",
                amountSompi = amountSompi,
                blockTimestamp = blockTime,
                isRead = true,
                deliveryStatus = "sent"
            )
        )
        if (!filed) return true
        Log.i("ChatRepository", "Restored sent payment ${tx.transactionId.take(12)} in its chat")
        // A chat with no handshake gets its contact note, so the next import finds it again
        // (iOS addMessageToConversation -> ensureContactStash for a sent message).
        contactNotesLazy.get().onMessageDelivered(recipient)
        return true
    }

    // MARK: Received payments held for their signature check (iOS 5806362)

    /** Whether our node lists coins created by [txId] that pay [address] exactly [amount] (the
     *  payment's outputs to us, still unspent - iOS `nodeConfirmsIncomingPayment`). The node is a
     *  second source the REST API can't speak for; a coin already spent can't be seen this way,
     *  so such a payment stays held. */
    private suspend fun nodeConfirmsIncomingPayment(txId: String, address: String, amount: Long): Boolean {
        if (amount <= 0) return false
        val utxos = nodePoolManagerLazy.get().getUtxosByAddress(address) ?: return false
        val paid = utxos
            .filter { it.outpoint.transactionId.equals(txId, ignoreCase = true) }
            .sumOf { it.utxoEntry.amount }
        return paid == amount
    }

    /** The sender the KaChat indexer recorded for [txId] paid to [receiver], lowercased (iOS
     *  `indexerSender`) - read by the indexer from its own node, so it is a source the REST server
     *  can't speak for. Null when the indexer has no such row (it indexes payments that carry a
     *  KaChat payload), names no sender, or can't be read. Second source for a node-confirmed
     *  payment (iOS 5a78d06, IOS-096 / AND-044). */
    private suspend fun indexerPaymentSender(txId: String, receiver: String, blockTime: Long): String? {
        val api = networkService.indexerApi.value ?: return null
        // its rows are read forward from block_time: start a little before the payment
        val from = if (blockTime > 600_000L) blockTime - 600_000L else 0L
        val rows = try {
            api.getPaymentsByReceiver(address = receiver, limit = 50, blockTime = from)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return indexedPaymentSender(rows, txId)
    }

    /** Serializes the read-modify-write of the held list (two syncs can run at once). */
    private val heldPaymentsMutex = kotlinx.coroutines.sync.Mutex()

    /** The held txids due this sync - at most [HELD_PAYMENTS_PER_SYNC], expired ones dropped
     *  with a log line (iOS `heldPaymentTxIds`). */
    private suspend fun heldPaymentTxIds(wallet: String): List<String> = heldPaymentsMutex.withLock {
        val held = settingsRepository.heldPayments(wallet)
        if (held.isEmpty()) return@withLock emptyList()
        val (due, expired) = heldPaymentsDue(held, System.currentTimeMillis())
        if (expired.isNotEmpty()) {
            expired.forEach {
                Log.i("ChatRepository", "Gave up on held payment ${it.take(16)} - signatures still uncheckable after 7 days")
            }
            settingsRepository.setHeldPayments(wallet, held - expired.toSet())
        }
        due
    }

    /** iOS `holdPayment`: the first hold's time is kept. */
    private suspend fun holdPayment(wallet: String, txId: String) = heldPaymentsMutex.withLock {
        val key = txId.lowercase()
        val held = settingsRepository.heldPayments(wallet)
        if (key !in held) settingsRepository.setHeldPayments(wallet, held + (key to System.currentTimeMillis()))
    }

    /** iOS `releaseHeldPayment`. */
    private suspend fun releaseHeldPayment(wallet: String, txId: String) = heldPaymentsMutex.withLock {
        val key = txId.lowercase()
        val held = settingsRepository.heldPayments(wallet)
        if (key in held) settingsRepository.setHeldPayments(wallet, held - key)
    }

    // MARK: KNS domain transfers (iOS ChatService+Fetching.swift, "KNS" section)

    /** A domain transfer this phone just sent (iOS `KNSTransferChatHint`): files its message the
     *  moment the payment sync sees the reveal, named as sent, before the KNS API knows it. In
     *  memory, as on iOS - after a restart the reveal's own inputs decide. */
    private data class KnsTransferHint(
        val txId: String,
        val domainName: String,
        val domainId: String,
        val counterpartyAddress: String,
        val isOutgoing: Boolean,
        val timestampMs: Long,
    )

    private val knsTransferHints = java.util.concurrent.ConcurrentHashMap<String, KnsTransferHint>()

    /** iOS `registerKNSTransferChatHint`, called by [com.kachat.app.services.WalletService.transferDomain]
     *  once the reveal is submitted. Ignored unless every field is there. */
    fun registerKnsTransferHint(
        txId: String,
        domainName: String,
        domainId: String,
        counterpartyAddress: String,
        isOutgoing: Boolean,
        timestampMs: Long? = null,
    ) {
        val key = txId.trim().lowercase()
        val domain = domainName.trim().lowercase()
        val id = domainId.trim()
        val counterparty = counterpartyAddress.trim()
        if (key.isEmpty() || domain.isEmpty() || id.isEmpty() || counterparty.isEmpty()) return
        knsTransferHints[key] = KnsTransferHint(
            txId = txId, domainName = domain, domainId = id, counterpartyAddress = counterparty,
            isOutgoing = isOutgoing, timestampMs = timestampMs ?: System.currentTimeMillis()
        )
    }

    /** iOS `handleKNSOperationTransactionIfNeeded` for a reveal: only a domain transfer files a message. */
    private suspend fun handleKnsRevealTransaction(myAddress: String, tx: TransactionResponse) {
        val transfer = parseKnsTransferOperation(
            (tx.inputs as List<com.kachat.app.services.TransactionInput>?).orEmpty().map { it.signatureScript as String? }
        ) ?: return
        ingestKnsTransferMessage(myAddress, tx, transfer.domainId, transfer.recipientAddress)
    }

    /** iOS `addKNSTransferMessageFromHintIfNeeded`: true when a hint decided this txid. */
    private suspend fun addKnsTransferMessageFromHint(myAddress: String, txId: String, blockTimeMs: Long?): Boolean {
        val key = txId.trim().lowercase()
        val hint = knsTransferHints[key] ?: return false
        database.messageDao().getById(txId, myAddress)?.let { existing ->
            if (existing.type == MessageProtocol.TYPE_PAY) {
                database.messageDao().deleteById(txId, myAddress)
            } else {
                knsTransferHints.remove(key)
                return true
            }
        }
        val blockTime = blockTimeMs?.takeIf { it > 0 } ?: hint.timestampMs
        fileKnsTransferMessage(
            myAddress = myAddress,
            txId = txId,
            contactAddress = hint.counterpartyAddress,
            isOutgoing = hint.isOutgoing,
            content = knsTransferMessageText(hint.domainName, hint.isOutgoing),
            blockTime = blockTime,
        )
        knsTransferHints.remove(key)
        Log.i("ChatRepository", "Added KNS transfer message from hint tx=${txId.take(12)} domain=${hint.domainName}")
        return true
    }

    /**
     * A domain transfer read from the payment history (iOS `ingestKNSTransferMessage`): a text
     * message - "Sent <domain> domain" in the chat with the recipient, or "Received <domain>
     * domain" in the chat with the sender - only when this wallet is a party to it: the
     * recipient, or the signer of one of its inputs (its chatting or a spending address). A KNS
     * transfer names its recipient in plaintext on chain, so being "not the recipient" alone
     * would file every stranger's transfer as one we sent (the leak iOS fixed).
     */
    private suspend fun ingestKnsTransferMessage(
        myAddress: String,
        tx: TransactionResponse,
        domainId: String,
        recipientAddress: String?,
    ) {
        val txId = tx.transactionId.trim()
        if (txId.isEmpty()) return
        val blockTimeMs = tx.acceptingBlockTime ?: tx.blockTime ?: System.currentTimeMillis()
        if (addKnsTransferMessageFromHint(myAddress, txId, blockTimeMs)) return

        database.messageDao().getById(txId, myAddress)?.let { existing ->
            if (existing.type == MessageProtocol.TYPE_PAY) database.messageDao().deleteById(txId, myAddress) else return
        }

        val me = myAddress.trim().lowercase()
        val recipient = recipientAddress?.trim().orEmpty()
        if (recipient.isEmpty()) {
            Log.i("ChatRepository", "KNS transfer ${txId.take(12)} missing recipient in payload")
            return
        }
        // the memoized own set (iOS d80c497), plus the chatting address in use
        val myAddresses = HashSet(ownAddressSet()).apply { add(me) }
        val inputAddresses = (tx.inputs as List<com.kachat.app.services.TransactionInput>?).orEmpty()
            .mapNotNull { it.previousOutpointAddress }
        val outputAddresses = (tx.outputs as List<com.kachat.app.services.TransactionOutput>?).orEmpty()
            .mapNotNull { it.scriptPublicKeyAddress }
        val party = knsTransferCounterparty(myAddress, recipient, myAddresses, inputAddresses, outputAddresses)
        if (party == null) {
            Log.i("ChatRepository", "KNS transfer ${txId.take(12)} is not ours (recipient=${recipient.takeLast(10)}) - ignoring")
            knsTransferHints.remove(txId.lowercase())
            return
        }
        val (isOutgoing, contactAddress) = party
        if (contactAddress == null || contactAddress.isEmpty() || contactAddress.lowercase() == me ||
            !KaspaAddress.isValid(contactAddress)
        ) {
            Log.i("ChatRepository", "KNS transfer ${txId.take(12)} has unresolved counterparty")
            return
        }

        val domainName = resolveKnsTransferDomainName(domainId, myAddress, contactAddress)
        fileKnsTransferMessage(
            myAddress = myAddress,
            txId = txId,
            contactAddress = contactAddress,
            isOutgoing = isOutgoing,
            content = knsTransferMessageText(domainName?.takeIf { it.isNotEmpty() }, isOutgoing),
            blockTime = blockTimeMs,
        )
        knsTransferHints.remove(txId.lowercase())
        Log.i(
            "ChatRepository",
            "Added KNS transfer message tx=${txId.take(12)} direction=${if (isOutgoing) "outgoing" else "incoming"} domain=${domainName ?: domainId}"
        )
    }

    /** iOS `resolveKNSTransferDomainName`: the cached domain lists of us, then the other party,
     *  then anyone; else up to three asks of the KNS API, 1.2 s apart. */
    private suspend fun resolveKnsTransferDomainName(domainId: String, myAddress: String, counterparty: String?): String? {
        val id = domainId.trim()
        if (id.isEmpty()) return null
        val kns = knsServiceLazy.get()
        kns.cachedDomainNameForAssetId(id, myAddress)?.let { return it }
        if (counterparty != null) kns.cachedDomainNameForAssetId(id, counterparty)?.let { return it }
        kns.cachedDomainNameForAssetId(id, ownerAddress = null)?.let { return it }
        for (attempt in 1..3) {
            kns.resolveDomainName(id)?.let { return it }
            if (attempt < 3) delay(1_200)
        }
        return null
    }

    /**
     * Files a KNS transfer message the way iOS `addMessageToConversation` files any message: not
     * for a chat deleted after it, not from a sender rejected in Message Requests, the chat
     * created when missing (a stranger who sent us a domain is a Message Request, as a chat
     * payment from one is), read when it is ours or backfilled history, and a banner for a new
     * one received live.
     */
    private suspend fun fileKnsTransferMessage(
        myAddress: String,
        txId: String,
        contactAddress: String,
        isOutgoing: Boolean,
        content: String,
        blockTime: Long,
    ) {
        if (!com.kachat.app.util.KaspaNetwork.isOnActiveNetwork(contactAddress)) return
        if (isOwnAccountAddress(contactAddress)) return
        if (isTombstoned(database.contactDao().getDeletedContact(contactAddress, myAddress), txId, blockTime)) return
        if (!isOutgoing && chatRequestStore.isBlocked(contactAddress)) return

        val contact = database.contactDao().getContact(contactAddress, myAddress)
            ?: ContactEntity(
                id = contactAddress, walletAddress = myAddress, alias = null, knsName = null, publicKeyHex = null,
                conversationStatus = if (isOutgoing) "active" else "pending",
            ).also { database.contactDao().insert(it) }

        val backfill = isBackfill(blockTime)
        val filed = insertMessageIfAbsent(
            MessageEntity(
                id = txId,
                contactId = contactAddress,
                walletAddress = myAddress,
                type = MessageProtocol.TYPE_COMM,
                direction = if (isOutgoing) "sent" else "received",
                plaintextBody = content,
                encryptedPayload = "",
                amountSompi = null,
                blockTimestamp = blockTime,
                isRead = isOutgoing || backfill || notificationHelper.isViewingContact(contactAddress),
                deliveryStatus = "sent",
            )
        )
        if (!filed) return
        if (isOutgoing) {
            contactNotesLazy.get().onMessageDelivered(contactAddress)
            return
        }
        if (!backfill && !pushState.isActive && !notifyAsMessageRequest(contact, myAddress, txId)) {
            notificationHelper.show(
                contactId = contact.id,
                title = contact.displayName,
                text = content,
                notificationOverride = ContactNotificationMode.fromName(contact.notificationOverride),
                dedupeTxId = txId,
            )
        }
    }

    companion object {
        /**
         * [firstContactInboxTag]'s decision. Every address check is case-insensitive, the
         * "have they written to us" lookup included: it used to match the stored conversation
         * exactly, so an address typed or scanned in upper case sent an established contact a
         * tagged first-contact message, landing in their Message Requests (iOS 1e07e72, audit
         * IOS-008). [hasReceivedFrom] is handed the lowercased address, and answers whether they
         * sent us a message - a payment from them is not one (iOS 1eb4708).
         */
        internal suspend fun firstContactInboxTagFor(
            address: String,
            me: String,
            state: ChatRequestState,
            hasReceivedFrom: suspend (lowercasedAddress: String) -> Boolean,
            inboxSupported: suspend () -> Boolean,
        ): String? {
            if (address.equals(me, ignoreCase = true)) return null
            val key = address.lowercase()
            if (key in state.privateChats || key in state.inboxTagged) return null
            if (hasReceivedFrom(key)) return null
            if (!inboxSupported()) return null
            return com.kachat.app.util.InboxTag.compute(address)
        }

        /**
         * The payment sync's direction analysis (iOS `fetchPaymentsFromKaspaAPI`): [inputs] are the
         * spent outpoints' addresses (null = not resolved), [outputs] each output's address and
         * amount, [outputScripts] each output's script hex. An output pays us when its script is
         * [myScriptHex]; only an output without a script (or without [myScriptHex]) falls back to
         * its address label, and one labelled ours whose script isn't counts for nobody (IOS-098).
         *  - Outgoing: one of the inputs is ours and something goes to another address. The
         *    destination is the smallest output to an address that is neither ours nor the
         *    first foreign input's (the larger one is usually change); a foreign input's own
         *    output is the fallback.
         *  - Incoming: none of the resolved inputs is ours and something comes to us; the payer is
         *    the first foreign input.
         *  - Unresolved: no input address resolved at all - the next poll retries.
         * Addresses compare case-insensitively.
         */
        internal fun classifyPaymentTx(
            myAddress: String,
            inputs: List<String?>,
            outputs: List<Pair<String?, Long>>,
            outputScripts: List<String?> = emptyList(),
            myScriptHex: String? = null,
        ): PaymentTxDirection {
            val resolved = inputs.mapNotNull { it?.trim()?.takeIf { a -> a.isNotEmpty() } }
            if (resolved.isEmpty()) return PaymentTxDirection.Unresolved
            val weAreSender = resolved.any { it.equals(myAddress, ignoreCase = true) }
            val foreignSender = resolved.firstOrNull { !it.equals(myAddress, ignoreCase = true) }

            var toUs = 0L
            var toOthers = 0L
            var recipient: String? = null
            var recipientAmount = 0L
            for ((i, output) in outputs.withIndex()) {
                val (rawAddress, amount) = output
                // paid to us: by the output's script when there is one (the signed data), else
                // by its label (IOS-098 / AND-045)
                val script = outputScripts.getOrNull(i)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                val paysUs = if (script != null && myScriptHex != null) {
                    script == myScriptHex
                } else {
                    rawAddress?.trim()?.equals(myAddress, ignoreCase = true) == true
                }
                if (paysUs) {
                    toUs += amount
                    continue
                }
                val address = rawAddress?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                // labelled ours, but the script says otherwise: not ours, and not anyone's we can
                // name either
                if (address.equals(myAddress, ignoreCase = true)) continue
                toOthers += amount
                if (foreignSender == null || !address.equals(foreignSender, ignoreCase = true)) {
                    // the actual destination, not change back to a co-signing sender: prefer the
                    // smaller amount (the larger is usually change)
                    if (recipient == null || amount < recipientAmount) {
                        recipient = address
                        recipientAmount = amount
                    }
                } else if (recipient == null) {
                    recipient = address
                    recipientAmount = amount
                }
            }

            if (weAreSender) {
                if (toOthers <= 0 || recipient == null) return PaymentTxDirection.None
                return PaymentTxDirection.Outgoing(recipient, recipientAmount)
            }
            if (toUs <= 0 || foreignSender == null) return PaymentTxDirection.None
            return PaymentTxDirection.Incoming(foreignSender, toUs)
        }

        /** [classifyPaymentTx] over a REST transaction as the payment sync reads it: "paid to
         *  [myAddress]" from each output's script hex compared with [myAddress]'s own script - the
         *  signed data - with the address label only when there is no script (iOS 5a78d06,
         *  IOS-098 / AND-045). */
        internal fun classifyPaymentTx(myAddress: String, tx: TransactionResponse): PaymentTxDirection {
            val myScriptHex = runCatching { KaspaAddress.getScriptPublicKey(myAddress).lowercase() }.getOrNull()
            // Gson leaves a field the JSON lacks null whatever its Kotlin type
            val outputs = (tx.outputs as List<com.kachat.app.services.TransactionOutput>?).orEmpty()
            return classifyPaymentTx(
                myAddress,
                tx.inputs.map { it.previousOutpointAddress },
                outputs.map { it.scriptPublicKeyAddress to it.amount },
                outputScripts = outputs.map { it.scriptPublicKey as String? },
                myScriptHex = myScriptHex,
            )
        }

        /** The indexer's sender for [txId] among its `payments/by-receiver` [rows], trimmed and
         *  lowercased; null when no row names it (iOS `indexerSender`). */
        internal fun indexedPaymentSender(rows: List<com.kachat.app.services.PaymentIndexerResponse>, txId: String): String? {
            val row = rows.firstOrNull { it.txId?.equals(txId, ignoreCase = true) == true } ?: return null
            return row.sender?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        }

        /** Who a node-confirmed payment is filed from (iOS 5a78d06, IOS-096 / AND-044): only when
         *  the indexer names a sender and it agrees with the REST one (or REST named none) - else
         *  null, and the payment stays held. */
        internal fun nodeOnlyPaymentSender(restSender: String?, indexedSender: String?): String? {
            if (indexedSender == null) return null
            if (restSender != null && restSender.lowercase() != indexedSender) return null
            return restSender ?: indexedSender
        }

        /** The verdict a received payment is filed by (iOS 2343e84, IOS-094): a Valid check with no
         *  verified input that isn't ours names no sender - the REST label is all there is - so it
         *  goes the Unknown way (node + indexer cross-check), never filed under the label. */
        internal fun paymentSignatureCheck(
            verdict: com.kachat.app.util.KaspaSignatureVerifier.ReceivedCheck,
        ): com.kachat.app.util.KaspaSignatureVerifier.SignatureCheck =
            if (verdict.check == com.kachat.app.util.KaspaSignatureVerifier.SignatureCheck.Valid && verdict.sender == null) {
                com.kachat.app.util.KaspaSignatureVerifier.SignatureCheck.Unknown
            } else {
                verdict.check
            }

        /** How many held payments one sync re-reads at most, and how long one is kept (iOS 5806362). */
        internal const val HELD_PAYMENTS_PER_SYNC = 25
        internal const val HELD_PAYMENT_MAX_AGE_MS = 7L * 86_400_000

        /** iOS `heldPaymentTxIds`: (due, expired) of [held] (txid -> first held ms) at [now] - an
         *  entry is given up once more than 7 days old; the rest are due, sorted, at most 25. */
        internal fun heldPaymentsDue(held: Map<String, Long>, now: Long): Pair<List<String>, List<String>> {
            val expired = held.filter { (_, at) -> now > at + HELD_PAYMENT_MAX_AGE_MS }.keys.toList()
            val due = (held.keys - expired.toSet()).sorted().take(HELD_PAYMENTS_PER_SYNC)
            return due to expired
        }

        /** iOS `isKNSRevealSignatureScript`: the signature script of a KNS reveal input - it
         *  pushes "kns" and carries a known operation ("addProfile", "create", "transfer") and a
         *  KNS field ("p":"domain", "key": or "id":), all matched as hex. */
        internal fun isKnsRevealSignatureScript(signatureScriptHex: String): Boolean {
            val lowered = signatureScriptHex.lowercase()
            if (!lowered.contains("036b6e73")) return false // push "kns"
            val hasKnownOp = lowered.contains("226f70223a2261646450726f66696c6522") || // "op":"addProfile"
                lowered.contains("226f70223a2263726561746522") || // "op":"create"
                lowered.contains("226f70223a227472616e7366657222") // "op":"transfer"
            if (!hasKnownOp) return false
            return lowered.contains("2270223a22646f6d61696e22") || // "p":"domain"
                lowered.contains("226b6579223a") || // "key":
                lowered.contains("226964223a") // "id":
        }

        /** iOS `isKNSRevealTransaction`: any input's signature script is a KNS reveal's. */
        internal fun isKnsRevealTransaction(tx: TransactionResponse): Boolean =
            (tx.inputs as List<com.kachat.app.services.TransactionInput>?).orEmpty().any { input ->
                val script = input.signatureScript as String?
                !script.isNullOrEmpty() && isKnsRevealSignatureScript(script)
            }

        /** iOS `decodeKNSRevealOperationJSON`: the JSON object between the script's first `{"`
         *  and its last `}`, or null. */
        internal fun decodeKnsRevealOperationJson(signatureScriptHex: String): com.google.gson.JsonObject? {
            val lowered = signatureScriptHex.lowercase()
            val start = lowered.indexOf("7b22")
            if (start < 0) return null
            val end = lowered.lastIndexOf("7d")
            if (end < start) return null
            val jsonHex = lowered.substring(start, end + 2)
            if (jsonHex.length % 2 != 0) return null
            val bytes = try {
                ByteArray(jsonHex.length / 2) { i ->
                    val hi = Character.digit(jsonHex[2 * i], 16)
                    val lo = Character.digit(jsonHex[2 * i + 1], 16)
                    require(hi >= 0 && lo >= 0)
                    ((hi shl 4) or lo).toByte()
                }
            } catch (e: Exception) {
                return null
            }
            return try {
                com.google.gson.JsonParser.parseString(String(bytes, Charsets.UTF_8))
                    .takeIf { it.isJsonObject }?.asJsonObject
            } catch (e: Exception) {
                null
            }
        }

        /** iOS `parseKNSTransferOperation`: the first KNS reveal input whose operation is a
         *  domain transfer ("op":"transfer", "p" absent or "domain", a non-empty "id"). */
        internal fun parseKnsTransferOperation(signatureScripts: List<String?>): KnsTransferOperation? {
            fun stringField(obj: com.google.gson.JsonObject, key: String): String? {
                val element = obj.get(key) ?: return null
                return if (element.isJsonPrimitive && element.asJsonPrimitive.isString) element.asString else null
            }
            for (script in signatureScripts) {
                if (script.isNullOrEmpty() || !isKnsRevealSignatureScript(script)) continue
                val operation = decodeKnsRevealOperationJson(script) ?: continue
                val op = stringField(operation, "op") ?: continue
                if (op.trim().lowercase() != "transfer") continue
                val proto = stringField(operation, "p")
                if (proto != null && proto.trim().lowercase() != "domain") continue
                val domainId = stringField(operation, "id")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                return KnsTransferOperation(domainId, stringField(operation, "to")?.trim())
            }
            return null
        }

        /**
         * Whether a KNS transfer is this wallet's, and with whom (iOS `ingestKNSTransferMessage`):
         * null when we are neither the recipient nor the signer of an input ([myAddresses]:
         * the chatting address and its spending addresses, lowercased). Otherwise (isOutgoing,
         * counterparty): sent - to the recipient; received - from the first input that is not
         * [myAddress], else the first output that is neither us nor the recipient, else the
         * first output that is not us (null when none).
         */
        internal fun knsTransferCounterparty(
            myAddress: String,
            recipient: String,
            myAddresses: Set<String>,
            inputAddresses: List<String>,
            outputAddresses: List<String>,
        ): Pair<Boolean, String?>? {
            val me = myAddress.trim().lowercase()
            val weAreRecipient = recipient.lowercase() in myAddresses
            val weSigned = inputAddresses.any { address ->
                val normalized = address.trim().lowercase()
                normalized.isNotEmpty() && normalized in myAddresses
            }
            if (!weAreRecipient && !weSigned) return null
            val isOutgoing = !weAreRecipient
            if (isOutgoing) return true to recipient
            inputAddresses.firstOrNull { it.isNotEmpty() && it != myAddress }?.takeIf { it.isNotBlank() }
                ?.let { return false to it }
            val outputs = outputAddresses.map { it.trim() }.filter { it.isNotEmpty() }
            outputs.firstOrNull { it.lowercase() != me && it.lowercase() != recipient.lowercase() }
                ?.let { return false to it }
            return false to outputs.firstOrNull { it.lowercase() != me }
        }

        /** iOS `localizedKNSTransferMessage` (its English strings). */
        internal fun knsTransferMessageText(domainName: String?, isOutgoing: Boolean): String {
            val domain = domainName?.trim().orEmpty()
            if (domain.isNotEmpty()) return if (isOutgoing) "Sent $domain domain" else "Received $domain domain"
            return if (isOutgoing) "Sent domain transfer" else "Received domain transfer"
        }

        /**
         * A `.kachat` registry or offer transaction (register and its commit, buy, list, extend,
         * renew, transfer, offers, release, reclaim) - never a payment chat (iOS 0b4e27e
         * `isKachatContractTransaction`). [ownAddresses]: the chatting address and every revealed
         * spending address, lowercased - read only when the transaction has a P2SH output, since
         * without one the output rule can never say "contract" (iOS d80c497).
         *
         * Since quantum (KIP-22) addresses, "P2SH" alone no longer means "contract": every
         * spending-address send carries P2SH change, and a payment can be PAID from a P2SH
         * address. So:
         * - a `kchat:1:name:` / `kchat:1:offer:` payload is a contract;
         * - a P2SH input is a covenant spend UNLESS it is a KIP-22 spend of its own address
         *   ([isKip22Spend]);
         * - a P2SH output by itself proves nothing on a received payment (it is the payer's
         *   change). The one contract transaction without a payload is the commit, and it pays
         *   only the contract and the committer's own change - so only on this wallet's OWN
         *   sends does "every outside output is P2SH" still mean the commit, never a chat (keeps
         *   commits out of "sent 0.2" payment chats, iOS 32fdaa4).
         */
        internal fun isKachatContractTransaction(tx: TransactionResponse, ownAddresses: () -> Set<String>): Boolean {
            val payload = tx.payload?.takeIf { it.isNotEmpty() }?.let { runCatching { it.hexToBytes() }.getOrNull() }
            if (payload != null) {
                val text = String(payload, Charsets.UTF_8)
                if (text.startsWith("kchat:1:name:") || text.startsWith("kchat:1:offer:")) return true
            }
            for (input in tx.inputs) {
                if (isScriptAddress(input.previousOutpointAddress) && !isKip22Spend(input)) return true
            }
            // Without a P2SH output the output rule below can never say "contract" - and the own
            // set, memoized as it is, is not worth touching for the common all-key transaction.
            if (tx.outputs.none { isScriptAddress(it.scriptPublicKeyAddress) }) return false
            val own = ownAddresses()
            val paidByUs = tx.inputs.any { input ->
                val address = input.previousOutpointAddress?.lowercase() ?: return@any false
                address in own
            }
            if (!paidByUs) return false
            val outside = tx.outputs.filter { output ->
                val address = output.scriptPublicKeyAddress?.lowercase() ?: return@filter true
                address !in own
            }
            return outside.isNotEmpty() && outside.all { isScriptAddress(it.scriptPublicKeyAddress) }
        }

        /**
         * A KIP-22 (quantum address) spend of a P2SH input (iOS `isKip22Spend`): the signature
         * script is exactly `0x41 <64-byte sig> <sighash byte>` then the canonical push of the
         * 34-byte redeem `0x20 <x-only key> 0xac` (101 bytes in all), and the redeem hashes to the
         * input's own P2SH address. Anything else spending P2SH (the registry's reveal, bond, gap
         * and offer covenants) is a contract.
         */
        internal fun isKip22Spend(input: TransactionInput): Boolean {
            val hex = input.signatureScript as String?
            if (hex == null || hex.length != 202) return false
            val b = runCatching { hex.hexToBytes() }.getOrNull() ?: return false
            if (b.size != 101 || b[0] != 0x41.toByte() || b[66] != 0x22.toByte() || b[67] != 0x20.toByte() ||
                b[100] != 0xac.toByte()
            ) return false
            val redeem = b.copyOfRange(67, 101)
            val address = input.previousOutpointAddress?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
            val expected = com.kachat.app.services.kachatnames.KachatNamesService.p2shAddress(
                com.kachat.app.services.kachatnames.KachatNames.Codec.p2shScript(redeem)
            ) ?: return false
            return expected.lowercase() == address
        }

        /** A pay-to-script-hash address (`kaspa:p...` / `kaspatest:p...`, version byte 0x08):
         *  a contract, never a chat partner (iOS 32fdaa4, isScriptAddress). */
        internal fun isScriptAddress(address: String?): Boolean {
            if (address.isNullOrBlank()) return false
            return runCatching { KaspaAddress.decode(address.trim().lowercase()).first == 0x08.toByte() }.getOrDefault(false)
        }

        /** "1", "3.98962" — trimmed decimal KAS amount, matching the reference apps' payment bubble style. */
        internal fun formatKas(sompi: Long): String {
            val kas = sompi.toDouble() / 100_000_000.0
            return String.format(java.util.Locale.US, "%.8f", kas).trimEnd('0').trimEnd('.')
        }

        /**
         * Decides the conversation status for a freshly-received handshake, given the
         * existing contact's prior state (null if this is a never-seen-before sender).
         * If we already sent them a handshake (or the conversation is already active),
         * this incoming one is their reply — auto-activate. Also auto-activates if THEY
         * marked this handshake as a response ([HandshakePayload.isResponse]) — that's them
         * confirming the connection, which needs to clear our own pending/request-to-connect
         * state even if we ourselves never sent a handshake (e.g. they're replying to a plain
         * message we sent to a contact we added manually, not via a handshake at all).
         * Otherwise it's a fresh incoming request that needs an explicit Accept/Decline from
         * the user.
         */
        internal fun deriveIncomingHandshakeStatus(existingStatus: String?, existingHandshakeComplete: Boolean, incomingIsResponse: Boolean = false): String {
            return when {
                existingStatus == "active" -> "active"
                existingHandshakeComplete -> "active"
                incomingIsResponse -> "active"
                else -> "pending"
            }
        }

        /**
         * Whether an incoming photo bubble from [contact] should auto-decode and render, vs.
         * staying hidden behind a "Show Photo" tap. Mirrors iOS's
         * `ContactsManager.shouldAutoDisplayPhotos(for:settings:)`.
         *
         * Unlike iOS (which added a dedicated `isAutoAdded`/`hasSentOutgoingMessage` pair),
         * Android already has an equivalent trust signal in [ContactEntity.conversationStatus]:
         * "pending" means an unsolicited incoming handshake the user hasn't accepted (or replied
         * to with their own handshake) yet, while "active" means either the user added/messaged
         * them first or accepted their request — see [deriveIncomingHandshakeStatus] and
         * `ChatViewModel.addContact`, which defaults manually-added contacts to "active". Since a
         * contact can't send a photo message at all without a completed handshake, this reuses
         * that field instead of duplicating it.
         *
         * [requireApproval] is Settings > Chats > "Require approval for photos from new contacts"
         * (on by default); off, Automatic shows every contact's photos, as on iOS.
         */
        fun shouldAutoDisplayPhotos(contact: ContactEntity?, requireApproval: Boolean = true): Boolean {
            return when (PhotoAutoDisplayMode.fromName(contact?.photoAutoDisplayOverride)) {
                PhotoAutoDisplayMode.ALWAYS_SHOW -> true
                PhotoAutoDisplayMode.ALWAYS_HIDE -> false
                PhotoAutoDisplayMode.AUTOMATIC -> !requireApproval || contact?.conversationStatus == "active"
            }
        }

        /**
         * Unlike handshake payloads (raw binary on-chain), comm payloads are base64 text
         * on-chain ("kchat:1:comm:<alias>:<base64>", or the legacy `ciph_msg:1:` root) — the indexer's message_payload
         * for a contextual message is hex(base64 ascii text), not hex(raw bytes) like a
         * handshake's. Decode both layers to get back to the actual encrypted bytes.
         */
        /**
         * The sealed bytes of a contextual message read straight OFF CHAIN, where the payload is
         * the whole "kchat:1:comm:<alias>:<base64>" string rather than the indexer's already
         * stripped `message_payload`. Null when it is not a contextual message at all.
         */
        internal fun decodeOnChainContextualPayload(hexPayload: String): ByteArray? {
            val text = try {
                // A first-contact `dm` message reads as `comm` (NO_HANDSHAKE_MESSAGING.md).
                MessageProtocol.normalizeFirstContact(String(hexPayload.hexToBytes(), Charsets.US_ASCII))
            } catch (e: Exception) {
                return null
            }
            val prefix = listOf("kchat:1:comm:", "ciph_msg:1:comm:").firstOrNull { text.startsWith(it) }
                ?: return null
            // "<alias>:<base64>" - the alias is plaintext and colon-delimited, so the sealed part
            // is everything after the FIRST colon that follows the prefix.
            val rest = text.removePrefix(prefix)
            val base64 = rest.substringAfter(':', missingDelimiterValue = "")
            if (base64.isEmpty()) return null
            return try { Base64.getDecoder().decode(base64) } catch (e: Exception) { null }
        }

        internal fun decodeContextualMessagePayload(hexPayload: String): ByteArray {
            val base64Text = String(hexPayload.hexToBytes(), Charsets.US_ASCII)
            return Base64.getDecoder().decode(base64Text)
        }

        /** The indexer's alias query param is hex-of-the-ASCII-bytes of the 12-char alias string, not hex-of-the-raw-6-bytes. */
        internal fun hexEncodeAscii(s: String): String =
            s.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

        /** In-chat tick on WiFi — the one cadence deliberately kept from before. */
        private const val POLL_INTERVAL_MS = 2_000L

        /** No conversation on screen: the chat list only needs fresh-ish, not live. */
        private const val POLL_INTERVAL_IDLE_MS = 15_000L

        /** Metered (cellular) tiers — see the poll loop in init. */
        private const val POLL_INTERVAL_IN_CHAT_METERED_MS = 5_000L
        private const val POLL_INTERVAL_IDLE_METERED_MS = 30_000L

        /** Cap for the double-per-consecutive-indexer-failure backoff in the poll loop. */
        private const val POLL_BACKOFF_CAP_MS = 60_000L

        /** How often a contact's own transactions are read for aliases - see [discoverPeerAliases]. */
        private const val ALIAS_DISCOVERY_ON_SCREEN_MS = 30_000L
        private const val ALIAS_DISCOVERY_INTERVAL_MS = 5 * 60_000L
        private const val ALIAS_DISCOVERY_TX_LIMIT = 25

        /** Poll-path payments cadence/page — see [syncMessages]; manual refresh keeps limit 50. */
        private const val PAYMENT_POLL_INTERVAL_MS = 20_000L
        private const val PAYMENT_POLL_LIMIT = 10
        /** How far back a payment catch-up pages at most (x 50 transactions) - iOS 20155aa. */
        private const val PAYMENT_CATCH_UP_MAX_PAGES = 4
        /** A whole-history payment read ([syncPayments]): iOS fetchFullTransactionsPaginated's
         *  page size and its 10,000-transaction cap. */
        private const val PAYMENT_HISTORY_PAGE_SIZE = 50
        private const val PAYMENT_HISTORY_MAX_PAGES = 10_000 / PAYMENT_HISTORY_PAGE_SIZE
        /** Pauses before each try of one history page (iOS historyPageRetryDelaysSeconds). */
        private val PAYMENT_HISTORY_RETRY_DELAYS_MS = listOf(0L, 600L, 2_000L, 5_000L)

        /** Poll-path contact-sweep shape — see [syncContextualMessages]. Matches iOS's
         *  startForegroundContactSweep (5s between sweeps, 100-120ms between contacts, cap 40). */
        private const val CONTACT_SWEEP_MIN_INTERVAL_MS = 5_000L
        /** Tries per pushed message before the ingest leaves it to the regular sync, and the
         *  least time between two of them. */
        private const val PUSHED_MAX_ATTEMPTS = 6
        private const val PUSHED_RETRY_SPACING_MS = 10_000L
        /** How often the fast poll tick also asks the inbox for first-contact messages. */
        private const val INBOX_POLL_INTERVAL_MS = 30_000L
        private const val CONTACT_SWEEP_CAP = 40
        private const val CONTACT_SWEEP_SPACING_MS = 110L

        /**
         * Page size and per-sweep page cap for the paginated contextual-message fetch in
         * [syncContextualMessages]. A caught-up conversation always ends on its first, short page,
         * so the cap only bites while a genuine backlog is draining (the first fetch of a peer's
         * whole pre-handshake history) - and that simply continues on the next sweep from the
         * cursor this one left behind.
         */
        private const val CONTEXTUAL_PAGE_LIMIT = 50
        private const val CONTEXTUAL_MAX_PAGES_PER_SWEEP = 20

        /** How far behind a contact's cursor a catch-up sweep starts - iOS syncReorgBufferMs. */
        private const val SYNC_REORG_REWIND_MS = 10L * 60 * 1000

        /** The same for the fast open-chat tick - iOS liveTailReorgBufferMs. Small, so the tick
         *  does not re-download a ten-minute window every two seconds. */
        private const val LIVE_TAIL_REORG_REWIND_MS = 90L * 1000

        /** How far behind a reply to look for the message it replied to - iOS replyRecoveryRewindMs. */
        private const val REPLY_RECOVERY_REWIND_MS = 24L * 60 * 60 * 1000

        /**
         * How far apart a provisional placeholder's wall-clock timestamp and its confirmed
         * sibling's chain blockTime may sit and still count as the same logical message. The
         * real pair is minutes apart at most (both are epoch ms around the moment of sending;
         * only the IMPORT may happen days later, and import time doesn't enter the comparison),
         * so 48 hours is generous headroom for clock skew while keeping an old identical text
         * ("ok", a repeated payment amount) from being mistaken for the twin.
         */
        internal const val PROVISIONAL_MATCH_WINDOW_MS = 48L * 60 * 60 * 1000

        /** Tolerated device-clock vs chain-clock skew when deciding "the archive row is not older
         *  than the placeholder" in [matchProvisionalOutgoing]'s sole-candidate rule. */
        internal const val PROVISIONAL_CLOCK_SKEW_MS = 10L * 60 * 1000

        /** Minimum placeholder age before the repair sweep may touch it — anything younger could
         *  be a live in-flight send whose finalize is about to run. */
        internal const val PROVISIONAL_REPAIR_MIN_AGE_MS = 2L * 60 * 1000
    }
}

/** Which way a transaction moved money for the payment sync - see [ChatRepository.classifyPaymentTx]. */
internal sealed class PaymentTxDirection {
    /** No input address resolved yet. */
    object Unresolved : PaymentTxDirection()
    /** Neither a payment we sent nor one we received (e.g. a move back to ourselves). */
    object None : PaymentTxDirection()
    data class Incoming(val sender: String, val amountSompi: Long) : PaymentTxDirection()
    data class Outgoing(val recipient: String, val amountSompi: Long) : PaymentTxDirection()
}

/** A KNS domain transfer read from a reveal - see [ChatRepository.parseKnsTransferOperation]. */
internal data class KnsTransferOperation(val domainId: String, val recipientAddress: String?)
