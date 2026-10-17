package com.kachat.app.ui.screens

import androidx.compose.foundation.Image
import com.kachat.app.ui.theme.iosGlass
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.R
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.showAddressCopiedToast
import com.kachat.app.viewmodels.ChatViewModel
import com.kachat.app.viewmodels.WalletViewModel
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------------------------
// Zero-balance funding gate — shared by the 1:1 chat thread, group chat thread, broadcast rooms
// (dimmed composer + floating card) and KaPosts (dialog instead of the post/reply composer).
// ---------------------------------------------------------------------------------------------

/** One screen's view of the zero-balance chat funding gate: [active] is true only while the
 *  chatting (identity) address balance is a *confirmed* 0 KAS — never while it's still
 *  unknown/loading — and [chattingAddress] is the address the funding card offers to fund. */
data class ZeroBalanceFundingGate(val active: Boolean, val chattingAddress: String?)

/** Observes [ChatViewModel.chattingBalanceGateActive] for the calling screen and keeps it
 *  honest: fetches a fresh chatting-address balance once on entry, then re-polls every 10s for
 *  as long as the gate is showing, so receiving funds from anywhere dismisses the gate on its own — nothing else pushes a balance update into WalletService
 *  while the user just sits on a gated screen. */
@Composable
fun rememberZeroBalanceFundingGate(): ZeroBalanceFundingGate {
    val chatViewModel: ChatViewModel = hiltViewModel()
    val walletViewModel: WalletViewModel = hiltViewModel()
    val active by chatViewModel.chattingBalanceGateActive.collectAsState()
    val address by walletViewModel.address.collectAsState()
    LaunchedEffect(Unit) { chatViewModel.refreshChattingBalance() }
    LaunchedEffect(active) {
        while (active) {
            delay(10_000)
            chatViewModel.refreshChattingBalance()
        }
    }
    return ZeroBalanceFundingGate(active, address)
}

/** Dims a composer the way iOS does - `.grayscale(1)` then `.opacity(0.45)` - and makes it inert
 *  while [active]. Consuming every pointer event in the Initial pass keeps all descendants
 *  (including a text field's own focus/keyboard handling) from ever seeing the touch, without
 *  restructuring the composer variants underneath. The grey is drawn through one offscreen layer
 *  with a zero-saturation colour filter, which every Android version can draw. Chain it after
 *  background() so only the controls dim, not the bar's background strip. */
fun Modifier.zeroBalanceComposerGate(active: Boolean): Modifier =
    if (active) {
        this
            .drawWithCache {
                val paint = androidx.compose.ui.graphics.Paint().apply {
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                        androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0f) }
                    )
                    alpha = 0.45f
                }
                onDrawWithContent {
                    drawIntoCanvas { canvas ->
                        canvas.saveLayer(androidx.compose.ui.geometry.Rect(androidx.compose.ui.geometry.Offset.Zero, size), paint)
                        drawContent()
                        canvas.restore()
                    }
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            }
    } else {
        this
    }

/** The funding-gate card itself (iOS ZeroBalanceFundingCardView): the headline in subheadline
 *  semibold, a 140 QR of the chatting address on a white plate (padded 8, corners 12 - the white
 *  is what keeps it scannable in dark mode), then the address in caption2 monospaced beside a
 *  copy button, 14 between each, padded 16 on glassBackground(cornerRadius: 20). [title] is the
 *  headline; null = "Fund your chatting address to start chatting" (iOS 2b45d6e). */
@Composable
fun ZeroBalanceFundingCard(walletAddress: String?, modifier: Modifier = Modifier, title: String? = null) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val colors = LocalAppColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .iosGlass(cornerRadius = 20.dp)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            title ?: "Fund your chatting address to start chatting",
            color = colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )
        walletAddress?.let { gateAddress ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .padding(8.dp)
            ) {
                Image(
                    painter = rememberQrBitmapPainter(gateAddress),
                    contentDescription = stringResource(R.string.qr_code),
                    modifier = Modifier.size(140.dp)
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = gateAddress,
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.weight(1f, fill = false)
                )
                // doc.on.doc at 14 semibold in the accent colour, padded 6 on a 10-corner glass.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .iosGlass(cornerRadius = 10.dp)
                        .clickable {
                            clipboard.setText(AnnotatedString(gateAddress))
                            showAddressCopiedToast(context, gateAddress)
                        }
                        .padding(6.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.copy_address),
                        tint = KaspaTeal,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/** [ZeroBalanceFundingCard] as a standalone dialog — KaPosts shows this instead of opening the
 *  post/reply composer while the gate is active (there's no persistent composer to dim there).
 *  Also the "not enough funds" popup of a profile save, with its own [title] (iOS 2b45d6e). */
@Composable
fun ZeroBalanceFundingDialog(walletAddress: String?, onDismiss: () -> Unit, title: String? = null) {
    Dialog(onDismissRequest = onDismiss) {
        ZeroBalanceFundingCard(walletAddress = walletAddress, modifier = Modifier.fillMaxWidth(), title = title)
    }
}
