package com.kachat.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.VerifiedUser
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.kachat.app.R
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.ui.tabIconPainter
import com.kachat.app.ui.theme.IosRoundedFontFamily
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import com.kachat.app.util.KaspaUnit
import com.kachat.app.services.kachatnames.nowMs

// ---------------------------------------------------------------------------------------------
// Kaspa Hub > .kachat - the marketplace for KaChat's own names (iOS b064468, KachatMarketView).
// ---------------------------------------------------------------------------------------------

/**
 * Kaspa Hub > .kachat: the marketplace for KaChat's own names - claim one, list it, buy one, peer
 * to peer and trustless (the name and the payment settle together on chain, no one holds either
 * in between).
 *
 * Where the registry isn't launched it is the live screen, empty, under "Coming soon" (iOS
 * 7227d69): search answers that registration isn't open, the tabs are the live pages with nothing
 * in them, and nothing reads or writes a registry. The placeholder pages (blank shapes, never
 * invented names or prices) remain only for a registry that is setting up.
 *
 * On testnet-10, and on mainnet since its launch (iOS ef6b21e), with the bundled registry manifest
 * verified, it is live
 * (KachatNamesLiveScreens.kt, iOS 5df42b4): search shows real availability and the price, Claim
 * registers, the tabs read the registry, registrations in flight show their progress, and a name
 * opens its live detail. [onOpenChat] opens a 1:1 chat (Message on a name's owner).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KachatMarketScreen(onBack: (() -> Unit)?, onOpenChat: (String) -> Unit = {}) {
    // Only where names are live: elsewhere the live model is never built, so it makes no network calls.
    val live: KachatLiveViewModel? = if (KachatLive.isEnabled) hiltViewModel() else null
    // Kept above the full-screen swaps below, so a name's detail returns to the same search and tab
    // (iOS pushes it on a NavigationStack).
    var page by remember { mutableIntStateOf(0) }
    var searchText by remember { mutableStateOf("") }
    var liveSheet by remember { mutableStateOf<KachatHubSheet?>(null) }
    if (live != null) {
        LaunchedEffect(Unit) { live.start() }
    }
    // A name a tapped notification pointed at (where names are live).
    // One name screen at a time (iOS b799091): the same name again leaves the open one as it is,
    // and another name replaces it (closed first, then opened) instead of piling up.
    var nameRoute by remember { mutableStateOf<String?>(null) }
    val routeScope = rememberCoroutineScope()
    val pendingName by KachatDeepLink.pendingName.collectAsState()
    // Taken once the screen is up (iOS's 0.4 s): a tap that arrives while it is up, after the tab
    // switch has landed, and a notification that opened this screen fresh (a cold start, or
    // .kachat not the open Kaspa Hub section), whose name is pushed once the screen has appeared
    // rather than in the same frame (iOS 04b0c2c).
    LaunchedEffect(pendingName) {
        if (pendingName == null || live == null) return@LaunchedEffect
        kotlinx.coroutines.delay(400)
        val name = KachatDeepLink.pendingName.value ?: return@LaunchedEffect
        KachatDeepLink.pendingName.value = null
        val open = nameRoute
        when {
            open == null -> nameRoute = name
            open == name -> Unit
            else -> {
                nameRoute = null
                routeScope.launch {
                    kotlinx.coroutines.delay(450)
                    nameRoute = name
                }
            }
        }
    }
    if (live != null) {
        nameRoute?.let { name ->
            KachatNameRouteScreen(name, onBack = { nameRoute = null }, onOpenChat = onOpenChat, vm = live)
            return
        }
    }
    // A listing opens over the market, and Buy / Make an Offer over the listing (iOS cd9e10c).
    var openListing by remember { mutableStateOf(false) }
    var listingSheet by remember { mutableStateOf<String?>(null) }
    if (live != null) {
        when (val sheet = liveSheet) {
            is KachatHubSheet.Detail -> {
                KachatLiveNameDetailScreen(sheet.info, onBack = { liveSheet = null }, onOpenChat = onOpenChat, vm = live)
                return
            }
            // a sheet over the market (iOS .sheet), not a full-screen swap
            is KachatHubSheet.Claim -> KachatClaimSheet(sheet.target, onClose = { liveSheet = null }, vm = live)
            null -> Unit
        }
    }
    when {
        listingSheet == "buy" -> { KachatBuyScreen(onClose = { listingSheet = null }); return }
        listingSheet == "offer" -> { KachatOfferScreen(onClose = { listingSheet = null }); return }
        openListing -> {
            KachatListingScreen(
                onBack = { openListing = false },
                onBuy = { listingSheet = "buy" },
                onOffer = { listingSheet = "offer" },
            )
            return
        }
    }
    if (onBack != null) BackHandler(onBack = onBack)
    val colors = LocalAppColors.current
    var showHowItWorks by remember { mutableStateOf(false) }
    // (smart-casts `live` to non-null where it is true)
    val isLive = live?.isLive == true
    // Pull to refresh on testnet only; mainnet has nothing to refresh.
    val pullState = rememberPullToRefreshState(enabled = { isLive })
    LaunchedEffect(pullState.isRefreshing) {
        if (pullState.isRefreshing) {
            live?.refresh()
            pullState.endRefresh()
        }
    }

    Scaffold(
        containerColor = colors.background,
        // Runs on under the dock, as iOS's content runs on under its tab bar.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = {
            // iOS's bar: the connection dot on glass leading, the chatting balance as the principal
            // item - the marketplace buys with the chatting address, like every other screen pays
            // (iOS 6e938ff) - and the claims button and "?" on one piece of glass trailing.
            HubSectionTopBar(onBack = onBack) {
                // names being claimed: shown only while there are some (iOS b219bb0)
                if (live != null) KachatClaimsButton(live)
                ToolbarIconButton(
                    Icons.Outlined.HelpOutline,
                    stringResource(R.string.km_how_it_works),
                    onClick = { showHowItWorks = true },
                    iconSize = HubHelpIconSize,
                )
            }
        }
    ) { padding ->
      Box(Modifier.fillMaxSize().padding(padding).then(if (isLive) Modifier.nestedScroll(pullState.nestedScrollConnection) else Modifier)) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = LocalDockInset.current + 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Hero: the logo and the testnet / setting-up status - no title or description, so
            // the search sits higher (iOS 27a4f39).
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // the wordmark, 56 tall (iOS KachatTabIcon.view(side: 56))
                com.kachat.app.ui.KachatWordmark(56.dp)
                if (isLive) {
                    KachatTestnetBadge()
                } else if (KachatLive.isEnabled && live?.upgrading == true) {
                    // the bundled manifest is for the previous registry: a calm "setting up", no
                    // error (iOS d2e0673)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        KachatTestnetBadge()
                        SettingUpPill()
                    }
                    Text(
                        stringResource(R.string.kn_registry_being_upgraded),
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                } else {
                    ComingSoonPill()
                    // Testnet, but the manifest did not verify: the mockup, and why.
                    val setupError = live?.setupError
                    if (live != null && live.ready == false && setupError != null) {
                        Text(
                            LocalContext.current.kachatErrorText(setupError),
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                    }
                }
            }
            // Search
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surface).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = colors.textSecondary)
                    TextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        placeholder = { Text(stringResource(R.string.km_find_a_name), color = colors.textSecondary) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrect = false),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                            cursorColor = KaspaTeal,
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Text(".kachat", color = colors.textSecondary, fontWeight = FontWeight.SemiBold)
                }
                val typed = searchText.trim().lowercase()
                if (typed.isNotEmpty() && isLive) {
                    KachatLiveSearchResult(
                        live, typed,
                        onOpen = { liveSheet = KachatHubSheet.Detail(it) },
                        onClaim = { liveSheet = KachatHubSheet.Claim(it) },
                    )
                } else if (typed.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surface).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("$typed.kachat", color = colors.textPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.km_registration_not_open), color = colors.textSecondary, fontSize = 12.sp)
                        }
                        Button(onClick = {}, enabled = false) { Text(stringResource(R.string.km_claim)) }
                    }
                }
            }
            // Registrations in flight: the claims button in the top bar (KachatClaimsButton, iOS b219bb0).
            HubUnderlineTabBar(
                // Names for sale, expired names anyone may claim, names in their grace period
                // (counting down to release), and everything that happens in the registry. Your own
                // names (and the offers you made) live in Profile > Your Domains (iOS 0765ce0,
                // eea52b2, cb3c27d; this order 73128b3).
                titles = listOf(
                    stringResource(R.string.km_marketplace), stringResource(R.string.kn_available),
                    stringResource(R.string.kn_status_expired), stringResource(R.string.km_activity)
                ),
                selectedIndex = page,
                onSelect = { page = it },
            )
            // Live, or not launched here (mainnet): the same pages - empty on mainnet, where `live`
            // is null (iOS 7227d69). The placeholder pages remain only for a testnet registry that
            // is setting up.
            if (isLive || !com.kachat.app.services.kachatnames.KachatNamesService.isLaunched) {
                when (page) {
                    0 -> KachatLiveMarketPage(live, onOpen = { liveSheet = KachatHubSheet.Detail(it) })
                    1 -> KachatLiveAvailablePage(
                        live,
                        onOpen = { liveSheet = KachatHubSheet.Detail(it) },
                        onClaim = { liveSheet = KachatHubSheet.Claim(it) },
                    )
                    2 -> KachatLiveExpiredPage(live, onOpen = { liveSheet = KachatHubSheet.Detail(it) })
                    else -> KachatLiveActivityPage(live)
                }
            } else {
                when (page) {
                    0 -> MarketPage(onOpenListing = { openListing = true })
                    1 -> AvailablePage()
                    2 -> ExpiredPage()
                    else -> ActivityPage()
                }
            }
        }
        if (isLive) PullToRefreshContainer(state = pullState, modifier = Modifier.align(Alignment.TopCenter))
      }
    }

    if (showHowItWorks) {
        ActionSheetContainer(title = stringResource(R.string.km_how_kachat_works), subtitle = null, onDismiss = { showHowItWorks = false }) {
            HowRow(KachatSymbols.AtBadgePlus, stringResource(R.string.km_claim), stringResource(R.string.km_claim_detail))
            HowRow(Icons.Outlined.Sell, stringResource(R.string.km_list), stringResource(R.string.km_list_detail))
            HowRow(Icons.Outlined.ShoppingCart, stringResource(R.string.km_buy), stringResource(R.string.km_buy_detail))
            HowRow(Icons.Outlined.PanTool, stringResource(R.string.kl_offer), KaspaUnit.label(stringResource(R.string.kl_offer_detail)))
            HowRow(Icons.Outlined.VerifiedUser, stringResource(R.string.km_trustless), stringResource(R.string.km_trustless_detail))
            Text(
                stringResource(
                    when {
                        // mainnet's names are real KAS (iOS ef6b21e)
                        isLive && com.kachat.app.services.kachatnames.KachatNamesService.networkName == "mainnet" -> R.string.kn_how_live_footer_mainnet
                        isLive -> R.string.kn_how_live_footer_testnet
                        else -> R.string.km_nothing_live
                    }
                ),
                color = LocalAppColors.current.textSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

/** iOS's "questionmark.circle" in a toolbar: a 22pt glyph, which Material's 20/24 circle reaches
 *  at 26. */
