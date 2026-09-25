package com.kachat.app.services

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a chart can be measured against besides a currency: Vanguard's S&P 500 ETF, gold and
 * silver, priced in US dollars. Yahoo Finance's chart endpoint, keyless, the way CoinGecko is for
 * KAS. Empty on any failure, like the other price clients - the chart then keeps whatever it had.
 *
 * KAS against one of these is KAS/USD over the pair's USD price at the same moment. A pair only
 * trades market hours, so between sessions its last close stands: a weekend line against VOO is
 * KAS's own move, which is the honest reading. Mirrors iOS's MarketPairService (39adefe).
 */
@Singleton
class MarketPairService @Inject constructor() {

    private val gson = Gson()

    /** Yahoo's chart payload, only the fields that matter here. */
    private data class ChartResponse(val chart: Chart?) {
        data class Chart(val result: List<Result>?)
        data class Result(
            val meta: Meta?,
            val timestamp: List<Long>?,
            val indicators: Indicators?,
        )
        data class Meta(@SerializedName("regularMarketPrice") val regularMarketPrice: Double?)
        data class Indicators(val quote: List<Quote>?)
        data class Quote(val close: List<Double?>?)
    }

    /** A pair's USD prices and its latest price - points oldest first, empty when unavailable. */
    data class PairHistory(val points: List<Pair<Long, Double>>, val latest: Double?)

    /**
     * The pair's USD prices covering a chart base range (see the view model's base days), oldest
     * first, and its latest price. A base of one day comes as five days of 5-minute points, so a
     * KAS point on a weekend still has a close before it to stand on.
     */
    suspend fun history(pair: ChartPair, baseDays: Int): PairHistory = withContext(Dispatchers.IO) {
        val symbol = pair.yahooSymbol ?: return@withContext PairHistory(emptyList(), null)
        val (range, interval) = when {
            baseDays == 0 -> "max" to "1d"
            baseDays <= 1 -> "5d" to "5m"
            baseDays <= 90 -> "3mo" to "1h"
            else -> "2y" to "1d"
        }
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$symbol" +
            "?range=$range&interval=$interval&includePrePost=false"
        repeat(2) { attempt ->
            try {
                val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 12_000
                    readTimeout = 25_000
                    // Yahoo's endpoint answers a browser; a bare client gets a 403.
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
                    )
                }
                val code = connection.responseCode
                val body = if (code == 200) connection.inputStream.bufferedReader().use { it.readText() } else null
                connection.disconnect()
                if (body != null) {
                    val decoded = gson.fromJson(body, ChartResponse::class.java)
                    val result = decoded?.chart?.result?.firstOrNull()
                        ?: return@withContext PairHistory(emptyList(), null)
                    val stamps = result.timestamp.orEmpty()
                    val closes = result.indicators?.quote?.firstOrNull()?.close.orEmpty()
                    val points = buildList {
                        stamps.forEachIndexed { index, stamp ->
                            val close = closes.getOrNull(index) ?: return@forEachIndexed
                            if (close > 0) add(stamp * 1000 to close)
                        }
                    }
                    return@withContext PairHistory(points, result.meta?.regularMarketPrice ?: points.lastOrNull()?.second)
                }
                if (attempt > 0 || (code != 429 && code < 500)) return@withContext PairHistory(emptyList(), null)
                delay(2_000)
            } catch (e: Exception) {
                Log.w("MarketPairService", "Could not read ${pair.code} history", e)
                return@withContext PairHistory(emptyList(), null)
            }
        }
        PairHistory(emptyList(), null)
    }

    companion object {
        /**
         * KAS priced in the pair: each KAS/USD point over the pair's last price at or before it.
         * KAS points from before the pair's first price are dropped rather than guessed.
         */
        fun divide(kas: List<Pair<Long, Double>>, pair: List<Pair<Long, Double>>): List<Pair<Long, Double>> {
            if (pair.isEmpty()) return emptyList()
            val result = ArrayList<Pair<Long, Double>>(kas.size)
            var index = 0
            for (point in kas) {
                while (index + 1 < pair.size && pair[index + 1].first <= point.first) index++
                val reference = pair[index]
                if (reference.first > point.first || reference.second <= 0) continue
                result.add(point.first to point.second / reference.second)
            }
            return result
        }
    }
}

/**
 * The things a chart can be compared against, one at a time. Bitcoin is a currency the app
 * already knows, so it needs no Yahoo symbol; the rest are quoted in US dollars and divided into
 * KAS/USD. Mirrors iOS's ChartPair (39adefe).
 */
enum class ChartPair(
    val id: String,
    val code: String,
    val title: String,
    val subtitle: String,
    val yahooSymbol: String?,
    /** Written after an amount: "0.0000203 oz". */
    val unitSuffix: String,
) {
    BITCOIN("bitcoin", "BTC", "Bitcoin", "Kaspa priced in bitcoin", null, ""),
    VOO("voo", "VOO", "VOO", "Shares of Vanguard's S&P 500 ETF", "VOO", " VOO"),
    GOLD("gold", "XAU", "Gold", "Troy ounces of gold", "GC=F", " oz"),
    SILVER("silver", "XAG", "Silver", "Troy ounces of silver", "SI=F", " oz");

    companion object {
        fun fromId(id: String?): ChartPair? = entries.firstOrNull { it.id == id }
    }
}
