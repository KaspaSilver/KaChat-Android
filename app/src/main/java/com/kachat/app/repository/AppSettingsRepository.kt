package com.kachat.app.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.gson.Gson
import com.kachat.app.models.PendingKnsCommit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Typed wrapper around DataStore<Preferences>.
 * Equivalent to AppSettings in the iOS app.
 *
 * Provides reactive Flows for all settings so the UI updates automatically.
 */
@Singleton
class AppSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    companion object {
        // Matches Screens.kt's QUICK_REACTION_EMOJIS / iOS's AppSettings.defaultQuickReactionEmojis.
        val DEFAULT_QUICK_REACTION_EMOJIS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

        // Network
        val KEY_NETWORK          = stringPreferencesKey("network")           // "mainnet" | "testnet"
        val KEY_INDEXER_URL      = stringPreferencesKey("indexer_url")
        val KEY_KNS_API_URL      = stringPreferencesKey("kns_api_url")
        val KEY_KASPA_REST_URL   = stringPreferencesKey("kaspa_rest_url")
        // K social indexer powering KaPosts feeds (the KaChat-owned fork).
        val KEY_KAPOST_INDEXER_URL = stringPreferencesKey("kapost_indexer_url")
        // KaChat broadcast indexer serving #kaspa/#kachat-bugs history (same domain).
        val KEY_BROADCAST_INDEXER_URL = stringPreferencesKey("broadcast_indexer_url")
        // A user-pinned "host:port" gRPC node - when non-blank, NodePoolManager stops
        // discovery (seeds/DNS/peer-gossip) entirely and only ever connects to this address,
        // Kaspium-style. Empty string = disabled (normal pool discovery).
        val KEY_TRUSTED_NODE_ADDRESS = stringPreferencesKey("trusted_node_address")
        // User-saved node addresses for quick copy/paste into the Kaspa Node field above -
        // Gson-encoded list, same pattern as KEY_PENDING_KNS_COMMIT below.
        val KEY_SAVED_NODE_ADDRESSES = stringPreferencesKey("saved_node_addresses")

        // Defaults matching the iOS app
        const val DEFAULT_NETWORK        = "mainnet"
        const val DEFAULT_INDEXER_URL    = "https://indexer.kasia.wtf"
        // Retired default - kasia.fyi doesn't run the group-chat REST endpoints
        // (/group-messages/..., /group-control/...), only kasia.wtf does. See `indexerUrl`'s
        // one-time migration off this value below.
        const val LEGACY_DEFAULT_INDEXER_URL = "https://indexer.kasia.fyi"
        const val DEFAULT_KNS_API_URL    = "https://api.knsdomains.org/mainnet/api/v1"
        const val DEFAULT_KASPA_REST_URL = "https://api.kaspa.org"
        const val DEFAULT_KAPOST_INDEXER_URL = "https://kachat.duckdns.org"
        const val DEFAULT_BROADCAST_INDEXER_URL = "https://kachat.duckdns.org"
        // KaChat ships pinned to Kaspium's public node out of the box, rather than defaulting
        // to full seed/DNS/peer-gossip discovery - the "Use Default" button in Connection
        // Settings resets back to this same address after a user has typed something else.
        // This is Kaspium's own currently-live default (see their node_settings_notifier.dart's
        // "temporary Toccata node override" - node.kaspium.io's cert had expired, so Kaspium's
        // app itself now points here instead) - TLS-secured, hence "grpcs://" (see
        // KaspadConnection.kt's parseNodeAddress).
        const val DEFAULT_TRUSTED_NODE_ADDRESS = "grpcs://toccata.kaspium.io"

        // Wallet (just a flag — actual keys live in Keystore)
        val KEY_HAS_WALLET       = booleanPreferencesKey("has_wallet")
        val KEY_ACTIVE_ADDRESS   = stringPreferencesKey("active_address")
        
        // How hard chat photos get compressed before sending — mirrors iOS's
        // `chatPhotoQualityPreset` setting. Only affects photos sent, not received.
        val KEY_CHAT_PHOTO_QUALITY_PRESET = stringPreferencesKey("chat_photo_quality_preset")
        // Which block explorer website "Go to Explorer" links open in.
        val KEY_KASPA_EXPLORER = stringPreferencesKey("kaspa_explorer")
        // Flat, chain-wide set of txIds the user has manually revealed a hidden photo for —
        // mirrors iOS's `PhotoRevealStore`. Not per-wallet: txIds are unique on-chain already.
        val KEY_REVEALED_PHOTO_TX_IDS = stringSetPreferencesKey("revealed_photo_tx_ids")

