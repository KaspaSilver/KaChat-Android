package com.kachat.app.services

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

// -------------------------------------------------------------------------
// Data transfer objects — CoinGecko's free public API, no key required.
// Same endpoints Kaspium's wallet uses for its own KAS price display
// (kaspium_wallet/lib/coingecko/coingecko_repository.dart).
// -------------------------------------------------------------------------

/**
 * `/api/v3/simple/price?ids=kaspa&vs_currencies=usd&include_24hr_change=true` response shape:
 * `{"kaspa":{"usd":0.123,"usd_24h_change":-3.45}}` — the 24h-change key just lives in the same
 * map as the price, keyed `"{currency}_24h_change"`.
 */
data class SimplePriceResponse(
    val kaspa: Map<String, Double>
)

/** `/api/v3/coins/kaspa/market_chart` response — `prices` is a list of `[timestampMillis, priceUsd]` pairs. */
data class MarketChartResponse(
    val prices: List<List<Double>>
)

/**
 * `/api/v3/coins/kaspa/history?date=DD-MM-YYYY` response — daily-granularity snapshot for a
 * specific past date (used by "Add Kaspa Address" to price auto-imported transactions).
 * `marketData` is absent (not present-with-nulls) when CoinGecko has no snapshot for that date —
 * a very recent date, or one before Kaspa was listed.
 */
data class CoinGeckoHistoryResponse(
    @SerializedName("market_data") val marketData: HistoryMarketData?
)

data class HistoryMarketData(
    @SerializedName("current_price") val currentPrice: Map<String, Double>?
)

/**
 * `/api/v3/coins/markets?vs_currency=usd&ids=kaspa` row — market cap and market-cap rank.
 *
 * CoinMarketCap's own API needs a key, and its rank agrees with CoinGecko's in all but the
 * occasional off-by-one around ties, so this is the figure people recognise without shipping a
 * second provider and a secret to reach it.
 */
data class CoinGeckoMarketRow(
    @SerializedName("market_cap") val marketCap: Double?,
    @SerializedName("market_cap_rank") val marketCapRank: Int?
)

// -------------------------------------------------------------------------
// Retrofit interface
// -------------------------------------------------------------------------

interface CoinGeckoApi {

    @GET("api/v3/simple/price")
    suspend fun getSimplePrice(
        @Query("ids") ids: String = "kaspa",
        @Query("vs_currencies") vsCurrencies: String = "usd",
        @Query("include_24hr_change") include24hrChange: Boolean = true
    ): SimplePriceResponse

    @GET("api/v3/coins/markets")
    suspend fun getMarkets(
        @Query("vs_currency") vsCurrency: String = "usd",
        @Query("ids") ids: String = "kaspa"
    ): List<CoinGeckoMarketRow>

    @GET("api/v3/coins/kaspa/market_chart")
    suspend fun getMarketChart(
        @Query("vs_currency") vsCurrency: String = "usd",
        @Query("days") days: Int = 30
    ): MarketChartResponse

    /** [date] must be `DD-MM-YYYY` (CoinGecko's required format for this endpoint). */
    @GET("api/v3/coins/kaspa/history")
    suspend fun getHistory(
        @Query("date") date: String,
        @Query("localization") localization: Boolean = false
    ): CoinGeckoHistoryResponse
}
