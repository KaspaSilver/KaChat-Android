package com.kachat.app.services

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.kachat.app.util.WebMOpusWriter
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * A voice note recorded the way iOS records one (iOS 48f0dce): 16-bit PCM at 48 kHz mono from
 * [AudioRecord], Opus at 6 kbps (CBR where the encoder offers it, 20 ms frames - the platform
 * Opus encoder's fixed frame) from [MediaCodec], wrapped by [WebMOpusWriter] - iOS's own WebM
 * layout, a second of packets per Cluster. ~1.06 KB a second, so a 1:1 note really reaches 25 s
 * inside the 31 KB cap. MediaRecorder's WebM costs ~1.6 KB a second and stopped at ~19 s.
 *
 * The recording ends at [maxDurationMs] of captured audio, or before the packet that would carry
 * the file past [maxBytes] ([limitReached] then turns true for the caller to stop); [finish]
 * drains the encoder and writes the file through the same trim rule iOS applies.
 */
internal class OpusWebMRecorder private constructor(
    private val audioRecord: AudioRecord,
    private val codec: MediaCodec,
    private val maxDurationMs: Long,
    private val maxBytes: Int?
) {
    private val trackUid = WebMOpusWriter.randomTrackUid()
    private val packets = ArrayList<ByteArray>()
    private var preSkip = WebMOpusWriter.DEFAULT_PRE_SKIP
    private var sizer = WebMOpusWriter.Sizer(WebMOpusWriter.opusHead(preSkip), trackUid)
    private var pcmSamples = 0L
    private var packetsFull = false
    private val bytesSoFar = AtomicInteger(sizer.totalBytes)

    @Volatile private var stopRequested = false
    @Volatile var limitReached = false
        private set
    @Volatile private var failure: Throwable? = null
    private val thread = Thread({ run() }, "OpusWebMRecorder")

    /** The file length the packets kept so far make. */
    val currentBytes: Int get() = bytesSoFar.get()

    private fun start() {
        thread.start()
    }

    /** Stops capture, drains the encoder and writes the note to [file]; false when nothing usable
     *  was captured. */
    fun finish(file: File): Boolean {
        stopRequested = true
        thread.join(JOIN_TIMEOUT_MS)
        failure?.let { Log.w(TAG, "Opus recording ended with an error", it) }
        if (thread.isAlive || packets.isEmpty()) return false
        val webm = WebMOpusWriter.encode(packets, pcmSamples, preSkip, maxBytes, trackUid)
        file.writeBytes(webm)
        return true
    }

    /** Stops and keeps nothing. */
    fun cancel() {
        stopRequested = true
        thread.join(JOIN_TIMEOUT_MS)
    }

    private fun run() {
        val maxSamples = maxDurationMs * WebMOpusWriter.SAMPLE_RATE_HZ / 1000
        val frameBytes = WebMOpusWriter.FRAME_SAMPLES * 2
        val frame = ByteArray(frameBytes)
        val info = MediaCodec.BufferInfo()
        try {
            audioRecord.startRecording()
            while (!stopRequested && !packetsFull && pcmSamples < maxSamples) {
                var filled = 0
                while (filled < frameBytes && !stopRequested) {
                    val read = audioRecord.read(frame, filled, frameBytes - filled)
                    if (read < 0) throw IllegalStateException("AudioRecord.read failed ($read)")
                    filled += read
                }
                if (filled < frameBytes) break // stopped mid-frame: only whole frames are kept
                queueInput(frame, frameBytes, endOfStream = false)
                pcmSamples += WebMOpusWriter.FRAME_SAMPLES
                drain(info, untilEndOfStream = false)
            }
            if (pcmSamples >= maxSamples || packetsFull) limitReached = true
            runCatching { audioRecord.stop() }
            queueInput(frame, 0, endOfStream = true)
            drain(info, untilEndOfStream = true)
        } catch (t: Throwable) {
            failure = t
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { audioRecord.release() }
        }
    }

    private fun queueInput(source: ByteArray, length: Int, endOfStream: Boolean) {
        var index = -1
        val deadline = System.nanoTime() + INPUT_WAIT_NS
        while (index < 0) {
            index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (index < 0) {
                drain(MediaCodec.BufferInfo(), untilEndOfStream = false)
                if (System.nanoTime() > deadline) throw IllegalStateException("Opus encoder took no input")
            }
        }
        val buffer = codec.getInputBuffer(index) ?: throw IllegalStateException("No encoder input buffer")
        buffer.clear()
        buffer.put(source, 0, length)
        val presentationUs = pcmSamples * 1_000_000L / WebMOpusWriter.SAMPLE_RATE_HZ
        codec.queueInputBuffer(index, 0, length, presentationUs, if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
    }

    private fun drain(info: MediaCodec.BufferInfo, untilEndOfStream: Boolean) {
        val deadline = System.nanoTime() + DRAIN_WAIT_NS
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (untilEndOfStream) DEQUEUE_TIMEOUT_US else 0L)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEndOfStream || System.nanoTime() > deadline) return
                continue
            }
            if (index < 0) continue // format / buffers changed
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                val bytes = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(bytes, 0, info.size)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    onCodecConfig(bytes)
                } else {
                    onPacket(bytes)
                }
            }
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    /** The encoder's header (a bare OpusHead, or Android's "AOPUSHDR" wrapping of one): only its
     *  pre-skip is taken - the OpusHead written is iOS's. */
    private fun onCodecConfig(bytes: ByteArray) {
        val at = indexOf(bytes, OPUS_HEAD_MAGIC)
        if (at < 0 || at + 12 > bytes.size) return
        val skip = (bytes[at + 10].toInt() and 0xFF) or ((bytes[at + 11].toInt() and 0xFF) shl 8)
        if (skip != preSkip && packets.isEmpty()) {
            preSkip = skip
            sizer = WebMOpusWriter.Sizer(WebMOpusWriter.opusHead(preSkip), trackUid)
            bytesSoFar.set(sizer.totalBytes)
        }
    }

    private fun onPacket(packet: ByteArray) {
        if (packetsFull) return
        if (maxBytes != null && packets.isNotEmpty() && sizer.totalWith(packet.size) > maxBytes) {
            // The next packet would carry the note past its ceiling: it, and anything after, stays out.
            packetsFull = true
            limitReached = true
            return
        }
        packets.add(packet)
        sizer.add(packet.size)
        bytesSoFar.set(sizer.totalBytes)
    }

    companion object {
        private const val TAG = "OpusWebMRecorder"
        private const val MIME = MediaFormat.MIMETYPE_AUDIO_OPUS
        private const val PREFERRED_ENCODER = "c2.android.opus.encoder"
        private const val BIT_RATE_BPS = 6_000
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val INPUT_WAIT_NS = 2_000_000_000L
        private const val DRAIN_WAIT_NS = 2_000_000_000L
        private const val JOIN_TIMEOUT_MS = 4_000L
        private val OPUS_HEAD_MAGIC = "OpusHead".toByteArray(Charsets.US_ASCII)

        /** The platform Opus encoder (API 29+), preferring AOSP's Codec2 one; null if none. */
        private val encoderName: String? by lazy {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@lazy null
            runCatching {
                val names = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                    .filter { info -> info.isEncoder && info.supportedTypes.any { it.equals(MIME, ignoreCase = true) } }
                    .map { it.name }
                names.firstOrNull { it == PREFERRED_ENCODER } ?: names.firstOrNull()
            }.getOrNull()
        }

        val isAvailable: Boolean get() = encoderName != null

        /** Starts a recording, or returns null when this device can't record this way (the caller
         *  then falls back to MediaRecorder). */
        @SuppressLint("MissingPermission") // the mic button asks for RECORD_AUDIO before recording
        fun start(maxDurationMs: Long, maxBytes: Int?): OpusWebMRecorder? {
            val name = encoderName ?: return null
            var codec: MediaCodec? = null
            var record: AudioRecord? = null
            return try {
                codec = MediaCodec.createByCodecName(name)
                val format = MediaFormat.createAudioFormat(MIME, WebMOpusWriter.SAMPLE_RATE_HZ, 1).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE_BPS)
                    setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, WebMOpusWriter.FRAME_SAMPLES * 2)
                    // iOS sets VBR off (opus_encoder_set_vbr(0)); ask for the same where offered.
                    val cbr = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    val caps = runCatching { codec.codecInfo.getCapabilitiesForType(MIME).encoderCapabilities }.getOrNull()
                    if (caps?.isBitrateModeSupported(cbr) == true) setInteger(MediaFormat.KEY_BITRATE_MODE, cbr)
                }
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()

                val minBuffer = AudioRecord.getMinBufferSize(
                    WebMOpusWriter.SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuffer <= 0) throw IllegalStateException("48 kHz mono PCM capture unsupported ($minBuffer)")
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    WebMOpusWriter.SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, WebMOpusWriter.FRAME_SAMPLES * 2 * 10)
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("AudioRecord did not initialize")
                OpusWebMRecorder(record, codec, maxDurationMs, maxBytes).also { it.start() }
            } catch (e: Exception) {
                Log.w(TAG, "Opus encoder unavailable for this recording; falling back to MediaRecorder", e)
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { record?.release() }
                null
            }
        }

        private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
            outer@ for (i in 0..haystack.size - needle.size) {
                for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }
    }
}
