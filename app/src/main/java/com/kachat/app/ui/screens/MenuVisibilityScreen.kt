package com.kachat.app.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.kachat.app.R
import com.kachat.app.ui.MAX_DOCK_ITEMS
import com.kachat.app.ui.PINNED_DOCK_ROUTES
import com.kachat.app.ui.Screen
import com.kachat.app.ui.hubTitle
import com.kachat.app.ui.kaspaHubSections
import com.kachat.app.ui.resolveDock
import com.kachat.app.ui.tabIconModifier
import com.kachat.app.ui.tabIconPainter
import com.kachat.app.ui.theme.AppColors
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.WalletViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Settings > Customization > Customize Dock: two lists ordered by dragging - the way Brave
 * arranges its menu actions. Matches iOS's MenuVisibilityView.
 *
 * Your Dock is the dock itself, all of it, in order: every row drags, Chats, Kaspa Hub and Profile
 * included ([PINNED_DOCK_ROUTES] - always there, so they carry a lock instead of a remove button),
 * and the two the person picked carry Brave's red minus, which sends them to Kaspa Hub. Kaspa Hub
 * lists everything else in its grid order, also by dragging, each with a green plus that moves it
 * into the dock - shown only while the dock has room ([MAX_DOCK_ITEMS] at most).
 *
 * The arrangement is a draft until this screen is left (or the app goes to the background), so
 * the dock under this screen is not rebuilt on every move. On leaving, it is applied once the
 * screen has finished closing, as it was when the screen closed.
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
    val haptics = LocalHapticFeedback.current

    // The draft, seeded once from the stored arrangement as the screen opens.
    val openedDock = remember { resolveDock(dockRoutes, hiddenTabs, childModeOn) }
    val openedHub = remember { kaspaHubSections(dockRoutes, hubRoutes, hiddenTabs, childModeOn) }
    var dock by remember { mutableStateOf(openedDock) }
    var hub by remember { mutableStateOf(openedHub) }
    val dockIsFull = dock.size >= MAX_DOCK_ITEMS

    /** Writes an arrangement to the real settings, and so rebuilds the dock. */
    fun applyArrangement(newDock: List<String>, newHub: List<String>) {
        // Nothing arranged, or already written (backgrounding): no write.
        if (newDock == openedDock.map { it.route } && newHub == openedHub.map { it.route }) return
        if (newDock == walletViewModel.dockTabs.value && newHub == walletViewModel.hubTabs.value) return
        walletViewModel.setPlacement(newDock, newHub)
    }

    DisposableEffect(Unit) {
        // Backgrounding is the one way off this screen that never disposes it, and an app killed
        // from the background would otherwise lose the arrangement - so it saves at once.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) applyArrangement(dock.map { it.route }, hub.map { it.route })
        }
        val processLifecycle = ProcessLifecycleOwner.get().lifecycle
        processLifecycle.addObserver(observer)
        onDispose {
            processLifecycle.removeObserver(observer)
            // Applied once this screen has finished closing (iOS waits 0.6 s after it disappears):
            // a new arrangement rebuilds the whole dock, and it should not be rebuilt under the
            // page that is closing. What was arranged is taken now, at close, and applied on the
            // main thread from the activity's view model, which outlives this screen.
            val closedDock = dock.map { it.route }
            val closedHub = hub.map { it.route }
            walletViewModel.viewModelScope.launch {
                delay(APPLY_AFTER_CLOSE_MS)
                applyArrangement(closedDock, closedHub)
            }
        }
    }

    /** Red minus: out of the dock, to the top of Kaspa Hub where it can be seen. */
    fun removeFromDock(screen: Screen) {
        if (screen.route in PINNED_DOCK_ROUTES) return
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        dock = dock.filter { it != screen }
        hub = listOf(screen) + hub.filter { it != screen }
    }

    /** Green plus: into the dock, at its end; drag it to where it should sit. */
    fun addToDock(screen: Screen) {
        if (dockIsFull) return
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        hub = hub.filter { it != screen }
        dock = dock.filter { it != screen } + screen
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBackIos, "Back", tint = KaspaTeal)
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
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
        ) {
            Spacer(Modifier.height(12.dp))

            // Your Dock
            SectionHeader(
                title = stringResource(R.string.customize_dock_your_dock),
                detail = "${dock.size}/$MAX_DOCK_ITEMS",
                colors = colors,
            )
            ReorderableCard(
                items = dock,
                colors = colors,
                separatorStart = LABEL_WITH_CONTROL_START,
                onMove = { from, to ->
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    dock = dock.toMutableList().apply { add(to, removeAt(from)) }
                },
            ) { screen ->
                if (screen.route in PINNED_DOCK_ROUTES) {
                    val always = stringResource(R.string.customize_dock_always_in_dock)
                    Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = always,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                } else {
                    val label = stringResource(R.string.customize_dock_move_to, screen.label, Screen.KaspaHub.label)
                    CircleControl(
                        color = colors.danger,
                        plus = false,
                        description = label,
                        onClick = { removeFromDock(screen) },
                    )
                }
                Spacer(Modifier.width(14.dp))
                TabIcon(screen)
                Spacer(Modifier.width(14.dp))
                Text(screen.label, color = colors.textPrimary, fontSize = 17.sp, maxLines = 1)
            }
            SectionFooter(
                stringResource(
                    R.string.customize_dock_always_stay,
                    Screen.Chats.label, Screen.KaspaHub.label, Screen.Profile.label
                ),
                colors,
            )

            Spacer(Modifier.height(16.dp))

            // Kaspa Hub
            SectionHeader(title = Screen.KaspaHub.label, detail = null, colors = colors)
            ReorderableCard(
                items = hub,
                colors = colors,
                separatorStart = if (dockIsFull) LABEL_START else LABEL_WITH_CONTROL_START,
                onMove = { from, to ->
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    hub = hub.toMutableList().apply { add(to, removeAt(from)) }
                },
            ) { screen ->
                if (!dockIsFull) {
                    val label = stringResource(R.string.customize_dock_add, screen.label)
                    CircleControl(
                        color = colors.success,
                        plus = true,
                        description = label,
                        onClick = { addToDock(screen) },
                    )
                    Spacer(Modifier.width(14.dp))
                }
                TabIcon(screen)
                Spacer(Modifier.width(14.dp))
                Text(screen.hubTitle, color = colors.textPrimary, fontSize = 17.sp, maxLines = 1)
            }
            SectionFooter(
                if (dockIsFull) {
                    stringResource(R.string.customize_dock_full)
                } else {
                    stringResource(R.string.customize_dock_hub_order, Screen.KaspaHub.label)
                },
                colors,
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** How long after Customize Dock has closed its arrangement is applied (iOS: 0.6 s). */
private const val APPLY_AFTER_CLOSE_MS = 600L

/** A row's height, shared by the layout and by the drag's slot arithmetic. */
private val ROW_HEIGHT = 52.dp

/** Where a row's label starts - its separator is inset to it, as an iOS List row's is. */
private val LABEL_START = 16.dp + 28.dp + 14.dp
private val LABEL_WITH_CONTROL_START = LABEL_START + 24.dp + 14.dp

/** iOS inset-grouped section header: secondary label, the dock's count trailing. */
@Composable
private fun SectionHeader(title: String, detail: String?, colors: AppColors) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title.uppercase(), fontSize = 13.sp, color = colors.textSecondary)
        Spacer(Modifier.weight(1f))
        if (detail != null) {
            Text(detail, fontSize = 13.sp, color = colors.textSecondary)
        }
    }
}

