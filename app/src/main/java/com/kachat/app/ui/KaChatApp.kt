package com.kachat.app.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kachat.app.ui.screens.*
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.WalletViewModel
import com.kachat.app.viewmodels.ChatViewModel
import kotlin.math.roundToInt
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.filled.CheckCircle

/**
 * Top-level navigation destinations.
 */
sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    /**
     * True for tabs drawn with the bundled Kaspa mark instead of a Material icon. [icon] stays a
     * real ImageVector so anything not taught about the drawable still renders something sensible
     * rather than nothing.
     */
    val usesKaspaLogo: Boolean get() = this is KaspaHub

    object Settings    : Screen("settings",     "Settings",     Icons.Default.Settings)
    object Chats       : Screen("chats",        "Chats",        Icons.Default.Forum)
    object Portfolio   : Screen("portfolio",    "Portfolio",    Icons.Default.PieChart)
    object Profile     : Screen("profile",      "Profile",      Icons.Default.AccountCircle)
    object Swap        : Screen("swap",         "Swap",         Icons.Default.SwapHoriz)
    // Labeled "Storage" (not "Cold Storage") and always in the default tab set, matching iOS's
    // AppTab.coldStorage — hideable like Portfolio/Swap via Settings > Customization > Menu, but
    // no longer a separate opt-in reached through Portfolio's old "Cold Storage Devices" row.
    object ColdStorage : Screen("cold_storage", "Storage",      Icons.Default.Security)
    object KaPosts     : Screen("kaposts",      "KaPosts",      Icons.Default.NoteAlt)
    object Broadcasts  : Screen("broadcasts",   "Broadcasts",   Icons.Default.Sensors)
    // A placeable tab like any other, matching iOS's AppTab.apps - it can sit in the dock or in
    // the Kaspa Hub, and it can be reordered in either. It used to be a hardcoded tile appended
    // to the Hub grid with no Screen behind it, which is exactly why Customize Dock could not
    // see it. Labeled "Websites" in the dock (the bar truncates hard); "Kaspa Websites" in full.
    object KaspaWebsites : Screen("kaspa_websites", "Websites", Icons.Default.Public)
    // Holds whatever of the above is turned on but not in the dock - see [kaspaHubSections].
    // Route stays "kaspa_hub" once shipped: it is persisted in saved dock arrangements.
    object KaspaHub    : Screen("kaspa_hub",    "Kaspa Hub",    Icons.Default.BubbleChart)
    // The old "+ More" pseudo-tab (opened Customize Dock from the dock itself) is gone —
    // Customize Dock is reached via Settings > Customization instead. The dock still caps at
    // MAX_DOCK_ITEMS, with over-cap KaPosts/Broadcasts riding the Chats-slot cycle.
}

/**
 * The name shown inside the Kaspa Hub grid and at the top of the section it opens. Differs from
 * [Screen.label] only where a dock label has to stay short - the bar truncates hard, so the full
 * names live here. Matches iOS's `AppTab.ecosystemTitle`.
 */
val Screen.hubTitle: String
    get() = when (this) {
        Screen.Swap -> "ChangeNOW Swap"
        Screen.KaspaWebsites -> "Kaspa Websites"
        else -> label
    }

/**
 * Tabs that always hold a dock slot and cannot be moved into Kaspa Hub.
 *
 * Kaspa Hub, because it is what HOLDS whatever is not in the dock - move it inside itself and
 * everything it holds becomes unreachable. Profile, because it is the way to Settings, the account
 * list and the wallet's own addresses, so it must never be a level deeper than the dock. Listed in
 * the order a fresh dock places them, so a missing one can be reinserted sensibly.
 */
val PINNED_DOCK_ROUTES = listOf(Screen.KaspaHub.route, Screen.Profile.route)

/** Tabs the user can place. Excludes the pinned two. */
val ASSIGNABLE_TAB_ROUTES = listOf(
    Screen.Chats.route, Screen.Portfolio.route, Screen.ColdStorage.route,
    Screen.Swap.route, Screen.KaPosts.route, Screen.Broadcasts.route,
    Screen.KaspaWebsites.route
)

/** Route strings for tabs that can never be hidden — see [resolveTabOrder]. */
val ALWAYS_VISIBLE_TAB_ROUTES = setOf(Screen.Chats.route, Screen.Profile.route)

// All top-level tabs, in the app's default order (matches iOS's AppTab.defaultOrder). Settings
// isn't a tab at all (matches iOS) — it's reached one tap in from Profile's gear icon instead,
// see ProfileScreen.
val bottomNavItems = listOf(
    Screen.Portfolio,
    Screen.ColdStorage,
    Screen.Chats,
    Screen.KaspaHub,
    Screen.Profile,
    Screen.Swap,
    Screen.KaPosts,
    Screen.Broadcasts,
    Screen.KaspaWebsites
)

/** The dock renders at most this many items (matches iOS's AppTab.maxDockItems). */
const val MAX_DOCK_ITEMS = 5

/**
 * Routes hard-hidden everywhere while Child Mode is on (Settings > Security > Child Mode) —
 * matches iOS's AppTab.isEnabled gate. [resolveTabOrder] below is the single choke point every
 * dock consumer flows through (the bar itself, the Chats-slot cycle, Customize Menu's preview),
 * so while it's on these tabs can't render in the dock NOR ride the Chats-slot cycle, regardless
 * of dock settings. Deliberately derived at render time only: the per-account stored dock prefs
 * (tab_order/hidden_tabs) are never rewritten with the masked state, so turning Child Mode off
 * restores exactly the arrangement the user had before.
 */
val CHILD_MODE_HIDDEN_ROUTES = setOf(
    Screen.Swap.route, Screen.KaPosts.route, Screen.Broadcasts.route,
    // A browser onto the open web - the one tab most obviously not for a child's phone.
    Screen.KaspaWebsites.route
)

/**
 * Maps persisted route strings (from AppSettingsRepository.tabOrder) back to [Screen] objects,
 * in that order. Any route no longer recognized (e.g. a tab removed in a future update) is
 * dropped, and any [Screen] missing from the persisted list (e.g. a tab added since the user
 * last reordered) is appended at the end — so neither a stale persisted value nor an app update
 * can leave a tab permanently missing or crash on an unknown route. [hiddenTabs] then filters out
 * anything the user unchecked in Settings > Customization > Menu (never applied to
 * [ALWAYS_VISIBLE_TAB_ROUTES]). [childMode] hard-hides [CHILD_MODE_HIDDEN_ROUTES] on top of
 * whatever the user's own dock settings say.
 */
@Deprecated("Superseded by resolveDock. Kept only so the one-time placement migration can read what a user could SEE under the old model.")
fun resolveTabOrder(routes: List<String>, hiddenTabs: Set<String>, childMode: Boolean = false): List<Screen> {
    val byRoute = bottomNavItems.associateBy { it.route }
    val resolved = routes.mapNotNull { byRoute[it] }
    val missing = bottomNavItems.filter { it !in resolved }
    val ordered = resolved + missing
    var visible = ordered.filter { it.route in ALWAYS_VISIBLE_TAB_ROUTES || it.route !in hiddenTabs }
    if (childMode) {
        visible = visible.filter { it.route !in CHILD_MODE_HIDDEN_ROUTES }
    }
    // Dock cap (matches iOS AppTab.visible). Anything enabled that doesn't fit simply isn't in
    // the dock - it is not lost, it is reached through Kaspa Hub. There is no longer a Chats-tab
    // cycle: re-tapping or holding Chats to reach KaPosts and Broadcasts was removed once the Hub
    // gave them a place of their own.
    if (visible.size > MAX_DOCK_ITEMS) {
        visible = visible.take(MAX_DOCK_ITEMS)
    }
    return visible
}

