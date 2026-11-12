package com.kachat.app.services

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * An on-chain voice note's raw recording (iOS 7c5c22e3: the composer records PCM, and Stop
 * encodes it as the parts it will go as - `WebMOpusEncoder.encodeParts`): 16-bit PCM at 48 kHz
 * mono from [AudioRecord], kept in memory - 2:50 is ~16 MB - until [stop] hands it to
 * [VoiceNoteCodec.encodeParts]. The note ends at [maxSamples] of captured audio ([limitReached]
 * then turns true for the caller to stop).
 */
internal class PcmVoiceRecorder private constructor(
    private val audioRecord: AudioRecord,
    private val maxSamples: Int,
) {
    private var buffer = ShortArray(minOf(maxSamples, SAMPLE_RATE_HZ * 15))
    private val captured = AtomicInteger(0)

    @Volatile private var stopRequested = false
    @Volatile var limitReached = false
        private set
    @Volatile private var failure: Throwable? = null
    private val thread = Thread({ run() }, "PcmVoiceRecorder")

    /** Samples captured so far. */
    val capturedSamples: Int get() = captured.get()

    private fun start() {
        thread.start()
    }

    /** Stops capture and returns what was recorded, or null when nothing was. */
    fun stop(): ShortArray? {
        stopRequested = true
        thread.join(JOIN_TIMEOUT_MS)
        failure?.let { Log.w(TAG, "PCM capture ended with an error", it) }
        if (thread.isAlive) return null
        val count = captured.get()
        if (count <= 0) return null
        return buffer.copyOf(count)
    }

    /** Stops and keeps nothing. */
    fun cancel() {
        stopRequested = true
        thread.join(JOIN_TIMEOUT_MS)
        buffer = ShortArray(0)
    }

    private fun run() {
        val chunk = ShortArray(FRAME_SAMPLES)
        try {
            audioRecord.startRecording()
            while (!stopRequested) {
                val room = maxSamples - captured.get()
                if (room <= 0) {
                    limitReached = true
                    break
                }
                val read = audioRecord.read(chunk, 0, minOf(chunk.size, room))
                if (read < 0) throw IllegalStateException("AudioRecord.read failed ($read)")
                if (read == 0) continue
                val count = captured.get()
                if (count + read > buffer.size) {
                    buffer = buffer.copyOf(minOf(maxSamples, maxOf(buffer.size * 2, count + read)))
                }
                System.arraycopy(chunk, 0, buffer, count, read)
                captured.set(count + read)
            }
        } catch (t: Throwable) {
            failure = t
        } finally {
            runCatching { audioRecord.stop() }
            runCatching { audioRecord.release() }
        }
    }

    companion object {
        private const val TAG = "PcmVoiceRecorder"
        const val SAMPLE_RATE_HZ = 48_000
        private const val FRAME_SAMPLES = SAMPLE_RATE_HZ / 50
        private const val JOIN_TIMEOUT_MS = 4_000L

        /** Starts capturing, or returns null when the microphone can't be opened this way. */
        @SuppressLint("MissingPermission") // the mic button asks for RECORD_AUDIO (MediaAccess) first
        fun start(maxSamples: Int): PcmVoiceRecorder? {
            var record: AudioRecord? = null
            return try {
                val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuffer <= 0) throw IllegalStateException("48 kHz mono PCM capture unsupported ($minBuffer)")
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, FRAME_SAMPLES * 2 * 10)
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("AudioRecord did not initialize")
                PcmVoiceRecorder(record, maxOf(1, maxSamples)).also { it.start() }
            } catch (e: Exception) {
                Log.w(TAG, "PCM capture unavailable", e)
                runCatching { record?.release() }
                null
            }
        }
    }
}
