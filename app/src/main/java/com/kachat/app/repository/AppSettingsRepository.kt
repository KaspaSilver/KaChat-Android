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
import com.kachat.app.util.KaspaNetwork
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
        // No longer read or written - `knsApiUrl` is pinned to DEFAULT_KNS_API_URL. Any value an
        // older build left in the DataStore is inert; the key stays declared so it is obvious the
        // name is taken rather than free to reuse.
        @Suppress("unused")
        val KEY_KNS_API_URL      = stringPreferencesKey("kns_api_url")
        val KEY_KASPA_REST_URL   = stringPreferencesKey("kaspa_rest_url")
        // Push-registration host (FCM device registration; mirrors iOS's pushIndexerURL). Same
        // domain by default, but configurable so push can point at a different indexer.
        val KEY_PUSH_INDEXER_URL = stringPreferencesKey("push_indexer_url")
        // Where .kachat names and profiles are read (Connection Settings > Domains; iOS
        // `kachatNamesIndexerURL`, 7abc06e). Its own field, apart from the message indexer, so a
        // names indexer can be run on its own. Unset = the message indexer, where names were read
        // before the field existed.
        val KEY_KACHAT_NAMES_INDEXER_URL = stringPreferencesKey("kachat_names_indexer_url")
        // A user-pinned "host:port" gRPC node - when non-blank, NodePoolManager stops
        // discovery (seeds/DNS/peer-gossip) entirely and only ever connects to this address,
        // Kaspium-style. Empty string = disabled (normal pool discovery).
        val KEY_TRUSTED_NODE_ADDRESS = stringPreferencesKey("trusted_node_address")
        // User-saved node addresses for quick copy/paste into the Kaspa Node field above -
        // Gson-encoded list, same pattern as KEY_PENDING_KNS_COMMIT below.
        val KEY_SAVED_NODE_ADDRESSES = stringPreferencesKey("saved_node_addresses")
        // NodePoolManager's persisted last-known-good gRPC nodes (comma-joined "host:port"
        // list, best-latency first). Written whenever the automatic-discovery pool has Active
        // nodes; read back on cold start and on a pinned->automatic switch so the pool dials
        // recently-proven nodes immediately instead of waiting on the hardcoded bootstrap
        // seeds (which go stale) or a fresh DNS-seed resolution.
        val KEY_KNOWN_GOOD_NODES = stringPreferencesKey("known_good_node_addresses")
        /** Testnet's own last-known-good list - one saved node list per network (iOS 8f98312). */
        val KEY_KNOWN_GOOD_NODES_TESTNET = stringPreferencesKey("known_good_node_addresses.testnet")
        /** The connection settings of the network NOT in use, kept while it is switched away
         *  from (iOS `AppSettings.connectionProfiles`). Gson [ConnectionProfile]. */
        fun connectionProfileKey(network: com.kachat.app.util.KaspaNetwork.Type) =
            stringPreferencesKey("connection_profile_" + network.raw)
        /** Which network's connection settings KEY_INDEXER_URL ... KEY_SAVED_NODE_ADDRESSES hold
         *  ("mainnet"/"testnet"). Unset (before the IOS-002 port) = the launch network's. */
        val KEY_CONNECTION_KEYS_NETWORK = stringPreferencesKey("connection_keys_network")

        // Defaults matching the iOS app
        const val DEFAULT_NETWORK        = "mainnet"
        const val DEFAULT_INDEXER_URL    = "https://indexer.kachat.app"
        // Superseded defaults, migrated away on read (see `indexerUrl` below): kasia.fyi is offline
        // and never ran the group-chat REST endpoints; kasia.wtf was the previous community default,
        // replaced by KaChat's own indexer, first at kachat.duckdns.org and now indexer.kachat.app.
        const val LEGACY_DEFAULT_INDEXER_URL = "https://indexer.kasia.fyi"
        const val LEGACY_DEFAULT_INDEXER_URL_KASIA_WTF = "https://indexer.kasia.wtf"
        /** KaChat's own indexer under its DuckDNS name, the default until it moved to
         *  indexer.kachat.app (2026-10-28). Moved forward by [replacingRetiredDefaultIndexer]. */
        const val LEGACY_DEFAULT_INDEXER_URL_DUCKDNS = "https://kachat.duckdns.org"

        /** A stored mainnet indexer URL with a superseded default (kasia.fyi, kasia.wtf, the
         *  DuckDNS name) moved to [DEFAULT_INDEXER_URL]; anything else - a custom indexer - is kept. */
        internal fun migratedIndexerUrl(stored: String): String {
            val normalized = stored.trim().trimEnd('/')
            if (normalized.equals(LEGACY_DEFAULT_INDEXER_URL, ignoreCase = true) ||
                normalized.equals(LEGACY_DEFAULT_INDEXER_URL_KASIA_WTF, ignoreCase = true)
            ) return DEFAULT_INDEXER_URL
            // The retired testnet default in a mainnet field clears like on testnet - and blank
            // on mainnet means the default.
            return replacingRetiredDefaultIndexer(stored).ifBlank { DEFAULT_INDEXER_URL }
        }

        /** An indexer, push or `.kachat` names URL still on a retired DuckDNS default moves on:
         *  the mainnet one to the current default, the testnet one to blank (testnet has no
         *  default). Anything else, a custom server included, is kept as it is. Case, trailing
         *  slashes and a missing https:// don't make a different host (iOS fc4aedb8
         *  `replacingRetiredDefaultIndexer`). */
        internal fun replacingRetiredDefaultIndexer(url: String): String {
            var normalized = url.trim().lowercase().trimEnd('/')
            if (normalized.isEmpty()) return url
            if (!normalized.contains("://")) normalized = "https://$normalized"
            return when (normalized) {
                LEGACY_DEFAULT_INDEXER_URL_DUCKDNS -> DEFAULT_INDEXER_URL
                LEGACY_TESTNET_INDEXER_URL -> ""
                else -> url
            }
        }
        const val DEFAULT_KNS_API_URL    = "https://api.knsdomains.org/mainnet/api/v1"
        const val DEFAULT_KASPA_REST_URL = "https://api.kaspa.org"
        /** Testnet-10 (Toccata covenants; the node pool's testnet seeds are on 16210). */
        const val DEFAULT_KASPA_TESTNET_REST_URL = "https://api-tn10.kaspa.org"
        /** Retired TN11 (503) - migrated forward on read (iOS a67a1c2). */
        const val LEGACY_DEFAULT_KASPA_TESTNET_REST_URL = "https://api-tn11.kaspa.org"
        const val DEFAULT_KNS_TESTNET_API_URL = "https://api.knsdomains.org/tn10/api/v1"
        /** The retired testnet-10 default. Testnet has no default indexer: its chat, push and
         *  `.kachat` names fields start blank (every indexer and push call is skipped then) until
         *  someone enters a server; a saved copy of this is cleared ([replacingRetiredDefaultIndexer]). */
        const val LEGACY_TESTNET_INDEXER_URL = "https://tnkachat.duckdns.org:7443"

        /** A stored testnet indexer URL: blank when unset, the retired defaults moved on. */
        internal fun testnetIndexerUrl(stored: String?): String =
            stored?.takeIf { it.isNotBlank() }?.let { replacingRetiredDefaultIndexer(it) } ?: ""

        fun defaultKaspaRestUrl(network: com.kachat.app.util.KaspaNetwork.Type) =
            if (network == com.kachat.app.util.KaspaNetwork.Type.MAINNET) DEFAULT_KASPA_REST_URL else DEFAULT_KASPA_TESTNET_REST_URL
        const val DEFAULT_PUSH_INDEXER_URL = DEFAULT_INDEXER_URL
        /**
         * DataStore keys the retired features wrote: KaPosts (feeds, follows, mutes, notification
         * switches, tips, the poller's last-seen), Public Chats (room indexers, the Popular and
         * avatar switches) and the post translator. [removeRetiredFeatureKeys] deletes them once.
         */
        private val RETIRED_KEY_PREFIXES = listOf(
            "kaposts_", "kapost_indexer_url", "broadcast_", "translation_service_url",
        )
        private val KEY_RETIRED_FEATURES_CLEANED = booleanPreferencesKey("retired_features_cleaned_v1")

        /**
         * DataStore keys the second cut retired (iOS d54ea87): the dock and Kaspa Hub layouts,
         * per account and global, with their one-time migrations, and Portfolio's and Swap's own
         * settings. [removeRetiredFeatureKeys] deletes them once.
         */
        private val RETIRED_KEY_PREFIXES_V2 = listOf(
            "tab_order", "dock_tabs", "hub_tabs", "hidden_tabs", "dock_placement_applied",
            "tab_defaults_40", "portfolio_", "swap_",
        )
        private val KEY_RETIRED_FEATURES_V2_CLEANED = booleanPreferencesKey("retired_features_cleaned_v2")

        /**
         * DataStore keys Simple Mode left behind (iOS eb2ccac): its on/off flag and the
         * "Who will use KaChat?" marker. [removeRetiredFeatureKeys] deletes them once.
         */
        private val RETIRED_KEY_PREFIXES_V3 = listOf("child_mode_enabled", "user_type_choice_state")
        private val KEY_RETIRED_FEATURES_V3_CLEANED = booleanPreferencesKey("retired_features_cleaned_v3")

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
        // Which block explorer website "Go to Explorer" links open in.
        val KEY_KASPA_EXPLORER = stringPreferencesKey("kaspa_explorer")
        // Flat, chain-wide set of txIds the user has manually revealed a hidden photo for —
        // mirrors iOS's `PhotoRevealStore`. Not per-wallet: txIds are unique on-chain already.
        val KEY_REVEALED_PHOTO_TX_IDS = stringSetPreferencesKey("revealed_photo_tx_ids")

        // Per-group hidden/muted member sets - each entry is "{groupId}|{address}", flattened
        // into one Set<String> rather than a nested map since DataStore preferences only have
        // flat native collection types. Mirrors iOS's GroupChatService.groupHiddenMembers/
        // groupMutedMembers (there, a real [String: Set<String>] via UserDefaults-JSON).
        val KEY_GROUP_HIDDEN_MEMBERS = stringSetPreferencesKey("group_hidden_members")
        val KEY_GROUP_MUTED_MEMBERS  = stringSetPreferencesKey("group_muted_members")
        // Group ids with "Only Notify if I'm Mentioned" on - mirrors iOS's
        // GroupChatService.groupMentionsOnlyNotifications.
        val KEY_GROUP_MENTIONS_ONLY  = stringSetPreferencesKey("group_mentions_only")
        val KEY_GROUP_SILENT         = stringSetPreferencesKey("group_silent")

        // Notifications — mirrors iOS's notificationMode/sound/vibration settings, minus
        // the remote-push mode (there's no FCM/APNs-equivalent registration wired up yet,
        // see NotificationHelper — only local notifications while the app process is alive).
        val KEY_NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val KEY_NOTIFICATION_SOUND    = booleanPreferencesKey("notification_sound_enabled")
        val KEY_NOTIFICATION_VIBRATION = booleanPreferencesKey("notification_vibration_enabled")

        // Settings > Notifications > Wallet. Local notifications when any of your spending or
        // cold storage addresses receives Kaspa from an external source — see
        // AddressActivityNotifier. Default ON, mirrors iOS's addressActivityNotificationsEnabled.
        val KEY_ADDRESS_ACTIVITY_NOTIFICATIONS = booleanPreferencesKey("address_activity_notifications_enabled")
        /** Receive notifications for the wallet's own spending addresses (Manage Addresses).
         *  Default on (iOS ba352a2). */
        val KEY_SPENDING_RECEIVE_NOTIFICATIONS = booleanPreferencesKey("spending_receive_notifications_enabled")
        val KEY_SHOW_FEE_ESTIMATE = booleanPreferencesKey("show_fee_estimate")
        // Settings > Chats > Require approval for photos from new contacts (iOS
        // AppSettings.requirePhotoApprovalForNewContacts). On by default: photos from a contact
        // you never added or wrote to stay behind "Show Photo" unless their Chat Info says
        // otherwise. Off shows every contact's photos unless that contact is set to Always Hide.
        val KEY_REQUIRE_PHOTO_APPROVAL = booleanPreferencesKey("require_photo_approval_for_new_contacts")
        // Settings > Chats > Higher quality photos (iOS AppSettings.higherQualityPhotos, off by
        // default): 1:1 on-chain photos use ImagePrep.HIGH_QUALITY_CHAT_TARGET_BYTES, the largest
        // one transaction carries, instead of the default, for a higher fee.
        val KEY_HIGHER_QUALITY_PHOTOS = booleanPreferencesKey("higher_quality_photos")

        // Customizable double-tap quick-reaction set - see QUICK_REACTION_EMOJIS's default and
        // QuickReactionBar (Screens.kt) / QuickReactionBarView (iOS) for where this is read.
        val KEY_QUICK_REACTION_EMOJIS = stringPreferencesKey("quick_reaction_emojis")

        // A single in-flight KNS commit awaiting its reveal — see PendingKnsCommit.
        val KEY_PENDING_KNS_COMMIT = stringPreferencesKey("pending_kns_commit")

        // Device-level message retention (Forever / 30 / 90 days / 1 year) - see BackupRetention.
        val KEY_BACKUP_RETENTION = stringPreferencesKey("backup_retention")

        // Settings > Customization > Dark Mode. True (dark) is the default so existing installs'
        // appearance is unchanged — every screen was designed dark-only until this toggle existed.
        val KEY_DARK_MODE_ENABLED = booleanPreferencesKey("dark_mode_enabled")
        // Settings > Customization > Appearance (iOS AppAppearance): System / Light / Dark, stored
        // as the enum's name. Replaces the Dark Mode switch above: until this is first written, a
        // switch someone set still decides (on = Dark, off = Light); with neither, System.
        val KEY_APPEARANCE = stringPreferencesKey("appearance")
        // Settings > Security. Both on by default — the seed phrase and account login were gated
        // behind device authentication unconditionally before these toggles existed.
        val KEY_BIOMETRIC_SEED_PHRASE_ENABLED = booleanPreferencesKey("biometric_seed_phrase_enabled")
        val KEY_BIOMETRIC_ACCOUNT_LOGIN_ENABLED = booleanPreferencesKey("biometric_account_login_enabled")
        val KEY_BIOMETRIC_SPENDING_KEY_ENABLED = booleanPreferencesKey("biometric_spending_key_enabled")
        // Settings > Connection Settings > Diagnostics. Default OFF: at the 2s sync-loop cadence,
        // per-request success logging is pure logcat noise, so only failures and slow requests are
        // logged unconditionally (see AppModule.provideOkHttpClient). This opt-in turns the full
        // per-request HttpLoggingInterceptor back on for debugging. Matches the iOS toggle.
        val KEY_VERBOSE_API_LOGGING = booleanPreferencesKey("verbose_api_logging")
        // Settings > Customization > Currency. Fiat currency for the Send screens' KAS price
        // (KasPriceRepository) - lowercase ISO 4217 code, doubling as the literal CoinGecko
        // `vs_currency` value (see CoinGeckoApi). Global, not per-account.
        val KEY_CURRENCY = stringPreferencesKey("currency")
        // An onboarding wizard run (auto-presented after create/import) is in progress and hasn't
        // reached Finish yet — killing the app mid-wizard re-presents the FULL guide at next
        // launch. Set when the guide auto-presents, cleared ONLY by its Finish button. Mirrors
        // iOS's "kachat_onboarding_wizard_pending" marker.
        val KEY_ONBOARDING_WIZARD_PENDING = booleanPreferencesKey("onboarding_wizard_pending")
    }

    // -------------------------------------------------------------------------
    // Reactive flows (collect in ViewModel with .stateIn)
    // -------------------------------------------------------------------------

    /** The selected network, "mainnet" or "testnet" - Settings > Connection's Testnet switch
     *  (iOS bdae4b7). Its own store (KaspaNetwork): it must be known before any service starts. */
    val network: Flow<String> = com.kachat.app.util.KaspaNetwork.selected.map { it.raw }

    // MARK: - Connection settings: running vs selected network (iOS e6f664f, IOS-002 / AND-003)
    //
    // The Testnet switch takes effect at the next launch, all at once. Flipping it swaps the
    // connection settings in the DataStore (switchNetwork), so the editable keys hold the CHOSEN
    // network's values - Connection Settings shows and edits those - while the running network's
    // values are set aside in its own profile. Everything the running app connects with (the
    // indexer, REST, push, the node pool, .kachat) reads the
    // flows below that resolve against [KaspaNetwork.launch], so nothing moves until the restart.
    // Connection Settings reads the `selected*` flows instead.

    private val gson = Gson()

    /** The network whose values the editable connection keys hold. */
    private fun liveKeysNetwork(prefs: Preferences): KaspaNetwork.Type =
        prefs[KEY_CONNECTION_KEYS_NETWORK]?.let { KaspaNetwork.Type.fromRaw(it) } ?: KaspaNetwork.launch

    private fun liveKeysProfile(prefs: Preferences) = ConnectionProfile(
        indexerUrl = prefs[KEY_INDEXER_URL],
        pushIndexerUrl = prefs[KEY_PUSH_INDEXER_URL],
        kachatNamesIndexerUrl = prefs[KEY_KACHAT_NAMES_INDEXER_URL],
        kaspaRestUrl = prefs[KEY_KASPA_REST_URL],
        trustedNodeAddress = prefs[KEY_TRUSTED_NODE_ADDRESS],
        savedNodeAddresses = prefs[KEY_SAVED_NODE_ADDRESSES],
    )

    /** [network]'s set-aside profile; null when there is none, or when it was written by a
     *  release build that renamed its fields (ConnectionProfile.decode) - the network's defaults
     *  then apply, and [switchNetwork] drops the record (audit AND-001). */
    private fun storedProfile(prefs: Preferences, network: KaspaNetwork.Type): ConnectionProfile? =
        prefs[connectionProfileKey(network)]?.let { ConnectionProfile.decode(it) }

    /** [network]'s connection settings: the editable keys when they hold that network's, else the
     *  profile they were set aside in, else the network's defaults. */
    private fun connectionProfile(prefs: Preferences, network: KaspaNetwork.Type): ConnectionProfile =
        if (liveKeysNetwork(prefs) == network) liveKeysProfile(prefs)
        else storedProfile(prefs, network) ?: ConnectionProfile.defaults(network)

    /** The running network's settings - what every live client uses until the next launch. */
    private val runningConnection: Flow<Pair<ConnectionProfile, KaspaNetwork.Type>> =
        dataStore.data.map { connectionProfile(it, KaspaNetwork.launch) to KaspaNetwork.launch }

    /** The Testnet switch's network's settings - what Connection Settings shows and edits. */
    private val selectedConnection: Flow<Pair<ConnectionProfile, KaspaNetwork.Type>> =
        combine(dataStore.data, KaspaNetwork.selected) { prefs, network -> connectionProfile(prefs, network) to network }

    private fun Flow<Pair<ConnectionProfile, KaspaNetwork.Type>>.resolve(
        value: (ConnectionProfile, KaspaNetwork.Type) -> String,
    ): Flow<String> = map { (profile, network) -> value(profile, network) }.distinctUntilChanged()

    /** A blank field means the network's own default: the mainnet host on mainnet, none on
     *  testnet (it has no KaChat indexer); a custom URL is kept. */
    private fun indexerOrDefault(stored: String?, default: String, network: KaspaNetwork.Type): String {
        val value = stored?.takeIf { it.isNotBlank() }
        if (network == KaspaNetwork.Type.TESTNET) return testnetIndexerUrl(value)
        // A push host saved on an old default moves to the current one, as the indexer does.
        return value?.let { migratedIndexerUrl(it) } ?: default
    }

    // Transforms away superseded default indexers on read (rather than a one-time write-back
    // migration) - anyone who saved settings on an old default (kasia.fyi, or the previous
    // community default kasia.wtf, or KaChat's own at its old kachat.duckdns.org name) is moved to
    // the current default (indexer.kachat.app). Custom indexers the user typed are kept as-is.
    private fun resolveIndexerUrl(profile: ConnectionProfile, network: KaspaNetwork.Type): String {
        val stored = profile.indexerUrl?.takeIf { url -> url.isNotBlank() }
        if (network == KaspaNetwork.Type.TESTNET) return testnetIndexerUrl(stored)
        return stored?.let { migratedIndexerUrl(it) } ?: DEFAULT_INDEXER_URL
    }

    /** The .kachat names indexer: its own field when set, else the message indexer (iOS 7abc06e).
     *  It started as a copy of the message indexer, so it can hold the same superseded defaults,
     *  which move the same way. */
    private fun resolveKachatNamesIndexerUrl(profile: ConnectionProfile, network: KaspaNetwork.Type): String {
        val stored = profile.kachatNamesIndexerUrl?.takeIf { it.isNotBlank() } ?: return resolveIndexerUrl(profile, network)
        if (network == KaspaNetwork.Type.TESTNET) return testnetIndexerUrl(stored)
        return migratedIndexerUrl(stored)
    }

    /**
     * Always the shipped endpoint. Deliberately NOT overridable: it is display-only in Connection
     * Settings, has no setter, and any value already in the DataStore is ignored.
     *
     * On iOS this was an editable field with no empty-string fallback, so clearing it wrote "" and
     * every KNS call then failed with an unsupported-URL error - there was nothing to point at the
     * setting that caused it. There is no reason for this endpoint to vary per install, so on both
     * platforms it is now pinned rather than merely defaulted.
     */
    private fun knsApiUrlFor(network: KaspaNetwork.Type): String =
        if (network == KaspaNetwork.Type.MAINNET) DEFAULT_KNS_API_URL else DEFAULT_KNS_TESTNET_API_URL

    // The rest stay overridable, but a BLANK stored value is treated as unset rather than passed
    // through - an empty base URL builds a request no HTTP client will send.
    private fun resolveKaspaRestUrl(profile: ConnectionProfile, network: KaspaNetwork.Type): String {
        val stored = profile.kaspaRestUrl?.takeIf { url -> url.isNotBlank() }
            // TN11's API is retired; its old default moves to TN10 (a custom URL is left alone).
            ?.let { url -> if (url == LEGACY_DEFAULT_KASPA_TESTNET_REST_URL) DEFAULT_KASPA_TESTNET_REST_URL else url }
        return stored ?: defaultKaspaRestUrl(network)
    }

    // Falls back to DEFAULT_TRUSTED_NODE_ADDRESS only when the key has never been written at
    // all (a fresh install) - once the user explicitly saves "" (clearing it via the Kaspa Node
    // field), that's a real stored value distinct from "never touched", so it correctly stays
    // empty (normal discovery) instead of snapping back to the default on every read.
    private fun resolveTrustedNodeAddress(profile: ConnectionProfile): String =
        profile.trustedNodeAddress ?: DEFAULT_TRUSTED_NODE_ADDRESS

    private fun resolveSavedNodeAddresses(profile: ConnectionProfile): List<com.kachat.app.models.SavedNodeAddress> =
        profile.savedNodeAddresses?.let { json ->
            try {
                gson.fromJson(json, Array<com.kachat.app.models.SavedNodeAddress>::class.java).toList()
            } catch (e: Exception) {
                emptyList()
            }
        } ?: emptyList()

    // The running network's endpoints - every live client.
    val indexerUrl: Flow<String> = runningConnection.resolve(::resolveIndexerUrl)
    val kachatNamesIndexerUrl: Flow<String> = runningConnection.resolve(::resolveKachatNamesIndexerUrl)
    val knsApiUrl: Flow<String> = flow { emit(knsApiUrlFor(KaspaNetwork.launch)) }
    val kaspaRestUrl: Flow<String> = runningConnection.resolve(::resolveKaspaRestUrl)
    val pushIndexerUrl: Flow<String> = runningConnection.resolve { p, n -> indexerOrDefault(p.pushIndexerUrl, DEFAULT_PUSH_INDEXER_URL, n) }
    val trustedNodeAddress: Flow<String> = runningConnection.resolve { p, _ -> resolveTrustedNodeAddress(p) }
    val savedNodeAddresses: Flow<List<com.kachat.app.models.SavedNodeAddress>> =
        runningConnection.map { (p, _) -> resolveSavedNodeAddresses(p) }.distinctUntilChanged()

    // The Testnet switch's network's values, for Connection Settings: after a switch they are the
    // chosen network's, which the app connects with from the next launch.
    val selectedIndexerUrl: Flow<String> = selectedConnection.resolve(::resolveIndexerUrl)
    val selectedKachatNamesIndexerUrl: Flow<String> = selectedConnection.resolve(::resolveKachatNamesIndexerUrl)
    val selectedKnsApiUrl: Flow<String> = KaspaNetwork.selected.map { knsApiUrlFor(it) }
    val selectedKaspaRestUrl: Flow<String> = selectedConnection.resolve(::resolveKaspaRestUrl)
    val selectedPushIndexerUrl: Flow<String> = selectedConnection.resolve { p, n -> indexerOrDefault(p.pushIndexerUrl, DEFAULT_PUSH_INDEXER_URL, n) }
    val selectedTrustedNodeAddress: Flow<String> = selectedConnection.resolve { p, _ -> resolveTrustedNodeAddress(p) }
    val selectedSavedNodeAddresses: Flow<List<com.kachat.app.models.SavedNodeAddress>> =
        selectedConnection.map { (p, _) -> resolveSavedNodeAddresses(p) }.distinctUntilChanged()

    /**
     * The group chats and bot chats pinned to the front of the Chats circles row, newest pin
     * first - ids "g:<groupId>" / "b:<address>", saved per wallet like iOS's
     * `kachat_chat_circle_pins_<address>` (a062577). Order matters, so it is one newline-joined
     * string rather than a string set; neither a group id nor an address can hold a newline. A
     * pin left from a public room ("r:<room>") matches no circle and is simply never shown.
     */
    private fun chatCirclePinsKey(walletAddress: String) =
        stringPreferencesKey("kachat_chat_circle_pins_${walletAddress.lowercase()}")

    fun chatCirclePins(walletAddress: String): Flow<List<String>> =
        dataStore.data.map { prefs ->
            prefs[chatCirclePinsKey(walletAddress)]?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
        }

    suspend fun setChatCirclePins(walletAddress: String, pins: List<String>) =
        dataStore.edit { it[chatCirclePinsKey(walletAddress)] = pins.joinToString("\n") }

    /**
     * The group chats pinned on Group Chats, newest pin first, saved per wallet like iOS's
     * `kachat_group_pins_<address>` (a53d343). Null until first saved, so the first read can carry
     * over the groups pinned in the old Chats circles row ("g:" ids in [chatCirclePins]).
     */
    private fun groupPinsKey(walletAddress: String) =
        stringPreferencesKey("kachat_group_pins_${walletAddress.lowercase()}")

    fun groupPins(walletAddress: String): Flow<List<String>?> =
        dataStore.data.map { prefs ->
            prefs[groupPinsKey(walletAddress)]?.split('\n')?.filter { it.isNotEmpty() }
        }

    suspend fun setGroupPins(walletAddress: String, pins: List<String>) =
        dataStore.edit { it[groupPinsKey(walletAddress)] = pins.joinToString("\n") }

    // Last-known-good automatic-discovery nodes — see KEY_KNOWN_GOOD_NODES.
    val knownGoodNodeAddresses: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[knownGoodNodesKey]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    }

    val hasWallet: Flow<Boolean> = dataStore.data.map {
        it[KEY_HAS_WALLET] ?: false
    }

    val activeAddress: Flow<String?> = dataStore.data.map {
        it[KEY_ACTIVE_ADDRESS]
    }

    val kaspaExplorer: Flow<com.kachat.app.models.KaspaExplorer> = dataStore.data.map {
        com.kachat.app.models.KaspaExplorer.fromName(it[KEY_KASPA_EXPLORER])
    }

    val revealedPhotoTxIds: Flow<Set<String>> = dataStore.data.map {
        it[KEY_REVEALED_PHOTO_TX_IDS] ?: emptySet()
    }

    /** See KEY_APPEARANCE: the stored choice, else the old Dark Mode switch's, else System. */
    val appearance: Flow<com.kachat.app.models.AppAppearance> = dataStore.data.map { prefs ->
        com.kachat.app.models.AppAppearance.fromName(prefs[KEY_APPEARANCE])
            ?: when (prefs[KEY_DARK_MODE_ENABLED]) {
                true -> com.kachat.app.models.AppAppearance.DARK
                false -> com.kachat.app.models.AppAppearance.LIGHT
                null -> com.kachat.app.models.AppAppearance.SYSTEM
            }
    }

    /** Lowercase ISO 4217 code (e.g. "usd", "eur") - see KEY_CURRENCY. */
    val currency: Flow<String> = dataStore.data.map { it[KEY_CURRENCY] ?: "usd" }

    val biometricSeedPhraseEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_BIOMETRIC_SEED_PHRASE_ENABLED] ?: true }
    // Account-login biometrics are opt-in (off by default) on every platform.
    val biometricAccountLoginEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_BIOMETRIC_ACCOUNT_LOGIN_ENABLED] ?: false }
    /** Gates the "Export" button on a spending address's own screen - separate from
     *  [biometricSeedPhraseEnabled] since revealing one address's own derived key is lower-stakes
     *  than the wallet's whole seed phrase, but still sensitive enough to gate independently. */
    val biometricSpendingKeyEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_BIOMETRIC_SPENDING_KEY_ENABLED] ?: true }
    /** Settings > Security > Child Mode — see [KEY_CHILD_MODE_ENABLED]. */
    /** True while an auto-presented onboarding wizard run hasn't reached Finish — see [KEY_ONBOARDING_WIZARD_PENDING]. */
    val onboardingWizardPending: Flow<Boolean> = dataStore.data.map { it[KEY_ONBOARDING_WIZARD_PENDING] ?: false }
    /** Opt-in per-request HTTP logging, default OFF — see [KEY_VERBOSE_API_LOGGING]. */
    val verboseApiLogging: Flow<Boolean> = dataStore.data.map { it[KEY_VERBOSE_API_LOGGING] ?: false }

    val notificationsEnabled: Flow<Boolean> = dataStore.data.map {
        it[KEY_NOTIFICATIONS_ENABLED] ?: true
    }

    val notificationSoundEnabled: Flow<Boolean> = dataStore.data.map {
        it[KEY_NOTIFICATION_SOUND] ?: true
    }

    val notificationVibrationEnabled: Flow<Boolean> = dataStore.data.map {
        it[KEY_NOTIFICATION_VIBRATION] ?: true
    }

    val addressActivityNotificationsEnabled: Flow<Boolean> = dataStore.data.map {
        it[KEY_ADDRESS_ACTIVITY_NOTIFICATIONS] ?: true
    }

    /** See [KEY_SPENDING_RECEIVE_NOTIFICATIONS]. */
    val spendingReceiveNotifications: Flow<Boolean> = dataStore.data.map {
        it[KEY_SPENDING_RECEIVE_NOTIFICATIONS] ?: true
    }

    val showFeeEstimate: Flow<Boolean> = dataStore.data.map {
        it[KEY_SHOW_FEE_ESTIMATE] ?: true
    }

    val requirePhotoApprovalForNewContacts: Flow<Boolean> = dataStore.data.map {
        it[KEY_REQUIRE_PHOTO_APPROVAL] ?: true
    }

    val higherQualityPhotos: Flow<Boolean> = dataStore.data.map {
        it[KEY_HIGHER_QUALITY_PHOTOS] ?: false
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

    /** [groupMentionsOnly] as notifications apply it: empty while mentions are off
     *  (MentionsFeature) - nobody can mention you then, so "only if mentioned" would silence the
     *  group. The stored choice is left alone for when mentions return (iOS 08dd836). */
    val groupMentionsOnlyApplied: Flow<Set<String>> =
        if (com.kachat.app.util.MentionsFeature.ENABLED) groupMentionsOnly else kotlinx.coroutines.flow.flowOf(emptySet())

    /**
     * Groups muted outright - no banner ever, mentioned or not. Sits ABOVE [groupMentionsOnly]:
     * silent wins, and it is checked before the fallback that fires when a push cannot be
     * ingested.
     */
    val groupSilent: Flow<Set<String>> = dataStore.data.map { it[KEY_GROUP_SILENT] ?: emptySet() }

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

    suspend fun setGroupSilent(groupId: String, enabled: Boolean) = dataStore.edit {
        val current = it[KEY_GROUP_SILENT] ?: emptySet()
        it[KEY_GROUP_SILENT] = if (enabled) current + groupId else current - groupId
    }

    /** Off by default — the user must explicitly turn this on, unlike iOS's iCloud sync. */
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
     * Whether the payment sync has read this wallet address's WHOLE transaction history once on
     * this device (back to the message-retention cutoff), as iOS's first payment phase does after
     * an import (`fetchNewMessages` with `lastPollTime == 0` -> `fetchFullTransactionsPaginated`
     * from block time 0). Until it has, the next full sync pages through all of it, so a fresh
     * import gets back its payment chats: payments it sent (a chat with each destination) and
     * payments it received (the payer's chat, or the self-chat for a stranger). Set only once a
     * scan fetched every page; cleared by a wipe so the re-sync reads it all again. Keyed
     * per-address since a device can hold multiple saved accounts.
     *
     * This replaced `payment_sync_baseline_<address>`, a first-run floor below which every
     * payment was dropped - a fresh import restored none of its payment history (iOS restores
     * all of it). That key is no longer read.
     */
    fun paymentHistoryScanned(address: String): Flow<Boolean> = dataStore.data.map {
        it[paymentHistoryScannedKey(address)] ?: false
    }

    /**
     * Per-account "live" baseline: the first moment this account ever synced on THIS device —
     * which is exactly the import/restore moment for a freshly imported account. Anything
     * on-chain OLDER than it is history being backfilled: inserted as already-read and never
     * notified, so importing an account doesn't replay every past 1:1/group message as a fresh
     * unread notification. Anything newer is live traffic and behaves normally. Get-or-init:
     * the first caller stamps "now".
     */
    suspend fun liveNotificationBaseline(address: String): Long {
        val key = liveNotificationBaselineKey(address)
        dataStore.data.first()[key]?.let { return it }
        val now = System.currentTimeMillis()
        dataStore.edit { it[key] = now }
        return now
    }

    /** Removes the baseline so the NEXT sync stamps a fresh "now" — used when an account's local
     *  data is wiped, so a later re-import treats the whole history as silent read backfill. */
    suspend fun clearLiveNotificationBaseline(address: String) {
        dataStore.edit { it.remove(liveNotificationBaselineKey(address)) }
    }

    /**
     * How far into the `handshakes/by-receiver` stream this wallet has already synced — the
     * indexer's `block_time` cursor, so a sync only asks for what's genuinely new since last time
     * instead of re-fetching the same recent window every cycle. Keyed per-address like
     * [paymentHistoryScanned]. Unlike contextual messages (per-contact-per-alias, so tracked in Room
     * — see [com.kachat.app.models.MessageSyncCursorEntity]), handshakes-by-receiver is a single
     * stream for the whole wallet, so a DataStore value is enough.
     */
    fun handshakeSyncCursor(address: String): Flow<Long?> = dataStore.data.map {
        it[handshakeSyncCursorKey(address)]
    }

    /**
     * Per-account toggle for whether the "Setup Guide" re-entry points (the Profile screen's
     * "Welcome Guide" row and the "Edit KNS Profile" screen's "Setup Guide" section) are shown.
     * Keyed per-address like [paymentHistoryScanned] so switching accounts on the same device
     * doesn't carry the choice over. Defaults to `true` (unset key) to match pre-existing
     * behavior for anyone who had these guides visible before this toggle existed.
     */
    fun showSetupGuides(address: String): Flow<Boolean> = dataStore.data.map {
        it[showSetupGuidesKey(address)] ?: true
    }

    // -------------------------------------------------------------------------
    // Write helpers (suspend — call from coroutine / ViewModel)
    // -------------------------------------------------------------------------

    suspend fun setIndexerUrl(value: String) = dataStore.edit { it[KEY_INDEXER_URL] = value }
    suspend fun setKaspaRestUrl(value: String) = dataStore.edit { it[KEY_KASPA_REST_URL] = value }
    suspend fun setPushIndexerUrl(value: String) = dataStore.edit { it[KEY_PUSH_INDEXER_URL] = value }

    /**
     * KaPosts, Public Chats and the post translator (iOS e2f6732), then the dock layouts,
     * Portfolio and Swap (iOS d54ea87) left the app on 2026-10-24 (iOS `RetiredFeatureCleanup`):
     * every DataStore key they wrote is deleted, once, so nothing of them lingers on the phone.
     */
    suspend fun removeRetiredFeatureKeys() = dataStore.edit { prefs ->
        for ((marker, prefixes) in listOf(
            KEY_RETIRED_FEATURES_CLEANED to RETIRED_KEY_PREFIXES,
            KEY_RETIRED_FEATURES_V2_CLEANED to RETIRED_KEY_PREFIXES_V2,
            KEY_RETIRED_FEATURES_V3_CLEANED to RETIRED_KEY_PREFIXES_V3,
        )) {
            if (prefs[marker] == true) continue
            for (key in prefs.asMap().keys.toList()) {
                if (prefixes.any { key.name.startsWith(it) }) prefs.remove(key)
            }
            prefs[marker] = true
        }
    }
    suspend fun setTrustedNodeAddress(value: String) = dataStore.edit { it[KEY_TRUSTED_NODE_ADDRESS] = value }
    suspend fun setKnownGoodNodeAddresses(addresses: List<String>) = dataStore.edit {
        it[knownGoodNodesKey] = addresses.joinToString(",")
    }

    /** The node pool runs on the launch network, and keeps that network's own list. */
    private val knownGoodNodesKey
        get() = if (com.kachat.app.util.KaspaNetwork.isTestnet) KEY_KNOWN_GOOD_NODES_TESTNET else KEY_KNOWN_GOOD_NODES

    /**
     * Settings > Connection > Testnet (iOS `SettingsViewModel.switchNetwork(to:)`, e6f664f). Only
     * records the choice: the running app stays on [KaspaNetwork.launch] - wallet address, node
     * pool, indexer, REST, push - and the whole switch happens at the next launch
     * ([KaspaNetwork.init] starts on the chosen network). Choosing the running network again
     * cancels it.
     *
     * Each network keeps its own connection settings (iOS `AppSettings.switchNetwork(to:)`): the
     * editable keys take the chosen network's (or its defaults, the first time), so Connection
     * Settings shows and edits what the app will use after the restart, and the values they held
     * are set aside in their network's profile - where the live clients keep reading the running
     * network's from until then (see runningConnection).
     */
    suspend fun switchNetwork(to: com.kachat.app.util.KaspaNetwork.Type) {
        dataStore.edit { prefs ->
            val current = liveKeysNetwork(prefs)
            if (to != current) {
                prefs[connectionProfileKey(current)] = ConnectionProfile.encode(liveKeysProfile(prefs))
                // A record an obfuscated build wrote is reset here, once: the chosen network
                // starts from its defaults instead of misreading it (audit AND-001).
                prefs[connectionProfileKey(to)]?.takeIf { ConnectionProfile.isForeignRecord(it) }?.let {
                    android.util.Log.w("AppSettings", "Dropped an unreadable ${to.raw} connection profile")
                    prefs.remove(connectionProfileKey(to))
                }
                val next = storedProfile(prefs, to) ?: ConnectionProfile.defaults(to)
                fun put(key: androidx.datastore.preferences.core.Preferences.Key<String>, value: String?) {
                    if (value == null) prefs.remove(key) else prefs[key] = value
                }
                put(KEY_INDEXER_URL, next.indexerUrl)
                put(KEY_PUSH_INDEXER_URL, next.pushIndexerUrl)
                put(KEY_KACHAT_NAMES_INDEXER_URL, next.kachatNamesIndexerUrl)
                put(KEY_KASPA_REST_URL, next.kaspaRestUrl)
                put(KEY_TRUSTED_NODE_ADDRESS, next.trustedNodeAddress)
                put(KEY_SAVED_NODE_ADDRESSES, next.savedNodeAddresses)
                prefs[KEY_CONNECTION_KEYS_NETWORK] = to.raw
            }
            // The choice for the next launch, recorded inside the edit so the settings and the
            // switch are written together.
            com.kachat.app.util.KaspaNetwork.select(to)
        }
    }
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
    suspend fun setKaspaExplorer(value: com.kachat.app.models.KaspaExplorer) = dataStore.edit { it[KEY_KASPA_EXPLORER] = value.name }
    suspend fun revealPhoto(txId: String) = dataStore.edit {
        it[KEY_REVEALED_PHOTO_TX_IDS] = (it[KEY_REVEALED_PHOTO_TX_IDS] ?: emptySet()) + txId
    }
    suspend fun setVerboseApiLogging(value: Boolean) = dataStore.edit { it[KEY_VERBOSE_API_LOGGING] = value }
    suspend fun setAppearance(value: com.kachat.app.models.AppAppearance) = dataStore.edit { it[KEY_APPEARANCE] = value.name }
    suspend fun setCurrency(value: String) = dataStore.edit { it[KEY_CURRENCY] = value }
    suspend fun setBiometricSeedPhraseEnabled(value: Boolean) = dataStore.edit { it[KEY_BIOMETRIC_SEED_PHRASE_ENABLED] = value }
    suspend fun setBiometricAccountLoginEnabled(value: Boolean) = dataStore.edit { it[KEY_BIOMETRIC_ACCOUNT_LOGIN_ENABLED] = value }
    suspend fun setBiometricSpendingKeyEnabled(value: Boolean) = dataStore.edit { it[KEY_BIOMETRIC_SPENDING_KEY_ENABLED] = value }
    /** Called right before the first-run guide auto-presents. Never downgrades an already-made
     *  choice (a second account created later re-shows the guide, but the device-level Adult/Child
     *  answer stands and Skip stays available). */
    /** The onboarding wizard auto-presented — an unfinished run must re-present at next launch. */
    suspend fun markOnboardingWizardPending() = dataStore.edit { it[KEY_ONBOARDING_WIZARD_PENDING] = true }
    /** Only the wizard's Finish button clears this. */
    suspend fun clearOnboardingWizardPending() = dataStore.edit { it[KEY_ONBOARDING_WIZARD_PENDING] = false }
    suspend fun setNotificationsEnabled(value: Boolean) = dataStore.edit { it[KEY_NOTIFICATIONS_ENABLED] = value }
    suspend fun setShowFeeEstimate(value: Boolean) = dataStore.edit { it[KEY_SHOW_FEE_ESTIMATE] = value }
    suspend fun setRequirePhotoApprovalForNewContacts(value: Boolean) = dataStore.edit { it[KEY_REQUIRE_PHOTO_APPROVAL] = value }
    suspend fun setHigherQualityPhotos(value: Boolean) = dataStore.edit { it[KEY_HIGHER_QUALITY_PHOTOS] = value }
    suspend fun setQuickReactionEmojis(value: List<String>) = dataStore.edit { it[KEY_QUICK_REACTION_EMOJIS] = Gson().toJson(value) }
    suspend fun setNotificationSoundEnabled(value: Boolean) = dataStore.edit { it[KEY_NOTIFICATION_SOUND] = value }
    suspend fun setNotificationVibrationEnabled(value: Boolean) = dataStore.edit { it[KEY_NOTIFICATION_VIBRATION] = value }
    suspend fun setAddressActivityNotificationsEnabled(value: Boolean) = dataStore.edit { it[KEY_ADDRESS_ACTIVITY_NOTIFICATIONS] = value }
    suspend fun setSpendingReceiveNotifications(value: Boolean) = dataStore.edit { it[KEY_SPENDING_RECEIVE_NOTIFICATIONS] = value }

    suspend fun setBackupRetention(value: com.kachat.app.models.BackupRetention) = dataStore.edit { it[KEY_BACKUP_RETENTION] = value.name }
    suspend fun setPendingKnsCommit(commit: PendingKnsCommit) = dataStore.edit { it[KEY_PENDING_KNS_COMMIT] = Gson().toJson(commit) }
    suspend fun clearPendingKnsCommit() = dataStore.edit { it.remove(KEY_PENDING_KNS_COMMIT) }
    suspend fun setPaymentHistoryScanned(address: String, value: Boolean) = dataStore.edit { it[paymentHistoryScannedKey(address)] = value }

    /**
     * Received payments whose signatures couldn't be checked yet, for [wallet] (iOS 5806362
     * `kachat_held_payments_<wallet>`): txid (lowercased) -> when it was first held (epoch ms).
     * The payment sync re-reads and checks them on every sync for up to 7 days
     * ([com.kachat.app.repository.ChatRepository.heldPaymentsDue]); never shown unverified.
     */
    suspend fun heldPayments(wallet: String): Map<String, Long> {
        val raw = dataStore.data.first()[heldPaymentsKey(wallet)] ?: return emptyMap()
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            (Gson().fromJson(raw, Map::class.java) as Map<String, Any?>)
                .mapNotNull { (txId, at) -> (at as? Number)?.toLong()?.let { txId to it } }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    suspend fun setHeldPayments(wallet: String, held: Map<String, Long>) = dataStore.edit {
        if (held.isEmpty()) it.remove(heldPaymentsKey(wallet)) else it[heldPaymentsKey(wallet)] = Gson().toJson(held)
    }
    suspend fun setHandshakeSyncCursor(address: String, value: Long) = dataStore.edit { it[handshakeSyncCursorKey(address)] = value }

    /** Cursor for the OUTGOING handshakes/by-sender restore pass — separate stream, separate cursor. */
    fun handshakeOutSyncCursor(address: String): Flow<Long?> = dataStore.data.map {
        it[handshakeOutSyncCursorKey(address)]
    }

    suspend fun setHandshakeOutSyncCursor(address: String, value: Long) = dataStore.edit { it[handshakeOutSyncCursorKey(address)] = value }
    suspend fun setShowSetupGuides(address: String, value: Boolean) = dataStore.edit { it[showSetupGuidesKey(address)] = value }

    private fun paymentHistoryScannedKey(address: String) = booleanPreferencesKey("payment_history_scanned_$address")
    private fun heldPaymentsKey(wallet: String) = stringPreferencesKey("kachat_held_payments_${wallet.lowercase()}")
    private fun liveNotificationBaselineKey(address: String) = longPreferencesKey("live_notification_baseline_$address")
    private fun handshakeSyncCursorKey(address: String) = longPreferencesKey("handshake_sync_cursor_$address")
    private fun handshakeOutSyncCursorKey(address: String) = longPreferencesKey("handshake_out_sync_cursor_$address")
    private fun showSetupGuidesKey(address: String) = booleanPreferencesKey("show_setup_guides_$address")
}

/**
 * One network's connection settings - see [AppSettingsRepository.switchNetwork] (iOS
 * `ConnectionProfile`). Stored raw: null is "never set" (the network's default), "" is "none".
 */
data class ConnectionProfile(
    val indexerUrl: String? = null,
    val pushIndexerUrl: String? = null,
    /** Where .kachat names are read (iOS 7abc06e); null = the message indexer. */
    val kachatNamesIndexerUrl: String? = null,
    val kaspaRestUrl: String? = null,
    val trustedNodeAddress: String? = null,
    /** Gson list of SavedNodeAddress, as stored under KEY_SAVED_NODE_ADDRESSES. */
    val savedNodeAddresses: String? = null,
) {
    companion object {
        /** Every field name above, as Gson writes them. app/proguard-rules.pro keeps these names
         *  in release builds; ConnectionProfileStoreTest checks the set against the class. */
        internal val FIELDS = setOf(
            "indexerUrl", "pushIndexerUrl", "kachatNamesIndexerUrl", "kaspaRestUrl", "trustedNodeAddress",
            "savedNodeAddresses",
        )

        /** Fields a record written before 2026-10-24 still carries - the KaPosts, Public Chats and
         *  translation addresses (iOS e2f6732). Ignored when read, and never foreign. */
        private val RETIRED_FIELDS = setOf("kapostIndexerUrl", "broadcastIndexerUrl", "translationServiceUrl")

        private val gson = com.google.gson.Gson()

        fun encode(profile: ConnectionProfile): String = gson.toJson(profile)

        /** A record written with renamed fields (a release build without the keep rule). */
        fun isForeignRecord(json: String): Boolean = com.kachat.app.util.PersistedJson.hasForeignFields(json, FIELDS + RETIRED_FIELDS)

        /** The stored profile; null when it does not parse or [isForeignRecord] - never a
         *  profile read out of a record that is not one. */
        fun decode(json: String): ConnectionProfile? {
            if (isForeignRecord(json)) return null
            return try { gson.fromJson(json, ConnectionProfile::class.java) } catch (e: Exception) { null }
        }

        /** What a network starts with. Testnet: the public testnet-10 REST API, automatic node
         *  discovery, and no KaChat indexer for chat and push (unset = the network's default,
         *  which on testnet is none). */
        fun defaults(network: com.kachat.app.util.KaspaNetwork.Type): ConnectionProfile =
            if (network == com.kachat.app.util.KaspaNetwork.Type.MAINNET) {
                ConnectionProfile()
            } else {
                ConnectionProfile(
                    indexerUrl = null,
                    pushIndexerUrl = null,
                    kaspaRestUrl = AppSettingsRepository.DEFAULT_KASPA_TESTNET_REST_URL,
                    // "" = Automatic Scan: the shipped default node is a mainnet node.
                    trustedNodeAddress = "",
                    savedNodeAddresses = "[]",
                )
            }
    }
}
