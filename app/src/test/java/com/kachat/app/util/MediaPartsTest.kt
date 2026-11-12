package com.kachat.app.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Long voice notes and multi-part photos (iOS 7c5c22e3, MESSAGING.md "Long voice notes
 * (multi-part)" and "Photos: quality tiers and multi-part photos"): the part tag, the voice part
 * limits, the photo tier budgets, splitting into parts and putting them back together.
 */
class MediaPartsTest {

    private val noteId = "00112233445566778899aabbccddeeff"

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private fun voicePart(index: Int, count: Int, id: String = noteId, bytes: ByteArray = ByteArray(200) { index.toByte() }): String {
        val tag = VoicePartTag.of(id, index, count)!!
        return ContextualMediaFrame.mediaFileJson(tag.fileName, bytes.size, "audio/webm", "data:audio/webm;base64,${b64(bytes)}", voicePart = tag)
    }

    // --- the tag ---

    @Test
    fun `the tag's rules - count 2 to 12, index 1 to count, a hex id of 16 to 64`() {
        assertNotNull(VoicePartTag.of(noteId, 1, 2))
        assertNotNull(VoicePartTag.of(noteId, 12, 12))
        assertNotNull(VoicePartTag.of("0123456789abcdef", 1, 2))
        assertNotNull(VoicePartTag.of("a".repeat(64), 1, 2))
        assertNull(VoicePartTag.of(noteId, 1, 1))
        assertNull(VoicePartTag.of(noteId, 1, 13))
        assertNull(VoicePartTag.of(noteId, 0, 2))
        assertNull(VoicePartTag.of(noteId, 3, 2))
        assertNull(VoicePartTag.of("0123456789abcde", 1, 2))
        assertNull(VoicePartTag.of("a".repeat(65), 1, 2))
        assertNull(VoicePartTag.of("0123456789abcdeg", 1, 2))
        // compared lowercased
        assertEquals(VoicePartTag.of(noteId, 1, 2), VoicePartTag.of(noteId.uppercase(), 1, 2))
        assertEquals(noteId, VoicePartTag.of(noteId.uppercase(), 1, 2)!!.id)
    }

    @Test
    fun `the tag is written in a fixed key order, with iOS's file names`() {
        val tag = VoicePartTag.of(noteId, 2, 12)!!
        assertEquals("{\"id\":\"$noteId\",\"index\":2,\"count\":12}", tag.json)
        assertEquals("voice-2of12.webm", tag.fileName)
        assertEquals("photo-2of12.jpg", tag.photoFileName)
        assertEquals(32, VoicePartTag.newGroupId().length)
        assertTrue(VoicePartTag.newGroupId().all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `sniff reads the tag from the head - voicePart on audio, mediaPart on a photo, nothing else`() {
        assertEquals(VoicePartTag.of(noteId, 3, 5), VoicePartTag.sniff(voicePart(3, 5)))
        val tag = VoicePartTag.of(noteId, 1, 2)!!
        val photo = ContextualMediaFrame.mediaFileJson(tag.photoFileName, 3, "image/jpeg", "data:image/jpeg;base64,AQID", mediaPart = tag)
        assertEquals(tag, VoicePartTag.sniff(photo + " ".repeat(10)))
        // the other kind's key is no tag
        val misplaced = ContextualMediaFrame.mediaFileJson("p.jpg", 3, "image/jpeg", "data:image/jpeg;base64,AQID", voicePart = tag)
        assertNull(VoicePartTag.sniff(misplaced))
        // a plain note, plain text, a reply quoting a part
        assertNull(VoicePartTag.sniff(ContextualMediaFrame.mediaFileJson("v.webm", 3, "audio/webm", "data:audio/webm;base64,AQID")))
        assertNull(VoicePartTag.sniff("hello"))
        assertNull(VoicePartTag.sniff(null))
        assertNull(VoicePartTag.sniff(MessageReply.encode("tx", "kaspa:q", "🎤 Audio message", "a reply about it with enough text to be long")))
        // malformed values
        val bad = voicePart(3, 5).replace("\"count\":5", "\"count\":\"5\"")
        assertNull(VoicePartTag.sniff(bad))
    }

    // --- voice part limits ---

    @Test
    fun `the voice policies match iOS - 14_5 s parts to 2_50 in 1-1, 9_8 s to 1_50 in groups, 16 kbps in 60 ms`() {
        assertEquals(16_000, VoiceNotePolicy.BITRATE)
        assertEquals(60, VoiceNotePolicy.FRAME_MS)
        assertEquals(14.5, VoiceNotePolicy.ONE_TO_ONE.partSeconds, 0.0)
        assertEquals(170_000L, VoiceNotePolicy.ONE_TO_ONE.maxMs)
        assertEquals(31_000, VoiceNotePolicy.ONE_TO_ONE.maxBytes)
        assertEquals(9.8, VoiceNotePolicy.GROUP.partSeconds, 0.0)
        assertEquals(110_000L, VoiceNotePolicy.GROUP.maxMs)
        assertEquals(21_000, VoiceNotePolicy.GROUP.maxBytes)
    }

    @Test
    fun `a full part is the size MESSAGING_md works out, and fits its cap`() {
        val oneToOne = VoiceNoteLayout.partSizes(14.5, 14.5, maxBytes = 31_000)
        assertEquals(listOf(30_807), oneToOne)
        val group = VoiceNoteLayout.partSizes(9.8, 9.8, maxBytes = 21_000)
        assertEquals(listOf(20_929), group)
        // one packet more would pass the cap: 244 packets is 31,059 bytes
        assertEquals(31_000, VoiceNoteLayout.partSizes(14.6, 14.6, maxBytes = 31_000).single())
    }

    @Test
    fun `the longest notes need exactly 12 parts, and a note one part long is one message`() {
        assertEquals(12, VoiceNoteLayout.partSizes(170.0, 14.5, maxBytes = 31_000).size)
        assertEquals(12, VoiceNoteLayout.partSizes(110.0, 9.8, maxBytes = 21_000).size)
        assertEquals(1, VoiceNoteLayout.partSizes(14.5, 14.5, maxBytes = 31_000).size)
        assertEquals(2, VoiceNoteLayout.partSizes(14.6, 14.5, maxBytes = 31_000).size)
        // never more than 12, however long the recording
        assertEquals(12, VoiceNoteLayout.partSizes(400.0, 14.5, maxBytes = 31_000).size)
        assertTrue(VoiceNoteLayout.partSizes(170.0, 14.5, maxBytes = 31_000).all { it <= 31_000 })
    }

    @Test
    fun `the longest notes cost under 1 KAS at the minimum rate`() {
        val oneToOne = MediaPartPayload.feeSompi(VoiceNoteLayout.partSizes(170.0, 14.5, maxBytes = 31_000), forGroup = false, feeRate = null)
        val group = MediaPartPayload.feeSompi(VoiceNoteLayout.partSizes(110.0, 9.8, maxBytes = 21_000), forGroup = true, feeRate = null)
        assertTrue("1:1 $oneToOne", oneToOne < 100_000_000L)
        assertTrue("group $group", group < 100_000_000L)
        // and a full part is about 0.083 / 0.086 KAS
        val part = MediaPartPayload.feeSompi(listOf(30_807), forGroup = false, feeRate = null)
        assertTrue(part in 8_000_000L..8_600_000L)
    }

    // --- photo tiers ---

    @Test
    fun `the tier budgets are iOS's - 1, 0_5 and 0_1 KAS, in bytes per path`() {
        assertEquals(100_000_000L, PhotoTier.HIGH.budgetSompi)
        assertEquals(50_000_000L, PhotoTier.MEDIUM.budgetSompi)
        assertEquals(10_000_000L, PhotoTier.LOW.budgetSompi)
        assertEquals(368_000, PhotoTier.HIGH.maxBytes(forGroup = false))
        assertEquals(184_000, PhotoTier.MEDIUM.maxBytes(forGroup = false))
        assertEquals(36_000, PhotoTier.LOW.maxBytes(forGroup = false))
        assertEquals(243_000, PhotoTier.HIGH.maxBytes(forGroup = true))
        assertEquals(121_500, PhotoTier.MEDIUM.maxBytes(forGroup = true))
        assertEquals(23_500, PhotoTier.LOW.maxBytes(forGroup = true))
    }

    @Test
    fun `each tier's largest photo fits its budget in 12 parts or fewer`() {
        val expectedParts = mapOf(PhotoTier.HIGH to 12, PhotoTier.MEDIUM to 6, PhotoTier.LOW to 2)
        for (group in listOf(false, true)) {
            for (tier in PhotoTier.values()) {
                val bytes = tier.maxBytes(group)
                val parts = PhotoPolicy.chunks(ByteArray(bytes), group)
                assertEquals("$tier group=$group", expectedParts[tier], parts.size)
                assertTrue("$tier group=$group", parts.size <= VoicePartTag.MAX_PARTS)
                val fee = PhotoPolicy.estimatedFeeSompi(bytes, group)
                assertTrue("$tier group=$group fee $fee", fee <= tier.budgetSompi)
                // and close to it: the budget buys the most it can
                assertTrue("$tier group=$group fee $fee", fee >= tier.budgetSompi * 9 / 10)
            }
        }
    }

    @Test
    fun `a part's fee follows the formula - a full 1-1 chunk 0_0838 KAS, a full group chunk 0_0860`() {
        // 1:1: 26 + 4 ceil((9 + 142 + 31,000 + 61) / 3) = 41,642 bytes, mass 2 x (payload + 272),
        // 100 sompi a gram. Tagged (two parts), so the ~142-byte header.
        val twoFull = PhotoPolicy.estimatedFeeSompi(62_000, forGroup = false)
        assertEquals(2 * (2 * (41_642L + 272) * 100 + 3), twoFull)
        val twoFullGroup = PhotoPolicy.estimatedFeeSompi(42_000, forGroup = true)
        assertEquals(2 * (2 * (391L + 2 * (9 + 142 + 21_000 + 16) + 272) * 100 + 3), twoFullGroup)
    }

    // --- splitting and reassembly ---

    @Test
    fun `a photo splits into part-size chunks in order, and one that fits is one chunk`() {
        val photo = ByteArray(70_000) { (it * 31 % 251).toByte() }
        val chunks = PhotoPolicy.chunks(photo, forGroup = false)
        assertEquals(listOf(31_000, 31_000, 8_000), chunks.map { it.size })
        assertArrayEquals(photo, chunks.reduce { a, b -> a + b })
        val groupChunks = PhotoPolicy.chunks(photo, forGroup = true)
        assertEquals(listOf(21_000, 21_000, 21_000, 7_000), groupChunks.map { it.size })
        val small = ByteArray(31_000)
        assertEquals(1, PhotoPolicy.chunks(small, forGroup = false).size)
        assertEquals(2, PhotoPolicy.chunks(small, forGroup = true).size)
    }

    @Test
    fun `a multi-part photo goes out as tagged frames and comes back whole, whatever order they arrive in`() {
        val photo = ByteArray(80_000) { (it * 7 % 253).toByte() }
        val chunks = PhotoPolicy.chunks(photo, forGroup = false)
        val id = VoicePartTag.newGroupId()
        val wire = chunks.mapIndexed { i, chunk ->
            val tag = VoicePartTag.of(id, i + 1, chunks.size)!!
            val text = ContextualMediaFrame.mediaFileJson(tag.photoFileName, chunk.size, "image/jpeg", "data:image/jpeg;base64,${b64(chunk)}", mediaPart = tag)
            ContextualMediaFrame.frameForEnvelope(text)!!
        }
        // received out of order, read back to text, grouped, joined in index order
        val received = wire.reversed().map { ContextualMediaFrame.envelopeText(it)!! }
        val rows = received.mapIndexed { i, text -> Row("tx$i", "alice", text) }
        val groups = VoicePartGrouping.group(rows, accessors)
        val cluster = groups.groupsByAnchorTxId.values.single()
        assertTrue(cluster.isComplete)
        assertEquals(listOf(1, 2, 3), cluster.partIndexes)
        val joined = cluster.parts.map { row ->
            val content = row.content
            Base64.getDecoder().decode(content.substringAfter("base64,").substringBefore("\""))
        }.reduce { a, b -> a + b }
        assertArrayEquals(photo, joined)
    }

    // --- grouping (one bubble, one unread, one ring) ---

    private class Row(val txId: String, val owner: String, val content: String, val status: String = "sent", val outgoing: Boolean = false)

    private val accessors = VoicePartGrouping.Accessors<Row>(
        txId = { it.txId },
        content = { it.content },
        owner = { it.owner },
        canBePart = { true },
        status = { it.status },
        isOutgoing = { it.outgoing },
    )

    @Test
    fun `parts collapse into one row at the earliest, the others hidden`() {
        val rows = listOf(
            Row("a", "alice", "hi"),
            Row("p2", "alice", voicePart(2, 3)),
            Row("b", "bob", "hello"),
            Row("p1", "alice", voicePart(1, 3)),
            Row("p3", "alice", voicePart(3, 3)),
        )
        val result = VoicePartGrouping.group(rows, accessors)
        val cluster = result.groupsByAnchorTxId.getValue("p2")
        assertEquals(3, cluster.count)
        assertTrue(cluster.isComplete)
        assertEquals(listOf("p1", "p2", "p3"), cluster.parts.map { it.txId })
        assertEquals(setOf("p1", "p3"), result.hiddenTxIds)
        assertEquals("p2", result.anchorTxIdByTxId["p3"])
        assertEquals(setOf("p1", "p2", "p3", "a"), result.expanded(listOf("p2", "a")))
    }

    @Test
    fun `parts belong together by owner, id and count`() {
        val other = "ffeeddccbbaa99887766554433221100"
        val rows = listOf(
            Row("p1", "alice", voicePart(1, 2)),
            Row("q1", "bob", voicePart(1, 2)),
            Row("r1", "alice", voicePart(1, 2, id = other)),
            Row("s1", "alice", voicePart(1, 3)),
        )
        val result = VoicePartGrouping.group(rows, accessors)
        assertEquals(4, result.groupsByAnchorTxId.size)
        assertTrue(result.hiddenTxIds.isEmpty())
        assertTrue(result.groupsByAnchorTxId.values.none { it.isComplete })
    }

    @Test
    fun `a part sent twice keeps the delivered copy, and the note's status is combined`() {
        val rows = listOf(
            Row("p1", "self", voicePart(1, 3), status = "sent", outgoing = true),
            Row("p2failed", "self", voicePart(2, 3), status = "failed", outgoing = true),
            Row("p2", "self", voicePart(2, 3), status = "sent", outgoing = true),
            Row("p3", "self", voicePart(3, 3), status = "pending", outgoing = true),
        )
        val cluster = VoicePartGrouping.group(rows, accessors).groupsByAnchorTxId.getValue("p1")
        assertEquals(listOf("p1", "p2", "p3"), cluster.parts.map { it.txId })
        assertEquals("pending", cluster.deliveryStatus)
        assertEquals(4, cluster.allTxIds.size)
        assertEquals("failed", VoicePartGrouping.combinedStatus(listOf("sent", "pending", "failed")))
        assertEquals("warning", VoicePartGrouping.combinedStatus(listOf("sent", "warning")))
        assertEquals("sent", VoicePartGrouping.combinedStatus(listOf("sent", "sent")))
    }

    @Test
    fun `Retry resends only the failed parts, in index order`() {
        val rows = listOf(
            Row("p1", "self", voicePart(1, 3), status = "sent", outgoing = true),
            Row("p3", "self", voicePart(3, 3), status = "failed", outgoing = true),
            Row("p2", "self", voicePart(2, 3), status = "failed", outgoing = true),
        )
        val cluster = VoicePartGrouping.group(rows, accessors).groupsByAnchorTxId.getValue("p1")
        assertEquals("failed", cluster.deliveryStatus)
        assertEquals(listOf("p2", "p3"), cluster.failedOutgoingParts.map { it.txId })
    }

    @Test
    fun `a note counts once as unread, and only its first part to arrive is news`() {
        val rows = listOf(
            Row("a", "alice", "hi"),
            Row("p2", "alice", voicePart(2, 3)),
            Row("p1", "alice", voicePart(1, 3)),
            Row("b", "alice", "bye"),
        )
        assertEquals(3, VoicePartGrouping.unreadUnits(rows, accessors))
        assertFalse(VoicePartGrouping.isLaterPart(rows[1], rows.take(1), accessors))
        assertTrue(VoicePartGrouping.isLaterPart(rows[2], rows.take(2), accessors))
        // itself doesn't count, nor someone else's part with the same id
        assertFalse(VoicePartGrouping.isLaterPart(rows[1], listOf(rows[1]), accessors))
        assertFalse(VoicePartGrouping.isLaterPart(Row("x", "bob", voicePart(1, 3)), rows, accessors))
        assertFalse(VoicePartGrouping.isLaterPart(rows[0], rows, accessors))
    }

    @Test
    fun `the notification key names the owner, id and count`() {
        assertEquals("kaspa:qalice|$noteId|3", VoicePartGrouping.partKey(voicePart(2, 3), "kaspa:QALICE"))
        assertNull(VoicePartGrouping.partKey("hello", "kaspa:qalice"))
    }
}
