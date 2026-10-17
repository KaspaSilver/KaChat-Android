package com.kachat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.kachat.app.ui.Screen
import com.kachat.app.ui.hubTitle
import com.kachat.app.ui.kaspaHubSections
import com.kachat.app.ui.tabIconPainter
import com.kachat.app.ui.tabIconModifier
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.ChatViewModel
import com.kachat.app.viewmodels.WalletViewModel

/**
 * The Kaspa Hub tab: a grid of everything that isn't in the dock, and the section you picked.
 *
 * Membership is computed, not configured - [kaspaHubSections] drops anything hidden and anything
 * that already has its own dock slot, so a feature is never in both places at once. Kaspa Websites
 * is always here: unlike iOS it has never been a dock tab on Android, so there is nothing for it to
 * be deduplicated against.
 *
 * Sections render INLINE rather than navigating to their own routes, matching iOS. Navigating away
 * would move the dock's selection off Kaspa Hub onto a tab that isn't even in the dock, leaving
 * nothing highlighted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KaspaHubScreen(
    navController: NavController,
    walletViewModel: WalletViewModel,
    chatViewModel: ChatViewModel,
) {
    val colors = LocalAppColors.current
    val dockRoutes by walletViewModel.dockTabs.collectAsState()
    val hubRoutes by walletViewModel.hubTabs.collectAsState()
    val hiddenTabs by walletViewModel.hiddenTabs.collectAsState()
    val childMode by walletViewModel.childModeEnabled.collectAsState()
    val reselect by walletViewModel.tabReselectSignal.collectAsState()

    // The same counts the dock shows, from the same stores. A tab's placement is the user's
    // arrangement, so moving KaPosts out of the dock and into here must not silently drop its
    // badge. Broadcasts shows its share of the notification feed; that deliberately overlaps
    // Profile's total, since the bell IS the whole feed and a tab badge is that tab's part.
    val badgeVm: com.kachat.app.viewmodels.NotificationCenterViewModel = hiltViewModel()
    val notifEntries by badgeVm.store.entries.collectAsState()
    val notifLastSeen by badgeVm.store.lastSeenAt.collectAsState()
    val kaPostsUnseen by badgeVm.kaPostsUnseen.unseenCount.collectAsState()
    fun badgeCount(screen: Screen): Int = when (screen) {
        Screen.Profile -> notifEntries.count { it.timestampMs > notifLastSeen }
        Screen.KaPosts -> kaPostsUnseen
        else -> 0
    }

    val sections = kaspaHubSections(dockRoutes, hubRoutes, hiddenTabs, childMode)
    // rememberSaveable, keyed by ROUTE (Screen is not Saveable): plain `remember` is discarded
    // the moment this destination leaves composition, which happens as soon as you open a
    // broadcast room - so backing out of the room landed on the Hub GRID rather than on the
    // Broadcasts list you opened the room from.
    var openSectionRoute by rememberSaveable { mutableStateOf<String?>(null) }
    val openSection = sections.firstOrNull { it.route == openSectionRoute }

    // Re-tapping the Kaspa Hub dock item steps back out to the grid - the same button that got you
    // in gets you out, so nothing competes with each section's own navigation.
    LaunchedEffect(reselect) {
        if (reselect.second == Screen.KaspaHub.route) {
            openSectionRoute = null
        }
    }
    // A section moved into the dock (or hidden) must not stay open here, or it is on screen
    // twice. `openSection` is already resolved against `sections`, so a route that has left the
    // list resolves to null on its own - this just clears the stale route behind it.
    LaunchedEffect(sections) {
        if (openSectionRoute != null && openSection == null) openSectionRoute = null
    }

    // System Back inside a section steps back out to the grid, as re-tapping the Kaspa Hub tab
    // does - never past it to the start destination. Registered before the section, so a
    // section's own BackHandler (an open sheet, a page inside it) still comes first.
    androidx.activity.compose.BackHandler(enabled = openSection != null) { openSectionRoute = null }
    // The grid runs on under the dock (LocalDockInset), as iOS's content runs on under its tab
    // bar; a section that does not pad for the dock yet is laid out above it instead.
    val dockInset = LocalDockInset.current
    // The sections that pad their own end for the dock run on under it too; the rest are laid
    // out above it.
    val runsUnderDock = openSection == null || openSection in setOf(
        Screen.KaPosts, Screen.Chats, Screen.Portfolio, Screen.KachatStats, Screen.KachatBots,
        Screen.KachatNames, Screen.AddressBook, Screen.KaspaWebsites, Screen.Chess,
        Screen.ColdStorage, Screen.Swap,
    )
    androidx.compose.foundation.layout.Box(
        androidx.compose.ui.Modifier.padding(bottom = if (runsUnderDock) 0.dp else dockInset)
    ) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalInHubSection provides (openSection != null),
        LocalDockInset provides if (runsUnderDock) dockInset else 0.dp,
    ) {
    when {
        openSection == Screen.KaspaWebsites -> KaspaAppsScreen(
            onBack = { openSectionRoute = null },
            onOpen = { app ->
                navController.navigate(
                    "in_app_browser?url=${java.net.URLEncoder.encode(app.url, "UTF-8")}" +
                        "&title=${java.net.URLEncoder.encode(app.name, "UTF-8")}"
                )
            }
        )
        openSection == Screen.Chats -> ChatsScreen(navController, walletViewModel, chatViewModel = chatViewModel)
        openSection == Screen.Portfolio -> PortfolioScreen(navController = navController)
        // The graph-scoped instance, same as the "cold_storage" route uses - so an account
        // opened from here reaches the detail and address screens with its addresses loaded.
        openSection == Screen.ColdStorage -> ColdStorageListScreen(
            navController = navController,
            viewModel = androidx.hilt.navigation.compose.hiltViewModel(
                remember(navController) { navController.getBackStackEntry(navController.graph.id) }
            )
        )
        // Never the one that opens a notification's post: MainShell moves to the KaPosts route
        // for that, and this copy is what it leaves behind (see handlesDeepLinks).
        openSection == Screen.KaPosts -> KaPostsScreen(navController, walletViewModel = walletViewModel, handlesDeepLinks = false)
        openSection == Screen.Swap -> SwapScreen(navController = navController)
        openSection == Screen.Chess -> ChessHomeScreen(navController, onBack = { openSectionRoute = null })
        openSection == Screen.KachatNames -> KachatMarketScreen(
            onBack = { openSectionRoute = null },
            onOpenChat = { address -> navController.navigate("chat/$address") }
        )
        openSection == Screen.KachatStats -> KaChatStatsScreen(onBack = { openSectionRoute = null })
        openSection == Screen.AddressBook -> AddressBookScreen(
            onBack = { openSectionRoute = null },
            onOpenChat = { address -> navController.navigate("chat/$address") },
            walletViewModel = walletViewModel,
        )
        openSection == Screen.KachatBots -> KaChatBotsScreen(
            onBack = { openSectionRoute = null },
            onOpenChat = { address -> navController.navigate("chat/$address") },
            chatViewModel = chatViewModel,
        )
        else -> HubGrid(
            sections = sections,
            onOpenSection = { openSectionRoute = it.route },
            onCustomize = { CustomizeDock.open() },
            colors = colors,
            badgeCount = { badgeCount(it) },
        )
    }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HubGrid(
    sections: List<Screen>,
    onOpenSection: (Screen) -> Unit,
    onCustomize: () -> Unit,
    colors: com.kachat.app.ui.theme.AppColors,
    /** Unseen items per destination - resolved by the caller, which owns the stores. */
    badgeCount: (Screen) -> Int = { 0 },
) {
    val dockInset = LocalDockInset.current
    Scaffold(
        containerColor = colors.background,
        // The grid runs on under the dock; it pads its own end.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = {
            MainPageHeader(
                title = Screen.KaspaHub.label,
                actions = {
                    // Straight to Customize Dock - the one screen that decides what is in here.
                    // iOS's slider.horizontal.3 on a glass disc (the header's actions slot is the
                    // glass), in the accent colour.
                    ToolbarIconButton(Icons.Outlined.Tune, "Customize Dock", onCustomize)
                },
            )
        }
    ) { padding ->
        // Three across, matching iOS. Every tile comes from `sections` - Kaspa Websites included,
        // now that it is a real Screen and can therefore be placed and reordered in Customize
        // Dock like the rest.
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize().padding(padding),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            // iOS's 16 sides and 8 top as CONTENT padding, not a padding around the grid: the
            // grid clips at its own edge, and a right-column badge sits 6 past its tile.
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp + dockInset)
        ) {
            items(sections, key = { it.route }) { screen ->
                val vector = hubTileIcon(screen)
                HubTile(
                    label = screen.hubTitle,
                    painter = if (vector != null) rememberVectorPainter(vector) else screen.tabIconPainter(),
                    // iOS EcosystemView: the Kaspa mark and the chess pieces 26 tall, the .kachat
                    // wordmark 30 (32 here, where the stand-in face runs narrower), and a 26pt
                    // SF Symbol - which a Material icon matches at 32, its glyph being inset
                    // in its box.
                    iconModifier = when {
                        screen == Screen.KachatNames -> screen.tabIconModifier(32.dp)
                        vector != null -> Modifier.size(32.dp)
                        else -> screen.tabIconModifier(26.dp)
                    },
                    colors = colors,
                    badgeCount = badgeCount(screen),
                    onClick = { onOpenSection(screen) }
                )
            }
        }
    }
}