/** iOS section footer: 13pt secondary label under the card. */
@Composable
private fun SectionFooter(text: String, colors: AppColors) {
    Text(
        text = text,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = colors.textSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

/** Brave's / iOS's edit-mode control: a filled circle with a white minus or plus. */
@Composable
private fun CircleControl(color: Color, plus: Boolean, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(width = 11.dp, height = 2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(Color.White)
            )
            if (plus) {
                Box(
                    Modifier
                        .size(width = 2.dp, height = 11.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(Color.White)
                )
            }
        }
    }
}

@Composable
private fun TabIcon(screen: Screen) {
    Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
        Icon(
            painter = screen.tabIconPainter(),
            contentDescription = null,
            tint = KaspaTeal,
            modifier = screen.tabIconModifier(20.dp)
        )
    }
}

/**
 * Where a row sits while a drag is in progress: everything between the gap the held row left and
 * the slot it is hovering over shifts one place to close it.
 */
private fun displacedIndex(index: Int, from: Int, to: Int): Int = when {
    to > from && index > from && index <= to -> index - 1
    to < from && index >= to && index < from -> index + 1
    else -> index
}

/** Slides a row aside; the held row tracks the finger instead and is never animated. */
private val SLIDE_SPEC = spring<Float>(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)

/**
 * Whether the list is mid-settle, and a spec that does not animate while it is: on release the
 * list is rewritten in the new order AND every slide target drops back to zero, which a spring
 * would animate as the row sliding back across its own new slot.
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
 * One inset-grouped card of rows, reordered by dragging the grip on each row's trailing edge (the
 * iOS List edit-mode reorder control). The list does not change under the finger: the held row
 * tracks it and the rows it passes slide aside; the move is reported once, on release.
 */
