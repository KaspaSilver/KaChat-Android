package com.kachat.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import com.kachat.app.models.KaspaExplorer
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.IosHaptic
import com.kachat.app.util.KaspaUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Debug builds only (iOS 7abc06e `SendFailureSimulation`): with the system property
 * `debug.kachat.fail_send` set to 1 (`adb shell setprop debug.kachat.fail_send 1`), every Send
 * Kaspa send fails after a moment as if the phone were offline - so the failure path can be seen
 * without cutting the network. Nothing is built or broadcast. Off in release builds.
 */
object SendFailureSimulation {
    val isEnabled: Boolean
        get() = com.kachat.app.BuildConfig.DEBUG && runCatching {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
                .invoke(null, "debug.kachat.fail_send") == "1"
        }.getOrDefault(false)

    /** How long the simulated send takes before it fails. */
    const val DELAY_MS = 1_500L

    fun error() = java.net.UnknownHostException("The Internet connection appears to be offline.")
}

private enum class SwipePhase { FORM, SENDING }

/**
 * A send screen with "Swipe up to Send" pinned at the bottom (iOS 7abc06e
 * `SwipeUpToSendScrollView`, from KaChat Wallet). Press the bar and drag it up: the whole screen
 * moves up with the finger, and the send screen rises from below in its place. Letting go past
 * halfway up sends: the send screen takes over the full screen, a BlockDAG runs across it while
 * the transaction goes out, and then it shows the confirmation ([sent]). Letting go sooner springs
 * it back. A send that fails or never starts shows "Failed" there with [failureMessage], and Try
 * Again brings the form back as it was. Only the bar answers - scrolling the form never sends.
 * TalkBack gets a plain activate action on the bar.
 *
 * It draws its own header ([SendStepHeader]: Cancel and [screenTitle], with [onBack] Back and
 * Cancel - a step-by-step send's Review, iOS bc3e1ff3) in place of a top bar, so the header goes
 * up with the rest of the screen. [content] is the form, scrolled.
 */