/**
 * The tile's glyph, as iOS draws it: `AppTab.icon`'s SF Symbol, which in the grid is the OUTLINED
 * symbol (only the dock fills them). The dock keeps its own filled icons ([Screen.icon]); null
 * for the sections drawn with a mark of their own (the Kaspa logo, the chess pieces, the .kachat
 * wordmark - see tabIconPainter).
 */
private fun hubTileIcon(screen: Screen): androidx.compose.ui.graphics.vector.ImageVector? = when (screen) {
    Screen.Chats -> Icons.Outlined.Forum                 // bubble.left.and.bubble.right
    Screen.Portfolio -> Icons.Outlined.PieChart          // chart.pie
    Screen.ColdStorage -> Icons.Outlined.Shield          // lock.shield
    Screen.Swap -> Icons.Outlined.SwapHoriz              // arrow.left.arrow.right
    Screen.KaPosts -> Icons.Outlined.EditNote            // square.and.pencil
    Screen.Broadcasts -> Icons.Outlined.Sensors          // dot.radiowaves.left.and.right
    Screen.KaspaWebsites -> Icons.Outlined.Language      // globe
    Screen.KachatStats -> Icons.Outlined.BarChart        // chart.bar.xaxis
    Screen.AddressBook -> Icons.Outlined.Book            // book.closed
    Screen.KachatBots -> Icons.Outlined.Memory           // cpu
    else -> null
}

