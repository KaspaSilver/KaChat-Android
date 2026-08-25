package com.kachat.app.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns a chat-history restore from tap to terminal state, independent of any screen's lifetime —
 * the Android port of iOS's `BackupRestoreCoordinator` (NextcloudService.swift). Interrupting a
 * restore midway can leave local state partially written, so the storage screens only OBSERVE
 * this singleton: the restore job runs on the coordinator's own scope (never a composable's or a
 * nav-entry-scoped viewmodel's), so recomposition, back navigation, or viewmodel teardown can
 * never cancel it. While [phase] is [Phase.Running] the storage screens present a full-screen
 * overlay (`ChatRestoreProgressOverlay` in StorageScreens.kt) that cannot be dismissed; the only
 * exits are the overlay's own Done / Try Again / Close buttons, which call back into [dismiss] /
 * [retry] here, and [dismiss] refuses to fire while a restore is running.
 *
 * Progress is real, monotonic, and stage-weighted exactly like iOS: download 0-30% (actual bytes
 * over Content-Length when the server sends it), validate 30-40%, per-conversation import 40-90%
 * (advances as [ChatHistoryExportImportService.importChatHistory] lands each conversation),
 * finalize 90-100%.
 *
 * The Danger Zone's "Wipe and Re-sync Incoming Messages" ([startIncomingResync]) runs through
 * the exact same machinery — same scope ownership, same blocking overlay, same terminal states —
 * with [kind] telling the overlay which flavor of copy to show. The wallet address is
 * snapshotted at start and every destructive/step is pinned to it, so a mid-flow account switch
 * aborts to [Phase.Failure] instead of touching the new account's data (see
 * [ChatRepository.resyncIncomingMessages]).
 */
