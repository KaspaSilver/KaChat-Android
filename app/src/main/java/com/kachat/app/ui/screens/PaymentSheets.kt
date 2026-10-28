package com.kachat.app.ui.screens

import androidx.compose.foundation.layout.statusBars

import androidx.compose.foundation.layout.navigationBarsPadding

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.activity.compose.BackHandler
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import com.kachat.app.models.KaspaExplorer
import com.kachat.app.models.MessageEntity
import com.kachat.app.ui.theme.IosRoundedFontFamily
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.KaspaUnit
import com.kachat.app.viewmodels.WalletViewModel
import java.text.DateFormat
import java.util.Date

/** Longest memo a payment carries. It rides encrypted in the payment payload and shows in the
 *  payment bubble, so it stays note-sized (iOS `maxPaymentMemoLength`, 8d208b2). */
const val MAX_PAYMENT_MEMO_LENGTH = 140

/** 0.10000001 KAS - the network dust limit below which a payment may be refused. */
private const val DUST_LIMIT_SOMPI = 10_000_001L

/**
 * "Send KAS" (iOS bc3e1ff3 `ChatDetailView.paymentSheet`): the Send Kaspa steps, from KaPay's
 * shared send pieces. The recipient is this chat, so there is no To step: From comes first
 * (Chatting Address or Choose an Address, the list on the screen, with a "To <name>" chip above
 * the question), then Amount on the app's own keypad (KAS or your currency, Max) with the To and
 * From chips, then the Review: "You're sending", a To / From / Total card, the encrypted memo
 * (it rides in the payment, so a Max amount is worked out again when it changes), the
 * empty-chatting-address warning, the fee card (speed, coin control, and Edit, which opens the fee
 * editor) and the dust warning - and the swipe-up send screen Send Kaspa uses (iOS 7abc06e, from
 * KaChat Wallet): swipe the bar up past halfway to send, so a payment can't go out on a stray
 * touch. The screen then runs the BlockDAG while it goes out and shows Sent ([sent]), or Failed
 * with Try Again. An amount under the dust limit is warned about on the Review, not asked again:
 * the swipe is deliberate.
 * It replaced the composer's payment mode; every way into a payment (the "+" sheet's Pay in
 * Kaspa, "Pay in Kaspa" from a group's sender sheet) opens it.
 *
 * The amount itself lives in the view model ([onAmountKasChange] feeds `setPaymentAmount`), so
 * the fee preview prices exactly what is typed; [amountSompi] is that amount read back.
 */