@Composable
fun SwipeUpToSendScreen(
    screenTitle: String,
    onCancel: () -> Unit,
    /** The bar's words: "Swipe up to Send", or "Swipe up to Consolidate". */
    title: String,
    isBusy: Boolean,
    isEnabled: Boolean,
    /** What is being sent, for the sending screen; null leaves the line out. */
    amountSompi: Long?,
    recipient: String?,
    /** Set by the screen once the send went out: the confirmation shows it. */
    sent: SentTransaction?,
    /** The screen's error for a send that failed, shown under "Failed". */
    failureMessage: String?,
    explorer: KaspaExplorer,
    /** The confirmation's Done. */
    onDone: () -> Unit,
    action: () -> Unit,
    /** A step-by-step send's Review: the header shows Back (and Cancel on the right). */
    onBack: (() -> Unit)? = null,
    /** False: only Back in the header (a step pushed inside another sheet, which Back leaves). */
    showsCancel: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalAppColors.current
    val dismissKeyboard = rememberDismissSystemKeyboard()
    val haptic = com.kachat.app.util.rememberHaptics()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    /** How far the screen has been pushed up, in px. */
    var lift by remember { mutableFloatStateOf(0f) }
    /** Past halfway: letting go sends. */
    var armed by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(SwipePhase.FORM) }
    /** The DAG gets a moment on screen before the confirmation, however fast the send was. */
    var dagShown by remember { mutableStateOf(false) }
    /** The screen went busy after the swipe: the send really started. */
    var sendStarted by remember { mutableStateOf(false) }
    /** [sent] arrived. */
    var confirmed by remember { mutableStateOf(false) }
    /** The send ended without a transaction: the sending screen shows "Failed". */
    var failed by remember { mutableStateOf(false) }
    var heightPx by remember { mutableIntStateOf(0) }
    val active = isEnabled && !isBusy
    val latestActive by rememberUpdatedState(active)
    val latestBusy by rememberUpdatedState(isBusy)
    val latestAction by rememberUpdatedState(action)

    fun settle(to: Float) {
        scope.launch { animate(lift, to, animationSpec = spring(dampingRatio = 0.86f, stiffness = 180f)) { v, _ -> lift = v } }
    }

    /** The send ended without a transaction: "Failed" with the error, once the DAG has had its
     *  moment. */
    fun showFailure() {
        if (phase != SwipePhase.SENDING || confirmed || failed) return
        failed = true
        haptic(IosHaptic.ERROR)
    }

    fun startSending() {
        dismissKeyboard()
        armed = false
        dagShown = false
        sendStarted = latestBusy
        confirmed = false
        failed = false
        phase = SwipePhase.SENDING
        settle(heightPx * 2f)
        latestAction()
        scope.launch {
            delay(1_800)
            dagShown = true
        }
        // A send that never started (a check failed before it went busy) fails too.
        scope.launch {
            delay(600)
            if (!sendStarted) showFailure()
        }
    }

    /** Try Again: back down to the form, filled in as it was. */
    fun returnToForm() {
        if (phase != SwipePhase.SENDING || confirmed) return
        settle(0f)
        // After the screen is back down, so the sending screen doesn't change on its way out.
        scope.launch {
            delay(450)
            phase = SwipePhase.FORM
            failed = false
        }
    }

    LaunchedEffect(isBusy) {
        if (isBusy) {
            sendStarted = true
        } else if (phase == SwipePhase.SENDING) {
            // The screen sets `sent` as it stops being busy; give that a moment to land.
            delay(300)
            if (!confirmed) showFailure()
        }
    }
    LaunchedEffect(sent) {
        if (sent != null) {
            confirmed = true
            haptic(IosHaptic.SUCCESS)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .onSizeChanged { heightPx = it.height }
    ) {
        // The form: header, the scrolled content, the bar.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, -lift.roundToInt()) }
                .background(colors.background)
        ) {
            SendStepHeader(screenTitle, onBack = onBack, onCancel = if (showsCancel || onBack == null) onCancel else null)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                content = content,
            )
            val threshold = max(with(density) { 160.dp.toPx() }, heightPx * 0.5f)
            SwipeUpBar(
                title = title,
                armed = armed,
                isEnabled = isEnabled,
                onActivate = { if (latestActive && phase == SwipePhase.FORM) startSending() },
                modifier = Modifier.pointerInput(threshold) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!latestActive || phase != SwipePhase.FORM) return@awaitEachGesture
                        // In screen terms: the bar moves with the screen, so its own coordinates
                        // would chase the finger.
                        val startY = down.position.y - lift
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            if (phase != SwipePhase.FORM) continue
                            val y = change.position.y - lift
                            val next = max(0f, startY - y)
                            if (lift == 0f && next > 0f) {
                                // a keyboard left up (the memo) would sit over the rising screen
                                dismissKeyboard()
                                haptic(IosHaptic.IMPACT_LIGHT)
                            }
                            lift = next
                            val pastThreshold = lift >= threshold
                            if (pastThreshold != armed) {
                                armed = pastThreshold
                                haptic(if (pastThreshold) IosHaptic.IMPACT_MEDIUM else IosHaptic.IMPACT_LIGHT)
                            }
                        }
                        if (phase != SwipePhase.FORM) return@awaitEachGesture
                        if (armed && latestActive) {
                            startSending()
                        } else {
                            armed = false
                            settle(0f)
                        }
                    }
                },
            )
        }

        if (lift > 0f || phase == SwipePhase.SENDING) {
            SendProgressScreen(
                amountSompi = amountSompi,
                recipient = recipient,
                sent = if (dagShown) sent else null,
                done = dagShown && sent != null,
                failure = if (failed && dagShown) failureMessage ?: stringResource(R.string.swipe_send_failed_fallback) else null,
                isSending = phase == SwipePhase.SENDING,
                armed = armed,
                explorer = explorer,
                onDone = onDone,
                onTryAgain = ::returnToForm,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, max(0f, heightPx - lift).roundToInt()) },
            )
        }
    }
    // Back is Cancel on the form; while the send screen is up it does nothing until Done or Try
    // Again (iOS interactiveDismissDisabled while sending).
    BackHandler(enabled = phase == SwipePhase.SENDING) {}
}

@Composable
private fun SwipeUpBar(
    title: String,
    armed: Boolean,
    isEnabled: Boolean,
    onActivate: () -> Unit,
    modifier: Modifier,
) {
    val releaseText = stringResource(R.string.swipe_release_to_send)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(LocalAppColors.current.background)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(KaspaTeal.copy(alpha = if (isEnabled) 1f else 0.4f), RoundedCornerShape(32.dp))
                .clearAndSetSemantics {
                    contentDescription = title
                    role = Role.Button
                    onClick { onActivate(); true }
                },
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                if (armed) Icons.Default.Check else Icons.Default.KeyboardArrowUp,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(20.dp),
            )
            Text(if (armed) releaseText else title, color = Color.Black, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
    }
}

/**
 * The full screen a swipe-up send moves to (iOS 7abc06e `SendProgressScreen`): a BlockDAG running
 * across while the transaction goes out, then the confirmation - what was sent, to whom, and the
 * transaction id as a link to the block explorer Settings names - or "Failed" with the error and
 * Try Again.
 */
