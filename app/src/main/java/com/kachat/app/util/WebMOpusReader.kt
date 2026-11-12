package com.kachat.app.util

/**
 * Reads a WebM-Opus voice note's packets (iOS `WebMOpusDecoder.parseWebM`): the OpusHead, every
 * SimpleBlock / Block payload in order, and the Info Duration - what the long voice note player
 * needs to join parts with no gap (iOS 7c5c22e3 `decodePartsToPCMFile`: drop each part's
 * pre-skip, cut it to its Duration). Laced blocks are skipped, unknown elements stepped over,
 * and nesting is bounded so a crafted file can't run away.
 */
object WebMOpusReader {
    class Parsed(
        val opusHead: ByteArray,
        val packets: List<ByteArray>,
        /** Segment Info's Duration in ms (TimecodeScale 1 ms, what our writers put), if any. */
        val durationMs: Double?,
    ) {
        val channels: Int get() = if (opusHead.size > 9) maxOf(1, opusHead[9].toInt() and 0xFF) else 1
        val preSkip: Int get() = if (opusHead.size > 11) (opusHead[10].toInt() and 0xFF) or ((opusHead[11].toInt() and 0xFF) shl 8) else 0
    }

    private val CONTAINERS = setOf(
        0x18538067L, // Segment
        0x1549A966L, // Info
        0x1654AE6BL, // Tracks
        0xAEL, // TrackEntry
        0x1F43B675L, // Cluster
        0xA0L, // BlockGroup
    )
    private const val MAX_DEPTH = 16
    private val OPUS_HEAD = "OpusHead".toByteArray(Charsets.US_ASCII)

    /** Null when [data] has no OpusHead or is not EBML that can be walked. */
    fun parse(data: ByteArray): Parsed? {
        var opusHead: ByteArray? = null
        val packets = ArrayList<ByteArray>()
        var durationMs: Double? = null

        fun walk(start: Int, end: Int, depth: Int) {
            if (depth > MAX_DEPTH) throw IllegalArgumentException("nested too deeply")
            var offset = start
            while (offset < end) {
                val (id, idLength) = readVint(data, offset, keepMarker = true) ?: return
                val sizeOffset = offset + idLength
                if (sizeOffset >= end) return
                var unknown = false
                val payloadStart: Int
                val size: Long
                if (id == 0x18538067L && sizeOffset + 8 <= end && (0 until 8).all { data[sizeOffset + it] == 0xFF.toByte() }) {
                    unknown = true
                    payloadStart = sizeOffset + 8
                    size = 0
                } else {
                    val (value, length) = readVint(data, sizeOffset, keepMarker = false) ?: return
                    // All value bits set is the "unknown size" marker - honoured on containers
                    // only: the writers here (and iOS's) encode a 127-byte block's size as 0xFF.
                    unknown = id in CONTAINERS && value == (1L shl (7 * length)) - 1
                    payloadStart = sizeOffset + length
                    size = value
                }
                if (payloadStart > end) return
                val payloadEnd = if (unknown) end else {
                    val e = payloadStart.toLong() + size
                    if (e < payloadStart) return
                    minOf(e, end.toLong()).toInt()
                }
                when {
                    id == 0x63A2L -> opusHead = data.copyOfRange(payloadStart, payloadEnd)
                    id == 0x4489L -> durationMs = readFloat(data, payloadStart, payloadEnd)
                    id == 0xA3L || id == 0xA1L -> block(data, payloadStart, payloadEnd)?.let { packets.add(it) }
                    id in CONTAINERS -> walk(payloadStart, payloadEnd, depth + 1)
                }
                if (payloadEnd <= offset) return
                offset = payloadEnd
            }
        }

        return try {
            walk(0, data.size, 0)
            val head = opusHead ?: return null
            if (head.size < 19 || !head.copyOfRange(0, 8).contentEquals(OPUS_HEAD)) return null
            Parsed(head, packets, durationMs)
        } catch (e: Exception) {
            null
        }
    }

    /** A block's frame: past the track number, the 16-bit timecode and the flags; laced: null. */
    private fun block(data: ByteArray, start: Int, end: Int): ByteArray? {
        val (_, trackLength) = readVint(data, start, keepMarker = false) ?: return null
        val offset = start + trackLength
        if (offset + 3 > end) return null
        val flags = data[offset + 2].toInt() and 0xFF
        if ((flags shr 1) and 3 != 0) return null
        return data.copyOfRange(offset + 3, end)
    }

    private fun readVint(data: ByteArray, offset: Int, keepMarker: Boolean): Pair<Long, Int>? {
        if (offset >= data.size) return null
        val first = data[offset].toInt() and 0xFF
        var mask = 0x80
        var length = 1
        while (length <= 8 && first and mask == 0) {
            mask = mask shr 1
            length++
        }
        if (length > 8 || offset + length > data.size) return null
        var value = (if (keepMarker) first else first and (mask - 1)).toLong()
        for (i in 1 until length) value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        return value to length
    }

    /** An EBML float: 4 or 8 bytes, big-endian; null for any other length or a non-finite or
     *  non-positive value. */
    private fun readFloat(data: ByteArray, start: Int, end: Int): Double? {
        var bits = 0L
        for (i in start until end) bits = (bits shl 8) or (data[i].toLong() and 0xFF)
        val value = when (end - start) {
            8 -> Double.fromBits(bits)
            4 -> Float.fromBits(bits.toInt()).toDouble()
            else -> return null
        }
        return value.takeIf { it.isFinite() && it > 0 }
    }
}
