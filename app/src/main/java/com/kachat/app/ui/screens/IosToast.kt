package com.kachat.app.ui.screens

import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.ui.theme.iosShadow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** iOS's `ToastStyle`: a green checkmark or a red x-octagon before the text. */
enum class IosToastStyle { Success, Error }

/** One toast on screen. [id] is iOS's `toastToken`: a later toast replaces it, and only its own timer takes it down. */
data class IosToast(val id: Long, val message: String, val style: IosToastStyle, val durationMs: Long)

/**
 * The state behind iOS's in-app toast (`View.toast(message:style:)`): one message at a time, the
 * newest replacing whatever was showing, each taken down [IosToastState.show]'s `durationMs`
 * after it went up - unless a newer one has replaced it, exactly as iOS's `showToast` compares
 * its `toastToken` before clearing.
 *
 * Every screen and sheet that can show a toast composes an [IosToastHost]; the host composed
 * last (the sheet on top) draws it, and when that sheet goes the toast carries on in the host
 * underneath - as iOS's toast stays up on the view a closing sheet returns to.
 *
 * [schedule] runs a callback after a delay (the main looper in the app, a fake in tests).
 */
class IosToastState(private val schedule: (delayMs: Long, block: () -> Unit) -> Unit) {
    private val nextId = AtomicLong(0)
    private val _current = MutableStateFlow<IosToast?>(null)
    val current: StateFlow<IosToast?> = _current.asStateFlow()

    private val _hosts = MutableStateFlow<List<Long>>(emptyList())
    /** The hosts on screen, oldest first; the last one draws. */
    val hosts: StateFlow<List<Long>> = _hosts.asStateFlow()

    /** Puts [message] up, replacing whatever was showing, and returns its id. A toast of
     *  [IosToastDuration.UNTIL_DISMISSED] stays until [dismiss]ed or replaced. */
    fun show(message: String, style: IosToastStyle = IosToastStyle.Success, durationMs: Long = IosToastDuration.STANDARD): Long {
        val toast = IosToast(nextId.incrementAndGet(), message, style, durationMs)
        _current.value = toast
        if (durationMs != IosToastDuration.UNTIL_DISMISSED) schedule(durationMs) { dismiss(toast.id) }
        return toast.id
    }

    /** Takes down the toast [id] if it is still the one showing. */
    fun dismiss(id: Long) {
        _current.update { if (it?.id == id) null else it }
    }

    fun newHostId(): Long = nextId.incrementAndGet()

    fun addHost(id: Long) = _hosts.update { it - id + id }

    fun removeHost(id: Long) = _hosts.update { it - id }
}

/** How long iOS's screens keep their toast up: 1.6 s, and 3 s in the group and public chats. */
object IosToastDuration {
    const val STANDARD = 1_600L
    const val LONG = 3_000L
    /** Up for as long as the state behind it lasts - iOS's `.toast(message: service.lastError)`. */
    const val UNTIL_DISMISSED = Long.MAX_VALUE
}

/** The app's one toast. Callable from any thread. */
object IosToasts {
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    val state = IosToastState { delayMs, block -> mainHandler.postDelayed(block, delayMs) }

    fun show(message: String, style: IosToastStyle = IosToastStyle.Success, durationMs: Long = IosToastDuration.STANDARD) {
        state.show(message, style, durationMs)
    }

    fun error(message: String, durationMs: Long = IosToastDuration.STANDARD) {
        state.show(message, IosToastStyle.Error, durationMs)
    }
}

/**
 * A toast bound to a piece of state, as iOS's `.toast(message:)` modifier is bound to an optional
 * string: up while [message] is non-null, down when it turns null or this leaves the screen.
 */
@Composable
fun IosToastWhile(message: String?, style: IosToastStyle = IosToastStyle.Success) {
    DisposableEffect(message, style) {
        val id = message?.let { IosToasts.state.show(it, style, IosToastDuration.UNTIL_DISMISSED) }
        onDispose { id?.let { IosToasts.state.dismiss(it) } }
    }
}

/**
 * Where [IosToasts] draws: iOS's `ToastPresenter`, the capsule at the bottom of the screen (or
 * sheet) it covers, 12 above [bottomInsets]. Fill the screen or sheet with it, over the content.
 */
@Composable
fun IosToastHost(
    modifier: Modifier = Modifier,
    bottomInsets: WindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
) {
    val state = IosToasts.state
    val hostId = remember { state.newHostId() }
    DisposableEffect(hostId) {
        state.addHost(hostId)
        onDispose { state.removeHost(hostId) }
    }
    val hosts by state.hosts.collectAsState()
    val toast by state.current.collectAsState()
    // What the capsule shows while it slides away, after the toast itself has gone.
    val last = remember { arrayOfNulls<IosToast>(1) }
    toast?.let { last[0] = it }
    Box(modifier.fillMaxSize().windowInsetsPadding(bottomInsets), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = toast != null && hosts.lastOrNull() == hostId,
            // .move(edge: .bottom).combined(with: .opacity), easeOut 0.2 in and easeIn 0.2 out.
            enter = slideInVertically(tween(200, easing = LinearOutSlowInEasing)) { it } + fadeIn(tween(200, easing = LinearOutSlowInEasing)),
            exit = slideOutVertically(tween(200, easing = FastOutLinearInEasing)) { it } + fadeOut(tween(200, easing = FastOutLinearInEasing)),
            modifier = Modifier.padding(bottom = 12.dp),
        ) {
            last[0]?.let { IosToastBanner(it.message, it.style) }
        }
    }
}

/** Inside a sheet already padded clear of the navigation bar: only the keyboard, past that bar. */
val SheetToastInsets: WindowInsets
    @Composable get() = WindowInsets.ime.exclude(WindowInsets.navigationBars)

/**
 * iOS's `ToastBanner`: the icon and the subheadline text 8 apart, 14 by 10 inside an
 * `.ultraThinMaterial` capsule with a hairline of the label colour at 8% and a soft shadow (black
 * at 15%, radius 8, 4 down), 16 in from the sides. The material is a solid stand-in - older
 * Android cannot blur what lies beneath a view.
 */
@Composable
fun IosToastBanner(message: String, style: IosToastStyle) {
    val colors = LocalAppColors.current
    val capsule = RoundedCornerShape(50)
    val material = if (colors.isDark) Color(0xF2262628) else Color(0xF2FAFAFA)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .iosShadow(100.dp, Color.Black.copy(alpha = 0.15f), 8.dp, 4.dp)
            .clip(capsule)
            .background(material)
            .border(1.dp, colors.textPrimary.copy(alpha = 0.08f), capsule)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Icon(
            if (style == IosToastStyle.Success) Icons.Filled.CheckCircle else Icons.Filled.Dangerous,
            contentDescription = null,
            tint = if (style == IosToastStyle.Success) colors.success else colors.danger,
            modifier = Modifier.size(17.dp),
        )
        Text(message, color = colors.textPrimary, fontSize = 15.sp, lineHeight = 20.sp)
    }
}
