package com.kachat.app.ui.screens

import com.kachat.app.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import com.kachat.app.ui.ALWAYS_VISIBLE_TAB_ROUTES
import com.kachat.app.ui.bottomNavItems
import com.kachat.app.ui.Screen
import com.kachat.app.ui.kaspaHubSections
import com.kachat.app.ui.resolveTabOrder
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.WalletViewModel

/**
 * Settings > Customization > Customize Dock — which bottom-nav tabs show up, and in what order.
 * Chats/Profile are permanently on (Settings itself is reached from Profile, not its own tab,
 * so it isn't listed here at all); every other tab can be hidden. There's no "+ More" dock
 * entry or enabled-tab limit anymore: anything can be toggled on, and the 5-item dock cap does
 * the rest — anything over the cap tail-drops out of the dock and is reached through Kaspa Hub
 * instead, which each row says on itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuVisibilityScreen(
    navController: NavController,
    walletViewModel: WalletViewModel = hiltViewModel()
) {
    val hiddenTabs by walletViewModel.hiddenTabs.collectAsState()
    val tabOrder by walletViewModel.tabOrder.collectAsState()
    // Child Mode removes Swap/KaPosts/Broadcasts from the whole app - their rows disappear from
    // this screen too so they can't even be flipped "for later" while it's on. Purely a render-
    // time filter: the stored per-account order/hidden prefs are never rewritten, so turning
    // Child Mode off restores the user's own arrangement untouched.
    val childModeOn by walletViewModel.childModeEnabled.collectAsState()
    // No enabled-tab limit: everything can be ON at once, and the 5-item dock cap handles the
    // rest. Where a feature ENDED UP is what a row needs to say - in the dock, in Kaspa Hub, or
    // nowhere because the Hub is off too, which is the only way to lose access to an enabled
    // feature and so the one most worth stating.
    val inDock = resolveTabOrder(tabOrder, hiddenTabs, childModeOn).toSet()
    val inHub = kaspaHubSections(tabOrder, hiddenTabs, childModeOn).toSet()
    // Resolved up front: stringResource is itself @Composable and cannot be called from a plain
    // local function.
    val hintInDock = stringResource(R.string.placement_in_dock)
    val hintInHub = stringResource(R.string.placement_in_kaspa_hub)
    val hintNowhere = stringResource(R.string.placement_nowhere)
    fun placementHint(screen: Screen): String? = when {
        screen.route in hiddenTabs -> null
        screen in inDock -> hintInDock
        screen in inHub -> hintInHub
        else -> hintNowhere
    }
    val kaPostsReTapHint = placementHint(Screen.KaPosts)
    val broadcastsReTapHint = placementHint(Screen.Broadcasts)

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.menu), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                stringResource(R.string.choose_which_tabs_appear_in_your),
                color = LocalAppColors.current.textSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            // Live dock preview (matches iOS): what the bottom bar shows right now - hold a
            // tab and slide it sideways to move it; hidden/cycled tabs keep their slots.
            DockPreviewStrip(
                visibleRoutes = com.kachat.app.ui.resolveTabOrder(tabOrder, hiddenTabs, childModeOn).map { it.route },
                fullOrder = run {
                    val known = bottomNavItems.map { it.route }
                    val resolved = tabOrder.filter { it in known }
                    resolved + known.filter { it !in resolved }
                },
                onReorder = { walletViewModel.setTabOrder(it) }
            )

            // Rows render in the DOCK'S order and reorder from here (the in-dock drag gesture
            // is gone, matching iOS): the arrows move a tab up/down in the persisted order.
            // While Child Mode is on, the Swap/KaPosts/Broadcasts rows aren't rendered at all -
            // the up/down arrows then move relative to the *displayed* neighbors, merged back
            // into the full persisted order so hidden routes keep their stored slots.
            val known = bottomNavItems.map { it.route }
            val resolvedOrder = tabOrder.filter { it in known }
            val fullOrder = resolvedOrder + known.filter { it !in resolvedOrder }
            val displayedOrder = if (childModeOn) {
                fullOrder.filter { it !in com.kachat.app.ui.CHILD_MODE_HIDDEN_ROUTES }
            } else fullOrder
            // Applies a reorder of the displayed rows back onto the full stored order: displayed
            // slots take the new sequence, non-displayed (child-hidden) routes stay where they were
            // - same merge the DockPreviewStrip uses, so nothing ever bakes the masked state in.
            fun persistDisplayedOrder(newDisplayed: List<String>) {
                val displayedSet = displayedOrder.toSet()
                var next = 0
                walletViewModel.setTabOrder(fullOrder.map { r -> if (r in displayedSet) newDisplayed[next++] else r })
            }
            Surface(
                color = LocalAppColors.current.surface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    displayedOrder.forEachIndexed { index, route ->
                        val screen = bottomNavItems.first { it.route == route }
                        if (index > 0) HorizontalDivider(color = LocalAppColors.current.divider)
                        val locked = route in com.kachat.app.ui.ALWAYS_VISIBLE_TAB_ROUTES
                        MenuVisibilityRow(
                            icon = screen.icon,
                            label = when (route) {
                                "kaposts" -> stringResource(R.string.kaposts)
                                else -> screen.label
                            },
                            checked = locked || route !in hiddenTabs,
                            locked = locked,
                            hint = when (route) {
                                "kaposts" -> kaPostsReTapHint
                                "broadcasts" -> broadcastsReTapHint
                                else -> null
                            },
                            onToggle = { checked -> walletViewModel.setTabHidden(route, !checked) },
                            onMoveUp = if (index > 0) ({
                                persistDisplayedOrder(displayedOrder.toMutableList().apply { add(index - 1, removeAt(index)) })
                            }) else null,
                            onMoveDown = if (index < displayedOrder.lastIndex) ({
                                persistDisplayedOrder(displayedOrder.toMutableList().apply { add(index + 1, removeAt(index)) })
                            }) else null
                        )
                    }
                }
            }
            if (childModeOn) {
                Text(
                    stringResource(R.string.child_mode_menu_footer),
                    color = LocalAppColors.current.textSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun MenuVisibilityRow(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    locked: Boolean,
    hint: String? = null,
    onToggle: (Boolean) -> Unit = {},
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !locked) { onToggle(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Reorder controls - dock position changes here since 4.0, not by dragging the dock.
        Column {
            Icon(
                Icons.Default.KeyboardArrowUp, "Move up",
                tint = if (onMoveUp != null) LocalAppColors.current.textSecondary else LocalAppColors.current.textSecondary.copy(alpha = 0.25f),
                modifier = Modifier.size(20.dp).clickable(enabled = onMoveUp != null) { onMoveUp?.invoke() }
            )
            Icon(
                Icons.Default.KeyboardArrowDown, "Move down",
                tint = if (onMoveDown != null) LocalAppColors.current.textSecondary else LocalAppColors.current.textSecondary.copy(alpha = 0.25f),
                modifier = Modifier.size(20.dp).clickable(enabled = onMoveDown != null) { onMoveDown?.invoke() }
            )
        }
        Spacer(Modifier.width(12.dp))
        Icon(icon, null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
            if (locked) {
                Text(stringResource(R.string.always_shown), color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
            } else if (hint != null) {
                Text(hint, color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
            }
        }
        Icon(
            if (checked) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = if (checked) "Shown" else "Hidden",
            tint = if (checked) (if (locked) LocalAppColors.current.textSecondary else KaspaTeal) else LocalAppColors.current.textSecondary,
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * The Customize Menu dock preview (matches iOS): renders the CURRENT visible dock; hold a tab
 * and drag sideways to move it. On release, the new visible sequence merges back into the
 * full persisted order - hidden/cycled tabs keep their relative slots.
 */
