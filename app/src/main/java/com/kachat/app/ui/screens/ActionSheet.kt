package com.kachat.app.ui.screens

import androidx.compose.foundation.layout.statusBars
import com.kachat.app.ui.theme.iosGlass

import androidx.compose.foundation.layout.navigationBarsPadding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.composed
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.TextUnit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import com.kachat.app.ui.theme.iosShadow
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import com.kachat.app.services.AddressActivityNotifier
import com.kachat.app.services.ColdStorageAddressDiscovery
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.FormatQuote
import com.kachat.app.util.KaspaUnit

/**
 * The frame every "..." menu in the app now uses: a half sheet with a title, a line of context,
 * and a stack of [ActionSheetRow]s.
 *
 * These were popup menus of bare labels, which have room for a verb and nothing else. A sheet has
 * room for each option to say what it does, and it keeps the thing being acted on on screen while
 * you choose. Mirrors iOS's `.sheet(item:)` menus.
 */
/**
 * A message's long-press menu, as a half sheet: what the message is at the top, the message
 * itself under it when there is text to show, then a square tile per action, three to a row
 * ([ActionSheetTiles] - iOS cdac6d0), each saying what it does to TalkBack. Every bubble in every
 * kind of chat opens this - text, payment, call, chess, photo, file, voice note and link card
 * alike - so a Reply reads the same wherever it is offered. The system-style popup of bare verbs
 * was the one hold-out (iOS 1bc6ba6).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionsSheet(
    title: String,
    preview: String?,
    onDismiss: () -> Unit,
    /** The [ActionSheetRow]s - drawn as tiles. */
    content: @Composable () -> Unit,
) {
    // iOS plays a medium impact as the long press brings the menu up.
    val haptic = com.kachat.app.util.rememberHaptics()
    LaunchedEffect(Unit) { haptic(com.kachat.app.util.IosHaptic.IMPACT_MEDIUM) }
    val shownPreview = preview?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("{") }
    // iOS MessageActionsSheet.height: the header (taller with the preview), the grid and 40,
    // never more than 85% of the screen. It counts the tiles; so does this, by laying them out.
    val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    var tileCount by remember { mutableIntStateOf(0) }
    val header = if (shownPreview == null) 76.dp else 116.dp
    TileActionSheet(
        title = title,
        subtitle = shownPreview,
        subtitleFontSize = 13.sp,
        subtitleMaxLines = 2,
        onDismiss = onDismiss,
        height = minOf(screenHeight * 0.85f, header + ActionSheetTileMetrics.gridHeight(tileCount) + 40.dp),
        bottomPadding = 20.dp,
        onTileCount = { tileCount = it },
        content = content,
    )
}

/**
 * The half sheet behind every long-press menu and the composer's "+": a title (and an optional
 * line under it), then the [ActionSheetRow]s as square tiles, three to a row. Exactly [height]
 * tall, the way iOS fixes each of these sheets' detent with `ActionSheetTileMetrics.sheetHeight`
 * rather than letting it grow to its content: the title 20 below the top edge, the grabber drawn
 * over the top rather than taking a row of its own, the tiles under the title, room to spare
 * below them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TileActionSheet(
    title: String,
    onDismiss: () -> Unit,
    height: Dp,
    subtitle: String? = null,
    subtitleFontSize: TextUnit = 12.sp,
    subtitleMaxLines: Int = Int.MAX_VALUE,
    bottomPadding: Dp = 24.dp,
    /** Told how many tiles the grid holds, for a sheet whose height follows its tile count. */
    onTileCount: ((Int) -> Unit)? = null,
    /** iOS's second `.large` detent: the sheet opens at [height] and drags up to full height. */
    largeDetent: Boolean = false,
    content: @Composable () -> Unit,
) {
    TileActionSheet(
        title = title,
        onDismiss = onDismiss,
        height = height,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        subtitle = subtitle,
        subtitleFontSize = subtitleFontSize,
        subtitleMaxLines = subtitleMaxLines,
        bottomPadding = bottomPadding,
        onTileCount = onTileCount,
        largeDetent = largeDetent,
        content = content,
    )
}

/** The same sheet on a [sheetState] the caller holds (the .kachat Manage Name sheet hides it
 *  itself before acting) - a separate overload so the experimental [SheetState] stays out of
 *  every other caller's signature, as [ActionSheetContainer] does. */
@ExperimentalMaterial3Api
@Composable
fun TileActionSheet(
    title: String,
    onDismiss: () -> Unit,
    height: Dp,
    sheetState: SheetState,
    subtitle: String? = null,
    subtitleFontSize: TextUnit = 12.sp,
    subtitleMaxLines: Int = Int.MAX_VALUE,
    bottomPadding: Dp = 24.dp,
    onTileCount: ((Int) -> Unit)? = null,
    largeDetent: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = LocalAppColors.current
    com.kachat.app.ui.theme.IosSheetColors {
        val colors = LocalAppColors.current
        ModalBottomSheet(
            shape = com.kachat.app.ui.theme.IosSheetShape,
            tonalElevation = com.kachat.app.ui.theme.IosSheetTonalElevation,
            windowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars,
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = colors.background,
            dragHandle = null,
        ) {
            // The sheet runs down behind the navigation bar, as iOS's does behind the home indicator;
            // its content stays above it.
            Column(Modifier.navigationBarsPadding()) {
                IosSheetDetents(height = height, largeDetent = largeDetent) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp)
                            .padding(bottom = bottomPadding),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Column(
                            modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                title,
                                color = colors.textPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 17.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!subtitle.isNullOrBlank()) {
                                Text(
                                    subtitle,
                                    color = colors.textSecondary,
                                    fontSize = subtitleFontSize,
                                    textAlign = TextAlign.Center,
                                    maxLines = subtitleMaxLines,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                            ActionSheetTiles(content, onTileCount)
                        }
                    }
                    IosSheetGrabber(Modifier.align(Alignment.TopCenter))
                }
            }
        }
    }
}

