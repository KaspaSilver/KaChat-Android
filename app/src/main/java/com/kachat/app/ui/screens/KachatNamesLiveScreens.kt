package com.kachat.app.ui.screens

import android.content.Context
import android.os.Build
import android.text.format.DateUtils
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.LabelOff
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
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

fun KaspaUnit.plain(sompi: Long): String {
    val whole = sompi / 100_000_000L
    val frac = sompi % 100_000_000L
    if (frac == 0L) return "$whole"
    // Locale.US: an amount keeps ASCII digits in every language, as iOS's String(format:) does.
    return "$whole." + String.format(Locale.US, "%08d", frac).trimEnd('0')
}

/** "+1.99 TKAS" / "-36.002 TKAS". */
fun KaspaUnit.signed(delta: Long): String = if (delta >= 0) "+${amount(delta)}" else "-${amount(-delta)}"

/** "12.5" or "12,5" (KAS) -> sompi; null for anything else or more than 8 decimals. */
fun KaspaUnit.parseSompi(text: String): Long? {
    val t = text.trim().replace(',', '.')
    if (t.isEmpty()) return null
    val parts = t.split('.')
    if (parts.size > 2) return null
    val w = parts[0]
    if (!w.all { it in '0'..'9' }) return null
    val whole = if (w.isEmpty()) 0L else w.toLongOrNull() ?: return null
    var frac = 0L
    if (parts.size == 2) {
        val f = parts[1]
        if (f.length > 8 || !f.all { it in '0'..'9' }) return null
        frac = f.padEnd(8, '0').toLong()
    }
    return try {
        Math.addExact(Math.multiplyExact(whole, 100_000_000L), frac)
    } catch (_: ArithmeticException) {
        null
    }
}

// MARK: - Shared pieces

object KachatLive {
    val isEnabled: Boolean get() = KachatNamesService.isEnabled

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
        "register" -> Icons.Default.AlternateEmail
        "transfer" -> Icons.AutoMirrored.Filled.CompareArrows
        "list" -> Icons.Default.Sell
        "delist" -> Icons.AutoMirrored.Filled.LabelOff
        "sale", "offer_accepted" -> Icons.Default.ShoppingCart
        "renew" -> Icons.Default.Refresh
        "release" -> Icons.AutoMirrored.Filled.Undo
        "reclaim" -> Icons.Default.Recycling
        else -> Icons.Default.PanTool
    }

    @StringRes
    fun eventTitle(op: String): Int = when (op) {
        "register" -> R.string.kn_ev_registered
        "transfer" -> R.string.kn_ev_transferred
        "list" -> R.string.kn_ev_listed
        "delist" -> R.string.kn_ev_delisted
        "sale" -> R.string.kn_ev_sold
        "offer_accepted", "offer_accept" -> R.string.kn_ev_offer_accepted
        "renew" -> R.string.kn_ev_renewed
        "release" -> R.string.kn_ev_released
        "reclaim" -> R.string.kn_ev_reclaimed
        "offer" -> R.string.kn_ev_offer_made
        "offer_withdraw" -> R.string.kn_ev_offer_withdrawn
        "offer_refund" -> R.string.kn_ev_offer_refunded
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

    /** iOS `.dateTime.year().month().day()` / `.formatted(date: .abbreviated)`: "Oct 2, 2026". */
    fun date(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

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
        is KachatNamesActions.ActionError.NotRegisterable -> when {
            m == "An expired name can't be listed. Renew it first." -> getString(R.string.kn_err_expired_list)
            m.endsWith(" is already registered.") -> getString(R.string.kn_err_already_registered, m.removeSuffix(" is already registered."))
            else -> m
        }
        else -> kachatPendingError(m)
    }
}

/** A registration's `lastError`: the driver's own two messages are localized (iOS 1ed6e57). */
fun Context.kachatPendingError(m: String): String = when (m) {
    "The commit never reached the chain." -> getString(R.string.kn_err_commit_never)
    "The commit is no longer on chain." -> getString(R.string.kn_err_commit_gone)
    else -> m
}

/** iOS `Haptics.success()`. */
private fun android.view.View.successHaptic() {
    performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
}

/** The device lock before any `.kachat` transaction is signed - the gate the seed phrase and
 *  private keys use (chat payments have none to copy), iOS `DeviceAuth.authenticate`. */
private fun Context.kachatAuthorize(onSuccess: () -> Unit) {
    authenticateWithDeviceCredential(title = getString(R.string.kn_auth_reason), onSuccess = onSuccess)
}

// MARK: - The view model

