package com.kachat.app.ui.screens

import com.kachat.app.ui.theme.iosShadow
import android.content.Context
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Pending
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kachat.app.R
import com.kachat.app.models.ContactEntity
import com.kachat.app.repository.ChatRepository
import com.kachat.app.services.WalletManager
import com.kachat.app.services.WalletService
import com.kachat.app.services.kachatnames.Event
import com.kachat.app.services.kachatnames.GapInfo
import com.kachat.app.services.kachatnames.KachatNames
import com.kachat.app.services.kachatnames.KachatNamesActions
import com.kachat.app.services.kachatnames.KachatNamesRegistry
import com.kachat.app.services.kachatnames.KachatNamesService
import com.kachat.app.services.kachatnames.KachatSocialImageResolver
import com.kachat.app.services.kachatnames.Lookup
import com.kachat.app.services.kachatnames.NameInfo
import com.kachat.app.services.kachatnames.OfferInfo
import com.kachat.app.services.kachatnames.Params
import com.kachat.app.services.kachatnames.PendingRegistration
import com.kachat.app.services.kachatnames.Plan
import com.kachat.app.services.kachatnames.Profile
import com.kachat.app.services.kachatnames.SocialProfile
import com.kachat.app.services.kachatnames.SocialSource
import com.kachat.app.services.kachatnames.Status
import com.kachat.app.services.kachatnames.nowMs
import com.kachat.app.ui.theme.IosActivityIndicator
import com.kachat.app.ui.theme.IosAlertDialog
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.KaspaUnit
import com.kachat.app.util.authenticateWithDeviceCredential
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

// ---------------------------------------------------------------------------------------------
// The live `.kachat` screens, TESTNET ONLY (testnet-10 and a verified registry manifest) - a port
// of iOS KaChat/Views/Ecosystem/KachatNamesLiveViews.swift (KaChat 5df42b4): the hub's search,
// registrations in flight, Marketplace / My Names / Activity, the name detail with its actions,
// every transaction sheet, Your Domains > .kachat and the address profile editor. On mainnet none
// of this is reached - KachatMarketScreen, the listing and the profile editor keep their "Coming
// soon" mockups. Every spending or destructive action shows its cost first, asks to confirm, then
// passes the device's own lock (authenticateWithDeviceCredential, iOS DeviceAuth) before anything
// is signed.
//
// iOS presents its sheets as full-height page sheets with Cancel top left; here, as in the rest of
// the .kachat port (KachatFormSheet), a sheet is a full-screen swap with Cancel top left.
// ---------------------------------------------------------------------------------------------

// MARK: - Amounts

/** "35 TKAS", "0.2 TKAS", "1.99831 TKAS": exact, trailing zeros dropped (iOS `KaspaUnit.amount`). */
fun KaspaUnit.amount(sompi: Long): String = "${plain(sompi)} $symbol"

/** "+1.99 TKAS" / "-36.002 TKAS". */
fun KaspaUnit.signed(delta: Long): String = if (delta >= 0) "+${amount(delta)}" else "-${amount(-delta)}"

// MARK: - Shared pieces

object KachatLive {
    /** The registry is live on this network (testnet only for now) - reads and actions run. Not
     *  the .kachat UI, which is on everywhere ([KachatNamesService.isEnabled], iOS 7227d69). */
    val isEnabled: Boolean get() = KachatNamesService.isLaunched

    /** testnet-10 runs at 10 blocks per second */
    const val DAA_PER_SECOND: Long = 10

    /** An event party: an address (indexer) or an x-only key in hex (walker), as a short address. */
    fun party(s: String?): String? {
        if (s.isNullOrEmpty()) return null
        if (s.startsWith("kaspa")) return KachatNamesRegistry.shortAddress(s)
        val key = runCatching { KachatNames.unhex32(s) }.getOrNull()
        val a = key?.let { KachatNamesRegistry.address(it) }
        return a?.let { KachatNamesRegistry.shortAddress(it) } ?: s
    }

    fun eventIcon(op: String): ImageVector = when (op) {
        "register" -> KachatSymbols.AtBadgePlus
        "transfer" -> Icons.Default.SwapHoriz
        "list" -> Icons.Outlined.Sell
        "delist" -> KachatSymbols.TagSlash
        "sale", "offer_accepted" -> Icons.Outlined.ShoppingCart
        "extend" -> KachatSymbols.CalendarBadgePlus
        "renew" -> Icons.Default.Refresh
        "release" -> Icons.AutoMirrored.Filled.Undo
        "reclaim" -> Icons.Default.Recycling
        else -> Icons.Outlined.PanTool
    }

    @StringRes
    fun eventTitle(op: String): Int = when (op) {
        "register" -> R.string.kn_ev_registered
        "transfer" -> R.string.kn_ev_transferred
        "list" -> R.string.kn_ev_listed
        "delist" -> R.string.kn_ev_delisted
        "sale" -> R.string.kn_ev_sold
        "offer_accepted", "offer_accept" -> R.string.kn_ev_offer_accepted
        "extend" -> R.string.kn_ev_extended
        "renew" -> R.string.kn_ev_renewed
        "release" -> R.string.kn_ev_released
        "reclaim" -> R.string.kn_ev_reclaimed
        "offer" -> R.string.kn_ev_offer_made
        "offer_withdraw" -> R.string.kn_ev_offer_withdrawn
        "offer_refund" -> R.string.kn_ev_offer_refunded
        "offer_decline" -> R.string.kn_ev_offer_declined
        else -> R.string.km_activity
    }

    /** Why a typed name is not a name. */
    @StringRes
    fun invalidReason(name: String): Int? {
        val b = name.toByteArray(Charsets.UTF_8)
        if (b.isEmpty() || b.size > 32) return R.string.kn_invalid_length
        if (!b.all { val x = it.toInt() and 0xff; x in 0x61..0x7a || x in 0x30..0x39 || x == 0x2d }) return R.string.kn_invalid_chars
        if (b.first().toInt() == 0x2d || b.last().toInt() == 0x2d) return R.string.kn_invalid_hyphen
        return null
    }

