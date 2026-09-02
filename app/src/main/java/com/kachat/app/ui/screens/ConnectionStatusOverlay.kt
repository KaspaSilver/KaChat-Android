package com.kachat.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

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
