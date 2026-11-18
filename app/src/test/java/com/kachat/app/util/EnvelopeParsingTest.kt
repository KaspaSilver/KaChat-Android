package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A reply or chess envelope missing a required field reads as plain text, as on iOS, instead of
 *  being returned half-empty (which crashed the chat it sits in on every open). */
class EnvelopeParsingTest {

    @Test
    fun `a complete reply parses`() {
        val encoded = MessageReply.encode("tx1", "kaspa:qabc", "hello", "hi back")
        val parsed = MessageReply.parseOrNull(encoded)
        assertNotNull(parsed)
        assertEquals("tx1", parsed!!.replyToId)
        assertEquals("hi back", parsed.text)
    }

    @Test
    fun `a reply missing a field or with a wrong type is not a reply`() {
        assertNull(MessageReply.parseOrNull("""{"type":"reply","text":"hi"}"""))
        assertNull(MessageReply.parseOrNull("""{"type":"reply","replyToId":"a","replyToSender":"b","text":"hi"}"""))
        assertNull(MessageReply.parseOrNull("""{"type":"reply","replyToId":"a","replyToSender":"b","replyToPreview":3,"text":"hi"}"""))
        assertNull(MessageReply.parseOrNull("""{"type":"reply","replyToId":"a","replyToSender":null,"replyToPreview":"p","text":"hi"}"""))
    }

    @Test
    fun `complete chess envelopes parse`() {
        val invite = ChessMessage.parseOrNull("""{"type":"chess_invite","gameId":"g","inviterColor":"white","tcMinutes":3,"tcIncSeconds":2}""")
        assertTrue(invite is ChessEnvelope.Invite)
        assertEquals(ChessInviteColor.WHITE, (invite as ChessEnvelope.Invite).content.inviterColor)
        assertTrue(ChessMessage.parseOrNull("""{"type":"chess_response","gameId":"g","accepted":false}""") is ChessEnvelope.Response)
        assertTrue(ChessMessage.parseOrNull("""{"type":"chess_move","gameId":"g","from":"e2","to":"e4","promotion":null}""") is ChessEnvelope.Move)
        assertTrue(ChessMessage.parseOrNull("""{"type":"chess_move","gameId":"g","from":"e7","to":"e8","promotion":"q","clockMs":1000}""") is ChessEnvelope.Move)
        assertTrue(ChessMessage.parseOrNull("""{"type":"chess_resign","gameId":"g"}""") is ChessEnvelope.Resign)
        val encoded = ChessMessage.encode(ChessMoveContent(gameId = "g", from = "a2", to = "a3", promotion = null))
        assertTrue(ChessMessage.parseOrNull(encoded) is ChessEnvelope.Move)
    }

    @Test
    fun `chess envelopes missing a required field are not chess`() {
        assertNull(ChessMessage.parseOrNull("""{"type":"chess_invite","inviterColor":"white"}"""))
        assertNull(ChessMessage.parseOrNull("""{"type":"chess_invite","gameId":"g","inviterColor":"purple"}"""))
        assertNull(ChessMessage.parseOrNull("""{"type":"chess_invite","gameId":"g"}"""))
        assertNull(ChessMessage.parseOrNull("""{"type":"chess_response","gameId":"g"}"""))
        assertNull(ChessMessage.parseOrNull("""{"type":"chess_move","gameId":"g","from":"e2"}"""))
        assertNull(ChessMessage.parseOrNull("""{"type":"chess_move","gameId":"g","from":"e2","to":"e4","clockMs":"x"}"""))
        assertNull(ChessMessage.parseOrNull("""{"type":"chess_resign"}"""))
    }
}