    /** iOS `.dateTime.year().month().day()` / `.formatted(date: .abbreviated)` / `KachatLive.day`:
     *  "Oct 2, 2026". */
    fun date(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

    /**
     * A unix-ms day as a row value ("Oct 12, 2027"), with the time when it is within two days
     * (testnet's 10-minute periods, or a renewal that opens tomorrow): iOS 49c0baa `KachatLive.day`
     * and `KachatNamesActions.dayString`.
     */
    fun day(ms: Long): String =
        if (kotlin.math.abs(ms - KachatNames.nowMs()) < 2 * 86_400_000L) {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
        } else {
            date(ms)
        }

    /** The registry parameters, once the manifest is verified (iOS `KachatLive.params`, bd2c54a). */
    fun params(service: KachatNamesService): Params? = service.manifest.value?.params

    /** Whether a period is a year (mainnet), not a short test clock (testnet's 10 minutes; iOS 49c0baa). */
    fun yearlyPeriods(p: Params?): Boolean = (p?.periodMs ?: KachatNames.YEAR_MS) == KachatNames.YEAR_MS

    /**
     * A length of time ("10m", "10d") in the app's language (iOS 49c0baa `KachatLive.duration`):
     * days from a day up, else hours and minutes from an hour up, else minutes; zero units dropped.
     */
    fun duration(ms: Long, context: Context): String {
        val s = ms / 1000
        val parts = when {
            s >= 86_400 -> listOf(KachatNamesActions.TimeLeftUnit.DAY to s / 86_400)
            s >= 3600 -> listOf(KachatNamesActions.TimeLeftUnit.HOUR to s / 3600, KachatNamesActions.TimeLeftUnit.MINUTE to (s % 3600) / 60)
                .filter { it.second > 0 }
            else -> listOf(KachatNamesActions.TimeLeftUnit.MINUTE to s / 60)
        }
        return timeLeft(parts, context)
    }

    /**
     * What registering [name] costs for its first period (registry v4: fixed, baked into the
     * pinned templates; iOS c8f1086 `KachatLive.price`). Each further period costs [renewPrice].
     */
    fun price(registry: KachatNamesRegistry, name: String): Long? {
        val prices = registry.registerPrices ?: return null
        if (prices.size != 5) return null
        return prices[KachatNames.Codec.tier(name.toByteArray(Charsets.UTF_8).size)]
    }

    /** What one more period of [name] costs: extend, renew, and registering past the first period
     *  (iOS c8f1086 `KachatLive.renewPrice`). */
    fun renewPrice(registry: KachatNamesRegistry, name: String): Long? {
        val prices = registry.renewPrices ?: return null
        if (prices.size != 5) return null
        return prices[KachatNames.Codec.tier(name.toByteArray(Charsets.UTF_8).size)]
    }

    /** Whether extending [info] by [years] fills its period to exactly `maxYears` (iOS
     *  `KachatExtendSheet.fillsPeriod`, bd2c54a; periodMs from 49c0baa). */
    fun fillsPeriod(info: NameInfo, years: Long, p: Params): Boolean {
        val start = info.periodStart ?: return false
        return info.expiresAt + years * p.periodMs == start + p.maxYears * p.periodMs
    }

    /**
     * "2d 4h" / "3h 12m" / "5m" in the app's language: iOS's abbreviated `DateComponentsFormatter`
     * (ba07975 `KachatOfferRow.expiresIn`) is ICU's narrow measure format.
     */
    fun timeLeft(parts: List<Pair<KachatNamesActions.TimeLeftUnit, Long>>, context: Context): String {
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val measures = parts.map { (unit, value) ->
            android.icu.util.Measure(
                value,
                when (unit) {
                    KachatNamesActions.TimeLeftUnit.DAY -> android.icu.util.MeasureUnit.DAY
                    KachatNamesActions.TimeLeftUnit.HOUR -> android.icu.util.MeasureUnit.HOUR
                    KachatNamesActions.TimeLeftUnit.MINUTE -> android.icu.util.MeasureUnit.MINUTE
                }
            )
        }
        return android.icu.text.MeasureFormat.getInstance(locale, android.icu.text.MeasureFormat.FormatWidth.NARROW)
            .formatMeasures(*measures.toTypedArray())
    }

    /** iOS `.relative(presentation: .named)`: "2 hours ago", "yesterday". */
    fun relative(ms: Long): String =
        DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

    /** What the transaction does to the wallet: its outputs to the wallet minus its inputs from it. */
    fun balanceChange(plan: Plan, me: ByteArray): Long {
        val mine = KachatNames.Codec.p2pkScript(me)
        val received = plan.unsignedTx.outputs.filter { it.script.contentEquals(mine) }.sumOf { it.value }
        val spent = plan.entries.filter { it.script.contentEquals(mine) }.sumOf { it.amount }
        return received - spent
    }
}

/**
 * The actions' and registration driver's errors, the way iOS shows them: its five `ActionError`s
 * and the messages it builds are localized (KachatNamesActions.swift `errorDescription`); the
 * Kotlin services keep them in English, so they are matched and translated here. Anything else
 * (node, REST, builder) shows as it is, as on iOS.
 */
fun Context.kachatErrorText(e: Throwable): String {
    val m = e.message ?: e.toString()
    return when (e) {
        is KachatNamesActions.ActionError.NoWallet -> getString(R.string.kn_err_no_wallet)
        is KachatNamesActions.ActionError.KeyMismatch -> getString(R.string.kn_err_key_mismatch)
        is KachatNamesActions.ActionError.NoSalt -> getString(R.string.kn_err_no_salt)
        is KachatNamesActions.ActionError.InvalidKey -> {
            val what = when (m.removeSuffix(" is not a valid key (not on the secp256k1 curve).")) {
                "The new owner" -> getString(R.string.kn_the_new_owner)
                "Your key" -> getString(R.string.kn_your_key)
                "The buyer" -> getString(R.string.kn_the_buyer)
                else -> return m
            }
            getString(R.string.kn_err_invalid_key, what)
        }
        is KachatNamesActions.ActionError.RenewalNotOpen -> getString(R.string.kn_renewal_opens_on, KachatLive.day(e.opensMs))
        is KachatNamesActions.ActionError.PeriodFull -> getString(R.string.kn_err_period_full_longest, KachatLive.day(e.renewalOpensMs))
        is KachatNamesActions.ActionError.PeriodUnknown -> getString(R.string.kn_err_period_unknown)
        // localized on iOS too (ba07975)
        is KachatNamesActions.ActionError.OfferExpired -> getString(R.string.kn_err_offer_expired)
        is KachatNamesActions.ActionError.OfferDeclined -> getString(R.string.kn_err_offer_declined)
        // localized on iOS too (49c0baa)
        is KachatNamesActions.ActionError.OwnName -> getString(R.string.kn_err_offer_own_name)
        is KachatNamesActions.ActionError.OfferTooLong -> getString(R.string.kn_err_offer_max_days)
        // localized on iOS too (4f5d95e)
        is KachatNamesActions.ActionError.PriceChanged -> getString(R.string.kn_err_price_changed, KaspaUnit.amount(e.price))
        // localized on iOS too (71128c4)
        is KachatNamesActions.ActionError.ExpiredTooLongToRenew -> getString(R.string.kn_err_renew_expired_too_long)
        is KachatNamesActions.ActionError.OfferNameNotActive -> getString(R.string.kn_err_offer_name_not_active)
        is KachatNamesActions.ActionError.AcceptNameExpired -> getString(R.string.kn_err_accept_name_expired)
        is KachatNamesService.ServiceError.RegistryUpgrading -> getString(R.string.kn_registry_upgrading)
        // localized on iOS too (d36fc42 `wrongAddressNetwork`)
        is KachatNamesService.ServiceError.WrongAddressNetwork -> getString(R.string.kn_err_wrong_address_network)
        is KachatNamesActions.ActionError.NotRegisterable -> when {
            m == "An expired name can't be listed. Renew it first." -> getString(R.string.kn_err_expired_list)
            m.endsWith(" is already registered.") -> getString(R.string.kn_err_already_registered, m.removeSuffix(" is already registered."))
            else -> m
        }
        else -> kachatPendingError(m)
    }
}

/** A registration's `lastError`: the driver's own messages are localized (iOS 1ed6e57). */
fun Context.kachatPendingError(m: String): String {
    FREEING_NAME.matchEntire(m)?.let { return getString(R.string.kn_freeing_name, it.groupValues[1]) }
    return when (m) {
        "The commit never reached the chain." -> getString(R.string.kn_err_commit_never)
        "The commit is no longer on chain." -> getString(R.string.kn_err_commit_gone)
        // the busy-network notes (iOS b219bb0)
        KachatNamesActions.COMMIT_WAITING_BUSY -> getString(R.string.kn_commit_waiting_busy)
        KachatNamesActions.COMMIT_SENT_AGAIN -> getString(R.string.kn_commit_sent_again)
        else -> m
    }
}

/** [KachatNamesActions.freeingName] (iOS eea52b2). */
private val FREEING_NAME = Regex("Freeing (.+) for you\\.\\.\\.")

/** iOS `Haptics.success()`. */
private fun android.view.View.successHaptic() = com.kachat.app.util.Haptics.perform(this, com.kachat.app.util.IosHaptic.SUCCESS)

/** The device lock before any `.kachat` transaction is signed - the gate the seed phrase and
 *  private keys use (chat payments have none to copy), iOS `DeviceAuth.authenticate`. */
private fun Context.kachatAuthorize(onSuccess: () -> Unit) {
    authenticateWithDeviceCredential(title = getString(R.string.kn_auth_reason), onSuccess = onSuccess)
}

// MARK: - The view model

/**
 * The live screens' state and services (iOS `KachatHubModel`, plus the singletons iOS reaches as
 * `.shared`). Hub state lives here; the detail and the sheets read the registry and run actions
 * through [registry] and [actions]. Created on testnet only - mainnet never constructs it: the
 * screens that show there (the hub's pages, Your Domains, an address's .kachat tab, the profile
 * editor) take a null model and draw their empty, "Coming soon" state (iOS 7227d69).
 */
@HiltViewModel
class KachatLiveViewModel @Inject constructor(
    val service: KachatNamesService,
    val registry: KachatNamesRegistry,
    val actions: KachatNamesActions,
    /** Social profiles (avatar, banner, bio) looked up on this device (iOS
     *  `KachatSocialImageResolver.shared`, ad32798). */
    val social: KachatSocialImageResolver,
    /** The chatting address's balance, which every name action spends from and pays back to
     *  (iOS `WalletManager.shared.currentWallet?.balanceSompi`, 8ecc38c). */
    val wallet: WalletService,
    /** The block explorer picked in Settings, for the finished-transaction sheet (iOS 0870fcc). */
    val settings: com.kachat.app.repository.AppSettingsRepository,
    private val chatRepository: ChatRepository,
    private val walletManager: WalletManager,
) : ViewModel() {
    sealed class Search {
        object Idle : Search()
        object Checking : Search()
        data class Invalid(val name: String) : Search()
        class Free(val name: String, val gap: GapInfo?) : Search()
        class Registered(val info: NameInfo) : Search()
        class Failed(val error: Throwable) : Search()
    }

    /** null until the manifest is checked; false when it fails (the hub then stays a mockup). */
    var ready by mutableStateOf<Boolean?>(null); private set
    var setupError by mutableStateOf<Throwable?>(null); private set
    /** The manifest is for the previous registry (v1): the hub says "Setting up", calmly (iOS d2e0673). */
    var upgrading by mutableStateOf(false); private set
    var search by mutableStateOf<Search>(Search.Idle); private set
    var listings by mutableStateOf<List<NameInfo>>(emptyList()); private set
    var lapsed by mutableStateOf<List<NameInfo>>(emptyList()); private set
    var mine by mutableStateOf<List<NameInfo>>(emptyList()); private set
    var myOffers by mutableStateOf<List<OfferInfo>>(emptyList()); private set
    var activity by mutableStateOf<List<Event>>(emptyList()); private set
    var loadError by mutableStateOf<Throwable?>(null); private set
    var loaded by mutableStateOf(false); private set

    /** This wallet's x-only key. Read once here: it parses the account list, too slow per row. */
    var myKey by mutableStateOf(if (KachatNamesService.isLaunched) actions.myKey else null); private set

    val isLive: Boolean get() = KachatLive.isEnabled && ready == true
    val graceMs: Long get() = registry.graceMs

    init {
        // iOS: onReceive(registry.$revision.dropFirst()) - the registry changed, reload the pages.
        if (KachatNamesService.isLaunched) {
            viewModelScope.launch {
                registry.revision.drop(1).collect { if (isLive) reload() }
            }
        }
    }

    fun isMine(key: ByteArray): Boolean = myKey?.contentEquals(key) == true

    suspend fun start() {
        if (!KachatLive.isEnabled) {
            ready = null
            return
        }
        myKey = actions.myKey
        try {
            registry.prepare(forceSourceCheck = true)
            ready = true
            setupError = null
            upgrading = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ready = false
            upgrading = KachatNamesService.isRegistryUpgrading(e)
            setupError = e
            return
        }
        actions.resume()
        registry.refresh()
        reload()
    }

    suspend fun refresh() {
        if (!isLive) return
        registry.refresh()
        reload()
    }

    suspend fun reload() {
        if (!isLive) return
        try {
            listings = registry.listings()
            lapsed = registry.lapsed()
            val me = myKey
            if (me != null) {
                mine = registry.names(me, includeInactive = true)
                myOffers = registry.myOffers(me)
                if (myOffers.isNotEmpty()) {
                    actions.refreshVirtualDaa()
                    // Your own expired offers come back to you on their own, and so do the ones
                    // whose name changed hands since you made them (iOS ba07975).
                    actions.returnExpiredOffers(myOffers)
                    actions.withdrawDeclinedOffers(myOffers)
                }
            } else {
                mine = emptyList()
                myOffers = emptyList()
            }
            activity = registry.activity()
            loadError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e
        }
        loaded = true
    }

    suspend fun lookup(text: String) {
        val typed = KachatNames.Codec.normalize(text)
        if (typed.isEmpty()) { search = Search.Idle; return }
        if (KachatLive.invalidReason(typed) != null) { search = Search.Invalid(typed); return }
        search = Search.Checking
        search = try {
            // an expired name past grace searches as free to claim (iOS eea52b2)
            when (val l = registry.claimLookup(typed)) {
                is Lookup.Registered -> Search.Registered(l.info)
                is Lookup.Free -> Search.Free(l.name, l.gap)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Search.Failed(e)
        }
    }

    /** What registering [name] costs for its first period (iOS c8f1086 `KachatLive.price`). */
    fun pricePerYear(name: String): Long? = KachatLive.price(registry, name)

    /** The profile hero's `.kachat` part: your label, your profile's avatar, banner and bio
     *  sources (a social link each, iOS c124cb3) and its Linktree link. */
    data class Hero(val label: String?, val avatar: String?, val banner: String?, val bio: String?, val linktree: String?)

    /**
     * The `.kachat` label of [address] (primary name, else oldest active name) and its address
     * profile's sources and Linktree link, for the profile hero (iOS ContactsView
     * `loadKachatLabel`, 5df42b4 / ad32798 / 1322216 / c124cb3) - the record this wallet last wrote first,
     * else the one the source knows. On mainnet (no registry yet) only the profile: no label
     * (iOS d36fc42, where `identity` is profile-only there).
     */
    suspend fun hero(address: String): Hero? {
        if (!KachatNamesService.profilesEnabled) return null
        registry.refreshIfStale(300_000)
        val identity = try {
            registry.identity(address)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val profile = registry.ownProfile(address)?.profile ?: identity?.profile
        return Hero(identity?.label, profile?.avatar, profile?.banner, profile?.bio, profile?.linktree)
    }

    /** Runs [block] past the screen's life: a send must not be cancelled by closing its sheet
     *  (iOS's unstructured `Task`). */
    fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    /**
     * Opens (or starts) a 1:1 chat with [address] (iOS `KachatLive.message`): its contact row is
     * created first, as a tapped profile link's is (ChatViewModel.openProfile), then [onReady].
     */
    fun message(address: String, onReady: (String) -> Unit) {
        viewModelScope.launch {
            if (chatRepository.getContact(address) == null) {
                chatRepository.addContact(
                    ContactEntity(id = address, walletAddress = walletManager.getAddress(), alias = null, knsName = null, publicKeyHex = null)
                )
            }
            onReady(address)
        }
    }
}

/**
 * Just the social lookups (avatar, banner, bio from a linked account), for the profile editor where
 * the registry isn't launched (mainnet, iOS 7227d69): it shows the live editor there, and its
 * previews look the accounts up, but nothing builds the registry stack.
 */
@HiltViewModel
class KachatSocialViewModel @Inject constructor(
    val social: KachatSocialImageResolver,
) : ViewModel()

// MARK: - Small building blocks

/** The app's glass card (iOS `kachatGlass`): the material surface, rounded, a 0.8 white hairline
 *  at 18% and a soft shadow (black at 10%, radius 8, 4 down). */
private fun Modifier.kachatGlass(colors: com.kachat.app.ui.theme.AppColors, radius: Int = 16): Modifier =
    this.iosShadow(radius.dp, Color.Black.copy(alpha = 0.10f), 8.dp, 4.dp)
        .clip(RoundedCornerShape(radius.dp))
        .background(colors.surface)
        .border(0.8.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(radius.dp))

@Composable
fun KachatTestnetBadge() {
    val colors = LocalAppColors.current
    Text(
        stringResource(R.string.testnet),
        color = colors.warning,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(colors.warning.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun KachatStatusPill(status: Status) {
    val colors = LocalAppColors.current
    val color = when (status) {
        Status.ACTIVE -> colors.success
        Status.GRACE -> colors.warning
        // past grace a name is free to claim (iOS eea52b2)
        Status.LAPSED -> colors.success
    }
    val text = when (status) {
        Status.ACTIVE -> R.string.active
        Status.GRACE -> R.string.kn_status_expired
        Status.LAPSED -> R.string.kn_available
    }
    Text(
        stringResource(text),
        color = color,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/** The green "Available" capsule of a name that is free to claim (iOS f420343). */
@Composable
private fun KachatAvailablePill() {
    val colors = LocalAppColors.current
    Text(
        stringResource(R.string.kn_available),
        color = colors.success,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(colors.success.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun KachatLiveSectionHeader(title: String, detail: String?) {
    val colors = LocalAppColors.current
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        if (detail != null) Text(detail, color = colors.textSecondary, fontSize = 12.sp)
    }
}

/** An empty list's line, or a spinner while loading ([text] null). */
@Composable
private fun KachatLiveEmpty(text: String?) {
    val colors = LocalAppColors.current
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).kachatGlass(colors).padding(vertical = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        if (text != null) Text(text, color = colors.textSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
        else IosActivityIndicator(color = KaspaTeal)
    }
}

@Composable
private fun KachatGlassList(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).kachatGlass(LocalAppColors.current), content = content)
}

@Composable
private fun KachatRowDivider(start: Int) {
    HorizontalDivider(Modifier.padding(start = start.dp), color = LocalAppColors.current.divider, thickness = 0.5.dp)
}

/** iOS `.bordered` / `.borderedProminent` buttons: a tinted capsule, or the filled accent one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KachatButton(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    prominent: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    large: Boolean = false,
    /** iOS `.controlSize(.small)`: a capsule exactly 28 tall, 10 either side of the title. */
    small: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    val tint = if (destructive) colors.danger else KaspaTeal
    // iOS's small control is 28 tall with 15pt type. Material holds every button to 40 dp
    // (ButtonDefaults.MinHeight) inside a 48 dp touch box; both give way here, for this size only:
    // a set minimum stops the 40 dp default applying, and the touch box isn't enforced.
    CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides !small) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = if (small) modifier.defaultMinSize(minWidth = 1.dp, minHeight = 28.dp) else modifier,
        contentPadding = when {
            large -> PaddingValues(horizontal = 12.dp, vertical = 12.dp)
            small -> PaddingValues(horizontal = 10.dp, vertical = 0.dp)
            else -> PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        },
        colors = if (prominent) {
            ButtonDefaults.buttonColors(containerColor = tint, contentColor = Color.Black)
        } else {
            ButtonDefaults.buttonColors(containerColor = tint.copy(alpha = 0.15f), contentColor = tint)
        }
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            title,
            fontWeight = if (prominent) FontWeight.Bold else FontWeight.SemiBold,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    }
}

/** iOS's segmented Picker (the same control the offer mockup draws). */
@Composable
private fun KachatSegmented(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val colors = LocalAppColors.current
    Row(Modifier.fillMaxWidth().padding(8.dp).clip(RoundedCornerShape(8.dp)).background(colors.surfaceVariant).padding(2.dp)) {
        titles.forEachIndexed { index, title ->
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(7.dp))
                    .background(if (index == selected) colors.surface else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(title, color = colors.textPrimary, fontSize = 13.sp, fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

/** "1 year" / "2 years", or on a short clock (testnet) "10m" / "20m" (iOS 49c0baa `KachatYearsText`). */
@Composable
private fun yearsText(years: Int, params: Params?): String = when {
    !KachatLive.yearlyPeriods(params) -> KachatLive.duration(years * (params?.periodMs ?: KachatNames.YEAR_MS), LocalContext.current)
    years == 1 -> stringResource(R.string.kn_one_year)
    else -> stringResource(R.string.kn_n_years, years)
}

/** "Price per year", or on a short clock "Price per 10m" (iOS 49c0baa `pricePerPeriodTitle`). */
@Composable
private fun pricePerPeriodTitle(params: Params?): String =
    if (KachatLive.yearlyPeriods(params)) stringResource(R.string.kn_price_per_year)
    else stringResource(R.string.kn_price_per_period, yearsText(1, params))

/** A Form text field: no box, the row is the field. */
@Composable
private fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    textStyle: TextStyle = LocalTextStyle.current,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    textAlign: TextAlign = TextAlign.Start,
) {
    val colors = LocalAppColors.current
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = colors.textTertiary, style = textStyle, textAlign = textAlign, modifier = Modifier.fillMaxWidth()) },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        textStyle = textStyle.copy(textAlign = textAlign),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrect = false, keyboardType = keyboardType),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            focusedTextColor = colors.textPrimary, unfocusedTextColor = colors.textPrimary, cursorColor = KaspaTeal,
        ),
        modifier = modifier
    )
}

/** A Form section: header, the rounded card, and its footer. */
@Composable
private fun FormSection(
    header: String? = null,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.padding(bottom = 16.dp)) {
        SettingsSection(title = header, content = content)
        footer?.invoke()
    }
}

/** iOS section footer, in any colour (red errors, green "Saved"). */
@Composable
private fun FormFooter(text: String, color: Color = LocalAppColors.current.textSecondary) {
    Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = color, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

/** A Form row: title left, value right (iOS `LabeledRow`). */
@Composable
private fun LabeledRow(title: String, value: String, bold: Boolean = false) {
    val colors = LocalAppColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = colors.textPrimary, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            color = colors.textPrimary,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/** A row with a spinner where the value goes (iOS `HStack { Text; Spacer; ProgressView }`). */
@Composable
private fun LoadingRow(title: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = LocalAppColors.current.textPrimary, modifier = Modifier.weight(1f))
        IosActivityIndicator(color = KaspaTeal, modifier = Modifier.size(18.dp))
    }
}

/** A Form's button row: centered, accent (red when destructive), grey when disabled. */
@Composable
private fun FormButtonRow(title: String, enabled: Boolean, busy: Boolean = false, destructive: Boolean = false, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Box(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled && !busy, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (busy) {
            IosActivityIndicator(color = KaspaTeal)
        } else {
            Text(
                title,
                color = if (!enabled) colors.textTertiary else if (destructive) colors.danger else KaspaTeal,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

/** A sheet: a Form with its title, Cancel top left (Done top right once it is finished). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KachatLiveForm(title: String, onClose: () -> Unit, finished: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onClose)
    val colors = LocalAppColors.current
    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (!finished) TextButton(onClick = onClose) { Text(stringResource(R.string.cancel), color = KaspaTeal) }
                },
                actions = {
                    if (finished) TextButton(onClick = onClose) { Text(stringResource(R.string.done), color = KaspaTeal, fontWeight = FontWeight.Bold) }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background)
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 40.dp),
            content = content,
        )
    }
}

/** An amount field with the unit after it (offer, list price). */
@Composable
private fun AmountField(value: String, onValueChange: (String) -> Unit) {
    val colors = LocalAppColors.current
    Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        FormTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = "0",
            keyboardType = KeyboardType.Decimal,
            textStyle = LocalTextStyle.current.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f)
        )
        Text(KaspaUnit.symbol, color = colors.textSecondary)
    }
}

// MARK: - Name tiles

/**
 * A square tile for one name in the marketplace grids (For sale, Available; iOS 27a4f39
 * `KachatNameTile`): the full name - it wraps onto more lines, never truncates, and the tile grows
 * to fit - with ".kachat" under it, then the footer: the price asked, and any button. Centered
 * (iOS c488d1d). [name] null
 * draws the tile's shape redacted (the preview pages: no invented name). [onClick] opens the name;
 * a button in [footer] keeps its own tap.
 */
@Composable
fun KachatNameTile(
    name: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    footer: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalAppColors.current
    val redacted = colors.textSecondary.copy(alpha = 0.25f)
    Column(
        modifier.fillMaxWidth().heightIn(min = 140.dp).kachatGlass(colors)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (name != null) {
                Text(name, color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp, textAlign = TextAlign.Center)
                Text(".kachat", color = KaspaTeal, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            } else {
                Box(Modifier.size(90.dp, 17.dp).clip(RoundedCornerShape(4.dp)).background(redacted))
                Box(Modifier.size(44.dp, 12.dp).clip(RoundedCornerShape(4.dp)).background(redacted))
            }
        }
        footer()
        Spacer(Modifier.weight(1f))
    }
}

/** Two tiles per row (iOS 27a4f39 `KachatNameGrid`). */
@Composable
fun <T> KachatNameGrid(items: List<T>, tile: @Composable (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                row.forEach { item -> Box(Modifier.weight(1f)) { tile(item) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** One line that shrinks to fit rather than being cut, down to [minScale] of its size (iOS
 *  `.lineLimit(1).minimumScaleFactor`). */
@Composable
private fun KachatFitText(
    text: String,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit = 15.sp,
    fontWeight: FontWeight = FontWeight.SemiBold,
    minScale: Float = 0.7f,
    textAlign: TextAlign? = null,
    modifier: Modifier = Modifier,
) {
    var size by remember(text) { mutableStateOf(fontSize) }
    Text(
        text,
        color = color,
        fontSize = size,
        fontWeight = fontWeight,
        maxLines = 1,
        softWrap = false,
        textAlign = textAlign,
        modifier = modifier,
        onTextLayout = { result ->
            if (result.hasVisualOverflow && size.value > fontSize.value * minScale) {
                size = (size.value * 0.94f).coerceAtLeast(fontSize.value * minScale).sp
            }
        },
    )
}

// MARK: - Hub: search result

/** A free name to claim, and the gap it is in. */
class KachatClaimTarget(val name: String, val gap: GapInfo)

/** The live search result under the hub's search field: availability, the price a year and
 *  Claim; a registered name opens its detail. */
@Composable
fun KachatLiveSearchResult(
    vm: KachatLiveViewModel,
    typed: String,
    onOpen: (NameInfo) -> Unit,
    onClaim: (KachatClaimTarget) -> Unit,
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    LaunchedEffect(typed) {
        delay(350)
        vm.lookup(typed)
    }
    val name = KachatNames.Codec.normalize(typed)

    @Composable
    fun row(subtitle: String) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("$name.kachat", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = colors.textSecondary, fontSize = 12.sp)
        }
    }

    Box(Modifier.fillMaxWidth().kachatGlass(colors, 12).padding(12.dp)) {
        when (val s = vm.search) {
            is KachatLiveViewModel.Search.Registered -> if (s.info.name == name) {
                val n = s.info
                Row(Modifier.fillMaxWidth().clickable { onOpen(n) }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(n.display, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        when (n.status(vm.graceMs)) {
                            Status.ACTIVE -> Text(
                                when {
                                    vm.isMine(n.owner) -> stringResource(R.string.kn_yours)
                                    n.isListed -> stringResource(R.string.kn_taken_for_sale, KaspaUnit.amount(n.price))
                                    else -> stringResource(R.string.kn_taken)
                                },
                                color = colors.textSecondary, fontSize = 12.sp
                            )
                            Status.GRACE -> Text(stringResource(R.string.kn_search_grace), color = colors.warning, fontSize = 12.sp)
                            // never reached: a lapsed name searches as free to claim (`claimLookup`, iOS eea52b2)
                            Status.LAPSED -> Unit
                        }
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(20.dp))
                }
            } else SearchChecking(name)
            is KachatLiveViewModel.Search.Free -> if (s.name == name) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${s.name}.kachat", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val params = vm.service.manifest.collectAsState().value?.params
                        vm.pricePerYear(s.name)?.let { price ->
                            val text = if (KachatLive.yearlyPeriods(params)) {
                                stringResource(R.string.kn_available_per_year, KaspaUnit.amount(price))
                            } else {
                                stringResource(R.string.kn_available_per_period, KaspaUnit.amount(price), yearsText(1, params))
                            }
                            Text(text, color = colors.success, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    val gap = s.gap
                    KachatButton(stringResource(R.string.km_claim), prominent = true, enabled = gap != null) {
                        if (gap != null) onClaim(KachatClaimTarget(s.name, gap))
                    }
                }
            } else SearchChecking(name)
            is KachatLiveViewModel.Search.Invalid -> row(stringResource(KachatLive.invalidReason(name) ?: R.string.kn_not_valid_name))
            is KachatLiveViewModel.Search.Failed -> row(context.kachatErrorText(s.error))
            else -> SearchChecking(name)
        }
    }
}

@Composable
private fun SearchChecking(name: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$name.kachat", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IosActivityIndicator(color = KaspaTeal, modifier = Modifier.size(20.dp))
    }
}

// MARK: - A finished transaction

/** A name transaction that went out: what it did and its id, for [KachatTxDoneSheet] (iOS
 *  `KachatTxDone`, 0870fcc). */
data class KachatTxDone(val txId: String, @StringRes val title: Int = R.string.kn_done_tx_sent)

/**
 * The half sheet every finished name transaction shows: what happened, the transaction id (tap to
 * copy), and a link to it on the block explorer - the one picked in Settings, which on testnet is
 * the testnet-10 explorer ([com.kachat.app.models.KaspaExplorer.txUrl]). It opens in the in-app
 * browser, over the sheet, as iOS's full-screen cover does (iOS `KachatTxDoneSheet`, 0870fcc).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KachatTxDoneSheet(done: KachatTxDone, onDismiss: () -> Unit, vm: KachatLiveViewModel = hiltViewModel()) {
    val colors = LocalAppColors.current
    val view = LocalView.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val explorer by vm.settings.kaspaExplorer.collectAsState(initial = com.kachat.app.models.KaspaExplorer.default)
    val explorerUrl = explorer.txUrl(done.txId)
    var copied by remember(done.txId) { mutableStateOf(false) }
    var browserUrl by remember { mutableStateOf<String?>(null) }

    com.kachat.app.ui.theme.IosSheetColors {
        val colors = LocalAppColors.current
        ModalBottomSheet(
            shape = com.kachat.app.ui.theme.IosSheetShape,
            tonalElevation = com.kachat.app.ui.theme.IosSheetTonalElevation,
            windowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars,
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.background,
        ) {
            // The sheet runs down behind the navigation bar, as iOS's does behind the home indicator;
            // its content stays above it.
            Column(Modifier.navigationBarsPadding()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = colors.success, modifier = Modifier.padding(top = 8.dp).size(48.dp))
                    Text(stringResource(done.title), color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp, textAlign = TextAlign.Center)
                    Text(stringResource(R.string.kn_sent_note), color = colors.textSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(colors.surface)
                            .clickable {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(done.txId))
                                copied = true
                                view.successHaptic()
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // iOS truncates in the middle: the start and the end of a txid are what people compare.
                        val id = done.txId
                        Text(
                            if (id.length > 28) "${id.take(13)}...${id.takeLast(13)}" else id,
                            color = colors.textSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(if (copied) Icons.Default.Check else Icons.Default.ContentCopy, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(14.dp))
                    }
                    KachatButton(
                        stringResource(R.string.view_in_explorer),
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Outlined.Explore,
                        prominent = true,
                        large = true
                    ) { browserUrl = explorerUrl }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.done), color = KaspaTeal, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    }
                }
            }
        }
    }

    browserUrl?.let { url ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { browserUrl = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            InAppBrowserScreen(url = url, title = runCatching { java.net.URI(url).host }.getOrNull() ?: "", onClose = { browserUrl = null })
        }
    }
}

// MARK: - Registrations in flight

/**
 * iOS's sheet grabber: the short rounded bar a sheet with more than one detent shows at its top
 * (the medium and large stops here), 5 high and 36 wide, 5 below the sheet's edge.
 */
@Composable
private fun KachatSheetGrabber() {
    Box(Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 5.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 36.dp, height = 5.dp).clip(CircleShape).background(LocalAppColors.current.textSecondary.copy(alpha = 0.5f)))
    }
}

/**
 * A registration's progress (iOS 61fb0fc `KachatRegistrationProgressSheet`'s body): "Claiming
 * name.kachat", the registration card, and - while it still needs the app - the keep-open note.
 * Claiming takes the app being open (the commit has to age about a minute before the name
 * registers). Swiping its half sheet away leaves the claim running: the .kachat screen's claims
 * button ([KachatClaimsButton]) lists it (iOS b219bb0). Shown by the claim sheet itself once Claim
 * went through, and by [KachatRegistrationPresenter] after a relaunch.
 */
@Composable
private fun KachatRegistrationProgressContent(registration: PendingRegistration, vm: KachatLiveViewModel) {
    val colors = LocalAppColors.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            stringResource(R.string.kn_claiming_name, "${registration.name}.kachat"),
            color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, textAlign = TextAlign.Center,
            // 22 from the sheet's top edge, the grabber's 10 included
            modifier = Modifier.padding(top = 12.dp, start = 24.dp, end = 24.dp)
        )
        KachatRegistrationCard(registration, vm)
        if (registration.needsDriving) {
            Text(
                stringResource(R.string.kn_keep_open_claiming),
                color = colors.textSecondary, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
    }
}

/**
 * The app-level progress sheet (iOS `KachatRegistrationPresenter`, on MainTabView): brings a claim
 * still in progress back up once when the app starts (a claim needs the app open to finish;
 * [KachatNamesActions.autoPresentedRegistration]). A half sheet - medium, draggable to large -
 * that swiping away (or Back) closes, leaving the claim running; it closes by itself once the
 * registration is dismissed (Done) or its commit was cancelled (iOS b219bb0). Launched networks
 * only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KachatRegistrationPresenter() {
    if (!KachatNamesService.isLaunched) return
    val vm: KachatLiveViewModel = hiltViewModel()
    val pending by vm.actions.pending.collectAsState()
    val shownId by vm.actions.autoPresentedRegistration.collectAsState()
    val id = shownId ?: return
    val registration = pending.firstOrNull { it.id == id }
    key(id) {
        val sheetState = rememberModalBottomSheetState()
        fun clear() {
            if (vm.actions.autoPresentedRegistration.value == id) vm.actions.autoPresentedRegistration.value = null
        }
        // closes once the registration is dismissed (Done) or its commit was cancelled
        val over = registration?.isOpen != true
        LaunchedEffect(over) {
            if (over) {
                try {
                    sheetState.hide()
                } finally {
                    clear()
                }
            }
        }
        com.kachat.app.ui.theme.IosSheetColors {
            ModalBottomSheet(
                shape = com.kachat.app.ui.theme.IosSheetShape,
                tonalElevation = com.kachat.app.ui.theme.IosSheetTonalElevation,
                windowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars,
                onDismissRequest = { clear() },
                sheetState = sheetState,
                containerColor = LocalAppColors.current.background,
                dragHandle = { KachatSheetGrabber() },
            ) {
                // The sheet runs down behind the navigation bar, as iOS's does behind the home indicator;
                // its content stays above it.
                Column(Modifier.navigationBarsPadding()) {
                    if (registration != null) KachatRegistrationProgressContent(registration, vm)
                }
            }
        }
    }
}

/**
 * The .kachat screen's claims button, next to "How it works" (iOS b219bb0 `KachatClaimsButton`):
 * the names being claimed right now (and finished ones not yet dismissed), with a count. Hidden
 * when there are none, and where the registry isn't launched.
 */
@Composable
fun KachatClaimsButton(vm: KachatLiveViewModel) {
    if (!KachatNamesService.isLaunched) return
    val pending by vm.actions.pending.collectAsState()
    val open = KachatNamesActions.openRegistrations(pending)
    var showList by remember { mutableStateOf(false) }
    if (open.isNotEmpty()) {
        val label = stringResource(R.string.kn_names_being_claimed)
        IconButton(onClick = { showList = true }, modifier = Modifier.semantics { contentDescription = label }) {
            Box {
                Icon(Icons.Default.HourglassEmpty, contentDescription = null, tint = KaspaTeal)
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 9.dp, y = (-8).dp)
                        .defaultMinSize(minWidth = 15.dp, minHeight = 15.dp)
                        .clip(RoundedCornerShape(50)).background(KaspaTeal)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("${open.size}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 10.sp, lineHeight = 15.sp)
                }
            }
        }
    }
    if (showList) KachatClaimsListSheet(vm, onDismiss = { showList = false })
}

/**
 * Every open claim with its progress: the list behind [KachatClaimsButton] (iOS b219bb0
 * `KachatClaimsListSheet`). A half sheet - medium, draggable to large - titled "Claiming" with
 * Done; it closes by itself once the last claim is dismissed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KachatClaimsListSheet(vm: KachatLiveViewModel, onDismiss: () -> Unit) {
    val pending by vm.actions.pending.collectAsState()
    val open = KachatNamesActions.openRegistrations(pending)
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val close by rememberUpdatedState(onDismiss)
    fun dismiss() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { close() }
    }
    // the last one dismissed: nothing left to show
    val empty = open.isEmpty()
    LaunchedEffect(empty) {
        if (empty) {
            try {
                sheetState.hide()
            } finally {
                close()
            }
        }
    }
    com.kachat.app.ui.theme.IosSheetColors(grouped = true) {
        val colors = LocalAppColors.current
        ModalBottomSheet(
            shape = com.kachat.app.ui.theme.IosSheetShape,
            tonalElevation = com.kachat.app.ui.theme.IosSheetTonalElevation,
            windowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars,
            onDismissRequest = { close() },
            sheetState = sheetState,
            containerColor = colors.background,
            dragHandle = { KachatSheetGrabber() },
        ) {
            // The sheet runs down behind the navigation bar, as iOS's does behind the home indicator;
            // its content stays above it.
            Column(Modifier.navigationBarsPadding()) {
                // The inline navigation bar: "Claiming" and Done (right).
                Box(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp)) {
                    Text(
                        stringResource(R.string.kn_claiming_title),
                        color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    TextButton(onClick = { dismiss() }, modifier = Modifier.align(Alignment.CenterEnd)) {
                        Text(stringResource(R.string.done), color = KaspaTeal, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    }
                }
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    open.forEach { registration ->
                        key(registration.id) { KachatRegistrationCard(registration, vm) }
                    }
                    Text(
                        stringResource(R.string.kn_keep_open_claiming),
                        color = colors.textSecondary, fontSize = 13.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }
        }
    }
}

/** One registration in flight: its stage, the commit's maturity progress, and what can be done
 *  (Done, Cancel Commit when the name was taken, Try Again). */
@Composable
fun KachatRegistrationCard(registration: PendingRegistration, vm: KachatLiveViewModel) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val virtualDaa by vm.actions.virtualDaa.collectAsState()
    val manifest by vm.service.manifest.collectAsState()
    var confirmCancel by remember(registration.id) { mutableStateOf(false) }
    var working by remember(registration.id) { mutableStateOf(false) }
    var error by remember(registration.id) { mutableStateOf<String?>(null) }
    var done by remember(registration.id) { mutableStateOf<KachatTxDone?>(null) }
    val tCommit = manifest?.params?.tCommit ?: 600L

