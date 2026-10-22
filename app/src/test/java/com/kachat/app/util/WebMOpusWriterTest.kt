package com.kachat.app.util

import com.kachat.app.services.VoiceRecorderService
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * [WebMOpusWriter] against iOS's own writer (iOS 48f0dce `WebMOpusEncoder`): the resources under
 * `webm/` were written by the Swift code itself - `buildWebM` and everything under it, plus
 * `encode`'s kept-duration and trim tail, copied verbatim and compiled standalone with only the
 * random TrackUID fixed - from the same deterministic packets these tests build.
 */
class WebMOpusWriterTest {

    /** Byte j of packet i, as the Swift harness wrote them: (i*31 + j*7 + 13) & 0xFF. */
    private fun packets(count: Int, size: (Int) -> Int): List<ByteArray> =
        List(count) { i -> ByteArray(size(i)) { j -> ((i * 31 + j * 7 + 13) and 0xFF).toByte() } }

    private fun vector(name: String): ByteArray =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("webm/ios48f0dce_$name.webm")) { name }.use { it.readBytes() }

    private class Case(val name: String, val packets: List<ByteArray>, val pcm: Long, val trackUid: Long, val maxBytes: Int?)

    private val cases = listOf(
        Case("p500", packets(500) { 15 }, 500L * 960 - 123, 0x1234ABCD, 31_000),
        Case("p1250", packets(1250) { 15 }, 1250L * 960, 0x9E, 31_000),
        Case("overcap", packets(1250) { i -> 15 + (i * 7) % 26 }, 1250L * 960, 0xFFFFFFFFL, 31_000),
        Case("p51nocap", packets(51) { i -> 1 + i % 20 }, 51L * 960 - 500, 0, null),
        Case("group600cap13k", packets(600) { i -> 15 + (i % 3) }, 600L * 960, 0x01020304, 13_000),
    )

    private fun write(case: Case) =
        WebMOpusWriter.encode(case.packets, case.pcm, preSkip = 312, maxBytes = case.maxBytes, trackUid = case.trackUid)

    @Test
    fun `every vector is byte-identical to the iOS writer`() {
        for (case in cases) {
            val expected = vector(case.name)
            val actual = write(case)
            assertEquals(case.name, expected.size, actual.size)
            assertArrayEquals(case.name, expected, actual)
        }
    }

    @Test
    fun `25 s of 6 kbps packets is 26,651 bytes - under the 31 KB cap, nothing trimmed`() {
        val case = cases.first { it.name == "p1250" }
        val file = write(case)
        assertEquals(26_651, file.size)
        assertTrue(file.size <= VoiceRecorderService.MAX_DIRECT_ON_CHAIN_VOICE_BYTES)
        val parsed = parse(file)
        assertEquals(1250, parsed.packets.size)
        for (i in case.packets.indices) assertArrayEquals(case.packets[i], parsed.packets[i])
        assertEquals(25_000.0, parsed.durationMs, 0.0)
        // a second of packets per Cluster, its timecode the first packet's, blocks 20 ms apart
        assertEquals(25, parsed.clusterTimecodes.size)
        assertEquals((0 until 25).map { it * 1000L }, parsed.clusterTimecodes)
        assertEquals((0 until 1250).map { (it % 50) * 20 }, parsed.blockRelativeMs)
        assertTrue(parsed.blockFlags.all { it == 0x80 })
    }

    @Test
    fun `over the cap the tail is dropped and the duration states what was kept`() {
        val case = cases.first { it.name == "overcap" }
        val file = write(case)
        assertTrue(file.size <= 31_000)
        val parsed = parse(file)
        val kept = parsed.packets.size
        assertTrue(kept in 2 until 1250)
        assertEquals(kept * 20.0, parsed.durationMs, 1e-9)
        // one packet more would not have fitted
        val sizer = WebMOpusWriter.Sizer(WebMOpusWriter.opusHead(312), case.trackUid)
        case.packets.take(kept).forEach { sizer.add(it.size) }
        assertEquals(file.size, sizer.totalBytes)
        assertTrue(sizer.totalWith(case.packets[kept].size) > 31_000)
    }

    @Test
    fun `the duration never claims more than the recorded PCM`() {
        val parsed = parse(write(cases.first { it.name == "p500" }))
        assertEquals((500.0 * 960 - 123) / 48_000.0 * 1000.0, parsed.durationMs, 0.0)
    }

    @Test
    fun `the OpusHead is iOS's 19 bytes`() {
        val parsed = parse(write(cases.first { it.name == "p500" }))
        assertArrayEquals(
            byteArrayOf(0x4F, 0x70, 0x75, 0x73, 0x48, 0x65, 0x61, 0x64, 1, 1, 0x38, 0x01, 0x80.toByte(), 0xBB.toByte(), 0, 0, 0, 0, 0),
            parsed.opusHead
        )
    }

    @Test
    fun `the sizer tracks the built length packet by packet`() {
        val sequence = packets(160) { i -> 1 + (i * 13) % 140 }
        for (trackUid in listOf(0L, 0xFFL, 0x1234ABCDL)) {
            val head = WebMOpusWriter.opusHead(312)
            val sizer = WebMOpusWriter.Sizer(head, trackUid)
            assertEquals(WebMOpusWriter.build(emptyList(), head, 48_000.0, 0.0, 20, trackUid).size, sizer.totalBytes)
            for (n in 1..sequence.size) {
                val predicted = sizer.totalWith(sequence[n - 1].size)
                sizer.add(sequence[n - 1].size)
                val built = WebMOpusWriter.build(sequence.subList(0, n), head, 48_000.0, n * 0.02, 20, trackUid).size
                assertEquals("n=$n", built, predicted)
                assertEquals("n=$n", built, sizer.totalBytes)
            }
        }
    }

    @Test
    fun `the recorder's stop rule keeps exactly what iOS's trim keeps`() {
        val case = cases.first { it.name == "overcap" }
        val sizer = WebMOpusWriter.Sizer(WebMOpusWriter.opusHead(312), case.trackUid)
        val kept = ArrayList<ByteArray>()
        for (packet in case.packets) {
            if (kept.isNotEmpty() && sizer.totalWith(packet.size) > 31_000) break
            kept.add(packet); sizer.add(packet.size)
        }
        val recorded = WebMOpusWriter.encode(kept, kept.size * 960L, 312, 31_000, case.trackUid)
        assertArrayEquals(vector("overcap"), recorded)
    }

    @Test
    fun `the 1-1 fee preview sizes the own WebM at its real rate`() {
        assertEquals(26_652, VoiceMessage.estimatedRecordingBytes(25_000))
        assertEquals(10_752, VoiceMessage.estimatedRecordingBytes(10_000))
        assertTrue(VoiceMessage.estimatedRecordingBytes(25_000) < VoiceRecorderService.MAX_DIRECT_ON_CHAIN_VOICE_BYTES)
    }

    // ---- iOS `WebMOpusDecoder.parseWebM` (MessageBubbleView.swift), ported to read the file back ----

    private class Parsed(
        val opusHead: ByteArray,
        val packets: List<ByteArray>,
        val durationMs: Double,
        val clusterTimecodes: List<Long>,
        val blockRelativeMs: List<Int>,
        val blockFlags: List<Int>
    )

    private fun parse(data: ByteArray): Parsed {
        var opusHead: ByteArray? = null
        var duration = Double.NaN
        val packets = ArrayList<ByteArray>()
        val timecodes = ArrayList<Long>()
        val relative = ArrayList<Int>()
        val flags = ArrayList<Int>()
        val containers = setOf(0x18538067L, 0x1549A966L, 0x1654AE6BL, 0xAEL, 0x1F43B675L, 0xA0L)

        fun readVInt(offset: Int, forSize: Boolean): Triple<Long, Int, Boolean> {
            val first = data[offset].toInt() and 0xFF
            var mask = 0x80
            var length = 1
            while (length <= 8 && (first and mask) == 0) { mask = mask shr 1; length++ }
            require(length <= 8 && offset + length <= data.size)
            var value = if (forSize) (first and (mask - 1)).toLong() else first.toLong()
            for (i in 1 until length) value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
            val unknown = forSize && value == (1L shl (7 * length)) - 1
            return Triple(value, length, unknown)
        }

        fun walk(start: Int, end: Int) {
            var offset = start
            while (offset < end) {
                val (id, idLen, _) = readVInt(offset, forSize = false)
                val sizeOffset = offset + idLen
                if (sizeOffset >= end) break
                val (size, sizeLen, unknown) = readVInt(sizeOffset, forSize = true)
                val payloadStart = sizeOffset + sizeLen
                if (payloadStart > end) break
                val payloadEnd = if (unknown) end else minOf(payloadStart + size.toInt(), end)
                when {
                    id == 0x63A2L -> opusHead = data.copyOfRange(payloadStart, payloadEnd)
                    id == 0x4489L -> duration = ByteBuffer.wrap(data, payloadStart, 8).double
                    id == 0xE7L -> timecodes.add((payloadStart until payloadEnd).fold(0L) { acc, i -> (acc shl 8) or (data[i].toLong() and 0xFF) })
                    id == 0xA3L || id == 0xA1L -> {
                        val (_, trackLen) = readVInt(payloadStart, forSize = true)
                        val at = payloadStart + trackLen
                        relative.add(((data[at].toInt() shl 8) or (data[at + 1].toInt() and 0xFF)).toShort().toInt())
                        val blockFlags = data[at + 2].toInt() and 0xFF
                        flags.add(blockFlags)
                        if ((blockFlags shr 1) and 0x03 == 0) packets.add(data.copyOfRange(at + 3, payloadEnd))
                    }
                    id in containers -> walk(payloadStart, payloadEnd)
                }
                if (payloadEnd <= offset) break
                offset = payloadEnd
            }
        }
        walk(0, data.size)
        return Parsed(requireNotNull(opusHead), packets, duration, timecodes, relative, flags)
    }
}
