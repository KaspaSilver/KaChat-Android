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
) {

    companion object {
        // Gson type tokens, resolved once. `object : TypeToken<...>() {}` allocates a fresh
        // anonymous class instance and walks reflection every time it is evaluated, and these sat
        // inside functions called on nearly every screen.
        private val ACCOUNTS_TYPE = object : TypeToken<List<Account>>() {}.type
        private val SPENDING_CACHE_TYPE = object : TypeToken<List<CachedSpendingAddress>>() {}.type
        private val USED_ADDRESSES_TYPE = object : TypeToken<Set<String>>() {}.type

        /**
         * True when [written] (the account list read back after an import / create) still holds
         * every account of [previous] with its seed and passphrase intact - the one being
         * (re)imported at [newAddress] aside, since it is the same account coming back (iOS
         * snapshotStoredWallet's read-back, 9758568 / IOS-016).
         */
        internal fun accountsKeptIntact(previous: List<Account>, written: List<Account>?, newAddress: String): Boolean {
            if (written == null || written.none { it.address == newAddress }) return false
            return previous
                .filter { !com.kachat.app.util.KaspaNetwork.isSameAccount(it.address, newAddress) }
                .all { p ->
                    written.any { w ->
                        com.kachat.app.util.KaspaNetwork.isSameAccount(w.address, p.address) &&
                            w.mnemonic == p.mnemonic && (w.passphrase ?: "") == (p.passphrase ?: "") &&
                            passphraseFormOf(w) == passphraseFormOf(p)
                    }
                }
        }

        /**
         * The one place a BIP39 seed is made (audit XP-013). BIP39 says
         * seed = PBKDF2(NFKD(mnemonic), "mnemonic" + NFKD(passphrase)), as iOS, Desktop and the
         * extension do; bitcoinj's [MnemonicCode.toSeed] hashes the strings as given, so the
         * normalization happens here. [PassphraseForm.RAW] is the pre-fix Android derivation
         * (the passphrase exactly as typed), kept only for accounts saved before the fix whose
         * passphrase NFKD changes, so their addresses never move.
         */
        internal fun seedFor(
            words: List<String>,
            passphrase: String,
            form: PassphraseForm = PassphraseForm.NFKD
        ): ByteArray = when (form) {
            PassphraseForm.NFKD -> MnemonicCode.toSeed(words.map { nfkd(it) }, nfkd(passphrase))
            PassphraseForm.RAW -> MnemonicCode.toSeed(words, passphrase)
        }

        internal fun nfkd(text: String): String =
            java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKD)

        /**
         * How [account]'s seed is made. Its [Account.passphraseForm] flag when set. A record
         * without the flag was saved before the fix: if NFKD leaves its passphrase unchanged
         * (no passphrase, ASCII, or already decomposed) both forms give the same seed, so it is
         * standard; otherwise it keeps the raw derivation it was created with.
         */
        internal fun passphraseFormOf(account: Account): PassphraseForm =
            when (account.passphraseForm) {
                PassphraseForm.RAW.stored -> PassphraseForm.RAW
                PassphraseForm.NFKD.stored -> PassphraseForm.NFKD
                else -> {
                    val pass = account.passphrase ?: ""
                    if (pass == nfkd(pass)) PassphraseForm.NFKD else PassphraseForm.RAW
                }
            }

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
        private const val PREF_QUANTUM_SPENT_ADDRESSES = "quantum_spent_addresses"

        // bitcoinj's own `MnemonicCode.INSTANCE` static initializer loads its wordlist via
        // `Class.getResourceAsStream`, which is documented by bitcoinj itself as "Won't work on
        // Android" — it fails silently there, leaving INSTANCE null and crashing every wallet
        // create/import with an NPE. Bundle the identical wordlist bitcoinj ships internally as
        // an Android asset and initialize INSTANCE from it manually instead.
        private const val WORDLIST_ASSET_NAME = "bip39-wordlist-english.txt"
        private const val WORDLIST_SHA256 = "ad90bf3beb7b0eb7e5acd74727dc0da96e0a280a258354e7293fb7e211ac03db"

        @Volatile private var instance: WalletManager? = null

        // --- Quantum-safe chain (KIP-22; see the spending-address section below) ---------------

        /**
         * Quantum addresses live in the same Int index space as the legacy spending chain, offset
         * by this base, so every API that passes a spending index (send sources, coin control,
         * labels, the hidden set, the primary pointer, the address cache) works for both chains
         * unchanged. Index `base + n` is quantum chain index `n` (iOS
         * `WalletManager.quantumIndexBase`).
         */
        const val QUANTUM_INDEX_BASE = 1_000_000

        fun isQuantumIndex(index: Int): Boolean = index >= QUANTUM_INDEX_BASE

        /** A spending row's number on its own chain, both named "Address #n" - quantum numbering
         *  starts at 0 again; the shield is what tells the chains apart (iOS
         *  `SpendingAddressEntry.displayTitle`, English like iOS). */
        fun spendingAddressTitle(index: Int): String =
            "Address #${if (isQuantumIndex(index)) index - QUANTUM_INDEX_BASE else index}"

        /**
         * The legacy chain's revealed bound: the stored max, widened by the primary only while the
         * primary is a legacy index - a quantum primary (index >= [QUANTUM_INDEX_BASE]) must never
         * widen the legacy chain (iOS `maxSpendingAddressIndex`). A stored max in quantum space is
         * ignored too: either term at 1,000,000+ would make every 0..max loop derive a million
         * legacy addresses (iOS 28ecee0, the launch hang).
         */
        fun legacyMaxSpendingIndex(account: Account): Int = maxOf(
            if (isQuantumIndex(account.maxSpendingAddressIndex)) 0 else account.maxSpendingAddressIndex,
            if (isQuantumIndex(account.spendingAddressIndex)) 0 else account.spendingAddressIndex,
        )

        /** The highest quantum chain index [account] has revealed; -1 before the first one. */
        fun maxQuantumIndex(account: Account): Int = account.maxQuantumAddressIndex ?: -1

        /** The next fresh quantum slot (public index): one past [maxQuantumIndex] (chain index;
         *  -1 = none revealed) and past every slot in [inFlight] (public indices a send picked
         *  for its change and has not revealed yet). */
        internal fun nextFreshQuantumIndex(maxQuantumIndex: Int, inFlight: Collection<Int>): Int {
            val revealed = QUANTUM_INDEX_BASE + maxQuantumIndex + 1
            val pending = inFlight.maxOrNull() ?: return revealed
            return maxOf(revealed, pending + 1)
        }

        /** How many used slots [verifyFreshQuantumIndex] skips at most (iOS
         *  `verifiedFreshQuantumIndex`'s 20). */
        const val MAX_USED_FRESH_QUANTUM_SKIPS = 20

        /**
         * iOS `verifiedFreshQuantumIndex`'s walk (da2c8e2, IOS-101 / AND-049): [next] is the
         * fresh slot (public index) as things stand; while [isUsed] says its [address] already has
         * history it is [skip]ped (the chain's end raised past it) and the next one asked, up to
         * [MAX_USED_FRESH_QUANTUM_SKIPS] times. A slot whose address can't be derived, or whose
         * check can't be answered (null), is taken as is - an unreachable server never blocks a
         * send. [skip] answers false when the probe's account has been removed meanwhile (iOS
         * 6891935, IOS-109 / AND-056): nothing more is written, and the slot past the used one is
         * returned unchecked.
         */
        internal suspend fun firstFreshQuantumSlot(
            next: () -> Int,
            address: suspend (Int) -> String?,
            isUsed: suspend (String) -> Boolean?,
            skip: (Int) -> Boolean,
        ): Int {
            var index = next()
            repeat(MAX_USED_FRESH_QUANTUM_SKIPS) {
                val candidate = address(index) ?: return index
                if (isUsed(candidate) != true) return index
                if (!skip(index)) return index + 1
                index = next()
            }
            return index
        }

        /**
         * The public slots one allocation on an account takes (iOS fb2323e, IOS-109 / AND-056
         * follow-up): [count] in a row from THAT account's fresh slot - past [revealedMax], its
         * quantum bound, and [inFlight], its own sends' change picks - whichever account is
         * active. Null, so nothing is taken or written, when [revealedMax] is null: the account
         * has been removed since its probe started.
         */
        internal fun freshQuantumAllocation(revealedMax: Int?, inFlight: Collection<Int>, count: Int): List<Int>? {
            if (revealedMax == null || count <= 0) return null
            val first = nextFreshQuantumIndex(revealedMax, inFlight)
            return List(count) { first + it }
        }

        // --- Writes after a send land on the account it started on (iOS ca09dd0, IOS-109 / AND-056)

        /** Whether a write meant for [owner] may go ahead: [owner] null means the active account
         *  (always), otherwise it must still be saved - a send's account removed while the network
         *  answered gets nothing (iOS `accountStillSaved(owner)`). */
        internal fun ownerStillSaved(accounts: List<Account>, owner: String?): Boolean =
            owner == null || accounts.any { it.address == owner }

        /**
         * The account list [setSpendingAddressIndex] writes (iOS `setActiveSpendingAddress(_:owner:)`,
         * ca09dd0): [owner]'s primary moved to [index] and its own chain's bound raised to cover it -
         * a quantum index never touches the legacy bound - and every other account left as is,
         * whichever one is active. Null, so nothing is written, once [owner] has been removed.
         */
        internal fun withSpendingAddressIndex(accounts: List<Account>, owner: String, index: Int): List<Account>? {
            if (index < 0 || accounts.none { it.address == owner }) return null
            return accounts.map {
                when {
                    it.address != owner -> it
                    isQuantumIndex(index) -> it.copy(
                        spendingAddressIndex = index,
                        maxQuantumAddressIndex = maxOf(maxQuantumIndex(it), index - QUANTUM_INDEX_BASE),
                    )
                    else -> it.copy(spendingAddressIndex = index, maxSpendingAddressIndex = maxOf(it.maxSpendingAddressIndex, index))
                }
            }
        }

        /**
         * What [revealSpendingAddress] writes for one account (iOS `revealSpendingAddress(at:owner:)`,
         * ca09dd0): [account]'s record with its own chain's bound raised to [index] when it is past
         * it, and its [hidden] set with the slots the raise newly covers added and [index] itself
         * taken out (listed). Null, so nothing is written, when [account] is null - removed.
         */
        internal fun spendingReveal(account: Account?, hidden: Set<Int>, index: Int): Pair<Account, Set<Int>>? {
            if (account == null || index < 0) return null
            var record: Account = account
            var newlyCovered: List<Int> = emptyList()
            if (isQuantumIndex(index)) {
                val quantumIndex = index - QUANTUM_INDEX_BASE
                val currentMax = maxQuantumIndex(account)
                if (quantumIndex > currentMax) {
                    record = account.copy(maxQuantumAddressIndex = quantumIndex)
                    newlyCovered = ((currentMax + 1) until quantumIndex).map { QUANTUM_INDEX_BASE + it }
                }
            } else {
                val currentMax = legacyMaxSpendingIndex(account)
                if (index > currentMax) {
                    record = account.copy(maxSpendingAddressIndex = index)
                    newlyCovered = ((currentMax + 1) until index).toList()
                }
            }
            return record to (hidden + newlyCovered - index)
        }

        /** The signature-script size a send from [spendingIndex] prices per input: a quantum
         *  source pushes its redeem script after the signature (iOS `kip22SignatureScriptSize`),
         *  anything else is the plain 66-byte Schnorr push. Null = the chatting address. */
        fun signatureScriptSize(spendingIndex: Int?): Int =
            if (spendingIndex != null && isQuantumIndex(spendingIndex)) com.kachat.app.util.KaspaMass.KIP22_SIGNATURE_SCRIPT_LEN
            else com.kachat.app.util.KaspaMass.SCHNORR_SIGNATURE_SCRIPT_LEN

        /** The legacy spending chain node m/44'/111111'/1'/0 of [seed] - see [spendingChainKey]. */
        internal fun spendingChainNode(seed: ByteArray): DeterministicKey {
            val masterKey = HDKeyDerivation.createMasterPrivateKey(seed)
            val key44h = HDKeyDerivation.deriveChildKey(masterKey, ChildNumber(44, true))
            val keyKaspaH = HDKeyDerivation.deriveChildKey(key44h, ChildNumber(111111, true))
            val keyAccountH = HDKeyDerivation.deriveChildKey(keyKaspaH, ChildNumber(1, true))
            return HDKeyDerivation.deriveChildKey(keyAccountH, ChildNumber(0, false))
        }

        /** A legacy (P2PK, version 0) spending address of [key], encoded for the network this
         *  launch runs on. */
        internal fun addressFromKey(key: DeterministicKey): String {
            val pubKey = key.pubKey
            val xOnlyPubKey = if (pubKey.size == 33) pubKey.sliceArray(1..32) else pubKey
            return KaspaAddress.encode(com.kachat.app.util.KaspaNetwork.hrp, 0x00, xOnlyPubKey)
        }

        /** The quantum chain node m/22'/111111'/0'/0 (KIP-0022) of [seed]. */
        internal fun quantumChainNode(seed: ByteArray): DeterministicKey {
            val master = HDKeyDerivation.createMasterPrivateKey(seed)
            val purpose = HDKeyDerivation.deriveChildKey(master, ChildNumber(22, true))
            val coinType = HDKeyDerivation.deriveChildKey(purpose, ChildNumber(111111, true))
            val account = HDKeyDerivation.deriveChildKey(coinType, ChildNumber(0, true))
            return HDKeyDerivation.deriveChildKey(account, ChildNumber(0, false))
        }

        /** The KIP-22 redeem script of [key]: `0x20 <x-only key> OP_CHECKSIG` - the .kachat
         *  registry's own P2PK script builder. Spends push it after the signature. */
        internal fun quantumRedeemScript(key: DeterministicKey): ByteArray {
            val pubKey = key.pubKey
            val xOnly = if (pubKey.size == 33) pubKey.sliceArray(1..32) else pubKey
            return com.kachat.app.services.kachatnames.KachatNames.Codec.p2pkScript(xOnly)
        }

        /** The quantum address at chain index [quantumIndex] under [chain]: the P2SH of its
         *  redeem script, through the same helpers the .kachat registry uses on chain every day.
         *  Encoded for the network this launch runs on. */
        internal fun quantumAddress(chain: DeterministicKey, quantumIndex: Int): String {
            val key = HDKeyDerivation.deriveChildKey(chain, ChildNumber(quantumIndex, false))
            val p2sh = com.kachat.app.services.kachatnames.KachatNames.Codec.p2shScript(quantumRedeemScript(key))
            return com.kachat.app.services.kachatnames.KachatNamesService.p2shAddress(p2sh)
                ?: throw IllegalStateException("Could not encode the quantum address")
        }

        /** [indices]' spending addresses, each chain through its own node - a quantum index never
         *  touches the legacy m/44' chain; a node is only made when one of its indices is asked. */
        internal fun deriveChainAddresses(
            indices: Collection<Int>,
            legacyNode: () -> DeterministicKey,
            quantumNode: () -> DeterministicKey,
        ): Map<Int, String> {
            val derived = HashMap<Int, String>()
            val legacy = indices.filter { it >= 0 && !isQuantumIndex(it) }
            val quantum = indices.filter { isQuantumIndex(it) }
            if (legacy.isNotEmpty()) {
                val chainKey = legacyNode()
                for (index in legacy) derived[index] = addressFromKey(HDKeyDerivation.deriveChildKey(chainKey, ChildNumber(index, false)))
            }
            if (quantum.isNotEmpty()) {
                val quantumKey = quantumNode()
                for (index in quantum) derived[index] = quantumAddress(quantumKey, index - QUANTUM_INDEX_BASE)
            }
            return derived
        }

        /** True when [address] is the active account's own chatting address - the chat with
         *  yourself (iOS `WalletManager.shared.currentWallet?.publicAddress`). */
        fun isOwnChattingAddress(address: String): Boolean {
            val own = instance?.activeAddressFlow?.value ?: return false
            return address.trim().equals(own.trim(), ignoreCase = true)
        }
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
        val chattingAddressIndex: Int = 0,
        // [PassphraseForm.stored]: how the BIP39 seed is made from [passphrase] (audit XP-013).
        // "nfkd" on every record saved since the fix; "raw" on a record saved before it whose
        // passphrase NFKD changes. Null only on records from before the field existed, until the
        // one-time stamp in init; [passphraseFormOf] resolves those the same way.
        val passphraseForm: String? = null,
        // The highest quantum-safe (KIP-22) chain index ever revealed, or null before the first
        // one (iOS `maxQuantumAddressIndex`'s -1). Its own bound, kept apart from
        // [maxSpendingAddressIndex]: the legacy max must never see base-space values. Nullable so
        // Gson leaves old records at "none" rather than 0 (which would reveal slot 0 unasked).
        val maxQuantumAddressIndex: Int? = null
    )

    /** How a passphrase enters the BIP39 seed - see [seedFor]. */
    enum class PassphraseForm(val stored: String) {
        /** BIP39: NFKD-normalized. Every account saved since audit XP-013's fix. */
        NFKD("nfkd"),
        /** The passphrase exactly as typed: Android before the fix. Legacy records only. */
        RAW("raw"),
    }

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

    /**
     * The parsed account list, held in memory.
     *
     * [getActiveAccount] and [getAddress] sit behind about 170 call sites across the app -
     * repositories, view models, composables - and every one of them used to mean a
     * SharedPreferences read, a fresh `TypeToken` anonymous class, and a full Gson parse of the
     * whole blob. `getActiveAccount` did it twice when no active address was stored. That is a
     * lot of reflection for data that changes when the user adds or switches an account.
     *
     * Invalidated by [saveAccounts] and [wipe], which are the only two things that write it.
     */
    @Volatile private var accountsCache: List<Account>? = null

    init {
        stampLegacyPassphraseForms()
    }

    /**
     * One-time, on the first launch after audit XP-013's fix: flags every saved record with the
     * passphrase form it was created with, so a record from before the fix keeps deriving the
     * same addresses ("raw" when NFKD changes its passphrase, "nfkd" otherwise) however it is
     * rewritten later. Leaves the stored list alone if it can't be read.
     */
    private fun stampLegacyPassphraseForms() {
        try {
            val json = sharedPrefs.getString(PREF_ACCOUNTS, null) ?: return
            val parsed: List<Account?> = gson.fromJson<List<Account?>>(json, ACCOUNTS_TYPE) ?: return
            if (parsed.any { it == null }) return
            val records = parsed.filterNotNull()
            if (records.none { it.passphraseForm == null }) return
            val stamped = records.map {
                if (it.passphraseForm != null) it else it.copy(passphraseForm = passphraseFormOf(it).stored)
            }
            sharedPrefs.edit().putString(PREF_ACCOUNTS, gson.toJson(stamped)).apply()
            accountsCache = null
        } catch (e: Exception) {
            // Unflagged records still resolve correctly through passphraseFormOf.
            android.util.Log.w("WalletManager", "passphrase form stamp skipped", e)
        }
    }

    private fun getAccounts(): List<Account> {
        accountsCache?.let { return it }
        val json = sharedPrefs.getString(PREF_ACCOUNTS, null) ?: return emptyList<Account>().also { accountsCache = it }
        val parsed = try {
            gson.fromJson<List<Account>>(json, ACCOUNTS_TYPE) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        // The same key's address on the network this launch runs on - an account saved as
        // kaspa: appears as its kaspatest: address on testnet and back again after (iOS
        // walletOnCurrentNetwork, bdae4b7). One key is one account on either network, so a list
        // holding both encodings keeps one (iOS updateSavedAccounts / 741c005).
        val onNetwork = parsed
            .map { it.copy(address = com.kachat.app.util.KaspaNetwork.reencode(it.address)) }
            .distinctBy { it.address.lowercase() }
        accountsCache = onNetwork
        return onNetwork
    }

    /** Thrown when an import / create would leave an account already on this device without its
     *  keys - nothing has been changed when it is thrown. */
    class AccountsNotKeptException(message: String) : Exception(message)

    private fun accountsNotKept() =
        AccountsNotKeptException(context.getString(com.kachat.app.R.string.current_account_keys_not_backed_up))

    /**
     * The accounts already saved on this device, for a write that will replace the stored list
     * (import, create). Throws [AccountsNotKeptException] when a stored list exists but can't be
     * read: [getAccounts] would read it as empty, and writing the new list would then drop every
     * other account's seed - the Android form of iOS IOS-016 (the keys must be safely kept
     * before anything is overwritten).
     */
    private fun accountsForReplacingWrite(): List<Account> {
        val json = try {
            sharedPrefs.getString(PREF_ACCOUNTS, null)
        } catch (e: Exception) {
            throw accountsNotKept()
        } ?: return emptyList()
        val parsed = try {
            gson.fromJson<List<Account>>(json, ACCOUNTS_TYPE)
        } catch (e: Exception) {
            null
        }
        @Suppress("SENSELESS_COMPARISON")
        if (parsed == null || parsed.any { it == null || it.address == null || it.mnemonic == null }) throw accountsNotKept()
        accountsCache = null
        return getAccounts()
    }

    /**
     * Writes [accounts] synchronously and reads them back; throws [AccountsNotKeptException] (with
     * the previous list put back) unless every account of [previous] survived intact.
     */
    private fun saveAccountsKeepingExisting(accounts: List<Account>, previous: List<Account>, newAddress: String) {
        val committed = try {
            sharedPrefs.edit().putString(PREF_ACCOUNTS, gson.toJson(accounts)).commit()
        } catch (e: Exception) {
            false
        }
        val back = try {
            sharedPrefs.getString(PREF_ACCOUNTS, null)?.let { gson.fromJson<List<Account>>(it, ACCOUNTS_TYPE) }
        } catch (e: Exception) {
            null
        }
        if (!committed || !accountsKeptIntact(previous, back, newAddress)) {
            runCatching { sharedPrefs.edit().putString(PREF_ACCOUNTS, gson.toJson(previous)).commit() }
            accountsCache = null
            throw accountsNotKept()
        }
        accountsCache = accounts
        refreshPrimarySpendingIndexFlow()
    }

    private fun saveAccounts(accounts: List<Account>) {
        val json = gson.toJson(accounts)
        accountsCache = accounts
        sharedPrefs.edit().putString(PREF_ACCOUNTS, json).apply()
        // saveAccounts is the single choke point every spendingAddressIndex write goes through
        // (send rotation via setSpendingAddressIndex, manual
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

    init {
        // the display-name rule (addressDisplayName) reaches the active address through the
        // companion, from code Hilt does not inject
        instance = this
    }

    /**
     * The active account's primary ("Pay in Kaspa") spending index, reactive to every change
     * (a spending payment no longer moves it - see KaspaWalletEngine.sendSpendingPayment), and the
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
        // Matched by key, not by string: the stored active address may be the other network's
        // encoding (iOS isSameAccount, 741c005).
        return getAccounts().find { com.kachat.app.util.KaspaNetwork.isSameAccount(it.address, address) }
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

    /**
     * Which index the Receive QR is currently handing out for [walletAddress], or null when it
     * has not chosen one yet. Persisted per wallet, and entirely separate from the primary:
     * receiving and spending are different jobs, and the QR must not change which address a
     * payment comes out of.
     */
    fun getReceiveAddressIndex(walletAddress: String): Int? =
        sharedPrefs.getInt("receive_index_$walletAddress", -1).takeIf { it >= 0 }

    fun setReceiveAddressIndex(walletAddress: String, index: Int) {
        sharedPrefs.edit().putInt("receive_index_$walletAddress", index).apply()
    }

    /** Indices hidden under [walletAddress] — never deletes the address itself, just what Manage Addresses filters out. */
    fun getHiddenSpendingIndices(walletAddress: String): Set<Int> =
        getAllHiddenSpendingAddresses().filter { it.walletAddress == walletAddress }.map { it.index }.toSet()

    /** Any address may be hidden - funded ones and the current one too (iOS 245a5d2): Address
     *  Visibility brings it back, and Profile's total still counts it. */
    fun setSpendingAddressHidden(walletAddress: String, index: Int, hidden: Boolean) {
        val remaining = getAllHiddenSpendingAddresses().filterNot { it.walletAddress == walletAddress && it.index == index }
        saveHiddenSpendingAddresses(if (hidden) remaining + HiddenSpendingAddress(walletAddress, index) else remaining)
    }

    /** Replaces [walletAddress]'s whole hidden set with [indices] in one write (Address
     *  Visibility > Hide All, iOS `hideAllSpendingAddresses`). */
    fun setSpendingAddressesHidden(walletAddress: String, indices: Set<Int>) {
        val others = getAllHiddenSpendingAddresses().filterNot { it.walletAddress == walletAddress }
        saveHiddenSpendingAddresses(others + indices.sorted().map { HiddenSpendingAddress(walletAddress, it) })
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
        // The accounts already here must be kept before the list is rewritten (IOS-016).
        val previous = accountsForReplacingWrite()
        val accounts = previous.toMutableList()
        // Stored in NFKD with the "nfkd" flag, as Desktop stores it (audit XP-013).
        accounts.add(
            Account(
                name,
                address,
                mnemonic.joinToString(" "),
                passphrase = nfkd(passphrase),
                passphraseForm = PassphraseForm.NFKD.stored
            )
        )
        saveAccountsKeepingExisting(accounts, previous, address)
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
        // The accounts already here must be kept before the list is rewritten: an unreadable
        // stored list, or a write that doesn't read back with them intact, stops the import with
        // nothing changed (iOS 9758568, IOS-016). Re-importing the same account is not a loss.
        val previous = accountsForReplacingWrite()
        val existing = previous.firstOrNull { it.address == address }
        val accounts = previous.filter { it.address != address }.toMutableList()
        accounts.add(
            0,
            Account(
                name = name,
                address = address,
                mnemonic = mnemonic.joinToString(" "),
                spendingAddressIndex = existing?.spendingAddressIndex ?: 0,
                maxSpendingAddressIndex = existing?.maxSpendingAddressIndex ?: 0,
                maxQuantumAddressIndex = existing?.maxQuantumAddressIndex,
                // Stored in NFKD with the "nfkd" flag, as Desktop stores it (audit XP-013).
                passphrase = nfkd(passphrase),
                sourceFamily = family.name,
                chattingAddressIndex = chattingAddressIndex,
                passphraseForm = PassphraseForm.NFKD.stored
            )
        )
        saveAccountsKeepingExisting(accounts, previous, address)
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
        accountsCache = null
        spendingAddressCacheMemo = null
        usedAddressesMemo = null
        quantumSpentMemo = null
        refreshActiveAddressFlow()
    }

    /**
     * Deletes a specific account.
     */
    fun deleteAccount(address: String) {
        val accounts = getAccounts().filterNot { com.kachat.app.util.KaspaNetwork.isSameAccount(it.address, address) }
        saveAccounts(accounts)
        val active = sharedPrefs.getString(PREF_ACTIVE_ADDRESS, null)
        if (active != null && com.kachat.app.util.KaspaNetwork.isSameAccount(active, address)) {
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
        family: WalletSourceFamily,
        form: PassphraseForm = PassphraseForm.NFKD
    ): DeterministicKey {
        val seed = seedFor(mnemonic, passphrase, form)
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

    /** Encoded for the network this launch runs on - kaspa: or kaspatest:. */
    private fun addressFromPrivateKeyBytes(privateKey: ByteArray): String =
        KaspaAddress.encode(com.kachat.app.util.KaspaNetwork.hrp, 0x00, com.kachat.app.util.Schnorr.publicKeyXOnly(privateKey))

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
        index: Int,
        form: PassphraseForm = PassphraseForm.NFKD
    ): String {
        val base = identityBaseNode(mnemonic, passphrase, family, form)
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
    private fun deriveKey(
        mnemonic: List<String>,
        accountIndex: Int = 1,
        addressIndex: Int = 0,
        passphrase: String = "",
        form: PassphraseForm = PassphraseForm.NFKD
    ): DeterministicKey {
        // The optional BIP39 passphrase feeds the PBKDF2 seed derivation ([seedFor]). Empty
        // string = no passphrase = the account's historical derivation.
        val seed = seedFor(mnemonic, passphrase, form)
        val masterKey = HDKeyDerivation.createMasterPrivateKey(seed)

        val key44h = HDKeyDerivation.deriveChildKey(masterKey, ChildNumber(44, true))
        val keyKaspaH = HDKeyDerivation.deriveChildKey(key44h, ChildNumber(111111, true))
        val keyAccountH = HDKeyDerivation.deriveChildKey(keyKaspaH, ChildNumber(accountIndex, true))
        val keyChain0 = HDKeyDerivation.deriveChildKey(keyAccountH, ChildNumber(0, false))
        return HDKeyDerivation.deriveChildKey(keyChain0, ChildNumber(addressIndex, false))
    }

    /** The active account's BIP39 passphrase (empty when it has none), used to re-derive its keys. */
    private fun activePassphrase(): String = getActiveAccount()?.passphrase ?: ""

    /** How the active account's seed is made from its passphrase (audit XP-013). */
    private fun activePassphraseForm(): PassphraseForm =
        getActiveAccount()?.let { passphraseFormOf(it) } ?: PassphraseForm.NFKD

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

    /** The active account's BIP39 passphrase, or null when it has none - part of the backup: the
     *  words alone restore a different, empty wallet (iOS 3076ab7, IOS-017). */
    fun getActivePassphrase(): String? = activePassphrase().takeIf { it.isNotEmpty() }

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
        val baseNode = identityBaseNode(mnemonic, account.passphrase ?: "", family, passphraseFormOf(account))
        return identityPrivateKeyBytes(baseNode, account.chattingAddressIndex, family)
            ?: throw IllegalStateException("This wallet type has no address at index ${account.chattingAddressIndex}.")
    }

    // --- Spending address (accountIndex=1 branch) ---------------------------------------
    // Separate from the identity address above: a payment whose From is a spending address
    // spends from it with its own key and routes change to a never-used next index (see
    // KaspaWalletEngine.sendSpendingPayment). Messaging (handshakes/chat messages) is untouched —
    // still identity-address-sourced via getAddress()/getPrivateKeyBytes() above.

    private fun activeMnemonicWords(): List<String> =
        getActiveMnemonic()?.split(" ") ?: throw IllegalStateException("No active account")

    /** One derived spending-chain ADDRESS (never a key), persisted so repeat lookups skip the
     *  PBKDF2 seed derivation entirely. Addresses are deterministic per (wallet, index) — the
     *  wallet address already encodes mnemonic+passphrase, so entries never go stale. Flat
     *  (walletAddress, index) keying, same pattern as [HiddenSpendingAddress]. */
    private data class CachedSpendingAddress(val walletAddress: String, val index: Int, val address: String)

    /** Same memo as [accountsCache], for the blob [deriveSpendingAddress] consults on every
     *  single index lookup. Invalidated by [cacheSpendingAddresses] and [wipe]. */
    @Volatile private var spendingAddressCacheMemo: List<CachedSpendingAddress>? = null

    private fun getAllCachedSpendingAddresses(): List<CachedSpendingAddress> {
        spendingAddressCacheMemo?.let { return it }
        val json = sharedPrefs.getString(PREF_SPENDING_ADDRESS_CACHE, null)
            ?: return emptyList<CachedSpendingAddress>().also { spendingAddressCacheMemo = it }
        val parsed = try {
            gson.fromJson<List<CachedSpendingAddress>>(json, SPENDING_CACHE_TYPE) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        spendingAddressCacheMemo = parsed
        return parsed
    }

    /** Every spending address this phone has derived, for every account it holds (the saved
     *  ones too, whose seeds aren't decrypted) - what a payment_notice may never claim as a
     *  contact's payment (IOS-083, iOS `cachedSpendingAddresses(ofAccount:)`). */
    fun allCachedSpendingAddresses(): List<String> = getAllCachedSpendingAddresses().map { it.address }

    private fun cacheSpendingAddresses(walletAddress: String, byIndex: Map<Int, String>) {
        if (byIndex.isEmpty()) return
        val existing = getAllCachedSpendingAddresses()
            .filterNot { it.walletAddress == walletAddress && byIndex.containsKey(it.index) }
        val added = byIndex.map { (index, address) -> CachedSpendingAddress(walletAddress, index, address) }
        val merged = existing + added
        spendingAddressCacheMemo = merged
        sharedPrefs.edit().putString(PREF_SPENDING_ADDRESS_CACHE, gson.toJson(merged)).apply()
    }

    fun deriveSpendingAddress(index: Int): String {
        val walletAddress = getActiveAccount()?.address
        if (walletAddress != null) {
            getAllCachedSpendingAddresses()
                .firstOrNull { it.walletAddress == walletAddress && it.index == index }
                ?.let { return it.address }
        }
        val address = if (isQuantumIndex(index)) {
            // the m/22' node, not the legacy chain (same cache: the index spaces cannot collide)
            quantumAddress(quantumChainKey(), index - QUANTUM_INDEX_BASE)
        } else {
            addressFromKey(deriveKey(activeMnemonicWords(), accountIndex = 1, addressIndex = index, passphrase = activePassphrase(), form = activePassphraseForm()))
        }
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
    private fun spendingChainKey(): DeterministicKey =
        spendingChainNode(seedFor(activeMnemonicWords(), activePassphrase(), activePassphraseForm()))

    // --- Quantum-safe chain (KIP-22) --------------------------------------------------------
    //
    // Path m/22'/111111'/0'/0/<n> (KIP-0022), from the same seed. The address is the P2SH of the
    // redeem script `<x-only key> OP_CHECKSIG`, so the chain shows only a hash of the key until
    // the address spends - a quantum attacker reading addresses learns nothing. Public index
    // [QUANTUM_INDEX_BASE] + n, with its own bound ([Account.maxQuantumAddressIndex]). The
    // chatting address is unchanged by design (its key is published for encryption).

    /** The quantum chain node (m/22'/111111'/0'/0), one seed derivation per call like
     *  [spendingChainKey]. */
    private fun quantumChainKey(): DeterministicKey =
        quantumChainNode(seedFor(activeMnemonicWords(), activePassphrase(), activePassphraseForm()))

    /** The active account's highest revealed quantum chain index; -1 before the first one. */
    fun maxQuantumAddressIndex(): Int = getActiveAccount()?.let { maxQuantumIndex(it) } ?: -1

    /** Raises [walletAddress]'s quantum bound to [quantumIndex] (chain index, not public). Only
     *  ever grows. */
    fun ensureMaxQuantumAddressIndexAtLeast(walletAddress: String, quantumIndex: Int) {
        val accounts = getAccounts().map {
            if (it.address == walletAddress && quantumIndex > maxQuantumIndex(it)) it.copy(maxQuantumAddressIndex = quantumIndex) else it
        }
        saveAccounts(accounts)
    }

    /** One past every quantum slot ever revealed, in public index space - where a spend's
     *  change goes (fresh and hidden-keyed, whatever chain the spend came from). Also past any
     *  slot a send still in flight picked for its change ([pickFreshQuantumChange]), so nothing
     *  else is handed the same one before that send reveals it. */
    fun nextFreshQuantumIndex(): Int = synchronized(spendingIndexAllocationLock) {
        nextFreshQuantumIndex(maxQuantumAddressIndex(), getActiveAccount()?.let { pendingQuantumChange[it.address] }.orEmpty())
    }

    /**
     * Checks the fresh quantum slot on chain before it is handed out (iOS
     * `verifiedFreshQuantumIndex`, da2c8e2 / IOS-101 / AND-049): the chain's end on this phone
     * can sit below slots used elsewhere (another device, a restore that ran before the sweep
     * looked far enough). While [isUsed] (the REST history check,
     * WalletService.spendingAddressUsedState) says the slot has history it is skipped and the end
     * raised, up to [MAX_USED_FRESH_QUANTUM_SKIPS] times; a check that can't be answered takes the
     * slot as is. Returns the slot (public index) - what [nextFreshQuantumIndex],
     * [pickFreshQuantumChange] and [allocateFreshQuantumIndices] hand out next, unless another
     * allocation takes it first: they stay the only allocators, under
     * [spendingIndexAllocationLock], so the in-flight guard is unchanged. Derives from the seed
     * off the main thread.
     *
     * The probe belongs to the account it started for (iOS 6891935, IOS-109 / AND-056, the
     * sweep's rule from IOS-107): [owner] (default: the active account) is taken when it starts,
     * its slots are derived from that account's own quantum chain and a used slot raises THAT
     * account's chain end, even if another account is active when a probe returns. Once the
     * account has been removed it writes nothing more.
     */
    suspend fun verifyFreshQuantumIndex(owner: String? = null, isUsed: suspend (String) -> Boolean?): Int {
        val account = spendingSweepAccount(owner) ?: return nextFreshQuantumIndex()
        return verifyFreshQuantumIndex(account, isUsed)
    }

    /** [verifyFreshQuantumIndex] for an account already taken (Generate New Address reveals on
     *  the same one it probed). */
    suspend fun verifyFreshQuantumIndex(account: SpendingSweepAccount, isUsed: suspend (String) -> Boolean?): Int =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            firstFreshQuantumSlot(
                next = { nextFreshQuantumIndex(account) },
                address = { index -> deriveSpendingAddresses(listOf(index), account)[index] },
                isUsed = isUsed,
                skip = { index -> skipUsedQuantumSlot(account, index) },
            )
        }

    /** [nextFreshQuantumIndex] for [account], whichever account is active. */
    private fun nextFreshQuantumIndex(account: SpendingSweepAccount): Int = synchronized(spendingIndexAllocationLock) {
        nextFreshQuantumIndex(sweepAccountRecord(account)?.let { maxQuantumIndex(it) } ?: -1, pendingQuantumChange[account.owner].orEmpty())
    }

    /** Raises [account]'s quantum bound past used public [index] (iOS
     *  `setMaxQuantumAddressIndex(index - quantumIndexBase, owner:)`), keeping hidden any slot a
     *  send in flight picked that the bound newly passes - as [allocateFreshQuantumIndices] does.
     *  False, writing nothing, once [account] has been removed (IOS-109 / AND-056). */
    private fun skipUsedQuantumSlot(account: SpendingSweepAccount, index: Int): Boolean = synchronized(spendingIndexAllocationLock) {
        val record = sweepAccountRecord(account) ?: return@synchronized false
        android.util.Log.i("WalletManager", "Quantum slot ${index - QUANTUM_INDEX_BASE} already has history - skipping it")
        val revealedMax = maxQuantumIndex(record)
        pendingQuantumChange[record.address].orEmpty()
            .filter { it - QUANTUM_INDEX_BASE > revealedMax && it < index }
            .forEach { setSpendingAddressHidden(record.address, it, true) }
        ensureMaxQuantumAddressIndexAtLeast(record.address, index - QUANTUM_INDEX_BASE)
        true
    }

    /** The KIP-22 redeem script of a quantum index's key, pushed after each signature when it
     *  spends; null for a legacy index. Derives from the seed. */
    fun quantumRedeemScript(index: Int): ByteArray? {
        if (!isQuantumIndex(index)) return null
        return quantumRedeemScript(HDKeyDerivation.deriveChildKey(quantumChainKey(), ChildNumber(index - QUANTUM_INDEX_BASE, false)))
    }

    /**
     * Reveals a brand-new quantum-safe slot and returns its public index - what Generate New
     * Address makes from now on (iOS `generateNextQuantumAddress`). Always extends the chain: a
     * never-spent-from address is the whole point, so nothing is recycled here.
     *
     * Past every change slot a send still has in flight, too ([nextFreshQuantumIndex], under
     * [spendingIndexAllocationLock] like every other allocation): Generate must never hand out the
     * slot a send picked for its change. Those it passes stay hidden, as a reservation leaves them
     * ([allocateFreshQuantumIndices]); their send lists them if it goes through. iOS has no
     * in-flight tracking - this is Android's own guarantee.
     */
    fun generateNextQuantumAddress(walletAddress: String): Int = synchronized(spendingIndexAllocationLock) {
        val account = getAccounts().firstOrNull { it.address == walletAddress } ?: getActiveAccount()
        val revealedMax = account?.let { maxQuantumIndex(it) } ?: -1
        val inFlight = pendingQuantumChange[walletAddress].orEmpty()
        val index = nextFreshQuantumIndex(revealedMax, inFlight)
        inFlight.filter { it - QUANTUM_INDEX_BASE > revealedMax }
            .forEach { setSpendingAddressHidden(walletAddress, it, true) }
        ensureMaxQuantumAddressIndexAtLeast(walletAddress, index - QUANTUM_INDEX_BASE)
        setSpendingAddressHidden(walletAddress, index, hidden = false)
        index
    }

    /** The quantum slots (public index) sends still in flight picked for their change on the
     *  active account - taken, so never recycled or handed out ([pickFreshQuantumChange]). */
    fun inFlightQuantumChange(): Set<Int> = synchronized(spendingIndexAllocationLock) {
        getActiveAccount()?.let { pendingQuantumChange[it.address]?.toSet() }.orEmpty()
    }

    /** Quantum addresses this phone has spent FROM, lowercased: the spend put their key on chain
     *  for good, so the shield comes off (iOS `qspent`). Address-keyed like the used cache - an
     *  address is one wallet's. Never unmarked: a revealed key cannot be unrevealed. */
    @Volatile private var quantumSpentMemo: Set<String>? = null

    private fun quantumSpentAddresses(): Set<String> {
        quantumSpentMemo?.let { return it }
        val json = sharedPrefs.getString(PREF_QUANTUM_SPENT_ADDRESSES, null)
            ?: return emptySet<String>().also { quantumSpentMemo = it }
        val parsed = try {
            gson.fromJson<Set<String>>(json, USED_ADDRESSES_TYPE) ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
        quantumSpentMemo = parsed
        return parsed
    }

    /** Marks [address]'s key as revealed by a spend - the moment the app sends from it, or when
     *  the chain shows a spend made elsewhere (WalletService.quantumAddressSpentState).
     *
     *  [owner]: the account whose address spent, when the caller started before an await the
     *  person may have switched accounts across (iOS ca09dd0, IOS-109 / AND-056). The mark is
     *  address-keyed here, so it is that account's whichever is active; nothing is written once
     *  [owner] has been removed. Null = the active account. */
    fun markQuantumAddressSpent(address: String, owner: String? = null) {
        if (!ownerStillSaved(getAccounts(), owner)) return
        val lowered = address.trim().lowercase()
        if (lowered.isEmpty()) return
        val current = quantumSpentAddresses()
        if (lowered in current) return
        val updated = current + lowered
        quantumSpentMemo = updated
        sharedPrefs.edit().putString(PREF_QUANTUM_SPENT_ADDRESSES, gson.toJson(updated)).apply()
    }

    fun isQuantumAddressSpentLocally(address: String): Boolean = address.trim().lowercase() in quantumSpentAddresses()

    /** Every revealed spending address: the legacy chain (0..its bound), then the quantum chain
     *  (0..its bound), derived with one seed computation per chain - for own-address watch sets
     *  and received-pool validation. Empty if there's no active account. */
    fun allSpendingAddresses(): List<String> {
        val account = getActiveAccount() ?: return emptyList()
        val maxIndex = legacyMaxSpendingIndex(account)
        val quantumMax = maxQuantumIndex(account)
        val quantumRange = QUANTUM_INDEX_BASE until (QUANTUM_INDEX_BASE + quantumMax + 1)
        // Cache-first: this runs on the 30s address-activity poll, and the full PBKDF2 seed
        // derivation per pass was pure waste once every index has been derived once.
        val cached = getAllCachedSpendingAddresses()
            .filter { it.walletAddress == account.address }
            .associate { it.index to it.address }
        if ((0..maxIndex).all { cached.containsKey(it) } && quantumRange.all { cached.containsKey(it) }) {
            return (0..maxIndex).map { cached.getValue(it) } + quantumRange.map { cached.getValue(it) }
        }
        val chainKey = try { spendingChainKey() } catch (e: Exception) { return emptyList() }
        val derived = (0..maxIndex).associateWith { index ->
            cached[index] ?: addressFromKey(HDKeyDerivation.deriveChildKey(chainKey, ChildNumber(index, false)))
        }.toMutableMap()
        if (quantumMax >= 0) {
            val quantumKey = try { quantumChainKey() } catch (e: Exception) { null }
            if (quantumKey != null) {
                for (index in quantumRange) derived[index] = cached[index] ?: quantumAddress(quantumKey, index - QUANTUM_INDEX_BASE)
            }
        }
        cacheSpendingAddresses(account.address, derived)
        return derived.keys.sorted().map { derived.getValue(it) }
    }

    /**
     * Which of this wallet's own addresses [address] is: "Chatting Address", or a spending
     * address's name ("Address #3", or the name the person gave it). Null for anyone else's.
     * The address-to-index map is built once per account and address count, hidden addresses
     * included, from the derivation cache ([deriveSpendingAddresses]); iOS 56f004f
     * `ownAddressLabel(for:)`. Derives on a miss, so call it off the main thread.
     */
    fun ownAddressLabel(address: String): String? {
        val target = address.trim().lowercase()
        if (target.isEmpty()) return null
        val account = getActiveAccount() ?: return null
        val wallet = account.address.lowercase()
        if (target == wallet) return context.getString(com.kachat.app.R.string.chatting_address)
        val maxIndex = maxOf(0, legacyMaxSpendingIndex(account))
        val quantumMax = maxQuantumIndex(account)
        val key = "$wallet|$maxIndex|q$quantumMax"
        val cached = ownAddressIndexCache
        val map = if (cached != null && cached.first == key) {
            cached.second
        } else {
            // both chains: the quantum range only once one has been revealed
            val indices = (0..maxIndex) + (QUANTUM_INDEX_BASE until QUANTUM_INDEX_BASE + quantumMax + 1)
            val built = deriveSpendingAddresses(indices).entries.associate { (index, spending) -> spending.lowercase() to index }
            // a failed derivation isn't remembered: the next call tries again
            if (built.isNotEmpty()) ownAddressIndexCache = key to built
            built
        }
        val index = map[target] ?: return null
        val named = getSpendingAddressLabels(account.address)[index]?.trim().orEmpty()
        return named.ifEmpty { spendingAddressTitle(index) }
    }

    /** [ownAddressLabel]'s address-to-index map, keyed by "wallet|highest index|q highest quantum index". */
    @Volatile private var ownAddressIndexCache: Pair<String, Map<String, Int>>? = null

    /**
     * [allSpendingAddresses] plus the chatting address, lowercased - MEMOIZED per account and
     * chain lengths (the same key as [ownAddressLabel]'s cache; iOS d80c497
     * `ownAddressSetLowercased`). The contract filter
     * ([com.kachat.app.repository.ChatRepository.isKachatContractTransaction]) and the payment
     * sync's own-address checks ask this per TRANSACTION, and [allSpendingAddresses] reads the
     * whole cached address list (or derives) every call. Derives on a miss, so call it off the
     * main thread.
     */
    fun ownAddressSetLowercased(): Set<String> {
        val account = getActiveAccount() ?: return emptySet()
        val wallet = account.address.trim().lowercase()
        val key = "$wallet|${legacyMaxSpendingIndex(account)}|q${maxQuantumIndex(account)}"
        ownAddressSetCache?.let { (cachedKey, set) -> if (cachedKey == key) return set }
        val spending = runCatching { allSpendingAddresses() }.getOrDefault(emptyList())
        val set = HashSet<String>(spending.size + 1)
        spending.mapNotNullTo(set) { it.trim().lowercase().takeIf { s -> s.isNotEmpty() } }
        set.add(wallet)
        // a failed derivation isn't remembered: the next call tries again
        if (spending.isNotEmpty()) ownAddressSetCache = key to set
        return set
    }

    /** [ownAddressSetLowercased]'s set, keyed like [ownAddressIndexCache]. */
    @Volatile private var ownAddressSetCache: Pair<String, Set<String>>? = null

    /**
     * Addresses for an arbitrary set of indices, derived with ONE seed computation.
     *
     * [deriveSpendingAddress] is the single-lookup form and redoes the BIP-39 seed derivation
     * every call - PBKDF2, 2048 rounds of HMAC-SHA512, deliberately slow. That is fine once and
     * ruinous fifty times: Address Visibility derived a whole page of rows that way from inside
     * composition, on the main thread, which is what made the screen unresponsive enough that a
     * row could not be tapped. Discovery had the same shape.
     *
     * Cache-first, like [allSpendingAddresses], so a range that has already been derived costs
     * nothing at all. Unlike that one this takes any indices, not just 0..revealed bound.
     */
    fun deriveSpendingAddresses(indices: Iterable<Int>): Map<Int, String> {
        val account = getActiveAccount() ?: return emptyMap()
        return deriveCachedSpendingAddresses(account.address, indices, cacheResults = true) { missing ->
            deriveChainAddresses(missing, { spendingChainKey() }, { quantumChainKey() })
        }
    }

    /**
     * [deriveSpendingAddresses] for the Discover sweep's own account (iOS e571f45
     * `sweepAddresses(inRange:account:)`, IOS-107): [account]'s chain nodes and its own slice of
     * the address cache, whichever account is active now. Nothing is cached once the account has
     * been removed - its results are dropped anyway.
     */
    fun deriveSpendingAddresses(indices: Iterable<Int>, account: SpendingSweepAccount): Map<Int, String> =
        deriveCachedSpendingAddresses(account.owner, indices, cacheResults = sweepAccountStillSaved(account)) { missing ->
            account.derive(missing)
        }

    /** Cache-first derivation under [walletAddress]'s slice of the address cache; [derive] fills
     *  the misses (throwing = nothing derived). */
    private fun deriveCachedSpendingAddresses(
        walletAddress: String,
        indices: Iterable<Int>,
        cacheResults: Boolean,
        derive: (List<Int>) -> Map<Int, String>,
    ): Map<Int, String> {
        val wanted = indices.filter { it >= 0 }.distinct()
        if (wanted.isEmpty()) return emptyMap()
        val cached = getAllCachedSpendingAddresses()
            .filter { it.walletAddress == walletAddress }
            .associate { it.index to it.address }
        val missing = wanted.filter { !cached.containsKey(it) }
        if (missing.isEmpty()) return wanted.associateWith { cached.getValue(it) }

        val derived = try { derive(missing) } catch (e: Exception) { return emptyMap() }
        if (cacheResults) cacheSpendingAddresses(walletAddress, derived)
        return wanted.associateWith { cached[it] ?: derived.getValue(it) }
    }

    /**
     * The Discover sweep's own copy of the account it started for (iOS e571f45
     * `SpendingSweepAccount`, IOS-107): that account's address and what both its chain nodes come
     * from - legacy m/44'/111111'/1'/0 and quantum m/22'/111111'/0'/0 - taken once at the start
     * ([spendingSweepAccount]). Every derivation and write of the sweep goes through it, so
     * switching accounts mid-sweep can't send results to the other account; they still land in
     * this one's keys. The nodes are made on first use, from one seed computation, so a sweep
     * whose addresses are all cached never runs the seed's PBKDF2.
     */
    class SpendingSweepAccount internal constructor(
        val owner: String,
        private val words: List<String>,
        private val passphrase: String,
        private val form: PassphraseForm,
    ) {
        private val seed: ByteArray by lazy { seedFor(words, passphrase, form) }
        private val changeKey: DeterministicKey by lazy { spendingChainNode(seed) }
        private val chainKey: DeterministicKey by lazy { quantumChainNode(seed) }

        /** [indices]' addresses on this account's own chains (no cache). Throws when the seed
         *  can't be made. */
        internal fun derive(indices: Collection<Int>): Map<Int, String> =
            deriveChainAddresses(indices, { changeKey }, { chainKey })
    }

    /** The sweep's account: [owner]'s saved record (default: the active account), taken now -
     *  null when no such account is saved. */
    fun spendingSweepAccount(owner: String? = null): SpendingSweepAccount? {
        val record = (if (owner == null) getActiveAccount() else getAccounts().find { it.address == owner }) ?: return null
        return SpendingSweepAccount(record.address, record.mnemonic.split(" "), record.passphrase ?: "", passphraseFormOf(record))
    }

    /** [account]'s saved record as it stands now (its bounds), whether or not it is the active
     *  one; null once it has been removed. */
    fun sweepAccountRecord(account: SpendingSweepAccount): Account? = getAccounts().find { it.address == account.owner }

    /** The sweep's results are dropped only if its account was removed meanwhile - a mere switch
     *  keeps them (iOS e571f45 `sweepAccountStillSaved`, IOS-107). */
    fun sweepAccountStillSaved(account: SpendingSweepAccount): Boolean = sweepAccountRecord(account) != null

    // --- "Ever used" cache: monotonic (a used address can never become unused), so positive
    // answers persist forever and skip the network history probe — mirrors iOS. Address-keyed:
    // used-ness is intrinsic to the address, not the wallet.
    /** Same memo again, for the set every address row asks about. */
    @Volatile private var usedAddressesMemo: Set<String>? = null

    private fun usedAddresses(): Set<String> {
        usedAddressesMemo?.let { return it }
        val json = sharedPrefs.getString(PREF_USED_SPENDING_ADDRESSES, null)
            ?: return emptySet<String>().also { usedAddressesMemo = it }
        val parsed = try {
            gson.fromJson<Set<String>>(json, USED_ADDRESSES_TYPE) ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
        usedAddressesMemo = parsed
        return parsed
    }

    fun isAddressKnownUsed(address: String): Boolean = address in usedAddresses()

    fun markAddressUsed(address: String) {
        if (address.isBlank()) return
        val current = usedAddresses()
        if (address in current) return
        val updated = current + address
        usedAddressesMemo = updated
        sharedPrefs.edit().putString(PREF_USED_SPENDING_ADDRESSES, gson.toJson(updated)).apply()
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

    /** Guards every fresh-index allocation (payment change AND Request Payment reservations) so
     *  two concurrent allocators can never hand out the same index — see [allocateFreshQuantumIndices]
     *  and [pickFreshQuantumChange]. */
    private val spendingIndexAllocationLock = Any()

    /** Quantum slots (public index) that sends still in flight picked for their change, per
     *  account: not revealed yet, but taken - see [pickFreshQuantumChange]. Guarded by
     *  [spendingIndexAllocationLock]. */
    private val pendingQuantumChange = HashMap<String, MutableSet<Int>>()

    /**
     * Picks the fresh quantum-safe (KIP-22) slot a send's change goes to (iOS
     * `nextFreshQuantumIndex`), WITHOUT revealing it: the send reveals it once the network takes
     * the transaction ([finishQuantumChangePick]), so a failed send burns no slot. iOS picks with
     * nothing held; here the pick is held as in flight until finished, so two sends at once (or a
     * Request Payment reservation meanwhile) never get the same slot. Null when the address can't
     * be derived - the caller then sends a legacy source's change back to it, as iOS does, and
     * stops a quantum source's send (iOS da2c8e2, IOS-104).
     *
     * Slot and address both come from [account], the one its probe ran on (iOS fb2323e
     * `verifiedFreshQuantumSlot`, IOS-109 / AND-056 follow-up): its bound, its in-flight picks,
     * its own quantum chain - whichever account is active now. Null, holding nothing, once
     * [account] has been removed. Callers go through [verifiedFreshQuantumChange]. Derives from
     * the seed, so call it off the main thread.
     */
    fun pickFreshQuantumChange(account: SpendingSweepAccount): QuantumChangePick? = synchronized(spendingIndexAllocationLock) {
        val revealedMax = sweepAccountRecord(account)?.let { maxQuantumIndex(it) }
        val index = freshQuantumAllocation(revealedMax, pendingQuantumChange[account.owner].orEmpty(), 1)?.single()
            ?: return@synchronized null
        val address = deriveSpendingAddresses(listOf(index), account)[index] ?: return@synchronized null
        pendingQuantumChange.getOrPut(account.owner) { HashSet() }.add(index)
        QuantumChangePick(account.owner, index, address)
    }

    /**
     * A send's fresh change slot, checked on chain and picked on ONE account (iOS fb2323e
     * `verifiedFreshQuantumSlot`, IOS-109 / AND-056 follow-up): [owner] (default: the active
     * account - the one the send's source was derived from) is taken now, probed
     * ([verifyFreshQuantumIndex]) and picked ([pickFreshQuantumChange]) there, so an account
     * switch while the probe is out never hands this send the other account's unprobed slot.
     * Null when no such account is saved, it was removed meanwhile, or the address can't be
     * derived.
     */
    suspend fun verifiedFreshQuantumChange(owner: String? = null, isUsed: suspend (String) -> Boolean?): QuantumChangePick? {
        val account = spendingSweepAccount(owner) ?: return null
        verifyFreshQuantumIndex(account, isUsed)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { pickFreshQuantumChange(account) }
    }

    /** A [pickFreshQuantumChange] pick: the account it was picked under, the slot's public index
     *  and its address. */
    data class QuantumChangePick(val walletAddress: String, val index: Int, val address: String)

    /**
     * Ends a [pickFreshQuantumChange] pick. [reveal] (the send went through - its change is on the
     * slot): the quantum bound rises to cover it, the slots it newly passes are kept hidden and it
     * is listed in Manage Addresses (iOS `revealSpendingAddress(at:)`). Without [reveal] the slot
     * is simply given back. A caller that also rotates the primary onto the slot (withdraw, KNS
     * transfer) does that after revealing it.
     *
     * The reveal goes to the pick's own account - the one the send started on - even if another
     * is active when the network answers, and nowhere once it has been removed (iOS ca09dd0,
     * IOS-109 / AND-056).
     */
    fun finishQuantumChangePick(pick: QuantumChangePick, reveal: Boolean) {
        synchronized(spendingIndexAllocationLock) {
            if (reveal) revealSpendingAddress(pick.walletAddress, pick.index)
            pendingQuantumChange[pick.walletAddress]?.let { picks ->
                picks.remove(pick.index)
                if (picks.isEmpty()) pendingQuantumChange.remove(pick.walletAddress)
            }
        }
    }

    /**
     * Lists spending [index] on [owner]'s Manage Addresses (iOS `revealSpendingAddress(at:owner:)`,
     * ca09dd0): its own chain's bound raised to cover it, the slots that newly covers kept hidden
     * (so revealing a far-out row doesn't flood the list), and [index] itself un-hidden - written
     * to [owner]'s record and hidden set whichever account is active, so a send that ends after
     * an account switch lists its change on the account it started on (IOS-109 / AND-056). False,
     * writing nothing, once [owner] has been removed.
     */
    fun revealSpendingAddress(owner: String, index: Int): Boolean = synchronized(spendingIndexAllocationLock) {
        val accounts = getAccounts()
        val stored = accounts.find { it.address == owner }
        val (record, hidden) = spendingReveal(stored, getHiddenSpendingIndices(owner), index) ?: return@synchronized false
        if (record != stored) saveAccounts(accounts.map { if (it.address == owner) record else it })
        setSpendingAddressesHidden(owner, hidden)
        true
    }

    /**
     * Reveals and returns [count] brand-new QUANTUM-SAFE slots (KIP-22) in one atomic step — what
     * Request Payment's Fresh Address reserves (iOS `reserveFreshSpendingAddresses`: fresh
     * addresses handed out are exactly what the quantum chain is for - never revealed, never
     * funded, key hidden until they spend). Indices start strictly past the quantum chain's
     * all-time max and past every change slot a send still has in flight
     * ([pickFreshQuantumChange]), and the max is bumped to cover them BEFORE returning, so: (a)
     * they have never been revealed, funded, or offered before, and (b) no later change address or
     * reservation can ever land on the same index — both go through [spendingIndexAllocationLock].
     * Returns (public index, address) pairs; empty on failure. The legacy chain is never grown
     * from here.
     */
    fun allocateFreshQuantumIndices(count: Int, account: SpendingSweepAccount): List<Pair<Int, String>> = synchronized(spendingIndexAllocationLock) {
        // [account]'s own slots, bound and in-flight picks - the account its probe ran on, even
        // if another is active now; nothing is reserved or written once it has been removed (iOS
        // fb2323e, IOS-109 / AND-056 follow-up)
        val record = sweepAccountRecord(account) ?: return@synchronized emptyList()
        val revealedMax = maxQuantumIndex(record)
        val inFlight = pendingQuantumChange[account.owner].orEmpty()
        // past any slot a send in flight picked for its change, too - kept hidden as the bound
        // passes it, as a reveal past it would; its send lists it if it goes through
        val indices = freshQuantumAllocation(revealedMax, inFlight, count) ?: return@synchronized emptyList()
        val derived = try { account.derive(indices) } catch (e: Exception) { return@synchronized emptyList() }
        val result = indices.map { index -> index to (derived[index] ?: return@synchronized emptyList()) }
        inFlight.filter { it - QUANTUM_INDEX_BASE > revealedMax }
            .forEach { setSpendingAddressHidden(account.owner, it, true) }
        cacheSpendingAddresses(account.owner, result.toMap())
        ensureMaxQuantumAddressIndexAtLeast(account.owner, indices.last() - QUANTUM_INDEX_BASE)
        result
    }

    /**
     * The address [allocateFreshQuantumIndices] hands out next - the quantum slot after the last
     * revealed one - DERIVED, not reserved: Request Payment shows it under Fresh Address before
     * anything is copied, shared or sent, so opening the screen and cancelling uses no address up
     * (iOS `spendingAddress(at: nextFreshQuantumIndex())`). Slot and address from [account], the
     * one its probe ran on (iOS fb2323e `verifiedFreshQuantumSlot()?.address`, IOS-109 / AND-056
     * follow-up). Null when it can't be derived or [account] has been removed. Derives from the
     * seed, so call it off the main thread.
     */
    fun nextFreshSpendingAddress(account: SpendingSweepAccount): String? {
        val index = synchronized(spendingIndexAllocationLock) {
            freshQuantumAllocation(sweepAccountRecord(account)?.let { maxQuantumIndex(it) }, pendingQuantumChange[account.owner].orEmpty(), 1)?.single()
        } ?: return null
        return deriveSpendingAddresses(listOf(index), account)[index]
    }

    /** The signing key of spending index [index], on whichever chain it lives. */
    fun getSpendingPrivateKeyBytes(index: Int): ByteArray =
        if (isQuantumIndex(index)) {
            HDKeyDerivation.deriveChildKey(quantumChainKey(), ChildNumber(index - QUANTUM_INDEX_BASE, false)).privKeyBytes
        } else {
            deriveKey(activeMnemonicWords(), accountIndex = 1, addressIndex = index, passphrase = activePassphrase(), form = activePassphraseForm()).privKeyBytes
        }

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

    /** Sets an explicit index as the one "Pay in Kaspa" sources from — used by the wallet-import
     *  gap-limit scan, by manually activating an address from the Manage Addresses screen, and by
     *  a send's primary following its change. A quantum index extends its own chain; the legacy
     *  max must never see base-space values (iOS `setActiveSpendingAddress`).
     *
     *  [address] is the account whose primary moves - for a send, the one it started on, even if
     *  another is active by the time the network answers (iOS ca09dd0 `owner`, IOS-109 / AND-056).
     *  Another account's move changes nothing on screen: [primarySpendingIndexFlow] follows the
     *  active account only. False, writing nothing, once [address] has been removed. */
    fun setSpendingAddressIndex(address: String, index: Int): Boolean {
        val accounts = withSpendingAddressIndex(getAccounts(), address, index) ?: return false
        saveAccounts(accounts)
        return true
    }

    /** Derives one more spending-chain address for the Manage Addresses screen to show, without changing which one is currently active. Returns the new highest index. */
    fun generateNextSpendingAddress(address: String): Int {
        var newMax = 0
        val accounts = getAccounts().map {
            if (it.address == address) {
                newMax = legacyMaxSpendingIndex(it) + 1
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
            // Legacy chain only: a quantum index here would make the legacy chain a million long.
            if (it.address == address && !isQuantumIndex(minIndex) && minIndex > it.maxSpendingAddressIndex) {
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
            identityBaseNode(account.mnemonic.split(" "), account.passphrase ?: "", family, passphraseFormOf(account))
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
        val newAddress = deriveIdentityAddress(mnemonic, account.passphrase ?: "", family, index, passphraseFormOf(account))
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

    /** The alias I watch for incoming deterministic-alias messages from this contact. Throws
     *  when the active account changed between reading its address and its key (iOS dfaba68):
     *  an alias is never derived for one account from another's key. */
    fun myDeterministicAlias(contactAddress: String): String =
        myDeterministicAlias(contactAddress, getPrivateKeyBytes(), getAddress())

    /** [myDeterministicAlias] for [myAddress] with [privateKey] - refused (throws) unless the
     *  key is [myAddress]'s own ([com.kachat.app.util.AccountKey]). */
    fun myDeterministicAlias(contactAddress: String, privateKey: ByteArray, myAddress: String): String {
        check(com.kachat.app.util.AccountKey.belongsTo(privateKey, myAddress)) { "Not this account's key" }
        val theirPubKey = KaspaAddress.decode(contactAddress).second
        val myPubKey = KaspaAddress.decode(myAddress).second
        return com.kachat.app.util.KasiaCipher.deriveDeterministicAlias(privateKey, theirPubKey, contextXOnlyPubKey = myPubKey)
    }

    /** The alias I tag outgoing deterministic-alias messages to this contact with. */
    fun theirDeterministicAlias(contactAddress: String): String {
        val theirPubKey = KaspaAddress.decode(contactAddress).second
        return com.kachat.app.util.KasiaCipher.deriveDeterministicAlias(getPrivateKeyBytes(), theirPubKey, contextXOnlyPubKey = theirPubKey)
    }
}