    // The finished registration (or cancelled commit) as the half sheet shows it (iOS 0870fcc).
    val finished: KachatTxDone? = when (registration.stage) {
        PendingRegistration.Stage.REGISTERED -> registration.registerTxId?.let { KachatTxDone(it, R.string.kn_done_registered) }
        PendingRegistration.Stage.CANCELLED -> registration.cancelTxId?.let { KachatTxDone(it, R.string.kn_done_commit_cancelled) }
        else -> null
    }
    // Pops up the moment the registration lands (or the commit is cancelled) - on a change of
    // stage, as iOS's onChange, not every time the card appears.
    var seenStage by remember(registration.id) { mutableStateOf(registration.stage) }
    LaunchedEffect(registration.stage) {
        if (registration.stage != seenStage) {
            seenStage = registration.stage
            finished?.let { done = it }
        }
    }

    fun authorizeCancel() {
        context.kachatAuthorize {
            working = true
            error = null
            vm.launch {
                try {
                    vm.actions.cancel(registration)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = context.kachatErrorText(e)
                }
                working = false
            }
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).kachatGlass(colors).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${registration.name}.kachat", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
            when {
                registration.needsDriving -> IosActivityIndicator(color = KaspaTeal, modifier = Modifier.size(20.dp))
                registration.stage == PendingRegistration.Stage.REGISTERED -> Icon(Icons.Default.Verified, null, tint = colors.success)
                else -> Icon(Icons.Default.Error, null, tint = colors.warning)
            }
        }
        val stageText = when (registration.stage) {
            PendingRegistration.Stage.COMMITTING -> stringResource(R.string.kn_stage_committing)
            PendingRegistration.Stage.WAITING ->
                if (registration.commitDaa == null) stringResource(R.string.kn_stage_waiting_confirm) else stringResource(R.string.kn_stage_waiting)
            PendingRegistration.Stage.REGISTERING -> stringResource(R.string.kn_stage_registering)
            PendingRegistration.Stage.REGISTERED -> stringResource(R.string.kn_stage_registered)
            PendingRegistration.Stage.TAKEN -> KaspaUnit.label(stringResource(R.string.kn_stage_taken))
            PendingRegistration.Stage.FAILED -> stringResource(R.string.kn_stage_failed)
            PendingRegistration.Stage.CANCELLING -> stringResource(R.string.kn_stage_cancelling)
            PendingRegistration.Stage.CANCELLED -> stringResource(R.string.kn_stage_cancelled)
        }
        Text(stageText, color = colors.textSecondary, fontSize = 15.sp)
        val daa = registration.commitDaa
        val now = virtualDaa
        if (registration.stage == PendingRegistration.Stage.WAITING && daa != null && now != null) {
            // matured at commitDaa + tCommit, registered a little past it (the driver's + 20)
            val target = (tCommit + 20).toDouble()
            val done = (if (now > daa) now - daa else 0L).toDouble()
            LinearProgressIndicator(
                progress = { (minOf(done, target) / target).toFloat() },
                color = KaspaTeal,
                trackColor = colors.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
            val left = maxOf(0, ((target - done) / KachatLive.DAA_PER_SECOND).toInt())
            Text(stringResource(R.string.kn_seconds_to_go, left), color = colors.textSecondary, fontSize = 12.sp)
        }
        val message = error ?: registration.lastError?.takeIf { registration.stage == PendingRegistration.Stage.FAILED }?.let { context.kachatPendingError(it) }
        val note = registration.lastError?.takeIf { registration.needsDriving }
        if (message != null) {
            Text(message, color = colors.danger, fontSize = 12.sp)
        } else if (note != null) {
            // what the driver is doing or waiting on (a busy network, freeing an expired name; iOS b219bb0)
            Text(context.kachatPendingError(note), color = colors.textSecondary, fontSize = 12.sp)
        }
        when (registration.stage) {
            PendingRegistration.Stage.REGISTERED, PendingRegistration.Stage.CANCELLED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                finished?.let { f -> KachatButton(stringResource(R.string.kn_view_transaction), prominent = true) { done = f } }
                KachatButton(stringResource(R.string.done)) { vm.actions.dismiss(registration) }
            }
            PendingRegistration.Stage.TAKEN ->
                KachatButton(stringResource(R.string.kn_cancel_commit), destructive = true, enabled = !working) { confirmCancel = true }
            PendingRegistration.Stage.FAILED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KachatButton(stringResource(R.string.try_again), prominent = true) { vm.actions.retry(registration) }
                KachatButton(stringResource(R.string.kn_cancel_commit), destructive = true, enabled = !working) { confirmCancel = true }
            }
            else -> Unit
        }
    }

    done?.let { KachatTxDoneSheet(it, onDismiss = { done = null }, vm = vm) }

    if (confirmCancel) {
        IosAlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text(stringResource(R.string.kn_cancel_commit_title)) },
            text = { Text(KaspaUnit.label(stringResource(R.string.kn_cancel_commit_body))) },
            confirmButton = {
                TextButton(onClick = { confirmCancel = false; authorizeCancel() }) {
                    Text(stringResource(R.string.kn_cancel_commit), color = colors.danger, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) { Text(stringResource(R.string.kn_keep), color = KaspaTeal, fontWeight = FontWeight.SemiBold) }
            }
        )
    }
}

// MARK: - Hub: pages

/**
 * Marketplace: names for sale (iOS 0765ce0 `KachatLiveMarketPage`). [vm] is null where the registry
 * isn't launched (mainnet): the same page, empty (iOS 7227d69).
 */
