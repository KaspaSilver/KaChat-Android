package com.kachat.app.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.kachat.app.R
import com.kachat.app.models.PaymentRequest
import com.kachat.app.ui.theme.IosRoundedFontFamily
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.KaspaUnit
import com.kachat.app.viewmodels.ChatViewModel
import com.kachat.app.viewmodels.WalletViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MARK: - Opening one

/**
 * The payment request waiting to be shown - a tapped request card, a pasted link, a link opened
 * from outside the app (iOS `PaymentRequestRouter`). [PaymentRequestSheetHost], in MainShell,
 * presents it: Send Kaspa filled in for someone else's, the request itself for your own. Every
 * opening is its own sheet, even of the same request.
 */
object PaymentRequestRouter {
    data class Pending(val request: PaymentRequest, val id: Long)

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()
    private var nextId = 0L

    @Synchronized
    fun open(request: PaymentRequest) {
        nextId += 1
        _pending.value = Pending(request, nextId)
    }

    /** The sheet for [id] has gone; a newer opening stays. */
    @Synchronized
    fun dismiss(id: Long) {
        if (_pending.value?.id == id) _pending.value = null
    }
}

/**
 * Pasting a payment request anywhere in KaChat opens Send Kaspa with it (iOS
 * `PaymentRequestPaste`): the chat composers (1:1, group, public), the KaPosts composer. Only text
 * that is the link and nothing else, and only someone else's request: your own pastes as text, so
 * you can send it to the person who should pay.
 */
object PaymentRequestPaste {
    /** Opens [text] when it is someone else's payment request and nothing more; true if it did. */
    fun openIfRequest(text: String?): Boolean {
        val pasted = text ?: return false
        if (!pasted.contains("kachat", ignoreCase = true)) return false
        val match = KaChatLink.findFirst(pasted) ?: return false
        if (pasted.trim() != match.raw) return false
        val request = (match.ref as? KaChatLinkRef.PaymentRequest)?.request ?: return false
        if (request.isOwn) return false
        PaymentRequestRouter.open(request)
        return true
    }