        // KaPosts local stores (poster ADDRESSES, chain-wide like revealed photos): who you
        // follow (mirrored on-chain by k:1:follow txs - this set drives instant UI state),
        // and muted/blocked authors whose content hides everywhere in KaPosts.
        val KEY_KAPOSTS_FOLLOWING = stringSetPreferencesKey("kaposts_following")
        val KEY_KAPOSTS_MUTED     = stringSetPreferencesKey("kaposts_muted")
        val KEY_KAPOSTS_BLOCKED   = stringSetPreferencesKey("kaposts_blocked")

        // Per-group hidden/muted member sets - each entry is "{groupId}|{address}", flattened
        // into one Set<String> rather than a nested map since DataStore preferences only have
        // flat native collection types. Mirrors iOS's GroupChatService.groupHiddenMembers/
        // groupMutedMembers (there, a real [String: Set<String>] via UserDefaults-JSON).
        val KEY_GROUP_HIDDEN_MEMBERS = stringSetPreferencesKey("group_hidden_members")
        val KEY_GROUP_MUTED_MEMBERS  = stringSetPreferencesKey("group_muted_members")
        // Group ids with "Only Notify if I'm Mentioned" on - mirrors iOS's
        // GroupChatService.groupMentionsOnlyNotifications.
        val KEY_GROUP_MENTIONS_ONLY  = stringSetPreferencesKey("group_mentions_only")

        // Notifications — mirrors iOS's notificationMode/sound/vibration settings, minus
        // the remote-push mode (there's no FCM/APNs-equivalent registration wired up yet,
        // see NotificationHelper — only local notifications while the app process is alive).
        val KEY_NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val KEY_NOTIFICATION_SOUND    = booleanPreferencesKey("notification_sound_enabled")
        val KEY_NOTIFICATION_VIBRATION = booleanPreferencesKey("notification_vibration_enabled")

        // System contacts sync — matches iOS's "Sync system contacts"/"Autocreate system contacts".
        val KEY_SYNC_SYSTEM_CONTACTS = booleanPreferencesKey("sync_system_contacts")
        val KEY_AUTOCREATE_SYSTEM_CONTACTS = booleanPreferencesKey("autocreate_system_contacts")

        val KEY_SHOW_FEE_ESTIMATE = booleanPreferencesKey("show_fee_estimate")

        // Customizable double-tap quick-reaction set - see QUICK_REACTION_EMOJIS's default and
        // QuickReactionBar (Screens.kt) / QuickReactionBarView (iOS) for where this is read.
        val KEY_QUICK_REACTION_EMOJIS = stringPreferencesKey("quick_reaction_emojis")

        // A single in-flight KNS commit awaiting its reveal — see PendingKnsCommit.
        val KEY_PENDING_KNS_COMMIT = stringPreferencesKey("pending_kns_commit")

        // Google Drive chat-history backup — off by default, unlike iOS's iCloud sync.
        val KEY_GOOGLE_BACKUP_ENABLED = booleanPreferencesKey("google_backup_enabled")
        val KEY_BACKUP_RETENTION = stringPreferencesKey("backup_retention")