internal val HubHelpIconSize = 26.dp

/**
 * The bar of a Kaspa Hub section that iOS gives the main pages' toolbar with no large title
 * (.kachat, KaChat Stats, the Address Book): the connection dot on a glass disc leading, the
 * [principal] item (the balance, unless the section has its own) centred, and [actions] on one
 * piece of glass trailing. Inside the Hub there is no back arrow, as on iOS (the system Back
 * still closes the section); reached some other way with [onBack], it sits before the dot.
 */
@Composable
internal fun HubSectionTopBar(
    onBack: (() -> Unit)?,
    principal: @Composable () -> Unit = { BalanceTopBarLabel() },
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = LocalAppColors.current
    // The principal item placed as UIKit places it (see IosToolbarRow).
    IosToolbarRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        leading = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onBack != null && !LocalInHubSection.current) {
                    // iOS 26: the chevron in the label colour on its glass.
                    ToolbarGlassGroup {
                        ToolbarIconButton(Icons.Filled.ArrowBackIosNew, "Back", onBack, tint = colors.textPrimary, iconSize = 20.dp)
                    }
                }
                ConnectionDotButton(onClick = { ConnectionStatusOverlayState.open() })
            }
        },
        principal = principal,
        trailing = { ToolbarGlassGroup(content = actions) },
    )
}

