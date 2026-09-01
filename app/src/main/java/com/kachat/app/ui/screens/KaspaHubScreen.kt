package com.kachat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.kachat.app.ui.Screen
import com.kachat.app.ui.hubTitle
import com.kachat.app.ui.kaspaHubSections
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

    val sections = kaspaHubSections(dockRoutes, hubRoutes, hiddenTabs, childMode)
    var openSection by remember { mutableStateOf<Screen?>(null) }
    var websitesOpen by remember { mutableStateOf(false) }

    // Re-tapping the Kaspa Hub dock item steps back out to the grid - the same button that got you
    // in gets you out, so nothing competes with each section's own navigation.
    LaunchedEffect(reselect) {
        if (reselect.second == Screen.KaspaHub.route) {
            openSection = null
            websitesOpen = false
        }
    }
    // A section moved into the dock (or hidden) must not stay open here, or it is on screen twice.
    LaunchedEffect(sections) {
        if (openSection != null && openSection !in sections) openSection = null
    }

    when {
        websitesOpen -> KaspaAppsScreen(
            onBack = { websitesOpen = false },
            onOpen = { app ->
                navController.navigate(
                    "in_app_browser?url=${java.net.URLEncoder.encode(app.url, "UTF-8")}" +
                        "&title=${java.net.URLEncoder.encode(app.name, "UTF-8")}"
                )
            }
        )
        openSection == Screen.Chats -> ChatsScreen(navController, walletViewModel, chatViewModel = chatViewModel)
        openSection == Screen.Portfolio -> PortfolioScreen(navController = navController)
        openSection == Screen.ColdStorage -> ColdStorageListScreen(navController = navController, walletViewModel = walletViewModel)
        openSection == Screen.KaPosts -> KaPostsScreen(navController, walletViewModel = walletViewModel)
        openSection == Screen.Broadcasts -> BroadcastListScreen(
            navController = navController,
            onBack = { openSection = null }
        )
        openSection == Screen.Swap -> SwapScreen(navController = navController)
        else -> HubGrid(
            sections = sections,
            onOpenSection = { openSection = it },
            onOpenWebsites = { websitesOpen = true },
            onCustomize = { navController.navigate("settings_menu") },
            colors = colors
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HubGrid(
    sections: List<Screen>,
    onOpenSection: (Screen) -> Unit,
    onOpenWebsites: () -> Unit,
    onCustomize: () -> Unit,
    colors: com.kachat.app.ui.theme.AppColors,
) {
    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        Screen.KaspaHub.label,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    // Straight to Customize Dock - the one screen that decides what is in here.
                    IconButton(onClick = onCustomize) {
                        Icon(Icons.Default.Tune, contentDescription = "Customize Dock", tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background)
            )
        }
    ) { padding ->
        // Three across, matching iOS. Kaspa Websites is appended as a tile with no Screen behind
        // it, since it has no dock tab of its own on Android.
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
        ) {
            items(sections, key = { it.route }) { screen ->
                HubTile(
                    label = screen.hubTitle,
                    icon = screen.icon,
                    useKaspaLogo = screen.usesKaspaLogo,
                    colors = colors,
                    onClick = { onOpenSection(screen) }
                )
            }
            item(key = "kaspa_websites") {
                HubTile(
                    label = "Kaspa Websites",
                    icon = Icons.Default.Public,
                    colors = colors,
                    onClick = onOpenWebsites
                )
            }
        }
    }
}

/**
 * One tile: a square whose size comes from the grid column, with the label laid OVER it.
 *
 * The overlay is the point - a longer name is a taller label, and letting the content size the
 * tile would make one square bigger than its neighbours. This way every tile is identical whatever
 * it is called. Matches iOS.
 */
@Composable
private fun HubTile(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    colors: com.kachat.app.ui.theme.AppColors,
    onClick: () -> Unit,
    /** Draw the bundled Kaspa mark instead of [icon]. */
    useKaspaLogo: Boolean = false,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .clickable { onClick() }
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = if (useKaspaLogo) {
                    androidx.compose.ui.res.painterResource(com.kachat.app.R.drawable.ic_kaspa_logo)
                } else {
                    androidx.compose.ui.graphics.vector.rememberVectorPainter(icon)
                },
                contentDescription = null,
                tint = KaspaTeal,
                modifier = Modifier.size(28.dp)
            )
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
}
