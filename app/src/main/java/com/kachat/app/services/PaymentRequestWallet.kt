package com.kachat.app.services

import com.kachat.app.models.PaymentRequest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The wallet side of payment requests ([PaymentRequest]): the chatting address that signs a new
 * request, its signature, and which addresses are this wallet's own - so your own request pastes
 * as text and its card shows it instead of opening Send. Built at startup (KaChatApplication) so
 * [PaymentRequest.isOwn] can ask from anywhere through [shared].
 */
@Singleton
class PaymentRequestWallet @Inject constructor(
    private val walletManager: WalletManager,
) {
    init {
        instance = this
    }

    /** The active account's chatting address, or null with no wallet. */
    fun chattingAddress(): String? = runCatching { walletManager.getAddress() }.getOrNull()

    /**
     * [request] signed by the chatting address (iOS 7c71a98): whoever pays sees it is from you -
     * your name and picture - even when it asks to be paid to a spending address, which has none.
     * An account whose key can't be had still asks; the payer is told it's unsigned. Derives the
     * key, so call it off the main thread.
     */
    fun sign(request: PaymentRequest): PaymentRequest {
        val creator = chattingAddress() ?: return request
        val key = runCatching { walletManager.getPrivateKeyBytes() }.getOrNull() ?: return request
        return try {
            request.signed(creator, key) ?: request
        } finally {
            key.fill(0)
        }
    }

    private var ownAddressCache: Pair<String, Set<String>>? = null

    /**
     * The chatting address and every revealed spending address, lowercased - worked out once per
     * account and address count and kept, since listing the spending addresses can derive each of
     * them (iOS 427f19b: the request card asks on every draw).
     */
    @Synchronized
    fun ownAddresses(): Set<String> {
        val account = walletManager.getActiveAccount() ?: return emptySet()
        val wallet = account.address.lowercase()
        // both chains: the key moves when either grows, or a new quantum address would not count
        val key = "$wallet|${WalletManager.legacyMaxSpendingIndex(account)}|q${WalletManager.maxQuantumIndex(account)}"
        ownAddressCache?.let { if (it.first == key) return it.second }
        val addresses = HashSet<String>()
        addresses.add(wallet)
        runCatching { walletManager.allSpendingAddresses() }.getOrDefault(emptyList()).forEach { addresses.add(it.lowercase()) }
        ownAddressCache = key to addresses
        return addresses
    }

    companion object {
        @Volatile private var instance: PaymentRequestWallet? = null

        /** The app-wide instance once Hilt has built it (KaChatApplication does, at startup). */
        val shared: PaymentRequestWallet? get() = instance
    }
}
