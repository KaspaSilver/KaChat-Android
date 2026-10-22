package com.kachat.app.services

import com.kachat.app.util.redactedForLog
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recovers a re-imported mnemonic's spending-address index — needed only on wallet **import** (a
 * mnemonic previously used with the spending-address feature on some other install/before a
 * wipe), not on every launch: a brand-new or already-locally-tracked account has nothing to
 * recover, its stored [WalletManager.Account.spendingAddressIndex] is already the source of
 * truth. The manual "Discover Addresses" sweep ([discoverFunded]) lives here too.
 */
@Singleton
class SpendingAddressDiscovery @Inject constructor(
    private val networkService: NetworkService,
    private val walletManager: WalletManager,
    /** .kas, .k and .kaspa - an address holding only a name is still found (iOS 7a5b157). */
    private val nameServices: NameServicesClient,
    private val addressActivity: AddressActivityService,
) {
    /**
     * Returns the recovered index — one past the highest address with any transaction history —
     * or 0 if the spending chain has never been used at all (including on any API failure,
     * since that's the safe default a brand-new import would already start at).
     *
     * ## Why not a small gap-limit walk (audit AND-037)
     *
     * It was one: one history lookup per address, stop after 5 unused in a row. But the app
     * itself leaves long runs of unused addresses - every Request Payment > Fresh Address
     * reserves a new index, and an unpaid request's address has no history - so five unpaid
     * requests in a row ended the scan before any later request's address. Funds paid there
     * after a restore were not counted, watched or listed, and new requests handed out addresses
     * already in old links.
     *
     * So it asks the way [discoverFunded] does: [AddressActivityService.lastActivity] answers "was
     * this ever used" for a [DEEP_SCAN_FLOOR]-address window in a few bulk requests, whatever the
     * gaps. A window whose last [IMPORT_TAIL_GAP] addresses saw activity is followed by the next
     * one, up to [MAX_SCAN_INDEX]. When the REST host doesn't serve that route, it falls back to a
     * history walk with a gap of [IMPORT_FALLBACK_GAP], plus the batched balance sweep over the
     * floor, so a funded address past any gap is still covered.
     *
     * Derived for [account], the restored account taken when the restore started (iOS e571f45,
     * IOS-107) - not whichever account is active by the time a window is derived.
     */
    suspend fun discoverIndex(account: WalletManager.SpendingSweepAccount): Int {
        val api = networkService.kaspaRestApi.value ?: return 0
        var lastUsedIndex = -1
        var windowStart = 0
        var activityKnown = true

        while (windowStart < MAX_SCAN_INDEX) {
            val indices = (windowStart until minOf(windowStart + DEEP_SCAN_FLOOR, MAX_SCAN_INDEX)).toList()
            val window = deriveOffMain(indices, account)
            if (window.isEmpty()) break
            val activity = addressActivity.lastActivity(indices.mapNotNull { window[it] })
            if (activity == null) {
                activityKnown = false
                break
            }
            for (i in indices) {
                val address = window[i] ?: continue
                if (activity.containsKey(address)) {
                    // Warm the monotonic used-cache while we're here: the rebuilt install's first
                    // Manage Addresses load then labels these "Used" instantly with no re-probe.
                    walletManager.markAddressUsed(address)
                    lastUsedIndex = maxOf(lastUsedIndex, i)
                }
            }
            val windowEnd = indices.last()
            // Something near the end of this window: the chain may go on past it.
            if (lastUsedIndex < windowEnd - IMPORT_TAIL_GAP + 1) break
            windowStart = windowEnd + 1
        }
        if (activityKnown) return lastUsedIndex + 1

        Log.w("SpendingAddressDiscovery", "Bulk activity unavailable, using the history walk and the batched balance sweep")
        lastUsedIndex = -1
        val floor = deriveOffMain((0 until DEEP_SCAN_FLOOR).toList(), account)
        var consecutiveUnused = 0
        var index = 0
        while (consecutiveUnused < IMPORT_FALLBACK_GAP && index < MAX_SCAN_INDEX) {
            val address = floor[index] ?: deriveOffMain(listOf(index), account)[index] ?: break
            val everUsed = try {
                api.getTransactions(address, limit = 1).isNotEmpty()
            } catch (e: Exception) {
                Log.w("SpendingAddressDiscovery", "Lookup failed for index $index, stopping scan", e)
                break
            }
            if (everUsed) {
                walletManager.markAddressUsed(address)
                lastUsedIndex = index
                consecutiveUnused = 0
            } else {
                consecutiveUnused++
            }
            index++
        }

        // A funded address past the walk's gap: the batched balances, a handful of requests over
        // the whole floor (funded means used).
        var batchStart = 0
        while (batchStart < DEEP_SCAN_FLOOR) {
            val batch = (batchStart until minOf(batchStart + BATCH_SIZE, DEEP_SCAN_FLOOR)).toList()
            val addresses = batch.mapNotNull { i -> floor[i]?.let { i to it } }.toMap()
            if (addresses.isEmpty()) break
            val balances = fetchBalances(addresses.values.toList()) ?: break
            for (i in batch) {
                val address = addresses[i] ?: continue
                if ((balances[address] ?: 0L) > 0L) {
                    walletManager.markAddressUsed(address)
                    lastUsedIndex = maxOf(lastUsedIndex, i)
                }
            }
            batchStart += batch.size
        }

        return lastUsedIndex + 1
    }

    /** Address derivation is CPU work (and a seed derivation when nothing is cached yet) - kept
     *  off the main thread the import's coroutine runs on. Always [account]'s own chains, whichever
     *  account is active (IOS-107). Empty when it fails. */
    private suspend fun deriveOffMain(indices: List<Int>, account: WalletManager.SpendingSweepAccount): Map<Int, String> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            try {
                walletManager.deriveSpendingAddresses(indices, account)
            } catch (e: Exception) {
                Log.w("SpendingAddressDiscovery", "Could not derive spending addresses", e)
                emptyMap()
            }
        }

    /** Where a scan currently is, so the UI can count up instead of showing an unmoving spinner.
     *  Mirrors [ColdStorageAddressDiscovery.DiscoveryProgress]. */
    data class DiscoveryProgress(val checkingIndex: Int, val foundCount: Int)

    companion object {
        /** Swept whatever the gaps, so a run of empty addresses can never end the scan early. */
        const val DEEP_SCAN_FLOOR = 1000
        /** Hard stop, so the scan always terminates. */
        const val MAX_SCAN_INDEX = 5000
        /** Addresses per bulk balances request. */
        const val BATCH_SIZE = 100
        /** KNS has no bulk endpoint, so domain ownership is only probed this far in. */
        const val KNS_PROBE_DEPTH = 200
        /** Import recovery: activity this close to a window's end looks at the next window too. */
        const val IMPORT_TAIL_GAP = 60
        /** Import recovery's history walk, used only when the bulk activity route is unavailable:
         *  the usual BIP44 gap (the batched balance sweep beside it covers funded addresses past
         *  any longer run of unpaid Fresh Address requests). */
        const val IMPORT_FALLBACK_GAP = 20
        /** How many quantum slots one step of the restore sweep looks at (iOS `quantumScanFloor`).
         *  The sweep keeps stepping while a window turns up any activity (IOS-101 / AND-049: a
         *  fixed 200 missed funds past slot 199), up to [QUANTUM_SCAN_LIMIT]. */
        const val QUANTUM_SCAN_FLOOR = 200
        /** Where the quantum sweep stops whatever it finds (iOS `quantumScanLimit`). */
        const val QUANTUM_SCAN_LIMIT = 10_000

        /** What the quantum sweep found: the funded slots (public index) and the highest slot
         *  (chain index, -1 = none) that ever saw activity, funded or not. */
        internal data class QuantumSweep(val funded: List<Int>, val highestUsed: Int)

        /**
         * The quantum chain's restore sweep (iOS da2c8e2 `discoverQuantumAddresses`, IOS-101 /
         * AND-049): [QUANTUM_SCAN_FLOOR]-slot windows from [base], stepping on while a window
         * shows any activity, up to [QUANTUM_SCAN_LIMIT]. Activity decides how far the chain
         * reaches; funds decide what is shown. Without the bulk [activity] answer (a REST server
         * that doesn't serve it, null) only the first window's [balances] are read, and the sweep
         * stops there as before. A window that can't be derived ends it.
         *
         * The sweep belongs to the account it started for (iOS e571f45, IOS-107, owner's
         * choice): [derive] is that account's own chain, so another account becoming active
         * meanwhile changes nothing and the sweep goes on. [stillSaved] says whether the account
         * is still saved on this phone; it is asked before and after every window's derivation
         * and once more at the end, and only once the account has been REMOVED does the sweep
         * stop and return null, its caller writing nothing.
         */
        internal suspend fun sweepQuantumWindows(
            base: Int,
            derive: suspend (List<Int>) -> Map<Int, String>,
            activity: suspend (List<String>) -> Map<String, Long>?,
            balances: suspend (List<String>) -> Map<String, Long>?,
            stillSaved: () -> Boolean = { true },
        ): QuantumSweep? {
            var start = 0
            val funded = ArrayList<Int>()
            var highestUsed = -1
            while (start < QUANTUM_SCAN_LIMIT) {
                if (!stillSaved()) return null
                val indices = (base + start until base + start + QUANTUM_SCAN_FLOOR).toList()
                val derived = derive(indices)
                if (!stillSaved()) return null
                val ordered = indices.mapNotNull { i -> derived[i]?.let { i to it } }
                if (ordered.isEmpty()) break
                val seen = activity(ordered.map { it.second })
                val touched = seen?.let { a -> ordered.filter { a.containsKey(it.second) } } ?: ordered
                if (seen != null) {
                    touched.maxOfOrNull { it.first }?.let { highestUsed = maxOf(highestUsed, it - base) }
                }
                if (touched.isNotEmpty()) {
                    balances(touched.map { it.second })?.let { read ->
                        funded += touched.filter { (read[it.second] ?: 0L) > 0L }.map { it.first }
                    }
                }
                if (seen == null || touched.isEmpty()) break
                start += QUANTUM_SCAN_FLOOR
            }
            if (!stillSaved()) return null
            funded.maxOrNull()?.let { highestUsed = maxOf(highestUsed, it - base) }
            return QuantumSweep(funded, highestUsed)
        }

        /**
         * The quantum restore sweep end to end ([discoverQuantumAddresses]), for [owner]: the
         * windows ([sweepQuantumWindows]), then the writes - the empty slots the new bound sweeps
         * in start hidden, every funded one is un-hidden, and the bound is raised - every one
         * keyed to [owner], whichever account is active by then (iOS e571f45, IOS-107).
         * [previousMax] is [owner]'s quantum bound as it stands at the write (null = the account
         * is gone). Nothing is written, and 0 returned, once [stillSaved] says the account was
         * removed. Returns how many funded slots it found.
         */
        internal suspend fun runQuantumDiscovery(
            owner: String,
            base: Int,
            derive: suspend (List<Int>) -> Map<Int, String>,
            activity: suspend (List<String>) -> Map<String, Long>?,
            balances: suspend (List<String>) -> Map<String, Long>?,
            stillSaved: () -> Boolean,
            previousMax: () -> Int?,
            setHidden: (owner: String, index: Int, hidden: Boolean) -> Unit,
            raiseBound: (owner: String, quantumIndex: Int) -> Unit,
            onDropped: () -> Unit = {},
        ): Int {
            if (!stillSaved()) return 0
            val sweep = sweepQuantumWindows(base, derive, activity, balances, stillSaved) ?: return 0.also { onDropped() }
            if (sweep.highestUsed < 0 || !stillSaved()) return 0
            val matched = sweep.funded.toSet()

            // read now, after the sweep: the owner's bound as it stands
            val before = previousMax() ?: return 0
            if (sweep.highestUsed > before) {
                for (index in (base + before + 1)..(base + sweep.highestUsed)) {
                    if (index !in matched) setHidden(owner, index, true)
                }
                raiseBound(owner, sweep.highestUsed)
            }
            matched.forEach { setHidden(owner, it, false) }
            return matched.size
        }
    }

    /**
     * The user-triggered "Discover Addresses" scan.
     *
     * An address is worth surfacing when it HOLDS SOMETHING - a balance, or a KNS domain - not
     * when it has transaction history. History still decides a row's "Used" badge (address reuse
     * is a privacy problem), but every address ever touched and emptied is not what the list is
     * for.
     *
     * ## Why this is not a gap-limit walk any more
     *
     * It was: one balance request per address, stop after 20 consecutive empties. That design
     * cannot find what it is asked to find. A wallet with a balance at index 291 and any run of
     * 20 empty addresses before it - which is ordinary, since a spending chain reveals addresses
     * it never funds - ended the scan in the twenties and reported nothing, every time, with no
     * way for the user to make it look further.
     *
     * So it sweeps a fixed floor of [DEEP_SCAN_FLOOR] indices whatever the gaps, in batches of
     * [BATCH_SIZE] against the node's bulk balances endpoint. That is a handful of requests
     * rather than hundreds of round trips, so it is both thorough and faster than the walk it
     * replaces. Past the floor it keeps going while something is still turning up, and stops for
     * good at [MAX_SCAN_INDEX] so it always terminates.
     *
     * KNS ownership is still checked, but only within [KNS_PROBE_DEPTH] and only for addresses
     * with no balance: there is no bulk endpoint for it, and a domain held on a spending address
     * past index 200 is not a case worth a thousand extra requests.
     *
     * Returns every matching index, in ascending order.
     *
     * The sweep belongs to the account it started for, [account] (iOS e571f45, IOS-107, owner's
     * choice): every derivation is that account's own chain, so it carries on through an account
     * switch, and only once the account has been removed does it stop, empty-handed. The caller
     * writes the matches to the same account.
     */
    suspend fun discoverFunded(
        account: WalletManager.SpendingSweepAccount,
        gapLimit: Int = 60,
        onProgress: ((DiscoveryProgress) -> Unit)? = null,
    ): List<Int> {
        fun stillSaved() = walletManager.sweepAccountStillSaved(account)
        if (!stillSaved()) return emptyList()
        val api = networkService.kaspaRestApi.value ?: return emptyList()
        val matched = sortedSetOf<Int>()
        var index = 0
        var consecutiveMisses = 0

        // The balances were already batched; KNS was not - it was asked about the first two
        // hundred addresses one at a time, at roughly a third of a second each, which is what made
        // this take the better part of a minute. `AddressActivityService` answers "was this ever
        // used" for the whole window at once, so balances AND KNS are asked only about the handful
        // the chain says were touched. Neither can sit on an address never touched. It also
        // removes the gap limit, which is what let the funded address at index 291 go unfound.
        val windowIndices = (0 until minOf(DEEP_SCAN_FLOOR, MAX_SCAN_INDEX)).toList()
        val window = try {
            walletManager.deriveSpendingAddresses(windowIndices, account)
        } catch (e: Exception) {
            Log.w("SpendingAddressDiscovery", "Could not derive the scan window", e)
            emptyMap()
        }
        if (!stillSaved()) return emptyList()
        val ordered = windowIndices.mapNotNull { i -> window[i]?.let { i to it } }
        onProgress?.invoke(DiscoveryProgress(checkingIndex = 0, foundCount = 0))

        val activity = if (ordered.isEmpty()) emptyMap() else addressActivity.lastActivity(ordered.map { it.second })
        if (activity != null) {
            val touched = ordered.filter { activity.containsKey(it.second) }
            val balances = if (touched.isEmpty()) emptyMap() else (fetchBalances(touched.map { it.second }) ?: emptyMap())
            onProgress?.invoke(DiscoveryProgress(checkingIndex = touched.lastOrNull()?.first ?: 0, foundCount = 0))

            val knsCandidates = touched.filter { (i, address) ->
                i < KNS_PROBE_DEPTH && (balances[address] ?: 0L) <= 0L
            }
            val domainOwners = if (knsCandidates.isEmpty()) emptySet() else coroutineScope {
                knsCandidates.map { (_, address) ->
                    async {
                        val owns = nameServices.ownsAnyName(address)
                        if (owns) address else null
                    }
                }.awaitAll().filterNotNull().toSet()
            }

            for ((i, address) in touched) {
                if ((balances[address] ?: 0L) > 0L || address in domainOwners) matched.add(i)
            }
            index = ordered.size
        } else {
            // The configured REST host does not serve addresses/active, or could not be reached.
            // Fall back to the batched sweep this replaced.
            Log.w("SpendingAddressDiscovery", "Bulk activity unavailable, using the batched sweep")
            while (index < MAX_SCAN_INDEX) {
                // Past the floor, stop once nothing has turned up for a long stretch.
                if (index >= DEEP_SCAN_FLOOR && consecutiveMisses >= gapLimit) break

                val batch = (index until minOf(index + BATCH_SIZE, MAX_SCAN_INDEX)).toList()
                onProgress?.invoke(DiscoveryProgress(checkingIndex = index, foundCount = matched.size))

                if (!stillSaved()) return emptyList()
                val addresses = try {
                    walletManager.deriveSpendingAddresses(batch, account)
                } catch (e: Exception) {
                    Log.w("SpendingAddressDiscovery", "Could not derive batch at $index, stopping scan", e)
                    break
                }
                if (!stillSaved()) return emptyList()
                if (addresses.isEmpty()) break

                val balances = fetchBalances(addresses.values.toList())
                if (balances == null) {
                    Log.w("SpendingAddressDiscovery", "Balances unavailable at $index, stopping scan")
                    break
                }

                for (i in batch) {
                    val address = addresses[i] ?: continue
                    val funded = (balances[address] ?: 0L) > 0L
                    val matches = funded ||
                        (i < KNS_PROBE_DEPTH && nameServices.ownsAnyName(address))
                    if (matches) {
                        matched.add(i)
                        consecutiveMisses = 0
                    } else {
                        consecutiveMisses++
                    }
                }
                index += batch.size
            }
        }

        if (!stillSaved()) return emptyList()
        onProgress?.invoke(DiscoveryProgress(checkingIndex = index, foundCount = matched.size))
        return matched.toList()
    }

    /**
     * The quantum chain's restore sweep (iOS `discoverQuantumAddresses`, da2c8e2 / IOS-101 /
     * AND-049): funded quantum-safe slots are shown again, and the chain's end is set past every
     * slot that EVER saw activity - not only funded ones - so a restored wallet never hands out
     * an already-used (possibly key-revealed) slot as fresh change or a fresh receive address.
     * The sweep steps in [QUANTUM_SCAN_FLOOR]-slot windows while a window shows activity, up to
     * [QUANTUM_SCAN_LIMIT] ([sweepQuantumWindows]). The empty slots the new bound sweeps in start
     * hidden, and every funded one is un-hidden. Returns how many funded slots it found.
     *
     * Same account rule as the legacy sweep (iOS e571f45, IOS-107): it belongs to [account],
     * derives that account's own quantum chain and writes the hidden set and the bound to it even
     * if another account is active by then ([runQuantumDiscovery]); dropped only if the account
     * was removed.
     */
    suspend fun discoverQuantumAddresses(account: WalletManager.SpendingSweepAccount): Int =
        runQuantumDiscovery(
            owner = account.owner,
            base = WalletManager.QUANTUM_INDEX_BASE,
            derive = { deriveOffMain(it, account) },
            activity = { addressActivity.lastActivity(it) },
            balances = { fetchBalances(it) },
            stillSaved = { walletManager.sweepAccountStillSaved(account) },
            previousMax = { walletManager.sweepAccountRecord(account)?.let { WalletManager.maxQuantumIndex(it) } },
            setHidden = walletManager::setSpendingAddressHidden,
            raiseBound = walletManager::ensureMaxQuantumAddressIndexAtLeast,
            onDropped = { Log.i("SpendingAddressDiscovery", "Quantum discovery dropped: its account was removed during the sweep") },
        )

    /**
     * Balances for a whole batch in one request, falling back to a paced per-address sweep when
     * the endpoint is unavailable. Mirrors [ColdStorageAddressDiscovery.fetchBalances]. Null
     * means "could not be read", which is not the same as "all empty" and must never be treated
     * as such.
     */
    private suspend fun fetchBalances(addresses: List<String>): Map<String, Long>? {
        if (addresses.isEmpty()) return emptyMap()
        val api = networkService.kaspaRestApi.value ?: return null
        return try {
            api.getBalances(BalancesRequest(addresses)).associate { it.address to it.balance }
        } catch (e: Exception) {
            Log.w("SpendingAddressDiscovery", "Batched balances unavailable, falling back per address", e)
            val out = mutableMapOf<String, Long>()
            for (address in addresses) {
                val balance = try { api.getBalance(address).balance } catch (e: Exception) { null }
                if (balance != null) out[address] = balance
            }
            if (out.isEmpty()) null else out
        }
    }
}
