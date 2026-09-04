package com.kachat.app.services

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kachat.app.models.WalletSourceFamily
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
    @ApplicationContext private val context: Context,
    // Store-only dependency (PaymentPoolStore depends on nothing but Context, so no Dagger
    // cycle): consulted by [setSpendingAddressHidden] so a spending address currently offered
    // to a contact for private payments can never be hidden, no matter which caller asks.
    private val paymentPoolStore: PaymentPoolStore
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
        private const val PREF_SPENDING_ADDRESS_CACHE = "spending_address_cache"
        private const val PREF_USED_SPENDING_ADDRESSES = "used_spending_addresses"
        private const val PREF_MANAGE_ADDRESSES_SNAPSHOT = "manage_addresses_snapshot"

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
        val passphrase: String = "",
        // [WalletSourceFamily] name — which wallet this seed was imported from, i.e. which BIP32
        // branch its identity (chatting) address lives on. Deliberately nullable rather than
        // defaulted: Gson deserializes old-shape stored JSON field-by-field without running the
        // Kotlin constructor, so an absent field lands as null, and
        // [WalletSourceFamily.fromRaw] maps null to KASPA_STANDARD — exactly what every account
        // created before this feature existed used.
        val sourceFamily: String? = null,
        // Index on the identity chain this account's chatting address is derived at. 0 for every
        // create and every plain import; nonzero only when the user picked a different address in
        // the import wizard's "Change Chatting Address" step. Same Gson zero-default behavior as
        // the two spending indices above.
        val chattingAddressIndex: Int = 0
    )

    private val gson = Gson()

    /**
     * When an account was first added to this device, in epoch millis, stamped once and never
     * moved. Keyed by address in the same encrypted prefs rather than added to [Account]: the
     * value has to survive the account record being rewritten (an import of a seed already here
     * replaces its Account wholesale), which is exactly where a field on the record would be
     * lost. Absent means the account predates this being recorded.
     */
    private fun addedAtKey(address: String) = "account_added_at_$address"

    /** Stamps [address]'s added-on date if it has none yet. Idempotent. */
    private fun stampAccountAddedAt(address: String) {
        if (sharedPrefs.contains(addedAtKey(address))) return
        sharedPrefs.edit().putLong(addedAtKey(address), System.currentTimeMillis()).apply()
    }

    /** Epoch millis this account was added, or null for one that predates the stamp. */
    fun accountAddedAt(address: String): Long? =
        sharedPrefs.getLong(addedAtKey(address), 0L).takeIf { it > 0L }

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
        // saveAccounts is the single choke point every spendingAddressIndex write goes through
        // (send rotation via setSpendingAddressIndex, advanceSpendingAddressIndex, manual
        // activation, import), so refreshing here keeps primarySpendingIndexFlow current no
        // matter which path rotated the primary.
        refreshPrimarySpendingIndexFlow()
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

    /**
     * The active account's primary ("Pay in Kaspa") spending index, reactive to every rotation —
     * a successful spending send advances it (see KaspaWalletEngine.sendSpendingPayment), and the
     * Manage Addresses screen needs to notice PROMPTLY so the old primary row loses its star and
     * becomes hideable without waiting for a full list reload. Updated from [saveAccounts] (the
     * choke point for all account mutations) and on account switches.
     */
    private val _primarySpendingIndex = MutableStateFlow(computePrimarySpendingIndex())
    val primarySpendingIndexFlow: StateFlow<Int?> = _primarySpendingIndex.asStateFlow()

    private fun computePrimarySpendingIndex(): Int? = getActiveAccount()?.spendingAddressIndex

    private fun refreshPrimarySpendingIndexFlow() {
        _primarySpendingIndex.value = computePrimarySpendingIndex()
    }

    fun setActiveAccount(address: String) {
        sharedPrefs.edit().putString(PREF_ACTIVE_ADDRESS, address).apply()
        refreshActiveAddressFlow()
        refreshPrimarySpendingIndexFlow()
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
        // Storage-level backstop for EVERY write path: the primary ("Pay in Kaspa") spending
        // index must always stay visible, so a hide of it never persists no matter which caller
        // asked. (Live-balance guarding lives in WalletService.setSpendingAddressHidden; bulk
        // paths that only touch never-funded fresh indices come straight here.)
        if (hidden && getAccounts().any { it.address == walletAddress && it.spendingAddressIndex == index }) return
        // Chat-privacy backstop, same authority level as the primary guard above: an address
        // currently offered to a contact for private payments (fresh-address payment pool) stays
        // visible while the offer stands. The pool store is queried directly - never a row flag.
        if (hidden && paymentPoolStore.isIndexOfferedForPrivacy(index, walletAddress)) return
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
        // A freshly created wallet is by definition a KaChat wallet: standard family, index 0.
        val address = deriveIdentityAddress(mnemonic, passphrase, WalletSourceFamily.KASPA_STANDARD, 0)
        val accounts = getAccounts().toMutableList()
        accounts.add(Account(name, address, mnemonic.joinToString(" "), passphrase = passphrase))
        saveAccounts(accounts)
        stampAccountAddedAt(address)
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
     *
     * [family] is the source-wallet derivation family chosen on the import chooser (see
     * [WalletSourceFamily]); [chattingAddressIndex] is the identity-chain index, nonzero only when
     * the import wizard's "Change Chatting Address" step picked a different one. Both are
     * persisted on the account and honored by every later re-derivation.
     */
    fun importWallet(
        mnemonic: List<String>,
        name: String,
        passphrase: String = "",
        family: WalletSourceFamily = WalletSourceFamily.KASPA_STANDARD,
        chattingAddressIndex: Int = 0
    ) {
        MnemonicCode.INSTANCE.check(mnemonic)
        // Derive with the passphrase — a different (or empty) passphrase yields a different address
        // and therefore a different account entry, which is exactly the BIP39 hidden-wallet model.
        // The family + index shape the identity path the same way (a KDX seed and a KaChat seed
        // with the same words are genuinely different accounts, on different branches).
        val address = deriveIdentityAddress(mnemonic, passphrase, family, chattingAddressIndex)
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
                passphrase = passphrase,
                sourceFamily = family.name,
                chattingAddressIndex = chattingAddressIndex
            )
        )
        saveAccounts(accounts)
        // Only if this address has never been seen here - re-importing a seed already on the
        // device is not the account arriving again.
        stampAccountAddedAt(address)
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

    // --- Identity (chatting) derivation, family-aware -----------------------------------------
    //
    // DECISION: KaChat's own spending-address chain stays on the fixed m/44'/111111'/1' branch
    // REGARDLESS of the imported wallet's source family (see [deriveKey] below, which is now used
    // by the spending chain only). Spending addresses are funds KaChat itself derives, reveals and
    // controls (payment pools, fresh change, reservations) — they are not something the source
    // wallet ever derived, so there is nothing to "find" on the source family's branch, and
    // keeping them pinned to account 1' guarantees no source family (standard account 0', legacy
    // 972, OneKey's tweaked account-0' keys) can ever collide with them. Mirrors the identical
    // decision comment in iOS's WalletManager.

    /**
     * The family's shared base node, from which each identity index derives with one final step
     * (see [identityPrivateKeyBytes]). Deriving this once per scan/import is the expensive part
     * (PBKDF2 mnemonic-to-seed plus the hardened HMAC-SHA512 chain), so callers that need many
     * indices reuse one node — same reasoning as [spendingChainKey].
     */
    private fun identityBaseNode(
        mnemonic: List<String>,
        passphrase: String,
        family: WalletSourceFamily
    ): DeterministicKey {
        val seed = MnemonicCode.toSeed(mnemonic, passphrase)
        val master = HDKeyDerivation.createMasterPrivateKey(seed)
        val purpose = HDKeyDerivation.deriveChildKey(master, ChildNumber(44, true))
        return when (family) {
            // m/44'/111111'/0'/0 — OneKey shares the standard chain; only the final key is tweaked.
            WalletSourceFamily.KASPA_STANDARD, WalletSourceFamily.ONE_KEY -> {
                val coinType = HDKeyDerivation.deriveChildKey(purpose, ChildNumber(111111, true))
                val account = HDKeyDerivation.deriveChildKey(coinType, ChildNumber(0, true))
                HDKeyDerivation.deriveChildKey(account, ChildNumber(0, false))
            }
            // m/44'/972/0'/0' — 972 deliberately NOT hardened (replicates KasWare's "m/44'/972/0'"
            // string exactly); the change level is hardened, and so is the final index (applied in
            // [identityPrivateKeyBytes]).
            WalletSourceFamily.KASPA_LEGACY_972 -> {
                val coinType = HDKeyDerivation.deriveChildKey(purpose, ChildNumber(972, false))
                val account = HDKeyDerivation.deriveChildKey(coinType, ChildNumber(0, true))
                HDKeyDerivation.deriveChildKey(account, ChildNumber(0, true))
            }
        }
    }

    /** Applies the family's index rule to [baseNode]. Null when the key is unusable (only
     *  reachable via a failed OneKey tweak). */
    private fun identityPrivateKeyBytes(
        baseNode: DeterministicKey,
        index: Int,
        family: WalletSourceFamily
    ): ByteArray? = when (family) {
        WalletSourceFamily.KASPA_STANDARD ->
            HDKeyDerivation.deriveChildKey(baseNode, ChildNumber(index, false)).privKeyBytes
        WalletSourceFamily.KASPA_LEGACY_972 ->
            HDKeyDerivation.deriveChildKey(baseNode, ChildNumber(index, true)).privKeyBytes
        WalletSourceFamily.ONE_KEY ->
            oneKeyTweakedPrivateKey(HDKeyDerivation.deriveChildKey(baseNode, ChildNumber(index, false)).privKeyBytes)
    }

    /**
     * OneKey's BIP340 taproot-style key tweak, replicated from KasWare's
     * `_onekeyPrivateKeyFromOriginPrivateKey` (bip340.ts) and iOS's `oneKeyTweakedPrivateKey`:
     * if the compressed pubkey has an odd Y (0x03 prefix), negate the private key mod n; then add
     * taggedHash("TapTweak", xOnlyPubkey) mod n. The address derives from the tweaked key. The
     * x-only pubkey is unaffected by the negation (negating flips Y only), so it is read off the
     * original point.
     */
    private fun oneKeyTweakedPrivateKey(privateKey: ByteArray): ByteArray? = try {
        val n = com.kachat.app.util.Secp256k1.N
        val d0 = java.math.BigInteger(1, privateKey)
        if (d0.signum() <= 0 || d0 >= n) {
            null
        } else {
            val point = com.kachat.app.util.Secp256k1.G.multiply(d0).normalize()
            // Compressed prefix 0x03 == odd Y.
            val d = if (point.affineYCoord.toBigInteger().testBit(0)) n.subtract(d0) else d0
            val xOnly = to32Bytes(point.affineXCoord.toBigInteger())
            val tweak = java.math.BigInteger(1, taggedSha256("TapTweak", xOnly))
            val tweaked = d.add(tweak).mod(n)
            if (tweaked.signum() == 0) null else to32Bytes(tweaked)
        }
    } catch (e: Exception) {
        null
    }

    /** BIP340 tagged hash: SHA256(SHA256(tag) || SHA256(tag) || data). */
    private fun taggedSha256(tag: String, data: ByteArray): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val tagHash = digest.digest(tag.toByteArray(Charsets.UTF_8))
        digest.reset()
        digest.update(tagHash)
        digest.update(tagHash)
        digest.update(data)
        return digest.digest()
    }

    /** Fixed-width 32-byte big-endian encoding (BigInteger.toByteArray may add a sign byte or drop leading zeros). */
    private fun to32Bytes(value: java.math.BigInteger): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(32)
        if (raw.size >= 32) {
            System.arraycopy(raw, raw.size - 32, out, 0, 32)
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.size, raw.size)
        }
        return out
    }

    private fun addressFromPrivateKeyBytes(privateKey: ByteArray): String =
        KaspaAddress.encode("kaspa", 0x00, com.kachat.app.util.Schnorr.publicKeyXOnly(privateKey))

    /**
     * Identity (chatting) address for one seed at one index within one source family — the single
     * place a chatting address is ever derived from.
     */
    /**
     * The chatting address a seed would produce with [passphrase], without committing anything.
     *
     * Exists for the passphrase step, where seeing address #0 change as you type is the only
     * direct evidence that a passphrase opens a DIFFERENT account rather than protecting the same
     * one - which is the single thing people get wrong about passphrases. Null when the words are
     * not a usable mnemonic.
     */
    fun previewIdentityAddress(
        mnemonic: List<String>,
        passphrase: String,
        family: WalletSourceFamily = WalletSourceFamily.KASPA_STANDARD,
        index: Int = 0,
    ): String? = try {
        deriveIdentityAddress(mnemonic, passphrase, family, index)
    } catch (e: Exception) {
        null
    }

    private fun deriveIdentityAddress(
        mnemonic: List<String>,
        passphrase: String,
        family: WalletSourceFamily,
        index: Int
    ): String {
        val base = identityBaseNode(mnemonic, passphrase, family)
        val privateKey = identityPrivateKeyBytes(base, index, family)
            ?: throw IllegalStateException("This wallet type has no address at index $index.")
        return addressFromPrivateKeyBytes(privateKey)
    }

    /**
     * Shared HD path walk for the SPENDING chain — `m/44'/111111'/{accountIndex}'/0/{addressIndex}`.
     * The spending-address chain lives at `accountIndex=1`, a distinct hardened branch pinned
     * regardless of the account's [WalletSourceFamily] (see the decision comment above), so it can
     * never collide with any family's identity path no matter how far its own addressIndex
     * advances. Identity keys no longer come through here — see [identityBaseNode].
     */
    private fun deriveKey(mnemonic: List<String>, accountIndex: Int = 1, addressIndex: Int = 0, passphrase: String = ""): DeterministicKey {
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

    /** The active account's BIP39 passphrase (empty when it has none), used to re-derive its keys. */
    private fun activePassphrase(): String = getActiveAccount()?.passphrase ?: ""

    /** The active account's identity derivation family (KASPA_STANDARD for every account that
     *  predates the import source-wallet chooser). */
    fun activeSourceFamily(): WalletSourceFamily =
        WalletSourceFamily.fromRaw(getActiveAccount()?.sourceFamily)

    /** The identity-chain index the active account's chatting address is derived at (0 = default). */
    fun activeChattingAddressIndex(): Int = getActiveAccount()?.chattingAddressIndex ?: 0

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

    /**
     * The active account's identity (chatting) private key — re-derived from its persisted
     * [WalletSourceFamily] and [Account.chattingAddressIndex], so an imported KDX/OneKey seed and
     * a chosen non-default chatting index both keep producing the key that actually matches the
     * stored address. Every identity consumer (handshakes, ECIES, deterministic aliases, message
     * signing) funnels through here.
     */
    fun getPrivateKeyBytes(): ByteArray {
        val account = getActiveAccount() ?: throw IllegalStateException("No active account")
        val mnemonic = account.mnemonic.split(" ")
        val family = WalletSourceFamily.fromRaw(account.sourceFamily)
        val baseNode = identityBaseNode(mnemonic, account.passphrase ?: "", family)
        return identityPrivateKeyBytes(baseNode, account.chattingAddressIndex, family)
            ?: throw IllegalStateException("This wallet type has no address at index ${account.chattingAddressIndex}.")
    }

    // --- Spending address (accountIndex=1 branch) ---------------------------------------
    // Separate from the identity address above for payment privacy: "Pay in Kaspa" sends
    // sweep this address's entire balance and route change to a freshly derived next index
    // (see KaspaWalletEngine.sendSpendingPayment), so KAS never sits in more than one
    // spending-chain address at a time. Messaging (handshakes/chat messages) is untouched —
    // still identity-address-sourced via getAddress()/getPrivateKeyBytes() above.

    private fun activeMnemonicWords(): List<String> =
        getActiveMnemonic()?.split(" ") ?: throw IllegalStateException("No active account")

    /** One derived spending-chain ADDRESS (never a key), persisted so repeat lookups skip the
     *  PBKDF2 seed derivation entirely. Addresses are deterministic per (wallet, index) — the
     *  wallet address already encodes mnemonic+passphrase, so entries never go stale. Flat
     *  (walletAddress, index) keying, same pattern as [HiddenSpendingAddress]. */
    private data class CachedSpendingAddress(val walletAddress: String, val index: Int, val address: String)

    private fun getAllCachedSpendingAddresses(): List<CachedSpendingAddress> {
        val json = sharedPrefs.getString(PREF_SPENDING_ADDRESS_CACHE, null) ?: return emptyList()
        val type = object : TypeToken<List<CachedSpendingAddress>>() {}.type
        return try { gson.fromJson(json, type) } catch (e: Exception) { emptyList() }
    }

    private fun cacheSpendingAddresses(walletAddress: String, byIndex: Map<Int, String>) {
        if (byIndex.isEmpty()) return
        val existing = getAllCachedSpendingAddresses()
            .filterNot { it.walletAddress == walletAddress && byIndex.containsKey(it.index) }
        val added = byIndex.map { (index, address) -> CachedSpendingAddress(walletAddress, index, address) }
        sharedPrefs.edit().putString(PREF_SPENDING_ADDRESS_CACHE, gson.toJson(existing + added)).apply()
    }

    fun deriveSpendingAddress(index: Int): String {
        val walletAddress = getActiveAccount()?.address
        if (walletAddress != null) {
            getAllCachedSpendingAddresses()
                .firstOrNull { it.walletAddress == walletAddress && it.index == index }
                ?.let { return it.address }
        }
        val address = addressFromKey(deriveKey(activeMnemonicWords(), accountIndex = 1, addressIndex = index, passphrase = activePassphrase()))
        if (walletAddress != null) cacheSpendingAddresses(walletAddress, mapOf(index to address))
        return address
    }

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
        // Cache-first: this runs on the 30s address-activity poll, and the full PBKDF2 seed
        // derivation per pass was pure waste once every index has been derived once.
        val cached = getAllCachedSpendingAddresses()
            .filter { it.walletAddress == account.address }
            .associate { it.index to it.address }
        if ((0..maxIndex).all { cached.containsKey(it) }) {
            return (0..maxIndex).map { cached.getValue(it) }
        }
        val chainKey = try { spendingChainKey() } catch (e: Exception) { return emptyList() }
        val derived = (0..maxIndex).associateWith { index ->
            addressFromKey(HDKeyDerivation.deriveChildKey(chainKey, ChildNumber(index, false)))
        }
        cacheSpendingAddresses(account.address, derived)
        return (0..maxIndex).map { derived.getValue(it) }
    }

    // --- "Ever used" cache: monotonic (a used address can never become unused), so positive
    // answers persist forever and skip the network history probe — mirrors iOS. Address-keyed:
    // used-ness is intrinsic to the address, not the wallet.
    fun isAddressKnownUsed(address: String): Boolean {
        val json = sharedPrefs.getString(PREF_USED_SPENDING_ADDRESSES, null) ?: return false
        val type = object : TypeToken<Set<String>>() {}.type
        return try { gson.fromJson<Set<String>>(json, type).contains(address) } catch (e: Exception) { false }
    }

    fun markAddressUsed(address: String) {
        if (address.isBlank()) return
        val type = object : TypeToken<Set<String>>() {}.type
        val current: Set<String> = sharedPrefs.getString(PREF_USED_SPENDING_ADDRESSES, null)
            ?.let { try { gson.fromJson(it, type) } catch (e: Exception) { emptySet<String>() } } ?: emptySet()
        if (address in current) return
        sharedPrefs.edit().putString(PREF_USED_SPENDING_ADDRESSES, gson.toJson(current + address)).apply()
    }

    // --- Manage Addresses snapshot: opaque JSON of the last fully-loaded entry list, persisted
    // per wallet so the screen paints instantly while the live refresh runs (WalletService owns
    // the entry type and its serialization; this is just the per-wallet keyed store).
    fun getManageAddressesSnapshot(walletAddress: String): String? =
        sharedPrefs.getString("${PREF_MANAGE_ADDRESSES_SNAPSHOT}_$walletAddress", null)

    fun setManageAddressesSnapshot(walletAddress: String, json: String) {
        sharedPrefs.edit().putString("${PREF_MANAGE_ADDRESSES_SNAPSHOT}_$walletAddress", json).apply()
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

    // --- Chatting-address scanning + switching (import wizard) --------------------------------

    /**
     * Derives a contiguous range of the ACTIVE account's identity chain, within its own source
     * family, reusing a single base node so the expensive PBKDF2 + hardened chain runs once for
     * the whole batch (rather than once per index). Returns (index, address) pairs in order;
     * empty when there is no active account or derivation fails outright.
     */
    fun deriveChattingAddresses(indices: IntRange): List<Pair<Int, String>> {
        val account = getActiveAccount() ?: return emptyList()
        val family = WalletSourceFamily.fromRaw(account.sourceFamily)
        val baseNode = try {
            identityBaseNode(account.mnemonic.split(" "), account.passphrase ?: "", family)
        } catch (e: Exception) {
            return emptyList()
        }
        return indices.mapNotNull { index ->
            if (index < 0) return@mapNotNull null
            val privateKey = identityPrivateKeyBytes(baseNode, index, family) ?: return@mapNotNull null
            index to addressFromPrivateKeyBytes(privateKey)
        }
    }

    /**
     * Switches the active account's identity to the chatting address at [index] on its own source
     * family's identity chain, and returns the new address.
     *
     * This is a CLEAN identity selection, not a migration: nothing is moved or deleted. The old
     * address's conversation history stays on-chain and in its own address-scoped storage (every
     * ChatRepository query is scoped by [activeAddressFlow]); switching simply parks it there. The
     * account entry is rewritten in place — same seed, same passphrase, same family, now living at
     * a different address — and the stale old entry is dropped so the saved-accounts list doesn't
     * keep a dead index-0 row forever (matches iOS's `setChattingAddress`). The spending-chain
     * indices carry over unchanged: the spending chain is pinned to m/44'/111111'/1' and is
     * therefore identical for both identities of this seed.
     *
     * Making the new address active goes through [setActiveAccount], the same account-switch
     * machinery every account switch uses, so [activeAddressFlow] re-emits and every scoped
     * consumer (chat repository, push registration, balance/spending refresh) re-scopes itself.
     */
    fun switchChattingAddress(index: Int): String {
        require(index >= 0) { "Invalid chatting address index" }
        val account = getActiveAccount() ?: throw IllegalStateException("No active account")
        if (index == account.chattingAddressIndex) return account.address
        val family = WalletSourceFamily.fromRaw(account.sourceFamily)
        val mnemonic = account.mnemonic.split(" ")
        val newAddress = deriveIdentityAddress(mnemonic, account.passphrase ?: "", family, index)
        val switched = account.copy(address = newAddress, chattingAddressIndex = index)
        // Drop both the old entry and any pre-existing entry for the new address, then insert the
        // switched account at the top — the same "remove, re-insert at 0" shape importWallet uses.
        val accounts = getAccounts()
            .filter { it.address != account.address && it.address != newAddress }
            .toMutableList()
        accounts.add(0, switched)
        saveAccounts(accounts)
        setActiveAccount(newAddress)
        return newAddress
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