@Composable
private fun SendProgressScreen(
    amountSompi: Long?,
    recipient: String?,
    /** Non-null once confirmed. */
    sent: SentTransaction?,
    done: Boolean,
    /** Non-null once the send failed: the error to show. */
    failure: String?,
    /** False while the swipe is still being dragged (a preview of what's coming). */
    isSending: Boolean,
    armed: Boolean,
    explorer: KaspaExplorer,
    onDone: () -> Unit,
    onTryAgain: () -> Unit,
    modifier: Modifier,
) {
    val colors = LocalAppColors.current
    val uriHandler = LocalUriHandler.current
    // Sent or failed: the DAG steps back and the outcome takes over.
    val finished = done || failure != null
    val dagAlpha by animateFloatAsState(if (finished) 0.35f else 1f, tween(350), label = "dag")
    Column(
        modifier = modifier
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(top = 70.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // From the top rather than centred: while the swipe is still rising, the DAG is the first
        // thing that comes into view.
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().height(200.dp)) {
            BlockDagAnimation(highlight = isSending && failure == null, modifier = Modifier.fillMaxSize().alpha(dagAlpha))
            // Not the ColumnScope overload: this sits in a Box.
            androidx.compose.animation.AnimatedVisibility(visible = done, enter = scaleIn(initialScale = 0.4f) + fadeIn()) {
                // iOS checkmark.circle.fill in black on the accent: the check shows the black disc
                // behind the icon's cut-out.
                Box(contentAlignment = Alignment.Center) {
                    Box(Modifier.size(56.dp).background(Color.Black, CircleShape))
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(86.dp))
                }
            }
            androidx.compose.animation.AnimatedVisibility(visible = failure != null && !done, enter = scaleIn(initialScale = 0.4f) + fadeIn()) {
                Box(contentAlignment = Alignment.Center) {
                    Box(Modifier.size(56.dp).background(Color.White, CircleShape))
                    Icon(Icons.Default.Cancel, contentDescription = null, tint = colors.danger, modifier = Modifier.size(86.dp))
                }
            }
        }

        Column(
            modifier = Modifier.padding(top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(
                    when {
                        done -> R.string.sent
                        failure != null -> R.string.swipe_failed
                        isSending -> R.string.swipe_sending
                        armed -> R.string.swipe_release_to_send
                        else -> R.string.swipe_keep_swiping
                    }
                ),
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 34.sp,
            )
            (sent?.amountSompi ?: amountSompi)?.let { sompi ->
                Text(kasText(sompi), color = KaspaTeal, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
            }
            (sent?.recipient ?: recipient)?.takeIf { it.isNotEmpty() }?.let { to ->
                MiddleEllipsisText(
                    stringResource(R.string.payment_to_name, to),
                    color = colors.textSecondary,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
            if (!finished) {
                Text(
                    stringResource(if (isSending) R.string.swipe_broadcasting else R.string.swipe_hint),
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        AnimatedVisibility(visible = failure != null, enter = fadeIn(), exit = fadeOut()) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .padding(top = 24.dp)
                    .fillMaxWidth()
                    .sendKaspaGlass(14.dp)
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = colors.danger, modifier = Modifier.size(18.dp))
                Text(failure.orEmpty(), color = colors.danger, fontSize = 15.sp)
            }
        }

        if (sent != null) {
            val url = explorer.txUrl(sent.txId)
            Column(
                modifier = Modifier.padding(horizontal = 24.dp).padding(top = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (url != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .sendKaspaGlass(14.dp)
                            .clickable { uriHandler.openUri(url) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MiddleEllipsisText(
                            sent.txId,
                            color = KaspaTeal,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(14.dp))
                    }
                    Text(stringResource(R.string.swipe_tap_tx_hint), color = colors.textSecondary, fontSize = 12.sp)
                } else {
                    MiddleEllipsisText(sent.txId, color = colors.textSecondary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        Spacer(Modifier.weight(1f))

        AnimatedVisibility(visible = done || failure != null, enter = slideInVertically { it } + fadeIn()) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(KaspaTeal, RoundedCornerShape(28.dp))
                    .clickable { if (done) onDone() else onTryAgain() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(if (done) R.string.done else R.string.try_again),
                    color = Color.Black,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                )
            }
        }
    }
}

private fun kasText(sompi: Long): String {
    var text = "%.8f".format(java.util.Locale.US, sompi / 100_000_000.0)
    while (text.endsWith("0")) text = text.dropLast(1)
    if (text.endsWith(".")) text = text.dropLast(1)
    return "$text ${KaspaUnit.symbol}"
}

/**
 * Kaspa's BlockDAG drifting right to left (iOS 7abc06e `BlockDAGAnimationView`): columns of
 * blocks, each pointing back at one or two blocks of the column before (several parents, not a
 * chain). With [highlight], one block - the transaction going out - lights up as it enters on the
 * right, and the block after it points back at it in the same colour. Laid out from each column's
 * index, so the picture is stable as it moves.
 */
@Composable
fun BlockDagAnimation(highlight: Boolean, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val spacing = with(density) { 54.dp.toPx() }
    val speed = with(density) { 60.dp.toPx() } // per second
    val block = with(density) { 18.dp.toPx() }
    val corner = with(density) { 5.dp.toPx() }
    val glowInset = with(density) { 7.dp.toPx() }
    val primary = LocalAppColors.current.textPrimary

    var elapsed by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> elapsed = (now - start) / 1_000_000_000f }
        }
    }
    /** When [highlight] turned on: the block entering on the right then is the transaction's. */
    var highlightStart by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(highlight) { highlightStart = if (highlight) elapsed else null }

    Canvas(modifier = modifier) {
        val scroll = elapsed * speed
        val firstVisible = floor(scroll / spacing).toInt() - 1
        val visibleColumns = (size.width / spacing).toInt() + 3
        // The column just off the right edge when the send started is the transaction's.
        val highlightScroll = (highlightStart ?: 0f) * speed
        val txColumn = ((highlightScroll + size.width) / spacing).toInt() + 1
        fun x(column: Int) = column * spacing - scroll
        fun point(column: Int, row: Int) = Offset(x(column), DagLayout.y(column, row) * size.height)
        val dim = primary.copy(alpha = 0.28f)

        // Edges first, under the blocks.
        for (column in firstVisible..(firstVisible + visibleColumns)) {
            for (row in 0 until DagLayout.count(column)) {
                for (parent in DagLayout.parents(column, row)) {
                    val lit = highlight && column - 1 == txColumn && parent == 0
                    drawLine(
                        color = if (lit) KaspaTeal.copy(alpha = 0.8f) else dim,
                        start = point(column, row),
                        end = point(column - 1, parent),
                        strokeWidth = with(density) { (if (lit) 2.dp else 1.dp).toPx() },
                    )
                }
            }
        }
        for (column in firstVisible..(firstVisible + visibleColumns)) {
            for (row in 0 until DagLayout.count(column)) {
                val center = point(column, row)
                val topLeft = Offset(center.x - block / 2, center.y - block / 2)
                if (highlight && column == txColumn && row == 0) {
                    val pulse = 0.25f + 0.2f * sin(elapsed * 4f)
                    drawRoundRect(
                        color = KaspaTeal.copy(alpha = pulse),
                        topLeft = Offset(topLeft.x - glowInset, topLeft.y - glowInset),
                        size = Size(block + glowInset * 2, block + glowInset * 2),
                        cornerRadius = CornerRadius(with(density) { 9.dp.toPx() }),
                    )
                    drawRoundRect(KaspaTeal, topLeft, Size(block, block), CornerRadius(corner))
                } else {
                    drawRoundRect(primary.copy(alpha = 0.16f), topLeft, Size(block, block), CornerRadius(corner))
                    drawRoundRect(primary.copy(alpha = 0.45f), topLeft, Size(block, block), CornerRadius(corner), style = Stroke(width = with(density) { 1.dp.toPx() }))
                }
            }
        }
    }
}

/** The DAG's layout, from each column's index (iOS `BlockDAGAnimationView`'s hash). */
private object DagLayout {
    /** A stable pseudo-random number for (column, salt). */
    fun hash(column: Int, salt: Int): Int {
        // Swift's Int is 64-bit: the products wrap there, not at 32 bits.
        var x = ((column.toLong() * 73_856_093L) xor (salt.toLong() * 19_349_663L)).toULong()
        x = x xor (x shr 33)
        x *= 0xff51afd7ed558ccdUL
        x = x xor (x shr 33)
        return (x % 1_000_003UL).toInt()
    }

    fun count(column: Int) = 1 + hash(column, 1) % 3

    /** Block [row] of [column]'s centre height, as a fraction of the view. */
    fun y(column: Int, row: Int): Float {
        val n = count(column)
        val jitter = (hash(column, 10 + row) % 100) / 100f * 0.12f - 0.06f
        return (row + 1f) / (n + 1f) + jitter
    }

    /** The parents of block [row] in [column]: rows of `column - 1`. */
    fun parents(column: Int, row: Int): List<Int> {
        val previous = count(column - 1)
        val first = hash(column, 20 + row) % previous
        return if (previous > 1 && hash(column, 30 + row) % 2 == 0) listOf(first, (first + 1) % previous) else listOf(first)
    }
}
