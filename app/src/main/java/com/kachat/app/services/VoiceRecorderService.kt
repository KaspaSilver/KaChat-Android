package com.kachat.app.services

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records short voice messages as Opus-in-WebM — the exact container/codec iOS embeds directly
 * in the encrypted on-chain message payload (`ChatDetailView.swift:2314-2323`, `WebMOpusEncoder`),
 * so a message recorded on Android decodes and plays back correctly on iOS and vice versa.
 * Sample rate/bitrate match iOS's `opusSampleRate`/`opusBitrate` exactly.
 *
 * Recording needs [MediaRecorder]'s OPUS encoder, only available from API 29 — callers must
 * check [isSupported] before offering the mic button. Every device this app runs on can still
 * play back a received audio message regardless (Opus *decoding* has been available since API 21).
 */
@Singleton
class VoiceRecorderService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Starts recording to a fresh temp file in app cache. Throws if unsupported or already recording. */
    fun startRecording(): File {
        check(isSupported) { "Voice message recording requires Android 10 or newer" }
        check(recorder == null) { "A recording is already in progress" }

        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.webm")
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

    /** Stops the active recording and returns the file, or null if nothing usable was captured. */
    fun stopRecording(): File? {
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
        const val MAX_RECORDING_DURATION_MS = 10_000L
        /** Nextcloud-uploaded voice notes aren't payload-bound — the server carries them —
         *  so 1:1 recording relaxes to this while "Send Media via Nextcloud" is active. */
        const val MAX_NEXTCLOUD_RECORDING_DURATION_MS = 600_000L
        const val MIN_RECORDING_DURATION_MS = 500L
    }
}
