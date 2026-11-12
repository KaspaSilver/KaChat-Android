package com.kachat.app.services

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.kachat.app.util.OpusPackets
import com.kachat.app.util.VoiceNotePolicy
import com.kachat.app.util.VoicePartTag
import com.kachat.app.util.WebMOpusReader
import com.kachat.app.util.WebMOpusWriter
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Long on-chain voice notes, both ways (iOS 7c5c22e3, MESSAGING.md "Long voice notes
 * (multi-part)").
 *
 * - [encodeParts]: a recording as the parts it goes as - iOS `WebMOpusEncoder.encodeParts`. Parts
 *   of at most the path's part length (14.5 s in 1:1, 9.8 s in groups), up to 12; one file when
 *   the note is that short. Each part is a complete WebM-Opus file at 16 kbps in 60 ms packets,
 *   its encoder also fed the codec's look-ahead from the next part, its Info Duration the part's
 *   real length.
 * - [decodePartsToWav]: the parts present, in order, as ONE PCM file - iOS
 *   `WebMOpusDecoder.decodePartsToPCMFile`: each part decoded on its own, its pre-skip dropped and
 *   cut to its Duration, so the joins are gapless and one player seeks across the whole note.
 */
object VoiceNoteCodec {
    private const val TAG = "VoiceNoteCodec"
    private const val MIME = MediaFormat.MIMETYPE_AUDIO_OPUS
    private const val SAMPLE_RATE = OpusPackets.SAMPLE_RATE_HZ
    /** iOS's libopus look-ahead at 48 kHz, fed past each part's end. */
    private const val LOOKAHEAD_SAMPLES = 312
    private const val FRAME_20_MS = SAMPLE_RATE / 50
    private const val FRAME_60_MS = SAMPLE_RATE * VoiceNotePolicy.FRAME_MS / 1000
    private const val TIMEOUT_US = 10_000L
    private const val STALL_NS = 3_000_000_000L
    private val OPUS_HEAD_MAGIC = "OpusHead".toByteArray(Charsets.US_ASCII)

    /** The platform Opus encoder is there (the parts need it). */
    val canEncode: Boolean get() = OpusWebMRecorder.encoderNameOrNull != null

    /**
     * [pcm] (48 kHz mono) as its parts, in order. Audio past [maxParts] parts is dropped, and so
     * is a last scrap under 0.2 s after a full part (the tap that stopped the recording), which
     * would otherwise cost a whole transaction. Throws when the encoder can't run.
     */
    fun encodeParts(pcm: ShortArray, path: VoiceNotePolicy.Path, maxParts: Int = VoicePartTag.MAX_PARTS): List<ByteArray> {
        require(pcm.isNotEmpty()) { "No audio was recorded." }
        val partSamples = maxOf(1, (path.partSeconds * SAMPLE_RATE).toInt())
        var total = minOf(pcm.size, partSamples * maxOf(1, maxParts))
        val remainder = total % partSamples
        if (total > partSamples && remainder > 0 && remainder < (0.2 * SAMPLE_RATE).toInt()) total -= remainder
        val parts = ArrayList<ByteArray>()
        var start = 0
        while (start < total) {
            val end = minOf(start + partSamples, total)
            parts.add(encodeRange(pcm, start, end, path.maxBytes))
            start = end
        }
        return parts
    }

    /** `pcm[start, end)` as one WebM-Opus file, its encoder also given the look-ahead past [end]
     *  (the next samples, else silence). */
    private fun encodeRange(pcm: ShortArray, start: Int, end: Int, maxBytes: Int): ByteArray {
        val name = OpusWebMRecorder.encoderNameOrNull ?: throw IllegalStateException("No Opus encoder on this device.")
        val content = end - start
        val available = minOf(pcm.size, end + LOOKAHEAD_SAMPLES) - start
        val wanted = content + LOOKAHEAD_SAMPLES
        // Whole 60 ms packets, the last zero-padded, as iOS's encoder pads its last frame.
        val padded = (wanted + FRAME_60_MS - 1) / FRAME_60_MS * FRAME_60_MS
        val codec = MediaCodec.createByCodecName(name)
        val packets = ArrayList<ByteArray>()
        var preSkip = WebMOpusWriter.DEFAULT_PRE_SKIP
        try {
            val format = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, VoiceNotePolicy.BITRATE)
                setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_20_MS * 2)
                // iOS sets VBR off (opus_encoder_set_vbr(0)); ask for the same where offered.
                val cbr = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                val caps = runCatching { codec.codecInfo.getCapabilitiesForType(MIME).encoderCapabilities }.getOrNull()
                if (caps?.isBitrateModeSupported(cbr) == true) setInteger(MediaFormat.KEY_BITRATE_MODE, cbr)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            val frame = ByteArray(FRAME_20_MS * 2)
            var fed = 0
            var inputDone = false
            var outputDone = false
            var lastProgress = System.nanoTime()
            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index) ?: throw IllegalStateException("No encoder input buffer")
                        buffer.clear()
                        val presentationUs = fed.toLong() * 1_000_000L / SAMPLE_RATE
                        if (fed >= padded) {
                            codec.queueInputBuffer(index, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            for (i in 0 until FRAME_20_MS) {
                                val at = fed + i
                                val sample = if (at < available) pcm[start + at].toInt() else 0
                                frame[2 * i] = sample.toByte()
                                frame[2 * i + 1] = (sample shr 8).toByte()
                            }
                            buffer.put(frame, 0, frame.size)
                            codec.queueInputBuffer(index, 0, frame.size, presentationUs, 0)
                            fed += FRAME_20_MS
                        }
                        lastProgress = System.nanoTime()
                    }
                }
                val out = codec.dequeueOutputBuffer(info, if (inputDone) TIMEOUT_US else 0L)
                if (out >= 0) {
                    val buffer = codec.getOutputBuffer(out)
                    if (buffer != null && info.size > 0) {
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(bytes, 0, info.size)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            preSkipFrom(bytes)?.let { preSkip = it }
                        } else {
                            packets.add(bytes)
                        }
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    lastProgress = System.nanoTime()
                } else if (System.nanoTime() - lastProgress > STALL_NS) {
                    throw IllegalStateException("The Opus encoder stalled.")
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        if (packets.isEmpty()) throw IllegalStateException("The Opus encoder made nothing.")
        val joined = OpusPackets.joinInto60ms(packets)
        return WebMOpusWriter.encodePart(
            packets = joined.map { it.first },
            packetSamples = joined.map { it.second },
            contentSamples = content.toLong(),
            preSkip = preSkip,
            maxBytes = maxBytes,
        )
    }

    /** The pre-skip of the encoder's header (a bare OpusHead, or Android's "AOPUSHDR" wrapping). */
    private fun preSkipFrom(bytes: ByteArray): Int? {
        outer@ for (i in 0..bytes.size - OPUS_HEAD_MAGIC.size) {
            for (j in OPUS_HEAD_MAGIC.indices) if (bytes[i + j] != OPUS_HEAD_MAGIC[j]) continue@outer
            if (i + 12 > bytes.size) return null
            return (bytes[i + 10].toInt() and 0xFF) or ((bytes[i + 11].toInt() and 0xFF) shl 8)
        }
        return null
    }

    class DecodedNote(val file: File, val durationMs: Long, val decodedParts: Int)

    /**
     * The parts present ([parts], in index order) as one 16-bit 48 kHz mono WAV at [output]. A
     * part that doesn't decode is left out; null when none does. Heavy: call it off the main
     * thread.
     */
    fun decodePartsToWav(parts: List<ByteArray>, output: File): DecodedNote? {
        var totalSamples = 0L
        var decodedParts = 0
        // A part is at most 14.5 s (25 s accepted); a crafted file can't grow one past this.
        val maxPartSamples = ((VoicePartTag.MAX_PART_SECONDS + 1) * SAMPLE_RATE).toInt()
        try {
            RandomAccessFile(output, "rw").use { file ->
                file.setLength(0)
                file.write(ByteArray(44))
                for (part in parts.take(VoicePartTag.MAX_PARTS)) {
                    val parsed = WebMOpusReader.parse(part) ?: continue
                    if (parsed.packets.isEmpty()) continue
                    var mono = decodePart(parsed, maxPartSamples) ?: continue
                    // Cut to the part's own length: its packets also hold the next part's start.
                    parsed.durationMs?.let { ms ->
                        val wanted = Math.round(ms / 1000.0 * SAMPLE_RATE).toInt()
                        if (wanted in 1 until mono.size) mono = mono.copyOf(wanted)
                    }
                    if (mono.isEmpty()) continue
                    val bytes = ByteBuffer.allocate(mono.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    bytes.asShortBuffer().put(mono)
                    file.write(bytes.array())
                    totalSamples += mono.size
                    decodedParts++
                }
                if (decodedParts > 0 && totalSamples > 0L) {
                    file.seek(0)
                    file.write(wavHeader(totalSamples))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not decode the voice note's parts", e)
            output.delete()
            return null
        }
        if (decodedParts == 0 || totalSamples == 0L) {
            output.delete()
            return null
        }
        return DecodedNote(output, totalSamples * 1000L / SAMPLE_RATE, decodedParts)
    }

    /** One part's packets as mono 48 kHz samples, its pre-skip removed. */
    private fun decodePart(parsed: WebMOpusReader.Parsed, maxSamples: Int): ShortArray? {
        val codec = try {
            MediaCodec.createDecoderByType(MIME)
        } catch (e: Exception) {
            return null
        }
        val out = java.io.ByteArrayOutputStream()
        var channels = minOf(2, parsed.channels)
        try {
            val format = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, channels).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(parsed.opusHead))
                // No codec delay here: the pre-skip is dropped below, once, whatever the decoder.
                setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0L).apply { flip() })
                setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80_000_000L).apply { flip() })
            }
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var next = 0
            var presentationUs = 0L
            var inputDone = false
            var outputDone = false
            var lastProgress = System.nanoTime()
            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index) ?: throw IllegalStateException("No decoder input buffer")
                        buffer.clear()
                        if (next >= parsed.packets.size) {
                            codec.queueInputBuffer(index, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val packet = parsed.packets[next++]
                            buffer.put(packet)
                            codec.queueInputBuffer(index, 0, packet.size, presentationUs, 0)
                            presentationUs += (OpusPackets.packetSamples(packet) ?: FRAME_20_MS) * 1_000_000L / SAMPLE_RATE
                        }
                        lastProgress = System.nanoTime()
                    }
                }
                val index = codec.dequeueOutputBuffer(info, if (inputDone) TIMEOUT_US else 0L)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    channels = runCatching { codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(channels)
                } else if (index >= 0) {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 && out.size() < maxSamples * 2 * channels) {
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(bytes, 0, info.size)
                        out.write(bytes)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    lastProgress = System.nanoTime()
                } else if (System.nanoTime() - lastProgress > STALL_NS) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "A voice note part did not decode", e)
            return null
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        val raw = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frames = raw.remaining() / maxOf(1, channels)
        if (frames == 0) return null
        var mono = ShortArray(minOf(frames, maxSamples)) { i ->
            if (channels == 1) raw.get(i) else ((raw.get(i * channels) + raw.get(i * channels + 1)) / 2).toShort()
        }
        // The pre-skip, once: unless this decoder already dropped it (fewer samples than the
        // packets hold by about that much).
        val held = parsed.packets.sumOf { (OpusPackets.packetSamples(it) ?: 0).toLong() }
        val alreadyDropped = held > 0 && frames <= held - parsed.preSkip / 2
        if (!alreadyDropped && parsed.preSkip in 1 until mono.size) mono = mono.copyOfRange(parsed.preSkip, mono.size)
        return mono
    }

    private fun wavHeader(samples: Long): ByteArray {
        val dataBytes = samples * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36 + dataBytes).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(1) // mono
        header.putInt(SAMPLE_RATE)
        header.putInt(SAMPLE_RATE * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes.toInt())
        return header.array()
    }
}
