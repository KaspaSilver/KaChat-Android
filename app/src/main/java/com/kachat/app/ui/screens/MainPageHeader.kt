package com.kachat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
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

/** iOS's large navigation title: 34pt bold. */
val LargeTitleSize = 34.sp

@Composable
fun MainPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    /** A back arrow beside the dot. Null on the dock destinations, which are not pushed into. */
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = LocalAppColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.background)
            .statusBarsPadding(),
    ) {
        // iOS's bar: leading items, the balance as the principal item centred in the space the
        // leading and trailing items leave, trailing items on one piece of glass.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (onBack != null) {
                    ToolbarGlassGroup {
                        ToolbarIconButton(Icons.AutoMirrored.Filled.ArrowBackIos, "Back", onBack, iconSize = 20.dp)
                    }
                }
                ConnectionDotButton(onClick = { ConnectionStatusOverlayState.open() })
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                BalanceTopBarLabel()
            }
            ToolbarGlassGroup(content = actions)
        }
        Text(
            text = title,
            color = colors.textPrimary,
            fontSize = LargeTitleSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 8.dp),
        )
    }
}
