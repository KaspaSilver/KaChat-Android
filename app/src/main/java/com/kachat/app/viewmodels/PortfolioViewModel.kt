package com.kachat.app.viewmodels

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kachat.app.models.PortfolioEntity
import com.kachat.app.models.PortfolioTransactionEntity
import com.kachat.app.repository.AddressImportResult
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.repository.PortfolioRepository
import com.kachat.app.services.PortfolioManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Per-portfolio display data for the picker header — computed for every portfolio at once (not
 * just the active one) since every card renders simultaneously. `todayChangeAmount`/Percent are
 * both null when there isn't yet a 24h-old value-history sample for that portfolio (e.g. created
 * today); callers should show a neutral/no-data state rather than a misleading number.
 */
data class PortfolioCardData(
    val currentValue: Double,
    val todayChangeAmount: Double?,
    val todayChangePercent: Double?
)

/**
 * All-time P&L, not per-lot realized/unrealized — money still held (valued at the current
 * price) plus money already taken out via sells, minus money originally put in. Correct
 * regardless of buy/sell ordering, and doesn't need FIFO/average-cost lot tracking, which is
 * more machinery than a personal manual tracker needs.
 */
data class PortfolioSummary(
    val holdingsKas: Double,
    val totalInvested: Double,
    val totalProceeds: Double,
    val currentValue: Double,
    val totalPL: Double,
    val totalPLPercent: Double,
    /** Lifetime cost basis per KAS across every buy (totalInvested / total KAS ever bought); null
     *  with no buys yet. Matches iOS/desktop's `averageBuyPriceUsd`. */
    val averageBuyPriceUsd: Double? = null
)

