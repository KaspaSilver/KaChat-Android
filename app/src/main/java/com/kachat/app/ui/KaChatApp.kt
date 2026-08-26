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
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.WalletViewModel
import com.kachat.app.viewmodels.ChatViewModel
import kotlin.math.roundToInt

/**
 * Top-level navigation destinations.
 */
sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Settings    : Screen("settings",     "Settings",     Icons.Default.Settings)
    object Chats       : Screen("chats",        "Chats",        Icons.Default.Forum)
    object Portfolio   : Screen("portfolio",    "Portfolio",    Icons.Default.PieChart)
    object Profile     : Screen("profile",      "Profile",      Icons.Default.AccountCircle)
    object Swap        : Screen("swap",         "Swap",         Icons.Default.SwapHoriz)
    // Labeled "Storage" (not "Cold Storage") and always in the default tab set, matching iOS's
    // AppTab.coldStorage — hideable like Portfolio/Swap via Settings > Customization > Menu, but
    // no longer a separate opt-in reached through Portfolio's old "Cold Storage Devices" row.
    object ColdStorage : Screen("cold_storage", "Storage",      Icons.Default.Lock)
    object KaPosts     : Screen("kaposts",      "KaPosts",      Icons.Default.EditNote)
    object Broadcasts  : Screen("broadcasts",   "Broadcasts",   Icons.Default.Sensors)
    // The old "+ More" pseudo-tab (opened Customize Dock from the dock itself) is gone —
    // Customize Dock is reached via Settings > Customization instead. The dock still caps at
    // MAX_DOCK_ITEMS, with over-cap KaPosts/Broadcasts riding the Chats-slot cycle.
}

/** Route strings for tabs that can never be hidden — see [resolveTabOrder]. */
val ALWAYS_VISIBLE_TAB_ROUTES = setOf(Screen.Chats.route, Screen.Profile.route)