@Composable
fun SendKasSheet(
    recipientName: String,
    /** The sheet's heading: "Send KAS" in a chat. */
    title: String = KaspaUnit.label(stringResource(R.string.send_kas_title)),
    amountSompi: Long,
    onAmountKasChange: (String) -> Unit,
    priceInCurrency: Double?,
    currencyCode: String,
    note: String,
    onNoteChange: (String) -> Unit,
    /** Where the payment comes from (the From step). The screen owns it, as it owns the send. */
    source: SendSource,
    onSourceChange: (SendSource) -> Unit,
    /** The From address's balance (the amount's Available and its check), null while it loads. */
    sourceBalanceSompi: Long?,
    /** The From address itself, for the Review. */
    sourceAddress: String?,
    walletViewModel: WalletViewModel,
    /** A warning on the Review (iOS 710fdc0, IOS-086): a chat payment From a spending address
     *  while the chatting address is empty, which can't tell the payee about it yet. */
    fromWarning: String? = null,
    /** The most this payment can be, in KAS - fee-aware, from the funds it actually spends. */
    maxKas: () -> Double,
    /** Changes whenever the fee Max leaves room for may have changed (the speed, a custom fee,
     *  the extra the send will pay, From, the memo). While the amount is still what Max filled
     *  in, Max is worked out again, so a change after Max cannot push the payment past the
     *  balance. */
    maxRefreshKey: Any? = null,
    /** The shared fee card (SendFeeControls) on the Review: Network Fee with Edit (the argument,
     *  which opens the fee editor), Normal / Fast / Priority, and Coin Control on the address
     *  this payment comes from (iOS 62c2773). The screen owns its state, as it owns the send. */
    feeControls: @Composable (onEditFee: () -> Unit) -> Unit,
    /** The network's fee for this payment (the fee editor's minimum) and the total with the
     *  speed's or a custom extra; null while unknown. */
    baseFeeSompi: Long?,
    totalFeeSompi: Long?,
    /** The fee editor's Confirm: the whole fee typed, in sompi. */
    onFeeTyped: (Long) -> Unit,
    error: String?,
    isSending: Boolean,
    /** Set once the payment went out: the swipe-up screen shows it until Done. */
    sent: SentTransaction?,
    explorer: KaspaExplorer,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
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
    val dismissKeyboard = rememberDismissSystemKeyboard()

    var step by remember { mutableStateOf(SendKasStep.FROM) }
    /** The From step's Choose an Address is on (its list shows), before or after a pick. */
    var choosingFrom by remember { mutableStateOf(false) }
    /** The fee being typed on the fee editor, in KAS. */
    var feeDraft by remember { mutableStateOf("") }
    fun go(next: SendKasStep) {
        dismissKeyboard()
        step = next
    }
    /** The amount is more than the From address holds. */
    val exceedsBalance = sourceBalanceSompi != null && amountSompi > 0 && amountSompi > sourceBalanceSompi

    val submit = {
        if (amountSompi > 0 && !isSending) {
            // The memo field can still hold the keyboard: drop it for the send.
            dismissKeyboard()
            onSend()
        }
    }

    IosFullSheet(onDismissed = onDismiss, swipeToDismiss = !isSending) { close ->
        // Back is the step's Back; on From it is Cancel (not while the payment is going out).
        BackHandler {
            when (step) {
                SendKasStep.FROM -> if (!isSending) close()
                SendKasStep.AMOUNT -> go(SendKasStep.FROM)
                SendKasStep.REVIEW -> if (!isSending) go(SendKasStep.AMOUNT)
                SendKasStep.FEE -> go(SendKasStep.REVIEW)
            }
        }
        val cancel: () -> Unit = { if (!isSending) close() }
        val toLabel = stringResource(R.string.to)
        val fromLabel = stringResource(R.string.from)
        val sourceName = sendSourceTitle(source)
        SendStepTransition(step, order = { it.ordinal }) { shown ->
            when (shown) {
                // Step 1 - From. The first step: Cancel sits at the bottom.
                SendKasStep.FROM -> SendStepScreen(
                    title = title,
                    onCancel = cancel,
                    canContinue = !(choosingFrom && source is SendSource.Chatting),
                    onContinue = { go(SendKasStep.AMOUNT) },
                ) {
                    SendFromStepContent(
                        source = source,
                        onSourceChange = onSourceChange,
                        choosingFrom = choosingFrom,
                        onChoosingFromChange = { choosingFrom = it },
                        balanceSompi = sourceBalanceSompi,
                        walletViewModel = walletViewModel,
                        header = { SendStepChip(toLabel, recipientName) },
                    )
                }

                // Step 2 - Amount
                SendKasStep.AMOUNT -> SendStepScreen(
                    title = title,
                    onBack = { go(SendKasStep.FROM) },
                    onCancel = cancel,
                    continueTitle = stringResource(R.string.review),
                    canContinue = amountSompi > 0 && !exceedsBalance,
                    onContinue = { go(SendKasStep.REVIEW) },
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .padding(top = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SendStepChip(toLabel, recipientName)
                        SendStepChip(fromLabel, sourceName)
                    }
                    val footnote = if (exceedsBalance) stringResource(R.string.send_exceeds_balance)
                    else sourceBalanceSompi?.let { KaspaUnit.label(stringResource(R.string.send_available, KaspaUnit.plain(it))) }
                    KaspaKeypadAmountEntry(
                        fiatAmountState = fiatAmountState,
                        priceInCurrency = priceInCurrency,
                        currencyCode = currencyCode,
                        onMax = applyMax,
                        footnote = footnote,
                        footnoteIsError = exceedsBalance,
                        modifier = Modifier.weight(1f),
                    )
                }

                // Step 3 - Review: swipe up to send.
                SendKasStep.REVIEW -> SwipeUpToSendScreen(
                    screenTitle = stringResource(R.string.review),
                    onCancel = cancel,
                    onBack = { if (!isSending) go(SendKasStep.AMOUNT) },
                    title = stringResource(R.string.swipe_up_to_send),
                    isBusy = isSending,
                    isEnabled = amountSompi > 0 && !exceedsBalance,
                    amountSompi = amountSompi.takeIf { it > 0 },
                    recipient = recipientName,
                    sent = sent,
                    failureMessage = error,
                    explorer = explorer,
                    onDone = close,
                    action = submit,
                ) {
                    SendKasReview(
                        recipientName = recipientName,
                        sourceName = sourceName,
                        sourceAddress = sourceAddress,
                        amountSompi = amountSompi,
                        totalFeeSompi = totalFeeSompi,
                        priceInCurrency = priceInCurrency,
                        currencyCode = currencyCode,
                        note = note,
                        onNoteChange = onNoteChange,
                        fromWarning = fromWarning,
                        feeControls = {
                            feeControls {
                                totalFeeSompi?.let { total ->
                                    feeDraft = KaspaUnit.plain(total)
                                    go(SendKasStep.FEE)
                                }
                            }
                        },
                        error = error,
                    )
                }

                // The fee editor (the review's Edit): the whole fee on the keypad, Confirm back to
                // the review - never under the network's minimum.
                SendKasStep.FEE -> SendFeeEditorScreen(
                    draft = feeDraft,
                    onDraftChange = { feeDraft = it },
                    minimumSompi = baseFeeSompi,
                    onCancel = { go(SendKasStep.REVIEW) },
                    onConfirm = { typed ->
                        if (baseFeeSompi != null) onFeeTyped(typed)
                        go(SendKasStep.REVIEW)
                    },
                )
            }
        }
    }
}

