package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KaPostsProtocolTest {

    @Test
    fun `marker is the U+2060 word joiner`() {
        assertEquals(1, KaPostsProtocol.KACHAT_MARKER.length)
        assertEquals(0x2060, KaPostsProtocol.KACHAT_MARKER[0].code)
    }

    @Test
    fun `marker detection and stripping`() {
        val marked = KaPostsProtocol.KACHAT_MARKER + "hello"
        assertTrue(KaPostsProtocol.isKaChatContent(marked))
        assertFalse(KaPostsProtocol.isKaChatContent("hello"))
        assertEquals("hello", KaPostsProtocol.stripMarker(marked))
        assertEquals("hello", KaPostsProtocol.stripMarker("hello"))
    }

    @Test
    fun `marked text base64 starts with the known KaChat prefix`() {
        // U+2060 in UTF-8 is E2 81 A0 - base64 of any marked text begins "4oGg" (the feed
        // filter and the indexer's exclusivity rule both depend on this).
        val b64 = KaPostsProtocol.b64(KaPostsProtocol.KACHAT_MARKER + "anything")
        assertTrue(b64.startsWith("4oGg"))
    }

    @Test
    fun `base64 round trip`() {
        val text = "hello Kaspa 🚀"
        assertEquals(text, KaPostsProtocol.decodeB64(KaPostsProtocol.b64(text)))
    }

    @Test
    fun `payload shapes match the K protocol spec`() {
        assertEquals(
            "kchat:1:post:PK:SIG:B64:[]",
            KaPostsProtocol.postPayload("PK", "SIG", "B64", "[]"),
        )
        assertEquals(
            "kchat:1:reply:PK:SIG:TX:B64:[]",
            KaPostsProtocol.replyPayload("PK", "SIG", "TX", "B64", "[]"),
        )
        assertEquals(
            "kchat:1:vote:PK:SIG:TX:upvote:AUTHOR",
            KaPostsProtocol.votePayload("PK", "SIG", "TX", "upvote", "AUTHOR"),
        )
        assertEquals(
            "kchat:1:follow:PK:SIG:follow:TARGET",
            KaPostsProtocol.followPayload("PK", "SIG", "follow", "TARGET"),
        )
        assertEquals(
            "kchat:1:quote:PK:SIG:CID:B64:QAUTHOR",
            KaPostsProtocol.quotePayload("PK", "SIG", "CID", "B64", "QAUTHOR"),
        )
        assertEquals(
            "kchat:1:unquote:PK:SIG:CID",
            KaPostsProtocol.unquotePayload("PK", "SIG", "CID"),
        )
    }

    @Test
    fun `signing strings are the payload minus prefix-kind-pubkey-signature`() {
        assertEquals("B64:[]", KaPostsProtocol.postSigningString("B64", "[]"))
        assertEquals("TX:B64:[]", KaPostsProtocol.replySigningString("TX", "B64", "[]"))
        assertEquals("TX:unvote:AUTHOR", KaPostsProtocol.voteSigningString("TX", "unvote", "AUTHOR"))
        assertEquals("unfollow:TARGET", KaPostsProtocol.followSigningString("unfollow", "TARGET"))
        assertEquals("CID:B64:QAUTHOR", KaPostsProtocol.quoteSigningString("CID", "B64", "QAUTHOR"))
        assertEquals("CID", KaPostsProtocol.unquoteSigningString("CID"))
    }

    @Test
    fun `a poll's payload and signing string carry the question, options and closing time`() {
        val csv = KaPostsProtocol.pollOptionsCsv(listOf("Yes", "No"))
        assertEquals("WWVz,Tm8=", csv)
        assertEquals(listOf("Yes", "No"), KaPostsProtocol.pollOptionsFromCsv(csv))
        assertEquals(
            "poll:B64:$csv:1790300000000:[]",
            KaPostsProtocol.pollSigningString("B64", csv, 1_790_300_000_000L, "[]"),
        )
        assertEquals(
            "kchat:1:poll:PK:SIG:B64:$csv:1790300000000:[]",
            KaPostsProtocol.pollPayload("PK", "SIG", "B64", csv, 1_790_300_000_000L, "[]"),
        )
        assertEquals("pollvote:POLLID:2", KaPostsProtocol.pollVoteSigningString("POLLID", 2))
        assertEquals(
            "kchat:1:pollvote:PK:SIG:POLLID:2",
            KaPostsProtocol.pollVotePayload("PK", "SIG", "POLLID", 2),
        )
    }

    @Test
    fun `a poll read off the chain is its question, like any post`() {
        val question = KaPostsProtocol.b64(KaPostsProtocol.KACHAT_MARKER + "Which one?")
        val csv = KaPostsProtocol.pollOptionsCsv(listOf("A", "B"))
        val parsed = KaPostsProtocol.parseChainPayload(
            KaPostsProtocol.pollPayload("PK", "SIG", question, csv, 1_790_300_000_000L, "[]")
        )
        assertEquals("poll", parsed?.action)
        assertEquals("Which one?", parsed?.message)
        assertEquals(null, parsed?.referencedId)
    }

    @Test
    fun `scheduling signs the transaction it hands over, and the cancellation names it`() {
        assertEquals("schedule:TXID:1790300000000", KaPostsProtocol.scheduleSigningString("TXID", 1_790_300_000_000L))
        assertEquals("cancel-schedule:TXID", KaPostsProtocol.cancelScheduleSigningString("TXID"))
    }

    // Poll limits counted the way the indexer counts them (iOS 7842c18, audits XP-007/XP-008).

    @Test
    fun `poll options are counted in unicode scalars like the indexer`() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66" // 7 scalars, 1 character
        assertEquals(7, KaPostsProtocol.pollOptionScalarCount(family))
        assertEquals(2, KaPostsProtocol.pollOptionScalarCount("\uD83C\uDDFA\uD83C\uDDF8")) // flag
        assertEquals(2, KaPostsProtocol.pollOptionScalarCount("e\u0301")) // decomposed é
        assertEquals(40, KaPostsProtocol.POLL_OPTION_MAX_SCALARS)
        // Six family emoji look like six characters but are 42 scalars: over the limit.
        assertTrue(KaPostsProtocol.pollOptionScalarCount(family.repeat(6)) > KaPostsProtocol.POLL_OPTION_MAX_SCALARS)
    }

    @Test
    fun `poll option is cut to 40 scalars at a character boundary`() {
        assertEquals("a".repeat(40), KaPostsProtocol.prefixPollOptionScalars("a".repeat(45)))
        assertEquals("short", KaPostsProtocol.prefixPollOptionScalars("short"))
        // 39 + a two-scalar flag = 41: the flag is dropped whole, never halved.
        val flag = "\uD83C\uDDFA\uD83C\uDDF8"
        assertEquals("a".repeat(39), KaPostsProtocol.prefixPollOptionScalars("a".repeat(39) + flag))
        assertEquals("a".repeat(38) + flag, KaPostsProtocol.prefixPollOptionScalars("a".repeat(38) + flag + "b"))
        // A family emoji (7 scalars) after 34 letters would make 41: it is dropped whole.
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66"
        assertEquals("a".repeat(34), KaPostsProtocol.prefixPollOptionScalars("a".repeat(34) + family))
        // Every surrogate stays paired.
        val cut = KaPostsProtocol.prefixPollOptionScalars("\uD83D\uDE00".repeat(50))
        assertEquals(40, KaPostsProtocol.pollOptionScalarCount(cut))
        assertEquals(80, cut.length)
    }

    @Test
    fun `the longest poll closes five minutes inside the indexer's seven day bound`() {
        val now = 1_790_300_000_000L
        val sevenDays = 7 * 86_400_000L
        assertEquals(now + 3_600_000L, KaPostsProtocol.pollClosesAtMs(now, 1))
        assertEquals(now + 72 * 3_600_000L, KaPostsProtocol.pollClosesAtMs(now, 72))
        assertEquals(now + sevenDays - 300_000L, KaPostsProtocol.pollClosesAtMs(now, 168))
        // A clock up to 5 minutes fast still lands inside block time + 7 days.
        assertTrue(KaPostsProtocol.pollClosesAtMs(now + 299_000L, 168) <= now + sevenDays)
    }
}