@HiltViewModel
class PortfolioViewModel @Inject constructor(
    private val repository: PortfolioRepository,
    private val settings: AppSettingsRepository,
    private val portfolioManager: PortfolioManager
) : ViewModel() {

    val transactions = repository.getTransactions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Every portfolio for the current wallet (up to [PortfolioManager.MAX_PORTFOLIOS]) and which one is active — back the picker header. */
    val portfolios: StateFlow<List<PortfolioEntity>> = portfolioManager.getPortfolios()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val activePortfolioId: StateFlow<String?> = portfolioManager.activePortfolioIdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setActivePortfolio(id: String) = portfolioManager.setActivePortfolio(id)

    fun addPortfolio(name: String) {
        viewModelScope.launch { portfolioManager.addPortfolio(name) }
    }

    fun renamePortfolio(id: String, newName: String) {
        viewModelScope.launch { portfolioManager.renamePortfolio(id, newName) }
    }

    fun deletePortfolio(id: String) {
        viewModelScope.launch { portfolioManager.deletePortfolio(id) }
    }

    private val _currentPriceUsd = MutableStateFlow<Double?>(null)
    val currentPriceUsd: StateFlow<Double?> = _currentPriceUsd.asStateFlow()

    /** KAS price's percent change over the last 24 hours, from CoinGecko's own rolling 24h
     *  figure (not derived from [priceHistory], which is a chart-range the user can toggle) —
     *  shown next to the price in [PortfolioSummaryCard]. Null while unavailable rather than 0,
     *  so the UI can distinguish "no data yet" from "flat". */
    private val _priceChange24h = MutableStateFlow<Double?>(null)
    val priceChange24h: StateFlow<Double?> = _priceChange24h.asStateFlow()

    /** Backs PortfolioScreen's pull-to-refresh indicator - true while a refreshPrice() call's price + history fetches are both still in flight. */
    private val _isRefreshingPortfolio = MutableStateFlow(false)
    val isRefreshingPortfolio: StateFlow<Boolean> = _isRefreshingPortfolio.asStateFlow()

    private val _priceHistory = MutableStateFlow<List<Pair<Long, Double>>>(emptyList())
    val priceHistory: StateFlow<List<Pair<Long, Double>>> = _priceHistory.asStateFlow()

    /** Backs the tappable "Price (Xd)" range switcher — 1, 7, or 30 days. */
    private val _priceRangeDays = MutableStateFlow(30)
    val priceRangeDays: StateFlow<Int> = _priceRangeDays.asStateFlow()

    val summary: StateFlow<PortfolioSummary> = combine(transactions, currentPriceUsd) { txs, price ->
        computeSummary(txs, price ?: 0.0)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), computeSummary(emptyList(), 0.0))

    /** Holdings' USD value at each price-history point — not the price itself, see [computeValueHistory]. */
    val valueHistory: StateFlow<List<Pair<Long, Double>>> = combine(transactions, priceHistory) { txs, prices ->
        computeValueHistory(txs, prices)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var priceHistoryJob: Job? = null

    // Per-range (days -> history) session cache. Re-selecting an already-fetched range applies
    // instantly with no network call — both nicer UX and, more importantly, far less likely to
    // hit CoinGecko's public-API rate limit (429) than re-fetching on every single tap of the
    // range cycle. Backed by a second, persistent layer surviving relaunches (see
    // [PortfolioRepository.readPersistedPriceHistory] and fetchPriceHistory below for the
    // session -> persisted -> network lookup order). Cleared on refreshPrice() (an explicit "get
    // me current data" action) since a genuine refresh should bypass stale cached history, not
    // just the current range's live price.
    //
    // Declared before the init block below, which calls refreshPrice() -> this map, on purpose:
    // Kotlin runs property initializers and init blocks in textual declaration order, so this had
    // been declared AFTER that init block once, and refreshPrice() crashed with a
    // NullPointerException on priceHistoryCache.clear() every single time the ViewModel was first
    // constructed (i.e. on every visit to the Portfolio tab) — confirmed via device crash logcat.
    private val priceHistoryCache = mutableMapOf<Int, List<Pair<Long, Double>>>()

    /** Lowercase ISO 4217 code - see [AppSettingsRepository.currency]. Declared before the init
     *  block below for the same reason [priceHistoryCache] is (see its doc comment): Kotlin runs
     *  property initializers in textual order, and [refreshPrice] reads [currency].value. */
    val currency: StateFlow<String> = settings.currency
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "usd")

    /** A 7-day price history kept independent of [priceHistory]'s user-selected chart range
     *  (1/7/30d) — the portfolio picker header's "today's change" per-card figures need a stable
     *  window that doesn't shift just because the user toggled the visible chart. Declared before
     *  the init block below for the same textual-order reason [priceHistoryCache] is. */
    private val _sevenDayPriceHistory = MutableStateFlow<List<Pair<Long, Double>>>(emptyList())

    /** Every portfolio's current value + today's change, for the picker header — computed from
     *  every portfolio's own transactions (not just the active one) replayed against
     *  [_sevenDayPriceHistory], since every card renders simultaneously. */
    val cardSummaries: StateFlow<Map<String, PortfolioCardData>> = combine(
        portfolios, repository.getAllTransactionsForWallet(), currentPriceUsd, _sevenDayPriceHistory
    ) { portfolioList, allTransactions, price, sevenDayHistory ->
        portfolioList.associate { portfolio ->
            val scoped = allTransactions.filter { it.portfolioId == portfolio.id }
            val currentValue = computeSummary(scoped, price ?: 0.0).currentValue
            val todayChange = computeTodayChange(computeValueHistory(scoped, sevenDayHistory))
            portfolio.id to PortfolioCardData(currentValue, todayChange?.first, todayChange?.second)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        refreshPrice()
        // Currency changed elsewhere (Settings, or the Welcome Guide's currency step) while
        // Portfolio is already alive - re-fetch in the new currency rather than leaving stale
        // numbers on screen mislabeled with a new currency's symbol. drop(1) skips the initial
        // emission refreshPrice() above already covers.
        viewModelScope.launch {
            settings.currency.drop(1).distinctUntilChanged().collect {
                refreshPrice()
            }
        }
    }

    fun refreshPrice() {
        val currencyCode = currency.value
        val priceJob = viewModelScope.launch {
            val result = repository.getCurrentPriceUsd(currencyCode)
            if (result != null) {
                _currentPriceUsd.value = result.price
                _priceChange24h.value = result.change24hPercent
            }
        }
        priceHistoryCache.clear()
        fetchPriceHistory(_priceRangeDays.value, force = true)
        fetchSevenDayPriceHistoryForCards()
        val historyJob = priceHistoryJob
        viewModelScope.launch {
            _isRefreshingPortfolio.value = true
            priceJob.join()
            historyJob?.join()
            _isRefreshingPortfolio.value = false
        }
    }

    /**
     * Fetches (or refetches, on a currency change) the fixed 7-day window [_sevenDayPriceHistory]
     * relies on — independent of whatever range the visible chart is currently toggled to.
     * Served from the persisted 10-minute cache when fresh enough (the cards tolerate slight
     * staleness), and otherwise staggered 1.5s behind the main chart's fetch — this call landing
     * in the same instant as the price + chart fetches was part of the launch burst that tripped
     * CoinGecko's keyless-tier throttle (see [PortfolioRepository.getPriceHistory]).
     */
    private fun fetchSevenDayPriceHistoryForCards() {
        val currencyCode = currency.value
        repository.readPersistedPriceHistory(7, currencyCode)?.let { persisted ->
            if (System.currentTimeMillis() - persisted.fetchedAtMillis < PortfolioRepository.PRICE_HISTORY_CACHE_TTL_MILLIS) {
                _sevenDayPriceHistory.value = persisted.points
                return
            }
        }
        viewModelScope.launch {
            delay(1_500)
            val result = repository.getPriceHistory(7, currencyCode)
            if (result.isNotEmpty()) {
                repository.persistPriceHistory(result, 7, currencyCode)
                _sevenDayPriceHistory.value = result
            }
        }
    }

    /** Switches the price chart's window (1/7/30 days) and refetches history for it. */
    fun setPriceRangeDays(days: Int) {
        if (_priceRangeDays.value == days) return
        _priceRangeDays.value = days
        fetchPriceHistory(days)
    }

    /**
     * Serves [days] from [priceHistoryCache] if already fetched this session, then from the
     * persisted 10-minute cache (surviving relaunches — see
     * [PortfolioRepository.readPersistedPriceHistory]), before hitting the network — cancelling
     * any still-in-flight fetch first (rapidly tapping the range cycle otherwise fires
     * overlapping requests — see [priceHistoryCache]'s doc comment for why that's a real problem,
     * not just wasteful). [force] (explicit refresh, see [refreshPrice]) skips both caches.
     *
     * An empty network result gets one paced retry (on top of the Retry-After-honoring retry
     * inside [PortfolioRepository.getPriceHistory] itself), then falls back to the persisted copy
     * for this range even if stale — a rate-limited fetch was previously either wiping out the
     * whole chart card (it only renders when `priceHistory.size >= 2`) or getting stuck silently
     * showing a stale *range* forever (every range's fetch coming back empty left the first
     * loaded range's ~1-day curve on screen no matter which range was selected) — both confirmed
     * via on-device repro; a stale curve for the *requested* range beats either. Only if there's
     * nothing at all is [_priceHistory] left alone, preserving whatever chart is on screen
     * instead of blanking it, and the range simply retried on its next selection since nothing
     * was cached.
     */
    private fun fetchPriceHistory(days: Int, force: Boolean = false) {
        val currencyCode = currency.value
        if (!force) {
            priceHistoryCache[days]?.let { cached ->
                _priceHistory.value = cached
                return
            }
            repository.readPersistedPriceHistory(days, currencyCode)?.let { persisted ->
                if (System.currentTimeMillis() - persisted.fetchedAtMillis < PortfolioRepository.PRICE_HISTORY_CACHE_TTL_MILLIS) {
                    priceHistoryCache[days] = persisted.points
                    _priceHistory.value = persisted.points
                    return
                }
            }
        }
        priceHistoryJob?.cancel()
        priceHistoryJob = viewModelScope.launch {
            var result = repository.getPriceHistory(days, currencyCode)
            if (result.isEmpty()) {
                // One paced retry — the keyless tier's throttle window usually clears quickly.
                delay(3_000)
                result = repository.getPriceHistory(days, currencyCode)
            }
            if (result.isNotEmpty()) {
                repository.persistPriceHistory(result, days, currencyCode)
            } else {
                result = repository.readPersistedPriceHistory(days, currencyCode)?.points ?: emptyList()
            }
            if (result.isNotEmpty()) {
                priceHistoryCache[days] = result
                _priceHistory.value = result
            }
        }
    }

    fun addTransaction(type: String, amountKas: Double, fiatValue: Double, timestampMillis: Long = System.currentTimeMillis(), notes: String? = null) {
        viewModelScope.launch {
            repository.addTransaction(type, (amountKas * 100_000_000).toLong(), fiatValue, timestampMillis, notes)
        }
    }

    fun updateTransaction(id: String, type: String, amountKas: Double, fiatValue: Double, timestampMillis: Long, notes: String? = null) {
        viewModelScope.launch {
            repository.updateTransaction(id, type, (amountKas * 100_000_000).toLong(), fiatValue, timestampMillis, notes)
        }
    }

    fun deleteTransaction(id: String) {
        viewModelScope.launch { repository.deleteTransaction(id) }
    }

    /** No network/DB work involved (transactions are already in memory), so no loading-state UI is needed. */
    fun exportCsv(onReady: (Uri) -> Unit) {
        try {
            onReady(repository.exportCsv(transactions.value))
        } catch (e: Exception) {
            Log.w("PortfolioViewModel", "CSV export failed", e)
        }
    }

    fun importCsv(uri: Uri, onResult: (Result<Int>) -> Unit) {
        viewModelScope.launch {
            try {
                onResult(Result.success(repository.importCsv(uri)))
            } catch (e: Exception) {
                Log.w("PortfolioViewModel", "CSV import failed", e)
                onResult(Result.failure(e))
            }
        }
    }

    /**
     * Suspend (not launched internally) so the caller can await the result while also observing
     * live [onProgress] updates during the fetch/pricing steps — call from a
     * `rememberCoroutineScope()`-launched coroutine in the UI, same as any other suspend
     * ViewModel call. See [com.kachat.app.repository.PortfolioRepository.importAddress] for what
     * "Add Kaspa Address" actually does.
     */
    suspend fun importAddress(address: String, onProgress: (String) -> Unit): Result<AddressImportResult> {
        return try {
            Result.success(repository.importAddress(address, currency.value, onProgress))
        } catch (e: Exception) {
            Log.w("PortfolioViewModel", "Address import failed", e)
            Result.failure(e)
        }
    }

    companion object {
        internal fun computeSummary(transactions: List<PortfolioTransactionEntity>, currentPriceUsd: Double): PortfolioSummary {
            var holdingsSompi = 0L
            var totalInvested = 0.0
            var totalProceeds = 0.0
            var totalBoughtSompi = 0L
            for (tx in transactions) {
                when (tx.type) {
                    "buy" -> {
                        holdingsSompi += tx.amountSompi
                        totalInvested += tx.fiatValue
                        totalBoughtSompi += tx.amountSompi
                    }
                    "sell" -> {
                        holdingsSompi -= tx.amountSompi
                        totalProceeds += tx.fiatValue
                    }
                }
            }
            val holdingsKas = holdingsSompi / 100_000_000.0
            val currentValue = holdingsKas * currentPriceUsd
            val totalPL = (currentValue + totalProceeds) - totalInvested
            val totalPLPercent = if (totalInvested > 0) (totalPL / totalInvested) * 100.0 else 0.0
            val totalBoughtKas = totalBoughtSompi / 100_000_000.0
            val averageBuyPriceUsd = if (totalBoughtKas > 0) totalInvested / totalBoughtKas else null
            return PortfolioSummary(
                holdingsKas = holdingsKas,
                totalInvested = totalInvested,
                totalProceeds = totalProceeds,
                currentValue = currentValue,
                totalPL = totalPL,
                totalPLPercent = totalPLPercent,
                averageBuyPriceUsd = averageBuyPriceUsd
            )
        }

        /**
         * Replays the transaction ledger against each price-history point to get holdings *as of
         * that moment* (not current holdings) — a buy/sell made partway through the window changes
         * the value curve's shape from that point on, not retroactively.
         */
        internal fun computeValueHistory(
            transactions: List<PortfolioTransactionEntity>,
            priceHistory: List<Pair<Long, Double>>
        ): List<Pair<Long, Double>> {
            if (priceHistory.isEmpty()) return emptyList()
            val sortedTx = transactions.sortedBy { it.timestampMillis }
            return priceHistory.map { (timestamp, price) ->
                var holdingsSompi = 0L
                for (tx in sortedTx) {
                    if (tx.timestampMillis > timestamp) break
                    when (tx.type) {
                        "buy" -> holdingsSompi += tx.amountSompi
                        "sell" -> holdingsSompi -= tx.amountSompi
                    }
                }
                val holdingsKas = holdingsSompi / 100_000_000.0
                timestamp to (holdingsKas * price)
            }
        }

        /**
         * Real today-only $ and % change (not all-time P&L) for the portfolio picker header's
         * cards — the latest value-history sample minus whichever sample is closest to (but not
         * after) 24h before it. `null` when no sample exists that far back yet (e.g. a portfolio
         * created today), so callers can show a neutral/no-data state instead of a wrong number.
         */
        internal fun computeTodayChange(valueHistory: List<Pair<Long, Double>>): Pair<Double, Double>? {
            val latest = valueHistory.lastOrNull() ?: return null
            val dayAgoMillis = latest.first - 86_400_000L
            val basePoint = valueHistory.lastOrNull { it.first <= dayAgoMillis } ?: return null
            val amount = latest.second - basePoint.second
            val percent = if (basePoint.second == 0.0) 0.0 else (amount / basePoint.second) * 100.0
            return amount to percent
        }
    }
}
