package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class BroadcastPushPreviewTest {

    @Test
    fun `plain text is left alone`() {
        assertEquals("gm everyone", BroadcastPushPreview.clean("gm everyone"))
    }

    @Test
    fun `a whole reply envelope reads as the reply's own text`() {
        val reply = """{"type":"reply","replyToId":"abc","replyToSender":"kaspa:qq","replyToPreview":"hi","text":"hello back"}"""
        assertEquals("hello back", BroadcastPushPreview.clean(reply))
    }

    @Test
    fun `a reply envelope cut off mid-text still yields what is there`() {
        val cut = """{"type":"reply","replyToId":"abc","replyToSender":"kaspa:qq","replyToPreview":"hi","text":"hello ba"""
        assertEquals("hello ba", BroadcastPushPreview.clean(cut))
    }

    @Test
    fun `a message still base64 encoded is decoded`() {
        val encoded = Base64.getEncoder().encodeToString("this was sent on encoded".toByteArray())
        assertEquals("this was sent on encoded", BroadcastPushPreview.clean(encoded))
    }

    @Test
    fun `a base64 reply envelope is decoded and then unwrapped`() {
        val reply = """{"type":"reply","replyToId":"abc","replyToSender":"kaspa:qq","replyToPreview":"hi","text":"nested"}"""
        val encoded = Base64.getEncoder().encodeToString(reply.toByteArray())
        assertEquals("nested", BroadcastPushPreview.clean(encoded))
    }

    @Test
    fun `a long word that only looks like base64 is not mangled`() {
        // Decodes to bytes that are not readable text, so the original stays.
        val word = "Supercalifragilistic"
        assertEquals(word, BroadcastPushPreview.clean(word))
    }
}
