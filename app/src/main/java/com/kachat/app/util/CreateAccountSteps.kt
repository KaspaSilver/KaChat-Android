package com.kachat.app.util

/**
 * The rules of Create Account's three steps (iOS acd879b / c6bd716): a name for the wallet
 * (stored only on this device), then the seed length, then the seed phrase. The passphrase step
 * after them commits the account. Import Account shares the name and length steps (iOS dd0aab1):
 * the source wallet, the name, the length, the words, then the passphrase.
 */
object CreateAccountSteps {
    /** The seed lengths offered, in the order shown: 12 first, neither one "recommended". */
    val WORD_COUNTS = listOf(12, 24)

    /** The name as it is saved (iOS `trimmedAlias`). */
    fun accountName(raw: String): String = raw.trim()

    /** Step 1's Next: a name that isn't only spaces. */
    fun canContinue(raw: String): Boolean = accountName(raw).isNotEmpty()

    /** Step 2's Generate Account: a length is chosen (none is until the user taps one), and the
     *  phrase isn't being generated already. */
    fun canGenerate(wordCount: Int?, isCreating: Boolean): Boolean = wordCount in WORD_COUNTS && !isCreating

    /** Import Account's length step Next (iOS dd0aab1 `ImportLengthStep`): a length is chosen
     *  (none is until the user taps one). */
    fun canChooseImportLength(wordCount: Int?): Boolean = wordCount in WORD_COUNTS

    /** The words screen's starting length from the length step: 12, or 24 for anything else
     *  (iOS `ImportWalletView.init`). A pasted phrase of the other length switches it. */
    fun importWordCount(chosen: Int): Int = if (chosen == 12) 12 else 24
}
