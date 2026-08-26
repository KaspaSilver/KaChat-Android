package com.kachat.app.services

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kachat.app.util.KaspaExtendedPublicKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists imported KasSigner "kpub" watch-only accounts — deliberately in its own
 * `EncryptedSharedPreferences` file, entirely separate from [WalletManager]'s. Cold Storage is a
 * completely different trust domain from KaChat's identity/spending wallet: no mnemonic or
 * private key material is ever stored here, only public keys, and a future "wipe wallet" action
 * on the main wallet must never be able to touch (or be confused with) this store, or vice versa.
 * [WalletManager] is injected purely to read which wallet is currently active for scoping
 * accounts per wallet (see [ColdAccount.walletAddress]) — never its mnemonic/private key.
 */
@Singleton
class ColdStorageManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val walletManager: WalletManager
) {
    companion object {
        private const val SECURE_PREFS_NAME = "cold_storage_secure_prefs"
        private const val PREF_ACCOUNTS = "cold_accounts"
        private const val PREF_ADDRESS_LABELS = "cold_address_labels"
        private const val PREF_HIDDEN_ADDRESSES = "cold_hidden_addresses"
        private const val PREF_UTXO_LABELS = "cold_utxo_labels"
        private const val PREF_ADDRESS_SNAPSHOT = "cold_address_snapshot"
    }

    data class ColdAccount(
        val id: String,
        val name: String,
        val kpub: String,
        // Which spending wallet this was imported under - "" for accounts saved before this
        // field existed (see getAccounts()'s claim-on-first-load migration).
        val walletAddress: String = "",
        // Highest external-chain (chain 0) index ever derived/shown for this account — bounds
        // the Manage-style address list the same way WalletManager.Account.maxSpendingAddressIndex
        // bounds the spending-chain list. "Generate More Addresses" bumps this directly, ahead of
        // whatever the gap-limit scan itself would have stopped at.
        val maxDerivedIndex: Int = 0
    )

    /** A user-given label for one specific derived address — flat list keyed by (accountId, index) rather than nested in [ColdAccount], so labeling is independent of the gap-limit-scanned address list's own lifecycle. */
    private data class AddressLabel(val accountId: String, val index: Int, val label: String)

    /** One address a user chose to hide from the main list — same flat (accountId, index) keying as [AddressLabel]. Hiding never deletes anything; it's purely a display preference. */
    private data class HiddenAddress(val accountId: String, val index: Int)

    /** A user-given label for one specific UTXO — keyed by address (the UTXOs tab is per-address,
     *  not per-account) + `"txId:index"` outpoint key, mirroring [AddressLabel]'s flat-list shape. */
    private data class UtxoLabel(val address: String, val outpointKey: String, val label: String)

    private val gson = Gson()

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPrefs = EncryptedSharedPreferences.create(
        context,
        SECURE_PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private fun getAllAccounts(): List<ColdAccount> {
        val json = sharedPrefs.getString(PREF_ACCOUNTS, null) ?: return emptyList()
        val type = object : TypeToken<List<ColdAccount>>() {}.type
        return gson.fromJson(json, type)
    }

    private fun saveAccounts(accounts: List<ColdAccount>) {
        val json = gson.toJson(accounts)
        sharedPrefs.edit().putString(PREF_ACCOUNTS, json).apply()
    }

    /**
     * Accounts imported under whichever wallet is currently active — otherwise switching
     * accounts on this device would show one wallet's imported kpubs under another. Accounts
     * saved before this field existed (walletAddress="") are claimed for the first wallet that
     * loads them post-upgrade, then persisted immediately so no other wallet can also claim
     * them — mirrors PortfolioRepository's identical migration.
     */
    fun getAccounts(): List<ColdAccount> {
        val walletAddress = walletManager.getAddress()
        val all = getAllAccounts()
        val unclaimed = all.filter { it.walletAddress.isEmpty() }
        if (unclaimed.isNotEmpty()) {
            val claimed = all.map { if (it.walletAddress.isEmpty()) it.copy(walletAddress = walletAddress) else it }
            saveAccounts(claimed)
            return claimed.filter { it.walletAddress == walletAddress }
        }
        return all.filter { it.walletAddress == walletAddress }
    }

    /** Re-importing the same kpub under the same wallet updates the existing entry's name rather than creating a duplicate; the same kpub imported under a different wallet is a separate entry. */
    fun saveAccount(name: String, kpub: String): Result<ColdAccount> {
        if (!KaspaExtendedPublicKey.isValidKpub(kpub)) {
            return Result.failure(IllegalArgumentException("Not a valid kpub"))
        }
        val walletAddress = walletManager.getAddress()
        val all = getAllAccounts()
        val existing = all.find { it.kpub == kpub && it.walletAddress == walletAddress }
        val account = existing?.copy(name = name)
            ?: ColdAccount(id = UUID.randomUUID().toString(), name = name, kpub = kpub, walletAddress = walletAddress)
        val accounts = all.filterNot { it.kpub == kpub && it.walletAddress == walletAddress } + account
        saveAccounts(accounts)
        return Result.success(account)
    }

    fun renameAccount(id: String, newName: String) {
        saveAccounts(getAllAccounts().map { if (it.id == id) it.copy(name = newName) else it })
    }

    fun deleteAccount(id: String) {
        saveAccounts(getAllAccounts().filter { it.id != id })
        sharedPrefs.edit().remove("${PREF_ADDRESS_SNAPSHOT}_$id").apply()
    }

    // --- Address-list snapshot: opaque JSON of the last fully-loaded row list, persisted per
    // account so the detail screen paints instantly while the live refresh runs — the cold twin
    // of WalletManager's Manage Addresses snapshot (ColdStorageViewModel owns the row type and
    // its serialization; this is just the per-account keyed store).
    fun getAddressSnapshot(accountId: String): String? =
        sharedPrefs.getString("${PREF_ADDRESS_SNAPSHOT}_$accountId", null)

    fun setAddressSnapshot(accountId: String, json: String) {
        sharedPrefs.edit().putString("${PREF_ADDRESS_SNAPSHOT}_$accountId", json).apply()
    }

    fun ensureMaxDerivedIndexAtLeast(id: String, minIndex: Int) {
        saveAccounts(
            getAllAccounts().map {
                if (it.id == id && minIndex > it.maxDerivedIndex) it.copy(maxDerivedIndex = minIndex) else it
            }
        )
    }

    private fun getAllAddressLabels(): List<AddressLabel> {
        val json = sharedPrefs.getString(PREF_ADDRESS_LABELS, null) ?: return emptyList()
        val type = object : TypeToken<List<AddressLabel>>() {}.type
        return gson.fromJson(json, type)
    }

    private fun saveAddressLabels(labels: List<AddressLabel>) {
        sharedPrefs.edit().putString(PREF_ADDRESS_LABELS, gson.toJson(labels)).apply()
    }

    /** index -> label, for every labeled address under [accountId]. Unlabeled addresses just aren't present in the map. */
    fun getAddressLabels(accountId: String): Map<Int, String> =
        getAllAddressLabels().filter { it.accountId == accountId }.associate { it.index to it.label }

    /** A blank [label] clears any existing label for this address rather than storing an empty string. */
    fun setAddressLabel(accountId: String, index: Int, label: String) {
        val remaining = getAllAddressLabels().filterNot { it.accountId == accountId && it.index == index }
        val trimmed = label.trim()
        saveAddressLabels(if (trimmed.isNotEmpty()) remaining + AddressLabel(accountId, index, trimmed) else remaining)
    }

    private fun getAllUtxoLabels(): List<UtxoLabel> {
        val json = sharedPrefs.getString(PREF_UTXO_LABELS, null) ?: return emptyList()
        val type = object : TypeToken<List<UtxoLabel>>() {}.type
        return gson.fromJson(json, type)
    }

    private fun saveUtxoLabels(labels: List<UtxoLabel>) {
        sharedPrefs.edit().putString(PREF_UTXO_LABELS, gson.toJson(labels)).apply()
    }

    /** outpointKey ("txId:index") -> label, for every labeled UTXO at [address]. Unlabeled UTXOs just aren't present in the map. */
    fun getUtxoLabels(address: String): Map<String, String> =
        getAllUtxoLabels().filter { it.address == address }.associate { it.outpointKey to it.label }

    /** A blank [label] clears any existing label for this UTXO rather than storing an empty string. */
    fun setUtxoLabel(address: String, outpointKey: String, label: String) {
        val remaining = getAllUtxoLabels().filterNot { it.address == address && it.outpointKey == outpointKey }
        val trimmed = label.trim()
        saveUtxoLabels(if (trimmed.isNotEmpty()) remaining + UtxoLabel(address, outpointKey, trimmed) else remaining)
    }

    private fun getAllHiddenAddresses(): List<HiddenAddress> {
        val json = sharedPrefs.getString(PREF_HIDDEN_ADDRESSES, null) ?: return emptyList()
        val type = object : TypeToken<List<HiddenAddress>>() {}.type
        return gson.fromJson(json, type)
    }

    private fun saveHiddenAddresses(hidden: List<HiddenAddress>) {
        sharedPrefs.edit().putString(PREF_HIDDEN_ADDRESSES, gson.toJson(hidden)).apply()
    }

    /** Indices hidden under [accountId] — never deletes the address itself, just what the main list filters out. */
    fun getHiddenIndices(accountId: String): Set<Int> =
        getAllHiddenAddresses().filter { it.accountId == accountId }.map { it.index }.toSet()

    fun setAddressHidden(accountId: String, index: Int, hidden: Boolean) {
        val remaining = getAllHiddenAddresses().filterNot { it.accountId == accountId && it.index == index }
        saveHiddenAddresses(if (hidden) remaining + HiddenAddress(accountId, index) else remaining)
    }
}
