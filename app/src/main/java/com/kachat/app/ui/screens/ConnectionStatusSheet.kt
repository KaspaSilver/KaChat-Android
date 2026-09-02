package com.kachat.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.kachat.app.ui.theme.LocalAppColors

/**
 * Whether the connection status half sheet is showing.
 *
 * The green dot appears in eight headers across the app and none of them own the sheet - it is
 * hosted once, above the NavHost, so it comes up over whatever you were reading instead of
 * replacing it. A single flag rather than a CompositionLocal because every one of those dots
 * opens it from inside a plain (non-composable) click lambda.
 */
object ConnectionStatusSheetState {
    var isOpen by mutableStateOf(false)
        private set

    fun open() { isOpen = true }

    fun close() { isOpen = false }
}

/**
 * Hosts the sheet [ConnectionStatusSheetState] drives. Place once, in the app shell.
 *
 * Holds exactly what the full Connection Status page held - status, protocol, node, the pool
 * lists and the pool actions - because it IS that page, rendered in a sheet. It opens at half
 * height; drag it up for the node lists. Mirrors iOS, where the dot has always presented
 * ConnectionStatusDetailView and now does so at `.medium`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionStatusSheetHost() {
    if (!ConnectionStatusSheetState.isOpen) return
    ModalBottomSheet(
        onDismissRequest = { ConnectionStatusSheetState.close() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = LocalAppColors.current.background,
    ) {
        // A bounded height is what gives the sheet a half-open position to settle at; content
        // that sizes itself would open fully every time.
        Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            ConnectionStatusScreen(
                onBack = { ConnectionStatusSheetState.close() },
                inSheet = true,
            )
        }
    }
}
