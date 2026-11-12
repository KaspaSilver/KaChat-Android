package com.kachat.app.services

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.kachat.app.util.VoiceMessage
import com.kachat.app.util.VoiceNotePolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records voice messages as Opus-in-WebM — the container/codec iOS embeds directly in the
 * encrypted on-chain message payload, so a message recorded on Android decodes and plays back
 * correctly on iOS and vice versa.
 *
 * - On chain ([startOnChainRecording], iOS 7c5c22e3): raw PCM ([PcmVoiceRecorder]) up to the
 *   path's note length - 2:50 in 1:1, 1:50 in groups ([VoiceNotePolicy]) - which Stop hands back
 *   to be encoded as the parts it goes as ([VoiceNoteCodec.encodeParts], 16 kbps in 60 ms
 *   packets). A device without the platform Opus encoder records one part's length the old way
 *   instead, as a single note.
 * - Via Nextcloud ([startRecording]): one file as captured, by [OpusWebMRecorder] (iOS's own WebM
 *   layout, iOS 48f0dce) where the platform Opus encoder is there, else [MediaRecorder].
 *
 * Recording needs Android 10 (API 29) either way — callers must check [isSupported] before
 * offering the mic button. Every device this app runs on can still play back a received audio
 * message regardless (Opus *decoding* has been available since API 21).
 */
