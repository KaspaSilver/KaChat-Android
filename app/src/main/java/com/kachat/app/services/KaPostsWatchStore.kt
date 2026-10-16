package com.kachat.app.services

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * KaPosts' bell: the people this account wants a push from whenever they post (the iOS repo's
 * PUSH_EXTENSIONS.md section 3a, iOS a6bd60a `KaPostsWatchStore`). Kept per account (each account
 * is its own KaPosts identity) under the same per-wallet key iOS uses, and sent to the push
 * service with the device registration ([PushRegistrationManager] observes [watching] and
 * re-registers on every change). Follows the current account by itself.
 */
@Singleton
class KaPostsWatchStore @Inject constructor(
    @ApplicationContext context: Context,
    private val walletManager: WalletManager,
) {
    private val prefs = context.getSharedPreferences("kaposts_watch", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _watching = MutableStateFlow<Set<String>>(emptySet())

    /** The watched authors' addresses (lowercased) for the current account. */
    val watching: StateFlow<Set<String>> = _watching.asStateFlow()

    private var loadedWallet: String? = null

    init {
        scope.launch {
            walletManager.activeAddressFlow.collect { address -> reload(address) }
        }
    }

    private fun prefKey(wallet: String) = "kachat_kaposts_watch_$wallet"

    @Synchronized
    private fun reload(address: String?) {
        val wallet = address?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        loadedWallet = wallet
        _watching.value = wallet?.let { prefs.getStringSet(prefKey(it), null)?.toSet() } ?: emptySet()
    }

    private fun walletKey(): String? =
        runCatching { walletManager.getAddress() }.getOrNull()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    fun isWatching(address: String): Boolean = address.lowercase() in _watching.value

    /** Turns the bell on or off for [address]; returns whether it is now on. */
    @Synchronized
    fun toggle(address: String): Boolean {
        val wallet = walletKey() ?: return false
        if (loadedWallet != wallet) reload(wallet)
        val target = address.trim().lowercase()
        if (target.isEmpty() || target == wallet) return false
        val set = _watching.value.toMutableSet()
        val on = target !in set
        if (on) set.add(target) else set.remove(target)
        _watching.value = set
        prefs.edit().putStringSet(prefKey(wallet), set.toSet()).apply()
        return on
    }

    /**
     * The watched authors as KaPosts pubkeys (66-hex compressed; the push service compares the
     * x-only part), for the push registration - in address order, as iOS sends them.
     */
    fun watchedPubkeys(addresses: Set<String> = _watching.value): List<String> =
        addresses.sorted().mapNotNull { KaPostsService.kapostPubkeyFromAddress(it) }
}
