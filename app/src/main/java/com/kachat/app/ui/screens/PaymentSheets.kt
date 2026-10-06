package com.kachat.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import com.kachat.app.models.KaspaExplorer
import com.kachat.app.models.MessageEntity
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.KaspaUnit
import java.text.DateFormat
import java.util.Date

/** Longest memo a payment carries. It rides encrypted in the payment payload and shows in the
 *  payment bubble, so it stays note-sized (iOS `maxPaymentMemoLength`, 8d208b2). */
const val MAX_PAYMENT_MEMO_LENGTH = 140

/** 0.10000001 KAS - the network dust limit below which a payment may be refused. */
private const val DUST_LIMIT_SOMPI = 10_000_001L

/**
 * "Send KAS": who it goes to, the exact amount (KAS or fiat), an encrypted memo, the available
 * balance, the fee card (speed, custom fee, coin control - iOS 62c2773), and a slide-to-send
 * button (iOS afaad34) - sliding rather than tapping, so a payment can't go out on a stray touch.
 * It replaced the composer's payment mode; every way into a payment (the "+" sheet's Pay in
 * Kaspa, "Pay in Kaspa" from a group or public chat's sender sheet) opens it. It scrolls and
 * opens at full height.
 * Mirrors iOS's `ChatDetailView.paymentSheet` (8d208b2).
 *
 * The amount itself lives in the view model ([onAmountKasChange] feeds `setPaymentAmount`), so
 * the fee preview prices exactly what is typed; [amountSompi] is that amount read back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendKasSheet(
    recipientName: String,
    amountSompi: Long,
    onAmountKasChange: (String) -> Unit,
    priceInCurrency: Double?,
    currencyCode: String,
    note: String,
    onNoteChange: (String) -> Unit,
    availableText: String,
    /** Chats Payment Privacy on: the Available pill is the paying spending address's balance,
     *  underlined, and opens Send From to pay from another spending address (iOS dae8a01). Off,
     *  payments come from the chatting address, so there's nothing to pick. */
    availableTappable: Boolean,
    onAvailableClick: () -> Unit,
    /** Which spending address pays ("Address #N"), shown after the balance with a chevron when
     *  [availableTappable]. */
    availableSourceLabel: String?,
    /** The payment goes to a fresh pool address the contact shared (privacy on only). */
    paysToFreshAddress: Boolean,
    /** The most this payment can be, in KAS - fee-aware, from the funds it actually spends. */
    maxKas: () -> Double,
    /** Changes whenever the fee Max leaves room for may have changed (the speed, a custom fee,
     *  the extra the send will pay). While the amount is still what Max filled in, Max is worked
     *  out again, so a speed picked after Max cannot push the payment past the balance. */
    maxRefreshKey: Any? = null,
    /** The shared fee card (SendFeeControls): Network Fee (tap for a custom fee), Normal / Fast /
     *  Priority, and Coin Control on the address this payment comes from - in place of the old
     *  fee pill (iOS 62c2773). The screen owns its state, as it owns the send. */
    feeControls: @Composable () -> Unit,
    error: String?,
    isSending: Boolean,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalAppColors.current
    val currentlySending by rememberUpdatedState(isSending)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        // Not while the payment is going out (iOS interactiveDismissDisabled(isSending)).
        confirmValueChange = { it != SheetValue.Hidden || !currentlySending },
    )
    // What Max last wrote (as KAS text); dropped as soon as the amount is edited away from it.
    var maxFilledKasText by remember { mutableStateOf<String?>(null) }
    val fiatAmountState = com.kachat.app.util.rememberKaspaFiatAmountState(onKasTextChange = { text ->
        if (text != maxFilledKasText) maxFilledKasText = null
        onAmountKasChange(text)
    })
    val applyMax: () -> Unit = {
        val kas = maxKas()
        maxFilledKasText = com.kachat.app.util.formatKasAmount(kas)
        fiatAmountState.setMaxKas(kas, priceInCurrency)
    }
    LaunchedEffect(maxRefreshKey) {
        if (maxFilledKasText != null) applyMax()
    }
    var showDustConfirm by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    val submit = {
        if (amountSompi > 0 && !isSending) {
            // The amount field (KaspaAmountEntry) keeps its own focus: drop the keyboard for the
            // dust question or the send (iOS 4d0324f).
            focusManager.clearFocus()
            if (amountSompi < DUST_LIMIT_SOMPI) showDustConfirm = true else onSend()
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!isSending) onDismiss() },
        sheetState = sheetState,
        containerColor = colors.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    KaspaUnit.label(stringResource(R.string.send_kas_title)),
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                )
                Text(
                    stringResource(R.string.payment_to_name, recipientName),
                    color = colors.textSecondary,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // The big centred amount, the KAS/fiat switch and Max - the shared Send Kaspa piece
            // (iOS 4d0324f).
            KaspaAmountEntry(
                fiatAmountState = fiatAmountState,
                priceInCurrency = priceInCurrency,
                currencyCode = currencyCode,
                focusOnAppear = true,
                onMax = applyMax,
            )

            // The memo, encrypted to the recipient with the payment and shown in its bubble.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(colors.surface)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
                BasicTextField(
                    value = note,
                    onValueChange = { onNoteChange(it.take(MAX_PAYMENT_MEMO_LENGTH)) },
                    maxLines = 3,
                    textStyle = TextStyle(color = colors.textPrimary, fontSize = 16.sp),
                    cursorBrush = SolidColor(KaspaTeal),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box {
                            if (note.isEmpty()) {
                                Text(stringResource(R.string.payment_memo_placeholder), color = colors.textTertiary, fontSize = 16.sp)
                            }
                            inner()
                        }
                    },
                )
            }

            // The available pill, then the fee card (iOS 62c2773).
            val sourcePickerHint = stringResource(R.string.send_from_hint)
            Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .then(
                            if (availableTappable) {
                                Modifier.clickable(onClickLabel = sourcePickerHint, role = androidx.compose.ui.semantics.Role.Button) { onAvailableClick() }
                            } else Modifier
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        availableText,
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (availableTappable) TextDecoration.Underline else null,
                        // The balance gives way first on a narrow screen, so the address shows.
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (availableTappable && availableSourceLabel != null) {
                        // Which spending address pays: the primary unless another was picked.
                        Text("·", color = colors.textSecondary, fontSize = 11.sp)
                        Text(availableSourceLabel, color = colors.textSecondary, fontSize = 11.sp, maxLines = 1)
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    if (availableTappable && paysToFreshAddress) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Payment goes to a fresh address this contact shared, so it cannot be linked to their chat address on-chain",
                            tint = KaspaTeal,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }

            feeControls()

            if (error != null) {
                Text(error, color = colors.danger, fontSize = 13.sp, textAlign = TextAlign.Center)
            } else if (amountSompi in 1 until DUST_LIMIT_SOMPI) {
                Text(
                    KaspaUnit.label(stringResource(R.string.payment_dust_warning)),
                    color = colors.warning,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }

            // The button gives its own haptics as the knob moves and reaches the end.
            SendActionButton(
                title = stringResource(R.string.slide_to_send),
                isBusy = isSending,
                isEnabled = amountSompi > 0 && !isSending,
                onSend = { submit() },
            )
        }
    }

    if (showDustConfirm) {
        com.kachat.app.ui.theme.IosAlertDialog(
            onDismissRequest = { showDustConfirm = false },
            title = { Text(stringResource(R.string.payment_small_amount), color = colors.textPrimary, fontWeight = FontWeight.Bold) },
            text = { Text(KaspaUnit.label(stringResource(R.string.payment_dust_warning)), color = colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    showDustConfirm = false
                    onSend()
                }) { Text(stringResource(R.string.payment_send_anyway), color = KaspaTeal, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showDustConfirm = false }) {
                    Text(stringResource(R.string.cancel), color = KaspaTeal)
                }
            },
        )
    }
}

