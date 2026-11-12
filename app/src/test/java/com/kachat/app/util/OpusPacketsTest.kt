package com.kachat.app.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Three 20 ms packets as one 60 ms code 3 packet (RFC 6716 §3.2.5) - the long voice note's
 *  packets on Android, whose platform encoder only makes 20 ms ones (iOS 7c5c22e3 encodes 60 ms). */
class OpusPacketsTest {

    /** SILK wideband 20 ms, mono: configuration 9, so a TOC of 9 << 3. */
    private val silkWb20 = 9 shl 3

    private fun packet(toc: Int, frameBytes: Int, seed: Int = 0): ByteArray =
        byteArrayOf(toc.toByte()) + ByteArray(frameBytes) { ((it * 11 + seed) and 0xFF).toByte() }

    @Test
    fun `frame lengths follow the configuration`() {
        assertEquals(960, OpusPackets.frameSamples(silkWb20))
        assertEquals(2880, OpusPackets.frameSamples(11 shl 3)) // SILK WB 60 ms
        assertEquals(960, OpusPackets.frameSamples(13 shl 3)) // Hybrid FB 20 ms
        assertEquals(960, OpusPackets.frameSamples(31 shl 3)) // CELT FB 20 ms
        assertEquals(120, OpusPackets.frameSamples(28 shl 3)) // CELT 2.5 ms
    }

    @Test
    fun `three 40-byte CBR packets become one 119-byte 60 ms packet`() {
        val packets = List(3) { packet(silkWb20, 39, it) }
        val joined = OpusPackets.join(packets)!!
        assertEquals(119, joined.size)
        assertEquals(silkWb20 or 3, joined[0].toInt() and 0xFF)
        assertEquals(3, joined[1].toInt()) // CBR, no padding, 3 frames
        assertArrayEquals(packets.flatMap { it.drop(1) }.toByteArray(), joined.copyOfRange(2, joined.size))
        assertEquals(3, OpusPackets.frameCount(joined))
        assertEquals(2880, OpusPackets.packetSamples(joined))
    }

    @Test
    fun `frames of different lengths are written out VBR`() {
        val packets = listOf(packet(silkWb20, 39, 1), packet(silkWb20, 37, 2), packet(silkWb20, 300, 3))
        val joined = OpusPackets.join(packets)!!
        assertEquals(0x80 or 3, joined[1].toInt() and 0xFF)
        assertEquals(39, joined[2].toInt() and 0xFF)
        assertEquals(37, joined[3].toInt() and 0xFF)
        assertEquals(2 + 2 + 39 + 37 + 300, joined.size)
        // the last frame's length is implied
        assertArrayEquals(packets[2].copyOfRange(1, 301), joined.copyOfRange(joined.size - 300, joined.size))
    }

    @Test
    fun `a frame of 252 bytes or more takes two length bytes`() {
        val packets = listOf(packet(silkWb20, 300, 1), packet(silkWb20, 39, 2), packet(silkWb20, 39, 3))
        val joined = OpusPackets.join(packets)!!
        val first = joined[2].toInt() and 0xFF
        val second = joined[3].toInt() and 0xFF
        assertEquals(300, first + 4 * second)
        assertEquals(39, joined[4].toInt() and 0xFF)
    }

    @Test
    fun `packets that can't share a code 3 packet are left alone`() {
        // different configurations (the encoder changed bandwidth inside the run)
        assertNull(OpusPackets.join(listOf(packet(silkWb20, 39), packet(10 shl 3, 39), packet(silkWb20, 39))))
        // not single-frame packets
        assertNull(OpusPackets.join(listOf(packet(silkWb20 or 1, 39), packet(silkWb20, 39), packet(silkWb20, 39))))
        // stereo next to mono
        assertNull(OpusPackets.join(listOf(packet(silkWb20, 39), packet(silkWb20 or 4, 39), packet(silkWb20, 39))))
        // over 120 ms
        assertNull(OpusPackets.join(List(3) { packet(11 shl 3, 39) }))
    }

    @Test
    fun `runs of three are joined, the rest kept as they come, each with its length`() {
        val run1 = List(3) { packet(silkWb20, 39, it) }
        // another 20 ms mode (SILK mediumband, config 5) in the middle: the run can't be joined
        val run2 = listOf(packet(silkWb20, 39), packet(5 shl 3, 39), packet(silkWb20, 39))
        val tail = listOf(packet(silkWb20, 39))
        val out = OpusPackets.joinInto60ms(run1 + run2 + tail)
        assertEquals(listOf(2880, 960, 960, 960, 960), out.map { it.second })
        assertArrayEquals(OpusPackets.join(run1), out[0].first)
        assertArrayEquals(run2[1], out[2].first)
        assertArrayEquals(tail[0], out[4].first)
    }
}
