package com.kachat.app.util

import java.util.Locale

/** Resolves a lowercase ISO 4217 code (e.g. "usd", "eur" - see AppSettingsRepository.currency)
 *  to its display symbol (e.g. "$", "€"), falling back to the uppercased code itself if the
 *  platform doesn't recognize it. */
fun currencySymbolFor(currencyCode: String): String {
    // Not ISO 4217 - Currency.getInstance() below throws for it (caught, falling back to "BTC"),
    // but the Unicode Bitcoin sign reads better as a prefix than the bare code.
    if (currencyCode.equals("btc", ignoreCase = true)) return "₿"
    // Looked up once per currency: Portfolio's rows and chart headers ask on every
    // recomposition, and each lookup built a currency NumberFormat (iOS b45cda1).
    return currencySymbols.getOrPut(currencyCode) {
        try {
            java.text.NumberFormat.getCurrencyInstance(Locale.US).apply {
                currency = java.util.Currency.getInstance(currencyCode.uppercase())
            }.currency?.symbol ?: currencyCode.uppercase()
        } catch (e: Exception) {
            currencyCode.uppercase()
        }
    }
}

private val currencySymbols = java.util.concurrent.ConcurrentHashMap<String, String>()

/** Generic fiat total formatter - symbol, thousands separators, 2 decimals. */
fun formatFiatAmount(value: Double, currencyCode: String): String {
    val sign = if (value < 0) "-" else ""
    return "$sign${currencySymbolFor(currencyCode)}${String.format(Locale.US, "%,.2f", kotlin.math.abs(value))}"
}

/**
 * Trimmed 8-decimal KAS amount (e.g. "12.5" rather than "12.50000000"). Deliberately no thousands
 * separators — this feeds live-editable amount text fields ([KaspaFiatAmountState] and the
 * Portfolio transaction editor's quantity field), where a comma would break `toDoubleOrNull()`
 * parsing. For a display-only comma-grouped variant, see [formatKasAmountGrouped].
 */
fun formatKasAmount(kas: Double): String {
    return String.format(Locale.US, "%.8f", kas).trimEnd('0').trimEnd('.')
}

/** Same as [formatKasAmount] but with thousands separators (e.g. "12,345.5") — for read-only display only, never for a value that gets parsed back (e.g. an editable text field). */
fun formatKasAmountGrouped(kas: Double): String {
    return String.format(Locale.US, "%,.8f", kas).trimEnd('0').trimEnd('.')
}