/**
 * iOS's UnderlineTabBar: equal-width tabs, each label 15pt bold on one line - the selected one in
 * the accent colour, the rest at half strength - shrinking to fit down to 75% rather than being
 * cut ("Marketplace" stays whole), 12 above it, a 2.5pt accent underline 8 below it under the
 * selected tab, and a hairline under the whole bar.
 */
@Composable
internal fun HubUnderlineTabBar(
    titles: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            titles.forEachIndexed { index, title ->
                val isSelected = index == selectedIndex
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            role = androidx.compose.ui.semantics.Role.Tab,
                        ) { onSelect(index) }
                        .semantics { selected = isSelected },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    KachatFitText(
                        title,
                        color = if (isSelected) KaspaTeal else KaspaTeal.copy(alpha = 0.5f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        minScale = 0.75f,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.5.dp)
                            .background(if (isSelected) KaspaTeal else Color.Transparent)
                    )
                }
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = colors.divider)
    }
}

/**
 * Where a tapped `.kachat` name notification lands (iOS KachatDeepLink, beeedd4): the name, kept
 * until the `.kachat` screen is on screen to take it, so a cold start from the notification still
 * opens the name.
 */
object KachatDeepLink {
    val pendingName = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}

/** What the live hub shows over itself (iOS NavigationLink / .sheet): a name's detail, or the
 *  claim sheet. */
private sealed class KachatHubSheet {
    class Detail(val info: com.kachat.app.services.kachatnames.NameInfo) : KachatHubSheet()
    class Claim(val target: KachatClaimTarget) : KachatHubSheet()
}

/**
 * The name a notification pointed at (iOS KachatNameRouteView, b799091): its live detail once
 * looked up, or - when it was released, reclaimed or expired past grace since - the name as free
 * to claim, with its price and Claim (iOS eea52b2 `claimLookup`). A failed lookup offers Try Again.
 */
