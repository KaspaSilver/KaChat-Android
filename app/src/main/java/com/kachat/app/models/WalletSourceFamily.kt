package com.kachat.app.models

/**
 * Identity derivation-path family of the wallet a seed phrase was imported from — the
 * KasWare-style "which wallet is this seed from?" selection shown before seed entry. Different
 * Kaspa wallets put the same seed's funds and KNS domains on different BIP32 branches; picking
 * the right family at import is what lets KaChat find them. Ported from iOS's
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
 * - [ONE_KEY]: the standard `m/44'/111111'/0'/0/{i}` key, then a BIP340 taproot-style tweak —
 *   negate the private key when its compressed pubkey has an odd Y (0x03 prefix), then add
 *   taggedHash("TapTweak", xOnlyPubkey) mod n. The address derives from the tweaked key.
 *
 * Persisted by [name] on [com.kachat.app.services.WalletManager.Account.sourceFamily] — do not
 * rename the entries. A null/unknown stored value means [KASPA_STANDARD], which is exactly what
 * every account created before this feature existed used.
 */
enum class WalletSourceFamily {
    KASPA_STANDARD,
    KASPA_LEGACY_972,
    ONE_KEY;

    /** Human-readable base path, shown in small monospaced text on the import chooser rows. */
    val pathDescription: String
        get() = when (this) {
            KASPA_STANDARD -> "m/44'/111111'/0'"
            KASPA_LEGACY_972 -> "m/44'/972/0'"
            ONE_KEY -> "m/44'/111111'/0' (OneKey)"
        }

    companion object {
        fun fromRaw(raw: String?): WalletSourceFamily =
            entries.firstOrNull { it.name == raw } ?: KASPA_STANDARD
    }
}

/**
 * One row of the import source-wallet chooser: a wallet the user might be coming from, and the
 * derivation [family] its seeds belong to. Order mirrors KasWare's restore list, with KaChat
 * first and preselected.
 */
data class SourceWalletOption(
    val displayName: String,
    val family: WalletSourceFamily,
    val isDefault: Boolean = false
) {
    companion object {
        val ALL: List<SourceWalletOption> = listOf(
            SourceWalletOption("KaChat", WalletSourceFamily.KASPA_STANDARD, isDefault = true),
            SourceWalletOption("KasWare Wallet", WalletSourceFamily.KASPA_STANDARD),
            SourceWalletOption("Kaspium Wallet", WalletSourceFamily.KASPA_STANDARD),
            SourceWalletOption("Kastle Wallet", WalletSourceFamily.KASPA_STANDARD),
            SourceWalletOption("KDX Wallet", WalletSourceFamily.KASPA_LEGACY_972),
            SourceWalletOption("Core Golang Cli Wallet", WalletSourceFamily.KASPA_STANDARD),
            SourceWalletOption("OKX Wallet", WalletSourceFamily.KASPA_STANDARD),
            SourceWalletOption("OneKey Wallet", WalletSourceFamily.ONE_KEY),
            SourceWalletOption("Ledger Wallet", WalletSourceFamily.KASPA_STANDARD)
        )
    }
}
