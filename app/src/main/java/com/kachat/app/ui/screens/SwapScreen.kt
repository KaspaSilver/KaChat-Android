package com.kachat.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.R
import com.kachat.app.models.CURATED_SWAP_COINS
import com.kachat.app.models.SwapCoin
import com.kachat.app.models.SwapTransactionEntity
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.SwapViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.kachat.app.util.showAddressCopiedToast

/** KAS <-> USDC (Polygon) swaps, powered by ChangeNOW — see [SwapViewModel] and [SwapRepository][com.kachat.app.repository.SwapRepository]. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SwapScreen(
    navController: androidx.navigation.NavController? = null,
    swapViewModel: SwapViewModel = hiltViewModel(),
    portfolioViewModel: com.kachat.app.viewmodels.PortfolioViewModel = hiltViewModel()
) {
    // "Add to Portfolio" first asks WHICH portfolio (4.0, matches iOS) - the chosen one
    // becomes active, then the prefilled add-transaction screen (which writes to the active
    // portfolio) opens as before.
    val allPortfolios by portfolioViewModel.portfolios.collectAsState()
    var portfolioPickerAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val kasIsSendSide by swapViewModel.kasIsSendSide.collectAsState()
    val otherCoin by swapViewModel.otherCoin.collectAsState()
    val amountText by swapViewModel.amountText.collectAsState()
    val payoutAddressText by swapViewModel.payoutAddressText.collectAsState()
    val estimateState by swapViewModel.estimateState.collectAsState()
    val createSwapState by swapViewModel.createSwapState.collectAsState()
    val toAddress by swapViewModel.toAddress.collectAsState()
    val swapHistory by swapViewModel.swapHistory.collectAsState()
    val swapDisclaimerAgreed by swapViewModel.swapDisclaimerAgreed.collectAsState()

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val pagerState = rememberPagerState(pageCount = { 2 })
    val pagerScope = rememberCoroutineScope()
    var selectedSwapId by remember { mutableStateOf<String?>(null) }
    var showCoinPicker by remember { mutableStateOf(false) }
    var pendingDeleteSwapId by remember { mutableStateOf<String?>(null) }
    val selectedSwap = swapHistory.find { it.id == selectedSwapId }

    val savedStateHandle = navController?.currentBackStackEntry?.savedStateHandle
    val pickedToIndex = savedStateHandle?.getStateFlow<Int?>("picked_to_index", null)?.collectAsState()
    LaunchedEffect(pickedToIndex?.value) {
        val index = pickedToIndex?.value ?: return@LaunchedEffect
        swapViewModel.selectToSpendingAddress(index)
        savedStateHandle?.remove<Int>("picked_to_index")
    }

    val toCoinForDisplay = if (kasIsSendSide) otherCoin else com.kachat.app.models.KAS_SWAP_COIN
    val needsPayoutAddress = toCoinForDisplay.ticker != "kas"

    LaunchedEffect(createSwapState.status) {
        if (createSwapState.status == SwapViewModel.CreateSwapStatus.SUCCESS) {
            Toast.makeText(context, context.getString(R.string.swap_started), Toast.LENGTH_SHORT).show()
        }
        if (createSwapState.status == SwapViewModel.CreateSwapStatus.FAILED) {
            Toast.makeText(context, createSwapState.errorMessage ?: context.getString(R.string.swap_failed), Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            Column {
                MainPageHeader(title = stringResource(R.string.swap))
                TabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = LocalAppColors.current.background,
                    contentColor = KaspaTeal
                ) {
                    Tab(
                        selected = pagerState.currentPage == 0,
                        onClick = { pagerScope.launch { pagerState.animateScrollToPage(0) } },
                        text = { Text(stringResource(R.string.swap), fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = pagerState.currentPage == 1,
                        onClick = { pagerScope.launch { pagerState.animateScrollToPage(1) } },
                        text = { Text(stringResource(R.string.swap_history), fontWeight = FontWeight.Bold) }
                    )
                }
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) { page ->
        if (page == 1) {
            SwapHistoryPage(
                swapHistory = swapHistory,
                onSwapClick = { selectedSwapId = it },
                onSwapDelete = { pendingDeleteSwapId = it }
            )
            return@HorizontalPager
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SwapAmountCard(
                label = "You Send",
                coin = if (kasIsSendSide) com.kachat.app.models.KAS_SWAP_COIN else otherCoin,
                coinLabel = if (kasIsSendSide) "KAS" else otherCoin.displayName,
                amountText = amountText,
                onAmountChange = { swapViewModel.setAmountText(it) },
                editable = true,
                onMaxClick = null,
                onCoinClick = if (!kasIsSendSide) { { showCoinPicker = true } } else null
            )

            // Flip control and the primary CTA share a row instead of the CTA sitting in its own
            // full-width button further down — keeps the whole form on screen without scrolling.
            val isBusy = createSwapState.status == SwapViewModel.CreateSwapStatus.CREATING
            val canSwap = estimateState.status == SwapViewModel.EstimateStatus.SUCCESS && !isBusy
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(
                    onClick = { swapViewModel.flipDirection() },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(KaspaTeal)
                ) {
                    Icon(Icons.Default.SwapVert, "Switch direction", tint = Color.Black)
                }
                Button(
                    onClick = { swapViewModel.executeSwap() },
                    enabled = canSwap,
                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, disabledContainerColor = LocalAppColors.current.surfaceVariant),
                    modifier = Modifier.weight(1f).height(40.dp)
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
                    } else {
                        Text(
                            "Get Deposit Address",
                            color = if (canSwap) Color.Black else LocalAppColors.current.textSecondary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            val estimatedAmountText = when (estimateState.status) {
                SwapViewModel.EstimateStatus.SUCCESS -> "%.8f".format(Locale.US, estimateState.toAmount ?: 0.0)
                SwapViewModel.EstimateStatus.LOADING -> "..."
                else -> ""
            }
            SwapAmountCard(
                label = "You Get",
                coin = if (kasIsSendSide) otherCoin else com.kachat.app.models.KAS_SWAP_COIN,
                coinLabel = if (kasIsSendSide) otherCoin.displayName else "KAS",
                amountText = estimatedAmountText,
                onAmountChange = {},
                editable = false,
                onCoinClick = if (kasIsSendSide) { { showCoinPicker = true } } else null
            )

            if (needsPayoutAddress) {
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = payoutAddressText,
                    onValueChange = { swapViewModel.setPayoutAddressText(it) },
                    label = { Text("Receive ${toCoinForDisplay.displayName} at") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = KaspaTeal,
                        unfocusedBorderColor = LocalAppColors.current.surfaceVariant,
                        focusedTextColor = LocalAppColors.current.textPrimary,
                        unfocusedTextColor = LocalAppColors.current.textPrimary,
                        focusedLabelColor = KaspaTeal,
                        unfocusedLabelColor = LocalAppColors.current.textSecondary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (!kasIsSendSide) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(LocalAppColors.current.surface)
                        .clickable(enabled = navController != null) {
                            navController?.navigate("manage_addresses_pick/to")
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.receiving_kas_at), color = LocalAppColors.current.textSecondary, fontSize = 11.sp)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (toAddress.length > 20) "${toAddress.take(12)}...${toAddress.takeLast(6)}" else toAddress,
                            color = LocalAppColors.current.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.change),
                        color = KaspaTeal,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            val isEstimateFailed = estimateState.status == SwapViewModel.EstimateStatus.FAILED
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(LocalAppColors.current.surface)
                    .padding(10.dp)
            ) {
                Text(stringResource(R.string.rate), color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(2.dp))
                val rateText = if (estimateState.status == SwapViewModel.EstimateStatus.SUCCESS) {
                    val fromAmount = amountText.toDoubleOrNull() ?: 0.0
                    val toAmount = estimateState.toAmount ?: 0.0
                    if (fromAmount > 0) {
                        val fromLabel = if (kasIsSendSide) "KAS" else otherCoin.displayName
                        val toLabel = if (kasIsSendSide) otherCoin.displayName else "KAS"
                        "1 $fromLabel ≈ %.8f $toLabel".format(Locale.US, toAmount / fromAmount)
                    } else "N/A"
                } else if (isEstimateFailed) {
                    estimateState.errorMessage ?: "Unavailable"
                } else "N/A"
                Text(
                    rateText,
                    color = if (isEstimateFailed) Color(0xFFFF3B30) else LocalAppColors.current.textSecondary,
                    fontSize = 12.sp
                )
            }

            createSwapState.result?.let { result ->
                Spacer(Modifier.height(16.dp))
                Surface(
                    color = LocalAppColors.current.surface,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val fromCoinDisplayName = if (kasIsSendSide) com.kachat.app.models.KAS_SWAP_COIN.displayName else otherCoin.displayName
                        if (kasIsSendSide) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = navController != null) {
                                        navController?.navigate("manage_addresses")
                                    }
                                    .padding(bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Go to Spending Addresses",
                                    color = KaspaTeal,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = KaspaTeal)
                            }
                        }
                        Text(
                            "Send $fromCoinDisplayName to this address",
                            color = LocalAppColors.current.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        // Neither side of a swap is something this app sends automatically - the
                        // user always pays into the deposit address themselves, from wherever they
                        // hold whichever coin they're giving up (including KAS).
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            val qrPainter = rememberQrBitmapPainter(result.payinAddress ?: "")
                            Box(
                                modifier = Modifier
                                    .size(180.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White)
                                    .clickable {
                                        result.payinAddress?.let {
                                            clipboardManager.setText(AnnotatedString(it))
                                            showAddressCopiedToast(context, it)
                                        }
                                    }
                                    .padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                androidx.compose.foundation.Image(qrPainter, "Deposit address QR", modifier = Modifier.fillMaxSize())
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    result.payinAddress?.let {
                                        clipboardManager.setText(AnnotatedString(it))
                                        showAddressCopiedToast(context, it)
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                result.payinAddress ?: "",
                                color = LocalAppColors.current.textSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Status: ${result.status ?: "new"}", color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.changenow_exchange_id), color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                        Text(
                            result.id,
                            color = LocalAppColors.current.textPrimary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    clipboardManager.setText(AnnotatedString(result.id))
                                    Toast.makeText(context, context.getString(R.string.exchange_id_copied), Toast.LENGTH_SHORT).show()
                                }
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                stringResource(R.string.refresh_status),
                                color = KaspaTeal,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.clickable { swapViewModel.refreshSwapStatus(result.id) }
                            )
                            Text(
                                stringResource(R.string.view_on_changenow),
                                color = KaspaTeal,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.clickable {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse("https://changenow.io/exchange/txs/${result.id}")
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                stringResource(R.string.powered_by_changenow),
                color = KaspaTeal,
                fontSize = 12.sp,
                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://changenow.io/terms-of-use/changenow-terms")
                            )
                        )
                    },
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))
        }
        }
    }

    selectedSwap?.let { swap ->
        SwapDetailDialog(
            swap = swap,
            onDismiss = { selectedSwapId = null },
            onRefresh = { swapViewModel.refreshSwapStatus(swap.id) },
            onAddToPortfolio = {
                val isKasReceived = swap.toTicker == "kas"
                val amountKas = (if (isKasReceived) swap.toAmount else swap.fromAmount).toDoubleOrNull()
                val fiatValue = (if (isKasReceived) swap.fromAmount else swap.toAmount).toDoubleOrNull()
                if (amountKas == null || fiatValue == null) {
                    Toast.makeText(context, context.getString(R.string.couldn_t_read_this_swap_s), Toast.LENGTH_SHORT).show()
                } else {
                    val notes = android.net.Uri.encode("ChangeNOW swap ${swap.id}")
                    val navigate = {
                        selectedSwapId = null
                        navController?.navigate(
                            "portfolio_transactions?prefillType=${if (isKasReceived) "buy" else "sell"}" +
                                "&prefillAmountKas=$amountKas&prefillFiatValue=$fiatValue" +
                                "&prefillTimestamp=${swap.createdAtMillis}&prefillNotes=$notes&prefillSwapId=${swap.id}"
                        )
                        Unit
                    }
                    if (allPortfolios.size > 1) portfolioPickerAction = navigate else navigate()
                }
            }
        )
    }

    portfolioPickerAction?.let { pendingNavigate ->
        AlertDialog(
            onDismissRequest = { portfolioPickerAction = null },
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.add_to_portfolio), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    // Portfolios that already hold this swap say so on their own row, so the
                    // duplicate is visible while the choice is being made rather than after.
                    val duplicateIds = selectedSwap?.let {
                        portfolioViewModel.portfolioIdsContaining(
                            com.kachat.app.viewmodels.PortfolioViewModel.swapSourceTxId(it.id)
                        )
                    } ?: emptySet()
                    allPortfolios.forEach { portfolio ->
                        val isDuplicate = portfolio.id in duplicateIds
                        Text(
                            if (isDuplicate) "${portfolio.name} (already added)" else portfolio.name,
                            color = if (isDuplicate) LocalAppColors.current.textSecondary else KaspaTeal,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    portfolioViewModel.setActivePortfolio(portfolio.id)
                                    portfolioPickerAction = null
                                    pendingNavigate()
                                }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { portfolioPickerAction = null }) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }

    if (showCoinPicker) {
        SwapCoinPickerDialog(
            currentCoin = otherCoin,
            onDismiss = { showCoinPicker = false },
            onPick = {
                swapViewModel.setOtherCoin(it)
                showCoinPicker = false
            }
        )
    }

    if (!swapDisclaimerAgreed) {
        var hasReadChangeNowTerms by remember { mutableStateOf(false) }
        val termsUriHandler = androidx.compose.ui.platform.LocalUriHandler.current
        AlertDialog(
            onDismissRequest = {},
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.before_you_swap), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.swaps_are_processed_by_changenow_a),
                        color = LocalAppColors.current.textSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Read ChangeNOW's Terms of Use",
                        color = KaspaTeal,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                        modifier = Modifier.clickable { termsUriHandler.openUri("https://changenow.io/terms-of-use") }
                    )
                    Spacer(Modifier.height(12.dp))
                    // Agreeing is gated on explicitly confirming the terms were read.
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.clickable { hasReadChangeNowTerms = !hasReadChangeNowTerms }
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = hasReadChangeNowTerms,
                            onCheckedChange = { hasReadChangeNowTerms = it },
                            colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = KaspaTeal)
                        )
                        Text(
                            "I have read and agree to ChangeNOW's Terms of Use",
                            color = LocalAppColors.current.textPrimary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 14.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { swapViewModel.agreeToSwapDisclaimer() },
                    enabled = hasReadChangeNowTerms
                ) {
                    Text(
                        stringResource(R.string.i_agree),
                        color = if (hasReadChangeNowTerms) KaspaTeal else LocalAppColors.current.textSecondary,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { navController?.popBackStack() }) {
                    Text(stringResource(R.string.not_now), color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }

    pendingDeleteSwapId?.let { swapId ->
        AlertDialog(
            onDismissRequest = { pendingDeleteSwapId = null },
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.delete_this_swap), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    stringResource(R.string.delete_swap_history_message),
                    color = LocalAppColors.current.textSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    swapViewModel.deleteSwap(swapId)
                    pendingDeleteSwapId = null
                }) {
                    Text(stringResource(R.string.delete), color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSwapId = null }) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }
}

/** Full detail for one past swap — its deposit QR again, live-ish status, the ChangeNOW exchange id, and a link to track it on changenow.io. */
@Composable
private fun SwapDetailDialog(
    swap: SwapTransactionEntity,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onAddToPortfolio: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = LocalAppColors.current.surface,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                val toAmountText = swap.toAmount.toDoubleOrNull()?.let { "%.8f".format(Locale.US, it) } ?: swap.toAmount
                Text(
                    "${swap.fromAmount} ${swap.fromTicker.uppercase()} → $toAmountText ${swap.toTicker.uppercase()}",
                    color = LocalAppColors.current.textPrimary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    remember(swap.createdAtMillis) {
                        SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.US).format(Date(swap.createdAtMillis))
                    },
                    color = LocalAppColors.current.textSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(16.dp))

                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val qrPainter = rememberQrBitmapPainter(swap.payinAddress)
                    Box(
                        modifier = Modifier
                            .size(180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(qrPainter, "Deposit address QR", modifier = Modifier.fillMaxSize())
                    }
                }
                Spacer(Modifier.height(12.dp))

                Text(stringResource(R.string.deposit_address), color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                Text(
                    swap.payinAddress,
                    color = LocalAppColors.current.textPrimary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboardManager.setText(AnnotatedString(swap.payinAddress))
                            showAddressCopiedToast(context, swap.payinAddress)
                        }
                )
                Spacer(Modifier.height(12.dp))

                Text(stringResource(R.string.status), color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        swap.status.replaceFirstChar { it.uppercase() },
                        color = when (swap.status) {
                            "finished" -> Color(0xFF4CD964)
                            "failed", "refunded" -> Color(0xFFFF3B30)
                            else -> Color(0xFFF39C12)
                        },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (swap.status == "finished") {
                        Spacer(Modifier.width(16.dp))
                        Text(
                            stringResource(R.string.add_to_portfolio),
                            color = KaspaTeal,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.clickable(onClick = onAddToPortfolio)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                Text(stringResource(R.string.changenow_exchange_id), color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                Text(
                    swap.id,
                    color = LocalAppColors.current.textPrimary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboardManager.setText(AnnotatedString(swap.id))
                            Toast.makeText(context, context.getString(R.string.exchange_id_copied), Toast.LENGTH_SHORT).show()
                        }
                )
                Spacer(Modifier.height(20.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        stringResource(R.string.refresh_status),
                        color = KaspaTeal,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clickable(onClick = onRefresh)
                    )
                    Text(
                        stringResource(R.string.view_on_changenow),
                        color = KaspaTeal,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clickable {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://changenow.io/exchange/txs/${swap.id}")
                                )
                            )
                        }
                    )
                }
            }
        }
    }
}

