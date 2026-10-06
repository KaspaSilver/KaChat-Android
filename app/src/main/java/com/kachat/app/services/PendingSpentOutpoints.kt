package com.kachat.app.services

/**
 * Coins a send has broadcast that a UTXO fetch may still list (audit AND-015).
 *
 * [KaspaWalletEngine] keeps the same kind of record for hot-wallet sends. Cold Storage builds
 * from a raw node / REST fetch, and the REST gateway in particular lags what was just broadcast:
 * a second send started right after the first could pick the same coin, be signed on the
 * KasSigner device, and only then be rejected as a double spend. Each successful broadcast's
 * inputs are recorded here per address, and every fetch that feeds a build, a Max, a fee preview
 * or the coin-control picker is filtered through it.
 *
 * An entry lives for [ttlMs] (120 s, as [KaspaWalletEngine]'s PENDING_UTXO_TTL_MS) and is not
 * dropped early when one fetch stops listing the coin: the next fetch may come from a lagging
 * source that still does, which is exactly the case this guards. A spent coin stays spent, so
 * keeping it out for the full TTL costs nothing.
 */
internal class PendingSpentOutpoints(
    private val ttlMs: Long = TTL_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Lowercased address -> outpoint key -> when it was recorded. */
    private val byAddress = mutableMapOf<String, MutableMap<String, Long>>()

    /** [fresh] without the coins recorded as spent from [address] within the TTL. */
    @Synchronized
    fun filter(address: String, fresh: List<UtxoEntry>): List<UtxoEntry> {
        val pending = livePending(address) ?: return fresh
        return fresh.filter { outpointKey(it.outpoint) !in pending }
    }

    /** Records [outpoints] as spent from [address] by a send that was just broadcast. */
    @Synchronized
    fun record(address: String, outpoints: List<Outpoint>) {
        if (outpoints.isEmpty()) return
        val at = now()
        val pending = byAddress.getOrPut(address.trim().lowercase()) { mutableMapOf() }
        outpoints.forEach { pending[outpointKey(it)] = at }
    }

    /** How many coins at [address] are being kept out right now. */
    @Synchronized
    fun pendingCount(address: String): Int = livePending(address)?.size ?: 0

    private fun livePending(address: String): Map<String, Long>? {
        val key = address.trim().lowercase()
        val pending = byAddress[key] ?: return null
        val cutoff = now() - ttlMs
        pending.entries.removeAll { it.value < cutoff }
        if (pending.isEmpty()) {
            byAddress.remove(key)
            return null
        }
        return pending
    }

    companion object {
        const val TTL_MS = 120_000L

        private fun outpointKey(outpoint: Outpoint) = KaspaWalletEngine.outpointKey(outpoint)
    }
}
