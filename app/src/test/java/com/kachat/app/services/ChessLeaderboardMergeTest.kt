package com.kachat.app.services

import com.kachat.app.util.ChessLeaderboardPeriod
import com.kachat.app.util.ChessLeaderboardRow
import com.kachat.app.util.ChessTournamentEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The indexer's leaderboard merged with the phone's own (iOS 77d6038, ONLINE_CHESS.md §6). */
class ChessLeaderboardMergeTest {

    private fun player(address: String, wins: Int? = 1, lastPlayedAt: Long? = 5) = ChessIndexerLeaderboardPlayer(
        address = address, wins = wins, losses = 2, duelWins = 1, duelLosses = 2,
        tournamentGameWins = 0, tournamentGameLosses = 0, tournamentsPlayed = 0,
        tournamentsWon = 0, tournamentsLost = 0, lastPlayedAt = lastPlayedAt,
    )

    @Test
    fun `per player each figure is the larger of the two, then most wins, fewest losses, latest`() {
        val remote = listOf(
            ChessLeaderboardRow("a", wins = 5, losses = 1, duelWins = 5, duelLosses = 1, tournamentsPlayed = 2, lastPlayedAt = 100),
            ChessLeaderboardRow("b", wins = 2, losses = 4, tournamentsWon = 1, lastPlayedAt = 300),
            ChessLeaderboardRow("c", wins = 3, losses = 3, lastPlayedAt = 50),
        )
        val local = listOf(
            // Newer games the indexer has not caught: more losses, a later time, fewer wins.
            ChessLeaderboardRow("a", wins = 4, losses = 2, duelWins = 4, duelLosses = 2, tournamentsPlayed = 3, lastPlayedAt = 200),
            ChessLeaderboardRow("b", wins = 3, losses = 1, tournamentGameWins = 3, tournamentsLost = 1, lastPlayedAt = 10),
            // Only on the phone.
            ChessLeaderboardRow("d", wins = 3, losses = 3, lastPlayedAt = 60),
        )
        val merged = ChessTournamentService.mergedBoard(local, remote)
        val a = merged.first { it.address == "a" }
        assertEquals(ChessLeaderboardRow("a", wins = 5, losses = 2, duelWins = 5, duelLosses = 2, tournamentsPlayed = 3, lastPlayedAt = 200), a)
        val b = merged.first { it.address == "b" }
        assertEquals(
            ChessLeaderboardRow("b", wins = 3, losses = 4, tournamentsWon = 1, tournamentsLost = 1, tournamentGameWins = 3, lastPlayedAt = 300),
            b,
        )
        // a 5W, then the 3W rows: b has 4 losses so it is last; c and d tie on 3-3, d played later.
        assertEquals(listOf("a", "d", "c", "b"), merged.map { it.address })
    }

    @Test
    fun `without an indexer board the phone's own board is shown as it is`() {
        val local = listOf(ChessLeaderboardRow("x", wins = 1), ChessLeaderboardRow("y", wins = 2))
        assertSame(local, ChessTournamentService.mergedBoard(local, null))
    }

    @Test
    fun `an indexer board is used only for the same period and the same since`() {
        val now = ChessTournamentEngine.LEADERBOARD_FRESH_FROM_MS + 86_400_000L
        val since = ChessTournamentEngine.periodSinceMs(ChessLeaderboardPeriod.MONTH, now)
        val good = ChessIndexerLeaderboardResponse(listOf(player("kaspa:a")), period = "month", since = since)

        val board = ChessTournamentService.acceptedIndexerBoard(ChessLeaderboardPeriod.MONTH, good)
        assertNotNull(board)
        assertEquals(since, board!!.since)
        assertEquals(listOf("kaspa:a"), board.rows.map { it.address })
        assertEquals(1, board.rows.single().duelWins)
        assertEquals(board.rows, ChessTournamentService.usableIndexerRows(ChessLeaderboardPeriod.MONTH, board, now))

        // Another period, an old indexer without `period` or `since`, a row missing a figure.
        assertNull(ChessTournamentService.acceptedIndexerBoard(ChessLeaderboardPeriod.ALL_TIME, good))
        assertNull(ChessTournamentService.acceptedIndexerBoard(ChessLeaderboardPeriod.MONTH, good.copy(period = null)))
        assertNull(ChessTournamentService.acceptedIndexerBoard(ChessLeaderboardPeriod.MONTH, good.copy(since = null)))
        assertNull(ChessTournamentService.acceptedIndexerBoard(
            ChessLeaderboardPeriod.MONTH, good.copy(players = listOf(player("kaspa:a"), player("kaspa:b", wins = null))),
        ))
        // `period=all` is served as "all".
        assertNotNull(ChessTournamentService.acceptedIndexerBoard(
            ChessLeaderboardPeriod.ALL_TIME, good.copy(period = "all", since = ChessTournamentEngine.LEADERBOARD_FRESH_FROM_MS),
        ))

        // A since that is not the one this phone computes now (another cutoff, or a month that
        // has since rolled over) is not used.
        assertNull(ChessTournamentService.usableIndexerRows(ChessLeaderboardPeriod.MONTH, board.copy(since = since - 1), now))
        val december = 1_796_083_200_000L + 1 // 2026-12-01 00:00 UTC, just after
        assertNull(ChessTournamentService.usableIndexerRows(ChessLeaderboardPeriod.MONTH, board, december))
        assertNull(ChessTournamentService.usableIndexerRows(ChessLeaderboardPeriod.MONTH, null, now))
    }
}
