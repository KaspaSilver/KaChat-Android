package com.kachat.app.models

import com.kachat.app.R

/**
 * Identity derivation-path family of the wallet a seed phrase was imported from. Different Kaspa
 * wallets put the same seed's funds and KNS domains on different BIP32 branches; import checks
 * every family for the words and opens the likeliest (iOS da467165,
 * [com.kachat.app.services.WalletService.findImportAccounts]). Ported from iOS's
 * `WalletSourceFamily` (`Services/WalletManager.swift`), whose rules were replicated from
 * KasWare's ADDRESS_TYPES/RESTORE_WALLETS constants and its `hd-keyring.ts` `_pubkeyFromIndex`
 * derivation switch.
 *
 * - [KASPA_STANDARD]: `m/44'/111111'/0'/0/{i}` (receive chain, normal final index). KaChat's own
 *   family, and also what KasWare, Kaspium, Kastle, Core Golang CLI, OKX and Ledger seed imports
 *   land on. (Kastle derives `m/44'/111111'/{account}'/0/{index}`; account 0 is byte-identical to
 *   this, and its secondary variant `m/44'/111111'/0'/0/{accountIndex}` falls inside the same
 *   scan range, so it needs no derivation code of its own.)
 * - [KASPA_LEGACY_972]: `m/44'/972/0'/0'/{i'}` — KDX and the Kaspanet Web Wallet. NOTE: 972 is
 *   deliberately NOT hardened (KasWare's hdPath string "m/44'/972/0'" has no apostrophe on 972
 *   and their keyring derives it normally), while the change level AND the final index ARE
 *   hardened.
 * - [CHAINGE]: `m/44'/111111'/0'/0'` — a single hardened leaf; Chainge wallets have exactly ONE
 *   address per seed, so only index 0 exists (iOS da467165 checks it on import).
 * - [ONE_KEY]: the standard `m/44'/111111'/0'/0/{i}` key, then a BIP340 taproot-style tweak —
 *   negate the private key when its compressed pubkey has an odd Y (0x03 prefix), then add
 *   taggedHash("TapTweak", xOnlyPubkey) mod n. The address derives from the tweaked key.
 *
 * Persisted by [name] on [com.kachat.app.services.WalletManager.Account.sourceFamily] — do not
 * rename the entries. A null/unknown stored value means [KASPA_STANDARD], which is exactly what
 * every account created before this feature existed used. The order is iOS's `allCases`: import's
 * ranking breaks ties by it, so the standard wallet wins when nothing tells them apart.
 */
enum class WalletSourceFamily {
    KASPA_STANDARD,
    KASPA_LEGACY_972,
    CHAINGE,
    ONE_KEY;

    /**
     * How import names this wallet type. Standard covers KaChat, KasWare, Kaspium, Kastle, Core
     * Golang CLI, OKX and Ledger, which all use the same addresses (iOS da467165 `displayName`).
     */
    fun displayName(context: android.content.Context): String = when (this) {
        KASPA_STANDARD -> context.getString(R.string.standard_kaspa_wallet)
        KASPA_LEGACY_972 -> "KDX / Kaspanet Web"
        CHAINGE -> "Chainge"
        ONE_KEY -> "OneKey"
    }

    /**
     * How many addresses import's auto-detect checks. The standard chain keeps the depth of one
     * page of the chatting address picker; the others only have to be recognised (iOS da467165).
     */
    val importSearchDepth: IntRange
        get() = when (this) {
            KASPA_STANDARD -> 0..49
            KASPA_LEGACY_972, ONE_KEY -> 0..19
            CHAINGE -> 0..0
        }

    /** Whether the chatting address picker can scan further addresses: Chainge wallets have
     *  exactly one (iOS 4f3dac2d `supportsIndexScan`). */
    val supportsIndexScan: Boolean
        get() = this != CHAINGE

    companion object {
        fun fromRaw(raw: String?): WalletSourceFamily =
            entries.firstOrNull { it.name == raw } ?: KASPA_STANDARD
    }
}

/** Which wallet type and address an import should open (iOS da467165). The default, Standard
 *  #0, is what import falls back to when nothing is found or the search can't run. */
data class ImportAccountChoice(
    val family: WalletSourceFamily = WalletSourceFamily.KASPA_STANDARD,
    val index: Int = 0,
)

/** An address import's auto-detect looked at
 *  ([com.kachat.app.services.WalletService.findImportAccounts], iOS da467165). */
data class ImportAccountCandidate(
    val family: WalletSourceFamily,
    val index: Int,
    val address: String,
    val balanceSompi: Long = 0L,
    val hasHistory: Boolean = false,
) {
    val choice: ImportAccountChoice get() = ImportAccountChoice(family, index)
    val id: String get() = "${family.name}-$index"

    companion object {
        /**
         * The candidates with funds or history, likeliest first: the wallet type holding the most
         * comes first, and each type's addresses are in index order, so the first entry is the
         * likeliest account. Empty when nothing has any activity (iOS da467165
         * `findImportAccounts`). [balances] by address (missing = 0); [withHistory] the addresses
         * with at least one transaction.
         */
        fun ranked(
            candidates: List<ImportAccountCandidate>,
            balances: Map<String, Long>,
            withHistory: Set<String>,
        ): List<ImportAccountCandidate> {
            val totals = mutableMapOf<WalletSourceFamily, Long>()
            val active = candidates.mapNotNull { candidate ->
                val checked = candidate.copy(
                    balanceSompi = balances[candidate.address] ?: 0L,
                    hasHistory = candidate.address in withHistory,
                )
                if (checked.balanceSompi <= 0L && !checked.hasHistory) return@mapNotNull null
                totals[checked.family] = (totals[checked.family] ?: 0L) + checked.balanceSompi
                checked
            }
            // Ties go to the earlier type, so the standard wallet wins when nothing tells them apart.
            return active.sortedWith(
                compareByDescending<ImportAccountCandidate> { totals[it.family] ?: 0L }
                    .thenBy { it.family.ordinal }
                    .thenBy { it.index }
            )
        }
    }
}