/** Ticker -> drawable resource for coins with real brand art (sourced from the Tangem wallet
 * app's bundled network logos, via its own remote icon CDN -
 * `s3.eu-central-1.amazonaws.com/tangem.api/coins/large/{coingecko-id}.png`). Tickers not listed
 * here have no available art and fall back to the plain ticker-text circle. */
private val coinLogoDrawables: Map<String, Int> = mapOf(
    "btc" to R.drawable.ic_coin_btc,
    "eth" to R.drawable.ic_coin_eth,
    "sol" to R.drawable.ic_coin_sol,
    "xrp" to R.drawable.ic_coin_xrp,
    "bnb" to R.drawable.ic_coin_bnb,
    "trx" to R.drawable.ic_coin_trx,
    "hype" to R.drawable.ic_coin_hype,
    "doge" to R.drawable.ic_coin_doge,
    "ltc" to R.drawable.ic_coin_ltc,
    "ada" to R.drawable.ic_coin_ada,
    "bch" to R.drawable.ic_coin_bch,
    "etc" to R.drawable.ic_coin_etc,
    "usdc" to R.drawable.ic_coin_usdc,
    "usdt" to R.drawable.ic_coin_usdt,
    "zec" to R.drawable.ic_coin_zec,
    "xmr" to R.drawable.ic_coin_xmr
)

