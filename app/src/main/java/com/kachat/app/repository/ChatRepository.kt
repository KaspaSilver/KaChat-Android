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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
    // Consulted at the notification-posting sites below AND by the poll-loop gate in init:
    // while native FCM push is active AND the app is backgrounded, the server is the
    // notification source for 1:1 handshakes/messages/payments (PUSH_EXTENSIONS.md §4) and the
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
    // Lazy for the same cycle reason: PaymentPoolService sends its envelopes through
    // WalletService, which depends on this repository.
    private val paymentPoolServiceLazy: dagger.Lazy<com.kachat.app.services.PaymentPoolService>,
    private val onboardingGate: com.kachat.app.services.OnboardingGate,
    private val peerAliasStore: com.kachat.app.services.PeerAliasStore,
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
     * @param deliberate the user typed this address in themselves (Create Chat). Another of the
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
        val previous = database.contactDao().getContact(contact.id, contact.walletAddress)
        database.contactDao().insert(contact)
        noteConversationActivated(previous, contact)
        return true
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
     * (payment baseline, handshake block_time cursor, and every per-contact-per-alias message
     * cursor — see [MessageSyncCursorEntity]); a list wipes only those conversations' received
     * messages and resets only their per-contact cursors. The handshake cursor is wallet-global
     * (no per-contact variant exists), so a scoped wipe rewinds it too — the re-scan is purely
     * additive for unselected chats because their handshake rows still exist and every insert
     * is txId-deduped. The payment baseline is left alone in scoped mode: it is a fixed
     * first-run floor, not a moving cursor, so the selected chats' deleted payment bubbles
     * re-fetch anyway, and rewinding it to 0 would dredge up pre-install payment history from
     * strangers the user never selected.
     */
    suspend fun wipeIncomingMessages(address: String, contactIds: List<String>?) {
        ensureStillActive(address)
        if (contactIds == null) {
            database.messageDao().deleteReceivedForWallet(address)
            database.messageDao().deleteSyncCursorsForWallet(address)
            settingsRepository.setPaymentSyncBaseline(address, 0L)
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
        networkService.kaspaRestApi.value?.let { syncPayments(address, it) }

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
        database.messageDao().deleteSyncCursorsForWallet(address)
        database.contactDao().deleteAllForWallet(address)
        database.contactDao().deleteTombstonesForWallet(address)
        // The DataStore sync cursors MUST reset with the data: they survived account deletion,
        // so re-importing an account previously held on this device resumed the handshake scan
        // at the old cursor — ZERO historical handshakes came back and no contacts were
        // recreated ("the account doesn't sync anymore"). The live baseline resets too so the
        // re-import runs as a silent read backfill, not a notification storm.
        settingsRepository.setPaymentSyncBaseline(address, 0L)
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
    suspend fun syncMessages(fromPollLoop: Boolean = false) {
        // Every other caller (pull-to-refresh, the sync worker, push handling) is held too - the
        // wizard is exclusive. The post-wizard initial sync releases the gate before running.
        if (onboardingGate.isHeld) return
        val myAddress = try { walletManager.getAddress() } catch (e: Exception) { return }
        // Before the indexer gate on purpose: healing stuck send-placeholders needs no network.
        repairStuckProvisionalMessages(myAddress)
        val api = networkService.indexerApi.value ?: return
        liveBaselineMs = settingsRepository.liveNotificationBaseline(myAddress)

        // Self-chat ("Note to Self"): ensure a contact for your own address (unless you deleted it)
        // so your self→self notes are swept by syncContextualMessages (default status "active") and
        // there's an entry point in the chat list. Mirrors the payment-path self-create + tombstone.
        if (database.contactDao().getContact(myAddress, myAddress) == null &&
            database.contactDao().getDeletedContact(myAddress, myAddress) == null) {
            database.contactDao().insert(ContactEntity(id = myAddress, walletAddress = myAddress, alias = null, knsName = null, publicKeyHex = null))
        }

        syncHandshakes(myAddress, api)
        syncOutgoingHandshakes(myAddress, api)
        syncContextualMessages(myAddress, api, pollShaped = fromPollLoop)
        // Payments get their own, slower cadence on the poll path: the endpoint is
        // full-transactions (inputs + outputs + payloads resolved server-side — by far the
        // heaviest GET in the cycle) and has no cursor, so polling it every fast tick
        // re-downloaded the same recent window over and over. A payment surfacing within ~20s
        // is still well inside "feels live" for a payment bubble.
        val now = System.currentTimeMillis()
        if (!fromPollLoop || now - lastPaymentPollAt >= PAYMENT_POLL_INTERVAL_MS) {
            networkService.kaspaRestApi.value?.let {
                syncPayments(myAddress, it, limit = if (fromPollLoop) PAYMENT_POLL_LIMIT else 50)
                lastPaymentPollAt = now
            }
        }
    }

    /** Last time the poll path actually hit the payments endpoint — see [syncMessages]. */
    @Volatile
    private var lastPaymentPollAt = 0L

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

        val encryptedBytes = handshake.messagePayload.hexToBytes()
        val encryptedMessage = KasiaCipher.EncryptedMessage.fromBytes(encryptedBytes)
        if (encryptedMessage == null) {
            Log.w("ChatRepository", "Handshake ${handshake.txId.take(16)} payload is not a sealed message - skipped")
            return true
        }
        val decryptedJson = MessageProtocol.decrypt(encryptedMessage, walletManager.getPrivateKeyBytes())
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
        if (!backfill && !pushState.isActive) {
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
     * `ciph_msg:1:comm:<alias>:<sealed>`, alias in the clear. So their recent transactions show
     * every alias they are using, and the ones whose sealed part opens with OUR key are the ones
     * meant for us - the same decrypt-first rule iOS applies to a contact's transactions. Any new
     * alias found goes into [PeerAliasStore], and the sync that follows fetches its whole history.
     *
     * Also reads, once per contact per process, every handshake of theirs already stored here:
     * those name aliases the single contact field has since forgotten.
     *
     * Throttled: the conversation on screen at most every 30 seconds, anyone else every 5 minutes.
     */
    private suspend fun discoverPeerAliases(myAddress: String, contact: ContactEntity, onScreen: Boolean) {
        if (contact.id == myAddress) return
        val key = "$myAddress|${contact.id}"
        val privateKey = walletManager.getPrivateKeyBytes()

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
                    val latestByContact = database.messageDao().getLatestMessagePerContact(myAddress)
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

            // Every other alias they have been seen sending to us under - older handshakes, and
            // any found on their own transactions. iOS sends under the first of ALL its legacy
            // aliases, which need not be the one its last handshake named; missing it meant not
            // one of their messages ever arrived here, while ours reached them fine.
            val knownAliasHexes = try {
                discoverPeerAliases(myAddress, contact, onScreen = contact.id == notificationHelper.currentContactId)
                peerAliasStore.aliases(myAddress, contact.id).map { hexEncodeAscii(it) }
            } catch (e: Exception) {
                Log.w("ChatRepository", "Alias discovery failed for ${contact.id.takeLast(8)}", e)
                emptyList()
            }

            for (aliasHex in (listOfNotNull(legacyAliasHex, deterministicAliasHex) + knownAliasHexes).distinct()) {
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
                            if (database.messageDao().exists(message.txId, myAddress)) continue
                            if (isTombstoned(deleted, message.txId, message.blockTime)) continue
                            processContextualMessage(myAddress, contact, message)
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
                upsertReaction(reaction.targetTxId, contact.id, contact.id, reaction.emoji, message.txId, message.blockTime, walletAddress = myAddress)
            } else {
                removeReaction(reaction.targetTxId, contact.id, walletAddress = myAddress)
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
        if (contact.id != myAddress) {
            com.kachat.app.util.CallCodec.parseOrNull(plaintext)?.let { envelope ->
                callServiceLazy.get().handleIncoming(envelope, contact.id, message.blockTime, isOutgoing = false)
            }
        }

        val replyContent = MessageReply.parseOrNull(plaintext)
        // A reply to a message this device never received is the one signal that a message was
        // missed; act on it rather than leave a quote that opens onto nothing.
        if (replyContent != null && replyContent.replyToId.isNotEmpty()) {
            recoverMissingReplyOriginal(contact.id, replyContent.replyToId, message.blockTime, force = false)
        }
        // Title above is already the contact's name, so these don't repeat it - matches iOS's
        // ChatService.formatNotificationBody wording exactly.
        val notificationText = when {
            replyContent != null -> "Replied to \"${replyContent.replyToPreview}\""
            VoiceMessage.parseOrNull(plaintext) != null -> "Sent a voice message"
            ImageMessage.parseOrNull(plaintext) != null -> "Sent a photo"
            com.kachat.app.util.ChessMessage.parseOrNull(plaintext) != null -> "♟️ Chess game"
            com.kachat.app.util.CallCodec.parseOrNull(plaintext) != null -> com.kachat.app.util.CallCodec.notificationPreview(com.kachat.app.util.CallCodec.parseOrNull(plaintext)!!)
            else -> plaintext
        }
        // The remote push is the only banner source while push is active, foreground or
        // background - see PushState. Only a device with no push at all banners from here.
        if (!backfill && !pushState.isActive) {
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
     * Detects plain incoming KAS payments (no ciph_msg payload — those are handled by
     * syncHandshakes/syncContextualMessages already) and creates a new conversation for
     * the sender if we've never seen them before, matching the real reference apps'
     * "Received X KAS" payment bubbles.
     *
     * Only payments received after the first time this ever ran for this address count —
     * otherwise a fresh install would immediately dredge up years of old payment history
     * as a wall of "new" chats. See AppSettingsRepository.paymentSyncBaseline.
     */
    private suspend fun syncPayments(myAddress: String, restApi: KaspaRestApi, limit: Int = 50) {
        val baseline = settingsRepository.paymentSyncBaseline(myAddress).first()
        if (baseline == null) {
            settingsRepository.setPaymentSyncBaseline(myAddress, System.currentTimeMillis())
            return
        }

        val transactions = try {
            restApi.getTransactions(myAddress, limit = limit)
        } catch (e: Exception) {
            noteIndexerError()
            Log.w("ChatRepository", "Failed to fetch transactions", e)
            return
        }

        if (seenPaymentTxIds.size > 5_000) seenPaymentTxIds.clear()
        for (tx in transactions) {
            try {
                // Early id diff against what this process already handled — the endpoint has no
                // cursor, so most of every page is transactions seen on the previous poll.
                val seenKey = "$myAddress|${tx.transactionId}"
                if (seenKey in seenPaymentTxIds) continue
                if ((tx.blockTime ?: 0L) < baseline) {
                    seenPaymentTxIds.add(seenKey)
                    continue
                }
                if (database.messageDao().exists(tx.transactionId, myAddress)) {
                    seenPaymentTxIds.add(seenKey)
                    continue
                }
                // Only a FINAL outcome marks the id seen — a transient input-resolution gap
                // (processPayment returns false) must stay retryable on the next poll, exactly
                // as it was before the seen-set existed.
                if (processPayment(myAddress, tx)) seenPaymentTxIds.add(seenKey)
            } catch (e: Exception) {
                Log.w("ChatRepository", "Failed to process transaction ${tx.transactionId}", e)
            }
        }
    }

    /** Returns true when the outcome is FINAL (recorded, or permanently irrelevant) — false only
     *  for the transient no-input-address-resolved gap, which the next poll must retry. */
    private suspend fun processPayment(myAddress: String, tx: TransactionResponse): Boolean {
        val payloadBytes = tx.payload?.hexToBytes() ?: ByteArray(0)
        if (MessageProtocol.isKaChatPayload(payloadBytes)) return true // real message/handshake, not a plain payment

        // Checks every input for a resolved address, not just the first — the REST API's
        // resolve_previous_outpoints=light can leave an individual input's address unresolved
        // (e.g. transient lookup gap) even when a later input in the same tx resolved fine.
        // Previously this bailed out entirely on inputs[0] being unresolved, silently dropping
        // an otherwise-valid received payment with no message, no contact, and no notification.
        val sender = tx.inputs.firstNotNullOfOrNull { it.previousOutpointAddress }
        if (sender == null) {
            Log.w("ChatRepository", "Dropping payment ${tx.transactionId}: no input address resolved")
            return false // transient — the next poll retries this tx
        }
        if (sender == myAddress) return true // our own outgoing transaction — already recorded locally at send time

        val receivedSompi = tx.outputs.filter { it.scriptPublicKeyAddress == myAddress }.sumOf { it.amount }
        if (receivedSompi <= 0) return true

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
        var displayText = "Received ${formatKas(receivedSompi)} KAS"
        if (existingContact == null) {
            // A plain payment from an address we have NO contact for must not open a chat
            // with the stranger. Internal moves from our own spending chain surface nowhere
            // in chats; genuinely unknown senders collect in the SELF-chat (the conversation
            // with our own chatting address) with the sender noted in the bubble. The wallet
            // notification below still fires either way.
            if (walletManager.isOwnSpendingAddress(sender)) return true
            val selfDeleted = database.contactDao().getDeletedContact(myAddress, myAddress)
            if (isTombstoned(selfDeleted, tx.transactionId, blockTime)) return true
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

        // The remote push is the only banner source while push is active, foreground or
        // background - see PushState. Only a device with no push at all banners from here.
        if (!backfill && !pushState.isActive) {
            notificationHelper.show(
                contactId = conversationId,
                title = "Payment received",
                text = displayText,
                notificationOverride = ContactNotificationMode.fromName(existingContact?.notificationOverride),
                dedupeTxId = tx.transactionId
            )
        }
        return true
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
        /**
         * The sealed bytes of a contextual message read straight OFF CHAIN, where the payload is
         * the whole "ciph_msg:1:comm:<alias>:<base64>" string rather than the indexer's already
         * stripped `message_payload`. Null when it is not a contextual message at all.
         */
        internal fun decodeOnChainContextualPayload(hexPayload: String): ByteArray? {
            val text = try {
                String(hexPayload.hexToBytes(), Charsets.US_ASCII)
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

        /** Poll-path contact-sweep shape — see [syncContextualMessages]. Matches iOS's
         *  startForegroundContactSweep (5s between sweeps, 100-120ms between contacts, cap 40). */
        private const val CONTACT_SWEEP_MIN_INTERVAL_MS = 5_000L
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
