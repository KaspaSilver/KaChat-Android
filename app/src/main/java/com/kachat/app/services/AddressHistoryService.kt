package com.kachat.app.services

import android.util.Log
import com.kachat.app.util.redactedForLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One address's on-chain history and coins, by address string, for the address pages (the
 * chatting address and the spending addresses in Manage Addresses). It used to live in Cold
 * Storage's address discovery, which left the app with Cold Storage on 2026-10-24 (iOS d54ea87).
 */
@Singleton
class AddressHistoryService @Inject constructor(
    private val networkService: NetworkService,
) {
    data class AddressTransaction(
        val txId: String,
        val sent: Boolean, // true = this address was a sender on this tx
        val amountSompi: Long, // net amount that left (sent) or arrived (received) — excludes change back to itself
        val blockTimeMillis: Long?,
        /** What this transaction paid the network, when the API resolved every input's amount.
         *  On a received transaction it is what the sender paid (iOS 9839906). */
        val feeSompi: Long? = null,
    )

    /**
     * Whether a history fetch actually finished, alongside what it got.
     *
     * [complete] false means the request failed after its retries - the list is not the address's
     * real history. Callers rendering a list need this: without it a rate-limited or timed-out
     * request is indistinguishable from an address with no transactions, and "No transactions
     * yet." is a confident lie about someone's money.
     */
    data class HistoryResult(
        val transactions: List<AddressTransaction>,
        val complete: Boolean,
    )

    data class AddressUtxo(
        val transactionId: String,
        val index: Int,
        val amountSompi: Long,
        val isCoinbase: Boolean
    )

    /** Retry on a growing pause before giving up. api.kaspa.org is a shared public endpoint that
     *  rate-limits, and a single 429 used to return "no transactions". */
    private val historyRetryDelaysMs = listOf(0L, 600L, 2_000L, 5_000L)

    /**
     * On-chain transaction history for a single address, newest first. Direction/amount aren't
     * fields the REST API returns directly — a tx is only "sent" from [address] if one of its
     * inputs' resolved previous-outpoint address matches (the default `resolve_previous_outpoints`
     * behavior on [KaspaRestApi.getTransactions] already resolves this); the amount then excludes
     * whatever output pays change back to [address] itself, mirroring the same sent-vs-received
     * inference [com.kachat.app.repository.ChatRepository]'s payment sync already relies on.
     */
    suspend fun getTransactionHistoryResult(address: String, limit: Int = HISTORY_PAGE_SIZE): HistoryResult {
        val api = networkService.kaspaRestApi.value ?: return HistoryResult(emptyList(), complete = false)
        var transactions: List<TransactionResponse>? = null
        for ((attempt, delay) in historyRetryDelaysMs.withIndex()) {
            if (delay > 0) kotlinx.coroutines.delay(delay)
            try {
                transactions = api.getTransactions(address, limit = limit)
                break
            } catch (e: Exception) {
                Log.w(TAG, "History attempt ${attempt + 1} failed for ${address.redactedForLog()}", e)
            }
        }
        val fetched = transactions
            ?: return HistoryResult(emptyList(), complete = false)
        // block rewards the chain never accepted paid nothing: not history
        return HistoryResult(mapHistory(address, fetched.filter { it.belongsInHistory }), complete = true)
    }

    private fun mapHistory(address: String, transactions: List<TransactionResponse>): List<AddressTransaction> {
        return transactions.map { tx ->
            val sent = tx.inputs.any { it.previousOutpointAddress == address }
            val amount = if (sent) {
                tx.outputs.filter { it.scriptPublicKeyAddress != address }.sumOf { it.amount }
            } else {
                tx.outputs.filter { it.scriptPublicKeyAddress == address }.sumOf { it.amount }
            }
            AddressTransaction(tx.transactionId, sent, amount, tx.blockTime, tx.feeSompi)
        }.sortedByDescending { it.blockTimeMillis ?: 0L }
    }

    /** The unspent outputs at a single address - the address page's UTXOs tab. A node that could
     *  not be asked throws instead of reading as an empty address, so the list can show iOS's
     *  "Couldn't load the coins at this address" rather than "No UTXOs.". */
    suspend fun getUtxosOrThrow(address: String): List<AddressUtxo> {
        val api = networkService.kaspaRestApi.value
            ?: throw IllegalStateException("Not connected to the Kaspa network yet. Try again in a moment.")
        return api.getUtxos(address).map {
            AddressUtxo(it.outpoint.transactionId, it.outpoint.index, it.utxoEntry.amount, it.utxoEntry.isCoinbase)
        }
    }

    companion object {
        private const val TAG = "AddressHistoryService"

        /** One address's history page: the 200 iOS asks for on Manage Addresses and the address
         *  book (pageSize 200, maxTransactions 200). */
        const val HISTORY_PAGE_SIZE = 200
    }
}
