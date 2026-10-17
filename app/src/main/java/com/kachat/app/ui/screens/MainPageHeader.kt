package com.kachat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors

/**
 * The header every main page carries: the connection dot leading, the chatting-address balance
 * centered, an optional trailing action - and the page's bold, left-aligned title underneath.
 *
 * On iOS `ConnectionStatusIndicator` (leading) and `BalanceToolbarLabel` (principal) are toolbar
 * items on essentially every screen, so how you are connected and what you hold are never more
 * than a glance away, whatever you are looking at. Android had them on some pages and not
 * others; this is the one place that decides, so they cannot drift apart again.
 *
 * Goes in a Scaffold's `topBar` slot, which is why it clears the status bar itself - unlike a
 * CenterAlignedTopAppBar, hand-built top bar content gets no inset for free.
 */
/** iOS 26 draws every toolbar item on glass: on black a #191919 disc or capsule edged by a
 *  #2E2E2E hairline (sampled from the iPhone), on white a white one with a faint edge. */
@Composable
fun toolbarGlassFill(): Color = if (LocalAppColors.current.isDark) Color(0xFF191919) else Color.White

@Composable
fun toolbarGlassEdge(): Color = if (LocalAppColors.current.isDark) Color(0xFF2E2E2E) else Color.Black.copy(alpha = 0.08f)

/** The glass behind a toolbar item group, drawn only once it holds something (an empty group
 *  measures 0 wide and shows nothing, as iOS shows no capsule for no items). */
@Composable
fun Modifier.toolbarGlass(): Modifier {
    val fill = toolbarGlassFill()
    val edge = toolbarGlassEdge()
    return this.drawBehind {
        if (size.width <= 0f) return@drawBehind
        val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(fill, cornerRadius = r)
        drawRoundRect(edge, cornerRadius = r, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 0.5.dp.toPx()))
    }
}

/** A toolbar item group on one piece of glass, 44 high - iOS puts neighbouring trailing items
 *  (Chats' gear and Select) in one capsule, a lone one (Profile's bell) in a disc. */
@Composable
fun ToolbarGlassGroup(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier.heightIn(min = 44.dp).defaultMinSize(minWidth = 0.dp).toolbarGlass(),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A text toolbar button ("Select", "Cancel", "Done"): 17pt regular in the accent colour. */
@Composable
fun ToolbarTextButton(text: String, onClick: () -> Unit, fontWeight: FontWeight = FontWeight.Normal) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
    ) {
        Text(text, color = KaspaTeal, fontSize = 17.sp, fontWeight = fontWeight, maxLines = 1)
    }
}

/** An icon toolbar button: a 44 disc's worth of tap target, the icon in the accent colour. */
@Composable
fun ToolbarIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    tint: Color = KaspaTeal,
    iconSize: androidx.compose.ui.unit.Dp = 22.dp,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .clickable(onClick = onClick),
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** True inside a Kaspa Hub section: iOS opens the section in the Hub's own place with no back
 *  button (re-tapping the Kaspa Hub tab steps back out), so the header leaves its back arrow off.
 *  The system Back still closes the section - each one keeps its BackHandler. */
val LocalInHubSection = androidx.compose.runtime.compositionLocalOf { false }

/** iOS's large navigation title: 34pt bold. */
val LargeTitleSize = 34.sp

/**
 * How much of the screen's bottom the floating dock covers (the dock plus the system bar under
 * it), for a tab screen that draws on underneath the dock the way iOS's content runs on under its
 * tab bar: its scrolling list ends with this much padding so the last row can still scroll clear,
 * and anything pinned to the bottom (a + button) sits this far up. 0 where no dock is shown.
 */
val LocalDockInset = androidx.compose.runtime.compositionLocalOf { 0.dp }

/**
 * Lays [content] out above the dock, for a page shown in place of a screen that runs on under it
 * but that does not pad its own end for the dock (LocalDockInset is 0 inside).
 */
@Composable
fun AboveDock(content: @Composable () -> Unit) {
    Box(Modifier.padding(bottom = LocalDockInset.current)) {
        androidx.compose.runtime.CompositionLocalProvider(LocalDockInset provides 0.dp) { content() }
    }
}