/**
 * A sheet's height on iOS's detents: exactly [height] (`.height(...)`), or with [largeDetent]
 * (`[.height(...), .large]`) that height plus the full one - drag up and the sheet grows to the
 * top, following the finger; let go and it settles on whichever detent the drag was heading for
 * (a flick decides it). Dragging down from full height brings it back to [height]; from [height]
 * the drag goes on to the sheet itself, which closes it as before.
 *
 * The drag reaches the enclosing ModalBottomSheet through nested scrolling, so the sheet's own
 * drag-to-dismiss and its scroll-aware settling keep working underneath.
 */
@Composable
fun IosSheetDetents(height: Dp, largeDetent: Boolean, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    if (!largeDetent) {
        Box(Modifier.fillMaxWidth().height(height)) { content() }
        return
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        // iOS's .large stops 10 below the status bar, the screen behind showing as a sliver.
        val fullPx = constraints.maxHeight.toFloat() - with(density) { 10.dp.toPx() }
        val fixedPx = with(density) { height.toPx() }.coerceAtMost(fullPx)
        val rangePx = (fullPx - fixedPx).coerceAtLeast(0f)
        // How far above the fixed detent the sheet is drawn: 0 at [height], rangePx at full.
        var extraPx by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
        val extra = extraPx.coerceIn(0f, rangePx)
        val latestRange by rememberUpdatedState(rangePx)
        val flickPx = with(density) { 125.dp.toPx() }
        val scrollState = androidx.compose.foundation.gestures.rememberScrollableState { delta ->
            // Down is positive: dragging up grows the sheet, dragging down shrinks it to the
            // fixed detent; what is left over goes on to the sheet (and closes it).
            val old = extraPx.coerceIn(0f, latestRange)
            val new = (old - delta).coerceIn(0f, latestRange)
            extraPx = new
            old - new
        }
        val settle = remember(flickPx) {
            object : androidx.compose.foundation.gestures.FlingBehavior {
                override suspend fun androidx.compose.foundation.gestures.ScrollScope.performFling(initialVelocity: Float): Float {
                    val range = latestRange
                    val from = extraPx.coerceIn(0f, range)
                    // At the fixed detent and not heading up: the sheet itself settles (or closes).
                    if (range <= 0f || (from <= 0f && initialVelocity >= 0f)) return initialVelocity
                    val target = when {
                        initialVelocity < -flickPx -> range
                        initialVelocity > flickPx -> 0f
                        else -> if (from > range / 2f) range else 0f
                    }
                    androidx.compose.animation.core.animate(
                        initialValue = from,
                        targetValue = target,
                        initialVelocity = -initialVelocity,
                        animationSpec = androidx.compose.animation.core.spring(
                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                        ),
                    ) { value, _ -> extraPx = value }
                    return 0f
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(with(density) { (fixedPx + extra).toDp() })
                .scrollable(
                    state = scrollState,
                    orientation = androidx.compose.foundation.gestures.Orientation.Vertical,
                    flingBehavior = settle,
                ),
        ) { content() }
    }
}

/**
 * iOS's sheet drag indicator (`.presentationDragIndicator(.visible)`): a 36 x 5 rounded bar 5
 * below the sheet's top edge, drawn over the content rather than above it, so a sheet's height
 * and its title's place match iOS's.
 */
@Composable
fun IosSheetGrabber(modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(top = 5.dp)
            .size(width = 36.dp, height = 5.dp)
            .clip(CircleShape)
            .background(LocalAppColors.current.textSecondary.copy(alpha = 0.5f)),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheetContainer(
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    /** Extra line under the subtitle - the txid on the transaction menu, for instance. */
    detail: String? = null,
    /** What iOS shows as a grouped List (the reactions sheet): the grouped light palette - see
     *  [com.kachat.app.ui.theme.IosSheetColors]. */
    grouped: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    ActionSheetContainer(
        title = title,
        subtitle = subtitle,
        onDismiss = onDismiss,
        grouped = grouped,
        // Opens EXPANDED, not half-height. Partial expansion caps the opening height at about
        // half the screen, so a sheet with more than a few options opened already cut off - the
        // 1:1 composer's + menu hid Send Handshake below the fold, and an option you cannot see
        // is an option that does not exist. Expanded still wraps its content, so a short sheet
        // looks exactly as it did.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        detail = detail,
        content = content,
    )
}

/**
 * The same sheet on a [sheetState] the caller holds, for a caller that hides the sheet itself and
 * acts once it has gone down (the .kachat Manage Name sheet, iOS f61b978). A separate overload so
 * the experimental [SheetState] stays out of every other caller's signature.
 */
@ExperimentalMaterial3Api
@Composable
fun ActionSheetContainer(
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    sheetState: SheetState,
    detail: String? = null,
    grouped: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalAppColors.current
    com.kachat.app.ui.theme.IosSheetColors(grouped = grouped) {
        val colors = LocalAppColors.current
        ModalBottomSheet(
            shape = com.kachat.app.ui.theme.IosSheetShape,
            tonalElevation = com.kachat.app.ui.theme.IosSheetTonalElevation,
            windowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars,
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = colors.background,
        ) {
            // The sheet runs down behind the navigation bar, as iOS's does behind the home indicator;
            // its content stays above it.
            Column(Modifier.navigationBarsPadding()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // For the rare sheet taller than the screen itself - expanded tops out at full
                        // height, and without this the overflow would be unreachable rather than merely
                        // below the fold.
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        title,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        textAlign = TextAlign.Center,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            subtitle,
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!detail.isNullOrBlank()) {
                        Text(
                            detail,
                            color = colors.textSecondary,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    content()
                }
            }
        }
    }
}

/**
 * One option in an [ActionSheetContainer]. Same shape as iOS's `ActionSheetRow`: a row with its
 * line saying what it does - or, inside [ActionSheetTiles], a square tile (iOS cdac6d0).
 */
@Composable
fun ActionSheetRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color = KaspaTeal,
    /** iOS `isDisabled`: shown, dimmed to half, and not tappable. */
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    ActionSheetRowFrame(
        icon = { size -> Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size)) },
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        enabled = enabled,
        // iOS: the tile's title takes the tint, unless it is the accent.
        tileTitleColor = if (tint == KaspaTeal) null else tint,
    )
}