/**
 * The dock, from the user's PLACEMENT rather than from the first few of an order.
 *
 * That derivation is what let a tab silently disappear: nothing hid it, it was simply pushed past
 * the cap by the tabs ahead of it. A tab is now placed in the dock or in the Hub, always exactly
 * one. The pinned tabs are reinserted whatever the stored list says, so no saved - or hand-edited
 * - arrangement can leave the app without them.
 */
fun resolveDock(dockRoutes: List<String>, hiddenTabs: Set<String>, childMode: Boolean = false): List<Screen> {
    val byRoute = bottomNavItems.associateBy { it.route }
    val placed = dockRoutes.mapNotNull { byRoute[it] }
        .filter { it.route !in hiddenTabs || it.route in ALWAYS_VISIBLE_TAB_ROUTES }
        .filter { !(childMode && it.route in CHILD_MODE_HIDDEN_ROUTES) }
        .toMutableList()
    for (route in PINNED_DOCK_ROUTES) {
        val screen = byRoute[route] ?: continue
        if (screen !in placed) {
            val position = AppSettingsRepository.DEFAULT_DOCK_TABS.indexOf(route).takeIf { it >= 0 } ?: placed.size
            placed.add(minOf(position, placed.size), screen)
        }
    }
    return placed.distinct().take(MAX_DOCK_ITEMS)
}

/**
 * What the Kaspa Hub grid actually shows: its candidates, minus anything hidden, minus anything
 * that already has its own dock slot.
 *
 * The dock subtraction is the point - a feature sitting in the dock has no reason to also be a
 * tile one level deeper, and listing it twice would just make the grid look padded. Matches iOS's
 * `AppTab.ecosystemSections`.
 *
 * The Kaspa Websites tile is NOT here: unlike iOS it has never been a dock tab on Android (it is
 * a route reached from Profile), so it has nothing to be deduplicated against and the grid always
 * shows it.
 */
fun kaspaHubSections(
    dockRoutes: List<String>,
    hubRoutes: List<String>,
    hiddenTabs: Set<String>,
    childMode: Boolean = false,
): List<Screen> {
    val byRoute = bottomNavItems.associateBy { it.route }
    val inDock = resolveDock(dockRoutes, hiddenTabs, childMode).toSet()
    fun eligible(screen: Screen) = screen.route in ASSIGNABLE_TAB_ROUTES &&
        screen !in inDock &&
        screen.route !in hiddenTabs &&
        !(childMode && screen.route in CHILD_MODE_HIDDEN_ROUTES)

    val ordered = hubRoutes.mapNotNull { byRoute[it] }.filter(::eligible).toMutableList()
    // Anything absent from the stored list - a tab added by an update, or one demoted before that
    // list knew about it - is appended rather than lost, which is the same failure that dropped a
    // tab from the dock.
    for (screen in bottomNavItems) {
        if (eligible(screen) && screen !in ordered) ordered.add(screen)
    }
    return ordered
}

/**
 * Root composable: bottom nav + NavHost.
 * Wallet onboarding is shown instead when no wallet exists.
 */
@Composable
fun KaChatApp(
    walletViewModel: WalletViewModel = hiltViewModel(),
    pendingContactId: String? = null,
    onPendingContactHandled: () -> Unit = {},
    pendingChannelName: String? = null,
    onPendingChannelHandled: () -> Unit = {},
    pendingGroupId: String? = null,
    onPendingGroupHandled: () -> Unit = {},
    pendingOpenGroups: Boolean = false,
    onPendingOpenGroupsHandled: () -> Unit = {},
    pendingWalletActivityKind: String? = null,
    onPendingWalletActivityHandled: () -> Unit = {}
) {
    val isLoggedIn by walletViewModel.isLoggedIn.collectAsState()
    val mnemonic by walletViewModel.mnemonic.collectAsState()
    val startupResolved by walletViewModel.startupResolved.collectAsState()

    if (!startupResolved) {
        // Cold start, routing decision still loading (the async biometrics-for-login read that
        // gates auto-login into the last-used account). Compose NOTHING yet — composing the
        // Onboarding/accounts screen as a transient default flashed the account list for a few
        // frames before jumping into the account. The themed Surface behind this composable
        // keeps the window seamlessly on the background color, iOS-style; the wait is a few
        // milliseconds of DataStore read.
        Box(modifier = Modifier.fillMaxSize())
    } else if (!isLoggedIn || mnemonic != null) {
        OnboardingScreen(walletViewModel)
    } else {
        MainShell(
            walletViewModel = walletViewModel,
            pendingContactId = pendingContactId,
            onPendingContactHandled = onPendingContactHandled,
            pendingChannelName = pendingChannelName,
            onPendingChannelHandled = onPendingChannelHandled,
            pendingGroupId = pendingGroupId,
            onPendingGroupHandled = onPendingGroupHandled,
            pendingOpenGroups = pendingOpenGroups,
            onPendingOpenGroupsHandled = onPendingOpenGroupsHandled,
            pendingWalletActivityKind = pendingWalletActivityKind,
            onPendingWalletActivityHandled = onPendingWalletActivityHandled
        )
    }
}

/**
 * The ONE [ColdStorageViewModel] every cold-storage screen shares, scoped to the nav graph.
 *
 * They used to hang off `getBackStackEntry("cold_storage")`, which only exists when you entered
 * through the Storage tab. Reached from the Kaspa Hub - which renders the list inline under
 * "kaspa_hub" - that route never exists, so each screen got its OWN empty instance: the account
 * detail could not find the list's addresses, and an address's page had no row for itself, so it
 * fell back to showing the raw address as its title instead of "Address #N". The graph entry is
 * always on the back stack, so this is one instance no matter how you arrived.
 */
