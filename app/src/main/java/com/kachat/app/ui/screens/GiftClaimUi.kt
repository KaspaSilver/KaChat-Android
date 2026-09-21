package com.kachat.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.kachat.app.services.GiftClaimState
import com.kachat.app.services.GiftManager
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.ChatViewModel
import com.kachat.app.viewmodels.GiftViewModel
import com.kachat.app.viewmodels.WalletViewModel
import kotlinx.coroutines.delay
import com.kachat.app.util.showAddressCopiedToast

private val GiftErrorRed = Color(0xFFFF3B30)

/**
 * Prominent full-width gift-claim button for the onboarding funding step. Mirrors iOS's
 * WelcomeGuideView.giftClaimSection: stays visible in every state and grays out when not claimable.
 * [walletAddress] is the chatting (identity) address the gift request carries.
 */
@Composable
fun GiftClaimWizardButton(walletAddress: String?, modifier: Modifier = Modifier) {
    val vm: GiftViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.checkEligibility() }
    val claimable = state is GiftClaimState.Eligible && walletAddress != null
    val request = rememberGiftRequest(vm)

    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = { if (claimable) walletAddress?.let { request(it) } },
            enabled = claimable,
            colors = ButtonDefaults.buttonColors(
                containerColor = KaspaTeal,
                disabledContainerColor = LocalAppColors.current.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            val contentColor = if (claimable) Color.Black else LocalAppColors.current.textSecondary
            if (state is GiftClaimState.Claiming) {
                CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else {
                Icon(
                    imageVector = if (state is GiftClaimState.Claimed) Icons.Default.CheckCircle else Icons.Default.CardGiftcard,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                giftTitle(state, "Claim a Gift of 2 Kaspa to Get Started"),
                color = contentColor,
                fontWeight = FontWeight.Bold
            )
        }
        (state as? GiftClaimState.Unavailable)?.let {
            Spacer(Modifier.height(6.dp))
            Text(it.reason, color = GiftErrorRed, fontSize = 12.sp)
        }
        if (state is GiftClaimState.Checking || state is GiftClaimState.Eligible) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Claim Gift opens an email with your chatting address filled in. Add a sentence or two on how you found Kaspa and KaChat, and send it. Gifts are sent by hand, so allow some time.",
                color = LocalAppColors.current.textSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Profile / Settings gift section - a single claim row. Mirrors iOS ProfileView.claimGiftSection
 * and Settings' gift page (whose footer explains the gift).
 */
@Composable
fun GiftClaimProfileSection(
    walletAddress: String?,
    /** Profile passes true: once the gift is settled either way there is nothing left to offer,
     *  and a permanent "Gift already requested" line answers a question nobody is still asking.
     *  Settings passes false, so the state stays visible, with the explanation under it. */
    hideWhenSettled: Boolean = false,
) {
    val vm: GiftViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.checkEligibility() }
    val request = rememberGiftRequest(vm)
    val claimable = state is GiftClaimState.Eligible
    val settled = state is GiftClaimState.Claimed || state is GiftClaimState.AlreadyClaimed
    if (hideWhenSettled && settled) return

    SettingsSection(title = null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (state is GiftClaimState.Eligible) walletAddress?.let { request(it) }
                }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.CardGiftcard,
                contentDescription = null,
                tint = if (claimable) KaspaTeal else LocalAppColors.current.textSecondary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(16.dp))
            Text(
                giftTitle(state, "Claim Gift"),
                color = if (claimable) LocalAppColors.current.textPrimary else LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            if (state is GiftClaimState.Claiming) {
                CircularProgressIndicator(color = KaspaTeal, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            }
        }
        (state as? GiftClaimState.Unavailable)?.let {
            Text(
                it.reason,
                color = GiftErrorRed,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        } ?: run {
            if (!hideWhenSettled) {
                Text(
                    "A one-time gift of 2 Kaspa to get you started. Your chatting address must have 0 Kaspa and never have been used before. Claim Gift opens an email request with your address filled in.",
                    color = LocalAppColors.current.textSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                )
            }
        }
    }
}

/**
 * The gift request, as iOS sends it from a phone without its Mail app (GiftService.claimGift):
 * ask first, because opening the mail app is what counts as this device's one request - Android
 * cannot see whether the email was then actually sent. The email goes to
 * [GiftManager.REQUEST_EMAIL] with the chatting address written in. With no mail app at all,
 * the request text is copied and the row says where to send it.
 *
 * Returns the action to run with the chatting address; the dialog lives in the caller.
 */
@Composable
private fun rememberGiftRequest(vm: GiftViewModel): (String) -> Unit {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var pendingAddress by remember { mutableStateOf<String?>(null) }

    pendingAddress?.let { address ->
        AlertDialog(
            onDismissRequest = { pendingAddress = null },
            title = { Text("Send your gift request?") },
            text = {
                Text("This opens your mail app with the request written for you. You can request the gift once on this device, and opening it counts as that request.")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingAddress = null
                    val body = vm.requestBody(address)
                    val intent = Intent(
                        Intent.ACTION_SENDTO,
                        // Also in the URI: some mail apps read only the mailto fields on SENDTO.
                        Uri.parse(
                            "mailto:${GiftManager.REQUEST_EMAIL}" +
                                "?subject=${Uri.encode(GiftManager.REQUEST_SUBJECT)}" +
                                "&body=${Uri.encode(body)}"
                        )
                    ).apply {
                        putExtra(Intent.EXTRA_EMAIL, arrayOf(GiftManager.REQUEST_EMAIL))
                        putExtra(Intent.EXTRA_SUBJECT, GiftManager.REQUEST_SUBJECT)
                        putExtra(Intent.EXTRA_TEXT, body)
                    }
                    try {
                        context.startActivity(intent)
                        vm.markRequested()
                    } catch (_: ActivityNotFoundException) {
                        clipboard.setText(AnnotatedString(body))
                        vm.markNoMailApp()
                    }
                }) { Text("Open Mail App", color = KaspaTeal, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { pendingAddress = null }) {
                    Text("Cancel", color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }
    return { address -> pendingAddress = address }
}

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
 *  as long as the gate is showing, so claiming the gift (or receiving funds from anywhere)
 *  dismisses the gate on its own — nothing else pushes a balance update into WalletService
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

/** The funding-gate card itself: title, the shared gift-claim flow (same GiftManager state as
 *  onboarding/profile, so a claim can never double-fire), a QR of the chatting address on a
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
            Spacer(Modifier.height(16.dp))
            GiftClaimWizardButton(walletAddress = walletAddress)
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

private fun giftTitle(state: GiftClaimState, claimLabel: String): String = when (state) {
    is GiftClaimState.Checking, is GiftClaimState.Eligible -> claimLabel
    is GiftClaimState.Claiming -> "Claiming gift..."
    is GiftClaimState.Claimed -> "Gift claimed"
    is GiftClaimState.AlreadyClaimed -> "Gift already requested"
    is GiftClaimState.Unavailable -> "Gift unavailable"
}