/**
 * The live screens' state and services (iOS `KachatHubModel`, plus the singletons iOS reaches as
 * `.shared`). Hub state lives here; the detail and the sheets read the registry and run actions
 * through [registry] and [actions]. Created on testnet only - mainnet never constructs it.
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
    var search by mutableStateOf<Search>(Search.Idle); private set
    var listings by mutableStateOf<List<NameInfo>>(emptyList()); private set
    var lapsed by mutableStateOf<List<NameInfo>>(emptyList()); private set
    var mine by mutableStateOf<List<NameInfo>>(emptyList()); private set
    var myOffers by mutableStateOf<List<OfferInfo>>(emptyList()); private set
    var activity by mutableStateOf<List<Event>>(emptyList()); private set
    var loadError by mutableStateOf<Throwable?>(null); private set
    var loaded by mutableStateOf(false); private set

    /** This wallet's x-only key. Read once here: it parses the account list, too slow per row. */
    var myKey by mutableStateOf(if (KachatNamesService.isEnabled) actions.myKey else null); private set

    val isLive: Boolean get() = KachatLive.isEnabled && ready == true
    val graceMs: Long get() = registry.graceMs

    init {
        // iOS: onReceive(registry.$revision.dropFirst()) - the registry changed, reload the pages.
        if (KachatNamesService.isEnabled) {
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ready = false
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
                if (myOffers.isNotEmpty()) actions.refreshVirtualDaa()
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
            when (val l = registry.lookup(typed)) {
                is Lookup.Registered -> Search.Registered(l.info)
                is Lookup.Free -> Search.Free(l.name, l.gap)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Search.Failed(e)
        }
    }

    fun pricePerYear(name: String): Long? = service.manifest.value?.params?.price(name.toByteArray(Charsets.UTF_8).size)

    /** The profile hero's `.kachat` part: your label, your profile's avatar, banner and bio
     *  sources (a social link each, iOS c124cb3) and its Linktree link. */
    data class Hero(val label: String?, val avatar: String?, val banner: String?, val bio: String?, val linktree: String?)

    /**
     * Testnet only: the `.kachat` label of [address] (primary name, else oldest active name) and
     * its address profile's sources and Linktree link, for the profile hero (iOS ContactsView
     * `loadKachatLabel`, 5df42b4 / ad32798 / 1322216 / c124cb3) - the record this wallet last wrote first,
     * else the one the source knows. Null on mainnet.
     */
    suspend fun hero(address: String): Hero? {
        if (!KachatNamesService.isEnabled) return null
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

// MARK: - Small building blocks

/** The app's glass card (iOS `kachatGlass`): the grouped surface, rounded. */
private fun Modifier.kachatGlass(colors: com.kachat.app.ui.theme.AppColors, radius: Int = 16): Modifier =
    this.clip(RoundedCornerShape(radius.dp)).background(colors.surface)

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
        Status.LAPSED -> colors.danger
    }
    val text = when (status) {
        Status.ACTIVE -> R.string.active
        Status.GRACE -> R.string.kn_status_expired
        Status.LAPSED -> R.string.kn_status_lapsed
    }
    Text(
        stringResource(text),
        color = color,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 3.dp)
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
@Composable
private fun KachatButton(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    prominent: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    large: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    val tint = if (destructive) colors.danger else KaspaTeal
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        contentPadding = if (large) PaddingValues(horizontal = 12.dp, vertical = 12.dp) else PaddingValues(horizontal = 14.dp, vertical = 6.dp),
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

/** "1 year" / "2 years". */
@Composable
private fun yearsText(years: Int): String =
    if (years == 1) stringResource(R.string.kn_one_year) else stringResource(R.string.kn_n_years, years)

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

// MARK: - One name in a list

/** One name in a list: the name, a line about it, and its price or status. */
@Composable
private fun KachatLiveNameRow(info: NameInfo, vm: KachatLiveViewModel, modifier: Modifier = Modifier, showPrice: Boolean = true, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    val status = info.status(vm.graceMs)
    Row(modifier.clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(KaspaTeal.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.AlternateEmail, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(info.display, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val who = if (vm.isMine(info.owner)) stringResource(R.string.kn_yours)
            else KachatNamesRegistry.address(info.owner)?.let { KachatNamesRegistry.shortAddress(it) }
            Text(
                listOfNotNull(who, "·", stringResource(R.string.kn_until, KachatLive.date(info.expiresAt))).joinToString(" "),
                color = colors.textSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        if (showPrice && info.isListed && status == Status.ACTIVE) {
            Text(KaspaUnit.amount(info.price), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        } else if (status != Status.ACTIVE) {
            KachatStatusPill(status)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(20.dp))
    }
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
                            Status.LAPSED -> Text(stringResource(R.string.kn_search_lapsed), color = colors.danger, fontSize = 12.sp)
                        }
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(20.dp))
                }
            } else SearchChecking(name)
            is KachatLiveViewModel.Search.Free -> if (s.name == name) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${s.name}.kachat", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        vm.pricePerYear(s.name)?.let { price ->
                            Text(stringResource(R.string.kn_available_per_year, KaspaUnit.amount(price)), color = colors.success, fontSize = 12.sp)
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.background,
    ) {
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
                icon = Icons.Default.Explore,
                prominent = true,
                large = true
            ) { browserUrl = explorerUrl }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.done), color = KaspaTeal, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
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

// MARK: - Hub: registrations in flight

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
        if (message != null) Text(message, color = colors.danger, fontSize = 12.sp)
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

/** Marketplace: names for sale, and Reclaimable (lapsed names, with Reclaim). */
@Composable
fun KachatLiveMarketPage(vm: KachatLiveViewModel, onOpen: (NameInfo) -> Unit, onReclaim: (NameInfo) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 4.dp)) {
        KachatLiveSectionHeader(stringResource(R.string.kn_for_sale), stringResource(R.string.kn_for_sale_detail))
        if (vm.listings.isEmpty()) {
            KachatLiveEmpty(if (vm.loaded) stringResource(R.string.kn_no_listings) else null)
        } else {
            KachatGlassList {
                vm.listings.forEachIndexed { index, n ->
                    KachatLiveNameRow(n, vm) { onOpen(n) }
                    if (index < vm.listings.lastIndex) KachatRowDivider(62)
                }
            }
        }

        KachatLiveSectionHeader(stringResource(R.string.kn_reclaimable), stringResource(R.string.kn_reclaimable_detail))
        if (vm.lapsed.isEmpty()) {
            KachatLiveEmpty(if (vm.loaded) stringResource(R.string.kn_nothing_to_reclaim) else null)
        } else {
            KachatGlassList {
                vm.lapsed.forEachIndexed { index, n ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KachatLiveNameRow(n, vm, modifier = Modifier.weight(1f), showPrice = false) { onOpen(n) }
                        KachatButton(stringResource(R.string.kn_reclaim), modifier = Modifier.padding(end = 12.dp)) { onReclaim(n) }
                    }
                    if (index < vm.lapsed.lastIndex) KachatRowDivider(62)
                }
            }
        }
    }
}

