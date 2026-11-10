package com.kachat.app.util

/**
 * The rules of Import Account's steps (iOS dd0aab1): a name for the wallet (stored only on this
 * device), the length, the words, then the passphrase, which finds the wallet type the words
 * belong to (iOS da467165). Creating an account has no steps of its own any more (iOS d063b897).
 */
object CreateAccountSteps {
    /** The seed lengths import takes, in the order shown: 12 first, neither one "recommended". */
    val WORD_COUNTS = listOf(12, 24)

    /** The name as it is saved (iOS `trimmedAlias`). */
    fun accountName(raw: String): String = raw.trim()

    /** The name step's Next: a name that isn't only spaces. */
    fun canContinue(raw: String): Boolean = accountName(raw).isNotEmpty()

    /** Import Account's length step Next (iOS dd0aab1 `ImportLengthStep`): a length is chosen
     *  (none is until the user taps one). */
    fun canChooseImportLength(wordCount: Int?): Boolean = wordCount in WORD_COUNTS

    /** The words screen's starting length from the length step: 12, or 24 for anything else
     *  (iOS `ImportWalletView.init`). A pasted phrase of the other length switches it. */
    fun importWordCount(chosen: Int): Int = if (chosen == 12) 12 else 24
}
