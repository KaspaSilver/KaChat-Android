package com.kachat.app.ui.screens

import com.kachat.app.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.ConnectionViewModel
import com.kachat.app.viewmodels.WalletViewModel
import com.kachat.app.util.showAddressCopiedToast

private enum class WelcomeGuideStep { WELCOME, LANGUAGE, CURRENCY, FEES, FUNDING, NODE_CONNECTION, ADDRESS_EXPLAINER }

private enum class NodeChoice { DEFAULT_NODE, OWN_NODE, AUTO_DISCOVER }

/**
 * First-run guided walkthrough shown automatically right after an account is added — whether
 * created or imported (see `WalletViewModel.pendingWelcomeGuide`) — and replayable any time from
 * the Profile screen. One screen driven by a step enum ([WelcomeGuideStep]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WelcomeGuideScreen(
    walletViewModel: WalletViewModel,
    connectionViewModel: ConnectionViewModel = hiltViewModel(),
    /**
     * Explicit presentation context, supplied by the presenter and NEVER inferred from persisted
     * markers (matches iOS): true only for auto-presented onboarding runs (create AND import,
     * including re-presentations of an interrupted run) — those are fully unskippable end to
     * end, from Welcome through Finish. Help replays leave this false and keep Skip and
     * back-dismiss.
     */
    isOnboardingRun: Boolean = false,
    onFinished: () -> Unit
) {
    var step by remember { mutableStateOf(WelcomeGuideStep.WELCOME) }
    val chattingAddress by walletViewModel.address.collectAsState()
    val justImportedWallet by walletViewModel.justImportedWallet.collectAsState()
    val trustedNodeAddress by connectionViewModel.trustedNodeAddress.collectAsState()
    val fundingBalanceSompi by walletViewModel.balanceSompi.collectAsState()

    // Skip gating: an onboarding run is unskippable END TO END (top-bar Skip hidden and system back
    // swallowed until Finish); a replay skips any time.
    val canSkip = !isOnboardingRun
    androidx.activity.compose.BackHandler(enabled = !canSkip) {
        // Swallowed - onboarding runs must reach Finish.
    }

    var nodeChoice by remember(trustedNodeAddress) {
        mutableStateOf(
            when {
                trustedNodeAddress.isBlank() -> NodeChoice.AUTO_DISCOVER
                trustedNodeAddress == AppSettingsRepository.DEFAULT_TRUSTED_NODE_ADDRESS -> NodeChoice.DEFAULT_NODE
                else -> NodeChoice.OWN_NODE
            }
        )
    }
    var ownNodeInput by remember(trustedNodeAddress) {
        mutableStateOf(if (nodeChoice == NodeChoice.OWN_NODE) trustedNodeAddress else "")
    }
    var nodeValidationError by remember { mutableStateOf<String?>(null) }
    val enterAsHostPortError = stringResource(R.string.enter_as_host_port_or_grpcs_host)

    // One step back. Null at Welcome so the shared button row renders Previous disabled there.
    val goBack: (() -> Unit)? = if (step == WelcomeGuideStep.WELCOME) null else {
        { step = WelcomeGuideStep.entries[step.ordinal - 1] }
    }
    // The funding step's Show QR Code, pushed over the whole guide as iOS's NavigationLink pushes it.
    var showFundingQr by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            // Nothing to put in the bar on an onboarding run: just iOS's 44 under the status bar.
            if (!canSkip) Spacer(Modifier.statusBarsPadding().height(44.dp))
            else TopAppBar(
                title = {},
                navigationIcon = {
                    // Skip exists only on replays - an onboarding run has no way out until
                    // Finish (back-dismiss swallowed too).
                    if (canSkip) {
                        SheetBarButton(stringResource(R.string.skip), onClick = onFinished, modifier = Modifier.padding(start = 12.dp))
                    }
                    // Previous-step navigation lives in the fixed bottom button row next
                    // to Next (see WelcomeGuideButtonRow) - only skipping forward stays
                    // forbidden.
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LocalAppColors.current.background)
            )
        },
        containerColor = LocalAppColors.current.background
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (step) {
                WelcomeGuideStep.WELCOME -> WelcomeGuideStepScaffold(
                    icon = Icons.Default.WavingHand,
                    title = stringResource(R.string.welcome_to_kachat),
                    body = stringResource(R.string.lets_walk_through_the_basics_so),
                    buttonLabel = stringResource(R.string.next),
                    onPrevious = goBack,
                    onNext = { step = WelcomeGuideStep.LANGUAGE }
                )
                WelcomeGuideStep.LANGUAGE -> WelcomeGuideLanguageStep(
                    walletViewModel = walletViewModel,
                    onPrevious = goBack,
                    onNext = { step = WelcomeGuideStep.CURRENCY }
                )
                WelcomeGuideStep.CURRENCY -> WelcomeGuideCurrencyStep(
                    walletViewModel = walletViewModel,
                    onPrevious = goBack,
                    onNext = { step = WelcomeGuideStep.FEES }
                )
                WelcomeGuideStep.FEES -> WelcomeGuideStepScaffold(
                    icon = Icons.Default.Language,
                    title = stringResource(R.string.how_kachat_uses_kaspa),
                    body = stringResource(R.string.kachat_lets_you_send_and_receive),
                    buttonLabel = stringResource(R.string.next),
                    onPrevious = goBack,
                    onNext = { step = WelcomeGuideStep.FUNDING }
                )
                WelcomeGuideStep.FUNDING -> WelcomeGuideFundingStep(
                    chattingAddress = chattingAddress,
                    // "Change Chatting Address" is offered ONLY on import onboarding runs: a
                    // freshly created wallet has nothing but index 0, and a Help replay must never
                    // let the user re-pick an identity behind their existing conversations.
                    walletViewModel = walletViewModel,
                    canChangeChattingAddress = isOnboardingRun && justImportedWallet,
                    onShowQr = { showFundingQr = true },
                    onPrevious = goBack,
                    onNext = { step = WelcomeGuideStep.NODE_CONNECTION }
                )
                WelcomeGuideStep.NODE_CONNECTION -> WelcomeGuideNodeConnectionStep(
                    nodeChoice = nodeChoice,
                    onNodeChoiceChange = { nodeChoice = it; nodeValidationError = null },
                    ownNodeInput = ownNodeInput,
                    onOwnNodeInputChange = { ownNodeInput = it; nodeValidationError = null },
                    validationError = nodeValidationError,
                    onPrevious = goBack,
                    onNext = {
                        val valueToApply = when (nodeChoice) {
                            NodeChoice.DEFAULT_NODE -> AppSettingsRepository.DEFAULT_TRUSTED_NODE_ADDRESS
                            NodeChoice.AUTO_DISCOVER -> ""
                            NodeChoice.OWN_NODE -> ownNodeInput
                        }
                        val trimmed = valueToApply.trim()
                        if (trimmed.isNotEmpty() && com.kachat.app.services.grpc.parseNodeAddress(trimmed) == null) {
                            nodeValidationError = enterAsHostPortError
                        } else {
                            nodeValidationError = null
                            connectionViewModel.setTrustedNodeAddress(trimmed)
                            step = WelcomeGuideStep.ADDRESS_EXPLAINER
                        }
                    }
                )
                WelcomeGuideStep.ADDRESS_EXPLAINER -> WelcomeGuideAddressExplainerStep(
                    chattingAddress = chattingAddress,
                    onPrevious = goBack,
                    // The guide's final step (iOS d1844582 removed Starting a Conversation after it).
                    onNext = {
                        // Reaching Finish is what completes an onboarding run — clear the
                        // persisted re-presentation marker (no-op on replays, which never set it).
                        walletViewModel.clearOnboardingWizardPending()
                        onFinished()
                    }
                )
            }
        }
    }
    PushedQrPage(visible = showFundingQr, white = true, onBack = { showFundingQr = false }) {
        ChattingAddressQrPage(
            address = chattingAddress.orEmpty(),
            balanceSompi = fundingBalanceSompi,
            dismiss = QrPageDismiss.BACK,
            onDismiss = { showFundingQr = false },
        )
    }
    }
}