/**
 * KAS and the tickers in `coinLogoDrawables` get their real brand marks; USDC-on-Polygon
 * additionally gets a small network badge in the corner; everything else falls back to a plain
 * ticker-text circle, since no art exists for them. Matches iOS's identical fallback
 * (`swapCoinIcon` in SwapView.swift).
 */
@Composable
private fun CoinIcon(coin: SwapCoin, size: Dp = 28.dp) {
    when {
        coin.ticker == "kas" -> {
            Image(
                painterResource(R.drawable.ic_kaspa_logo),
                contentDescription = coin.displayName,
                modifier = Modifier.size(size).clip(CircleShape)
            )
        }
        coin.ticker == "usdc" && coin.network == "matic" -> {
            Box(modifier = Modifier.size(size)) {
                Image(
                    painterResource(R.drawable.ic_coin_usdc),
                    contentDescription = coin.displayName,
                    modifier = Modifier.size(size).clip(CircleShape)
                )
                Image(
                    painterResource(R.drawable.ic_polygon_network),
                    contentDescription = stringResource(R.string.polygon_network),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(size * 0.5f)
                        .clip(CircleShape)
                        .border(1.dp, LocalAppColors.current.surface, CircleShape)
                )
            }
        }
        coinLogoDrawables.containsKey(coin.ticker) -> {
            Image(
                painterResource(coinLogoDrawables.getValue(coin.ticker)),
                contentDescription = coin.displayName,
                modifier = Modifier.size(size).clip(CircleShape)
            )
        }
        else -> {
            Box(
                modifier = Modifier.size(size).clip(CircleShape).background(KaspaTeal.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    coin.ticker.uppercase(),
                    color = LocalAppColors.current.textPrimary,
                    fontSize = (size.value * 0.28f).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

/** Full-page swap history — its own pager page rather than a collapsible section, so it's a normal-height scrollable list. */
@Composable
private fun SwapHistoryPage(swapHistory: List<SwapTransactionEntity>, onSwapClick: (String) -> Unit, onSwapDelete: (String) -> Unit) {
    if (swapHistory.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_swaps_yet), color = LocalAppColors.current.textSecondary, textAlign = TextAlign.Center)
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Surface(
            color = LocalAppColors.current.surface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                swapHistory.forEachIndexed { index, swap ->
                    SwapHistoryRow(swap, onClick = { onSwapClick(swap.id) }, onDelete = { onSwapDelete(swap.id) })
                    if (index < swapHistory.lastIndex) {
                        HorizontalDivider(color = LocalAppColors.current.divider)
                    }
                }
            }
        }
    }
}

@Composable
private fun SwapHistoryRow(swap: SwapTransactionEntity, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(LocalAppColors.current.surface)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val toAmountText = swap.toAmount.toDoubleOrNull()?.let { "%.8f".format(Locale.US, it) } ?: swap.toAmount
            Text(
                "${swap.fromAmount} ${swap.fromTicker.uppercase()} → $toAmountText ${swap.toTicker.uppercase()}",
                color = LocalAppColors.current.textPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                remember(swap.createdAtMillis) {
                    SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.US).format(Date(swap.createdAtMillis))
                },
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text(
            swap.status.replaceFirstChar { it.uppercase() },
            color = when (swap.status) {
                "finished" -> Color(0xFF4CD964)
                "failed", "refunded" -> Color(0xFFFF3B30)
                else -> Color(0xFFF39C12)
            },
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodySmall
        )
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.delete),
                tint = Color(0xFFFF3B30),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun SwapAmountCard(
    label: String,
    coin: SwapCoin,
    coinLabel: String,
    amountText: String,
    onAmountChange: (String) -> Unit,
    editable: Boolean,
    onMaxClick: (() -> Unit)? = null,
    // Only the non-KAS side of the pair is actually pickable - KAS is always the fixed side, so
    // callers only ever pass this for the card showing `otherCoin`.
    onCoinClick: (() -> Unit)? = null
) {
    Surface(
        color = LocalAppColors.current.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(label, color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (editable) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = onAmountChange,
                        placeholder = { Text(stringResource(R.string.n_0_00), color = LocalAppColors.current.textSecondary) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                        ),
                        trailingIcon = onMaxClick?.let { max ->
                            { TextButton(onClick = max) { Text(stringResource(R.string.max), color = KaspaTeal) } }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = KaspaTeal,
                            unfocusedBorderColor = LocalAppColors.current.surfaceVariant,
                            focusedTextColor = LocalAppColors.current.textPrimary,
                            unfocusedTextColor = LocalAppColors.current.textPrimary
                        ),
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Text(
                        amountText.ifBlank { "0.00" },
                        color = LocalAppColors.current.textPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(LocalAppColors.current.surfaceVariant)
                        .then(if (onCoinClick != null) Modifier.clickable(onClick = onCoinClick) else Modifier)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CoinIcon(coin, size = 20.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(coinLabel, color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    if (onCoinClick != null) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = LocalAppColors.current.textSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Reached by tapping either amount card's coin badge (the non-KAS side only - KAS is always the
 * fixed side of the pair). Searchable since [CURATED_SWAP_COINS] now has ~50 entries across many
 * networks, not just the one hardcoded USDC-Polygon pair this originally shipped with. Mirrors
 * iOS's SwapCoinPickerView.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwapCoinPickerDialog(currentCoin: SwapCoin, onDismiss: () -> Unit, onPick: (SwapCoin) -> Unit) {
    // Tickers with more than one network - collapsed to a single row on the root list that
    // expands in place to show its networks (rather than listing all ~7-9 networks inline
    // unconditionally, or navigating to a second screen), since that's most of what made the flat
    // list unwieldy.
    val groupedTickers = remember { mapOf("usdt" to "Tether", "usdc" to "USD Coin") }
    var expandedGroups by remember { mutableStateOf(setOf<String>()) }
    var searchText by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // Root list: USDC and USDT are pinned as the first two rows (in that order) since they're
        // the most commonly swapped stablecoins, each a collapsed/expandable row that shows one
        // indented network row per its coins while expanded; everything else follows in
        // CURATED_SWAP_COINS's order.
        data class RootRow(val key: String, val ticker: String, val displayName: String, val icon: SwapCoin, val coin: SwapCoin?, val isGroup: Boolean, val isNetwork: Boolean)
        val rootRows = remember(expandedGroups) {
            val rows = mutableListOf<RootRow>()
            for (ticker in listOf("usdc", "usdt")) {
                val groupName = groupedTickers[ticker] ?: continue
                val representative = CURATED_SWAP_COINS.first { it.ticker == ticker }
                rows.add(RootRow("group-$ticker", ticker, groupName, representative, null, isGroup = true, isNetwork = false))
                if (expandedGroups.contains(ticker)) {
                    for (coin in CURATED_SWAP_COINS) {
                        if (coin.ticker == ticker) {
                            rows.add(RootRow("network-${coin.ticker}-${coin.network}", coin.ticker, coin.displayName, coin, coin, isGroup = false, isNetwork = true))
                        }
                    }
                }
            }
            for (coin in CURATED_SWAP_COINS) {
                if (groupedTickers[coin.ticker] == null) {
                    rows.add(RootRow("${coin.ticker}-${coin.network}", coin.ticker, coin.displayName, coin, coin, isGroup = false, isNetwork = false))
                }
            }
            rows
        }
        val filteredRows = remember(searchText, rootRows) {
            val query = searchText.trim().lowercase()
            if (query.isEmpty()) {
                rootRows
            } else {
                rootRows.filter { it.displayName.lowercase().contains(query) || it.ticker.lowercase().contains(query) }
            }
        }

        Scaffold(
            containerColor = LocalAppColors.current.background,
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.choose_coin), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel), tint = LocalAppColors.current.textPrimary)
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
                )
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    placeholder = { Text(stringResource(R.string.search_coins), color = LocalAppColors.current.textSecondary) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = LocalAppColors.current.textPrimary,
                        unfocusedTextColor = LocalAppColors.current.textPrimary,
                        focusedBorderColor = KaspaTeal,
                        unfocusedBorderColor = LocalAppColors.current.textSecondary
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filteredRows, key = { it.key }) { row ->
                        val isExpanded = expandedGroups.contains(row.ticker)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    when {
                                        row.isGroup -> {
                                            expandedGroups = if (isExpanded) expandedGroups - row.ticker else expandedGroups + row.ticker
                                        }
                                        row.coin != null -> onPick(row.coin)
                                    }
                                }
                                .padding(
                                    start = if (row.isNetwork) 40.dp else 16.dp,
                                    end = 16.dp,
                                    top = 12.dp,
                                    bottom = 12.dp
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CoinIcon(row.icon, size = 28.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(row.displayName, color = LocalAppColors.current.textPrimary, modifier = Modifier.weight(1f))
                            if (row.isGroup) {
                                if (currentCoin.ticker == row.ticker) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = KaspaTeal, modifier = Modifier.padding(end = 4.dp))
                                }
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    tint = LocalAppColors.current.textSecondary,
                                    modifier = Modifier.rotate(if (isExpanded) 180f else 0f)
                                )
                            } else if (row.coin == currentCoin) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = KaspaTeal)
                            }
                        }
                        HorizontalDivider(color = LocalAppColors.current.divider)
                    }
                }
            }
        }
    }
}