@Composable
private fun ReorderableCard(
    items: List<Screen>,
    colors: AppColors,
    separatorStart: Dp,
    onMove: (from: Int, to: Int) -> Unit,
    content: @Composable (Screen) -> Unit,
) {
    // An empty section shows its header and footer only, as an empty iOS List section does.
    if (items.isEmpty()) return

    val density = LocalDensity.current
    val stridePx = with(density) { ROW_HEIGHT.toPx() }
    val latestItems by rememberUpdatedState(items)
    val latestOnMove by rememberUpdatedState(onMove)

    var draggingRoute by remember { mutableStateOf<String?>(null) }
    var dragStartIndex by remember { mutableIntStateOf(0) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var targetIndex by remember { mutableIntStateOf(-1) }
    val (settle, slideSpec) = rememberSettling()

    fun targetFor(): Int =
        (dragStartIndex + (dragY / stridePx).roundToInt())
            .coerceIn(0, latestItems.lastIndex.coerceAtLeast(0))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(colors.surface)
            .animateContentSize(),
        verticalArrangement = Arrangement.Top
    ) {
        items.forEachIndexed { index, screen ->
            key(screen.route) {
                val latestIndex by rememberUpdatedState(index)
                val isDragging = draggingRoute == screen.route
                val moved = if (draggingRoute != null && !isDragging) {
                    displacedIndex(index, dragStartIndex, targetIndex)
                } else {
                    index
                }
                val slideY by animateFloatAsState(
                    targetValue = (moved - index) * stridePx,
                    animationSpec = slideSpec,
                    label = "dockRowSlide"
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ROW_HEIGHT)
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragging) dragY else slideY }
                        .shadow(if (isDragging) 8.dp else 0.dp)
                        .background(colors.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        content(screen)
                        Spacer(Modifier.weight(1f))
                        // The reorder grip. Dragging starts here, as on iOS, so a swipe anywhere
                        // else on the row still scrolls the page.
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(56.dp)
                                .pointerInput(screen.route) {
                                    detectDragGestures(
                                        onDragStart = {
                                            dragStartIndex = latestIndex
                                            draggingRoute = screen.route
                                            dragY = 0f
                                            targetIndex = latestIndex
                                        },
                                        onDragEnd = {
                                            settle()
                                            val target = targetFor()
                                            if (target != dragStartIndex) latestOnMove(dragStartIndex, target)
                                            draggingRoute = null
                                            targetIndex = -1
                                        },
                                        onDragCancel = {
                                            draggingRoute = null
                                            targetIndex = -1
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragY += amount.y
                                            targetIndex = targetFor()
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Menu,
                                contentDescription = null,
                                tint = colors.textTertiary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    if (index < items.lastIndex && !isDragging) {
                        HorizontalDivider(
                            color = colors.divider,
                            thickness = 0.5.dp,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = separatorStart)
                        )
                    }
                }
            }
        }
    }
}
