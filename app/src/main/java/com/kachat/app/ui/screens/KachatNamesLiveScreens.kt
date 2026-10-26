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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Cancel
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
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
import com.kachat.app.services.kachatnames.Plan
import com.kachat.app.services.kachatnames.Profile
import com.kachat.app.services.kachatnames.SocialProfile
import com.kachat.app.services.kachatnames.SocialSource
import com.kachat.app.services.kachatnames.Status
import com.kachat.app.services.kachatnames.nowMs
import com.kachat.app.ui.theme.IosActivityIndicator
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
// The live `.kachat` screens, where the registry is live (testnet-10, and mainnet since iOS ef6b21e;
// with a verified registry manifest) - a port
// of iOS KaChat/Views/Ecosystem/KachatNamesLiveViews.swift (KaChat 5df42b4): the hub's search,
// registrations in flight, Marketplace / My Names / Activity, the name detail with its actions,
// every transaction sheet, Your Domains > .kachat and the address profile editor. Where the
// registry isn't launched, the same screens show empty under "Coming soon" (iOS 7227d69). Every spending or destructive action shows its cost first, asks to confirm, then
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
    /** The registry is live on this network (testnet-10 and mainnet) - reads and actions run. Not
     *  the .kachat UI, which is on everywhere ([KachatNamesService.isEnabled], iOS 7227d69). */
    val isEnabled: Boolean get() = KachatNamesService.isLaunched

    /** testnet-10 and mainnet run at 10 blocks per second */
    const val DAA_PER_SECOND: Long = 10

    /**
     * MAINNET.md C6 (iOS 0312a3f `KachatLiveNameDetail.currentOffers`): an offer is bound to a
     * seller key, so one made to this owner before the name last left them - and came back - could
     * be accepted again. Only offers made since the current owner got the name are shown for them;
     * others to other sellers (declined, on their way back to the buyer) stay.
     */
    fun currentOffers(offers: List<OfferInfo>, owner: ByteArray, history: List<Event>): List<OfferInfo> {
        val ownershipOps = setOf("register", "import", "transfer", "sale", "offer_accepted", "offer_accept")
        val since = history.filter { it.op in ownershipOps }.mapNotNull { it.at }.maxOrNull() ?: return offers
        return offers.filter { o ->
            val created = o.createdAt
            if (!o.seller.contentEquals(owner) || created == null) return@filter true
            created >= since
        }
    }

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
        // registry v5's import from the old registry (iOS dd836cb `arrow.down.doc`)
        "import" -> Icons.Outlined.FileDownload
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
        "import" -> R.string.kn_ev_imported
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
     * (testnet's 24-hour periods, or a renewal that opens tomorrow): iOS 49c0baa `KachatLive.day`
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

    /** Whether a period is a year (mainnet), not a short test clock (testnet's 24 hours; iOS 49c0baa). */
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
     * (ba07975 `KachatOfferRow.expiresIn`, now 7f50e84 `KachatOfferState.timeLeft`) is ICU's narrow measure format.
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
        // One formatter per language, not one per call: the Expired page ticks every tile's
        // countdown once a second (iOS defda2e). Formatted under the lock, as ICU formatters are
        // not documented as safe to share across threads.
        synchronized(measureFormats) {
            val format = measureFormats.getOrPut(locale) {
                android.icu.text.MeasureFormat.getInstance(locale, android.icu.text.MeasureFormat.FormatWidth.NARROW)
            }
            return format.formatMeasures(*measures.toTypedArray())
        }
    }

    private val measureFormats = HashMap<Locale, android.icu.text.MeasureFormat>()

    /**
     * Time left until a moment, for a live countdown (iOS cb3c27d `KachatLive.countdown`): "2d 5h"
     * while days remain, else "1:04:09" or "4:09" (hours, minutes, seconds).
     */
    fun countdown(ms: Long, context: Context): String {
        val s = maxOf(ms, 0L) / 1000
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        return when {
            s >= 86_400 -> timeLeft(
                listOf(KachatNamesActions.TimeLeftUnit.DAY to s / 86_400, KachatNamesActions.TimeLeftUnit.HOUR to (s % 86_400) / 3600)
                    .filter { it.second > 0 },
                context
            )
            s >= 3600 -> String.format(locale, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
            else -> String.format(locale, "%d:%02d", s / 60, s % 60)
        }
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
        is KachatNamesActions.ActionError.NoWallet -> getString(R.string.kn_err_no_wallet_open)
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
        // localized on iOS too (0312a3f)
        is KachatNamesActions.ActionError.OfferPastExpiry -> getString(R.string.kn_err_offer_past_expiry)
        // localized on iOS too (4f5d95e)
        is KachatNamesActions.ActionError.PriceChanged -> getString(R.string.kn_err_price_changed, KaspaUnit.amount(e.price))
        // localized on iOS too (7e2b6cd, 218dc42)
        is KachatNamesActions.ActionError.FeeChanged -> getString(R.string.kn_err_fee_changed, KaspaUnit.amount(e.fee))
        // localized on iOS too (dd836cb)
        is KachatNamesActions.ActionError.RegistrationNotOpen -> getString(R.string.kn_registration_not_open, KachatLive.day(e.opensMs))
        // localized on iOS too (71128c4)
        is KachatNamesActions.ActionError.ExpiredTooLongToRenew -> getString(R.string.kn_err_renew_expired_too_long)
        is KachatNamesActions.ActionError.OfferNameNotActive -> getString(R.string.kn_err_offer_name_not_active)
        is KachatNamesActions.ActionError.AcceptNameExpired -> getString(R.string.kn_err_accept_name_expired)
        is KachatNamesService.ServiceError.RegistryUpgrading -> getString(R.string.kn_registry_being_upgraded)
        // localized on iOS too (ef6b21e)
        is KachatNamesService.ServiceError.TestnetOnly -> getString(R.string.kachat_names_not_live_here)
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
    // a commit waiting for registry v5's migration deadline (iOS dd836cb)
    REGISTRATION_NOT_OPEN.matchEntire(m)?.groupValues?.get(1)?.toLongOrNull()?.let {
        return getString(R.string.kn_registration_not_open, KachatLive.day(it))
    }
    return when (m) {
        "The commit never reached the chain." -> getString(R.string.kn_err_commit_never)
        "The commit is no longer on chain." -> getString(R.string.kn_err_commit_gone)
        // the busy-network notes (iOS b219bb0)
        KachatNamesActions.COMMIT_WAITING_BUSY -> getString(R.string.kn_commit_waiting_busy)
        KachatNamesActions.COMMIT_SENT_AGAIN -> getString(R.string.kn_commit_sent_again)
        // combining many small coins first (iOS f1c16ec)
        KachatNamesActions.COMBINING_COINS -> getString(R.string.kn_combining_coins)
        KachatNamesActions.NOT_ENOUGH_TO_COMBINE -> getString(R.string.kn_not_enough_to_combine)
        else -> m
    }
}

/** [KachatNamesActions.freeingName] (iOS eea52b2). */
private val FREEING_NAME = Regex("Freeing (.+) for you\\.\\.\\.")

/** [KachatNamesActions.registrationNotOpenNote] (iOS dd836cb). */
private val REGISTRATION_NOT_OPEN = Regex("Names are moving to the new registry\\. New names can be claimed from (\\d+)\\.")

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
    /** Expired and still in grace: the Expired tab, with a countdown to each release (iOS cb3c27d). */
    var grace by mutableStateOf<List<NameInfo>>(emptyList()); private set
    var mine by mutableStateOf<List<NameInfo>>(emptyList()); private set
    var myOffers by mutableStateOf<List<OfferInfo>>(emptyList()); private set
    var activity by mutableStateOf<List<Event>>(emptyList()); private set
    var loadError by mutableStateOf<Throwable?>(null); private set
    var loaded by mutableStateOf(false); private set

    /** This wallet's x-only key. Read once here: it parses the account list, too slow per row. */
    var myKey by mutableStateOf(if (KachatNamesService.isLaunched) actions.myKey else null); private set
    /** Every key of this wallet - the chatting address's and each revealed spending address's
     *  ([KachatNamesActions.allOwnKeys], iOS d1026de). Worked out off the main thread on start and
     *  every reload; until then just the chatting key. */
    var ownKeys by mutableStateOf<List<ByteArray>>(listOfNotNull(myKey)); private set

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

    /** A key of this wallet: the chatting address or any revealed spending address (iOS d1026de
     *  `KachatLive.isMine`, IOS-085). */
    fun isMine(key: ByteArray): Boolean =
        myKey?.contentEquals(key) == true || ownKeys.any { it.contentEquals(key) }

    /** The chatting address's own key only: Set as Primary applies only to its names (iOS d1026de). */
    fun isChattingKey(key: ByteArray): Boolean = myKey?.contentEquals(key) == true

    /** Works [ownKeys] out again (cached per account and address count, so cheap after the first). */
    suspend fun loadOwnKeys() {
        val keys = actions.allOwnKeys()
        if (keys.size != ownKeys.size || keys.indices.any { !keys[it].contentEquals(ownKeys[it]) }) ownKeys = keys
    }

    suspend fun start() {
        if (!KachatLive.isEnabled) {
            ready = null
            return
        }
        myKey = actions.myKey
        loadOwnKeys()
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
        // Coming back to the screen: the pages are loaded, so only a stale registry is refreshed
        // (the pages reload on its revision). A full refresh and reload ran on every appear.
        if (loaded && loadError == null) {
            registry.refreshIfStale()
            return
        }
        // A refresh that moved `revision` already reloads through the revision observer above;
        // reloading here as well ran the seven requests twice, side by side (iOS 9de981a).
        val before = registry.revision.value
        registry.refresh()
        if (registry.revision.value == before) reload()
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
            grace = try {
                registry.inGrace()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            // the chatting address and every used address of this wallet (iOS d1026de, IOS-085)
            loadOwnKeys()
            if (actions.myKey != null) {
                val (names, offers) = actions.ownNamesAndOffers(heldOnly = false)
                mine = names
                myOffers = offers
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
        // a profile saved on another device since this one's last save replaces the local copy
        // (iOS 5d4ce87)
        registry.syncOwnProfile(address)
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

    /** "Address #3" (or its name) for one of this wallet's addresses, else null
     *  ([WalletManager.ownAddressLabel], iOS 6e938ff). Off the main thread: it derives on a miss. */
    suspend fun ownAddressLabel(address: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        runCatching { walletManager.ownAddressLabel(address) }.getOrNull()
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

/** A filled field's trailing clear button (iOS `xmark.circle.fill`, secondary). The 20dp icon
 *  sits 16 in from the edge as before; the tap target around it is 44dp, iOS's minimum. */
@Composable
private fun FormClearButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(end = 4.dp)
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Cancel,
            contentDescription = stringResource(R.string.clear),
            tint = LocalAppColors.current.textSecondary,
            modifier = Modifier.size(20.dp),
        )
    }
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
private fun KachatLiveForm(
    title: String,
    onClose: () -> Unit,
    finished: Boolean = false,
    /** Held by the caller when the form steps away and comes back (a review shown in its place)
     *  and has to come back where it was, as under an iOS sheet. */
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onClose)
    val colors = LocalAppColors.current
    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                // iOS 26: the bar's buttons on glass capsules, 16 in from the edges.
                navigationIcon = {
                    if (!finished) SheetBarButton(stringResource(R.string.cancel), onClick = onClose, modifier = Modifier.padding(start = 12.dp))
                },
                actions = {
                    if (finished) SheetBarButton(stringResource(R.string.done), onClick = onClose, modifier = Modifier.padding(end = 12.dp))
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background)
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(scrollState).padding(16.dp).padding(bottom = 40.dp),
            content = content,
        )
    }
}

