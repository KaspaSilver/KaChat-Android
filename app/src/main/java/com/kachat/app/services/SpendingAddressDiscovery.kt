package com.kachat.app.services

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recovers a re-imported mnemonic's spending-address index by gap-limit scanning — needed only
 * on wallet **import** (a mnemonic previously used with the spending-address feature on some
 * other install/before a wipe), not on every launch: a brand-new or already-locally-tracked
 * account has nothing to recover, its stored [WalletManager.Account.spendingAddressIndex] is
 * already the source of truth. Mirrors Kaspium's own gap-limit address-discovery pattern
 * (`lib/wallet_address/address_discovery/address_discovery.dart`) — sequential scan, small gap,
 * stop once enough consecutive addresses show no on-chain history at all.
 */
@Singleton
class SpendingAddressDiscovery @Inject constructor(
    private val networkService: NetworkService,
    private val walletManager: WalletManager,
    private val knsService: KnsService,
) {
    /**
     * Returns the recovered index — one past the last address with any transaction history —
     * or 0 if the spending chain has never been used at all (including on any API failure,
     * since that's the safe default a brand-new import would already start at).
     */
    suspend fun discoverIndex(gapLimit: Int = 5): Int {
        val api = networkService.kaspaRestApi.value ?: return 0
        var lastUsedIndex = -1
        var consecutiveUnused = 0
        var index = 0

        while (consecutiveUnused < gapLimit) {
            val address = try {
                walletManager.deriveSpendingAddress(index)
            } catch (e: Exception) {
                break
            }
            val everUsed = try {
                api.getTransactions(address, limit = 1).isNotEmpty()
            } catch (e: Exception) {
                Log.w("SpendingAddressDiscovery", "Lookup failed for index $index, stopping scan", e)
                break
            }
            if (everUsed) {
                // Warm the monotonic used-cache while we're here: the rebuilt install's first
                // Manage Addresses load then labels these "Used" instantly with no re-probe.
                walletManager.markAddressUsed(address)
                lastUsedIndex = index
                consecutiveUnused = 0
            } else {
                consecutiveUnused++
            }
            index++
        }

        return lastUsedIndex + 1
    }

    /** Where a scan currently is, so the UI can count up instead of showing an unmoving spinner
     *  for the time a gap-limit walk takes. Mirrors [ColdStorageAddressDiscovery.DiscoveryProgress]. */
    data class DiscoveryProgress(val checkingIndex: Int, val foundCount: Int)

    /**
     * The user-triggered "Discover Addresses" scan, matching Cold Storage's exactly
     * ([ColdStorageAddressDiscovery.discoverAddresses]) rather than [discoverIndex]'s import-time
     * recovery. Two differences that matter:
     *
     * An address is worth surfacing when it HOLDS SOMETHING - a balance, or a KNS domain - not
     * when it has transaction history. History still decides a row's "Used" badge (address reuse
     * is a privacy problem), but every address ever touched and emptied is not what the list is
     * for.
     *
     * And the gap is 20, not 5. Five was too shallow for a wallet whose funded addresses are not
     * contiguous: a six-address gap ended the scan early and everything past it was never seen.
     *
     * Returns every matching index, in ascending order.
     * Sequential on purpose - see [ColdStorageAddressDiscovery.discoverAddresses] for why
     * batching these lookups made scans slower and more brittle, not faster.
     */
    suspend fun discoverFunded(
        gapLimit: Int = 20,
        onProgress: ((DiscoveryProgress) -> Unit)? = null,
    ): List<Int> {
        val api = networkService.kaspaRestApi.value ?: return emptyList()
        val matched = mutableListOf<Int>()
        var consecutiveMisses = 0
        var index = 0

        while (consecutiveMisses < gapLimit) {
            onProgress?.invoke(DiscoveryProgress(checkingIndex = index, foundCount = matched.size))
            val address = try {
                walletManager.deriveSpendingAddress(index)
            } catch (e: Exception) {
                break
            }
            // A balance only counts when it was actually READ: a throttled request is not
            // evidence of an empty address, and treating it as one drops real addresses.
            var balanceConfirmed = true
            val balance = try {
                api.getBalance(address).balance
            } catch (e: Exception) {
                Log.w("SpendingAddressDiscovery", "Balance lookup failed for index $index", e)
                balanceConfirmed = false
                0L
            }
            // Balance first - it is already in hand, and short-circuits the KNS lookup.
            val matches = (balanceConfirmed && balance > 0) ||
                knsService.getOwnedDomains(address).isNotEmpty()
            if (matches) {
                matched.add(index)
                consecutiveMisses = 0
            } else {
                consecutiveMisses++
            }
            index++
        }

        return matched
    }
}