@Composable
fun KachatLiveMarketPage(vm: KachatLiveViewModel?, onOpen: (NameInfo) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 4.dp)) {
        KachatLiveSectionHeader(stringResource(R.string.kn_for_sale), null)
        if (vm == null || vm.listings.isEmpty()) {
            KachatLiveEmpty(if (vm?.loaded != false) stringResource(R.string.kn_no_listings) else null)
        } else {
            val colors = LocalAppColors.current
            val manifest by vm.service.manifest.collectAsState()
            // 30 days on mainnet's yearly clock, the renewal window on testnet's 10-minute one (iOS ad184c3)
            val soonMs = manifest?.params?.expiresSoonMs ?: (30L * 86_400_000L)
            KachatNameGrid(vm.listings) { n ->
                KachatNameTile(n.name, onClick = { onOpen(n) }) {
                    KachatFitText(KaspaUnit.amount(n.price), color = colors.textPrimary)
                    // what a buyer gets: the paid time left, flagged when it's short (iOS ad184c3)
                    Text(
                        stringResource(R.string.kn_expires_on, KachatLive.day(n.expiresAt)),
                        color = colors.textSecondary, fontSize = 11.sp, textAlign = TextAlign.Center
                    )
                    if (n.expiresAt - soonMs < KachatNames.nowMs()) {
                        Text(
                            stringResource(R.string.kn_expires_soon),
                            color = colors.warning, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(colors.warning.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Available: names that expired and stayed unrenewed through the grace period, back on the market
 * at the normal price. Claim frees the old record and registers it in one go (the claim sheet,
 * then the progress half sheet; iOS eea52b2 `KachatLiveAvailablePage`). [vm] null: mainnet, empty
 * (iOS 7227d69).
 */
@Composable
fun KachatLiveAvailablePage(vm: KachatLiveViewModel?, onOpen: (NameInfo) -> Unit, onClaim: (KachatClaimTarget) -> Unit) {
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 4.dp)) {
        KachatLiveSectionHeader(stringResource(R.string.kn_available), null)
        if (vm == null || vm.lapsed.isEmpty()) {
            KachatLiveEmpty(if (vm?.loaded != false) stringResource(R.string.kn_no_expired_names) else null)
        } else {
            // the tile opens the name; the Claim button inside keeps its own tap
            val colors = LocalAppColors.current
            vm.service.manifest.collectAsState().value // re-price once the manifest (its price tables) loads
            KachatNameGrid(vm.lapsed) { n ->
                KachatNameTile(n.name, onClick = { onOpen(n) }) {
                    // what claiming it costs: the price for its length (iOS c488d1d)
                    KachatLive.price(vm.registry, n.name)?.let { price ->
                        KachatFitText(KaspaUnit.amount(price), color = colors.textPrimary)
                    }
                    KachatButton(stringResource(R.string.km_claim), small = true, prominent = true) {
                        // in the gap its reclaim reopens (iOS eea52b2 `claimGap`)
                        scope.launch {
                            val gap = try {
                                vm.registry.claimGap(n)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                null
                            }
                            if (gap != null) onClaim(KachatClaimTarget(n.name, gap))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The offers this wallet made, with Withdraw (and Refund once expired). Shown in Profile > Your
 * Domains > .kachat, under your names - moved there from the marketplace's former My Names tab
 * (iOS 0765ce0 `KachatMyOffersSection`).
 */
@Composable
fun KachatMyOffersSection(offers: List<OfferInfo>, vm: KachatLiveViewModel, onOfferAction: (KachatOfferAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        KachatLiveSectionHeader(stringResource(R.string.kn_my_offers), stringResource(R.string.kn_my_offers_detail))
        KachatGlassList {
            offers.forEachIndexed { index, o ->
                KachatOfferRow(o, vm, isBuyer = true, isOwner = false, onAction = onOfferAction)
                if (index < offers.lastIndex) KachatRowDivider(50)
            }
        }
    }
}

/** Recent activity across the registry. [vm] null: mainnet, empty (iOS 7227d69). */
@Composable
fun KachatLiveActivityPage(vm: KachatLiveViewModel?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
        KachatLiveSectionHeader(stringResource(R.string.km_recent_activity), stringResource(R.string.kn_activity_all_detail))
        if (vm == null || vm.activity.isEmpty()) {
            KachatLiveEmpty(if (vm?.loaded != false) stringResource(R.string.kn_nothing_yet) else null)
        } else {
            val shown = vm.activity.take(100)
            KachatGlassList {
                shown.forEachIndexed { index, e ->
                    KachatEventRow(e, showName = true)
                    if (index < shown.lastIndex) KachatRowDivider(56)
                }
            }
        }
    }
}

@Composable
private fun KachatEventRow(event: Event, showName: Boolean = false) {
    val colors = LocalAppColors.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            Icon(KachatLive.eventIcon(event.op), contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val title = stringResource(KachatLive.eventTitle(event.op)) + (event.name?.takeIf { showName }?.let { " $it.kachat" } ?: "")
            Text(title, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = listOfNotNull(
                KachatLive.party(event.to)?.takeIf { event.op != "offer" }?.let { "→ $it" },
                event.at?.let { KachatLive.relative(it) }
            ).joinToString(" ")
            if (line.isNotEmpty()) Text(line, color = colors.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        val price = event.price
        val years = event.years
        if (price != null) {
            Text(KaspaUnit.amount(price), color = colors.textPrimary, fontSize = 15.sp)
        } else if (years != null) {
            Text("+$years", color = colors.textSecondary, fontSize = 15.sp)
        }
    }
}

// MARK: - Offers

/** What the person wants to do with an offer. */
class KachatOfferAction(val kind: Kind, val offer: OfferInfo, val name: NameInfo? = null) {
    enum class Kind { WITHDRAW, REFUND, ACCEPT, DECLINE }
}

/**
 * One offer (iOS `KachatOfferRow`, ba07975): the name, who made it, its amount, and either how
 * long it has left ("Expires in 2d 4h") or why it is on its way back. For the owner a tap anywhere
 * on the row opens the accept flow - unless it has expired (it goes back to the buyer) or was
 * [declined] (made to an earlier owner of the name).
 */
@Composable
private fun KachatOfferRow(
    offer: OfferInfo,
    vm: KachatLiveViewModel,
    isBuyer: Boolean,
    isOwner: Boolean,
    onAction: (KachatOfferAction) -> Unit,
    name: NameInfo? = null,
    /** Made before the name changed hands: never acceptable, and on its way back to the buyer. */
    declined: Boolean = false,
) {
    val colors = LocalAppColors.current
    val virtualDaa by vm.actions.virtualDaa.collectAsState()
    val returningOffers by vm.actions.returningOffers.collectAsState()
    val withdrawingOffers by vm.actions.withdrawingOffers.collectAsState()
    val refundable = virtualDaa?.let { offer.refundable(it) } ?: false
    val returning = offer.id in returningOffers
    // The owner can take it: still inside its time (an expired one is on its way back), and the
    // name itself still active - an expired name would reach the buyer only to be reclaimed (iOS 71128c4).
    val nameActive = name?.status(vm.graceMs) == Status.ACTIVE
    val acceptable = isOwner && !refundable && !declined && nameActive
    // Declined and being pulled back by this app (the buyer's).
    val withdrawing = offer.id in withdrawingOffers
    val expiresIn = virtualDaa?.let { daa ->
        KachatNamesActions.offerTimeLeft(offer.refundAfter, daa, KachatLive.DAA_PER_SECOND)
    }?.let { left -> stringResource(R.string.kn_offer_expires_in, KachatLive.timeLeft(left, LocalContext.current)) }
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            // A tap anywhere on the row opens the accept flow for the owner; the buttons inside keep their own taps.
            .clickable(enabled = acceptable) { onAction(KachatOfferAction(KachatOfferAction.Kind.ACCEPT, offer, name)) }
            .alpha(if (refundable || declined || withdrawing) 0.6f else 1f)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.PanTool, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            offer.name?.let { Text("$it.kachat", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
            val who = if (isBuyer) stringResource(R.string.kl_your_offer)
            else KachatNamesRegistry.address(offer.buyer)?.let { KachatNamesRegistry.shortAddress(it) }
            if (who != null) Text(who, color = colors.textSecondary, fontSize = 12.sp)
            when {
                declined || withdrawing -> Text(
                    stringResource(if (isBuyer) R.string.kn_offer_declined_returning_to_you else R.string.kn_offer_declined_earlier_owner),
                    color = colors.warning, fontSize = 11.sp
                )
                refundable -> Text(
                    stringResource(
                        when {
                            !(returning || isOwner) -> R.string.kn_offer_expired_refundable
                            isBuyer -> R.string.kn_offer_expired_returning_to_you
                            else -> R.string.kn_offer_expired_returning_to_buyer
                        }
                    ),
                    color = colors.warning, fontSize = 11.sp
                )
                expiresIn != null -> Text(expiresIn, color = colors.textSecondary, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(KaspaUnit.amount(offer.amount), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        when {
            isBuyer -> Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.Pending, contentDescription = null, tint = KaspaTeal)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.kn_withdraw)) },
                        onClick = { menu = false; onAction(KachatOfferAction(KachatOfferAction.Kind.WITHDRAW, offer)) }
                    )
                    if (refundable) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.kn_refund)) },
                            onClick = { menu = false; onAction(KachatOfferAction(KachatOfferAction.Kind.REFUND, offer)) }
                        )
                    }
                }
            }
            acceptable -> {
                // the owner can decline it too (registry v3, iOS 49c0baa)
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Outlined.Pending, contentDescription = null, tint = KaspaTeal)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.decline), color = LocalAppColors.current.danger) },
                            onClick = { menu = false; onAction(KachatOfferAction(KachatOfferAction.Kind.DECLINE, offer)) }
                        )
                    }
                }
                KachatButton(stringResource(R.string.accept), prominent = true) {
                    onAction(KachatOfferAction(KachatOfferAction.Kind.ACCEPT, offer, name))
                }
            }
            refundable && !returning -> {
                Spacer(Modifier.width(8.dp))
                KachatButton(stringResource(R.string.kn_refund)) { onAction(KachatOfferAction(KachatOfferAction.Kind.REFUND, offer)) }
            }
        }
    }
}

/** The sheet for an offer action. */
@Composable
fun KachatOfferActionSheet(action: KachatOfferAction, onClose: () -> Unit) {
    val offer = action.offer
    val vm: KachatLiveViewModel = hiltViewModel()
    when (action.kind) {
        KachatOfferAction.Kind.WITHDRAW -> KachatTxSheet(
            title = stringResource(R.string.kn_withdraw_offer), confirmTitle = stringResource(R.string.kn_withdraw),
            rows = listOf(KachatTxRow(stringResource(R.string.kl_offer), KaspaUnit.amount(offer.amount))),
            operation = KachatNamesActions.Operation.Withdraw(offer), operationKey = offer.id, onClose = onClose, vm = vm,
            doneTitle = R.string.kn_ev_offer_withdrawn
        )
        KachatOfferAction.Kind.REFUND -> KachatTxSheet(
            title = stringResource(R.string.kn_refund_offer), confirmTitle = stringResource(R.string.kn_refund),
            rows = listOf(KachatTxRow(stringResource(R.string.kl_offer), KaspaUnit.amount(offer.amount))),
            operation = KachatNamesActions.Operation.Refund(offer), operationKey = offer.id, onClose = onClose, vm = vm,
            doneTitle = R.string.kn_ev_offer_refunded
        )
        KachatOfferAction.Kind.ACCEPT -> {
            val n = action.name
            if (n == null) {
                LaunchedEffect(Unit) { onClose() }
                return
            }
            KachatTxSheet(
                title = stringResource(R.string.kn_accept_offer), confirmTitle = stringResource(R.string.kn_accept_and_transfer),
                warning = stringResource(R.string.kn_accept_warning),
                rows = listOf(
                    KachatTxRow(stringResource(R.string.kl_name), n.display),
                    KachatTxRow(stringResource(R.string.kl_offer), KaspaUnit.amount(offer.amount)),
                    KachatTxRow(stringResource(R.string.kn_buyer), KachatNamesRegistry.address(offer.buyer)?.let { KachatNamesRegistry.shortAddress(it) } ?: ""),
                ),
                operation = KachatNamesActions.Operation.Accept(offer, n), operationKey = offer.id, onClose = onClose, vm = vm,
                doneTitle = R.string.kn_ev_offer_accepted
            )
        }
        KachatOfferAction.Kind.DECLINE -> KachatTxSheet(
            title = stringResource(R.string.kn_decline_offer), confirmTitle = stringResource(R.string.decline),
            footer = stringResource(R.string.kn_decline_footer),
            rows = listOf(
                KachatTxRow(stringResource(R.string.kl_offer), KaspaUnit.amount(offer.amount)),
                KachatTxRow(stringResource(R.string.kn_buyer), KachatNamesRegistry.address(offer.buyer)?.let { KachatNamesRegistry.shortAddress(it) } ?: ""),
            ),
            operation = KachatNamesActions.Operation.Decline(offer), operationKey = "decline-${offer.id}", onClose = onClose, vm = vm,
            doneTitle = R.string.kn_ev_offer_declined
        )
    }
}

// MARK: - The transaction sheet

class KachatTxRow(val title: String, val value: String)

/**
 * Every action's sheet: its inputs, what it costs (built against live UTXOs, nothing sent), one
 * Confirm - an extra warning for the destructive ones - then the device lock, then the
 * transaction. Shows the txid when it is sent (iOS `KachatTxSheet`).
 */
@Composable
fun KachatTxSheet(
    title: String,
    confirmTitle: String,
    operation: KachatNamesActions.Operation?,
    operationKey: String,
    onClose: () -> Unit,
    warning: String? = null,
    footer: String? = null,
    rows: List<KachatTxRow> = emptyList(),
    /** The headline of the finished-transaction half sheet (iOS `doneTitle`, 0870fcc). */
    @StringRes doneTitle: Int = R.string.kn_done_tx_sent,
    onDone: (String) -> Unit = {},
    vm: KachatLiveViewModel = hiltViewModel(),
    inputs: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val view = LocalView.current
    var plan by remember { mutableStateOf<Plan?>(null) }
    var planError by remember { mutableStateOf<String?>(null) }
    var building by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var confirmWarning by remember { mutableStateOf(false) }
    var txId by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<KachatTxDone?>(null) }
    var sendError by remember { mutableStateOf<String?>(null) }
    val currentOperation by rememberUpdatedState(operation)
    val chattingBalance by vm.wallet.balance.collectAsState()
    val chattingBalanceKnown by vm.wallet.balanceKnown.collectAsState()
    // bumped to build the plan again (the price moved at send time, iOS 4f5d95e `rebuild()`)
    var rebuilds by remember { mutableIntStateOf(0) }

    LaunchedEffect(operationKey, rebuilds) {
        plan = null
        planError = null
        val op = currentOperation
        if (op == null) { building = false; return@LaunchedEffect }
        building = true
        delay(300)
        try {
            plan = vm.actions.plan(op)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            planError = context.kachatErrorText(e)
        }
        building = false
    }

    fun send() {
        val op = currentOperation ?: return
        sending = true
        sendError = null
        vm.launch {
            try {
                // never pays more than the price shown (iOS 4f5d95e, c8f1086)
                val id = vm.actions.perform(op, maxPrice = plan?.priceFee)
                txId = id
                view.successHaptic()
                onDone(id)
                done = KachatTxDone(id, doneTitle)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendError = context.kachatErrorText(e)
                // the price moved: show the new plan so the person can confirm it
                if (e is KachatNamesActions.ActionError.PriceChanged) rebuilds++
            }
            sending = false
        }
    }

    fun authorize() = context.kachatAuthorize { send() }

    KachatLiveForm(title = title, onClose = onClose, finished = txId != null) {
        inputs()
        FormSection(footer = {
            val pe = planError
            if (pe != null) FormFooter(pe, colors.danger) else if (footer != null) FormFooter(footer)
        }) {
            // (title, value, bold); the value null is the spinner while the plan is built
            val lines = rows.map { Triple(it.title, it.value as String?, false) }.toMutableList()
            val p = plan
            if (p != null) {
                if (p.priceFee > 0) lines += Triple(stringResource(R.string.kn_price_to_miners), KaspaUnit.amount(p.priceFee), false)
                lines += Triple(stringResource(R.string.kl_network_fee), KaspaUnit.amount(p.networkFee), false)
                // Names always spend from, and pay back to, the chatting address: show its real
                // balance and what it will be once this is sent (iOS 8ecc38c).
                vm.myKey?.let { me ->
                    val change = KachatLive.balanceChange(p, me)
                    if (chattingBalanceKnown) {
                        lines += Triple(stringResource(R.string.kn_chatting_balance), KaspaUnit.amount(chattingBalance), false)
                        lines += Triple(stringResource(R.string.kn_balance_after), KaspaUnit.amount(maxOf(0L, chattingBalance + change)), true)
                    } else {
                        lines += Triple(stringResource(R.string.kn_balance_change), KaspaUnit.signed(change), true)
                    }
                }
            } else if (building) {
                lines += Triple(stringResource(R.string.kl_network_fee), null, false)
            }
            lines.forEachIndexed { index, (title, value, bold) ->
                if (value != null) LabeledRow(title, value, bold) else LoadingRow(title)
                if (index < lines.lastIndex) SettingsDivider()
            }
        }
        if (warning != null) {
            FormSection {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = colors.danger, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(warning, color = colors.danger)
                }
            }
        }
        FormSection(footer = { sendError?.let { FormFooter(it, colors.danger) } }) {
            val sent = txId
            if (sent != null) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = colors.success, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.sent), color = colors.success)
                    }
                    SelectionContainer {
                        Text(sent, color = colors.textSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                    Text(stringResource(R.string.kn_sent_note), color = colors.textSecondary, fontSize = 12.sp)
                }
            } else {
                FormButtonRow(confirmTitle, enabled = plan != null && !sending, busy = sending, destructive = warning != null) {
                    if (warning != null) confirmWarning = true else authorize()
                }
            }
        }
    }

    // Closing the finished-transaction sheet closes the action too (iOS onDismiss: dismiss()).
    done?.let { KachatTxDoneSheet(it, onDismiss = { done = null; onClose() }, vm = vm) }

    if (confirmWarning && warning != null) {
        IosAlertDialog(
            onDismissRequest = { confirmWarning = false },
            title = { Text(title) },
            text = { Text(warning) },
            confirmButton = {
                TextButton(onClick = { confirmWarning = false; authorize() }) {
                    Text(confirmTitle, color = colors.danger, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmWarning = false }) { Text(stringResource(R.string.cancel), color = KaspaTeal, fontWeight = FontWeight.SemiBold) }
            }
        )
    }
}

// MARK: - Claim

