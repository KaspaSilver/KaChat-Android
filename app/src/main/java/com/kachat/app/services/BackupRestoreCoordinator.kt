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
 */
@Singleton
class BackupRestoreCoordinator @Inject constructor(
    private val nextcloudService: NextcloudService,
    private val googleDriveBackupService: GoogleDriveBackupService,
    private val chatHistoryExportImportService: ChatHistoryExportImportService,
    private val walletManager: WalletManager
) {
    sealed class Phase {
        data object Idle : Phase()
        data object Running : Phase()
        data class Success(val conversations: Int, val messages: Int) : Phase()
        data class Failure(val message: String) : Phase()
    }

    enum class Source { NEXTCLOUD, GOOGLE_DRIVE }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** 0..1, monotonic — overlapping async reports can never move the bar backwards. */
    private val _fraction = MutableStateFlow(0f)
    val fraction: StateFlow<Float> = _fraction.asStateFlow()

    private val _stageText = MutableStateFlow("")
    val stageText: StateFlow<String> = _stageText.asStateFlow()

    val isRunning: Boolean get() = _phase.value is Phase.Running

    /** Kept so Try Again after a failure reruns the exact same restore. */
    private var lastSource: Source? = null

    /** Held by the singleton (not a screen) so navigation can never cancel it. */
    private var restoreJob: Job? = null

    fun startNextcloudRestore() = start(Source.NEXTCLOUD)
    fun startGoogleDriveRestore() = start(Source.GOOGLE_DRIVE)

    /** Reruns the failed restore. Only valid from the failure state. */
    fun retry() {
        if (_phase.value !is Phase.Failure) return
        val source = lastSource ?: return
        _phase.value = Phase.Idle
        start(source)
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
        _fraction.value = 0f
        _stageText.value = "Downloading backup..."
        _phase.value = Phase.Running
        restoreJob = scope.launch { run(source) }
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