    /**
     * For a text field's change from [old] to [new]: when what was put in at once (a paste) is
     * someone else's payment request, it opens Send Kaspa and the field keeps [old] - returns true,
     * and the caller drops the change.
     */
    fun interceptInsert(old: String, new: String): Boolean {
        // A link is far longer than anything typed in one change.
        if (new.length < 20) return false
        val max = minOf(old.length, new.length)
        var prefix = 0
        while (prefix < max && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < max - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
        val inserted = new.substring(prefix, new.length - suffix)
        if (inserted.length < 20) return false
        return openIfRequest(inserted)
    }
}

/**
 * Presents the payment request waiting in [PaymentRequestRouter] (iOS
 * `PaymentRequestSheetPresenter`): your own shows it ([OwnPaymentRequestSheet]); someone else's
 * opens Send Kaspa from the chatting address with the address locked and the amount and memo
 * filled in - every send check as always, and nothing goes out until slide to send.
 */
@Composable
fun PaymentRequestSheetHost(walletViewModel: WalletViewModel, chatViewModel: ChatViewModel) {
    val pending by PaymentRequestRouter.pending.collectAsState()
    val current = pending ?: return
    key(current.id) {
        var isOwn by remember { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(Unit) {
            isOwn = withContext(Dispatchers.Default) { current.request.isOwn }
        }
        when (isOwn) {
            null -> Unit
            true -> OwnPaymentRequestSheet(current.request, onDismissed = { PaymentRequestRouter.dismiss(current.id) })
            false -> {
                val address by walletViewModel.address.collectAsState()
                val balanceSompi by walletViewModel.balanceSompi.collectAsState()
                val from = address
                if (from == null) {
                    LaunchedEffect(Unit) { PaymentRequestRouter.dismiss(current.id) }
                } else {
                    IosFullSheet(onDismissed = { PaymentRequestRouter.dismiss(current.id) }, swipeToDismiss = false) { close ->
                        SpendingAddressSendFlow(
                            fromAddress = from,
                            balanceSompi = balanceSompi,
                            title = stringResource(R.string.chats_send_kaspa),
                            viewModel = walletViewModel,
                            onDone = close,
                            presentedAsSheet = true,
                            paymentRequest = current.request,
                            chatViewModel = chatViewModel,
                        )
                    }
                }
            }
        }
    }
}

/** Shares [link] through the system share sheet (iOS `ShareLink`). */
private fun sharePaymentRequestLink(context: android.content.Context, link: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, link)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
}

/** iOS's `Label("Share", systemImage: "square.and.arrow.up")` button. */
@Composable
private fun PaymentRequestShareButton(enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Icon(Icons.Outlined.IosShare, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.share), color = KaspaTeal, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

// MARK: - Request Payment (Chats > + > Request Payment, and a 1:1 chat's + sheet)

private enum class PaymentRequestDestination {
    /** A brand-new spending address, never revealed, used or handed out. */
    FRESH,
    /** One of your addresses, picked from the list: the chatting address or a spending one. */
    CHOSEN,
}

/**
 * Request Payment's tile icon, for the New sheet and a 1:1 chat's + sheet: iOS's SF Symbol
 * "doc.text" (semibold) drawn to its proportions - a rounded page with its top-right corner folded
 * down and two short lines of text in the middle. Material's Description reads as a different,
 * squarer page with longer lines, and is already the File tile's icon next to this one.
 */
val RequestPaymentIcon: androidx.compose.ui.graphics.vector.ImageVector by lazy {
    val ink = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black)
    androidx.compose.ui.graphics.vector.ImageVector.Builder(
        name = "RequestPaymentDocText",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // the page, the fold's diagonal closing it
        path(
            stroke = ink,
            strokeLineWidth = 2f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
        ) {
            moveTo(12.86f, 3.36f)
            lineTo(7.04f, 3.36f)
            arcTo(1.7f, 1.7f, 0f, false, false, 5.34f, 5.06f)
            lineTo(5.34f, 18.94f)
            arcTo(1.7f, 1.7f, 0f, false, false, 7.04f, 20.64f)
            lineTo(16.96f, 20.64f)
            arcTo(1.7f, 1.7f, 0f, false, false, 18.66f, 18.94f)
            lineTo(18.66f, 9.17f)
            close()
        }
        // the folded corner
        path(
            stroke = ink,
            strokeLineWidth = 2f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
        ) {
            moveTo(11.96f, 3.36f)
            lineTo(11.96f, 9.16f)
            arcTo(1f, 1f, 0f, false, false, 12.96f, 10.16f)
            lineTo(18.66f, 10.16f)
        }
        // the two lines of text
        path(
            stroke = ink,
            strokeLineWidth = 1.1f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
        ) {
            moveTo(8.8f, 13.8f)
            lineTo(15.02f, 13.8f)
            moveTo(8.8f, 16.68f)
            lineTo(15.02f, 16.68f)
        }
    }.build()
}

/**
 * Makes a payment request link (iOS `CreatePaymentRequestView`, c6851d6 / 7c71a98 / 6594936,
 * 86dfd2d / 6dee531 / 338d9f5 / 3f5f79c): paid to a Fresh Address or one you choose, an exact
 * amount (KAS or your currency) and what it's for - an optional memo up to
 * [PaymentRequest.MEMO_LIMIT] characters. Signed by your chatting address when it goes out, so
 * whoever pays sees it is from you.
 *
 * Fresh Address (the default) is a brand-new spending address, never revealed, used or handed out:
 * the screen shows the next unrevealed slot (derived, not reserved) and reserves it only when the
 * request is copied, shared or sent - opening the screen and cancelling uses none up. Once per
 * screen: Copy, Share and Send all reuse it, so no two requests share an address. Choose an
 * Address picks one of yours: a request to the chatting address is a chat payment with the memo;
 * any other is a plain send.
 *
 * From Chats > + it gives the link: Copy Payment Request or Share. Made from a 1:1 chat's + sheet
 * ([recipientContactId]) it is for that person: Send Payment Request sends it into the chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePaymentRequestSheet(
    walletViewModel: WalletViewModel,
    onDismissed: () -> Unit,
    recipientContactId: String? = null,
    chatViewModel: ChatViewModel? = null,
    portfolioViewModel: com.kachat.app.viewmodels.PortfolioViewModel = hiltViewModel(),
) {
    IosFullSheet(onDismissed = onDismissed, swipeToDismiss = true) { close ->
        BackHandler(onBack = close)
        val colors = LocalAppColors.current
        val context = LocalContext.current
        val clipboard = LocalClipboardManager.current
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val haptic = com.kachat.app.util.rememberHaptics()
        val fiatPriceInCurrency by portfolioViewModel.currentPriceUsd.collectAsState()
        val fiatCurrencyCode by portfolioViewModel.currency.collectAsState()
        val chattingAddress by walletViewModel.address.collectAsState()

        var destination by remember { mutableStateOf(PaymentRequestDestination.FRESH) }
        /** The brand-new address reserved for this request - only once it's copied, shared or sent. */
        var freshAddress by remember { mutableStateOf<String?>(null) }
        /** The next brand-new address, shown before it's reserved (the slot after the last revealed one). */
        var nextFreshAddress by remember { mutableStateOf<String?>(null) }
        var chosenAddress by remember { mutableStateOf<String?>(null) }
        var showAddressPicker by remember { mutableStateOf(false) }
        var amountInput by remember { mutableStateOf("") }
        val fiatAmountState = com.kachat.app.util.rememberKaspaFiatAmountState(onKasTextChange = { amountInput = it })
        var memo by remember { mutableStateOf("") }
        var copied by remember { mutableStateOf(false) }
        var isWorking by remember { mutableStateOf(false) }
        var sendError by remember { mutableStateOf<String?>(null) }