/**
 * Claim a free name, in a sheet (iOS `KachatClaimSheet`): 1 or 2 years; price x years to miners,
 * the bond and the registry deposit (both back on release), the commit (back at registration),
 * network fees, total and what the chatting address has. Claim starts the registration (commit,
 * then the driver registers), and the sheet then turns into that registration's progress half
 * sheet (iOS 61fb0fc) - from the marketplace and from Your Domains alike. Swiping it away leaves
 * the claim running: the .kachat screen's claims button lists it (iOS b219bb0).
 *
 * Opens at full height (iOS's large detent) and can be pulled to half (medium); the progress opens
 * at half.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KachatClaimSheet(target: KachatClaimTarget, onClose: () -> Unit, onStarted: () -> Unit = {}, vm: KachatLiveViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val manifest by vm.service.manifest.collectAsState()
    val pending by vm.actions.pending.collectAsState()
    var years by remember { mutableLongStateOf(1L) }
    var quote by remember { mutableStateOf<KachatNamesActions.Quote?>(null) }
    var quoteError by remember { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(false) }
    var startError by remember { mutableStateOf<String?>(null) }
    // Once the registration started, this sheet shows its progress (iOS `progressId`).
    var progressId by remember { mutableStateOf<String?>(null) }
    val maxYears = manifest?.params?.maxYears ?: 2L
    val registration = progressId?.let { id -> pending.firstOrNull { it.id == id } }
    val close by rememberUpdatedState(onClose)
    val sheetState = rememberModalBottomSheetState()

    fun dismiss() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { close() }
    }

    // Opens at the large detent, as iOS's claim sheet does: the sheet first heads for its half
    // stop, so it carries on to the top.
    LaunchedEffect(sheetState) {
        val first = snapshotFlow { sheetState.targetValue }.first { it != SheetValue.Hidden }
        if (first == SheetValue.PartiallyExpanded && progressId == null) sheetState.expand()
    }

    // Closes once the registration is dismissed (Done) or its commit was cancelled.
    val over = progressId != null && registration?.isOpen != true
    LaunchedEffect(over) {
        if (over) {
            try {
                sheetState.hide()
            } finally {
                close()
            }
        }
    }

    LaunchedEffect(years) {
        quote = null
        quoteError = null
        try {
            quote = vm.actions.quote(target.name, years, target.gap)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            quoteError = context.kachatErrorText(e)
        }
    }

    fun start() {
        // the price shown is the most the registration will ever pay (iOS 4f5d95e)
        val q = quote
        if (q == null || q.years != years) return
        starting = true
        startError = null
        vm.launch {
            try {
                vm.actions.startRegistration(target.name, years, maxPrice = q.price)
                view.successHaptic()
                onStarted()
                progressId = vm.actions.pending.value.lastOrNull { it.name == target.name && it.isOpen }?.id
                if (progressId == null) dismiss() else scope.launch { sheetState.partialExpand() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                startError = context.kachatErrorText(e)
            }
            starting = false
        }
    }

    com.kachat.app.ui.theme.IosSheetColors(grouped = true) {
        val colors = LocalAppColors.current
        ModalBottomSheet(
            shape = com.kachat.app.ui.theme.IosSheetShape,
            tonalElevation = com.kachat.app.ui.theme.IosSheetTonalElevation,
            windowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars,
            // A swipe, a tap outside or Back closes it - the form, or the progress (the claim keeps
            // running; iOS b219bb0).
            onDismissRequest = { close() },
            sheetState = sheetState,
            containerColor = colors.background,
            dragHandle = { KachatSheetGrabber() },
        ) {
            // The sheet runs down behind the navigation bar, as iOS's does behind the home indicator;
            // its content stays above it.
            Column(Modifier.navigationBarsPadding()) {
                val shown = registration
                if (progressId != null && shown != null) {
                    KachatRegistrationProgressContent(shown, vm)
                } else {
                    Column(Modifier.fillMaxSize()) {
                        // The inline navigation bar: Cancel (left) and "Claim Name".
                        Box(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp)) {
                            TextButton(onClick = { dismiss() }, modifier = Modifier.align(Alignment.CenterStart)) {
                                Text(stringResource(R.string.cancel), color = KaspaTeal, fontSize = 17.sp)
                            }
                            Text(
                                stringResource(R.string.kn_claim_name_title),
                                color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 40.dp)
                        ) {
                            FormSection {
                                LabeledRow(stringResource(R.string.kl_name), "${target.name}.kachat", bold = false)
                                SettingsDivider()
                                val count = maxOf(1, maxYears.toInt())
                                KachatSegmented((1..count).map { yearsText(it, manifest?.params) }, (years - 1).toInt()) { years = (it + 1).toLong() }
                            }

                            FormSection(
                                header = stringResource(R.string.kn_cost),
                                footer = {
                                    val q = quote
                                    if (q != null && !q.affordable) FormFooter(KaspaUnit.label(stringResource(R.string.kn_not_enough)), colors.danger)
                                    else FormFooter(stringResource(R.string.kn_claim_footer))
                                }
                            ) {
                                val q = quote
                                val qe = quoteError
                                when {
                                    q != null -> {
                                        // the first period at the registration price, any further one at the renewal price (iOS c8f1086)
                                        LabeledRow(stringResource(R.string.kn_price_to_miners), KaspaUnit.amount(q.price)); SettingsDivider()
                                        LabeledRow(stringResource(R.string.kn_bond_returned), KaspaUnit.amount(q.bond)); SettingsDivider()
                                        LabeledRow(stringResource(R.string.kn_deposit_returned), KaspaUnit.amount(q.gapDeposit)); SettingsDivider()
                                        LabeledRow(stringResource(R.string.kn_commit_returned), KaspaUnit.amount(q.commit)); SettingsDivider()
                                        LabeledRow(stringResource(R.string.kn_network_fees), KaspaUnit.amount(q.networkFee)); SettingsDivider()
                                        LabeledRow(stringResource(R.string.kl_total), KaspaUnit.amount(q.total), bold = true); SettingsDivider()
                                        LabeledRow(stringResource(R.string.kn_available), KaspaUnit.amount(q.spendable))
                                    }
                                    qe != null -> Text(qe, color = colors.danger, modifier = Modifier.padding(16.dp))
                                    else -> LoadingRow(stringResource(R.string.kl_total))
                                }
                            }

                            FormSection(header = stringResource(R.string.kn_how_claiming_works)) {
                                val params = manifest?.params
                                val step3 = if (KachatLive.yearlyPeriods(params)) {
                                    stringResource(R.string.kn_claim_step3_cap)
                                } else {
                                    stringResource(R.string.kn_claim_step3_time, yearsText(maxYears.toInt(), params), KachatLive.duration(params?.renewWindowMs ?: 0L, context))
                                }
                                listOf(stringResource(R.string.kn_claim_step1), stringResource(R.string.kn_claim_step2), step3).forEachIndexed { index, text ->
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
                                        Box(Modifier.size(22.dp).clip(CircleShape).background(KaspaTeal.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                                            Text("${index + 1}", color = KaspaTeal, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Text(text, color = colors.textPrimary, fontSize = 15.sp)
                                    }
                                    if (index < 2) SettingsDivider(50.dp)
                                }
                            }

                            FormSection(footer = { startError?.let { FormFooter(it, colors.danger) } }) {
                                FormButtonRow(
                                    stringResource(R.string.kn_claim_name_button, target.name),
                                    enabled = quote?.affordable == true && !starting,
                                    busy = starting
                                ) {
                                    context.kachatAuthorize { start() }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Name detail

private enum class KachatDetailSheet { BUY, OFFER, EXTEND, RENEW, LIST, DELIST, TRANSFER, RELEASE }

/**
 * The Manage Name sheet's actions for an owner (iOS f61b978 `manageItems`), in order. A lapsed
 * name never gets here: it shows as free to claim, and its old owner claims it like anyone else
 * (iOS eea52b2 dropped Reclaim to Own).
 */
object KachatManageMenu {
    enum class Action { EXTEND, RENEW, CHANGE_PRICE, DELIST, LIST, TRANSFER, PRIMARY, RELEASE }

    fun actions(info: NameInfo, status: Status, params: Params?, mine: Boolean): List<Action> {
        return buildList {
            if (params != null) {
                if (info.extendableYears(params) > 0) add(Action.EXTEND)
                if (info.renewOpen(params)) add(Action.RENEW)
            }
            if (info.isListed) {
                add(Action.CHANGE_PRICE)
                add(Action.DELIST)
            } else {
                add(Action.LIST)
            }
            add(Action.TRANSFER)
            // The primary name is the chatting address's identity; a name on a spending address can't be it.
            if (mine) add(Action.PRIMARY)
            add(Action.RELEASE)
        }
    }
}

/**
 * A registered name, live: who owns it, its status and expiry, its price, and what the person can
 * do with it - buy, offer or message the owner; or, for their own names, extend or renew (registry
 * v2, iOS bd2c54a), list, transfer, release and make it their primary name. Offers and history
 * below (iOS `KachatLiveNameDetail`).
 *
 * It knows which of your addresses holds the name ([KachatNamesActions.ownAddress], iOS 881ada6):
 * the chatting address gets every owner action, Set as Primary included; a spending address gets
 * list / delist / transfer / release / accept, signed and paid for by that address's key; a
 * KasSigner address is read-only (the Owner card says which one holds it). Your own names are
 * never offered Buy / Make an Offer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KachatLiveNameDetailScreen(
    initial: NameInfo,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    vm: KachatLiveViewModel = hiltViewModel(),
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val view = LocalView.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val revision by vm.registry.revision.collectAsState()
    val source by vm.registry.source.collectAsState()
    var info by remember(initial.name) { mutableStateOf(initial) }
    var ownerCopied by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<KachatDetailSheet?>(null) }
    var offerAction by remember { mutableStateOf<KachatOfferAction?>(null) }
    var ownerLabel by remember { mutableStateOf<String?>(null) }
    var offers by remember { mutableStateOf<List<OfferInfo>>(emptyList()) }
    var history by remember { mutableStateOf<List<Event>>(emptyList()) }
    var gone by remember { mutableStateOf(false) }
    // The free gap the name sits in once it's gone (released or reclaimed), or the one claiming a
    // lapsed name reopens: Claim uses it (iOS f420343, eea52b2).
    var freeGap by remember { mutableStateOf<GapInfo?>(null) }
    var confirmPrimary by remember { mutableStateOf(false) }
    var claimTarget by remember { mutableStateOf<KachatClaimTarget?>(null) }
    // The Manage Name half sheet (iOS f61b978): what it picks opens once it has gone down.
    var showManage by remember { mutableStateOf(false) }
    val manifest by vm.service.manifest.collectAsState()
    // Which of this wallet's addresses holds the name (chatting, a spending address, a KasSigner
    // address), or null for someone else's. Resolved on load: it derives addresses (iOS 881ada6).
    var heldBy by remember(initial.name) { mutableStateOf<KachatNamesActions.OwnAddress?>(null) }

    // Held by the chatting address: the identity, so "Set as Primary" applies.
    val mine = heldBy == KachatNamesActions.OwnAddress.Chatting || (heldBy == null && vm.isMine(info.owner))
    // Held by an address this app can sign for: every owner action is available.
    val canActAsOwner = when (heldBy) {
        KachatNamesActions.OwnAddress.Chatting, is KachatNamesActions.OwnAddress.Spending -> true
        else -> mine
    }
    // Held by any of this wallet's addresses - never offered Buy / Make an Offer.
    val ownedByWallet = heldBy != null || mine
    val status = info.status(vm.graceMs)
    // Listed and still active: the only state in which the asking price means anything (iOS ba1a734).
    val forSale = info.isListed && status == Status.ACTIVE
    // Free to claim: released or reclaimed, or expired past grace (claiming frees it first; iOS eea52b2).
    val isFree = gone || status == Status.LAPSED
    val ownerAddress = KachatNamesRegistry.address(info.owner)

    LaunchedEffect(revision, initial.name) {
        try {
            when (val l = vm.registry.lookup(info.name)) {
                is Lookup.Registered -> {
                    info = l.info
                    gone = false
                    // lapsed: free to claim, in the gap claiming it reopens (iOS eea52b2)
                    freeGap = if (l.info.status(vm.graceMs) == Status.LAPSED) {
                        try {
                            vm.registry.claimGap(l.info)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        null
                    }
                }
                is Lookup.Free -> { gone = true; freeGap = l.gap }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        val owner = KachatNamesRegistry.address(info.owner)
        val held = runCatching { vm.actions.ownAddress(info.owner) }.getOrNull()
        heldBy = held
        if (held == null && !vm.isMine(info.owner) && owner != null) {
            runCatching { vm.registry.identity(owner) }.getOrNull()?.let { ownerLabel = it.label }
        }
        offers = runCatching { vm.registry.offers(info.name) }.getOrNull() ?: emptyList()
        history = runCatching { vm.registry.history(info.name) }.getOrNull() ?: emptyList()
        if (offers.isNotEmpty()) {
            vm.actions.refreshVirtualDaa()
            // Expired offers don't stay on your name: the owner's app (and the buyer's) send them
            // back (iOS ba07975). Owner here = an address this app signs for, as canActAsOwner.
            val ownerHere = held == KachatNamesActions.OwnAddress.Chatting || held is KachatNamesActions.OwnAddress.Spending ||
                (held == null && vm.isMine(info.owner))
            if (ownerHere) {
                vm.actions.returnExpiredOffers(offers)
            } else {
                vm.actions.returnExpiredOffers(offers.filter { vm.isMine(it.buyer) })
            }
            // Your offers made to an earlier owner of this name: pulled back.
            vm.actions.withdrawDeclinedOffers(offers)
        }
    }

    // The claim sheet is a real sheet over the detail (iOS .sheet); the others are full-screen
    // swaps, Cancel top left.
    claimTarget?.let { target ->
        KachatClaimSheet(target, onClose = { claimTarget = null }, vm = vm)
    }
    offerAction?.let { action ->
        KachatOfferActionSheet(action, onClose = { offerAction = null })
        return
    }
    sheet?.let { s ->
        val close = { sheet = null }
        when (s) {
            KachatDetailSheet.BUY -> KachatLiveBuySheet(info, close)
            KachatDetailSheet.OFFER -> KachatLiveOfferSheet(info, close)
            KachatDetailSheet.EXTEND -> KachatExtendSheet(info, close)
            KachatDetailSheet.RENEW -> KachatRenewSheet(info, close)
            KachatDetailSheet.LIST -> KachatListSheet(info, close)
            KachatDetailSheet.DELIST -> KachatTxSheet(
                title = stringResource(R.string.kn_delist), confirmTitle = stringResource(R.string.kn_delist),
                rows = listOf(KachatTxRow(stringResource(R.string.kl_name), info.display), KachatTxRow(stringResource(R.string.kl_listed_at), KaspaUnit.amount(info.price))),
                operation = KachatNamesActions.Operation.List(info, 0),
                operationKey = "delist-${info.outpoint.index}-${KachatNames.hex(info.outpoint.txid)}",
                onClose = close, vm = vm, doneTitle = R.string.kn_ev_delisted
            )
            KachatDetailSheet.TRANSFER -> KachatTransferSheet(info, close)
            KachatDetailSheet.RELEASE -> KachatTxSheet(
                title = stringResource(R.string.kn_release_name), confirmTitle = stringResource(R.string.kn_release),
                warning = stringResource(R.string.kn_release_warning),
                rows = listOf(KachatTxRow(stringResource(R.string.kl_name), info.display)),
                operation = KachatNamesActions.Operation.Release(info),
                operationKey = "release-${KachatNames.hex(info.outpoint.txid)}",
                onClose = close, vm = vm, doneTitle = R.string.kn_done_released
            )
        }
        return
    }

    // Set as Primary: the profile save review (iOS 7e238e5) - your current profile with this name
    // as the primary one, since setting it rewrites the whole record.
    if (confirmPrimary) {
        KachatProfileSaveSheet(
            title = stringResource(R.string.set_as_primary), confirmTitle = stringResource(R.string.set_as_primary),
            doneTitle = R.string.kn_done_primary_set,
            makeProfile = {
                var profile = Profile()
                val address = vm.actions.myAddress
                if (address != null) {
                    profile = vm.registry.ownProfile(address)?.profile
                        ?: runCatching { vm.registry.identity(address).profile }.getOrNull() ?: profile
                }
                profile.copy(primaryName = info.name)
            },
            onClose = { confirmPrimary = false }, vm = vm
        )
        return
    }

    BackHandler(onBack = onBack)
    val pullState = rememberPullToRefreshState()
    LaunchedEffect(pullState.isRefreshing) {
        if (pullState.isRefreshing) {
            vm.registry.refresh()
            pullState.endRefresh()
        }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(info.display, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBackIos, "Back", tint = KaspaTeal) }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background)
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).nestedScroll(pullState.nestedScrollConnection)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp).padding(bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // The name card
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).kachatGlass(colors, 18).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(18.dp)).background(KaspaTeal), contentAlignment = Alignment.Center) {
                        Text(info.display, color = Color.Black, fontWeight = FontWeight.Black, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    if (isFree) {
                        // Released, reclaimed or lapsed: the old record (its expiry, period,
                        // listing) is history (iOS eea52b2).
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.kn_free_to_claim), color = colors.textSecondary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            KachatAvailablePill()
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                // A listing only stands while the name is active: an expired or lapsed
                                // name's old asking price is never shown (iOS ba1a734).
                                Text(stringResource(if (forSale) R.string.kl_price else R.string.kn_not_for_sale), color = colors.textSecondary, fontSize = 12.sp)
                                if (forSale) Text(KaspaUnit.amount(info.price), color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                KachatStatusPill(status)
                                Text(stringResource(R.string.kn_expires_on, KachatLive.date(info.expiresAt)), color = colors.textSecondary, fontSize = 12.sp)
                            }
                        }
                        info.periodStart?.let { start ->
                            // registry v2: the paid period, from its start to the expiry (at most 2 years)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.CalendarMonth, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.kn_paid_from_to, KachatLive.day(start), KachatLive.day(info.expiresAt)),
                                    color = colors.textSecondary, fontSize = 12.sp
                                )
                            }
                        }
                        when {
                            status == Status.GRACE && ownedByWallet -> Text(stringResource(R.string.kn_detail_grace_mine), color = colors.warning, fontSize = 13.sp)
                            status == Status.GRACE -> Text(stringResource(R.string.kn_detail_grace), color = colors.warning, fontSize = 13.sp)
                        }
                    }
                }

                if (isFree) {
                    val gap = freeGap
                    if (gap != null && KachatLive.isEnabled) {
                        KachatButton(stringResource(R.string.km_claim), Modifier.fillMaxWidth().padding(horizontal = 16.dp), KachatSymbols.AtBadgePlus, prominent = true, large = true) {
                            claimTarget = KachatClaimTarget(info.name, gap)
                        }
                    }
                    Text(
                        stringResource(if (gone) R.string.kn_name_gone else R.string.kn_name_expired_free),
                        color = colors.textSecondary, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 20.dp)
                    )
                } else {
                    // Actions
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val big = Modifier.fillMaxWidth()
                        if (canActAsOwner) {
                            // Expired (in grace) and renewable: the one thing that matters now
                            // stays on the page instead of inside the menu (iOS f61b978). (A lapsed
                            // name shows as free to claim, iOS eea52b2.)
                            val p = manifest?.params
                            if (status != Status.ACTIVE && p != null && info.renewOpen(p)) {
                                KachatButton(stringResource(R.string.kn_renew), big, Icons.Default.Refresh, prominent = true, large = true) {
                                    sheet = KachatDetailSheet.RENEW
                                }
                            }
                            // Every owner action lives in one half sheet of tiles.
                            KachatButton(stringResource(R.string.kn_manage_name), big, Icons.Default.Tune, prominent = status == Status.ACTIVE, large = true) {
                                showManage = true
                            }
                        } else if (heldBy is KachatNamesActions.OwnAddress.KasSigner) {
                            // Read-only: the app shows that a KasSigner address holds the name (the
                            // Owner card says which); acting on it is the device's job (iOS 881ada6).
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (info.isListed && status == Status.ACTIVE) {
                                    KachatButton(stringResource(R.string.kl_buy_now), Modifier.weight(1f), Icons.Outlined.ShoppingCart, prominent = true, large = true) { sheet = KachatDetailSheet.BUY }
                                }
                                // an expired name is free to claim soon: no offers on it (iOS 71128c4, eea52b2)
                                if (status == Status.ACTIVE) {
                                    KachatButton(stringResource(R.string.kl_make_offer), Modifier.weight(1f), Icons.Outlined.PanTool, large = true) { sheet = KachatDetailSheet.OFFER }
                                }
                            }
                        }
                    }

                    // Owner
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        KachatLiveSectionHeader(stringResource(R.string.kn_owner), null)
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp).kachatGlass(colors).padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.AccountCircle, contentDescription = null, tint = KaspaTeal.copy(alpha = 0.6f), modifier = Modifier.size(34.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                val held = heldBy
                                when {
                                    mine -> Text(stringResource(R.string.kn_you), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                    held is KachatNamesActions.OwnAddress.Spending -> Text(
                                        stringResource(R.string.kn_your_spending_address, held.index),
                                        color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp
                                    )
                                    held is KachatNamesActions.OwnAddress.KasSigner -> Text(
                                        stringResource(R.string.kn_your_kassigner_address, held.account, held.index),
                                        color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp
                                    )
                                    else -> ownerLabel?.let { Text("$it.kachat", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
                                }
                                ownerAddress?.let { address ->
                                    // The whole address doesn't fit on two lines: show its network
                                    // prefix and both ends on one line, and copy the full address
                                    // on tap, a checkmark for 1.5 s (iOS 71448d8).
                                    val copyHint = stringResource(R.string.kn_copies_the_address)
                                    Row(
                                        Modifier
                                            .clickable {
                                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(address))
                                                view.successHaptic()
                                                ownerCopied = true
                                            }
                                            .semantics(mergeDescendants = true) { contentDescription = "$address. $copyHint" },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            KachatNamesRegistry.compactAddress(address),
                                            color = colors.textSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                            maxLines = 1, softWrap = false
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Icon(
                                            if (ownerCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                                            contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(12.dp)
                                        )
                                    }
                                    LaunchedEffect(ownerCopied) {
                                        if (!ownerCopied) return@LaunchedEffect
                                        delay(1_500)
                                        ownerCopied = false
                                    }
                                }
                            }
                            if (!ownedByWallet && ownerAddress != null) {
                                Spacer(Modifier.width(8.dp))
                                KachatButton(stringResource(R.string.kl_message), icon = Icons.Outlined.Forum) {
                                    vm.message(ownerAddress, onOpenChat)
                                }
                            }
                        }
                    }

                    // Offers
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        KachatLiveSectionHeader(stringResource(R.string.kl_offers), if (canActAsOwner) stringResource(R.string.kn_offers_tap_to_accept) else null)
                        if (offers.isEmpty()) {
                            Box(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp).kachatGlass(colors).padding(vertical = 14.dp),
                                contentAlignment = Alignment.Center
                            ) { Text(stringResource(R.string.kn_no_open_offers), color = colors.textSecondary, fontSize = 15.sp) }
                        } else {
                            KachatGlassList {
                                offers.forEachIndexed { index, o ->
                                    KachatOfferRow(
                                        o, vm, isBuyer = vm.isMine(o.buyer), isOwner = canActAsOwner && source?.isIndexer == true,
                                        onAction = { offerAction = it }, name = info,
                                        // made to an earlier owner (registry v3: the offer's seller, iOS 49c0baa)
                                        declined = o.isDeclined(info.owner)
                                    )
                                    if (index < offers.lastIndex) KachatRowDivider(50)
                                }
                            }
                        }
                        if (source == KachatNamesRegistry.Source.Chain) {
                            Text(stringResource(R.string.kn_offers_need_indexer), color = colors.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
                        }
                    }
                }

                // History
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    KachatLiveSectionHeader(stringResource(R.string.kl_history), null)
                    if (history.isEmpty()) {
                        Box(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp).kachatGlass(colors).padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center
                        ) { Text(stringResource(R.string.kn_no_history), color = colors.textSecondary, fontSize = 15.sp) }
                    } else {
                        val shown = history.take(50)
                        KachatGlassList {
                            shown.forEachIndexed { index, e ->
                                KachatEventRow(e)
                                if (index < shown.lastIndex) KachatRowDivider(56)
                            }
                        }
                    }
                }
            }
            PullToRefreshContainer(state = pullState, modifier = Modifier.align(Alignment.TopCenter))
        }
    }

    if (showManage) {
        KachatManageNameSheet(
            info, status, manifest?.params, mine,
            onDismiss = { showManage = false },
            onOpen = { sheet = it },
            onPrimary = { confirmPrimary = true }
        )
    }
}

/** One tile of the Manage Name sheet (iOS f61b978 `ManageItem`). */
private class KachatManageItem(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val tint: Color? = null,
    val enabled: Boolean = true,
    val run: () -> Unit,
)

