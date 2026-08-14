package com.kachat.app.services

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kachat.app.util.KaspaAddress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.HDKeyDerivation
import org.bitcoinj.crypto.MnemonicCode
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WalletManager — secure key lifecycle management.
 */
@Singleton
class WalletManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "kachat_wallet_key"
        private const val SECURE_PREFS_NAME = "kachat_secure_prefs"
        private const val PREF_ACCOUNTS = "accounts"
        private const val PREF_ACTIVE_ADDRESS = "active_address"
        private const val PREF_HIDDEN_SPENDING_ADDRESSES = "hidden_spending_addresses"
        private const val PREF_SPENDING_ADDRESS_LABELS = "spending_address_labels"
        private const val PREF_SPENDING_UTXO_LABELS = "spending_utxo_labels"

        // bitcoinj's own `MnemonicCode.INSTANCE` static initializer loads its wordlist via
        // `Class.getResourceAsStream`, which is documented by bitcoinj itself as "Won't work on
        // Android" — it fails silently there, leaving INSTANCE null and crashing every wallet
        // create/import with an NPE. Bundle the identical wordlist bitcoinj ships internally as
        // an Android asset and initialize INSTANCE from it manually instead.
        private const val WORDLIST_ASSET_NAME = "bip39-wordlist-english.txt"
        private const val WORDLIST_SHA256 = "ad90bf3beb7b0eb7e5acd74727dc0da96e0a280a258354e7293fb7e211ac03db"
    }

    init {
        if (MnemonicCode.INSTANCE == null) {
            MnemonicCode.INSTANCE = context.assets.open(WORDLIST_ASSET_NAME).use {
                MnemonicCode(it, WORDLIST_SHA256)
            }
        }
    }

    data class Account(
        val name: String,
        val address: String,
        val mnemonic: String,
        // Gson deserializes old-shape stored JSON (from before this field existed) with this
        // defaulted to 0 — verified in WalletManagerTest's Gson round-trip test, since every
        // existing on-device account depends on that being true. See deriveSpendingAddress.
        val spendingAddressIndex: Int = 0,
        // Highest spending-chain index the Manage Addresses screen has ever generated/shown —
        // distinct from [spendingAddressIndex] (which is the address "Pay in Kaspa" currently
        // sources from). Generating a new address raises this without changing which one is
        // active; same Gson zero-default behavior as spendingAddressIndex above for old JSON.
        val maxSpendingAddressIndex: Int = 0,
        // Optional BIP39 passphrase (the "25th word"). Combined with the mnemonic during seed
        // derivation (see [deriveKey]) to unlock a distinct, hidden account. Gson defaults this to
        // "" for accounts stored before this field existed (empty = no passphrase = identical
        // derivation to before), so no migration is needed — same mechanism as the two indices
        // above. Persisted inside the already-encrypted prefs, so it rides the Keystore-backed
        // encryption like the mnemonic itself.
        val passphrase: String = ""
    )

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

    /**
     * Returns true if at least one wallet has been created/imported.
     */
    fun hasWallet(): Boolean {
        return getAccounts().isNotEmpty()
    }

    private fun getAccounts(): List<Account> {
        val json = sharedPrefs.getString(PREF_ACCOUNTS, null) ?: return emptyList()
        val type = object : TypeToken<List<Account>>() {}.type
        return gson.fromJson(json, type)
    }

    private fun saveAccounts(accounts: List<Account>) {
        val json = gson.toJson(accounts)
        sharedPrefs.edit().putString(PREF_ACCOUNTS, json).apply()
    }

    /**
     * The active wallet's address, reactive to every account switch/create/import/delete —
     * ChatRepository re-scopes its contact/message queries off this so switching accounts
     * doesn't require recreating any ViewModel, and (critically) so chat data from one
     * account never leaks into another's view just because the underlying Flow was built
     * once and never re-subscribed.
     */
    private val _activeAddress = MutableStateFlow(computeActiveAddress())
    val activeAddressFlow: StateFlow<String?> = _activeAddress.asStateFlow()

    private fun computeActiveAddress(): String? = getActiveAccount()?.address

    private fun refreshActiveAddressFlow() {
        _activeAddress.value = computeActiveAddress()
    }

    fun setActiveAccount(address: String) {
        sharedPrefs.edit().putString(PREF_ACTIVE_ADDRESS, address).apply()
        refreshActiveAddressFlow()
    }

    fun getActiveAccount(): Account? {
        val address = sharedPrefs.getString(PREF_ACTIVE_ADDRESS, null) ?: return getAccounts().firstOrNull()
        return getAccounts().find { it.address == address }
    }

    fun getAllAccounts(): List<Account> = getAccounts()

    /** One spending-chain address a user chose to hide from Manage Addresses — flat (walletAddress, index) keying, same pattern as Cold Storage's [ColdStorageManager] hidden addresses. Hiding never deletes anything; it's purely a display preference. */
    private data class HiddenSpendingAddress(val walletAddress: String, val index: Int)

    private fun getAllHiddenSpendingAddresses(): List<HiddenSpendingAddress> {
        val json = sharedPrefs.getString(PREF_HIDDEN_SPENDING_ADDRESSES, null) ?: return emptyList()
        val type = object : TypeToken<List<HiddenSpendingAddress>>() {}.type
        return gson.fromJson(json, type)
    }

    private fun saveHiddenSpendingAddresses(hidden: List<HiddenSpendingAddress>) {
        sharedPrefs.edit().putString(PREF_HIDDEN_SPENDING_ADDRESSES, gson.toJson(hidden)).apply()
    }

    /** Indices hidden under [walletAddress] — never deletes the address itself, just what Manage Addresses filters out. */
    fun getHiddenSpendingIndices(walletAddress: String): Set<Int> =
        getAllHiddenSpendingAddresses().filter { it.walletAddress == walletAddress }.map { it.index }.toSet()

    fun setSpendingAddressHidden(walletAddress: String, index: Int, hidden: Boolean) {
        val remaining = getAllHiddenSpendingAddresses().filterNot { it.walletAddress == walletAddress && it.index == index }
        saveHiddenSpendingAddresses(if (hidden) remaining + HiddenSpendingAddress(walletAddress, index) else remaining)
    }

    /** A user-given nickname for one spending-chain address, shown in Manage Addresses in place of the default "Address #N" — same flat (walletAddress, index) keying as [HiddenSpendingAddress]. */
    private data class SpendingAddressLabel(val walletAddress: String, val index: Int, val label: String)

    private fun getAllSpendingAddressLabels(): List<SpendingAddressLabel> {
        val json = sharedPrefs.getString(PREF_SPENDING_ADDRESS_LABELS, null) ?: return emptyList()
        val type = object : TypeToken<List<SpendingAddressLabel>>() {}.type
        return gson.fromJson(json, type)
    }

    private fun saveSpendingAddressLabels(labels: List<SpendingAddressLabel>) {
        sharedPrefs.edit().putString(PREF_SPENDING_ADDRESS_LABELS, gson.toJson(labels)).apply()
    }

    /** Labels by index under [walletAddress] — indices with no custom label are simply absent. */
    fun getSpendingAddressLabels(walletAddress: String): Map<Int, String> =
        getAllSpendingAddressLabels().filter { it.walletAddress == walletAddress }.associate { it.index to it.label }

    fun setSpendingAddressLabel(walletAddress: String, index: Int, label: String?) {
        val remaining = getAllSpendingAddressLabels().filterNot { it.walletAddress == walletAddress && it.index == index }
        saveSpendingAddressLabels(
            if (!label.isNullOrBlank()) remaining + SpendingAddressLabel(walletAddress, index, label.trim()) else remaining
        )
    }

    /**
     * A user-given nickname for one specific UTXO at a spending address, keyed by address +
     * "txId:index" outpoint key — mirrors [ColdStorageManager]'s identical per-UTXO label scheme
     * (see that class's own copy of this pattern) so a spending address's UTXOs tab can be named
     * the same way Cold Storage's already can. Keyed by address alone (no walletAddress), since
     * a derived address string is already globally unique — same reasoning as
     * [ColdStorageManager]'s version, which needs no account-scoping either.
     */
    private data class SpendingUtxoLabel(val address: String, val outpointKey: String, val label: String)

    private fun getAllSpendingUtxoLabels(): List<SpendingUtxoLabel> {
        val json = sharedPrefs.getString(PREF_SPENDING_UTXO_LABELS, null) ?: return emptyList()
        val type = object : TypeToken<List<SpendingUtxoLabel>>() {}.type
        return gson.fromJson(json, type)
    }

    private fun saveSpendingUtxoLabels(labels: List<SpendingUtxoLabel>) {
        sharedPrefs.edit().putString(PREF_SPENDING_UTXO_LABELS, gson.toJson(labels)).apply()
    }

    fun getSpendingUtxoLabels(address: String): Map<String, String> =
        getAllSpendingUtxoLabels().filter { it.address == address }.associate { it.outpointKey to it.label }

    fun setSpendingUtxoLabel(address: String, outpointKey: String, label: String?) {
        val remaining = getAllSpendingUtxoLabels().filterNot { it.address == address && it.outpointKey == outpointKey }
        saveSpendingUtxoLabels(
            if (!label.isNullOrBlank()) remaining + SpendingUtxoLabel(address, outpointKey, label.trim()) else remaining
        )
    }

    /** Hex-encoded private key for one spending-chain address - see [getSpendingPrivateKeyBytes]. */
    fun getSpendingPrivateKeyHex(index: Int): String =
        getSpendingPrivateKeyBytes(index).joinToString("") { "%02x".format(it) }

    /**
     * Generates a fresh BIP39 mnemonic WITHOUT deriving keys or persisting anything — the wallet
     * is committed later via [commitCreatedWallet], once the user has backed up the phrase and
     * chosen whether to add a passphrase. Deferring derivation lets the optional BIP39 passphrase
     * (which changes the derived address) be known before we ever derive or save, so there is no
     * throwaway account to migrate afterwards.
     */
    fun generateMnemonic(wordCount: Int = 12): List<String> {
        val entropySize = if (wordCount == 24) 32 else 16
        val entropy = ByteArray(entropySize)
        SecureRandom().nextBytes(entropy)
        return MnemonicCode.INSTANCE.toMnemonic(entropy)
    }

    /**
     * Commits a wallet generated by [generateMnemonic]: derives the address using the optional
     * BIP39 [passphrase], persists the account (with the passphrase), and makes it active. Pass
     * "" for no passphrase.
     */
    fun commitCreatedWallet(name: String, mnemonic: List<String>, passphrase: String = "") {
        val address = deriveAddress(mnemonic, passphrase)
        val accounts = getAccounts().toMutableList()
        accounts.add(Account(name, address, mnemonic.joinToString(" "), passphrase = passphrase))
        saveAccounts(accounts)
        setActiveAccount(address)
    }

    /**
     * Generate + commit in one shot with no passphrase. Retained for any non-interactive caller;
     * the onboarding UI uses [generateMnemonic]/[commitCreatedWallet] separately so it can insert
     * the passphrase step in between.
     */
    fun createWallet(name: String, wordCount: Int = 12): List<String> {
        val words = generateMnemonic(wordCount)
        commitCreatedWallet(name, words, "")
        return words
    }

    /** BIP39 checksum/wordlist validity — true if [mnemonic] is a well-formed phrase. */
    fun isValidMnemonic(mnemonic: List<String>): Boolean = try {
        MnemonicCode.INSTANCE.check(mnemonic)
        true
    } catch (e: Exception) {
        false
    }

    /** The BIP39 English wordlist (2048 words), for the in-app import keyboard's autocomplete. */
    fun bip39WordList(): List<String> = MnemonicCode.INSTANCE.wordList

    /** True if [word] is an exact BIP39 English word. */
    fun isValidMnemonicWord(word: String): Boolean =
        MnemonicCode.INSTANCE.wordList.contains(word.lowercase())

    /**
     * Import an existing wallet from a BIP39 mnemonic phrase. Throws [org.bitcoinj.crypto.MnemonicException]
     * if the phrase's checksum/wordlist is invalid — the caller is expected to catch this and show
     * the user an error, not let it crash silently.
     *
     * Re-importing a mnemonic that's already saved overwrites that entry's name and moves it to
     * the top rather than creating a duplicate, matching iOS's `updateSavedAccounts` behavior
     * (`WalletManager.swift:501-509`: remove any existing entry with the same address, then
     * re-insert at index 0). Carries over the existing entry's `spendingAddressIndex`/
     * `maxSpendingAddressIndex` rather than resetting them to their 0 defaults - this used to
     * silently reset the "Manage Addresses" state (which spending address is primary, how many
     * had been generated) to just address #0 any time the same account's seed phrase was
     * re-imported, matching a bug found and fixed on iOS (`WalletManager.importWallet` there had
     * the same "reconstruct a bare wallet, overwrite the real record" shape).
     */
    fun importWallet(mnemonic: List<String>, name: String, passphrase: String = "") {
        MnemonicCode.INSTANCE.check(mnemonic)
        // Derive with the passphrase — a different (or empty) passphrase yields a different address
        // and therefore a different account entry, which is exactly the BIP39 hidden-wallet model.
        val address = deriveAddress(mnemonic, passphrase)
        val existing = getAccounts().firstOrNull { it.address == address }
        val accounts = getAccounts().filter { it.address != address }.toMutableList()
        accounts.add(
            0,
            Account(
                name = name,
                address = address,
                mnemonic = mnemonic.joinToString(" "),
                spendingAddressIndex = existing?.spendingAddressIndex ?: 0,
                maxSpendingAddressIndex = existing?.maxSpendingAddressIndex ?: 0,
                passphrase = passphrase
            )
        )
        saveAccounts(accounts)
        setActiveAccount(address)
    }

    /**
     * Wipes all wallets from the device.
     */
    fun wipe() {
        sharedPrefs.edit().clear().apply()
        refreshActiveAddressFlow()
    }

    /**
     * Deletes a specific account.
     */
    fun deleteAccount(address: String) {
        val accounts = getAccounts().filter { it.address != address }
        saveAccounts(accounts)
        if (sharedPrefs.getString(PREF_ACTIVE_ADDRESS, null) == address) {
            sharedPrefs.edit().remove(PREF_ACTIVE_ADDRESS).apply()
        }
        refreshActiveAddressFlow()
    }

    /**
     * Shared HD path walk — `m/44'/111111'/{accountIndex}'/0/{addressIndex}`. The identity
     * address/key (used everywhere except spending) is `accountIndex=0, addressIndex=0`, always.
     * The spending-address chain lives at `accountIndex=1` — a distinct hardened branch, so it
     * can never collide with the identity path no matter how far its own addressIndex advances.
     */
    private fun deriveKey(mnemonic: List<String>, accountIndex: Int = 0, addressIndex: Int = 0, passphrase: String = ""): DeterministicKey {
        // The optional BIP39 passphrase feeds bitcoinj's PBKDF2 seed derivation. Empty string =
        // no passphrase = the account's historical derivation. Every identity/spending/private-key
        // call funnels through here, so this one parameter makes the whole account passphrase-aware.
        val seed = MnemonicCode.toSeed(mnemonic, passphrase)
        val masterKey = HDKeyDerivation.createMasterPrivateKey(seed)

        val key44h = HDKeyDerivation.deriveChildKey(masterKey, ChildNumber(44, true))
        val keyKaspaH = HDKeyDerivation.deriveChildKey(key44h, ChildNumber(111111, true))
        val keyAccountH = HDKeyDerivation.deriveChildKey(keyKaspaH, ChildNumber(accountIndex, true))
        val keyChain0 = HDKeyDerivation.deriveChildKey(keyAccountH, ChildNumber(0, false))
        return HDKeyDerivation.deriveChildKey(keyChain0, ChildNumber(addressIndex, false))
    }

    private fun addressFromKey(key: DeterministicKey): String {
        val pubKey = key.pubKey
        val xOnlyPubKey = if (pubKey.size == 33) pubKey.sliceArray(1..32) else pubKey
        return KaspaAddress.encode("kaspa", 0x00, xOnlyPubKey)
    }

    private fun deriveAddress(mnemonic: List<String>, passphrase: String = ""): String =
        addressFromKey(deriveKey(mnemonic, passphrase = passphrase))

    /** The active account's BIP39 passphrase (empty when it has none), used to re-derive its keys. */
    private fun activePassphrase(): String = getActiveAccount()?.passphrase ?: ""

    /**
     * Returns the primary Kaspa address for the active wallet.
     */
    fun getAddress(): String {
        return getActiveAccount()?.address ?: throw IllegalStateException("No active account")
    }

    fun getAccountName(): String {
        return getActiveAccount()?.name ?: "My Account"
    }

    /** Renames a saved account in place — used from the Profile screen's editable account name. */
    fun renameAccount(address: String, newName: String) {
        val accounts = getAccounts().map { if (it.address == address) it.copy(name = newName) else it }
        saveAccounts(accounts)
    }

    fun getActiveMnemonic(): String? {
        return getActiveAccount()?.mnemonic
    }

    fun getPrivateKeyHex(): String {
        return getPrivateKeyBytes().joinToString("") { "%02x".format(it) }
    }

    fun getPrivateKeyBytes(): ByteArray {
        val mnemonic = getActiveMnemonic()?.split(" ") ?: throw IllegalStateException("No active account")
        return deriveKey(mnemonic, passphrase = activePassphrase()).privKeyBytes
    }

    // --- Spending address (accountIndex=1 branch) ---------------------------------------
    // Separate from the identity address above for payment privacy: "Pay in Kaspa" sends
    // sweep this address's entire balance and route change to a freshly derived next index
    // (see KaspaWalletEngine.sendSpendingPayment), so KAS never sits in more than one
    // spending-chain address at a time. Messaging (handshakes/chat messages) is untouched —
    // still identity-address-sourced via getAddress()/getPrivateKeyBytes() above.

    private fun activeMnemonicWords(): List<String> =
        getActiveMnemonic()?.split(" ") ?: throw IllegalStateException("No active account")

    fun deriveSpendingAddress(index: Int): String =
        addressFromKey(deriveKey(activeMnemonicWords(), accountIndex = 1, addressIndex = index, passphrase = activePassphrase()))

    /**
     * The shared spending-chain node (m/44'/111111'/1'/0), derived once — each
     * [deriveSpendingAddress] call redoes the expensive PBKDF2 mnemonic-to-seed plus four
     * derivations from scratch, which is fine for one lookup but dominates when a caller needs
     * every revealed address (pool validation, address-activity watch sets). Mirrors iOS's
     * `spendingChangeKey()` optimization.
     */
    private fun spendingChainKey(): DeterministicKey {
        val seed = MnemonicCode.toSeed(activeMnemonicWords(), activePassphrase())
        val masterKey = HDKeyDerivation.createMasterPrivateKey(seed)
        val key44h = HDKeyDerivation.deriveChildKey(masterKey, ChildNumber(44, true))
        val keyKaspaH = HDKeyDerivation.deriveChildKey(key44h, ChildNumber(111111, true))
        val keyAccountH = HDKeyDerivation.deriveChildKey(keyKaspaH, ChildNumber(1, true))
        return HDKeyDerivation.deriveChildKey(keyAccountH, ChildNumber(0, false))
    }

    /** Every revealed spending-chain address (0..max(spendingAddressIndex, maxSpendingAddressIndex)),
     *  derived with a single seed computation — for own-address watch sets and received-pool
     *  validation. Empty if there's no active account. */
    fun allSpendingAddresses(): List<String> {
        val account = getActiveAccount() ?: return emptyList()
        val maxIndex = maxOf(account.spendingAddressIndex, account.maxSpendingAddressIndex)
        val chainKey = try { spendingChainKey() } catch (e: Exception) { return emptyList() }
        return (0..maxIndex).map { index ->
            addressFromKey(HDKeyDerivation.deriveChildKey(chainKey, ChildNumber(index, false)))
        }
    }

    /** True if [address] is one of this wallet's own revealed spending-chain addresses — used to
     *  reject a received payment pool that tries to feed our own addresses back to us. */
    fun isOwnSpendingAddress(address: String): Boolean = allSpendingAddresses().contains(address)

    /** Guards every fresh-spending-index allocation (payment change AND pool reservations) so two
     *  concurrent allocators can never hand out the same index — see [allocateFreshSpendingIndices]. */
    private val spendingIndexAllocationLock = Any()

    /**
     * Reveals and returns [count] brand-new spending-chain slots in one atomic step — used both
     * by the fresh-address payment pool feature (reserving addresses to offer a contact) and by
     * spending-payment change routing. Indices start strictly past the all-time max
     * (`max(spendingAddressIndex, maxSpendingAddressIndex) + 1`) and the max is bumped to cover
     * them BEFORE returning, so: (a) they have never been revealed, funded, or offered before,
     * and (b) no later payment-change address or pool reservation can ever land on the same
     * index — both allocation paths funnel through this one synchronized method (the Android
     * equivalent of iOS serializing reservation + payment-change derivation through the outgoing
     * tx queue). Returns (index, address) pairs; empty on failure.
     */
    fun allocateFreshSpendingIndices(count: Int): List<Pair<Int, String>> = synchronized(spendingIndexAllocationLock) {
        if (count <= 0) return@synchronized emptyList()
        val account = getActiveAccount() ?: return@synchronized emptyList()
        val chainKey = try { spendingChainKey() } catch (e: Exception) { return@synchronized emptyList() }
        val base = maxOf(account.spendingAddressIndex, account.maxSpendingAddressIndex) + 1
        val result = (0 until count).map { offset ->
            val index = base + offset
            index to addressFromKey(HDKeyDerivation.deriveChildKey(chainKey, ChildNumber(index, false)))
        }
        ensureMaxSpendingAddressIndexAtLeast(account.address, base + count - 1)
        result
    }

    fun getSpendingPrivateKeyBytes(index: Int): ByteArray =
        deriveKey(activeMnemonicWords(), accountIndex = 1, addressIndex = index, passphrase = activePassphrase()).privKeyBytes

    /** The spending address a "Pay in Kaspa" send should currently source funds from/top up. */
    fun currentSpendingAddress(): String {
        val index = getActiveAccount()?.spendingAddressIndex ?: throw IllegalStateException("No active account")
        return deriveSpendingAddress(index)
    }

    /** Signing key for [currentSpendingAddress] — same index, so callers always get a matching pair. */
    fun currentSpendingPrivateKeyBytes(): ByteArray {
        val index = getActiveAccount()?.spendingAddressIndex ?: throw IllegalStateException("No active account")
        return getSpendingPrivateKeyBytes(index)
    }

    /** Called only after a spending-address send is actually accepted by the network. */
    fun advanceSpendingAddressIndex(address: String) {
        val accounts = getAccounts().map {
            if (it.address == address) {
                val next = it.spendingAddressIndex + 1
                it.copy(spendingAddressIndex = next, maxSpendingAddressIndex = maxOf(it.maxSpendingAddressIndex, next))
            } else it
        }
        saveAccounts(accounts)
    }

    /** Sets an explicit index as the one "Pay in Kaspa" sources from — used by the wallet-import gap-limit scan, and by manually activating an address from the Manage Addresses screen. */
    fun setSpendingAddressIndex(address: String, index: Int) {
        val accounts = getAccounts().map {
            if (it.address == address) {
                it.copy(spendingAddressIndex = index, maxSpendingAddressIndex = maxOf(it.maxSpendingAddressIndex, index))
            } else it
        }
        saveAccounts(accounts)
    }

    /** Derives one more spending-chain address for the Manage Addresses screen to show, without changing which one is currently active. Returns the new highest index. */
    fun generateNextSpendingAddress(address: String): Int {
        var newMax = 0
        val accounts = getAccounts().map {
            if (it.address == address) {
                newMax = maxOf(it.maxSpendingAddressIndex, it.spendingAddressIndex) + 1
                it.copy(maxSpendingAddressIndex = newMax)
            } else it
        }
        saveAccounts(accounts)
        return newMax
    }

    /**
     * Raises [Account.maxSpendingAddressIndex] to at least [minIndex] without touching which
     * address is currently active — used after a "Discover Addresses" scan turns up on-chain
     * history past what the Manage Addresses screen currently shows (e.g. KAS sent directly to
     * an address before it was ever generated locally).
     */
    fun ensureMaxSpendingAddressIndexAtLeast(address: String, minIndex: Int) {
        val accounts = getAccounts().map {
            if (it.address == address && minIndex > it.maxSpendingAddressIndex) {
                it.copy(maxSpendingAddressIndex = minIndex)
            } else it
        }
        saveAccounts(accounts)
    }

    /**
     * Derives the shared symmetric key (ECDH + HKDF-SHA256) with a contact's
     * x-only secp256k1 public key. See [com.kachat.app.util.KasiaCipher].
     */
    fun deriveSharedSecret(contactPublicKeyHex: String): ByteArray {
        val peerPubKey = contactPublicKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return com.kachat.app.util.KasiaCipher.deriveSymmetricKey(getPrivateKeyBytes(), peerPubKey)
    }

    /** The alias I watch for incoming deterministic-alias messages from this contact. */
    fun myDeterministicAlias(contactAddress: String): String {
        val theirPubKey = KaspaAddress.decode(contactAddress).second
        val myPubKey = KaspaAddress.decode(getAddress()).second
        return com.kachat.app.util.KasiaCipher.deriveDeterministicAlias(getPrivateKeyBytes(), theirPubKey, contextXOnlyPubKey = myPubKey)
    }

    /** The alias I tag outgoing deterministic-alias messages to this contact with. */
    fun theirDeterministicAlias(contactAddress: String): String {
        val theirPubKey = KaspaAddress.decode(contactAddress).second
        return com.kachat.app.util.KasiaCipher.deriveDeterministicAlias(getPrivateKeyBytes(), theirPubKey, contextXOnlyPubKey = theirPubKey)
    }
}
