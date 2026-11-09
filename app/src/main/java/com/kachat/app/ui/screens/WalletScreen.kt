package com.kachat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.kachat.app.R
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.KaspaNetwork
import com.kachat.app.util.KaspaUnit
import com.kachat.app.util.formatFiatAmount
import com.kachat.app.viewmodels.ChatViewModel
import com.kachat.app.viewmodels.KasPriceViewModel
import com.kachat.app.viewmodels.WalletViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** What Wallet's three round buttons open (iOS bfe9f022 `WalletView.WalletAction`). */
private enum class WalletAction { SEND, RECEIVE, REQUEST }

/**
 * The Wallet tab (iOS bfe9f022 `WalletView`): everything this account holds and what you can do
 * with it. The total up top, split into the chatting and spending addresses; Send, Receive and
 * Request under it; Backup Hygiene when due; then the two ways into the addresses themselves.
 * Own-address receipts that name no one address land here (MainShell).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(
    viewModel: WalletViewModel,
    navController: NavController,
    chatViewModel: ChatViewModel = hiltViewModel(),
    priceViewModel: KasPriceViewModel = hiltViewModel(),
) {
    val address by viewModel.address.collectAsState()
    val chattingSompi by viewModel.balanceSompi.collectAsState()
    /** The chatting balance on the card. Null until it's known, which shows as a dash. */
    val knownChattingSompi by viewModel.knownChattingSompi.collectAsState()
    /** Every revealed spending address together. Null until it's known, which shows as a dash. */
    val spendingTotalSompi by viewModel.spendingTotalSompi.collectAsState()
    val backupConfirmedAt by viewModel.accountBackupStatus.confirmedAt.collectAsState()
    val backupPendingAddresses by viewModel.accountBackupStatus.pendingAddresses.collectAsState()
    var action by remember { mutableStateOf<WalletAction?>(null) }
    var showChattingQr by remember { mutableStateOf(false) }
    // Receive's fresh address: decided before the tap, so Receive opens on a code rather than a
    // spinner, and re-confirmed when the page comes up (shown now: it goes on the Manage Addresses
    // list and is watched for payments). A QR must not change under a pointed camera for any
    // lesser reason, so it isn't cleared first.
    var receiveAddress by remember(address) { mutableStateOf<String?>(null) }
    var receiveResolving by remember(address) { mutableStateOf(true) }

    LaunchedEffect(address) {
        viewModel.resolveFreshReceiveAddress(revealing = false) { resolved ->
            receiveAddress = resolved
            receiveResolving = false
        }
        // The network part at most every 30 s: switching tabs back and forth would start these
        // requests each time. Arriving funds still refresh at once, below.
        if (viewModel.claimWalletAppearRefresh()) {
            viewModel.refreshBalance()
            viewModel.refreshSpendingAddress()
        }
    }
    LaunchedEffect(action) {
        if (action == WalletAction.RECEIVE) {
            if (receiveAddress == null) receiveResolving = true
            viewModel.resolveFreshReceiveAddress(revealing = true) { resolved ->
                receiveAddress = resolved
                receiveResolving = false
            }
        }
    }
    // Coalesces the bursts the activity events arrive in (iOS `scheduleRefresh`): each new one
    // restarts the half-second wait.
    LaunchedEffect(Unit) {
        chatViewModel.ownAddressUtxoActivityEvents.collectLatest {
            delay(500L)
            viewModel.refreshBalance()
            viewModel.refreshSpendingAddress()
        }
    }

    val pullRefreshState = rememberPullToRefreshState()
    LaunchedEffect(pullRefreshState.isRefreshing) {
        if (pullRefreshState.isRefreshing) {
            viewModel.refreshBalanceAndAwait()
            viewModel.refreshSpendingBalanceAndAwait()
            pullRefreshState.endRefresh()
        }
    }

    // Re-tapping the Wallet tab backs out of a pushed QR page (see WalletViewModel.notifyTabReselected).
    val tabReselectSignal by viewModel.tabReselectSignal.collectAsState()
    LaunchedEffect(tabReselectSignal) {
        if (tabReselectSignal.second == com.kachat.app.ui.Screen.Wallet.route) showChattingQr = false
    }

    val dockInset = LocalDockInset.current
    Scaffold(
        containerColor = LocalAppColors.current.background,
        // The page runs on under the dock; its scroll ends with room to clear it.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            MainPageHeader(
                title = stringResource(R.string.wallet_title),
                balanceLabel = { BalanceLabelRow(knownChattingSompi?.let { KaspaUnit.amount(it) } ?: "-- ${KaspaUnit.symbol}") },
                banner = { AccountBackupBanner(viewModel) { navController.navigate("account_backup") } },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(pullRefreshState.nestedScrollConnection)
        ) {
            val current = address
            if (current != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(top = 8.dp, bottom = 24.dp + dockInset),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    WalletBalanceCard(
                        chattingSompi = knownChattingSompi,
                        spendingSompi = spendingTotalSompi,
                        priceViewModel = priceViewModel,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        WalletActionButton(stringResource(R.string.wallet_send), Icons.Filled.ArrowUpward, Modifier.weight(1f)) { action = WalletAction.SEND }
                        WalletActionButton(stringResource(R.string.wallet_receive), Icons.Filled.ArrowDownward, Modifier.weight(1f)) { action = WalletAction.RECEIVE }
                        WalletActionButton(stringResource(R.string.wallet_request), Icons.Outlined.Description, Modifier.weight(1f)) { action = WalletAction.REQUEST }
                    }
                    if (remember(current, backupConfirmedAt, backupPendingAddresses) { viewModel.accountBackupStatus.isHygieneDue(current) }) {
                        BackupHygieneCard { navController.navigate("backup_hygiene") }
                    }
                    // Testnet only: free TN10 coins for the chatting address, from the official faucet.
                    if (KaspaNetwork.isTestnet) {
                        TestnetFaucetClaimButton(
                            address = current,
                            balanceSompi = { viewModel.balanceSompi.value },
                            refreshBalance = { viewModel.refreshBalanceAndAwait() },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        WalletAddressSquare(
                            icon = Icons.AutoMirrored.Filled.ListAlt,
                            title = stringResource(R.string.manage_addresses),
                            modifier = Modifier.weight(1f),
                        ) { navController.navigate("manage_addresses") }
                        WalletAddressSquare(
                            icon = Icons.Filled.QrCode,
                            title = stringResource(R.string.chatting_address),
                            modifier = Modifier.weight(1f),
                        ) { showChattingQr = true }
                    }
                }
            }
            PullToRefreshContainer(state = pullRefreshState, modifier = Modifier.align(Alignment.TopCenter))
        }
    }

    // Pushed as iOS's NavigationLink pushes it: over the whole screen, the dock left in place.
    PushedQrPage(visible = showChattingQr, white = true, onBack = { showChattingQr = false }) {
        ChattingAddressQrPage(
            address = address ?: "",
            balanceSompi = chattingSompi,
            dismiss = QrPageDismiss.BACK,
            onDismiss = { showChattingQr = false },
        )
    }
    when (action) {
        WalletAction.SEND -> ChatsSpendingSendLauncher(walletViewModel = viewModel, onDone = { action = null })
        WalletAction.RECEIVE -> QrPageSheet(onDismiss = { action = null }, white = receiveAddress != null) {
            ReceiveKaspaQrPage(
                address = receiveAddress,
                resolving = receiveResolving,
                dismiss = QrPageDismiss.DONE,
                onDismiss = { action = null },
                title = stringResource(R.string.receive_kaspa),
            )
        }
        WalletAction.REQUEST -> CreatePaymentRequestSheet(
            walletViewModel = viewModel,
            onDismissed = { action = null },
        )
        null -> Unit
    }
}