// All top-level tabs, in the app's default order (matches iOS's AppTab.defaultOrder). Settings
// isn't a tab at all (matches iOS) — it's reached one tap in from Profile's gear icon instead,
// see ProfileScreen.
val bottomNavItems = listOf(
    Screen.Portfolio,
    Screen.ColdStorage,
    Screen.Chats,
    Screen.Swap,
    Screen.Profile,
    Screen.KaPosts,
    Screen.Broadcasts
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
val CHILD_MODE_HIDDEN_ROUTES = setOf(Screen.Swap.route, Screen.KaPosts.route, Screen.Broadcasts.route)

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
fun resolveTabOrder(routes: List<String>, hiddenTabs: Set<String>, childMode: Boolean = false): List<Screen> {
    val byRoute = bottomNavItems.associateBy { it.route }
    val resolved = routes.mapNotNull { byRoute[it] }
    val missing = bottomNavItems.filter { it !in resolved }
    val ordered = resolved + missing
    var visible = ordered.filter { it.route in ALWAYS_VISIBLE_TAB_ROUTES || it.route !in hiddenTabs }
    if (childMode) {
        visible = visible.filter { it.route !in CHILD_MODE_HIDDEN_ROUTES }
    }
    // Dock cap (matches iOS AppTab.visible): when over capacity KaPosts drops out first, then
    // Broadcasts - both stay reachable by cycling the Chats tab - then the tail falls off
    // silently (any future tab beyond the cap stays hidden until the user frees a slot).
    for (cyclable in listOf(Screen.KaPosts, Screen.Broadcasts)) {
        if (visible.size > MAX_DOCK_ITEMS) {
            visible = visible.filter { it != cyclable }
        }
    }
    if (visible.size > MAX_DOCK_ITEMS) {
        visible = visible.take(MAX_DOCK_ITEMS)
    }
    return visible
}

/** KaPosts is enabled but didn't fit the dock - it joins the Chats-tab cycle. Never while Child
 *  Mode is on: the cycle must skip the hidden tabs entirely, not keep them reachable. */
fun kaPostsAccessibleViaChatsTab(routes: List<String>, hiddenTabs: Set<String>, childMode: Boolean = false): Boolean {
    if (childMode) return false
    if (Screen.KaPosts.route in hiddenTabs) return false
    return resolveTabOrder(routes, hiddenTabs, childMode).none { it.route == Screen.KaPosts.route }
}

/** Broadcasts is enabled but didn't fit the dock - it joins the Chats-tab cycle. Never while
 *  Child Mode is on (same reasoning as [kaPostsAccessibleViaChatsTab]). */
fun broadcastsAccessibleViaChatsTab(routes: List<String>, hiddenTabs: Set<String>, childMode: Boolean = false): Boolean {
    if (childMode) return false
    if (Screen.Broadcasts.route in hiddenTabs) return false
    return resolveTabOrder(routes, hiddenTabs, childMode).none { it.route == Screen.Broadcasts.route }
}

/**
 * What tapping the Chats slot cycles through (matches iOS AppTab.chatsSlotCycle): always Chats
 * itself, then whichever of KaPosts/Broadcasts are enabled but masked out of the full dock.
 * While Child Mode is on this is always just [Screen.Chats].
 */
fun chatsSlotCycle(routes: List<String>, hiddenTabs: Set<String>, childMode: Boolean = false): List<Screen> {
    val cycle = mutableListOf<Screen>(Screen.Chats)
    if (kaPostsAccessibleViaChatsTab(routes, hiddenTabs, childMode)) cycle.add(Screen.KaPosts)
    if (broadcastsAccessibleViaChatsTab(routes, hiddenTabs, childMode)) cycle.add(Screen.Broadcasts)
    return cycle
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
    onPendingGroupHandled: () -> Unit = {}
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
            onPendingGroupHandled = onPendingGroupHandled
        )
    }
}

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
    onPendingGroupHandled: () -> Unit = {}
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
            dest.route == "broadcasts" || dest.route == "broadcast_channel/{channelName}"
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
    val localTabOrder = resolveTabOrder(persistedTabOrder, hiddenTabs, childModeEnabled)
    // Chats-slot cycle (matches iOS): KaPosts/Broadcasts enabled but over the dock cap ride the
    // Chats slot - tapping it cycles chats -> kaposts -> broadcasts. The slot is STICKY: leaving
    // to another tab and coming back returns to whichever page the slot last showed.
    val slotCycle = chatsSlotCycle(persistedTabOrder, hiddenTabs, childModeEnabled)
    var chatsSlotRoute by rememberSaveable { mutableStateOf(Screen.Chats.route) }
    val currentTopRoute = currentDestination?.route
    // Deep links/notifications can land on a cycle page directly - keep the slot in sync.
    LaunchedEffect(currentTopRoute) {
        if (currentTopRoute != null && currentTopRoute != Screen.Chats.route &&
            slotCycle.any { it.route == currentTopRoute }
        ) {
            chatsSlotRoute = currentTopRoute
        }
    }
    // A menu change can remove the slot's current page from the cycle - snap home to Chats.
    LaunchedEffect(slotCycle.map { it.route }) {
        if (slotCycle.none { it.route == chatsSlotRoute }) chatsSlotRoute = Screen.Chats.route
    }
    // Child Mode just turned on (or the app landed on a now-hidden screen): snap home to Chats.
    // Covers the three hidden tab routes plus Broadcasts' pushed room screen.
    LaunchedEffect(childModeEnabled, currentTopRoute) {
        if (childModeEnabled && currentTopRoute != null && (
                currentTopRoute in CHILD_MODE_HIDDEN_ROUTES ||
                    currentTopRoute.startsWith("broadcast_channel/") ||
                    currentTopRoute == "hidden_broadcast_users"
                )
        ) {
            navController.popBackStack(Screen.Chats.route, false)
        }
    }

    fun showSlotRoute(route: String) {
        chatsSlotRoute = route
        navController.popBackStack(Screen.Chats.route, false)
        if (route != Screen.Chats.route) {
            navController.navigate(route) { launchSingleTop = true }
        }
    }

    // Hold-to-slide slot menu: hold the Chats slot, a card with the cycle options rises above
    // the dock; slide onto one and release (or tap it) to jump straight there.
    var slotMenuVisible by remember { mutableStateOf(false) }
    var slotMenuHighlight by remember { mutableStateOf<Int?>(null) }
    var slotOriginInRoot by remember { mutableStateOf(Offset.Zero) }
    var slotMenuBounds by remember { mutableStateOf<Rect?>(null) }
    LaunchedEffect(slotMenuVisible) {
        if (slotMenuVisible) {
            // Failsafe: never let the menu linger (backgrounding mid-hold skips onDragEnd).
            kotlinx.coroutines.delay(8_000)
            slotMenuVisible = false
            slotMenuHighlight = null
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
            if (walletViewModel.isChildModeEnabled()) {
                navController.popBackStack(Screen.Chats.route, false)
            } else {
                navController.navigate("broadcast_channel/$pendingChannelName")
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
    LaunchedEffect(userTypePendingMarker, onboardingWizardPendingMarker, pendingWelcomeGuide, currentTopRoute) {
        if (pendingWelcomeGuide || welcomeGuidePresentedThisSession) return@LaunchedEffect
        if (currentTopRoute == null || currentTopRoute.startsWith("welcome_guide")) return@LaunchedEffect
        if (userTypePendingMarker == true) {
            navController.navigate("welcome_guide?startAtUserType=true&onboarding=true")
        } else if (onboardingWizardPendingMarker == true) {
            navController.navigate("welcome_guide?onboarding=true")
        }
    }

    // "What's new in 4.0" dock wizard: shown once per install, only while sitting on a tab
    // page (so it never covers onboarding or the Welcome Guide - fresh installs see the setup
    // wizard first, then this). Any dismissal is permanent.
    val dockWizardDismissed by walletViewModel.dockWizardDismissed.collectAsState()
    val dockWizardChildMode by walletViewModel.childModeEnabled.collectAsState()
    var showDockWizard by remember { mutableStateOf(false) }
    LaunchedEffect(dockWizardDismissed, pendingWelcomeGuide, currentTopRoute, dockWizardChildMode) {
        // Child Mode skips the wizard entirely - it teaches the Chats cycle, which
        // Child Mode removes (KaPosts/Broadcasts are hidden).
        if (dockWizardDismissed == false && !pendingWelcomeGuide && !showDockWizard &&
            dockWizardChildMode != true &&
            bottomNavItems.any { it.route == currentTopRoute }
        ) {
            kotlinx.coroutines.delay(1_200)
            showDockWizard = true
        }
    }
    if (showDockWizard) {
        DockWizardDialog(onDismiss = {
            showDockWizard = false
            walletViewModel.dismissDockWizard()
        })
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
                    // Hold-to-slide menu card: rises above the dock while holding the Chats slot;
                    // slide onto an option and release (or tap it) to jump straight there.
                    if (slotMenuVisible && slotCycle.size > 1) {
                        Row(
                            modifier = Modifier
                                .padding(bottom = 10.dp)
                                .background(LocalAppColors.current.surface, RoundedCornerShape(22.dp))
                                .padding(6.dp)
                                .onGloballyPositioned { slotMenuBounds = it.boundsInRoot() },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            slotCycle.forEachIndexed { index, option ->
                                val highlighted = slotMenuHighlight == index
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .width(86.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(
                                            if (highlighted) KaspaTeal.copy(alpha = 0.22f)
                                            else Color.Transparent
                                        )
                                        .clickable {
                                            slotMenuVisible = false
                                            slotMenuHighlight = null
                                            showSlotRoute(option.route)
                                        }
                                        .padding(vertical = 10.dp)
                                ) {
                                    Icon(
                                        imageVector = option.icon,
                                        contentDescription = option.label,
                                        tint = if (highlighted) KaspaTeal else LocalAppColors.current.textPrimary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = option.label,
                                        color = if (highlighted) KaspaTeal else LocalAppColors.current.textPrimary,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
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
                                // The Chats slot lights up (and morphs its icon/label) while any
                                // of its cycle pages is showing.
                                val onCyclePage = slotCycle.size > 1 && currentDestination?.hierarchy?.any { dest ->
                                    slotCycle.any { it.route == dest.route && it != Screen.Chats }
                                } == true
                                val chatsSlotMorph: Screen? = if (screen == Screen.Chats && slotCycle.size > 1) {
                                    slotCycle.firstOrNull { it.route == chatsSlotRoute && it != Screen.Chats }
                                } else null
                                val selected = (screen == Screen.Chats && onCyclePage) ||
                                    currentDestination?.hierarchy?.any { it.route == screen.route } == true
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
                                        .onGloballyPositioned {
                                            if (screen == Screen.Chats) {
                                                slotOriginInRoot = it.positionInRoot()
                                            }
                                        }
                                        .pointerInput(screen.route, slotCycle.size) {
                                            // In-dock drag-reorder is gone (4.0): tab order is
                                            // changed in Customize Menu, matching iOS. The only
                                            // hold gesture left is the Chats slot's jump menu.
                                            if (screen != Screen.Chats || slotCycle.size <= 1) return@pointerInput
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    slotMenuVisible = true
                                                    slotMenuHighlight = null
                                                },
                                                onDrag = { change, _ ->
                                                    change.consume()
                                                    // Highlight whichever menu option the finger is over.
                                                    val rootPos = slotOriginInRoot + change.position
                                                    val bounds = slotMenuBounds
                                                    slotMenuHighlight = if (bounds != null && bounds.width > 0f) {
                                                        val index = ((rootPos.x - bounds.left) / (bounds.width / slotCycle.size)).toInt()
                                                        if (rootPos.x in bounds.left..bounds.right && index in slotCycle.indices) index else null
                                                    } else null
                                                },
                                                onDragEnd = {
                                                    // Release on an option selects it; release elsewhere
                                                    // keeps the menu open for a plain tap.
                                                    slotMenuHighlight?.let { index ->
                                                        slotCycle.getOrNull(index)?.let { showSlotRoute(it.route) }
                                                        slotMenuVisible = false
                                                    }
                                                    slotMenuHighlight = null
                                                },
                                                onDragCancel = {
                                                    slotMenuVisible = false
                                                    slotMenuHighlight = null
                                                }
                                            )
                                        }
                                        .clickable {
                                            if (slotMenuVisible && screen != Screen.Chats) {
                                                slotMenuVisible = false
                                                slotMenuHighlight = null
                                            }
                                            // Chats slot with masked pages behind it: tapping while
                                            // ON the slot advances the cycle (chats -> kaposts ->
                                            // broadcasts -> chats); tapping from another tab returns
                                            // to whichever page the slot last showed.
                                            if (screen == Screen.Chats && slotCycle.size > 1) {
                                                val onSlotNow = currentDestination?.route != null &&
                                                    slotCycle.any { it.route == currentDestination.route }
                                                if (onSlotNow) {
                                                    val currentIndex = slotCycle.indexOfFirst { it.route == chatsSlotRoute }
                                                        .coerceAtLeast(0)
                                                    val next = slotCycle[(currentIndex + 1) % slotCycle.size]
                                                    showSlotRoute(next.route)
                                                } else {
                                                    showSlotRoute(chatsSlotRoute)
                                                }
                                                return@clickable
                                            }
                                            // Already there — let that screen know it was re-tapped (so it can
                                            // dismiss its own transient UI, e.g. a full-screen QR overlay) rather
                                            // than running the popBackStack/navigate logic below, which for a tab
                                            // with nothing "pushed" above it falls through to navigate() and
                                            // lands back on the graph's start destination (Chats) instead of
                                            // staying put.
                                            if (selected) {
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
                                                imageVector = chatsSlotMorph?.icon ?: screen.icon,
                                                contentDescription = chatsSlotMorph?.label ?: screen.label,
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
                                            text = chatsSlotMorph?.label ?: screen.label,
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
                    ColdStorageListScreen(navController = navController, walletViewModel = walletViewModel)
                }
            }

            composable(
                "cold_storage_detail/{accountId}",
                arguments = listOf(navArgument("accountId") { type = NavType.StringType })
            ) { backStackEntry ->
                // Shares the "cold_storage" list screen's own ViewModel instance (always on the
                // back stack — this screen is only ever reached by tapping a row there) rather
                // than a fresh one scoped to this destination. A fresh instance's deleteAccount()
                // updated its own _accounts flow only, leaving the list screen's copy stale until
                // something else happened to recompose it — deleting an account looked like it
                // hadn't taken effect until you left and came back.
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry("cold_storage")
                }
                ColdStorageDetailScreen(
                    accountId = backStackEntry.arguments?.getString("accountId") ?: "",
                    navController = navController,
                    viewModel = hiltViewModel(parentEntry)
                )
            }

            composable(
                "cold_storage_tx_history/{address}",
                arguments = listOf(navArgument("address") { type = NavType.StringType })
            ) { backStackEntry ->
                // Shares the SAME instance ColdStorageDetailScreen itself uses — which is the
                // "cold_storage" list route's ViewModel (see that composable above: it shares
                // parentEntry = getBackStackEntry("cold_storage"), not its own route's entry).
                // Targeting "cold_storage_detail/{accountId}" here directly would resolve to that
                // destination's OWN, never-populated ViewModelStore instead — a different,
                // still-empty instance — since the detail screen never requests a ViewModel
                // scoped to its own entry either. Not every caller of this route necessarily has
                // "cold_storage" on the back stack, though (e.g. the withdraw-flow receipt-history
                // shortcut) — getBackStackEntry throws in that case, so fall back to a fresh
                // instance same as PortfolioTransactionsScreen above does for the same reason.
                // (Manage Addresses/Manage Addresses Hidden used to reuse this route too, before
                // getting their own "spending_address_detail/{index}" route below — its Send
                // button unconditionally opened Cold Storage's external-QR-signer flow, which
                // can't work for a spending address whose private key already lives in this wallet.)
                val parentEntry = remember(backStackEntry) {
                    try {
                        navController.getBackStackEntry("cold_storage")
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                }
                ColdStorageTxHistoryScreen(
                    address = backStackEntry.arguments?.getString("address") ?: "",
                    onBack = { navController.popBackStack() },
                    viewModel = if (parentEntry != null) hiltViewModel(parentEntry) else hiltViewModel()
                )
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
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry("cold_storage_detail/{accountId}")
                }
                ColdStorageAddressVisibilityScreen(
                    accountId = backStackEntry.arguments?.getString("accountId") ?: "",
                    viewModel = hiltViewModel(parentEntry),
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
                val target = backStackEntry.arguments?.getString("target") ?: "from"
                ManageAddressesScreen(
                    viewModel = walletViewModel,
                    onBack = { navController.popBackStack() },
                    onNavigateToHidden = { navController.navigate("manage_addresses_hidden_pick/$target") },
                    onAddressPicked = { entry ->
                        // Swap only ever sends the user here to pick where received KAS should
                        // land (target == "to") - the old "pick which address to auto-send KAS
                        // from" flow (target == "from") was removed along with Swap's auto-send.
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
                    onFinished = {
                        // Reached either straight from the Profile tab (pop back one step is
                        // correct) or from Edit KNS Profile's "Setup Guide" row - in the latter
                        // case, pop back PAST that screen too rather than returning to it: its
                        // text fields are seeded once from the profile that existed when it
                        // opened, so returning to it after the wizard just wrote new values could
                        // show stale fields and let a later Save silently overwrite what the
                        // wizard just inscribed.
                        if (navController.previousBackStackEntry?.destination?.route == "edit_kns_profile") {
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

            composable("connection_status") {
                ConnectionStatusScreen(
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

            composable("hidden_broadcast_users") {
                HiddenBroadcastUsersScreen(
                    onBack = { navController.popBackStack() }
                )
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
                    onNavigateToPhotoSettings = { id -> navController.navigate("contact_photo_settings/$id") },
                    onNavigateToNotificationSettings = { id -> navController.navigate("contact_notification_settings/$id") }
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
