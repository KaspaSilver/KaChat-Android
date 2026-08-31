package com.kachat.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.kachat.app.R
import com.kachat.app.ui.ASSIGNABLE_TAB_ROUTES
import com.kachat.app.ui.MAX_DOCK_ITEMS
import com.kachat.app.ui.PINNED_DOCK_ROUTES
import com.kachat.app.ui.Screen
import com.kachat.app.ui.hubTitle
import com.kachat.app.ui.kaspaHubSections
import com.kachat.app.ui.resolveDock
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.WalletViewModel

/**
 * Settings > Customization > Customize Dock - where each tab lives.
 *
 * Placement, not visibility. Every tab is either in the dock or in Kaspa Hub, and it is always in
 * exactly one of them, so nothing can end up nowhere. That was possible before: tabs were toggled
 * on or off and the dock then took the first five of an order, silently dropping the rest.
 *
 * Kaspa Hub and Profile are pinned to the dock ([PINNED_DOCK_ROUTES]) - the Hub because it holds
 * whatever is not in the dock, Profile because it is the way to Settings and the account list.
 * Both sections reorder, so a tab moved across can be put exactly where it is wanted.
 *
 * Matches iOS's MenuVisibilityView.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuVisibilityScreen(
    navController: NavController,
    walletViewModel: WalletViewModel = hiltViewModel()
) {
    val colors = LocalAppColors.current
    val hiddenTabs by walletViewModel.hiddenTabs.collectAsState()
    val childModeOn by walletViewModel.childModeEnabled.collectAsState()
    val dockRoutes by walletViewModel.dockTabs.collectAsState()
    val hubRoutes by walletViewModel.hubTabs.collectAsState()

    val dock = resolveDock(dockRoutes, hiddenTabs, childModeOn)
    val hub = kaspaHubSections(dockRoutes, hubRoutes, hiddenTabs, childModeOn)
    val dockIsFull = dock.size >= MAX_DOCK_ITEMS

    fun commit(newDock: List<Screen>, newHub: List<Screen>) {
        walletViewModel.setPlacement(newDock.map { it.route }, newHub.map { it.route })
    }

    fun moveAcross(screen: Screen, toDock: Boolean) {
        if (screen.route in PINNED_DOCK_ROUTES) return
        val d = dock.toMutableList()
        val h = hub.toMutableList()
        if (toDock) {
            if (d.size >= MAX_DOCK_ITEMS || screen in d) return
            h.remove(screen)
            d.add(screen)
        } else {
            d.remove(screen)
            if (screen !in h) h.add(screen)
        }
        commit(d, h)
    }

    fun shift(list: List<Screen>, index: Int, delta: Int, isDock: Boolean) {
        val target = index + delta
        if (target !in list.indices) return
        val reordered = list.toMutableList()
        reordered.add(target, reordered.removeAt(index))
        if (isDock) commit(reordered, hub) else commit(dock, reordered)
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.menu),
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SectionHeader("In Your Dock", "${dock.size} of $MAX_DOCK_ITEMS", dockIsFull, colors)
            dock.forEachIndexed { index, screen ->
                PlacementRow(
                    screen = screen,
                    pinned = screen.route in PINNED_DOCK_ROUTES,
                    actionLabel = "Move to ${Screen.KaspaHub.label}",
                    actionEnabled = true,
                    canMoveUp = index > 0,
                    canMoveDown = index < dock.lastIndex,
                    onAction = { moveAcross(screen, toDock = false) },
                    onMoveUp = { shift(dock, index, -1, isDock = true) },
                    onMoveDown = { shift(dock, index, 1, isDock = true) },
                    colors = colors
                )
            }
            Text(
                if (dockIsFull) {
                    "The dock is full. Move something to ${Screen.KaspaHub.label} to free a slot."
                } else {
                    "${Screen.KaspaHub.label} and ${Screen.Profile.label} always stay here - the Hub holds everything below, and Profile is the way to Settings and your accounts."
                },
                color = colors.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            SectionHeader("In ${Screen.KaspaHub.label}", null, false, colors)
            if (hub.isEmpty()) {
                Text(
                    "Everything is in your dock.",
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                hub.forEachIndexed { index, screen ->
                    PlacementRow(
                        screen = screen,
                        pinned = false,
                        actionLabel = "Move to Dock",
                        // Refused, not ignored: without this the tap looks broken.
                        actionEnabled = !dockIsFull,
                        canMoveUp = index > 0,
                        canMoveDown = index < hub.lastIndex,
                        onAction = { moveAcross(screen, toDock = true) },
                        onMoveUp = { shift(hub, index, -1, isDock = false) },
                        onMoveDown = { shift(hub, index, 1, isDock = false) },
                        colors = colors
                    )
                }
            }
            Text(
                "Opened from the ${Screen.KaspaHub.label} tab, in this order. Nothing here is switched off - it is one tap further away than the dock.",
                color = colors.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    trailing: String?,
    warn: Boolean,
    colors: com.kachat.app.ui.theme.AppColors,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = colors.textPrimary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            Text(
                trailing,
                color = if (warn) androidx.compose.ui.graphics.Color(0xFFFFA726) else colors.textSecondary,
                fontSize = 12.sp
            )
        }
    }
}

/**
 * Up/down controls rather than a drag: Compose has no equivalent of the reorderable list iOS gets
 * for free, and a hand-rolled drag was removed from the portfolio cards for being unreliable. With
 * a handful of rows these are quicker anyway, and they work with TalkBack.
 */
@Composable
private fun PlacementRow(
    screen: Screen,
    pinned: Boolean,
    actionLabel: String,
    actionEnabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onAction: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    colors: com.kachat.app.ui.theme.AppColors,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(screen.icon, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
        Text(
            screen.hubTitle,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = "Move ${screen.label} up",
                tint = if (canMoveUp) KaspaTeal else colors.textSecondary.copy(alpha = 0.4f)
            )
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = "Move ${screen.label} down",
                tint = if (canMoveDown) KaspaTeal else colors.textSecondary.copy(alpha = 0.4f)
            )
        }
        Spacer(Modifier.width(4.dp))
        if (pinned) {
            Text("Always in dock", color = colors.textSecondary, fontSize = 11.sp)
        } else {
            TextButton(onClick = onAction, enabled = actionEnabled) {
                Text(
                    actionLabel,
                    color = if (actionEnabled) KaspaTeal else colors.textSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}