@Composable
private fun KachatNameRouteScreen(name: String, onBack: () -> Unit, onOpenChat: (String) -> Unit, vm: KachatLiveViewModel) {
    var found by remember(name) { mutableStateOf<KachatRouteFound?>(null) }
    var attempt by remember(name) { mutableIntStateOf(0) }
    var claimTarget by remember(name) { mutableStateOf<KachatClaimTarget?>(null) }
    LaunchedEffect(name, attempt) {
        found = null
        found = try {
            when (val l = vm.registry.claimLookup(name)) {
                is com.kachat.app.services.kachatnames.Lookup.Registered -> KachatRouteFound.Registered(l.info)
                is com.kachat.app.services.kachatnames.Lookup.Free -> KachatRouteFound.Free(l.gap)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            KachatRouteFound.Failed
        }
    }
    // a sheet over the name (iOS .sheet)
    claimTarget?.let { target ->
        KachatClaimSheet(target, onClose = { claimTarget = null }, vm = vm)
    }
    when (val f = found) {
        is KachatRouteFound.Registered -> KachatLiveNameDetailScreen(f.info, onBack = onBack, onOpenChat = onOpenChat, vm = vm)
        is KachatRouteFound.Free -> KachatFreeNameScreen(name, f.gap, onBack = onBack, onClaim = { claimTarget = it }, vm = vm)
        KachatRouteFound.Failed -> KachatNameLookupScreen(name, onBack = onBack, onRetry = { attempt++ })
        null -> KachatNameLookupScreen(name, onBack = onBack, onRetry = null)
    }
}

private sealed class KachatRouteFound {
    class Registered(val info: com.kachat.app.services.kachatnames.NameInfo) : KachatRouteFound()
    class Free(val gap: com.kachat.app.services.kachatnames.GapInfo?) : KachatRouteFound()
    object Failed : KachatRouteFound()
}

@Composable
private fun SettingUpPill() {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(KaspaTeal.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Hardware, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.kn_setting_up), color = KaspaTeal, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
private fun ComingSoonPill() {
    Text(
        stringResource(R.string.coming_soon),
        color = KaspaTeal,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(KaspaTeal.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

/** A redacted text-shaped block: where a real value will go, with nothing invented in it. */
@Composable
private fun Redacted(width: Int, height: Int = 14, color: Color = LocalAppColors.current.textSecondary.copy(alpha = 0.25f)) {
    Box(Modifier.size(width.dp, height.dp).clip(RoundedCornerShape(4.dp)).background(color))
}

@Composable
private fun SectionHeader(title: String, detail: String?) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(title, color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        if (detail != null) Text(detail, color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun MarketPage(onOpenListing: () -> Unit) {
    val colors = LocalAppColors.current
    Column(verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 4.dp)) {
        SectionHeader(stringResource(R.string.kn_for_sale), null)
        KachatNameGrid(List(4) { it }) {
            TilePlaceholder(onClick = onOpenListing)
        }
        OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(48.dp)) {
            Icon(Icons.Outlined.Sell, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.km_list_a_name), fontWeight = FontWeight.Bold)
        }
        Text(
            stringResource(R.string.km_listings_appear),
            color = colors.textSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** A name tile's shape, redacted: no invented name or price (iOS 27a4f39 `tilePlaceholder`). */
@Composable
private fun TilePlaceholder(onClick: (() -> Unit)? = null) {
    KachatNameTile(name = null, onClick = onClick) {
        Redacted(60, 15)
    }
}

@Composable
private fun ExpiredPage() {
    val colors = LocalAppColors.current
    Column(verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 4.dp)) {
        SectionHeader(stringResource(R.string.kn_status_expired), null)
        KachatNameGrid(List(2) { it }) {
            TilePlaceholder()
        }
        Text(
            stringResource(R.string.km_grace_appear),
            color = colors.textSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun AvailablePage() {
    val colors = LocalAppColors.current
    Column(verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 4.dp)) {
        SectionHeader(stringResource(R.string.kn_available), null)
        KachatNameGrid(List(2) { it }) {
            TilePlaceholder()
        }
        Text(
            stringResource(R.string.km_expired_appear),
            color = colors.textSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ActivityPage() {
    val colors = LocalAppColors.current
    val icons = listOf(Icons.Outlined.Sell, Icons.Outlined.ShoppingCart, Icons.Default.AlternateEmail, Icons.Default.SwapHoriz)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
        SectionHeader(stringResource(R.string.km_recent_activity), stringResource(R.string.kn_activity_all_detail))
        Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(colors.surface)) {
            icons.forEachIndexed { index, icon ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = KaspaTeal, modifier = Modifier.width(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Redacted(140)
                        Redacted(40, 10)
                    }
                    Redacted(50)
                }
                if (index < icons.lastIndex) HorizontalDivider(Modifier.padding(start = 56.dp), color = colors.background)
            }
        }
        Text(
            stringResource(R.string.km_activity_appears),
            color = colors.textSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun HowRow(icon: ImageVector, title: String, detail: String, tint: Color = KaspaTeal) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(detail, color = LocalAppColors.current.textSecondary, fontSize = 14.sp)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The .kachat names guide (iOS b000310, cdc5611, KachatSetupGuideView).
// ---------------------------------------------------------------------------------------------

/**
 * The .kachat names guide: claim a name, how a name works, make it your name, keep it. Opened
 * from Edit KaChat Profile and Profile > Help. About the name only - an avatar, banner and bio
 * are not part of a name (they are the address's profile, set in Edit KaChat Profile; iOS cdc5611).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KachatSetupGuideScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val colors = LocalAppColors.current
    var step by remember { mutableIntStateOf(0) }
    val last = 4
    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.kn_kachat_names), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
                navigationIcon = {
                    SheetBarButton(stringResource(R.string.kg_close), onClick = onClose, modifier = Modifier.padding(start = 12.dp))
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background)
            )
        },
        bottomBar = {
            KnsWizardBottomBar(
                onBack = if (step > 0) ({ step -= 1 }) else null,
                onNext = { if (step < last) step += 1 else onClose() },
                nextLabel = stringResource(if (step == last) R.string.done else R.string.kg_next),
                modifier = Modifier.navigationBarsPadding(),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // One dot per step before the last, filled up to the current one.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (dot in 0 until last) {
                    Box(
                        Modifier
                            .height(8.dp)
                            .width(if (dot == step) 22.dp else 8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (dot <= step) KaspaTeal else colors.surfaceVariant)
                    )
                }
            }
            @Composable
            fun header(icon: @Composable () -> Unit, title: Int, body: Int) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Box(Modifier.height(64.dp), contentAlignment = Alignment.Center) { icon() }
                    Text(stringResource(title), color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 22.sp, textAlign = TextAlign.Center)
                    Text(stringResource(body), color = colors.textSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
                }
            }
            @Composable
            fun bulletList(lines: List<Int>) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surface).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    lines.forEach { line ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.CheckCircle, null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(line), color = colors.textPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            when (step) {
                0 -> {
                    header({ com.kachat.app.ui.KachatWordmark(56.dp) }, R.string.kg_claim_title, R.string.kg_claim_body)
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surface).padding(12.dp)) {
                            Text("yourname", color = colors.textTertiary, modifier = Modifier.weight(1f))
                            Text(".kachat", color = colors.textSecondary, fontWeight = FontWeight.SemiBold)
                        }
                        Text(stringResource(R.string.kg_claim_hint), color = colors.textSecondary, fontSize = 12.sp)
                    }
                }
                1 -> {
                    header({ Icon(Icons.Default.Verified, null, tint = KaspaTeal, modifier = Modifier.size(48.dp)) }, R.string.kg_how_title, R.string.kg_how_body)
                    bulletList(listOf(R.string.kg_how_commit, R.string.kg_how_period, R.string.kg_how_miners))
                }
                2 -> {
                    header({ Icon(Icons.Default.Badge, null, tint = KaspaTeal, modifier = Modifier.size(46.dp)) }, R.string.kg_use_title, R.string.kg_use_body)
                    bulletList(listOf(R.string.kg_use_find, R.string.kg_use_pay, R.string.kg_use_link))
                }
                3 -> {
                    header({ Icon(Icons.Default.Autorenew, null, tint = KaspaTeal, modifier = Modifier.size(46.dp)) }, R.string.kg_keep_title, R.string.kg_keep_body)
                    bulletList(listOf(R.string.kg_keep_renew, R.string.kg_keep_grace, R.string.kg_keep_transfer))
                }
                else -> header({ Icon(Icons.Default.Verified, null, tint = KaspaTeal, modifier = Modifier.size(52.dp)) }, R.string.kg_finished_title, R.string.kg_finished_body)
            }
        }
    }
}


/**
 * One listing in the .kachat marketplace (iOS cd9e10c, KachatListingDetailView): the name and its
 * price, Buy Now / Make an Offer / Message the seller, the open offers and the history. UI only,
 * like the rest of the marketplace: every name, price, seller and offer is a blank shape and every
 * final action is disabled; Buy and Make an Offer still open their forms.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KachatListingScreen(onBack: () -> Unit, onBuy: () -> Unit, onOffer: () -> Unit) {
    BackHandler(onBack = onBack)
    val colors = LocalAppColors.current
    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.kl_listing), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
                navigationIcon = { SheetBarBackButton(onBack, Modifier.padding(start = 12.dp)) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background)
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(vertical = 16.dp).padding(bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(18.dp)).background(KaspaTeal), contentAlignment = Alignment.Center) {
                    Redacted(140, 22, Color.Black.copy(alpha = 0.25f))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.kl_price), color = colors.textSecondary, fontSize = 12.sp)
                        Redacted(80, 20)
                    }
                    Redacted(60, 10)
                }
                ComingSoonPill()
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onBuy, modifier = Modifier.weight(1f).height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, contentColor = Color.Black)) {
                    Icon(Icons.Outlined.ShoppingCart, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.kl_buy_now), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                OutlinedButton(onClick = onOffer, modifier = Modifier.weight(1f).height(48.dp)) {
                    Icon(Icons.Outlined.PanTool, null, tint = KaspaTeal, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.kl_make_offer), color = KaspaTeal, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader(stringResource(R.string.kl_seller), null)
                Row(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(KaspaTeal.copy(alpha = 0.25f)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Redacted(120)
                        Text(stringResource(R.string.kl_seller_detail), color = colors.textSecondary, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    // Opens a 1:1 chat with the seller once listings are real.
                    OutlinedButton(onClick = {}, enabled = false) { Text(stringResource(R.string.kl_message), fontSize = 14.sp) }
                }
            }
            ListingPlaceholderList(stringResource(R.string.kl_offers), stringResource(R.string.kl_offers_on_name), List(3) { Icons.Outlined.PanTool })
            ListingPlaceholderList(stringResource(R.string.kl_history), null, listOf(Icons.Outlined.Sell, Icons.Default.SwapHoriz, KachatSymbols.AtBadgePlus))
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Icons.Outlined.ShoppingCart to R.string.kl_note_buy, Icons.Outlined.Lock to R.string.kl_note_offer, Icons.Outlined.Forum to R.string.kl_note_message).forEach { (icon, text) ->
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(icon, null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(KaspaUnit.label(stringResource(text)), color = colors.textSecondary, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ListingPlaceholderList(title: String, detail: String?, icons: List<ImageVector>) {
    val colors = LocalAppColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(title, detail)
        Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(colors.surface)) {
            icons.forEachIndexed { index, icon ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = KaspaTeal, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) { Redacted(120); Redacted(50, 10) }
                    Redacted(50)
                }
                if (index < icons.lastIndex) HorizontalDivider(Modifier.padding(start = 50.dp), color = colors.divider, thickness = 0.5.dp)
            }
        }
    }
}

/** A Form-style sheet with Cancel top left, full height (iOS 6dd5578). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KachatFormSheet(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onClose)
    val colors = LocalAppColors.current
    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
                navigationIcon = { SheetBarButton(stringResource(R.string.cancel), onClick = onClose, modifier = Modifier.padding(start = 12.dp)) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background)
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun SummaryRow(title: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = LocalAppColors.current.textPrimary, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        Redacted(70)
    }
}

/** Buy at the listed price: what you pay, then one confirmation. Disabled until names launch. */
@Composable
private fun KachatBuyScreen(onClose: () -> Unit) {
    KachatFormSheet(stringResource(R.string.kl_buy_name), onClose) {
        SettingsSection(title = null) {
            SummaryRow(stringResource(R.string.kl_name)); SettingsDivider()
            SummaryRow(stringResource(R.string.kl_price)); SettingsDivider()
            SummaryRow(stringResource(R.string.kl_network_fee)); SettingsDivider()
            SummaryRow(stringResource(R.string.kl_total), bold = true)
        }
        SettingsFooter(stringResource(R.string.kl_buy_footer))
        Spacer(Modifier.height(16.dp))
        SettingsSection(title = null) {
            Text(
                stringResource(R.string.kl_confirm_purchase),
                color = LocalAppColors.current.textTertiary,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).wrapContentHeight()
            )
        }
        SettingsFooter(stringResource(R.string.kl_buying_opens))
    }
}

/** Make an offer: an amount, how long it stands, and what happens to the KAS meanwhile. The amount
 *  and expiry can be set so the form can be tried; sending is disabled until names launch. */
@Composable
private fun KachatOfferScreen(onClose: () -> Unit) {
    var amount by remember { mutableStateOf("") }
    var expiry by remember { mutableIntStateOf(1) }
    val colors = LocalAppColors.current
    KachatFormSheet(stringResource(R.string.kl_make_offer), onClose) {
        SettingsSection(title = null) {
            SummaryRow(stringResource(R.string.kl_name)); SettingsDivider()
            SummaryRow(stringResource(R.string.kl_listed_at))
        }
        Spacer(Modifier.height(16.dp))
        SettingsSection(title = stringResource(R.string.kl_your_offer)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = amount,
                    // Thousands commas while shown, stored plain (iOS 40c5258).
                    onValueChange = { amount = KaspaAmountGrouping.ungrouped(it, previous = amount).filter { c -> c.isDigit() || c == '.' } },
                    visualTransformation = KaspaAmountGrouping.visualTransformation,
                    placeholder = { Text("0", color = colors.textTertiary, fontSize = 20.sp) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = colors.textPrimary, unfocusedTextColor = colors.textPrimary, cursorColor = KaspaTeal,
                    ),
                    modifier = Modifier.weight(1f)
                )
                Text(KaspaUnit.symbol, color = colors.textSecondary)
            }
        }
        SettingsFooter(KaspaUnit.label(stringResource(R.string.kn_offer_locked_decline)))
        Spacer(Modifier.height(16.dp))
        SettingsSection(title = stringResource(R.string.kl_expires_after)) {
            // iOS's segmented control.
            Row(Modifier.fillMaxWidth().padding(8.dp).clip(RoundedCornerShape(8.dp)).background(colors.surfaceVariant).padding(2.dp)) {
                // up to 7 days, the app's cap on offers (KachatNamesActions.MAX_OFFER_DAYS, iOS 49c0baa)
                listOf(R.string.kl_1d, R.string.kl_3d, R.string.kl_7d).forEachIndexed { index, res ->
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(7.dp))
                            .background(if (index == expiry) colors.surface else Color.Transparent)
                            .clickable { expiry = index }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(stringResource(res), color = colors.textPrimary, fontSize = 13.sp, fontWeight = if (index == expiry) FontWeight.SemiBold else FontWeight.Normal) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        SettingsSection(title = null) {
            Text(
                stringResource(R.string.kl_send_offer),
                color = colors.textTertiary,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).wrapContentHeight()
            )
        }
        SettingsFooter(stringResource(R.string.kl_offers_open))
    }
}

// MARK: - An address's .kachat names

/**
 * The ".kachat" tab of every screen that shows an address's history - Manage Addresses, Cold
 * Storage and the chatting address: the .kachat names that address holds. It replaced the KNS
 * Domains tab (iOS b96d727, 5.2). With an [address] it is that address's own live list
 * ([KachatAddressLiveNamesList], iOS 881ada6) - on every network since iOS 7227d69, empty where
 * the registry isn't launched; without one, the "coming" note (kept, unreachable).
 */
@Composable
fun KachatAddressDomainsList(address: String? = null, onOpen: (com.kachat.app.services.kachatnames.NameInfo) -> Unit = {}) {
    if (address != null && com.kachat.app.services.kachatnames.KachatNamesService.isEnabled) {
        KachatAddressLiveNamesList(address, onOpen)
    } else {
        KachatAddressComingNote()
    }
}

/**
 * One address's .kachat names - Manage Addresses (spending), the chatting address and KasSigner
 * each show their own address's names in its .kachat tab, with the same cards and detail screen as
 * Your Domains > .kachat; [onOpen] opens the detail, which knows which of your addresses holds the
 * name (a KasSigner one is read-only). Where the registry isn't launched (mainnet) nothing is read:
 * the tab is its empty state (iOS 881ada6 `KachatAddressLiveNamesList`, 7227d69).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KachatAddressLiveNamesList(
    address: String,
    onOpen: (com.kachat.app.services.kachatnames.NameInfo) -> Unit,
    vm: KachatLiveViewModel? = if (com.kachat.app.services.kachatnames.KachatNamesService.isLaunched) hiltViewModel() else null,
) {
    val colors = LocalAppColors.current
    // `vm` is null for the screen's whole life on mainnet, so these calls are never conditional in practice.
    val revision = vm?.registry?.revision?.collectAsState()?.value
    val upgrading = vm?.service?.registryUpgrading?.collectAsState()?.value == true
    var names by remember { mutableStateOf<List<com.kachat.app.services.kachatnames.NameInfo>>(emptyList()) }
    var loaded by remember { mutableStateOf(vm == null) }
    LaunchedEffect(address, revision) {
        val key = com.kachat.app.services.kachatnames.KachatNamesRegistry.keyOf(address)
        if (vm == null || key == null) { loaded = true; return@LaunchedEffect }
        if (vm.registry.refreshedAt.value == null) vm.registry.refresh()
        // an expired name past grace isn't theirs any more: it's available to anyone (iOS eea52b2)
        names = try {
            vm.registry.heldNames(key)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        loaded = true
    }
    val pullState = rememberPullToRefreshState(enabled = { vm != null })
    LaunchedEffect(pullState.isRefreshing) {
        if (pullState.isRefreshing) {
            vm?.registry?.refresh()
            pullState.endRefresh()
        }
    }
    Box(Modifier.fillMaxSize().nestedScroll(pullState.nestedScrollConnection)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when {
                !loaded -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    com.kachat.app.ui.theme.IosActivityIndicator(color = KaspaTeal)
                }
                upgrading || names.isEmpty() -> Column(
                    Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        painter = com.kachat.app.ui.Screen.KachatNames.tabIconPainter(),
                        contentDescription = null,
                        tint = KaspaTeal,
                        modifier = Modifier.size(width = 120.dp, height = 40.dp),
                    )
                    Text(
                        stringResource(R.string.kachat_no_names_on_address),
                        color = colors.textPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        stringResource(R.string.kachat_names_this_address_owns),
                        color = colors.textSecondary,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                    )
                }
                else -> names.forEach { n ->
                    DomainNameCard(
                        title = n.display,
                        badge = kachatNameBadge(n, vm?.graceMs ?: 0L),
                        modifier = Modifier.clickable { onOpen(n) }
                    )
                }
            }
        }
        if (vm != null) PullToRefreshContainer(state = pullState, modifier = Modifier.align(Alignment.TopCenter))
    }
}

/** The tab before .kachat names were live on any network - kept, unreachable, as the switch-back. */
@Composable
private fun KachatAddressComingNote() {
    val colors = LocalAppColors.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    painter = com.kachat.app.ui.Screen.KachatNames.tabIconPainter(),
                    contentDescription = null,
                    tint = KaspaTeal,
                    modifier = Modifier.size(width = 120.dp, height = 40.dp),
                )
                Text(
                    stringResource(R.string.kachat_no_names_on_address),
                    color = colors.textPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.kachat_names_on_address_hint),
                    color = colors.textSecondary,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Kaspa Hub > KaChat Bots (iOS 82ab31d, 7dd508a, 64ce0c7, d576941, 5fcf755 - KaChatBotsView).
// ---------------------------------------------------------------------------------------------

/**
 * Kaspa Hub > KaChat Bots: one card per bot ([com.kachat.app.models.KaChatBot.all]); tapping a card
 * opens (or starts) the 1:1 chat with it, accepted and named after the bot. Each card carries the
 * chat's unread count too (the chat's circle above the chat list does). Bots run on mainnet only.
 * [onBack] is the Hub's way back to its grid; null as a dock tab.
 */
@Composable
fun KaChatBotsScreen(
    onBack: (() -> Unit)?,
    onOpenChat: (String) -> Unit,
    chatViewModel: com.kachat.app.viewmodels.ChatViewModel,
) {
    if (onBack != null) BackHandler(onBack = onBack)
    val colors = LocalAppColors.current
    val conversations by chatViewModel.conversations.collectAsState()
    fun unread(bot: com.kachat.app.models.KaChatBot): Int {
        if (bot.address.isEmpty()) return 0
        val key = bot.address.lowercase()
        return conversations.firstOrNull { it.contact.id.lowercase() == key }?.unreadCount ?: 0
    }

    Scaffold(
        containerColor = colors.background,
        // Runs on under the dock, as iOS's content runs on under its tab bar.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = { MainPageHeader(title = stringResource(R.string.bots_title), onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = LocalDockInset.current + 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            com.kachat.app.models.KaChatBot.all.forEach { bot ->
                KaChatBotCard(
                    bot = bot,
                    unread = unread(bot),
                    onOpen = { name -> chatViewModel.openBotChat(bot, name) { address -> onOpenChat(address) } },
                )
            }
            Text(
                stringResource(R.string.bots_fee_note),
                color = colors.textSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun KaChatBotCard(
    bot: com.kachat.app.models.KaChatBot,
    unread: Int,
    /** Called with the bot's (localized) name, which the new chat is named after. */
    onOpen: (String) -> Unit,
) {
    val colors = LocalAppColors.current
    val haptic = com.kachat.app.util.rememberHaptics()
    val unavailable = bot.unavailableReasonRes?.let { stringResource(it) }
    val name = stringResource(bot.nameRes)
    val hint = unavailable ?: stringResource(R.string.bots_opens_chat_hint)
    val unreadLabel = stringResource(R.string.bots_unread_count, unread)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            // The card's hint (iOS accessibilityHint): what a tap does, or why it can't.
            .clickable(enabled = unavailable == null, onClickLabel = hint) {
                haptic(com.kachat.app.util.IosHaptic.IMPACT_LIGHT)
                onOpen(name)
            }
            .alpha(if (unavailable == null) 1f else 0.6f)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier.size(52.dp).background(KaspaTeal.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(bot.icon, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(26.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text(unavailable ?: stringResource(bot.summaryRes), color = colors.textSecondary, fontSize = 15.sp)
        }
        if (unread > 0) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.Red)
                    .padding(horizontal = 7.dp, vertical = 3.dp)
                    .semantics { contentDescription = unreadLabel },
            ) {
                Text(
                    if (unread > 99) "99+" else unread.toString(),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (unavailable == null) {
            // iOS's chevron.right at footnote semibold: a 7 x 11 glyph flush with the card's
            // 16 padding. Material's arrow is 7/24 of its box, centred: 23 draws it that size,
            // and the 7-wide slot keeps its blank sides from pushing it inwards.
            Box(Modifier.size(width = 7.dp, height = 12.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.requiredSize(23.dp),
                )
            }
        }
    }
}