/**
 * The Manage Name half sheet (iOS f61b978 `manageSheet`): the name, "Renewal opens on <date>"
 * while the renewal window hasn't opened, and the owner's actions as square tiles
 * ([ActionSheetTiles]). Registry v2 periods: "Extend" while the paid period holds less than 2
 * years ("Extend to 2 years" when that fills it), "Renew" once the renewal window is open (10 days
 * before the expiry, and on through grace and lapse). Set as Primary only when the chatting
 * address holds the name ([mine], iOS 881ada6). The picked action runs once the sheet has gone
 * down: iOS can't present two sheets at once, and here the action's own sheet replaces the page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KachatManageNameSheet(
    info: NameInfo,
    status: Status,
    params: Params?,
    mine: Boolean,
    onDismiss: () -> Unit,
    onOpen: (KachatDetailSheet) -> Unit,
    onPrimary: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    /** Closes the sheet and runs [then] once it has gone. */
    fun close(then: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            then()
        }
    }
    val open: (KachatDetailSheet) -> () -> Unit = { s -> { close { onOpen(s) } } }
    val active = status == Status.ACTIVE
    val danger = LocalAppColors.current.danger
    val items = KachatManageMenu.actions(info, status, params, mine).map { action ->
        when (action) {
            KachatManageMenu.Action.EXTEND -> {
                val p = params!!
                val extendable = info.extendableYears(p)
                val yearly = KachatLive.yearlyPeriods(p)
                val title = when {
                    !KachatLive.fillsPeriod(info, extendable, p) -> stringResource(R.string.kn_extend)
                    yearly -> stringResource(R.string.kn_extend_to_years, p.maxYears.toInt())
                    else -> stringResource(R.string.kn_extend_to_period, yearsText(p.maxYears.toInt(), p))
                }
                val subtitle = if (yearly) stringResource(R.string.kn_manage_extend_hint)
                else stringResource(R.string.kn_manage_extend_hint_time, yearsText(p.maxYears.toInt(), p))
                KachatManageItem(title, subtitle, KachatSymbols.CalendarBadgePlus, run = open(KachatDetailSheet.EXTEND))
            }
            KachatManageMenu.Action.RENEW ->
                KachatManageItem(stringResource(R.string.kn_renew), stringResource(R.string.kn_manage_renew_hint), Icons.Default.Refresh, run = open(KachatDetailSheet.RENEW))
            KachatManageMenu.Action.CHANGE_PRICE ->
                KachatManageItem(stringResource(R.string.kn_change_price), stringResource(R.string.kn_manage_change_price_hint), Icons.Outlined.Sell, enabled = active, run = open(KachatDetailSheet.LIST))
            KachatManageMenu.Action.DELIST ->
                KachatManageItem(stringResource(R.string.kn_delist), stringResource(R.string.kn_manage_delist_hint), KachatSymbols.TagSlash, run = open(KachatDetailSheet.DELIST))
            KachatManageMenu.Action.LIST ->
                KachatManageItem(stringResource(R.string.kn_list_for_sale), stringResource(R.string.kn_manage_list_hint), Icons.Outlined.Sell, enabled = active, run = open(KachatDetailSheet.LIST))
            KachatManageMenu.Action.TRANSFER ->
                KachatManageItem(stringResource(R.string.portfolio_type_transfer), stringResource(R.string.kn_manage_transfer_hint), Icons.Default.SwapHoriz, run = open(KachatDetailSheet.TRANSFER))
            KachatManageMenu.Action.PRIMARY ->
                KachatManageItem(stringResource(R.string.set_as_primary), stringResource(R.string.kn_manage_primary_hint), KachatSymbols.PersonCircleBadgeCheckmark, enabled = active) { close(onPrimary) }
            KachatManageMenu.Action.RELEASE ->
                KachatManageItem(stringResource(R.string.kn_release_name), stringResource(R.string.kn_manage_release_hint), Icons.Outlined.Delete, tint = danger, run = open(KachatDetailSheet.RELEASE))
        }
    }
    // When the renewal window opens, while it hasn't yet - under the sheet's title.
    val note = params?.takeIf { !info.renewOpen(it) }?.let { stringResource(R.string.kn_renewal_opens_on, KachatLive.date(info.renewOpens(it))) }
    TileActionSheet(
        title = info.display,
        subtitle = note,
        onDismiss = onDismiss,
        sheetState = sheetState,
        height = ActionSheetTileMetrics.sheetHeight(tiles = items.size, header = if (note == null) 70.dp else 90.dp),
    ) {
            items.forEach { item ->
                ActionSheetRow(item.icon, item.title, item.subtitle, tint = item.tint ?: KaspaTeal, enabled = item.enabled, onClick = item.run)
            }
    }
}

// MARK: - Sheets with inputs

@Composable
fun KachatLiveBuySheet(info: NameInfo, onClose: () -> Unit, vm: KachatLiveViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val manifest by vm.service.manifest.collectAsState()
    // 30 days on mainnet's yearly clock, the renewal window on testnet's 10-minute one (iOS 24d673a)
    val soonMs = manifest?.params?.expiresSoonMs ?: (30L * 86_400_000L)
    val soon = info.expiresAt - soonMs < KachatNames.nowMs()
    KachatTxSheet(
        title = stringResource(R.string.kl_buy_name), confirmTitle = stringResource(R.string.kl_confirm_purchase),
        doneTitle = R.string.kn_done_bought,
        footer = if (soon) stringResource(R.string.kn_buy_soon_left, KachatLive.duration(soonMs, context)) else stringResource(R.string.kn_buy_footer),
        rows = listOf(
            KachatTxRow(stringResource(R.string.kl_name), info.display),
            KachatTxRow(stringResource(R.string.kn_price_to_seller), KaspaUnit.amount(info.price)),
            KachatTxRow(stringResource(R.string.kn_expires), KachatLive.day(info.expiresAt)),
        ),
        operation = KachatNamesActions.Operation.Buy(info), operationKey = "buy-${KachatNames.hex(info.outpoint.txid)}",
        onClose = onClose, vm = vm
    )
}

/**
 * An offer on [info], the name as registered: it is made to its current owner, the only one who
 * can accept or decline it, and runs for at most 7 days (registry v3, iOS 49c0baa).
 */
@Composable
fun KachatLiveOfferSheet(info: NameInfo, onClose: () -> Unit, vm: KachatLiveViewModel = hiltViewModel()) {
    var amountText by remember { mutableStateOf("") }
    var days by remember { mutableIntStateOf(3) }
    var virtualDaa by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { virtualDaa = runCatching { vm.service.currentVirtualDaaScore() }.getOrNull() }

    val amount = KaspaUnit.sompiFromUserText(amountText)?.takeIf { it > 0 }
    val refundAfter = virtualDaa?.let { it + days.toLong() * 86_400L * KachatLive.DAA_PER_SECOND }
    val operation = if (amount != null && refundAfter != null) KachatNamesActions.Operation.Offer(info, amount, refundAfter) else null
    val belowListing = info.isListed && amount != null && info.price < amount

    val rows = buildList {
        add(KachatTxRow(stringResource(R.string.kl_name), "${info.name}.kachat"))
        if (info.isListed) add(KachatTxRow(stringResource(R.string.kl_listed_at), KaspaUnit.amount(info.price)))
        add(KachatTxRow(stringResource(R.string.kn_expires), KachatLive.day(info.expiresAt)))
        if (amount != null) add(KachatTxRow(stringResource(R.string.kl_offer), KaspaUnit.amount(amount)))
    }
    // up to 7 days, the app's cap (KachatNamesActions.MAX_OFFER_DAYS)
    val choices = listOf(1 to R.string.kl_1d, 3 to R.string.kl_3d, 7 to R.string.kl_7d)
    KachatTxSheet(
        title = stringResource(R.string.kl_make_offer), confirmTitle = stringResource(R.string.kl_send_offer),
        doneTitle = R.string.kn_done_offer_sent,
        footer = if (belowListing) stringResource(R.string.kn_offer_below_listing_buy) else null,
        rows = rows,
        operation = operation, operationKey = "${amount ?: 0}-$days-${virtualDaa ?: 0}",
        onClose = onClose, vm = vm
    ) {
        FormSection(header = stringResource(R.string.kl_your_offer), footer = { FormFooter(KaspaUnit.label(stringResource(R.string.kn_offer_locked_decline))) }) {
            AmountField(amountText) { amountText = it }
        }
        FormSection(header = stringResource(R.string.kn_refundable_after)) {
            KachatSegmented(choices.map { stringResource(it.second) }, choices.indexOfFirst { it.first == days }) { days = choices[it].first }
        }
    }
}

/**
 * `extend` (iOS bd2c54a / 49c0baa `KachatExtendSheet`): periods added to the current paid period
 * (periodStart kept), up to `maxYears` periods past its start - in practice a 1-period name extended
 * to 2. Anyone may extend any name. Each period costs the renewal price (registry v4, iOS c8f1086).
 */
@Composable
fun KachatExtendSheet(info: NameInfo, onClose: () -> Unit, vm: KachatLiveViewModel = hiltViewModel()) {
    val manifest by vm.service.manifest.collectAsState()
    val params = manifest?.params
    var years by remember { mutableLongStateOf(1L) }
    val maxYears = params?.maxYears ?: 2L
    /** The years that still fit in the period (in practice 1). */
    val available = maxOf(1L, params?.let { info.extendableYears(it) } ?: 1L)
    val perYear = KachatLive.renewPrice(vm.registry, info.name) ?: 0L
    val periodMs = params?.periodMs ?: KachatNames.YEAR_MS
    val yearly = KachatLive.yearlyPeriods(params)
    val title = when {
        params == null || !KachatLive.fillsPeriod(info, years, params) -> stringResource(R.string.kn_extend)
        yearly -> stringResource(R.string.kn_extend_to_years, maxYears.toInt())
        else -> stringResource(R.string.kn_extend_to_period, yearsText(maxYears.toInt(), params))
    }
    KachatTxSheet(
        title = title, confirmTitle = stringResource(R.string.kn_extend),
        doneTitle = R.string.kn_ev_extended,
        footer = if (yearly) stringResource(R.string.kn_extend_footer)
        else stringResource(R.string.kn_extend_footer_time, yearsText(maxYears.toInt(), params)),
        rows = listOf(
            KachatTxRow(stringResource(R.string.kl_name), info.display),
            KachatTxRow(pricePerPeriodTitle(params), KaspaUnit.amount(perYear)),
            KachatTxRow(stringResource(R.string.kn_expires), KachatLive.day(info.expiresAt)),
            KachatTxRow(stringResource(R.string.kn_new_expiry), KachatLive.day(info.expiresAt + years * periodMs)),
        ),
        operation = KachatNamesActions.Operation.Extend(info, minOf(years, available)), operationKey = "extend-$years",
        onClose = onClose, vm = vm
    ) {
        if (available > 1) {
            FormSection {
                KachatSegmented((1..available.toInt()).map { yearsText(it, params) }, (years - 1).toInt()) { years = (it + 1).toLong() }
            }
        }
    }
}

/**
 * `renew`: the next period, from the current expiry, for 1 or 2 periods - only once the renewal
 * window is open (`renewWindowMs` before the expiry; the detail screen says when; iOS bd2c54a,
 * 49c0baa). Each period costs the renewal price (registry v4, iOS c8f1086).
 */
@Composable
fun KachatRenewSheet(info: NameInfo, onClose: () -> Unit, vm: KachatLiveViewModel = hiltViewModel()) {
    val manifest by vm.service.manifest.collectAsState()
    var years by remember { mutableLongStateOf(1L) }
    val params = manifest?.params
    val maxYears = params?.maxYears ?: 2L
    val perYear = KachatLive.renewPrice(vm.registry, info.name) ?: 0L
    val periodMs = params?.periodMs ?: KachatNames.YEAR_MS
    KachatTxSheet(
        title = stringResource(R.string.kn_renew), confirmTitle = stringResource(R.string.kn_renew),
        doneTitle = R.string.kn_ev_renewed,
        footer = stringResource(R.string.kn_renew_from_expiry_footer),
        rows = listOf(
            KachatTxRow(stringResource(R.string.kl_name), info.display),
            KachatTxRow(pricePerPeriodTitle(params), KaspaUnit.amount(perYear)),
            KachatTxRow(
                stringResource(R.string.kn_new_period),
                "${KachatLive.day(info.expiresAt)} – ${KachatLive.day(info.expiresAt + years * periodMs)}"
            ),
        ),
        operation = KachatNamesActions.Operation.Renew(info, years), operationKey = "renew-$years",
        onClose = onClose, vm = vm
    ) {
        FormSection {
            val count = maxOf(1, maxYears.toInt())
            KachatSegmented((1..count).map { yearsText(it, params) }, (years - 1).toInt()) { years = (it + 1).toLong() }
        }
    }
}

