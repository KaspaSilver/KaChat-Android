package com.kachat.app.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import androidx.navigation.navArgument
import com.kachat.app.ui.screens.*
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.WalletViewModel
import com.kachat.app.viewmodels.ChatViewModel
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.filled.CheckCircle

/**
 * The dock's tabs (iOS `AppTab`). Since 2026-10-24 KaChat is 1:1 and group chats, and the dock is
 * fixed - since 2026-10-26 a five: Contacts, Groups, Chats, Profile, Settings, with Chats in the
 * center (iOS d54ea87, 95a7d1f, a53d343): no Kaspa Hub, no Customize Dock. The wallet features
 * (Portfolio, Cold Storage, Swap, .kachat names) live in the KaChat wallet app.
 */
sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Chats       : Screen("chats",        "Chats",        Icons.Default.Forum)
    // Group Chats, their own screen again since 2026-10-26 (they were circles above the chats).
    object Groups      : Screen("group_chats",  "Groups",       Icons.Outlined.Groups)
    // Saved Kaspa addresses with names, per wallet (iOS 00767a4); "Contacts" with a contact-card
    // icon since 2026-10-28 (iOS bc3e1ff3; "Addresses" before). The route keeps its old name.
    object AddressBook : Screen("address_book", "Contacts",     ContactsIcon)
    object Profile     : Screen("profile",      "Profile",      Icons.Default.AccountCircle)
    // Its own tab since 2026-10-24 (it used to be a sheet behind Profile's settings row).
    object Settings    : Screen("settings",     "Settings",     Icons.Default.Settings)
}

/**
 * The tab a route belongs to, for the dock to light while you are on it: the route itself when
 * it is one of the tabs, the tab that opens it when it is a screen further in. Null for a route
 * the dock is not shown over, or one no tab owns.
 */
private fun dockOwnerRoute(route: String?): String? = when (route) {
    null -> null
    "chat/{contactId}?paymentMode={paymentMode}" -> Screen.Chats.route
    "group_chat/{groupId}" -> Screen.Groups.route
    "help", "manage_addresses", "identity_address_detail", "spending_address_detail/{index}",
    "kns_domains" -> Screen.Profile.route
    else -> route.takeIf { r -> bottomNavItems.any { it.route == r } }
}

/**
 * Switches to [tab] the way a dock tap does: back to it when it is already on the back stack (a
 * navigate() + popUpTo to the start destination alone is a no-op for Chats in Navigation
 * Compose), else a first visit that keeps each tab's own stack.
 */