/** My Names, and My Offers (withdraw, refund once refundable). */
@Composable
fun KachatLiveMyNamesPage(vm: KachatLiveViewModel, onOpen: (NameInfo) -> Unit, onOfferAction: (KachatOfferAction) -> Unit) {
    val colors = LocalAppColors.current
    val source by vm.registry.source.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 4.dp)) {
        KachatLiveSectionHeader(stringResource(R.string.km_my_names), stringResource(R.string.kn_my_names_detail))
        if (vm.mine.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Default.AlternateEmail, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(40.dp))
                Text(stringResource(R.string.km_no_names), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text(stringResource(R.string.kn_search_above), color = colors.textSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
            }
        } else {
            KachatGlassList {
                vm.mine.forEachIndexed { index, n ->
                    KachatLiveNameRow(n, vm) { onOpen(n) }
                    if (index < vm.mine.lastIndex) KachatRowDivider(62)
                }
            }
        }

        KachatLiveSectionHeader(stringResource(R.string.kn_my_offers), stringResource(R.string.kn_my_offers_detail))
        if (vm.myOffers.isEmpty()) {
            KachatLiveEmpty(if (vm.loaded) stringResource(R.string.kn_no_open_offers) else null)
        } else {
            KachatGlassList {
                vm.myOffers.forEachIndexed { index, o ->
                    KachatOfferRow(o, vm, isBuyer = true, isOwner = false, onAction = onOfferAction)
                    if (index < vm.myOffers.lastIndex) KachatRowDivider(50)
                }
            }
        }
        if (source == KachatNamesRegistry.Source.Chain) {
            Text(stringResource(R.string.kn_offers_need_indexer), color = colors.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
        }
    }
}

