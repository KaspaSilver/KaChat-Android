package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/**
 * The KaPost link preview reads a shared post off its own transaction payload, because the K
 * indexer has no single-post lookup. These shapes come from live mainnet transactions, so a
 * change to the payload format that would blank those cards fails here first.
 */
class KaPostsChainPayloadTest {

    private fun b64(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    private val pubkey = "0325bb74214337f52a4d451f680f22ecb7c5758416c1b1c5ef4fcf27a57d9435b7"
    private val signature = "9f5eab321e9c2355d27b6deb6d252b44549ef0d" + "a".repeat(89)
    private val postId = "1943b5083d67c98cac49c775e1809d8c1b317053a02b2489206fab7a5fca026f"

    @Test
    fun `reads a post`() {
        val payload = "kchat:1:post:$pubkey:$signature:${b64("⁠hello kaspa")}:[]"
        val parsed = KaPostsProtocol.parseChainPayload(payload)!!
        assertEquals("post", parsed.action)
        assertEquals(pubkey, parsed.authorPubkey)
        // The exclusivity marker is stripped - it is invisible, but it would still lead the text.
        assertEquals("hello kaspa", parsed.message)
    }

    @Test
    fun `reads a reply, whose message sits one field later`() {
        val payload = "kchat:1:reply:$pubkey:$signature:$postId:${b64("⁠agreed")}:[]"
        val parsed = KaPostsProtocol.parseChainPayload(payload)!!
        assertEquals("reply", parsed.action)
        assertEquals("agreed", parsed.message)
    }

    @Test
    fun `reads a quote`() {
        val payload = "kchat:1:quote:$pubkey:$signature:$postId:${b64("⁠worth reading")}:$pubkey"
        val parsed = KaPostsProtocol.parseChainPayload(payload)!!
        assertEquals("quote", parsed.action)
        assertEquals("worth reading", parsed.message)
    }

    @Test
    fun `a quote with no comment is a repost, not a parse failure`() {
        val payload = "kchat:1:quote:$pubkey:$signature:$postId:${b64("")}:$pubkey"
        val parsed = KaPostsProtocol.parseChainPayload(payload)!!
        assertEquals("", parsed.message)
    }

    @Test
    fun `reads the legacy k prefix, which older posts still carry`() {
        val payload = "k:1:post:$pubkey:$signature:${b64("⁠from before the migration")}:[]"
        assertEquals("from before the migration", KaPostsProtocol.parseChainPayload(payload)?.message)
    }

    @Test
    fun `a message containing colons survives, since base64 has none`() {
        val text = "ratio 3:1, see https://example.com:8443/x"
        val payload = "kchat:1:post:$pubkey:$signature:${b64("⁠$text")}:[]"
        assertEquals(text, KaPostsProtocol.parseChainPayload(payload)?.message)
    }

    @Test
    fun `actions that carry no text are not posts`() {
        assertNull(KaPostsProtocol.parseChainPayload("kchat:1:vote:$pubkey:$signature:$postId:upvote:$pubkey"))
        assertNull(KaPostsProtocol.parseChainPayload("kchat:1:follow:$pubkey:$signature:follow:$pubkey"))
        assertNull(KaPostsProtocol.parseChainPayload("kchat:1:unquote:$pubkey:$signature:$postId"))
    }

    @Test
    fun `another app's payload on the same chain is ignored`() {
        assertNull(KaPostsProtocol.parseChainPayload("ciph_msg:1:comm:deadbeef"))
        assertNull(KaPostsProtocol.parseChainPayload(""))
        assertNull(KaPostsProtocol.parseChainPayload("kchat:1:post:$pubkey"))
    }

    @Test
    fun `a message that is not base64 is not a post`() {
        assertNull(KaPostsProtocol.parseChainPayload("kchat:1:post:$pubkey:$signature:not base64!:[]"))
    }
}
