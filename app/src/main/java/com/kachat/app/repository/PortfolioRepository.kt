package com.kachat.app.repository

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.kachat.app.models.PortfolioTransactionEntity
import com.kachat.app.services.CoinGeckoApi
import com.kachat.app.services.ColdStorageAddressDiscovery
import com.kachat.app.services.PortfolioManager
import com.kachat.app.services.WalletManager
import com.kachat.app.services.database.KaChatDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import retrofit2.HttpException
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.SimpleTimeZone
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * KAS portfolio tracker — a manual buy/sell ledger plus current/historical price from
 * CoinGecko's free public API (same source Kaspium's wallet uses for its own price display).
 * Entries are normally user-entered (an address's transaction history can't reliably distinguish
 * a real purchase from an ordinary payment, matching CoinMarketCap's own portfolio feature), but
 * [importAddress] offers an explicit opt-in on-chain auto-import for users who want every
 * send/receive on an address treated as a trade with no filtering — see its doc comment.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PortfolioRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: KaChatDatabase,
    private val coinGeckoApi: CoinGeckoApi,
    private val walletManager: WalletManager,
    private val portfolioManager: PortfolioManager,
    private val coldStorageAddressDiscovery: ColdStorageAddressDiscovery
) {
    /**
     * Whichever portfolio is currently active within whichever wallet is currently active —
     * re-emits automatically on either switching, same pattern as BroadcastRepository/
     * GroupRepository. Pre-wallet-scoping rows (walletAddress="") are claimed first, then
     * pre-portfolio-scoping rows (portfolioId="") are claimed for the wallet's default portfolio
     * — a very old install upgrading straight from before either migration needs both claims in
     * that order.
     */
    fun getTransactions(): Flow<List<PortfolioTransactionEntity>> {
        return combine(walletManager.activeAddressFlow, portfolioManager.activePortfolioIdFlow) { address, portfolioId ->
            address to portfolioId
        }.flatMapLatest { (address, portfolioId) ->
            if (address == null || portfolioId == null) {
                flowOf(emptyList())
            } else {
                database.portfolioDao().claimUnscopedTransactions(address)
                database.portfolioDao().claimUnscopedPortfolio(address, portfolioId)
                database.portfolioDao().getTransactions(address, portfolioId)
            }
        }
    }

    /** Every portfolio's transactions for the current wallet, unfiltered — used by the picker header to compute every portfolio's card simultaneously. */
    fun getAllTransactionsForWallet(): Flow<List<PortfolioTransactionEntity>> {
        return walletManager.activeAddressFlow.flatMapLatest { address ->
            if (address == null) flowOf(emptyList())
            else database.portfolioDao().getAllTransactionsForWallet(address)
        }
    }

    private suspend fun currentPortfolioId(): String? = portfolioManager.activePortfolioIdFlow.first()

    /**
     * [portfolioId] targets a specific ledger rather than whichever is active - "Add to
     * Portfolio" from an address history lets the user pick. [sourceAddress]/[sourceTxId] are set
     * when the row came from a real on-chain transaction, so a later add of the same transaction
     * is recognised instead of silently double-counting it.
     */
    suspend fun addTransaction(
        type: String,
        amountSompi: Long,
        fiatValue: Double,
        timestampMillis: Long = System.currentTimeMillis(),
        notes: String? = null,
        portfolioId: String? = null,
        sourceAddress: String? = null,
        sourceTxId: String? = null,
    ) {
        val targetPortfolioId = portfolioId ?: currentPortfolioId() ?: return
        database.portfolioDao().insert(
            PortfolioTransactionEntity(
                id = UUID.randomUUID().toString(),
                walletAddress = walletManager.getAddress(),
                portfolioId = targetPortfolioId,
                type = type,
                amountSompi = amountSompi,
                fiatValue = fiatValue,
                timestampMillis = timestampMillis,
                notes = notes,
                sourceAddress = sourceAddress,
                sourceTxId = sourceTxId,
            )
        )
    }

    /**
     * Same [id] — Room's REPLACE conflict strategy on insert() means this overwrites the
     * existing row. Preserves the row's existing [PortfolioTransactionEntity.portfolioId] (an
     * edit never moves a transaction to a different portfolio) rather than re-stamping with
     * whatever's currently active.
     */
    suspend fun updateTransaction(id: String, type: String, amountSompi: Long, fiatValue: Double, timestampMillis: Long, notes: String? = null) {
        val existingPortfolioId = database.portfolioDao().getAllTransactionsForWallet(walletManager.getAddress()).first()
            .firstOrNull { it.id == id }?.portfolioId
            ?: currentPortfolioId() ?: return
        database.portfolioDao().insert(
            PortfolioTransactionEntity(
                id = id,
                walletAddress = walletManager.getAddress(),
                portfolioId = existingPortfolioId,
                type = type,
                amountSompi = amountSompi,
                fiatValue = fiatValue,
                timestampMillis = timestampMillis,
                notes = notes
            )
        )
    }

    suspend fun deleteTransaction(id: String) = database.portfolioDao().delete(id)

    /** Null on any failure (offline, rate-limited, etc.) — callers fall back to the last-known price.
     *  [currency] is the lowercase ISO 4217 code (Settings > Customization > Currency, defaults to "usd"). */
    /**
     * [PriceWithChange.change24hPercent] is nil only on a decode/response oddity, not treated as
     * a separate failure from the price fetch itself — CoinGecko returns both in the same call
     * (`include_24hr_change=true`), so there's no second request to independently fail.
     */
    data class PriceWithChange(val price: Double, val change24hPercent: Double?)

    /**
     * Wall-clock time before which CoinGecko told us not to retry (a 429's Retry-After header,
     * observed live at 59 seconds on the keyless tier) — null when no throttle window is known.
     * Callers scheduling a deferred retry should wait until this passes rather than retrying
     * blind; cleared on any successful request.
     */
    @Volatile
    var throttledUntilMillis: Long? = null
        private set

    private fun recordThrottleWindow(retryAfterSeconds: Double) {
        throttledUntilMillis = System.currentTimeMillis() + (retryAfterSeconds * 1000).toLong()
    }

    /**
     * A 429/5xx gets one in-place retry only when the server's Retry-After fits inside
     * [MAX_INLINE_RETRY_SECONDS] — CoinGecko's real throttle window is ~59s, and parking a
     * caller that long just holds a refresh spinner hostage. Longer windows are recorded in
     * [throttledUntilMillis] for the ViewModel's deferred retry instead. Successes persist to
     * [readPersistedPrice]'s cache so the UI can paint the last-known price instantly on the
     * next open even when every fetch in a session is throttled.
     */
    suspend fun getCurrentPriceUsd(currency: String = "usd"): PriceWithChange? {
        repeat(2) { attempt ->
            try {
                val kaspa = coinGeckoApi.getSimplePrice(vsCurrencies = currency).kaspa
                val price = kaspa[currency] ?: return null
                val result = PriceWithChange(price, kaspa["${currency}_24h_change"])
                persistCurrentPrice(result, currency)
                throttledUntilMillis = null
                return result
            } catch (e: HttpException) {
                if (attempt > 0 || (e.code() != 429 && e.code() < 500)) return null
                val retryAfterSeconds = e.response()?.headers()?.get("Retry-After")?.toDoubleOrNull() ?: 2.0
                if (retryAfterSeconds > MAX_INLINE_RETRY_SECONDS) {
                    recordThrottleWindow(retryAfterSeconds)
                    return null
                }
                delay((retryAfterSeconds * 1000).toLong())
            } catch (e: Exception) {
                // Includes coroutine cancellation mid-request — bail out quietly, same
                // "degrade gracefully" contract as getPriceHistory.
                return null
            }
        }
        return null
    }

    /**
     * (timestampMillis, price) pairs in [currency], oldest first — empty on failure rather than
     * throwing, so callers must not blindly overwrite existing cached history with an empty
     * result (see [com.kachat.app.viewmodels.PortfolioViewModel]'s fetchPriceHistory).
     *
     * CoinGecko's keyless tier throttles bursts hard (429 for a stretch after just a few rapid
     * calls) — a launch plus a couple of chart-range taps was enough to make every subsequent
     * range fetch come back empty, leaving the chart stuck on whatever range loaded first. A
     * 429/5xx here gets one in-place retry when Retry-After fits [MAX_INLINE_RETRY_SECONDS];
     * a longer window is recorded in [throttledUntilMillis] for a deferred retry instead.
     */
    suspend fun getPriceHistory(days: Int = 30, currency: String = "usd"): List<Pair<Long, Double>> {
        repeat(2) { attempt ->
            try {
                return coinGeckoApi.getMarketChart(vsCurrency = currency, days = days).prices.mapNotNull { point ->
                    if (point.size < 2) null else point[0].toLong() to point[1]
                }
            } catch (e: HttpException) {
                if (attempt > 0 || (e.code() != 429 && e.code() < 500)) return emptyList()
                val retryAfterSeconds = e.response()?.headers()?.get("Retry-After")?.toDoubleOrNull() ?: 2.0
                if (retryAfterSeconds > MAX_INLINE_RETRY_SECONDS) {
                    // The real window (observed: 59s) doesn't fit an in-place park — capping the
                    // delay at 10s just guaranteed the retry landed inside the window and failed
                    // too. Record the window for the ViewModel's deferred retry and bail now.
                    recordThrottleWindow(retryAfterSeconds)
                    return emptyList()
                }
                delay((retryAfterSeconds * 1000).toLong())
            } catch (e: Exception) {
                // Includes coroutine cancellation mid-request — bail out quietly, same
                // "degrade gracefully" contract as getCurrentPriceUsd.
                return emptyList()
            }
        }
        return emptyList()
    }

    // -------------------------------------------------------------------------
    // Persistent price-history cache (10-minute TTL)
    // -------------------------------------------------------------------------
    //
    // CoinGecko's keyless tier throttles bursts aggressively — a cold launch already costs a few
    // calls, so cycling chart ranges could exhaust the limit and leave every new range's fetch
    // returning empty, with the chart stuck showing the first range's ~1-day curve no matter
    // which range was selected. Persisting each (currency, days) history for 10 minutes makes
    // range cycling free after the first fetch (and across relaunches), and on a failed fetch the
    // stale copy for the *requested* range still beats showing the wrong range. Storage is a
    // SharedPreferences JSON payload per (currency, days) key, same prefs+Gson pattern as
    // GiftManager; the freshness policy (TTL check, stale fallback) lives with the caller —
    // see PortfolioViewModel's fetchPriceHistory.

    /** Gson payload persisted per (currency, days) — [t] = timestampMillis, [p] = price, kept short since a 365-day history is thousands of points. */
    private data class StoredPricePoint(val t: Long, val p: Double)
    private data class StoredPriceHistory(val fetchedAt: Long, val points: List<StoredPricePoint>)

    /** A previously fetched history plus when it was fetched, so callers can apply their own freshness policy (see [PRICE_HISTORY_CACHE_TTL_MILLIS]). */
    data class PersistedPriceHistory(val fetchedAtMillis: Long, val points: List<Pair<Long, Double>>)

    private val priceHistoryPrefs = context.getSharedPreferences(PRICE_HISTORY_PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    private fun priceHistoryCacheKey(days: Int, currency: String) = "kachat_price_history_${currency}_$days"

    /** Null when nothing was ever persisted for this (currency, days) — or the payload is corrupt/empty, which callers treat the same way. */
    fun readPersistedPriceHistory(days: Int, currency: String): PersistedPriceHistory? {
        val json = priceHistoryPrefs.getString(priceHistoryCacheKey(days, currency), null) ?: return null
        return try {
            val stored = gson.fromJson(json, StoredPriceHistory::class.java) ?: return null
            if (stored.points.isNullOrEmpty()) return null
            PersistedPriceHistory(stored.fetchedAt, stored.points.map { it.t to it.p })
        } catch (e: Exception) {
            null
        }
    }

    fun persistPriceHistory(points: List<Pair<Long, Double>>, days: Int, currency: String) {
        if (points.isEmpty()) return
        val stored = StoredPriceHistory(System.currentTimeMillis(), points.map { StoredPricePoint(it.first, it.second) })
        priceHistoryPrefs.edit().putString(priceHistoryCacheKey(days, currency), gson.toJson(stored)).apply()
    }

    // -------------------------------------------------------------------------
    // Persistent current-price cache
    // -------------------------------------------------------------------------
    //
    // The current price previously had no cache and no retry at all — one throttled launch burst
    // and the portfolio showed a bare dash until the user pulled to refresh (firing another
    // burst, usually still inside the same throttle window). Persisting every successful fetch
    // per currency lets the UI paint the last-known price instantly on open; freshness policy
    // lives with the caller (see PortfolioViewModel.refreshPrice), same split as the history
    // cache above.

    /** Gson payload persisted per currency — fetchedAt lets callers decide whether a network refresh is even needed. */
    private data class StoredCurrentPrice(val fetchedAt: Long, val price: Double, val change: Double?)

    /** The last successfully fetched price plus when it was fetched, so callers can apply their own freshness policy (see [CURRENT_PRICE_FRESH_MILLIS]). */
    data class PersistedPrice(val fetchedAtMillis: Long, val price: Double, val change24hPercent: Double?)

    private fun currentPriceCacheKey(currency: String) = "kachat_current_price_$currency"

    /** Null when no price was ever successfully fetched for this currency (or the payload is corrupt). */
    fun readPersistedPrice(currency: String): PersistedPrice? {
        val json = priceHistoryPrefs.getString(currentPriceCacheKey(currency), null) ?: return null
        return try {
            val stored = gson.fromJson(json, StoredCurrentPrice::class.java) ?: return null
            PersistedPrice(stored.fetchedAt, stored.price, stored.change)
        } catch (e: Exception) {
            null
        }
    }

    private fun persistCurrentPrice(price: PriceWithChange, currency: String) {
        val stored = StoredCurrentPrice(System.currentTimeMillis(), price.price, price.change24hPercent)
        priceHistoryPrefs.edit().putString(currentPriceCacheKey(currency), gson.toJson(stored)).apply()
    }

    private val historyDateFormat = java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    // -------------------------------------------------------------------------
    // Persistent historical-price cache (per currency + UTC day, no TTL)
    // -------------------------------------------------------------------------
    //
    // A finished UTC day's snapshot price never changes, so unlike the current-price/history
    // caches above this one has no TTL — once a day is priced it's priced forever, and
    // re-importing an address (or importing a second address active on the same days) costs
    // zero CoinGecko calls for already-known days. Today's still-moving price is deliberately
    // never persisted.

    private fun historicalPriceCacheKey(dayStartMillis: Long, currency: String) =
        "kachat_hist_price_${currency}_$dayStartMillis"

    /** Null when this (currency, day) was never successfully priced. */
    fun readPersistedHistoricalPrice(dayStartMillis: Long, currency: String): Double? =
        priceHistoryPrefs.getString(historicalPriceCacheKey(dayStartMillis, currency), null)?.toDoubleOrNull()

    private fun persistHistoricalPrice(dayStartMillis: Long, currency: String, price: Double) {
        // Only a *finished* UTC day's snapshot is immutable — never freeze today's price.
        if (dayStartMillis >= utcDayStartMillis(System.currentTimeMillis())) return
        priceHistoryPrefs.edit().putString(historicalPriceCacheKey(dayStartMillis, currency), price.toString()).apply()
    }

    /**
     * Daily-granularity snapshot price CoinGecko recorded for [dayStartMillis] (pass a UTC
     * day-start timestamp) — used by "Add Kaspa Address" to price auto-imported transactions.
     * Null on any failure or when CoinGecko simply has no data for that date, same "degrade
     * gracefully" contract as [getCurrentPriceUsd]/[getPriceHistory].
     *
     * Served from the persistent per-day cache first (see [readPersistedHistoricalPrice]).
     * A 429/5xx gets the same Retry-After treatment as [getCurrentPriceUsd]: one in-place
     * retry when the window fits [MAX_INLINE_RETRY_SECONDS], otherwise the window is recorded
     * in [throttledUntilMillis] so callers (the import backfill) can wait it out instead of
     * burning attempts inside it — the raw one-shot fetch this used to be was the main reason
     * address imports came back mostly unpriced.
     */
    suspend fun getHistoricalPrice(dayStartMillis: Long, currency: String = "usd"): Double? {
        readPersistedHistoricalPrice(dayStartMillis, currency)?.let { return it }
        repeat(2) { attempt ->
            try {
                val dateString = synchronized(historyDateFormat) { historyDateFormat.format(java.util.Date(dayStartMillis)) }
                val price = coinGeckoApi.getHistory(date = dateString).marketData?.currentPrice?.get(currency)
                    ?: return null // CoinGecko has no data for that date — retrying won't create any.
                persistHistoricalPrice(dayStartMillis, currency, price)
                throttledUntilMillis = null
                return price
            } catch (e: HttpException) {
                if (attempt > 0 || (e.code() != 429 && e.code() < 500)) return null
                val retryAfterSeconds = e.response()?.headers()?.get("Retry-After")?.toDoubleOrNull() ?: 2.0
                if (retryAfterSeconds > MAX_INLINE_RETRY_SECONDS) {
                    recordThrottleWindow(retryAfterSeconds)
                    return null
                }
                delay((retryAfterSeconds * 1000).toLong())
            } catch (e: Exception) {
                // Includes coroutine cancellation mid-request — bail out quietly.
                return null
            }
        }
        return null
    }

    private fun utcDayStartMillis(timestampMillis: Long): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = timestampMillis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun isValidKaspaAddress(address: String): Boolean {
        return try {
            com.kachat.app.util.KaspaAddress.getScriptPublicKey(address).isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /** Runs price backfill after an address import — outlives the import dialog's coroutine on
     *  purpose, so closing the progress dialog (or leaving the screen) never kills the backfill. */
    private val priceBackfillScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Fetches [address]'s on-chain transaction history and adds new buy/sell rows into the
     * active portfolio — every received transaction becomes a buy, every sent transaction
     * becomes a sell, priced at that day's historical KAS price. Deliberately no attempt to
     * filter out ordinary KaChat payments/protocol overhead (see PortfolioTransactionEntity's
     * doc comment on why manual entry was originally the only path) — an explicit, simpler
     * alternative the user opted into. Re-entering the same address later only adds transactions
     * not already present for it (deduped by on-chain tx id).
     *
     * Never all-or-nothing: every row is inserted IMMEDIATELY — priced from the persistent
     * per-day cache when the day is already known, otherwise with fiatValue 0.0 and
     * [PRICE_UNAVAILABLE_NOTE] — so the ledger (and the portfolio's KAS balance) appears the
     * moment the on-chain history lands, and the remaining days' prices backfill in the
     * background via [backfillHistoricalPrices] as each CoinGecko fetch succeeds. A day whose
     * price never arrives (persistent offline, CoinGecko has no data) simply keeps its
     * flagging note for the user to price manually; nothing is dropped or rolled back.
     */
    suspend fun importAddress(address: String, currency: String = "usd", onProgress: (String) -> Unit): AddressImportResult {
        val trimmed = address.trim()
        if (!isValidKaspaAddress(trimmed)) {
            throw PortfolioAddressImportError.InvalidAddress
        }

        val walletAddress = walletManager.getAddress()
        val portfolioId = currentPortfolioId() ?: throw PortfolioAddressImportError.NoTransactions

        val existingTxIds = database.portfolioDao().getAllTransactionsForWallet(walletAddress).first()
            .filter { it.sourceAddress == trimmed }
            .mapNotNull { it.sourceTxId }
            .toSet()

        onProgress("Fetching transactions…")
        val history = coldStorageAddressDiscovery.getFullTransactionHistoryPaginated(trimmed)

        data class Candidate(val txId: String, val sent: Boolean, val amountSompi: Long, val dayStartMillis: Long, val timestampMillis: Long)

        val candidates = history.mapNotNull { tx ->
            val blockTime = tx.blockTimeMillis ?: return@mapNotNull null
            if (existingTxIds.contains(tx.txId)) return@mapNotNull null
            Candidate(tx.txId, tx.sent, tx.amountSompi, utcDayStartMillis(blockTime), blockTime)
        }
        if (candidates.isEmpty()) {
            throw PortfolioAddressImportError.NoTransactions
        }

        onProgress("Saving ${candidates.size} transaction${if (candidates.size == 1) "" else "s"}…")
        var importedCount = 0
        var pendingPriceCount = 0
        val pendingIdsByDay = mutableMapOf<Long, MutableList<String>>()
        for (candidate in candidates) {
            // Days already in the persistent cache price instantly and for free — only genuinely
            // unknown days go to the background backfill.
            val cachedPrice = readPersistedHistoricalPrice(candidate.dayStartMillis, currency)
            val amountKas = candidate.amountSompi / 100_000_000.0
            val id = UUID.randomUUID().toString()
            database.portfolioDao().insert(
                PortfolioTransactionEntity(
                    id = id,
                    walletAddress = walletAddress,
                    portfolioId = portfolioId,
                    type = if (candidate.sent) "sell" else "buy",
                    amountSompi = candidate.amountSompi,
                    fiatValue = amountKas * (cachedPrice ?: 0.0),
                    timestampMillis = candidate.timestampMillis,
                    notes = if (cachedPrice == null) PRICE_UNAVAILABLE_NOTE else null,
                    sourceAddress = trimmed,
                    sourceTxId = candidate.txId
                )
            )
            importedCount++
            if (cachedPrice == null) {
                pendingPriceCount++
                pendingIdsByDay.getOrPut(candidate.dayStartMillis) { mutableListOf() }.add(id)
            }
        }

        if (pendingIdsByDay.isNotEmpty()) {
            priceBackfillScope.launch { backfillHistoricalPrices(walletAddress, pendingIdsByDay, currency) }
        }

        return AddressImportResult(importedCount, pendingPriceCount)
    }

    /**
     * Prices imported-but-unpriced rows day by day, updating each day's rows as its price lands.
     * One historical-price fetch per unique day (CoinGecko's history endpoint is daily-granularity
     * anyway), paced [PRICE_REQUEST_SPACING_MILLIS] apart to stay under the free tier's limit.
     * A recorded Retry-After window ([throttledUntilMillis]) is waited out before each attempt —
     * the old inline loop's blind 1.2s retry always landed inside the ~59s window and failed —
     * and plain failures back off exponentially per day, [MAX_BACKFILL_ATTEMPTS_PER_DAY] tries
     * each. Rows the user has manually priced meanwhile (note no longer [PRICE_UNAVAILABLE_NOTE])
     * are left alone.
     */
    private suspend fun backfillHistoricalPrices(
        walletAddress: String,
        pendingIdsByDay: Map<Long, List<String>>,
        currency: String
    ) {
        val days = pendingIdsByDay.keys.sorted()
        for ((index, day) in days.withIndex()) {
            var price: Double? = null
            var backoffMillis = 3_000L
            for (attempt in 0 until MAX_BACKFILL_ATTEMPTS_PER_DAY) {
                // Don't spend an attempt inside a known throttle window — wait it out instead.
                throttledUntilMillis?.let { until ->
                    val wait = until - System.currentTimeMillis()
                    if (wait > 0) delay(wait + 1_000L)
                }
                price = getHistoricalPrice(day, currency)
                if (price != null) break
                if (attempt < MAX_BACKFILL_ATTEMPTS_PER_DAY - 1 && throttledUntilMillis == null) {
                    delay(backoffMillis)
                    backoffMillis = minOf(backoffMillis * 2, 60_000L)
                }
            }
            if (price != null) {
                val dayPrice = price
                val idsForDay = pendingIdsByDay.getValue(day).toSet()
                val currentRows = database.portfolioDao().getAllTransactionsForWallet(walletAddress).first()
                for (row in currentRows) {
                    // Re-check the marker so a price the user already set by hand mid-backfill
                    // is never overwritten.
                    if (row.id in idsForDay && row.notes == PRICE_UNAVAILABLE_NOTE) {
                        val amountKas = row.amountSompi / 100_000_000.0
                        database.portfolioDao().insert(row.copy(fiatValue = amountKas * dayPrice, notes = null))
                    }
                }
            }
            if (index < days.size - 1) {
                delay(PRICE_REQUEST_SPACING_MILLIS)
            }
        }
    }

    // -------------------------------------------------------------------------
    // CSV (CoinMarketCap "Transaction History" format)
    // -------------------------------------------------------------------------
    //
    // Column order matches CoinMarketCap's portfolio Transaction History export exactly:
    // Date (UTC±H:MM),Token,Type,Price (USD),Amount,Total value (USD),Fee,Fee Currency,Notes
    // — so a file exported from CoinMarketCap imports here unmodified, and a file exported from
    // here imports back into CoinMarketCap unmodified. Mirrors iOS's PortfolioViewModel exactly.

    private val trackedToken = "KAS"

    /**
     * CoinMarketCap formats numeric columns with thousands-separator commas above 999 (e.g.
     * "10,597.25", "6,093,184.09"), which plain toDouble() rejects outright — parsing every such
     * row would otherwise silently fail and get skipped. Strips those before parsing.
     */
    private fun parseLenientDouble(raw: String): Double? =
        raw.trim().replace(",", "").toDoubleOrNull()

    private fun makeDateFormat(timeZone: TimeZone): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            isLenient = false
            this.timeZone = timeZone
        }

    /**
     * CoinMarketCap bakes the exporting user's local UTC offset into the date column's own
     * header name (e.g. "Date (UTC-4:00)") rather than into each row, so the offset has to be
     * parsed once from the header before any row's timestamp can be interpreted correctly. Falls
     * back to UTC if the header doesn't look like CoinMarketCap's (or is missing).
     */
    private fun parseHeaderUtcOffset(header: String): TimeZone {
        val utcTimeZone = TimeZone.getTimeZone("UTC")
        val utcIndex = header.indexOf("UTC", ignoreCase = true)
        if (utcIndex == -1) return utcTimeZone
        val afterUtc = utcIndex + 3
        val closeParen = header.indexOf(')', afterUtc)
        if (closeParen == -1) return utcTimeZone
        val offsetString = header.substring(afterUtc, closeParen).trim()
        val parts = offsetString.split(":")
        if (parts.size != 2) return utcTimeZone
        val hours = parts[0].toIntOrNull() ?: return utcTimeZone
        val minutes = parts[1].toIntOrNull() ?: return utcTimeZone
        val sign = if (offsetString.startsWith("-")) -1 else 1
        val offsetMillis = sign * (abs(hours) * 3600 + minutes * 60) * 1000
        return SimpleTimeZone(offsetMillis, "CMC-IMPORT")
    }

    /**
     * Builds a CoinMarketCap-compatible CSV in app-private cache and returns a content:// URI
     * ready for a share sheet. Rows are exported in ascending timestamp order, always in UTC
     * (spelled out in the header) so re-importing never depends on the exporting device's local
     * timezone. Fee / Fee Currency are written as zero/USD — the ledger doesn't keep fee as a
     * separate line item; any fee captured at import time is already folded into Total value
     * (USD).
     */
    fun exportCsv(transactions: List<PortfolioTransactionEntity>): Uri {
        val exportDir = File(context.cacheDir, "portfolio_exports").apply { mkdirs() }
        val fileTimestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(":", "-")
        val csvFile = File(exportDir, "kachat-portfolio-$fileTimestamp.csv")

        val dateFormat = makeDateFormat(TimeZone.getTimeZone("UTC"))
        val csv = buildString {
            append("Date (UTC+0:00),Token,Type,Price (USD),Amount,Total value (USD),Fee,Fee Currency,Notes\n")
            transactions.sortedBy { it.timestampMillis }.forEach { tx ->
                val kas = tx.amountSompi / 100_000_000.0
                val price = if (kas != 0.0) tx.fiatValue / kas else 0.0
                val date = dateFormat.format(Date(tx.timestampMillis))
                val notes = (tx.notes ?: "").replace("\"", "\"\"")
                append("\"$date\",\"$trackedToken\",\"${tx.type}\",\"$price\",\"$kas\",\"${tx.fiatValue}\",\"0.00\",\"USD\",\"$notes\"\n")
            }
        }
        csvFile.writeText(csv)

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", csvFile)
    }

    /**
     * Parses a CoinMarketCap "Transaction History" CSV — same column order [exportCsv] writes,
     * so real CoinMarketCap exports import here directly too. Only rows for the tracked token
     * (KAS) are imported; other tokens in a mixed-portfolio CMC export are silently skipped, as
     * are malformed rows and unsupported Type values (only buy/sell are tracked). Fee is folded
     * into Total value (USD) when the fee is itself denominated in USD — added for buys,
     * subtracted for sells — since the ledger doesn't track fee as a separate line item. A row
     * whose timestamp exactly matches an existing transaction replaces it in place (same id, new
     * data) rather than adding a duplicate — re-importing a corrected or re-exported CSV updates
     * the ledger instead of piling up copies. Returns the number of rows imported or replaced.
     */
    /**
     * Imports into whichever portfolio is currently active. Timestamp-match-and-replace only
     * considers that portfolio's own rows (not the whole wallet's, which may include other
     * portfolios' transactions) — otherwise a row could get silently reassigned or overwritten
     * across portfolios just because two unrelated ledgers happen to share a timestamp.
     */
    suspend fun importCsv(uri: Uri): Int {
        val portfolioId = currentPortfolioId() ?: return 0
        val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return 0
        val lines = content.split("\r\n", "\n", "\r").toMutableList()
        if (lines.isEmpty()) return 0
        val header = lines.removeAt(0)
        val dateFormat = makeDateFormat(parseHeaderUtcOffset(header))

        val walletAddress = walletManager.getAddress()
        val existing = database.portfolioDao().getTransactions(walletAddress, portfolioId).first()
        val idByTimestamp = existing.associate { it.timestampMillis to it.id }.toMutableMap()

        var imported = 0
        for (line in lines) {
            if (line.isBlank()) continue
            val fields = parseCsvLine(line)
            if (fields.size < 6) continue

            val token = fields[1].trim()
            if (!token.equals(trackedToken, ignoreCase = true)) continue

            val type = fields[2].trim().lowercase()
            if (type != "buy" && type != "sell") continue
            val timestampMillis = try { dateFormat.parse(fields[0].trim())?.time } catch (e: Exception) { null } ?: continue
            val kas = parseLenientDouble(fields[4]) ?: continue
            val totalValue = parseLenientDouble(fields[5]) ?: continue

            var fiatValue = totalValue
            if (fields.size > 7) {
                val feeCurrency = fields[7].trim()
                if (feeCurrency.equals("USD", ignoreCase = true)) {
                    val fee = parseLenientDouble(fields[6])
                    if (fee != null) {
                        fiatValue = if (type == "buy") fiatValue + fee else maxOf(fiatValue - fee, 0.0)
                    }
                }
            }

            val notes = if (fields.size > 8 && fields[8].isNotEmpty()) fields[8] else null
            val amountSompi = (kas * 100_000_000).roundToLong()

            val existingId = idByTimestamp[timestampMillis]
            if (existingId != null) {
                updateTransaction(existingId, type, amountSompi, fiatValue, timestampMillis, notes)
            } else {
                val newId = UUID.randomUUID().toString()
                database.portfolioDao().insert(
                    PortfolioTransactionEntity(
                        id = newId,
                        walletAddress = walletAddress,
                        portfolioId = portfolioId,
                        type = type,
                        amountSompi = amountSompi,
                        fiatValue = fiatValue,
                        timestampMillis = timestampMillis,
                        notes = notes
                    )
                )
                idByTimestamp[timestampMillis] = newId
            }
            imported++
        }
        return imported
    }

    /** Splits on commas outside double quotes, and unescapes "" back to " within a quoted field. */
    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    fields.add(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        fields.add(current.toString())
        return fields
    }

    companion object {
        private const val PRICE_HISTORY_PREFS_NAME = "kachat_price_history_cache"

        /** How long a persisted (currency, days) history counts as fresh — long enough to make range cycling and relaunches free, short enough that the chart never looks meaningfully out of date. */
        const val PRICE_HISTORY_CACHE_TTL_MILLIS = 10 * 60 * 1000L

        /** How long a persisted current price counts as fresh enough to skip the network entirely
         *  on a non-forced refresh — CoinGecko itself caches this endpoint 30-60s server-side
         *  (Cache-Control max-age=30, s-maxage=60), so refetching inside a minute buys nothing
         *  and burns keyless-tier rate-limit budget. Every screen that instantiates its own
         *  PortfolioViewModel (Send, Cold Storage, Portfolio tab) fires a refresh on init, so
         *  this window is what keeps normal navigation from tripping the throttle. */
        const val CURRENT_PRICE_FRESH_MILLIS = 60 * 1000L

        /** The longest Retry-After worth honoring with an in-place delay inside a fetch call —
         *  anything longer (CoinGecko's real throttle window is ~59s) is recorded in
         *  [throttledUntilMillis] for a deferred, non-blocking retry instead. */
        const val MAX_INLINE_RETRY_SECONDS = 10.0

        /** Spacing between sequential historical-price fetches during an import's backfill. */
        const val PRICE_REQUEST_SPACING_MILLIS = 1_200L

        /** Attempts per day before the backfill moves on and leaves that day's rows flagged for
         *  manual pricing — each attempt already waits out any known throttle window first. */
        const val MAX_BACKFILL_ATTEMPTS_PER_DAY = 4
    }
}

/** [pendingPriceCount] rows were imported with fiatValue 0.0 and a flagging note — their days'
 *  prices are being backfilled in the background and fill in as each fetch lands; only rows
 *  whose price never arrives keep the note for manual pricing. */
data class AddressImportResult(val importedCount: Int, val pendingPriceCount: Int)

/** Marks a [PortfolioTransactionEntity.notes] value as "auto-imported but couldn't be priced" — checked by [com.kachat.app.ui.screens.PortfolioScreen]'s transaction row to show a warning icon flagging rows that still need the user to fill in a price. */
const val PRICE_UNAVAILABLE_NOTE = "Price unavailable — set manually"

sealed class PortfolioAddressImportError(message: String) : Exception(message) {
    object InvalidAddress : PortfolioAddressImportError("That doesn't look like a valid Kaspa address.")
    object NoTransactions : PortfolioAddressImportError("No new transactions found for this address.")
}
