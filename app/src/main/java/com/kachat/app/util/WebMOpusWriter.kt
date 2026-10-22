package com.kachat.app.util

import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * iOS's voice-note WebM writer (`WebMOpusEncoder`, `ChatDetailView.swift`, iOS 48f0dce), byte for
 * byte: the same EBML header, Segment, Info (TimecodeScale, a float64 Duration that states the
 * audio actually kept, "kasia-ios" muxing/writing app), Tracks (one A_OPUS track, a 19-byte
 * OpusHead as CodecPrivate) and Clusters of about a second (50 packets) each - a SimpleBlock per
 * 20 ms packet carrying its offset from the Cluster timecode, the keyframe flag, no lacing.
 * 25 s of 15-byte packets is 26,651 bytes - the 31 KB on-chain cap is met with ~4 KB to spare.
 *
 * Every size and value encoding below copies iOS's exactly (including its shortest-unsigned and
 * VINT rules), so a note recorded here is the file an iPhone would write for the same packets -
 * `WebMOpusWriterTest` checks it against outputs of the Swift writer itself.
 */
object WebMOpusWriter {
    const val SAMPLE_RATE_HZ = 48_000

    /** iOS `frameSize`: 20 ms at 48 kHz. */
    const val FRAME_SAMPLES = SAMPLE_RATE_HZ / 50

    /** iOS `frameDurationMs` - computed the same way (20). */
    val FRAME_DURATION_MS: Long = ((FRAME_SAMPLES.toDouble() / SAMPLE_RATE_HZ.toDouble()) * 1000.0).toLong()

    /** iOS's libopus lookahead at 48 kHz (`opus_encoder_get_lookahead`): the OpusHead pre-skip
     *  when an encoder does not report its own. */
    const val DEFAULT_PRE_SKIP = 312

    private val PACKETS_PER_CLUSTER: Int = maxOf(1, (1000 / maxOf(FRAME_DURATION_MS, 1L)).toInt())

