package com.kachat.app.util

/**
 * Opus packet framing (RFC 6716 §3), for the long voice note's 60 ms packets (iOS 7c5c22e3,
 * `VoiceNotePolicy`: 16 kbps CBR in 60 ms frames).
 *
 * iOS's libopus encodes 60 ms frames directly. The platform Opus encoder here (Codec2's
 * `c2.android.opus.encoder`) only makes 20 ms packets, so three of them are joined into one
 * 60 ms packet: a code 3 packet carrying the three frames, which any Opus decoder reads exactly
 * as it reads libopus's own. At 16 kbps CBR each 20 ms packet is 40 bytes (one TOC byte and 39 of
 * frame), so the joined packet is 1 + 1 + 3 x 39 = 119 bytes against libopus's 120, and a part
 * fits the same 31,000 / 21,000-byte caps.
 */
object OpusPackets {
    const val SAMPLE_RATE_HZ = 48_000

    /** Samples per frame at 48 kHz for a TOC byte's configuration (RFC 6716 Table 2). */
    fun frameSamples(toc: Int): Int {
        val config = (toc and 0xFF) ushr 3
        return when {
            config < 12 -> intArrayOf(480, 960, 1920, 2880)[config and 3] // SILK: 10/20/40/60 ms
            config < 16 -> intArrayOf(480, 960)[config and 1] // Hybrid: 10/20 ms
            else -> intArrayOf(120, 240, 480, 960)[config and 3] // CELT: 2.5/5/10/20 ms
        }
    }

    /** The number of frames in [packet], or null when it is malformed (RFC 6716 §3.2). */
    fun frameCount(packet: ByteArray): Int? {
        if (packet.isEmpty()) return null
        return when (packet[0].toInt() and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (packet.size < 2) null else (packet[1].toInt() and 0x3F).takeIf { it > 0 }
        }
    }

    /** The samples [packet] decodes to at 48 kHz, or null when it is malformed. */
    fun packetSamples(packet: ByteArray): Int? {
        val frames = frameCount(packet) ?: return null
        return frames * frameSamples(packet[0].toInt())
    }

    /**
     * [packets] - single-frame (code 0) packets of one configuration and channel count - as one
     * code 3 packet holding their frames in order: constant-bitrate when the frames are all one
     * length, else with the frame lengths written out. Null when they can't be joined (another
     * packet code, different configurations, more than 120 ms, a frame over 1275 bytes); the
     * caller then keeps them as they are.
     */
    fun join(packets: List<ByteArray>): ByteArray? {
        if (packets.isEmpty() || packets.size > 48) return null
        val toc = packets[0].takeIf { it.isNotEmpty() }?.get(0)?.toInt()?.and(0xFF) ?: return null
        if (packets.size == 1) return packets[0]
        for (packet in packets) {
            if (packet.isEmpty()) return null
            val t = packet[0].toInt() and 0xFF
            if (t and 3 != 0) return null // only single-frame packets
            if (t and 0xFC != toc and 0xFC) return null // same configuration and stereo flag
            if (packet.size - 1 > 1275) return null
        }
        if (frameSamples(toc) * packets.size > 5760) return null // 120 ms at most
        val frames = packets.map { it.copyOfRange(1, it.size) }
        val cbr = frames.all { it.size == frames[0].size }
        val out = java.io.ByteArrayOutputStream()
        out.write((toc and 0xFC) or 3)
        out.write((if (cbr) 0 else 0x80) or packets.size)
        if (!cbr) {
            for (frame in frames.dropLast(1)) {
                val length = frame.size
                if (length < 252) {
                    out.write(length)
                } else {
                    val first = 252 + (length and 3)
                    out.write(first)
                    out.write((length - first) ushr 2)
                }
            }
        }
        for (frame in frames) out.write(frame)
        return out.toByteArray()
    }

    /**
     * 20 ms packets as 60 ms ones, in order: each run of three joined ([join]); a run that can't
     * be (the encoder changed mode inside it), and a last run of fewer than three, stay as they
     * are. Returns each packet with its length in samples.
     */
    fun joinInto60ms(packets20: List<ByteArray>): List<Pair<ByteArray, Int>> {
        val out = ArrayList<Pair<ByteArray, Int>>(packets20.size / 3 + 2)
        var i = 0
        while (i < packets20.size) {
            val run = packets20.subList(i, minOf(i + 3, packets20.size))
            val joined = if (run.size == 3) join(run) else null
            if (joined != null) {
                out.add(joined to (packetSamples(joined) ?: 2880))
            } else {
                for (packet in run) out.add(packet to (packetSamples(packet) ?: 960))
            }
            i += run.size
        }
        return out
    }
}
