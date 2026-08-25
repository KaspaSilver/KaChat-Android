package com.kachat.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.R
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.WalletViewModel

/**
 * Fiat currency for Portfolio's live KAS price/value display, independent of device locale.
 * Raw [code] is the lowercase ISO 4217 value, doubling as the literal CoinGecko `vs_currency`
 * value (see `CoinGeckoApi`/`PortfolioRepository`) - no separate mapping table to keep in sync.
 * Mirrors iOS's `AppCurrency` enum's shape and case list exactly.
 */
enum class AppCurrency(val code: String, val currencyName: String) {
    USD("usd", "US Dollar"),
    EUR("eur", "Euro"),
    GBP("gbp", "British Pound"),
    JPY("jpy", "Japanese Yen"),
    CNY("cny", "Chinese Yuan"),
    AUD("aud", "Australian Dollar"),
    CAD("cad", "Canadian Dollar"),
    CHF("chf", "Swiss Franc"),
    HKD("hkd", "Hong Kong Dollar"),
    INR("inr", "Indian Rupee"),
    KRW("krw", "South Korean Won"),
    SGD("sgd", "Singapore Dollar"),
    IDR("idr", "Indonesian Rupiah"),
    NZD("nzd", "New Zealand Dollar"),
    MXN("mxn", "Mexican Peso"),
    BRL("brl", "Brazilian Real"),
    RUB("rub", "Russian Ruble"),
    TRY("try", "Turkish Lira"),
    ZAR("zar", "South African Rand"),
    // Not ISO 4217 (no fiat currency is) - CoinGecko's `vs_currency` list includes major
    // cryptocurrencies alongside fiat ones, "btc" among them, so this needs no special handling
    // in CoinGeckoApi/PortfolioRepository: same API call as any other code.
    BTC("btc", "Bitcoin");

    val displayName: String get() = "$currencyName (${code.uppercase()})"

    companion object {
        /** Falls back to [USD] for an unrecognized/blank stored code (e.g. right after a fresh install). */
        fun fromCode(code: String): AppCurrency = entries.firstOrNull { it.code == code } ?: USD
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurrencySettingsScreen(onBack: () -> Unit, walletViewModel: WalletViewModel = hiltViewModel()) {
    val currencyCode by walletViewModel.currency.collectAsState()
    val current = AppCurrency.fromCode(currencyCode)

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.currency), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBackIos, null, tint = LocalAppColors.current.textPrimary, modifier = Modifier.size(20.dp))
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSection(title = stringResource(R.string.currency)) {
                AppCurrency.entries.forEachIndexed { index, currency ->
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { walletViewModel.setCurrency(currency.code) }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                currency.displayName,
                                color = LocalAppColors.current.textPrimary,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            if (current == currency) {
                                Icon(Icons.Default.Check, null, tint = KaspaTeal)
                            }
                        }
                        if (index < AppCurrency.entries.lastIndex) {
                            SettingsDivider()
                        }
                    }
                }
            }
        }
    }
}
