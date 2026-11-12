package com.kachat.app.viewmodels

import kotlinx.coroutines.flow.flowOn
import com.kachat.app.util.UserFacingError
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kachat.app.models.BackupRetention
import com.kachat.app.models.ContactEntity
import com.kachat.app.models.Conversation
import com.kachat.app.models.MessageEntity
import com.kachat.app.models.ReactionEntity
import com.kachat.app.models.displayName
import com.kachat.app.repository.GroupConversation
import com.kachat.app.services.ChatHistoryExportImportService
import com.kachat.app.services.KnsProfileFields
import com.kachat.app.services.KnsService
import com.kachat.app.services.NextcloudFile
import com.kachat.app.services.NextcloudService
import com.kachat.app.services.VoiceRecorderService
import com.kachat.app.util.ImageMessage
import com.kachat.app.util.ImagePrep
import com.kachat.app.util.KaspaUnit
import com.kachat.app.util.MessageProtocol
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.source
import com.kachat.app.util.MessageReaction
import com.kachat.app.util.MessageReply
import com.kachat.app.util.VoiceMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.kachat.app.repository.isExpiredSystemMessage
import kotlinx.coroutines.flow.flow

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val draftStore: com.kachat.app.services.DraftStore,
    private val chatRepository: com.kachat.app.repository.ChatRepository,
    private val networkService: com.kachat.app.services.NetworkService,
    private val walletManager: com.kachat.app.services.WalletManager,
    private val settings: com.kachat.app.repository.AppSettingsRepository,
    private val walletService: com.kachat.app.services.WalletService,
    private val notificationHelper: com.kachat.app.services.NotificationHelper,
    private val knsService: KnsService,
    private val chatHistoryExportImportService: ChatHistoryExportImportService,
    private val diagnosticsExportService: com.kachat.app.services.DiagnosticsExportService,
    private val voiceRecorderService: VoiceRecorderService,
    private val nextcloudService: NextcloudService,
    private val nextcloudSyncService: com.kachat.app.services.NextcloudSyncService,
    private val backupRestoreCoordinator: com.kachat.app.services.BackupRestoreCoordinator,
    private val groupRepository: com.kachat.app.repository.GroupRepository,
    private val shareShortcutsManager: com.kachat.app.services.ShareShortcutsManager,
    private val addressActivityNotifier: com.kachat.app.services.AddressActivityNotifier,
    private val onboardingGate: com.kachat.app.services.OnboardingGate,
    private val nameServices: com.kachat.app.services.NameServicesClient,
    /** Sends a chat payment and owns its retry timer (iOS `ChatService.sendPayment`). */
    private val chatPaymentSender: com.kachat.app.services.ChatPaymentSender,
    /** Reads the payment source's coins fresh and reconciled ([refreshSpendingUtxos]). */
    private val walletEngine: com.kachat.app.services.KaspaWalletEngine,
) : ViewModel() {

    // ---------------------------------------------------------------------
    // Onboarding sync hold
    // ---------------------------------------------------------------------

    /** Which phase the post-wizard initial sync is in, for its progress sheet. Null when idle. */
    enum class InitialSyncPhase(val label: String) {
        Messages("Downloading message history"),
        Groups("Syncing your group chats"),
        Balance("Checking your balance"),
        Finished("Finished"),
    }

    private val _initialSyncPhase = MutableStateFlow<InitialSyncPhase?>(null)
    val initialSyncPhase: StateFlow<InitialSyncPhase?> = _initialSyncPhase.asStateFlow()

    val onboardingSyncHeld: StateFlow<Boolean> get() = onboardingGate.held

    fun holdSyncForOnboarding() = onboardingGate.hold()

    /**
     * Lifts the wizard's hold and runs the account's first full sync, publishing a phase as it
     * goes. Everything was deferred until now, so this is where all of that work happens - and a
     * half-populated chat list invites taps on chats whose history has not arrived.
     */
    fun runInitialSyncAfterOnboarding() {
        if (!onboardingGate.isHeld) return
        onboardingGate.release()
        viewModelScope.launch {
            try {
                _initialSyncPhase.value = InitialSyncPhase.Messages
                chatRepository.syncMessages()
                _initialSyncPhase.value = InitialSyncPhase.Groups
                groupRepository.syncGroups()
                _initialSyncPhase.value = InitialSyncPhase.Balance
                walletService.refreshBalance()
            } catch (e: Exception) {
                Log.w("ChatViewModel", "Initial sync after onboarding failed", e)
            } finally {
                _initialSyncPhase.value = InitialSyncPhase.Finished
            }
        }
    }

    fun clearInitialSyncPhase() { _initialSyncPhase.value = null }

    /** Lifts a hold with no wizard behind it, so the account can never be stranded unsynced. */
    fun releaseOnboardingHoldIfIdle() {
        if (onboardingGate.isHeld) onboardingGate.release()
    }

    /** Own-address balance-change events (see AddressActivityNotifier.utxoActivityEvents) — the
     *  payment composer's Available pill refreshes off these when the current spending address
     *  is involved, e.g. when a rotation change lands after a private-mode send. */
    val ownAddressUtxoActivityEvents = addressActivityNotifier.utxoActivityEvents

    /** Suppresses a notification for whichever contact's thread is currently open. */
    fun enterContact(contactId: String) {
        notificationHelper.enterContact(contactId)
        // Opening a chat is one of the two moments the recency order the share sheet shows can
        // change — keep the direct-share conversation shortcuts current. refresh() diffs against
        // what's already published, so this is free when nothing changed.
        shareShortcutsManager.refresh(conversations.value)
        // What arrived while the app was away, straight away rather than on the poll's next
        // tick (iOS 360e5d2).
        viewModelScope.launch {
            try {
                chatRepository.syncConversationNow(contactId)
            } catch (e: Exception) {
                Log.w("ChatViewModel", "Sync on opening chat failed", e)
            }
        }
    }

    /** Releases [enterContact]'s hold; another screen still showing this chat keeps it open. */
    fun leaveContact(contactId: String) = notificationHelper.leaveContact(contactId)

    /** Suppresses a notification for whichever group's thread is currently open, and (via
     *  `GroupRepository`'s `isViewingGroup` check) keeps it marked read in real time so a message
     *  arriving while you're actively looking at it doesn't tick the unread badge up. */
    fun setActiveGroup(groupId: String?) {
        notificationHelper.setActiveGroup(groupId)
        // What arrived while the app was away, now - through the coalesced catch-up, so a
        // tapped push and this open never run two full syncs at once (iOS 360e5d2).
        if (groupId != null) {
            viewModelScope.launch {
                try {
                    groupRepository.runGroupCatchUp()
                } catch (e: Exception) {
                    Log.w("ChatViewModel", "Group catch-up on opening failed", e)
                }
            }
        }
    }

    /** True only when the chatting (identity) address balance is a *confirmed* 0 KAS — never
     *  while it's still unknown/loading. `WalletService.balanceKnown` gates the balance flow's
     *  0L initial placeholder, so a cold launch can't flash the gate before the first fetch
     *  lands. Drives ChatThreadScreen's 1:1 zero-balance funding gate (dimmed composer +
     *  "fund your chatting address" card); group chats and broadcasts don't read it. */
    val chattingBalanceGateActive: StateFlow<Boolean> =
        combine(walletService.balance, walletService.balanceKnown) { balanceSompi, known ->
            known && balanceSompi == 0L
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Re-fetches the chatting-address balance backing [chattingBalanceGateActive] — fired when
     *  a 1:1 thread opens, and polled while the funding gate is showing so an
     *  external deposit dismisses the gate without leaving the screen (nothing else pushes a
     *  balance update into WalletService while the user just sits on the thread). */
    fun refreshChattingBalance() {
        viewModelScope.launch { walletService.refreshBalance() }
    }

    val kaspaExplorer: StateFlow<com.kachat.app.models.KaspaExplorer> = settings.kaspaExplorer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), com.kachat.app.models.KaspaExplorer.default)

    fun updateKaspaExplorer(explorer: com.kachat.app.models.KaspaExplorer) {
        viewModelScope.launch { settings.setKaspaExplorer(explorer) }
    }

    /** When this contact's incoming photos start to show (ChatRepository.photosUnlockAt); null
     *  until you have both sent a message. */
    fun photosUnlockAt(contactId: String): Flow<Long?> = chatRepository.photosUnlockAt(contactId)

    /** Per-contact override for the "Incoming Notifications" picker in Chat Info — null clears back to Default. */
    fun updateContactNotificationOverride(contactId: String, override: com.kachat.app.models.ContactNotificationMode?) {
        viewModelScope.launch {
            val existing = getOrCreateContact(contactId)
            chatRepository.addContact(existing.copy(notificationOverride = override?.name))
        }
    }

    // -------------------------------------------------------------------------
    // Chat history export/import — matches iOS's plaintext JSON archive, scoped to whichever
    // account is active. Export hands a file off to the share sheet; import always merges,
    // never wipes existing local data.
    // -------------------------------------------------------------------------

    enum class ChatHistoryOpStatus { IDLE, IN_PROGRESS, SUCCESS, FAILED }
    data class ChatHistoryOpState(val status: ChatHistoryOpStatus = ChatHistoryOpStatus.IDLE, val message: String? = null)

    private val _exportState = MutableStateFlow(ChatHistoryOpState())
    val exportState: StateFlow<ChatHistoryOpState> = _exportState.asStateFlow()

    private val _importState = MutableStateFlow(ChatHistoryOpState())
    val importState: StateFlow<ChatHistoryOpState> = _importState.asStateFlow()

    /** Builds the export file, then hands its content:// URI to [onReady] (the caller launches the share sheet — that's a UI concern, not this ViewModel's). */
    fun exportChatHistory(onReady: (Uri) -> Unit) {
        if (_exportState.value.status == ChatHistoryOpStatus.IN_PROGRESS) return
        viewModelScope.launch {
            _exportState.value = ChatHistoryOpState(status = ChatHistoryOpStatus.IN_PROGRESS)
            try {
                // Off the main thread: building the archive serializes + AES-encrypts the entire
                // chat history (megabytes for long histories) and writes the file — viewModelScope
                // alone would run all of that on Main and freeze the UI for the duration.
                val uri = withContext(Dispatchers.IO) { chatHistoryExportImportService.exportChatHistory() }
                _exportState.value = ChatHistoryOpState(status = ChatHistoryOpStatus.SUCCESS)
                onReady(uri)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Chat history export failed", e)
                _exportState.value = ChatHistoryOpState(status = ChatHistoryOpStatus.FAILED, message = UserFacingError.message(e, "Export failed"))
            }
        }
    }

    private val _diagnosticsExportState = MutableStateFlow(ChatHistoryOpState())
    val diagnosticsExportState: StateFlow<ChatHistoryOpState> = _diagnosticsExportState.asStateFlow()

    /** Builds the diagnostics zip, then hands its content:// URI to [onReady] — see DiagnosticsExportService. */
    fun exportDiagnostics(onReady: (Uri) -> Unit) {
        if (_diagnosticsExportState.value.status == ChatHistoryOpStatus.IN_PROGRESS) return
        viewModelScope.launch {
            _diagnosticsExportState.value = ChatHistoryOpState(status = ChatHistoryOpStatus.IN_PROGRESS)
            try {
                // Zip assembly is file I/O — keep it off Main (same reasoning as exportChatHistory).
                val uri = withContext(Dispatchers.IO) { diagnosticsExportService.exportDiagnostics() }
                _diagnosticsExportState.value = ChatHistoryOpState(status = ChatHistoryOpStatus.SUCCESS)
                onReady(uri)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Diagnostics export failed", e)
                _diagnosticsExportState.value = ChatHistoryOpState(status = ChatHistoryOpStatus.FAILED, message = UserFacingError.message(e, "Export failed"))
            }
        }
    }

    fun importChatHistory(uri: Uri) {
        if (_importState.value.status == ChatHistoryOpStatus.IN_PROGRESS) return
        viewModelScope.launch {
            _importState.value = ChatHistoryOpState(status = ChatHistoryOpStatus.IN_PROGRESS)
            try {
                // Decrypt + full-archive Gson parse are CPU-heavy; keep them off Main. The Room
                // inserts inside are suspend and safe either way.
                val result = withContext(Dispatchers.IO) { chatHistoryExportImportService.importChatHistory(uri) }
                _importState.value = ChatHistoryOpState(
                    status = ChatHistoryOpStatus.SUCCESS,
                    message = "Imported ${result.importedMessageCount} messages from ${result.conversationCount} chats."
                )
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Chat history import failed", e)
                _importState.value = ChatHistoryOpState(status = ChatHistoryOpStatus.FAILED, message = UserFacingError.message(e, "Import failed"))
            }
        }
    }

    // -------------------------------------------------------------------------
    // Cloud backup — Nextcloud only. Message retention is a device-level setting (see the
    // Storage hub), and the restore coordinator owns cloud restores end to end.
    // -------------------------------------------------------------------------

    val backupRetention: StateFlow<BackupRetention> = settings.backupRetention
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), BackupRetention.FOREVER)

    /**
     * The singleton that owns cloud restores (Nextcloud) end to end — exposed so the storage
     * screens can observe its phase/progress and render the blocking full-screen modal. The
     * restore job lives on the coordinator's own scope, NOT viewModelScope: this viewmodel is
     * nav-entry scoped on the storage pages, and popping the screen must never cancel a
     * half-written import.
     */
    val restoreCoordinator: com.kachat.app.services.BackupRestoreCoordinator get() = backupRestoreCoordinator

    fun setBackupRetention(retention: BackupRetention) {
        viewModelScope.launch { settings.setBackupRetention(retention) }
    }

    // -------------------------------------------------------------------------
    // Nextcloud — the same JSON archive + merge logic as local export/import, with the user's
    // own Nextcloud server (WebDAV) as the transport. The service itself is exposed so the
    // settings section and picker composables can read account state / list folders / mint
    // share links directly; the archive-touching operations stay here.
    // -------------------------------------------------------------------------

    val nextcloud: NextcloudService get() = nextcloudService

    /** When the active wallet's archive last synced to Nextcloud automatically, null = never. */
    val nextcloudLastAutoSyncMs: StateFlow<Long?> = nextcloudSyncService.lastAutoSyncMs

    /** The Automatic Sync toggle (the pre-existing per-account auto-backup switch, upgraded).
     *  Routed through the sync service so turning it on marks the archive dirty and prompts the
     *  first sync, and turning it off drops any pending debounced upload. */
    fun setNextcloudAutoSyncEnabled(enabled: Boolean) {
        nextcloudSyncService.setAutoSyncEnabled(enabled)
    }

    private val _nextcloudConnectState = MutableStateFlow(ChatHistoryOpState())
    val nextcloudConnectState: StateFlow<ChatHistoryOpState> = _nextcloudConnectState.asStateFlow()

    fun connectNextcloud(server: String, username: String, appPassword: String) {
        if (_nextcloudConnectState.value.status == ChatHistoryOpStatus.IN_PROGRESS) return
        viewModelScope.launch {
            _nextcloudConnectState.value = ChatHistoryOpState(status = ChatHistoryOpStatus.IN_PROGRESS)
            try {
                nextcloudService.connect(server, username, appPassword)
                _nextcloudConnectState.value = ChatHistoryOpState(status = ChatHistoryOpStatus.SUCCESS)
                refreshNextcloudBackupInfo()
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Nextcloud connect failed", e)
                _nextcloudConnectState.value = ChatHistoryOpState(status = ChatHistoryOpStatus.FAILED, message = UserFacingError.message(e, "Could not connect"))
            }
        }
    }

    fun disconnectNextcloud() {
        // Its sync state goes with it: a different server is a new start (iOS `disconnect`).
        walletManager.activeAddressFlow.value?.let { nextcloudSyncService.purgeStoredState(it) }
        nextcloudService.disconnect()
        _nextcloudConnectState.value = ChatHistoryOpState()
        _nextcloudBackupState.value = ChatHistoryOpState()
        _nextcloudBackupInfo.value = null
    }

    private val _nextcloudBackupState = MutableStateFlow(ChatHistoryOpState())
    val nextcloudBackupState: StateFlow<ChatHistoryOpState> = _nextcloudBackupState.asStateFlow()

    /** Mirrors [backupNow], with the user's own Nextcloud server as the destination. */
    fun nextcloudBackupNow() {
        if (_nextcloudBackupState.value.status == ChatHistoryOpStatus.IN_PROGRESS) return
        viewModelScope.launch {
            _nextcloudBackupState.value = ChatHistoryOpState(status = ChatHistoryOpStatus.IN_PROGRESS)
            try {
                // Merge-on-upload: reads the server's copy first, so a manual backup can never undo
                // another device's newer change, and it brings those changes in here.
                nextcloudSyncService.backUpContactsNow()
                _nextcloudBackupState.value = ChatHistoryOpState(status = ChatHistoryOpStatus.SUCCESS, message = "Backed up just now")
                refreshNextcloudBackupInfo()
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Nextcloud backup failed", e)
                _nextcloudBackupState.value = ChatHistoryOpState(status = ChatHistoryOpStatus.FAILED, message = UserFacingError.message(e, "Backup failed"))
            }
        }
    }

    private val _nextcloudBackupInfo = MutableStateFlow<NextcloudFile?>(null)
    val nextcloudBackupInfo: StateFlow<NextcloudFile?> = _nextcloudBackupInfo.asStateFlow()

    /** The backup file's server-side date + size for the settings "last backup" line — null means none found (or not connected). */
    fun refreshNextcloudBackupInfo() {
        viewModelScope.launch {
            _nextcloudBackupInfo.value = if (nextcloudService.isConnected) nextcloudService.fetchBackupInfo() else null
        }
    }

    // -------------------------------------------------------------------------
    // Storage — mirrors iOS's "Local storage used" readout in Settings.
    // -------------------------------------------------------------------------

    private val _localStorageSizeBytes = MutableStateFlow<Long?>(null)
    val localStorageSizeBytes: StateFlow<Long?> = _localStorageSizeBytes.asStateFlow()

    /** Room's `kachat.db` file (+ `-wal`/`-shm` sidecars in WAL mode) on local disk — free, no network. */
    fun refreshLocalStorageSize() {
        val dbFile = appContext.getDatabasePath("kachat.db")
        var total = dbFile.length()
        for (suffix in listOf("-wal", "-shm")) {
            val sidecar = java.io.File(dbFile.parentFile, dbFile.name + suffix)
            if (sidecar.exists()) total += sidecar.length()
        }
        _localStorageSizeBytes.value = total
    }

    // -------------------------------------------------------------------------
    // Danger Zone — matches iOS's three destructive settings actions exactly in scope
    // (SettingsView.swift:216-295): all three act on the currently active account only, never
    // other saved accounts on the device.
    // -------------------------------------------------------------------------

    /** Sync keeps failing right now: only a note on the wipe-and-resync sheet (iOS 76aec44f). */
    val syncTrouble: StateFlow<Boolean> = chatRepository.syncTrouble

    /** What each chat's row shows about its own fetch; a chat with no entry shows nothing. */
    val chatFetchStates: StateFlow<Map<String, com.kachat.app.repository.ChatRepository.ChatFetchState>> =
        chatRepository.chatFetchStates

    enum class DangerZoneOpStatus { IDLE, IN_PROGRESS, SUCCESS, FAILED }
    data class DangerZoneOpState(val status: DangerZoneOpStatus = DangerZoneOpStatus.IDLE, val message: String? = null)

    /**
     * "Wipe and Re-sync Incoming Messages" — deletes received messages (all chats when
     * [contactIds] is null, otherwise just the selected 1:1 conversations), then re-fetches
     * their history from the blockchain. The whole flow is handed to [restoreCoordinator],
     * which owns the job in its own scope and drives the same blocking progress modal as a
     * backup restore — sent messages, contacts, and the wallet's keys are untouched.
     */
    fun wipeAndResyncIncomingMessages(contactIds: List<String>?) {
        backupRestoreCoordinator.startIncomingResync(contactIds)
    }

    // Auto-created payment chats are always shown — no toggle to hide them.
    val conversations: StateFlow<List<Conversation>> = combine(
        chatRepository.getContacts(),
        chatRepository.getLatestMessages(),
        chatRepository.getUnreadCounts()
    ) { contacts, latestMessages, unreadCounts ->
        val latestByContact = latestMessages.associateBy { it.contactId }
        val unreadByContact = unreadCounts.associateBy({ it.contactId }, { it.count })
        contacts
            .map { contact ->
                Conversation(contact, latestByContact[contact.id], unreadByContact[contact.id] ?: 0)
            }
            // The auto-seeded self-chat contact (ChatRepository.syncMessages creates one for your
            // own address on every sync so self-notes have a sweep target) only earns a chat-list
            // row once it actually holds a message. Without this, every brand-new account showed
            // one mystery conversation for its own (unrecognizable) address within seconds of
            // creation. iOS behaves this way already: it seeds the self CONTACT but only lists
            // conversations that have messages.
            .sortedByDescending { it.lastMessage?.blockTimestamp ?: 0L }
            // Your chat with yourself always sits first, empty or not: notes to self, and where
            // unknown senders land - never something to scroll for (iOS 195f228, ef4f183).
            .sortedByDescending { it.contact.id.equals(it.contact.walletAddress, ignoreCase = true) }
    // Built off the main thread, and kept alive for a few seconds after the Chats tab goes away,
    // so switching tabs and back does not tear the list down and rebuild it from the database.
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // MARK: - Message Requests (NO_HANDSHAKE_MESSAGING.md, iOS f7ca401)

    /** The active wallet's Message Requests state - accepted, private, blocked. */
    val chatRequestState: StateFlow<com.kachat.app.repository.ChatRequestState> = chatRepository.chatRequestStateFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.kachat.app.repository.ChatRequestState())

    /** Contacts whose chat is a Message Request: someone else started it and you haven't
     *  accepted (ChatRequestStore.isMessageRequest). */
    val messageRequestIds: StateFlow<Set<String>> = combine(
        chatRepository.getContacts(),
        chatRepository.getContactMessageStats(),
        chatRepository.chatRequestStateFlow(),
    ) { contacts, stats, state ->
        val statsByContact = stats.associateBy { it.contactId }
        contacts.filter { contact ->
            val s = statsByContact[contact.id]
            com.kachat.app.repository.ChatRequestStore.isMessageRequest(
                contactId = contact.id,
                contactAddedAt = contact.addedAt,
                myAddress = contact.walletAddress,
                state = state,
                firstMessageAt = s?.firstAt,
                anySent = (s?.anySent ?: 0) > 0,
            )
        }.map { it.id }.toSet()
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** The chat list: not blocked, and not a Message Request (those sit behind the list's
     *  Message Requests row). */
    val chatListConversations: StateFlow<List<Conversation>> = combine(
        conversations, messageRequestIds, chatRequestState,
    ) { all, requests, state ->
        all.filter { it.contact.id !in requests && it.contact.id.lowercase() !in state.blocked }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Unread messages across the chats the Chats list shows - not Message Requests or blocked
     *  chats: an unaccepted sender must not be able to keep ringing the badge (iOS 64d63846
     *  `chatListUnreadCount`). The Chats dock tab's count. */
    val chatListUnreadCount: StateFlow<Int> = chatListConversations.map { list -> list.sumOf { it.unreadCount.coerceAtLeast(0) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Every bot that can be messaged on this network, always, as a circle in the row above the
     * chat list beside the group chats - with its chat when there is one (iOS d54ea87
     * `botCircles`). There is no Kaspa Hub page for them any more; bot chats are never rows in
     * the list itself.
     */
    val botCircles: StateFlow<List<BotCircle>> = conversations.map { all ->
        com.kachat.app.models.KaChatBot.available.map { bot ->
            val key = bot.address.lowercase()
            BotCircle(bot, all.firstOrNull { it.contact.id.lowercase() == key })
        }
    }.flowOn(Dispatchers.Default).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        com.kachat.app.models.KaChatBot.available.map { BotCircle(it, null) },
    )

    /** One bot in the Chats circles row, with its chat when there is one (iOS `BotCircle`). */
    data class BotCircle(val bot: com.kachat.app.models.KaChatBot, val conversation: Conversation?)

    /** Opens (or starts) the chat with [bot] - accepted, named [name] - then [onReady] with its
     *  address, for the caller to navigate to (iOS `KaChatBot.startChat`). */
    fun openBotChat(bot: com.kachat.app.models.KaChatBot, name: String, onReady: (String) -> Unit) {
        if (bot.unavailableReasonRes != null) return
        viewModelScope.launch {
            if (chatRepository.openBotChat(bot.address, name)) onReady(bot.address)
        }
    }

    // MARK: - Payment requests (iOS c6851d6, 6594936)

    /**
     * Sends a payment request into the chat with [contactId] (a 1:1 chat's + > Request Payment):
     * signed by the chatting address, as a plain message holding its link - it shows there as a
     * Payment Request card they tap to pay. [onResult] gets whether it went out and, if not, why.
     */
    fun sendPaymentRequest(contactId: String, request: com.kachat.app.models.PaymentRequest, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val signed = withContext(Dispatchers.Default) {
                com.kachat.app.services.PaymentRequestWallet.shared?.sign(request) ?: request
            }
            var error: String? = null
            val ok = sendMessageAwait(contactId, signed.shareUrlString, onError = { error = it }, plain = true)
            onResult(ok, error)
        }
    }

    /**
     * A request to someone's chatting address is paid as a chat payment, so it needs their
     * contact: the existing one, else one added automatically (iOS `addContact(isAutoAdded:
     * true)`). False when there can't be one (one of your own accounts) - the request is then
     * paid as a plain send.
     */
    suspend fun ensurePaymentRequestContact(address: String): Boolean {
        if (chatRepository.getContact(address) != null) return true
        val myAddress = runCatching { walletManager.getAddress() }.getOrNull() ?: return false
        return runCatching {
            chatRepository.addContact(ContactEntity(id = address, walletAddress = myAddress, alias = null, knsName = null, publicKeyHex = null))
        }.getOrDefault(false)
    }

    /** Message Requests, newest first. */
    val messageRequests: StateFlow<List<Conversation>> = combine(conversations, messageRequestIds) { all, requests ->
        all.filter { it.contact.id in requests }.sortedByDescending { it.lastMessage?.blockTimestamp ?: 0L }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Accept: the chat joins the chat list. Nothing is sent on chain. */
    fun acceptChat(contactId: String) {
        viewModelScope.launch { chatRepository.acceptChat(contactId) }
    }

    /** Reject: deleted from this device, and the address blocked until you write to them. */
    fun rejectChat(contactId: String) {
        viewModelScope.launch { chatRepository.rejectChat(contactId) }
    }

    /** Starting a chat - Private or not - accepts it; Private also never tags it. */
    fun startChat(contactId: String, isPrivate: Boolean) {
        viewModelScope.launch {
            if (isPrivate) chatRepository.setPrivateChat(contactId, true) else chatRepository.acceptChat(contactId)
        }
    }

    /** Whether the indexer files first-contact messages - if not, the handshake banner and
     *  "Send Handshake" stay as the way to reach a stranger. */
    private val _inboxSupported = MutableStateFlow(false)
    val inboxSupported: StateFlow<Boolean> = _inboxSupported.asStateFlow()

    fun refreshInboxSupport() {
        viewModelScope.launch { _inboxSupported.value = chatRepository.inboxSupported() }
    }

    /**
     * address -> cached KNS avatar URL, for group chat's per-sender avatars - group members are
     * always saved contacts (created automatically when added to a group), so this reuses the
     * same [com.kachat.app.models.ContactEntity.knsAvatarUrl] caching 1:1 chat avatars already
     * rely on, rather than broadcast's separate anonymous-sender KNS lookup path.
     */
    val contactAvatarsByAddress: StateFlow<Map<String, String?>> = chatRepository.getContacts()
        .map { contacts -> contacts.associateBy({ it.id }, { it.knsAvatarUrl }) }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** address -> live contact alias (KNS-resolved name or custom nickname), for group chat's sender labels - see [contactAvatarsByAddress]. */
    /** Contacts you have accepted (an active conversation) - whose links in a group may load
     *  their preview without a tap, the same rule as a 1:1 chat (iOS 680cff3). */
    val acceptedContactAddresses: StateFlow<Set<String>> = chatRepository.getContacts()
        .map { contacts -> contacts.filter { it.conversationStatus == "active" }.map { it.id }.toSet() }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val contactAliasesByAddress: StateFlow<Map<String, String>> = chatRepository.getContacts()
        .map { contacts -> contacts.mapNotNull { c -> c.alias?.takeIf { it.isNotBlank() }?.let { c.id to it } }.toMap() }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** contactId -> that conversation's newest reaction, for the chat list's "Reacted to your/
     *  their message" preview when it's more recent than the last real message - a separate flow
     *  (not folded into [Conversation]) since reactions never become messages, mirroring how
     *  [contactAvatarsByAddress]/[contactAliasesByAddress] are already kept alongside
     *  [conversations] rather than inside it. */
    val latestReactionByContact: StateFlow<Map<String, com.kachat.app.services.database.LatestReactionRow>> =
        chatRepository.getLatestReactions()
            .map { rows -> rows.associateBy { it.contactId } }
            .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Group sibling of [latestReactionByContact]: groupId -> that group's newest reaction, for
     *  the Group Chats tab's "Alice reacted to a message" card preview when it's more recent than
     *  the last real message. */
    val latestReactionByGroup: StateFlow<Map<String, com.kachat.app.services.database.LatestGroupReactionRow>> =
        groupRepository.getLatestGroupReactions()
            .map { rows -> rows.associateBy { it.groupId } }
            .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /**
     * Fetches KNS name + avatar for every member of a group - group rosters cache a `displayName`
     * snapshot taken at add/join time (`GroupMember.displayName`), which never reflects a KNS name
     * resolved afterward, so the thread/info screens call this on appear the same way
     * [refreshKnsNamesForAllContacts] runs for the chat list. `refreshKnsProfile` works for any
     * address (creates no contact row itself), so this is safe even for members added by address/QR
     * with no KNS domain.
     */
    fun refreshKnsProfilesForGroupMembers(addresses: List<String>) {
        refreshKnsNamesForAllContacts()
        for (address in addresses) {
            refreshKnsProfile(address)
            viewModelScope.launch {
                val explicitPrimary = knsService.identityPrimaryDomain(address)
                _groupMemberPrimaryKnsByAddress.update { it + (address to explicitPrimary) }
            }
        }
    }

    fun markAsRead(contactId: String) {
        viewModelScope.launch { chatRepository.markAsRead(contactId) }
    }

    fun markAsUnread(contactId: String) {
        viewModelScope.launch { chatRepository.markAsUnread(contactId) }
    }

    /** Chat-list multi-select bulk actions. */
    fun markContactsAsRead(contactIds: Collection<String>) {
        viewModelScope.launch { contactIds.forEach { chatRepository.markAsRead(it) } }
    }

    fun markContactsAsUnread(contactIds: Collection<String>) {
        viewModelScope.launch { contactIds.forEach { chatRepository.markAsUnread(it) } }
    }

    /** Permanently deletes the chat (contact + all local messages) — see ChatRepository.deleteChat. */
    fun deleteChat(contactId: String) {
        viewModelScope.launch { chatRepository.deleteChat(contactId) }
    }

    /** Multi-select bulk delete — mirrors markContactsAsRead/markContactsAsUnread's Collection shape. */
    fun deleteChats(contactIds: Collection<String>) {
        // deleteChat itself skips your own chat, even when it was part of a Select All.
        viewModelScope.launch { contactIds.forEach { chatRepository.deleteChat(it) } }
    }

    /** Deletes individual messages from this device only - local-only, never on-chain (the
     *  recipient still has their own copy, and the underlying transaction is still permanently on
     *  the blockchain). Used by ChatThreadScreen's message multi-select "Delete". */
    fun deleteMessages(messageIds: Collection<String>) {
        viewModelScope.launch { messageIds.forEach { chatRepository.deleteMessage(it) } }
    }

    /**
     * Contacts whose Accept is currently building/submitting, and the last failure per contact.
     *
     * Accepting is an ON-CHAIN transaction and it can fail - most often on a brand new account
     * where the 0.2 KAS the handshake itself delivered has not been seen as a spendable UTXO yet.
     * This used to catch every exception into a log line and change nothing on screen, so a
     * failure was indistinguishable from a dead button: the reported "I click accept and nothing
     * happens". Decline is local-only, which is why it always appeared to work.
     */
    private val _handshakeAcceptInFlight = MutableStateFlow<Set<String>>(emptySet())
    val handshakeAcceptInFlight: StateFlow<Set<String>> = _handshakeAcceptInFlight.asStateFlow()

    private val _handshakeAcceptError = MutableStateFlow<Map<String, String>>(emptyMap())
    val handshakeAcceptError: StateFlow<Map<String, String>> = _handshakeAcceptError.asStateFlow()

    fun clearHandshakeAcceptError(contactId: String) {
        _handshakeAcceptError.value = _handshakeAcceptError.value - contactId
    }

    /**
     * iOS ChatDetailView's "Failed to Send" alert, per chat: why a send, an edit, a retry, a
     * handshake or a photo/voice/video send failed. Kept here, not in the screen, because the
     * send finishes in the background and the reason must still be waiting when it does.
     */
    private val _sendFailureAlert = MutableStateFlow<Map<String, String>>(emptyMap())
    val sendFailureAlert: StateFlow<Map<String, String>> = _sendFailureAlert.asStateFlow()

    fun reportSendFailure(contactId: String, message: String) {
        _sendFailureAlert.value = _sendFailureAlert.value + (contactId to message)
    }

    fun clearSendFailure(contactId: String) {
        _sendFailureAlert.value = _sendFailureAlert.value - contactId
    }

    /** The reporter a 1:1 send path hands its failure to - the chat's "Failed to Send" alert. */
    private fun sendFailureAlert(contactId: String): (String) -> Unit = { reportSendFailure(contactId, it) }

    /** Sends a real reciprocal handshake and activates the conversation. */
    fun acceptHandshake(contactId: String) {
        if (contactId in _handshakeAcceptInFlight.value) return
        viewModelScope.launch {
            _handshakeAcceptInFlight.value = _handshakeAcceptInFlight.value + contactId
            _handshakeAcceptError.value = _handshakeAcceptError.value - contactId
            try {
                // The KAS that arrived WITH the handshake is what pays for the reply, and on a
                // fresh account it landed seconds ago - refresh before building so the send is
                // not rejected over a UTXO set the app simply had not caught up with.
                runCatching { walletService.refreshBalance() }
                walletService.acceptHandshake(contactId)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error accepting handshake", e)
                _handshakeAcceptError.value = _handshakeAcceptError.value +
                    (contactId to (e.message?.takeIf { it.isNotBlank() } ?: "Could not accept. Try again."))
            } finally {
                _handshakeAcceptInFlight.value = _handshakeAcceptInFlight.value - contactId
            }
        }
    }

    private val _handshakeSendInFlight = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Contact addresses with an outbound handshake currently being built/submitted. Lets every
     * entry point into [sendHandshake] (the composer menu's "Send Handshake" row and the new-chat
     * banner's button) show progress and refuse a double-tap without either of them owning the
     * send itself — the transaction still goes out through the one path below.
     */
    val handshakeSendInFlight: StateFlow<Set<String>> = _handshakeSendInFlight.asStateFlow()

    /** Manually starts a conversation by sending an initial handshake — the hand icon in a fresh chat. */
    fun sendHandshake(contactId: String) {
        if (contactId in _handshakeSendInFlight.value) return
        viewModelScope.launch {
            _handshakeSendInFlight.value = _handshakeSendInFlight.value + contactId
            try {
                walletService.sendHandshakeToNewContact(contactId)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error sending handshake", e)
                reportSendFailure(contactId, UserFacingError.message(e, "Failed to send"))
            } finally {
                _handshakeSendInFlight.value = _handshakeSendInFlight.value - contactId
            }
        }
    }

    /** Purely local — no transaction sent. Declining fully deletes the contact (see [deleteChat]) — the same as any other delete, they'd need a fresh handshake to reach you again. */
    fun declineHandshake(contactId: String) {
        viewModelScope.launch { chatRepository.deleteChat(contactId) }
    }

    /**
     * A broadcast sender viewed via "User Info" isn't necessarily a saved 1:1 contact yet — unlike
     * [addContact] (the real "add/import" flow, with its primary-domain auto-fill side effect),
     * this is just a bare local row so per-contact fields (name/photo/notification overrides,
     * chosen KNS domain) have somewhere to persist to the first time one of them is edited.
     */
    /**
     * Makes sure a contact row exists for [contactId] before opening their thread - a broadcast
     * sender or a group member viewed through User Info may never have been one. A deliberate
     * add, as iOS ChatInfoView's openChatWithContact (`addContact` without isAutoAdded): it also
     * undoes an earlier deletion of that chat. Nothing changes when the contact already exists.
     */
    fun ensureContactExists(contactId: String) {
        viewModelScope.launch {
            if (chatRepository.getContact(contactId) != null) return@launch
            // A deliberate add is of the network the app runs on (see addContact).
            if (!com.kachat.app.util.KaspaAddress.isValidOnActiveNetwork(contactId.trim())) return@launch
            chatRepository.addContact(getOrCreateContact(contactId), deliberate = true)
        }
    }

    /** A profile link's person: their contact, auto-added when there is none yet, so the name and
     *  settings saved on User Info - or the chat a `chat=1` link opens - stick (iOS 81df734). */
    fun openProfile(address: String, onReady: (String) -> Unit) {
        viewModelScope.launch {
            if (chatRepository.getContact(address) == null) {
                chatRepository.addContact(
                    ContactEntity(id = address, walletAddress = walletManager.getAddress(), alias = null, knsName = null, publicKeyHex = null)
                )
            }
            onReady(address)
        }
    }

    private suspend fun getOrCreateContact(contactId: String): ContactEntity {
        return chatRepository.getContact(contactId) ?: ContactEntity(
            id = contactId,
            walletAddress = walletManager.getAddress(),
            alias = null,
            knsName = null,
            publicKeyHex = null
        )
    }

    fun updateContactName(contactId: String, newName: String) {
        viewModelScope.launch {
            val existing = getOrCreateContact(contactId)
            val updated = existing.copy(alias = if (newName.isBlank()) null else newName)
            chatRepository.addContact(updated)
        }
    }

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /**
     * Pull-to-refresh on the Chats list: the 1:1 fetch, as iOS's `.refreshable` awaits
     * `fetchNewMessages()`. Group chats refresh on their own tab ([refreshGroups], iOS a53d343).
     * The balance refreshes alongside without holding the wheel.
     *
     * The wheel stops after [REFRESH_SPINNER_CAP_MS] whatever happens: the fetch keeps going and
     * its rows still land, but a slow node or indexer cannot pin the control on screen.
     */
    fun refreshChats() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            val fetch = launch {
                try {
                    chatRepository.syncMessages()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("ChatViewModel", "Error refreshing chats", e)
                }
            }
            launch { runCatching { walletService.refreshBalance() } }
            kotlinx.coroutines.withTimeoutOrNull(REFRESH_SPINNER_CAP_MS) { fetch.join() }
            _isRefreshing.value = false
        }
    }

    private val _isRefreshingGroups = MutableStateFlow(false)
    val isRefreshingGroups: StateFlow<Boolean> = _isRefreshingGroups.asStateFlow()

    /**
     * Pull-to-refresh on Group Chats: the group catch-up, as iOS's `.refreshable` awaits
     * `performCatchUpSync()` (a53d343). It goes through runGroupCatchUp, so repeated pulls queue
     * rather than pile up full syncs side by side, and group invites (gctl_root) surface on a pull
     * instead of only via the 15-min SyncWorker or the live delivery. The wheel stops after
     * [REFRESH_SPINNER_CAP_MS]: the group sync is every member of every group, up to 40 pages each.
     */
    fun refreshGroups() {
        if (_isRefreshingGroups.value) return
        viewModelScope.launch {
            _isRefreshingGroups.value = true
            val groups = launch {
                try {
                    groupRepository.runGroupCatchUp()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("ChatViewModel", "Group catch-up after a pull failed", e)
                }
            }
            launch { runCatching { walletService.refreshBalance() } }
            kotlinx.coroutines.withTimeoutOrNull(REFRESH_SPINNER_CAP_MS) { groups.join() }
            _isRefreshingGroups.value = false
        }
    }

    // Messages/photos/voice notes are identity-address self-stashes; "Pay in Kaspa" sources
    // from the spending address instead (see WalletManager's spending-address doc comment) —
    // kept as two separate UTXO sets so the live fee preview below prices each correctly rather
    // than one silently reusing the other's (possibly empty, possibly wrong-balance) UTXOs.
    private val _currentUtxos = MutableStateFlow<List<com.kachat.app.services.UtxoEntry>>(emptyList())
    val currentUtxos: StateFlow<List<com.kachat.app.services.UtxoEntry>> = _currentUtxos.asStateFlow()

    private val _spendingUtxos = MutableStateFlow<List<com.kachat.app.services.UtxoEntry>>(emptyList())
    // Exposed publicly (unlike the backing field) so the payment composer's "Max" button can mass/
    // fee-estimate against the spending-chain UTXOs actually spent from, not the identity address's.
    val spendingUtxos: StateFlow<List<com.kachat.app.services.UtxoEntry>> = _spendingUtxos.asStateFlow()

    private val _paymentAmount = MutableStateFlow("")
    val paymentAmount: StateFlow<String> = _paymentAmount.asStateFlow()

    /** The Send KAS sheet's memo (iOS 8d208b2): rides encrypted in the payment's `kchat:1:pay:`
     *  payload, so the fee preview below prices it in. Empty outside the sheet. */
    private val _paymentNote = MutableStateFlow("")

    /** The Send KAS sheet's coin control: the payment spends exactly these coins of its source
     *  address; null = automatic (iOS 62c2773). The fee preview prices this set. */
    private val _paymentManualUtxos = MutableStateFlow<List<com.kachat.app.services.UtxoEntry>?>(null)

    fun setPaymentManualUtxos(utxos: List<com.kachat.app.services.UtxoEntry>?) {
        _paymentManualUtxos.value = utxos?.takeIf { it.isNotEmpty() }
    }

    private val _messageText = MutableStateFlow("")
    val messageText: StateFlow<String> = _messageText.asStateFlow()

    // Sompi per mass-gram: the network minimum, what every send pays (iOS KaspaFeePolicy).
    private val _networkFeeRate = MutableStateFlow(com.kachat.app.util.KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM.toDouble())
    val networkFeeRate: StateFlow<Double> = _networkFeeRate.asStateFlow()

    // User-adjustable override for a busy fee market — set via the composer's clickable fee pill.
    // Applies to whatever's sent next (message/photo/voice/payment) and clears itself afterward so
    // a stale manual bump doesn't silently carry over to an unrelated later send.
    private val _feeRateOverride = MutableStateFlow<Long?>(null)
    val feeRateOverride: StateFlow<Long?> = _feeRateOverride.asStateFlow()

    fun setFeeRateOverride(rate: Long?) {
        _feeRateOverride.value = rate
    }

    /** Recording, then "Encoding…", then "Audio ready" with a preview until Send or the trash
     *  (iOS ca013fc8, [VoiceNoteComposer]). */
    enum class VoiceRecordingStatus { IDLE, RECORDING, ENCODING, READY }
    data class VoiceRecordingState(
        val status: VoiceRecordingStatus = VoiceRecordingStatus.IDLE,
        val elapsedMs: Long = 0L,
        /** The note's length limit: 2:50 / 1:50 on chain, 5:00 via Nextcloud - the ring fills to it. */
        val maxMs: Long = 0L,
        val viaNextcloud: Boolean = false,
        /** A ready note's preview: playing, where it is, and how long the note is. */
        val previewPlaying: Boolean = false,
        val previewPositionMs: Long = 0L,
        val previewDurationMs: Long = 0L,
        /** A ready on-chain note's part sizes (WebM bytes), for its exact fee. */
        val readyPartSizes: List<Int>? = null,
    ) {
        /** Anything is staged in the voice row: recording, encoding or ready. */
        val isActive: Boolean get() = status != VoiceRecordingStatus.IDLE
    }

    private val _voiceRecordingState = MutableStateFlow(VoiceRecordingState())
    val voiceRecordingState: StateFlow<VoiceRecordingState> = _voiceRecordingState.asStateFlow()

    private val _pendingPhotoUri = MutableStateFlow<Uri?>(null)
    /** A picked-but-not-yet-sent chat photo, staged for preview (thumbnail + fee) before the user confirms send. */
    val pendingPhotoUri: StateFlow<Uri?> = _pendingPhotoUri.asStateFlow()

    /**
     * The photo or voice note being composed was sent "via Nextcloud" from the "+" sheet's
     * second step (ComposerMediaRouteSheet). Everything else - the on-chain choice, a camera shot
     * or library pick with no server connected, a shared photo - goes on chain. This replaced the
     * "Send Media via Nextcloud" setting (iOS 8b13460): the route is chosen per send now. Cleared
     * when the attachment is sent or discarded. StateFlows, so the live fee preview prices the
     * ~100-byte share link while one is staged.
     */
    private val _nextcloudPhotoRequested = MutableStateFlow(false)
    private val _nextcloudVoiceRequested = MutableStateFlow(false)
    private val _groupNextcloudPhotoRequested = MutableStateFlow(false)
    private val _groupNextcloudVoiceRequested = MutableStateFlow(false)

    /** Via Nextcloud only while the server is still connected - a disconnect in between sends
     *  on chain, as iOS's `photoViaNextcloud` / `voiceViaNextcloud` do. */
    private val photoViaNextcloud: Boolean get() = _nextcloudPhotoRequested.value && nextcloudService.isConnected
    private val voiceViaNextcloud: Boolean get() = _nextcloudVoiceRequested.value && nextcloudService.isConnected
    val groupPhotoViaNextcloud: Boolean get() = _groupNextcloudPhotoRequested.value && nextcloudService.isConnected
    val groupVoiceViaNextcloud: Boolean get() = _groupNextcloudVoiceRequested.value && nextcloudService.isConnected

    /**
     * A photo or video picked "via Nextcloud", waiting in the composer (iOS 7abc06e
     * `NextcloudStagedMedia`). Its upload starts the moment it is staged, not when send is tapped,
     * and the send button spins until the share link is back - then a tap sends the link. Shared
     * by 1:1 and group chats.
     */
    data class NextcloudStagedMedia(val id: Long, val isVideo: Boolean, val phase: Phase) {
        sealed interface Phase {
            data object Uploading : Phase
            data class Ready(val url: String) : Phase
            /** Photos only: send then puts the photo on chain. A video that fails is dropped
             *  from the composer instead, since no on-chain path fits one. */
            data object Failed : Phase
        }

        val isUploading: Boolean get() = phase == Phase.Uploading
    }

    /** One composer's staged Nextcloud upload ([NextcloudStagedMedia]), with the composer's own
     *  pending media and route state, which a failed video upload clears. */
    private inner class NextcloudStager(
        private val pendingUri: MutableStateFlow<Uri?>,
        private val requested: MutableStateFlow<Boolean>,
    ) {
        val staged = MutableStateFlow<NextcloudStagedMedia?>(null)
        private var job: kotlinx.coroutines.Job? = null

        /** Starts uploading [uri] as the staged media; [onVideoFailed] shows why a video left. */
        fun start(uri: Uri, isVideo: Boolean, onVideoFailed: (String) -> Unit) {
            job?.cancel()
            val media = NextcloudStagedMedia(System.nanoTime(), isVideo, NextcloudStagedMedia.Phase.Uploading)
            staged.value = media
            job = viewModelScope.launch {
                try {
                    val url = uploadMediaToNextcloud(uri, isVideo)
                    // Warm the preview cache so the sender's own bubble shows the card at once.
                    launch { com.kachat.app.services.LinkPreviewService.fetchPreview(url) }
                    if (staged.value?.id == media.id) staged.value = media.copy(phase = NextcloudStagedMedia.Phase.Ready(url))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (staged.value?.id != media.id) return@launch
                    Log.w("ChatViewModel", "Nextcloud ${if (isVideo) "video" else "photo"} upload failed", e)
                    if (isVideo) {
                        discard()
                        onVideoFailed(e.message ?: appContext.getString(com.kachat.app.R.string.media_video_load_failed))
                    } else {
                        staged.value = media.copy(phase = NextcloudStagedMedia.Phase.Failed)
                        requested.value = false
                        com.kachat.app.ui.screens.IosToasts.error(appContext.getString(com.kachat.app.R.string.nc_photo_upload_failed_toast))
                    }
                }
            }
        }

        /** Stops the upload; the composer's pending media stays. */
        fun cancel() {
            job?.cancel()
            job = null
            staged.value = null
        }

        /** Removing the pending media cancels its upload. */
        fun discard() {
            cancel()
            requested.value = false
            pendingUri.value = null
        }
    }

    private val nextcloudStager = NextcloudStager(_pendingPhotoUri, _nextcloudPhotoRequested)
    /** The 1:1 composer's staged Nextcloud upload, if any. */
    val nextcloudStaged: StateFlow<NextcloudStagedMedia?> = nextcloudStager.staged.asStateFlow()

    fun setPendingPhoto(uri: Uri?, viaNextcloud: Boolean = false) {
        nextcloudStager.cancel()
        _nextcloudPhotoRequested.value = viaNextcloud && uri != null
        _pendingPhotoUri.value = uri
        if (uri != null && photoViaNextcloud) nextcloudStager.start(uri, isVideo = false) {}
    }

    /** A video picked "via Nextcloud" waits in the composer like a photo and starts uploading
     *  right away (iOS 7abc06e). It used to send on its own the moment it was picked. */
    fun setPendingNextcloudVideo(contactId: String, uri: Uri) {
        nextcloudStager.cancel()
        _nextcloudPhotoRequested.value = true
        _pendingPhotoUri.value = uri
        nextcloudStager.start(uri, isVideo = true) { reportSendFailure(contactId, it) }
    }

    fun cancelPendingPhoto() {
        nextcloudStager.discard()
        photoTierPrep.clear()
    }

    // Group chat's own photo-staging/voice-recording state - kept separate from the 1:1 fields
    // above (rather than reused) since both screens share this same ViewModel instance and a
    // photo staged on one screen bleeding into the other on a quick navigation would be a real bug.
    private val _groupVoiceRecordingState = MutableStateFlow(VoiceRecordingState())
    val groupVoiceRecordingState: StateFlow<VoiceRecordingState> = _groupVoiceRecordingState.asStateFlow()

    private val _groupPendingPhotoUri = MutableStateFlow<Uri?>(null)
    val groupPendingPhotoUri: StateFlow<Uri?> = _groupPendingPhotoUri.asStateFlow()

    private val groupNextcloudStager = NextcloudStager(_groupPendingPhotoUri, _groupNextcloudPhotoRequested)
    /** The group composer's staged Nextcloud upload, if any. */
    val groupNextcloudStaged: StateFlow<NextcloudStagedMedia?> = groupNextcloudStager.staged.asStateFlow()

    fun setGroupPendingPhoto(uri: Uri?, viaNextcloud: Boolean = false) {
        groupNextcloudStager.cancel()
        _groupNextcloudPhotoRequested.value = viaNextcloud && uri != null
        _groupPendingPhotoUri.value = uri
        if (uri != null && groupPhotoViaNextcloud) groupNextcloudStager.start(uri, isVideo = false) {}
    }

    /** The group composer's [setPendingNextcloudVideo]; [onError] shows a failed upload above the
     *  composer, as iOS's group chat shows it. */
    fun setGroupPendingNextcloudVideo(uri: Uri, onError: (String) -> Unit) {
        groupNextcloudStager.cancel()
        _groupNextcloudPhotoRequested.value = true
        _groupPendingPhotoUri.value = uri
        groupNextcloudStager.start(uri, isVideo = true, onError)
    }

    fun cancelGroupPendingPhoto() {
        groupNextcloudStager.discard()
        groupPhotoTierPrep.clear()
    }

    /** Bumped when code (not a keystroke) sets the 1:1 composer's text, or the fee editor opens:
     *  the fee preview then prices the text at once instead of waiting for a pause. */
    private val _feePreviewForce = MutableStateFlow(0L)

    /**
     * The 1:1 composer's text bytes as the fee preview prices them: while typing, only after a
     * 0.6 s pause and not while within 24 bytes of the size already priced (iOS 0977a5b, see
     * [com.kachat.app.util.TypingFeeGate]) - so a keystroke doesn't re-price, and redraw, the
     * fee pill. A reply, fee override, staged photo, recording, Nextcloud route or payment
     * change re-prices at once. `replyingTo` is declared further down, so it's read lazily.
     */
    private val typedTextPreview: SharedFlow<com.kachat.app.util.TypingFeeGate.Preview> = com.kachat.app.util.TypingFeeGate.preview(
        combine(
            combine(_messageText, _feePreviewForce, flow { emitAll(replyingTo) }) { text, force, reply -> Triple(text, force, reply?.id) },
            combine(_feeRateOverride, voiceRecordingState.map { it.status }.distinctUntilChanged(), pendingPhotoUri) { override, recording, photoUri -> Triple(override, recording, photoUri) },
            combine(_nextcloudPhotoRequested, _nextcloudVoiceRequested) { photoNextcloud, voiceNextcloud -> photoNextcloud to voiceNextcloud },
            combine(paymentAmount, _paymentNote) { amount, note -> amount to note },
        ) { (text, force, replyId), media, nextcloud, payment ->
            com.kachat.app.util.TypingFeeGate.Input(text, listOf(force, replyId, media, nextcloud, payment))
        }
    ).shareIn(viewModelScope, SharingStarted.WhileSubscribed(), replay = 1)

    private val typedTextPreviewBytes: Flow<Int> = com.kachat.app.util.TypingFeeGate.payloadBytes(typedTextPreview)

    /** The 1:1 fee pill shows iOS's shimmering placeholder while a re-price waits for its pause. */
    val isEstimatingFee: StateFlow<Boolean> = com.kachat.app.util.TypingFeeGate.estimating(typedTextPreview)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    /**
     * The payload byte count to price the live fee preview off of: the typed text's payload
     * while composing, or the ~100-byte share link for media going via Nextcloud. An on-chain
     * voice note is priced part by part ([voiceNoteFeeSompi]), and an on-chain photo has no fee
     * until the quality sheet shows each tier's (iOS 7c5c22e3).
     */
    private val previewPayloadSize: Flow<Int> = combine(
        combine(typedTextPreviewBytes, voiceRecordingState, pendingPhotoUri, flow { emitAll(replyingTo) }) { textBytes, recording, photoUri, reply -> PreviewInput(textBytes, recording, photoUri, reply) },
        _nextcloudPhotoRequested,
        _nextcloudVoiceRequested,
    ) { (textBytes, recording, photoUri, reply), photoNextcloud, voiceNextcloud ->
        // Via Nextcloud (chosen in the "+" sheet), the chain only carries the ~100-byte share
        // link regardless of media size — mirrors groupPreviewPayloadSize's identical branch.
        val connected = nextcloudService.isConnected
        if (recording.isActive) {
            if (recording.viaNextcloud && connected) NEXTCLOUD_LINK_PREVIEW_BYTES else 0
        } else if (photoUri != null) {
            if (photoNextcloud && connected) NEXTCLOUD_LINK_PREVIEW_BYTES else 0
        } else if (textBytes == 0) {
            0
        } else {
            // The whole payload a send carries, reply envelope included, so the pill shows what
            // the send costs (iOS estimateMessageFee builds this payload to price it).
            MessageProtocol.estimatedCommPayloadSize(textBytes + replyEnvelopeBytes(reply))
        }
    }

    private data class PreviewInput(
        val textBytes: Int,
        val recording: VoiceRecordingState,
        val photoUri: android.net.Uri?,
        val reply: MessageEntity?,
    )

    /**
     * The fee of the on-chain voice note being recorded or ready to send (iOS 7c5c22e3
     * `updateRecordingFee`): the sum over the parts it goes as - while recording, the part sizes
     * the layout gives for the length so far ([com.kachat.app.util.VoiceNoteLayout]); once
     * encoded, the real parts. Null when there is no on-chain note.
     */
    private fun voiceNoteFeeFlow(state: StateFlow<VoiceRecordingState>, path: com.kachat.app.util.VoiceNotePolicy.Path, forGroup: Boolean): StateFlow<Long?> =
        combine(state, _networkFeeRate, _feeRateOverride) { recording, networkRate, overrideRate ->
            if (!recording.isActive || recording.viaNextcloud) return@combine null
            val sizes = recording.readyPartSizes ?: com.kachat.app.util.VoiceNoteLayout.partSizes(
                durationSeconds = recording.elapsedMs / 1000.0,
                partSeconds = path.partSeconds,
                maxBytes = path.maxBytes,
            )
            if (sizes.isEmpty()) null
            else com.kachat.app.util.MediaPartPayload.feeSompi(sizes, forGroup, overrideRate ?: networkRate.toLong())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    /** The 1:1 composer's on-chain voice note fee. */
    val voiceNoteFeeSompi: StateFlow<Long?> by lazy { voiceNoteFeeFlow(voiceRecordingState, com.kachat.app.util.VoiceNotePolicy.ONE_TO_ONE, forGroup = false) }

    /** The group composer's on-chain voice note fee. */
    val groupVoiceNoteFeeSompi: StateFlow<Long?> by lazy { voiceNoteFeeFlow(groupVoiceRecordingState, com.kachat.app.util.VoiceNotePolicy.GROUP, forGroup = true) }

    /** What replying to [reply] adds to the text's bytes; built as [sendMessage] builds it. */
    private fun replyEnvelopeBytes(reply: MessageEntity?): Int {
        if (reply == null) return 0
        val preview = VoiceMessage.parseOrNull(reply.plaintextBody)?.let { "🎤 Audio message" }
            ?: ImageMessage.parseOrNull(reply.plaintextBody)?.let { "📷 Photo" }
            ?: MessageReply.parseOrNull(reply.plaintextBody)?.text
            ?: (reply.plaintextBody ?: "")
        val sender = if (reply.direction == "sent") runCatching { walletManager.getAddress() }.getOrDefault("") else reply.contactId
        return MessageReply.encode(replyToId = reply.id, replyToSender = sender, replyToPreview = preview, text = "").toByteArray(Charsets.UTF_8).size
    }

    /** Where the open Send KAS sheet pays from - its From (iOS 6333dd3
     *  `SendSource`): null = the chatting address (the default), else that spending address.
     *  The sheet resets it each time it opens and closes. [refreshSpendingUtxos] reads this
     *  source's UTXOs, so the fee preview and Max price what the send will actually spend.
     *  Declared before the fee preview below, which reads it. */
    private val _paymentSourceIndex = MutableStateFlow<Int?>(null)

    /** What the fee preview prices: the message coins, the payment's coins (its From - the
     *  chatting address or a spending address, selected the same way either way) and the
     *  payment's coin control pick. */
    private data class FeeEstimateCoins(
        val identity: List<com.kachat.app.services.UtxoEntry>,
        val payment: List<com.kachat.app.services.UtxoEntry>,
        val manual: List<com.kachat.app.services.UtxoEntry>?,
        /** The payment From's per-input signature-script size: a quantum-safe (KIP-22) spending
         *  address's inputs carry its redeem script too (iOS estimatePaymentFee(signatureScriptSize:)). */
        val paymentSignatureScriptSize: Int,
    )

    private val utxosForFeeEstimate: Flow<FeeEstimateCoins> =
        combine(_currentUtxos, _spendingUtxos, _paymentManualUtxos, _paymentSourceIndex) { identity, spending, manual, source ->
            FeeEstimateCoins(identity, spending, manual, com.kachat.app.services.WalletManager.signatureScriptSize(source))
        }

    /** [paymentFeeEstimateOrThrow], null when it can't go or can't be worked out. */
    private fun paymentFeeEstimate(
        coins: FeeEstimateCoins,
        amountSompi: Long,
        note: String,
        overrideRate: Long?,
        extraFeeSompi: Long = 0L,
    ): com.kachat.app.util.SendFeeModel.SendFeeEstimate? {
        if (amountSompi <= 0) return null
        return try {
            paymentFeeEstimateOrThrow(coins, amountSompi, note, overrideRate, extraFeeSompi)
        } catch (e: IllegalStateException) {
            null
        }
    }

    /**
     * What the chat payment being priced costs (iOS `estimatePaymentFee`): the From's non-coinbase
     * coins, or coin control's pick among them. With no [extraFeeSompi] it is the Normal base; with
     * one, the shape the send builds at that speed (its network fee holds the extra). Throws why it
     * can't go - too little for the amount and fee, real change storage mass won't let stand, more
     * inputs than one transaction holds, no spendable coins, or every picked coin gone (never a
     * silent fall back to automatic selection, iOS d23e6f7a `resolveReviewSelection`) - and the
     * sheet shows it with Try Again ([paymentFeeError]).
     */
    private fun paymentFeeEstimateOrThrow(
        coins: FeeEstimateCoins,
        amountSompi: Long,
        note: String,
        overrideRate: Long?,
        extraFeeSompi: Long = 0L,
    ): com.kachat.app.util.SendFeeModel.SendFeeEstimate {
        val candidates = coins.payment.filterNot { it.utxoEntry.isCoinbase }
        val manual = resolvePaymentSelection(coins.manual, candidates)
        if ((manual ?: candidates).isEmpty()) {
            throw IllegalStateException(appContext.getString(com.kachat.app.R.string.no_spendable_utxos))
        }
        return com.kachat.app.util.SendFeeModel.estimate(
            // Picked greedily among coin control's coins, as the send does.
            utxos = manual ?: candidates,
            manual = null,
            amountSompi = amountSompi,
            feeRateSompiPerGram = overrideRate?.coerceAtLeast(com.kachat.app.util.KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM)
                ?: com.kachat.app.util.KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM,
            // A chat payment always carries its sealed `kchat:1:pay:` payload, memo or not (see
            // sendPayment), so the fee prices it - as iOS estimatePaymentFee(note:) always does.
            payloadSize = MessageProtocol.estimatedPaymentPayloadSize(note.trim(), amountSompi),
            signatureScriptSize = coins.paymentSignatureScriptSize,
            extraFeeSompi = extraFeeSompi,
        )
    }

    /** Coin control for the payment's fee or Max: the picked coins still among [candidates], null
     *  for automatic. When none of the picked coins are left it throws the send's own error
     *  rather than pricing automatic selection (iOS d23e6f7a `resolveReviewSelection`). */
    private fun resolvePaymentSelection(
        manual: List<com.kachat.app.services.UtxoEntry>?,
        candidates: List<com.kachat.app.services.UtxoEntry>,
    ): List<com.kachat.app.services.UtxoEntry>? = com.kachat.app.util.SendFeeModel.resolveReviewSelection(
        manual, candidates, appContext.getString(com.kachat.app.R.string.selected_utxos_are_no_longer_available_please)
    )

    val estimatedFeeSompi: StateFlow<Long?> = combine(
        combine(paymentAmount, _paymentNote) { amount, note -> amount to note },
        previewPayloadSize, utxosForFeeEstimate, _networkFeeRate, _feeRateOverride
    ) { (amount, paymentNote), textPayloadSize, coins, networkRate, overrideRate ->
        val rate = overrideRate?.toDouble() ?: networkRate
        if (amount.isEmpty() && textPayloadSize == 0) return@combine null

        if (amount.isNotEmpty()) {
            return@combine paymentFeeEstimate(coins, KaspaUnit.sompiFromUserText(amount) ?: 0L, paymentNote, overrideRate)?.networkFeeSompi
        }

        var count = 0
        var total = 0L
        for (utxo in coins.identity) {
            total += utxo.utxoEntry.amount
            count++
            if (total >= 1000) break // Buffer for fee
        }

        // Preview only. A message is a zero-amount self-stash send, and KaspaWalletEngine
        // skips the zero-value recipient output for those (a 0-value output is non-standard and
        // gets rejected) — matches iOS's estimateContextualMessageFee, which also prices a
        // message off a single output. Assuming 2 outputs here previously overpriced every
        // message/voice/photo preview by a phantom output (~412 mass, ~0.0004 KAS at min rate).
        val mass = com.kachat.app.util.KaspaMass.calculateMass(
            numInputs = count.coerceAtLeast(1),
            outputScriptLens = listOf(34),
            payloadSize = textPayloadSize,
        )
        com.kachat.app.util.KaspaMass.calculateFee(mass, rate.toLong())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    /** The Send KAS sheet's speed (its multiplier) and custom extra, which the fee it shows is
     *  worked out with ([paymentBuiltFeeSompi]). Set by the sheet. */
    private val _paymentFeeSpeed = MutableStateFlow<Pair<Long, Long?>>(1L to null)

    fun setPaymentFeeSpeed(feeMultiplier: Long, customExtraFeeSompi: Long?) {
        _paymentFeeSpeed.value = feeMultiplier to customExtraFeeSompi
    }

    /** The payment source's coins as [refreshSpendingUtxos] last read them: still being read, or
     *  why they couldn't be. Declared before the fee quote below, which reads it. */
    private data class PaymentCoinsStatus(val loading: Boolean = false, val error: String? = null)

    private val _paymentCoinsStatus = MutableStateFlow(PaymentCoinsStatus())

    /** The payment's fee at the chosen speed, or why it couldn't be worked out. */
    private data class PaymentFeeQuote(val builtFeeSompi: Long? = null, val error: String? = null)

    /**
     * The fee the payment being priced pays at the chosen speed: network fee, extra and any change
     * too small to keep (iOS `paymentBuiltFeeSompi`). The Normal base is priced first (the speeds
     * multiply it), then the payment again with the extra in: a bigger extra can take another coin
     * or leave no change worth keeping, so it is not just added on top. Either failing holds the
     * swipe with the error: never a silent fall back to a fee nobody saw (iOS d23e6f7a
     * `paymentFeeError`). While the coins are being read there is no fee and no error.
     */
    private val paymentFeeQuote: StateFlow<PaymentFeeQuote> = combine(
        combine(paymentAmount, _paymentNote) { amount, note -> amount to note },
        utxosForFeeEstimate, _feeRateOverride, _paymentFeeSpeed, _paymentCoinsStatus
    ) { (amount, paymentNote), coins, overrideRate, (multiplier, customExtra), coinsStatus ->
        val amountSompi = KaspaUnit.sompiFromUserText(amount) ?: 0L
        if (amountSompi <= 0L || coinsStatus.loading) return@combine PaymentFeeQuote()
        coinsStatus.error?.let { return@combine PaymentFeeQuote(error = it) }
        try {
            val base = paymentFeeEstimateOrThrow(coins, amountSompi, paymentNote, overrideRate)
            val extra = com.kachat.app.util.SendFeeModel.extraFee(base.networkFeeSompi, multiplier, customExtra)
            val paid = if (extra > 0L) paymentFeeEstimateOrThrow(coins, amountSompi, paymentNote, overrideRate, extra) else base
            PaymentFeeQuote(builtFeeSompi = paid.networkFeeSompi + paid.foldedChangeSompi)
        } catch (e: IllegalStateException) {
            PaymentFeeQuote(error = UserFacingError.message(e, appContext.getString(com.kachat.app.R.string.could_not_work_out_the_network_fee)))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), PaymentFeeQuote())

    /** [paymentFeeQuote]'s fee; null while unknown or when it can't go. */
    val paymentBuiltFeeSompi: StateFlow<Long?> = paymentFeeQuote.map { it.builtFeeSompi }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    /** Why the payment's fee couldn't be worked out: the Review shows it with Try Again
     *  ([refreshSpendingUtxos]) and the swipe waits. */
    val paymentFeeError: StateFlow<String?> = paymentFeeQuote.map { it.error }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    /**
     * The Send KAS sheet's Max: what the payment's source can send after the base fee and the
     * Fast / Priority / custom extra (iOS 62c2773 estimateMaxPaymentAmount leaves room for the
     * extra). With coin control on, only the picked coins count. Every coin counted is an input,
     * as a send of everything spends them all (coin control's exact set, or the source's coins -
     * the chatting address or a spending address alike, iOS estimateMaxPaymentAmount). The
     * payment's `kchat:1:pay:` payload (memo or not) is priced in, so a Max payment still covers its
     * fee.
     *
     * The extra is worked out here, from the base this prices ([feeMultiplier] - 1 times it, or
     * [customExtraFeeSompi]) - not taken from the sheet, whose extra comes from the fee estimate
     * and is 0 while the amount is empty. Max with Fast or Priority then filled in balance - base,
     * and the send needed base + extra on top of it (audit AND-010).
     *
     * Only what one transaction can spend counts: the 80 largest coins when there are more (see
     * [paymentMaxCoins]) - a busy address holding more was refused at send (AND-012).
     *
     * The speed's base is the fee preview's own for the max send (iOS df4aba6): the coins and
     * payload [estimatedFeeSompi] would price for that amount.
     */
    fun paymentMaxSompi(feeMultiplier: Long, customExtraFeeSompi: Long?): PaymentMax {
        val spending = _spendingUtxos.value
        // Coin control's pick still among the coins; every picked coin gone gives no Max (iOS
        // d23e6f7a: the fee estimate says to reselect), never a Max of automatic selection.
        val manual = try {
            resolvePaymentSelection(_paymentManualUtxos.value, spending.filterNot { it.utxoEntry.isCoinbase })
        } catch (e: IllegalStateException) {
            return PaymentMax(0L, cappedAtInputLimit = false)
        }
        val (balance, inputs, capped) = paymentMaxCoins(spending, manual)
        val note = _paymentNote.value
        val maxSompi = computePaymentMaxSompi(
            balanceSompi = balance,
            inputCount = inputs,
            note = note,
            feeRate = _feeRateOverride.value?.toDouble() ?: com.kachat.app.util.KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM.toDouble(),
            feeMultiplier = feeMultiplier,
            customExtraFeeSompi = customExtraFeeSompi,
            signatureScriptSize = com.kachat.app.services.WalletManager.signatureScriptSize(_paymentSourceIndex.value),
            provisionalShape = { provisional ->
                paymentFeeInputs(spending, manual, provisional).second to
                    MessageProtocol.estimatedPaymentPayloadSize(note.trim(), provisional)
            },
        )
        return PaymentMax(maxSompi, cappedAtInputLimit = capped)
    }

    /** A chat payment's Max: the amount, and whether it is what one transaction's largest coins
     *  can carry rather than all of them (iOS d23e6f7a `cappedAtInputLimit`) - the Review then
     *  says so while the amount is still that Max. */
    data class PaymentMax(val amountSompi: Long, val cappedAtInputLimit: Boolean)

    /** What a chat payment's Max spends ([paymentMaxCoins]): the coins' total, how many inputs
     *  that is, and whether it is only the largest ones one transaction can carry. */
    data class PaymentMaxCoins(val balanceSompi: Long, val inputCount: Int, val cappedAtInputLimit: Boolean)

    // -------------------------------------------------------------------------
    // Group chat's own live fee preview - same KaspaMass calc as [estimatedFeeSompi] above, but
    // sized for gcomm's wire format (see estimatedGroupWirePayloadSize) instead of 1:1's plain
    // ECIES envelope. The composer's text field is local Compose state (unlike 1:1's ViewModel-
    // owned messageText), so setGroupMessageText bridges it in via LaunchedEffect.
    // -------------------------------------------------------------------------

    private val _groupMessageText = MutableStateFlow("")

    /** Bumped when code (not a keystroke) sets the group composer's text, or the fee editor
     *  opens - priced at once, like 1:1's [_feePreviewForce]. */
    private val _groupFeePreviewForce = MutableStateFlow(0L)

    /** [fromTyping]: a keystroke in the composer, whose fee preview waits for a pause (see
     *  [groupTypedTextPreviewBytes]); any other set is priced at once. */
    fun setGroupMessageText(text: String, fromTyping: Boolean = false) {
        _groupMessageText.value = text
        if (!fromTyping) _groupFeePreviewForce.value++
    }

    /** Re-prices the group fee preview off the composer's exact text now (the fee editor opening). */
    fun refreshGroupFeePreview() {
        _groupFeePreviewForce.value++
    }

    /**
     * Raw bytes -> gcomm wire size: for media (photo/audio), raw bytes -> base64 (+33%) in the
     * JSON envelope (+150 bytes overhead) first; either way, that content is then ChaCha20-Poly1305
     * encrypted (+16 byte tag) and the whole ciphertext is hex-encoded (2x) for the wire, plus a
     * fixed ~370 bytes of hex-encoded overhead (blinded_group_id/sender_id/sender_pub/msg_id/
     * signature) that 1:1's plain-ECIES envelope doesn't carry. Matches iOS's
     * GroupChatService.estimatedGroupWirePayloadSize. Preview only - the real send measures exactly.
     */
    private fun estimatedGroupWirePayloadSize(rawBytes: Int, isMediaEnvelope: Boolean): Int {
        val innerBytes = if (isMediaEnvelope) (rawBytes * 1.33).toInt() + 150 else rawBytes
        val ciphertextHexBytes = (innerBytes + 16) * 2
        return ciphertextHexBytes + 370
    }

    /** The group composer's text bytes as the fee preview prices them - the same typing gate as
     *  1:1's [typedTextPreviewBytes] (iOS 0977a5b). `groupReplyingTo` is declared further down,
     *  so it's read lazily. */
    private val groupTypedTextPreview: SharedFlow<com.kachat.app.util.TypingFeeGate.Preview> = com.kachat.app.util.TypingFeeGate.preview(
        combine(
            combine(_groupMessageText, _groupFeePreviewForce, flow { emitAll(groupReplyingTo) }) { text, force, reply -> Triple(text, force, reply?.txId) },
            combine(_feeRateOverride, groupVoiceRecordingState.map { it.status }.distinctUntilChanged(), groupPendingPhotoUri) { override, recording, photoUri -> Triple(override, recording, photoUri) },
            combine(_groupNextcloudPhotoRequested, _groupNextcloudVoiceRequested, nextcloudService.account) { photoNextcloud, voiceNextcloud, account -> Triple(photoNextcloud, voiceNextcloud, account != null) },
        ) { (text, force, replyId), media, nextcloud ->
            com.kachat.app.util.TypingFeeGate.Input(text, listOf(force, replyId, media, nextcloud))
        }
    ).shareIn(viewModelScope, SharingStarted.WhileSubscribed(), replay = 1)

    private val groupTypedTextPreviewBytes: Flow<Int> = com.kachat.app.util.TypingFeeGate.payloadBytes(groupTypedTextPreview)

    /** The group fee pill's placeholder state - see [isEstimatingFee]. */
    val groupIsEstimatingFee: StateFlow<Boolean> = com.kachat.app.util.TypingFeeGate.estimating(groupTypedTextPreview)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    private val groupPreviewPayloadSize: Flow<Int> = combine(
        combine(groupTypedTextPreviewBytes, groupVoiceRecordingState, groupPendingPhotoUri) { textBytes, recording, photoUri -> Triple(textBytes, recording, photoUri) },
        _groupNextcloudPhotoRequested,
        _groupNextcloudVoiceRequested,
        nextcloudService.account,
    ) { (textBytes, recording, photoUri), photoNextcloud, voiceNextcloud, account ->
        // Media sent "via Nextcloud" from the "+" sheet goes out as just a short share-link text
        // message (see sendPendingGroupPhoto/sendRecordedGroupVoice), so the fee pill
        // prices a link-sized payload instead of the embedded media envelope.
        val connected = account != null
        // An on-chain voice note is priced part by part ([groupVoiceNoteFeeSompi]) and an
        // on-chain photo in the quality sheet (iOS 7c5c22e3): 0 here, no pill.
        when {
            recording.isActive && recording.viaNextcloud && connected -> estimatedGroupWirePayloadSize(NEXTCLOUD_LINK_PREVIEW_BYTES, isMediaEnvelope = false)
            recording.isActive -> 0
            photoUri != null && photoNextcloud && connected -> estimatedGroupWirePayloadSize(NEXTCLOUD_LINK_PREVIEW_BYTES, isMediaEnvelope = false)
            photoUri != null -> 0
            else -> estimatedGroupWirePayloadSize(textBytes, isMediaEnvelope = false)
        }
    }

    val groupEstimatedFeeSompi: StateFlow<Long?> = combine(groupPreviewPayloadSize, _currentUtxos, _networkFeeRate, _feeRateOverride) { payloadSize, utxos, networkRate, overrideRate ->
        val rate = overrideRate?.toDouble() ?: networkRate
        if (payloadSize <= 372) return@combine null // 370 fixed overhead + 2 for an empty ciphertext -> nothing actually staged/typed yet

        var total = 0L
        var count = 0
        for (utxo in utxos) {
            total += utxo.utxoEntry.amount
            count++
            if (total >= 1000) break
        }

        val mass = com.kachat.app.util.KaspaMass.calculateMass(
            numInputs = count.coerceAtLeast(1),
            outputScriptLens = listOf(34),
            payloadSize = payloadSize
        )
        com.kachat.app.util.KaspaMass.calculateFee(mass, rate.toLong())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    /** Synchronous mass-based fee estimate (sompi) for ONE group control tx of the given payload
     *  size, using the current network fee rate — mirrors [groupEstimatedFeeSompi]'s math. Lets the
     *  group-info confirmations show the on-chain cost of an action before committing. */
    fun estimateGroupControlTxFeeSompi(payloadBytes: Int): Long {
        val rate = _feeRateOverride.value?.toDouble() ?: _networkFeeRate.value
        var total = 0L; var count = 0
        for (u in _currentUtxos.value) { total += u.utxoEntry.amount; count++; if (total >= 1000) break }
        val mass = com.kachat.app.util.KaspaMass.calculateMass(
            numInputs = count.coerceAtLeast(1), outputScriptLens = listOf(34), payloadSize = payloadBytes
        )
        return com.kachat.app.util.KaspaMass.calculateFee(mass, rate.toLong())
    }

    /** [fromTyping]: a keystroke in the composer, whose fee preview waits for a pause (see
     *  [typedTextPreviewBytes]); any other set is priced at once. */
    fun setMessageText(text: String, fromTyping: Boolean = false) {
        _messageText.value = text
        if (!fromTyping) _feePreviewForce.value++
        activeDraftKey?.let { draftStore.setDraft(it, text) }
    }

    /** Re-prices the 1:1 fee preview off the composer's exact text now - the fee editor opening,
     *  so the rate it derives from the shown fee isn't off by a pending keystroke or two. */
    fun refreshFeePreview() {
        _feePreviewForce.value++
    }

    /** The conversation whose draft the 1:1 composer is editing - see [openDraft]. */
    @Volatile
    private var activeDraftKey: String? = null

    /**
     * A 1:1 chat was entered: the composer shows what was typed there last time, and from now on
     * keeps it as it changes (iOS: ChatService drafts keyed by the contact's address). Every chat
     * shares this one composer, so it is always REPLACED - text left from another chat never
     * carries over.
     */
    fun openDraft(contactId: String) {
        activeDraftKey = contactId
        _messageText.value = draftStore.draft(contactId)
        _feePreviewForce.value++
    }

    /** The chat was left. Its draft stays saved; a different chat already opened is untouched. */
    fun closeDraft(contactId: String) {
        if (activeDraftKey == contactId) activeDraftKey = null
    }

    /** Puts [text] in [contactId]'s composer for when the chat opens - the share sheet's fallback. */
    fun stageDraft(contactId: String, text: String) {
        draftStore.setDraft(contactId, text)
        if (activeDraftKey == contactId) {
            _messageText.value = text
            _feePreviewForce.value++
        }
    }

    /** A group's saved draft, and saving it - see [com.kachat.app.services.DraftStore]. */
    fun groupDraft(groupId: String): String = draftStore.draft(com.kachat.app.services.DraftStore.groupKey(groupId))

    fun saveGroupDraft(groupId: String, text: String) =
        draftStore.setDraft(com.kachat.app.services.DraftStore.groupKey(groupId), text)

    fun setPaymentAmount(amount: String) {
        _paymentAmount.value = amount
    }

    fun setPaymentNote(note: String) {
        _paymentNote.value = note
    }

    fun refreshUtxos() {
        viewModelScope.launch {
            try {
                val address = walletManager.getAddress()
                val api = networkService.kaspaRestApi.value ?: return@launch

                _currentUtxos.value = api.getUtxos(address)
            } catch (e: Exception) {
                Log.w("ChatViewModel", "UTXO refresh failed", e)
            }
        }
    }

    /** The account in use - never offered as a group member from the Address Book. */
    val activeAddress: String? get() = walletManager.activeAddressFlow.value

    fun setPaymentSource(index: Int?) {
        if (_paymentSourceIndex.value == index) return
        _paymentSourceIndex.value = index
        refreshSpendingUtxos()
    }

    /** Re-reads the payment source's coins into [spendingUtxos] (iOS
     *  `paymentFundingSourceAddress`: the chatting address, or the chosen spending address); the
     *  returned job completes when the read is done. Read fresh and reconciled, as the send reads
     *  them: coins a send or message from this app just spent stay out until that is in a block,
     *  and only confirmed, non-coinbase coins count (iOS d23e6f7a `withdrawalSpendableUtxos`). A
     *  read that fails is the fee's error, with Try Again (this again). */
    fun refreshSpendingUtxos(): kotlinx.coroutines.Job {
        val source = _paymentSourceIndex.value
        _paymentCoinsStatus.value = PaymentCoinsStatus(loading = true)
        return viewModelScope.launch {
            try {
                val address = if (source != null) walletManager.deriveSpendingAddress(source) else walletManager.getAddress()
                val utxos = walletEngine.fetchUtxosOrThrow(address)
                    .filter { !it.utxoEntry.isCoinbase && it.utxoEntry.blockDaaScore > 0 }
                // A refresh for a source that has since changed would price the wrong address.
                if (_paymentSourceIndex.value == source) {
                    _spendingUtxos.value = utxos
                    _paymentCoinsStatus.value = PaymentCoinsStatus()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ChatViewModel", "Spending UTXO refresh failed", e)
                if (_paymentSourceIndex.value == source) {
                    _paymentCoinsStatus.value = PaymentCoinsStatus(
                        error = UserFacingError.message(e, appContext.getString(com.kachat.app.R.string.could_not_work_out_the_network_fee))
                    )
                }
            }
        }
    }

    /**
     * @param address Must always be a real kaspa: address — never a raw KNS domain
     * string, since it's used as the contact's primary key everywhere (sends,
     * encryption, etc.). Resolve a domain to its owning address first (see
     * [onCreateChatAddressChanged]) and pass the result here.
     * @param knsName The domain name to display, if this contact was added via KNS.
     */
    /**
     * [deliberate]: the user typed the address in (Create Chat) - their own other accounts are
     * allowed, only the account in use is refused (see ChatRepository.addContact). [onResult]
     * gets the refusal reason, or null when the contact was stored, so the caller can say why
     * where the user can see it instead of a tap that does nothing.
     */
    fun addContact(address: String, name: String?, knsName: String? = null, deliberate: Boolean = false, onResult: (String?) -> Unit = {}) {
        viewModelScope.launch {
            // A deliberate add is of the network the app runs on: a chat with the other network's
            // address is never read and is dropped on the next launch (iOS 218dc42, IOS-063).
            if (deliberate && !com.kachat.app.util.KaspaAddress.isValidOnActiveNetwork(address.trim())) {
                onResult(com.kachat.app.util.KaspaAddress.otherNetworkReason(address) ?: "Invalid Kaspa address format.")
                return@launch
            }
            val existing = chatRepository.getContact(address)
            val stored = if (existing != null) {
                // If contact exists, update name if provided
                val updated = existing.copy(
                    alias = if (name.isNullOrBlank()) existing.alias else name,
                    knsName = knsName ?: existing.knsName
                )
                chatRepository.addContact(updated, deliberate = deliberate)
            } else {
                val newContact = ContactEntity(
                    id = address,
                    walletAddress = walletManager.getAddress(),
                    alias = if (name.isNullOrBlank()) null else name,
                    knsName = knsName,
                    publicKeyHex = null
                )
                chatRepository.addContact(newContact, deliberate = deliberate)
            }
            if (!stored) {
                onResult(
                    if (deliberate) "That is the account you are using - there is no one to talk to."
                    else "That address is one of your own accounts."
                )
                return@launch
            }
            onResult(null)

            // Added by raw address with no domain typed: record their primary KNS domain so
            // `ContactEntity.displayName` can show it. It is deliberately NOT copied into
            // `alias` — a contact is named only when the user names one, and a domain baked
            // into the alias would go stale the moment the domain moved.
            if (knsName == null) {
                val primary = knsService.identityName(address)
                if (primary != null) {
                    chatRepository.getContact(address)?.let { current ->
                        chatRepository.addContact(current.copy(knsName = primary))
                    }
                }
            }
            refreshKnsProfile(address)
        }
    }

    // -------------------------------------------------------------------------
    // Group chats
    // -------------------------------------------------------------------------

    val groups = groupRepository.getGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Groups with their latest message, for the Group Chats tab's list (mirrors [conversations]'
     * shape for 1:1 chats). Group messages are stored encrypted, so this used to subscribe to
     * every group's full message flow and decrypt all of it just to find the newest one and
     * count unread - per group, on every database emission, on the main thread. It now reads
     * [GroupRepository.getGroupSummary]: the count comes from SQL over the plaintext
     * `isOutgoing`/`blockTimestamp` columns, and exactly one message is decrypted per group.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val groupConversations: StateFlow<List<GroupConversation>> = groups.flatMapLatest { groupList ->
        if (groupList.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(groupList.map { group ->
                groupRepository.getGroupSummary(group.groupId, group.lastReadAt, group.isAdmin).map { summary ->
                    GroupConversation(group, summary.latestMessage, summary.unreadCount)
                }
            }) { conversations -> conversations.sortedByDescending { it.lastMessage?.blockTimestamp ?: 0L } }
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Unread messages across every group (iOS `totalGroupUnreadCount`): the Groups dock tab's
     *  count (iOS 64d63846). */
    val groupsUnreadCount: StateFlow<Int> = groupConversations.map { list -> list.sumOf { it.unreadCount.coerceAtLeast(0) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /**
     * The groups a chat-list search matches, as iOS's Select All counts them
     * (ChatListView.displayedGroups): the group's name, any member's name you gave them or their
     * address, or any of its messages (up to 4 KB each). Broader than the circles row, which
     * matches the name alone - so Select All while searching picks the same groups on both.
     */
    /** The 1:1 chats one of whose messages matches the Chats search (iOS ChatListView). */
    suspend fun contactIdsWithMessageMatching(query: String): Set<String> {
        val q = query.trim()
        if (q.isEmpty()) return emptySet()
        return runCatching { chatRepository.contactIdsWithMessageContaining(q) }
            .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else emptySet() }
    }

    suspend fun groupIdsMatchingSearch(groups: List<com.kachat.app.models.GroupEntity>, query: String): Set<String> {
        val q = query.trim()
        if (q.isEmpty()) return groups.map { it.groupId }.toSet()
        val aliases = chatRepository.getContacts().first().associate { it.id to it.alias }
        return groups.filter { group ->
            group.name.contains(q, ignoreCase = true) ||
                groupRepository.membersOf(group).any { member ->
                    aliases[member.address]?.contains(q, ignoreCase = true) == true ||
                        member.address.contains(q, ignoreCase = true)
                } ||
                groupRepository.anyMessageContains(group.groupId, q)
        }.map { it.groupId }.toSet()
    }

    /**
     * One-shot KNS resolve for a single group-member address row - unlike
     * [onCreateChatAddressChanged]/[knsResolvedAddress] (single shared StateFlow, fine for the
     * one-address Create Chat flow), the group member list can have up to 10 rows resolving
     * concurrently, so each row owns its own debounce/resolving state locally in Compose and
     * just calls this directly.
     */
    /** What a typed name points to on every service, .kachat first - the primary answer and the
     *  "Other domains" (iOS 6ac48a7). */
    suspend fun resolveEverywhere(input: String): List<com.kachat.app.services.NameResolution> =
        runCatching { nameServices.resolveEverywhere(input) }.getOrDefault(emptyList())

    private val _isCreatingGroup = MutableStateFlow(false)
    val isCreatingGroup: StateFlow<Boolean> = _isCreatingGroup.asStateFlow()

    private val _createGroupError = MutableStateFlow<String?>(null)
    val createGroupError: StateFlow<String?> = _createGroupError.asStateFlow()

    fun clearCreateGroupError() {
        _createGroupError.value = null
    }

    /** Any address not already a contact is auto-added, matching [addContact]'s own-or-create behavior. */
    fun createGroupChat(
        name: String,
        addresses: List<String>,
        /** Compressed JPEG hex picked during creation - pushed once the group has an id. */
        photoHex: String? = null,
        onCreated: (String) -> Unit,
    ) {
        val trimmedName = name.trim()
        val trimmedAddresses = addresses.map { it.trim() }.filter { it.isNotEmpty() }
        if (trimmedName.isEmpty()) {
            _createGroupError.value = "Enter a group name."
            return
        }
        if (trimmedAddresses.isEmpty()) {
            _createGroupError.value = "Add at least one address."
            return
        }
        val invalid = trimmedAddresses.firstOrNull { !com.kachat.app.util.KaspaAddress.isValid(it) }
        if (invalid != null) {
            // iOS AddContactView.createGroupChat: KasiaError.invalidAddress's text.
            _createGroupError.value = "Invalid Kaspa address format."
            return
        }
        // A member is added deliberately, and a deliberate add is of the network the app runs on:
        // the other network's address is the same key on another chain, its chat is never read
        // and is dropped on the next launch (iOS ContactsManager.addContact, IOS-063 - creating
        // the group fails there).
        trimmedAddresses.firstOrNull { !com.kachat.app.util.KaspaAddress.isValidOnActiveNetwork(it) }?.let { other ->
            _createGroupError.value = com.kachat.app.util.KaspaAddress.otherNetworkMessageRes(other)
                ?.let { appContext.getString(it) } ?: "Invalid Kaspa address format."
            return
        }

        _isCreatingGroup.value = true
        _createGroupError.value = null
        viewModelScope.launch {
            try {
                // A member who is not a contact yet is added deliberately: the user typed them
                // in, as iOS's group creation does (addContact without isAutoAdded).
                val contacts = trimmedAddresses.map { address ->
                    chatRepository.getContact(address) ?: ContactEntity(
                        id = address,
                        walletAddress = walletManager.getAddress(),
                        alias = null,
                        knsName = null,
                        publicKeyHex = null
                    ).also { chatRepository.addContact(it, deliberate = true) }
                }
                val group = groupRepository.createGroup(trimmedName, contacts)
                // Best effort: a failed photo send must not undo a group that was created
                // successfully - the admin can set it again from Group Info.
                if (!photoHex.isNullOrEmpty()) {
                    runCatching { groupRepository.setGroupPhoto(group.groupId, photoHex) }
                }
                _isCreatingGroup.value = false
                onCreated(group.groupId)
            } catch (e: Exception) {
                _isCreatingGroup.value = false
                _createGroupError.value = UserFacingError.message(e, "Failed to create group")
            }
        }
    }

    /**
     * Ticks once a minute so expiring system lines drop out of an open thread on their own,
     * rather than on the next unrelated emission.
     */
    private val systemLineClock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            kotlinx.coroutines.delay(60_000)
        }
    }

    fun getGroupMessages(groupId: String) = groupRepository.getMessages(groupId)
        .combine(groupHiddenMembers) { messages, hidden ->
            messages.filter { it.senderAddress == null || "$groupId|${it.senderAddress}" !in hidden }
        }
        .combine(systemLineClock) { messages, now ->
            // Membership/rename/photo lines are news only while they are recent.
            messages.filterNot { it.isExpiredSystemMessage(now) }
        }

    /** Drops expired system lines from the database for one group - called when it is opened. */
    fun pruneExpiredGroupSystemMessages(groupId: String) {
        viewModelScope.launch {
            runCatching { groupRepository.pruneExpiredSystemMessages(groupId) }
        }
    }

    // The group message currently being replied to (long-press menu on its bubble to set this),
    // shown as a banner above the compose field — cleared automatically once the reply sends.
    // Mirrors [_replyingTo]'s shape for 1:1 chats.
    private val _groupReplyingTo = MutableStateFlow<com.kachat.app.repository.GroupMessage?>(null)
    val groupReplyingTo: StateFlow<com.kachat.app.repository.GroupMessage?> = _groupReplyingTo.asStateFlow()

    fun startGroupReplyTo(message: com.kachat.app.repository.GroupMessage) {
        _groupReplyingTo.value = message
    }

    fun cancelGroupReply() {
        _groupReplyingTo.value = null
    }

    fun getGroupReactions(groupId: String) = groupRepository.getReactions(groupId)

    fun getGroupEdits(groupId: String) = groupRepository.getEdits(groupId)

    // The group message whose text the composer is editing (one of your own), shown as a banner
    // above the compose field. Mutually exclusive with [_groupReplyingTo], as on iOS.
    private val _groupEditingMessage = MutableStateFlow<com.kachat.app.repository.GroupMessage?>(null)
    val groupEditingMessage: StateFlow<com.kachat.app.repository.GroupMessage?> = _groupEditingMessage.asStateFlow()

    fun startGroupEditing(message: com.kachat.app.repository.GroupMessage) {
        _groupReplyingTo.value = null
        _groupEditingMessage.value = message
    }

    fun cancelGroupEditing() {
        _groupEditingMessage.value = null
    }

    /** Edits one of this wallet's own group messages - see [GroupRepository.sendGroupEdit]. */
    fun sendGroupEdit(groupId: String, targetTxId: String, text: String, onError: (String) -> Unit = {}) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        _groupEditingMessage.value = null
        viewModelScope.launch {
            try {
                groupRepository.sendGroupEdit(targetTxId, groupId, clean)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error sending group edit", e)
                onError(UserFacingError.message(e, "Edit failed"))
            }
        }
    }

    /**
     * Reacts to [targetTxId] with [emoji] ("add"), or removes this wallet's existing reaction on
     * it ("remove"). Mirrors [sendReaction] for 1:1 chats - see [GroupRepository.sendGroupReaction].
     */
    fun sendGroupReaction(groupId: String, targetTxId: String, emoji: String, action: String) {
        viewModelScope.launch {
            try {
                groupRepository.sendGroupReaction(targetTxId, groupId, emoji, action)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error sending group reaction", e)
            }
        }
    }

    /** Retries a group reaction whose send previously failed - re-attempts the stored add/remove. */
    fun retryGroupReaction(groupId: String, targetTxId: String, emoji: String, action: String) {
        sendGroupReaction(groupId, targetTxId, emoji, action)
    }

    // -------------------------------------------------------------------------
    // Group hide/mute/mentions-only - mirrors iOS's GroupChatService equivalents.
    // -------------------------------------------------------------------------

    val groupHiddenMembers: StateFlow<Set<String>> = settings.groupHiddenMembers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    val groupMutedMembers: StateFlow<Set<String>> = settings.groupMutedMembers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    val groupMentionsOnly: StateFlow<Set<String>> = settings.groupMentionsOnly
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    val groupSilent: StateFlow<Set<String>> = settings.groupSilent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun isGroupMemberHidden(groupId: String, address: String) = "$groupId|$address" in groupHiddenMembers.value
    fun isGroupMemberMuted(groupId: String, address: String) = "$groupId|$address" in groupMutedMembers.value
    fun isGroupMentionsOnly(groupId: String) = com.kachat.app.util.MentionsFeature.ENABLED && groupId in groupMentionsOnly.value

    /** Groups currently being force-refreshed, so the button can show progress and refuse a second tap. */
    private val _refreshingGroupIds = MutableStateFlow<Set<String>>(emptySet())
    val refreshingGroupIds: StateFlow<Set<String>> = _refreshingGroupIds.asStateFlow()

    /**
     * Live state for [refreshGroup]'s progress sheet: which group, what it is doing, and - once
     * finished - how many messages the rebuild recovered. Null while idle.
     */
    data class GroupRefreshState(
        val groupId: String,
        val phase: com.kachat.app.repository.GroupRepository.GroupRefreshPhase?,
        val recovered: Int? = null,
        val rejections: com.kachat.app.repository.GroupRepository.GroupRefreshRejections? = null,
    ) {
        val label: String
            get() = when (val p = phase) {
                is com.kachat.app.repository.GroupRepository.GroupRefreshPhase.Invites -> "Re-reading your group invites"
                is com.kachat.app.repository.GroupRepository.GroupRefreshPhase.Control -> "Re-reading roster and epoch keys"
                is com.kachat.app.repository.GroupRepository.GroupRefreshPhase.Messages -> "Re-fetching messages (${p.done} of ${p.total} members)"
                is com.kachat.app.repository.GroupRepository.GroupRefreshPhase.Rebuilding -> "Decrypting history"
                null -> "Starting"
            }
    }

    private val _groupRefreshState = MutableStateFlow<GroupRefreshState?>(null)
    val groupRefreshState: StateFlow<GroupRefreshState?> = _groupRefreshState.asStateFlow()

    fun clearGroupRefreshState() { _groupRefreshState.value = null }

    /** Re-fetches one group from the start - see GroupRepository.forceRefreshGroup. */
    fun refreshGroup(groupId: String) {
        if (groupId in _refreshingGroupIds.value) return
        viewModelScope.launch {
            _refreshingGroupIds.value = _refreshingGroupIds.value + groupId
            _groupRefreshState.value = GroupRefreshState(groupId, null)
            try {
                val recovered = groupRepository.forceRefreshGroup(groupId) { phase ->
                    _groupRefreshState.value = GroupRefreshState(groupId, phase)
                }
                _groupRefreshState.value = GroupRefreshState(
                    groupId, null, recovered = maxOf(0, recovered),
                    rejections = groupRepository.lastRefreshRejections,
                )
            } catch (e: Exception) {
                Log.w("ChatViewModel", "Group refresh failed", e)
                _groupRefreshState.value = GroupRefreshState(groupId, null, recovered = 0)
            } finally {
                _refreshingGroupIds.value = _refreshingGroupIds.value - groupId
            }
        }
    }

    fun setGroupSilent(groupId: String, enabled: Boolean) {
        viewModelScope.launch { settings.setGroupSilent(groupId, enabled) }
    }

    /**
     * Group Chats' pins for [walletAddress], newest first (iOS a53d343 `loadPins`). The first
     * time, the groups pinned in the old Chats circles row carry over, in the same order.
     */
    fun groupPins(walletAddress: String): Flow<List<String>> =
        settings.groupPins(walletAddress).map { saved ->
            saved ?: settings.chatCirclePins(walletAddress).first()
                .filter { it.startsWith("g:") }
                .map { it.removePrefix("g:") }
                .also { settings.setGroupPins(walletAddress, it) }
        }

    /** Pins a group (newest pin first) or unpins it; returns whether it is pinned now, for the
     *  toast (iOS a53d343 `togglePin`). */
    suspend fun toggleGroupPin(walletAddress: String, groupId: String): Boolean {
        val pins = groupPins(walletAddress).first()
        val pinned = groupId !in pins
        settings.setGroupPins(walletAddress, if (pinned) listOf(groupId) + pins else pins - groupId)
        return pinned
    }

    /** Deleted groups leave the pins with them (iOS a53d343 `deleteGroups`). */
    suspend fun unpinGroups(walletAddress: String, groupIds: Collection<String>) {
        val pins = groupPins(walletAddress).first()
        if (pins.any { it in groupIds }) settings.setGroupPins(walletAddress, pins.filterNot { it in groupIds })
    }

    /** Takes deleted chats' pins ("c:<address>") out of the Chats circles row (iOS 7abc06e). */
    suspend fun unpinChatCircles(walletAddress: String, ids: Collection<String>) {
        val pins = settings.chatCirclePins(walletAddress).first()
        if (pins.any { it in ids }) settings.setChatCirclePins(walletAddress, pins.filterNot { it in ids })
    }

    /** The Chats circles row's pins for [walletAddress], in order (iOS a062577 `circlePins`). */
    fun chatCirclePins(walletAddress: String): Flow<List<String>> = settings.chatCirclePins(walletAddress)

    /**
     * Pins a circle ("b:<bot>" / "c:<chat>") - newest pin first - or unpins it.
     * Returns whether it is pinned now, for the toast (iOS a062577 `toggleCirclePin`).
     */
    suspend fun toggleChatCirclePin(walletAddress: String, id: String): Boolean {
        val pins = settings.chatCirclePins(walletAddress).first()
        val pinned = id !in pins
        settings.setChatCirclePins(walletAddress, if (pinned) listOf(id) + pins else pins - id)
        return pinned
    }

    fun hideGroupMember(groupId: String, address: String) {
        viewModelScope.launch { settings.hideGroupMember(groupId, address) }
    }

    fun unhideGroupMember(groupId: String, address: String) {
        viewModelScope.launch { settings.unhideGroupMember(groupId, address) }
    }

    fun muteGroupMember(groupId: String, address: String) {
        viewModelScope.launch { settings.muteGroupMember(groupId, address) }
    }

    fun unmuteGroupMember(groupId: String, address: String) {
        viewModelScope.launch { settings.unmuteGroupMember(groupId, address) }
    }

    fun setGroupMentionsOnly(groupId: String, enabled: Boolean) {
        viewModelScope.launch { settings.setGroupMentionsOnly(groupId, enabled) }
    }

    fun markGroupRead(groupId: String) {
        viewModelScope.launch { groupRepository.markGroupRead(groupId) }
    }

    /** Bulk "Mark as Read" for multi-selected groups in the Group Chats tab — mirrors [markContactsAsRead]'s shape. */
    fun markGroupsAsRead(groupIds: Collection<String>) {
        viewModelScope.launch { groupIds.forEach { groupRepository.markGroupRead(it) } }
    }

    /** Bulk "Mark as Unread" for multi-selected groups in the Group Chats tab — mirrors [markContactsAsUnread]'s shape. */
    fun markGroupsAsUnread(groupIds: Collection<String>) {
        viewModelScope.launch { groupIds.forEach { groupRepository.markGroupUnread(it) } }
    }

    fun sendGroupMessage(text: String, groupId: String, onError: (String) -> Unit = {}) {
        val reply = _groupReplyingTo.value
        viewModelScope.launch {
            try {
                // If replying, wrap the content in the shared reply envelope (matches
                // ChatViewModel.sendMessage/BroadcastViewModel's identical wrapping) so the
                // quote survives even if the original message is later pruned.
                val payload = if (reply != null) {
                    val preview = VoiceMessage.parseOrNull(reply.content)?.let { "🎤 Audio message" }
                        ?: ImageMessage.parseOrNull(reply.content)?.let { "📷 Photo" }
                        ?: MessageReply.parseOrNull(reply.content)?.text
                        ?: reply.content
                    MessageReply.encode(replyToId = reply.txId, replyToSender = reply.senderAddress ?: "", replyToPreview = preview, text = text)
                } else {
                    text
                }
                groupRepository.sendGroupMessage(payload, groupId)
                _groupReplyingTo.value = null
            } catch (e: Exception) {
                onError(UserFacingError.message(e, "Failed to send"))
            }
        }
    }

    fun addGroupMember(contact: ContactEntity, groupId: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            try {
                groupRepository.addMember(contact, groupId)
                onResult(true, null)
            } catch (e: Exception) {
                onResult(false, e.message)
            }
        }
    }

    /**
     * Adds several members one at a time - each [GroupRepository.addMember] rotates the epoch and
     * redistributes the new root, so they MUST run sequentially (a concurrent pair would both read
     * the same current epoch and collide). Mirrors iOS's serial add loop. Reports how many were
     * added and how many failed once the whole batch is done.
     */
    /**
     * Takes addresses rather than contacts so someone who is not in the address book can still be
     * invited: a group invite is encrypted to a public key decoded from the address itself, so
     * there is no handshake or prior chat to require. Mints the contact on the way through,
     * exactly as [createGroupChat] does for a brand-new group.
     */
    fun addGroupMembers(
        addresses: List<String>,
        groupId: String,
        /**
         * Fires as each member lands, so the UI can say which one it is on.
         *
         * Every added member rotates the epoch and sends two control transactions to EVERY
         * member, so adding three people to a group of twelve is around eighty transactions,
         * serialised, each waiting for the previous one's change to settle. That is minutes of
         * real work behind what used to be a dialog with no progress and no way out - reported
         * as the screen being stuck, and force-quitting mid-distribution is exactly how a group
         * ends up with some members holding the new root and some the old.
         */
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onResult: (added: Int, failed: Int) -> Unit,
    ) {
        viewModelScope.launch {
            var added = 0
            var failed = 0
            onProgress(0, addresses.size)
            for (address in addresses) {
                try {
                    val contact = chatRepository.getContact(address) ?: ContactEntity(
                        id = address,
                        walletAddress = walletManager.getAddress(),
                        alias = null,
                        knsName = null,
                        publicKeyHex = null
                    ).also { chatRepository.addContact(it) }
                    groupRepository.addMember(contact, groupId)
                    added++
                } catch (e: Exception) { failed++ }
                onProgress(added + failed, addresses.size)
            }
            onResult(added, failed)
        }
    }

    fun removeGroupMember(member: com.kachat.app.models.GroupMember, groupId: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            try {
                groupRepository.removeMember(member, groupId)
                onResult(true, null)
            } catch (e: Exception) {
                onResult(false, e.message)
            }
        }
    }

    fun deleteGroupChat(groupId: String) {
        viewModelScope.launch {
            groupRepository.deleteGroup(groupId)
        }
    }

    /** Multi-select bulk delete — mirrors markGroupsAsRead/markGroupsAsUnread's Collection shape. */
    fun deleteGroupChats(groupIds: Collection<String>) {
        viewModelScope.launch {
            groupIds.forEach { groupRepository.deleteGroup(it) }
        }
    }

    /** Groups this wallet was added to and hasn't answered, newest first - they wait in Message
     *  Requests (iOS `pendingGroupInvites`, bfe9f022). */
    val pendingGroupInvites: StateFlow<List<com.kachat.app.models.PendingGroupInvite>> =
        groupRepository.getPendingGroupInvites()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Joins now; the answer and the group's history follow. A send that can't go yet is retried
     *  at the next catch-up. */
    fun acceptGroupInvite(invite: com.kachat.app.models.PendingGroupInvite) {
        viewModelScope.launch {
            try { groupRepository.acceptGroupInvite(invite) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w("ChatViewModel", "Accepting a group invite failed", e)
            }
        }
    }

    /** Never offered again; the group hears you declined. Retried like an accept. */
    fun rejectGroupInvite(invite: com.kachat.app.models.PendingGroupInvite) {
        viewModelScope.launch {
            try { groupRepository.rejectGroupInvite(invite) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w("ChatViewModel", "Rejecting a group invite failed", e)
            }
        }
    }

    /** Member address -> "invited" or "declined", for a group's member rows. */
    fun groupInviteStatuses(groupId: String) = groupRepository.getGroupInviteStatuses(groupId)

    /** Deletes individual messages from a group, this device only - local-only, never on-chain.
     *  Used by GroupChatThreadScreen's message multi-select "Delete". [groupId] isn't needed by
     *  the delete itself (txId + wallet address alone identify a group message row) but is kept
     *  in the signature to mirror deleteMessages(contactId:)'s 1:1 shape and leave room for a
     *  future per-group scoping need without another signature change. */
    fun deleteGroupMessages(groupId: String, messageIds: Collection<String>) {
        viewModelScope.launch { groupRepository.deleteMessages(messageIds) }
    }

    fun renameGroup(groupId: String, newName: String, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            try {
                groupRepository.renameGroup(groupId, newName)
            } catch (e: Exception) {
                onError(UserFacingError.message(e, "Failed to rename group"))
            }
        }
    }

    /** Re-broadcast the group invite to every member (admin) - retries invites that failed to send. */
    fun resendGroupInvites(groupId: String, onResult: (String) -> Unit = {}) {
        viewModelScope.launch {
            try { groupRepository.resendInvites(groupId); onResult("Invites resent to all members.") }
            catch (e: Exception) { onResult(UserFacingError.message(e, "Failed to resend invites")) }
        }
    }

    /** Admin: set (photoHex = compressed-JPEG hex) or clear (photoHex = "") the group photo. */
    fun setGroupPhoto(groupId: String, photoHex: String, onResult: (String?) -> Unit = {}) {
        viewModelScope.launch {
            try { groupRepository.setGroupPhoto(groupId, photoHex); onResult(null) }
            catch (e: Exception) { onResult(UserFacingError.message(e, "Failed to update group photo")) }
        }
    }

    /** Re-broadcast the group invite to ONE member (admin) - a targeted retry. */
    fun resendGroupInviteToMember(groupId: String, address: String, onResult: (String) -> Unit = {}) {
        viewModelScope.launch {
            try { groupRepository.resendInviteToMember(groupId, address); onResult("Invite resent.") }
            catch (e: Exception) { onResult(UserFacingError.message(e, "Failed to resend invite")) }
        }
    }

    /**
     * Group messages don't have an in-place "retry" record the way 1:1 does - resends the same
     * content (works uniformly for text/photo/audio, since all three are just a content string)
     * as a fresh message rather than mutating the failed one, which stays in history marked failed.
     */
    /** Re-sends a failed group message IN PLACE: the failed row is reused as the pending
     *  placeholder and swapped for the real row, rather than a second bubble appearing next to
     *  the failed one (see GroupRepository.sendGroupMessage's retryOfTxId). */
    fun retryGroupMessage(groupId: String, content: String, failedTxId: String? = null) {
        viewModelScope.launch {
            try {
                groupRepository.sendGroupMessage(content, groupId, retryOfTxId = failedTxId)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error retrying group message", e)
            }
        }
    }

    /** Retry on a group's long voice note or multi-part photo: only its failed parts, in index
     *  order, one after another, stopping at the first that fails again (iOS 7c5c22e3
     *  `retryGroupVoiceNoteParts`). */
    fun retryGroupFailedParts(groupId: String, failedParts: List<com.kachat.app.repository.GroupMessage>) {
        viewModelScope.launch {
            for (part in failedParts) {
                if (!part.isOutgoing || part.deliveryStatus != "failed") continue
                try {
                    groupRepository.sendGroupMessage(part.content, groupId, retryOfTxId = part.txId)
                } catch (e: Exception) {
                    Log.e("ChatViewModel", "Error retrying a group media part", e)
                    com.kachat.app.ui.screens.IosToasts.error(UserFacingError.message(e, "Failed to send"))
                    return@launch
                }
            }
        }
    }

    data class KnsProfileUiState(
        val ownedDomains: List<String> = emptyList(),
        val selectedDomain: String? = null,
        val profile: KnsProfileFields? = null,
        /**
         * The contact's REAL primary, straight from the reverse lookup, as distinct from
         * [selectedDomain], which prefers a domain the user pinned for this chat. The Domains
         * section marks this one, so it agrees with iOS and never presents a local preference,
         * or the mere first domain owned, as the contact's own choice. Null when they have not
         * set one.
         */
        val explicitPrimaryDomain: String? = null
    )

    private val _knsProfiles = MutableStateFlow<Map<String, KnsProfileUiState>>(emptyMap())
    val knsProfiles: StateFlow<Map<String, KnsProfileUiState>> = _knsProfiles.asStateFlow()

    /**
     * Address -> their *explicitly-set* primary KNS domain, or null if they've never set one -
     * unlike [knsProfiles]/[KnsService.reverseResolve], this does NOT fall back to "first owned
     * domain". Drives group chat's @mention autocomplete (only members with an explicit primary
     * are mentionable), populated by [refreshKnsProfilesForGroupMembers].
     */
    private val _groupMemberPrimaryKnsByAddress = MutableStateFlow<Map<String, String?>>(emptyMap())
    val groupMemberPrimaryKnsByAddress: StateFlow<Map<String, String?>> = _groupMemberPrimaryKnsByAddress.asStateFlow()

    /**
     * address -> avatar URL for GROUP members. Group members are usually NOT saved contacts, so
     * their avatar lives only in the address-keyed [knsProfiles] cache (fetched for every member by
     * [refreshKnsProfilesForGroupMembers]), not in the contact-derived [contactAvatarsByAddress].
     * Merge the two - contact-cached avatar wins, the KNS profile avatar fills in the rest - so
     * group chats show avatars for every member like 1:1 does, instead of blank placeholders.
     */
    val groupMemberAvatarsByAddress: StateFlow<Map<String, String?>> =
        combine(contactAvatarsByAddress, knsProfiles) { contacts, kns ->
            val merged = contacts.toMutableMap()
            for ((addr, state) in kns) {
                val url = state.profile?.avatarUrl
                if (!url.isNullOrBlank() && merged[addr].isNullOrBlank()) merged[addr] = url
            }
            merged
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /**
     * address -> display name for GROUP members, resolved with the same priority as 1:1:
     * manual/contact alias first, then the member's KNS primary name (explicit primary, else the
     * active KNS domain resolved into [knsProfiles]). So a non-contact member shows their KNS name
     * instead of a raw address. A blank result falls through to the roster displayName / shortened
     * address at the call site.
     */
    val groupMemberNamesByAddress: StateFlow<Map<String, String>> =
        combine(contactAliasesByAddress, groupMemberPrimaryKnsByAddress, knsProfiles) { aliases, primaryKns, kns ->
            val merged = aliases.toMutableMap()
            // Identity is .kachat (on every network since iOS 7227d69): KNS is not consulted, and
            // the screens add each member's .kachat name when they read this map (liveNameFor;
            // iOS e52357d) - on mainnet, with no launched registry, that is the short address.
            if (com.kachat.app.services.kachatnames.KachatNamesService.isEnabled) return@combine merged
            for (addr in (primaryKns.keys + kns.keys)) {
                if (!merged[addr].isNullOrBlank()) continue
                val name = primaryKns[addr]?.takeIf { it.isNotBlank() }
                    ?: kns[addr]?.selectedDomain?.takeIf { it.isNotBlank() }
                if (name != null) merged[addr] = name
            }
            merged
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /**
     * Fetches this address's owned KNS domains + the active one's profile (avatar/bio/socials).
     * Works for any address, not just a saved 1:1 contact — a broadcast sender viewed via "User
     * Info" may never have a [ContactEntity] row at all, and their KNS profile should still show.
     */
    fun refreshKnsProfile(contactId: String) {
        // Someone's .kas names are not shown since 5.2 (KnsService.SHOWS_DOMAIN_NAMES_AS_IDENTITY):
        // nothing to ask KNS for, and an empty state is what every reader falls back from.
        if (!com.kachat.app.services.KnsService.SHOWS_DOMAIN_NAMES_AS_IDENTITY) {
            _knsProfiles.update { it + (contactId to KnsProfileUiState()) }
            return
        }
        viewModelScope.launch { loadKnsProfile(contactId) }
    }

    /**
     * [refreshKnsProfile], awaited: the address's .kas profile asked for again now (User Info >
     * Rescan Profile, iOS bc95d17 `fetchProfile(force: true)`). Returns the profile, null when it
     * has none or names are not shown.
     */
    suspend fun refreshKnsProfileNow(contactId: String): KnsProfileFields? {
        if (!com.kachat.app.services.KnsService.SHOWS_DOMAIN_NAMES_AS_IDENTITY) {
            _knsProfiles.update { it + (contactId to KnsProfileUiState()) }
            return null
        }
        return loadKnsProfile(contactId)
    }

    private suspend fun loadKnsProfile(contactId: String): KnsProfileFields? {
        val contact = chatRepository.getContact(contactId)
        val ownedAssets = knsService.getOwnedDomains(contactId)
        val ownedNames = ownedAssets.mapNotNull { it.asset }

        if (ownedNames.isEmpty()) {
            _knsProfiles.update { it + (contactId to KnsProfileUiState()) }
            return null
        }

        // The real answer, before pickActiveDomain folds in the user's pinned choice.
        val explicitPrimary = knsService.getExplicitPrimaryDomain(contactId)
        val primary = explicitPrimary ?: ownedAssets.firstOrNull()?.asset
        val activeName = KnsService.pickActiveDomain(ownedNames, contact?.knsName, primary)
        val activeAsset = ownedAssets.firstOrNull { it.asset == activeName }
        val profile = activeAsset?.assetId?.let { knsService.getProfile(it) }

        _knsProfiles.update { it + (contactId to KnsProfileUiState(ownedNames, activeName, profile, explicitPrimary)) }

        // Keep the chat list's cached avatar current — only meaningful for an actual saved contact.
        if (contact != null && profile?.avatarUrl != contact.knsAvatarUrl) {
            chatRepository.addContact(contact.copy(knsAvatarUrl = profile?.avatarUrl))
        }
        return profile
    }

    /**
     * Records each contact's primary KNS domain, if they have one — matches iOS's
     * fetchKNSDomainsForAllContacts, run whenever the chat list appears. It writes only
     * `knsName`; `ContactEntity.displayName` reads it, so nothing here NAMES a contact.
     * Never overwrites a real custom nickname the user typed in, never
     * overwrites a contact linked to a system (phone) contact, and never overwrites a
     * domain the user explicitly picked in Chat Info (tracked via `knsName` — once set,
     * that choice is pinned until the user changes it themselves, even if the contact's
     * on-chain primary is or becomes something else).
     */
    fun refreshKnsNamesForAllContacts(force: Boolean = false) {
        // Debounced, and the stamp is PERSISTED. Every contact costs two uncached KNS calls
        // (getPrimaryName, then assets-by-owner), and this runs whenever the chat list appears -
        // so without a guard, opening the app walked the whole address book again every single
        // time, including on a cold start seconds after the names were read from the database
        // where they already live. An in-memory guard would still have re-walked on every
        // launch, which is exactly the case worth stopping.
        // Every result of this sweep is a .kas name to show for a contact, which 5.2 stopped doing
        // (KnsService.SHOWS_DOMAIN_NAMES_AS_IDENTITY), so it no longer asks KNS at all.
        if (!com.kachat.app.services.KnsService.SHOWS_DOMAIN_NAMES_AS_IDENTITY) return
        val prefs = appContext.getSharedPreferences("kns_sweep", Context.MODE_PRIVATE)
        val last = prefs.getLong(KNS_NAME_SWEEP_KEY, 0L)
        val now = System.currentTimeMillis()
        if (!force && now - last < KNS_NAME_SWEEP_INTERVAL_MS) return
        if (!knsNameSweepRunning.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
            val contacts = chatRepository.getContacts().first()
            for (contact in contacts) {
                if (!canAutoUpdateAliasToDomain(contact.alias, contact.knsName)) continue
                val primary = knsService.reverseResolve(contact.id) ?: continue
                if (primary != contact.knsName) {
                    chatRepository.addContact(contact.copy(knsName = primary))
                }
            }
            prefs.edit().putLong(KNS_NAME_SWEEP_KEY, System.currentTimeMillis()).apply()
            } finally {
                knsNameSweepRunning.set(false)
            }
        }
    }

    /**
     * Fills in every chat's cached KNS avatar, in bounded-concurrency batches. Run whenever the
     * chat list appears, alongside the name refresh - matches iOS's `preloadAvatarsForAllChats`.
     *
     * Without this the ONLY thing writing `knsAvatarUrl` was [refreshKnsProfile], which runs when
     * you open a specific thread. So a freshly imported wallet showed initials for every chat
     * until you had opened each one individually - the avatars were never missing, just never
     * asked for.
     *
     * Skips contacts that already have one: this runs on every appearance, and re-resolving a
     * cached avatar on each visit is a round trip per contact for an answer that rarely changes.
     */
    fun refreshKnsAvatarsForAllContacts() {
        // .kas profiles are not shown since 5.2 (KnsService.LOADS_DOMAIN_PROFILES).
        if (!com.kachat.app.services.KnsService.LOADS_DOMAIN_PROFILES) return
        viewModelScope.launch {
            val contacts = chatRepository.getContacts().first()
            for (contact in contacts) {
                // Skip ones that already have an avatar: this runs on every appearance, and
                // re-resolving a cached answer per contact per visit is a round trip for
                // something that rarely changes.
                if (!contact.knsAvatarUrl.isNullOrBlank()) continue
                val assets = try {
                    knsService.getOwnedDomainsCached(contact.id)
                } catch (e: Exception) {
                    continue
                }
                if (assets.isEmpty()) continue
                val primary = try { knsService.getExplicitPrimaryDomain(contact.id) } catch (e: Exception) { null }
                // KnsAsset.asset is nullable on the wire, so filter rather than force it.
                val names = assets.mapNotNull { it.asset }
                if (names.isEmpty()) continue
                val active = KnsService.pickActiveDomain(names, contact.knsName, primary ?: names.first())
                val assetId = assets.firstOrNull { it.asset == active }?.assetId ?: continue
                val avatar = try { knsService.getProfile(assetId)?.avatarUrl } catch (e: Exception) { null }
                if (!avatar.isNullOrBlank() && avatar != contact.knsAvatarUrl) {
                    chatRepository.addContact(contact.copy(knsAvatarUrl = avatar))
                }
            }
        }
    }

    private val _knsResolvedAddress = MutableStateFlow<String?>(null)
    val knsResolvedAddress: StateFlow<String?> = _knsResolvedAddress.asStateFlow()

    /** The domain that resolved, as the lookup asked for it ("kaspasilver" typed bare becomes
     *  "kaspasilver.kas") - what the Create Chat screen says it resolved, matching iOS. */
    private val _knsResolvedDomain = MutableStateFlow<String?>(null)
    val knsResolvedDomain: StateFlow<String?> = _knsResolvedDomain.asStateFlow()

    private val _isResolvingKns = MutableStateFlow(false)
    val isResolvingKns: StateFlow<Boolean> = _isResolvingKns.asStateFlow()

    private val _knsError = MutableStateFlow<String?>(null)
    val knsError: StateFlow<String?> = _knsError.asStateFlow()

    private var knsResolveJob: Job? = null

    /** What the typed name points to on every name service (.kachat first), and which one the
     *  chat will use - the priority answer until a different one is picked under "Other
     *  domains" (iOS a0dbc15). */
    private val _nameResolutions = MutableStateFlow<List<com.kachat.app.services.NameResolution>>(emptyList())
    val nameResolutions: StateFlow<List<com.kachat.app.services.NameResolution>> = _nameResolutions.asStateFlow()
    private val _selectedResolutionTld = MutableStateFlow<com.kachat.app.services.NameServiceTLD?>(null)
    val selectedResolutionTld: StateFlow<com.kachat.app.services.NameServiceTLD?> = _selectedResolutionTld.asStateFlow()

    /** Call on every keystroke in the Create Chat address field - debounces, then looks a name
     *  (with or without its ending) up on every service at once. The chat goes to the priority
     *  answer - the ending typed, else .kachat, then .kas, .k, .kaspa - and the others are listed
     *  under "Other domains" to pick instead. */
    fun onCreateChatAddressChanged(input: String) {
        knsResolveJob?.cancel()
        _knsResolvedAddress.value = null
        _knsResolvedDomain.value = null
        _knsError.value = null
        _nameResolutions.value = emptyList()
        _selectedResolutionTld.value = null

        val typed = input.trim()
        if (!com.kachat.app.services.NameServicesClient.looksLikeName(typed)) {
            _isResolvingKns.value = false
            return
        }

        _isResolvingKns.value = true
        knsResolveJob = viewModelScope.launch {
            // Same 300ms debounce iOS types against.
            delay(300)
            val results = nameServices.resolveEverywhere(typed)
            _nameResolutions.value = results
            _isResolvingKns.value = false
            val primary = com.kachat.app.services.NameServicesClient.primary(results, typed)
            if (primary?.address != null) {
                selectNameResolution(primary)
            } else {
                // Deliberately does NOT set a name. A contact is only ever named when the user
                // types one; display falls through to the domain on its own.
                _knsError.value = com.kachat.app.services.NameServicesClient.notFoundMessage(appContext, typed, results)
            }
        }
    }

    /** Points the chat at [resolution] - the priority answer, or one picked under "Other domains". */
    fun selectNameResolution(resolution: com.kachat.app.services.NameResolution) {
        val address = resolution.address ?: return
        _selectedResolutionTld.value = resolution.tld
        _knsResolvedAddress.value = address
        _knsResolvedDomain.value = resolution.display
        _knsError.value = null
    }

    /**
     * Sends an on-chain message via WalletService. Inserts a "pending" placeholder
     * immediately (before the network call) so it shows up in the thread right away;
     * on success that placeholder is swapped for the real message (real tx id), on
     * failure it flips to "failed" in place and stays visible with a Retry option —
     * matches iOS ChatService+Conversations' optimistic send flow.
     */
    // The message currently being replied to (double-tap on its bubble to set this), shown as a
    // banner above the compose field — cleared automatically once the reply actually sends.
    private val _replyingTo = MutableStateFlow<MessageEntity?>(null)
    val replyingTo: StateFlow<MessageEntity?> = _replyingTo.asStateFlow()

    fun startReplyTo(message: MessageEntity) {
        _replyingTo.value = message
    }

    fun cancelReply() {
        _replyingTo.value = null
    }

    // The message currently being edited (long-press > Edit on one of your own text bubbles),
    // shown as a banner above the compose field with the message's current text already in it.
    // Mirrors [_replyingTo]'s shape; the two are mutually exclusive, exactly like iOS's inputBar.
    private val _editingMessage = MutableStateFlow<MessageEntity?>(null)
    val editingMessage: StateFlow<MessageEntity?> = _editingMessage.asStateFlow()

    fun startEditing(message: MessageEntity) {
        _replyingTo.value = null
        _editingMessage.value = message
    }

    fun cancelEditing() {
        _editingMessage.value = null
    }

    /**
     * Edits one of this wallet's own text messages: applied locally at once (pending), then sent
     * as an edit envelope through the same [WalletService.sendKasiaMessage] pipeline a reaction
     * uses - never a visible bubble of its own. Sent (green check) or failed (red) follow, and
     * [onError] gets the failure's reason for the same toast a failed send shows.
     */
    fun sendEdit(contactId: String, target: MessageEntity, text: String, onError: (String) -> Unit = {}) {
        val clean = text.trim()
        if (clean.isEmpty() || target.direction != "sent" ||
            !com.kachat.app.util.MessageEdit.isEditable(target.plaintextBody)) return
        _editingMessage.value = null
        viewModelScope.launch {
            val owner = walletManager.getAddress()
            val now = System.currentTimeMillis()
            chatRepository.upsertOwnEdit(target.id, contactId, clean, null, now, deliveryStatus = "pending")
            try {
                val payload = com.kachat.app.util.MessageEdit.encode(target.id, clean)
                val result = walletService.sendKasiaMessage(contactId, payload)
                // The send can outlast the account; its results don't go in the next one (iOS 4f8e14bb).
                if (walletManager.getAddress() != owner) return@launch
                chatRepository.upsertOwnEdit(target.id, contactId, clean, result.txId, now, deliveryStatus = "sent")
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Edit of ${target.id.take(12)} failed: ${e.message}", e)
                if (walletManager.getAddress() != owner) return@launch
                chatRepository.upsertOwnEdit(target.id, contactId, clean, null, now, deliveryStatus = "failed")
                onError(UserFacingError.message(e, "Edit failed"))
            }
        }
    }

    /** Retries an edit whose send previously failed - re-sends the stored text. */
    fun retryEdit(contactId: String, target: MessageEntity, text: String, onError: (String) -> Unit = {}) {
        sendEdit(contactId, target, text, onError)
    }

    /** [onError] gets the failure's reason - the chat passes its "Failed to Send" alert, as
     *  iOS's composer does; chess moves keep their own failed-move handling. */
    fun sendMessage(contactId: String, text: String, onError: ((String) -> Unit)? = null) {
        if (text.isEmpty()) return
        viewModelScope.launch { sendMessageAwait(contactId, text, onError) }
    }

    /** The composer's send: a failure raises the chat's "Failed to Send" alert, as on iOS. */
    fun sendComposerMessage(contactId: String, text: String) = sendMessage(contactId, text, sendFailureAlert(contactId))

    /**
     * The actual 1:1 send, awaitable and reporting success — [sendMessage] is the fire-and-forget
     * wrapper every composer uses, while the share-sheet compose sheet needs the outcome so it can
     * show progress/success and fall back to staging a draft when the send fails.
     */
    suspend fun sendMessageAwait(
        contactId: String,
        text: String,
        onError: ((String) -> Unit)? = null,
        /** Sent as is, outside the composer: not as a reply to the message being replied to, and
         *  without the composer's fee override (a payment request sent from the + sheet, iOS
         *  6594936 `sendMessage(to:content:feeOverride: nil)`). */
        plain: Boolean = false,
    ): Boolean {
        if (text.isEmpty()) return false
        // Sending bumps this conversation to the top of the recency order the system share sheet
        // mirrors — promote it explicitly since the conversations flow only reorders after the
        // pending insert lands. Diffed inside refresh(), so repeat sends to the same top chat are free.
        shareShortcutsManager.refresh(conversations.value, promoteContactId = contactId)
        val reply = if (plain) null else _replyingTo.value
        val feeRate = if (plain) null else _feeRateOverride.value
        if (!plain) _feeRateOverride.value = null
        run {
            val pendingId = "pending_${java.util.UUID.randomUUID()}"
            try {
                val myAddress = walletManager.getAddress()
                val payload = if (reply != null) {
                    val preview = VoiceMessage.parseOrNull(reply.plaintextBody)?.let { "🎤 Audio message" }
                        ?: ImageMessage.parseOrNull(reply.plaintextBody)?.let { "📷 Photo" }
                        // Replying to a message that's itself a reply — unwrap to its actual text
                        // rather than showing the inner reply's raw JSON as the preview.
                        ?: MessageReply.parseOrNull(reply.plaintextBody)?.text
                        ?: (reply.plaintextBody ?: "")
                    val replyToSender = if (reply.direction == "sent") myAddress else contactId
                    MessageReply.encode(replyToId = reply.id, replyToSender = replyToSender, replyToPreview = preview, text = text)
                } else {
                    text
                }
                chatRepository.insertMessage(
                    MessageEntity(
                        id = pendingId,
                        contactId = contactId,
                        walletAddress = myAddress,
                        type = com.kachat.app.util.MessageProtocol.TYPE_COMM,
                        direction = "sent",
                        plaintextBody = payload,
                        encryptedPayload = "",
                        amountSompi = 0,
                        blockTimestamp = System.currentTimeMillis(),
                        deliveryStatus = "pending"
                    )
                )

                // Encrypt + self-stash the message. Sending a message NEVER sends a handshake —
                // handshakes are only ever sent by an explicit user tap (sendHandshakeToNewContact).
                refuseIfAccountChanged(myAddress)
                val result = walletService.sendKasiaMessage(contactId, payload, feeRateOverride = feeRate)

                // Merge-aware finalize: if a backup mirror import inserted the txId row first,
                // this merges into it (never a second copy) and always removes the placeholder.
                chatRepository.finalizeProvisionalMessage(
                    pendingId,
                    MessageEntity(
                        id = result.txId,
                        contactId = contactId,
                        walletAddress = myAddress,
                        type = com.kachat.app.util.MessageProtocol.TYPE_COMM,
                        direction = "sent",
                        plaintextBody = payload,
                        encryptedPayload = result.payloadHex,
                        amountSompi = 0,
                        blockTimestamp = System.currentTimeMillis(),
                        deliveryStatus = "sent"
                    )
                )
                if (!plain) _replyingTo.value = null
                return true
            } catch (unknown: com.kachat.app.services.SendOutcomeUnknownException) {
                // It may have landed: never Retry (that could post it twice). The row takes the
                // transaction's own id and shows Not confirmed until its delivered copy arrives
                // (iOS d23e6f7a markSendUnconfirmed).
                Log.w("ChatViewModel", "Message send outcome unknown: marked not confirmed")
                finalizeUnconfirmedSend(pendingId, unknown.txId)
                onError?.invoke(UserFacingError.message(unknown, "Failed to send"))
                return false
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error sending message", e)
                chatRepository.updateMessageStatus(pendingId, "failed")
                onError?.invoke(UserFacingError.message(e, "Failed to send"))
                return false
            }
        }
    }

    /**
     * The share-sheet compose sheet's direct send: the (possibly edited) text first, then every
     * shared image, each as its own message through the exact same 1:1 pipeline the composer uses
     * (so Nextcloud media-send, photo quality presets, pending bubbles and delivery status all
     * behave identically). Returns true only when every part went out — the sheet stages the text
     * as a draft in that chat otherwise.
     */
    suspend fun sendSharedContent(contactId: String, text: String, imageUris: List<Uri>): Boolean {
        var allOk = true
        if (text.isNotBlank() && !sendMessageAwait(contactId, text)) allOk = false
        for (uri in imageUris) {
            val bytes = prepareSharedImage(uri)
            if (bytes == null || !sendImageAwait(contactId, bytes, onError = null)) allOk = false
        }
        return allOk
    }

    /** One shared image as it goes when sent straight away: on chain at Low (iOS 7c5c22e3 - a
     *  share opened in the composer asks for a quality at Send instead). Shared, pasted and
     *  dropped photos go on chain - the on-chain-or-Nextcloud choice is only asked from the "+"
     *  sheet now that the "Send Media via Nextcloud" setting is gone (iOS 8b13460). */
    private suspend fun prepareSharedImage(uri: Uri): ByteArray? {
        return try {
            withContext(Dispatchers.Default) {
                ImagePrep.prepareForTier(appContext, uri, com.kachat.app.util.PhotoTier.LOW, forGroup = false).bytes
            }
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Error preparing shared photo", e)
            null
        }
    }

    /**
     * Reacts to [targetTxId] with [emoji] ("add"), or removes the caller's existing reaction on
     * it ("remove"). Unlike [sendMessage], this never creates a visible pending bubble - the
     * reaction is applied to the local reactions table immediately (optimistic UI) and the actual
     * send happens in the background via the same [WalletService.sendKasiaMessage] pipeline any
     * other message uses.
     */
    /** [onError] gets the failure's reason. The pill already turns red with a Retry, but the
     *  error itself was only logged, so a reaction failing instantly on every attempt gave
     *  nothing to go on - the thread shows it in the same toast a failed send uses (iOS). */
    fun sendReaction(contactId: String, targetTxId: String, emoji: String, action: String, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            val myAddress = walletManager.getAddress()
            if (action == "add") {
                // Optimistically pending (no icon) - flips to sent (green checkmark) once the send
                // succeeds below, or failed (red error + Retry) in the catch.
                chatRepository.upsertReaction(targetTxId, myAddress, contactId, emoji, null, System.currentTimeMillis(), deliveryStatus = "pending")
            } else {
                chatRepository.removeReaction(targetTxId, myAddress)
            }
            try {
                val payload = MessageReaction.encode(targetTxId, emoji, action)
                val result = walletService.sendKasiaMessage(contactId, payload)
                // The send can outlast the account; its results don't go in the next one (iOS 4f8e14bb).
                if (walletManager.getAddress() != myAddress) return@launch
                if (action == "add") {
                    chatRepository.upsertReaction(targetTxId, myAddress, contactId, emoji, result.txId, System.currentTimeMillis())
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Reaction $action $emoji on ${targetTxId.take(12)} failed: ${e.message}", e)
                if (walletManager.getAddress() != myAddress) return@launch
                // Flag failed so the pill shows the red error icon and a Retry appears under the
                // message. A failed "remove" restores the optimistically-deleted reaction (marked
                // failed) so it isn't silently lost; Retry re-attempts the change.
                chatRepository.upsertReaction(targetTxId, myAddress, contactId, emoji, null, System.currentTimeMillis(), deliveryStatus = "failed", failedAction = action)
                onError(UserFacingError.message(e, "Reaction failed"))
            }
        }
    }

    /** Retries a 1:1 reaction whose send previously failed - re-attempts the stored add/remove. */
    fun retryReaction(contactId: String, targetTxId: String, emoji: String, action: String, onError: (String) -> Unit = {}) {
        sendReaction(contactId, targetTxId, emoji, action, onError)
    }

    // -------------------------------------------------------------------------
    // Chess — "Play Chess" 1:1 feature. Each action is just a JSON envelope (see ChessMessage.kt)
    // sent through the exact same sendMessage() pipeline as text, the same way VoiceMessage/
    // ImageMessage don't have their own send path either. `cancelReply()` first since these
    // aren't things you'd ever want wrapped in an unrelated pending reply.
    // -------------------------------------------------------------------------

    /** Starting a new game always supersedes whichever one is currently active against this
     *  contact - only one active chess game per contact is allowed, so an existing in-progress/
     *  pending-response game is auto-resigned first rather than left orphaned alongside a second
     *  one. Looks up the contact's messages fresh (rather than relying on a UI-side cache) so this
     *  check is correct even right after the very latest message. */
    fun startChessGame(contactId: String, tcMinutes: Int? = null, tcIncSeconds: Int? = null) {
        cancelReply()
        viewModelScope.launch {
            val myAddress = walletManager.getAddress()
            val messages = chatRepository.getMessages(contactId).first().map { entity ->
                com.kachat.app.util.ChessGameEngine.SimpleChessSourceMessage(
                    id = entity.id,
                    plaintextBody = entity.plaintextBody,
                    isOutgoing = entity.direction == "sent",
                    blockTimestamp = entity.blockTimestamp,
                    sendFailed = entity.direction == "sent" && entity.deliveryStatus == "failed"
                )
            }
            val existing = com.kachat.app.util.ChessGameEngine.activeGame(messages, myAddress, contactId)
            if (existing != null) {
                resignChessGame(contactId, existing.gameId)
            }
            val content = com.kachat.app.util.ChessInviteContent(
                gameId = java.util.UUID.randomUUID().toString(),
                inviterColor = if (kotlin.random.Random.nextBoolean()) com.kachat.app.util.ChessInviteColor.WHITE else com.kachat.app.util.ChessInviteColor.BLACK,
                tcMinutes = tcMinutes,
                tcIncSeconds = tcIncSeconds
            )
            sendMessage(contactId, com.kachat.app.util.ChessMessage.encode(content))
        }
    }

    fun respondToChessInvite(contactId: String, gameId: String, accepted: Boolean) {
        cancelReply()
        val content = com.kachat.app.util.ChessResponseContent(gameId = gameId, accepted = accepted)
        sendMessage(contactId, com.kachat.app.util.ChessMessage.encode(content))
    }

    /** `clockMs`: timed games only - the mover's remaining time in ms after this move, increment
     *  already added (computed by ChessGameScreen from its local thinking-time accumulator). */
    fun sendChessMove(contactId: String, gameId: String, move: com.kachat.app.util.ChessMove, clockMs: Long? = null) {
        cancelReply()
        val content = com.kachat.app.util.ChessMoveContent(
            gameId = gameId,
            from = move.from.algebraic,
            to = move.to.algebraic,
            promotion = move.promotion?.promotionLetter,
            clockMs = clockMs
        )
        sendMessage(contactId, com.kachat.app.util.ChessMessage.encode(content))
    }

    // The timeout resign being sent for each game id, and the one resign row each game's tries
    // reuse. Only ever touched on the main thread (the view model's scope and the board screen).
    private val timeoutResigns = mutableMapOf<String, kotlinx.coroutines.Deferred<Boolean>>()
    private val timeoutResignRows = mutableMapOf<String, String>()
    // iOS's tries; then a minute before the next cycle, where iOS starts one on the next tick.
    private val timeoutResignRetryDelaysSeconds = listOf(0L, 3L, 10L, 30L)
    private val timeoutResignPauseMs = 60_000L

    /**
     * Flagging: my clock hit zero on my turn, so the game is resigned with reason "timeout". A send
     * that fails is tried again, a little later each time: dropped once, the opponent waited on a
     * move that could never come. Runs on the view model's scope, so leaving the screen doesn't
     * stop it (iOS `sendTimeoutResign`).
     *
     * One send per game: a second call while one is running (the board reopened, the clock
     * re-firing) gets the same one. Every try reuses one resign row, so failed tries leave one
     * failed bubble, not one each, and the composer's reply and fee are left alone. It is sent
     * from the account that flagged and stops if another becomes active. A send whose outcome is
     * unknown may have landed, so it counts as sent: resigning twice is worse than once.
     *
     * Completes with true once sent. After a full cycle fails it waits [timeoutResignPauseMs]
     * before completing with false, so the board, while it still shows the clock at zero, starts
     * the next cycle no sooner than that (iOS: the next clock tick after a cycle gives up).
     */
    fun sendTimeoutResign(contactId: String, gameId: String, onSent: () -> Unit): kotlinx.coroutines.Deferred<Boolean> {
        timeoutResigns[gameId]?.takeIf { it.isActive }?.let { return it }
        val resign = viewModelScope.async {
            val account = walletManager.activeAddressFlow.value
            if (account != null) {
                for (delaySeconds in timeoutResignRetryDelaysSeconds) {
                    if (delaySeconds > 0) delay(delaySeconds * 1_000L)
                    if (walletManager.activeAddressFlow.value != account) {
                        Log.w("ChatViewModel", "[Chess] Timeout resign dropped: another account is active")
                        break
                    }
                    if (attemptTimeoutResign(contactId, gameId, account)) {
                        timeoutResignRows.remove(gameId)
                        onSent()
                        return@async true
                    }
                    Log.w("ChatViewModel", "[Chess] Timeout resign didn't send")
                }
            }
            delay(timeoutResignPauseMs)
            false
        }
        timeoutResigns[gameId] = resign
        return resign
    }

    /** One try of [sendTimeoutResign]: the game's resign row goes (back) to pending and is sent. */
    private suspend fun attemptTimeoutResign(contactId: String, gameId: String, account: String): Boolean {
        val payload = com.kachat.app.util.ChessMessage.encode(
            com.kachat.app.util.ChessResignContent(gameId = gameId, reason = "timeout")
        )
        var rowId: String? = null
        return try {
            // The row a previous try left, unless it has gone since (deleted, or sent by Retry).
            val previous = timeoutResignRows[gameId]?.takeIf { chatRepository.getMessage(it) != null }
            val id = previous ?: "pending_${java.util.UUID.randomUUID()}"
            rowId = id
            if (previous != null) {
                chatRepository.updateMessageStatus(id, "pending")
            } else {
                chatRepository.insertMessage(
                    MessageEntity(
                        id = id,
                        contactId = contactId,
                        walletAddress = account,
                        type = com.kachat.app.util.MessageProtocol.TYPE_COMM,
                        direction = "sent",
                        plaintextBody = payload,
                        encryptedPayload = "",
                        amountSompi = 0,
                        blockTimestamp = System.currentTimeMillis(),
                        deliveryStatus = "pending"
                    )
                )
                timeoutResignRows[gameId] = id
            }
            var outcomeUnknown = false
            val (txId, payloadHex) = try {
                walletService.sendKasiaMessage(contactId, payload).let { it.txId to it.payloadHex }
            } catch (unknown: com.kachat.app.services.SendOutcomeUnknownException) {
                // It may have landed (audit IOS-014): counted as sent, so no second resign goes
                // after it, but its row says Not confirmed rather than Sent (iOS d23e6f7a).
                Log.w("ChatViewModel", "[Chess] Timeout resign outcome unknown, counted as sent")
                outcomeUnknown = true
                unknown.txId to ""
            }
            chatRepository.finalizeProvisionalMessage(
                id,
                MessageEntity(
                    id = txId,
                    contactId = contactId,
                    walletAddress = account,
                    type = com.kachat.app.util.MessageProtocol.TYPE_COMM,
                    direction = "sent",
                    plaintextBody = payload,
                    encryptedPayload = payloadHex,
                    amountSompi = 0,
                    blockTimestamp = System.currentTimeMillis(),
                    deliveryStatus = if (outcomeUnknown) "warning" else "sent"
                )
            )
            true
        } catch (e: Exception) {
            Log.e("ChatViewModel", "[Chess] Error sending timeout resign", e)
            rowId?.let { chatRepository.updateMessageStatus(it, "failed") }
            false
        }
    }

    /** `reason`: "timeout" when the local player flagged, null for a manual resign.
     *  [onResult] is (sent, errorMessage): the board leaves only once the resignation is out (iOS
     *  d23e6f7a `resign()`); a failed one keeps the player there with the error, and its row offers
     *  Retry. */
    fun resignChessGame(
        contactId: String,
        gameId: String,
        reason: String? = null,
        onResult: ((Boolean, String?) -> Unit)? = null,
    ) {
        cancelReply()
        val content = com.kachat.app.util.ChessResignContent(gameId = gameId, reason = reason)
        viewModelScope.launch {
            var error: String? = null
            val sent = sendMessageAwait(contactId, com.kachat.app.util.ChessMessage.encode(content), onError = { error = it })
            onResult?.invoke(sent, error)
        }
    }

    // -------------------------------------------------------------------------
    // Voice messages — recorded as Opus-in-WebM, then sent through the exact same
    // sendMessage() pipeline as text: the entire encoded audio is embedded as base64 in the
    // message content JSON, encrypted, and put on-chain like any other message. No separate
    // upload/transport, matching iOS.
    // -------------------------------------------------------------------------

    /** Recording (not playback) needs Android 10+ — the mic button should be disabled below that. */
    val voiceRecordingSupported: Boolean get() = voiceRecorderService.isSupported

    /** The chat the 1:1 voice note is being recorded in: where a failure is reported. */
    private var voiceContactId: String? = null

    /** The 1:1 composer's voice note (iOS 7c5c22e3 / ca013fc8): on chain up to 2:50 as parts of
     *  at most 14.5 s ([VoiceNotePolicy.ONE_TO_ONE]), via Nextcloud up to 5:00. */
    private val voiceComposer by lazy {
        VoiceNoteComposer(
            scope = viewModelScope,
            recorder = voiceRecorderService,
            cacheDir = appContext.cacheDir,
            path = com.kachat.app.util.VoiceNotePolicy.ONE_TO_ONE,
            state = _voiceRecordingState,
            onFailure = { message -> voiceContactId?.let { reportSendFailure(it, message) } },
        )
    }

    fun startVoiceRecording(contactId: String, viaNextcloud: Boolean = false) {
        if (_voiceRecordingState.value.isActive) return
        _nextcloudVoiceRequested.value = viaNextcloud
        voiceContactId = contactId
        // On chain: up to 2:50 (iOS 7c5c22e3 VoiceNotePolicy); a "Record via Nextcloud" note only
        // needs to fit the server, so it runs to the app-wide Nextcloud ceiling (5 minutes).
        if (!voiceComposer.start(viaNextcloud = voiceViaNextcloud)) _nextcloudVoiceRequested.value = false
    }

    /** The composer's stop button, and the length limit: stops and encodes the note, which is
     *  then ready to preview and send (iOS ca013fc8). */
    fun stopVoiceRecording() = voiceComposer.stop()

    /** Play or pause the ready note's preview. */
    fun toggleVoicePreview() = voiceComposer.togglePreview()

    /** Sends the ready note: its parts on chain, or its upload's link via Nextcloud. */
    fun sendRecordedVoice(contactId: String) {
        val viaNextcloud = _voiceRecordingState.value.viaNextcloud
        val ready = voiceComposer.take() ?: return
        _nextcloudVoiceRequested.value = false
        sendVoiceMessage(contactId, ready, viaNextcloud)
    }

    /** The trash: throws the note away at any stage. */
    fun cancelVoiceRecording() {
        _nextcloudVoiceRequested.value = false
        voiceComposer.cancel()
    }

    override fun onCleared() {
        super.onCleared()
        // Avoid leaking a live recorder, an encode or a preview player if the screen/ViewModel is
        // torn down mid-note.
        if (_voiceRecordingState.value.isActive) voiceComposer.release()
        if (_groupVoiceRecordingState.value.isActive) groupVoiceComposer.release()
    }

    // -------------------------------------------------------------------------
    // Group chat photo/voice - same recording/compression pipeline as 1:1 above, routed through
    // GroupRepository's sendGroupImage/sendGroupAudio (gcomm payload) instead of sendMessage.
    // -------------------------------------------------------------------------

    /** The group composer's voice note, recorded like 1:1's (iOS ca013fc8): on chain up to 1:50
     *  as parts of at most 9.8 s ([VoiceNotePolicy.GROUP]), via Nextcloud up to 5:00. */
    private val groupVoiceComposer by lazy {
        VoiceNoteComposer(
            scope = viewModelScope,
            recorder = voiceRecorderService,
            cacheDir = appContext.cacheDir,
            path = com.kachat.app.util.VoiceNotePolicy.GROUP,
            state = _groupVoiceRecordingState,
            onFailure = { message -> com.kachat.app.ui.screens.IosToasts.error(message) },
        )
    }

    fun startGroupVoiceRecording(groupId: String, viaNextcloud: Boolean = false) {
        if (_groupVoiceRecordingState.value.isActive) return
        _groupNextcloudVoiceRequested.value = viaNextcloud
        if (!groupVoiceComposer.start(viaNextcloud = groupVoiceViaNextcloud)) _groupNextcloudVoiceRequested.value = false
    }

    /** The group composer's stop button, and the length limit: stops and encodes the note. */
    fun stopGroupVoiceRecording() = groupVoiceComposer.stop()

    fun toggleGroupVoicePreview() = groupVoiceComposer.togglePreview()

    /** Group twin of the 1:1 [sendRecordedVoice] (iOS ca013fc8 `takeRecording`): on chain, the
     *  ready note's parts - one untagged message when it is one part, else every part tagged
     *  `voicePart`, in the binary media frame inside gcomm (iOS 7c5c22e3). Recorded "via
     *  Nextcloud" (and still connected), the recorded file (Opus-in-WebM, exactly as captured)
     *  uploads to the server and the group message is just the public share link; an upload or
     *  share failure falls back to the file on chain when one transaction carries it, with a
     *  toast so the sender knows. */
    fun sendRecordedGroupVoice(groupId: String) {
        val viaNextcloud = _groupVoiceRecordingState.value.viaNextcloud
        val ready = groupVoiceComposer.take() ?: return
        _groupNextcloudVoiceRequested.value = false
        if (ready is VoiceNoteComposer.Ready.OnChain) {
            viewModelScope.launch {
                try {
                    if (ready.parts.size > 1) {
                        groupRepository.sendGroupMediaParts(ready.parts, groupId, mimeType = "audio/webm")
                    } else {
                        groupRepository.sendGroupAudio(ready.parts.first(), groupId)
                    }
                } catch (e: Exception) {
                    Log.e("ChatViewModel", "Error sending group voice message", e)
                    com.kachat.app.ui.screens.IosToasts.error(UserFacingError.message(e, "Failed to send"))
                }
            }
            return
        }
        val file = (ready as VoiceNoteComposer.Ready.Nextcloud).file
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                if (viaNextcloud) {
                    try {
                        val extension = file.extension.ifEmpty { "webm" }
                        val mimeType = when (extension.lowercase()) {
                            "webm" -> "audio/webm"
                            "ogg", "opus" -> "audio/ogg"
                            "m4a", "mp4" -> "audio/mp4"
                            else -> "application/octet-stream"
                        }
                        // The recorder already names files voice_<timestamp>.webm — keep that name.
                        val url = nextcloudService.uploadMediaAndShare(bytes, file.name, mimeType)
                        groupRepository.sendGroupMessage(url, groupId)
                        return@launch
                    } catch (e: Exception) {
                        Log.w("ChatViewModel", "Nextcloud group voice upload failed, falling back to on-chain send", e)
                        com.kachat.app.ui.screens.IosToasts.error(appContext.getString(com.kachat.app.R.string.nextcloud_upload_failed_sending_on_chain))
                    }
                }
                // On chain only as one ordinary note that one transaction carries.
                if (bytes.size > com.kachat.app.util.VoiceNotePolicy.GROUP.maxBytes) {
                    throw IllegalStateException("Recording failed. Please try again.")
                }
                groupRepository.sendGroupAudio(bytes, groupId, fileName = file.name)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error sending group voice message", e)
            } finally {
                file.delete()
            }
        }
    }

    /** The trash: throws the group note away at any stage. */
    fun cancelGroupVoiceRecording() {
        _groupNextcloudVoiceRequested.value = false
        groupVoiceComposer.cancel()
    }

    /** The 1:1 and group composers' pending photos compressed for the quality sheet (iOS
     *  7c5c22e3 `preparePhotoTiers`), at each path's byte budgets. */
    private val photoTierPrep by lazy { PhotoTierPrep(viewModelScope, appContext, forGroup = false) }
    private val groupPhotoTierPrep by lazy { PhotoTierPrep(viewModelScope, appContext, forGroup = true) }

    /** Each tier's fee for the 1:1 pending photo, once compressed - the quality sheet's numbers. */
    val photoTierFees: StateFlow<Map<com.kachat.app.util.PhotoTier, Long>> get() = photoTierPrep.fees
    val groupPhotoTierFees: StateFlow<Map<com.kachat.app.util.PhotoTier, Long>> get() = groupPhotoTierPrep.fees

    /** The 1:1 pending photo goes on chain - no Nextcloud upload for it, or the upload failed -
     *  so Send asks for a quality first (iOS 7c5c22e3 `pendingPhotoGoesOnChain`). */
    val pendingPhotoGoesOnChain: Boolean
        get() = nextcloudStager.staged.value.let { it == null || it.phase is NextcloudStagedMedia.Phase.Failed }
    val groupPendingPhotoGoesOnChain: Boolean
        get() = groupNextcloudStager.staged.value.let { it == null || it.phase is NextcloudStagedMedia.Phase.Failed }

    /** Starts compressing the 1:1 pending photo for every tier as the quality sheet opens. */
    fun preparePhotoTiers() {
        _pendingPhotoUri.value?.let { photoTierPrep.prepare(it) }
    }

    fun prepareGroupPhotoTiers() {
        _groupPendingPhotoUri.value?.let { groupPhotoTierPrep.prepare(it) }
    }

    /** Mirrors [sendPendingPhoto] for the group's own staged photo, at the group tier budgets
     *  (iOS 7c5c22e3: the `gcomm` payload carries the photo's frame as hex).
     *
     *  Same Nextcloud route as 1:1's [sendPendingPhoto]: a photo or video staged "via Nextcloud"
     *  has been uploading since it was staged, and the group message is its share link once that
     *  is back; a photo whose upload failed goes on chain at [tier]. */
    fun sendPendingGroupPhoto(groupId: String, tier: com.kachat.app.util.PhotoTier = com.kachat.app.util.PhotoTier.LOW) {
        val uri = _groupPendingPhotoUri.value ?: return
        val staged = groupNextcloudStager.staged.value
        // The send button spins until the share link is back.
        if (staged?.isUploading == true) return
        val shareUrl = (staged?.phase as? NextcloudStagedMedia.Phase.Ready)?.url
        groupNextcloudStager.cancel()
        _groupPendingPhotoUri.value = null
        _groupNextcloudPhotoRequested.value = false
        viewModelScope.launch {
            if (shareUrl != null) {
                try {
                    groupRepository.sendGroupMessage(shareUrl, groupId)
                } catch (e: Exception) {
                    Log.e("ChatViewModel", "Error sending a Nextcloud link to a group", e)
                }
                return@launch
            }
            try {
                // The chosen tier's photo; bigger than one transaction, it goes as a multi-part
                // photo (GroupRepository.sendGroupImage).
                val bytes = groupPhotoTierPrep.dataFor(uri, tier)
                groupPhotoTierPrep.clear()
                groupRepository.sendGroupImage(bytes, groupId, fileName = "photo.jpg", mimeType = "image/jpeg")
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error sending group photo message", e)
                com.kachat.app.ui.screens.IosToasts.error(UserFacingError.message(e, "Failed to send"))
            }
        }
    }

    /** Compresses and sends the currently staged [pendingPhotoUri] — clears the staged photo either way, matching the picker-cancel UX (a failed compression just drops back to the empty input bar, same as [sendVoiceMessage] logging and moving on rather than surfacing a dedicated error).
     *
     *  A photo or video staged "via Nextcloud" from the "+" sheet has been uploading at full
     *  quality since it was staged ([nextcloudStaged], iOS 7abc06e); once the share link is back
     *  the chat message is just that link - the recipient's link preview renders it as a photo or
     *  video bubble. A photo whose upload failed goes on chain at [tier] (iOS 7c5c22e3: the quality
     *  sheet asks first), as several tagged parts when it is bigger than one transaction. */
    fun sendPendingPhoto(contactId: String, tier: com.kachat.app.util.PhotoTier = com.kachat.app.util.PhotoTier.LOW) {
        val uri = _pendingPhotoUri.value ?: return
        val staged = nextcloudStager.staged.value
        // The send button spins until the share link is back.
        if (staged?.isUploading == true) return
        val shareUrl = (staged?.phase as? NextcloudStagedMedia.Phase.Ready)?.url
        nextcloudStager.cancel()
        _pendingPhotoUri.value = null
        _nextcloudPhotoRequested.value = false
        viewModelScope.launch {
            if (shareUrl != null) {
                sendMessage(contactId, shareUrl, sendFailureAlert(contactId))
                return@launch
            }
            try {
                // On chain, the chosen tier's photo: compressed already for the sheet, else now.
                val bytes = photoTierPrep.dataFor(uri, tier)
                photoTierPrep.clear()
                sendImageAwait(contactId, bytes, sendFailureAlert(contactId))
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error preparing photo message", e)
                reportSendFailure(contactId, "Couldn't load that photo. Please try another.")
            }
        }
    }

    /**
     * Uploads a picked photo or video to the Nextcloud media folder and returns its share link,
     * which the recipient's link preview renders as a photo or video (iOS a890102). A photo goes
     * up as the ORIGINAL picked image, at full quality; a video is streamed from the content
     * provider into the PUT - a phone video can be far larger than is sensible to hold in memory.
     */
    private suspend fun uploadMediaToNextcloud(uri: Uri, isVideo: Boolean): String = withContext(Dispatchers.IO) {
        val resolver = appContext.contentResolver
        if (!isVideo) {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw java.io.IOException("Could not read the selected photo.")
            val mimeType = resolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
            val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "jpg"
            return@withContext nextcloudService.uploadMediaAndShare(bytes, "photo_${System.currentTimeMillis()}.$extension", mimeType)
        }
        val mimeType = resolver.getType(uri)?.takeIf { it.startsWith("video/") } ?: "video/mp4"
        val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "mp4"
        val length = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()?.takeIf { it >= 0 } ?: -1L
        val body = object : okhttp3.RequestBody() {
            override fun contentType() = mimeType.toMediaTypeOrNull()
            override fun contentLength(): Long = length
            override fun writeTo(sink: okio.BufferedSink) {
                val input = resolver.openInputStream(uri)
                    ?: throw java.io.IOException("Couldn't load that video. Please try another.")
                input.use { stream -> sink.writeAll(stream.source()) }
            }
        }
        nextcloudService.uploadMediaAndShare(body, "video_${System.currentTimeMillis() / 1000}.$extension")
    }

    /** A ready 1:1 voice note. On chain: one ordinary message when it is one part, else every
     *  part tagged `voicePart` ([sendMediaPartsAwait], iOS 7c5c22e3 `sendVoiceNoteParts`).
     *  Recorded "via Nextcloud", the recorded file (Opus-in-WebM, exactly as captured — no
     *  duration cap pressure from on-chain payload size) uploads to the server and the message
     *  is the share link; a failure falls back to the file on chain, with a toast, when one
     *  transaction carries it. */
    private fun sendVoiceMessage(contactId: String, ready: VoiceNoteComposer.Ready, viaNextcloud: Boolean) {
        if (ready is VoiceNoteComposer.Ready.OnChain) {
            viewModelScope.launch {
                if (ready.parts.size > 1) {
                    sendMediaPartsAwait(contactId, ready.parts, "audio/webm", sendFailureAlert(contactId))
                } else {
                    val bytes = ready.parts.first()
                    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    sendMessageAwait(contactId, VoiceMessage.encode(fileName = "voice.webm", sizeBytes = bytes.size.toLong(), base64Audio = base64), sendFailureAlert(contactId))
                }
            }
            return
        }
        val file = (ready as VoiceNoteComposer.Ready.Nextcloud).file
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                if (viaNextcloud) {
                    try {
                        val extension = file.extension.ifEmpty { "webm" }
                        val mimeType = when (extension.lowercase()) {
                            "webm" -> "audio/webm"
                            "ogg", "opus" -> "audio/ogg"
                            "m4a", "mp4" -> "audio/mp4"
                            else -> "application/octet-stream"
                        }
                        // The recorder already names files voice_<timestamp>.webm — keep that name.
                        val url = nextcloudService.uploadMediaAndShare(bytes, file.name, mimeType)
                        sendMessage(contactId, url, sendFailureAlert(contactId))
                        return@launch
                    } catch (e: Exception) {
                        Log.w("ChatViewModel", "Nextcloud voice upload failed, falling back to on-chain send", e)
                        com.kachat.app.ui.screens.IosToasts.error(appContext.getString(com.kachat.app.R.string.nextcloud_upload_failed_sending_on_chain))
                    }
                }
                // On chain only as one ordinary note that one transaction carries.
                if (bytes.size > com.kachat.app.util.VoiceNotePolicy.ONE_TO_ONE.maxBytes) {
                    throw IllegalStateException("Recording failed. Please try again.")
                }
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val json = VoiceMessage.encode(fileName = file.name, sizeBytes = bytes.size.toLong(), base64Audio = base64)
                sendMessage(contactId, json, sendFailureAlert(contactId))
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error preparing voice message", e)
                reportSendFailure(contactId, "Failed to prepare audio: ${UserFacingError.message(e, "Recording failed. Please try again.")}")
            } finally {
                file.delete()
            }
        }
    }

    /** A JPEG on chain (iOS 7c5c22e3 `sendImage`): one ordinary photo message when it fits one
     *  transaction, else its chunks as one multi-part photo ([sendMediaPartsAwait]). */
    private suspend fun sendImageAwait(contactId: String, bytes: ByteArray, onError: ((String) -> Unit)?, plain: Boolean = false): Boolean {
        val chunks = com.kachat.app.util.PhotoPolicy.chunks(bytes, forGroup = false)
        if (chunks.size > 1) return sendMediaPartsAwait(contactId, chunks, "image/jpeg", onError)
        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        return sendMessageAwait(contactId, ImageMessage.encode(fileName = "photo.jpg", sizeBytes = bytes.size.toLong(), base64Image = base64), onError, plain = plain)
    }

    /**
     * The parts of one long voice note or one multi-part photo (iOS 7c5c22e3 `sendMediaParts`,
     * MESSAGING.md "Long voice notes (multi-part)" / "Photos: quality tiers and multi-part
     * photos"): each part its own ordinary message tagged `voicePart` (audio) or `mediaPart`
     * (image) {id, index, count}, so older clients show the parts in order and this app one.
     *
     * - The balance is checked for ALL parts before anything goes out.
     * - Every part's row is created up front (the one bubble shows Sending), then the parts go
     *   out in index order, one after another, so each spends the previous one's change.
     * - A part that fails stops the rest: they are marked failed too, and Retry on the bubble
     *   resends only the failed parts ([retryFailedParts]).
     * - Parts carry no reply envelope (it would take them off the binary media frame and past
     *   what one transaction carries); a reply being composed is dropped, as after any send.
     */
    suspend fun sendMediaPartsAwait(contactId: String, parts: List<ByteArray>, mimeType: String, onError: ((String) -> Unit)?): Boolean {
        if (parts.size !in 2..com.kachat.app.util.VoicePartTag.MAX_PARTS || parts.any { it.isEmpty() }) {
            onError?.invoke("Audio file is empty")
            return false
        }
        val owner = runCatching { walletManager.getAddress() }.getOrNull() ?: return false
        shareShortcutsManager.refresh(conversations.value, promoteContactId = contactId)
        val feeRate = _feeRateOverride.value
        _feeRateOverride.value = null
        val isPhoto = mimeType.lowercase().startsWith("image/")
        val noteId = com.kachat.app.util.VoicePartTag.newGroupId()
        val contents = parts.mapIndexed { offset, data ->
            val tag = com.kachat.app.util.VoicePartTag.of(noteId, offset + 1, parts.size)!!
            com.kachat.app.util.ContextualMediaFrame.mediaFileJson(
                name = if (isPhoto) tag.photoFileName else tag.fileName,
                size = data.size,
                mimeType = mimeType,
                dataUrlContent = "data:$mimeType;base64,${android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)}",
                voicePart = tag.takeUnless { isPhoto },
                mediaPart = tag.takeIf { isPhoto },
            )
        }
        // The whole note first, before a single part goes out.
        try {
            ensureBalanceForParts(contents.map { content ->
                com.kachat.app.util.MessageProtocol.estimatedCommPayloadSize(
                    com.kachat.app.util.ContextualMediaFrame.frameForEnvelope(content)?.size ?: content.toByteArray(Charsets.UTF_8).size
                )
            }, feeRate)
        } catch (e: Exception) {
            onError?.invoke(UserFacingError.message(e, "Failed to send"))
            return false
        }
        // Every part's row now, a millisecond apart so they keep their order.
        val start = System.currentTimeMillis()
        val pendingIds = contents.mapIndexed { offset, content ->
            val pendingId = "pending_${java.util.UUID.randomUUID()}"
            chatRepository.insertMessage(
                MessageEntity(
                    id = pendingId,
                    contactId = contactId,
                    walletAddress = owner,
                    type = com.kachat.app.util.MessageProtocol.TYPE_COMM,
                    direction = "sent",
                    plaintextBody = content,
                    encryptedPayload = "",
                    amountSompi = 0,
                    blockTimestamp = start + offset,
                    deliveryStatus = "pending"
                )
            )
            pendingId
        }
        _replyingTo.value = null
        for ((offset, content) in contents.withIndex()) {
            val pendingId = pendingIds[offset]
            try {
                refuseIfAccountChanged(owner)
                val result = walletService.sendKasiaMessage(contactId, content, feeRateOverride = feeRate)
                chatRepository.finalizeProvisionalMessage(
                    pendingId,
                    MessageEntity(
                        id = result.txId,
                        contactId = contactId,
                        walletAddress = owner,
                        type = com.kachat.app.util.MessageProtocol.TYPE_COMM,
                        direction = "sent",
                        plaintextBody = content,
                        encryptedPayload = result.payloadHex,
                        amountSompi = 0,
                        blockTimestamp = maxOf(System.currentTimeMillis(), start + offset),
                        deliveryStatus = "sent"
                    )
                )
            } catch (e: Exception) {
                if (e is com.kachat.app.services.SendOutcomeUnknownException) {
                    Log.w("ChatViewModel", "Media part send outcome unknown: marked not confirmed")
                    finalizeUnconfirmedSend(pendingId, e.txId)
                } else {
                    Log.e("ChatViewModel", "Error sending media part ${offset + 1} of ${contents.size}", e)
                    chatRepository.updateMessageStatus(pendingId, "failed")
                }
                // The rest never went: failed too, so one Retry resends them in order.
                for (rest in pendingIds.drop(offset + 1)) chatRepository.updateMessageStatus(rest, "failed")
                onError?.invoke(UserFacingError.message(e, "Failed to send"))
                return false
            }
        }
        return true
    }

    /**
     * What all of a note's parts cost together (each a self-send paying only its fee), against
     * the identity address's balance, leaving the dust threshold over so the last part still has
     * a change output to stand on (iOS `ensureSufficientBalanceForVoiceParts`). Skipped while the
     * balance isn't known yet; the sends themselves still refuse what can't be paid.
     */
    private fun ensureBalanceForParts(payloadSizes: List<Int>, feeRate: Long?) {
        if (!walletService.balanceKnown.value) return
        val rate = feeRate ?: _networkFeeRate.value.toLong()
        var total = 0L
        for (size in payloadSizes) {
            val mass = com.kachat.app.util.KaspaMass.calculateMass(numInputs = 1, outputScriptLens = listOf(34), payloadSize = size)
            total += com.kachat.app.util.KaspaMass.calculateFee(mass, rate)
        }
        val required = total + MEDIA_PARTS_DUST_SOMPI
        val available = walletService.balance.value
        if (available < required) throw IllegalStateException("Insufficient funds: Needed $required, have $available")
    }

    /** Retry on a long voice note's or multi-part photo's bubble: only its failed parts, in index
     *  order, one after another, stopping at the first that fails again (iOS
     *  `retryVoiceNoteParts`). Each keeps its row and its place. */
    fun retryFailedParts(failedParts: List<MessageEntity>, onError: ((String) -> Unit)? = null) {
        viewModelScope.launch {
            val owner = runCatching { walletManager.getAddress() }.getOrNull() ?: return@launch
            for (part in failedParts) {
                if (part.deliveryStatus != "failed" || part.direction != "sent") continue
                val text = part.plaintextBody ?: continue
                try {
                    if (!chatRepository.claimFailedSendForRetry(part.id)) continue
                    refuseIfAccountChanged(owner)
                    val result = walletService.sendKasiaMessage(part.contactId, text)
                    chatRepository.finalizeProvisionalMessage(
                        part.id,
                        part.copy(id = result.txId, walletAddress = owner, encryptedPayload = result.payloadHex, deliveryStatus = "sent")
                    )
                } catch (e: Exception) {
                    if (e is com.kachat.app.services.SendOutcomeUnknownException) {
                        finalizeUnconfirmedSend(part.id, e.txId)
                    } else {
                        Log.e("ChatViewModel", "Error retrying a media part", e)
                        chatRepository.updateMessageStatus(part.id, "failed")
                    }
                    onError?.invoke(UserFacingError.message(e, "Failed to send"))
                    return@launch
                }
            }
        }
    }

    /** Re-attempts a failed message, reusing its same id/content — the same placeholder resurrected, not a new message.
     *  Never a Not confirmed one ("warning"): it may have landed, and a second send could post it
     *  twice (iOS d23e6f7a `retryOutgoingMessage`). */
    fun retrySendMessage(message: MessageEntity, onError: ((String) -> Unit)? = null) {
        if (message.deliveryStatus == "warning") return
        val text = message.plaintextBody ?: return
        viewModelScope.launch {
            val owner = runCatching { walletManager.getAddress() }.getOrNull() ?: return@launch
            try {
                // Pending from this moment: a second tap finds nothing left to retry, where the
                // row used to stay Failed and post twice (iOS 4f8e14bb).
                if (!chatRepository.claimFailedSendForRetry(message.id)) return@launch
                refuseIfAccountChanged(owner)
                val result = walletService.sendKasiaMessage(message.contactId, text)
                chatRepository.finalizeProvisionalMessage(
                    message.id,
                    MessageEntity(
                        id = result.txId,
                        contactId = message.contactId,
                        walletAddress = owner,
                        type = com.kachat.app.util.MessageProtocol.TYPE_COMM,
                        direction = "sent",
                        plaintextBody = text,
                        encryptedPayload = result.payloadHex,
                        amountSompi = 0,
                        blockTimestamp = System.currentTimeMillis(),
                        deliveryStatus = "sent"
                    )
                )
            } catch (unknown: com.kachat.app.services.SendOutcomeUnknownException) {
                // It may have landed: Not confirmed under its own id, never Failed with Retry.
                Log.w("ChatViewModel", "Retried message outcome unknown: marked not confirmed")
                finalizeUnconfirmedSend(message.id, unknown.txId)
                onError?.invoke(UserFacingError.message(unknown, "Failed to send"))
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error retrying message", e)
                chatRepository.updateMessageStatus(message.id, "failed")
                onError?.invoke(UserFacingError.message(e, "Failed to send"))
            }
        }
    }

    /** A send runs when its turn comes, and the person may have switched accounts since: refused
     *  rather than signed with the new account's key (iOS 4f8e14bb `refuseIfAccountChanged`). */
    private fun refuseIfAccountChanged(owner: String) {
        if (walletManager.getAddress() != owner) {
            throw IllegalStateException(appContext.getString(com.kachat.app.R.string.not_sent_you_switched_accounts))
        }
    }

    /**
     * A send whose outcome is unknown ([com.kachat.app.services.SendOutcomeUnknownException]):
     * its pending row [provisionalId] takes the transaction's own id and "warning" - Not confirmed,
     * with no Retry - until the delivered copy is read back and settles it to sent (iOS d23e6f7a
     * `markSendUnconfirmed`). A row already gone (deleted meanwhile) stays gone.
     */
    private suspend fun finalizeUnconfirmedSend(provisionalId: String, txId: String) {
        try {
            val row = chatRepository.getMessage(provisionalId) ?: return
            chatRepository.finalizeProvisionalMessage(
                provisionalId,
                row.copy(id = txId, blockTimestamp = System.currentTimeMillis(), deliveryStatus = "warning")
            )
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Couldn't mark a send not confirmed", e)
            chatRepository.updateMessageStatus(provisionalId, "warning")
        }
    }

    /**
     * Sends a Kaspa payment via WalletService. Same optimistic pending/failed pattern
     * as [sendMessage], but with no retry entry point — matches iOS explicitly excluding
     * payment retry, since blindly re-sending a payment risks paying twice.
     */
    /** [onResult] is (succeeded, errorMessage, txId). A 1:1 chat payment has no sent-confirmation
     *  sheet - its bubble is the confirmation (iOS 80a6aae); the txId is null on failure.
     *
     *  [note] is the Send KAS sheet's memo (iOS 8d208b2): sealed to the recipient in a
     *  `kchat:1:pay:` payload, which the seed also reads back on another device (iOS bfe9f022).
     *  The sender's own bubble keeps it from send time ("Sent X KAS — memo", iOS 2be75ed). A payment
     *  from the chatting address carries that payload even with no memo (iOS buildPaymentTx);
     *  one from a spending address carries none (see below).
     *
     *  [sourceSpendingIndex] is the payment's From (iOS 6333dd3 `sendPayment(sourceSpendingIndex:)`):
     *  null = the chatting address (the default) - signed with the identity key, change back to
     *  it; else that spending address - signed with its own key, change to a never-used address,
     *  which is revealed in Manage Addresses (no primary moves) - see
     *  [com.kachat.app.services.KaspaWalletEngine.sendSpendingPayment]. Either way it is paid to
     *  the contact's chatting address. */
    fun sendPayment(
        contactId: String,
        amount: String,
        note: String = "",
        sourceSpendingIndex: Int? = null,
        /** The Send KAS sheet's Fast / Priority / custom extra over the base fee, and its coin
         *  control pick (null = automatic) - both paid / spent exactly (iOS 62c2773). */
        extraFeeSompi: Long = 0L,
        manualUtxos: List<com.kachat.app.services.UtxoEntry>? = null,
        /** The fee the Send KAS sheet's Review showed (iOS 4f8e14bb): a build that would pay
         *  more sends nothing. Null holds it to nothing. */
        expectedFeeSompi: Long? = null,
        onResult: ((Boolean, String?, String?) -> Unit)? = null,
    ) {
        // Exact (iOS 16b64bc, AND-013): "2.3" pays 230,000,000 sompi - a Double multiply and
        // toLong() paid 229,999,999. Comma or dot, at most 8 decimals, nothing above the supply.
        val sompi = KaspaUnit.sompiFromUserText(amount)?.takeIf { it > 0 }
            ?: run { onResult?.invoke(false, "Enter a valid amount.", null); return }
        // The other network's address is the same key on another chain: never paid (iOS ce20e87).
        com.kachat.app.util.KaspaAddress.otherNetworkMessageRes(contactId)?.let {
            onResult?.invoke(false, appContext.getString(it), null)
            return
        }
        val feeRate = _feeRateOverride.value
        _feeRateOverride.value = null
        val memo = note.trim()
        viewModelScope.launch {
            // Paying someone is reaching out to them, like writing to them - and watches for
            // their messages from now on, so a reply their app didn't tag for our inbox still
            // arrives (iOS 1eb4708).
            if (!contactId.equals(walletManager.getAddress(), ignoreCase = true)) {
                chatRepository.acceptChat(contactId)
                chatRepository.watchPaymentDestination(contactId)
            }
            val outcome = chatPaymentSender.send(
                com.kachat.app.services.ChatPaymentSender.Request(
                    contactId = contactId,
                    amountSompi = sompi,
                    memo = memo,
                    sourceSpendingIndex = sourceSpendingIndex,
                    feeRateOverride = feeRate,
                    extraFeeSompi = extraFeeSompi,
                    manualUtxos = manualUtxos,
                    expectedFeeSompi = expectedFeeSompi,
                )
            )
            when (outcome) {
                is com.kachat.app.services.ChatPaymentSender.Outcome.Sent -> {
                    // The From's coins lag the send, as its balance does (~1.5s and ~4s).
                    launch {
                        delay(1_500)
                        refreshSpendingUtxos()
                        delay(2_500)
                        refreshSpendingUtxos()
                    }
                    onResult?.invoke(true, null, outcome.txId)
                }
                // No confirmed coins yet: the bubble is in the chat and the sender's retry timer
                // owns it, so there is no transaction to show (iOS sendPayment returning nil).
                com.kachat.app.services.ChatPaymentSender.Outcome.Deferred -> onResult?.invoke(true, null, null)
                is com.kachat.app.services.ChatPaymentSender.Outcome.Failed -> onResult?.invoke(false, outcome.message, null)
            }
        }
    }

    /** The deterministic alias on messages THIS CONTACT sends me (what my sync watches) — Chat
     *  Info's "Receiving alias". Derived on demand only; null when derivation fails. */
    fun deriveReceivingAlias(contactId: String): String? =
        try { walletManager.myDeterministicAlias(contactId) } catch (e: Exception) { null }

    /** The deterministic alias on messages I send this contact — Chat Info's "Sending alias". */
    fun deriveSendingAlias(contactId: String): String? =
        try { walletManager.theirDeterministicAlias(contactId) } catch (e: Exception) { null }

    fun getConversation(contactId: String): Conversation? {
        return conversations.value.find { it.contact.id == contactId }
    }
    
    fun getMessages(contactId: String): Flow<List<MessageEntity>> {
        return chatRepository.getMessages(contactId)
    }

    // MARK: - Thread history window + load-older paging (iOS inMemoryConversationWindowSize /
    // loadOlderMessagesPageAsync)

    /**
     * Older pages the thread has walked back into, per contact, oldest first. The live window
     * from the store is the newest [THREAD_WINDOW_SIZE] rows; everything before that arrives
     * here a page at a time as the reader scrolls up, and stays for the life of the view model
     * so a re-opened thread does not have to re-page what was already read.
     */
    private val _olderThreadPages = MutableStateFlow<Map<String, List<MessageEntity>>>(emptyMap())
    private val _olderHistoryExhausted = MutableStateFlow<Set<String>>(emptySet())
    private val _loadingOlderContacts = MutableStateFlow<Set<String>>(emptySet())
    private val olderPageJobs = mutableMapOf<String, kotlinx.coroutines.Deferred<Int>>()
    /** The newest window per contact as last emitted, so a page cursor can be taken without a
     *  round trip to the flow. */
    private val latestThreadWindows = mutableMapOf<String, List<MessageEntity>>()

    /** Whether the thread for [contactId] is fetching older history right now. */
    fun isLoadingOlderMessages(contactId: String): Flow<Boolean> =
        _loadingOlderContacts.map { contactId in it }.distinctUntilChanged()

    /** False once a page came back short: there is nothing older to fetch. */
    fun hasOlderMessages(contactId: String): Flow<Boolean> =
        _olderHistoryExhausted.map { contactId !in it }.distinctUntilChanged()

    /**
     * What the open thread renders: the store's live window (newest rows plus sticky handshakes
     * and unsent sends) with the older pages the reader has scrolled into prepended. Deduped by
     * id - a row can sit in both once the window slides - and kept in chain order.
     */
    fun threadMessages(contactId: String): Flow<List<MessageEntity>> = combine(
        chatRepository.getMessageWindow(contactId, THREAD_WINDOW_SIZE, THREAD_UNSENT_STICKY_LIMIT)
            .onEach { latestThreadWindows[contactId] = it },
        _olderThreadPages.map { it[contactId].orEmpty() }.distinctUntilChanged(),
    ) { window, older ->
        if (older.isEmpty()) return@combine window
        val seen = HashSet<String>(window.size + older.size)
        (older + window)
            .filter { seen.add(it.id) }
            .sortedWith(compareBy<MessageEntity> { it.blockTimestamp }.thenBy { it.id })
    }

    /** The oldest row the thread currently holds, which is where the next page starts. */
    private fun oldestLoadedMessage(contactId: String): MessageEntity? {
        val older = _olderThreadPages.value[contactId].orEmpty()
        val candidates = older + latestThreadWindows[contactId].orEmpty()
        return candidates.minWithOrNull(compareBy<MessageEntity> { it.blockTimestamp }.thenBy { it.id })
    }

    /**
     * Pages one batch of older history into the thread. Returns how many rows were added; zero
     * means the beginning of the conversation has been reached. One fetch per contact at a time:
     * a second caller joins the in-flight one rather than fetching the same page twice.
     */
    suspend fun loadOlderMessages(contactId: String, pageSize: Int = THREAD_OLDER_PAGE_SIZE): Int {
        if (contactId in _olderHistoryExhausted.value) return 0
        olderPageJobs[contactId]?.let { return it.await() }
        val job = viewModelScope.async {
            _loadingOlderContacts.value = _loadingOlderContacts.value + contactId
            try {
                val cursor = oldestLoadedMessage(contactId)
                if (cursor == null) {
                    _olderHistoryExhausted.value = _olderHistoryExhausted.value + contactId
                    return@async 0
                }
                val page = try {
                    chatRepository.getOlderMessagesPage(contactId, cursor, pageSize)
                } catch (e: Exception) {
                    Log.w("ChatViewModel", "Older history page failed", e)
                    emptyList()
                }
                if (page.size < pageSize) {
                    _olderHistoryExhausted.value = _olderHistoryExhausted.value + contactId
                }
                if (page.isEmpty()) return@async 0
                val existing = _olderThreadPages.value[contactId].orEmpty()
                val known = existing.mapTo(HashSet()) { it.id }
                val fresh = page.asReversed().filter { it.id !in known }
                _olderThreadPages.value = _olderThreadPages.value + (contactId to fresh + existing)
                fresh.size
            } finally {
                _loadingOlderContacts.value = _loadingOlderContacts.value - contactId
            }
        }
        olderPageJobs[contactId] = job
        return try { job.await() } finally { olderPageJobs.remove(contactId) }
    }

    private val historyPreloadJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    /**
     * The chat was entered: its whole stored history comes into memory in the background, 500
     * rows at a time with a beat between, so scrolling up only ever moves over messages already
     * loaded instead of waiting on a store read at each trigger and visibly filling in as it
     * goes (iOS d30cc0a). Scrolling's own page loads join an in-flight page, never double it.
     */
    fun preloadThreadHistory(contactId: String) {
        if (historyPreloadJobs[contactId]?.isActive == true) return
        historyPreloadJobs[contactId] = viewModelScope.launch {
            // Let the thread's first frame land before the backlog starts.
            delay(HISTORY_PRELOAD_START_DELAY_MS)
            while (true) {
                if (loadOlderMessages(contactId, pageSize = HISTORY_PRELOAD_PAGE_SIZE) == 0) break
                delay(HISTORY_PRELOAD_PAGE_GAP_MS)
            }
        }
    }

    /** The chat was left: the load stops and its older history is let go, as iOS lets the normal
     *  memory trim apply again. Re-entering loads it again in the background. */
    fun releaseThreadHistory(contactId: String) {
        historyPreloadJobs.remove(contactId)?.cancel()
        _olderThreadPages.value = _olderThreadPages.value - contactId
        _olderHistoryExhausted.value = _olderHistoryExhausted.value - contactId
    }

    /**
     * Pulls every remaining page in, for "jump to the first message": scrolling first would land
     * on whatever the oldest LOADED row happened to be (iOS jumpToChatStart). Bounded so a store
     * that keeps answering can never spin here forever.
     */
    suspend fun loadAllOlderMessages(contactId: String) {
        var pagesLeft = 200
        while (pagesLeft-- > 0) {
            if (loadOlderMessages(contactId, pageSize = 500) == 0) break
        }
    }

    /**
     * Pages older history in until the thread holds [messageId], for tapping a reply quote whose
     * original is not loaded yet (iOS jumpToReplyOriginal grows the window to the target).
     * Returns false when the conversation ran out without finding it.
     */
    suspend fun loadOlderMessagesUntil(contactId: String, messageId: String): Boolean {
        var pagesLeft = 200
        while (pagesLeft-- > 0) {
            if (_olderThreadPages.value[contactId].orEmpty().any { it.id == messageId }) return true
            if (latestThreadWindows[contactId].orEmpty().any { it.id == messageId }) return true
            if (loadOlderMessages(contactId) == 0) break
        }
        return _olderThreadPages.value[contactId].orEmpty().any { it.id == messageId }
    }

    /**
     * A tapped quote whose original is on neither the thread nor the store: the reply names it,
     * so the repository can go and get it rather than just say no (see
     * ChatRepository.recoverMissingReplyOriginal). Forced: a quote tap retries a known miss.
     */
    fun recoverMissingReplyOriginal(contactId: String, replyToId: String, replyBlockTime: Long) {
        chatRepository.recoverMissingReplyOriginal(contactId, replyToId, replyBlockTime, force = true)
    }

    /** Older pages belong to the account that read them. */
    private fun resetThreadHistoryForAccountSwitch() {
        _olderThreadPages.value = emptyMap()
        _olderHistoryExhausted.value = emptySet()
        latestThreadWindows.clear()
    }

    init {
        // Avatars stored from .kas profiles before 5.2 stop showing with the profiles themselves
        // (iOS d6ded9d strips its cached profiles on load the same way).
        if (!com.kachat.app.services.KnsService.LOADS_DOMAIN_PROFILES) {
            viewModelScope.launch(Dispatchers.IO) { runCatching { chatRepository.clearStoredKnsAvatars() } }
        }
        // Every account opens with its chat with yourself already there, first in the list -
        // a new or just-imported account included, before any sync (iOS ef4f183).
        viewModelScope.launch {
            walletManager.activeAddressFlow.collect { address ->
                if (address != null) runCatching { chatRepository.ensureSelfConversation(address) }
                // .kas names stop being people's names (iOS 509c0fe) - on every load, not once
                // (iOS ce0b636): one can come back later (a Nextcloud backup another platform or
                // an older build wrote) and it must not stay.
                if (address != null && !com.kachat.app.services.KnsService.SHOWS_DOMAIN_NAMES_AS_IDENTITY) {
                    runCatching { chatRepository.clearKasDomainNames(address) }
                }
            }
        }
    }

    init {
        viewModelScope.launch {
            walletManager.activeAddressFlow.drop(1).collect { resetThreadHistoryForAccountSwitch() }
        }
    }

    fun getReactions(contactId: String): Flow<List<ReactionEntity>> {
        return chatRepository.getReactionsForContact(contactId)
    }

    /** The newest edit per message in this conversation - applied when the bubble is drawn. */
    fun getEdits(contactId: String): Flow<List<com.kachat.app.models.MessageEditEntity>> {
        return chatRepository.getEditsForContact(contactId)
    }

    /** One sweep at a time - see [refreshKnsNamesForAllContacts]. */
    private val knsNameSweepRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    companion object {
        /**
         * The balance and input count the Send KAS sheet's Max prices (iOS d23e6f7a
         * estimateMaxPaymentAmount): coin control's pick (already resolved against the source's
         * coins), else the source's coins (fresh, confirmed - [refreshSpendingUtxos]) - leaving out
         * coinbase coins, as iOS's candidates do. With no pick and more coins than one transaction
         * can spend, it is what the largest [com.kachat.app.util.KaspaUtxoSelector.MAX_INPUTS_PER_TRANSACTION]
         * carry, and [PaymentMaxCoins.cappedAtInputLimit] says so; a pick is never cut.
         */
        fun paymentMaxCoins(
            sourceCoins: List<com.kachat.app.services.UtxoEntry>,
            manual: List<com.kachat.app.services.UtxoEntry>?,
        ): PaymentMaxCoins {
            // Node-supplied amounts that are not real values count as nothing (IOS-020).
            fun sum(c: List<com.kachat.app.services.UtxoEntry>) =
                com.kachat.app.util.UtxoMath.checkedTotal(c.map { it.utxoEntry.amount }) ?: 0L
            if (manual != null) {
                val picked = manual.filterNot { it.utxoEntry.isCoinbase }
                return PaymentMaxCoins(sum(picked), picked.size, cappedAtInputLimit = false)
            }
            val coins = sourceCoins.filterNot { it.utxoEntry.isCoinbase }
            val limit = com.kachat.app.util.KaspaUtxoSelector.MAX_INPUTS_PER_TRANSACTION
            val spent = if (coins.size > limit) coins.sortedByDescending { it.utxoEntry.amount }.take(limit) else coins
            return PaymentMaxCoins(sum(spent), spent.size, cappedAtInputLimit = spent.size < coins.size)
        }

        /**
         * The coins a payment's fee preview prices, as (their total, how many): the largest of
         * coin control's pick, or of the source's coins, one by one, until the amount and a little
         * fee are covered (iOS estimatePaymentFee(manualUtxos:)) - the engine's own largest-first
         * selection, also stopping at 80 inputs. The same for either From: a
         * spending-address payment selects its coins exactly as a chatting-address one does
         * (iOS 6333dd3, KaspaWalletEngine.sendSpendingPayment). A busy address used to be priced
         * on every coin it held, in fetch order, past what one send can spend.
         */
        fun paymentFeeInputs(
            paymentCoins: List<com.kachat.app.services.UtxoEntry>,
            manual: List<com.kachat.app.services.UtxoEntry>?,
            sompiNeeded: Long,
        ): Pair<Long, Int> {
            // Node-supplied amounts that are not real values count as nothing (IOS-020).
            var total = 0L
            var count = 0
            for (utxo in (manual ?: paymentCoins).sortedByDescending { it.utxoEntry.amount }.take(com.kachat.app.util.KaspaUtxoSelector.MAX_INPUTS_PER_TRANSACTION)) {
                total = com.kachat.app.util.UtxoMath.checkedTotal(listOf(total, utxo.utxoEntry.amount)) ?: return 0L to 0
                count++
                if (total >= sompiNeeded + 1000) break // Buffer for fee
            }
            return total to count
        }

        /**
         * [paymentMaxSompi]'s arithmetic: [balanceSompi] over [inputCount] inputs, less the base
         * fee at [feeRate] (rounded up, never under the network minimum - the rate the send pays)
         * with the memo's payload priced in, less the extra. [payloadSize] prices a payload other
         * than the chat payment's own.
         *
         * The extra is the custom one when set. Otherwise a speed above Normal multiplies the
         * base fee of the max send itself, worked out as iOS df4aba6 does: the Normal max first
         * (provisional), then the fee preview's base for sending that much - [provisionalShape]
         * gives its (input count, payload size); by default [inputCount] and the memo's payload
         * for that amount - and extra = base x (multiplier - 1). That base is never under what the
         * preview charges once Max fills the amount in, so the send is always covered (AND-010).
         */
        fun computePaymentMaxSompi(
            balanceSompi: Long,
            inputCount: Int,
            note: String,
            feeRate: Double,
            feeMultiplier: Long,
            customExtraFeeSompi: Long?,
            payloadSize: Int? = null,
            /** The source's per-input signature-script size (a quantum-safe source's is bigger). */
            signatureScriptSize: Int = com.kachat.app.util.KaspaMass.SCHNORR_SIGNATURE_SCRIPT_LEN,
            provisionalShape: ((provisionalSompi: Long) -> Pair<Int, Int>)? = null,
        ): Long {
            // Every chat payment carries its payload, an empty memo too (iOS estimateMaxPaymentAmount).
            val pricedPayloadSize = payloadSize ?: MessageProtocol.estimatedPaymentPayloadSize(note.trim(), balanceSompi)
            val rate = kotlin.math.ceil(feeRate).toLong().coerceAtLeast(com.kachat.app.util.KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM)
            fun max(extra: Long?) = com.kachat.app.util.SendFeeModel.maxAfterFees(
                totalSompi = balanceSompi,
                inputCount = inputCount,
                feeRateSompiPerGram = rate,
                customExtraFeeSompi = extra,
                payloadSize = pricedPayloadSize,
                signatureScriptSize = signatureScriptSize,
            )
            if (customExtraFeeSompi != null || feeMultiplier <= 1) {
                return max(customExtraFeeSompi?.coerceAtLeast(0L) ?: 0L)
            }
            val provisional = max(0L)
            if (provisional <= 0L) return 0L
            val (provisionalInputs, provisionalPayload) = provisionalShape?.invoke(provisional)
                ?: (inputCount to (payloadSize ?: MessageProtocol.estimatedPaymentPayloadSize(note.trim(), provisional)))
            val base = com.kachat.app.util.SendFeeModel.baseFee(
                provisionalInputs, rate, provisionalPayload, signatureScriptSize = signatureScriptSize
            )
            return max(com.kachat.app.util.SendFeeModel.extraFee(base, feeMultiplier, null))
        }

        /** The longest a pull-to-refresh wheel stays up; the work itself keeps going. */
        private const val REFRESH_SPINNER_CAP_MS = 15_000L
        /** Newest rows the open thread keeps live from the store - iOS
         *  ChatService.inMemoryConversationWindowSize. Older history pages in on scroll. */
        const val THREAD_WINDOW_SIZE = 160

        /** One scroll-up batch of older history - iOS olderHistoryBatchSize (three ~40-row pages). */
        const val THREAD_OLDER_PAGE_SIZE = 120

        /** Unsent (pending/failed) rows kept sticky in the window - iOS inMemoryUnsentStickyLimit. */
        const val THREAD_UNSENT_STICKY_LIMIT = 50

        /** Whole-history load on entering a chat - iOS d30cc0a (500-row pages, a beat apart). */
        const val HISTORY_PRELOAD_PAGE_SIZE = 500
        const val HISTORY_PRELOAD_PAGE_GAP_MS = 150L
        const val HISTORY_PRELOAD_START_DELAY_MS = 300L

        /** How close to the top of the loaded history counts as "nearing it", in rows - iOS
         *  nearTopPrefetchThresholdIndex. */
        const val THREAD_OLDER_PREFETCH_ROWS = 12

        private const val KNS_NAME_SWEEP_KEY = "last_kns_name_sweep_ms"
        /** A contact's primary domain changes rarely, and the name already lives in the
         *  database, so the sweep is a refresh rather than something the UI waits on. */
        private const val KNS_NAME_SWEEP_INTERVAL_MS = 6L * 60 * 60 * 1000

        /** What a long voice note's or multi-part photo's balance check leaves over, so the last
         *  part still has a change output (iOS `KasiaTransactionBuilder.dustThreshold`, 0.2 KAS). */
        private const val MEDIA_PARTS_DUST_SOMPI = 20_000_000L

        /** Rough byte length of a Nextcloud public share link ("https://<host>/s/<token>") for the
         *  live fee preview while media is staged in Nextcloud mode — the real send measures the
         *  actual link exactly, same preview-only contract as [estimatedGroupWirePayloadSize]. */
        private const val NEXTCLOUD_LINK_PREVIEW_BYTES = 100

        /**
         * Whether the "the recipient won't see your messages yet" banner belongs in a 1:1 chat.
         *
         * Cross-platform corrected semantics (desktop's `relationshipState === "established"`,
         * which this is the Android equivalent of):
         *  - Visible from the moment the chat opens. It is NOT gated on having already sent
         *    something, and NOT gated on the composer having text — the whole point is to warn
         *    *before* the user types into the void.
         *  - Hidden only once the relationship is genuinely reciprocated: at least one real
         *    non-handshake message in EACH direction. Evidence in one direction only isn't
         *    enough, and a handshake on its own isn't evidence of communication at all.
         *
         * "Genuine" excludes handshakes (protocol traffic, not conversation) and empty-bodied
         * messages, but counts a payment either way — an actual KAS transfer is real two-way
         * contact even when it carries no note.
         */
        /**
         * A handshake we sent that they have not answered - no handshake back, no genuine message
         * back. Their answer of either kind makes the action available again.
         *
         * A handshake costs 0.2 KAS, and the notice banner deliberately stays up until they
         * reply, so the screen looks identical before and after a send: tapping again
         * (reasonably, since nothing appeared to happen) simply spent another 0.2 KAS. Matches
         * iOS's `hasUnansweredOutgoingHandshake`.
         */
        internal fun hasUnansweredOutgoingHandshake(messages: List<MessageEntity>): Boolean {
            val handshake = com.kachat.app.util.MessageProtocol.TYPE_HANDSHAKE
            // Any of ours that didn't fail: a Not confirmed one ("warning", outcome unknown) may
            // have landed, so it counts as out (iOS `hasOutgoingHandshakeMessage`: deliveryStatus
            // != .failed).
            val sentHandshake = messages.any { it.direction == "sent" && it.type == handshake && it.deliveryStatus != "failed" }
            if (!sentHandshake) return false
            val answered = messages.any {
                it.direction == "received" &&
                    (it.type == handshake || !it.plaintextBody.isNullOrBlank())
            }
            return !answered
        }

        internal fun shouldShowUnnotifiedWarning(messages: List<MessageEntity>): Boolean {
            fun isGenuine(m: MessageEntity): Boolean =
                m.type != com.kachat.app.util.MessageProtocol.TYPE_HANDSHAKE &&
                    (m.type == com.kachat.app.util.MessageProtocol.TYPE_PAY || !m.plaintextBody.isNullOrBlank())

            val hasGenuineOutgoing = messages.any { it.direction == "sent" && isGenuine(it) }
            val hasGenuineIncoming = messages.any { it.direction == "received" && isGenuine(it) }
            // A handshake FROM them also ends the warning: whether it's their acceptance of our
            // handshake or their own initiation, their side provably has the conversation and
            // can see what we send - waiting for a genuine reply made the banner outlive a
            // completed handshake. A sent-but-unanswered handshake keeps it up. Matches iOS.
            val hasIncomingHandshake = messages.any {
                it.direction == "received" && it.type == com.kachat.app.util.MessageProtocol.TYPE_HANDSHAKE
            }

            return !hasIncomingHandshake && !(hasGenuineOutgoing && hasGenuineIncoming)
        }

        /** Matches iOS's `shouldShowRetry` — only failed outgoing non-payment messages can be retried. */
        internal fun shouldShowRetryOption(message: MessageEntity): Boolean =
            message.direction == "sent" && message.deliveryStatus == "failed" && message.type != com.kachat.app.util.MessageProtocol.TYPE_PAY

        /**
         * Safe to auto-overwrite a contact's alias with the detected primary KNS domain only if
         * it's unset — never clobber a real custom nickname the user typed in, matching iOS's
         * identical rule. Once any domain is associated with a contact (`knsName` set, whether by this
         * same auto-detection or by an explicit pick in Chat Info), that choice is pinned —
         * without this, an explicit non-primary domain selection would silently revert back to
         * primary the next time this runs, since the selected domain also "looks like" a domain
         * and would otherwise pass the old unset-or-domain-shaped check.
         */
        internal fun canAutoUpdateAliasToDomain(currentAlias: String?, knsName: String? = null): Boolean =
            knsName == null && currentAlias == null
    }
}