fun navigateToTab(navController: NavController, tab: Screen) {
    val poppedToExisting = navController.popBackStack(route = tab.route, inclusive = false, saveState = true)
    if (!poppedToExisting) {
        navController.navigate(tab.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
}

/** What the dock renders, in order (iOS `AppTab.dock`). The app opens on Chats. */
val bottomNavItems = listOf(
    Screen.AddressBook,
    Screen.Groups,
    Screen.Chats,
    Screen.Profile,
    Screen.Settings,
)

/**
 * Root composable: bottom nav + NavHost.
 * Wallet onboarding is shown instead when no wallet exists.
 */
@Composable
fun KaChatApp(
    walletViewModel: WalletViewModel = hiltViewModel(),
    pendingContactId: String? = null,
    onPendingContactHandled: () -> Unit = {},
    pendingGroupId: String? = null,
    onPendingGroupHandled: () -> Unit = {},
    pendingOpenGroups: Boolean = false,
    onPendingOpenGroupsHandled: () -> Unit = {},
    pendingWalletActivityKind: String? = null,
    onPendingWalletActivityHandled: () -> Unit = {},
    pendingOwnAddress: String? = null,
    onPendingOwnAddressHandled: () -> Unit = {}
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
            pendingGroupId = pendingGroupId,
            onPendingGroupHandled = onPendingGroupHandled,
            pendingOpenGroups = pendingOpenGroups,
            onPendingOpenGroupsHandled = onPendingOpenGroupsHandled,
            pendingWalletActivityKind = pendingWalletActivityKind,
            onPendingWalletActivityHandled = onPendingWalletActivityHandled,
            pendingOwnAddress = pendingOwnAddress,
            onPendingOwnAddressHandled = onPendingOwnAddressHandled
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
    pendingGroupId: String? = null,
    onPendingGroupHandled: () -> Unit = {},
    pendingOpenGroups: Boolean = false,
    onPendingOpenGroupsHandled: () -> Unit = {},
    pendingWalletActivityKind: String? = null,
    onPendingWalletActivityHandled: () -> Unit = {},
    pendingOwnAddress: String? = null,
    onPendingOwnAddressHandled: () -> Unit = {}
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    // The whole stack, for the dock to light the tab a pushed screen sits in.
    val dockBackStack by navController.currentBackStack.collectAsState()
    val currentDestination = navBackStackEntry?.destination
    val onTabRoute = currentDestination?.hierarchy?.any { dest ->
        bottomNavItems.any { it.route == dest.route } ||
            // A chat keeps the tab bar too: iOS's ChatDetailView / GroupChatDetailView sit inside
            // the Chats tab with its bar showing, the composer above it.
            dest.route == "chat/{contactId}?paymentMode={paymentMode}" ||
            dest.route == "group_chat/{groupId}" ||
            // Help is pushed inside the Profile tab on iOS (ProfileHelpView): its tab bar stays.
            dest.route == "help" ||
            // So are Manage Addresses, its address pages and Your Domains (NavigationLinks in
            // ContactsView / ManageAddressesView) - all keep iOS's tab bar.
            dest.route == "manage_addresses" ||
            dest.route == "identity_address_detail" ||
            dest.route == "spending_address_detail/{index}" ||
            dest.route == "kns_domains"
    } == true

    val hideBottomBar by walletViewModel.hideBottomBar.collectAsState()
    // The fixed four (iOS `AppTab.dock`).
    val localTabOrder = bottomNavItems
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
                    com.kachat.app.ui.theme.IosActivityIndicator(color = KaspaTeal, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
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

    // Tapping a notification for a message/handshake/payment jumps straight to that
    // conversation, matching what a real chat app does — otherwise you'd land on the
    // chat list and have to go find it yourself.
    LaunchedEffect(pendingContactId) {
        if (pendingContactId != null) {
            navController.navigate("chat/$pendingContactId")
            onPendingContactHandled()
        }
    }

    // Same idea for a group chat notification: the Groups tab, then the group on top of it (iOS
    // a53d343 - a tapped group notification switches to that tab and opens its group).
    LaunchedEffect(pendingGroupId) {
        if (pendingGroupId != null) {
            navigateToTab(navController, Screen.Groups)
            navController.navigate("group_chat/$pendingGroupId")
            onPendingGroupHandled()
        }
    }

    // A group notification that names no local group (a "you were added" push, or a group push
    // this device could not resolve or ingest): the Groups list, where the new group appears once
    // the next sync lands - as specific as that tap can get (iOS a53d343). Better than a
    // group_chat/<blinded id> route that resolves to an empty, unusable thread.
    LaunchedEffect(pendingOpenGroups) {
        if (pendingOpenGroups) {
            navigateToTab(navController, Screen.Groups)
            onPendingOpenGroupsHandled()
        }
    }

    // Wallet address-activity receipt ("Received X KAS" on a spending address) that names no one
    // address: Profile, which shows the wallet's addresses and balances (iOS d54ea87 .openProfile).
    LaunchedEffect(pendingWalletActivityKind) {
        if (pendingWalletActivityKind != null) {
            if (!navController.popBackStack(Screen.Profile.route, inclusive = false)) {
                navController.navigate(Screen.Profile.route) { launchSingleTop = true }
            }
            onPendingWalletActivityHandled()
        }
    }

    // A "Received" receipt that named one of your addresses: that address's History, in a sheet
    // over whatever tab is showing (iOS 13046ad). MainActivity holds the address until this shell
    // exists, so a cold start (still on the loading route when the tap arrived) lands here too -
    // iOS's OwnAddressRoute.pending.
    var ownAddressSheet by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingOwnAddress) {
        val address = pendingOwnAddress
        if (address != null) {
            ownAddressSheet = address
            onPendingOwnAddressHandled()
        }
    }
    // A bell "Received" / "Balance increased" row that carries its address: the same sheet
    // (iOS 95e2cba, OwnAddressRoute.pending + .openOwnAddress).
    val pendingBellOwnAddress by OwnAddressRoute.pending.collectAsState()
    LaunchedEffect(pendingBellOwnAddress) {
        val address = pendingBellOwnAddress ?: return@LaunchedEffect
        ownAddressSheet = address
        OwnAddressRoute.pending.value = null
    }
    ownAddressSheet?.let { address ->
        // keyed so a second tap on another address resolves afresh rather than reusing the first
        key(address) {
            OwnAddressHistorySheet(
                address = address,
                walletViewModel = walletViewModel,
                onOpenChat = { contact ->
                    ownAddressSheet = null
                    navController.navigate("chat/$contact")
                },
                onDismiss = { ownAddressSheet = null },
            )
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

    // Someone's profile link (kachat://profile/<address>, https://kachat.app/u/<address>, or a
    // tapped KaChat Profile card). It opens User Info for anyone - contact or not - with Open Chat
    // one tap away and their name and avatar to check who the link is for before writing to them
    // (iOS 81df734). Your own shows your own card, and you are never added as your own contact
    // (iOS 95f50ca); someone new gets an auto-added contact. A link from outside the app with
    // `chat=1` (KaPosts' Chat button) opens the 1:1 chat itself, started if need be, the way the
    // Addresses screen's Chat does (iOS 724d477) - but not for your own address, which has no
    // chat with itself.
    val pendingProfile by com.kachat.app.ui.screens.ProfileDeepLink.pending.collectAsState()
    LaunchedEffect(pendingProfile) {
        val link = pendingProfile ?: return@LaunchedEffect
        com.kachat.app.ui.screens.ProfileDeepLink.consumePending()
        val address = link.address
        val mine = walletViewModel.address.value
        when {
            mine != null && mine.equals(address, ignoreCase = true) ->
                navController.navigate("chat_info/$mine")
            link.openChat -> chatViewModel.openProfile(address) { navController.navigate("chat/$it") }
            else -> chatViewModel.openProfile(address) { navController.navigate("chat_info/$it") }
        }
    }

    // A payment request tapped, pasted or opened from outside the app (iOS c6851d6
    // PaymentRequestSheetPresenter): Send Kaspa with it filled in, or your own to copy or share.
    com.kachat.app.ui.screens.PaymentRequestSheetHost(walletViewModel = walletViewModel, chatViewModel = chatViewModel)

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
            // Onboarding runs are unskippable end to end - a run only completes at Finish, and
            // this persisted marker re-presents an interrupted run at next launch.
            walletViewModel.markOnboardingWizardPending()
            navController.navigate("welcome_guide?onboarding=true")
            walletViewModel.consumePendingWelcomeGuide()
        }
    }

    // Interrupted first run: the auto-present trigger above is in-memory only and lost on
    // relaunch, so the persisted marker re-presents the guide until it's genuinely finished,
    // replaying the whole onboarding run from the top (still unskippable).
    val onboardingWizardPendingMarker by walletViewModel.onboardingWizardPending.collectAsState()
    // A hold with no wizard behind it would strand the account unsynced forever. Once the guide
    // is neither pending nor on screen, lift it.
    LaunchedEffect(pendingWelcomeGuide, currentTopRoute) {
        if (!pendingWelcomeGuide && currentTopRoute?.startsWith("welcome_guide") != true) {
            chatViewModel.releaseOnboardingHoldIfIdle()
        }
    }

    LaunchedEffect(onboardingWizardPendingMarker, pendingWelcomeGuide, currentTopRoute) {
        if (pendingWelcomeGuide || welcomeGuidePresentedThisSession) return@LaunchedEffect
        if (currentTopRoute == null || currentTopRoute.startsWith("welcome_guide")) return@LaunchedEffect
        if (onboardingWizardPendingMarker == true) {
            navController.navigate("welcome_guide?onboarding=true")
        }
    }

    // Frosted glass under the floating dock: the NavHost below is the backdrop, and the dock
    // draws a blurred copy of whatever sits behind it (RenderEffect on Android 12+, a
    // translucent scrim below that) - the way iOS's bar is a material rather than a colour.
    val dockHaze = remember { HazeState() }
    Scaffold(
        containerColor = LocalAppColors.current.background,
        bottomBar = {
            // Over a white page (Profile's QR pages) iOS's tab bar glass turns light, in dark mode
            // too: the glass takes on what is behind it.
            CompositionLocalProvider(
                LocalAppColors provides if (com.kachat.app.ui.screens.DockOverLightPage.active) com.kachat.app.ui.theme.LightAppColors else LocalAppColors.current
            ) {
                // Only show the floating tab bar on the top-level tab destinations —
                // "pushed" detail screens (chat thread, settings sub-screens, etc.) fill
                // the whole screen with their own Scaffold and must not have this
                // overlaid on top of them (it was blocking the chat input entirely).
                if (onTabRoute && !hideBottomBar) {
                    // Red dot on the Profile tab while the notification bell (which lives on the
                    // Profile screen) holds unread entries; Chats and Groups show their counts.
                    val dockNotifVm: com.kachat.app.viewmodels.NotificationCenterViewModel = hiltViewModel()
                    // The store is a singleton keyed per wallet internally; reload whenever the
                    // active account changes so a switch never leaves the previous account's unread
                    // dot (and entries) showing until something else happens to poke it.
                    val dockNotifAddress by walletViewModel.address.collectAsState()
                    LaunchedEffect(dockNotifAddress) {
                        dockNotifVm.store.reloadIfNeeded()
                    }
                    val dockNotifEntries by dockNotifVm.store.entries.collectAsState()
                    val dockNotifLastSeen by dockNotifVm.store.lastSeenAt.collectAsState()
                    val dockNotifUnread = dockNotifEntries.count { it.timestampMs > dockNotifLastSeen }
                    // Chats and Groups show their unread message counts (iOS 64d63846).
                    val dockChatsUnread by chatViewModel.chatListUnreadCount.collectAsState()
                    val dockGroupsUnread by chatViewModel.groupsUnreadCount.collectAsState()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // navigationBarsPadding() first so the 24dp visual margin sits above
                            // the system nav bar (gesture pill or 3-button bar) rather than being
                            // eaten by it — its height isn't the same on every device, so a fixed
                            // dp value alone left the tab bar sitting under/behind it on some phones.
                            .navigationBarsPadding()
                            // iOS 26's tab bar: 21pt in from each side (measured on the iPhone).
                            .padding(bottom = 12.dp, start = 21.dp, end = 21.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // Sliding a finger along the bar carries the lens; releasing lands on the
                        // tab under it, the way the glass follows a finger on iOS. Null while nobody
                        // is dragging, so the lens follows the selection instead.
                        var dockDragIndex by remember { mutableStateOf<Int?>(null) }

                        /** What a tap on a dock tab does - shared so a drag lands in the same place. */
                        fun goToDockTab(screen: Screen) {
                            val onOwnRoute = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                            if (onOwnRoute) {
                                walletViewModel.notifyTabReselected(screen.route)
                                return
                            }
                            navigateToTab(navController, screen)
                        }

                        // The slide handler below is keyed only on the tab order and the slot width,
                        // so it is created once and outlives every navigation. Calling goToDockTab
                        // from it directly used the closure from THAT first composition, whose
                        // currentDestination was the tab you started on: sliding back onto that tab
                        // later read as "already here" and did nothing. This always holds the current one.
                        val latestGoToDockTab by androidx.compose.runtime.rememberUpdatedState<(Screen) -> Unit> {
                            goToDockTab(it)
                        }

                        // Which tab the glass pill sits under - the same test each item makes for
                        // itself below, so the pill and the lit icon can never disagree.
                        // The tab you are IN, the way iOS's tab bar keeps the tab that pushed a screen
                        // lit. A dock tab's own route lights itself. A screen that is not a dock tab
                        // lights the tab it belongs to (a chat lights Chats, Manage Addresses Profile).
                        val selectedDockIndex = run {
                            val direct = localTabOrder.indexOfFirst { screen ->
                                currentDestination?.hierarchy?.any { it.route == screen.route } == true
                            }
                            if (direct >= 0) return@run direct
                            // The tab whose stack the screen was pushed onto: the nearest dock tab
                            // under it. iOS keeps that tab lit.
                            val underneath = dockBackStack.asReversed().drop(1).firstNotNullOfOrNull { entry ->
                                localTabOrder.indexOfFirst { it.route == entry.destination.route }.takeIf { it >= 0 }
                            }
                            if (underneath != null) return@run underneath
                            val owner = dockOwnerRoute(currentTopRoute) ?: return@run -1
                            localTabOrder.indexOfFirst { it.route == owner }
                        }
                        // A new dock arrangement builds a fresh bar (iOS d7c3fcf keys its TabView the
                        // same way), so every icon, label and the glass lens start from the tabs the
                        // bar now holds rather than from whichever tab held that slot before. The key
                        // changes only when the arrangement does.
                        val dockArrangementKey = localTabOrder.joinToString(",") { it.route }
                        key(dockArrangementKey) {
                        // Fixed at the bar's own height so the lens can stand proud of it without
                        // growing the dock or shifting it up the screen.
                        // iOS's bar is 60pt tall (measured on the iPhone 17).
                        BoxWithConstraints(Modifier.fillMaxWidth().height(60.dp)) {
                        // The pill slides between tabs rather than appearing under the new one, the
                        // way iOS's glass tab bar moves: one piece of glass that travels.
                        val dockItemWidth = (maxWidth - 4.dp) / localTabOrder.size.coerceAtLeast(1)
                        val pillIndex = dockDragIndex ?: selectedDockIndex
                        val pillOffset by animateDpAsState(
                            targetValue = 2.dp + dockItemWidth * pillIndex.coerceAtLeast(0),
                            // Under a finger it keeps up; left to itself it settles with a spring.
                            animationSpec = if (dockDragIndex != null) tween(90) else spring(dampingRatio = 0.75f, stiffness = 420f),
                            label = "dockPill",
                        )
                        // The bar itself: the backdrop blurred, tinted with the surface colour so it
                        // still reads as a bar in either theme. It holds no items - they are in the
                        // Row below, over the pill.
                        Box(
                            Modifier
                                .height(60.dp)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(30.dp))
                                // The shape goes to hazeChild itself: it paints the blurred backdrop
                                // to the shape it is given, and its default is a rectangle - which is
                                // what put a square panel behind the rounded bar.
                                .hazeChild(
                                    state = dockHaze,
                                    shape = RoundedCornerShape(30.dp),
                                    style = HazeStyle(
                                        // iOS's bar on black reads #191919 (the toolbar glass)
                                        tint = com.kachat.app.ui.screens.toolbarGlassFill().copy(alpha = 0.92f),
                                        blurRadius = 24.dp,
                                    ),
                                )
                                .border(0.5.dp, com.kachat.app.ui.screens.toolbarGlassEdge(), RoundedCornerShape(30.dp)),
                        )
                        // Drawn between the bar and its icons: a brighter pane of the same glass.
                        // Also while a finger is sliding: on a screen that is not a dock tab nothing is
                        // lit, and the slide used to carry no lens at all - it looked like it had not
                        // registered, though releasing still went to the tab.
                        if (dockDragIndex == null && selectedDockIndex >= 0) {
                            // At rest iOS 26 marks the tab with a filled capsule - #3A3A3A on the
                            // #191919 bar, 4pt inside its height (measured on the iPhone) - and no rim.
                            val restShape = RoundedCornerShape(percent = 50)
                            Box(
                                Modifier
                                    .offset(x = pillOffset)
                                    .padding(vertical = 4.dp)
                                    .width(dockItemWidth)
                                    .height(52.dp)
                                    .clip(restShape)
                                    .background(
                                        if (LocalAppColors.current.isDark) Color(0xFF3A3A3A)
                                        else Color.Black.copy(alpha = 0.07f)
                                    ),
                            )
                        } else if (dockDragIndex != null) {
                            // While a finger slides, the lens: a nearly clear bubble with a bright
                            // rim that stands a little proud of the bar, as iOS's does.
                            val pillShape = RoundedCornerShape(percent = 50)
                            Box(
                                Modifier
                                    .offset(x = pillOffset - 3.dp, y = (-4).dp)
                                    .width(dockItemWidth + 6.dp)
                                    .height(68.dp)
                                    .clip(pillShape)
                                    .hazeChild(
                                        state = dockHaze,
                                        shape = pillShape,
                                        style = HazeStyle(
                                            tint = Color.White.copy(alpha = 0.06f),
                                            blurRadius = 30.dp,
                                        ),
                                    )
                                    // The rim catches the light round its edge rather than being one
                                    // flat stroke - iOS's is iridescent.
                                    .border(
                                        width = 1.5.dp,
                                        brush = Brush.linearGradient(
                                            listOf(
                                                Color.White.copy(alpha = 0.55f),
                                                KaspaTeal.copy(alpha = 0.55f),
                                                Color.White.copy(alpha = 0.30f),
                                            ),
                                        ),
                                        shape = pillShape,
                                    ),
                            )
                        }
                        val dockDensity = LocalDensity.current
                        Row(
                            modifier = Modifier
                                .height(60.dp)
                                .fillMaxWidth()
                                // Watched on the INITIAL pass, so a slide can take over from the
                                // items underneath. A press that never moves is left to their tap;
                                // anything that travels carries the lens, whether it slides straight
                                // away or is held first, as on the iPhone. Nothing else on the bar
                                // wants this finger - tabs are arranged in Settings, not by dragging.
                                // Gesture navigation reserves a strip at each screen edge for Back,
                                // wider than the dock's side margin at higher Back sensitivity. A slide
                                // starting on the outermost tab began inside it, and Android took it.
                                .systemGestureExclusion()
                                .pointerInput(localTabOrder, dockItemWidth) {
                                    val slotPx = with(dockDensity) { dockItemWidth.toPx() }
                                    val startPadPx = with(dockDensity) { 2.dp.toPx() }
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                        var sliding = false
                                        try {
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            if (!change.pressed) {
                                                if (sliding) {
                                                    dockDragIndex?.let { index ->
                                                        localTabOrder.getOrNull(index)?.let { latestGoToDockTab(it) }
                                                    }
                                                }
                                                dockDragIndex = null
                                                break
                                            }
                                            val movedX = kotlin.math.abs(change.position.x - down.position.x)
                                            if (!sliding && movedX > viewConfiguration.touchSlop) sliding = true
                                            if (sliding) {
                                                change.consume()
                                                dockDragIndex = (((change.position.x - startPadPx) / slotPx).toInt())
                                                    .coerceIn(0, localTabOrder.lastIndex.coerceAtLeast(0))
                                            }
                                        }
                                        } finally {
                                            // A slide the system takes away - the back gesture, an
                                            // incoming call - cancels this rather than delivering a
                                            // release, and the lens used to stay stranded wherever the
                                            // finger was until the next touch.
                                            dockDragIndex = null
                                        }
                                    }
                                }
                                .padding(horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            localTabOrder.forEach { screen ->
                                // Keyed by route (not position), so a tab's own state stays with that
                                // tab when the arrangement changes in Settings rather than with
                                // whichever slot happens to render it.
                                key(screen.route) {
                                    // Standing ON this tab's own route, which is what the reselect
                                    // gesture below keys off.
                                    val onOwnRoute =
                                        currentDestination?.hierarchy?.any { it.route == screen.route } == true
                                    // Lit by the same answer the glass pill sits under - see
                                    // selectedDockIndex - so the two can never disagree. Lit only: a
                                    // tap from a screen below the tab still has to pop back up to it,
                                    // so this must not feed the reselect check (onOwnRoute does).
                                    val selected = localTabOrder.getOrNull(selectedDockIndex) == screen
                                    Box(
                                        modifier = Modifier
                                            .height(52.dp)
                                            .weight(1f)
                                            .clip(RoundedCornerShape(26.dp))
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
                                                    painter = rememberVectorPainter(screen.icon),
                                                    contentDescription = screen.label,
                                                    tint = if (selected) KaspaTeal else LocalAppColors.current.textPrimary,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                                // How many are waiting, not just that some are. A
                                                // bare dot said "something happened"; the number is
                                                // the difference between deciding to look now and
                                                // deciding to look later, and the dock is where that
                                                // decision gets made.
                                                val badgeCount = when (screen) {
                                                    Screen.Profile -> dockNotifUnread
                                                    Screen.Chats -> dockChatsUnread
                                                    Screen.Groups -> dockGroupsUnread
                                                    else -> 0
                                                }
                                                if (badgeCount > 0 && screen == Screen.Profile) {
                                                    // Profile: a red DOT while the bell holds anything
                                                    // unread - the bell itself is a dot, so the dock
                                                    // says the same thing.
                                                    // The entries/lastSeen flows above recompose this
                                                    // from any tab, on tab switches and at launch.
                                                    Box(
                                                        modifier = Modifier
                                                            .align(Alignment.TopEnd)
                                                            .offset(x = 4.dp, y = (-2).dp)
                                                            .size(10.dp)
                                                            .clip(RoundedCornerShape(50))
                                                            .background(Color(0xFFE0245E)),
                                                    )
                                                } else if (badgeCount > 0) {
                                                    // A BOX with a minimum square size, not a Text
                                                    // with padding. Padding alone gave a one-digit
                                                    // badge its text's width plus 8dp against its
                                                    // height plus 2dp - wider than it was tall, which
                                                    // is the oval. defaultMinSize floors both to the
                                                    // same 16dp, so one digit is a circle and only a
                                                    // longer label stretches it into a pill.
                                                    Box(
                                                        modifier = Modifier
                                                            .align(Alignment.TopEnd)
                                                            // Offset out over the icon's corner so a
                                                            // two- or three-character badge grows
                                                            // outward instead of shoving the icon.
                                                            .offset(x = 8.dp, y = (-6).dp)
                                                            .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                                                            .clip(RoundedCornerShape(50))
                                                            .background(Color(0xFFE0245E))
                                                            .padding(horizontal = 4.dp),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Text(
                                                            // Capped in the LABEL, not in the stored
                                                            // count: the real number survives a long
                                                            // absence and only its rendering shortens.
                                                            text = if (badgeCount > 99) "99+" else badgeCount.toString(),
                                                            color = Color.White,
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            maxLines = 1,
                                                        )
                                                    }
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = screen.label,
                                                color = if (selected) KaspaTeal else LocalAppColors.current.textPrimary,
                                                // Longer labels ("Public Chats") don't fit at 10sp once there are
                                                // enough tabs that each weight(1f) slot narrows below their natural
                                                // width — shrink just those instead of letting them clip/wrap and
                                                // get cut off by the fixed-height Box. Twelve characters need a third
                                                // step: at six tabs on a 320dp screen a slot is ~53dp, which 8sp
                                                // would run past, and softWrap = false clips rather than wraps.
                                                fontSize = when {
                                                    screen.label.length > 11 -> 7.sp
                                                    screen.label.length > 9 -> 8.sp
                                                    else -> 10.sp
                                                },
                                                maxLines = 1,
                                                softWrap = false,
                                                overflow = TextOverflow.Ellipsis,
                                                // iOS 26's tab labels are medium weight, selected or not -
                                                // only the colour marks the selected tab.
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        }
                        } // key(dockArrangementKey)
                        }
                    }
                }
        }
        }
    ) { innerPadding ->
        // The green dot's half sheet, hosted once for the whole app rather than by each of the
        // eight headers that draw a dot - so it comes up OVER whatever you were reading.
        ConnectionStatusOverlayHost()

        // Only the bottom-tab destinations sit inside this shell's floating nav bar and need
        // innerPadding reserved beneath them. "Pushed" detail screens (chat thread, settings
        // sub-screens, etc.) fill the whole screen with their own Scaffold — applying innerPadding
        // to the NavHost as a whole left permanent dead space at the bottom of every one of those,
        // which became a visible gap once a Scaffold there also added imePadding().
        NavHost(
            navController = navController,
            startDestination = Screen.Chats.route,
            // What the dock's glass samples.
            modifier = Modifier.haze(state = dockHaze),
            // NavHost's own default is a 700ms crossfade — noticeably sluggish for something
            // that happens on every single tab switch/screen push. A short, snappy fade reads
            // as instant without the jarring hard-cut of no animation at all.
            enterTransition = { fadeIn(animationSpec = tween(150)) },
            exitTransition = { fadeOut(animationSpec = tween(150)) },
            popEnterTransition = { fadeIn(animationSpec = tween(150)) },
            popExitTransition = { fadeOut(animationSpec = tween(150)) }
        ) {
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
                // Runs on under the dock, as iOS's list runs on under its tab bar (LocalDockInset).
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides innerPadding.calculateBottomPadding()) {
                    ChatsScreen(navController, walletViewModel, chatViewModel = chatViewModel)
                }
            }
            composable(
                Screen.Groups.route,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
            ) {
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides innerPadding.calculateBottomPadding()) {
                    com.kachat.app.ui.screens.GroupChatsScreen(navController, walletViewModel, chatViewModel = chatViewModel)
                }
            }
            composable(
                Screen.Profile.route,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
            ) {
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides innerPadding.calculateBottomPadding()) {
                    ProfileScreen(
                        viewModel = walletViewModel,
                        navController = navController
                    )
                }
            }
            // Its own tab since 2026-10-24 (iOS d54ea87): no Done button, the dock under it.
            composable(
                Screen.Settings.route,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
            ) {
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides innerPadding.calculateBottomPadding()) {
                    SettingsScreen(navController, walletViewModel = walletViewModel)
                }
            }
            composable("seed_phrase") {
                SeedPhraseScreen(
                    viewModel = walletViewModel,
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
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides innerPadding.calculateBottomPadding()) {
                    SpendingAddressTxHistoryScreen(
                        index = backStackEntry.arguments?.getInt("index") ?: 0,
                        onBack = { navController.popBackStack() },
                        viewModel = walletViewModel,
                        onOpenChat = { address -> navController.navigate("chat/$address") },
                    )
                }
            }

            // The chatting/identity address's "Manage Address" row (Profile > Chatting Address) -
            // field-for-field the same screen as spending_address_detail, just for the single
            // fixed identity address instead of a spending-chain index. Used to reuse
            // "cold_storage_tx_history/{address}" (labeled "Transaction History"), which had the
            // exact same watch-only-signer mismatch spending addresses had before getting their
            // own route above.
            composable("identity_address_detail") {
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides innerPadding.calculateBottomPadding()) {
                    IdentityAddressDetailScreen(
                        onBack = { navController.popBackStack() },
                        viewModel = walletViewModel,
                        onOpenChat = { address -> navController.navigate("chat/$address") },
                    )
                }
            }

            composable("manage_addresses") {
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides innerPadding.calculateBottomPadding()) {
                    ManageAddressesScreen(
                        viewModel = walletViewModel,
                        onBack = { navController.popBackStack() },
                        onNavigateToTxHistory = { index -> navController.navigate("spending_address_detail/$index") },
                        onOpenChattingAddress = { navController.navigate("identity_address_detail") },
                        onNavigateToHidden = { navController.navigate("manage_addresses_hidden") },
                        onNavigateToVisibility = { navController.navigate("manage_addresses_visibility") }
                    )
                }
            }

            composable("manage_addresses_visibility") {
                AddressVisibilityScreen(
                    viewModel = walletViewModel,
                    onBack = { navController.popBackStack() }
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

            composable("edit_kachat_profile") {
                com.kachat.app.ui.screens.KachatProfileEditorScreen(
                    onBack = { navController.popBackStack() },
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
                "welcome_guide?onboarding={onboarding}",
                arguments = listOf(
                    // Explicit presentation context (never inferred from persisted markers):
                    // true only for auto-presented onboarding runs, which are unskippable end
                    // to end. Help replays keep the default false and stay skippable.
                    navArgument("onboarding") { type = NavType.BoolType; defaultValue = false }
                )
            ) { backStackEntry ->
                WelcomeGuideScreen(
                    walletViewModel = walletViewModel,
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
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides innerPadding.calculateBottomPadding()) {
                    KnsDomainsScreen(
                        viewModel = walletViewModel,
                        onBack = { navController.popBackStack() },
                        // Message on a .kachat name's owner (testnet).
                        onOpenChat = { address -> navController.navigate("chat/$address") }
                    )
                }
            }

            composable("settings_section/{sectionKey}") { backStackEntry ->
                SettingsScreen(
                    navController = navController,
                    sectionKey = backStackEntry.arguments?.getString("sectionKey")
                )
            }

            // Settings > Storage sub-pages, one per backup provider (see StorageScreens.kt) -
            // the Storage section itself is just the hub of rows pointing here, like iOS.
            composable("storage_nextcloud") {
                NextcloudStorageScreen(onBack = { navController.popBackStack() })
            }

            composable("help") {
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides innerPadding.calculateBottomPadding()) {
                    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
                    HelpScreen(
                        onBack = { navController.popBackStack() },
                        onWelcomeGuide = { navController.navigate("welcome_guide") },
                    )
                }
            }

            composable("cache_settings") {
                val cacheManager: com.kachat.app.services.CacheManager = hiltViewModel<com.kachat.app.viewmodels.CacheViewModel>().cacheManager
                CacheSettingsScreen(
                    onBack = { navController.popBackStack() },
                    cacheManager = cacheManager,
                )
            }

            composable("open_source_licenses") {
                OpenSourceLicensesScreen(onBack = { navController.popBackStack() })
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

            composable("quick_reaction_settings") {
                QuickReactionSettingsScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                Screen.AddressBook.route,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
            ) {
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides innerPadding.calculateBottomPadding()) {
                    com.kachat.app.ui.screens.AddressBookScreen(
                        onBack = null,
                        onOpenChat = { address -> navController.navigate("chat/$address") },
                        walletViewModel = walletViewModel,
                    )
                }
            }

            // `group` is an optional query-style arg (defaults false) so the create button can
            // open this screen straight into group-builder mode.
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
                // The messages run on under the dock, as iOS's do under its tab bar; the composer
                // sits just above it (LocalDockInset). Only while the dock is actually visible:
                // once the keyboard opens, the dock goes behind it anyway, and the thread's own
                // bottomBar already applies imePadding() to clear the keyboard itself - reserving
                // both at once left a dead black gap between the message input and the keyboard.
                val imeVisible = WindowInsets.isImeVisible
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides if (imeVisible) 0.dp else innerPadding.calculateBottomPadding()) {
                    GroupChatThreadScreen(navController = navController, groupId = groupId, chatViewModel = chatViewModel, walletViewModel = walletViewModel)
                }
            }

            composable("group_chat_info/{groupId}") { backStackEntry ->
                val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
                GroupChatInfoScreen(navController = navController, groupId = groupId, chatViewModel = chatViewModel)
            }

            // Message Requests - people who wrote first and aren't accepted (iOS f7ca401).
            composable("message_requests") {
                com.kachat.app.ui.screens.MessageRequestsScreen(navController = navController, chatViewModel = chatViewModel)
            }

            // Chat thread — navigated to from ChatsScreen. paymentMode is an optional query-style
            // arg (defaults false) so a "Pay in Kaspa" shortcut elsewhere (e.g. a group sender's
            // avatar menu) can land the user straight in payment-entry mode.
            composable(
                "chat/{contactId}?paymentMode={paymentMode}",
                arguments = listOf(navArgument("paymentMode") { type = NavType.BoolType; defaultValue = false })
            ) { backStackEntry ->
                val contactId = backStackEntry.arguments?.getString("contactId") ?: return@composable
                val startInPaymentMode = backStackEntry.arguments?.getBoolean("paymentMode") ?: false
                // The messages run on under the dock and the composer sits just above it, as the
                // group thread's does (see there).
                val imeVisible = WindowInsets.isImeVisible
                CompositionLocalProvider(com.kachat.app.ui.screens.LocalDockInset provides if (imeVisible) 0.dp else innerPadding.calculateBottomPadding()) {
                    ChatThreadScreen(
                        navController = navController,
                        contactId = contactId,
                        chatViewModel = chatViewModel,
                        walletViewModel = walletViewModel,
                        startInPaymentMode = startInPaymentMode
                    )
                }
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

            composable("chat_info/{contactId}") { backStackEntry ->
                val contactId = backStackEntry.arguments?.getString("contactId") ?: return@composable
                ChatInfoScreen(
                    contactId = contactId,
                    onBack = { navController.popBackStack() },
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

        // iOS's in-app toast for every screen: over a tab it sits above the dock, as iOS's sits
        // above the tab bar; a pushed screen has it above the navigation bar and keyboard.
        val dockShown = onTabRoute && !hideBottomBar
        com.kachat.app.ui.screens.IosToastHost(
            modifier = Modifier.padding(bottom = if (dockShown) innerPadding.calculateBottomPadding() else 0.dp),
            bottomInsets = if (dockShown) {
                androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
            } else {
                androidx.compose.foundation.layout.WindowInsets.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Bottom)
            },
        )
    }
}

