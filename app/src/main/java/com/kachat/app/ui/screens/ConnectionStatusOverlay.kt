package com.kachat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

/**
 * Whether the Connection Status page is showing.
 *
 * The green dot appears in nine headers across the app and none of them own the page - it is
 * hosted once, above the NavHost. A single flag rather than a CompositionLocal because every one
 * of those dots opens it from inside a plain (non-composable) click lambda.
 */
object ConnectionStatusOverlayState {
    var isOpen by mutableStateOf(false)
        private set

    fun open() { isOpen = true }

    fun close() { isOpen = false }
}

/**
 * Hosts the Connection Status page that [ConnectionStatusOverlayState] drives. Place once, in the
 * app shell.
 *
 * A full-screen OVERLAY rather than a navigation destination, which is the whole point: pushing a
 * route meant Done ran popBackStack, and from a screen the dot shares with an inline host (the
 * Kaspa Hub) or a restored back stack that did not necessarily land you back where you tapped.
 * An overlay has nowhere else to go - closing it reveals exactly the screen you were on. Same as
 * iOS, where the dot presents ConnectionStatusDetailView as a sheet from wherever you are.
 */
@Composable
fun ConnectionStatusOverlayHost() {
    if (!ConnectionStatusOverlayState.isOpen) return
    Dialog(
        onDismissRequest = { ConnectionStatusOverlayState.close() },
        properties = DialogProperties(
            // Full screen, and drawing edge to edge the way the route it replaced did - so the
            // page's own top bar keeps applying the status bar inset itself, exactly once.
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        ConnectionStatusScreen(onBack = { ConnectionStatusOverlayState.close() })
    }
}

/**
 * The header's clickable connection dot (same look as the chat-thread headers), collecting the
 * live node-health color INSIDE its own restart scope: color ticks repaint this 32dp circle only,
 * instead of recomposing the whole screen body.
 */
@Composable
internal fun ConnectionDotButton(onClick: () -> Unit, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    val dotColorHex by androidx.hilt.navigation.compose.hiltViewModel<com.kachat.app.viewmodels.ConnectionViewModel>()
        .dotColorHex.collectAsState()
    // iOS ConnectionStatusIndicator as a toolbar item: a 44pt glass disc, a 12pt status dot.
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .size(44.dp)
            .toolbarGlass()
            .clip(CircleShape)
            .clickable { onClick() },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = androidx.compose.ui.Modifier.size(12.dp).background(androidx.compose.ui.graphics.Color(dotColorHex), CircleShape)
        )
    }
}
