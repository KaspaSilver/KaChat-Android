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
            "k:1:post:PK:SIG:B64:[]",
            KaPostsProtocol.postPayload("PK", "SIG", "B64", "[]"),
        )
        assertEquals(
            "k:1:reply:PK:SIG:TX:B64:[]",
            KaPostsProtocol.replyPayload("PK", "SIG", "TX", "B64", "[]"),
        )
        assertEquals(
            "k:1:vote:PK:SIG:TX:upvote:AUTHOR",
            KaPostsProtocol.votePayload("PK", "SIG", "TX", "upvote", "AUTHOR"),
        )
        assertEquals(
            "k:1:follow:PK:SIG:follow:TARGET",
            KaPostsProtocol.followPayload("PK", "SIG", "follow", "TARGET"),
        )
        assertEquals(
            "k:1:quote:PK:SIG:CID:B64:QAUTHOR",
            KaPostsProtocol.quotePayload("PK", "SIG", "CID", "B64", "QAUTHOR"),
        )
        assertEquals(
            "k:1:unquote:PK:SIG:CID",
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
}