@Composable
private fun sharedColdStorageViewModel(
    navController: androidx.navigation.NavHostController
): com.kachat.app.viewmodels.ColdStorageViewModel =
    androidx.hilt.navigation.compose.hiltViewModel(
        androidx.compose.runtime.remember(navController) {
            navController.getBackStackEntry(navController.graph.id)
        }
    )

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainShell(
    walletViewModel: WalletViewModel = hiltViewModel(),
    chatViewModel: ChatViewModel = hiltViewModel(),
    pendingContactId: String? = null,
    onPendingContactHandled: () -> Unit = {},
    pendingChannelName: String? = null,
    onPendingChannelHandled: () -> Unit = {},
    pendingGroupId: String? = null,
    onPendingGroupHandled: () -> Unit = {},
    pendingOpenGroups: Boolean = false,
    onPendingOpenGroupsHandled: () -> Unit = {},
    pendingWalletActivityKind: String? = null,
    onPendingWalletActivityHandled: () -> Unit = {}
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    // The broadcasts list isn't one of the four real tabs, but it's still a top-level browsing
    // screen (like Chats) rather than a "pushed" detail screen — the bottom nav should stay put
    // and stay functional there, not disappear the way it does for chat/chat_info/etc. A broadcast
    // room itself gets the same treatment (unlike a 1:1 chat thread) — it's still just browsing/
    // participating in a public room, one tap away from Chats/Profile, not a private conversation
    // you'd want to maximize screen space for.
    val onTabRoute = currentDestination?.hierarchy?.any { dest ->
        bottomNavItems.any { it.route == dest.route } ||
            dest.route == Screen.KaPosts.route ||
            dest.route == "broadcasts" || dest.route == "broadcast_channel/{channelName}" ||
            // Cold Storage keeps its dock the whole way down, the way iOS's tab bar does: an
            // account and one of its addresses are still browsing your own holdings, not a
            // "pushed" screen you want the full height for.
            dest.route == "cold_storage_detail/{accountId}" ||
            dest.route == "cold_storage_tx_history/{address}"
    } == true

    // Press-and-hold a tab, then drag to reorder — the persisted order (WalletViewModel.tabOrder)
    // is only written on drag end; localTabOrder is the live, possibly-mid-drag copy the Row
    // actually renders from, reconciled back to the persisted order whenever it changes and no
    // drag is in progress (so a fresh install / another device's order still applies normally).
    // Also reconciled on hiddenTabs changes, so toggling a tab in Settings > Customization > Menu
    // updates the bar immediately without needing to leave/reopen it.
    val persistedTabOrder by walletViewModel.tabOrder.collectAsState()
    val hiddenTabs by walletViewModel.hiddenTabs.collectAsState()
    val hideBottomBar by walletViewModel.hideBottomBar.collectAsState()
    // Child Mode strips Swap/KaPosts/Broadcasts from the dock AND the Chats-slot cycle - derived
    // fresh on every render, never baked into the stored dock prefs (see CHILD_MODE_HIDDEN_ROUTES).
    val childModeEnabled by walletViewModel.childModeEnabled.collectAsState()
    val dockRoutes by walletViewModel.dockTabs.collectAsState()
    val localTabOrder = resolveDock(dockRoutes, hiddenTabs, childModeEnabled)
    val currentTopRoute = currentDestination?.route

    // Blocking progress for the account's first full sync, which the setup wizard deferred.
    // Escapable on purpose: a sync can stall on a slow indexer, and a dialog with no way out
    // would be worse than an incomplete chat list.
    val initialSyncPhase by chatViewModel.initialSyncPhase.collectAsState()
    if (initialSyncPhase != null) {
        val finished = initialSyncPhase == ChatViewModel.InitialSyncPhase.Finished
        var canSkip by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(20_000); canSkip = true }
        Dialog(
            onDismissRequest = { if (finished) chatViewModel.clearInitialSyncPhase() },
            properties = DialogProperties(dismissOnBackPress = finished, dismissOnClickOutside = false)
        ) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(LocalAppColors.current.surface)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (finished) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("You're all set", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("Your chats and history are up to date.", color = LocalAppColors.current.textSecondary, fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { chatViewModel.clearInitialSyncPhase() }) {
                        Text("Done", color = KaspaTeal, fontWeight = FontWeight.Bold)
                    }
                } else {
                    CircularProgressIndicator(color = KaspaTeal, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(14.dp))
                    Text("Setting up your account", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(initialSyncPhase?.label ?: "Starting", color = LocalAppColors.current.textSecondary, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Downloading everything this account has on chain. It only takes this long once.",
                        color = LocalAppColors.current.textSecondary,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                    if (canSkip) {
                        Spacer(Modifier.height(10.dp))
                        TextButton(onClick = { chatViewModel.clearInitialSyncPhase() }) {
                            Text("Continue anyway", color = KaspaTeal)
                        }
                    }
                }
            }
        }
    }
    // Child Mode just turned on (or the app landed on a now-hidden screen): snap home to Chats.
    // Covers the three hidden tab routes plus Broadcasts' pushed room screen.
    LaunchedEffect(childModeEnabled, currentTopRoute) {
        if (childModeEnabled && currentTopRoute != null && (
                currentTopRoute in CHILD_MODE_HIDDEN_ROUTES ||
                    currentTopRoute.startsWith("broadcast_channel/")
                )
        ) {
            navController.popBackStack(Screen.Chats.route, false)
        }
    }


    // Tapping a notification for a message/handshake/payment jumps straight to that
    // conversation, matching what a real chat app does — otherwise you'd land on the
    // chat list and have to go find it yourself.
    LaunchedEffect(pendingContactId) {
        if (pendingContactId != null) {
            navController.navigate("chat/$pendingContactId")
            onPendingContactHandled()
        }
    }

    // Same idea for a notify-enabled broadcast channel's new message notification. Child Mode:
    // a stray broadcast notification tap (e.g. one delivered before the mode was switched on, or
    // a remote push that raced the re-registration) must not route into the hidden feature -
    // land on the main Chats screen instead. Read race-free (suspend, not the StateFlow's `false`
    // initial value) so a cold-start tap can't slip through before DataStore loads.
    LaunchedEffect(pendingChannelName) {
        if (pendingChannelName != null) {
            // The name can come straight off a server push payload (see MainActivity's
            // applyFcmNotificationTarget), so it gets the same sanitizing the share-link path
            // uses - an unnormalized or hostile name would otherwise be pasted raw into the
            // route and land on nothing.
            val channel = com.kachat.app.ui.screens.KaChatLink.sanitizeChannelName(pendingChannelName)
            if (channel == null || walletViewModel.isChildModeEnabled()) {
                navController.popBackStack(Screen.Chats.route, false)
            } else {
                navController.navigate("broadcast_channel/$channel")
            }
            onPendingChannelHandled()
        }
    }

    // Same idea for a group chat notification.
    LaunchedEffect(pendingGroupId) {
        if (pendingGroupId != null) {
            navController.navigate("group_chat/$pendingGroupId")
            onPendingGroupHandled()
        }
    }

    // A group notification that names no local group (a "you were added" push, or a group push
    // this device could not resolve or ingest): the Group Chats list, which is where the new
    // group appears once the next sync lands. Better than a group_chat/<blinded id> route that
    // resolves to an empty, unusable thread.
    LaunchedEffect(pendingOpenGroups) {
        if (pendingOpenGroups) {
            ChatsTabIntake.pendingGroupsTab.value = true
            navController.popBackStack(Screen.Chats.route, false)
            onPendingOpenGroupsHandled()
        }
    }

    // Wallet address-activity receipt ("Received X KAS" on a spending or cold-storage address).
    // Cold storage has its own tab; spending addresses live in Manage Addresses, which is a real
    // route here (on iOS it is a sheet several screens deep, so iOS only fronts the app).
    LaunchedEffect(pendingWalletActivityKind) {
        val kind = pendingWalletActivityKind
        if (kind != null) {
            if (kind == com.kachat.app.services.NotificationHelper.KIND_COLD) {
                navController.navigate(Screen.ColdStorage.route) { launchSingleTop = true }
            } else {
                navController.navigate("manage_addresses") { launchSingleTop = true }
            }
            onPendingWalletActivityHandled()
        }
    }

    // Incoming system-share (text/link/image shared from another app — see MainActivity.
    // handleShareIntent): a direct-share pick carries the chosen conversation and opens the
    // in-place compose sheet directly; a plain "KaChat" share target lands on the Chats list,
    // whose banner asks the user to pick the chat, and the thread they open hands the share to
    // the same sheet.
    val pendingShare by com.kachat.app.services.ShareIntake.pending.collectAsState()
    LaunchedEffect(pendingShare) {
        val share = pendingShare ?: return@LaunchedEffect
        if (share.targetContactId != null) {
            // Direct-share pick: the conversation is already known, so go straight to the in-place
            // compose sheet (matching iOS's share extension) instead of dropping the user into the
            // chat with a silently pre-filled composer.
            com.kachat.app.services.ShareIntake.pending.value = null
            if (!share.isExpired()) {
                com.kachat.app.services.ShareIntake.compose.value = com.kachat.app.services.ShareCompose(
                    contactId = share.targetContactId,
                    text = share.text,
                    imageUris = share.imageUris
                )
            }
        } else {
            // Untargeted: surface the Chats list wherever the user currently is; whichever chat
            // the user opens next hands the share to the same compose sheet.
            navController.popBackStack(Screen.Chats.route, false)
        }
    }

    // The in-place share compose sheet, rendered above the whole shell so it survives whatever
    // screen is underneath (see ShareComposeSheet).
    val shareCompose by com.kachat.app.services.ShareIntake.compose.collectAsState()
    shareCompose?.let { compose ->
        ShareComposeSheet(
            share = compose,
            onOpenChat = { contactId -> navController.navigate("chat/$contactId") },
            onDismiss = { com.kachat.app.services.ShareIntake.compose.value = null },
            chatViewModel = chatViewModel
        )
    }

    // KaPosts share/deep links (kachat://kapost/<txid>, https://kachat.duckdns.org/post/…):
    // surface the KaPosts tab; KaPostsScreen itself consumes the pending txid and opens the
    // post's thread once visible.
    val pendingKaPostTxId by KaPostsDeepLink.pendingPostTxId.collectAsState()
    LaunchedEffect(pendingKaPostTxId) {
        if (pendingKaPostTxId != null) {
            // Child Mode: KaPosts links (universal https://.../post/<txid> and kachat://kapost/...)
            // no-op to the main Chats screen instead of opening the hidden feature.
            if (walletViewModel.isChildModeEnabled()) {
                KaPostsDeepLink.pendingPostTxId.value = null
                KaPostsDeepLink.pendingFocusReplyTxId.value = null
                navController.popBackStack(Screen.Chats.route, false)
            } else {
                navController.navigate(Screen.KaPosts.route) { launchSingleTop = true }
            }
        }
    }

    // Broadcast room share/deep links (kachat://broadcast/<channel>,
    // https://kachat.duckdns.org/broadcast/…) and taps on an in-app room-invite card. The name has
    // already been normalized and validated by BroadcastDeepLink.request; a room that isn't one of
    // the curated ones carries a join request that BroadcastChannelScreen consumes on open, so it
    // lands in the user's own channel list rather than disappearing when they navigate away.
    val pendingBroadcastLinkChannel by BroadcastDeepLink.pendingChannel.collectAsState()
    LaunchedEffect(pendingBroadcastLinkChannel) {
        val channel = pendingBroadcastLinkChannel ?: return@LaunchedEffect
        BroadcastDeepLink.consumePending()
        // Child Mode hides Broadcasts entirely - a link must not route into it, and must not
        // silently join a room either. Read race-free (suspend), same as the notification path.
        if (walletViewModel.isChildModeEnabled()) {
            BroadcastDeepLink.clearJoinRequests()
            navController.popBackStack(Screen.Chats.route, false)
        } else {
            navController.navigate("broadcast_channel/$channel")
        }
    }

    // Shows the Welcome Guide automatically the first time this shell renders after an account is
    // added — whether created or imported — see `WalletViewModel.pendingWelcomeGuide`. Always shown
    // on create/import, independent of the "show setup guides" setting.
    val pendingWelcomeGuide by walletViewModel.pendingWelcomeGuide.collectAsState()
    // Guards the interrupted-run re-presenter below against racing the auto-present:
    // markOnboardingWizardPending() persists BEFORE the navigation composes, so the marker
    // flow could re-fire that effect while the top route was still the old tab page - which
    // stacked a SECOND welcome_guide under the first and made a fresh import walk the whole
    // wizard twice. Once auto-presented, re-presenting is only ever a next-launch concern.
    var welcomeGuidePresentedThisSession by remember { mutableStateOf(false) }
    LaunchedEffect(pendingWelcomeGuide) {
        if (pendingWelcomeGuide) {
            welcomeGuidePresentedThisSession = true
            // First-run auto-present: the "Who will use KaChat?" step is now owed an answer -
            // persist the marker so killing the app mid-wizard re-presents the step at next
            // launch. Never downgrades an already-"chosen" device (see markUserTypePending), and
            // legacy installs that never auto-present are never marked (so never forced).
            walletViewModel.markUserTypePending()
            // Onboarding runs are unskippable end to end - a run only completes at Finish, and
            // this persisted marker re-presents an interrupted run at next launch.
            walletViewModel.markOnboardingWizardPending()
            navController.navigate("welcome_guide?onboarding=true")
            walletViewModel.consumePendingWelcomeGuide()
        }
    }

    // Interrupted first run: the auto-present trigger above is in-memory only and lost on
    // relaunch, so the persisted markers re-present the guide until it's genuinely finished -
    // jumping back to the Adult/Child choice when that's what is still owed, otherwise replaying
    // the whole onboarding run from the top (still unskippable).
    val userTypePendingMarker by walletViewModel.userTypePending.collectAsState()
    val onboardingWizardPendingMarker by walletViewModel.onboardingWizardPending.collectAsState()
    // A hold with no wizard behind it would strand the account unsynced forever. Once the guide
    // is neither pending nor on screen, lift it.
    LaunchedEffect(pendingWelcomeGuide, currentTopRoute) {
        if (!pendingWelcomeGuide && currentTopRoute?.startsWith("welcome_guide") != true) {
            chatViewModel.releaseOnboardingHoldIfIdle()
        }
    }

    LaunchedEffect(userTypePendingMarker, onboardingWizardPendingMarker, pendingWelcomeGuide, currentTopRoute) {
        if (pendingWelcomeGuide || welcomeGuidePresentedThisSession) return@LaunchedEffect
        if (currentTopRoute == null || currentTopRoute.startsWith("welcome_guide")) return@LaunchedEffect
        if (userTypePendingMarker == true) {
            navController.navigate("welcome_guide?startAtUserType=true&onboarding=true")
        } else if (onboardingWizardPendingMarker == true) {
            navController.navigate("welcome_guide?onboarding=true")
        }
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        bottomBar = {
            // Only show the floating tab bar on the top-level tab destinations —
            // "pushed" detail screens (chat thread, settings sub-screens, etc.) fill
            // the whole screen with their own Scaffold and must not have this
            // overlaid on top of them (it was blocking the chat input entirely).
            if (onTabRoute && !hideBottomBar) {
                // Red dot on the Profile tab while the notification bell (which lives on the
                // Profile screen) holds unread entries.
                val dockNotifVm: com.kachat.app.viewmodels.NotificationCenterViewModel = hiltViewModel()
                // The store is a singleton keyed per wallet internally; reload whenever the
                // active account changes so a switch never leaves the previous account's unread
                // dot (and entries) showing until something else happens to poke it.
                val dockNotifAddress by walletViewModel.address.collectAsState()
                LaunchedEffect(dockNotifAddress) { dockNotifVm.store.reloadIfNeeded() }
                val dockNotifEntries by dockNotifVm.store.entries.collectAsState()
                val dockNotifLastSeen by dockNotifVm.store.lastSeenAt.collectAsState()
                val dockNotifUnread = dockNotifEntries.count { it.timestampMs > dockNotifLastSeen }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // navigationBarsPadding() first so the 24dp visual margin sits above
                        // the system nav bar (gesture pill or 3-button bar) rather than being
                        // eaten by it — its height isn't the same on every device, so a fixed
                        // dp value alone left the tab bar sitting under/behind it on some phones.
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp, start = 24.dp, end = 24.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        modifier = Modifier
                            .height(80.dp)
                            .fillMaxWidth()
                            .background(LocalAppColors.current.surface, RoundedCornerShape(40.dp))
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        localTabOrder.forEach { screen ->
                            // Keyed by route (not position) so a tab's drag gesture/animation
                            // state stays attached to the same logical tab as the list reorders,
                            // rather than to whichever position happens to render it.
                            key(screen.route) {
                                // Standing ON this tab's own route, which is what the reselect
                                // gesture below keys off.
                                val onOwnRoute =
                                    currentDestination?.hierarchy?.any { it.route == screen.route } == true
                                // Cold Storage's account and address-history screens keep the
                                // dock, so the Storage tab stays lit while you are down there
                                // rather than the bar going blank - iOS's tab bar does. Lit only:
                                // a tap from down there still has to pop back up to the list, so
                                // this must not feed the reselect check.
                                val selected = onOwnRoute ||
                                    (screen.route == Screen.ColdStorage.route &&
                                        (currentTopRoute == "cold_storage_detail/{accountId}" ||
                                            currentTopRoute == "cold_storage_tx_history/{address}"))
                                Box(
                                    modifier = Modifier
                                        .height(64.dp)
                                        .weight(1f)
                                        .clip(RoundedCornerShape(32.dp))
                                        // Long-press then drag to reorder. Keyed on the route (stable
                                        // across reorders) so this gesture detector isn't restarted
                                        // mid-drag when localTabOrder itself changes — every state read
                                        // inside the callbacks below is a live Compose State read, so
                                        // there's no stale-closure risk from not re-keying on the list.
                                        .clickable {
                                            // Already there — let that screen know it was re-tapped (so it can
                                            // dismiss its own transient UI, e.g. a full-screen QR overlay) rather
                                            // than running the popBackStack/navigate logic below, which for a tab
                                            // with nothing "pushed" above it falls through to navigate() and
                                            // lands back on the graph's start destination (Chats) instead of
                                            // staying put.
                                            if (onOwnRoute) {
                                                walletViewModel.notifyTabReselected(screen.route)
                                                return@clickable
                                            }

                                            // Tapping back to the graph's start destination (Chats) from a
                                            // "pushed" screen like Broadcasts via navigate()+popUpTo alone is
                                            // silently a no-op in Navigation Compose — popBackStack to the
                                            // route directly first (it's already on the back stack) actually
                                            // pops it. Only fall back to navigate() when the tab isn't already
                                            // present on the stack (first visit to a peer tab).
                                            val poppedToExisting = navController.popBackStack(
                                                route = screen.route,
                                                inclusive = false,
                                                saveState = true
                                            )
                                            if (!poppedToExisting) {
                                                navController.navigate(screen.route) {
                                                    popUpTo(navController.graph.findStartDestination().id) {
                                                        saveState = true
                                                    }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box {
                                            Icon(
                                                painter = if (screen.usesKaspaLogo) {
                                                    painterResource(com.kachat.app.R.drawable.ic_kaspa_logo)
                                                } else {
                                                    rememberVectorPainter(screen.icon)
                                                },
                                                contentDescription = screen.label,
                                                tint = if (selected) KaspaTeal else LocalAppColors.current.textPrimary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                            if (screen == Screen.Profile && dockNotifUnread > 0) {
                                                Box(
                                                    modifier = Modifier
                                                        .align(Alignment.TopEnd)
                                                        .size(8.dp)
                                                        .clip(RoundedCornerShape(50))
                                                        .background(Color(0xFFE0245E)),
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = screen.label,
                                            color = if (selected) KaspaTeal else LocalAppColors.current.textPrimary,
                                            // Longer labels ("Broadcasts") don't fit at 10sp once there are
                                            // enough tabs that each weight(1f) slot narrows below their natural
                                            // width — shrink just those instead of letting them clip/wrap and
                                            // get cut off by the fixed-height Box.
                                            fontSize = if (screen.label.length > 9) 8.sp else 10.sp,
                                            maxLines = 1,
                                            softWrap = false,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
    ) { innerPadding ->
        // The green dot's half sheet, hosted once for the whole app rather than by each of the
        // eight headers that draw a dot - so it comes up OVER whatever you were reading.
        ConnectionStatusOverlayHost()

        // Only the bottom-tab destinations (Settings/Chats/Profile) sit inside this
        // shell's floating nav bar and need innerPadding reserved beneath them.
        // "Pushed" detail screens (chat thread, settings sub-screens, etc.) fill the
        // whole screen with their own Scaffold — applying innerPadding to the NavHost
        // as a whole left permanent dead space at the bottom of every one of those,
        // which became a visible gap once a Scaffold there also added imePadding().
        NavHost(
            navController = navController,
            startDestination = Screen.Chats.route,
            // NavHost's own default is a 700ms crossfade — noticeably sluggish for something
            // that happens on every single tab switch/screen push. A short, snappy fade reads
            // as instant without the jarring hard-cut of no animation at all.
            enterTransition = { fadeIn(animationSpec = tween(150)) },
            exitTransition = { fadeOut(animationSpec = tween(150)) },
            popEnterTransition = { fadeIn(animationSpec = tween(150)) },
            popExitTransition = { fadeOut(animationSpec = tween(150)) }
        ) {
            // Settings isn't a bottom-tab destination (matches iOS - reached one tap in from
            // Profile's gear icon), so it gets the normal NavHost-level fade like any other
            // pushed detail screen, not the instant tab-swap treatment below.
            composable(Screen.Settings.route) {
                Box(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
                    SettingsScreen(navController, walletViewModel = walletViewModel)
                }
            }
            // The four bottom-tab destinations get an instant swap, overriding the NavHost-level
            // 150ms fade above just for these routes — that fade is a good fit for pushed detail
            // screens, but a tab bar's own instant selected-tint feedback reads as sluggish when
            // paired with any fade on the content behind it, however short.
            composable(
                Screen.Chats.route,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
            ) {
                Box(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
                    ChatsScreen(navController, walletViewModel, chatViewModel = chatViewModel)
                }
            }
            composable(
                Screen.KaPosts.route,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
            ) {
                Box(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
                    KaPostsScreen(navController, walletViewModel = walletViewModel)
                }
            }
            composable(
                Screen.Portfolio.route,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
            ) {
                Box(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
                    PortfolioScreen(navController = navController)
                }
            }
            composable(
                Screen.Profile.route,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
            ) {
                Box(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
                    ProfileScreen(
                        viewModel = walletViewModel,
                        navController = navController
                    )
                }
            }
            composable(
                Screen.KaspaHub.route,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
            ) {
                Box(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
                    KaspaHubScreen(
                        navController = navController,
                        walletViewModel = walletViewModel,
                        chatViewModel = chatViewModel
                    )
                }
            }
            composable(
                Screen.Swap.route,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
            ) {
                Box(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
                    SwapScreen(navController = navController)
                }
            }

            composable(
                "portfolio_transactions?prefillType={prefillType}&prefillAmountKas={prefillAmountKas}&prefillFiatValue={prefillFiatValue}&prefillTimestamp={prefillTimestamp}&prefillNotes={prefillNotes}&prefillSwapId={prefillSwapId}",
                arguments = listOf(
                    navArgument("prefillType") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("prefillAmountKas") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("prefillFiatValue") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("prefillTimestamp") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("prefillNotes") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("prefillSwapId") { type = NavType.StringType; nullable = true; defaultValue = null }
                )
            ) { backStackEntry ->
                // Shares the Portfolio tab's own PortfolioViewModel instance rather than a fresh
                // one, so adding/editing/deleting a transaction here is immediately reflected in
                // the summary card and charts back on Portfolio — see PortfolioTransactionsScreen's
                // doc comment. That only works if the Portfolio tab's own back stack entry already
                // exists (getBackStackEntry throws otherwise) — true when reached from Portfolio's
                // own "View All" button, but NOT when reached from Swap's "Add to Portfolio" if the
                // user never opened the Portfolio tab this session, so fall back to a fresh instance.
                val parentEntry = remember(backStackEntry) {
                    try {
                        navController.getBackStackEntry(Screen.Portfolio.route)
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                }
                val args = backStackEntry.arguments
                PortfolioTransactionsScreen(
                    onBack = { navController.popBackStack() },
                    viewModel = if (parentEntry != null) hiltViewModel(parentEntry) else hiltViewModel(),
                    prefillType = args?.getString("prefillType"),
                    prefillAmountKas = args?.getString("prefillAmountKas")?.toDoubleOrNull(),
                    prefillFiatValue = args?.getString("prefillFiatValue")?.toDoubleOrNull(),
                    prefillTimestampMillis = args?.getString("prefillTimestamp")?.toLongOrNull(),
                    prefillNotes = args?.getString("prefillNotes")?.let { android.net.Uri.decode(it) },
                    prefillSwapId = args?.getString("prefillSwapId")
                )
            }

            // Full-screen KAS price / portfolio value charts, opened from the Portfolio squares.
            // Both share the Portfolio tab's PortfolioViewModel instance (same rationale as
            // portfolio_transactions above) so price history / selected range / summary are already
            // loaded and stay consistent - the squares always push these from the Portfolio tab.
            composable("portfolio_price_chart") { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    try { navController.getBackStackEntry(Screen.Portfolio.route) } catch (e: IllegalArgumentException) { null }
                }
                PortfolioPriceChartScreen(
                    navController = navController,
                    viewModel = if (parentEntry != null) hiltViewModel(parentEntry) else hiltViewModel()
                )
            }
            composable("portfolio_value_chart") { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    try { navController.getBackStackEntry(Screen.Portfolio.route) } catch (e: IllegalArgumentException) { null }
                }
                PortfolioValueChartScreen(
                    navController = navController,
                    viewModel = if (parentEntry != null) hiltViewModel(parentEntry) else hiltViewModel()
                )
            }

            composable("portfolio_hashrate_chart") { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    try { navController.getBackStackEntry(Screen.Portfolio.route) } catch (e: IllegalArgumentException) { null }
                }
                PortfolioHashrateChartScreen(
                    navController = navController,
                    viewModel = if (parentEntry != null) hiltViewModel(parentEntry) else hiltViewModel()
                )
            }

            composable("seed_phrase") {
                SeedPhraseScreen(
                    viewModel = walletViewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                Screen.ColdStorage.route,
                // One of the five real bottom tabs now (see bottomNavItems), so it gets the same
                // instant tab-swap treatment as Portfolio/Chats/Swap/Profile above, not the
                // NavHost-level fade.
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
            ) {
                // Reserve room beneath the screen's own FAB the same way every other tab screen
                // does, or the "Scan" button sits underneath/behind the floating nav bar instead
                // of above it.
                Box(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
                    ColdStorageListScreen(
                        navController = navController,
                        walletViewModel = walletViewModel,
                        viewModel = sharedColdStorageViewModel(navController)
                    )
                }
            }

            composable(
                "cold_storage_detail/{accountId}",
                arguments = listOf(navArgument("accountId") { type = NavType.StringType })
            ) { backStackEntry ->
                // Shares the one graph-scoped instance - see [sharedColdStorageViewModel]. A
                // fresh instance per destination meant deleteAccount() updated its own _accounts
                // flow only, leaving the list stale until something else recomposed it.
                val sharedViewModel = sharedColdStorageViewModel(navController)
                // The floating dock stays visible here (see onTabRoute), so reserve room for it
                // the same way the tab screens do rather than letting it sit over the content.
                Box(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
                    ColdStorageDetailScreen(
                        accountId = backStackEntry.arguments?.getString("accountId") ?: "",
                        navController = navController,
                        viewModel = sharedViewModel
                    )
                }
            }

            composable(
                "cold_storage_tx_history/{address}",
                arguments = listOf(navArgument("address") { type = NavType.StringType })
            ) { backStackEntry ->
                // The SAME instance the list and the account detail use - see
                // [sharedColdStorageViewModel]. It has to be: this screen reads the account's
                // address rows to find the one it is showing, which is what gives it a label
                // ("Address #7") instead of falling back to printing the raw address.
                // (Manage Addresses/Manage Addresses Hidden used to reuse this route too, before
                // getting their own "spending_address_detail/{index}" route below — its Send
                // button unconditionally opened Cold Storage's external-QR-signer flow, which
                // can't work for a spending address whose private key already lives in this wallet.)
                val sharedViewModel = sharedColdStorageViewModel(navController)
                // Dock visible here too, and this screen has its own bottom bar (the pager), so
                // the padding is what keeps the two from stacking on top of each other.
                Box(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
                    ColdStorageTxHistoryScreen(
                        address = backStackEntry.arguments?.getString("address") ?: "",
                        onBack = { navController.popBackStack() },
                        viewModel = sharedViewModel
                    )
                }
            }

            composable(
                "cold_storage_visibility/{accountId}",
                arguments = listOf(navArgument("accountId") { type = NavType.StringType })
            ) { backStackEntry ->
                // Shares ColdStorageDetailScreen's own ViewModel instance (the only screen this
                // one is ever reached from) rather than getting a fresh one scoped to this
                // destination — a fresh instance would need its own full gap-limit rescan just to
                // reconstruct a list the detail screen already has loaded, showing an empty state
                // the whole time that rescan is in flight. Sharing also means visibility edits
                // show on the detail list the instant this screen pops.
                ColdStorageAddressVisibilityScreen(
                    accountId = backStackEntry.arguments?.getString("accountId") ?: "",
                    viewModel = sharedColdStorageViewModel(navController),
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                "spending_address_detail/{index}",
                arguments = listOf(navArgument("index") { type = NavType.IntType })
            ) { backStackEntry ->
                // Deliberately its OWN route/screen rather than reusing
                // "cold_storage_tx_history/{address}" (which Manage Addresses used to route
                // through) — that screen's Send button unconditionally opens the external-QR-
                // signer flow for a watch-only key, which is broken for a spending address: its
                // private key already lives in this wallet, so it needs direct signing via
                // WalletViewModel.withdrawFromSpendingAddress instead. Index (not address) is the
                // route arg since that's what withdrawFromSpendingAddress signs by.
                SpendingAddressTxHistoryScreen(
                    index = backStackEntry.arguments?.getInt("index") ?: 0,
                    onBack = { navController.popBackStack() },
                    viewModel = walletViewModel
                )
            }

            // The chatting/identity address's "Manage Address" row (Profile > Chatting Address) -
            // field-for-field the same screen as spending_address_detail, just for the single
            // fixed identity address instead of a spending-chain index. Used to reuse
            // "cold_storage_tx_history/{address}" (labeled "Transaction History"), which had the
            // exact same watch-only-signer mismatch spending addresses had before getting their
            // own route above.
            composable("identity_address_detail") {
                IdentityAddressDetailScreen(
                    onBack = { navController.popBackStack() },
                    viewModel = walletViewModel
                )
            }

            composable("manage_addresses") {
                ManageAddressesScreen(
                    viewModel = walletViewModel,
                    onBack = { navController.popBackStack() },
                    onNavigateToTxHistory = { index -> navController.navigate("spending_address_detail/$index") },
                    onNavigateToHidden = { navController.navigate("manage_addresses_hidden") },
                    onNavigateToVisibility = { navController.navigate("manage_addresses_visibility") }
                )
            }

            composable("manage_addresses_visibility") {
                AddressVisibilityScreen(
                    viewModel = walletViewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                "manage_addresses_pick/{target}",
                arguments = listOf(navArgument("target") { type = NavType.StringType })
            ) { backStackEntry ->
                // Swap only ever sends the user here to pick where received KAS should land
                // (target == "to") - the old "pick which address to auto-send KAS from" flow
                // (target == "from") went with Swap's auto-send. It used to open Manage
                // Addresses, a MANAGEMENT screen: rename, hide, QR, consolidate, activate, none
                // of which is the question being asked, and one wrong tap there changes your
                // primary address. Matches iOS's Choose Address now.
                SwapAddressPickerScreen(
                    viewModel = walletViewModel,
                    onBack = { navController.popBackStack() },
                    onAddressPicked = { entry ->
                        val swapEntry = navController.getBackStackEntry(Screen.Swap.route)
                        swapEntry.savedStateHandle.set("picked_to_index", entry.index)
                        navController.popBackStack(Screen.Swap.route, false)
                    }
                )
            }

            composable(
                "manage_addresses_hidden_pick/{target}",
                arguments = listOf(navArgument("target") { type = NavType.StringType })
            ) { _ ->
                ManageAddressesHiddenScreen(
                    viewModel = walletViewModel,
                    onBack = { navController.popBackStack() },
                    onAddressPicked = { entry ->
                        // Swap only ever sends the user here to pick where received KAS should
                        // land - the old "pick which address to auto-send KAS from" flow was
                        // removed along with Swap's auto-send.
                        val swapEntry = navController.getBackStackEntry(Screen.Swap.route)
                        swapEntry.savedStateHandle.set("picked_to_index", entry.index)
                        navController.popBackStack(Screen.Swap.route, false)
                    }
                )
            }

            composable("manage_addresses_hidden") {
                ManageAddressesHiddenScreen(
                    viewModel = walletViewModel,
                    onBack = { navController.popBackStack() },
                    onNavigateToTxHistory = { index -> navController.navigate("spending_address_detail/$index") }
                )
            }

            composable("edit_kns_profile") {
                EditKnsProfileScreen(
                    viewModel = walletViewModel,
                    onBack = { navController.popBackStack() },
                    onNavigateToDomains = { navController.navigate("kns_domains") },
                    onNavigateToSetupGuide = { navController.navigate("create_kns_profile") }
                )
            }

            composable("create_kns_profile") {
                KnsCreateProfileWizardScreen(
                    viewModel = walletViewModel,
                    onFinished = { wroteSomething ->
                        // Reached either straight from the Profile tab (pop back one step is
                        // correct) or from Edit KNS Profile's "Setup Guide" row. Closing the
                        // guide without having inscribed anything returns to whichever of those
                        // it came from. Once it HAS written, pop back PAST the editor rather
                        // than returning to it: its text fields are seeded once from the profile
                        // that existed when it opened, so returning would show stale fields and
                        // let a later Save silently overwrite what the wizard just inscribed.
                        if (wroteSomething &&
                            navController.previousBackStackEntry?.destination?.route == "edit_kns_profile"
                        ) {
                            navController.popBackStack(route = "edit_kns_profile", inclusive = true)
                        } else {
                            navController.popBackStack()
                        }
                    }
                )
            }

            composable(
                "welcome_guide?startAtUserType={startAtUserType}&onboarding={onboarding}",
                arguments = listOf(
                    navArgument("startAtUserType") { type = NavType.BoolType; defaultValue = false },
                    // Explicit presentation context (never inferred from persisted markers):
                    // true only for auto-presented onboarding runs, which are unskippable end
                    // to end. Help replays keep the default false and stay skippable.
                    navArgument("onboarding") { type = NavType.BoolType; defaultValue = false }
                )
            ) { backStackEntry ->
                WelcomeGuideScreen(
                    walletViewModel = walletViewModel,
                    startAtUserType = backStackEntry.arguments?.getBoolean("startAtUserType") ?: false,
                    isOnboardingRun = backStackEntry.arguments?.getBoolean("onboarding") ?: false,
                    onFinished = {
                        // The import run is over — a later Help replay of this same guide must not
                        // offer "Change Chatting Address" again (see WelcomeGuideFundingStep).
                        walletViewModel.clearJustImportedWallet()
                        // Everything the account owns starts syncing now, behind a dialog: a
                        // half-populated chat list invites taps on chats whose history has not
                        // landed yet.
                        chatViewModel.runInitialSyncAfterOnboarding()
                        navController.popBackStack()
                    }
                )
            }

            composable("kns_domains") {
                KnsDomainsScreen(
                    viewModel = walletViewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            composable("settings_section/{sectionKey}") { backStackEntry ->
                SettingsScreen(
                    navController = navController,
                    sectionKey = backStackEntry.arguments?.getString("sectionKey")
                )
            }

            // Settings > Storage sub-pages, one per backup provider (see StorageScreens.kt) -
            // the Storage section itself is just the hub of rows pointing here, like iOS.
            composable("storage_google_drive") {
                GoogleDriveStorageScreen(onBack = { navController.popBackStack() })
            }

            composable("storage_nextcloud") {
                NextcloudStorageScreen(onBack = { navController.popBackStack() })
            }

            composable("help") {
                @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
                HelpScreen(
                    onBack = { navController.popBackStack() },
                    onWelcomeGuide = { navController.navigate("welcome_guide") },
                    onKnsGuide = { navController.navigate("create_kns_profile") }
                )
            }

            composable("kaspa_apps") {
                @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
                KaspaAppsScreen(
                    onBack = { navController.popBackStack() },
                    onOpen = { app ->
                        navController.navigate(
                            "in_app_browser?url=${java.net.URLEncoder.encode(app.url, "UTF-8")}&title=${java.net.URLEncoder.encode(app.name, "UTF-8")}"
                        )
                    }
                )
            }

            composable(
                "in_app_browser?url={url}&title={title}",
                arguments = listOf(
                    androidx.navigation.navArgument("url") { defaultValue = "" },
                    androidx.navigation.navArgument("title") { defaultValue = "" }
                )
            ) { backStackEntry ->
                InAppBrowserScreen(
                    url = java.net.URLDecoder.decode(backStackEntry.arguments?.getString("url") ?: "", "UTF-8"),
                    title = java.net.URLDecoder.decode(backStackEntry.arguments?.getString("title") ?: "", "UTF-8"),
                    onClose = { navController.popBackStack() }
                )
            }

            composable("settings_menu") {
                MenuVisibilityScreen(navController = navController)
            }

            composable("cache_settings") {
                val cacheManager: com.kachat.app.services.CacheManager = hiltViewModel<com.kachat.app.viewmodels.CacheViewModel>().cacheManager
                CacheSettingsScreen(
                    onBack = { navController.popBackStack() },
                    cacheManager = cacheManager,
                )
            }

            composable("child_mode_settings") {
                ChildModeSettingsScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable("connection_settings") {
                ConnectionSettingsScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable("language_settings") {
                LanguageSettingsScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable("currency_settings") {
                CurrencySettingsScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable("kaspa_explorer_settings") {
                KaspaExplorerSettingsScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable("notification_settings") {
                NotificationSettingsScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable("wallet_notification_settings") {
                WalletNotificationSettingsScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable("kaposts_notification_settings") {
                KaPostsNotificationSettingsScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable("photo_quality_settings") {
                PhotoQualitySettingsScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable("quick_reaction_settings") {
                QuickReactionSettingsScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            // Reachable as a dock tab now, not only as a Hub section.
            @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
            composable("kaspa_websites") {
                Box(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
                    KaspaAppsScreen(
                        onBack = { navController.popBackStack() },
                        onOpen = { app ->
                            navController.navigate(
                                "in_app_browser?url=${java.net.URLEncoder.encode(app.url, "UTF-8")}" +
                                    "&title=${java.net.URLEncoder.encode(app.name, "UTF-8")}"
                            )
                        }
                    )
                }
            }

            composable(
                "broadcasts",
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
            ) {
                // A dock/cycle page since 4.0 (see the Chats-slot cycle) with the floating
                // bottom nav visible over it, same bottom-padding treatment as Chats/Profile.
                Box(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
                    BroadcastListScreen(
                        navController = navController,
                        onBack = { navController.popBackStack() }
                    )
                }
            }

            composable(
                "broadcast_room_info/{channelName}",
                arguments = listOf(navArgument("channelName") { type = NavType.StringType })
            ) { backStackEntry ->
                val room = backStackEntry.arguments?.getString("channelName") ?: ""
                BroadcastRoomInfoScreen(
                    channelName = room,
                    onBack = { navController.popBackStack() },
                    onOpenHiddenUsers = { navController.navigate("broadcast_hidden_users/$room") }
                )
            }

            composable(
                "broadcast_hidden_users/{channelName}",
                arguments = listOf(navArgument("channelName") { type = NavType.StringType })
            ) { backStackEntry ->
                BroadcastHiddenUsersScreen(
                    channelName = backStackEntry.arguments?.getString("channelName") ?: "",
                    onBack = { navController.popBackStack() }
                )
            }

            composable("broadcast_channel/{channelName}") { backStackEntry ->
                val channelName = backStackEntry.arguments?.getString("channelName") ?: return@composable
                // Same bottom-padding treatment as "broadcasts" — the floating tab bar sits below
                // this screen's own message-compose bar rather than overlapping it. But only while
                // the tab bar is actually visible: once the keyboard opens, the tab bar goes behind
                // it anyway, and this screen's own bottomBar already applies imePadding() to clear
                // the keyboard itself — reserving both at once left a dead black gap between the
                // message input and the keyboard.
                val imeVisible = WindowInsets.isImeVisible
                Box(modifier = Modifier.padding(bottom = if (imeVisible) 0.dp else innerPadding.calculateBottomPadding())) {
                    BroadcastChannelScreen(
                        channelName = channelName,
                        onBack = { navController.popBackStack() },
                        navController = navController
                    )
                }
            }

            // `group` is an optional query-style arg (defaults false) so the tab-aware create
            // button can open this screen straight into group-builder mode from the Group Chats tab.
            composable(
                "create_chat?group={group}",
                arguments = listOf(navArgument("group") { type = NavType.BoolType; defaultValue = false })
            ) { backStackEntry ->
                val startInGroupMode = backStackEntry.arguments?.getBoolean("group") ?: false
                CreateChatScreen(
                    onBack = { navController.popBackStack() },
                    onChatCreated = { address ->
                        navController.navigate("chat/$address") {
                            popUpTo(Screen.Chats.route)
                        }
                    },
                    onGroupCreated = { groupId ->
                        navController.navigate("group_chat/$groupId") {
                            popUpTo(Screen.Chats.route)
                        }
                    },
                    startInGroupMode = startInGroupMode,
                    chatViewModel = chatViewModel
                )
            }

            composable("group_chat/{groupId}") { backStackEntry ->
                val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
                GroupChatThreadScreen(navController = navController, groupId = groupId, chatViewModel = chatViewModel, walletViewModel = walletViewModel)
            }

            composable("group_chat_info/{groupId}") { backStackEntry ->
                val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
                GroupChatInfoScreen(navController = navController, groupId = groupId, chatViewModel = chatViewModel)
            }

            // Chat thread — navigated to from ChatsScreen. paymentMode is an optional query-style
            // arg (defaults false) so a "Pay in Kaspa" shortcut elsewhere (e.g. a broadcast
            // sender's avatar menu) can land the user straight in payment-entry mode.
            composable(
                "chat/{contactId}?paymentMode={paymentMode}",
                arguments = listOf(navArgument("paymentMode") { type = NavType.BoolType; defaultValue = false })
            ) { backStackEntry ->
                val contactId = backStackEntry.arguments?.getString("contactId") ?: return@composable
                val startInPaymentMode = backStackEntry.arguments?.getBoolean("paymentMode") ?: false
                ChatThreadScreen(
                    navController = navController,
                    contactId = contactId,
                    chatViewModel = chatViewModel,
                    walletViewModel = walletViewModel,
                    startInPaymentMode = startInPaymentMode
                )
            }

            composable("chess_game/{contactId}/{gameId}") { backStackEntry ->
                val contactId = backStackEntry.arguments?.getString("contactId") ?: return@composable
                val gameId = backStackEntry.arguments?.getString("gameId") ?: return@composable
                ChessGameScreen(
                    navController = navController,
                    contactId = contactId,
                    gameId = gameId,
                    chatViewModel = chatViewModel
                )
            }

            composable(
                "chat_info/{contactId}?fromBroadcast={fromBroadcast}",
                arguments = listOf(navArgument("fromBroadcast") { type = NavType.BoolType; defaultValue = false })
            ) { backStackEntry ->
                val contactId = backStackEntry.arguments?.getString("contactId") ?: return@composable
                val fromBroadcast = backStackEntry.arguments?.getBoolean("fromBroadcast") ?: false
                ChatInfoScreen(
                    contactId = contactId,
                    onBack = { navController.popBackStack() },
                    fromBroadcast = fromBroadcast,
                    // Replaces this screen rather than stacking on it, so Back from the chat
                    // returns where User Info was opened from instead of to User Info again.
                    onOpenChat = { id ->
                        navController.popBackStack()
                        navController.navigate("chat/$id")
                    },
                    onNavigateToPhotoSettings = { id -> navController.navigate("contact_photo_settings/$id") },
                    onNavigateToNotificationSettings = { id -> navController.navigate("contact_notification_settings/$id") },
                    onNavigateToDomains = { id -> navController.navigate("contact_domains/$id") }
                )
            }

            composable(
                "contact_domains/{contactId}",
                arguments = listOf(navArgument("contactId") { type = NavType.StringType })
            ) { backStackEntry ->
                val contactId = backStackEntry.arguments?.getString("contactId") ?: return@composable
                ContactDomainsScreen(
                    contactId = contactId,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                "contact_photo_settings/{contactId}",
                arguments = listOf(navArgument("contactId") { type = NavType.StringType })
            ) { backStackEntry ->
                val contactId = backStackEntry.arguments?.getString("contactId") ?: return@composable
                ContactPhotoSettingsScreen(
                    contactId = contactId,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                "contact_notification_settings/{contactId}",
                arguments = listOf(navArgument("contactId") { type = NavType.StringType })
            ) { backStackEntry ->
                val contactId = backStackEntry.arguments?.getString("contactId") ?: return@composable
                ContactNotificationSettingsScreen(
                    contactId = contactId,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
