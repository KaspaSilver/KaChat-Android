package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `a reply cut off before its text says a reply came`() {
        val cut = """{"type":"reply","replyToId":"abcdef0123456789","replyToSender":"kaspa:qqqqq","replyToPrev"""
        assertEquals("Replied to a message", BroadcastPushPreview.clean(cut))
    }

    @Test
    fun `an envelope re-serialized with spaces still reads as its reply text`() {
        val spaced = """{"type" : "reply", "replyToId" : "abc", "replyToSender" : "kaspa:qq", "replyToPreview" : "hi", "text" : "spaced out"}"""
        assertEquals("spaced out", BroadcastPushPreview.clean(spaced))
    }

    @Test
    fun `the whole on-chain payload keeps only the message`() {
        assertEquals("gm everyone", BroadcastPushPreview.clean("kchat:1:bcast:kaspa:gm everyone"))
    }

    @Test
    fun `an envelope this cannot read never shows as JSON`() {
        assertEquals("🎤 Audio message", BroadcastPushPreview.clean("""{"mimeType":"audio/mp4","dat"""))
        assertEquals("📷 Photo", BroadcastPushPreview.clean("""{"mimeType":"image/jpeg","dat"""))
        assertEquals("New message", BroadcastPushPreview.clean("""{"somethingNew":"12345678901234567890"""))
    }

    @Test
    fun `an edit envelope is recognised even when cut off or re-spaced`() {
        assertTrue(BroadcastPushPreview.isEditEnvelope(MessageEdit.encode("abc", "fixed")))
        assertTrue(BroadcastPushPreview.isEditEnvelope("""{"type" : "edit", "targetTxId" : "abc", "te"""))
        assertFalse(BroadcastPushPreview.isEditEnvelope("just a message"))
        assertFalse(BroadcastPushPreview.isEditEnvelope(MessageReaction.encode("abc", "👍", "add")))
    }
}