        // derived, not reserved: opening the screen and cancelling uses no address up
        LaunchedEffect(Unit) { nextFreshAddress = walletViewModel.nextFreshSpendingAddress() }

        val amountSompi = KaspaUnit.sompiFromUserText(amountInput)?.takeIf { it > 0 }
        /** Ready to hand out: an amount, and an address (or a fresh one to be made). */
        val canMake = amountSompi != null && (destination == PaymentRequestDestination.FRESH || chosenAddress != null)
        LaunchedEffect(amountInput, memo, destination, chosenAddress) { copied = false }

        /**
         * The request to hand out, unsigned (signed by the chatting address as it goes out - the
         * key is only derived then). A fresh address is reserved here, the first time it's needed.
         */
        suspend fun makeRequest(): PaymentRequest? {
            val sompi = amountSompi ?: return null
            val address = when (destination) {
                PaymentRequestDestination.FRESH -> freshAddress ?: run {
                    val reserved = walletViewModel.reserveFreshSpendingAddress()
                    if (reserved == null) {
                        sendError = context.getString(R.string.pr_couldnt_make_address)
                        return null
                    }
                    freshAddress = reserved
                    reserved
                }
                PaymentRequestDestination.CHOSEN -> chosenAddress ?: return null
            }
            return PaymentRequest.create(address, sompi, memo, address == chattingAddress)
        }

        /** The request's link, signed by the chatting address (or unsigned when its key can't be had). */
        suspend fun signedLink(unsigned: PaymentRequest): String = withContext(Dispatchers.Default) {
            (com.kachat.app.services.PaymentRequestWallet.shared?.sign(unsigned) ?: unsigned).shareUrlString
        }

