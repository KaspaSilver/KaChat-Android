package com.kachat.app.util

import com.kachat.app.services.UtxoEntry

/**
 * Arithmetic on node-supplied UTXO values (iOS 5040628, audit IOS-020).
 *
 * The node pool connects to public peers, and a broken or malicious node (or REST endpoint) can
 * report anything. A uint64 above Long.MAX_VALUE arrives here as a negative Long, and Kotlin's
 * `+` / `sumOf` wrap silently instead of trapping, so a nonsense value could quietly turn into a
 * plausible-looking total - a Max, a selection that "covers" an amount, a coinbase that looks
 * mature. These helpers treat such values as invalid instead.
 */
object UtxoMath {

    /** More than any real coin, or any real set of coins, can hold: above Kaspa's ~28.7 billion
     *  KAS supply. */
    const val MAX_SOMPI: Long = KaspaUnit.MAX_TYPED_SOMPI

    /** iOS `totalAmount()`'s error text. */
    const val INVALID_AMOUNT_MESSAGE = "Invalid UTXO data: amount overflow"

    /** A single amount a coin can really hold: not negative, not above the supply. */
    fun isValidAmount(amount: Long): Boolean = amount in 0..MAX_SOMPI

    /** The sum of [amounts], or null when any is invalid or the total passes the supply. Each
     *  step adds two values of at most [MAX_SOMPI], so the sum itself cannot overflow. */
    fun checkedTotal(amounts: Iterable<Long>): Long? {
        var total = 0L
        for (amount in amounts) {
            if (!isValidAmount(amount)) return null
            total += amount
            if (total > MAX_SOMPI) return null
        }
        return total
    }

    /** The sum of [amounts]; throws [IllegalStateException] with [INVALID_AMOUNT_MESSAGE] when
     *  it is not a real total. */
    fun total(amounts: Iterable<Long>): Long =
        checkedTotal(amounts) ?: throw IllegalStateException(INVALID_AMOUNT_MESSAGE)

    /** [runningTotal] + [amount], by the same rule as [checkedTotal]; throws on nonsense. */
    fun add(runningTotal: Long, amount: Long): Long {
        if (!isValidAmount(runningTotal) || !isValidAmount(amount)) throw IllegalStateException(INVALID_AMOUNT_MESSAGE)
        val sum = runningTotal + amount
        if (sum > MAX_SOMPI) throw IllegalStateException(INVALID_AMOUNT_MESSAGE)
        return sum
    }

    /** The total amount of these UTXOs, null if it is not a real total. */
    fun List<UtxoEntry>.checkedTotalAmount(): Long? = checkedTotal(map { it.utxoEntry.amount })

    /** The total amount of these UTXOs; throws on invalid data. */
    fun List<UtxoEntry>.totalAmount(): Long = total(map { it.utxoEntry.amount })

    /**
     * Whether a coinbase output at [blockDaaScore] is spendable at [virtualDaaScore]:
     * `blockDaaScore + maturity < virtualDaaScore`. A negative score (a uint64 past Long.MAX) or a
     * sum that overflows counts as not yet mature - a wrapped sum would otherwise come out
     * negative and read as long since mature.
     */
    fun isMatureCoinbase(blockDaaScore: Long, maturity: Long, virtualDaaScore: Long): Boolean {
        if (blockDaaScore < 0 || maturity < 0) return false
        val matureAt = try {
            Math.addExact(blockDaaScore, maturity)
        } catch (e: ArithmeticException) {
            return false
        }
        return matureAt < virtualDaaScore
    }
}