@Composable
private fun DockPreviewStrip(
    visibleRoutes: List<String>,
    fullOrder: List<String>,
    onReorder: (List<String>) -> Unit,
) {
    var order by androidx.compose.runtime.remember(visibleRoutes) { androidx.compose.runtime.mutableStateOf(visibleRoutes) }
    var draggedRoute by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var dragOffsetX by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0f) }
    var itemWidthPx by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0f) }

    Text(
        "Dock preview - hold and slide a tab to move it",
        color = LocalAppColors.current.textSecondary,
        fontSize = 13.sp,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    Surface(
        color = LocalAppColors.current.surface,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            order.forEach { route ->
                val screen = bottomNavItems.first { it.route == route }
                val isDragging = draggedRoute == route
                androidx.compose.runtime.key(route) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .zIndex(if (isDragging) 1f else 0f)
                            .offset { IntOffset(if (isDragging) dragOffsetX.roundToInt() else 0, 0) }
                            .onSizeChanged { itemWidthPx = it.width.toFloat() }
                            .pointerInput(route) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggedRoute = route
                                        dragOffsetX = 0f
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffsetX += dragAmount.x
                                        if (itemWidthPx <= 0f) return@detectDragGesturesAfterLongPress
                                        val current = order.indexOf(route)
                                        val shift = (dragOffsetX / itemWidthPx).roundToInt()
                                        if (shift == 0) return@detectDragGesturesAfterLongPress
                                        val target = (current + shift).coerceIn(0, order.lastIndex)
                                        if (target != current) {
                                            order = order.toMutableList().apply { add(target, removeAt(current)) }
                                            dragOffsetX -= shift * itemWidthPx
                                        }
                                    },
                                    onDragEnd = {
                                        draggedRoute = null
                                        dragOffsetX = 0f
                                        // Merge the reordered visible tabs back into the full
                                        // order: visible slots take the new sequence, hidden/
                                        // cycled tabs stay where they were.
                                        val visibleSet = visibleRoutes.toSet()
                                        var nextVisible = 0
                                        val merged = fullOrder.map { r ->
                                            if (r in visibleSet) order[nextVisible++] else r
                                        }
                                        onReorder(merged)
                                    },
                                    onDragCancel = {
                                        draggedRoute = null
                                        dragOffsetX = 0f
                                        order = visibleRoutes
                                    }
                                )
                            }
                    ) {
                        Icon(screen.icon, screen.label, tint = if (isDragging) KaspaTeal else LocalAppColors.current.textPrimary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.height(3.dp))
                        Text(
                            screen.label,
                            color = if (isDragging) KaspaTeal else LocalAppColors.current.textPrimary,
                            fontSize = 9.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}