/**
 * The same row with an asset image in place of the icon glyph - the Kaspa logo on "Pay in
 * Kaspa", which every menu in the app shows with the logo rather than a stand-in. Mirrors iOS's
 * `ActionSheetRow(customIcon:)`. The image is drawn as-is, untinted.
 */
@Composable
fun ActionSheetRow(
    icon: Painter,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ActionSheetRowFrame(
        // A touch larger as a tile, like iOS's custom icon (26 pt against the glyphs' 24).
        icon = { size ->
            Icon(icon, contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(if (size > 22.dp) 26.dp else size))
        },
        title = title,
        subtitle = subtitle,
        onClick = onClick,
    )
}

@Composable
private fun ActionSheetRowFrame(
    icon: @Composable (Dp) -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    /** The tile form's title colour; null is the primary text colour. */
    tileTitleColor: Color? = null,
) {
    val colors = LocalAppColors.current
    if (LocalActionSheetTiles.current) {
        // The square form, for long-press menus: the icon and a short title, so a glance says
        // what each one does. The explanatory line becomes the TalkBack hint (iOS cdac6d0).
        Column(
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (enabled) 1f else 0.45f)
                .iosGlass(18.dp)
                .clickable(enabled = enabled) { onClick() }
                .semantics { contentDescription = "$title. $subtitle" }
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.height(30.dp), contentAlignment = Alignment.Center) {
                icon(24.dp)
            }
            Text(
                title,
                color = tileTitleColor ?: colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }
    // iOS's row: the glyph centred in 28, 12 to the words - the title in .subheadline semibold
    // (taking the tint unless it is the accent), the line under it in .caption, 2 apart - on the
    // frosted card (glassBackground, 16 corners, its shadow).
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .iosGlass(16.dp)
            .clickable(enabled = enabled) { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) { icon(22.dp) }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = tileTitleColor ?: colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(subtitle, color = colors.textSecondary, fontSize = 12.sp)
        }
    }
}

/** Set by [ActionSheetTiles]: its [ActionSheetRow]s draw as square tiles instead of rows. */
private val LocalActionSheetTiles = staticCompositionLocalOf { false }

/**
 * Lays the [ActionSheetRow]s inside it out as square tiles, three to a row - the look of every
 * long-press menu in the app (messages, chat rows, group and room circles, room rows), so each
 * option reads at a glance instead of as a paragraph (iOS cdac6d0 `ActionSheetTiles`). Other
 * half sheets (confirmations, account menus) keep the row form.
 *
 * Each child is one tile: the columns share the width evenly, and a short last row keeps the
 * column width rather than stretching. A [TileActionSheet] holds them at the height iOS gives its
 * detent ([ActionSheetTileMetrics.sheetHeight]).
 */
@Composable
fun ActionSheetTiles(content: @Composable () -> Unit, onTileCount: ((Int) -> Unit)? = null) {
    CompositionLocalProvider(LocalActionSheetTiles provides true) {
        Layout(content = content, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
            onTileCount?.invoke(measurables.size)
            val spacing = ActionSheetTileMetrics.spacing.roundToPx()
            val tileHeight = ActionSheetTileMetrics.tileHeight.roundToPx()
            val width = constraints.maxWidth
            val tileWidth = ((width - spacing * 2) / 3).coerceAtLeast(0)
            val placeables = measurables.map { it.measure(Constraints.fixed(tileWidth, tileHeight)) }
            val rows = (placeables.size + 2) / 3
            val height = if (rows == 0) 0 else rows * tileHeight + (rows - 1) * spacing
            layout(width, height) {
                placeables.forEachIndexed { index, placeable ->
                    placeable.place((index % 3) * (tileWidth + spacing), (index / 3) * (tileHeight + spacing))
                }
            }
        }
    }
}

/** Sizes for [ActionSheetTiles], and a sheet height to fit them (iOS `ActionSheetTileMetrics`). */
object ActionSheetTileMetrics {
    val tileHeight = 96.dp
    val spacing = 12.dp

    /** The grid's height for [count] tiles (one row at least). */
    fun gridHeight(count: Int): Dp {
        val rows = maxOf(1, (count + 2) / 3)
        return tileHeight * rows + spacing * (rows - 1)
    }

    /** A [TileActionSheet]'s height: its title area, the grid, and the bottom padding. */
    fun sheetHeight(tiles: Int, header: Dp = 70.dp): Dp = header + gridHeight(tiles) + 44.dp
}

/**
 * The sender half sheet a tapped avatar opens in a group thread or a broadcast room: what the
 * avatar's popup menu used to offer, with a line under each option saying what it does - the
 * same shape as the composer's "+" sheet and every other menu in the app that became a sheet.
 * One sheet serves every row; the parent presents it for whichever sender was tapped. Mirrors
 * iOS's `GroupChatDetailView.senderSheet` / `BroadcastChannelView.senderSheet`.
 *
 * Where an option opens something of its own (profile, chat), the caller's callback runs
 * AFTER [onDismiss], so the navigation starts from under a sheet already on its way out.
 *
 * @param muteState null hides the Mute row (broadcast rooms have no per-sender mute); otherwise
 *   whether the sender is currently muted, which picks the row's wording.
 * @param hideSubtitle where the hide is undone - "Room Info" or "Group Info".
 */
