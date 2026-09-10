package com.kachat.app.services

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Has this address ever been used, and when" - for a whole list of addresses at once.
 *
 * Both address scans in the app used to establish that one address at a time, and both were slow
 * and shallow because of it. The balance sweeps were already batched; what actually dominated a
 * discover was KNS, asked about the first two hundred addresses ONE AT A TIME at roughly a third
 * of a second each, which no amount of batching balances touches.
 *
 * `POST addresses/active` removes the reason to walk at all: ask about the whole window in a
 * handful of requests, and only the addresses the chain says were actually touched cost anything
 * further. Neither a balance nor a KNS domain can sit on an address that has never been touched,
 * so it is safe as a pre-filter.
 *
 * Measured against api.kaspa.org while building this: a 1000-address window answers in about
 * 0.65 seconds across four requests.
 */
@Singleton
class AddressActivityService @Inject constructor(
    private val networkService: NetworkService,
) {
    companion object {
        private const val TAG = "AddressActivity"

        /**
         * Addresses per request, and how many requests to keep in flight.
         *
         * From measurement, not taste: 250 addresses answer in ~350ms and 300 in ~315ms, but 500
         * in a single body stalls past 25 seconds. The ceiling is real and well under 500.
         */
        const val BATCH_SIZE = 250
        const val CONCURRENCY = 2
    }

    private suspend fun readyApi(): KaspaRestApi? =
        networkService.kaspaRestApi.value
            ?: withTimeoutOrNull(10_000) { networkService.kaspaRestApi.filterNotNull().first() }

    /**
     * The subset of [addresses] the chain has ever seen, mapped to the block time of their last
     * transaction (0 when the server reports activity without a timestamp).
     *
     * Returns null when the configured REST host does not serve the route, or could not be
     * reached. That is deliberately distinct from an empty result: empty means every address is
     * genuinely unused, null means we do not know - which is the caller's signal to fall back
     * rather than report an emptiness it never confirmed. One unreadable batch makes the whole
     * answer null for the same reason: a failed request is not evidence of an unused address, and
     * treating it as one hides real balances.
     */
    suspend fun lastActivity(addresses: List<String>): Map<String, Long>? {
        if (addresses.isEmpty()) return emptyMap()
        val api = readyApi() ?: return null

        val merged = mutableMapOf<String, Long>()
        val batches = addresses.chunked(BATCH_SIZE)
        // Two in flight at a time, a pair at a time. Simple on purpose: the batch count is single
        // digits, so a sliding window would be machinery for nothing.
        for (group in batches.chunked(CONCURRENCY)) {
            val results = coroutineScope {
                group.map { batch ->
                    async {
                        try {
                            api.getActiveAddresses(ActiveAddressesRequest(batch))
                        } catch (e: Exception) {
                            Log.w(TAG, "Bulk activity lookup unavailable", e)
                            null
                        }
                    }
                }.awaitAll()
            }
            for (rows in results) {
                if (rows == null) return null
                for (row in rows) {
                    if (row.active) merged[row.address] = row.lastTxBlockTime ?: 0L
                }
            }
        }
        return merged
    }

    /** Just the set that has ever been used, for callers that do not need the timestamps. */
    suspend fun activeAddresses(addresses: List<String>): Set<String>? =
        lastActivity(addresses)?.keys
}
