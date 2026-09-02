package com.kachat.app.util

import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Thousands grouping for a number the user is typing. Mirrors iOS's `DecimalInputFormat`.
 *
 * A decimal keypad gives you digits and nothing else, so a six-figure hashrate arrives as
 * "1200000" and has to be counted by eye. [grouped] regroups after every keystroke; [value] hands
 * the number back for whatever needs to compute with it.
 */
object DecimalInputFormat {
    private val symbols: DecimalFormatSymbols get() = DecimalFormatSymbols.getInstance(Locale.getDefault())
    private val grouping: Char get() = symbols.groupingSeparator
    private val decimal: Char get() = symbols.decimalSeparator

    /**
     * [text] with grouping separators put back where they belong.
     *
     * The fractional part is left exactly as typed - grouping it means nothing, and reformatting
     * it would fight the user over trailing zeros and over a lone separator mid-entry ("1." has
     * to survive long enough to become "1.5").
     */
    fun grouped(text: String): String {
        val bare = text.replace(grouping.toString(), "")
        val dot = bare.indexOf(decimal)
        val whole = if (dot >= 0) bare.substring(0, dot) else bare
        val rest = if (dot >= 0) bare.substring(dot) else ""

        val digits = whole.filter { it.isDigit() }
        if (digits.isEmpty()) return bare

        val withSeparators = digits
            .reversed()
            .chunked(3)
            .joinToString(grouping.toString())
            .reversed()
        return withSeparators + rest
    }

    /**
     * Groups a string that uses "." as its decimal point - String.format output, say - into the
     * locale's own form.
     *
     * Not the same as calling [grouped] on it: in a locale like German "." IS the grouping
     * separator, so [grouped] would strip the decimal point out of "1200000.00" and read the
     * whole thing as 120000000.
     */
    fun groupedFromCanonical(text: String): String =
        grouped(text.replace(".", decimal.toString()))

    /** The number behind grouped text, or null when there isn't one yet. */
    fun value(text: String): Double? =
        text.replace(grouping.toString(), "")
            .replace(decimal, '.')
            // Whichever separator the locale did NOT claim is still a decimal point to a user
            // who typed it.
            .replace(',', '.')
            .toDoubleOrNull()
}