/** Recent activity across the registry. */
@Composable
fun KachatLiveActivityPage(vm: KachatLiveViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
        KachatLiveSectionHeader(stringResource(R.string.km_recent_activity), stringResource(R.string.kn_activity_detail))
        if (vm.activity.isEmpty()) {
            KachatLiveEmpty(if (vm.loaded) stringResource(R.string.kn_nothing_yet) else null)
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
    enum class Kind { WITHDRAW, REFUND, ACCEPT }
}

@Composable
private fun KachatOfferRow(
    offer: OfferInfo,
    vm: KachatLiveViewModel,
    isBuyer: Boolean,
    isOwner: Boolean,
    onAction: (KachatOfferAction) -> Unit,
    name: NameInfo? = null,
) {
    val colors = LocalAppColors.current
    val virtualDaa by vm.actions.virtualDaa.collectAsState()
    val refundable = virtualDaa?.let { offer.refundable(it) } ?: false
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.PanTool, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            offer.name?.let { Text("$it.kachat", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
            val who = if (isBuyer) stringResource(R.string.kl_your_offer)
            else KachatNamesRegistry.address(offer.buyer)?.let { KachatNamesRegistry.shortAddress(it) }
            if (who != null) Text(who, color = colors.textSecondary, fontSize = 12.sp)
            if (refundable) Text(stringResource(R.string.kn_refundable_now), color = colors.warning, fontSize = 11.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text(KaspaUnit.amount(offer.amount), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        when {
            isBuyer -> Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreHoriz, contentDescription = null, tint = KaspaTeal)
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
            isOwner -> {
                Spacer(Modifier.width(8.dp))
                KachatButton(stringResource(R.string.accept), prominent = true) {
                    onAction(KachatOfferAction(KachatOfferAction.Kind.ACCEPT, offer, name))
                }
            }
            refundable -> {
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

    LaunchedEffect(operationKey) {
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
                val id = vm.actions.perform(op)
                txId = id
                view.successHaptic()
                onDone(id)
                done = KachatTxDone(id, doneTitle)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendError = context.kachatErrorText(e)
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
 * Claim a free name: 1 or 2 years; price x years to miners, the bond and the registry deposit
 * (both back on release), the commit (back at registration), network fees, total and what the
 * chatting address has. Claim starts the registration (commit, then the driver registers).
 */
@Composable
fun KachatClaimSheet(target: KachatClaimTarget, onClose: () -> Unit, onStarted: () -> Unit = {}, vm: KachatLiveViewModel = hiltViewModel()) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val view = LocalView.current
    val manifest by vm.service.manifest.collectAsState()
    var years by remember { mutableLongStateOf(1L) }
    var quote by remember { mutableStateOf<KachatNamesActions.Quote?>(null) }
    var quoteError by remember { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(false) }
    var startError by remember { mutableStateOf<String?>(null) }
    val maxYears = manifest?.params?.maxYears ?: 2L

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
        starting = true
        startError = null
        vm.launch {
            try {
                vm.actions.startRegistration(target.name, years)
                view.successHaptic()
                onStarted()
                onClose()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                startError = context.kachatErrorText(e)
            }
            starting = false
        }
    }

    KachatLiveForm(title = stringResource(R.string.kn_claim_name_title), onClose = onClose) {
        FormSection {
            LabeledRow(stringResource(R.string.kl_name), "${target.name}.kachat", bold = false)
            SettingsDivider()
            val count = maxOf(1, maxYears.toInt())
            KachatSegmented((1..count).map { yearsText(it) }, (years - 1).toInt()) { years = (it + 1).toLong() }
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
                    LabeledRow(stringResource(R.string.kn_price_to_miners), "${KaspaUnit.amount(q.price / maxOf(q.years, 1L))} × ${q.years}"); SettingsDivider()
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
            listOf(R.string.kn_claim_step1, R.string.kn_claim_step2, R.string.kn_claim_step3).forEachIndexed { index, res ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(KaspaTeal.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        Text("${index + 1}", color = KaspaTeal, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(res), color = colors.textPrimary, fontSize = 15.sp)
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

// MARK: - Name detail

private enum class KachatDetailSheet { BUY, OFFER, RENEW, LIST, DELIST, TRANSFER, RELEASE, RECLAIM }

/**
 * A registered name, live: who owns it, its status and expiry, its price, and what the person can
 * do with it - buy, offer or message the owner; or, for their own names, renew, list, transfer,
 * release and make it their primary name. Offers and history below (iOS `KachatLiveNameDetail`).
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
    val revision by vm.registry.revision.collectAsState()
    val source by vm.registry.source.collectAsState()
    var info by remember(initial.name) { mutableStateOf(initial) }
    var sheet by remember { mutableStateOf<KachatDetailSheet?>(null) }
    var offerAction by remember { mutableStateOf<KachatOfferAction?>(null) }
    var ownerLabel by remember { mutableStateOf<String?>(null) }
    var offers by remember { mutableStateOf<List<OfferInfo>>(emptyList()) }
    var history by remember { mutableStateOf<List<Event>>(emptyList()) }
    var gone by remember { mutableStateOf(false) }
    var confirmPrimary by remember { mutableStateOf(false) }
    var primaryWorking by remember { mutableStateOf(false) }
    var primaryMessage by remember { mutableStateOf<String?>(null) }
    var primaryDone by remember { mutableStateOf<KachatTxDone?>(null) }

    val mine = vm.isMine(info.owner)
    val status = info.status(vm.graceMs)
    val ownerAddress = KachatNamesRegistry.address(info.owner)

    LaunchedEffect(revision, initial.name) {
        try {
            when (val l = vm.registry.lookup(info.name)) {
                is Lookup.Registered -> { info = l.info; gone = false }
                is Lookup.Free -> gone = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        val owner = KachatNamesRegistry.address(info.owner)
        if (!vm.isMine(info.owner) && owner != null) {
            runCatching { vm.registry.identity(owner) }.getOrNull()?.let { ownerLabel = it.label }
        }
        offers = runCatching { vm.registry.offers(info.name) }.getOrNull() ?: emptyList()
        history = runCatching { vm.registry.history(info.name) }.getOrNull() ?: emptyList()
        if (offers.isNotEmpty()) vm.actions.refreshVirtualDaa()
    }

    // Sheets over the detail (full-screen swaps, Cancel top left).
    offerAction?.let { action ->
        KachatOfferActionSheet(action, onClose = { offerAction = null })
        return
    }
    sheet?.let { s ->
        val close = { sheet = null }
        when (s) {
            KachatDetailSheet.BUY -> KachatLiveBuySheet(info, close)
            KachatDetailSheet.OFFER -> KachatLiveOfferSheet(info.name, info, close)
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
            KachatDetailSheet.RECLAIM -> KachatReclaimSheet(info, close)
        }
        return
    }

    fun setPrimary() {
        context.kachatAuthorize {
            primaryWorking = true
            primaryMessage = null
            vm.launch {
                try {
                    var profile = Profile()
                    val address = vm.actions.myAddress
                    if (address != null) {
                        val own = vm.registry.ownProfile(address)?.profile
                        profile = own ?: runCatching { vm.registry.identity(address).profile }.getOrNull() ?: profile
                    }
                    val tx = vm.actions.saveProfile(profile.copy(primaryName = info.name))
                    primaryDone = KachatTxDone(tx, R.string.kn_done_primary_set)
                    view.successHaptic()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    primaryMessage = context.kachatErrorText(e)
                }
                primaryWorking = false
            }
        }
    }

    BackHandler(onBack = onBack)
    primaryDone?.let { KachatTxDoneSheet(it, onDismiss = { primaryDone = null }, vm = vm) }
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
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(if (info.isListed) R.string.kl_price else R.string.kn_not_for_sale), color = colors.textSecondary, fontSize = 12.sp)
                            if (info.isListed) Text(KaspaUnit.amount(info.price), color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            KachatStatusPill(status)
                            Text(stringResource(R.string.kn_expires_on, KachatLive.date(info.expiresAt)), color = colors.textSecondary, fontSize = 12.sp)
                        }
                    }
                    when {
                        status == Status.GRACE && mine -> Text(stringResource(R.string.kn_detail_grace_mine), color = colors.warning, fontSize = 13.sp)
                        status == Status.GRACE -> Text(stringResource(R.string.kn_detail_grace), color = colors.warning, fontSize = 13.sp)
                        status == Status.LAPSED -> Text(stringResource(R.string.kn_detail_lapsed), color = colors.danger, fontSize = 13.sp)
                    }
                }

                if (gone) {
                    Text(stringResource(R.string.kn_name_gone), color = colors.textSecondary, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 20.dp))
                } else {
                    // Actions
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val big = Modifier.fillMaxWidth()
                        if (mine) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                KachatButton(stringResource(R.string.kn_renew), Modifier.weight(1f), Icons.Default.Refresh, prominent = status != Status.ACTIVE, large = true) { sheet = KachatDetailSheet.RENEW }
                                KachatButton(
                                    stringResource(if (info.isListed) R.string.kn_change_price else R.string.kn_list_for_sale),
                                    Modifier.weight(1f), Icons.Default.Sell, enabled = status == Status.ACTIVE, large = true
                                ) { sheet = KachatDetailSheet.LIST }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                KachatButton(stringResource(R.string.portfolio_type_transfer), Modifier.weight(1f), Icons.AutoMirrored.Filled.CompareArrows, large = true) { sheet = KachatDetailSheet.TRANSFER }
                                if (info.isListed) {
                                    KachatButton(stringResource(R.string.kn_delist), Modifier.weight(1f), Icons.AutoMirrored.Filled.LabelOff, large = true) { sheet = KachatDetailSheet.DELIST }
                                } else {
                                    KachatButton(
                                        stringResource(R.string.set_as_primary), Modifier.weight(1f), Icons.Default.HowToReg,
                                        enabled = status == Status.ACTIVE && !primaryWorking, large = true
                                    ) { confirmPrimary = true }
                                }
                            }
                            if (info.isListed) {
                                KachatButton(
                                    stringResource(R.string.set_as_primary), big, Icons.Default.HowToReg,
                                    enabled = status == Status.ACTIVE && !primaryWorking, large = true
                                ) { confirmPrimary = true }
                            }
                            KachatButton(stringResource(R.string.kn_release_name), big, Icons.Default.Delete, destructive = true, large = true) { sheet = KachatDetailSheet.RELEASE }
                        } else if (status == Status.LAPSED) {
                            KachatButton(stringResource(R.string.kn_reclaim), big, Icons.Default.Recycling, prominent = true, large = true) { sheet = KachatDetailSheet.RECLAIM }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (info.isListed && status == Status.ACTIVE) {
                                    KachatButton(stringResource(R.string.kl_buy_now), Modifier.weight(1f), Icons.Default.ShoppingCart, prominent = true, large = true) { sheet = KachatDetailSheet.BUY }
                                }
                                KachatButton(stringResource(R.string.kl_make_offer), Modifier.weight(1f), Icons.Default.PanTool, large = true) { sheet = KachatDetailSheet.OFFER }
                            }
                        }
                    }
                    primaryMessage?.let {
                        Text(it, color = colors.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
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
                                if (mine) {
                                    Text(stringResource(R.string.kn_you), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                } else {
                                    ownerLabel?.let { Text("$it.kachat", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
                                }
                                ownerAddress?.let {
                                    SelectionContainer {
                                        Text(it, color = colors.textSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 2)
                                    }
                                }
                            }
                            if (!mine && ownerAddress != null) {
                                Spacer(Modifier.width(8.dp))
                                KachatButton(stringResource(R.string.kl_message), icon = Icons.Default.Forum) {
                                    vm.message(ownerAddress, onOpenChat)
                                }
                            }
                        }
                    }

                    // Offers
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        KachatLiveSectionHeader(stringResource(R.string.kl_offers), if (mine) stringResource(R.string.kn_accept_one) else null)
                        if (offers.isEmpty()) {
                            Box(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp).kachatGlass(colors).padding(vertical = 14.dp),
                                contentAlignment = Alignment.Center
                            ) { Text(stringResource(R.string.kn_no_open_offers), color = colors.textSecondary, fontSize = 15.sp) }
                        } else {
                            KachatGlassList {
                                offers.forEachIndexed { index, o ->
                                    KachatOfferRow(
                                        o, vm, isBuyer = vm.isMine(o.buyer), isOwner = mine && source?.isIndexer == true,
                                        onAction = { offerAction = it }, name = info
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

    if (confirmPrimary) {
        IosAlertDialog(
            onDismissRequest = { confirmPrimary = false },
            title = { Text(stringResource(R.string.kn_make_primary_title, info.display)) },
            text = { Text(stringResource(R.string.kn_make_primary_body)) },
            confirmButton = {
                TextButton(onClick = { confirmPrimary = false; setPrimary() }) {
                    Text(stringResource(R.string.set_as_primary), color = KaspaTeal, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmPrimary = false }) { Text(stringResource(R.string.cancel), color = KaspaTeal, fontWeight = FontWeight.SemiBold) }
            }
        )
    }
}

// MARK: - Sheets with inputs

@Composable
fun KachatLiveBuySheet(info: NameInfo, onClose: () -> Unit) {
    val soon = info.expiresAt - 30L * 86_400_000L < KachatNames.nowMs()
    KachatTxSheet(
        title = stringResource(R.string.kl_buy_name), confirmTitle = stringResource(R.string.kl_confirm_purchase),
        doneTitle = R.string.kn_done_bought,
        footer = stringResource(if (soon) R.string.kn_buy_soon else R.string.kn_buy_footer),
        rows = listOf(
            KachatTxRow(stringResource(R.string.kl_name), info.display),
            KachatTxRow(stringResource(R.string.kn_price_to_seller), KaspaUnit.amount(info.price)),
            KachatTxRow(stringResource(R.string.kn_expires), KachatLive.date(info.expiresAt)),
        ),
        operation = KachatNamesActions.Operation.Buy(info), operationKey = "buy-${KachatNames.hex(info.outpoint.txid)}",
        onClose = onClose
    )
}

@Composable
fun KachatLiveOfferSheet(name: String, info: NameInfo?, onClose: () -> Unit, vm: KachatLiveViewModel = hiltViewModel()) {
    var amountText by remember { mutableStateOf("") }
    var days by remember { mutableIntStateOf(3) }
    var virtualDaa by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { virtualDaa = runCatching { vm.service.currentVirtualDaaScore() }.getOrNull() }

    val amount = KaspaUnit.parseSompi(amountText)?.takeIf { it > 0 }
    val refundAfter = virtualDaa?.let { it + days.toLong() * 86_400L * KachatLive.DAA_PER_SECOND }
    val operation = if (amount != null && refundAfter != null) KachatNamesActions.Operation.Offer(name, amount, refundAfter, info) else null
    val belowListing = info != null && info.isListed && amount != null && info.price < amount

    val rows = buildList {
        add(KachatTxRow(stringResource(R.string.kl_name), "$name.kachat"))
        if (info != null && info.isListed) add(KachatTxRow(stringResource(R.string.kl_listed_at), KaspaUnit.amount(info.price)))
        if (info != null) add(KachatTxRow(stringResource(R.string.kn_expires), KachatLive.date(info.expiresAt)))
        if (amount != null) add(KachatTxRow(stringResource(R.string.kl_offer), KaspaUnit.amount(amount)))
    }
    val choices = listOf(1 to R.string.kl_1d, 3 to R.string.kl_3d, 7 to R.string.kl_7d, 30 to R.string.kl_30d)
    KachatTxSheet(
        title = stringResource(R.string.kl_make_offer), confirmTitle = stringResource(R.string.kl_send_offer),
        doneTitle = R.string.kn_done_offer_sent,
        footer = if (belowListing) stringResource(R.string.kn_offer_below_listing) else null,
        rows = rows,
        operation = operation, operationKey = "${amount ?: 0}-$days-${virtualDaa ?: 0}",
        onClose = onClose, vm = vm
    ) {
        FormSection(header = stringResource(R.string.kl_your_offer), footer = { FormFooter(KaspaUnit.label(stringResource(R.string.kn_offer_locked))) }) {
            AmountField(amountText) { amountText = it }
        }
        FormSection(header = stringResource(R.string.kn_refundable_after)) {
            KachatSegmented(choices.map { stringResource(it.second) }, choices.indexOfFirst { it.first == days }) { days = choices[it].first }
        }
    }
}

@Composable
fun KachatRenewSheet(info: NameInfo, onClose: () -> Unit, vm: KachatLiveViewModel = hiltViewModel()) {
    val manifest by vm.service.manifest.collectAsState()
    var years by remember { mutableLongStateOf(1L) }
    val maxYears = manifest?.params?.maxYears ?: 2L
    val perYear = manifest?.params?.renewPrice(info.name.toByteArray(Charsets.UTF_8).size) ?: 0L
    KachatTxSheet(
        title = stringResource(R.string.kn_renew), confirmTitle = stringResource(R.string.kn_renew),
        doneTitle = R.string.kn_ev_renewed,
        footer = stringResource(R.string.kn_renew_footer),
        rows = listOf(
            KachatTxRow(stringResource(R.string.kl_name), info.display),
            KachatTxRow(stringResource(R.string.kn_price_per_year), KaspaUnit.amount(perYear)),
            KachatTxRow(stringResource(R.string.kn_new_expiry), KachatLive.date(info.expiresAt + years * KachatNames.YEAR_MS)),
        ),
        operation = KachatNamesActions.Operation.Renew(info, years), operationKey = "renew-$years",
        onClose = onClose, vm = vm
    ) {
        FormSection {
            val count = maxOf(1, maxYears.toInt())
            KachatSegmented((1..count).map { yearsText(it) }, (years - 1).toInt()) { years = (it + 1).toLong() }
        }
    }
}

@Composable
fun KachatListSheet(info: NameInfo, onClose: () -> Unit) {
    var priceText by remember { mutableStateOf("") }
    val price = KaspaUnit.parseSompi(priceText)?.takeIf { it > 0 }
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

@Composable
fun KachatReclaimSheet(info: NameInfo, onClose: () -> Unit, vm: KachatLiveViewModel = hiltViewModel()) {
    val manifest by vm.service.manifest.collectAsState()
    KachatTxSheet(
        title = stringResource(R.string.kn_reclaim), confirmTitle = stringResource(R.string.kn_reclaim),
        doneTitle = R.string.kn_done_reclaimed,
        footer = stringResource(R.string.kn_reclaim_footer),
        rows = listOf(
            KachatTxRow(stringResource(R.string.kl_name), info.display),
            KachatTxRow(stringResource(R.string.kn_bond_to_last_owner), KaspaUnit.amount(manifest?.params?.bond ?: 0L)),
        ),
        operation = KachatNamesActions.Operation.Reclaim(info), operationKey = "reclaim-${KachatNames.hex(info.outpoint.txid)}",
        onClose = onClose, vm = vm
    )
}

// MARK: - Your Domains > .kachat

/** Your Domains > .kachat on testnet: the wallet's names (iOS `KachatLiveDomainsTab`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KachatLiveDomainsTab(walletAddress: String, onOpen: (NameInfo) -> Unit, vm: KachatLiveViewModel = hiltViewModel()) {
    val colors = LocalAppColors.current
    val revision by vm.registry.revision.collectAsState()
    var names by remember { mutableStateOf<List<NameInfo>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(revision, walletAddress) {
        val key = KachatNamesRegistry.keyOf(walletAddress)
        if (key == null) { loaded = true; return@LaunchedEffect }
        if (vm.registry.refreshedAt.value == null) vm.registry.refresh()
        names = try {
            vm.registry.names(key, includeInactive = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        loaded = true
    }

    val pullState = rememberPullToRefreshState()
    LaunchedEffect(pullState.isRefreshing) {
        if (pullState.isRefreshing) {
            vm.registry.refresh()
            pullState.endRefresh()
        }
    }
    val listed = stringResource(R.string.kn_ev_listed)
    val expired = stringResource(R.string.kn_status_expired)
    val lapsed = stringResource(R.string.kn_status_lapsed)
    Box(Modifier.fillMaxSize().nestedScroll(pullState.nestedScrollConnection)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when {
                !loaded -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) { IosActivityIndicator(color = KaspaTeal) }
                names.isEmpty() -> Column(
                    Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.AlternateEmail, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(44.dp))
                    Text(stringResource(R.string.km_no_names), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text(stringResource(R.string.kn_domains_claim_hint), color = colors.textSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
                }
                else -> names.forEach { n ->
                    val badge = when (n.status(vm.graceMs)) {
                        Status.ACTIVE -> if (n.isListed) listed else null
                        Status.GRACE -> expired
                        Status.LAPSED -> lapsed
                    }
                    DomainNameCard(title = n.display, badge = badge, modifier = Modifier.clickable { onOpen(n) })
                }
            }
        }
        PullToRefreshContainer(state = pullState, modifier = Modifier.align(Alignment.TopCenter))
    }
}

// MARK: - Edit .kachat Profile

/**
 * Where a source's lookup stands - the editor saves only a field whose lookup found what that
 * field shows, so what gets saved is what was reviewed (iOS `KachatSocialLookup`, 169f6a0 /
 * c124cb3).
 */
enum class KachatSocialLookup { NONE, LOOKING, FOUND, EMPTY, UNREACHABLE }

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
                        coil.compose.SubcomposeAsyncImage(
                            model = resolved?.banner,
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(8.dp)),
                            loading = { Box(Modifier.fillMaxSize().background(gradient)) },
                            error = { Box(Modifier.fillMaxSize().background(gradient)) }
                        )
                    }
                    SocialSource.Kind.BIO -> Text(resolved?.bio ?: "", color = colors.textPrimary, fontSize = 15.sp)
                }
                Text(stringResource(R.string.kn_social_from, name), color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            KachatSocialLookup.EMPTY -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.NoAccounts, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                val missing = when (kind) {
                    SocialSource.Kind.AVATAR -> R.string.kn_social_no_avatar
                    SocialSource.Kind.BANNER -> R.string.kn_social_no_banner
                    SocialSource.Kind.BIO -> R.string.kn_social_no_bio
                }
                Text(stringResource(missing, name), color = colors.textSecondary, fontSize = 13.sp)
            }
            KachatSocialLookup.UNREACHABLE -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.WifiOff, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
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
fun KachatLiveProfileEditorScreen(onBack: () -> Unit, vm: KachatLiveViewModel = hiltViewModel()) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val view = LocalView.current
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
    var saving by remember { mutableStateOf(false) }
    var confirmSave by remember { mutableStateOf(false) }
    var savedTx by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<KachatTxDone?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pickPrimary by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("kachat_prefs", Context.MODE_PRIVATE) }
    var privacySeen by remember { mutableStateOf(prefs.getBoolean(PRIVACY_SEEN_KEY, false)) }

    LaunchedEffect(Unit) {
        val address = vm.actions.myAddress
        if (address == null) { loaded = true; return@LaunchedEffect }
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
    // A field is saved only once its lookup found what it shows - what you reviewed.
    fun notReviewed(input: KachatSourceInput, lookup: KachatSocialLookup) = !input.isEmpty && lookup != KachatSocialLookup.FOUND
    val blocked = avatarIn.isBad(SocialSource.Kind.AVATAR) || bannerIn.isBad(SocialSource.Kind.BANNER) || bioIn.isBad(SocialSource.Kind.BIO) ||
        badLinktree || notReviewed(avatarIn, avatarLookup) || notReviewed(bannerIn, bannerLookup) || notReviewed(bioIn, bioLookup)

    fun profile(): Profile = Profile(
        avatar = avatarIn.source(SocialSource.Kind.AVATAR)?.link,
        banner = bannerIn.source(SocialSource.Kind.BANNER)?.link,
        bio = bioIn.source(SocialSource.Kind.BIO)?.link,
        linktree = Profile.linktreeLinkFromUsername(linktree),
        primaryName = primary.ifEmpty { null }
    ).sanitized()

    fun authorizeSave() {
        privacySeen = true
        prefs.edit().putBoolean(PRIVACY_SEEN_KEY, true).apply()
        context.kachatAuthorize {
            saving = true
            error = null
            val p = profile()
            vm.launch {
                try {
                    val tx = vm.actions.saveProfile(p)
                    savedTx = tx
                    done = KachatTxDone(tx, R.string.kn_done_profile_saved)
                    view.successHaptic()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = context.kachatErrorText(e)
                }
                saving = false
            }
        }
    }

    @Composable
    fun invalidHandleNote() = FormFooter(stringResource(R.string.kn_handle_bad), colors.danger)

    KachatLiveForm(title = stringResource(R.string.edit_kachat_profile), onClose = onBack, finished = savedTx != null) {
        FormSection(footer = { FormFooter(stringResource(R.string.kn_profile_pieces_footer)) }) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.ContactPage, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.kn_profile_belongs), color = colors.textPrimary, fontSize = 15.sp)
            }
        }
        FormSection(header = stringResource(R.string.avatar), footer = if (avatarIn.isBad(SocialSource.Kind.AVATAR)) ({ invalidHandleNote() }) else null) {
            KachatSourceField(avatarIn, { avatarIn = it }, SocialSource.Kind.AVATAR, avatarLookup, { avatarLookup = it }, vm.social)
        }
        FormSection(header = stringResource(R.string.banner), footer = if (bannerIn.isBad(SocialSource.Kind.BANNER)) ({ invalidHandleNote() }) else null) {
            KachatSourceField(bannerIn, { bannerIn = it }, SocialSource.Kind.BANNER, bannerLookup, { bannerLookup = it }, vm.social)
        }
        FormSection(header = stringResource(R.string.bio), footer = if (bioIn.isBad(SocialSource.Kind.BIO)) ({ invalidHandleNote() }) else null) {
            KachatSourceField(bioIn, { bioIn = it }, SocialSource.Kind.BIO, bioLookup, { bioLookup = it }, vm.social)
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
        FormSection(header = stringResource(R.string.kachat_name_section), footer = { FormFooter(stringResource(R.string.kn_primary_footer)) }) {
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
        FormSection(footer = {
            val tx = savedTx
            val err = error
            when {
                tx != null -> FormFooter(stringResource(R.string.kn_saved_tx, tx.take(16) + "..."), colors.success)
                err != null -> FormFooter(err, colors.danger)
                else -> FormFooter(stringResource(R.string.kn_save_footer))
            }
        }) {
            FormButtonRow(stringResource(R.string.kn_save_profile), enabled = !saving && loaded && !blocked, busy = saving) { confirmSave = true }
        }
    }

    // Closing the finished-transaction sheet closes the editor (iOS onDismiss: dismiss()).
    done?.let { KachatTxDoneSheet(it, onDismiss = { done = null; onBack() }, vm = vm) }

    if (confirmSave) {
        IosAlertDialog(
            onDismissRequest = { confirmSave = false },
            title = { Text(stringResource(R.string.kn_save_title)) },
            text = { Text(stringResource(if (privacySeen) R.string.kn_save_body else R.string.kn_save_body_first)) },
            confirmButton = {
                TextButton(onClick = { confirmSave = false; authorizeSave() }) {
                    Text(stringResource(R.string.save), color = KaspaTeal, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSave = false }) { Text(stringResource(R.string.cancel), color = KaspaTeal, fontWeight = FontWeight.SemiBold) }
            }
        )
    }
}

/** iOS `@AppStorage("kachat_profile_privacy_seen")`: the first save says profiles are public. */
private const val PRIVACY_SEEN_KEY = "kachat_profile_privacy_seen"
