package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scripted tournament iOS verified its reducer with (334cdd1), run through the port. */
class ChessTournamentEngineTest {

    private val id = "7c1e0000-0000-0000-0000-000000000001"
    private val players = (1..9).map { "kaspa:player$it" }
    private var clock = 1_000_000L
    private var tx = 0
    private val events = mutableListOf<ChessArenaEvent>()

    private fun post(sender: String, message: ChessTournamentMessage, advanceMs: Long = 1_000) {
        clock += advanceMs
        tx += 1
        // Round-trip through the wire format, as every row does.
        val decoded = ChessTournamentCodec.decode(ChessTournamentCodec.encode(message))
        assertNotNull("encoded message must decode", decoded)
        events += ChessArenaEvent("tx%05d".format(tx), sender, clock, decoded!!)
    }

    private fun state() = ChessTournamentEngine.reduce(events)[id]!!

    private fun seatEight() {
        post(players[0], ChessTournamentCodec.create(id, "Friday Blitz"))
        for (p in players.subList(1, 8)) post(p, ChessTournamentCodec.join(id))
    }

    @Test
    fun `eight seats fill, the ninth join is ignored, round one is 1v8 2v7 3v6 4v5`() {
        seatEight()
        post(players[8], ChessTournamentCodec.join(id))
        val t = state()
        assertEquals(ChessTournament.Status.LIVE, t.status)
        assertEquals(players.subList(0, 8), t.players)
        val round1 = t.gamesInRound(1)
        assertEquals(listOf(0 to 7, 1 to 6, 2 to 5, 3 to 4).map { players[it.first] to players[it.second] },
            round1.map { it.white to it.black })
    }

    @Test
    fun `illegal and out-of-turn moves are ignored, fools mate ends the game`() {
        seatEight()
        val game = "1-0"
        val white = players[0]
        val black = players[7]
        post(black, ChessTournamentCodec.move(id, game, 1, "e7", "e5", null)) // black out of turn
        post(white, ChessTournamentCodec.move(id, game, 1, "e2", "e5", null)) // illegal
        post(white, ChessTournamentCodec.move(id, game, 2, "f2", "f3", null)) // wrong ply
        assertEquals(0, state().games[game]!!.moves.size)
        post(white, ChessTournamentCodec.move(id, game, 1, "f2", "f3", null))
        post(black, ChessTournamentCodec.move(id, game, 2, "e7", "e5", null))
        post(white, ChessTournamentCodec.move(id, game, 3, "g2", "g4", null))
        post(black, ChessTournamentCodec.move(id, game, 4, "d8", "h4", null))
        val g = state().games[game]!!
        assertEquals(black, g.winner)
        assertEquals(ChessTournamentOutcome.Checkmate, g.outcome)
    }

    @Test
    fun `an early claim is rejected and a claim after the flag is accepted`() {
        seatEight()
        val game = "1-1"
        val white = players[1]
        val black = players[6]
        // White has not moved; black claims too early, then after five minutes.
        post(black, ChessTournamentCodec.claim(id, game), advanceMs = 60_000)
        assertNull(state().games[game]!!.winner)
        post(black, ChessTournamentCodec.claim(id, game), advanceMs = ChessTournamentCodec.CLOCK_MS)
        val g = state().games[game]!!
        assertEquals(black, g.winner)
        assertEquals(ChessTournamentOutcome.Timeout, g.outcome)
        // A move after one's own flag would have been void too.
        post(white, ChessTournamentCodec.move(id, game, 1, "e2", "e4", null))
        assertEquals(0, state().games[game]!!.moves.size)
    }

    @Test
    fun `winners meet in round two with colours by whites so far then seed, and the leaderboard counts`() {
        seatEight()
        // 1-0: seed 1 (white) beats seed 8 by resignation; 1-1: seed 7 (black) beats seed 2.
        post(players[7], ChessTournamentCodec.resign(id, "1-0"))
        post(players[1], ChessTournamentCodec.resign(id, "1-1"))
        val t = state()
        val semi = t.games["2-0"]!!
        // Seed 1 had white once, seed 7 had black: seed 7 has fewer whites, so seed 7 is white.
        assertEquals(players[6], semi.white)
        assertEquals(players[0], semi.black)
        val board = ChessTournamentEngine.leaderboard(listOf(t))
        assertEquals(8, board.size)
        val seed1 = board.first { it.address == players[0] }
        assertEquals(1, seed1.wins)
        assertEquals(1, seed1.tournamentsPlayed)
    }

    @Test
    fun `the decoder is as strict as iOS's`() {
        val good = """{"a":"move","from":"e2","g":"1-0","n":1,"t":"$id","to":"e4","type":"chess_t","v":1}"""
        assertNotNull(ChessTournamentCodec.decode(good))
        // A ply sent as a string, a missing v, trailing junk, a lenient single-quoted key.
        assertNull(ChessTournamentCodec.decode(good.replace("\"n\":1", "\"n\":\"1\"")))
        assertNull(ChessTournamentCodec.decode(good.replace(",\"v\":1", "")))
        assertNull(ChessTournamentCodec.decode("$good x"))
        assertNull(ChessTournamentCodec.decode(good.replace("\"a\":", "'a':")))
        assertNull(ChessTournamentCodec.decode(good.replace("\"v\":1", "\"v\":2")))
        assertTrue(ChessTournamentCodec.encode(ChessTournamentCodec.join(id)) ==
            """{"a":"join","t":"$id","type":"chess_t","v":1}""")
    }
}
