package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit AND-007: a 1:1 banner never shows a file's JSON envelope. */
class DmNotificationTextTest {

    private fun file(mime: String, name: String = "f") =
        """{"type":"file","name":"$name","size":1234,"mimeType":"$mime","content":"data:$mime;base64,AAAAIGZ0eXBpc29tAAACAGlzb21pc28y"}"""

    @Test
    fun `a video says Sent a video`() {
        assertEquals("Sent a video", dmNotificationText(file("video/mp4", "clip.mp4")))
        assertEquals("Sent a video", dmNotificationText(file("VIDEO/QuickTime", "clip.mov")))
    }

    @Test
    fun `any other file says Sent a file`() {
        assertEquals("Sent a file", dmNotificationText(file("application/pdf", "doc.pdf")))
        assertEquals("Sent a file", dmNotificationText(file("application/zip", "a.zip")))
    }

    @Test
    fun `photos and voice keep their wording`() {
        assertEquals("Sent a photo", dmNotificationText(file("image/jpeg", "p.jpg")))
        assertEquals("Sent a voice message", dmNotificationText(file("audio/webm", "v.webm")))
    }

    @Test
    fun `labels come from the caller`() {
        val labels = DmNotificationLabels(sentVideo = "Video!", sentFile = "File!", newMessage = "Neu")
        assertEquals("Video!", dmNotificationText(file("video/mp4"), labels))
        assertEquals("File!", dmNotificationText(file("application/pdf"), labels))
        assertEquals("Neu", dmNotificationText("""{"type":"unknown","x":1}""", labels))
    }

    @Test
    fun `a reply shows what it replied to`() {
        val reply = MessageReply.encode(replyToId = "abc", replyToSender = "kaspa:qq", replyToPreview = "hello", text = "hi back")
        assertEquals("Replied to \"hello\"", dmNotificationText(reply))
    }

    @Test
    fun `leftover JSON is New message, never the envelope`() {
        assertEquals("New message", dmNotificationText("""{"type":"something_new","payload":"xyz"}"""))
        // cut short: no longer parses, still an envelope
        assertEquals("New message", dmNotificationText("""{"type":"file","mimeType":"video/mp4","content":"data:video/mp4;base64,AAAAIGZ0"""))
        // a file whose content is not a data URI is not parsed as a file, and is still JSON
        assertEquals("New message", dmNotificationText("""{"type":"file","mimeType":"video/mp4","content":"https://x/y"}"""))
        assertEquals("New message", dmNotificationText("""  { "type" : "x" }  """))
    }

    @Test
    fun `typed text passes through`() {
        assertEquals("hello there", dmNotificationText("hello there"))
        assertEquals("{hi}", dmNotificationText("{hi}"))
        assertEquals("{ not json", dmNotificationText("{ not json"))
        assertEquals("", dmNotificationText(""))
    }

    @Test
    fun `json sniffing`() {
        assertTrue(looksLikeJsonEnvelope("""{"a":1}"""))
        assertTrue(looksLikeJsonEnvelope("""{"a":"""))
        assertTrue(looksLikeJsonEnvelope("""{ "a": 1 }"""))
        assertFalse(looksLikeJsonEnvelope("{hi}"))
        assertFalse(looksLikeJsonEnvelope("[1,2]"))
        assertFalse(looksLikeJsonEnvelope("plain"))
    }
}
