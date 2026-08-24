package com.kachat.app.services

import android.util.Log
import com.kachat.app.util.KaspaExtendedPublicKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.bitcoinj.crypto.DeterministicKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gap-limit scan over a kpub's derived addresses — the Cold Storage analogue of
 * [SpendingAddressDiscovery], but for a watch-only public key rather than a locally-held
 * mnemonic, and returning every discovered address with its live balance rather than just a
 * single boundary index, since the Cold Storage detail screen needs to show the whole
 * used-address history, not just "the current one."
 */
@Singleton
class ColdStorageAddressDiscovery @Inject constructor(
    private val networkService: NetworkService
) {
    data class DiscoveredAddress(val index: Int, val address: String, val balanceSompi: Long, val hasHistory: Boolean)

    /**
     * Same brief bounded wait as WalletService.readyApi: right after a cold app launch the REST
     * client can still be null for the first moments while the persisted URL loads, and every
     * caller here used to just fail instantly on that null — which on the detail screen read as
     * "this cold wallet has no addresses".
     */
    private suspend fun readyApi(): KaspaRestApi? =
        networkService.kaspaRestApi.value ?: withTimeoutOrNull(10_000) { networkService.kaspaRestApi.filterNotNull().first() }

    /**
     * Live balances for many addresses in ONE round trip (the same batched endpoint iOS's cold
     * storage list uses via getUtxosByAddresses) — this is what lets funded addresses paint fast
     * instead of waiting out a per-index sequential scan. Falls back to a small-chunk concurrent
     * sweep when the configured REST host doesn't implement the batch endpoint; addresses whose
     * lookup failed are simply absent from the result. Returns null when the API is unreachable
     * outright, so callers can tell "could not check" apart from "zero balance".
     */
    suspend fun fetchBalances(addresses: List<String>): Map<String, Long>? {
        if (addresses.isEmpty()) return emptyMap()
        val api = readyApi() ?: return null
        return try {
            api.getBalances(BalancesRequest(addresses)).associate { it.address to it.balance }
        } catch (e: Exception) {
            Log.w("ColdStorageAddressDiscovery", "Batched balances unavailable, falling back to per-address sweep", e)
            val out = mutableMapOf<String, Long>()
            coroutineScope {
                for (chunk in addresses.chunked(8)) {
                    chunk.map { address ->
                        async { address to (try { api.getBalance(address).balance } catch (e: Exception) { null }) }
                    }.awaitAll().forEach { (address, balance) -> if (balance != null) out[address] = balance }
                }
            }
            if (out.isEmpty()) null else out
        }
    }

    /**
     * @param chain 0 = external/receive, 1 = internal/change (KaChat only ever sources sends/
     * change from chain 0 for cold storage — see [KaspaExtendedPublicKey] doc).
     * @param startIndex first index to scan — the detail screen's refresh paints 0..maxDerivedIndex
     * from a batched balance call first and only gap-scans BEYOND that bound, so a failed lookup
     * here can no longer blank the already-known addresses.
     */
    suspend fun discoverAddresses(
        rootKey: DeterministicKey,
        chain: Int = 0,
        gapLimit: Int = 5,
        startIndex: Int = 0
    ): List<DiscoveredAddress> {
        readyApi() ?: return emptyList()
        val results = mutableListOf<DiscoveredAddress>()
        var consecutiveUnused = 0
        var index = startIndex

        // Sequential, one address at a time - a prior attempt at concurrent/batched lookups here
        // (firing several addresses' history+balance calls at once against the shared public REST
        // API) made things *worse*, not faster: it had no rate-limit handling, so a burst of
        // concurrent requests routinely got throttled/timed out, and - worse - a single failed
        // lookup anywhere in a batch (via checkAddress returning null) aborted the entire scan
        // early. One-at-a-time is slower per-request in isolation but finishes the whole scan
        // faster and more reliably in practice. Matches iOS's WalletManager.discoverSpendingAddresses/
        // ColdStorageManager.discoverAddresses, both deliberately sequential for the same reason.
        while (consecutiveUnused < gapLimit) {
            val result = checkAddress(rootKey, chain, index) ?: break
            results.add(result)
            consecutiveUnused = if (result.hasHistory || result.balanceSompi > 0) 0 else consecutiveUnused + 1
            index++
        }

        return results
    }

    /**
     * One specific address's live balance/history, outside the gap-limit scan — used to pull in
     * an index a user manually generated past the scan's own stopping point (see
     * [com.kachat.app.viewmodels.ColdStorageViewModel.generateMoreAddresses]), which
     * [discoverAddresses] alone would never reach on a fresh unused-account rescan.
     */
    suspend fun checkAddress(rootKey: DeterministicKey, chain: Int, index: Int): DiscoveredAddress? {
        val api = readyApi() ?: return null
        val address = try {
            KaspaExtendedPublicKey.deriveChildAddress(rootKey, chain, index)
        } catch (e: Exception) {
            return null
        }
        val hasHistory = try {
            api.getTransactions(address, limit = 1).isNotEmpty()
        } catch (e: Exception) {
            Log.w("ColdStorageAddressDiscovery", "Lookup failed for index $index", e)
            return null
        }
        val balance = try {
            api.getBalance(address).balance
        } catch (e: Exception) {
            0L
        }
        return DiscoveredAddress(index, address, balance, hasHistory)
    }

    /** History-only probe for the detail screen's background Used backfill — the balance is
     *  already known from the batched fetch, so this skips the second round trip [checkAddress]
     *  would make. Null means the lookup failed (NOT "unused"). */
    suspend fun hasHistory(address: String): Boolean? {
        val api = readyApi() ?: return null
        return try {
            api.getTransactions(address, limit = 1).isNotEmpty()
        } catch (e: Exception) {
            Log.w("ColdStorageAddressDiscovery", "History probe failed for $address", e)
            null
        }
    }

    data class AddressTransaction(
        val txId: String,
        val sent: Boolean, // true = this address was a sender on this tx
        val amountSompi: Long, // net amount that left (sent) or arrived (received) — excludes change back to itself
        val blockTimeMillis: Long?
    )

    /**
     * On-chain transaction history for a single address, newest first. Direction/amount aren't
     * fields the REST API returns directly — a tx is only "sent" from [address] if one of its
     * inputs' resolved previous-outpoint address matches (the default `resolve_previous_outpoints`
     * behavior on [KaspaRestApi.getTransactions] already resolves this); the amount then excludes
     * whatever output pays change back to [address] itself, mirroring the same sent-vs-received
     * inference [com.kachat.app.repository.ChatRepository]'s payment sync already relies on.
     */
    suspend fun getTransactionHistory(address: String, limit: Int = 50): List<AddressTransaction> {
        val api = networkService.kaspaRestApi.value ?: return emptyList()
        val transactions = try {
            api.getTransactions(address, limit = limit)
        } catch (e: Exception) {
            Log.w("ColdStorageAddressDiscovery", "Failed to fetch transaction history for $address", e)
            return emptyList()
        }
        return transactions.map { tx ->
            val sent = tx.inputs.any { it.previousOutpointAddress == address }
            val amount = if (sent) {
                tx.outputs.filter { it.scriptPublicKeyAddress != address }.sumOf { it.amount }
            } else {
                tx.outputs.filter { it.scriptPublicKeyAddress == address }.sumOf { it.amount }
            }
            AddressTransaction(tx.transactionId, sent, amount, tx.blockTime)
        }.sortedByDescending { it.blockTimeMillis ?: 0L }
    }

    /**
     * Full paginated transaction history for a single address, oldest first — unlike
     * [getTransactionHistory] (a single page, newest 50, for the Cold Storage display list), this
     * loops [KaspaRestApi.getTransactions]' `offset` until a page returns fewer than [pageSize]
     * rows or [maxTransactions] is hit, for callers that need a complete history rather than a
     * recent-activity list (currently only "Add Kaspa Address" portfolio auto-import). Mirrors
     * iOS's `ChatService.fetchFullTransactionsPaginated`'s loop shape.
     */
    suspend fun getFullTransactionHistoryPaginated(
        address: String,
        // Confirmed live against api.kaspa.org that limit=500 works fine in a single call
        // (~0.7s) - cuts a full 500-tx history down to 1 round trip instead of 10 sequential
        // limit=50 ones, without changing anything else about this loop's shape/correctness.
        pageSize: Int = 500,
        maxTransactions: Int = 500
    ): List<AddressTransaction> {
        val api = networkService.kaspaRestApi.value ?: return emptyList()
        val all = mutableListOf<AddressTransaction>()
        var offset = 0

        while (all.size < maxTransactions) {
            val page = try {
                api.getTransactions(address, limit = pageSize, offset = offset)
            } catch (e: Exception) {
                Log.w("ColdStorageAddressDiscovery", "Paginated fetch failed for $address at offset $offset", e)
                break
            }
            if (page.isEmpty()) break

            all.addAll(
                page.map { tx ->
                    val sent = tx.inputs.any { it.previousOutpointAddress == address }
                    val amount = if (sent) {
                        tx.outputs.filter { it.scriptPublicKeyAddress != address }.sumOf { it.amount }
                    } else {
                        tx.outputs.filter { it.scriptPublicKeyAddress == address }.sumOf { it.amount }
                    }
                    AddressTransaction(tx.transactionId, sent, amount, tx.blockTime)
                }
            )

            if (page.size < pageSize) break
            offset += pageSize
        }

        return all.sortedBy { it.blockTimeMillis ?: 0L }
    }

    data class AddressUtxo(
        val transactionId: String,
        val index: Int,
        val amountSompi: Long,
        val isCoinbase: Boolean
    )

    /** Unspent outputs currently sitting at a single address — backs the Cold Storage tx history
     *  screen's "UTXOs" tab. */
    suspend fun getUtxos(address: String): List<AddressUtxo> {
        val api = networkService.kaspaRestApi.value ?: return emptyList()
        return try {
            api.getUtxos(address).map {
                AddressUtxo(it.outpoint.transactionId, it.outpoint.index, it.utxoEntry.amount, it.utxoEntry.isCoinbase)
            }
        } catch (e: Exception) {
            Log.w("ColdStorageAddressDiscovery", "Failed to fetch UTXOs for $address", e)
            emptyList()
        }
    }
}
