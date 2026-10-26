package com.kachat.app.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.repository.KasPriceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 1 KAS in the app's currency for the Send screens' fiat mirror (iOS `KasPriceModel`, d54ea87),
 * which used to borrow Portfolio's view model for it. The last price paints at once; a stale one
 * is fetched again, and a failed fetch retries after CoinGecko's own throttle window.
 */
@HiltViewModel
class KasPriceViewModel @Inject constructor(
    private val repository: KasPriceRepository,
    settings: AppSettingsRepository,
) : ViewModel() {
    val currency: StateFlow<String> = settings.currency
        .stateIn(viewModelScope, SharingStarted.Eagerly, "usd")

    private val _currentPrice = MutableStateFlow<Double?>(null)
    /** Priced in [currency], whatever the currency - the name the Send screens read. */
    val currentPriceUsd: StateFlow<Double?> = _currentPrice.asStateFlow()

    private var retryJob: Job? = null
    private var retryBackoffMillis = INITIAL_RETRY_BACKOFF_MILLIS

    init {
        viewModelScope.launch {
            settings.currency.distinctUntilChanged().collect { refresh(it) }
        }
    }

    private fun refresh(currencyCode: String) {
        retryJob?.cancel()
        val persisted = repository.readPersisted(currencyCode)
        _currentPrice.value = persisted?.price
        if (persisted != null && System.currentTimeMillis() - persisted.fetchedAtMillis < KasPriceRepository.FRESH_MILLIS) return
        viewModelScope.launch {
            val price = repository.fetch(currencyCode)
            if (price != null) {
                _currentPrice.value = price
                retryBackoffMillis = INITIAL_RETRY_BACKOFF_MILLIS
            } else {
                scheduleRetry(currencyCode)
            }
        }
    }

    private fun scheduleRetry(currencyCode: String) {
        val waitMillis = repository.throttledUntilMillis?.let { maxOf(it - System.currentTimeMillis(), 0L) + 1_000L }
            ?: retryBackoffMillis.also { retryBackoffMillis = minOf(it * 2, MAX_RETRY_BACKOFF_MILLIS) }
        retryJob = viewModelScope.launch {
            delay(waitMillis)
            refresh(currencyCode)
        }
    }

    private companion object {
        const val INITIAL_RETRY_BACKOFF_MILLIS = 5_000L
        const val MAX_RETRY_BACKOFF_MILLIS = 120_000L
    }
}