/**
 * One tile: a square whose size comes from the grid column, with the label laid OVER it.
 *
 * The overlay is the point - a longer name is a taller label, and letting the content size the
 * tile would make one square bigger than its neighbours. This way every tile is identical whatever
 * it is called. Matches iOS: `.regularMaterial` in an 18pt rounded square with a 0.8 white hairline
 * at 15%, the icon in a 30pt-tall slot, 10 above a caption-semibold label.
 */
@Composable
private fun HubTile(
    label: String,
    /** The section's icon - the Kaspa mark, the chess pieces, or its vector (see tabIconPainter). */
    painter: androidx.compose.ui.graphics.painter.Painter,
    /** The icon's box - wide for the chess pieces and the .kachat wordmark (tabIconModifier). */
    iconModifier: Modifier = Modifier.size(32.dp),
    colors: com.kachat.app.ui.theme.AppColors,
    onClick: () -> Unit,
    /** Unseen items this destination is holding. Zero draws nothing. */
    badgeCount: Int = 0,
) {
    val shape = RoundedCornerShape(18.dp)
    Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(colors.surface)
                .border(0.8.dp, Color.White.copy(alpha = 0.15f), shape)
                .clickable { onClick() }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // A fixed 30-tall slot (iOS `.frame(height: 30)`), so every label starts at the same
                // height whichever icon sits above it; a taller icon overflows it evenly.
                Box(Modifier.height(30.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painter,
                        contentDescription = null,
                        tint = KaspaTeal,
                        modifier = Modifier.wrapContentSize(unbounded = true).then(iconModifier)
                    )
                }
                androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
                Text(
                    label,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        if (badgeCount > 0) {
            // iOS anchors it on the tile's top-right corner and pushes it 6 out each way, so a
            // two- or three-character badge grows outward instead of over the icon. A Box with
            // a minimum SQUARE size, not a padded Text: padding alone makes a one-digit badge
            // wider than it is tall, which reads as an oval.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 6.dp, y = (-6).dp)
                    .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                    .clip(RoundedCornerShape(50))
                    .background(colors.danger)
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (badgeCount > 99) "99+" else badgeCount.toString(),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}
