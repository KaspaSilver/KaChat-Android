package com.kachat.app.ui.screens

import androidx.compose.foundation.Image
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

/** Dims a composer to 35% and makes it inert while [active] — consuming every pointer event in
 *  the Initial pass keeps all descendants (including a TextField's own focus/keyboard handling)
 *  from ever seeing the touch, without restructuring the composer variants underneath. Chain it
 *  after background() so only the controls dim, not the bar's background strip. */
fun Modifier.zeroBalanceComposerGate(active: Boolean): Modifier =
    if (active) {
        this
            .alpha(0.35f)
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

/** The funding-gate card itself: title, a QR of the chatting address on a
 *  white plate (contrast + quiet zone is what makes it scannable regardless of theme — same
 *  reasoning as QrCodeOverlay), the address in monospace, and a copy button. */
@Composable
fun ZeroBalanceFundingCard(walletAddress: String?, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Surface(
        color = LocalAppColors.current.surface,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 8.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Fund your chatting address to start chatting",
                color = LocalAppColors.current.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
            walletAddress?.let { gateAddress ->
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .border(2.dp, KaspaTeal, RoundedCornerShape(16.dp))
                        .padding(12.dp)
                ) {
                    Image(
                        painter = rememberQrBitmapPainter(gateAddress),
                        contentDescription = stringResource(R.string.qr_code),
                        modifier = Modifier.size(150.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = gateAddress,
                    color = LocalAppColors.current.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 260.dp)
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(gateAddress))
                    showAddressCopiedToast(context, gateAddress)
                }) {
                    Icon(Icons.Default.ContentCopy, null, tint = KaspaTeal, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.copy_address), color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** [ZeroBalanceFundingCard] as a standalone dialog — KaPosts shows this instead of opening the
 *  post/reply composer while the gate is active (there's no persistent composer to dim there). */
@Composable
fun ZeroBalanceFundingDialog(walletAddress: String?, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        ZeroBalanceFundingCard(walletAddress = walletAddress, modifier = Modifier.fillMaxWidth())
    }
}
