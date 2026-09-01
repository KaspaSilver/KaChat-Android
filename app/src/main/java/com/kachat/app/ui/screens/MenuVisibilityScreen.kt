package com.kachat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
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
import androidx.compose.runtime.key
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
            ReorderableList(
                items = dock,
                actionLabel = "Move to ${Screen.KaspaHub.label}",
                actionEnabled = { true },
                onAction = { moveAcross(it, toDock = false) },
                onReorder = { commit(it, hub) },
                colors = colors
            )
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
                ReorderableList(
                    items = hub,
                    actionLabel = "Move to Dock",
                    // Refused, not ignored: without this the tap looks broken.
                    actionEnabled = { !dockIsFull },
                    onAction = { moveAcross(it, toDock = true) },
                    onReorder = { commit(dock, it) },
                    colors = colors
                )
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

/** Fixed so a drag's target slot is arithmetic rather than a measurement of every row. */
private val ROW_HEIGHT = 56.dp

/**
 * A hold-then-drag reorderable list, matching how iOS's Customize Dock reorders.
 *
 * Deliberately NOT animated. The dragged row is positioned by an offset that cancels how far its
 * own slot has travelled, and on iOS animating the slot change while that offset jumped instantly
 * drew the row a full slot away for the length of the animation. With both instant they cancel
 * exactly, so the row stays under the finger and the others snap past it.
 *
 * A long press is required first, so an ordinary swipe still scrolls the screen rather than
 * picking a row up by accident.
 */
@Composable
private fun ReorderableList(
    items: List<Screen>,
    actionLabel: String,
    actionEnabled: () -> Boolean,
    onAction: (Screen) -> Unit,
    onReorder: (List<Screen>) -> Unit,
    colors: com.kachat.app.ui.theme.AppColors,
) {
    val density = LocalDensity.current
    val rowHeightPx = with(density) { ROW_HEIGHT.toPx() }

    var draggingRoute by remember { mutableStateOf<String?>(null) }
    var dragStartIndex by remember { mutableStateOf(0) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    // The order being previewed mid-drag; committed on release and discarded otherwise.
    var preview by remember { mutableStateOf<List<Screen>?>(null) }

    val shown = preview ?: items

    Column {
        shown.forEachIndexed { index, screen ->
            // Keyed by route, which is what keeps a drag alive across a swap.
            //
            // Compose memoizes by call-site POSITION, so without this the row at index 2 becomes a
            // different Screen the moment the order changes: its pointerInput gets new keys, the
            // gesture restarts, and the drag dies mid-swipe. That reads as the drag stuttering or
            // dropping the row, not as the reorder it actually is.
            key(screen.route) {
            val isDragging = draggingRoute == screen.route
            PlacementRow(
                screen = screen,
                pinned = screen.route in PINNED_DOCK_ROUTES,
                actionLabel = actionLabel,
                actionEnabled = actionEnabled(),
                isDragging = isDragging,
                // A LAMBDA, read in the draw phase - not a value read here during composition.
                // Reading dragOffsetY at composition meant every pixel of finger movement
                // recomposed this whole list and rebuilt every row's modifier chain, which is
                // what made the drag stutter. Read from inside graphicsLayer it only invalidates
                // drawing, so a moving finger costs one redraw of one row.
                //
                // The subtraction cancels how far this row's slot has moved since the drag began,
                // so it tracks the finger rather than jumping a slot each time it swaps.
                offsetProvider = {
                    if (isDragging) dragOffsetY - (index - dragStartIndex) * rowHeightPx else 0f
                },
                onAction = { onAction(screen) },
                colors = colors,
                // Keyed on the ROUTE alone. `items` is recomputed on every recomposition of the
                // screen, so including it restarted the gesture detector for reasons that have
                // nothing to do with this row. The handler reads the current order from state
                // when it runs, so it is never working from a stale list.
                dragModifier = Modifier.pointerInput(screen.route) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            val order = preview ?: items
                            dragStartIndex = order.indexOfFirst { it.route == screen.route }
                            if (dragStartIndex < 0) return@detectDragGesturesAfterLongPress
                            preview = order
                            draggingRoute = screen.route
                            dragOffsetY = 0f
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            if (draggingRoute != screen.route) return@detectDragGesturesAfterLongPress
                            dragOffsetY += amount.y
                            val order = (preview ?: items).toMutableList()
                            val current = order.indexOfFirst { it.route == screen.route }
                            if (current < 0) return@detectDragGesturesAfterLongPress
                            // From the FIXED start index plus whole slots travelled, so the result
                            // depends only on where the finger is - not on the order of updates,
                            // which is what stops a fast drag oscillating.
                            val slots = (dragOffsetY / rowHeightPx).roundToInt()
                            val target = (dragStartIndex + slots).coerceIn(0, order.lastIndex)
                            if (target != current) {
                                order.add(target, order.removeAt(current))
                                preview = order
                            }
                        },
                        onDragEnd = {
                            preview?.let { if (it != items) onReorder(it) }
                            draggingRoute = null
                            dragOffsetY = 0f
                            preview = null
                        },
                        onDragCancel = {
                            draggingRoute = null
                            dragOffsetY = 0f
                            preview = null
                        }
                    )
                }
            )
            }
        }
    }
}

@Composable
private fun PlacementRow(
    screen: Screen,
    pinned: Boolean,
    actionLabel: String,
    actionEnabled: Boolean,
    isDragging: Boolean,
    offsetProvider: () -> Float,
    onAction: () -> Unit,
    colors: com.kachat.app.ui.theme.AppColors,
    dragModifier: Modifier,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .zIndex(if (isDragging) 1f else 0f)
            // graphicsLayer, not offset: a translation change here is a DRAW-phase invalidation,
            // where an offset would re-run layout for the whole column on every frame.
            .graphicsLayer { translationY = offsetProvider() }
            .background(if (isDragging) colors.surface else Color.Transparent)
            .then(dragModifier)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // The affordance, matching the handle iOS shows in edit mode.
        Icon(
            Icons.Default.DragIndicator,
            contentDescription = "Reorder ${screen.label}",
            tint = colors.textSecondary.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp)
        )
        Icon(
            painter = if (screen.usesKaspaLogo) {
                androidx.compose.ui.res.painterResource(com.kachat.app.R.drawable.ic_kaspa_logo)
            } else {
                androidx.compose.ui.graphics.vector.rememberVectorPainter(screen.icon)
            },
            contentDescription = null,
            tint = KaspaTeal,
            modifier = Modifier.size(20.dp)
        )
        Text(
            screen.hubTitle,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
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