/**
 * A navigation bar's row laid out the way UIKit places a `.principal` toolbar item: [principal]
 * centred on the bar when it fits clear of the [leading] and [trailing] items, otherwise packed
 * 12 after the leading items (the Chats bar, whose wide gear-and-Select glass leaves no room to
 * centre the balance - measured on the iPhone).
 */
@Composable
fun IosToolbarRow(
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
    principal: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    androidx.compose.ui.layout.Layout(
        contents = listOf(
            { Box(contentAlignment = Alignment.Center) { leading() } },
            { Box(contentAlignment = Alignment.Center) { principal() } },
            { Box(contentAlignment = Alignment.Center) { trailing() } },
        ),
        modifier = modifier,
    ) { (l, p, t), constraints ->
        val gap = 12.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val lead = l.first().measure(loose)
        val trail = t.first().measure(loose)
        val width = constraints.maxWidth
        val room = (width - lead.width - trail.width - 2 * gap).coerceAtLeast(0)
        val mid = p.first().measure(loose.copy(maxWidth = room))
        val height = maxOf(lead.height, mid.height, trail.height).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            lead.placeRelative(0, (height - lead.height) / 2)
            trail.placeRelative(width - trail.width, (height - trail.height) / 2)
            // Centred when that leaves it clear of both sides (the iPhone centres the Address
            // Book's balance 14 from its glass); otherwise
            // UIKit packs it right after the leading items rather than sliding it partway.
            val centred = (width - mid.width) / 2
            val clear = 14.dp.roundToPx()
            val fitsCentred = centred >= lead.width + clear && centred + mid.width <= width - trail.width - clear
            val x = if (fitsCentred) centred else lead.width + gap
            mid.placeRelative(x, (height - mid.height) / 2)
        }
    }
}

@Composable
fun MainPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    /** A back arrow beside the dot. Null on the dock destinations, which are not pushed into. */
    onBack: (() -> Unit)? = null,
    /** The connection dot and the balance. A pushed page iOS gives only a back button and its
     *  title (Help) passes false. */
    showStatus: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = LocalAppColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.background)
            .statusBarsPadding(),
    ) {
        // iOS's bar: leading items, the balance as the principal item (see IosToolbarRow),
        // trailing items on one piece of glass.
        IosToolbarRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            leading = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (onBack != null && !LocalInHubSection.current) {
                        ToolbarGlassGroup {
                            // iOS 26: the chevron in the label colour, centred on its glass.
                            ToolbarIconButton(Icons.Filled.ArrowBackIosNew, "Back", onBack, tint = LocalAppColors.current.textPrimary, iconSize = 20.dp)
                        }
                    }
                    if (showStatus) ConnectionDotButton(onClick = { ConnectionStatusOverlayState.open() })
                }
            },
            principal = { if (showStatus) BalanceTopBarLabel() },
            trailing = { ToolbarGlassGroup(content = actions) },
        )
        Text(
            text = title,
            color = colors.textPrimary,
            fontSize = LargeTitleSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            // measured on the iPhone: the title's cap 43 below the bar's centre, 5 below it
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 5.dp),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// iOS 26 sheet bars.
//
// In every iOS 26 sheet (and on a pushed page) the bar's text buttons - Cancel, Done, Save, Clear
// All, Close, Edit - sit on glass capsules 44 high, 16 in from the sheet's edges, the text 17pt
// regular in the accent colour (secondary grey when disabled, the glass kept), with the title
// centred in headline. Icon buttons (an "x", a back chevron) sit on a 44 glass disc.
// ---------------------------------------------------------------------------------------------

/** True while drawing with the raised sheet palette ([com.kachat.app.ui.theme.IosSheetColors]):
 *  in dark mode that is the only palette whose background is not black. */
@Composable
fun isInIosSheetPalette(): Boolean {
    val colors = LocalAppColors.current
    return colors.isDark && colors.background != Color.Black
}

/** The glass behind a bar button. On a sheet (dark) iOS's glass reads #252527 edged #444449
 *  (sampled on the iPhone); on a black page it is the toolbar's #191919 edged #2E2E2E; in light
 *  mode white with a faint edge either way. */