@Singleton
class VoiceRecorderService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var recorder: MediaRecorder? = null
    private var opusRecorder: OpusWebMRecorder? = null
    private var pcmRecorder: PcmVoiceRecorder? = null
    private var outputFile: File? = null
    private var maxDurationMs = MAX_NEXTCLOUD_RECORDING_DURATION_MS
    private var maxBytes: Long? = null
    private var fallbackStopsAtBytes = false

    /** What a stopped recording is. */
    sealed class Recording {
        /** An on-chain note's PCM (48 kHz mono), to be encoded as its parts. */
        class Pcm(val samples: ShortArray) : Recording()
        /** One finished file: a Nextcloud recording, or an on-chain note recorded the old way. */
        class SingleFile(val file: File) : Recording()
    }

    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** The active recording is iOS's own WebM layout ([OpusWebMRecorder]), not MediaRecorder's -
     *  the fee preview sizes it by [VoiceMessage.estimatedRecordingBytes]'s matching rate. */
    val writesOwnWebM: Boolean get() = opusRecorder != null

    val isRecording: Boolean get() = recorder != null || opusRecorder != null || pcmRecorder != null

    /**
     * Starts an on-chain voice note on [path] (iOS 7c5c22e3 `VoiceNotePolicy`): up to its note
     * length, encoded into parts on stop. Without the platform Opus encoder (the parts need it) it
     * records one part's length as a single note, as before. Throws if unsupported or already
     * recording.
     */
    fun startOnChainRecording(path: VoiceNotePolicy.Path) {
        check(isSupported) { "Voice message recording requires Android 10 or newer" }
        check(!isRecording) { "A recording is already in progress" }
        if (VoiceNoteCodec.canEncode) {
            val maxSamples = (path.maxSeconds * PcmVoiceRecorder.SAMPLE_RATE_HZ).toInt()
            PcmVoiceRecorder.start(maxSamples)?.let { started ->
                pcmRecorder = started
                maxDurationMs = path.maxMs
                maxBytes = null
                fallbackStopsAtBytes = false
                return
            }
        }
        startRecording(maxDurationMs = path.partMs, maxBytes = path.maxBytes.toLong(), fallbackStopsAtBytes = true)
    }

    /**
     * Starts recording one file to a fresh temp file in app cache - the Nextcloud route, and the
     * on-chain fallback above. Throws if unsupported or already recording.
     *
     * [maxDurationMs] and [maxBytes] are the note's ceilings - [reachedLimit] says when it is
     * there. [maxBytes] is iOS's `maxBytes`: the own-WebM note stops before the packet that would
     * pass it and its file is trimmed to it as iOS trims. MediaRecorder's note only stops by
     * bytes when [fallbackStopsAtBytes].
     */
    fun startRecording(
        maxDurationMs: Long = MAX_NEXTCLOUD_RECORDING_DURATION_MS,
        maxBytes: Long? = null,
        fallbackStopsAtBytes: Boolean = false
    ): File {
        check(isSupported) { "Voice message recording requires Android 10 or newer" }
        check(!isRecording) { "A recording is already in progress" }

        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.webm")
        this.maxDurationMs = maxDurationMs
        this.maxBytes = maxBytes
        this.fallbackStopsAtBytes = fallbackStopsAtBytes
        OpusWebMRecorder.start(maxDurationMs, maxBytes?.toInt())?.let { started ->
            opusRecorder = started
            outputFile = file
            return file
        }
        val newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        try {
            newRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.WEBM)
                setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                setAudioSamplingRate(SAMPLE_RATE_HZ)
                setAudioEncodingBitRate(BIT_RATE_BPS)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            newRecorder.release()
            file.delete()
            throw e
        }
        recorder = newRecorder
        outputFile = file
        return file
    }

    /** Bytes the active single-file recording holds so far (0 otherwise). */
    fun currentRecordingBytes(): Long = opusRecorder?.currentBytes?.toLong() ?: outputFile?.length() ?: 0L

    /**
     * The active recording has reached its ceiling after [elapsedMs] on the wall clock. The PCM
     * and own-WebM notes count their captured audio themselves (so they hold the full length, not
     * the length minus the mic's start-up), with the wall clock only as a backstop; MediaRecorder's
     * note stops on the wall clock, and by its written bytes or its measured rate when asked to.
     */
    fun reachedLimit(elapsedMs: Long): Boolean {
        pcmRecorder?.let { return it.limitReached || elapsedMs >= maxDurationMs + OWN_WEBM_WALL_CLOCK_GRACE_MS }
        opusRecorder?.let { return it.limitReached || elapsedMs >= maxDurationMs + OWN_WEBM_WALL_CLOCK_GRACE_MS }
        if (elapsedMs >= maxDurationMs) return true
        val cap = maxBytes ?: return false
        return fallbackStopsAtBytes &&
            maxOf(currentRecordingBytes(), VoiceMessage.estimatedRecordingBytes(elapsedMs, ownWebM = false).toLong()) >= cap
    }

    /** Stops the active recording and returns it, or null if nothing usable was captured. */
    fun stop(): Recording? {
        pcmRecorder?.let { pcm ->
            pcmRecorder = null
            return pcm.stop()?.let { Recording.Pcm(it) }
        }
        return stopRecording()?.let { Recording.SingleFile(it) }
    }

    /** Stops the active single-file recording and returns the file, or null if nothing usable
     *  was captured. */
    fun stopRecording(): File? {
        opusRecorder?.let { opus ->
            opusRecorder = null
            val file = outputFile
            outputFile = null
            val kept = file != null && runCatching { opus.finish(file) }
                .onFailure { Log.w("VoiceRecorderService", "Could not write the recorded voice note", it) }
                .getOrDefault(false)
            if (!kept) file?.delete()
            return if (kept) file else null
        }
        val current = recorder ?: return null
        recorder = null
        val file = outputFile
        outputFile = null
        return try {
            current.stop()
            current.release()
            file
        } catch (e: Exception) {
            // MediaRecorder.stop() throws IllegalStateException if called too soon after
            // start() (roughly under a second) — nothing usable was recorded.
            Log.w("VoiceRecorderService", "Recording stopped before anything usable was captured", e)
            current.release()
            file?.delete()
            null
        }
    }

    /** Stops (if needed) and discards the in-progress recording — no message should ever come out of this. */
    fun cancelRecording() {
        pcmRecorder?.let { pcm ->
            pcmRecorder = null
            pcm.cancel()
            return
        }
        opusRecorder?.let { opus ->
            opusRecorder = null
            opus.cancel()
            outputFile?.delete()
            outputFile = null
            return
        }
        val current = recorder ?: return
        recorder = null
        val file = outputFile
        outputFile = null
        try {
            current.stop()
        } catch (e: Exception) {
            // Same short-recording case as stopRecording — irrelevant here since we're discarding anyway.
        }
        current.release()
        file?.delete()
    }

    companion object {
        private const val SAMPLE_RATE_HZ = 48_000
        /** The single-file recorders' rate (the Nextcloud route and the no-encoder fallback). */
        private const val BIT_RATE_BPS = 6_000
        /** Nextcloud-uploaded voice notes aren't payload-bound — the server carries them — so a
         *  "Record via Nextcloud" note runs to this, everywhere in the app (1:1, groups and
         *  public chats). 5 minutes (iOS `ComposerMediaLimits.nextcloudVoiceSeconds`). */
        const val MAX_NEXTCLOUD_RECORDING_DURATION_MS = 300_000L
        const val MIN_RECORDING_DURATION_MS = 500L
        /** How far the wall clock may run past a note's ceiling before it is stopped anyway -
         *  normally its own audio count stops it first. */
        private const val OWN_WEBM_WALL_CLOCK_GRACE_MS = 2_000L
    }
}