        Scaffold(
            containerColor = colors.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                // iOS 26's sheet bar: the title in headline, Cancel on a glass capsule.
                SheetNavBar(
                    title = stringResource(R.string.pr_request_payment),
                    leading = { SheetBarButton(stringResource(R.string.cancel), onClick = close) },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SelectablePill(
                            title = stringResource(R.string.pr_fresh_address),
                            selected = destination == PaymentRequestDestination.FRESH,
                            modifier = Modifier.weight(1f),
                        ) {
                            destination = PaymentRequestDestination.FRESH
                        }
                        SelectablePill(
                            title = stringResource(R.string.pr_choose_an_address),
                            selected = destination == PaymentRequestDestination.CHOSEN,
                            modifier = Modifier.weight(1f),
                        ) {
                            destination = PaymentRequestDestination.CHOSEN
                            showAddressPicker = true
                        }
                    }
                    when (destination) {
                        PaymentRequestDestination.FRESH -> {
                            val shown = freshAddress ?: nextFreshAddress
                            if (shown != null) {
                                AddressResolutionCard(address = shown)
                            } else {
                                Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                                    com.kachat.app.ui.theme.IosActivityIndicator(color = colors.textSecondary)
                                }
                            }
                        }
                        PaymentRequestDestination.CHOSEN -> {
                            val chosen = chosenAddress
                            if (chosen != null) {
                                AddressResolutionCard(address = chosen)
                                Text(
                                    stringResource(if (chosen == chattingAddress) R.string.pr_chatting_footer else R.string.pr_memo_shows_note),
                                    color = colors.textSecondary,
                                    fontSize = 13.sp,
                                )
                            } else {
                                Text(stringResource(R.string.pr_pick_address), color = colors.textSecondary, fontSize = 13.sp)
                            }
                        }
                    }
                }

                KaspaAmountEntry(
                    fiatAmountState = fiatAmountState,
                    priceInCurrency = fiatPriceInCurrency,
                    currencyCode = fiatCurrencyCode,
                    onMax = {},
                    showsMax = false,
                    focusOnAppear = true,
                    modifier = Modifier.padding(top = 8.dp),
                )

                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val placeholder = stringResource(R.string.pr_memo_placeholder)
                    BasicTextField(
                        value = memo,
                        onValueChange = { value ->
                            memo = if (PaymentRequest.characterCount(value) > PaymentRequest.MEMO_LIMIT) {
                                PaymentRequest.takeCharacters(value, PaymentRequest.MEMO_LIMIT)
                            } else value
                        },
                        maxLines = 4,
                        textStyle = TextStyle(color = colors.textPrimary, fontSize = 17.sp),
                        cursorBrush = SolidColor(KaspaTeal),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier
                            .fillMaxWidth()
                            .sendKaspaGlass(14.dp)
                            .padding(14.dp)
                            .semantics { contentDescription = placeholder },
                        decorationBox = { inner ->
                            Box {
                                if (memo.isEmpty()) Text(placeholder, color = colors.textTertiary, fontSize = 17.sp)
                                inner()
                            }
                        },
                    )
                    Text(
                        "${PaymentRequest.characterCount(memo)}/${PaymentRequest.MEMO_LIMIT}",
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                    )
                }

                sendError?.let {
                    Text(it, color = colors.danger, fontSize = 13.sp, textAlign = TextAlign.Center)
                }

                if (recipientContactId != null && chatViewModel != null) {
                    SendActionButton(
                        title = stringResource(R.string.pr_send_request),
                        isBusy = isWorking,
                        isEnabled = canMake && !isWorking,
                        requiresSlide = false,
                        onSend = {
                            isWorking = true
                            sendError = null
                            scope.launch {
                                val unsigned = makeRequest()
                                if (unsigned == null) {
                                    isWorking = false
                                    return@launch
                                }
                                // Sent into the chat as a message: it shows there as a Payment
                                // Request card, which they tap to pay.
                                chatViewModel.sendPaymentRequest(recipientContactId, unsigned) { ok, error ->
                                    if (ok) {
                                        haptic(com.kachat.app.util.IosHaptic.SUCCESS)
                                        close()
                                    } else {
                                        isWorking = false
                                        sendError = error
                                    }
                                }
                            }
                        },
                    )
                } else {
                    SendActionButton(
                        title = stringResource(if (copied) R.string.ab_copied else R.string.pr_copy),
                        isBusy = isWorking,
                        isEnabled = canMake && !isWorking,
                        requiresSlide = false,
                        onSend = {
                            isWorking = true
                            sendError = null
                            scope.launch {
                                try {
                                    val unsigned = makeRequest() ?: return@launch
                                    clipboard.setText(AnnotatedString(signedLink(unsigned)))
                                    haptic(com.kachat.app.util.IosHaptic.SUCCESS)
                                    copied = true
                                } finally {
                                    isWorking = false
                                }
                            }
                        },
                    )
                    // Share waits for the fresh address before the share sheet opens.
                    PaymentRequestShareButton(enabled = canMake && !isWorking) {
                        isWorking = true
                        sendError = null
                        scope.launch {
                            try {
                                val unsigned = makeRequest() ?: return@launch
                                sharePaymentRequestLink(context, signedLink(unsigned))
                            } finally {
                                isWorking = false
                            }
                        }
                    }
                }

                Text(
                    stringResource(R.string.pr_anyone_note),
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (showAddressPicker) {
            OwnAddressPicker(
                title = stringResource(R.string.pr_choose_an_address),
                selected = chosenAddress,
                walletViewModel = walletViewModel,
                onPick = { picked -> chosenAddress = picked.address(chattingAddress) },
                onDismiss = { showAddressPicker = false },
            )
        }
    }
}