    private val ID_EBML = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
    private val ID_SEGMENT = byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67)
    private val ID_INFO = byteArrayOf(0x15, 0x49, 0xA9.toByte(), 0x66)
    private val ID_TRACKS = byteArrayOf(0x16, 0x54, 0xAE.toByte(), 0x6B)
    private val ID_CLUSTER = byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75)
    private val ID_CLUSTER_TIMECODE = byteArrayOf(0xE7.toByte())
    private val ID_SIMPLE_BLOCK = byteArrayOf(0xA3.toByte())

    /** iOS's TrackUID: a random 32-bit value. */
    fun randomTrackUid(): Long = Random.nextLong(0L, 1L shl 32)

    /** iOS `makeOpusHead`: version 1, [channels], little-endian pre-skip/sample rate/gain, mapping 0. */
    fun opusHead(preSkip: Int, channels: Int = 1, sampleRate: Int = SAMPLE_RATE_HZ, outputGain: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("OpusHead".toByteArray(Charsets.US_ASCII))
        out.write(1)
        out.write(channels)
        val skip = preSkip.coerceIn(0, 0xFFFF)
        out.write(skip and 0xFF); out.write((skip shr 8) and 0xFF)
        out.write(sampleRate and 0xFF); out.write((sampleRate shr 8) and 0xFF)
        out.write((sampleRate shr 16) and 0xFF); out.write((sampleRate shr 24) and 0xFF)
        out.write(outputGain and 0xFF); out.write((outputGain shr 8) and 0xFF)
        out.write(0)
        return out.toByteArray()
    }

    /**
     * iOS `WebMOpusEncoder.encode` from its packets on: [pcmSampleCount] is the recorded PCM
     * (the last packet's frame zero-padded), the Info duration is what the kept packets hold, and
     * over [maxBytes] trailing packets are dropped (never the first) until the file fits.
     */
    fun encode(
        packets: List<ByteArray>,
        pcmSampleCount: Long,
        preSkip: Int = DEFAULT_PRE_SKIP,
        maxBytes: Int? = null,
        trackUid: Long = randomTrackUid()
    ): ByteArray {
        val head = opusHead(preSkip)
        val sampleRate = SAMPLE_RATE_HZ.toDouble()
        val durationSeconds = pcmSampleCount.toDouble() / sampleRate
        var count = packets.size
        if (maxBytes != null) {
            val sizer = Sizer(head, trackUid)
            val sizes = IntArray(packets.size)
            for (i in packets.indices) { sizer.add(packets[i].size); sizes[i] = sizer.totalBytes }
            while (count > 1 && sizes[count - 1] > maxBytes) count--
        }
        val kept = if (count == packets.size) packets else packets.subList(0, count)
        val keptDuration = minOf(durationSeconds, kept.size.toDouble() * FRAME_DURATION_MS.toDouble() / 1000.0)
        return build(kept, head, sampleRate, keptDuration, FRAME_DURATION_MS, trackUid)
    }

    /** iOS `buildWebM`. */
    fun build(
        packets: List<ByteArray>,
        opusHead: ByteArray,
        sampleRate: Double,
        durationSeconds: Double,
        frameDurationMs: Long,
        trackUid: Long
    ): ByteArray {
        val ebmlHeader = ebmlHeader()
        val info = info(durationSeconds)
        val tracks = tracks(opusHead, sampleRate, channels = 1, trackUid = trackUid)
        val clusters = clusters(packets, frameDurationMs)
        val segmentPayloadSize = info.size + tracks.size + clusters.size
        val out = ByteArrayOutputStream(ebmlHeader.size + 12 + segmentPayloadSize)
        out.write(ebmlHeader)
        out.write(ID_SEGMENT)
        out.write(encodeVint(segmentPayloadSize.toLong()))
        out.write(info)
        out.write(tracks)
        out.write(clusters)
        return out.toByteArray()
    }

    /**
     * The exact length [build] would write, kept packet by packet - a recording stops before the
     * packet that would carry it past its byte ceiling, without rebuilding the file each time.
     */
    class Sizer(opusHead: ByteArray, trackUid: Long) {
        private val fixedSegmentBytes = info(0.0).size + tracks(opusHead, SAMPLE_RATE_HZ.toDouble(), 1, trackUid).size
        private val ebmlHeaderBytes = ebmlHeader().size
        private var closedClustersBytes = 0L
        private var openClusterPayload = 0L
        private var packetCount = 0

        /** File length with [packetCount] packets, as built. */
        var totalBytes: Int = sizeWith(0L, 0L, 0)
            private set

        val packets: Int get() = packetCount

        /** The file length if one more packet of [packetSize] bytes were kept. */
        fun totalWith(packetSize: Int): Int {
            val (closed, open) = advance(packetSize)
            return sizeWith(closed, open, packetCount + 1)
        }

        fun add(packetSize: Int) {
            val (closed, open) = advance(packetSize)
            closedClustersBytes = closed
            openClusterPayload = open
            packetCount++
            totalBytes = sizeWith(closedClustersBytes, openClusterPayload, packetCount)
        }

        private fun advance(packetSize: Int): Pair<Long, Long> {
            val indexInCluster = packetCount % PACKETS_PER_CLUSTER
            var closed = closedClustersBytes
            var open = openClusterPayload
            if (indexInCluster == 0) {
                if (packetCount > 0) closed += elementSize(ID_CLUSTER.size, open)
                open = elementSize(ID_CLUSTER_TIMECODE.size, encodeUnsigned(packetCount.toLong() * FRAME_DURATION_MS).size.toLong())
            }
            open += elementSize(ID_SIMPLE_BLOCK.size, 4L + packetSize)
            return closed to open
        }

        private fun sizeWith(closed: Long, open: Long, count: Int): Int {
            val clusters = closed + if (count > 0) elementSize(ID_CLUSTER.size, open) else 0L
            val segmentPayload = fixedSegmentBytes + clusters
            return (ebmlHeaderBytes + ID_SEGMENT.size + encodeVint(segmentPayload).size + segmentPayload).toInt()
        }

        private fun elementSize(idBytes: Int, payload: Long): Long = idBytes + encodeVint(payload).size + payload
    }

    private fun ebmlHeader(): ByteArray = element(
        ID_EBML,
        join(
            element(byteArrayOf(0x42, 0x86.toByte()), 1),
            element(byteArrayOf(0x42, 0xF7.toByte()), 1),
            element(byteArrayOf(0x42, 0xF2.toByte()), 4),
            element(byteArrayOf(0x42, 0xF3.toByte()), 8),
            element(byteArrayOf(0x42, 0x82.toByte()), "webm"),
            element(byteArrayOf(0x42, 0x87.toByte()), 4),
            element(byteArrayOf(0x42, 0x85.toByte()), 2)
        )
    )

    private fun info(durationSeconds: Double): ByteArray {
        val timecodeScale = 1_000_000L
        val durationMs = durationSeconds * 1000.0
        return element(
            ID_INFO,
            join(
                element(byteArrayOf(0x2A, 0xD7.toByte(), 0xB1.toByte()), timecodeScale),
                element(byteArrayOf(0x44, 0x89.toByte()), durationMs),
                element(byteArrayOf(0x4D, 0x80.toByte()), "kasia-ios"),
                element(byteArrayOf(0x57, 0x41), "kasia-ios")
            )
        )
    }

    private fun tracks(opusHead: ByteArray, sampleRate: Double, channels: Int, trackUid: Long): ByteArray {
        val audio = element(
            byteArrayOf(0xE1.toByte()),
            join(
                element(byteArrayOf(0xB5.toByte()), sampleRate),
                element(byteArrayOf(0x9F.toByte()), channels.toLong())
            )
        )
        val entry = element(
            byteArrayOf(0xAE.toByte()),
            join(
                element(byteArrayOf(0xD7.toByte()), 1),
                element(byteArrayOf(0x73, 0xC5.toByte()), trackUid),
                element(byteArrayOf(0x83.toByte()), 2),
                element(byteArrayOf(0x86.toByte()), "A_OPUS"),
                element(byteArrayOf(0x63, 0xA2.toByte()), opusHead),
                audio
            )
        )
        return element(ID_TRACKS, entry)
    }

    /** iOS `makeClusters`: ~1 s of packets per Cluster, its timecode the first packet's. */
    private fun clusters(packets: List<ByteArray>, frameDurationMs: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val perCluster = maxOf(1, (1000 / maxOf(frameDurationMs, 1L)).toInt())
        var index = 0
        while (index < packets.size) {
            val end = minOf(index + perCluster, packets.size)
            out.write(cluster(index.toLong() * frameDurationMs, packets.subList(index, end), frameDurationMs))
            index = end
        }
        return out.toByteArray()
    }

    private fun cluster(timecodeMs: Long, packets: List<ByteArray>, frameDurationMs: Long): ByteArray {
        val payload = ByteArrayOutputStream()
        payload.write(element(ID_CLUSTER_TIMECODE, timecodeMs))
        for ((offset, packet) in packets.withIndex()) {
            // relative to the Cluster's timecode: at most ~1 s, inside the signed 16 bits (iOS clamps)
            val relative = (offset.toLong() * frameDurationMs).coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toInt()
            payload.write(simpleBlock(packet, relative))
        }
        return element(ID_CLUSTER, payload.toByteArray())
    }

    private fun simpleBlock(packet: ByteArray, relativeMs: Int): ByteArray {
        val block = ByteArray(4 + packet.size)
        block[0] = 0x81.toByte() // track number 1 (VINT)
        block[1] = (relativeMs shr 8).toByte() // relative timecode, int16 big-endian
        block[2] = relativeMs.toByte()
        block[3] = 0x80.toByte() // flags: keyframe (every Opus packet decodes on its own); no lacing
        System.arraycopy(packet, 0, block, 4, packet.size)
        return element(ID_SIMPLE_BLOCK, block)
    }

    private fun join(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(parts.sumOf { it.size })
        for (part in parts) out.write(part)
        return out.toByteArray()
    }

    private fun element(id: ByteArray, payload: ByteArray): ByteArray {
        val size = encodeVint(payload.size.toLong())
        val out = ByteArray(id.size + size.size + payload.size)
        System.arraycopy(id, 0, out, 0, id.size)
        System.arraycopy(size, 0, out, id.size, size.size)
        System.arraycopy(payload, 0, out, id.size + size.size, payload.size)
        return out
    }

    private fun element(id: ByteArray, value: Long): ByteArray = element(id, encodeUnsigned(value))

    private fun element(id: ByteArray, value: Int): ByteArray = element(id, value.toLong())

    private fun element(id: ByteArray, value: Double): ByteArray = element(id, encodeFloat64(value))

    private fun element(id: ByteArray, value: String): ByteArray = element(id, value.toByteArray(Charsets.UTF_8))

    /** iOS `encodeUnsigned`: big-endian, shortest form, at least one byte (0 is `00`). */
    private fun encodeUnsigned(value: Long): ByteArray {
        var length = 1
        var temp = value ushr 8
        while (temp != 0L) { length++; temp = temp ushr 8 }
        return ByteArray(length) { i -> (value ushr (8 * (length - 1 - i))).toByte() }
    }

    private fun encodeFloat64(value: Double): ByteArray {
        val bits = value.toRawBits()
        return ByteArray(8) { i -> (bits ushr (8 * (7 - i))).toByte() }
    }

    /** iOS `encodeVint`: the shortest length whose all-ones value still holds [value] (iOS's
     *  `<=`, kept so the bytes match), else the 8-byte unknown size. */
    private fun encodeVint(value: Long): ByteArray {
        for (length in 1..8) {
            val maxValue = (1L shl (7 * length)) - 1
            if (value <= maxValue) {
                val bytes = ByteArray(length) { i -> (value ushr (8 * (length - 1 - i))).toByte() }
                bytes[0] = (bytes[0].toInt() or (1 shl (8 - length))).toByte()
                return bytes
            }
        }
        return byteArrayOf(0x01) + ByteArray(7) { 0xFF.toByte() }
    }
}