/**
 * A tapped payment bubble: what moved (amount, direction, memo, when), and the transaction - View
 * in Explorer (the explorer chosen in Settings) and Copy Transaction ID. A payment still waiting to
 * go on chain has no transaction yet, so it says so instead. It replaced the sent-confirmation
 * sheet after a chat payment: the bubble is the confirmation, and its transaction is a tap away.
 * Mirrors iOS's `ChatDetailView.paymentDetailSheet` (80a6aae).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentDetailSheet(
    message: MessageEntity,
    explorer: KaspaExplorer,
    onDismiss: () -> Unit,
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val isSent = message.direction == "sent"
    val parts = remember(message.plaintextBody) { parsePaymentCardParts(message.plaintextBody) }
    val isOnChain = !message.id.startsWith("pending_") &&
        message.deliveryStatus != "pending" && message.deliveryStatus != "failed"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    painterResource(R.drawable.ic_kaspa_logo),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(40.dp),
                )
                Text(
                    stringResource(if (isSent) R.string.sent else R.string.payment_received),
                    color = colors.textSecondary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
                if (parts != null) {
                    Text(
                        "${parts.first} ${KaspaUnit.symbol}",
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 34.sp,
                        maxLines = 1,
                    )
                    parts.second?.let { note ->
                        Text(note, color = colors.textPrimary, fontSize = 15.sp, textAlign = TextAlign.Center)
                    }
                } else {
                    Text(
                        message.plaintextBody.orEmpty(),
                        color = colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                        textAlign = TextAlign.Center,
                    )
                }
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(message.blockTimestamp)),
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                )
            }

            if (isOnChain) {
                ActionSheetRow(
                    icon = Icons.Default.Public,
                    title = stringResource(R.string.view_in_explorer),
                    subtitle = stringResource(R.string.payment_explorer_subtitle),
                ) {
                    onDismiss()
                    uriHandler.openUri(explorer.txUrl(message.id))
                }
                val copiedText = stringResource(R.string.transaction_id_copied)
                ActionSheetRow(
                    icon = Icons.Default.ContentCopy,
                    title = stringResource(R.string.copy_transaction_id),
                    subtitle = stringResource(R.string.payment_copy_txid_subtitle),
                ) {
                    clipboard.setText(AnnotatedString(message.id))
                    onDismiss()
                    Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                }
            } else {
                Text(stringResource(R.string.payment_not_on_chain_yet), color = colors.textSecondary, fontSize = 13.sp)
            }
        }
    }
}
