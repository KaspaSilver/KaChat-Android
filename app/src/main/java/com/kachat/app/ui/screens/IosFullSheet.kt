package com.kachat.app.ui.screens

import androidx.compose.foundation.layout.statusBars

import androidx.compose.foundation.layout.Column

import androidx.compose.foundation.layout.navigationBarsPadding

import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.padding
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import com.kachat.app.ui.theme.LocalAppColors
import kotlinx.coroutines.launch

/**
 * A full-height sheet the way iOS presents a `.sheet` with its own navigation bar (Cancel leading)
 * - the send screens: up from the bottom over everything, dock included, its top 10 below the
 * status bar as iOS's large sheet stops.
 *
 * [swipeToDismiss] false is iOS's `.interactiveDismissDisabled()`: neither a swipe down nor a tap
 * above the sheet closes it - only the bar's Cancel does.
 *
 * Android's system Back, which iOS has no counterpart for, goes to the content's own
 * [androidx.activity.compose.BackHandler]s, as it does on a screen: the send screens' Back acts as
 * their Cancel (at any time, mid-send too, as iOS's Cancel), and Back with the QR scanner or coin control open
 * closes just that. A sheet's window would otherwise take Back for itself and close the whole
 * sheet.
 *
 * [content] gets `close`, which slides the sheet down and then calls [onDismissed] - what Cancel
 * and a finished send call.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IosFullSheet(
    onDismissed: () -> Unit,
    swipeToDismiss: Boolean,
    /** iOS's List or Form as the sheet's content (coin control): the grouped light palette
     *  rather than the white plain-content one - see [com.kachat.app.ui.theme.IosSheetColors]. */
    grouped: Boolean = false,
    content: @Composable (close: () -> Unit) -> Unit,
) {
    val latestSwipe by rememberUpdatedState(swipeToDismiss)
    val latestDismissed by rememberUpdatedState(onDismissed)
    val confirm = remember { { value: SheetValue -> value != SheetValue.Hidden || latestSwipe } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = confirm)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = remember(sheetState) {
        { scope.launch { sheetState.hide() }.invokeOnCompletion { latestDismissed() } }
    }
    com.kachat.app.ui.theme.IosSheetColors(grouped = grouped) {
        ModalBottomSheet(
            shape = com.kachat.app.ui.theme.IosSheetShape,
            tonalElevation = com.kachat.app.ui.theme.IosSheetTonalElevation,
            windowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars,
            onDismissRequest = { latestDismissed() },
            sheetState = sheetState,
            containerColor = LocalAppColors.current.background,
            dragHandle = null,
            properties = ModalBottomSheetProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.Inherit, isFocusable = true, shouldDismissOnBackPress = false),
        ) {
            // The sheet runs down behind the navigation bar, as iOS's does behind the home indicator;
            // its content stays above it.
            Column(Modifier.navigationBarsPadding()) {
                val dispatcher = remember { OnBackPressedDispatcher() }
                val lifecycleOwner = LocalLifecycleOwner.current
                val owner = remember(lifecycleOwner) {
                    object : OnBackPressedDispatcherOwner {
                        override val onBackPressedDispatcher: OnBackPressedDispatcher = dispatcher
                        override val lifecycle: Lifecycle get() = lifecycleOwner.lifecycle
                    }
                }
                SheetWindowBack { if (dispatcher.hasEnabledCallbacks()) dispatcher.onBackPressed() }
                CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        Box(Modifier.fillMaxWidth().height(maxHeight - 10.dp)) {
                            content(close)
                            // The sheet's own toast, over it as iOS's toast is over the sheet's view.
                            IosToastHost(bottomInsets = SheetToastInsets)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Back pressed while this sheet's window has focus: a key event the window leaves unhandled, or,
 * where the app takes predictive back, the window's own back callback.
 */
@Composable
private fun SheetWindowBack(onBack: () -> Unit) {
    val view = LocalView.current
    val latest by rememberUpdatedState(onBack)
    DisposableEffect(view) {
        val keyListener = ViewCompat.OnUnhandledKeyEventListenerCompat { _, event ->
            if (event.keyCode != KeyEvent.KEYCODE_BACK) return@OnUnhandledKeyEventListenerCompat false
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) latest()
            true
        }
        ViewCompat.addOnUnhandledKeyEventListener(view, keyListener)
        val callback = if (Build.VERSION.SDK_INT >= 33) Api33.register(view) { latest() } else null
        onDispose {
            ViewCompat.removeOnUnhandledKeyEventListener(view, keyListener)
            if (Build.VERSION.SDK_INT >= 33) Api33.unregister(view, callback)
        }
    }
}

@RequiresApi(33)
private object Api33 {
    fun register(view: View, onBack: () -> Unit): Any? {
        val callback = OnBackInvokedCallback { onBack() }
        view.findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
        return callback
    }

    fun unregister(view: View, callback: Any?) {
        if (callback is OnBackInvokedCallback) view.findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(callback)
    }
}

/**
 * A sheet's inline navigation bar, as iOS draws a `NavigationStack` with
 * `.navigationBarTitleDisplayMode(.inline)` inside a sheet: 56 tall, the title centred in the
 * headline weight, [leading] and [trailing] (usually [IosBarTextButton]s) at the ends.
 */
@Composable
fun IosSheetNavBar(
    title: String,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp)) {
        leading?.let { Box(Modifier.align(androidx.compose.ui.Alignment.CenterStart)) { it() } }
        androidx.compose.material3.Text(
            title,
            color = LocalAppColors.current.textPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.align(androidx.compose.ui.Alignment.Center).padding(horizontal = 80.dp),
        )
        trailing?.let { Box(Modifier.align(androidx.compose.ui.Alignment.CenterEnd)) { it() } }
    }
}

/** A text button in [IosSheetNavBar]: the accent colour at 17, semibold for a confirming action. */
@Composable
fun IosBarTextButton(text: String, onClick: () -> Unit, bold: Boolean = false, enabled: Boolean = true) {
    androidx.compose.material3.TextButton(onClick = onClick, enabled = enabled) {
        androidx.compose.material3.Text(
            text,
            color = if (enabled) com.kachat.app.ui.theme.KaspaTeal else LocalAppColors.current.textSecondary,
            fontSize = 17.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