@Composable
fun KachatListSheet(info: NameInfo, onClose: () -> Unit) {
    var priceText by remember { mutableStateOf("") }
    val price = KaspaUnit.sompiFromUserText(priceText)?.takeIf { it > 0 }
    KachatTxSheet(
        title = stringResource(if (info.isListed) R.string.kn_change_price else R.string.kn_list_for_sale),
        confirmTitle = stringResource(if (info.isListed) R.string.kn_change_price else R.string.km_list),
        doneTitle = if (info.isListed) R.string.kn_done_price_changed else R.string.kn_done_listed,
        footer = stringResource(R.string.kn_list_footer),
        rows = if (info.isListed) listOf(KachatTxRow(stringResource(R.string.kl_listed_at), KaspaUnit.amount(info.price))) else emptyList(),
        operation = price?.let { KachatNamesActions.Operation.List(info, it) }, operationKey = "list-${price ?: 0}",
        onClose = onClose
    ) {
        FormSection(header = stringResource(R.string.kl_price)) {
            AmountField(priceText) { priceText = it }
        }
    }
}

@Composable
fun KachatTransferSheet(info: NameInfo, onClose: () -> Unit, vm: KachatLiveViewModel = hiltViewModel()) {
    val colors = LocalAppColors.current
    var input by remember { mutableStateOf("") }
    var resolved by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    var resolveError by remember { mutableStateOf<Int?>(null) }
    var resolving by remember { mutableStateOf(false) }

    LaunchedEffect(input) {
        delay(400)
        resolved = null
        resolveError = null
        val t = input.trim().lowercase()
        if (t.isEmpty()) return@LaunchedEffect
        if (t.startsWith("kaspatest:") || t.startsWith("kaspa:")) {
            val key = KachatNamesRegistry.keyOf(t)
            if (key == null) { resolveError = R.string.kn_err_not_testnet_address; return@LaunchedEffect }
            if (runCatching { KachatNamesActions.validateKey(key, "") }.isFailure) { resolveError = R.string.kn_err_address_key; return@LaunchedEffect }
            resolved = t to key
            return@LaunchedEffect
        }
        val name = KachatNames.Codec.normalize(t)
        if (KachatLive.invalidReason(name) != null) { resolveError = R.string.kn_err_enter_address; return@LaunchedEffect }
        resolving = true
        try {
            val l = vm.registry.lookup(name)
            val active = (l as? Lookup.Registered)?.info?.takeIf { it.status(vm.graceMs) == Status.ACTIVE }
            if (active != null) {
                KachatNamesRegistry.address(active.owner)?.let { resolved = it to active.owner }
            } else {
                resolveError = R.string.kn_err_no_active_name
            }
        } catch (e: CancellationException) {
            resolving = false
            throw e
        } catch (_: Exception) {
            resolveError = R.string.kn_err_lookup
        }
        resolving = false
    }

    val target = resolved
    KachatTxSheet(
        title = stringResource(R.string.portfolio_type_transfer), confirmTitle = stringResource(R.string.portfolio_type_transfer),
        doneTitle = R.string.kn_done_transferred,
        warning = stringResource(R.string.kn_transfer_warning),
        rows = listOf(KachatTxRow(stringResource(R.string.kl_name), info.display)) +
            (target?.let { listOf(KachatTxRow(stringResource(R.string.to), it.first)) } ?: emptyList()),
        operation = target?.let { KachatNamesActions.Operation.Transfer(info, it.second) }, operationKey = target?.first ?: "-",
        onClose = onClose, vm = vm
    ) {
        FormSection(header = stringResource(R.string.kn_new_owner), footer = { FormFooter(stringResource(R.string.kn_transfer_footer)) }) {
            FormTextField(input, { input = it }, stringResource(R.string.kn_transfer_placeholder), Modifier.fillMaxWidth())
            when {
                resolving -> { SettingsDivider(); Box(Modifier.padding(16.dp)) { IosActivityIndicator(color = KaspaTeal) } }
                target != null -> {
                    SettingsDivider()
                    SelectionContainer {
                        Text(target.first, color = colors.textSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(16.dp))
                    }
                }
                resolveError != null -> {
                    SettingsDivider()
                    Text(stringResource(resolveError!!), color = colors.danger, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

// MARK: - Your Domains > .kachat

/**
 * The card badge for a name: Listed, or Expired (in grace) - shared by Your Domains and the
 * per-address lists ([KachatAddressLiveNamesList], iOS 881ada6 `KachatLiveDomainsTab.badge`);
 * neither lists lapsed names ([KachatNamesRegistry.heldNames], iOS eea52b2).
 */
@Composable
fun kachatNameBadge(n: NameInfo, graceMs: Long): String? = when (n.status(graceMs)) {
    Status.ACTIVE -> if (n.isListed) stringResource(R.string.kn_ev_listed) else null
    Status.GRACE -> stringResource(R.string.kn_status_expired)
    Status.LAPSED -> stringResource(R.string.kn_available)
}

/**
 * Your Domains > .kachat: the wallet's names (iOS `KachatLiveDomainsTab`). On every network since
 * iOS 7227d69 - where the registry isn't launched (mainnet) [vm] is null and the tab is its empty
 * state with the Inscribe button, without ever building the registry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KachatLiveDomainsTab(
    walletAddress: String,
    onOpen: (NameInfo) -> Unit,
    /** Inscribe: the .kachat marketplace over Your Domains (iOS e4da63d). */
    onInscribe: () -> Unit = {},
    /** Set when the marketplace was just closed: the list refreshes so a new name shows at once. */
    refreshRequested: Boolean = false,
    onRefreshHandled: () -> Unit = {},
    /** Withdraw or refund one of the offers this wallet made (My Offers, iOS 0765ce0). */
    onOfferAction: (KachatOfferAction) -> Unit = {},
    vm: KachatLiveViewModel? = if (KachatNamesService.isLaunched) hiltViewModel() else null,
) {
    val colors = LocalAppColors.current
    // `vm` is null for the screen's whole life on mainnet, so these calls are never conditional in practice.
    val revision = vm?.registry?.revision?.collectAsState()?.value
    val upgrading = vm?.service?.registryUpgrading?.collectAsState()?.value == true
    var names by remember { mutableStateOf<List<NameInfo>>(emptyList()) }
    // the offers this wallet made (moved here from the marketplace's former My Names tab, iOS 0765ce0)
    var myOffers by remember { mutableStateOf<List<OfferInfo>>(emptyList()) }
    var loaded by remember { mutableStateOf(vm == null) }

    LaunchedEffect(revision, walletAddress) {
        val key = KachatNamesRegistry.keyOf(walletAddress)
        if (vm == null || key == null) { loaded = true; return@LaunchedEffect }
        if (vm.registry.refreshedAt.value == null) vm.registry.refresh()
        // A lapsed name is no longer yours: it moves to the marketplace's Available tab (and the
        // bell says so, KachatNamesNotifier). Expired names in grace stay, to be renewed (iOS e26562e).
        names = try {
            vm.registry.heldNames(key)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        myOffers = try {
            vm.registry.myOffers(key)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        if (myOffers.isNotEmpty()) {
            // expired offers, and ones made to an earlier owner, come back on their own (iOS ba07975)
            vm.actions.refreshVirtualDaa()
            vm.actions.returnExpiredOffers(myOffers)
            vm.actions.withdrawDeclinedOffers(myOffers)
        }
        loaded = true
        // a name that lapses while this is open leaves right then (iOS aa36d2a)
        vm.registry.dropLapsed(names) { names = it }
    }

    LaunchedEffect(refreshRequested) {
        if (refreshRequested) {
            onRefreshHandled()
            vm?.registry?.refresh()
        }
    }

    val pullState = rememberPullToRefreshState()
    LaunchedEffect(pullState.isRefreshing) {
        if (pullState.isRefreshing) {
            vm?.registry?.refresh()
            pullState.endRefresh()
        }
    }
    Column(Modifier.fillMaxSize()) {
    Box(Modifier.weight(1f).fillMaxWidth().nestedScroll(pullState.nestedScrollConnection)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when {
                !loaded -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) { IosActivityIndicator(color = KaspaTeal) }
                // the bundled manifest is for the previous registry: calm, no error (iOS d2e0673)
                upgrading -> Column(
                    Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.Hardware, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(40.dp))
                    Text(stringResource(R.string.kn_setting_up), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text(stringResource(R.string.kn_registry_upgrading), color = colors.textSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
                }
                else -> {
                    if (names.isEmpty()) {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(KachatSymbols.AtCircle, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(44.dp))
                            Text(stringResource(R.string.km_no_names), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        }
                    } else {
                        names.forEach { n ->
                            DomainNameCard(title = n.display, badge = kachatNameBadge(n, vm?.graceMs ?: 0L), modifier = Modifier.clickable { onOpen(n) })
                        }
                    }
                    if (vm != null && myOffers.isNotEmpty()) {
                        // the section's own 16 dp gutters, flush with the list's
                        Box(Modifier.padding(top = 8.dp).layout { measurable, constraints ->
                            val wide = constraints.copy(maxWidth = constraints.maxWidth + 32.dp.roundToPx(), minWidth = constraints.minWidth + 32.dp.roundToPx())
                            val placeable = measurable.measure(wide)
                            layout(constraints.maxWidth, placeable.height) { placeable.place(-16.dp.roundToPx(), 0) }
                        }) {
                            KachatMyOffersSection(myOffers, vm, onOfferAction)
                        }
                    }
                }
            }
        }
        PullToRefreshContainer(state = pullState, modifier = Modifier.align(Alignment.TopCenter))
    }
    // Pinned under the list like the other name services' "Get a domain" button - the same
    // outlined capsule - whenever the registry is live (iOS e4da63d).
    if (loaded && !upgrading) {
        val hint = stringResource(R.string.kn_opens_marketplace)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(colors.surface)
                .border(androidx.compose.foundation.BorderStroke(1.5.dp, KaspaTeal), RoundedCornerShape(28.dp))
                .clickable(onClickLabel = hint) { onInscribe() }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.inscribe), color = KaspaTeal, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
    }
}

// MARK: - A name opened from a notification

/** The bare page a notification's name opens on while it is looked up (spinner, [onRetry] null)
 *  or when the lookup failed: the name, "Couldn't look that name up." and Try Again (iOS b799091). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KachatNameLookupScreen(name: String, onBack: () -> Unit, onRetry: (() -> Unit)?) {
    KachatRoutePage(onBack) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (onRetry == null) {
                IosActivityIndicator(color = KaspaTeal)
            } else {
                val colors = LocalAppColors.current
                Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("$name.kachat", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, textAlign = TextAlign.Center)
                    Text(stringResource(R.string.kn_err_lookup), color = colors.textSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
                    KachatButton(stringResource(R.string.try_again), onClick = onRetry)
                }
            }
        }
    }
}

/**
 * A name released or reclaimed since its notification: free to claim, with its price and Claim
 * on the gap it sits in (disabled while the source has no gap for it; iOS b799091 `freeName`).
 */
@Composable
fun KachatFreeNameScreen(name: String, gap: GapInfo?, onBack: () -> Unit, onClaim: (KachatClaimTarget) -> Unit, vm: KachatLiveViewModel) {
    val colors = LocalAppColors.current
    val params = vm.service.manifest.collectAsState().value?.params
    KachatRoutePage(onBack) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(Modifier.fillMaxWidth().kachatGlass(colors).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(18.dp)).background(KaspaTeal), contentAlignment = Alignment.Center) {
                    KachatFitText(
                        "$name.kachat", color = Color.Black, fontSize = 22.sp, fontWeight = FontWeight.Black, minScale = 0.5f,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.kn_free_to_claim), color = colors.textSecondary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    KachatAvailablePill()
                }
                vm.pricePerYear(name)?.let { price ->
                    val text = if (KachatLive.yearlyPeriods(params)) {
                        stringResource(R.string.kn_available_per_year, KaspaUnit.amount(price))
                    } else {
                        stringResource(R.string.kn_available_per_period, KaspaUnit.amount(price), yearsText(1, params))
                    }
                    Text(text, color = colors.textSecondary, fontSize = 12.sp)
                }
            }
            KachatButton(stringResource(R.string.km_claim), Modifier.fillMaxWidth(), Icons.Default.AlternateEmail, prominent = true, enabled = gap != null, large = true) {
                if (gap != null) onClaim(KachatClaimTarget(name, gap))
            }
        }
    }
}

/** A pushed page with only its back button (iOS's inline navigation bar with no title). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KachatRoutePage(onBack: () -> Unit, content: @Composable () -> Unit) {
    val colors = LocalAppColors.current
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBackIos, "Back", tint = KaspaTeal) } },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background)
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) { content() }
    }
}

// MARK: - Edit KaChat Profile

/**
 * Where a source's lookup stands (iOS `KachatSocialLookup`, 169f6a0 / c124cb3). A field whose
 * lookup hasn't found what it shows no longer blocks saving (iOS 5cac6af, [KachatProfileSaveRule]).
 */
enum class KachatSocialLookup { NONE, LOOKING, FOUND, EMPTY, UNREACHABLE }

/**
 * When Edit KaChat Profile can save (iOS `KachatLiveProfileEditor.blocked` /
 * `hasUncheckedLinks`, 5cac6af). Only a malformed handle or Linktree username stops a save. A
 * filled-in field whose lookup hasn't found what it shows (still looking, unreachable, or nothing
 * there) doesn't: a social site being slow or unreachable from this phone must never stop a
 * profile (or a primary name) from saving. The link is saved as entered, every viewer's app looks
 * it up itself, and the editor just says so.
 */
object KachatProfileSaveRule {
    data class Field(val input: KachatSourceInput, val kind: SocialSource.Kind, val lookup: KachatSocialLookup)

    private fun notReviewed(f: Field) = !f.input.isEmpty && f.lookup != KachatSocialLookup.FOUND

    fun blocked(fields: List<Field>, badLinktree: Boolean): Boolean =
        badLinktree || fields.any { it.input.isBad(it.kind) }

    fun hasUncheckedLinks(fields: List<Field>): Boolean = fields.any(::notReviewed)
}

/**
 * One profile field's source as the editor holds it: the platform picked and the handle typed
 * after its prefix (iOS `KachatSourceInput`, c124cb3).
 */
data class KachatSourceInput(val platform: SocialSource.Platform = SocialSource.Platform.X, val handle: String = "") {
    val isEmpty: Boolean get() = handle.isBlank()

    fun source(kind: SocialSource.Kind): SocialSource? = SocialSource.from(platform, handle, kind)

    fun isBad(kind: SocialSource.Kind): Boolean = !isEmpty && source(kind) == null

    /** The source when the handle field holds a whole pasted link (rather than a handle). */
    fun pastedSource(kind: SocialSource.Kind): SocialSource? = if (SocialSource.looksLikeLink(handle)) source(kind) else null

    companion object {
        /** From a stored profile link. */
        fun stored(link: String?, kind: SocialSource.Kind): KachatSourceInput =
            SocialSource.from(link ?: "", kind)?.let { KachatSourceInput(it.platform, it.displayHandle) } ?: KachatSourceInput()
    }
}

/**
 * One profile field's source (avatar, banner or bio) looked up on this device, showing exactly the
 * piece other people will see (iOS `KachatSocialPreview`, 169f6a0 / c124cb3 / 0f44a07). The
 * caller composes it only while the handle names an account.
 *
 * It can't restart itself (iOS 0f44a07): the lookup runs in one LaunchedEffect at the top of this
 * composable, keyed only by the link and the Retry count - never by [lookup] or the result - so
 * swapping the spinner for the result doesn't cancel and relaunch it. Leaving the composition or a
 * new key cancels the old lookup, so only the latest one ever writes its state.
 */
@Composable
private fun KachatSocialPreview(
    link: String,
    kind: SocialSource.Kind,
    lookup: KachatSocialLookup,
    onLookup: (KachatSocialLookup) -> Unit,
    resolver: KachatSocialImageResolver,
) {
    val colors = LocalAppColors.current
    val source = remember(link, kind) { SocialSource.from(link, kind) }
    var resolved by remember { mutableStateOf<SocialProfile?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val setLookup by rememberUpdatedState(onLookup)

    fun piece(p: SocialProfile?): String? = when (kind) {
        SocialSource.Kind.AVATAR -> p?.avatar
        SocialSource.Kind.BANNER -> p?.banner
        SocialSource.Kind.BIO -> p?.bio
    }

    // Debounced: one lookup once typing pauses; `attempt` reruns it for Retry. Whatever happens -
    // cancelled, restarted, failed - the state always lands somewhere final.
    LaunchedEffect(source?.link, attempt) {
        if (source == null) { resolved = null; setLookup(KachatSocialLookup.NONE); return@LaunchedEffect }
        setLookup(KachatSocialLookup.LOOKING)
        delay(500)
        val result = resolver.resolve(source)
        ensureActive()
        resolved = result.profile
        setLookup(
            when (result) {
                is KachatSocialImageResolver.Lookup.Answered -> if (piece(result.profile) == null) KachatSocialLookup.EMPTY else KachatSocialLookup.FOUND
                is KachatSocialImageResolver.Lookup.Unreachable -> if (piece(result.profile) == null) KachatSocialLookup.UNREACHABLE else KachatSocialLookup.FOUND
            }
        )
    }

    if (source == null) return
    val name = source.platform.displayName
    SettingsDivider()
    Column(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.Center) {
        when (lookup) {
            KachatSocialLookup.NONE, KachatSocialLookup.LOOKING -> Row(verticalAlignment = Alignment.CenterVertically) {
                IosActivityIndicator(modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.kn_social_looking), color = colors.textSecondary, fontSize = 13.sp)
            }
            KachatSocialLookup.FOUND -> Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (kind) {
                    SocialSource.Kind.AVATAR -> ContactAvatar(imageUrl = resolved?.avatar, fallbackText = "", size = 64.dp)
                    SocialSource.Kind.BANNER -> {
                        val gradient = androidx.compose.ui.graphics.Brush.linearGradient(listOf(KaspaTeal.copy(alpha = 0.55f), KaspaTeal.copy(alpha = 0.15f)))
                        // Whole, at its own proportions (iOS c66bfc7).
                        WholeBanner(model = resolved?.banner, placeholderHeight = 90.dp, shape = RoundedCornerShape(8.dp)) {
                            Box(Modifier.fillMaxSize().background(gradient))
                        }
                    }
                    SocialSource.Kind.BIO -> Text(resolved?.bio ?: "", color = colors.textPrimary, fontSize = 15.sp)
                }
                Text(stringResource(R.string.kn_social_from, name), color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            KachatSocialLookup.EMPTY -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(KachatSymbols.PersonCircleBadgeExclamation, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                val missing = when (kind) {
                    SocialSource.Kind.AVATAR -> R.string.kn_social_no_avatar
                    SocialSource.Kind.BANNER -> R.string.kn_social_no_banner
                    SocialSource.Kind.BIO -> R.string.kn_social_no_bio
                }
                Text(stringResource(missing, name), color = colors.textSecondary, fontSize = 13.sp)
            }
            KachatSocialLookup.UNREACHABLE -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SignalWifiStatusbarConnectedNoInternet4, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.kn_couldnt_reach, name), color = colors.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.retry),
                    color = KaspaTeal,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { attempt++ }.padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/**
 * Platform picker, the handle after the platform's prefix, and the preview of what it shows (iOS
 * `sourceField`, c124cb3 / 0f44a07). A whole pasted link switches the picker to its platform.
 */
@Composable
private fun KachatSourceField(
    input: KachatSourceInput,
    onInput: (KachatSourceInput) -> Unit,
    kind: SocialSource.Kind,
    lookup: KachatSocialLookup,
    onLookup: (KachatSocialLookup) -> Unit,
    resolver: KachatSocialImageResolver,
) {
    val colors = LocalAppColors.current
    var picking by remember { mutableStateOf(false) }
    // iOS's menu Picker: the label, the choice on the right, the choices in a menu.
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { picking = true }.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.kn_account_on), color = colors.textPrimary, modifier = Modifier.weight(1f))
            Text(input.platform.displayName, color = colors.textSecondary)
            Icon(Icons.Default.UnfoldMore, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
            SocialSource.Platform.choices(kind).forEach { p ->
                DropdownMenuItem(text = { Text(p.displayName) }, onClick = { onInput(input.copy(platform = p)); picking = false })
            }
        }
    }
    SettingsDivider()
    Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(input.platform.prefix, color = colors.textSecondary)
        FormTextField(
            input.handle,
            { h ->
                val typed = input.copy(handle = h)
                val pasted = typed.pastedSource(kind)
                onInput(if (pasted != null) KachatSourceInput(pasted.platform, pasted.displayHandle) else typed)
            },
            stringResource(if (input.platform == SocialSource.Platform.DISCORD) R.string.kn_invite else R.string.kn_handle),
            Modifier.weight(1f),
            keyboardType = KeyboardType.Uri
        )
    }
    // Only while the handle names an account: no empty row, and the lookup starts when it appears.
    input.source(kind)?.link?.let { link ->
        KachatSocialPreview(link, kind, lookup, onLookup, resolver)
    }
}

