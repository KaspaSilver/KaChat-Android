package com.kachat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors

/**
 * The toolbar iOS gives a main page with no large title in its bar (the Address Book): the
 * connection dot on a glass disc leading, the [principal] item (the balance, unless the page has
 * its own) centred, and [actions] on one piece of glass trailing. With [onBack], the back chevron
 * sits before the dot.
 */
@Composable
internal fun HubSectionTopBar(
    onBack: (() -> Unit)?,
    principal: @Composable () -> Unit = { BalanceTopBarLabel() },
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = LocalAppColors.current
    // The principal item placed as UIKit places it (see IosToolbarRow).
    IosToolbarRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        leading = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onBack != null && !LocalInHubSection.current) {
                    // iOS 26: the chevron in the label colour on its glass.
                    ToolbarGlassGroup {
                        ToolbarIconButton(Icons.Filled.ArrowBackIosNew, "Back", onBack, tint = colors.textPrimary, iconSize = 20.dp)
                    }
                }
                ConnectionDotButton(onClick = { ConnectionStatusOverlayState.open() })
            }
        },
        principal = principal,
        trailing = { ToolbarGlassGroup(content = actions) },
    )
}

/**
 * iOS's UnderlineTabBar: equal-width tabs, each label 15pt bold on one line - the selected one in
 * the accent colour, the rest at half strength - shrinking to fit down to 75% rather than being
 * cut ("Marketplace" stays whole), 12 above it, a 2.5pt accent underline 8 below it under the
 * selected tab, and a hairline under the whole bar.
 */
@Composable
internal fun HubUnderlineTabBar(
    titles: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            titles.forEachIndexed { index, title ->
                val isSelected = index == selectedIndex
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            role = androidx.compose.ui.semantics.Role.Tab,
                        ) { onSelect(index) }
                        .semantics { selected = isSelected },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    KachatFitText(
                        title,
                        color = if (isSelected) KaspaTeal else KaspaTeal.copy(alpha = 0.5f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        minScale = 0.75f,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.5.dp)
                            .background(if (isSelected) KaspaTeal else Color.Transparent)
                    )
                }
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = colors.divider)
    }
}
