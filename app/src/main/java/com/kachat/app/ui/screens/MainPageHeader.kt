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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier.align(Alignment.CenterStart),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                }
                ConnectionDotButton(onClick = { ConnectionStatusOverlayState.open() })
            }
            BalanceTopBarLabel(modifier = Modifier.align(Alignment.Center))
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
        Text(
            text = title,
            color = colors.textPrimary,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 8.dp),
        )
    }
}