@Composable
private fun WelcomeGuideStepScaffold(
    icon: ImageVector,
    title: String,
    body: String,
    buttonLabel: String,
    onPrevious: (() -> Unit)?,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(20.dp))
            Text(
                title,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = LocalAppColors.current.textPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Text(
                body,
                color = LocalAppColors.current.textSecondary,
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(16.dp))
        WelcomeGuideButtonRow(onPrevious = onPrevious, onNext = onNext, nextLabel = buttonLabel)
    }
}

/**
 * The fixed two-button row every guide step ends with: Previous on the left, Next (or Finish)
 * on the right, always in the exact same position so the guide can be tapped through without
 * chasing buttons (matches iOS WelcomeGuideView.guideBottomBar). On the Welcome step
 * [onPrevious] is null and Previous renders disabled and dimmed rather than vanishing, so
 * Next never shifts.
 */
@Composable
private fun WelcomeGuideButtonRow(
    onPrevious: (() -> Unit)?,
    onNext: () -> Unit,
    nextLabel: String = stringResource(R.string.next),
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = { onPrevious?.invoke() },
            enabled = onPrevious != null,
            shape = RoundedCornerShape(14.dp),
            // iOS systemGray5, the whole button at 0.35 on the first step.
            colors = ButtonDefaults.buttonColors(
                containerColor = LocalAppColors.current.incomingBubble,
                disabledContainerColor = LocalAppColors.current.incomingBubble.copy(alpha = 0.35f)
            ),
            modifier = Modifier.weight(1f).height(48.dp)
        ) {
            Text(
                stringResource(R.string.previous),
                color = LocalAppColors.current.textPrimary.copy(alpha = if (onPrevious != null) 1f else 0.35f),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Button(
            onClick = onNext,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
            modifier = Modifier.weight(1f).height(48.dp)
        ) {
            Text(nextLabel, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * Picking a language here applies it immediately via [applyAppLanguage], which recreates the
 * Activity (see [applyAppLanguage]'s doc). [WalletViewModel.markPendingWelcomeGuide] is re-armed
 * first so the guide automatically restarts from the beginning - this time actually rendered in
 * the new language - once the recreated Activity's `MainShell` observes the flag again.
 */
@Composable
private fun WelcomeGuideLanguageStep(walletViewModel: WalletViewModel, onPrevious: (() -> Unit)?, onNext: () -> Unit) {
    val current = currentAppLanguage()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Language, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.choose_your_language),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = LocalAppColors.current.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.select_the_language_youd_like_to),
            color = LocalAppColors.current.textSecondary,
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            AppLanguage.entries.forEach { language ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(LocalAppColors.current.surface)
                        .clickable {
                            // No-op re-tap of the already-active language (most commonly "System",
                            // the pre-checked top row) must not re-arm the restart flag below:
                            // setApplicationLocales() only recreates the Activity on an actual
                            // locale change, so with no change this composable never gets torn
                            // down to consume the flag itself - it would loop this same still-alive
                            // MainShell back to a fresh "welcome_guide" destination on every tap,
                            // resetting the wizard to WELCOME with no way to progress past here.
                            if (language != current) {
                                walletViewModel.markPendingWelcomeGuide()
                                applyAppLanguage(language)
                            }
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (language == AppLanguage.SYSTEM) stringResource(R.string.system_default) else language.displayName,
                        color = LocalAppColors.current.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    if (current == language) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(16.dp))
        WelcomeGuideButtonRow(onPrevious = onPrevious, onNext = onNext)
    }
}

/**
 * Only affects Portfolio's live KAS price/value display (see `CoinGeckoApi`/`PortfolioRepository`)
 * - unlike language, this takes effect immediately with no Activity recreation, so the guide
 * simply continues to Fees on tap with no restart/reload concern.
 */
@Composable
private fun WelcomeGuideCurrencyStep(walletViewModel: WalletViewModel, onPrevious: (() -> Unit)?, onNext: () -> Unit) {
    val currencyCode by walletViewModel.currency.collectAsState()
    val current = AppCurrency.fromCode(currencyCode)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.MonetizationOn, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.choose_your_currency),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = LocalAppColors.current.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.select_the_currency_youd_like_prices),
            color = LocalAppColors.current.textSecondary,
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            AppCurrency.entries.forEach { currency ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(LocalAppColors.current.surface)
                        .clickable { walletViewModel.setCurrency(currency.code) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        currency.displayName,
                        color = LocalAppColors.current.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    if (current == currency) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(16.dp))
        WelcomeGuideButtonRow(onPrevious = onPrevious, onNext = onNext)
    }
}

@Composable
private fun WelcomeGuideFundingStep(
    chattingAddress: String?,
    walletViewModel: WalletViewModel,
    canChangeChattingAddress: Boolean,
    onShowQr: () -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: () -> Unit
) {
    var showChattingAddressPicker by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    // The picker takes over the whole step while open, and pops back here on Back or after a
    // switch — this step re-renders with the new address, since [chattingAddress] reads the live
    // WalletViewModel state the switch updates.
    if (showChattingAddressPicker) {
        ChattingAddressPickerScreen(
            walletViewModel = walletViewModel,
            onBack = { showChattingAddressPicker = false },
            onSwitched = { showChattingAddressPicker = false }
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.QrCode, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.fund_your_chatting_address),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = LocalAppColors.current.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.welcome_fund_chatting_body),
            color = LocalAppColors.current.textSecondary,
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )
        if (chattingAddress != null) {
            Spacer(Modifier.height(20.dp))
            Text(
                chattingAddress,
                // iOS: .footnote.monospaced(), at most two lines, 24pt in from the sides.
                fontSize = 13.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = LocalAppColors.current.textPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier
                    .clickable {
                        clipboardManager.setText(AnnotatedString(chattingAddress))
                        showAddressCopiedToast(context, chattingAddress)
                    }
                    .padding(horizontal = 24.dp)
            )
            TextButton(onClick = onShowQr) {
                Icon(Icons.Default.QrCode, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.show_qr_code), color = KaspaTeal, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (canChangeChattingAddress) {
            TextButton(onClick = {
                walletViewModel.resetChattingAddressScan()
                showChattingAddressPicker = true
            }) {
                Icon(Icons.Outlined.Badge, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.change_chatting_address), color = KaspaTeal, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
        Spacer(Modifier.height(16.dp))
        WelcomeGuideButtonRow(onPrevious = onPrevious, onNext = onNext)
    }
}

@Composable
private fun WelcomeGuideNodeConnectionStep(
    nodeChoice: NodeChoice,
    onNodeChoiceChange: (NodeChoice) -> Unit,
    ownNodeInput: String,
    onOwnNodeInputChange: (String) -> Unit,
    validationError: String?,
    onPrevious: (() -> Unit)?,
    onNext: () -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Dns, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.connect_to_a_node),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = LocalAppColors.current.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.kachat_needs_to_connect_to_a),
            color = LocalAppColors.current.textSecondary,
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))

        NodeChoiceRow(
            selected = nodeChoice == NodeChoice.DEFAULT_NODE,
            title = stringResource(R.string.default_option),
            badge = stringResource(R.string.recommended),
            subtitle = AppSettingsRepository.DEFAULT_TRUSTED_NODE_ADDRESS,
            onClick = { onNodeChoiceChange(NodeChoice.DEFAULT_NODE) }
        )
        Spacer(Modifier.height(10.dp))
        NodeChoiceRow(
            selected = nodeChoice == NodeChoice.OWN_NODE,
            title = stringResource(R.string.connect_your_own_node),
            badge = stringResource(R.string.best),
            subtitle = stringResource(R.string.enter_a_node_address_you_trust),
            onClick = { onNodeChoiceChange(NodeChoice.OWN_NODE) }
        )
        if (nodeChoice == NodeChoice.OWN_NODE) {
            Spacer(Modifier.height(8.dp))
            TextField(
                value = ownNodeInput,
                onValueChange = onOwnNodeInputChange,
                placeholder = { Text(stringResource(R.string.host_port_or_grpcs_host), color = LocalAppColors.current.textTertiary) },
                modifier = Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(12.dp)),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = LocalAppColors.current.surfaceVariant,
                    unfocusedContainerColor = LocalAppColors.current.surfaceVariant,
                    focusedTextColor = LocalAppColors.current.textPrimary,
                    unfocusedTextColor = LocalAppColors.current.textPrimary,
                    cursorColor = KaspaTeal,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
        }
        Spacer(Modifier.height(10.dp))
        NodeChoiceRow(
            selected = nodeChoice == NodeChoice.AUTO_DISCOVER,
            title = stringResource(R.string.auto_search_for_nodes),
            badge = null,
            subtitle = stringResource(R.string.most_taxing_on_the_device_not_as),
            onClick = { onNodeChoiceChange(NodeChoice.AUTO_DISCOVER) }
        )

        validationError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = LocalAppColors.current.danger, fontSize = 12.sp)
        }
    }
        Spacer(Modifier.height(16.dp))
        WelcomeGuideButtonRow(onPrevious = onPrevious, onNext = onNext)
    }
}

