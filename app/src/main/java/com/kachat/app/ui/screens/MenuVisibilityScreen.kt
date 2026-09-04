package com.kachat.app.ui.screens

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.kachat.app.R
import com.kachat.app.ui.MAX_DOCK_ITEMS
import com.kachat.app.ui.PINNED_DOCK_ROUTES
import com.kachat.app.ui.Screen
import com.kachat.app.ui.hubTitle
import com.kachat.app.ui.kaspaHubSections
import com.kachat.app.ui.resolveDock
import com.kachat.app.ui.theme.AppColors
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.WalletViewModel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Settings > Customization > Customize Dock - where each tab lives, arranged on a picture of the
 * thing being arranged.
 *
 * The screen used to be two lists of rows with "Move to Dock" buttons and drag handles. It worked,
 * but it asked you to hold a layout in your head: the dock is a bar of five at the bottom of the
 * screen and Kaspa Hub is a three-across grid, and neither looked anything like a table row. So
 * this is the Hub grid up top and the dock bar along the bottom, both drawn the way they actually
 * appear.
 *
 * Two gestures, one job each. A TAP moves a tab between the two: tap something in the Hub and it
 * joins the dock, tap something in the dock and it goes back to the Hub. A HOLD-AND-DRAG only
 * reorders, within whichever section the tab is already in.
 *
 * Placement, not visibility. Every tab is either in the dock or in the Hub, and always in exactly
 * one of them, so nothing can end up nowhere. Kaspa Hub and Profile are pinned to the dock
 * ([PINNED_DOCK_ROUTES]) - the Hub because it is what holds everything not in the dock, Profile
 * because it is the way to Settings and your accounts.
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

    val haptics = LocalHapticFeedback.current
    /** Set when a tap cannot be honoured, so the reason appears instead of the tap looking dead. */
    var refusal by remember { mutableStateOf<String?>(null) }

    fun commit(newDock: List<Screen>, newHub: List<Screen>) {
        refusal = null
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        walletViewModel.setPlacement(newDock.map { it.route }, newHub.map { it.route })
    }

    fun refuse(message: String) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        refusal = message
    }

    /** Tap in the Hub: join the dock, at the end, where the empty slot you can see already is. */
    fun moveToDock(screen: Screen) {
        if (dockIsFull) {
            refuse("Your dock is full. Tap something in the dock to move it up here first.")
            return
        }
        commit(dock.filter { it != screen } + screen, hub.filter { it != screen })
    }

    /** Tap in the dock: back to the Hub, at the end of the grid. */
    fun moveToHub(screen: Screen) {
        if (screen.route in PINNED_DOCK_ROUTES) {
            refuse("${screen.label} has to stay in your dock.")
            return
        }
        commit(dock.filter { it != screen }, hub.filter { it != screen } + screen)
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
        },
        bottomBar = {
            DockPreview(
                dock = dock,
                dockIsFull = dockIsFull,
                colors = colors,
                onTap = { moveToHub(it) },
                onReorder = { commit(it, hub) },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            Text(Screen.KaspaHub.label, color = colors.textPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Opened from the ${Screen.KaspaHub.label} tab, in this order. Nothing here is " +
                    "switched off - it is one tap further away than the dock. Tap one to move it " +
                    "into your dock, or hold and drag to reorder.",
                color = colors.textSecondary,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))

            if (hub.isEmpty()) {
                EmptyHubHint(colors)
            } else {
                HubGrid(
                    hub = hub,
                    colors = colors,
                    onTap = { moveToDock(it) },
                    onReorder = { commit(dock, it) },
                )
            }

            refusal?.let { message ->
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(message, color = colors.textSecondary, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Grid spacing, shared by the layout and by the drag's slot arithmetic. */
private val GRID_SPACING = 14.dp

/**
 * Tap and hold-to-drag from ONE gesture, so they can never both fire and never miss each other.
 *
 * A `clickable` next to a `detectDragGesturesAfterLongPress` is two detectors racing over the same
 * touch: `clickable` still reports a click when the finger comes up after a long press (which sent
 * the item you were reordering into Kaspa Hub), and the pick-up could be lost to whichever
 * detector claimed the pointer first (which is a hold that selects nothing). Deciding it here
 * removes the race: one touch is a tap OR a drag, settled by whether it survives the long-press
 * timeout, and the pointer that went down is the one the drag follows.
 */
private suspend fun PointerInputScope.detectTapOrHoldDrag(
    onTap: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val heldLongEnough = try {
            // Up before the timeout is a tap; null is the gesture being taken over by something
            // else, which is how a scroll of the page still wins over a press on a tile.
            withTimeout(viewConfiguration.longPressTimeoutMillis) {
                waitForUpOrCancellation()
            }?.let { up ->
                up.consume()
                onTap()
            }
            false
        } catch (_: PointerEventTimeoutCancellationException) {
            true
        }
        if (!heldLongEnough) return@awaitEachGesture

        onDragStart()
        val completed = drag(down.id) { change ->
            onDrag(change.positionChange())
            change.consume()
        }
        if (completed) onDragEnd() else onDragCancel()
    }
}

/**
 * Where a tile sits while a drag is in progress: everything between the gap the held tile left and
 * the slot it is hovering over shifts one place to close it. Returns [index] unchanged for tiles
 * the drag does not pass over.
 */
private fun displacedIndex(index: Int, from: Int, to: Int): Int = when {
    to > from && index > from && index <= to -> index - 1
    to < from && index >= to && index < from -> index + 1
    else -> index
}

/** Slides a tile aside; the held tile tracks the finger instead and is never animated. */
private val SLIDE_SPEC = spring<Float>(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)

/**
 * Remembers whether the layout is mid-settle, and a spec that does not animate while it is.
 *
 * On release two things change: the list is rewritten in the new order, and every slide target
 * drops back to zero. Both leave a tile exactly where it already is, but a spring would animate
 * the second one - so a tile that had slid one place left would slide back RIGHT across its own
 * new slot. Snapping through the changeover keeps it where the finger left it. The window covers
 * the extra frame the reordered list takes to arrive from the view model.
 */
@Composable
private fun rememberSettling(): Pair<() -> Unit, AnimationSpec<Float>> {
    var settling by remember { mutableStateOf(false) }
    LaunchedEffect(settling) {
        if (settling) {
            delay(100)
            settling = false
        }
    }
    return Pair({ settling = true }, if (settling) snap() else SLIDE_SPEC)
}

/**
 * The Hub as it is drawn for real: the same three-across grid of square tiles as [KaspaHubScreen].
 *
 * Not a LazyVerticalGrid, and the underlying list does NOT change under the finger. Reordering it
 * mid-drag would move a tile from one row of the grid to another, and a composable that changes
 * parent loses the gesture that is dragging it, so the drag would die halfway through. What moves
 * instead is where each tile is DRAWN: the held tile tracks the finger, and every tile between
 * the gap it left and the slot it is over slides one place to close it. The arrangement under
 * your finger is the arrangement you get, and the list is rewritten once, on release.
 */
@Composable
private fun HubGrid(
    hub: List<Screen>,
    colors: AppColors,
    onTap: (Screen) -> Unit,
    onReorder: (List<Screen>) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current

    // Live, for the same reason as latestHub below: a tap that ran the callback captured when the
    // tile was first composed worked from a stale arrangement, so a second tap undid the first and
    // put something else back - which looks like the tap swapping two items rather than moving one.
    val latestOnTap by rememberUpdatedState(onTap)
    val latestOnReorder by rememberUpdatedState(onReorder)

    var draggingRoute by remember { mutableStateOf<String?>(null) }
    var dragStartIndex by remember { mutableIntStateOf(0) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var targetIndex by remember { mutableIntStateOf(-1) }
    val (settle, slideSpec) = rememberSettling()

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val tileSize = (maxWidth - GRID_SPACING * 2) / 3
        val stridePx = with(density) { (tileSize + GRID_SPACING).toPx() }

        // Read through rememberUpdatedState, because the gesture below is keyed on the route
        // alone and so is NOT restarted when the list changes. Capturing these directly left it
        // working from whatever the grid looked like when the tile was first composed: a tile that
        // had since moved picked up the wrong slot, which is a hold that grabs the wrong thing and
        // a release that lands somewhere unasked for.
        val latestHub by rememberUpdatedState(hub)
        val latestStride by rememberUpdatedState(stridePx)

        fun targetFor(): Int {
            val columns = (dragX / latestStride).roundToInt()
            val rows = (dragY / latestStride).roundToInt()
            return (dragStartIndex + columns + rows * 3)
                .coerceIn(0, latestHub.lastIndex.coerceAtLeast(0))
        }

        Column(verticalArrangement = Arrangement.spacedBy(GRID_SPACING)) {
            hub.chunked(3).forEachIndexed { rowIndex, rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(GRID_SPACING)) {
                    rowItems.forEachIndexed { columnIndex, screen ->
                        val index = rowIndex * 3 + columnIndex
                        key(screen.route) {
                            val latestIndex by rememberUpdatedState(index)
                            val isDragging = draggingRoute == screen.route
                            // Where this tile slides to while some OTHER tile is being dragged
                            // over its slot. Animated, because that slide is the whole point;
                            // the held tile is excluded because it has to track the finger.
                            val moved = if (draggingRoute != null && !isDragging) {
                                displacedIndex(index, dragStartIndex, targetIndex)
                            } else {
                                index
                            }
                            val slideX by animateFloatAsState(
                                targetValue = (moved % 3 - index % 3) * stridePx,
                                animationSpec = slideSpec,
                                label = "hubSlideX"
                            )
                            val slideY by animateFloatAsState(
                                targetValue = (moved / 3 - index / 3) * stridePx,
                                animationSpec = slideSpec,
                                label = "hubSlideY"
                            )
                            HubTile(
                                screen = screen,
                                colors = colors,
                                isDragging = isDragging,
                                // Lambdas, read in the draw phase. Reading the offsets during
                                // composition would recompose the whole grid on every pixel of
                                // finger movement; from inside graphicsLayer a moving finger
                                // costs one redraw of one tile.
                                offsetX = { if (isDragging) dragX else slideX },
                                offsetY = { if (isDragging) dragY else slideY },
                                modifier = Modifier
                                    .weight(1f)
                                    // Keyed on the route alone: `hub` is rebuilt on every
                                    // recomposition of the screen, so including it would restart
                                    // the detector for reasons unrelated to this tile.
                                    .pointerInput(screen.route) {
                                        detectTapOrHoldDrag(
                                            onTap = { latestOnTap(screen) },
                                            onDragStart = {
                                                dragStartIndex = latestIndex
                                                draggingRoute = screen.route
                                                dragX = 0f
                                                dragY = 0f
                                                targetIndex = latestIndex
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            },
                                            onDrag = { amount ->
                                                dragX += amount.x
                                                dragY += amount.y
                                                val next = targetFor()
                                                if (next != targetIndex) {
                                                    targetIndex = next
                                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                            },
                                            onDragEnd = {
                                                settle()
                                                val target = targetFor()
                                                if (target != dragStartIndex) {
                                                    val order = latestHub.toMutableList()
                                                    order.add(target, order.removeAt(dragStartIndex))
                                                    latestOnReorder(order)
                                                }
                                                draggingRoute = null
                                                targetIndex = -1
                                            },
                                            onDragCancel = {
                                                draggingRoute = null
                                                targetIndex = -1
                                            }
                                        )
                                    }
                            )
                        }
                    }
                    // A short last row still has to lay its tiles out at the same width as a full
                    // one, or the final tile stretches across the leftover space.
                    repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun HubTile(
    screen: Screen,
    colors: AppColors,
    isDragging: Boolean,
    offsetX: () -> Float,
    offsetY: () -> Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                translationX = offsetX()
                translationY = offsetY()
            }
            .shadow(if (isDragging) 8.dp else 0.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = if (screen.usesKaspaLogo) {
                    painterResource(R.drawable.ic_kaspa_logo)
                } else {
                    rememberVectorPainter(screen.icon)
                },
                contentDescription = null,
                tint = KaspaTeal,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.size(10.dp))
            Text(
                screen.hubTitle,
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

/**
 * With everything in the dock there is nothing to tap or drag up here, so this is a sentence
 * rather than a target: the way back is to tap something in the dock.
 */
@Composable
private fun EmptyHubHint(colors: AppColors) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "Everything is in your dock.\nTap a dock item to move it back here.",
            color = colors.textSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * The dock as it is drawn for real: the same rounded bar of icon-over-label items that sits at the
 * bottom of the app, with its unfilled slots left visible so the count is legible without reading
 * the number.
 *
 * Same no-reorder-under-the-finger rule as the grid, for the same reason.
 */
@Composable
private fun DockPreview(
    dock: List<Screen>,
    dockIsFull: Boolean,
    colors: AppColors,
    onTap: (Screen) -> Unit,
    onReorder: (List<Screen>) -> Unit,
) {
    val haptics = LocalHapticFeedback.current

    // Live - see the same note in HubGrid.
    val latestOnTap by rememberUpdatedState(onTap)
    val latestOnReorder by rememberUpdatedState(onReorder)

    var draggingRoute by remember { mutableStateOf<String?>(null) }
    var dragStartIndex by remember { mutableIntStateOf(0) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var targetIndex by remember { mutableIntStateOf(-1) }
    val (settle, slideSpec) = rememberSettling()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Your Dock", color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                "${dock.size} of $MAX_DOCK_ITEMS",
                color = if (dockIsFull) Color(0xFFFFA726) else colors.textSecondary,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(6.dp))

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            // Divided by the number of items ACTUALLY in the dock, not by the cap. Every item is
            // one weight(1f) of the bar, so a three-tab dock has three third-width slots - sizing
            // them at a fifth each made the target index run away from the finger, which is what
            // made a reorder impossible to land where it was aimed.
            val slotPx = with(density) {
                ((maxWidth - 16.dp) / dock.size.coerceAtLeast(1)).toPx()
            }

            // coerceAtLeast guards the empty-dock case, which the pinned tabs make impossible in
            // practice but which would be a crash rather than a no-op if it ever were not.
            // Read through rememberUpdatedState - see the same note in HubGrid.
            val latestDock by rememberUpdatedState(dock)
            val latestSlot by rememberUpdatedState(slotPx)

            fun targetFor(): Int =
                (dragStartIndex + (dragX / latestSlot).roundToInt())
                    .coerceIn(0, latestDock.lastIndex.coerceAtLeast(0))

            Row(
                modifier = Modifier
                    .height(80.dp)
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(40.dp))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                // No placeholder slots for the unused capacity. The real dock divides its whole
                // width between however many tabs it has, so a three-tab dock is three WIDER items
                // and not three items with a gap on the right - and a preview showing the gap
                // would be showing an arrangement the user is never going to see. The count above
                // the bar is what says how much room is left.
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                dock.forEachIndexed { index, screen ->
                    key(screen.route) {
                        val latestIndex by rememberUpdatedState(index)
                        val pinned = screen.route in PINNED_DOCK_ROUTES
                        val isDragging = draggingRoute == screen.route
                        val moved = if (draggingRoute != null && !isDragging) {
                            displacedIndex(index, dragStartIndex, targetIndex)
                        } else {
                            index
                        }
                        val slideX by animateFloatAsState(
                            targetValue = (moved - index) * slotPx,
                            animationSpec = slideSpec,
                            label = "dockSlide"
                        )
                        DockItem(
                            screen = screen,
                            colors = colors,
                            pinned = pinned,
                            isDragging = isDragging,
                            offsetX = { if (isDragging) dragX else slideX },
                            // Pinned tabs drag like the rest: what they cannot do is LEAVE the
                            // dock, and a reorder never moves anything out of it. Where Kaspa Hub
                            // and Profile sit among the five is still the user's, and the dimming
                            // plus the refused tap is what says so.
                            modifier = Modifier
                                .weight(1f)
                                .pointerInput(screen.route) {
                                    detectTapOrHoldDrag(
                                        onTap = { latestOnTap(screen) },
                                        onDragStart = {
                                            dragStartIndex = latestIndex
                                            draggingRoute = screen.route
                                            dragX = 0f
                                            targetIndex = latestIndex
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDrag = { amount ->
                                            dragX += amount.x
                                            val next = targetFor()
                                            if (next != targetIndex) {
                                                targetIndex = next
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        },
                                        onDragEnd = {
                                            settle()
                                            val target = targetFor()
                                            if (target != dragStartIndex) {
                                                val order = latestDock.toMutableList()
                                                order.add(target, order.removeAt(dragStartIndex))
                                                latestOnReorder(order)
                                            }
                                            draggingRoute = null
                                            targetIndex = -1
                                        },
                                        onDragCancel = {
                                            draggingRoute = null
                                            targetIndex = -1
                                        }
                                    )
                                }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            if (dockIsFull) {
                "Your dock is full. Tap one of these to move it up to ${Screen.KaspaHub.label} and " +
                    "free a slot. ${Screen.KaspaHub.label} and ${Screen.Profile.label} must stay in the dock."
            } else {
                "Tap a dock item to move it up to ${Screen.KaspaHub.label}, or hold and drag to " +
                    "reorder. ${Screen.KaspaHub.label} and ${Screen.Profile.label} must stay in the dock."
            },
            color = colors.textSecondary,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun DockItem(
    screen: Screen,
    colors: AppColors,
    pinned: Boolean,
    isDragging: Boolean,
    offsetX: () -> Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(64.dp)
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer { translationX = offsetX() }
            .clip(RoundedCornerShape(32.dp))
            // Pinned tabs are dimmed rather than hidden: they hold their real dock position, and
            // the dimming is what says "this one is not yours to move OUT" before you tap it.
            // Dragging them to a different slot is still fine.
            .alpha(if (pinned) 0.55f else 1f),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = if (screen.usesKaspaLogo) {
                    painterResource(R.drawable.ic_kaspa_logo)
                } else {
                    rememberVectorPainter(screen.icon)
                },
                contentDescription = screen.label,
                tint = colors.textPrimary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = screen.label,
                color = colors.textPrimary,
                fontSize = if (screen.label.length > 9) 8.sp else 10.sp,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}
