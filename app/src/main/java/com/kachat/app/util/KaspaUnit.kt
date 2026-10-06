package com.kachat.app.util

/**
 * The unit every amount is shown in: KAS on mainnet, TKAS on testnet, so a testnet amount can
 * never be read as real KAS (iOS `KaspaUnit`, 0af0cde). Follows [KaspaNetwork.isTestnet], the
 * network this launch runs on, not the Settings switch. Market data (the KAS price, the
 * converter, exchange tickers) stays "KAS" on purpose.
 */
object KaspaUnit {
    val symbol: String get() = if (KaspaNetwork.isTestnet) "TKAS" else "KAS"

    private val kasWord = Regex("(?<![A-Za-z])KAS(?![A-Za-z])")

    /**
     * [text] with the word KAS shown as the running network's unit. For strings that are already
     * localized: every translation keeps "KAS" as is, so one relabel covers all of them. Matches
     * KAS only as a whole ASCII word, so it also works inside scripts without word breaks
     * ("%1$s KASを受信") and never touches "Kaspa" or an existing "TKAS".
     */
    fun label(text: String): String {
        if (!KaspaNetwork.isTestnet) return text
        return kasWord.replace(text, "TKAS")
    }

    // MARK: - Typed amounts (iOS 16b64bc, IOS-010 / AND-013)

    /** Most sompi any typed amount can mean: a little above Kaspa's 28.7 billion KAS supply. */
    const val MAX_TYPED_SOMPI: Long = 29_000_000_000L * 100_000_000L

    /**
     * A KAS amount the person typed, in sompi: "1.5", "1,5" (comma-decimal keyboards), ".5", at
     * most 8 decimals, no grouping, signs or exponents; Arabic-Indic and Persian digits are read
     * as digits. Exact decimal math (BigDecimal, never a Double multiply or a truncation, so
     * "2.3" is exactly 230,000,000 sompi), and null above [MAX_TYPED_SOMPI], so no input can
     * overflow. 0 is a valid result. The one parser for every KAS amount and custom-fee field
     * (iOS `KaspaUnit.sompi(fromUserText:)`).
     */
    fun sompiFromUserText(text: String): Long? {
        val t = text.trim().map(::asciiAmountChar).joinToString("")
        val dot = t.indexOf('.')
        val wholePart = if (dot >= 0) t.substring(0, dot) else t
        val fracPart = if (dot >= 0) t.substring(dot + 1) else ""
        if (wholePart.isEmpty() && fracPart.isEmpty()) return null
        if (!wholePart.all { it in '0'..'9' } || !fracPart.all { it in '0'..'9' }) return null
        if (fracPart.length > 8) return null
        // 29e9 KAS has 11 digits; anything longer is over the cap anyway.
        val wholeDigits = wholePart.trimStart('0')
        if (wholeDigits.length > 11) return null
        val sompi = java.math.BigDecimal((wholeDigits.ifEmpty { "0" }) + "." + fracPart.ifEmpty { "0" }).movePointRight(8)
        // Compared before it becomes a Long: 11 digits of KAS can still be past Long.MAX_VALUE sompi.
        if (sompi > java.math.BigDecimal.valueOf(MAX_TYPED_SOMPI)) return null
        return sompi.longValueExact()
    }

    /**
     * Cleans an amount field as it's typed: digits and one decimal point ("," becomes "."), at
     * most 8 decimals. The default for every amount entry (iOS `KaspaUnit.sanitizeAmountInput`).
     */
    fun sanitizeAmountInput(value: String): String {
        val result = StringBuilder()
        var dotSeen = false
        var decimals = 0
        for (raw in value) {
            val ch = asciiAmountChar(raw)
            if (ch == '.') {
                if (dotSeen) continue
                dotSeen = true
                result.append(ch)
            } else if (ch in '0'..'9') {
                if (dotSeen) {
                    if (decimals == 8) continue
                    decimals += 1
                }
                result.append(ch)
            }
        }
        return result.toString()
    }

    /** Arabic-Indic and Persian digits (what those keyboards type) as ASCII digits, and the
     *  comma and the Arabic decimal separator as ".". */
    private fun asciiAmountChar(c: Char): Char = when (c) {
        ',', '\u066B' -> '.'
        in '\u0660'..'\u0669' -> '0' + (c - '\u0660')
        in '\u06F0'..'\u06F9' -> '0' + (c - '\u06F0')
        else -> c
    }

    /** [sompi] as plain KAS text, exact, trailing zeros dropped: "2.3", "0.00000001", "35"
     *  (ASCII digits in every language, as iOS's String(format:) does). */
    fun plain(sompi: Long): String {
        val whole = sompi / 100_000_000L
        val frac = sompi % 100_000_000L
        if (frac == 0L) return "$whole"
        return "$whole." + String.format(java.util.Locale.US, "%08d", frac).trimEnd('0')
    }
}
