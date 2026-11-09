package com.kachat.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.ui.theme.LocalAppColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One button behind a swiped row. */
class SwipeAction(val icon: ImageVector, val title: String, val tint: Color, val onClick: () -> Unit)

private val TrailingActionWidth = 74.dp
private val LeadingActionWidth = 96.dp

/** How far ahead a flick's speed counts when deciding where a row settles. */
private const val FLICK_PROJECTION_SECONDS = 0.15f
private const val FLICK_SAMPLE_WINDOW_MS = 100L
private const val MIN_FLICK_SAMPLE_MS = 16L

/**
 * A list row with iOS's `.swipeActions`, used by the chat and group rows (iOS d063b897): swiping it
 * reveals [trailingActions] from the trailing edge and [leadingActions] from the leading edge, and
 * a tap on an open row closes it. One row is open at a time: the list holds that row's key in
 * [openKey], and clears it to close the row, as when the list scrolls.
 *
 * Only the leading side has a full swipe (iOS `allowsFullSwipe`), which runs its first action; the
 * trailing side holds Delete, and a long swipe must not start one, unless [trailingFullSwipe] says
 * otherwise (a Contacts row: iOS `.onDelete`). As on iOS the trailing side's first action sits at the
 * edge. "Leading" follows the layout direction, so it mirrors in RTL.
 */
@Composable
fun SwipeActionsRow(
    key: Any,
    openKey: Any?,
    onOpenKeyChange: (Any?) -> Unit,
    enabled: Boolean,
    leadingActions: List<SwipeAction>,
    trailingActions: List<SwipeAction>,
    modifier: Modifier = Modifier,
    /** For a row too short for the circle and its title (a Contacts row): a smaller circle alone. */
    compactActions: Boolean = false,
    trailingFullSwipe: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    // Positive shows the leading actions and negative the trailing ones, whichever way the layout
    // runs; [direction] turns it into pixels on screen.
    val offset = remember { Animatable(0f) }
    var rowWidth by remember { mutableIntStateOf(0) }
    val side by remember { derivedStateOf { if (offset.value > 0f) 1 else if (offset.value < 0f) -1 else 0 } }

    val leadingPx = with(density) { leadingActions.size * LeadingActionWidth.toPx() }
    val trailingPx = with(density) { trailingActions.size * TrailingActionWidth.toPx() }
    val currentLeading by rememberUpdatedState(leadingActions)
    val currentTrailing by rememberUpdatedState(trailingActions)
    val currentOpenKey by rememberUpdatedState(openKey)
    val currentOnOpenKeyChange by rememberUpdatedState(onOpenKeyChange)

    val isOpenRow = openKey == key
    LaunchedEffect(isOpenRow) {
        if (!isOpenRow && offset.value != 0f) offset.animateTo(0f)
    }
    LaunchedEffect(enabled) {
        if (!enabled) offset.snapTo(0f)
    }

    fun close() {
        scope.launch { offset.animateTo(0f) }
        if (currentOpenKey == key) currentOnOpenKeyChange(null)
    }

    /** Where the finger let go, and how fast it was going: a flick counts for where it was heading. */
    fun settle(value: Float, velocity: Float) {
        val fullSwipe = currentLeading.firstOrNull()?.takeIf { value >= rowWidth * 0.5f }
            ?: currentTrailing.firstOrNull()?.takeIf { trailingFullSwipe && value <= -rowWidth * 0.5f }
        val projected = value + velocity * FLICK_PROJECTION_SECONDS
        when {
            fullSwipe != null -> {
                close()
                fullSwipe.onClick()
            }
            leadingPx > 0f && projected >= leadingPx / 2f -> scope.launch { offset.animateTo(leadingPx) }
            trailingPx > 0f && projected <= -trailingPx / 2f -> scope.launch { offset.animateTo(-trailingPx) }
            else -> close()
        }
    }

    @Composable
    fun ActionCell(action: SwipeAction, width: androidx.compose.ui.unit.Dp) {
        // iOS 26's swipe action: a tinted circle with the title under it.
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(width)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    close()
                    action.onClick()
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier.size(if (compactActions) 42.dp else 50.dp).clip(CircleShape).background(action.tint),
                contentAlignment = Alignment.Center,
            ) {
                Icon(action.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            if (!compactActions) {
                Spacer(Modifier.height(4.dp))
                Text(
                    action.title,
                    color = LocalAppColors.current.textSecondary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    val gestures = if (enabled) {
        Modifier.pointerInput(leadingPx, trailingPx, rowWidth, direction) {
            // Kept here rather than read back from [offset]: its updates are posted, so on a quick
            // flick it was still behind when the finger lifted and the row snapped shut.
            var position = 0f
            // The row's own recent positions, for how fast it was going when the finger lifted.
            val recent = ArrayDeque<Pair<Long, Float>>()
            fun speed(): Float {
                val first = recent.firstOrNull() ?: return 0f
                val last = recent.last()
                val elapsedMs = last.first - first.first
                return if (elapsedMs < MIN_FLICK_SAMPLE_MS) 0f else (last.second - first.second) * 1000f / elapsedMs
            }
            detectHorizontalDragGestures(
                onDragStart = {
                    position = offset.value
                    recent.clear()
                    currentOnOpenKeyChange(key)
                },
                onDragEnd = { settle(position, speed()) },
                onDragCancel = { settle(position, 0f) },
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    val leadingLimit = if (leadingPx > 0f) rowWidth.toFloat() else 0f
                    val trailingLimit = if (trailingFullSwipe && trailingPx > 0f) rowWidth.toFloat() else trailingPx
                    position = (position + amount * direction).coerceIn(-trailingLimit, leadingLimit)
                    recent.addLast(change.uptimeMillis to position)
                    while (change.uptimeMillis - recent.first().first > FLICK_SAMPLE_WINDOW_MS) recent.removeFirst()
                    val next = position
                    scope.launch { offset.snapTo(next) }
                },
            )
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { rowWidth = it.width }
            .clipToBounds()
            .semantics {
                // TalkBack has no swipe to reveal these, so they are its actions on the row.
                if (enabled) {
                    customActions = (leadingActions + trailingActions).map { action ->
                        CustomAccessibilityAction(action.title) {
                            action.onClick()
                            true
                        }
                    }
                }
            }
            .then(gestures),
    ) {
        if (side > 0) {
            Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.Start) {
                // Stretches with the swipe, as a full swipe does on iOS.
                val swiped = with(density) { offset.value.toDp() }
                leadingActions.forEach { ActionCell(it, maxOf(LeadingActionWidth, swiped)) }
            }
        } else if (side < 0) {
            Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.End) {
                val swiped = with(density) { (-offset.value).toDp() }
                trailingActions.asReversed().forEach {
                    ActionCell(it, if (trailingFullSwipe) maxOf(TrailingActionWidth, swiped) else TrailingActionWidth)
                }
            }
        }
        Box(
            Modifier
                .absoluteOffset { IntOffset((offset.value * direction).roundToInt(), 0) }
                .background(LocalAppColors.current.background)
        ) {
            content()
            // An open row's tap closes it rather than opening the chat.
            if (side != 0) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { close() },
                        )
                )
            }
        }
    }
}
