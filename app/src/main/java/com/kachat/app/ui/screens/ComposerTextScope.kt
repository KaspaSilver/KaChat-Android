package com.kachat.app.ui.screens

import androidx.compose.runtime.Composable

/**
 * Runs [content] in a recomposition scope of its own. The chat composers (1:1, group, public
 * room) read their text only in here, so a keystroke re-runs just the text field - not the
 * composer around it, the message list or the header. The rest of the screen reads only what it
 * needs, through `derivedStateOf` (empty or not), which changes on the first and last character
 * rather than on every one (iOS 0977a5b's ComposerTextBox).
 *
 * It lays nothing out of its own: [content] is placed as if written in place, so a Row's
 * `Modifier.weight` on it still applies.
 */
@Composable
fun ComposerTextScope(content: @Composable () -> Unit) {
    content()
}
