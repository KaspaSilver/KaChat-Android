package com.kachat.app.services

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * KaChat broadcast indexer (BROADCAST_INDEXER.md): 24/7 chain watcher for the featured
 * #kaspa/#kachat-bugs rooms, serving their history over REST so clients can backfill what was
 * sent while the app was closed. Base URL: Settings > Connection Settings > Broadcast Indexer
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
}

data class BroadcastHistoryResponse(
    val messages: List<BroadcastHistoryRow>?,
    val hasMore: Boolean?,
)

data class BroadcastHistoryRow(
    val txId: String?,
    val channel: String?,
    val senderAddress: String?,
    val content: String?,
    val blockTime: Long?,
)