/** The steps of the Send KAS sheet, in order (iOS bc3e1ff3 `PaymentStep`). */
private enum class SendKasStep { FROM, AMOUNT, REVIEW, FEE }

/** The Send KAS sheet's Review, under its swipe-up bar: what goes where, the memo, the fee. */
@Composable
private fun SendKasReview(
    recipientName: String,
    sourceName: String,
    sourceAddress: String?,
    amountSompi: Long,
    totalFeeSompi: Long?,
    priceInCurrency: Double?,
    currencyCode: String,
    note: String,
    onNoteChange: (String) -> Unit,
    fromWarning: String?,
    feeControls: @Composable () -> Unit,
    error: String?,
) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SendReviewAmount(
            label = stringResource(R.string.send_youre_sending),
            amountSompi = amountSompi,
            priceInCurrency = priceInCurrency,
            currencyCode = currencyCode,
            modifier = Modifier.padding(top = 8.dp),
        )

        SendReviewCard {
            SendReviewRow(stringResource(R.string.to)) {
                // iOS truncates a long address-like name in the middle, so it keeps its end.
                MiddleEllipsisText(recipientName, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider(color = colors.divider)
            SendReviewRow(stringResource(R.string.from)) {
                SendReviewValue(sourceName)
                sourceAddress?.let { SendReviewAddress(it) }
            }
            HorizontalDivider(color = colors.divider)
            SendReviewRow(stringResource(R.string.total)) {
                SendReviewTotal(amountSompi = amountSompi.takeIf { it > 0 }, feeSompi = totalFeeSompi, isWorking = false)
            }
        }

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

        if (fromWarning != null) {
            // iOS: Label(systemImage: "exclamationmark.triangle.fill"), footnote, orange, leading.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = colors.warning,
                    modifier = Modifier.padding(top = 1.dp).size(15.dp),
                )
                Text(fromWarning, color = colors.warning, fontSize = 13.sp)
            }
        }

        // The only things that can still change: the memo, the fee and the coins it spends.
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

    com.kachat.app.ui.theme.IosSheetColors {
        val colors = LocalAppColors.current
        ModalBottomSheet(
            scrimColor = com.kachat.app.ui.theme.IosSheetScrim,
            shape = com.kachat.app.ui.theme.IosSheetShape,
            tonalElevation = com.kachat.app.ui.theme.IosSheetTonalElevation,
            windowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars,
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.background,
        ) {
            // The sheet runs down behind the navigation bar, as iOS's does behind the home indicator;
            // its content stays above it.
            Column(Modifier.navigationBarsPadding()) {
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
                            // iOS: one line, shrinking to 0.6 before it is cut.
                            KachatFitText(
                                "${parts.first} ${KaspaUnit.symbol}",
                                color = colors.textPrimary,
                                fontSize = 34.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = IosRoundedFontFamily,
                                minScale = 0.6f,
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
                            IosToasts.show(copiedText)
                        }
                    } else {
                        Text(stringResource(R.string.payment_not_on_chain_yet), color = colors.textSecondary, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