/**
 * The total and its split (iOS `balanceCard`): the total as largeTitle bold, its worth in your
 * currency, then Chatting and Spending. Both parts or a dash: a total missing one of them would
 * read as the whole balance. A tap on the total copies it.
 */
@Composable
private fun WalletBalanceCard(
    chattingSompi: Long?,
    spendingSompi: Long?,
    priceViewModel: KasPriceViewModel,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val colors = LocalAppColors.current
    val clipboard = LocalClipboardManager.current
    val haptic = com.kachat.app.util.rememberHaptics()
    val price by priceViewModel.currentPriceUsd.collectAsState()
    val currency by priceViewModel.currency.collectAsState()
    val total = if (chattingSompi != null && spendingSompi != null) chattingSompi + spendingSompi else null
    // What the total is worth in your currency. Testnet coins aren't worth anything.
    val fiat = price?.takeIf { total != null && !KaspaNetwork.isTestnet && it > 0.0 }?.let { p ->
        formatFiatAmount((total ?: 0L) / 100_000_000.0 * p, currency, decimals = 4)
    }
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(KaspaTeal.copy(alpha = 0.32f), KaspaTeal.copy(alpha = 0.08f))))
            .border(0.8.dp, KaspaTeal.copy(alpha = 0.35f), shape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.wallet_total_balance), color = colors.textSecondary, fontSize = 15.sp)
            KachatFitText(
                total?.let { KaspaUnit.amount(it) } ?: "--",
                color = colors.textPrimary,
                fontSize = LargeTitleSize,
                fontWeight = FontWeight.Bold,
                minScale = 0.6f,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    if (total != null) {
                        clipboard.setText(AnnotatedString(KaspaUnit.plain(total)))
                        haptic(com.kachat.app.util.IosHaptic.SUCCESS)
                        IosToasts.show(context.getString(R.string.balance_copied_to_clipboard))
                    }
                },
            )
            if (fiat != null) {
                Text(fiat, color = colors.textSecondary, fontSize = 15.sp)
            }
        }
        HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            WalletBalancePart(stringResource(R.string.wallet_chatting), chattingSompi, Modifier.weight(1f))
            WalletBalancePart(stringResource(R.string.wallet_spending), spendingSompi, Modifier.weight(1f))
        }
    }
}

@Composable
private fun WalletBalancePart(title: String, sompi: Long?, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = colors.textSecondary, fontSize = 12.sp)
        KachatFitText(
            sompi?.let { KaspaUnit.amount(it) } ?: "--",
            color = colors.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            minScale = 0.7f,
        )
    }
}

/** One of Send, Receive and Request (iOS `actionButton`): a 60 accent circle with a black symbol
 *  over its subheadline title. */
@Composable
private fun WalletActionButton(title: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val haptic = com.kachat.app.util.rememberHaptics()
    Column(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                haptic(com.kachat.app.util.IosHaptic.IMPACT_LIGHT)
                onClick()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier.size(60.dp).clip(CircleShape).background(KaspaTeal),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.Black, modifier = Modifier.size(26.dp))
        }
        Text(title, color = LocalAppColors.current.textPrimary, fontSize = 15.sp, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/** One of Manage Addresses and Chatting Address (iOS `addressSquare`): a square of material with
 *  the accent symbol in a 48 circle over its title. */
@Composable
private fun WalletAddressSquare(icon: ImageVector, title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(colors.surface)
            .border(0.8.dp, Color.White.copy(alpha = 0.18f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Box(
            modifier = Modifier.size(48.dp).clip(CircleShape).background(colors.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(24.dp))
        }
        Text(
            title,
            color = colors.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}
