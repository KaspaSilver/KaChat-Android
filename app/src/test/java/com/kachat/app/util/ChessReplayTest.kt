package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** ChessGameEngine.summarize replays by turn, not timestamp (iOS c1ee033, 6cfb62c). */
class ChessReplayTest {

    private val me = "kaspa:me"
    private val them = "kaspa:them"
    private val gameId = "g1"

    private fun msg(id: String, body: String, outgoing: Boolean, time: Long, failed: Boolean = false) =
        ChessGameEngine.SimpleChessSourceMessage(id, body, outgoing, time, sendFailed = failed)

    private fun invite(time: Long) = msg("inv", ChessMessage.encode(ChessInviteContent(gameId = gameId, inviterColor = ChessInviteColor.WHITE)), true, time)
    private fun accept(time: Long, outgoing: Boolean = false, failed: Boolean = false) =
        msg("acc$time", ChessMessage.encode(ChessResponseContent(gameId = gameId, accepted = true)), outgoing, time, failed)
    private fun move(id: String, from: String, to: String, outgoing: Boolean, time: Long, failed: Boolean = false) =
        msg(id, ChessMessage.encode(ChessMoveContent(gameId = gameId, from = from, to = to, promotion = null)), outgoing, time, failed)

    @Test
    fun `a sender clock running ahead still replays every move`() {
        // My phone runs 60s fast: my moves carry my clock, their replies the chain's.
        val skew = 60_000L
        val messages = listOf(
            invite(0),
            accept(1_000),
            move("m1", "e2", "e4", outgoing = true, time = 2_000 + skew),
            move("t1", "e7", "e5", outgoing = false, time = 3_000),
            move("m2", "g1", "f3", outgoing = true, time = 4_000 + skew),
            move("t2", "b8", "c6", outgoing = false, time = 5_000),
        )
        val summary = ChessGameEngine.summarize(gameId, messages, me, them)
        assertNotNull(summary)
        assertEquals(4, summary!!.moveHistory.size)
        assertEquals(ChessColor.WHITE, summary.board.sideToMove)
        assertEquals(ChessGameStatusKind.IN_PROGRESS, summary.status.kind)
    }

    @Test
    fun `a move that failed to send is not on the board`() {
        val messages = listOf(
            invite(0),
            accept(1_000),
            move("m1", "e2", "e4", outgoing = true, time = 2_000, failed = true),
        )
        val summary = ChessGameEngine.summarize(gameId, messages, me, them)!!
        assertEquals(0, summary.moveHistory.size)
        assertEquals(ChessColor.WHITE, summary.board.sideToMove)
        assertEquals("m1", summary.lastMessageId)
    }

    @Test
    fun `an unsent accept leaves the game waiting for a response`() {
        val messages = listOf(
            msg("inv", ChessMessage.encode(ChessInviteContent(gameId = gameId, inviterColor = ChessInviteColor.WHITE)), false, 0),
            accept(1_000, outgoing = true, failed = true),
        )
        val summary = ChessGameEngine.summarize(gameId, messages, me, them)!!
        assertEquals(ChessGameStatusKind.PENDING_RESPONSE, summary.status.kind)
    }

    @Test
    fun `a move on the board counts as acceptance`() {
        val messages = listOf(
            invite(0),
            move("m1", "e2", "e4", outgoing = true, time = 2_000),
            move("t1", "e7", "e5", outgoing = false, time = 3_000),
        )
        val summary = ChessGameEngine.summarize(gameId, messages, me, them)!!
        assertEquals(ChessGameStatusKind.IN_PROGRESS, summary.status.kind)
        assertEquals(2, summary.moveHistory.size)
    }
}
