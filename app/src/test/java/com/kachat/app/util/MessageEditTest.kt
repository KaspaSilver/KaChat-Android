package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The edit envelope is cross-platform wire format (MESSAGING.md "Message Edits"): an edit sent
 * from iPhone has to parse here field-for-field, and ours there. These lock the shape down.
 */
class MessageEditTest {

    @Test
    fun `encodes the documented envelope`() {
        assertEquals(
            """{"type":"edit","targetTxId":"abc123","text":"the new text"}""",
            MessageEdit.encode("abc123", "the new text")
        )
    }

    @Test
    fun `parses an envelope written by the other platform`() {
        val parsed = MessageEdit.parseOrNull("""{"type":"edit","targetTxId":"abc123","text":"fixed typo"}""")
        assertEquals("abc123", parsed?.targetTxId)
        assertEquals("fixed typo", parsed?.text)
    }

    @Test
    fun `unknown fields are ignored, as the spec requires`() {
        val parsed = MessageEdit.parseOrNull("""{"type":"edit","targetTxId":"t","text":"x","future":1}""")
        assertEquals("x", parsed?.text)
    }

    @Test
    fun `everything that is not an edit envelope is not one`() {
        assertNull(MessageEdit.parseOrNull("plain text"))
        assertNull(MessageEdit.parseOrNull(null))
        assertNull(MessageEdit.parseOrNull(""))
        // Another envelope of the same family.
        assertNull(MessageEdit.parseOrNull(MessageReaction.encode("t", "👍", "add")))
        // An edit with no target names nothing.
        assertNull(MessageEdit.parseOrNull("""{"type":"edit","targetTxId":"","text":"x"}"""))
    }

    @Test
    fun `text is capped at the shared maximum`() {
        val encoded = MessageEdit.encode("t", "x".repeat(MessageEdit.MAX_LENGTH + 500))
        assertEquals(MessageEdit.MAX_LENGTH, MessageEdit.parseOrNull(encoded)?.text?.length)
    }

    @Test
    fun `a reply keeps its quote and gets the new text`() {
        val original = MessageReply.encode(
            replyToId = "orig", replyToSender = "kaspa:sender", replyToPreview = "hello", text = "frist"
        )
        val edited = MessageEdit.apply("first", original)
        val quote = MessageReply.parseOrNull(edited)
        assertEquals("orig", quote?.replyToId)
        assertEquals("kaspa:sender", quote?.replyToSender)
        assertEquals("hello", quote?.replyToPreview)
        assertEquals("first", quote?.text)
    }

    @Test
    fun `plain text is simply replaced`() {
        assertEquals("after", MessageEdit.apply("after", "before"))
    }

    @Test
    fun `only text messages are editable`() {
        assertTrue(MessageEdit.isEditable("hello"))
        assertTrue(
            MessageEdit.isEditable(
                MessageReply.encode(replyToId = "o", replyToSender = "s", replyToPreview = "p", text = "hi")
            )
        )
        assertFalse(MessageEdit.isEditable(""))
        assertFalse(MessageEdit.isEditable("   "))
        assertFalse(MessageEdit.isEditable(null))
        // Any envelope - a reaction here - is never editable.
        assertFalse(MessageEdit.isEditable(MessageReaction.encode("t", "👍", "add")))
    }
}
