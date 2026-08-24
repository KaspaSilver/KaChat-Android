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
    private val walletManager: WalletManager
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
}
