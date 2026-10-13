package com.kachat.app.ui.screens

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** One look at the list's last row: which row it is, how tall, and how far it runs past the end. */
private data class ChatBottomRow(
    val index: Int,
    val size: Int,
    /** px the row's bottom sits below the visible end of the list (<= 0: the end is in view). */
    val overflow: Int,
    val scrolling: Boolean
)

/**
 * Keeps a 1:1 or group chat pinned to its bottom while the content under it grows - a long
 * message still laying out, a link preview or a photo arriving a moment after its row (iOS
 * aa26c35, `ScrollViewReference`). The LazyColumn keeps its first visible row anchored, so a
 * growing last row was pushed down past the end and the list was shoved up in steps. Only when
 * the reader was at the end just before the growth (within 24dp) and isn't touching the list;
 * anyone reading history above is never moved. No animation, like iOS.
 */
@Composable
internal fun KeepChatPinnedWhileRowsGrow(listState: LazyListState) {
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    val liveIsDragged by rememberUpdatedState(isDragged)
    val tolerancePx = with(LocalDensity.current) { 24.dp.toPx() }
    LaunchedEffect(listState) {
        var previous: ChatBottomRow? = null
        // A scroll the reader started (a drag and the fling after it) - left alone to its end,
        // like iOS's isTracking / isDragging / isDecelerating.
        var readerScrolling = false
        // Growth while a programmatic scroll (an arrival's glide to the bottom) was in flight:
        // pinned once that scroll ends rather than cutting it off.
        var pendingPin = false
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            if (last == null || last.index != info.totalItemsCount - 1) {
                null
            } else {
                ChatBottomRow(
                    index = last.index,
                    size = last.size,
                    overflow = last.offset + last.size - (info.viewportEndOffset - info.afterContentPadding),
                    scrolling = listState.isScrollInProgress
                )
            }
        }.collect { row ->
            val before = previous
            previous = row
            if (liveIsDragged) readerScrolling = true
            if (row == null) {
                pendingPin = false
                if (!listState.isScrollInProgress && !liveIsDragged) readerScrolling = false
                return@collect
            }
            if (readerScrolling) {
                if (!row.scrolling && !liveIsDragged) readerScrolling = false
                pendingPin = false
                return@collect
            }
            // Measured against the row BEFORE the growth: was the reader at the end then?
            val grew = before != null && before.index == row.index && row.size > before.size &&
                before.overflow <= tolerancePx
            if (row.scrolling) {
                if (grew) pendingPin = true
                return@collect
            }
            if (grew || pendingPin) {
                pendingPin = false
                if (row.overflow > 0) listState.scrollBy(row.overflow.toFloat())
            }
        }
    }
}