@Composable
fun Modifier.barGlass(): Modifier {
    val onSheet = isInIosSheetPalette()
    val fill = if (onSheet) Color(0xFF252527) else toolbarGlassFill()
    val edge = if (onSheet) Color(0xFF444449) else toolbarGlassEdge()
    val stroke = if (onSheet) 0.8.dp else 0.5.dp
    return this.drawBehind {
        if (size.width <= 0f) return@drawBehind
        val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(fill, cornerRadius = r)
        drawRoundRect(edge, cornerRadius = r, style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke.toPx()))
    }
}

/**
 * A text button in a sheet's (or pushed page's) bar - "Cancel", "Done", "Save", "Clear All" - on
 * its own glass capsule, 44 high: 17pt in the accent colour, [emphasized] semibold, secondary grey
 * while disabled (the glass stays, as iOS keeps it under a disabled "Clear All"). The glass is the
 * sheet's when drawn inside the sheet palette, else the toolbar's ([barGlass]).
 */
@Composable
fun SheetBarButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasized: Boolean = false,
    color: Color = KaspaTeal,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = 44.dp)
            .barGlass()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
    ) {
        Text(
            text,
            color = if (enabled) color else LocalAppColors.current.textSecondary,
            fontSize = 17.sp,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/** An icon button in a sheet's (or pushed page's) bar - an "x", a back chevron - on a 44 glass
 *  disc ([barGlass]), the icon in the accent colour (secondary grey while disabled). */
@Composable
fun SheetBarIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = KaspaTeal,
    iconSize: androidx.compose.ui.unit.Dp = 20.dp,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(44.dp)
            .barGlass()
            .clip(androidx.compose.foundation.shape.CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Icon(
            icon,
            contentDescription,
            tint = if (enabled) tint else LocalAppColors.current.textSecondary,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** The back button of a pushed page or a sheet's second level: iOS 26's chevron on a glass disc. */
@Composable
fun SheetBarBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    // iOS 26 draws the back chevron in the label colour (white in dark mode), not the accent.
    SheetBarIconButton(Icons.Filled.ArrowBackIosNew, "Back", onClick, modifier = modifier, tint = LocalAppColors.current.textPrimary, iconSize = 20.dp)
}

/**
 * A sheet's inline bar, built by hand (no Material top bar): [title] in headline centred, the
 * [leading] and [trailing] items 16 in from the edges, the whole bar 76 high so the title sits
 * where iOS puts it under a sheet's rounded top. Put [SheetBarButton]s / [SheetBarIconButton]s
 * in the slots.
 */
@Composable
fun SheetNavBar(
    title: String,
    modifier: Modifier = Modifier,
    leading: @Composable RowScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Box(modifier.fillMaxWidth().height(76.dp).padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = leading,
        )
        Text(
            title,
            color = LocalAppColors.current.textPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 96.dp),
        )
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = trailing,
        )
    }
}

/**
 * A full-height iOS sheet: it starts 10 below the status bar with iOS's rounded top corners
 * ([com.kachat.app.ui.theme.IosSheetShape]) and draws in the sheet palette ([grouped] for a List
 * or Form: #1C1C1E behind #2C2C2E cards in dark), the screen behind dimmed.
 *
 * Inside a full-screen Dialog (an overlay) the real screen behind shows through the dialog's own
 * dim, set to iOS's; as a navigation destination there is nothing behind to show, so the frame
 * paints the page background dimmed instead.
 */
@Composable
fun IosSheetPage(
    grouped: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val base = LocalAppColors.current
    val dialogWindow = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
    if (dialogWindow != null) {
        androidx.compose.runtime.SideEffect { dialogWindow.setDimAmount(0.48f) }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (dialogWindow != null) Modifier
                else Modifier.background(Color.Black.copy(alpha = 0.48f).compositeOver(base.background))
            ),
    ) {
        com.kachat.app.ui.theme.IosSheetColors(grouped = grouped) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(top = 10.dp)
                    .clip(com.kachat.app.ui.theme.IosSheetShape)
                    .background(LocalAppColors.current.background),
                content = content,
            )
        }
    }
}