/**
 * The address profile (KACHAT_NAMES.md section 7): where the avatar, banner and bio come from (a
 * social profile link each - they can be different accounts), a Linktree link, and which of your
 * names labels you - written as a `kchat:1:profile:` self-transfer. No free text and no uploads:
 * what shows comes from a platform that moderates it (iOS `KachatLiveProfileEditor`, 5df42b4 /
 * 1322216 / 169f6a0 / c124cb3 / 0f44a07).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KachatLiveProfileEditorScreen(
    onBack: () -> Unit,
    /** On every network since iOS d36fc42: a profile is a self-send, with no registry behind it,
     *  so mainnet reads and saves it too. Only the primary name waits for mainnet's registry. */
    vm: KachatLiveViewModel? = if (KachatNamesService.profilesEnabled) hiltViewModel() else null,
    social: KachatSocialImageResolver = vm?.social ?: hiltViewModel<KachatSocialViewModel>().social,
) {
    val colors = LocalAppColors.current
    // Each piece's source: a platform from the picker plus the handle typed after its prefix.
    var avatarIn by remember { mutableStateOf(KachatSourceInput()) }
    var bannerIn by remember { mutableStateOf(KachatSourceInput()) }
    var bioIn by remember { mutableStateOf(KachatSourceInput()) }
    var avatarLookup by remember { mutableStateOf(KachatSocialLookup.NONE) }
    var bannerLookup by remember { mutableStateOf(KachatSocialLookup.NONE) }
    var bioLookup by remember { mutableStateOf(KachatSocialLookup.NONE) }
    var linktree by remember { mutableStateOf("") }
    var primary by remember { mutableStateOf("") }
    var activeNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    // Save Profile opens the review sheet (iOS 7e238e5), which saves and then closes the editor.
    var showSave by remember { mutableStateOf(false) }
    var pickPrimary by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val address = vm?.actions?.myAddress
        if (vm == null || address == null) { loaded = true; return@LaunchedEffect }
        vm.registry.refreshIfStale()
        val p = vm.registry.ownProfile(address)?.profile
            ?: runCatching { vm.registry.identity(address).profile }.getOrNull()
        if (p != null) {
            avatarIn = KachatSourceInput.stored(p.avatar, SocialSource.Kind.AVATAR)
            bannerIn = KachatSourceInput.stored(p.banner, SocialSource.Kind.BANNER)
            bioIn = KachatSourceInput.stored(p.bio, SocialSource.Kind.BIO)
            linktree = Profile.linktreeUsername(p.linktree)
        }
        KachatNamesRegistry.keyOf(address)?.let { key ->
            activeNames = (runCatching { vm.registry.names(key, includeInactive = false) }.getOrNull() ?: emptyList()).map { it.name }
        }
        p?.primaryName?.let { if (it in activeNames) primary = it }
        loaded = true
    }

    // Once one field's account is found, the empty fields take the same account where its platform
    // can fill them - one handle sets up the whole profile, and each stays editable. Each effect
    // runs only when its own lookup changes (iOS onChange), and only fills empty fields, so it
    // can't feed itself.
    fun fillEmpty(input: KachatSourceInput) {
        fun fits(kind: SocialSource.Kind) = input.platform in SocialSource.Platform.choices(kind)
        if (avatarIn.isEmpty && fits(SocialSource.Kind.AVATAR)) avatarIn = input
        if (bannerIn.isEmpty && fits(SocialSource.Kind.BANNER)) bannerIn = input
        if (bioIn.isEmpty && fits(SocialSource.Kind.BIO)) bioIn = input
    }
    LaunchedEffect(avatarLookup) { if (avatarLookup == KachatSocialLookup.FOUND) fillEmpty(avatarIn) }
    LaunchedEffect(bannerLookup) { if (bannerLookup == KachatSocialLookup.FOUND) fillEmpty(bannerIn) }
    LaunchedEffect(bioLookup) { if (bioLookup == KachatSocialLookup.FOUND) fillEmpty(bioIn) }

    // The Linktree field holds just the username (`linktr.ee/` is shown in front of it).
    val badLinktree = linktree.trim().let { it.isNotEmpty() && Profile.linktreeLinkFromUsername(it) == null }
    // Only a malformed handle or Linktree username stops a save; links that couldn't be checked
    // are saved as entered, with a note saying so (iOS 5cac6af).
    val saveFields = listOf(
        KachatProfileSaveRule.Field(avatarIn, SocialSource.Kind.AVATAR, avatarLookup),
        KachatProfileSaveRule.Field(bannerIn, SocialSource.Kind.BANNER, bannerLookup),
        KachatProfileSaveRule.Field(bioIn, SocialSource.Kind.BIO, bioLookup),
    )
    val blocked = KachatProfileSaveRule.blocked(saveFields, badLinktree)
    val hasUncheckedLinks = KachatProfileSaveRule.hasUncheckedLinks(saveFields)

    fun profile(): Profile = Profile(
        avatar = avatarIn.source(SocialSource.Kind.AVATAR)?.link,
        banner = bannerIn.source(SocialSource.Kind.BANNER)?.link,
        bio = bioIn.source(SocialSource.Kind.BIO)?.link,
        linktree = Profile.linktreeLinkFromUsername(linktree),
        primaryName = primary.ifEmpty { null }
    ).sanitized()

    if (showSave && vm != null) {
        KachatProfileSaveSheet(
            title = stringResource(R.string.kn_save_profile), confirmTitle = stringResource(R.string.kn_save_profile),
            doneTitle = R.string.kn_done_profile_saved, makeProfile = { profile() },
            onClose = { showSave = false }, onSaved = onBack, vm = vm
        )
        return
    }

    @Composable
    fun invalidHandleNote() = FormFooter(stringResource(R.string.kn_handle_bad), colors.danger)

    KachatLiveForm(title = stringResource(R.string.edit_kachat_profile), onClose = onBack) {
        FormSection(footer = { FormFooter(stringResource(R.string.kn_profile_pieces_footer)) }) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Badge, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.kn_profile_belongs), color = colors.textPrimary, fontSize = 15.sp)
            }
        }
        FormSection(header = stringResource(R.string.avatar), footer = if (avatarIn.isBad(SocialSource.Kind.AVATAR)) ({ invalidHandleNote() }) else null) {
            KachatSourceField(avatarIn, { avatarIn = it }, SocialSource.Kind.AVATAR, avatarLookup, { avatarLookup = it }, social)
        }
        FormSection(header = stringResource(R.string.banner), footer = if (bannerIn.isBad(SocialSource.Kind.BANNER)) ({ invalidHandleNote() }) else null) {
            KachatSourceField(bannerIn, { bannerIn = it }, SocialSource.Kind.BANNER, bannerLookup, { bannerLookup = it }, social)
        }
        FormSection(header = stringResource(R.string.bio), footer = if (bioIn.isBad(SocialSource.Kind.BIO)) ({ invalidHandleNote() }) else null) {
            KachatSourceField(bioIn, { bioIn = it }, SocialSource.Kind.BIO, bioLookup, { bioLookup = it }, social)
        }
        FormSection(
            header = stringResource(R.string.kn_links),
            footer = {
                if (badLinktree) FormFooter(stringResource(R.string.kn_linktree_username_bad), colors.danger)
                else FormFooter(stringResource(R.string.kn_linktree_footer))
            }
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("linktr.ee/", color = colors.textSecondary)
                FormTextField(linktree, { linktree = it }, stringResource(R.string.kn_username), Modifier.weight(1f), keyboardType = KeyboardType.Uri)
            }
        }
        FormSection(
            header = stringResource(R.string.kachat_name_section),
            footer = {
                FormFooter(stringResource(if (KachatNamesService.isLaunched) R.string.kn_primary_footer else R.string.kn_primary_not_on_mainnet))
            }
        ) {
            // The primary name needs the registry: until it launches on this network (mainnet)
            // there's no name to pick, so the profile saves without one (iOS d36fc42).
            if (!KachatNamesService.isLaunched) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.kn_primary_name), color = colors.textPrimary, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.coming_soon), color = colors.textSecondary)
                }
            } else
            // iOS's menu Picker: the label, the choice on the right, the choices in a menu.
            Box {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { pickPrimary = true }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.kn_primary_name), color = colors.textPrimary, modifier = Modifier.weight(1f))
                    Text(if (primary.isEmpty()) stringResource(R.string.kn_none) else "$primary.kachat", color = colors.textSecondary)
                    Icon(Icons.Default.UnfoldMore, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = pickPrimary, onDismissRequest = { pickPrimary = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.kn_none)) }, onClick = { primary = ""; pickPrimary = false })
                    activeNames.forEach { n ->
                        DropdownMenuItem(text = { Text("$n.kachat") }, onClick = { primary = n; pickPrimary = false })
                    }
                }
            }
        }
        // Saves on every network: a profile is a self-send, with no registry behind it (iOS d36fc42).
        FormSection(footer = {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (hasUncheckedLinks) {
                    Text(stringResource(R.string.kn_save_unchecked_links), fontSize = 13.sp, lineHeight = 18.sp, color = colors.textSecondary)
                }
                Text(stringResource(R.string.kn_save_footer), fontSize = 13.sp, lineHeight = 18.sp, color = colors.textSecondary)
            }
        }) {
            FormButtonRow(stringResource(R.string.kn_save_profile), enabled = loaded && !blocked && KachatNamesService.profilesEnabled) { showSave = true }
        }
    }
}

/**
 * Review before a profile record goes out - what will be saved, the network fee, the chatting
 * address's balance before and after - the same confirmation every other name action shows; then
 * the device lock, the save, and the finished-transaction half sheet. Used by Edit KaChat
 * Profile and by Set as Primary (iOS `KachatProfileSaveSheet`, 7e238e5). A full-screen swap with
 * Cancel top left, as every sheet in this port.
 */
@Composable
fun KachatProfileSaveSheet(
    title: String,
    confirmTitle: String,
    @StringRes doneTitle: Int,
    /** Builds the record to save when the sheet opens (Set as Primary reads your current profile). */
    makeProfile: suspend () -> Profile,
    onClose: () -> Unit,
    onSaved: () -> Unit = {},
    vm: KachatLiveViewModel = hiltViewModel(),
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val view = LocalView.current
    val prefs = remember { context.getSharedPreferences("kachat_prefs", Context.MODE_PRIVATE) }
    var privacySeen by remember { mutableStateOf(prefs.getBoolean(PRIVACY_SEEN_KEY, false)) }
    var profile by remember { mutableStateOf<Profile?>(null) }
    var fee by remember { mutableStateOf<Long?>(null) }
    var quoteError by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<KachatTxDone?>(null) }
    val chattingBalance by vm.wallet.balance.collectAsState()
    val chattingBalanceKnown by vm.wallet.balanceKnown.collectAsState()
    val currentMakeProfile by rememberUpdatedState(makeProfile)
    val none = stringResource(R.string.kn_none)

    LaunchedEffect(Unit) {
        val p = currentMakeProfile()
        profile = p
        try {
            fee = vm.actions.profileFee(p)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            quoteError = context.kachatErrorText(e)
        }
    }

    fun source(link: String?, kind: SocialSource.Kind): String =
        SocialSource.from(link ?: "", kind)?.let { "${it.platform.displayName} · ${it.platform.prefix}${it.displayHandle}" } ?: none

    fun authorize() {
        val p = profile ?: return
        context.kachatAuthorize {
            sending = true
            sendError = null
            vm.launch {
                try {
                    val tx = vm.actions.saveProfile(p)
                    privacySeen = true
                    prefs.edit().putBoolean(PRIVACY_SEEN_KEY, true).apply()
                    done = KachatTxDone(tx, doneTitle)
                    view.successHaptic()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    sendError = context.kachatErrorText(e)
                }
                sending = false
            }
        }
    }

    KachatLiveForm(title = title, onClose = onClose) {
        profile?.let { p ->
            FormSection(header = stringResource(R.string.kn_your_profile)) {
                LabeledRow(stringResource(R.string.avatar), source(p.avatar, SocialSource.Kind.AVATAR)); SettingsDivider()
                LabeledRow(stringResource(R.string.banner), source(p.banner, SocialSource.Kind.BANNER)); SettingsDivider()
                LabeledRow(stringResource(R.string.bio), source(p.bio, SocialSource.Kind.BIO)); SettingsDivider()
                LabeledRow(stringResource(R.string.kn_linktree), p.linktree?.replace("https://", "") ?: none); SettingsDivider()
                LabeledRow(stringResource(R.string.kn_primary_name), p.primaryName?.let { "$it.kachat" } ?: none)
            }
        }
        val f = fee
        val qe = quoteError
        if (f != null || qe == null) {
            FormSection(footer = {
                when {
                    qe != null -> FormFooter(qe, colors.danger)
                    privacySeen -> FormFooter(stringResource(R.string.kn_saved_on_chain_note))
                    else -> FormFooter(stringResource(R.string.kn_profiles_public_note))
                }
            }) {
                if (f != null) {
                    LabeledRow(stringResource(R.string.kl_network_fee), KaspaUnit.amount(f))
                    if (chattingBalanceKnown) {
                        SettingsDivider()
                        LabeledRow(stringResource(R.string.kn_chatting_balance), KaspaUnit.amount(chattingBalance))
                        SettingsDivider()
                        LabeledRow(stringResource(R.string.kn_balance_after), KaspaUnit.amount(maxOf(0L, chattingBalance - f)), bold = true)
                    }
                } else {
                    LoadingRow(stringResource(R.string.kl_network_fee))
                }
            }
        } else {
            // iOS: an empty section whose footer carries the quote's error.
            FormFooter(qe, colors.danger)
        }
        FormSection(footer = { sendError?.let { FormFooter(it, colors.danger) } }) {
            FormButtonRow(confirmTitle, enabled = f != null && profile != null && !sending && done == null, busy = sending) { authorize() }
        }
    }

    // Closing the finished-transaction sheet closes this sheet, and then the caller's (iOS
    // onDismiss: dismiss(); onSaved()).
    done?.let { KachatTxDoneSheet(it, onDismiss = { done = null; onClose(); onSaved() }, vm = vm) }
}

/** iOS `@AppStorage("kachat_profile_privacy_seen")`: the first save says profiles are public. */
private const val PRIVACY_SEEN_KEY = "kachat_profile_privacy_seen"
