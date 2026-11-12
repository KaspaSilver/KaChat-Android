package com.kachat.app.viewmodels

import android.media.MediaPlayer
import android.util.Log
import com.kachat.app.services.VoiceNoteCodec
import com.kachat.app.services.VoiceRecorderService
import com.kachat.app.util.VoiceNotePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * A composer's voice note, recorded the same way in 1:1 and group chats (iOS ca013fc8: group
 * recording like 1:1; iOS `VoiceNoteRecorder` and `ChatDetailView`): the button stops the
 * recording (so does the length limit), the note is encoded as it will be sent ("Encoding…"),
 * then it is "Audio ready" with a play / pause preview, and the button sends it ([take]). The
 * trash ([cancel]) discards it at any stage.
 *
 * On chain the note is the parts it goes as (iOS 7c5c22e3, [VoiceNoteCodec.encodeParts]), and the
 * preview is those parts decoded and joined, so it sounds as the other side will hear it. Via
 * Nextcloud it is the one file as recorded, which is what uploads.
 */
class VoiceNoteComposer(
    private val scope: CoroutineScope,
    private val recorder: VoiceRecorderService,
    private val cacheDir: File,
    /** The on-chain path: [VoiceNotePolicy.ONE_TO_ONE] or [VoiceNotePolicy.GROUP]. */
    private val path: VoiceNotePolicy.Path,
    private val state: MutableStateFlow<ChatViewModel.VoiceRecordingState>,
    private val onFailure: (String) -> Unit,
) {
    /** A note ready to send, handed over by [take]. */
    sealed class Ready {
        /** On chain: the parts, in order (one part, sent untagged, when the note is that short). */
        class OnChain(val parts: List<ByteArray>) : Ready()
        /** Via Nextcloud: the recorded file, which the caller uploads and then deletes. */
        class Nextcloud(val file: File) : Ready()
    }

    private var tickerJob: Job? = null
    private var encodeJob: Job? = null
    private var previewJob: Job? = null
    private var ready: Ready? = null
    private var previewFile: File? = null
    private var player: MediaPlayer? = null
    /** Bumped by every start and cancel: an older stop's encode is then stale. */
    private var generation = 0

    private val status: ChatViewModel.VoiceRecordingStatus get() = state.value.status

    /** Starts recording; false when the microphone couldn't be opened ([onFailure] says why). */
    fun start(viaNextcloud: Boolean): Boolean {
        if (status == ChatViewModel.VoiceRecordingStatus.RECORDING || status == ChatViewModel.VoiceRecordingStatus.ENCODING) return false
        discardReady()
        generation++
        val maxMs = if (viaNextcloud) VoiceRecorderService.MAX_NEXTCLOUD_RECORDING_DURATION_MS else path.maxMs
        try {
            if (viaNextcloud) {
                recorder.startRecording(VoiceRecorderService.MAX_NEXTCLOUD_RECORDING_DURATION_MS)
            } else {
                recorder.startOnChainRecording(path)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not start voice recording", e)
            onFailure("Failed to start recording: ${com.kachat.app.util.UserFacingError.message(e, "Recording failed. Please try again.")}")
            return false
        }
        state.value = ChatViewModel.VoiceRecordingState(
            status = ChatViewModel.VoiceRecordingStatus.RECORDING,
            maxMs = maxMs,
            viaNextcloud = viaNextcloud,
        )
        val startedAt = System.currentTimeMillis()
        tickerJob = scope.launch {
            while (isActive && status == ChatViewModel.VoiceRecordingStatus.RECORDING) {
                val elapsed = System.currentTimeMillis() - startedAt
                state.value = state.value.copy(elapsedMs = elapsed)
                // The length limit stops, never sends (iOS ca013fc8).
                if (recorder.reachedLimit(elapsed)) {
                    stop()
                    break
                }
                delay(200)
            }
        }
        return true
    }

    /** Stops recording and encodes the note as it will be sent, then it is ready with a preview.
     *  A stray tap (under half a second) is discarded silently. */
    fun stop() {
        if (status != ChatViewModel.VoiceRecordingStatus.RECORDING) return
        tickerJob?.cancel()
        tickerJob = null
        val current = state.value
        val recording = recorder.stop()
        if (recording == null || current.elapsedMs < VoiceRecorderService.MIN_RECORDING_DURATION_MS) {
            (recording as? VoiceRecorderService.Recording.SingleFile)?.file?.delete()
            state.value = ChatViewModel.VoiceRecordingState()
            return
        }
        state.value = current.copy(status = ChatViewModel.VoiceRecordingStatus.ENCODING)
        val token = generation
        encodeJob = scope.launch {
            var preview: File? = null
            try {
                val result: Ready
                when (recording) {
                    is VoiceRecorderService.Recording.Pcm -> {
                        val parts = withContext(Dispatchers.Default) { VoiceNoteCodec.encodeParts(recording.samples, path) }
                        val wav = File(cacheDir, "voice_preview_${System.nanoTime()}.wav")
                        preview = withContext(Dispatchers.Default) { VoiceNoteCodec.decodePartsToWav(parts, wav) }?.file
                        result = Ready.OnChain(parts)
                    }
                    is VoiceRecorderService.Recording.SingleFile -> {
                        preview = recording.file
                        result = if (current.viaNextcloud) {
                            Ready.Nextcloud(recording.file)
                        } else {
                            Ready.OnChain(listOf(withContext(Dispatchers.IO) { recording.file.readBytes() }))
                        }
                    }
                }
                if (token != generation || status != ChatViewModel.VoiceRecordingStatus.ENCODING) {
                    preview?.delete()
                    (result as? Ready.Nextcloud)?.file?.delete()
                    return@launch
                }
                ready = result
                previewFile = preview
                val durationMs = previewDuration(preview) ?: current.elapsedMs
                state.value = current.copy(
                    status = ChatViewModel.VoiceRecordingStatus.READY,
                    previewDurationMs = durationMs,
                    readyPartSizes = (result as? Ready.OnChain)?.parts?.map { it.size },
                )
            } catch (e: Exception) {
                Log.e(TAG, "Could not encode the voice note", e)
                preview?.delete()
                (recording as? VoiceRecorderService.Recording.SingleFile)?.file?.delete()
                if (token != generation) return@launch
                state.value = ChatViewModel.VoiceRecordingState()
                onFailure("Failed to encode audio: ${com.kachat.app.util.UserFacingError.message(e, "Recording failed. Please try again.")}")
            }
        }
    }

    /** The ready note, for sending; the composer goes back to idle. From here the caller owns a
     *  Nextcloud recording's file. */
    fun take(): Ready? {
        if (status != ChatViewModel.VoiceRecordingStatus.READY) return null
        val taken = ready ?: return null
        ready = null
        stopPreview()
        player?.let { runCatching { it.release() } }
        player = null
        val preview = previewFile
        previewFile = null
        if (preview != null && (taken as? Ready.Nextcloud)?.file != preview) preview.delete()
        state.value = ChatViewModel.VoiceRecordingState()
        return taken
    }

    /** Throws the recording away, whatever stage it's at: the trash. */
    fun cancel() {
        generation++
        tickerJob?.cancel()
        tickerJob = null
        encodeJob?.cancel()
        encodeJob = null
        if (status == ChatViewModel.VoiceRecordingStatus.RECORDING) recorder.cancelRecording()
        discardReady()
        state.value = ChatViewModel.VoiceRecordingState()
    }

    /** Play or pause the ready note's preview. */
    fun togglePreview() {
        if (state.value.previewPlaying) {
            stopPreview()
            return
        }
        if (status != ChatViewModel.VoiceRecordingStatus.READY) return
        val file = previewFile ?: return
        try {
            val mediaPlayer = player ?: MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
            }.also { player = it }
            mediaPlayer.setOnCompletionListener { stopPreview() }
            mediaPlayer.start()
            state.value = state.value.copy(previewPlaying = true, previewPositionMs = 0)
            previewJob?.cancel()
            previewJob = scope.launch {
                while (isActive && state.value.previewPlaying) {
                    state.value = state.value.copy(previewPositionMs = runCatching { mediaPlayer.currentPosition.toLong() }.getOrDefault(0L))
                    delay(250)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not play the voice note preview", e)
            stopPreview()
        }
    }

    private fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        player?.let { mediaPlayer ->
            runCatching { if (mediaPlayer.isPlaying) mediaPlayer.pause() }
            runCatching { mediaPlayer.seekTo(0) }
        }
        if (state.value.previewPlaying || state.value.previewPositionMs != 0L) {
            state.value = state.value.copy(previewPlaying = false, previewPositionMs = 0)
        }
    }

    private fun previewDuration(file: File?): Long? {
        file ?: return null
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun discardReady() {
        stopPreview()
        player?.let { runCatching { it.release() } }
        player = null
        previewFile?.delete()
        previewFile = null
        (ready as? Ready.Nextcloud)?.file?.delete()
        ready = null
    }

    /** The screen's ViewModel is going: nothing may outlive it. */
    fun release() = cancel()

    private companion object {
        const val TAG = "VoiceNoteComposer"
    }
}
