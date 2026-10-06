package com.kachat.app.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mainnet or testnet (testnet-10), iOS `NetworkType` + `AppSettings.networkType` (bdae4b7).
 *
 * Two values, as on iOS:
 *  - [selected] is what Settings > Connection's Testnet switch says: the choice for the next
 *    launch (iOS `PendingNetworkSwitch` / `selectedNetworkType`, e6f664f). Flipping it only
 *    records it (AppSettingsRepository.switchNetwork).
 *  - [launch] is the network the app started on (iOS `SettingsViewModel.launchNetworkType`). The
 *    node pool, the wallet address and every service - indexer, REST, push, .kachat - follow it
 *    until the app is opened again, so the switch happens at the next launch, all at once
 *    ([init], from KaChatApplication.attachBaseContext); the switch's note says so while the two
 *    differ (IOS-002 / AND-003).
 *
 * Kept in plain SharedPreferences, not the settings DataStore, because it has to be known
 * synchronously before any service starts (KaChatApplication.attachBaseContext calls [init]).
 * Unit tests that never call [init] run on mainnet.
 */
object KaspaNetwork {
    enum class Type(val raw: String, val hrp: String) {
        MAINNET("mainnet", "kaspa"),
        TESTNET("testnet", "kaspatest");

        companion object {
            fun fromRaw(raw: String?): Type = if (raw == TESTNET.raw) TESTNET else MAINNET
        }
    }

    private const val PREFS = "kachat_network"
    private const val KEY_NETWORK = "network"

    @Volatile private var prefs: android.content.SharedPreferences? = null

    @Volatile var launch: Type = Type.MAINNET
        private set

    private val _selected = MutableStateFlow(Type.MAINNET)
    val selected: StateFlow<Type> = _selected.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        // applicationContext is null while the Application is still attaching; the base context
        // reads the same prefs file.
        val p = (context.applicationContext ?: context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val stored = Type.fromRaw(p.getString(KEY_NETWORK, null))
        launch = stored
        _selected.value = stored
    }

    /** Records the choice for the next launch. The running app stays on [launch] until it is
     *  opened again; choosing [launch] again cancels a pending switch. */
    fun select(type: Type) {
        prefs?.edit()?.putString(KEY_NETWORK, type.raw)?.commit()
        _selected.value = type
    }

    /** The running network is testnet. */
    val isTestnet: Boolean get() = launch == Type.TESTNET

    /** The address prefix of the running network: "kaspa" or "kaspatest". */
    val hrp: String get() = launch.hrp

    /** By prefix, kaspa: / kaspatest:; null for an address without one. `kaspatest:` is checked
     *  first because it also starts with "kaspa". */
    fun ofAddress(address: String): Type? {
        val lower = address.trim().lowercase()
        return when {
            lower.startsWith("kaspatest:") -> Type.TESTNET
            lower.startsWith("kaspa:") -> Type.MAINNET
            else -> null
        }
    }

    /** False only for an address of the OTHER network than the running one (iOS
     *  `NetworkType.isOnActiveNetwork`). An address without a prefix counts as on network. */
    fun isOnActiveNetwork(address: String): Boolean {
        val network = ofAddress(address) ?: return true
        return network == launch
    }

    /** The same key's address with [hrp] - one key, one address per network. The address itself
     *  when it can't be decoded or already has that prefix. */
    fun reencode(address: String, hrp: String = this.hrp): String {
        val trimmed = address.trim()
        if (trimmed.substringBefore(':', "").equals(hrp, ignoreCase = true)) return trimmed
        return try {
            val (version, payload) = KaspaAddress.decode(trimmed)
            KaspaAddress.encode(hrp, version, payload)
        } catch (e: Exception) {
            trimmed
        }
    }

    /** Both encodings of one account: the address itself first, then the other network's. */
    fun accountAddressVariants(address: String): List<String> {
        val variants = mutableListOf(address)
        for (other in Type.values().map { it.hrp }) {
            val converted = reencode(address, other)
            if (converted !in variants) variants.add(converted)
        }
        return variants
    }

    /** Same key on either network (iOS `WalletManager.isSameAccount`). */
    fun isSameAccount(a: String, b: String): Boolean {
        if (a.equals(b, ignoreCase = true)) return true
        return try {
            val (va, pa) = KaspaAddress.decode(a.trim())
            val (vb, pb) = KaspaAddress.decode(b.trim())
            va == vb && pa.contentEquals(pb)
        } catch (e: Exception) {
            false
        }
    }
}