@Composable
private fun NodeChoiceRow(
    selected: Boolean,
    title: String,
    badge: String?,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LocalAppColors.current.surface)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) KaspaTeal else LocalAppColors.current.textSecondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Row {
                Text(title, fontWeight = FontWeight.SemiBold, color = LocalAppColors.current.textPrimary, fontSize = 15.sp)
                if (badge != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(badge, fontWeight = FontWeight.Bold, color = KaspaTeal, fontSize = 11.sp)
                }
            }
            Text(subtitle, color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun WelcomeGuideAddressExplainerStep(chattingAddress: String?, onPrevious: (() -> Unit)?, onNext: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            stringResource(R.string.welcome_your_chatting_address),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = LocalAppColors.current.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.welcome_chatting_address_phone_number),
            fontSize = 15.sp,
            color = LocalAppColors.current.textSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        AddressMockRow(
            icon = Icons.Filled.Forum,
            title = stringResource(R.string.welcome_how_people_reach_you),
            address = chattingAddress.orEmpty(),
            caption = stringResource(R.string.welcome_how_people_reach_you_caption)
        )
        Spacer(Modifier.height(12.dp))
        AddressMockRow(
            icon = Icons.AutoMirrored.Filled.Send,
            title = stringResource(R.string.welcome_paying_for_messages),
            address = "",
            caption = stringResource(R.string.welcome_paying_for_messages_caption)
        )
        Spacer(Modifier.height(12.dp))
        AddressMockRow(
            icon = Icons.Filled.Visibility,
            title = stringResource(R.string.welcome_what_others_can_see),
            address = "",
            caption = stringResource(R.string.welcome_what_others_can_see_caption)
        )
    }
        Spacer(Modifier.height(16.dp))
        WelcomeGuideButtonRow(onPrevious = onPrevious, onNext = onNext, nextLabel = stringResource(R.string.finish))
    }
}

@Composable
private fun AddressMockRow(icon: ImageVector, title: String, address: String, caption: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LocalAppColors.current.surface)
            .padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = KaspaTeal,
            modifier = Modifier.width(28.dp).height(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = LocalAppColors.current.textPrimary, fontSize = 15.sp)
            if (address.isNotEmpty()) {
                MiddleEllipsisText(
                    address,
                    color = LocalAppColors.current.textSecondary,
                    fontSize = 12.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(caption, color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
        }
    }
}
