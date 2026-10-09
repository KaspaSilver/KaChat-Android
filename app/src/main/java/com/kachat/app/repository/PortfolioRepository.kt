package com.kachat.app.repository

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.kachat.app.models.PortfolioFeeRecord
import com.kachat.app.models.PortfolioTransactionEntity
import com.kachat.app.services.CoinGeckoApi
import com.kachat.app.services.ColdStorageAddressDiscovery
import com.kachat.app.services.PortfolioManager
import com.kachat.app.services.WalletManager
import com.kachat.app.services.database.KaChatDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
import kotlin.math.ceil
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
    private val coldStorageAddressDiscovery: ColdStorageAddressDiscovery,
    // Every ledger-row and fee write goes through it: stamped for Nextcloud sync, deletions
    // recorded, the backup marked dirty (iOS PortfolioViewModel.persist / persistFees).
    private val ledgerStore: com.kachat.app.services.PortfolioLedgerStore,
    // The app currency, for a price backfill no screen started (after a restore).
    private val settings: AppSettingsRepository,
) {
    /**
     * Whichever portfolio is currently active within whichever wallet is currently active —
     * re-emits automatically on either switching, same pattern as BroadcastRepository/
     * GroupRepository. Pre-wallet-scoping rows (walletAddress="") are claimed first, then
     * pre-portfolio-scoping rows (portfolioId="") are claimed for the wallet's default portfolio
     * — a very old install upgrading straight from before either migration needs both claims in
     * that order.
     */
    /** Every portfolio's rows for the active wallet - what "already in another portfolio"
     *  checks look across (iOS checks its whole transactions list, not the active portfolio). */
    /** The wallet's own chatting address - "Add Chatting Address" imports it (iOS 61eff0f). */
    fun chattingAddress(): String? = walletManager.activeAddressFlow.value?.takeIf { it.isNotBlank() }

    fun getAllTransactionsForActiveWallet(): Flow<List<PortfolioTransactionEntity>> =
        walletManager.activeAddressFlow.flatMapLatest { address ->
            if (address == null) flowOf(emptyList())
            else database.portfolioDao().getAllTransactionsForWallet(address)
        }

    // -------------------------------------------------------------------------
    // Fees (iOS 61eff0f): network fees imported addresses paid, stored per wallet beside the
    // ledger - one JSON list per wallet, kept by PortfolioLedgerStore (which also carries them
    // in the Nextcloud backup).
    // -------------------------------------------------------------------------

    fun loadFees(walletAddress: String): List<PortfolioFeeRecord> = ledgerStore.loadFees(walletAddress)

    private fun updateFees(walletAddress: String, transform: (List<PortfolioFeeRecord>) -> List<PortfolioFeeRecord>) =
        ledgerStore.updateFees(walletAddress, transform)

    /** Every portfolio's fees for the active wallet; re-emits on a wallet switch or a save. */
    fun getFeesForActiveWallet(): Flow<List<PortfolioFeeRecord>> =
        walletManager.activeAddressFlow.flatMapLatest { address ->
            ledgerStore.feeVersion.map { if (address == null) emptyList() else loadFees(address) }
        }

    /** A deleted portfolio's fees go with it (iOS forgetPortfolio). */
    fun forgetPortfolioFees(portfolioId: String) {
        updateFees(walletManager.getAddress()) { fees -> fees.filter { it.portfolioId != portfolioId } }
    }

    /**
     * Moves one row to another of this wallet's portfolios - the same record, keeping its id and
     * source, so a later "Add to Portfolio" of the same transaction still recognises it wherever
     * it now lives (iOS b438f2d).
     */
    suspend fun moveTransaction(id: String, toPortfolioId: String) {
        val row = database.portfolioDao().getAllTransactionsForWallet(walletManager.getAddress()).first()
            .firstOrNull { it.id == id } ?: return
        if (row.portfolioId == toPortfolioId) return
        ledgerStore.saveTransaction(row.copy(portfolioId = toPortfolioId))
    }

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
        ledgerStore.saveTransaction(
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
        val existingRow = database.portfolioDao().getAllTransactionsForWallet(walletManager.getAddress()).first()
            .firstOrNull { it.id == id }
        val existingPortfolioId = existingRow?.portfolioId
            ?: currentPortfolioId() ?: return
        ledgerStore.saveTransaction(
            PortfolioTransactionEntity(
                id = id,
                walletAddress = walletManager.getAddress(),
                portfolioId = existingPortfolioId,
                type = type,
                amountSompi = amountSompi,
                fiatValue = fiatValue,
                timestampMillis = timestampMillis,
                notes = notes,
                // Kept through an edit: they are how a re-import of the address recognises this
                // row. Dropping them made the next import add the same transaction a second time -
                // as a sell again, undoing a sell the user had just marked a transfer (iOS 7423330).
                sourceAddress = existingRow?.sourceAddress,
                sourceTxId = existingRow?.sourceTxId
            )
        )
    }

    suspend fun deleteTransaction(id: String) = ledgerStore.deleteTransactions(walletManager.getAddress(), listOf(id))

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
    /**
     * Market cap and market-cap rank for KAS. Null on any failure - the caller keeps its last
     * good values rather than blanking a rank because one request timed out.
     */
    suspend fun getMarketStats(currency: String = "usd"): Pair<Double, Int?>? = try {
        val row = coinGeckoApi.getMarkets(vsCurrency = currency).firstOrNull()
        row?.marketCap?.let { it to row.marketCapRank }
    } catch (e: Exception) {
        null
    }

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
    /**
     * All-time history. CoinGecko's keyless tier refuses anything past 365 days (error 10012),
     * so [days] = [ALL_TIME_DAYS] draws the older part from Gate.io's daily KAS_USDT closes -
     * public, no key, trading there since 2023-03-21 - with CoinGecko's own last 365 days sitting
     * on top unchanged. Gate quotes USDT: the older points are scaled into the chosen currency by
     * the ratio at the seam (CoinGecko's first point over Gate's close for that day), exact for
     * dollars and a constant-rate approximation for anything else. Daily granularity throughout
     * (iOS be477d1).
     */
    private suspend fun getAllTimeHistory(currency: String): List<Pair<Long, Double>> {
        val recent = getPriceHistory(365, currency)
        val gate = fetchGateDailyCloses()
        if (gate.isEmpty()) return recent
        val firstRecent = recent.firstOrNull() ?: return if (currency == "usd") gate else emptyList()
        val anchor = gate.lastOrNull { it.first <= firstRecent.first } ?: gate.last()
        val ratio = if (anchor.second > 0) firstRecent.second / anchor.second else 1.0
        val older = gate.filter { it.first < firstRecent.first }.map { it.first to it.second * ratio }
        return older + recent
    }

    /** Gate.io daily closes for KAS_USDT, oldest first, paged backwards 1000 days at a time until
     *  the listing. Row shape: [time, quote volume, close, high, low, open, ...]. */
    private suspend fun fetchGateDailyCloses(): List<Pair<Long, Double>> = withContext(Dispatchers.IO) {
        val closes = sortedMapOf<Long, Double>()
        var to = System.currentTimeMillis() / 1000
        repeat(6) {
            val url = "https://api.gateio.ws/api/v4/spot/candlesticks" +
                "?currency_pair=KAS_USDT&interval=1d&limit=1000&to=$to"
            val body = try {
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.useCaches = false
                try {
                    if (connection.responseCode != 200) return@repeat
                    connection.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                return@withContext closes.map { it.key * 1000 to it.value }
            }
            val rows = try {
                com.google.gson.Gson().fromJson(body, Array<Array<String>>::class.java)
            } catch (e: Exception) {
                null
            } ?: return@withContext closes.map { it.key * 1000 to it.value }
            if (rows.isEmpty()) return@withContext closes.map { it.key * 1000 to it.value }
            var earliest = to
            for (row in rows) {
                if (row.size < 3) continue
                val time = row[0].toLongOrNull() ?: continue
                val close = row[2].toDoubleOrNull() ?: continue
                closes[time] = close
                earliest = minOf(earliest, time)
            }
            if (rows.size < 1000 || earliest >= to) return@withContext closes.map { it.key * 1000 to it.value }
            to = earliest - 86_400
        }
        closes.map { it.key * 1000 to it.value }
    }

    suspend fun getPriceHistory(days: Int = 30, currency: String = "usd"): List<Pair<Long, Double>> {
        // All-time is not a CoinGecko range - it is Gate.io's history with CoinGecko's year on top.
        if (days == ALL_TIME_DAYS) return getAllTimeHistory(currency)
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
    // One JSON file per (currency, days) series, NOT a SharedPreferences entry. Seven ranges
    // across the app currency and up to four comparison pairs, at up to 6000 points each, all
    // used to live in one prefs file - and SharedPreferences loads the whole file on first
    // access and rewrites the whole file on every apply(), so reading one day of prices meant
    // parsing every series ever cached, and writing one meant serialising them all again
    // (iOS 584e6eb). A file per series reads and writes only what was asked for.
    //
    // Reads stay synchronous because the callers are not suspend functions, so the first read of
    // a series still touches disk on the calling thread - but it is one small file rather than
    // the whole multi-megabyte blob, and every read after it is served from memory. Writes go
    // out on an IO scope and land atomically via a temp file and a rename, so a kill mid-write
    // leaves the previous series intact rather than a half-written one.
    //
    // The freshness policy (TTL check, stale fallback) lives with the caller - see
    // PortfolioViewModel's fetchPriceHistory.

    /** Gson payload persisted per (currency, days) — [t] = timestampMillis, [p] = price, kept short since a 365-day history is thousands of points. */
    private data class StoredPricePoint(val t: Long, val p: Double)
    private data class StoredPriceHistory(val fetchedAt: Long, val points: List<StoredPricePoint>)

    /** A previously fetched history plus when it was fetched, so callers can apply their own freshness policy (see [PRICE_HISTORY_CACHE_TTL_MILLIS]). */
    data class PersistedPriceHistory(val fetchedAtMillis: Long, val points: List<Pair<Long, Double>>)

    private val priceHistoryPrefs = context.getSharedPreferences(PRICE_HISTORY_PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    /** Under noBackupFilesDir: the counterpart of iOS putting these in Application Support with
     *  the backup flag cleared. Nothing here is worth restoring - it is all refetchable. */
    private val priceHistoryDir: File by lazy {
        File(context.noBackupFilesDir, "price_history").apply { mkdirs() }
    }

    /** Parsed series held in memory, so only the first read of each touches disk. */
    private val priceHistoryMemory = java.util.concurrent.ConcurrentHashMap<String, PersistedPriceHistory>()

    private val priceHistoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        priceHistoryScope.launch { runCatching { migrateLegacyPriceHistoryEntries() } }
    }

    private fun priceHistoryCacheKey(days: Int, currency: String) = "kachat_price_history_${currency}_$days"

    private fun priceHistoryFile(days: Int, currency: String) =
        File(priceHistoryDir, priceHistoryCacheKey(days, currency) + ".json")

    /** Null when nothing was ever persisted for this (currency, days) — or the payload is corrupt/empty, which callers treat the same way. */
    fun readPersistedPriceHistory(days: Int, currency: String): PersistedPriceHistory? {
        val key = priceHistoryCacheKey(days, currency)
        priceHistoryMemory[key]?.let { return it }
        val file = priceHistoryFile(days, currency)
        if (!file.exists()) return null
        return try {
            val stored = gson.fromJson(file.readText(), StoredPriceHistory::class.java) ?: return null
            if (stored.points.isNullOrEmpty()) return null
            PersistedPriceHistory(stored.fetchedAt, stored.points.map { it.t to it.p })
                .also { priceHistoryMemory[key] = it }
        } catch (e: Exception) {
            // A corrupt series is not worth keeping around to fail again on the next launch.
            runCatching { file.delete() }
            null
        }
    }

    fun persistPriceHistory(points: List<Pair<Long, Double>>, days: Int, currency: String) {
        if (points.isEmpty()) return
        val stored = StoredPriceHistory(System.currentTimeMillis(), points.map { StoredPricePoint(it.first, it.second) })
        val key = priceHistoryCacheKey(days, currency)
        priceHistoryMemory[key] = PersistedPriceHistory(stored.fetchedAt, points)
        val json = gson.toJson(stored)
        priceHistoryScope.launch {
            runCatching {
                val target = priceHistoryFile(days, currency)
                val temp = File(target.parentFile, target.name + ".tmp")
                temp.writeText(json)
                if (!temp.renameTo(target)) {
                    target.delete()
                    temp.renameTo(target)
                }
            }
        }
    }

    /** The series used to live in [PRICE_HISTORY_PREFS_NAME] alongside the current-price and
     *  per-day caches, which stay there - they are a handful of short strings. Dropped once so an
     *  updating install stops carrying megabytes it will never read again. */
    private fun migrateLegacyPriceHistoryEntries() {
        if (priceHistoryPrefs.getBoolean(PREF_SERIES_MIGRATED_TO_FILES, false)) return
        val stale = priceHistoryPrefs.all.keys.filter { it.startsWith("kachat_price_history_") }
        priceHistoryPrefs.edit().apply {
            stale.forEach { remove(it) }
            putBoolean(PREF_SERIES_MIGRATED_TO_FILES, true)
        }.apply()
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

    /** [persistHistoricalPrice] for a whole fetched range in one write, today skipped. */
    private fun persistHistoricalPrices(pricesByDay: Map<Long, Double>, currency: String) {
        val today = utcDayStartMillis(System.currentTimeMillis())
        val finished = pricesByDay.filterKeys { it < today }
        if (finished.isEmpty()) return
        val editor = priceHistoryPrefs.edit()
        for ((day, price) in finished) editor.putString(historicalPriceCacheKey(day, currency), price.toString())
        editor.apply()
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
     * in [throttledUntilMillis] and this returns null.
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

    /** Runs the background price backfill — outlives the import dialog's coroutine on purpose,
     *  so closing the progress dialog (or leaving the screen) never kills the backfill. */
    private val priceBackfillScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The one background price-backfill loop ([startPriceBackfillIfNeeded]) while it runs - iOS's
     *  single `priceBackfillTask`. Cancelled on a wallet switch so a pass never writes into the
     *  wrong ledger. */
    private var priceBackfillJob: Job? = null

    /** The wallet [priceBackfillJob] is pricing. */
    private var priceBackfillWallet: String? = null

    /**
     * Prices [walletAddress]'s rows still waiting for a price (either marker, [isPricePending])
     * and its unpriced fees, in the app currency (iOS `PortfolioViewModel.startPriceBackfillIfNeeded`).
     * Runs a pass now and repeats it after 30 s, 2 min and 5 min, stopping as soon as nothing is
     * pending, the wallet is no longer the active one, or the loop is cancelled - each pass is
     * [runPriceBackfillPass]. Started after an address import, a CSV import, a wallet load
     * ([onWalletLoaded]) and a Nextcloud restore that changed this wallet's portfolios. One loop
     * at a time: starting it while it runs is a no-op, and the running loop picks up newly
     * pending rows on its next pass.
     */
    @Synchronized
    fun startPriceBackfillIfNeeded(walletAddress: String) {
        if (walletAddress.isEmpty() || priceBackfillJob?.isActive == true) return
        priceBackfillWallet = walletAddress
        priceBackfillJob = priceBackfillScope.launch {
            for (delayMillis in PRICE_BACKFILL_PASS_DELAYS_MILLIS) {
                if (delayMillis > 0) delay(delayMillis)
                if (!isActive || !isActiveWallet(walletAddress) || !hasPendingPriceRows(walletAddress)) break
                runPriceBackfillPass(walletAddress)
            }
        }
    }

    /** The wallet whose portfolio is loaded now (iOS `activeWalletAddress`). */
    private fun isActiveWallet(walletAddress: String): Boolean =
        walletManager.activeAddressFlow.value == walletAddress

    /** Any row waiting for a price, or any unpriced fee (iOS `hasPendingPriceRows`). */
    private suspend fun hasPendingPriceRows(walletAddress: String): Boolean =
        database.portfolioDao().getAllTransactionsForWalletOnce(walletAddress).any { isPricePending(it.notes) }
            || loadFees(walletAddress).any { it.fiatValue == null }

    /**
     * A wallet's portfolio loading - app start, account switch, logout ([WalletManager.activeAddressFlow]
     * fires on every wallet load; null with no wallet). A backfill still pricing another wallet's
     * rows stops first, then this wallet's pending rows and fees resume pricing (iOS
     * `PortfolioViewModel.setCurrentWallet`: cancel `priceBackfillTask`, load, then
     * `startPriceBackfillIfNeeded`).
     */
    @Synchronized
    private fun onWalletLoaded(walletAddress: String?) {
        if (priceBackfillWallet != walletAddress) {
            priceBackfillJob?.cancel()
            priceBackfillJob = null
            priceBackfillWallet = null
        }
        if (!walletAddress.isNullOrEmpty()) startPriceBackfillIfNeeded(walletAddress)
    }

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
     * background ([startPriceBackfillIfNeeded]). A day whose price never arrives (persistent
     * offline, CoinGecko has no data) simply keeps its flagging note for the user to price
     * manually; nothing is dropped or rolled back.
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
        val existingFeeTxIds = loadFees(walletAddress).filter { it.portfolioId == portfolioId }.map { it.txId }.toSet()

        onProgress("Fetching transactions…")
        val historyResult = coldStorageAddressDiscovery.getFullTransactionHistoryPaginated(trimmed)
        val history = historyResult.transactions

        data class Candidate(val txId: String, val sent: Boolean, val amountSompi: Long, val dayStartMillis: Long, val timestampMillis: Long)

        val candidates = history.mapNotNull { tx ->
            val blockTime = tx.blockTimeMillis ?: return@mapNotNull null
            if (existingTxIds.contains(tx.txId)) return@mapNotNull null
            Candidate(tx.txId, tx.sent, tx.amountSompi, utcDayStartMillis(blockTime), blockTime)
        }
        // Fees on their own terms: a message to yourself has no buy or sell in it but it still
        // paid a fee. Only a transaction this address sent (one of its coins is an input), and
        // only when every input's amount is known - the whole fee goes to this address, since
        // KaChat's sends spend one address's coins (iOS feeSompi(of:paidBy:)).
        val feeCandidates = history.mapNotNull { tx ->
            val blockTime = tx.blockTimeMillis ?: return@mapNotNull null
            if (!tx.sent || existingFeeTxIds.contains(tx.txId)) return@mapNotNull null
            val fee = tx.feeSompi?.takeIf { it > 0 } ?: return@mapNotNull null
            val cachedPrice = readPersistedHistoricalPrice(utcDayStartMillis(blockTime), currency)
            PortfolioFeeRecord(
                txId = tx.txId,
                portfolioId = portfolioId,
                sourceAddress = trimmed,
                amountSompi = fee,
                timestampMillis = blockTime,
                fiatValue = cachedPrice?.let { fee / 100_000_000.0 * it },
            )
        }
        if (candidates.isEmpty() && feeCandidates.isEmpty()) {
            // Nothing new because the fetch gave up is not "no new transactions" (iOS).
            throw if (historyResult.complete) PortfolioAddressImportError.NoTransactions
            else PortfolioAddressImportError.HistoryFetchFailed
        }
        if (feeCandidates.isNotEmpty()) {
            updateFees(walletAddress) { it + feeCandidates }
        }

        onProgress("Saving ${candidates.size} transaction${if (candidates.size == 1) "" else "s"}…")
        var importedCount = 0
        var pendingPriceCount = 0
        val importedRows = ArrayList<PortfolioTransactionEntity>(candidates.size)
        for (candidate in candidates) {
            // Days already in the persistent cache price instantly and for free — only genuinely
            // unknown days go to the background backfill.
            val cachedPrice = readPersistedHistoricalPrice(candidate.dayStartMillis, currency)
            val amountKas = candidate.amountSompi / 100_000_000.0
            val id = UUID.randomUUID().toString()
            importedRows.add(
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
            if (cachedPrice == null) pendingPriceCount++
        }
        ledgerStore.saveTransactions(importedRows)

        // Rows and fees the cache couldn't price land with the right balance and a "price
        // loading" note - the backfill fills their prices in behind, so the import never blocks
        // (or fails) on CoinGecko's rate limit.
        startPriceBackfillIfNeeded(walletAddress)

        return AddressImportResult(importedCount, pendingPriceCount, feeCandidates.size, historyComplete = historyResult.complete)
    }

    /**
     * One backfill pass (iOS `PortfolioViewModel.runPriceBackfillPass`): every row of the wallet
     * still waiting for a price, from an address import or a CSV re-import of one (only its date
     * is needed), and every unpriced fee. Their days go first through [resolveDailyPrices] (the
     * persistent day cache plus at most one range request); the days it can't cover take the
     * paced per-day fallback ([resolveDailyPriceSingle]), newest first and at most
     * [MAX_FALLBACK_DAYS_PER_PASS] of them so one pass stays bounded - the rest wait for the next
     * pass. The prices found are written at the end of the pass, unless the loop was cancelled or
     * the wallet is no longer the active one. A row the user priced by hand meanwhile (note no
     * longer pending) is left alone.
     */
    private suspend fun runPriceBackfillPass(walletAddress: String) {
        val currency = settings.currency.first()
        val pending = database.portfolioDao().getAllTransactionsForWalletOnce(walletAddress)
            .filter { isPricePending(it.notes) }
        val pendingFees = loadFees(walletAddress).filter { it.fiatValue == null }
        if (pending.isEmpty() && pendingFees.isEmpty()) return
        val days = (pending.map { utcDayStartMillis(it.timestampMillis) } +
            pendingFees.map { utcDayStartMillis(it.timestampMillis) }).toSet()

        val prices = resolveDailyPrices(days, currency).toMutableMap()
        val missing = days.sortedDescending().filter { prices[it] == null }
        for (day in missing.take(MAX_FALLBACK_DAYS_PER_PASS)) {
            if (!currentCoroutineContext().isActive || !isActiveWallet(walletAddress)) break
            resolveDailyPriceSingle(day, currency)?.let { prices[day] = it }
            delay(PRICE_REQUEST_SPACING_MILLIS)
        }

        if (prices.isEmpty() || !currentCoroutineContext().isActive || !isActiveWallet(walletAddress)) return
        // Re-read the rows so a price the user already set by hand mid-pass is never overwritten.
        // A priced row is an edit like any other (stamped, synced).
        ledgerStore.saveTransactions(
            database.portfolioDao().getAllTransactionsForWalletOnce(walletAddress).mapNotNull { row ->
                if (!isPricePending(row.notes)) return@mapNotNull null
                val price = prices[utcDayStartMillis(row.timestampMillis)] ?: return@mapNotNull null
                row.copy(fiatValue = row.amountSompi / 100_000_000.0 * price, notes = null)
            }
        )
        updateFees(walletAddress) { fees ->
            fees.map { fee ->
                val price = if (fee.fiatValue == null) prices[utcDayStartMillis(fee.timestampMillis)] else null
                if (price != null) fee.copy(fiatValue = fee.amountKas * price) else fee
            }
        }
    }

    /**
     * Historical prices for a set of UTC days (iOS `PortfolioAddressImporter.resolveDailyPrices`):
     * the persistent day cache first, then ONE market_chart range request ([getPriceHistory])
     * reaching back to the oldest uncached day, at most 365 days (the keyless tier's limit). The
     * last sample of each UTC day is that day's price; the whole fetched range joins the cache, so
     * later passes and imports price those days without a request. Days it can't cover (older
     * than a year, or the request failed) are left out for the per-day fallback.
     */
    private suspend fun resolveDailyPrices(days: Collection<Long>, currency: String): Map<Long, Double> {
        val unique = days.toSortedSet()
        if (unique.isEmpty()) return emptyMap()
        val resolved = mutableMapOf<Long, Double>()
        for (day in unique) readPersistedHistoricalPrice(day, currency)?.let { resolved[day] = it }
        val missing = unique.filter { it !in resolved }
        val oldestMissing = missing.firstOrNull() ?: return resolved

        val daysBack = maxOf(1, ceil((System.currentTimeMillis() - oldestMissing) / 86_400_000.0).toInt() + 1)
        val points = getPriceHistory(minOf(daysBack, 365), currency)
        if (points.isEmpty()) return resolved

        val byDay = HashMap<Long, Double>()
        for ((timestampMillis, price) in points) byDay[utcDayStartMillis(timestampMillis)] = price
        persistHistoricalPrices(byDay, currency)
        for (day in missing) byDay[day]?.let { resolved[day] = it }
        return resolved
    }

    /**
     * One day's price through the per-day history endpoint, for a day [resolveDailyPrices]
     * couldn't cover (iOS `PortfolioAddressImporter.resolveDailyPriceSingle`): cache first, then
     * [getHistoricalPrice] (with its own Retry-After retry), then one more try
     * [PRICE_REQUEST_SPACING_MILLIS] later if that failed. A price found joins the cache.
     */
    private suspend fun resolveDailyPriceSingle(dayStartMillis: Long, currency: String): Double? {
        getHistoricalPrice(dayStartMillis, currency)?.let { return it }
        delay(PRICE_REQUEST_SPACING_MILLIS)
        return getHistoricalPrice(dayStartMillis, currency)
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
        // "NaN"/"Infinity" parse as doubles; a CSV cell holding one is not a number (iOS 1f129d6).
        raw.trim().replace(",", "").toDoubleOrNull()?.takeIf { it.isFinite() }

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
     *
     * The file is named after the portfolio as named in the app ("Long Term 2026-10-08T18-37-50Z
     * .csv", [baseName] from [exportBaseName]), so it is recognizable in Files and Nextcloud
     * (iOS 87b2a0b). The time is ISO 8601 in whole seconds with ':' as '-', as iOS writes it.
     */
    fun exportCsv(transactions: List<PortfolioTransactionEntity>, baseName: String = DEFAULT_EXPORT_NAME): Uri {
        val exportDir = File(context.cacheDir, "portfolio_exports").apply { mkdirs() }
        val fileTimestamp = DateTimeFormatter.ISO_INSTANT
            .format(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)).replace(":", "-")
        val csvFile = File(exportDir, "$baseName $fileTimestamp.csv")

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
        // Whole records, not lines: a note can hold line breaks, which the export writes inside
        // its quotes. Splitting the file on every newline first cut such a row in two (iOS 98f5009).
        val records = parseCsvRecords(content).toMutableList()
        if (records.isEmpty()) return 0
        val header = records.removeAt(0).joinToString(",")
        val dateFormat = makeDateFormat(parseHeaderUtcOffset(header))

        val walletAddress = walletManager.getAddress()
        val existing = database.portfolioDao().getTransactions(walletAddress, portfolioId).first()
        val idByTimestamp = existing.associate { it.timestampMillis to it.id }.toMutableMap()

        var imported = 0
        for (fields in records) {
            if (fields.size < 6) continue

            val token = fields[1].trim()
            if (!token.equals(trackedToken, ignoreCase = true)) continue

            val typeRaw = fields[2].trim().lowercase()
            // CoinMarketCap writes "Transfer In" / "Transfer Out"; both are a transfer here.
            val type = when {
                typeRaw == "buy" || typeRaw == "sell" || typeRaw == "transfer" -> typeRaw
                typeRaw.startsWith("transfer") -> "transfer"
                else -> continue
            }
            val timestampMillis = try { dateFormat.parse(fields[0].trim())?.time } catch (e: Exception) { null } ?: continue
            val kas = parseLenientDouble(fields[4]) ?: continue
            val totalValue = parseLenientDouble(fields[5]) ?: continue

            var fiatValue = totalValue
            if (fields.size > 7) {
                val feeCurrency = fields[7].trim()
                if (feeCurrency.equals("USD", ignoreCase = true)) {
                    val fee = parseLenientDouble(fields[6])
                    if (fee != null) {
                        fiatValue = when (type) {
                            "buy" -> fiatValue + fee
                            "sell" -> maxOf(fiatValue - fee, 0.0)
                            else -> fiatValue
                        }
                    }
                }
            }

            val notes = if (fields.size > 8 && fields[8].isNotEmpty()) fields[8] else null
            // roundToLong throws on NaN, and an amount past Kaspa's supply overflows sompi.
            val amountSompi = com.kachat.app.viewmodels.PortfolioViewModel.kasToSompiOrNull(kas) ?: continue

            val existingId = idByTimestamp[timestampMillis]
            if (existingId != null) {
                updateTransaction(existingId, type, amountSompi, fiatValue, timestampMillis, notes)
            } else {
                val newId = UUID.randomUUID().toString()
                ledgerStore.saveTransaction(
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
        if (imported > 0) {
            // A re-imported export can carry rows whose price was still loading when it was
            // written, and a CSV row has no on-chain source - price them by their date like any
            // other (iOS 98f5009).
            startPriceBackfillIfNeeded(walletAddress)
        }
        return imported
    }

    init {
        // Every wallet load resumes that wallet's price backfill (see [onWalletLoaded]). Last in
        // the class on purpose: the backfill runs on another thread and reads properties declared
        // above, which must all be initialized before it can start.
        priceBackfillScope.launch {
            walletManager.activeAddressFlow.collect { onWalletLoaded(it) }
        }
    }

    companion object {
        /** The CSV export's name when the portfolio has none (iOS 87b2a0b). */
        const val DEFAULT_EXPORT_NAME = "KaChat Portfolio"

        /**
         * The export's file name: the name given to the portfolio in the app, so it is
         * recognizable in Files and Nextcloud; [DEFAULT_EXPORT_NAME] if it has none. Characters a
         * file name can't hold are dropped; at most 60 characters (iOS
         * `PortfolioViewModel.exportBaseName`, 87b2a0b).
         */
        fun exportBaseName(portfolioName: String?): String {
            val cleaned = portfolioName.orEmpty()
                .filterNot { it in "/\\:?*\"<>|" || Character.isISOControl(it) }
                .trim()
            if (cleaned.isEmpty()) return DEFAULT_EXPORT_NAME
            // 60 characters as a person counts them (never half an emoji).
            val breaks = java.text.BreakIterator.getCharacterInstance().apply { setText(cleaned) }
            var end = 0
            var count = 0
            while (count < 60) {
                val next = breaks.next()
                if (next == java.text.BreakIterator.DONE) { end = cleaned.length; break }
                end = next
                count++
            }
            return cleaned.substring(0, end)
        }

        /**
         * Splits a CSV document into records of fields (RFC 4180): commas and line breaks inside
         * double quotes belong to the field, "" inside quotes is one literal quote, and a line
         * break outside quotes - LF, CRLF or CR - ends the record. Blank lines yield no record.
         * Mirrors iOS `parseCsvRecords` (98f5009).
         */
        fun parseCsvRecords(content: String): List<List<String>> {
            val records = mutableListOf<List<String>>()
            var fields = mutableListOf<String>()
            val current = StringBuilder()
            var inQuotes = false
            fun endRecord() {
                fields.add(current.toString())
                if (!(fields.size == 1 && fields[0].isBlank())) records.add(fields)
                fields = mutableListOf()
                current.clear()
            }
            var i = 0
            while (i < content.length) {
                val c = content[i]
                if (inQuotes) {
                    if (c == '"') {
                        if (i + 1 < content.length && content[i + 1] == '"') {
                            current.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        current.append(c)
                    }
                } else when (c) {
                    '"' -> inQuotes = true
                    ',' -> {
                        fields.add(current.toString())
                        current.clear()
                    }
                    '\r' -> {
                        if (i + 1 < content.length && content[i + 1] == '\n') i++
                        endRecord()
                    }
                    '\n' -> endRecord()
                    else -> current.append(c)
                }
                i++
            }
            if (current.isNotEmpty() || fields.isNotEmpty()) endRecord()
            return records
        }

        private const val PRICE_HISTORY_PREFS_NAME = "kachat_price_history_cache"
        private const val PREF_SERIES_MIGRATED_TO_FILES = "series_moved_to_files"

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

        /** The "All" range: everything there is, drawn from Gate.io's listing onwards with
         *  CoinGecko's last year on top (see getAllTimeHistory). Not a day count - a marker. */
        const val ALL_TIME_DAYS = 0

        /** Spacing between the price backfill's per-day historical-price requests (iOS
         *  `PortfolioAddressImporter.priceRequestSpacingNanoseconds`). */
        const val PRICE_REQUEST_SPACING_MILLIS = 1_200L

        /** When the price backfill's passes run: right away, then 30 s, 2 min and 5 min later,
         *  each only while something is still pending (iOS `startPriceBackfillIfNeeded`). */
        val PRICE_BACKFILL_PASS_DELAYS_MILLIS = longArrayOf(0L, 30_000L, 120_000L, 300_000L)

        /** Days one backfill pass prices through the per-day fallback (iOS `missing.prefix(30)`). */
        const val MAX_FALLBACK_DAYS_PER_PASS = 30
    }
}

/** [pendingPriceCount] rows were imported with fiatValue 0.0 and a flagging note — their days'
 *  prices are being backfilled in the background and fill in as each fetch lands; only rows
 *  whose price never arrives keep the note for manual pricing. */
data class AddressImportResult(
    val importedCount: Int,
    val pendingPriceCount: Int,
    /** Network fees this address paid that the import counted, for the Fees Spent card. */
    val feeCount: Int = 0,
    /** False when the history fetch gave up partway: what it got is imported, and adding the
     *  address again later imports the rest (iOS ImportResult.historyComplete). */
    val historyComplete: Boolean = true,
)

/**
 * The toast after "Add Kaspa Address", iOS PortfolioTransactionsView's wording a sentence at a
 * time: how many were imported, the fees counted, the prices still loading, and - when the
 * history came back incomplete - that re-adding the address later imports the rest.
 * [feesCounted] renders ". Network fees counted: N" in the app's language.
 */
fun addressImportToastMessage(result: AddressImportResult, feesCounted: (Int) -> String): String {
    var message = "Imported ${result.importedCount} transaction${if (result.importedCount == 1) "" else "s"}"
    if (result.feeCount > 0) message += feesCounted(result.feeCount)
    if (result.pendingPriceCount > 0) {
        message += ". Prices for ${result.pendingPriceCount} are still loading and will fill in automatically"
    }
    if (!result.historyComplete) {
        message += ". Some history couldn't be fetched, re-add this address later to import the rest"
    }
    return message
}

/** Marks a [PortfolioTransactionEntity.notes] value as "auto-imported but couldn't be priced" — checked by [com.kachat.app.ui.screens.PortfolioScreen]'s transaction row to show a warning icon flagging rows that still need the user to fill in a price. */
const val PRICE_UNAVAILABLE_NOTE = "Price unavailable — set manually"

/** iOS's current price-pending marker (`PortfolioAddressImporter.priceUnavailableNote`); a row
 *  restored from an iOS device's backup can carry it. */
const val IOS_PRICE_LOADING_NOTE = "Price loading, will fill in automatically"

/** True when [notes] marks a row whose price is still pending, either marker (iOS
 *  `PortfolioAddressImporter.isPricePending`). */
fun isPricePending(notes: String?): Boolean = notes == PRICE_UNAVAILABLE_NOTE || notes == IOS_PRICE_LOADING_NOTE

sealed class PortfolioAddressImportError(message: String) : Exception(message) {
    object InvalidAddress : PortfolioAddressImportError("That doesn't look like a valid Kaspa address.")
    object NoTransactions : PortfolioAddressImportError("No new transactions found for this address.")
    object HistoryFetchFailed : PortfolioAddressImportError("Couldn't fetch this address's transactions. Check your connection and try again.")
}