// MARK: - Name tiles

/** One line that shrinks to fit rather than being cut, down to [minScale] of its size (iOS
 *  `.lineLimit(1).minimumScaleFactor`). */
@Composable
internal fun KachatFitText(
    text: String,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit = 15.sp,
    fontWeight: FontWeight = FontWeight.SemiBold,
    fontFamily: FontFamily? = null,
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
        fontFamily = fontFamily,
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

// MARK: - A finished transaction

/** A name transaction that went out: what it did and its id, for [KachatTxDoneSheet] (iOS
 *  `KachatTxDone`, 0870fcc). [accepted]: already known to be in a block (a registration the
 *  driver saw land) - no progress to follow (iOS e426432). */
data class KachatTxDone(
    val txId: String,
    @StringRes val title: Int = R.string.kn_done_tx_sent,
    val accepted: Boolean = false,
    /** A line under the headline - a registration says how much stays locked with the name (iOS b3b62f5). */
    val note: String? = null,
)

/**
 * The receipt every name transaction ends on, in the Send receipt's style (iOS e426432
 * `KachatTxDoneSheet`): what it does, its progress followed on a node - sent, in a block, showing
 * in KaChat ([KachatNamesActions.txStages]) - and the transaction id as a link to the block
 * explorer picked in Settings (the testnet-10 one on testnet), opened in the in-app browser over
 * the sheet. Closing it early is fine: the change still lands.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KachatTxDoneSheet(done: KachatTxDone, onDismiss: () -> Unit, vm: KachatLiveViewModel = hiltViewModel()) {
    val explorer by vm.settings.kaspaExplorer.collectAsState(initial = com.kachat.app.models.KaspaExplorer.default)
    val explorerUrl = explorer.txUrl(done.txId)
    val stages by vm.actions.txStages.collectAsState()
    val stage = stages[done.txId] ?: if (done.accepted) KachatNamesActions.TxStage.SHOWN else KachatNamesActions.TxStage.SENT
    val inBlock = stage == KachatNamesActions.TxStage.ACCEPTED || stage == KachatNamesActions.TxStage.SHOWN
    var browserUrl by remember { mutableStateOf<String?>(null) }

    // a transaction this sheet wasn't handed by `perform` (a profile save): follow it here
    LaunchedEffect(done.txId) {
        if (vm.actions.txStages.value[done.txId] == null && !done.accepted) vm.actions.follow(done.txId, null)
    }

    com.kachat.app.ui.theme.IosSheetColors {
        val colors = LocalAppColors.current
        // Floats on its height detent, as iOS 26's does, settling onto the edges as it grows.
        var sheetFloat by remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
        ModalBottomSheet(
            scrimColor = com.kachat.app.ui.theme.IosSheetScrim,
            modifier = Modifier.iosFloatingSheet(sheetFloat),
            shape = iosFloatingSheetShape(sheetFloat),
            tonalElevation = com.kachat.app.ui.theme.IosSheetTonalElevation,
            windowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars,
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.background,
            dragHandle = null,
        ) {
            // The sheet runs down behind the navigation bar, as iOS's does behind the home indicator;
            // its content stays above it. iOS: `.presentationDetents([.height(520), .large])` with
            // `.presentationDragIndicator(.visible)` - 520 high, pulled up to full height, the
            // grabber drawn over the content.
            Column(Modifier.navigationBarsPadding()) {
                IosSheetDetents(height = 520.dp, largeDetent = true, onFloat = { sheetFloat = it }) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.padding(top = 28.dp).height(52.dp), contentAlignment = Alignment.Center) {
                            when (stage) {
                                KachatNamesActions.TxStage.SHOWN ->
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = colors.success, modifier = Modifier.size(52.dp))
                                KachatNamesActions.TxStage.DROPPED ->
                                    Icon(Icons.Default.Error, contentDescription = null, tint = colors.warning, modifier = Modifier.size(52.dp))
                                else -> IosActivityIndicator(color = colors.textSecondary, modifier = Modifier.size(36.dp))
                            }
                        }
                        Text(
                            stringResource(done.title), color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp, start = 24.dp, end = 24.dp)
                        )
                        Text(
                            stringResource(
                                when (stage) {
                                    KachatNamesActions.TxStage.SENT, KachatNamesActions.TxStage.IN_MEMPOOL -> R.string.kn_tx_stage_waiting
                                    KachatNamesActions.TxStage.ACCEPTED -> R.string.kn_tx_stage_in_block
                                    KachatNamesActions.TxStage.SHOWN -> R.string.kn_tx_stage_done
                                    KachatNamesActions.TxStage.DROPPED -> R.string.kn_tx_stage_dropped
                                }
                            ),
                            color = colors.textSecondary, fontSize = 15.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp, start = 28.dp, end = 28.dp)
                        )

                        done.note?.let { note ->
                            Row(
                                Modifier.padding(top = 12.dp, start = 20.dp, end = 20.dp).fillMaxWidth().sendKaspaGlass(14.dp).padding(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = KaspaTeal, modifier = Modifier.padding(top = 1.dp).size(15.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(note, color = KaspaTeal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Start)
                            }
                        }

                        Column(
                            Modifier.padding(top = 18.dp, start = 20.dp, end = 20.dp).fillMaxWidth().sendKaspaGlass(16.dp).padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            KachatTxStep(stringResource(R.string.kn_step_sent), done = true)
                            KachatTxStep(stringResource(R.string.kn_step_in_block), done = inBlock, active = !inBlock && stage != KachatNamesActions.TxStage.DROPPED)
                            KachatTxStep(stringResource(R.string.kn_step_updated), done = stage == KachatNamesActions.TxStage.SHOWN, active = stage == KachatNamesActions.TxStage.ACCEPTED)
                        }

                        Row(
                            Modifier.padding(top = 12.dp, start = 20.dp, end = 20.dp).fillMaxWidth().sendKaspaGlass(14.dp)
                                .clickable(enabled = explorerUrl != null) { browserUrl = explorerUrl }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            // iOS truncates in the middle: the start and the end of a txid are what people compare.
                            val id = done.txId
                            Text(
                                if (id.length > 36) "${id.take(17)}...${id.takeLast(17)}" else id,
                                color = KaspaTeal, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(15.dp))
                        }
                        Text(
                            stringResource(R.string.kn_tap_tx_explorer), color = colors.textSecondary, fontSize = 12.sp,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, start = 20.dp, end = 20.dp)
                        )

                        Box(
                            Modifier.padding(top = 18.dp, start = 20.dp, end = 20.dp).fillMaxWidth().clip(CircleShape).background(KaspaTeal)
                                .clickable(onClick = onDismiss).padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(stringResource(R.string.done), color = Color.Black, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        }
                    }
                    IosSheetGrabber(Modifier.align(Alignment.TopCenter))
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

/** One step of the receipt: a green check when done, a spinner while it's the one in progress,
 *  else an empty circle (iOS e426432 `step`). */
@Composable
private fun KachatTxStep(title: String, done: Boolean, active: Boolean = false) {
    val colors = LocalAppColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
            when {
                done -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = colors.success, modifier = Modifier.size(20.dp))
                active -> IosActivityIndicator(color = colors.textSecondary, modifier = Modifier.size(16.dp))
                else -> Icon(Icons.Default.RadioButtonUnchecked, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(title, color = if (done || active) colors.textPrimary else colors.textSecondary, fontSize = 15.sp)
    }
}

// MARK: - Your Domains > .kachat

/**
 * The card badge for a name in Your Domains: Listed, or Expired (in grace) - iOS
 * `KachatOwnedNamesList.badge`. Lapsed names are never listed ([KachatNamesRegistry.heldNames]).
 */
@Composable
fun kachatNameBadge(n: NameInfo, graceMs: Long): String? = when (n.status(graceMs)) {
    Status.ACTIVE -> if (n.isListed) stringResource(R.string.kn_ev_listed) else null
    Status.GRACE -> stringResource(R.string.kn_status_expired)
    Status.LAPSED -> stringResource(R.string.kn_available)
}

/** Your Domains > .kachat card's bottom-right corner: when the name expires - or, once it has,
 *  when its grace period ends (renew before then to keep it) (iOS 72b7dc7 `expiryLine`). */
@Composable
fun kachatNameExpiryLine(n: NameInfo, graceMs: Long): String? = when (n.status(graceMs)) {
    Status.ACTIVE -> stringResource(R.string.kn_expires_on, KachatLive.day(n.expiresAt))
    Status.GRACE -> stringResource(R.string.kn_grace_ends, KachatLive.day(n.expiresAt + graceMs))
    Status.LAPSED -> null
}

/**
 * How many .kachat names [walletAddress] holds, for its Your Domains card (iOS 2ee667c
 * `refreshKachatCount`): the same read [KachatLiveDomainsTab] does, reduced to a count - the whole
 * wallet's held names for the own chatting address, the one address's otherwise. Counted again
 * whenever the registry revision moves, so a name inscribed, bought, transferred or lapsed behind
 * a card shows the moment you are back. Null until the first count lands (the card shows a dash,
 * not a made-up 0).
 */
@Composable
fun rememberKachatHeldCount(
    walletAddress: String,
    vm: KachatLiveViewModel? = if (KachatNamesService.isLaunched) hiltViewModel() else null,
): Int? {
    // `vm` is null for the screen's whole life where the registry isn't launched.
    val revision = vm?.registry?.revision?.collectAsState()?.value
    var count by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(revision, walletAddress) {
        if (vm == null || !KachatNamesService.isEnabled) { count = 0; return@LaunchedEffect }
        if (vm.registry.refreshedAt.value == null) vm.registry.refresh()
        val own = if (walletAddress.lowercase() == vm.actions.myAddress) {
            try {
                vm.actions.ownNamesAndOffers(heldOnly = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val key = KachatNamesRegistry.keyOf(walletAddress)
        count = when {
            own != null -> own.first.size
            key != null -> try {
                vm.registry.heldNames(key).size
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            else -> 0
        }
    }
    return count
}

/**
 * Your Domains > .kachat: the names this wallet holds, each with when it expires (or, in its grace
 * period, when that ends). Read-only - buying, renewing, selling and transferring them happens in
 * KaChat Wallet since the market left this app (iOS d63b29d `KachatOwnedNamesList`). Your own list
 * (the chatting address's) holds the names on every address of this wallet; any other address
 * lists its own. Lapsed names aren't yours any more and aren't listed. Where the registry isn't
 * launched [vm] is null and the list is empty, without ever building the registry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KachatOwnedNamesList(
    walletAddress: String,
    vm: KachatLiveViewModel? = if (KachatNamesService.isLaunched) hiltViewModel() else null,
) {
    val colors = LocalAppColors.current
    // `vm` is null for the screen's whole life where the registry isn't launched.
    val revision = vm?.registry?.revision?.collectAsState()?.value
    val upgrading = vm?.service?.registryUpgrading?.collectAsState()?.value == true
    var names by remember { mutableStateOf<List<NameInfo>>(emptyList()) }
    var loaded by remember { mutableStateOf(vm == null) }

    LaunchedEffect(revision, walletAddress) {
        val key = KachatNamesRegistry.keyOf(walletAddress)
        if (vm == null || key == null) { loaded = true; return@LaunchedEffect }
        if (vm.registry.refreshedAt.value == null) vm.registry.refresh()
        val own = if (walletAddress.lowercase() == vm.actions.myAddress) {
            try {
                vm.actions.ownNamesAndOffers(heldOnly = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        names = own?.first ?: try {
            vm.registry.heldNames(key)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        loaded = true
        // a name that lapses while this is open leaves right then
        vm.registry.dropLapsed(names) { names = it }
    }

    val pullState = rememberPullToRefreshState()
    LaunchedEffect(pullState.isRefreshing) {
        if (pullState.isRefreshing) {
            vm?.registry?.refresh()
            pullState.endRefresh()
        }
    }
    // Your Domains runs on under the dock: the list clears it itself.
    val dockInset = LocalDockInset.current
    Box(Modifier.fillMaxSize().nestedScroll(pullState.nestedScrollConnection)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
                .padding(bottom = dockInset),
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
                    Text(stringResource(R.string.kn_registry_being_upgraded), color = colors.textSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
                }
                names.isEmpty() -> Column(
                    Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(KachatSymbols.AtCircle, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(44.dp))
                    Text(stringResource(R.string.km_no_names), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                }
                else -> names.forEach { n ->
                    DomainNameCard(
                        title = n.display,
                        badge = kachatNameBadge(n, vm?.graceMs ?: 0L),
                        footnote = kachatNameExpiryLine(n, vm?.graceMs ?: 0L),
                    )
                }
            }
            Text(
                stringResource(R.string.kn_manage_in_wallet),
                color = colors.textSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
        PullToRefreshContainer(state = pullState, modifier = Modifier.align(Alignment.TopCenter))
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
 * new key cancels the old lookup, so only the latest one ever writes its state. Coming back into the
 * composition, it shows the resolver's stored answer at once and re-checks quietly (iOS d17d208).
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
        // The preview came back into the composition (the Save / Clear review left the form and
        // closed again): the answer this screen already got shows at once - no debounce, no
        // "Looking up" flash, no refetch (iOS d17d208). Retry (attempt > 0) still runs the full
        // lookup.
        val cached = if (attempt == 0) resolver.cachedProfile(source) else null
        if (cached != null) {
            resolved = cached
            setLookup(if (piece(cached) == null) KachatSocialLookup.EMPTY else KachatSocialLookup.FOUND)
            // Still re-checked quietly (the resolver's own 5-minute freshness): a profile changed
            // on the platform lands here without the flash.
            val result = resolver.resolve(source)
            ensureActive()
            if (result !is KachatSocialImageResolver.Lookup.Answered || result.profile == cached) return@LaunchedEffect
            resolved = result.profile
            setLookup(if (piece(result.profile) == null) KachatSocialLookup.EMPTY else KachatSocialLookup.FOUND)
            return@LaunchedEffect
        }
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
        // One tap empties the field; saving then writes the profile without it (iOS 9638498).
        if (input.handle.isNotEmpty()) {
            FormClearButton {
                onInput(KachatSourceInput())
                onLookup(KachatSocialLookup.NONE)
            }
        }
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
    // Clear Profile opens the same review sheet with the empty record (iOS 9638498).
    var showClear by remember { mutableStateOf(false) }
    var pickPrimary by remember { mutableStateOf(false) }
    // The editor's scroll position outlives the Save / Clear review drawn in its place, so Cancel
    // there returns to the field you were at - iOS shows the review as a sheet over the editor.
    val formScroll = rememberScrollState()

    LaunchedEffect(Unit) {
        val address = vm?.actions?.myAddress
        if (vm == null || address == null) { loaded = true; return@LaunchedEffect }
        vm.registry.refreshIfStale()
        // start from the newest profile, wherever it was saved (another device included; iOS
        // 5d4ce87) - never from an older copy that a save would write over it
        vm.registry.syncOwnProfile(address)
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
    // The newest record is the whole profile, so the empty record IS "this address has nothing":
    // the indexer and every app show nothing for it from then on (iOS 9638498).
    if (showClear && vm != null) {
        KachatProfileSaveSheet(
            title = stringResource(R.string.kn_clear_profile), confirmTitle = stringResource(R.string.kn_clear_profile),
            doneTitle = R.string.kn_done_profile_cleared, makeProfile = { Profile() },
            onClose = { showClear = false }, onSaved = onBack, vm = vm
        )
        return
    }

    @Composable
    fun invalidHandleNote() = FormFooter(stringResource(R.string.kn_handle_bad), colors.danger)

    KachatLiveForm(title = stringResource(R.string.edit_kachat_profile), onClose = onBack, scrollState = formScroll) {
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
                if (linktree.isNotEmpty()) FormClearButton { linktree = "" }
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
        FormSection(footer = { FormFooter(stringResource(R.string.kn_clear_footer)) }) {
            // Same gate as saving: a clear IS a save, of the empty record.
            FormButtonRow(stringResource(R.string.kn_clear_profile), enabled = loaded && KachatNamesService.profilesEnabled, destructive = true) { showClear = true }
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
    /** The "not enough funds" popup: the chatting address's QR to fund it (iOS 2b45d6e). It opens
     *  by itself once per sheet; after that the Fund button under the message opens it (iOS
     *  43d4bc0, IOS-084). */
    var showFunding by remember { mutableStateOf(false) }
    var fundingShownOnce by remember { mutableStateOf(false) }
    /** The last quote or save failed for lack of funds: the Fund button shows. */
    var shortOfFunds by remember { mutableStateOf(false) }
    /** Bumped to work the fee out again (iOS `Task { await quote() }`). */
    var quoteTick by remember { mutableIntStateOf(0) }
    val notEnoughFundsMessage = KaspaUnit.label(stringResource(R.string.kn_not_enough_funds_send_1_kas))

    /** The save can't be paid: the chatting address has no spendable coin, or too little for the
     *  fee - read from the error alone, an unreachable node is not "no funds" (iOS
     *  `isShortOfFunds`, 43d4bc0). */
    fun isShortOfFunds(e: Throwable): Boolean =
        e.message?.contains("insufficient", ignoreCase = true) == true

    /** Short of funds: the popup the first time, the Fund button after that (iOS 43d4bc0). */
    fun noteShortOfFunds() {
        shortOfFunds = true
        if (!fundingShownOnce) {
            fundingShownOnce = true
            showFunding = true
        }
    }

    LaunchedEffect(quoteTick) {
        val p = currentMakeProfile()
        profile = p
        try {
            fee = vm.actions.profileFee(p)
            shortOfFunds = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isShortOfFunds(e)) {
                quoteError = notEnoughFundsMessage
                noteShortOfFunds()
            } else {
                quoteError = context.kachatErrorText(e)
            }
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
                    if (isShortOfFunds(e)) {
                        sendError = notEnoughFundsMessage
                        noteShortOfFunds()
                    } else {
                        sendError = context.kachatErrorText(e)
                    }
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
                    qe != null -> FundingErrorFooter(qe, shortOfFunds) { showFunding = true }
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
            FundingErrorFooter(qe, shortOfFunds) { showFunding = true }
        }
        FormSection(footer = { sendError?.let { FundingErrorFooter(it, shortOfFunds) { showFunding = true } } }) {
            FormButtonRow(confirmTitle, enabled = f != null && profile != null && !sending && done == null, busy = sending) { authorize() }
        }
    }

    // Closing the finished-transaction sheet closes this sheet, and then the caller's (iOS
    // onDismiss: dismiss(); onSaved()).
    done?.let { KachatTxDoneSheet(it, onDismiss = { done = null; onClose(); onSaved() }, vm = vm) }

    // Closes by itself once any KAS has arrived; either way the fee is worked out again (iOS
    // 43d4bc0: ZeroBalanceFundingSheetView with its default dismissAtSompi, 1 sompi).
    if (showFunding) {
        val reQuote: () -> Unit = {
            showFunding = false
            quoteError = null
            sendError = null
            fee = null
            quoteTick++
        }
        LaunchedEffect(Unit) {
            // nothing else pushes a balance update while the popup is up (see
            // rememberZeroBalanceFundingGate): fetch now, then every 10 s
            while (true) {
                vm.wallet.refreshBalance()
                delay(10_000)
            }
        }
        // iOS onChange: a change of the balance, not the one it opens with
        LaunchedEffect(Unit) {
            vm.wallet.balance.drop(1).collect { balance ->
                if (vm.wallet.balanceKnown.value && balance >= FUNDED_SOMPI) reQuote()
            }
        }
        ZeroBalanceFundingDialog(
            walletAddress = vm.actions.myAddress,
            onDismiss = reQuote,
            title = notEnoughFundsMessage,
        )
    }
}

/** The balance at which the profile save's "not enough funds" popup closes itself: any KAS
 *  (iOS 43d4bc0). */
private const val FUNDED_SOMPI = 1L

/**
 * A profile save's error in red under its section and, while the last quote or save failed for
 * lack of funds, a Fund button that opens the funding popup again (iOS 43d4bc0 / a4d3402,
 * IOS-084: a `VStack` of the message and `Label("Fund", systemImage: "qrcode")`).
 */
@Composable
private fun FundingErrorFooter(text: String, showsFund: Boolean, onFund: () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = colors.danger)
        if (showsFund) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.clickable(onClick = onFund),
            ) {
                Icon(Icons.Default.QrCode, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.kn_fund), color = KaspaTeal, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** iOS `@AppStorage("kachat_profile_privacy_seen")`: the first save says profiles are public. */
private const val PRIVACY_SEEN_KEY = "kachat_profile_privacy_seen"