@Singleton
class BackupRestoreCoordinator @Inject constructor(
    private val nextcloudService: NextcloudService,
    private val googleDriveBackupService: GoogleDriveBackupService,
    private val chatHistoryExportImportService: ChatHistoryExportImportService,
    private val walletManager: WalletManager,
    // Lazy because ChatRepository reaches GoogleDriveSyncService (which needs this coordinator)
    // through its own Lazy edge — keep this side lazy too so the object graph stays acyclic at
    // construction time regardless of instantiation order.
    private val chatRepositoryLazy: dagger.Lazy<com.kachat.app.repository.ChatRepository>
) {
    sealed class Phase {
        data object Idle : Phase()
        data object Running : Phase()
        data class Success(val conversations: Int, val messages: Int) : Phase()
        data class Failure(val message: String) : Phase()
    }

    enum class Source { NEXTCLOUD, GOOGLE_DRIVE }

    /** Which flow the current [phase]/[fraction]/[stageText] belong to — picks the overlay's copy. */
    enum class Kind { RESTORE, RESYNC }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** 0..1, monotonic — overlapping async reports can never move the bar backwards. */
    private val _fraction = MutableStateFlow(0f)
    val fraction: StateFlow<Float> = _fraction.asStateFlow()

    private val _stageText = MutableStateFlow("")
    val stageText: StateFlow<String> = _stageText.asStateFlow()

    private val _kind = MutableStateFlow(Kind.RESTORE)
    val kind: StateFlow<Kind> = _kind.asStateFlow()

    val isRunning: Boolean get() = _phase.value is Phase.Running

    /** Kept so Try Again after a failure reruns the exact same restore. */
    private var lastSource: Source? = null

    /** The wipe-and-resync equivalent of [lastSource]: the snapshotted wallet + chat scope, kept for Try Again. */
    private data class ResyncRequest(val address: String, val contactIds: List<String>?)
    private var lastResync: ResyncRequest? = null

    /** Held by the singleton (not a screen) so navigation can never cancel it. */
    private var restoreJob: Job? = null

    fun startNextcloudRestore() = start(Source.NEXTCLOUD)
    fun startGoogleDriveRestore() = start(Source.GOOGLE_DRIVE)

    /**
     * Danger Zone "Wipe and Re-sync Incoming Messages". [contactIds] scopes both the wipe and
     * the re-fetch to just those 1:1 conversations; null means every chat. The active wallet is
     * snapshotted HERE — everything after runs against that address and aborts if it stops
     * being the active one (Try Again re-uses the same snapshot, so retrying after a switch
     * fails fast instead of wiping the new account).
     */
    fun startIncomingResync(contactIds: List<String>?) {
        if (isRunning) return
        val address = try { walletManager.getAddress() } catch (e: Exception) { null } ?: return
        val request = ResyncRequest(address, contactIds)
        lastResync = request
        lastSource = null
        _kind.value = Kind.RESYNC
        _fraction.value = 0f
        _stageText.value = "Wiping incoming messages..."
        _phase.value = Phase.Running
        restoreJob = scope.launch { runResync(request) }
    }

    /** Reruns the failed restore or resync. Only valid from the failure state. */
    fun retry() {
        if (_phase.value !is Phase.Failure) return
        when (_kind.value) {
            Kind.RESTORE -> {
                val source = lastSource ?: return
                _phase.value = Phase.Idle
                start(source)
            }
            Kind.RESYNC -> {
                val request = lastResync ?: return
                _phase.value = Phase.Idle
                if (isRunning) return
                _fraction.value = 0f
                _stageText.value = "Wiping incoming messages..."
                _phase.value = Phase.Running
                restoreJob = scope.launch { runResync(request) }
            }
        }
    }

    /** Leaves the modal. Only honored from a terminal state; a running restore cannot be dismissed. */
    fun dismiss() {
        if (isRunning) return
        _phase.value = Phase.Idle
        _fraction.value = 0f
        _stageText.value = ""
    }

    private fun start(source: Source) {
        if (isRunning) return
        lastSource = source
        lastResync = null
        _kind.value = Kind.RESTORE
        _fraction.value = 0f
        _stageText.value = "Downloading backup..."
        _phase.value = Phase.Running
        restoreJob = scope.launch { run(source) }
    }

    /**
     * Wipe (0-15%) then per-chat re-fetch (15-95%) then finalize. Both halves live in
     * [com.kachat.app.repository.ChatRepository] and are pinned to the snapshotted address;
     * either throwing (account switch, indexer down) lands in [Phase.Failure] with Try Again.
     */
    private suspend fun runResync(request: ResyncRequest) {
        try {
            advance(0.02f, "Wiping incoming messages...")
            val chatRepository = chatRepositoryLazy.get()
            chatRepository.wipeIncomingMessages(request.address, request.contactIds)
            advance(0.15f, "Re-syncing from the blockchain...")

            val result = chatRepository.resyncIncomingMessages(request.address, request.contactIds) { done, total ->
                val f = if (total > 0) done.toFloat() / total else 1f
                advance(0.15f + 0.80f * f, "Re-syncing... $done of $total chats")
            }
            advance(0.97f, "Finishing up...")

            _fraction.value = 1f
            _stageText.value = "Done"
            // Retry is only offered after a failure; drop the request on success.
            lastResync = null
            _phase.value = Phase.Success(
                conversations = result.chatCount,
                messages = result.messageCount
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _phase.value = Phase.Failure(e.message ?: "Something went wrong. Please try again.")
        }
    }

    private suspend fun run(source: Source) {
        try {
            val json = when (source) {
                Source.NEXTCLOUD -> nextcloudService.downloadBackup { received, total ->
                    if (total != null && total > 0) {
                        val downloaded = (received.toFloat() / total).coerceAtMost(1f)
                        advance(0.30f * downloaded, "Downloading backup...")
                    }
                }
                // Drive's API hands back the whole body at once — no byte progress to stream.
                Source.GOOGLE_DRIVE -> googleDriveBackupService.downloadBackup(walletManager.getAddress())
                    ?: throw IllegalStateException("No Google Drive backup found")
            }
            advance(0.32f, "Validating backup...")

            val result = chatHistoryExportImportService.importChatHistory(json) { done, total ->
                val f = if (total > 0) done.toFloat() / total else 1f
                advance(0.40f + 0.50f * f, "Restoring messages... $done of $total conversations")
            }
            advance(0.92f, "Finishing up...")

            _fraction.value = 1f
            _stageText.value = "Done"
            // Retry is only offered after a failure; drop the source on success.
            lastSource = null
            _phase.value = Phase.Success(
                conversations = result.conversationCount,
                messages = result.importedMessageCount
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _phase.value = Phase.Failure(e.message ?: "Something went wrong. Please try again.")
        }
    }

    /** Monotonic progress: overlapping async reports can never move the bar backwards. */
    private fun advance(value: Float, stage: String) {
        _fraction.value = maxOf(_fraction.value, value.coerceAtMost(1f))
        _stageText.value = stage
    }
}
