package com.kachat.app.services

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Network hashrate, current and historical, from the Kaspa REST API.
 *
 * Goes through [NetworkService] like every other REST caller, so a user pointed at their own
 * node's API in Connection Settings gets their own numbers. Matches iOS's
 * `KaspaNetworkStatsService`.
 */
@Singleton
class KaspaNetworkStatsService @Inject constructor(
    private val networkService: NetworkService
) {
    /** Hashrate over time as (epochMillis, PH/s), oldest first. Empty until the first fetch. */
    private val _hashrateHistory = MutableStateFlow<List<Pair<Long, Double>>>(emptyList())
    val hashrateHistory: StateFlow<List<Pair<Long, Double>>> = _hashrateHistory

    /** The most recent sample, in PH/s. */
    private val _currentHashrate = MutableStateFlow<Double?>(null)
    val currentHashrate: StateFlow<Double?> = _currentHashrate

    /**
     * Current block reward in KAS. Kaspa's reward steps down every month (the chromatic halving,
     * a smooth 1/2^(1/12) rather than a four-yearly cliff), so a hardcoded constant would be wrong
     * within weeks.
     */
    private val _blockRewardKas = MutableStateFlow<Double?>(null)
    val blockRewardKas: StateFlow<Double?> = _blockRewardKas

    /** What the reward steps down to at the next chromatic halving, and when (epoch seconds).
     *  Read from the API rather than derived: the step is a clean 1/2^(1/12), but the DAA score
     *  it lands on is not something a client can date accurately on its own. */
    private val _nextBlockRewardKas = MutableStateFlow<Double?>(null)
    val nextBlockRewardKas: StateFlow<Double?> = _nextBlockRewardKas
    private val _nextHalvingTimestamp = MutableStateFlow<Long?>(null)
    val nextHalvingTimestamp: StateFlow<Long?> = _nextHalvingTimestamp

    private var lastFetchedAt = 0L
    private var inFlight = false

    /**
     * One sample per day. Finer resolutions exist (down to 15m) but return tens of thousands of
     * points for the full chain history, which is a lot of payload for a chart a few hundred
     * pixels wide.
     */
    private val resolution = "1d"

    /**
     * The series moves slowly - a multi-day chart of a quantity that changes by a few percent a
     * day - so refetching more often than this buys nothing and costs data.
     */
    private val minimumRefetchIntervalMs = 15 * 60 * 1000L

    suspend fun refreshIfNeeded(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && lastFetchedAt != 0L && now - lastFetchedAt < minimumRefetchIntervalMs) return
        if (inFlight) return
        // NetworkService builds its clients from a settings Flow, so on a cold start the portfolio
        // can reach here before one exists. Waiting briefly beats leaving the card empty until the
        // user navigates away and back.
        val api = networkService.kaspaRestApi.value
            ?: withTimeoutOrNull(5_000) { networkService.kaspaRestApi.filterNotNull().first() }
            ?: return
        inFlight = true
        try {
            val points = seriesFrom(api.getHashrateHistory(resolution))
            if (points.isEmpty()) return
            _hashrateHistory.value = points
            _currentHashrate.value = points.last().second
            lastFetchedAt = now
            try {
                val reward = api.getBlockReward().blockreward
                if (reward > 0) _blockRewardKas.value = reward
            } catch (e: Exception) {
                // The chart is still useful without it; the estimate just says it is unavailable.
                Log.w("Hashrate", "Block reward fetch failed: ${e.message}")
            }
            try {
                val halving = api.getHalving()
                if (halving.nextHalvingAmount > 0) _nextBlockRewardKas.value = halving.nextHalvingAmount
                if (halving.nextHalvingTimestamp > 0) _nextHalvingTimestamp.value = halving.nextHalvingTimestamp
            } catch (e: Exception) {
                // The rest of the screen stands without it; those two rows just stay empty.
                Log.w("Hashrate", "Halving fetch failed: ${e.message}")
            }
        } catch (e: Exception) {
            // A chart nobody asked for is not worth an error banner; the card simply stays empty.
            Log.w("Hashrate", "Fetch failed: ${e.message}")
        } finally {
            inFlight = false
        }
    }

    companion object {
        /**
         * Blocks per second on mainnet since the Crescendo hardfork (May 2025) took Kaspa from 1
         * to 10. With the block reward this gives daily emission: at today's ~2.31 KAS reward
         * that is about 2.0 million KAS a day, which is what the network actually pays out.
         */
        const val BLOCKS_PER_SECOND = 10.0

        /**
         * Maps the API's kilohashes to PH/s and sorts oldest first.
         *
         * Deliberately UNFILTERED. The series looks like it carries wild outliers - days
         * reporting four times the current hashrate - and an outlier filter was written on iOS
         * before the data was actually checked. It is not noise: the network really did climb to
         * around 1,480 PH/s across 2024-25 before falling back to roughly 320. Filtering on a
         * multiple of the median deleted about a quarter of the series and drew a history that
         * never happened.
         */
        fun seriesFrom(samples: List<HashrateSample>): List<Pair<Long, Double>> =
            samples
                .filter { it.hashrateKh > 0 }
                // 1 PH/s = 1e12 kH/s. This divisor was right and the LABEL was wrong: the series
                // was drawn as EH/s, a thousand times what it is. Checked against both endpoints -
                // /info/hashrate/history's newest sample and /info/hashrate - and they agree at
                // ~317 PH/s, i.e. 0.32 EH/s.
                .map { it.timestamp to it.hashrateKh / 1e12 }
                .sortedBy { it.first }
    }
}

/**
 * Formats a hashrate given in PH/s, stepping the unit so both the network today (hundreds of PH/s)
 * and a single miner (tens of TH/s) read naturally.
 */
fun formatHashrate(phs: Double): String = when {
    phs >= 1_000 -> String.format(Locale.US, "%.2f EH/s", phs / 1_000)
    phs >= 1 -> String.format(Locale.US, "%.1f PH/s", phs)
    phs >= 0.001 -> String.format(Locale.US, "%.1f TH/s", phs * 1_000)
    else -> String.format(Locale.US, "%.1f GH/s", phs * 1_000_000)
}
