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
    /** Hashrate over time as (epochMillis, EH/s), oldest first. Empty until the first fetch. */
    private val _hashrateHistory = MutableStateFlow<List<Pair<Long, Double>>>(emptyList())
    val hashrateHistory: StateFlow<List<Pair<Long, Double>>> = _hashrateHistory

    /** The most recent sample, in EH/s. */
    private val _currentHashrate = MutableStateFlow<Double?>(null)
    val currentHashrate: StateFlow<Double?> = _currentHashrate

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
        } catch (e: Exception) {
            // A chart nobody asked for is not worth an error banner; the card simply stays empty.
            Log.w("Hashrate", "Fetch failed: ${e.message}")
        } finally {
            inFlight = false
        }
    }

    companion object {
        /**
         * Maps the API's kilohashes to EH/s and sorts oldest first.
         *
         * Deliberately UNFILTERED. The series looks like it carries wild outliers - days
         * reporting four times the current hashrate - and an outlier filter was written on iOS
         * before the data was actually checked. It is not noise: the network really did climb to
         * around 1,480 EH/s across 2024-25 before falling back to roughly 400. Filtering on a
         * multiple of the median deleted about a quarter of the series and drew a history that
         * never happened.
         */
        fun seriesFrom(samples: List<HashrateSample>): List<Pair<Long, Double>> =
            samples
                .filter { it.hashrateKh > 0 }
                // 1 EH/s = 1e12 kH/s.
                .map { it.timestamp to it.hashrateKh / 1e12 }
                .sortedBy { it.first }
    }
}

/** Formats a hashrate in EH/s, stepping down the units so an early-history value stays legible. */
fun formatHashrate(ehs: Double): String = when {
    ehs >= 1 -> String.format(Locale.US, "%.2f EH/s", ehs)
    ehs >= 0.001 -> String.format(Locale.US, "%.1f PH/s", ehs * 1_000)
    else -> String.format(Locale.US, "%.1f TH/s", ehs * 1_000_000)
}
