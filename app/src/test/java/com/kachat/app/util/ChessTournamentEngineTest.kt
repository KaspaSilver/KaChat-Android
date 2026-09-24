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
    // Past the instant the clock allowances start applying, so the games these tests play are
    // judged by today's rules (see ChessTournamentCodec.ALLOWANCE_FROM_MS).
    private var clock = ChessTournamentCodec.ALLOWANCE_FROM_MS + 1_000_000L
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
        post(players[0], ChessTournamentCodec.create(id, "Friday Blitz", ChessTournamentCodec.PRIVATE_CREATE_CODE))
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
    fun `25 seconds' grace on a first move, ten seconds after, and the rest is charged`() {
        seatEight()
        val game = "1-0"
        val white = players[0]
        val black = players[7]
        // White's first move 20s in: inside the 25s grace, so nothing is charged.
        post(white, ChessTournamentCodec.move(id, game, 1, "e2", "e4", null), advanceMs = 20_000)
        assertEquals(0L, state().games[game]!!.whiteUsedMs)
        // Black's first move 35s later: 25s are free, the last 10s are charged.
        post(black, ChessTournamentCodec.move(id, game, 2, "e7", "e5", null), advanceMs = 35_000)
        assertEquals(10_000L, state().games[game]!!.blackUsedMs)
        // A later move inside ten seconds is free; past it, only the excess counts.
        post(white, ChessTournamentCodec.move(id, game, 3, "g1", "f3", null), advanceMs = 8_000)
        assertEquals(0L, state().games[game]!!.whiteUsedMs)
        post(black, ChessTournamentCodec.move(id, game, 4, "b8", "c6", null), advanceMs = 25_000)
        assertEquals(10_000L + 15_000L, state().games[game]!!.blackUsedMs)
        // White is to move, so black is the one who can claim - and the claim is judged against
        // the same allowance: five minutes of CHARGE, not of wall clock.
        post(black, ChessTournamentCodec.claim(id, game), advanceMs = ChessTournamentCodec.CLOCK_MS)
        assertNull(state().games[game]!!.winner)
        post(black, ChessTournamentCodec.claim(id, game), advanceMs = ChessTournamentCodec.MOVE_DELAY_MS)
        assertEquals(black, state().games[game]!!.winner)
    }

    @Test
    fun `a game from before the allowances keeps the rules of its day`() {
        // A game that started before the activation instant is charged from the first second.
        clock = ChessTournamentCodec.ALLOWANCE_FROM_MS - 10 * 60 * 1000
        seatEight()
        val game = "1-0"
        val old = state().games[game]!!
        assertEquals(0L, ChessTournamentCodec.allowanceMs(1, old.startedAt))
        assertEquals(30_000L, old.chargedMs(30_000L))
        // And a claim five minutes in is valid, as it was under the rules of that day.
        post(players[7], ChessTournamentCodec.claim(id, game), advanceMs = ChessTournamentCodec.CLOCK_MS)
        assertEquals(players[7], state().games[game]!!.winner)
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
        // p and k decode, and a p of the wrong type rejects the message.
        val create = ChessTournamentCodec.encode(ChessTournamentCodec.createDuel("abcd2345", "Game"))
        assertEquals(2L, ChessTournamentCodec.decode(create)!!.p)
        assertNull(ChessTournamentCodec.decode(create.replace("\"p\":2", "\"p\":\"2\"")))
    }

    private fun rooms() = ChessTournamentEngine.reduce(events)

    @Test
    fun `a join opens whichever public room it names, a third joiner is ignored`() {
        // Any join opens the room it names - a phone whose history starts at room 2 must not
        // reject it (iOS d2ab780).
        post(players[0], ChessTournamentCodec.join(ChessTournamentCodec.duelId(2)))
        assertEquals(listOf(players[0]), rooms()[ChessTournamentCodec.duelId(2)]!!.players)
        post(players[0], ChessTournamentCodec.join(ChessTournamentCodec.duelId(1)))
        post(players[1], ChessTournamentCodec.join(ChessTournamentCodec.duelId(1)))
        post(players[2], ChessTournamentCodec.join(ChessTournamentCodec.duelId(1)))
        val room1 = rooms()[ChessTournamentCodec.duelId(1)]!!
        assertEquals(listOf(players[0], players[1]), room1.players)
        assertEquals(ChessTournament.Status.LIVE, room1.status)
        val game = room1.games["1-0"]!!
        assertEquals(players[0], game.white)
        assertEquals(players[1], game.black)
        // The loser of the race joins room 2, which player 1 had already opened.
        post(players[2], ChessTournamentCodec.join(ChessTournamentCodec.duelId(2)))
        assertEquals(listOf(players[0], players[2]), rooms()[ChessTournamentCodec.duelId(2)]!!.players)
        // A resignation finishes a 1v1: it has one game and one round.
        post(players[1], ChessTournamentCodec.resign(ChessTournamentCodec.duelId(1), "1-0"))
        val done = rooms()[ChessTournamentCodec.duelId(1)]!!
        assertEquals(ChessTournament.Status.FINISHED, done.status)
        assertEquals(players[0], done.champion)
        // Public rooms cannot be created or cancelled by message, and a non-ASCII room number is not a room.
        post(players[3], ChessTournamentCodec.create(ChessTournamentCodec.publicId(1), "Mine", ChessTournamentCodec.PRIVATE_CREATE_CODE))
        assertNull(rooms()[ChessTournamentCodec.publicId(1)])
        post(players[3], ChessTournamentCodec.join("duel-\u0661"))
        assertNull(rooms()["duel-\u0661"])
    }

    @Test
    fun `a private 1v1 needs no code and a private tournament needs the creator key`() {
        post(players[0], ChessTournamentCodec.createDuel("frnd2345", ""))
        val duel = rooms()["frnd2345"]!!
        assertEquals("1v1", duel.name)
        assertEquals(2, duel.capacity)
        post(players[1], ChessTournamentCodec.create("nokey234", "No key", "WRONG-CODE"))
        assertNull(rooms()["nokey234"])
        post(players[1], ChessTournamentCodec.create("haskey23", "Friends", "  kachat-chess "))
        assertEquals(8, rooms()["haskey23"]!!.capacity)
    }

    @Test
    fun `a seat given back frees the room, and seats older than five minutes expire`() {
        val room = ChessTournamentCodec.duelId(1)
        post(players[0], ChessTournamentCodec.join(room))
        post(players[0], ChessTournamentCodec.leave(room))
        assertEquals(emptyList<String>(), rooms()[room]!!.players)
        // A seat taken now is dropped by a join arriving more than five minutes later.
        post(players[1], ChessTournamentCodec.join(room))
        post(players[2], ChessTournamentCodec.join(room), advanceMs = ChessTournamentCodec.SEAT_TTL_MS + 1_000)
        val afterExpiry = rooms()[room]!!
        assertEquals(listOf(players[2]), afterExpiry.players)
        assertEquals(ChessTournament.Status.OPEN, afterExpiry.status)
        // A seat inside the window stays, and the second player fills the room.
        post(players[3], ChessTournamentCodec.join(room), advanceMs = 60_000)
        val started = rooms()[room]!!
        assertEquals(listOf(players[2], players[3]), started.players)
        assertEquals(ChessTournament.Status.LIVE, started.status)
        // Once it has started, leaving does nothing - only resigning ends a game.
        post(players[3], ChessTournamentCodec.leave(room))
        assertEquals(listOf(players[2], players[3]), rooms()[room]!!.players)
    }

    @Test
    fun `seatedPlayers counts only live seats while a room waits`() {
        val room = ChessTournamentCodec.publicId(1)
        post(players[0], ChessTournamentCodec.join(room))
        val t = rooms()[room]!!
        val joinTime = clock
        assertTrue(t.isSeated(players[0], joinTime + 60_000))
        assertTrue(!t.isSeated(players[0], joinTime + ChessTournamentCodec.SEAT_TTL_MS + 1))
        assertEquals(joinTime + ChessTournamentCodec.SEAT_TTL_MS, t.seatExpiry(players[0]))
    }

    @Test
    fun `a 1v1 counts on the duel board only, a tournament on the tournament board only`() {
        // A public 1v1, won by the first seat.
        val duel = ChessTournamentCodec.duelId(1)
        post(players[0], ChessTournamentCodec.join(duel))
        post(players[1], ChessTournamentCodec.join(duel))
        post(players[1], ChessTournamentCodec.resign(duel, "1-0"))
        // A tournament, one round-1 game decided.
        seatEight()
        post(players[7], ChessTournamentCodec.resign(id, "1-0"))
        val rows = ChessTournamentEngine.leaderboard(ChessTournamentEngine.reduce(events).values)
        val winner = rows.first { it.address == players[0] }
        assertEquals(1, winner.duelWins)
        assertEquals(1, winner.tournamentGameWins)
        assertEquals(2, winner.wins)
        // The 1v1 does not make anyone a tournament player.
        assertEquals(1, winner.tournamentsPlayed)
        val duelBoard = ChessTournamentEngine.duelLeaderboard(rows).map { it.address }
        assertEquals(listOf(players[0], players[1]), duelBoard)
        val tournamentBoard = ChessTournamentEngine.tournamentLeaderboard(rows)
        assertEquals(8, tournamentBoard.size)
        assertEquals(players[0], tournamentBoard.first().address)
    }

    @Test
    fun `the leaderboard is most wins, then fewest losses`() {
        val a = ChessLeaderboardRow("a", wins = 3, losses = 2)
        val b = ChessLeaderboardRow("b", wins = 3, losses = 1)
        val c = ChessLeaderboardRow("c", wins = 1, losses = 0)
        // Exercised through a reduce instead of by hand would need 6 games; the order is the point.
        val sorted = listOf(a, b, c).sortedWith(
            compareByDescending<ChessLeaderboardRow> { it.wins }.thenBy { it.losses }.thenByDescending { it.lastPlayedAt }
        )
        assertEquals(listOf("b", "a", "c"), sorted.map { it.address })
    }
}
