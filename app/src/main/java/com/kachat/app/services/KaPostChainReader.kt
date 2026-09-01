package com.kachat.app.services

import android.util.Log
import com.kachat.app.util.KaPostsProtocol
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads a KaPost straight off the transaction it was published as. Mirrors iOS's
 * `KaPostChainReader`.
 *
 * The K indexer has no single-post lookup (`get-post?id=` is still a NEEDED item in
 * KAPOSTS_INDEXER.md), so any post outside the feed window is unanswerable by the API - which is
 * why opening a shared or notified post used to fail with "Post not found - it may be older than
 * the feed window" once the search through feed, own profile and the notification author's posts
 * came up empty. The chain has every post that ever existed: the post id IS the transaction id.
 */
@Singleton
class KaPostChainReader @Inject constructor(
    private val networkService: NetworkService,
) {
    data class Record(
        val txId: String,
        val action: String,
        val authorPubkey: String,
        val message: String,
        /** Parent for a reply, quoted post for a quote. */
        val referencedId: String?,
        val blockTimeMillis: Long?,
    )

    /**
     * Null when the REST API has no such transaction, when it carries no payload, or when the
     * payload is not a KaPosts message (a vote, a follow, or another app's transaction).
     */
    suspend fun fetch(txId: String): Record? {
        if (txId.isEmpty()) return null
        // NetworkService builds its clients from a settings Flow, so a cold start can reach here
        // before one exists.
        val api = networkService.kaspaRestApi.value
            ?: withTimeoutOrNull(5_000) { networkService.kaspaRestApi.filterNotNull().first() }
            ?: return null
        val tx = try {
            api.getTransactionPayload(txId)
        } catch (e: Exception) {
            Log.w(TAG, "No transaction for post $txId: ${e.message}")
            return null
        }
        val payload = tx.payload?.takeIf { it.isNotBlank() }?.let { hexToString(it) } ?: return null
        val parsed = KaPostsProtocol.parseChainPayload(payload) ?: return null
        return Record(
            txId = txId,
            action = parsed.action,
            authorPubkey = parsed.authorPubkey,
            message = parsed.message,
            referencedId = parsed.referencedId,
            blockTimeMillis = tx.blockTime,
        )
    }

    private fun hexToString(hex: String): String? = try {
        val clean = hex.trim()
        if (clean.length % 2 != 0) null
        else String(ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }, Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val TAG = "KaPostChainReader"
    }
}