/** The sender whose avatar was tapped; non-null presents [SenderActionsSheet]. */
data class SenderSheetTarget(val address: String, val isOwnMessage: Boolean)

@Composable
fun SenderActionsSheet(
    address: String,
    displayName: String,
    isOwnMessage: Boolean,
    onDismiss: () -> Unit,
    onViewProfile: () -> Unit,
    onOpenChat: () -> Unit,
    onPayInKaspa: () -> Unit,
    onCopyAddress: () -> Unit,
    onHide: () -> Unit,
    hideSubtitle: String,
    muteState: Boolean? = null,
    onToggleMute: () -> Unit = {},
) {
    val colors = LocalAppColors.current
    ActionSheetContainer(
        title = if (isOwnMessage) "You" else displayName,
        subtitle = null,
        onDismiss = onDismiss,
    ) {
        Text(
            address,
            color = colors.textSecondary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        ActionSheetRow(
            icon = Icons.Default.Person,
            title = stringResource(R.string.view_profile),
            subtitle = stringResource(R.string.their_profile_kachat_name_address),
        ) { onDismiss(); onViewProfile() }
        if (!isOwnMessage) {
            ActionSheetRow(
                icon = Icons.AutoMirrored.Filled.Chat,
                title = stringResource(R.string.open_chat),
                subtitle = "Message them directly, one to one.",
            ) { onDismiss(); onOpenChat() }
            ActionSheetRow(
                icon = painterResource(R.drawable.ic_kaspa_logo),
                title = stringResource(R.string.pay_in_kaspa),
                subtitle = "Open your chat with them in payment mode.",
            ) { onDismiss(); onPayInKaspa() }
        }
        ActionSheetRow(
            icon = Icons.Default.ContentCopy,
            title = stringResource(R.string.copy_address),
            subtitle = "Copy their Kaspa address.",
        ) { onDismiss(); onCopyAddress() }
        if (!isOwnMessage) {
            if (muteState != null) {
                ActionSheetRow(
                    icon = if (muteState) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    title = if (muteState) "Unmute User" else "Mute User",
                    subtitle = if (muteState) {
                        "Get notified for their messages again."
                    } else {
                        "Their messages still show, but never notify you."
                    },
                ) { onDismiss(); onToggleMute() }
            }
            ActionSheetRow(
                icon = Icons.Default.VisibilityOff,
                title = stringResource(R.string.hide_user),
                subtitle = hideSubtitle,
                tint = LocalAppColors.current.danger,
            ) { onDismiss(); onHide() }
        }
    }
}

/**
 * The rename half of an [ActionSheetContainer] that renames something in place: a field, a Cancel
 * that walks back to the options, and a Save that commits and closes.
 */
@Composable
fun ColumnScope.ActionSheetRenameFields(
    value: String,
    onValueChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    label: String = stringResource(R.string.name),
) {
    val colors = LocalAppColors.current
    val focusRequester = remember { FocusRequester() }
    // The keyboard should be up the moment the field appears - the tap that revealed it was
    // already the decision to type.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    com.kachat.app.ui.theme.IosTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (value.isNotBlank()) onSave() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
            focusedBorderColor = KaspaTeal,
            unfocusedBorderColor = colors.textSecondary,
            focusedLabelColor = KaspaTeal,
            unfocusedLabelColor = colors.textSecondary,
        ),
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f).height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textPrimary),
        ) {
            Text(stringResource(R.string.cancel), fontWeight = FontWeight.SemiBold)
        }
        Button(
            onClick = onSave,
            enabled = value.isNotBlank(),
            modifier = Modifier.weight(1f).height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, contentColor = Color.Black),
        ) {
            Text(stringResource(R.string.save), fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * What to do with a transaction you tapped in an address history: look at it, or record it.
 *
 * Shared by cold storage history, the spending-address history and the chatting address, all
 * three of which used to raise their own identical popup menu.
 */
@Composable
fun TransactionActionsSheet(
    tx: ColdStorageAddressDiscovery.AddressTransaction,
    onOpenExplorer: () -> Unit,
    onAddToPortfolio: () -> Unit,
    onDismiss: () -> Unit,
) {
    ActionSheetContainer(
        title = "Transaction",
        subtitle = summary(tx, com.kachat.app.util.is24HourClock()),
        detail = AddressActivityNotifier.shortAddress(tx.txId),
        onDismiss = onDismiss,
    ) {
        ActionSheetRow(
            icon = Icons.AutoMirrored.Filled.OpenInNew,
            title = "Open in Explorer",
            subtitle = "Opens this transaction on the block explorer.",
            onClick = onOpenExplorer,
        )
        ActionSheetRow(
            icon = Icons.Default.PieChart,
            title = "Add to Portfolio",
            subtitle = "Records it as a buy or a sell in a portfolio of your choosing.",
            onClick = onAddToPortfolio,
        )
    }
}

/** "Sent 12.5 KAS on Sep 3, 2026, 2:02 PM" - what is about to be acted on, in one line. */
private fun summary(tx: ColdStorageAddressDiscovery.AddressTransaction, is24Hour: Boolean): String {
    val direction = if (tx.sent) "Sent" else "Received"
    val amount = AddressActivityNotifier.formatKas(tx.amountSompi)
    val time = tx.blockTimeMillis?.let {
        com.kachat.app.util.IosDateStyle.mediumDateShortTime(it, is24Hour)
    }
    return if (time == null) "$direction $amount ${KaspaUnit.symbol}" else "$direction $amount ${KaspaUnit.symbol} on $time"
}

/**
 * The emoji on a message and who used each, most-used first - iOS ReactionsSheet's `grouped`.
 * Equal counts keep the order the emoji first appear in (iOS's dictionary order is arbitrary
 * there); within an emoji the reactors keep theirs.
 */
internal fun <T> reactionGroups(reactions: List<T>, emojiOf: (T) -> String): List<Pair<String, List<T>>> =
    reactions.groupBy(emojiOf).entries
        .sortedByDescending { it.value.size }
        .map { it.key to it.value }

/**
 * Who reacted to one message, and with what. Shared by 1:1, group and broadcast bubbles.
 *
 * The pill on a bubble shows which emoji are on it and nothing else - not how many of each, and
 * not from whom. iOS's `ReactionsSheet`: "N Reactions" in an inline bar, then a grouped List - a
 * section per emoji, most-used first, headed by the emoji and "N people", a row per reactor with
 * their avatar and name. Opens at half height and drags up to full (`[.medium, .large]`), with
 * the grabber.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatReactionsSheet(
    reactions: List<com.kachat.app.models.ReactionEntity>,
    isMe: (String) -> Boolean,
    nameFor: (String) -> String,
    onDismiss: () -> Unit,
    /** KNS avatar for a reactor, when one is cached. A face is how you recognise someone in a
     *  list of names you may not have saved. */
    avatarFor: (String) -> String? = { null },
) {
    val grouped = remember(reactions) { reactionGroups(reactions) { it.emoji } }
    val halfScreen = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp / 2).dp
    com.kachat.app.ui.theme.IosSheetColors(grouped = true) {
        val colors = LocalAppColors.current
        ModalBottomSheet(
            shape = com.kachat.app.ui.theme.IosSheetShape,
            tonalElevation = com.kachat.app.ui.theme.IosSheetTonalElevation,
            windowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars,
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.background,
            dragHandle = null,
        ) {
            Column(Modifier.navigationBarsPadding()) {
                IosSheetDetents(height = halfScreen, largeDetent = true) {
                    Column(Modifier.fillMaxSize()) {
                        IosSheetNavBar(
                            title = if (reactions.size == 1) stringResource(R.string.reactions_one)
                            else stringResource(R.string.reactions_count, reactions.size),
                        )
                        androidx.compose.foundation.lazy.LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            grouped.forEach { (emoji, rows) ->
                                item(key = emoji) {
                                    Column {
                                        // The section header: the emoji at title3, then the count in
                                        // the grouped list's small upper-case caption.
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
                                        ) {
                                            Text(emoji, fontSize = 20.sp)
                                            Text(
                                                (if (rows.size == 1) stringResource(R.string.reactions_one_person)
                                                else stringResource(R.string.reactions_people, rows.size)).uppercase(),
                                                color = colors.textSecondary,
                                                fontSize = 13.sp,
                                            )
                                        }
                                        Column(
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(26.dp))
                                                .background(colors.surface)
                                        ) {
                                            rows.forEachIndexed { index, row ->
                                                val label = if (isMe(row.reactorAddress)) stringResource(R.string.reactions_you) else nameFor(row.reactorAddress)
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .heightIn(min = 44.dp)
                                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                                ) {
                                                    ContactAvatar(
                                                        imageUrl = avatarFor(row.reactorAddress),
                                                        fallbackText = label,
                                                        size = 28.dp,
                                                        address = row.reactorAddress,
                                                    )
                                                    MiddleEllipsisText(
                                                        label,
                                                        color = colors.textPrimary,
                                                        fontSize = 17.sp,
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                }
                                                if (index < rows.lastIndex) {
                                                    HorizontalDivider(
                                                        color = colors.divider,
                                                        thickness = 0.5.dp,
                                                        // Under the name, past the avatar, as iOS's separator.
                                                        modifier = Modifier.padding(start = 54.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    IosSheetGrabber(Modifier.align(Alignment.TopCenter))
                }
            }
        }
    }
}


/**
 * What to do with a link someone sent: the half-sheet form of the old alert dialog.
 *
 * A dialog of bare text buttons could show the verb and nothing else, and squeezed the URL into
 * its title. As a sheet the link itself gets room to be read - which matters, because deciding
 * whether to open a link IS reading it. Mirrors iOS's `LinkActionsSheet`.
 */
@Composable
fun LinkActionsSheet(
    url: String,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    /** Null where replying makes no sense (a KaPost, a broadcast you cannot reply into). */
    onReply: (() -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    ActionSheetContainer(title = "Link", subtitle = null, onDismiss = onDismiss) {
        MiddleEllipsisText(
            url,
            color = colors.textSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 3,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(4.dp))
        ActionSheetRow(
            icon = Icons.Default.Public,
            title = stringResource(R.string.open_link),
            subtitle = "Opens in your browser.",
        ) { onDismiss(); onOpen() }
        // Not for a Nextcloud share: the link is the address of someone's file, and the preview
        // card already refuses to hand it out on long-press - same rule here, same classifier.
        if (com.kachat.app.services.LinkPreviewService.nextcloudShareEndpoints(url) == null) {
            ActionSheetRow(
                icon = Icons.Default.ContentCopy,
                title = stringResource(R.string.copy_link),
                subtitle = "Copies the address to your clipboard.",
            ) { onDismiss(); onCopy() }
        }
        if (onReply != null) {
            ActionSheetRow(
                icon = Icons.AutoMirrored.Filled.Reply,
                title = stringResource(R.string.reply),
                subtitle = "Reply to this message instead.",
            ) { onDismiss(); onReply() }
        }
    }
}

/** Repost as-is, or quote it with your own words. Mirrors iOS's `RepostActionsSheet`. */
@Composable
fun RepostActionsSheet(
    isReposted: Boolean,
    onDismiss: () -> Unit,
    onRepost: () -> Unit,
    onQuote: () -> Unit,
) {
    ActionSheetContainer(
        title = if (isReposted) "Reposted" else "Repost",
        subtitle = null,
        onDismiss = onDismiss,
    ) {
        ActionSheetRow(
            icon = if (isReposted) Icons.AutoMirrored.Filled.Undo else Icons.Default.Repeat,
            title = if (isReposted) "Undo Repost" else "Repost",
            subtitle = if (isReposted) {
                "Removes it from your profile."
            } else {
                "Shares it to your followers as-is."
            },
            tint = if (isReposted) LocalAppColors.current.danger else KaspaTeal,
        ) { onDismiss(); onRepost() }
        ActionSheetRow(
            icon = Icons.Default.FormatQuote,
            title = "Quote",
            subtitle = "Adds your own words above it.",
        ) { onDismiss(); onQuote() }
    }
}

/**
 * A yes/no confirmation, in a half sheet.
 *
 * The app's confirmations were AlertDialogs - a stack of bare verbs with the reason squeezed into
 * a small line above them. As a sheet the consequence gets a full row of its own next to the
 * action it belongs to, which is what someone about to log out or delete something is actually
 * reading for.
 */
@Composable
fun ConfirmActionSheet(
    title: String,
    confirmTitle: String,
    /** What actually happens if they go ahead - the row's second line. */
    confirmSubtitle: String,
    confirmIcon: ImageVector,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isDestructive: Boolean = true,
    /** An optional SECOND way to go ahead that takes more with it - iOS's "Delete and Remove
     *  Nextcloud Backup" beside a plain Delete. All four are needed for the row to appear, so
     *  every existing caller is unchanged. */
    secondaryTitle: String? = null,
    secondarySubtitle: String? = null,
    secondaryIcon: ImageVector? = null,
    onSecondary: (() -> Unit)? = null,
) {
    ActionSheetContainer(title = title, subtitle = null, onDismiss = onDismiss) {
        ActionSheetRow(
            icon = confirmIcon,
            title = confirmTitle,
            subtitle = confirmSubtitle,
            tint = if (isDestructive) LocalAppColors.current.danger else KaspaTeal,
            onClick = { onDismiss(); onConfirm() },
        )
        if (secondaryTitle != null && secondarySubtitle != null && secondaryIcon != null && onSecondary != null) {
            ActionSheetRow(
                icon = secondaryIcon,
                title = secondaryTitle,
                subtitle = secondarySubtitle,
                tint = LocalAppColors.current.danger,
                onClick = { onDismiss(); onSecondary() },
            )
        }
        ActionSheetRow(
            icon = Icons.Default.Close,
            title = "Cancel",
            subtitle = "Leave everything as it is.",
            onClick = onDismiss,
        )
    }
}

/**
 * A completed Kaspa send, for [SentConfirmationSheet].
 *
 * [amountSompi] and [recipient] are optional: a consolidation self-send has no meaningful "to
 * whom", and naming the address it just came from reads as a mistake.
 */
data class SentTransaction(
    val txId: String,
    val amountSompi: Long? = null,
    val recipient: String? = null,
)

/**
 * The half sheet every successful Kaspa send ends on: a checkmark, what was sent, and the
 * transaction id as a live link to whichever block explorer Settings names.
 *
 * A tip on KaPosts used to close its dialog and tell you nothing - no confirmation, and no
 * transaction to go and check - and the send flow confirmed inside its own full screen instead.
 * A send is the one moment where the txid is worth handing over, so it is a link: tapping it
 * opens the explorer. Mirrors iOS's `SentConfirmationSheet`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentConfirmationSheet(
    transaction: SentTransaction,
    explorerName: String,
    explorerUrl: String?,
    onDone: () -> Unit,
) {
    val colors = LocalAppColors.current
    val uriHandler = LocalUriHandler.current

    com.kachat.app.ui.theme.IosSheetColors {
        val colors = LocalAppColors.current
        ModalBottomSheet(
            shape = com.kachat.app.ui.theme.IosSheetShape,
            tonalElevation = com.kachat.app.ui.theme.IosSheetTonalElevation,
            windowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars,
            onDismissRequest = onDone,
            // Expanded, not half-height: partial expansion cuts the Done button off.
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.background,
        ) {
            // The sheet runs down behind the navigation bar, as iOS's does behind the home indicator;
            // its content stays above it.
            Column(Modifier.navigationBarsPadding()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = LocalAppColors.current.success,
                        modifier = Modifier.size(52.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Sent", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)

                    transaction.amountSompi?.let { sompi ->
                        Spacer(Modifier.height(2.dp))
                        Text(trimmedKasAmount(sompi), color = colors.textSecondary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    }

                    transaction.recipient?.takeIf { it.isNotBlank() }?.let { recipient ->
                        Spacer(Modifier.height(2.dp))
                        MiddleEllipsisText(
                            "to $recipient",
                            color = colors.textSecondary,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    // The txid, as the link. Middle-truncated because both ends identify it and the
                    // middle does not.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .iosGlass(14.dp)
                            .let { base ->
                                if (explorerUrl != null) base.clickable { uriHandler.openUri(explorerUrl) } else base
                            }
                            .padding(16.dp),
                    ) {
                        Text(
                            if (explorerUrl != null) "Transaction ID · tap to view in $explorerName" else "Transaction ID",
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        MiddleEllipsisText(
                            transaction.txId,
                            color = if (explorerUrl != null) KaspaTeal else colors.textPrimary,
                            fontSize = 13.sp,
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    Button(
                        onClick = onDone,
                        colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, contentColor = Color.Black),
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Text("Done", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * A sheet that iOS draws as a `NavigationStack` holding a `List` or `Form` with an inline title
 * (chat info's ".kachat Names" and "KNS Domains"): the grouped palette, the title centred in the
 * bar, then one 26pt-cornered section of rows - [content] draws the rows, [IosListRowDivider]
 * between them. Opens at half height and drags up to full (`[.medium, .large]`), with the grabber.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IosInlineListSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val halfScreen = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp / 2).dp
    com.kachat.app.ui.theme.IosSheetColors(grouped = true) {
        val colors = LocalAppColors.current
        ModalBottomSheet(
            shape = com.kachat.app.ui.theme.IosSheetShape,
            tonalElevation = com.kachat.app.ui.theme.IosSheetTonalElevation,
            windowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars,
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.background,
            dragHandle = null,
        ) {
            Column(Modifier.navigationBarsPadding()) {
                IosSheetDetents(height = halfScreen, largeDetent = true) {
                    Column(Modifier.fillMaxSize()) {
                        IosSheetNavBar(title = title)
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)
                        ) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(26.dp))
                                    .background(colors.surface),
                                content = content,
                            )
                        }
                    }
                    IosSheetGrabber(Modifier.align(Alignment.TopCenter))
                }
            }
        }
    }
}

/** The hairline between two rows of an [IosInlineListSheet], inset from the leading edge as
 *  iOS's separator is (past the icon, when the row has one). */
@Composable
fun IosListRowDivider(start: Dp = 16.dp) {
    HorizontalDivider(
        color = LocalAppColors.current.divider,
        thickness = 0.5.dp,
        modifier = Modifier.padding(start = start),
    )
}

/**
 * iOS ManageAddressesView's ConsolidateSuccessCard: after "Send All Kaspa To Primary Spend
 * Address", a half sheet - min(420, 220 + 44 per transaction) tall, with the grabber - holding a
 * 300-wide card: the green checkmark, "Sent", every transaction the sweep submitted (one per
 * source address) as a monospaced link to the explorer, and OK.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsolidateSuccessSheet(
    txIds: List<String>,
    explorer: com.kachat.app.models.KaspaExplorer,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    com.kachat.app.ui.theme.IosSheetColors {
        val colors = LocalAppColors.current
        ModalBottomSheet(
            shape = com.kachat.app.ui.theme.IosSheetShape,
            tonalElevation = com.kachat.app.ui.theme.IosSheetTonalElevation,
            windowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars,
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.background,
            dragHandle = null,
        ) {
            Column(Modifier.navigationBarsPadding()) {
                IosSheetDetents(height = minOf(420, 220 + txIds.size * 44).dp, largeDetent = false) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .widthIn(max = 300.dp)
                            .fillMaxWidth()
                            .iosShadow(20.dp, Color.Black.copy(alpha = 0.2f), 20.dp, 10.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(colors.surface)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = colors.success,
                            modifier = Modifier.size(44.dp),
                        )
                        Text(stringResource(R.string.sent), color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            txIds.forEach { txId ->
                                Text(
                                    txId,
                                    color = KaspaTeal,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    textAlign = TextAlign.Center,
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                    modifier = Modifier.clickable { uriHandler.openUri(explorer.txUrl(txId)) },
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 50.dp)
                                .clip(CircleShape)
                                .background(KaspaTeal)
                                .clickable(onClick = onDismiss),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(stringResource(R.string.ok), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        }
                    }
                    IosSheetGrabber(Modifier.align(Alignment.TopCenter))
                }
            }
        }
    }
}

/** Trailing zeros trimmed - "1.5 KAS", not "1.50000000 KAS". */
private fun trimmedKasAmount(sompi: Long): String {
    var text = "%.8f".format(java.util.Locale.US, sompi / 100_000_000.0)
    while (text.endsWith("0")) text = text.dropLast(1)
    if (text.endsWith(".")) text = text.dropLast(1)
    return "$text ${KaspaUnit.symbol}"
}

/** What a sent message's delivery row is reporting. Mirrors iOS's `DeliveryStatusLabel.Status`. */
/**
 * The line under a message: its time, and - for your own messages - the delivery status right
 * after it ("10:57 AM  ✓ Sent"). Every chat shows this under every message; it replaced the
 * swipe-left-to-reveal times (iOS fc6aec6, `MessageTimeLine`).
 *
 * [showsDay]: public chat rooms are long feeds, so a message from another day says which
 * ("Yesterday, 9:41 AM", "Sep 28, 9:41 AM"). Threads keep the bare time under their day separators.
 */
@Composable
fun MessageTimeLine(
    timestampMs: Long,
    modifier: Modifier = Modifier,
    showsDay: Boolean = false,
    status: @Composable () -> Unit = {},
) {
    val yesterday = stringResource(R.string.chat_day_yesterday)
    val text = remember(timestampMs, showsDay, yesterday) {
        com.kachat.app.util.ChatTimeFormat.formatTimeLine(timestampMs, showsDay, yesterday)
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, color = LocalAppColors.current.textSecondary, fontSize = 11.sp)
        status()
    }
}

enum class DeliveryStatus { PENDING, SENT, FAILED, WARNING }

/** Maps the persisted `deliveryStatus` string onto [DeliveryStatus]; anything unknown reads as sent,
 *  which is what the old `else ->` branch did. */
fun deliveryStatusOf(raw: String?): DeliveryStatus = when (raw) {
    "pending" -> DeliveryStatus.PENDING
    "failed" -> DeliveryStatus.FAILED
    "warning" -> DeliveryStatus.WARNING
    else -> DeliveryStatus.SENT
}

/**
 * The delivery row under a sent message: an icon and, now, the word for it — "Sending", "Sent",
 * "Failed · Tap to retry", "Needs attention". Direct port of iOS's `DeliveryStatusLabel`
 * (bb1f9f5): a green check, a clock and a red mark alone left people guessing, and a colour-only
 * glyph tells a screen reader nothing.
 *
 * [onRetry] non-null on [DeliveryStatus.FAILED] makes the whole row the retry button.
 * [compact] is the chat list's smaller, secondary rendering.
 */
@Composable
fun DeliveryStatusLabel(
    status: DeliveryStatus,
    onRetry: (() -> Unit)? = null,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val tint = when (status) {
        DeliveryStatus.SENT -> if (compact) colors.textSecondary else LocalAppColors.current.success
        DeliveryStatus.PENDING -> colors.textSecondary
        DeliveryStatus.FAILED -> LocalAppColors.current.danger
        DeliveryStatus.WARNING -> Color(0xFFFF9500)
    }
    val icon = when (status) {
        DeliveryStatus.SENT -> if (compact) Icons.Default.Check else Icons.Default.CheckCircle
        DeliveryStatus.PENDING -> Icons.Default.Schedule
        DeliveryStatus.FAILED, DeliveryStatus.WARNING -> Icons.Default.Error
    }
    val retryable = status == DeliveryStatus.FAILED && onRetry != null
    val label = when (status) {
        DeliveryStatus.SENT -> stringResource(R.string.delivery_sent)
        DeliveryStatus.PENDING -> stringResource(R.string.delivery_sending)
        DeliveryStatus.FAILED ->
            if (retryable) stringResource(R.string.delivery_failed_tap_to_retry)
            else stringResource(R.string.delivery_failed)
        DeliveryStatus.WARNING -> stringResource(R.string.delivery_needs_attention)
    }
    val spoken = if (retryable) stringResource(R.string.delivery_failed_retry_accessibility) else label
    Row(
        modifier = modifier
            .then(if (retryable) Modifier.clickable { onRetry?.invoke() } else Modifier)
            .semantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
        Text(
            label,
            color = tint,
            fontSize = 11.sp,
            fontWeight = if (retryable) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

// MARK: - Chat header tap band

/** Where the header chip (avatar and name) sits on screen, recorded by [chatHeaderChip]. Plain
 *  fields rather than state: they are read only at the moment of a tap, never drawn from. */
class ChatHeaderTapBandState {
    internal var chipLeft = 0f
    internal var chipRight = 0f
    internal var hasChip = false
    internal var bandLeft = 0f
}

/** Marks the header chip, so [chatHeaderTapBand] knows where it is. */
fun Modifier.chatHeaderChip(state: ChatHeaderTapBandState): Modifier =
    onGloballyPositioned {
        val bounds = it.boundsInRoot()
        state.chipLeft = bounds.left
        state.chipRight = bounds.right
        state.hasChip = true
    }

/**
 * The header row over a 1:1, group or public chat thread, as iOS's `chatHeaderTapBand` (1d5a555):
 * taps over the chip and within 24dp either side of it open the info screen - User Info, Group
 * Info, Room Info - and taps on the dead space further out jump to the first message.
 *
 * The chip, the back button and the header's actions keep their own taps: a child that handles a
 * press consumes it, and this only ever sees what nothing above it claimed, so the margin can
 * never take a tap from Back. It decides by position rather than by enlarging the chip, which
 * would have done exactly that on a narrow screen. Positions are in screen coordinates, so the
 * chip may be a child of the band (1:1, group) or sit in the app bar's title slot (public chat).
 */
fun Modifier.chatHeaderTapBand(
    state: ChatHeaderTapBandState,
    onChip: () -> Unit,
    onBand: () -> Unit,
): Modifier = composed {
    val latestOnChip by rememberUpdatedState(onChip)
    val latestOnBand by rememberUpdatedState(onBand)
    val haptics = LocalHapticFeedback.current
    this
        .onGloballyPositioned { state.bandLeft = it.positionInRoot().x }
        .pointerInput(state) {
            val margin = 24.dp.toPx()
            detectTapGestures { offset ->
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                val x = state.bandLeft + offset.x
                if (state.hasChip && x >= state.chipLeft - margin && x <= state.chipRight + margin) {
                    latestOnChip()
                } else {
                    latestOnBand()
                }
            }
        }
}

/**
 * The app's underline tab bar - bold teal labels, dimmed when unselected, a teal bar under the
 * selected one and a divider below: the Chats / Group Chats / Public Chats bar, shared by Your
 * Domains and Connection Settings (iOS UnderlineTabBar, ee01f81).
 */
@Composable
fun UnderlineTabBar(
    titles: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Equal widths, like iOS's bar; five tabs (Connection Settings) take a slightly smaller label.
    val labelSize = if (titles.size > 4) 13.sp else 15.sp
    androidx.compose.material3.TabRow(
        selectedTabIndex = selectedIndex.coerceIn(0, (titles.size - 1).coerceAtLeast(0)),
        containerColor = LocalAppColors.current.background,
        contentColor = KaspaTeal,
        modifier = modifier,
    ) {
        titles.forEachIndexed { index, title ->
            androidx.compose.material3.Tab(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                text = {
                    Text(
                        title,
                        fontWeight = FontWeight.Bold,
                        fontSize = labelSize,
                        color = if (index == selectedIndex) KaspaTeal else KaspaTeal.copy(alpha = 0.5f),
                        maxLines = 1,
                        softWrap = false,
                    )
                },
            )
        }
    }
}