/**
 * The own address a tapped Profile-bell wallet row asks [MainShell] to open in its History sheet
 * (iOS 95e2cba `OwnAddressRoute.pending`). MainShell takes it and clears it.
 */
object OwnAddressRoute {
    val pending = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}

/**
 * One of your own addresses, opened from a tapped "Received" notification on its History tab
 * (iOS 13046ad `OwnAddressHistorySheet`): the chatting address or a spending address, each on the same screen you'd reach it through
 * (Profile > Chatting Address, Manage Addresses), resolved with its balance for the header. Those
 * screens already open on History; at the root of this sheet each puts Done top left in place of
 * its back arrow (closesWithDone), where iOS's sheet has its Done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OwnAddressHistorySheet(
    address: String,
    walletViewModel: WalletViewModel,
    onOpenChat: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var resolved by remember { mutableStateOf<OwnAddressResolution?>(null) }
    LaunchedEffect(address) {
        resolved = when {
            walletViewModel.address.value?.equals(address, ignoreCase = true) == true -> OwnAddressResolution.Chatting
            else -> walletViewModel.resolveOwnSpendingAddress(address)?.let { OwnAddressResolution.Spending(it) }
                ?: OwnAddressResolution.Unknown
        }
    }
    com.kachat.app.ui.theme.IosSheetColors(grouped = true) {
        ModalBottomSheet(
            scrimColor = com.kachat.app.ui.theme.IosSheetScrim,
            shape = com.kachat.app.ui.theme.IosSheetShape,
            tonalElevation = com.kachat.app.ui.theme.IosSheetTonalElevation,
            windowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars,
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = LocalAppColors.current.background,
            dragHandle = null,
        ) {
            // The sheet runs down behind the navigation bar, as iOS's does behind the home indicator;
            // its content stays above it.
            Column(Modifier.navigationBarsPadding()) {
                Box(Modifier.fillMaxSize()) {
                    when (val target = resolved) {
                        OwnAddressResolution.Chatting -> IdentityAddressDetailScreen(
                            onBack = onDismiss,
                            viewModel = walletViewModel,
                            onOpenChat = onOpenChat,
                            closesWithDone = true,
                        )
                        is OwnAddressResolution.Spending -> SpendingAddressTxHistoryScreen(
                            index = target.entry.index,
                            onBack = onDismiss,
                            viewModel = walletViewModel,
                            onOpenChat = onOpenChat,
                            fallbackEntry = target.entry,
                            closesWithDone = true,
                        )
                        OwnAddressResolution.Unknown, null -> Column(Modifier.fillMaxSize()) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                                TextButton(onClick = onDismiss) {
                                    Text(
                                        androidx.compose.ui.res.stringResource(com.kachat.app.R.string.done),
                                        color = KaspaTeal,
                                    )
                                }
                            }
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                if (target == null) {
                                    CircularProgressIndicator(color = KaspaTeal)
                                } else {
                                    Text(
                                        androidx.compose.ui.res.stringResource(com.kachat.app.R.string.own_address_not_in_wallet),
                                        color = LocalAppColors.current.textSecondary,
                                        style = MaterialTheme.typography.bodyMedium,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(16.dp),
                                    )
                                }
                            }
                        }
                    }
                    // Copies from these screens confirm in the sheet's own toast.
                    com.kachat.app.ui.screens.IosToastHost(bottomInsets = com.kachat.app.ui.screens.SheetToastInsets)
                }
            }
        }
    }
}

/** Which of your addresses a "Received" notification named (iOS 13046ad `Resolved`). */
private sealed interface OwnAddressResolution {
    data object Chatting : OwnAddressResolution
    data class Spending(val entry: com.kachat.app.services.WalletService.SpendingAddressEntry) : OwnAddressResolution
    data object Unknown : OwnAddressResolution
}
