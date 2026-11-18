package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** A reply's quote follows iOS `MessageReplyCodec.previewText`: no raw JSON, no share URL. */
class MessageReplyPreviewTest {

    @Test
    fun `plain text and a reply's own text are quoted as they are`() {
        assertEquals("hello", MessageReply.previewText("hello"))
        assertEquals("inner", MessageReply.previewText(MessageReply.encode("a", "kaspa:q", "x", "inner")))
        assertEquals("", MessageReply.previewText(null))
    }

    @Test
    fun `media, reactions and chess get iOS's labels`() {
        assertEquals("🎤 Audio message", MessageReply.previewText("""{"type":"file","name":"v.webm","mimeType":"audio/webm","content":"data:audio/webm;base64,AAAA"}"""))
        assertEquals("📷 Photo", MessageReply.previewText("""{"type":"file","name":"p.jpg","mimeType":"image/jpeg","content":"data:image/jpeg;base64,AAAA"}"""))
        assertEquals("🎬 Video", MessageReply.previewText("""{"type":"file","name":"v.mp4","mimeType":"video/mp4","content":"data:video/mp4;base64,AAAA"}"""))
        assertEquals("📎 File", MessageReply.previewText("""{"type":"file","name":"d.pdf","mimeType":"application/pdf","content":"data:application/pdf;base64,AAAA"}"""))
        assertEquals("📎 File", MessageReply.previewText("""{"type":"file","name":"x"}"""))
        assertEquals("Reacted 👍", MessageReply.previewText(MessageReaction.encode("tx", "👍", "add")))
        assertEquals("♟️ Chess", MessageReply.previewText("""{"type":"chess_move","gameId":"g","from":"e2","to":"e4"}"""))
        assertEquals("♟️ Chess - 3 | 2", MessageReply.previewText("""{"type":"chess_invite","gameId":"g","inviterColor":"white","tcMinutes":3,"tcIncSeconds":2}"""))
    }

    @Test
    fun `a Nextcloud share never puts its server in the quote`() {
        val url = "https://cloud.example.org/s/AbCdEfGhIjKl"
        assertEquals("📎 Shared a file", MessageReply.previewText(url))
        assertEquals("look at this", MessageReply.previewText("look at this $url"))
    }
}
