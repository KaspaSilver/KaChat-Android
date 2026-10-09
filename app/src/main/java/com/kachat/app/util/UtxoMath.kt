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

    /** iOS `checkedFromNetwork()`'s error text. */
    const val ABOVE_SUPPLY_MESSAGE = "Invalid UTXO data: amount above the Kaspa supply"

    /**
     * A node's or REST API's answer as the app may use it: no coin can hold more than Kaspa's
     * whole supply, and neither can all of them together. A response that breaks that is a broken
     * or hostile peer's, refused where it's decoded (throws [IllegalStateException] with
     * [ABOVE_SUPPLY_MESSAGE]), so every plain `+` over UTXOs further on (balances, contact sums,
     * Max) stays far from overflowing (iOS 283cd28, IOS-020).
     */
    fun <T> checkedFromNetwork(utxos: List<T>, amount: (T) -> Long): List<T> {
        if (checkedTotal(utxos.map(amount)) == null) throw IllegalStateException(ABOVE_SUPPLY_MESSAGE)
        return utxos
    }

    /** [checkedFromNetwork] for the REST API's UTXOs. */
    fun List<UtxoEntry>.checkedFromNetwork(): List<UtxoEntry> = checkedFromNetwork(this) { it.utxoEntry.amount }

    /**
     * The Gson hook that applies [checkedFromNetwork] to every `List<UtxoEntry>` a Retrofit client
     * decodes (the REST API's `GET /addresses/{a}/utxos`): the answer is refused as it is read,
     * before any caller can add it up (iOS 283cd28, IOS-020). Every other type passes through.
     */
    object RestUtxoGuard : com.google.gson.TypeAdapterFactory {
        override fun <T : Any?> create(gson: com.google.gson.Gson, type: com.google.gson.reflect.TypeToken<T>): com.google.gson.TypeAdapter<T>? {
            if (!List::class.java.isAssignableFrom(type.rawType)) return null
            val parameterized = type.type as? java.lang.reflect.ParameterizedType ?: return null
            val arg = parameterized.actualTypeArguments.singleOrNull()?.let {
                if (it is java.lang.reflect.WildcardType) it.upperBounds.singleOrNull() else it
            }
            if (arg != UtxoEntry::class.java) return null
            val delegate = gson.getDelegateAdapter(this, type)
            return object : com.google.gson.TypeAdapter<T>() {
                override fun write(out: com.google.gson.stream.JsonWriter, value: T) = delegate.write(out, value)
                override fun read(reader: com.google.gson.stream.JsonReader): T {
                    val value = delegate.read(reader)
                    @Suppress("UNCHECKED_CAST")
                    val list = (value as? List<UtxoEntry>) ?: return value
                    // Gson leaves an absent field null whatever its Kotlin type: a coin with no
                    // entry is refused like one with a nonsense amount.
                    @Suppress("SENSELESS_COMPARISON")
                    val broken = list.any { it == null || it.utxoEntry == null }
                    if (broken || checkedTotal(list.map { it.utxoEntry.amount }) == null) {
                        throw com.google.gson.JsonParseException(ABOVE_SUPPLY_MESSAGE)
                    }
                    return value
                }
            }
        }
    }

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
