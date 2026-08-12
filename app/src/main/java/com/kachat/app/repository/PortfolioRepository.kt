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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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

    suspend fun addTransaction(type: String, amountSompi: Long, fiatValue: Double, timestampMillis: Long = System.currentTimeMillis(), notes: String? = null) {
        val portfolioId = currentPortfolioId() ?: return
        database.portfolioDao().insert(
            PortfolioTransactionEntity(
                id = UUID.randomUUID().toString(),
                walletAddress = walletManager.getAddress(),
                portfolioId = portfolioId,
                type = type,
                amountSompi = amountSompi,
                fiatValue = fiatValue,
                timestampMillis = timestampMillis,
                notes = notes
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

    suspend fun getCurrentPriceUsd(currency: String = "usd"): PriceWithChange? {
        return try {
            val kaspa = coinGeckoApi.getSimplePrice(vsCurrencies = currency).kaspa
            val price = kaspa[currency] ?: return null
            PriceWithChange(price, kaspa["${currency}_24h_change"])
        } catch (e: Exception) {
            null
        }
    }

    /**
     * (timestampMillis, price) pairs in [currency], oldest first — empty on failure rather than
     * throwing, so callers must not blindly overwrite existing cached history with an empty
     * result (see [com.kachat.app.viewmodels.PortfolioViewModel]'s fetchPriceHistory).
     *
     * CoinGecko's keyless tier throttles bursts hard (429 for a stretch after just a few rapid
     * calls) — a launch plus a couple of chart-range taps was enough to make every subsequent
     * range fetch come back empty, leaving the chart stuck on whatever range loaded first. A
     * 429/5xx here gets one retry, honoring the response's Retry-After (capped at 10s).
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
                delay((minOf(retryAfterSeconds, 10.0) * 1000).toLong())
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

    private val historyDateFormat = java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    /**
     * Daily-granularity snapshot price CoinGecko recorded for [dayStartMillis] (pass a UTC
     * day-start timestamp) — used by "Add Kaspa Address" to price auto-imported transactions.
     * Null on any failure or when CoinGecko simply has no data for that date, same "degrade
     * gracefully" contract as [getCurrentPriceUsd]/[getPriceHistory].
     */
    suspend fun getHistoricalPrice(dayStartMillis: Long, currency: String = "usd"): Double? {
        return try {
            val dateString = synchronized(historyDateFormat) { historyDateFormat.format(java.util.Date(dayStartMillis)) }
            coinGeckoApi.getHistory(date = dateString).marketData?.currentPrice?.get(currency)
        } catch (e: Exception) {
            null
        }
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

    /**
     * Fetches [address]'s on-chain transaction history and adds new buy/sell rows into the
     * active portfolio — every received transaction becomes a buy, every sent transaction
     * becomes a sell, priced at that day's historical KAS price. Deliberately no attempt to
     * filter out ordinary KaChat payments/protocol overhead (see PortfolioTransactionEntity's
     * doc comment on why manual entry was originally the only path) — an explicit, simpler
     * alternative the user opted into. Re-entering the same address later only adds transactions
     * not already present for it (deduped by on-chain tx id). Every matching transaction is
     * imported even if its day's price couldn't be fetched — it lands with fiatValue 0.0 and a
     * note flagging it, rather than being silently dropped from the ledger.
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

        // One historical-price fetch per unique day (not per transaction) — CoinGecko's history
        // endpoint is daily-granularity anyway, and this keeps request count bounded even for a
        // very active address. Paced sequentially to stay under the free tier's rate limit.
        val priceRequestSpacingMillis = 1_200L
        val uniqueDays = candidates.map { it.dayStartMillis }.distinct().sorted()
        val priceByDay = mutableMapOf<Long, Double?>()
        for ((index, day) in uniqueDays.withIndex()) {
            onProgress("Pricing ${index + 1}/${uniqueDays.size} days…")
            var price = getHistoricalPrice(day, currency)
            if (price == null) {
                // One retry — a single transient failure shouldn't cost that whole day's rows.
                delay(priceRequestSpacingMillis)
                price = getHistoricalPrice(day, currency)
            }
            priceByDay[day] = price
            if (index < uniqueDays.size - 1) {
                delay(priceRequestSpacingMillis)
            }
        }

        // Every candidate is imported regardless of whether its day's price could be fetched —
        // a row with no price is still real ledger data (type, amount, date, source tx) the user
        // can see and fill the price into themselves, rather than silently disappearing.
        var importedCount = 0
        var missingPriceCount = 0
        for (candidate in candidates) {
            val price = priceByDay[candidate.dayStartMillis]
            if (price == null) missingPriceCount++
            val amountKas = candidate.amountSompi / 100_000_000.0
            database.portfolioDao().insert(
                PortfolioTransactionEntity(
                    id = UUID.randomUUID().toString(),
                    walletAddress = walletAddress,
                    portfolioId = portfolioId,
                    type = if (candidate.sent) "sell" else "buy",
                    amountSompi = candidate.amountSompi,
                    fiatValue = amountKas * (price ?: 0.0),
                    timestampMillis = candidate.timestampMillis,
                    notes = if (price == null) PRICE_UNAVAILABLE_NOTE else null,
                    sourceAddress = trimmed,
                    sourceTxId = candidate.txId
                )
            )
            importedCount++
        }

        return AddressImportResult(importedCount, missingPriceCount)
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
    }
}

/** [missingPriceCount] rows were still imported (with fiatValue 0.0 and a flagging note) — not skipped — because that day's historical price couldn't be fetched. */
data class AddressImportResult(val importedCount: Int, val missingPriceCount: Int)

/** Marks a [PortfolioTransactionEntity.notes] value as "auto-imported but couldn't be priced" — checked by [com.kachat.app.ui.screens.PortfolioScreen]'s transaction row to show a warning icon flagging rows that still need the user to fill in a price. */
const val PRICE_UNAVAILABLE_NOTE = "Price unavailable — set manually"

sealed class PortfolioAddressImportError(message: String) : Exception(message) {
    object InvalidAddress : PortfolioAddressImportError("That doesn't look like a valid Kaspa address.")
    object NoTransactions : PortfolioAddressImportError("No new transactions found for this address.")
}
