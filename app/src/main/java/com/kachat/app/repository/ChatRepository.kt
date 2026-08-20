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
import com.kachat.app.services.ContextualMessageIndexerResponse
import com.kachat.app.services.HandshakeIndexerResponse
import com.kachat.app.services.KasiaIndexerApi
import com.kachat.app.services.KaspaRestApi
import com.kachat.app.services.ChatHistoryExportImportService
import com.kachat.app.services.GoogleDriveBackupService
import com.kachat.app.services.NetworkService
import com.kachat.app.services.NotificationHelper
import com.kachat.app.services.PushState
import com.kachat.app.services.TransactionResponse
import com.kachat.app.services.WalletManager
import com.kachat.app.services.database.KaChatDatabase
import com.kachat.app.util.ImageMessage
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KasiaCipher
import com.kachat.app.util.MessageProtocol
import com.kachat.app.util.MessageReaction
import com.kachat.app.util.MessageReply
import com.kachat.app.util.VoiceMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
    // Consulted ONLY at the notification-posting sites below: while native FCM push is active,
    // the server is the notification source for 1:1 handshakes/messages/payments
    // (PUSH_EXTENSIONS.md §4) and posting here too would duplicate every banner. The sync work
    // itself is never gated on it — it still powers the live UI and the local store.
    private val pushState: PushState,
    private val googleDriveBackupService: GoogleDriveBackupService,
    // Lazy because ChatHistoryExportImportService itself depends on ChatRepository (it reads
    // contacts/messages to build the archive) — a direct circular constructor dependency Dagger
    // can't resolve. Lazy<T> defers instantiation past construction time, breaking the cycle
    // while still letting this class call into it once actually needed (auto-backup).
    private val chatHistoryExportImportServiceLazy: dagger.Lazy<ChatHistoryExportImportService>,
    // Lazy for the same cycle reason: PaymentPoolService sends its envelopes through
    // WalletService, which depends on this repository.
    private val paymentPoolServiceLazy: dagger.Lazy<com.kachat.app.services.PaymentPoolService>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private var backupDebounceJob: Job? = null

    init {
        // Real-time-ish receive for the LIVE UI while the app is open: poll the indexer
        // periodically, mirroring NodePoolManager's probe-loop pattern. syncMessages()
        // already no-ops safely if there's no active wallet yet. Each cycle is just a
        // couple of lightweight indexer GETs, so this can run much faster than a typical
        // polling interval — Kaspa's block time is far faster than 15s ever reflected.
        // Once the app leaves the foreground this loop simply freezes with the process
        // (nothing keeps it alive anymore, by design) — FCM push is the only background
        // delivery path for DMs, so push failures surface instead of being masked.
        scope.launch {
            pruneOldMessages() // once on startup, matching iOS's on-launch retention prune
            while (true) {
                syncMessages()
                delay(POLL_INTERVAL_MS)
            }
        }
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

    fun getLatestMessages(): Flow<List<MessageEntity>> {
        return scopedToActiveAccount({ address -> database.messageDao().getLatestMessagePerContact(address) }, emptyList())
    }

    /** Every message for the active wallet, across all contacts — for chat-history export, not the live UI. */
    suspend fun getAllMessages(): List<MessageEntity> {
        return database.messageDao().getAllMessagesForWallet(walletManager.getAddress())
    }

    fun getUnreadCounts(): Flow<List<UnreadCount>> {
        return scopedToActiveAccount({ address -> database.messageDao().getUnreadCounts(address) }, emptyList())
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

    /**
     * Permanently deletes [contactId] and every local message with them — replaces the old
     * reversible "archive". Talking to them again requires a fresh handshake, same as a stranger.
     * Records a tombstone first so a future re-handshake's full-history re-sync can't silently
     * resurrect the deleted conversation (see [DeletedContactEntity]'s doc comment).
     */
    suspend fun deleteChat(contactId: String) {
        val myAddress = walletManager.getAddress()
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
        database.messageDao().deleteAllForContact(contactId, myAddress)
        database.reactionDao().deleteAllForContact(contactId, myAddress)
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
     */
    private fun isTombstoned(deleted: DeletedContactEntity?, txId: String, blockTime: Long): Boolean {
        if (deleted == null) return false
        if (blockTime < deleted.deletedAt) return true
        if (blockTime == deleted.deletedAt) {
            return deleted.deletedAtTxIds.split(",").contains(txId)
        }
        return false
    }

    suspend fun addContact(contact: ContactEntity) {
        database.contactDao().insert(contact)
    }

    /** Every tombstoned contact address for the active wallet — carried in backups so restores skip deleted chats. */
    suspend fun getAllDeletedContactIds(): List<String> =
        database.contactDao().getAllDeletedContactIds(walletManager.getAddress())

    /** Whether the user deleted the chat with [contactId] — restores must never resurrect it. */
    suspend fun hasDeletionTombstone(contactId: String): Boolean =
        database.contactDao().getDeletedContact(contactId, walletManager.getAddress()) != null

    suspend fun getContact(id: String): ContactEntity? {
        return database.contactDao().getContact(id, walletManager.getAddress())
    }

    suspend fun linkSystemContact(
        contactId: String,
        lookupKey: String,
        displayName: String,
        source: String = "manual",
        photoUri: String? = null
    ) {
        val contact = getContact(contactId) ?: return
        addContact(
            contact.copy(
                alias = displayName,
                systemContactId = lookupKey,
                systemContactName = displayName,
                systemContactLinkSource = source,
                systemContactPhotoUri = photoUri
            )
        )
    }

    /**
     * Refreshes just the cached device-address-book photo of an already-linked contact — used by
     * the periodic sync to backfill contacts linked before photos were stored and to follow photo
     * changes made in the phone's address book. No-op when nothing changed, so it never churns the
     * contacts flow (and therefore never re-renders the chat list) for no reason.
     */
    suspend fun updateSystemContactPhotoUri(contactId: String, photoUri: String?) {
        val contact = getContact(contactId) ?: return
        if (contact.systemContactPhotoUri == photoUri) return
        addContact(contact.copy(systemContactPhotoUri = photoUri))
    }

    suspend fun unlinkSystemContact(contactId: String) {
        val contact = getContact(contactId) ?: return
        addContact(contact.copy(systemContactId = null, systemContactName = null, systemContactLinkSource = null, systemContactPhotoUri = null))
    }

    suspend fun insertMessage(message: MessageEntity) {
        database.messageDao().insert(message)
        scheduleAutoBackupIfEnabled()
    }

    /**
     * Debounced automatic Google Drive backup — every new message (sent or received, from any
     * insertion path, since all of them now funnel through [insertMessage]) restarts an ~8s
     * quiet-time timer rather than uploading on every single message, so a rapid exchange only
     * triggers one upload after things settle. No-ops entirely if backup isn't enabled. Runs on
     * this repository's own always-alive scope, not any UI-scoped one, so it isn't cut short by
     * navigating away from whatever screen sent the message.
     */
    private fun scheduleAutoBackupIfEnabled() {
        scope.launch {
            if (!settingsRepository.googleBackupEnabled.first()) return@launch
            backupDebounceJob?.cancel()
            backupDebounceJob = scope.launch {
                delay(AUTO_BACKUP_DEBOUNCE_MS)
                try {
                    val myAddress = walletManager.getAddress()
                    val json = chatHistoryExportImportServiceLazy.get().buildArchiveJson()
                    val success = googleDriveBackupService.uploadBackup(myAddress, json)
                    if (success) pruneOldMessages()
                } catch (e: Exception) {
                    Log.w("ChatRepository", "Automatic Google Drive backup failed", e)
                }
            }
        }
    }

    /**
     * Backup retention pruning — permanently deletes messages older than the configured window
     * for the active account. Only runs while Google Drive backup is enabled and retention isn't
     * FOREVER: retention is presented to the user as a property of the backup feature, not an
     * always-on independent rule (deliberately diverges from iOS's `MessageStore.applyRetention`,
     * which prunes regardless of iCloud sync state — see the plan doc for why).
     */
    suspend fun pruneOldMessages() {
        if (!settingsRepository.googleBackupEnabled.first()) return
        val retention = settingsRepository.backupRetention.first()
        val cutoff = retention.cutoffMillis(System.currentTimeMillis()) ?: return
        val myAddress = try { walletManager.getAddress() } catch (e: Exception) { return }
        database.messageDao().deleteOlderThan(myAddress, cutoff)
    }

    suspend fun messageExists(id: String): Boolean {
        return database.messageDao().exists(id, walletManager.getAddress())
    }

    suspend fun updateMessageStatus(id: String, status: String) {
        database.messageDao().updateStatus(id, walletManager.getAddress(), status)
    }

    suspend fun deleteMessage(id: String) {
        database.messageDao().deleteById(id, walletManager.getAddress())
    }

    /** Replaces any previous reaction [reactorAddress] left on [targetTxId] with [emoji] - one reaction per (message, reactor). */
    suspend fun upsertReaction(targetTxId: String, reactorAddress: String, contactId: String, emoji: String, reactionTxId: String?, blockTimestamp: Long, deliveryStatus: String = "sent", failedAction: String? = null) {
        database.reactionDao().upsertReaction(
            ReactionEntity(
                targetTxId = targetTxId,
                walletAddress = walletManager.getAddress(),
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

    suspend fun removeReaction(targetTxId: String, reactorAddress: String) {
        database.reactionDao().deleteReaction(targetTxId, walletManager.getAddress(), reactorAddress)
    }

    fun getReactionsForContact(contactId: String): Flow<List<ReactionEntity>> {
        return database.reactionDao().getReactionsForContact(contactId, walletManager.getAddress())
    }

    /**
     * "Wipe and re-sync incoming messages" — deletes only received messages for the active
     * account (sent messages, contacts, and the wallet itself are untouched), resets every sync
     * cursor back to the start (payment baseline, handshake block_time cursor, and every
     * per-contact-per-alias message cursor — see [MessageSyncCursorEntity]) so a full re-fetch
     * happens on the sync that follows instead of picking up where the now-deleted local cache
     * left off, then triggers that sync immediately. Matches iOS's `wipeIncomingMessagesAndResync`.
     */
    suspend fun wipeIncomingMessagesAndResync() {
        val myAddress = walletManager.getAddress()
        database.messageDao().deleteReceivedForWallet(myAddress)
        database.messageDao().deleteSyncCursorsForWallet(myAddress)
        settingsRepository.setPaymentSyncBaseline(myAddress, 0L)
        settingsRepository.setHandshakeSyncCursor(myAddress, 0L)
        syncMessages()
    }

    /** Deletes every local message and contact for [address] — used when wiping an account entirely. Does not touch the wallet's keys (see WalletManager.deleteAccount) or any Google Drive backup. */
    suspend fun wipeAllLocalDataForAddress(address: String) {
        database.messageDao().deleteAllForWallet(address)
        database.reactionDao().deleteAllForWallet(address)
        database.messageDao().deleteSyncCursorsForWallet(address)
        database.contactDao().deleteAllForWallet(address)
        database.contactDao().deleteTombstonesForWallet(address)
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

    suspend fun syncMessages() {
        val myAddress = try { walletManager.getAddress() } catch (e: Exception) { return }
        val api = networkService.indexerApi.value ?: return
        liveBaselineMs = settingsRepository.liveNotificationBaseline(myAddress)

        syncHandshakes(myAddress, api)
        syncContextualMessages(myAddress, api)
        networkService.kaspaRestApi.value?.let { syncPayments(myAddress, it) }
    }

    /** True when [blockTime] predates this account's first sync on this device — backfilled history, not live traffic. */
    private fun isBackfill(blockTime: Long?): Boolean = (blockTime ?: 0L) < liveBaselineMs

    private suspend fun syncHandshakes(myAddress: String, api: KasiaIndexerApi) {
        // block_time cursor — see AppSettingsRepository.handshakeSyncCursor's doc comment. Only
        // fetches what's genuinely new since the last successful sync instead of the same recent
        // window every cycle.
        val cursor = settingsRepository.handshakeSyncCursor(myAddress).first()
        val handshakes = try {
            api.getHandshakesByReceiver(myAddress, blockTime = cursor)
        } catch (e: Exception) {
            Log.w("ChatRepository", "Failed to fetch handshakes", e)
            return
        }

        for (handshake in handshakes) {
            try {
                if (database.messageDao().exists(handshake.txId, myAddress)) continue
                processHandshake(myAddress, handshake)
            } catch (e: Exception) {
                Log.w("ChatRepository", "Failed to process handshake ${handshake.txId}", e)
            }
        }

        val maxBlockTime = handshakes.maxOfOrNull { it.blockTime }
        if (maxBlockTime != null && maxBlockTime > (cursor ?: 0L)) {
            settingsRepository.setHandshakeSyncCursor(myAddress, maxBlockTime)
        }
    }

    private suspend fun processHandshake(myAddress: String, handshake: HandshakeIndexerResponse) {
        if (!KaspaAddress.isValid(handshake.sender)) return

        // A deleted contact's tombstone outlives the contact row itself. This still matters even
        // with the block_time sync cursor above: the very first sync for a *newly re-created*
        // contact (e.g. a fresh handshake after deletion) has no cursor yet, so that one fetch can
        // still surface the old pre-deletion handshake transaction if the indexer hasn't pruned it.
        // Only a handshake sent *after* the deletion creates a real contact/conversation.
        val deleted = database.contactDao().getDeletedContact(handshake.sender, myAddress)
        if (isTombstoned(deleted, handshake.txId, handshake.blockTime)) return

        val encryptedBytes = handshake.messagePayload.hexToBytes()
        val encryptedMessage = KasiaCipher.EncryptedMessage.fromBytes(encryptedBytes) ?: return
        val decryptedJson = MessageProtocol.decrypt(encryptedMessage, walletManager.getPrivateKeyBytes())
        val payload = try { gson.fromJson(decryptedJson, HandshakePayload::class.java) } catch (e: Exception) { null }
        val theirAlias = payload?.alias

        val senderPubKeyHex = KaspaAddress.decode(handshake.sender).second.joinToString("") { "%02x".format(it) }
        val existing = database.contactDao().getContact(handshake.sender, myAddress)
        val newStatus = deriveIncomingHandshakeStatus(existing?.conversationStatus, existing?.handshakeComplete ?: false, payload?.isResponse ?: false)

        database.contactDao().insert(
            (existing ?: ContactEntity(id = handshake.sender, walletAddress = myAddress, alias = null, knsName = null, publicKeyHex = null))
                .copy(
                    publicKeyHex = senderPubKeyHex,
                    conversationStatus = newStatus,
                    theirAlias = theirAlias ?: existing?.theirAlias
                )
        )

        val backfill = isBackfill(handshake.blockTime)
        insertMessage(
            MessageEntity(
                id = handshake.txId,
                contactId = handshake.sender,
                walletAddress = myAddress,
                type = MessageProtocol.TYPE_HANDSHAKE,
                direction = "received",
                plaintextBody = "${theirAlias ?: com.kachat.app.util.KaspaAddress.shortDisplay(handshake.sender)} wants to connect",
                encryptedPayload = handshake.messagePayload,
                amountSompi = null,
                blockTimestamp = handshake.blockTime,
                isRead = backfill || notificationHelper.isViewingContact(handshake.sender)
            )
        )

        val displayName = theirAlias ?: com.kachat.app.util.KaspaAddress.shortDisplay(handshake.sender)
        if (!backfill && !pushState.isActive) { // handshake pushes come from the server in remote-push mode
            notificationHelper.show(
                contactId = handshake.sender,
                title = if (newStatus == "pending") "Request to communicate" else "Connected",
                text = if (newStatus == "pending") "$displayName wants to connect" else "$displayName accepted your request",
                notificationOverride = ContactNotificationMode.fromName(existing?.notificationOverride)
            )
        }
    }

    private suspend fun syncContextualMessages(myAddress: String, api: KasiaIndexerApi) {
        val activeContacts = database.contactDao().getContactsByStatus("active", myAddress)

        for (contact in activeContacts) {
            // See processHandshake's identical tombstone check — still needed even with the
            // block_time cursor below, since a newly re-created contact's first-ever sync has no
            // cursor yet and could otherwise surface old pre-deletion messages.
            val deleted = database.contactDao().getDeletedContact(contact.id, myAddress)

            // Legacy: the alias they told us in their handshake reply, if any. Deterministic:
            // derivable purely from both addresses, so it's always tryable even with no
            // handshake at all — see WalletManager.myDeterministicAlias.
            val legacyAliasHex = contact.theirAlias?.let { hexEncodeAscii(it) }
            val deterministicAliasHex = try {
                hexEncodeAscii(walletManager.myDeterministicAlias(contact.id))
            } catch (e: Exception) {
                null // Non-Schnorr/invalid address — skip the deterministic candidate for this contact.
            }

            for (aliasHex in listOfNotNull(legacyAliasHex, deterministicAliasHex).distinct()) {
                // block_time cursor, tracked per (contact, alias) since each is its own independent
                // stream on the indexer — see MessageSyncCursorEntity's doc comment.
                val cursor = database.messageDao().getMessageSyncCursor(contact.id, myAddress, aliasHex)
                val messages = try {
                    api.getContextualMessagesBySender(contact.id, aliasHex, blockTime = cursor)
                } catch (e: Exception) {
                    Log.w("ChatRepository", "Failed to fetch messages for ${contact.id}", e)
                    continue
                }

                for (message in messages) {
                    try {
                        if (database.messageDao().exists(message.txId, myAddress)) continue
                        if (isTombstoned(deleted, message.txId, message.blockTime)) continue
                        processContextualMessage(myAddress, contact, message)
                    } catch (e: Exception) {
                        Log.w("ChatRepository", "Failed to process message ${message.txId}", e)
                    }
                }

                val maxBlockTime = messages.maxOfOrNull { it.blockTime }
                if (maxBlockTime != null && maxBlockTime > (cursor ?: 0L)) {
                    database.messageDao().setMessageSyncCursor(
                        MessageSyncCursorEntity(contactId = contact.id, walletAddress = myAddress, aliasHex = aliasHex, lastBlockTime = maxBlockTime)
                    )
                }
            }
        }
    }

    /**
     * [myAddress] is passed down from [syncContextualMessages] rather than re-read here via
     * `walletManager.getAddress()` — if the user switched the active account mid-sync, a fresh
     * read here would stamp this message under the NEW account even though [contact] belongs
     * to the account the sync actually started with.
     */
    private suspend fun processContextualMessage(myAddress: String, contact: ContactEntity, message: ContextualMessageIndexerResponse) {
        val encryptedBytes = decodeContextualMessagePayload(message.messagePayload)
        val encryptedMessage = KasiaCipher.EncryptedMessage.fromBytes(encryptedBytes) ?: return
        // Decryption only needs our own private key + the ephemeral key embedded in the
        // message itself (ECDH) — the sender's static pubkey is never required here.
        val plaintext = MessageProtocol.decrypt(encryptedMessage, walletManager.getPrivateKeyBytes())

        // Reactions are never shown as their own chat bubble - just attached to the message they
        // target - so intercept and route to the reactions table before a MessageEntity is ever
        // created for this tx. The sender of an incoming reaction is always this contact.
        val reaction = MessageReaction.parseOrNull(plaintext)
        if (reaction != null) {
            if (reaction.action == "add") {
                upsertReaction(reaction.targetTxId, contact.id, contact.id, reaction.emoji, message.txId, message.blockTime)
            } else {
                removeReaction(reaction.targetTxId, contact.id)
            }
            return
        }

        // Fresh-address payment pool envelopes (addr_pool / addr_pool_request / payment_notice)
        // are invisible, exactly like reactions - intercepted before a MessageEntity is ever
        // created. A payment_notice produces a payment bubble, but the envelope itself never
        // renders. See MESSAGING.md "Fresh-Address Payment Pools" and PaymentPoolService.
        val poolEnvelope = com.kachat.app.util.PaymentPoolProtocol.parse(plaintext)
        if (poolEnvelope != null) {
            try {
                paymentPoolServiceLazy.get().handleIncomingEnvelope(
                    envelope = poolEnvelope,
                    txId = message.txId,
                    blockTime = message.blockTime,
                    contact = contact,
                    myAddress = myAddress
                )
            } catch (e: Exception) {
                Log.w("ChatRepository", "Failed to handle payment pool envelope ${message.txId}", e)
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
                direction = "received",
                plaintextBody = plaintext,
                encryptedPayload = message.messagePayload,
                amountSompi = null,
                blockTimestamp = message.blockTime,
                // Read when it's backfilled history OR the user is looking at this thread
                // right now - watching a message arrive must not leave an unread badge.
                isRead = backfill || notificationHelper.isViewingContact(contact.id)
            )
        )

        val replyContent = MessageReply.parseOrNull(plaintext)
        // Title above is already the contact's name, so these don't repeat it - matches iOS's
        // ChatService.formatNotificationBody wording exactly.
        val notificationText = when {
            replyContent != null -> "Replied to \"${replyContent.replyToPreview}\""
            VoiceMessage.parseOrNull(plaintext) != null -> "Sent a voice message"
            ImageMessage.parseOrNull(plaintext) != null -> "Sent a photo"
            com.kachat.app.util.ChessMessage.parseOrNull(plaintext) != null -> "♟️ Chess game"
            else -> plaintext
        }
        if (!backfill && !pushState.isActive) { // contextual-message pushes come from the server in remote-push mode
            notificationHelper.show(
                contactId = contact.id,
                title = contact.alias ?: contact.id.takeLast(8),
                text = notificationText,
                notificationOverride = ContactNotificationMode.fromName(contact.notificationOverride)
            )
        }
    }

    /**
     * Detects plain incoming KAS payments (no ciph_msg payload — those are handled by
     * syncHandshakes/syncContextualMessages already) and creates a new conversation for
     * the sender if we've never seen them before, matching the real reference apps'
     * "Received X KAS" payment bubbles.
     *
     * Only payments received after the first time this ever ran for this address count —
     * otherwise a fresh install would immediately dredge up years of old payment history
     * as a wall of "new" chats. See AppSettingsRepository.paymentSyncBaseline.
     */
    private suspend fun syncPayments(myAddress: String, restApi: KaspaRestApi) {
        val baseline = settingsRepository.paymentSyncBaseline(myAddress).first()
        if (baseline == null) {
            settingsRepository.setPaymentSyncBaseline(myAddress, System.currentTimeMillis())
            return
        }

        val transactions = try {
            restApi.getTransactions(myAddress)
        } catch (e: Exception) {
            Log.w("ChatRepository", "Failed to fetch transactions", e)
            return
        }

        for (tx in transactions) {
            try {
                if ((tx.blockTime ?: 0L) < baseline) continue
                if (database.messageDao().exists(tx.transactionId, myAddress)) continue
                processPayment(myAddress, tx)
            } catch (e: Exception) {
                Log.w("ChatRepository", "Failed to process transaction ${tx.transactionId}", e)
            }
        }
    }

    private suspend fun processPayment(myAddress: String, tx: TransactionResponse) {
        val payloadBytes = tx.payload?.hexToBytes() ?: ByteArray(0)
        if (MessageProtocol.isKaChatPayload(payloadBytes)) return // real message/handshake, not a plain payment

        // Checks every input for a resolved address, not just the first — the REST API's
        // resolve_previous_outpoints=light can leave an individual input's address unresolved
        // (e.g. transient lookup gap) even when a later input in the same tx resolved fine.
        // Previously this bailed out entirely on inputs[0] being unresolved, silently dropping
        // an otherwise-valid received payment with no message, no contact, and no notification.
        val sender = tx.inputs.firstNotNullOfOrNull { it.previousOutpointAddress }
        if (sender == null) {
            Log.w("ChatRepository", "Dropping payment ${tx.transactionId}: no input address resolved")
            return
        }
        if (sender == myAddress) return // our own outgoing transaction — already recorded locally at send time

        val receivedSompi = tx.outputs.filter { it.scriptPublicKeyAddress == myAddress }.sumOf { it.amount }
        if (receivedSompi <= 0) return

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
        if (isTombstoned(deleted, tx.transactionId, blockTime)) return

        val existingContact = database.contactDao().getContact(sender, myAddress)
        var conversationId = sender
        var displayText = "Received ${formatKas(receivedSompi)} KAS"
        if (existingContact == null) {
            // A plain payment from an address we have NO contact for must not open a chat
            // with the stranger. Internal moves from our own spending chain surface nowhere
            // in chats; genuinely unknown senders collect in the SELF-chat (the conversation
            // with our own chatting address) with the sender noted in the bubble. The wallet
            // notification below still fires either way.
            if (walletManager.isOwnSpendingAddress(sender)) return
            val selfDeleted = database.contactDao().getDeletedContact(myAddress, myAddress)
            if (isTombstoned(selfDeleted, tx.transactionId, blockTime)) return
            conversationId = myAddress
            displayText += "\nFrom: $sender"
            if (database.contactDao().getContact(myAddress, myAddress) == null) {
                database.contactDao().insert(ContactEntity(id = myAddress, walletAddress = myAddress, alias = null, knsName = null, publicKeyHex = null))
            }
        }

        val backfill = isBackfill(blockTime)
        insertMessage(
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

        if (!backfill && !pushState.isActive) { // payment pushes come from the server in remote-push mode
            notificationHelper.show(
                contactId = conversationId,
                title = "Payment received",
                text = displayText,
                notificationOverride = ContactNotificationMode.fromName(existingContact?.notificationOverride)
            )
        }
    }

    companion object {
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
         */
        fun shouldAutoDisplayPhotos(contact: ContactEntity?): Boolean {
            return when (PhotoAutoDisplayMode.fromName(contact?.photoAutoDisplayOverride)) {
                PhotoAutoDisplayMode.ALWAYS_SHOW -> true
                PhotoAutoDisplayMode.ALWAYS_HIDE -> false
                PhotoAutoDisplayMode.AUTOMATIC -> contact?.conversationStatus == "active"
            }
        }

        /**
         * Unlike handshake payloads (raw binary on-chain), comm payloads are base64 text
         * on-chain ("ciph_msg:1:comm:<alias>:<base64>") — the indexer's message_payload
         * for a contextual message is hex(base64 ascii text), not hex(raw bytes) like a
         * handshake's. Decode both layers to get back to the actual encrypted bytes.
         */
        internal fun decodeContextualMessagePayload(hexPayload: String): ByteArray {
            val base64Text = String(hexPayload.hexToBytes(), Charsets.US_ASCII)
            return Base64.getDecoder().decode(base64Text)
        }

        /** The indexer's alias query param is hex-of-the-ASCII-bytes of the 12-char alias string, not hex-of-the-raw-6-bytes. */
        internal fun hexEncodeAscii(s: String): String =
            s.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

        private const val POLL_INTERVAL_MS = 2_000L
        private const val AUTO_BACKUP_DEBOUNCE_MS = 8_000L
    }
}
