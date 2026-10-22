package com.kachat.app.services

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.kachat.app.util.VoiceMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records short voice messages as Opus-in-WebM — the exact container/codec iOS embeds directly
 * in the encrypted on-chain message payload (`ChatDetailView.swift`, `WebMOpusEncoder`), so a
 * message recorded on Android decodes and plays back correctly on iOS and vice versa.
 * Sample rate/bitrate match iOS's `opusSampleRate`/`opusBitrate` exactly.
 *
 * Where the platform Opus encoder is there (practically every Android 10+ device) the note is
 * [OpusWebMRecorder]'s: PCM -> MediaCodec Opus -> iOS's own WebM layout, byte for byte the file an
 * iPhone writes (iOS 48f0dce), ~1.06 KB a second - a 1:1 note really reaches 25 s inside 31 KB.
 * Without it, [MediaRecorder]'s Opus/WebM records instead (~1.6 KB a second), stopped by its
 * written bytes before the ceiling, as before.
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
    private var outputFile: File? = null
    private var maxDurationMs = MAX_NEXTCLOUD_RECORDING_DURATION_MS
    private var maxBytes: Long? = null
    private var fallbackStopsAtBytes = false

    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** The active recording is iOS's own WebM layout ([OpusWebMRecorder]), not MediaRecorder's -
     *  the fee preview sizes it by [VoiceMessage.estimatedRecordingBytes]'s matching rate. */
    val writesOwnWebM: Boolean get() = opusRecorder != null

    /**
     * Starts recording to a fresh temp file in app cache. Throws if unsupported or already recording.
     *
     * [maxDurationMs] and [maxBytes] are the note's ceilings - [reachedLimit] says when it is
     * there. [maxBytes] is iOS's `maxBytes`: the own-WebM note stops before the packet that would
     * pass it and its file is trimmed to it as iOS trims. MediaRecorder's note (the fallback) only
     * stops by bytes when [fallbackStopsAtBytes] - the 1:1 note, whose 25 s it can't fit.
     */
    fun startRecording(
        maxDurationMs: Long = MAX_NEXTCLOUD_RECORDING_DURATION_MS,
        maxBytes: Long? = null,
        fallbackStopsAtBytes: Boolean = false
    ): File {
        check(isSupported) { "Voice message recording requires Android 10 or newer" }
        check(recorder == null && opusRecorder == null) { "A recording is already in progress" }

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

    /** Bytes the active recording holds so far (0 when not recording): the own-WebM file's exact
     *  length, or what MediaRecorder has written - a container that writes late shows less than
     *  it holds. */
    fun currentRecordingBytes(): Long = opusRecorder?.currentBytes?.toLong() ?: outputFile?.length() ?: 0L

    /**
     * The active recording has reached its ceiling after [elapsedMs] on the wall clock. The
     * own-WebM note counts its captured audio itself (so it holds the full 25 s, not 25 s minus
     * the mic's start-up), with the wall clock only as a backstop; MediaRecorder's note stops on
     * the wall clock, and by its written bytes or its measured rate when asked to.
     */
    fun reachedLimit(elapsedMs: Long): Boolean {
        opusRecorder?.let { return it.limitReached || elapsedMs >= maxDurationMs + OWN_WEBM_WALL_CLOCK_GRACE_MS }
        if (elapsedMs >= maxDurationMs) return true
        val cap = maxBytes ?: return false
        return fallbackStopsAtBytes &&
            maxOf(currentRecordingBytes(), VoiceMessage.estimatedRecordingBytes(elapsedMs, ownWebM = false).toLong()) >= cap
    }

    /** Stops the active recording and returns the file, or null if nothing usable was captured. */
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
        private const val BIT_RATE_BPS = 6_000
        /** On-chain voice notes in groups and public chats (payload-bound, unchanged). */
        const val MAX_RECORDING_DURATION_MS = 10_000L
        /** On-chain 1:1 voice notes, which travel in the binary media frame (iOS f5f6a11): up to
         *  25 s or [MAX_DIRECT_ON_CHAIN_VOICE_BYTES], whichever comes first - as long as one
         *  transaction carries with margin. Size follows length, so a short note costs what it did. */
        const val MAX_DIRECT_ON_CHAIN_RECORDING_DURATION_MS = 25_000L
        /** iOS `maxAudioBytes`: ~31 KB of Opus in the frame, ~42 KB of payload. */
        const val MAX_DIRECT_ON_CHAIN_VOICE_BYTES = 31_000L
        /** iOS `PublicChatAudioRecorder.maxBytes` (groups and public chats): the own-WebM note's
         *  trim ceiling there - 10 s of it is ~10.8 KB, so it never cuts one short. */
        const val GROUP_ON_CHAIN_VOICE_BYTES = 13_000L
        /** Nextcloud-uploaded voice notes aren't payload-bound — the server carries them — so a
         *  "Record via Nextcloud" note runs to this, everywhere in the app (1:1, groups and
         *  public chats). 5 minutes, down from 10 (iOS 8b13460 `ComposerMediaLimits`). */
        const val MAX_NEXTCLOUD_RECORDING_DURATION_MS = 300_000L
        const val MIN_RECORDING_DURATION_MS = 500L
        /** How far the wall clock may run past an own-WebM note's ceiling before it is stopped
         *  anyway - normally its own audio count stops it first. */
        private const val OWN_WEBM_WALL_CLOCK_GRACE_MS = 2_000L
    }
}
