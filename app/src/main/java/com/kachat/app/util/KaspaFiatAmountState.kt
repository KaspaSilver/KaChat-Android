package com.kachat.app.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Bridges a single Kaspa-amount text field to an optional live fiat mirror. The value handed to
 * [onKasTextChange] is always KAS regardless of which unit the user is actually typing in
 * ([isFiatMode]) - callers keep exactly the same "amount in KAS" state/send logic they already
 * have, just routing the field's `value`/`onValueChange` through this instead of directly through
 * their own state.
 *
 * [priceInCurrency] (1 KAS's price in the caller's selected fiat currency, from
 * `PortfolioViewModel.currentPriceUsd` - misleadingly named, it's actually priced in whatever
 * currency is selected) and [currencyCode] are passed into each call fresh rather than captured
 * once, since both can change live while this field is on screen (a price refresh landing async,
 * a currency switched in Settings).
 *
 * Every call site using this is behind a conditionally-composed screen/dialog (payment mode,
 * a withdraw `AlertDialog`, a send flow) that gets torn down on cancel/success, so this resets to
 * blank/KAS-mode for free on next open - no explicit reset needed.
 */
class KaspaFiatAmountState internal constructor(private val onKasTextChange: (String) -> Unit) {
    var isFiatMode: Boolean by mutableStateOf(false)
        private set
    var displayText: String by mutableStateOf("")
        private set

    private fun kasFromDisplay(priceInCurrency: Double?): Double? {
        val entered = displayText.toDoubleOrNull() ?: return null
        if (!isFiatMode) return entered
        val price = priceInCurrency ?: return null
        return if (price > 0.0) entered / price else null
    }

    fun onDisplayTextChange(text: String, priceInCurrency: Double?) {
        displayText = text
        if (!isFiatMode) {
            onKasTextChange(text)
            return
        }
        onKasTextChange(kasFromDisplay(priceInCurrency)?.let { formatKasAmount(it) } ?: "")
    }

    /** Caller's own Max button already knows the current max sendable KAS (fee-aware, etc.) -
     *  this just also reflects it into whichever unit is currently being displayed. */
    fun setMaxKas(maxKas: Double, priceInCurrency: Double?) {
        displayText = if (isFiatMode && priceInCurrency != null && priceInCurrency > 0.0) {
            formatFiatPlain(maxKas * priceInCurrency)
        } else {
            formatKasAmount(maxKas)
        }
        onKasTextChange(formatKasAmount(maxKas))
    }

    /** Flips units, carrying today's typed number over converted into the other one rather than
     *  clearing the field. No-ops if there's no live price yet to convert with. */
    fun toggleMode(priceInCurrency: Double?) {
        if (priceInCurrency == null || priceInCurrency <= 0.0) return
        val kas = kasFromDisplay(priceInCurrency)
        isFiatMode = !isFiatMode
        displayText = when {
            kas == null -> ""
            isFiatMode -> formatFiatPlain(kas * priceInCurrency)
            else -> formatKasAmount(kas)
        }
    }

    /** Live value of whichever unit ISN'T currently being typed, for the small label shown next
     *  to Max - null while nothing's entered yet, or (KAS-typing mode only) while there's no live
     *  price to convert with. */
    fun conversionLabelText(priceInCurrency: Double?, currencyCode: String): String? {
        val kas = kasFromDisplay(priceInCurrency) ?: return null
        return if (isFiatMode) {
            "${formatKasAmount(kas)} ${KaspaUnit.symbol}"
        } else {
            if (priceInCurrency == null || priceInCurrency <= 0.0) null else formatFiatAmount(kas * priceInCurrency, currencyCode)
        }
    }
}

/** [resetKey] mirrors the pattern of a `remember(key) { mutableStateOf(...) }` sibling state var
 *  at the same call site (e.g. a dialog's own `remember(entry) { mutableStateOf("") }`) - pass the
 *  same key so this resets in step with it instead of carrying stale text/mode across, for a
 *  composable instance that's reused across different subjects (a different address, a different
 *  contact) rather than torn down and recreated. */
@Composable
fun rememberKaspaFiatAmountState(resetKey: Any? = Unit, onKasTextChange: (String) -> Unit): KaspaFiatAmountState =
    remember(resetKey) { KaspaFiatAmountState(onKasTextChange) }

/** Plain (no currency symbol, no thousands separator) 2-decimal fiat number - for the fiat-mode
 *  field's own edit buffer. [formatFiatAmount] (with symbol/separators) is for display-only text
 *  like the conversion label, not something meant to be typed back into a field. */
private fun formatFiatPlain(value: Double): String = String.format(Locale.US, "%.2f", value)
