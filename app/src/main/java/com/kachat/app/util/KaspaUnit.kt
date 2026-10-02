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
}