        // Broadcasts — whether the "Popular" tab (curated rooms, see FeaturedBroadcastChannels)
        // shows at all, toggled from the gear icon next to Broadcasts' join button.
        val KEY_BROADCAST_POPULAR_ENABLED = booleanPreferencesKey("broadcast_popular_enabled")
        // Whether senders' KNS profile pictures render in broadcast rooms and are looked up at
        // all, or every sender just shows fallback initials instead (and no lookup happens) —
        // toggled from the gear icon next to Broadcasts' join button. A KNS profile's avatarUrl
        // is an arbitrary attacker-controlled string written via a permissionless on-chain
        // inscription (see updateKnsProfileField), and since the fetch fires just from a message
        // rendering on screen with no tap or other user action, an attacker could otherwise use
        // it as a tracking pixel to learn a viewer's IP/timing/fingerprint just from them opening
        // a channel — this toggle is what gates that.
        val KEY_BROADCAST_SHOW_KNS_AVATARS = booleanPreferencesKey("broadcast_show_kns_avatars")
        // User's custom bottom-tab order (press-and-hold to drag/reorder), comma-joined route
        // strings e.g. "portfolio,chats,swap,profile" — a stringSetPreferencesKey can't
        // be used here since Set has no defined iteration order, and order is the entire point.
        val KEY_TAB_ORDER = stringPreferencesKey("tab_order")
        // Kept in sync with KaChatApp.kt's bottomNavItems default order. "settings" is deliberately
        // absent - it isn't a tab (matches iOS), it's reached one tap in from Profile's gear icon.
        val DEFAULT_TAB_ORDER = listOf("portfolio", "cold_storage", "chats", "swap", "profile")
        // Which bottom-tab routes the user has hidden from the nav bar (Settings > Customization >
        // Menu) — "chats"/"profile" are never allowed in here, only "portfolio"/"cold_storage"/
        // "swap". A route absent from this set is visible.
        val KEY_HIDDEN_TABS = stringSetPreferencesKey("hidden_tabs")
        // One-time 4.0 seeding marker for KEY_HIDDEN_TABS - see applyKaPostsTabDefaultsIfNeeded.
        val KEY_TAB_DEFAULTS_40_APPLIED = booleanPreferencesKey("tab_defaults_40_applied")
        // v3 (final 4.0 rules): existing users get KaPosts/Broadcasts/+More force-ENABLED - a
        // full dock sends KaPosts/Broadcasts to the Chats-slot cycle, a free slot gets "+More";
        // brand-new installs get a Chats/Profile/+More dock with everything else off until
        // enabled through Customize Menu. Also scrubs v1's kaposts-hide.
        val KEY_TAB_DEFAULTS_40_V3_APPLIED = booleanPreferencesKey("tab_defaults_40_v3_applied")
        // The 4.0 dock wizard has been dismissed (shown once per install).
        val KEY_DOCK_WIZARD_DISMISSED = booleanPreferencesKey("dock_wizard_dismissed")
        // Settings > Customization > Dark Mode. True (dark) is the default so existing installs'
        // appearance is unchanged — every screen was designed dark-only until this toggle existed.
        val KEY_DARK_MODE_ENABLED = booleanPreferencesKey("dark_mode_enabled")
        // Settings > Security. Both on by default — the seed phrase and account login were gated
        // behind device authentication unconditionally before these toggles existed.
        val KEY_BIOMETRIC_SEED_PHRASE_ENABLED = booleanPreferencesKey("biometric_seed_phrase_enabled")
        val KEY_BIOMETRIC_ACCOUNT_LOGIN_ENABLED = booleanPreferencesKey("biometric_account_login_enabled")
        val KEY_BIOMETRIC_SPENDING_KEY_ENABLED = booleanPreferencesKey("biometric_spending_key_enabled")
        // One-time ChangeNOW terms/liability disclaimer shown the first time Swap is opened.
        val KEY_SWAP_DISCLAIMER_AGREED = booleanPreferencesKey("swap_disclaimer_agreed")
        // Settings > Customization > Currency. Fiat currency for Portfolio's live KAS price/value
        // display - lowercase ISO 4217 code, doubling as the literal CoinGecko `vs_currency` value
        // (see CoinGeckoApi). Global, not per-account, matching darkModeEnabled/hiddenTabs.
        val KEY_CURRENCY = stringPreferencesKey("currency")
    }

    // -------------------------------------------------------------------------
    // Reactive flows (collect in ViewModel with .stateIn)
    // -------------------------------------------------------------------------

    // Testnet is no longer selectable anywhere in the app (4.0, matches iOS) - always mainnet,
    // regardless of any previously stored value.
    val network: Flow<String> = dataStore.data.map { DEFAULT_NETWORK }

    // Transforms away the retired kasia.fyi default on read (rather than requiring a one-time
    // write-back migration) - anyone who saved settings before the indexer moved to kasia.wtf
    // would otherwise stay stuck on kasia.fyi forever, which 404s on every group-chat REST call.
    val indexerUrl: Flow<String> = dataStore.data.map {
        val stored = it[KEY_INDEXER_URL]
        if (stored == null || stored == LEGACY_DEFAULT_INDEXER_URL) DEFAULT_INDEXER_URL else stored
    }

    val knsApiUrl: Flow<String> = dataStore.data.map {
        it[KEY_KNS_API_URL] ?: DEFAULT_KNS_API_URL
    }

    val kaspaRestUrl: Flow<String> = dataStore.data.map {
        it[KEY_KASPA_REST_URL] ?: DEFAULT_KASPA_REST_URL
    }

    val kapostIndexerUrl: Flow<String> = dataStore.data.map {
        it[KEY_KAPOST_INDEXER_URL] ?: DEFAULT_KAPOST_INDEXER_URL
    }

    val broadcastIndexerUrl: Flow<String> = dataStore.data.map {
        it[KEY_BROADCAST_INDEXER_URL] ?: DEFAULT_BROADCAST_INDEXER_URL
    }

    val kapostsFollowing: Flow<Set<String>> = dataStore.data.map { it[KEY_KAPOSTS_FOLLOWING] ?: emptySet() }
    val kapostsMuted: Flow<Set<String>> = dataStore.data.map { it[KEY_KAPOSTS_MUTED] ?: emptySet() }
    val kapostsBlocked: Flow<Set<String>> = dataStore.data.map { it[KEY_KAPOSTS_BLOCKED] ?: emptySet() }

    val dockWizardDismissed: Flow<Boolean> = dataStore.data.map { it[KEY_DOCK_WIZARD_DISMISSED] ?: false }

    // Falls back to DEFAULT_TRUSTED_NODE_ADDRESS only when the key has never been written at
    // all (a fresh install) - once the user explicitly saves "" (clearing it via the Kaspa Node
    // field), that's a real stored value distinct from "never touched", so it correctly stays
    // empty (normal discovery) instead of snapping back to the default on every read.
    val trustedNodeAddress: Flow<String> = dataStore.data.map {
        it[KEY_TRUSTED_NODE_ADDRESS] ?: DEFAULT_TRUSTED_NODE_ADDRESS
    }

    val savedNodeAddresses: Flow<List<com.kachat.app.models.SavedNodeAddress>> = dataStore.data.map { prefs ->
        prefs[KEY_SAVED_NODE_ADDRESSES]?.let { json ->
            try {
                Gson().fromJson(json, Array<com.kachat.app.models.SavedNodeAddress>::class.java).toList()
            } catch (e: Exception) {
                emptyList()
            }
        } ?: emptyList()
    }

    val hasWallet: Flow<Boolean> = dataStore.data.map {
        it[KEY_HAS_WALLET] ?: false
    }

    val activeAddress: Flow<String?> = dataStore.data.map {
        it[KEY_ACTIVE_ADDRESS]
    }

    val chatPhotoQualityPreset: Flow<com.kachat.app.models.ChatPhotoQualityPreset> = dataStore.data.map {
        com.kachat.app.models.ChatPhotoQualityPreset.fromName(it[KEY_CHAT_PHOTO_QUALITY_PRESET])
    }

    val kaspaExplorer: Flow<com.kachat.app.models.KaspaExplorer> = dataStore.data.map {
        com.kachat.app.models.KaspaExplorer.fromName(it[KEY_KASPA_EXPLORER])
    }

    val revealedPhotoTxIds: Flow<Set<String>> = dataStore.data.map {
        it[KEY_REVEALED_PHOTO_TX_IDS] ?: emptySet()
    }

    val broadcastPopularEnabled: Flow<Boolean> = dataStore.data.map {
        it[KEY_BROADCAST_POPULAR_ENABLED] ?: true
    }

    val broadcastShowKnsAvatars: Flow<Boolean> = dataStore.data.map {
        it[KEY_BROADCAST_SHOW_KNS_AVATARS] ?: true
    }

    // The dock is PER ACCOUNT: each account gets its own order/visibility keys once it diverges;
    // until then it inherits the legacy global keys (which the 4.0 seeding below also targets),
    // so the account that existed before this feature keeps its arrangement and new accounts
    // start from a sensible base. KEY_ACTIVE_ADDRESS is mirrored from WalletManager's active
    // account by WalletViewModel, so these transactional reads always see the right account.
    private fun tabOrderKeyFor(address: String) = stringPreferencesKey("tab_order_$address")
    private fun hiddenTabsKeyFor(address: String) = stringSetPreferencesKey("hidden_tabs_$address")

    /** Route strings, in display order — per-account, falling back to the legacy global key, then the app's default tab order. */
    val tabOrder: Flow<List<String>> = dataStore.data.map { prefs ->
        val address = prefs[KEY_ACTIVE_ADDRESS]
        val raw = address?.let { prefs[tabOrderKeyFor(it)] } ?: prefs[KEY_TAB_ORDER]
        raw?.split(",")?.filter { it.isNotBlank() } ?: DEFAULT_TAB_ORDER
    }

    val hiddenTabs: Flow<Set<String>> = dataStore.data.map { prefs ->
        val address = prefs[KEY_ACTIVE_ADDRESS]
        (address?.let { prefs[hiddenTabsKeyFor(it)] }) ?: prefs[KEY_HIDDEN_TABS] ?: emptySet()
    }

    val darkModeEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_DARK_MODE_ENABLED] ?: true }

    /** Lowercase ISO 4217 code (e.g. "usd", "eur") - see KEY_CURRENCY. */
    val currency: Flow<String> = dataStore.data.map { it[KEY_CURRENCY] ?: "usd" }

    val biometricSeedPhraseEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_BIOMETRIC_SEED_PHRASE_ENABLED] ?: true }
    val biometricAccountLoginEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_BIOMETRIC_ACCOUNT_LOGIN_ENABLED] ?: true }
    /** Gates the "Export" button on a spending address's own screen - separate from
     *  [biometricSeedPhraseEnabled] since revealing one address's own derived key is lower-stakes
     *  than the wallet's whole seed phrase, but still sensitive enough to gate independently. */
    val biometricSpendingKeyEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_BIOMETRIC_SPENDING_KEY_ENABLED] ?: true }
    val swapDisclaimerAgreed: Flow<Boolean> = dataStore.data.map { it[KEY_SWAP_DISCLAIMER_AGREED] ?: false }

    val notificationsEnabled: Flow<Boolean> = dataStore.data.map {
        it[KEY_NOTIFICATIONS_ENABLED] ?: true
    }

    val notificationSoundEnabled: Flow<Boolean> = dataStore.data.map {
        it[KEY_NOTIFICATION_SOUND] ?: true
    }

    val notificationVibrationEnabled: Flow<Boolean> = dataStore.data.map {
        it[KEY_NOTIFICATION_VIBRATION] ?: true
    }

    val syncSystemContactsEnabled: Flow<Boolean> = dataStore.data.map {
        it[KEY_SYNC_SYSTEM_CONTACTS] ?: false
    }

    val showFeeEstimate: Flow<Boolean> = dataStore.data.map {
        it[KEY_SHOW_FEE_ESTIMATE] ?: true
    }

    /** Falls back to [DEFAULT_QUICK_REACTION_EMOJIS] (matches the default in `Screens.kt`'s
     *  `QUICK_REACTION_EMOJIS` / iOS's `AppSettings.defaultQuickReactionEmojis`) if never
     *  customized, or if a stored value somehow isn't exactly 6 entries. */
    val quickReactionEmojis: Flow<List<String>> = dataStore.data.map { prefs ->
        val stored = prefs[KEY_QUICK_REACTION_EMOJIS]?.let { json ->
            try {
                Gson().fromJson(json, Array<String>::class.java).toList()
            } catch (e: Exception) {
                null
            }
        }
        stored?.takeIf { it.size == 6 } ?: DEFAULT_QUICK_REACTION_EMOJIS
    }

    val groupHiddenMembers: Flow<Set<String>> = dataStore.data.map { it[KEY_GROUP_HIDDEN_MEMBERS] ?: emptySet() }
    val groupMutedMembers: Flow<Set<String>> = dataStore.data.map { it[KEY_GROUP_MUTED_MEMBERS] ?: emptySet() }
    val groupMentionsOnly: Flow<Set<String>> = dataStore.data.map { it[KEY_GROUP_MENTIONS_ONLY] ?: emptySet() }

    suspend fun hideGroupMember(groupId: String, address: String) = dataStore.edit {
        it[KEY_GROUP_HIDDEN_MEMBERS] = (it[KEY_GROUP_HIDDEN_MEMBERS] ?: emptySet()) + "$groupId|$address"
    }

    suspend fun unhideGroupMember(groupId: String, address: String) = dataStore.edit {
        it[KEY_GROUP_HIDDEN_MEMBERS] = (it[KEY_GROUP_HIDDEN_MEMBERS] ?: emptySet()) - "$groupId|$address"
    }

    suspend fun muteGroupMember(groupId: String, address: String) = dataStore.edit {
        it[KEY_GROUP_MUTED_MEMBERS] = (it[KEY_GROUP_MUTED_MEMBERS] ?: emptySet()) + "$groupId|$address"
    }

    suspend fun unmuteGroupMember(groupId: String, address: String) = dataStore.edit {
        it[KEY_GROUP_MUTED_MEMBERS] = (it[KEY_GROUP_MUTED_MEMBERS] ?: emptySet()) - "$groupId|$address"
    }

    suspend fun setGroupMentionsOnly(groupId: String, enabled: Boolean) = dataStore.edit {
        val current = it[KEY_GROUP_MENTIONS_ONLY] ?: emptySet()
        it[KEY_GROUP_MENTIONS_ONLY] = if (enabled) current + groupId else current - groupId
    }

    val autoCreateSystemContactsEnabled: Flow<Boolean> = dataStore.data.map {
        it[KEY_AUTOCREATE_SYSTEM_CONTACTS] ?: false
    }

    /** Off by default — the user must explicitly turn this on, unlike iOS's iCloud sync. */
    val googleBackupEnabled: Flow<Boolean> = dataStore.data.map {
        it[KEY_GOOGLE_BACKUP_ENABLED] ?: false
    }

    val backupRetention: Flow<com.kachat.app.models.BackupRetention> = dataStore.data.map {
        com.kachat.app.models.BackupRetention.fromName(it[KEY_BACKUP_RETENTION])
    }

    val pendingKnsCommit: Flow<PendingKnsCommit?> = dataStore.data.map { prefs ->
        prefs[KEY_PENDING_KNS_COMMIT]?.let { json ->
            try {
                Gson().fromJson(json, PendingKnsCommit::class.java)
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * The first moment payment-sync ever ran for this wallet address, so historical
     * payments from before the user started using KaChat never turn into auto-created
     * chats — only payments received after that moment do. Keyed per-address since a
     * device can hold multiple saved accounts, each with its own real payment history.
     */
    fun paymentSyncBaseline(address: String): Flow<Long?> = dataStore.data.map {
        it[paymentSyncBaselineKey(address)]
    }

    /**
     * How far into the `handshakes/by-receiver` stream this wallet has already synced — the
     * indexer's `block_time` cursor, so a sync only asks for what's genuinely new since last time
     * instead of re-fetching the same recent window every cycle. Keyed per-address like
     * [paymentSyncBaseline]. Unlike contextual messages (per-contact-per-alias, so tracked in Room
     * — see [com.kachat.app.models.MessageSyncCursorEntity]), handshakes-by-receiver is a single
     * stream for the whole wallet, so a DataStore value is enough.
     */
    fun handshakeSyncCursor(address: String): Flow<Long?> = dataStore.data.map {
        it[handshakeSyncCursorKey(address)]
    }

    /**
     * Per-account toggle for whether the "Setup Guide" re-entry points (the Profile screen's
     * "Welcome Guide" row and the "Edit KNS Profile" screen's "Setup Guide" section) are shown.
     * Keyed per-address like [paymentSyncBaseline] so switching accounts on the same device
     * doesn't carry the choice over. Defaults to `true` (unset key) to match pre-existing
     * behavior for anyone who had these guides visible before this toggle existed.
     */
    fun showSetupGuides(address: String): Flow<Boolean> = dataStore.data.map {
        it[showSetupGuidesKey(address)] ?: true
    }

    // -------------------------------------------------------------------------
    // Write helpers (suspend — call from coroutine / ViewModel)
    // -------------------------------------------------------------------------

    suspend fun setNetwork(value: String) = dataStore.edit { it[KEY_NETWORK] = value }
    suspend fun setIndexerUrl(value: String) = dataStore.edit { it[KEY_INDEXER_URL] = value }
    suspend fun setKnsApiUrl(value: String) = dataStore.edit { it[KEY_KNS_API_URL] = value }
    suspend fun setKaspaRestUrl(value: String) = dataStore.edit { it[KEY_KASPA_REST_URL] = value }
    suspend fun setKapostIndexerUrl(value: String) = dataStore.edit { it[KEY_KAPOST_INDEXER_URL] = value }
    suspend fun setBroadcastIndexerUrl(value: String) = dataStore.edit { it[KEY_BROADCAST_INDEXER_URL] = value }
    suspend fun setKapostsFollowing(value: Set<String>) = dataStore.edit { it[KEY_KAPOSTS_FOLLOWING] = value }
    suspend fun setKapostsMuted(value: Set<String>) = dataStore.edit { it[KEY_KAPOSTS_MUTED] = value }
    suspend fun setKapostsBlocked(value: Set<String>) = dataStore.edit { it[KEY_KAPOSTS_BLOCKED] = value }
    suspend fun setDockWizardDismissed() = dataStore.edit { it[KEY_DOCK_WIZARD_DISMISSED] = true }
    suspend fun setTrustedNodeAddress(value: String) = dataStore.edit { it[KEY_TRUSTED_NODE_ADDRESS] = value }
    suspend fun addSavedNodeAddress(entry: com.kachat.app.models.SavedNodeAddress) = dataStore.edit { prefs ->
        val current = prefs[KEY_SAVED_NODE_ADDRESSES]?.let { json ->
            try {
                Gson().fromJson(json, Array<com.kachat.app.models.SavedNodeAddress>::class.java).toList()
            } catch (e: Exception) {
                emptyList()
            }
        } ?: emptyList()
        prefs[KEY_SAVED_NODE_ADDRESSES] = Gson().toJson(current + entry)
    }
    suspend fun removeSavedNodeAddress(id: String) = dataStore.edit { prefs ->
        val current = prefs[KEY_SAVED_NODE_ADDRESSES]?.let { json ->
            try {
                Gson().fromJson(json, Array<com.kachat.app.models.SavedNodeAddress>::class.java).toList()
            } catch (e: Exception) {
                emptyList()
            }
        } ?: emptyList()
        prefs[KEY_SAVED_NODE_ADDRESSES] = Gson().toJson(current.filterNot { it.id == id })
    }
    suspend fun setHasWallet(value: Boolean) = dataStore.edit { it[KEY_HAS_WALLET] = value }
    suspend fun setActiveAddress(value: String) = dataStore.edit { it[KEY_ACTIVE_ADDRESS] = value }
    suspend fun setChatPhotoQualityPreset(value: com.kachat.app.models.ChatPhotoQualityPreset) = dataStore.edit { it[KEY_CHAT_PHOTO_QUALITY_PRESET] = value.name }
    suspend fun setKaspaExplorer(value: com.kachat.app.models.KaspaExplorer) = dataStore.edit { it[KEY_KASPA_EXPLORER] = value.name }
    suspend fun revealPhoto(txId: String) = dataStore.edit {
        it[KEY_REVEALED_PHOTO_TX_IDS] = (it[KEY_REVEALED_PHOTO_TX_IDS] ?: emptySet()) + txId
    }
    suspend fun setBroadcastPopularEnabled(value: Boolean) = dataStore.edit { it[KEY_BROADCAST_POPULAR_ENABLED] = value }
    suspend fun setBroadcastShowKnsAvatars(value: Boolean) = dataStore.edit { it[KEY_BROADCAST_SHOW_KNS_AVATARS] = value }
    suspend fun setTabOrder(routes: List<String>) = dataStore.edit { prefs ->
        val address = prefs[KEY_ACTIVE_ADDRESS]
        val encoded = routes.joinToString(",")
        if (address != null) prefs[tabOrderKeyFor(address)] = encoded else prefs[KEY_TAB_ORDER] = encoded
    }
    suspend fun setTabHidden(route: String, hidden: Boolean) = dataStore.edit { prefs ->
        val address = prefs[KEY_ACTIVE_ADDRESS]
        val readKey = if (address != null) hiddenTabsKeyFor(address) else KEY_HIDDEN_TABS
        // First divergence for an account starts from what it currently inherits (legacy global).
        val current = prefs[readKey] ?: prefs[KEY_HIDDEN_TABS] ?: emptySet()
        prefs[readKey] = if (hidden) current + route else current - route
    }

    /**
     * One-time 4.0 dock seeding, run at app start (WalletViewModel init). New tabs default to
     * VISIBLE here (hidden_tabs polarity + resolveTabOrder auto-append), which is wrong for both
     * audiences, so:
     * - EXISTING users (has_wallet set): hide the new "kaposts" and "more" items so their dock is
     *   unchanged by the update - both stay available in Settings > Customization > Menu.
     * - FRESH installs (no wallet yet): seed the minimal 4.0 dock - Chats, Profile and "+ More"
     *   only (portfolio/storage/swap/kaposts hidden until toggled on via More).
     * The sentinel guarantees this never re-runs, so later user choices are never overwritten.
     */
    suspend fun applyKaPostsTabDefaultsIfNeeded() = dataStore.edit { prefs ->
        if (prefs[KEY_TAB_DEFAULTS_40_V3_APPLIED] == true) return@edit
        val hasWallet = prefs[KEY_HAS_WALLET] ?: false
        val current = prefs[KEY_HIDDEN_TABS] ?: emptySet()
        prefs[KEY_HIDDEN_TABS] = if (hasWallet) {
            // EXISTING users: KaPosts, Broadcasts and "+More" all ENABLED. The dock cap does
            // the rest: a full 5-tab dock is unchanged (KaPosts/Broadcasts ride the Chats
            // cycle, "+More" falls off the tail); a dock with a free slot gets "+More" in it.
            current - "kaposts" - "broadcasts" - "more"
        } else {
            // BRAND-NEW installs: Chats, Profile and "+More" only - everything else (including
            // KaPosts/Broadcasts) stays off until toggled on through Customize Menu.
            current + setOf("portfolio", "cold_storage", "swap", "kaposts", "broadcasts")
        }
        prefs[KEY_TAB_DEFAULTS_40_V3_APPLIED] = true
        prefs[KEY_TAB_DEFAULTS_40_APPLIED] = true
    }
    suspend fun setDarkModeEnabled(value: Boolean) = dataStore.edit { it[KEY_DARK_MODE_ENABLED] = value }
    suspend fun setCurrency(value: String) = dataStore.edit { it[KEY_CURRENCY] = value }
    suspend fun setBiometricSeedPhraseEnabled(value: Boolean) = dataStore.edit { it[KEY_BIOMETRIC_SEED_PHRASE_ENABLED] = value }
    suspend fun setBiometricAccountLoginEnabled(value: Boolean) = dataStore.edit { it[KEY_BIOMETRIC_ACCOUNT_LOGIN_ENABLED] = value }
    suspend fun setBiometricSpendingKeyEnabled(value: Boolean) = dataStore.edit { it[KEY_BIOMETRIC_SPENDING_KEY_ENABLED] = value }
    suspend fun setSwapDisclaimerAgreed(value: Boolean) = dataStore.edit { it[KEY_SWAP_DISCLAIMER_AGREED] = value }
    suspend fun setNotificationsEnabled(value: Boolean) = dataStore.edit { it[KEY_NOTIFICATIONS_ENABLED] = value }
    suspend fun setShowFeeEstimate(value: Boolean) = dataStore.edit { it[KEY_SHOW_FEE_ESTIMATE] = value }
    suspend fun setQuickReactionEmojis(value: List<String>) = dataStore.edit { it[KEY_QUICK_REACTION_EMOJIS] = Gson().toJson(value) }
    suspend fun setNotificationSoundEnabled(value: Boolean) = dataStore.edit { it[KEY_NOTIFICATION_SOUND] = value }
    suspend fun setNotificationVibrationEnabled(value: Boolean) = dataStore.edit { it[KEY_NOTIFICATION_VIBRATION] = value }
    suspend fun setSyncSystemContactsEnabled(value: Boolean) = dataStore.edit { it[KEY_SYNC_SYSTEM_CONTACTS] = value }
    suspend fun setGoogleBackupEnabled(value: Boolean) = dataStore.edit { it[KEY_GOOGLE_BACKUP_ENABLED] = value }
    suspend fun setBackupRetention(value: com.kachat.app.models.BackupRetention) = dataStore.edit { it[KEY_BACKUP_RETENTION] = value.name }
    suspend fun setAutoCreateSystemContactsEnabled(value: Boolean) = dataStore.edit { it[KEY_AUTOCREATE_SYSTEM_CONTACTS] = value }
    suspend fun setPendingKnsCommit(commit: PendingKnsCommit) = dataStore.edit { it[KEY_PENDING_KNS_COMMIT] = Gson().toJson(commit) }
    suspend fun clearPendingKnsCommit() = dataStore.edit { it.remove(KEY_PENDING_KNS_COMMIT) }
    suspend fun setPaymentSyncBaseline(address: String, value: Long) = dataStore.edit { it[paymentSyncBaselineKey(address)] = value }
    suspend fun setHandshakeSyncCursor(address: String, value: Long) = dataStore.edit { it[handshakeSyncCursorKey(address)] = value }
    suspend fun setShowSetupGuides(address: String, value: Boolean) = dataStore.edit { it[showSetupGuidesKey(address)] = value }

    private fun paymentSyncBaselineKey(address: String) = longPreferencesKey("payment_sync_baseline_$address")
    private fun handshakeSyncCursorKey(address: String) = longPreferencesKey("handshake_sync_cursor_$address")
    private fun showSetupGuidesKey(address: String) = booleanPreferencesKey("show_setup_guides_$address")
}