// MARK: - Paying one

/**
 * What a payment request asks for, above the amount on Send Kaspa (iOS `PaymentRequestSummary`):
 * the amount, the memo, how it is paid, and who made it - "Requested by" with that address's name
 * and picture once the signature checked out, else "Not signed".
 */
@Composable
fun PaymentRequestSummary(request: PaymentRequest, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .sendKaspaGlass(14.dp)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(R.string.pr_requested).uppercase(),
            color = KaspaTeal,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "${request.amountText} ${KaspaUnit.symbol}",
            color = colors.textPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (request.memo.isNotEmpty()) {
            Text(request.memo, color = colors.textPrimary, fontSize = 15.sp)
        }
        Text(
            stringResource(if (request.toChattingAddress) R.string.pr_paid_as_chat_payment else R.string.pr_paid_to_their_spending),
            color = colors.textSecondary,
            fontSize = 12.sp,
        )

        HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 4.dp))

        val creator = request.creator
        if (creator != null) {
            // Who made it - checked against the signature in the link when it was opened.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.Verified, contentDescription = null, tint = colors.success, modifier = Modifier.size(14.dp))
                Text(stringResource(R.string.pr_requested_by), color = colors.success, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            AddressResolutionCard(address = creator)
            if (!creator.equals(request.address, ignoreCase = true)) {
                Text(stringResource(R.string.pr_signed_by_their_chatting), color = colors.textSecondary, fontSize = 12.sp)
            }
        } else {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.warning, modifier = Modifier.size(14.dp).padding(top = 1.dp))
                Text(stringResource(R.string.pr_not_signed), color = colors.warning, fontSize = 12.sp)
            }
        }
    }
}

/**
 * One of your own payment requests, tapped in a chat (iOS `OwnPaymentRequestView`, 1fe8388): what
 * it asks for and where it pays into, with its link to copy or share again. Nothing to pay - it's
 * yours.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OwnPaymentRequestSheet(request: PaymentRequest, onDismissed: () -> Unit) {
    IosFullSheet(onDismissed = onDismissed, swipeToDismiss = true) { close ->
        BackHandler(onBack = close)
        val colors = LocalAppColors.current
        val context = LocalContext.current
        val clipboard = LocalClipboardManager.current
        val haptic = com.kachat.app.util.rememberHaptics()
        var copied by remember { mutableStateOf(false) }
        Scaffold(
            containerColor = colors.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                // iOS 26's sheet bar: the title in headline, Done on a glass capsule.
                SheetNavBar(
                    title = stringResource(R.string.pr_your_payment_request),
                    trailing = { SheetBarButton(stringResource(R.string.done), onClick = close) },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "${request.amountText} ${KaspaUnit.symbol}",
                        color = colors.textPrimary,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = IosRoundedFontFamily,
                        textAlign = TextAlign.Center,
                    )
                    if (request.memo.isNotEmpty()) {
                        Text(request.memo, color = colors.textPrimary, fontSize = 17.sp, textAlign = TextAlign.Center)
                    }
                }

                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(
                            if (request.toChattingAddress) R.string.pr_paid_to_your_chatting else R.string.pr_paid_to_your_spending
                        ).uppercase(),
                        color = KaspaTeal,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    AddressResolutionCard(address = request.address)
                    Text(
                        stringResource(if (request.toChattingAddress) R.string.pr_chatting_footer else R.string.pr_spending_footer_new),
                        color = colors.textSecondary,
                        fontSize = 13.sp,
                    )
                }

                SendActionButton(
                    title = stringResource(if (copied) R.string.ab_copied else R.string.pr_copy),
                    isBusy = false,
                    isEnabled = true,
                    requiresSlide = false,
                    onSend = {
                        clipboard.setText(AnnotatedString(request.shareUrlString))
                        haptic(com.kachat.app.util.IosHaptic.SUCCESS)
                        copied = true
                    },
                )
                PaymentRequestShareButton { sharePaymentRequestLink(context, request.shareUrlString) }
            }
        }
    }
}
