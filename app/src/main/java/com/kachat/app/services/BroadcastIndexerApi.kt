package com.kachat.app.services

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * KaChat broadcast indexer (PUBLIC_CHATS_INDEXER.md): 24/7 chain watcher for the featured
 * #kaspa/#kachat-bugs rooms, serving their history over REST so clients can backfill what was
 * sent while the app was closed. Base URL: Settings > Connection Settings > Public Chats Indexer
 * (shares the KaPosts indexer's domain). The app merges rows with its own live scanning and
 * dedupes by txId - the server only needs to be honest and reasonably complete, not realtime.
 */
interface BroadcastIndexerApi {
    @GET("get-broadcasts")
    suspend fun getBroadcasts(
        @Query("channel") channel: String,
        @Query("limit") limit: Int = 200,
        @Query("before") before: Long? = null,
    ): BroadcastHistoryResponse

    /**
     * GET /broadcast-channels -> {"channels": [...]}: the rooms this indexer tracks now (set on
     * the indexer's dashboard). An indexer that predates the endpoint answers 404, which throws
     * (iOS `PublicChatIndexerClient.fetchTrackedChannels`, 21c480d).
     */
    @GET("broadcast-channels")
    suspend fun getTrackedChannels(): BroadcastTrackedChannelsResponse

    /**
     * GET /chess/leaderboard?period=month|all&limit= (ONLINE_CHESS.md §6): the arena replayed
     * over all its history, for one period. An indexer that predates `period` answers without
     * it, and its board is not used (iOS 77d6038).
     */
    @GET("chess/leaderboard")
    suspend fun getChessLeaderboard(
        @Query("period") period: String,
        @Query("limit") limit: Int = 1000,
    ): ChessIndexerLeaderboardResponse
}

/** Every field is nullable so a row missing one can be refused as a whole, like iOS's strict
 *  Decodable (see ChessTournamentService.acceptedIndexerBoard). */
data class ChessIndexerLeaderboardResponse(
    val players: List<ChessIndexerLeaderboardPlayer?>?,
    val period: String?,
    val since: Long?,
)

data class ChessIndexerLeaderboardPlayer(
    val address: String?,
    val wins: Int?,
    val losses: Int?,
    val duelWins: Int?,
    val duelLosses: Int?,
    val tournamentGameWins: Int?,
    val tournamentGameLosses: Int?,
    val tournamentsPlayed: Int?,
    val tournamentsWon: Int?,
    val tournamentsLost: Int?,
    val lastPlayedAt: Long?,
)

data class BroadcastTrackedChannelsResponse(
    val channels: List<String?>?,
)

data class BroadcastHistoryResponse(
    val messages: List<BroadcastHistoryRow>?,
    val hasMore: Boolean?,
)

data class BroadcastHistoryRow(
    val txId: String?,
    val channel: String?,
    // The indexer's guess, never stored: the sender is verified from the transaction instead
    // (input 0's address == output 0's, BroadcastSenderVerifier, audit XP-012).
    val senderAddress: String?,
    val content: String?,
    val blockTime: Long?,
)
