package com.kachat.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.kachat.app.R
import com.kachat.app.models.PortfolioTransactionEntity
import com.kachat.app.repository.PRICE_UNAVAILABLE_NOTE
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.services.formatHashrate
import com.kachat.app.util.currencySymbolFor
import com.kachat.app.util.formatFiatAmount
import com.kachat.app.util.formatKasAmount
import com.kachat.app.util.formatKasAmountGrouped
import com.kachat.app.viewmodels.PortfolioSummary
import com.kachat.app.viewmodels.PortfolioViewModel
import com.kachat.app.viewmodels.SwapViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * For a single coin's price rather than a fiat total — KAS trades under 1 unit of most tracked
 * currencies, where 2 decimals (CoinGecko rounds 0.0288... to "0.03") loses essentially all the
 * precision that actually distinguishes one day's price from the next. Sub-1 prices get 5
 * decimals instead; anything 1 and up still just gets the usual 2.
 */
private fun formatUsdPrice(value: Double, currencyCode: String): String {
    val sign = if (value < 0) "-" else ""
    val decimals = if (kotlin.math.abs(value) < 1.0) 5 else 2
    return "$sign${currencySymbolFor(currencyCode)}${String.format(Locale.US, "%,.${decimals}f", kotlin.math.abs(value))}"
}

/** "1d"/"7d"/"30d"/"3m"/"1y" — matches the PortfolioViewModel.priceRangeDays values in the range switcher. */
private fun priceRangeLabel(days: Int): String = when (days) {
    1 -> "1d"
    7 -> "7d"
    90 -> "3m"
    365 -> "1y"
    else -> "${days}d"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PortfolioScreen(
    navController: NavController,
    viewModel: PortfolioViewModel = hiltViewModel(),
    swapViewModel: SwapViewModel = hiltViewModel()
) {
    val currentPriceUsd by viewModel.currentPriceUsd.collectAsState()
    val priceChange24h by viewModel.priceChange24h.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val currencyCode by viewModel.currency.collectAsState()
    val portfolios by viewModel.portfolios.collectAsState()
    val activePortfolioId by viewModel.activePortfolioId.collectAsState()
    val cardSummaries by viewModel.cardSummaries.collectAsState()
    val isRefreshing by viewModel.isRefreshingPortfolio.collectAsState()
    val currentHashrate by viewModel.currentHashrate.collectAsState()
    val hashrateHistory by viewModel.hashrateHistory.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshHashrate() }
    // 0 = Data, 1 = Transactions. Swipeable (see HorizontalPager below) as well as tap-to-switch.
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    val tabCoroutineScope = rememberCoroutineScope()

    // Pull-to-refresh (portfolio cards + tab row stay fixed above the pager; only the FAB/toolbar
    // refresh icon was removed, replaced by this gesture). `pullRefreshState.isRefreshing` starts
    // the ViewModel's fetch; the second effect ends the gesture's spinner once that fetch's own
    // `isRefreshingPortfolio` flips back to false, rather than on a fixed delay.
    val pullRefreshState = rememberPullToRefreshState()
    LaunchedEffect(pullRefreshState.isRefreshing) {
        if (pullRefreshState.isRefreshing) {
            viewModel.refreshPrice()
        }
    }
    LaunchedEffect(isRefreshing) {
        if (!isRefreshing && pullRefreshState.isRefreshing) {
            pullRefreshState.endRefresh()
        }
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            Column(modifier = Modifier.background(LocalAppColors.current.background)) {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.portfolio), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold, fontSize = 26.sp) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
                )
                // Total balance under the title, same as every other main page's header
                // (iOS PortfolioView's centered BalanceToolbarLabel).
                BalanceTopBarLabel(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 4.dp)
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            PortfolioPickerHeader(
                portfolios = portfolios,
                activePortfolioId = activePortfolioId,
                cardSummaries = cardSummaries,
                currencyCode = currencyCode,
                onSelect = { viewModel.setActivePortfolio(it) },
                onAdd = { viewModel.addPortfolio(it) },
                onRename = { id, name -> viewModel.renamePortfolio(id, name) },
                onDelete = { viewModel.deletePortfolio(it) },
                onReorder = { viewModel.reorderPortfolios(it) }
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // PullToRefreshContainer translates itself fully above its own position when
                    // idle (verticalOffset 0) via a graphicsLayer translation rather than actually
                    // hiding - with no clip here, that translated-away circle draws outside this
                    // Box's bounds and bleeds into the TabRow above it instead of disappearing.
                    .clipToBounds()
                    .nestedScroll(pullRefreshState.nestedScrollConnection)
            ) {
                // 4.0 (matches iOS): one continuous page - no Data/Transactions tabs, no
                // horizontal paging. Cards first, then the transaction ledger (it keeps its
                // own internal list, sized to roughly a screenful at the end of the scroll).
                val portfolioScreenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Two tappable squares (KAS price | portfolio value) - each opens its own
                    // full-screen chart destination. The inline summary/price/value cards were
                    // replaced by these squares (matches iOS/desktop).
                    PortfolioLauncherSquares(
                        currentPriceUsd = currentPriceUsd,
                        priceChange24h = priceChange24h,
                        summary = summary,
                        currencyCode = currencyCode,
                        onOpenPrice = { navController.navigate("portfolio_price_chart") },
                        onOpenValue = { navController.navigate("portfolio_value_chart") }
                    )
                    // Network hashrate, full width under the squares: it is one series with a
                    // long history, so it reads far better wide than squeezed into a third square.
                    NetworkHashrateCard(
                        hashrate = currentHashrate,
                        history = hashrateHistory,
                        onOpen = { navController.navigate("portfolio_hashrate_chart") }
                    )
                    PortfolioTransactionsContent(
                        viewModel = viewModel,
                        swapViewModel = swapViewModel,
                        modifier = Modifier.height(portfolioScreenHeight * 0.8f)
                    )
                }

                PullToRefreshContainer(
                    state = pullRefreshState,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }
        }
    }
}


/**
 * Full-screen wrapper around [PortfolioTransactionsContent] for the swap-originated deep link
 * (see KaChatApp.kt's "portfolio_transactions?..." route) — arriving here from a completed swap's
 * "Add to Portfolio" action needs its own top bar/back button since it's pushed as a separate
 * destination, unlike the Transactions tab embedded directly in PortfolioScreen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioTransactionsScreen(
    onBack: () -> Unit,
    viewModel: PortfolioViewModel = hiltViewModel(),
    swapViewModel: SwapViewModel = hiltViewModel(),
    prefillType: String? = null,
    prefillAmountKas: Double? = null,
    prefillFiatValue: Double? = null,
    prefillTimestampMillis: Long? = null,
    prefillNotes: String? = null,
    prefillSwapId: String? = null
) {
    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.transactions), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        }
    ) { padding ->
        PortfolioTransactionsContent(
            viewModel = viewModel,
            swapViewModel = swapViewModel,
            // The Scaffold's top bar already says "Transactions" — keep just the action icons.
            showTitle = false,
            prefillType = prefillType,
            prefillAmountKas = prefillAmountKas,
            prefillFiatValue = prefillFiatValue,
            prefillTimestampMillis = prefillTimestampMillis,
            prefillNotes = prefillNotes,
            prefillSwapId = prefillSwapId,
            modifier = Modifier.padding(padding)
        )
    }
}

/**
 * The transaction ledger content — list, CSV import/export, and the add/edit/delete dialog — with
 * no Scaffold/top bar of its own, so it can be embedded either inside [PortfolioTransactionsScreen]
 * (full-screen, swap deep link) or directly as PortfolioScreen's Transactions tab. Shares whichever
 * PortfolioViewModel instance the caller passes in rather than creating its own, so a transaction
 * added/edited/deleted here is immediately reflected in the summary card and charts elsewhere.
 */
@Composable
private fun PortfolioTransactionsContent(
    viewModel: PortfolioViewModel,
    swapViewModel: SwapViewModel,
    showTitle: Boolean = true,
    prefillType: String? = null,
    prefillAmountKas: Double? = null,
    prefillFiatValue: Double? = null,
    prefillTimestampMillis: Long? = null,
    prefillNotes: String? = null,
    prefillSwapId: String? = null,
    modifier: Modifier = Modifier
) {
    val transactions by viewModel.transactions.collectAsState()
    val currentPriceUsd by viewModel.currentPriceUsd.collectAsState()
    val currencyCode by viewModel.currency.collectAsState()
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(prefillType != null) }
    // Only the auto-opened dialog (arriving from a swap) should be prefilled — cleared the moment
    // it's dismissed or saved so a later manual "+" tap opens a genuinely blank form.
    var pendingPrefillSwapId by remember { mutableStateOf(prefillSwapId) }
    var editingTransaction by remember { mutableStateOf<PortfolioTransactionEntity?>(null) }
    var showCsvMenu by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showAddAddressDialog by remember { mutableStateOf(false) }
    var isImportingAddress by remember { mutableStateOf(false) }
    var importProgressText by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    val importCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.importCsv(uri) { result ->
                val message = result.fold(
                    onSuccess = { count -> "Imported $count transaction${if (count == 1) "" else "s"}" },
                    onFailure = { "Import failed. Check the CSV format" }
                )
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // iOS-style section header: "Transactions" title with the add and import/export menus
        // on the same row (replaces the old floating action buttons at the bottom).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showTitle) {
                Text(
                    stringResource(R.string.transactions),
                    color = LocalAppColors.current.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { showAddMenu = true }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.AddCircle,
                        contentDescription = stringResource(R.string.add_transaction),
                        tint = KaspaTeal,
                        modifier = Modifier.size(26.dp)
                    )
                }
                if (showAddMenu) {
                    CenteredOptionsMenu(onDismissRequest = { showAddMenu = false }) {
                        PopupMenuRow(Icons.Default.MonetizationOn, stringResource(R.string.add_transaction)) {
                            showAddMenu = false
                            pendingPrefillSwapId = null
                            showAddDialog = true
                        }
                        HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                        PopupMenuRow(Icons.Default.QrCodeScanner, stringResource(R.string.add_kaspa_address)) {
                            showAddMenu = false
                            showAddAddressDialog = true
                        }
                    }
                }
            }
            Box {
                IconButton(onClick = { showCsvMenu = true }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.ImportExport,
                        contentDescription = "Import or export CSV",
                        tint = KaspaTeal,
                        modifier = Modifier.size(24.dp)
                    )
                }
                if (showCsvMenu) {
                    CenteredOptionsMenu(onDismissRequest = { showCsvMenu = false }) {
                        PopupMenuRow(Icons.Default.FileDownload, stringResource(R.string.export_csv)) {
                            showCsvMenu = false
                            viewModel.exportCsv(
                                onReady = { uri ->
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/csv"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        // clipData carries the URI grant to targets that read
                                        // the stream off the ClipData rather than the extra.
                                        clipData = ClipData.newRawUri("Portfolio CSV", uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    // No share target installed at all throws rather than showing
                                    // an empty chooser; say so instead of crashing the tab.
                                    try {
                                        context.startActivity(Intent.createChooser(intent, "Export Portfolio CSV"))
                                    } catch (e: ActivityNotFoundException) {
                                        Toast.makeText(context, "No app available to share the CSV", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onUnavailable = { message ->
                                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                        HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                        PopupMenuRow(Icons.Default.FileUpload, stringResource(R.string.import_csv)) {
                            showCsvMenu = false
                            importCsvLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*"))
                        }
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            // Horizontal inset is per-item below (not here).
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (transactions.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.no_transactions_yet_tap_to_add),
                        color = LocalAppColors.current.textSecondary,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(vertical = 24.dp),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                // asReversed() is an O(1) view (no per-recomposition list copy like reversed()),
                // and a stable key lets Compose reuse item state / animate list changes.
                items(transactions.asReversed(), key = { it.id }) { tx ->
                    TransactionRow(
                        tx = tx,
                        onClick = { editingTransaction = tx },
                        onDelete = { viewModel.deleteTransaction(tx.id) },
                        currencyCode = currencyCode,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }

    if (showAddDialog || editingTransaction != null) {
        val existing = editingTransaction
        val swapIdForThisDialog = pendingPrefillSwapId
        TransactionDialog(
            existing = existing,
            prefillType = if (existing == null) prefillType else null,
            prefillAmountKas = if (existing == null) prefillAmountKas else null,
            prefillFiatValue = if (existing == null) prefillFiatValue else null,
            prefillTimestampMillis = if (existing == null) prefillTimestampMillis else null,
            prefillNotes = if (existing == null) prefillNotes else null,
            currentPriceUsd = currentPriceUsd,
            currencyCode = currencyCode,
            onDismiss = {
                showAddDialog = false
                editingTransaction = null
                pendingPrefillSwapId = null
            },
            onSave = { type, amountKas, fiatValue, timestampMillis, notes ->
                if (existing != null) {
                    viewModel.updateTransaction(existing.id, type, amountKas, fiatValue, timestampMillis, notes)
                } else {
                    viewModel.addTransaction(type, amountKas, fiatValue, timestampMillis, notes)
                    swapIdForThisDialog?.let { swapViewModel.markSwapAddedToPortfolio(it) }
                }
                showAddDialog = false
                editingTransaction = null
                pendingPrefillSwapId = null
            },
            onDelete = existing?.let { tx ->
                {
                    viewModel.deleteTransaction(tx.id)
                    showAddDialog = false
                    editingTransaction = null
                }
            }
        )
    }

    if (showAddAddressDialog) {
        AddressEntryDialog(
            onDismiss = { showAddAddressDialog = false },
            onConfirm = { address ->
                showAddAddressDialog = false
                isImportingAddress = true
                importProgressText = "Starting…"
                coroutineScope.launch {
                    val result = viewModel.importAddress(address) { text -> importProgressText = text }
                    isImportingAddress = false
                    val message = result.fold(
                        onSuccess = { imported ->
                            val base = "Imported ${imported.importedCount} transaction${if (imported.importedCount == 1) "" else "s"}"
                            if (imported.pendingPriceCount > 0) {
                                "$base. Prices are filling in the background."
                            } else {
                                base
                            }
                        },
                        onFailure = { it.message ?: "Import failed." }
                    )
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            },
            resolveKns = viewModel::resolveKnsDomain
        )
    }

    if (isImportingAddress) {
        AlertDialog(
            onDismissRequest = {},
            containerColor = LocalAppColors.current.surface,
            title = { Text("Add Kaspa Address", color = LocalAppColors.current.textPrimary) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(color = KaspaTeal, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(importProgressText, color = LocalAppColors.current.textSecondary)
                }
            },
            confirmButton = {}
        )
    }
}

/** "kaspa:qrabc...wxyz" — enough of each end to recognize the address without wrapping the dialog. */
private fun shortenKaspaAddress(address: String): String =
    if (address.length <= 24) address else "${address.take(14)}...${address.takeLast(6)}"

@Composable
private fun AddressEntryDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    resolveKns: suspend (String) -> String?
) {
    var addressText by remember { mutableStateOf("") }
    var showScanner by remember { mutableStateOf(false) }
    var isResolvingKns by remember { mutableStateOf(false) }
    var knsResolvedAddress by remember { mutableStateOf<String?>(null) }
    var knsNotFound by remember { mutableStateOf(false) }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    // Debounced live KNS resolution — the same 500ms pattern the send flows' address fields use
    // (see ColdStorageScreens' recipient field): restart on every keystroke, resolve only input
    // that looks like a domain rather than a raw address.
    LaunchedEffect(addressText) {
        knsResolvedAddress = null
        knsNotFound = false
        val input = addressText.trim()
        if (input.isEmpty() || input.startsWith("kaspa:", ignoreCase = true) ||
            input.startsWith("kaspatest:", ignoreCase = true) ||
            !com.kachat.app.services.KnsService.looksLikeDomain(input)
        ) {
            isResolvingKns = false
            return@LaunchedEffect
        }
        isResolvingKns = true
        kotlinx.coroutines.delay(500)
        val resolved = resolveKns(input)
        isResolvingKns = false
        if (resolved != null) knsResolvedAddress = resolved else knsNotFound = true
    }

    val isRawValid = remember(addressText) {
        try {
            com.kachat.app.util.KaspaAddress.getScriptPublicKey(addressText.trim()).isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }
    // The address actually imported — a resolved KNS domain wins over the raw text.
    val effectiveAddress = knsResolvedAddress ?: addressText.trim()
    val isValid = knsResolvedAddress != null || isRawValid

    if (showScanner) {
        // Full-screen (usePlatformDefaultWidth = false) so the camera overlay isn't squeezed
        // into a dialog-width box — reuses the same scanner composable as the send flows.
        Dialog(
            onDismissRequest = { showScanner = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            QrScannerOverlay(
                onScanned = { scanned ->
                    addressText = scanned.trim()
                    showScanner = false
                },
                onDismiss = { showScanner = false }
            )
        }
        return
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(color = LocalAppColors.current.surface, shape = RoundedCornerShape(20.dp)) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Add Kaspa Address", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, "Close", tint = LocalAppColors.current.textSecondary)
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = addressText,
                    onValueChange = { addressText = it },
                    placeholder = { Text("kaspa:qr... or name.kas") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (addressText.trim().isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    when {
                        isResolvingKns -> Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(14.dp), color = KaspaTeal, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.resolving_domain), color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                        }
                        knsResolvedAddress != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CD964), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Resolves to ${shortenKaspaAddress(knsResolvedAddress ?: "")}", color = Color(0xFF4CD964), fontSize = 12.sp)
                        }
                        // Quiet by design — an unfinished domain isn't an error worth shouting about.
                        knsNotFound -> Text("Domain not found", color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                        isRawValid -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CD964), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.valid_address), color = Color(0xFF4CD964), fontSize = 12.sp)
                        }
                        else -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Cancel, null, tint = Color(0xFFFF3B30), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.invalid_address_format), color = Color(0xFFFF3B30), fontSize = 12.sp)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { clipboardManager.getText()?.text?.let { addressText = it.trim() } }) {
                        Icon(Icons.Default.ContentPaste, null, tint = KaspaTeal, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.paste_from_clipboard), color = KaspaTeal, fontSize = 12.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showScanner = true }) {
                        Icon(Icons.Default.QrCodeScanner, null, tint = KaspaTeal, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.scan_qr_code), color = KaspaTeal, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Every received transaction on this address becomes a buy, every sent transaction becomes a sell, priced at that day's historical KAS price. Re-adding the same address later only imports transactions found since the last import.",
                    color = LocalAppColors.current.textSecondary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onConfirm(effectiveAddress) },
                    enabled = isValid,
                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, disabledContainerColor = LocalAppColors.current.surfaceVariant),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("Import", color = if (isValid) Color.Black else Color.Gray, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PortfolioSummaryCard(
    summary: PortfolioSummary,
    currentPriceUsd: Double?,
    priceChange24h: Double? = null,
    scrubbedPrice: Pair<Long, Double>? = null,
    currencyCode: String
) {
    val plColor = if (summary.totalPL >= 0) Color(0xFF4CD964) else Color(0xFFFF3B30)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(LocalAppColors.current.surface)
            .padding(14.dp)
    ) {
        Text(
            if (scrubbedPrice != null) formatDateTime(scrubbedPrice.first) else "KAS Price",
            color = LocalAppColors.current.textSecondary,
            fontSize = 12.sp
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = when {
                    scrubbedPrice != null -> formatUsdPrice(scrubbedPrice.second, currencyCode)
                    currentPriceUsd != null -> formatUsdPrice(currentPriceUsd, currencyCode)
                    else -> "—"
                },
                color = LocalAppColors.current.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp
            )
            // Only shown at rest — a 24h change badge next to a scrubbed historical price would
            // be misleading (it's always "now vs 24h ago", not relative to the scrubbed point).
            if (scrubbedPrice == null && priceChange24h != null) {
                Spacer(Modifier.width(8.dp))
                val isPositive = priceChange24h >= 0
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 3.dp)) {
                    Icon(
                        if (isPositive) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                        contentDescription = null,
                        tint = if (isPositive) Color(0xFF4CD964) else Color(0xFFFF3B30),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        "${String.format(Locale.US, "%.2f", kotlin.math.abs(priceChange24h))}%",
                        color = if (isPositive) Color(0xFF4CD964) else Color(0xFFFF3B30),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(stringResource(R.string.holdings), color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                Text("${formatKasAmount(summary.holdingsKas)} KAS", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.current_value), color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                Text(formatFiatAmount(summary.currentValue, currencyCode), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(10.dp))
        androidx.compose.material3.HorizontalDivider(color = LocalAppColors.current.divider)
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(stringResource(R.string.total_invested), color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                Text(formatFiatAmount(summary.totalInvested, currencyCode), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.total_p_l), color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (summary.totalPL >= 0) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                        contentDescription = null,
                        tint = plColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${formatFiatAmount(summary.totalPL, currencyCode)} (${String.format(Locale.US, "%.1f", summary.totalPLPercent)}%)",
                        color = plColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/** Compact sparkline — just enough to show the price trend at a glance above the summary card. */
@Composable
private fun PriceChartCard(
    priceHistory: List<Pair<Long, Double>>,
    onScrub: (Pair<Long, Double>?) -> Unit,
    selectedRangeDays: Int,
    onRangeSelected: (Int) -> Unit
) {
    var canvasWidthPx by remember { mutableStateOf(0) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(LocalAppColors.current.surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.clickable {
                // Cycles 1 -> 7 -> 30 -> 90 -> 365 -> 1 day...
                val nextDays = when (selectedRangeDays) {
                    1 -> 7
                    7 -> 30
                    30 -> 90
                    90 -> 365
                    else -> 1
                }
                onRangeSelected(nextDays)
            },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Price (${priceRangeLabel(selectedRangeDays)})",
                color = LocalAppColors.current.textSecondary,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.width(12.dp))
        val minPrice = priceHistory.minOf { it.second }
        val maxPrice = priceHistory.maxOf { it.second }
        val range = (maxPrice - minPrice).takeIf { it > 0 } ?: 1.0
        val textSecondaryColor = LocalAppColors.current.textSecondary
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(36.dp)
                .onSizeChanged { canvasWidthPx = it.width }
                .pointerInput(priceHistory) {
                    fun scrubAt(x: Float) {
                        if (canvasWidthPx <= 0) return
                        val index = ((x / canvasWidthPx) * (priceHistory.size - 1)).roundToInt().coerceIn(0, priceHistory.size - 1)
                        selectedIndex = index
                        onScrub(priceHistory[index])
                    }
                    detectDragGestures(
                        onDragStart = { offset -> scrubAt(offset.x) },
                        onDrag = { change, _ -> scrubAt(change.position.x); change.consume() },
                        onDragEnd = { selectedIndex = null; onScrub(null) },
                        onDragCancel = { selectedIndex = null; onScrub(null) }
                    )
                }
        ) {
            val stepX = size.width / (priceHistory.size - 1).coerceAtLeast(1)
            val path = Path()
            priceHistory.forEachIndexed { index, (_, price) ->
                val x = index * stepX
                val y = size.height - ((price - minPrice) / range * size.height).toFloat()
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path = path, color = KaspaTeal, style = Stroke(width = 3f))

            selectedIndex?.let { index ->
                val x = index * stepX
                val y = size.height - ((priceHistory[index].second - minPrice) / range * size.height).toFloat()
                drawLine(color = textSecondaryColor, start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = 2f)
                drawCircle(color = KaspaTeal, radius = 4f, center = Offset(x, y))
            }
        }
    }
}

/**
 * Holdings' USD value over time, not price — touch and drag horizontally to scrub through
 * history; the header above the chart swaps to show the value/date under your finger while
 * dragging, and reverts to the latest value on release.
 */
@Composable
private fun PortfolioValueChartCard(valueHistory: List<Pair<Long, Double>>, currencyCode: String) {
    var touchX by remember { mutableStateOf<Float?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val minValue = valueHistory.minOf { it.second }
    val maxValue = valueHistory.maxOf { it.second }
    val range = (maxValue - minValue).takeIf { it > 0 } ?: 1.0

    val selectedIndex = touchX?.let { x ->
        if (canvasSize.width <= 0) null
        else ((x / canvasSize.width) * (valueHistory.size - 1)).roundToInt().coerceIn(0, valueHistory.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(LocalAppColors.current.surface)
            .padding(14.dp)
    ) {
        val (headerLabel, headerTimestamp, headerValue) = if (selectedIndex != null) {
            val (ts, value) = valueHistory[selectedIndex]
            Triple("Value on ${formatDateTime(ts)}", ts, value)
        } else {
            Triple("Value Over Time", valueHistory.last().first, valueHistory.last().second)
        }
        Text(headerLabel, color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
        Text(formatFiatAmount(headerValue, currencyCode), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.height(6.dp))
        val textSecondaryColor = LocalAppColors.current.textSecondary
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp)
                .onSizeChanged { canvasSize = it }
                .pointerInput(valueHistory) {
                    detectDragGestures(
                        onDragStart = { offset -> touchX = offset.x },
                        onDrag = { change, _ -> touchX = change.position.x; change.consume() },
                        onDragEnd = { touchX = null },
                        onDragCancel = { touchX = null }
                    )
                }
        ) {
            val stepX = size.width / (valueHistory.size - 1).coerceAtLeast(1)
            val path = Path()
            valueHistory.forEachIndexed { index, (_, value) ->
                val x = index * stepX
                val y = size.height - ((value - minValue) / range * size.height).toFloat()
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path = path, color = KaspaTeal, style = Stroke(width = 4f))

            if (selectedIndex != null) {
                val x = selectedIndex * stepX
                val y = size.height - ((valueHistory[selectedIndex].second - minValue) / range * size.height).toFloat()
                drawLine(color = textSecondaryColor, start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = 2f)
                drawCircle(color = KaspaTeal, radius = 6f, center = Offset(x, y))
            }
        }
    }
}

private fun formatAxisHour(millis: Long): String = SimpleDateFormat("h a", Locale.getDefault()).format(Date(millis))
private fun formatAxisDate(millis: Long): String = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(millis))

// MARK: two launcher squares (KAS price | portfolio value)

@Composable
private fun PortfolioLauncherSquares(
    currentPriceUsd: Double?,
    priceChange24h: Double?,
    summary: PortfolioSummary,
    currencyCode: String,
    onOpenPrice: () -> Unit,
    onOpenValue: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LauncherSquare(
            modifier = Modifier.weight(1f).clickable { onOpenPrice() },
            title = "Kaspa",
            value = currentPriceUsd?.let { formatUsdPrice(it, currencyCode) } ?: "—",
            changePercent = priceChange24h,
            headerIcon = {
                Image(
                    painter = painterResource(R.drawable.ic_kaspa_logo),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp).clip(CircleShape)
                )
            }
        )
        LauncherSquare(
            modifier = Modifier.weight(1f).clickable { onOpenValue() },
            title = "Value",
            value = formatFiatAmount(summary.currentValue, currencyCode),
            changePercent = summary.totalPLPercent,
            headerIcon = {
                Icon(
                    Icons.Default.TrendingUp,
                    contentDescription = null,
                    tint = LocalAppColors.current.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        )
    }
}

@Composable
private fun LauncherSquare(
    modifier: Modifier,
    title: String,
    value: String,
    changePercent: Double?,
    headerIcon: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .height(122.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(LocalAppColors.current.surface)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            headerIcon()
            Spacer(Modifier.width(6.dp))
            Text(
                title,
                color = LocalAppColors.current.textSecondary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = LocalAppColors.current.textSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            value,
            color = LocalAppColors.current.textPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            maxLines = 1
        )
        if (changePercent != null) {
            val positive = changePercent >= 0
            val color = if (positive) Color(0xFF4CD964) else Color(0xFFFF3B30)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (positive) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    "${String.format(Locale.US, "%.2f", kotlin.math.abs(changePercent))}%",
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
            }
        }
    }
}

// MARK: shared big chart (area fill + gridlines + x-axis labels + scrub) and range selector

@Composable
private fun PortfolioBigChart(
    points: List<Pair<Long, Double>>,
    lineColor: Color,
    onScrub: (Pair<Long, Double>?) -> Unit
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    val minV = points.minOf { it.second }
    val maxV = points.maxOf { it.second }
    val range = (maxV - minV).takeIf { it > 0 } ?: 1.0
    val gridColor = LocalAppColors.current.divider
    val cursorColor = LocalAppColors.current.textSecondary

    Column(modifier = Modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .onSizeChanged { canvasSize = it }
                .pointerInput(points) {
                    fun scrubAt(x: Float) {
                        if (canvasSize.width <= 0) return
                        val idx = ((x / canvasSize.width) * (points.size - 1)).roundToInt().coerceIn(0, points.size - 1)
                        selectedIndex = idx
                        onScrub(points[idx])
                    }
                    detectDragGestures(
                        onDragStart = { scrubAt(it.x) },
                        onDrag = { change, _ -> scrubAt(change.position.x); change.consume() },
                        onDragEnd = { selectedIndex = null; onScrub(null) },
                        onDragCancel = { selectedIndex = null; onScrub(null) }
                    )
                }
        ) {
            val padTop = 10f
            val padBottom = 10f
            val usableH = size.height - padTop - padBottom
            val stepX = size.width / (points.size - 1).coerceAtLeast(1)
            fun yFor(v: Double): Float = padTop + (1f - ((v - minV) / range).toFloat()) * usableH

            val gridCount = 4
            for (i in 0..gridCount) {
                val y = padTop + usableH * i / gridCount
                drawLine(color = gridColor, start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = 1f, alpha = 0.5f)
            }

            val linePath = Path()
            points.forEachIndexed { index, (_, v) ->
                val x = index * stepX
                val y = yFor(v)
                if (index == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
            }
            val areaPath = Path().apply {
                addPath(linePath)
                lineTo((points.size - 1) * stepX, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(
                path = areaPath,
                brush = Brush.verticalGradient(
                    colors = listOf(lineColor.copy(alpha = 0.28f), lineColor.copy(alpha = 0.02f)),
                    startY = 0f,
                    endY = size.height
                )
            )
            drawPath(path = linePath, color = lineColor, style = Stroke(width = 3f))

            selectedIndex?.let { idx ->
                val x = idx * stepX
                val y = yFor(points[idx].second)
                drawLine(color = cursorColor, start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = 2f)
                drawCircle(color = lineColor, radius = 6f, center = Offset(x, y))
            }
        }
        Spacer(Modifier.height(6.dp))
        // X-axis labels: hours for an intraday (<= ~2d) span, else month/day, so 1D doesn't crowd.
        val intraday = (points.last().first - points.first().first) <= 2L * 24 * 60 * 60 * 1000
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val labelCount = 4
            for (i in 0 until labelCount) {
                val idx = ((i.toFloat() / (labelCount - 1)) * (points.size - 1)).roundToInt().coerceIn(0, points.size - 1)
                val ts = points[idx].first
                Text(
                    text = if (intraday) formatAxisHour(ts) else formatAxisDate(ts),
                    color = LocalAppColors.current.textSecondary,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun PortfolioRangeSelector(selectedDays: Int, onSelect: (Int) -> Unit) {
    val ranges = listOf(1 to "1D", 7 to "1W", 30 to "1M", 90 to "3M", 365 to "1Y")
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ranges.forEach { (days, label) ->
            val active = days == selectedDays
            Text(
                text = label,
                color = if (active) KaspaTeal else LocalAppColors.current.textSecondary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) KaspaTeal.copy(alpha = 0.15f) else Color.Transparent)
                    .clickable { onSelect(days) }
                    .padding(vertical = 8.dp)
            )
        }
    }
}

// MARK: full-screen chart destinations (registered in KaChatApp's NavHost)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioPriceChartScreen(
    navController: NavController,
    viewModel: PortfolioViewModel = hiltViewModel()
) {
    val currentPriceUsd by viewModel.currentPriceUsd.collectAsState()
    val priceChange24h by viewModel.priceChange24h.collectAsState()
    val priceHistory by viewModel.priceHistory.collectAsState()
    val priceRangeDays by viewModel.priceRangeDays.collectAsState()
    val currencyCode by viewModel.currency.collectAsState()
    var scrubbed by remember { mutableStateOf<Pair<Long, Double>?>(null) }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("KAS Price", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        }
    ) { padding ->
        val pullRefreshState = rememberPullToRefreshState()
        val isRefreshing by viewModel.isRefreshingPortfolio.collectAsState()
        LaunchedEffect(pullRefreshState.isRefreshing) { if (pullRefreshState.isRefreshing) viewModel.refreshPrice() }
        LaunchedEffect(isRefreshing) { if (!isRefreshing && pullRefreshState.isRefreshing) pullRefreshState.endRefresh() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .clipToBounds()
                .nestedScroll(pullRefreshState.nestedScrollConnection)
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header: logo + name stay put while scrubbing; only the date + price change.
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.ic_kaspa_logo),
                        contentDescription = null,
                        modifier = Modifier.size(30.dp).clip(CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Kaspa", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                }
                scrubbed?.let {
                    Text(formatDateTime(it.first), color = LocalAppColors.current.textSecondary, fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = when {
                            scrubbed != null -> formatUsdPrice(scrubbed!!.second, currencyCode)
                            currentPriceUsd != null -> formatUsdPrice(currentPriceUsd!!, currencyCode)
                            else -> "—"
                        },
                        color = LocalAppColors.current.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 32.sp
                    )
                    if (scrubbed == null && priceChange24h != null) {
                        Spacer(Modifier.width(8.dp))
                        val positive = priceChange24h!! >= 0
                        val color = if (positive) Color(0xFF4CD964) else Color(0xFFFF3B30)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                            Icon(
                                if (positive) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                                contentDescription = null,
                                tint = color,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                "${String.format(Locale.US, "%.2f", kotlin.math.abs(priceChange24h!!))}% (24h)",
                                color = color,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }

            if (priceHistory.size >= 2) {
                PortfolioBigChart(points = priceHistory, lineColor = KaspaTeal, onScrub = { scrubbed = it })
            } else {
                Box(modifier = Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    Text("Loading…", color = LocalAppColors.current.textSecondary)
                }
            }

            PortfolioRangeSelector(selectedDays = priceRangeDays, onSelect = { scrubbed = null; viewModel.setPriceRangeDays(it) })

            KasConverterCard(price = currentPriceUsd, currencyCode = currencyCode)
        }
            PullToRefreshContainer(state = pullRefreshState, modifier = Modifier.align(Alignment.TopCenter))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioValueChartScreen(
    navController: NavController,
    viewModel: PortfolioViewModel = hiltViewModel()
) {
    val valueHistory by viewModel.valueHistory.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val priceRangeDays by viewModel.priceRangeDays.collectAsState()
    val currencyCode by viewModel.currency.collectAsState()
    var scrubbed by remember { mutableStateOf<Pair<Long, Double>?>(null) }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Value Over Time", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        }
    ) { padding ->
        val pullRefreshState = rememberPullToRefreshState()
        val isRefreshing by viewModel.isRefreshingPortfolio.collectAsState()
        LaunchedEffect(pullRefreshState.isRefreshing) { if (pullRefreshState.isRefreshing) viewModel.refreshPrice() }
        LaunchedEffect(isRefreshing) { if (!isRefreshing && pullRefreshState.isRefreshing) pullRefreshState.endRefresh() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .clipToBounds()
                .nestedScroll(pullRefreshState.nestedScrollConnection)
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column {
                Text("Portfolio Value", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                scrubbed?.let {
                    Text(formatDateTime(it.first), color = LocalAppColors.current.textSecondary, fontSize = 13.sp)
                }
                Text(
                    formatFiatAmount(scrubbed?.second ?: summary.currentValue, currencyCode),
                    color = LocalAppColors.current.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp
                )
            }

            if (valueHistory.size >= 2) {
                PortfolioBigChart(points = valueHistory, lineColor = KaspaTeal, onScrub = { scrubbed = it })
            } else {
                Box(modifier = Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Not enough history yet - check back after a few days of activity.",
                        color = LocalAppColors.current.textSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }

            PortfolioRangeSelector(selectedDays = priceRangeDays, onSelect = { scrubbed = null; viewModel.setPriceRangeDays(it) })

            PortfolioValueStatsCard(summary = summary, currencyCode = currencyCode)
        }
            PullToRefreshContainer(state = pullRefreshState, modifier = Modifier.align(Alignment.TopCenter))
        }
    }
}

@Composable
private fun PortfolioValueStatsCard(summary: PortfolioSummary, currencyCode: String) {
    val plColor = if (summary.totalPL >= 0) Color(0xFF4CD964) else Color(0xFFFF3B30)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(LocalAppColors.current.surface)
            .padding(14.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(stringResource(R.string.holdings), color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                Text("${formatKasAmountGrouped(summary.holdingsKas)} KAS", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.current_value), color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                Text(formatFiatAmount(summary.currentValue, currencyCode), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = LocalAppColors.current.divider)
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(stringResource(R.string.total_invested), color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                Text(formatFiatAmount(summary.totalInvested, currencyCode), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.total_p_l), color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                Text(
                    "${formatFiatAmount(summary.totalPL, currencyCode)} (${String.format(Locale.US, "%.1f", summary.totalPLPercent)}%)",
                    color = plColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        summary.averageBuyPriceUsd?.let { avg ->
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = LocalAppColors.current.divider)
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Avg. Buy Price", color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                    Text(formatUsdPrice(avg, currencyCode), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun TransactionRow(
    tx: PortfolioTransactionEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    currencyCode: String,
    modifier: Modifier = Modifier
) {
    val isBuy = tx.type == "buy"
    val amountKas = tx.amountSompi / 100_000_000.0
    val needsPrice = tx.notes == PRICE_UNAVAILABLE_NOTE
    val dateStr = remember(tx.timestampMillis) {
        SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(tx.timestampMillis))
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LocalAppColors.current.surface)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (isBuy) Color(0xFF4CD964).copy(alpha = 0.15f) else Color(0xFFFF3B30).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isBuy) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                    contentDescription = null,
                    tint = if (isBuy) Color(0xFF4CD964) else Color(0xFFFF3B30),
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isBuy) "Buy" else "Sell", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
                    if (needsPrice) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = stringResource(R.string.price_needed),
                            tint = Color(0xFFFFC107),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Text(dateStr, color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${formatKasAmountGrouped(amountKas)} KAS", color = LocalAppColors.current.textPrimary)
            Text(formatFiatAmount(tx.fiatValue, currencyCode), color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onDelete, modifier = Modifier.size(20.dp)) {
            Icon(Icons.Default.Delete, "Delete", tint = Color(0xFFFF3B30), modifier = Modifier.size(18.dp))
        }
    }
}

private fun formatDateTime(millis: Long): String =
    SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.US).format(Date(millis))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionDialog(
    existing: PortfolioTransactionEntity?,
    currentPriceUsd: Double?,
    currencyCode: String,
    onDismiss: () -> Unit,
    onSave: (type: String, amountKas: Double, fiatValue: Double, timestampMillis: Long, notes: String?) -> Unit,
    onDelete: (() -> Unit)? = null,
    // Pre-populates a brand-new (existing == null) form — e.g. arriving from a completed swap
    // with its amounts already known — without switching the dialog into edit mode.
    prefillType: String? = null,
    prefillAmountKas: Double? = null,
    prefillFiatValue: Double? = null,
    prefillTimestampMillis: Long? = null,
    prefillNotes: String? = null
) {
    var isBuy by remember { mutableStateOf(existing?.let { it.type == "buy" } ?: prefillType?.let { it == "buy" } ?: true) }
    var quantityText by remember {
        mutableStateOf(
            existing?.let { formatKasAmount(it.amountSompi / 100_000_000.0) }
                ?: prefillAmountKas?.let { formatKasAmount(it) }
                ?: ""
        )
    }
    // Editing: derive price-per-coin from the stored total rather than the live price, so
    // reopening an old entry shows what was actually paid, not today's price. Fee isn't stored
    // separately (see PortfolioRepository), so it isn't recoverable into its own field here —
    // the derived price-per-coin already nets it out, and the total still matches exactly
    // unless the user changes quantity/price/fee themselves. Same math for a swap prefill, using
    // its known KAS amount and USD total in place of a stored entity.
    var priceText by remember {
        mutableStateOf(
            existing?.let {
                val kas = it.amountSompi / 100_000_000.0
                if (kas > 0) String.format(Locale.US, "%.8f", it.fiatValue / kas).trimEnd('0').trimEnd('.') else ""
            } ?: if (prefillAmountKas != null && prefillFiatValue != null && prefillAmountKas > 0) {
                String.format(Locale.US, "%.8f", prefillFiatValue / prefillAmountKas).trimEnd('0').trimEnd('.')
            } else {
                currentPriceUsd?.let { String.format(Locale.US, "%.8f", it).trimEnd('0').trimEnd('.') } ?: ""
            }
        )
    }
    var feeText by remember { mutableStateOf("") }
    var notesText by remember { mutableStateOf(existing?.notes ?: prefillNotes ?: "") }
    var timestampMillis by remember { mutableStateOf(existing?.timestampMillis ?: prefillTimestampMillis ?: System.currentTimeMillis()) }
    var showDatePicker by remember { mutableStateOf(false) }

    val quantity = quantityText.toDoubleOrNull()
    val pricePerCoin = priceText.toDoubleOrNull()
    val fee = feeText.toDoubleOrNull() ?: 0.0
    val total = if (quantity != null && pricePerCoin != null) {
        val base = quantity * pricePerCoin
        if (isBuy) base + fee else base - fee
    } else null
    val isValid = quantity != null && quantity > 0 && pricePerCoin != null && pricePerCoin > 0

    Dialog(onDismissRequest = onDismiss) {
        Surface(color = LocalAppColors.current.surface, shape = RoundedCornerShape(20.dp)) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (existing != null) "Edit Transaction" else "Add Transaction", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, "Close", tint = LocalAppColors.current.textSecondary)
                    }
                }
                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Buy" to true, "Sell" to false).forEach { (label, value) ->
                        Surface(
                            color = if (isBuy == value) KaspaTeal else LocalAppColors.current.surfaceVariant,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.weight(1f).clickable { isBuy = value }
                        ) {
                            Text(
                                label,
                                color = if (isBuy == value) Color.Black else Color.White,
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                // Static — this tracker is KAS-only (see PortfolioTransactionEntity's doc comment).
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LocalAppColors.current.surfaceVariant).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(28.dp).clip(CircleShape).background(KaspaTeal.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(painterResource(R.drawable.ic_kaspa_logo), null, tint = Color.Unspecified, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.kaspa), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.kas_2), color = LocalAppColors.current.textSecondary)
                }
                Spacer(Modifier.height(12.dp))

                // Full width, stacked rather than side-by-side — a half-width field cut off KAS
                // prices with several decimal digits (e.g. "0.02874099"), which didn't fit next
                // to Quantity in a shared row and just clipped at the field's edge.
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it },
                    label = { Text(stringResource(R.string.quantity)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = it },
                    label = { Text(stringResource(R.string.price_per_coin)) },
                    leadingIcon = { Text(currencySymbolFor(currencyCode), color = LocalAppColors.current.textSecondary) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        color = LocalAppColors.current.surfaceVariant,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.clickable { showDatePicker = true }
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CalendarToday, null, tint = KaspaTeal, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(formatDateTime(timestampMillis), color = LocalAppColors.current.textPrimary, fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = feeText,
                    onValueChange = { feeText = it },
                    label = { Text(stringResource(R.string.fee_usd_optional)) },
                    leadingIcon = { Text(currencySymbolFor(currencyCode), color = LocalAppColors.current.textSecondary) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = notesText,
                    onValueChange = { notesText = it },
                    label = { Text(stringResource(R.string.notes_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(LocalAppColors.current.surfaceVariant)
                        .padding(16.dp)
                ) {
                    Text(if (isBuy) "Total Spent" else "Total Received", color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                    Text(
                        text = if (total != null) formatFiatAmount(total, currencyCode) else "${currencySymbolFor(currencyCode)}0",
                        color = LocalAppColors.current.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                }
                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (isValid) onSave(if (isBuy) "buy" else "sell", quantity!!, total ?: 0.0, timestampMillis, notesText.ifBlank { null })
                    },
                    enabled = isValid,
                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, disabledContainerColor = LocalAppColors.current.surfaceVariant),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text(
                        if (existing != null) "Save Changes" else "Add Transaction",
                        color = if (isValid) Color.Black else Color.Gray,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (onDelete != null) {
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onDelete,
                        colors = ButtonDefaults.buttonColors(containerColor = LocalAppColors.current.surfaceVariant),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text(stringResource(R.string.delete_transaction), color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        DateTimePickerFlow(
            initialMillis = timestampMillis,
            onDismiss = { showDatePicker = false },
            onConfirm = { millis ->
                timestampMillis = millis
                showDatePicker = false
            }
        )
    }
}

/** Date picker first, then a time picker, merged into one epoch-millis value in the local timezone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimePickerFlow(
    initialMillis: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    var pickingTime by remember { mutableStateOf(false) }
    var pickedDateMillis by remember { mutableStateOf(initialMillis) }
    val dateState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    val initialCal = remember(initialMillis) { Calendar.getInstance().apply { timeInMillis = initialMillis } }
    val timeState = rememberTimePickerState(
        initialHour = initialCal.get(Calendar.HOUR_OF_DAY),
        initialMinute = initialCal.get(Calendar.MINUTE)
    )

    if (!pickingTime) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    pickedDateMillis = dateState.selectedDateMillis ?: initialMillis
                    pickingTime = true
                }) { Text(stringResource(R.string.next)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
        ) {
            DatePicker(state = dateState)
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.select_time), color = LocalAppColors.current.textPrimary) },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    // DatePicker's selectedDateMillis is UTC midnight of the chosen day — pull the
                    // year/month/day out in UTC, then build the final instant in the local timezone
                    // with the picked time-of-day, so this doesn't silently shift a day depending on
                    // the device's offset from UTC.
                    val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = pickedDateMillis }
                    val merged = Calendar.getInstance().apply {
                        set(
                            utcCal.get(Calendar.YEAR),
                            utcCal.get(Calendar.MONTH),
                            utcCal.get(Calendar.DAY_OF_MONTH),
                            timeState.hour,
                            timeState.minute,
                            0
                        )
                        set(Calendar.MILLISECOND, 0)
                    }
                    onConfirm(merged.timeInMillis)
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

// MARK: KAS <-> fiat converter (replaces the old "About Kaspa" blurb on the price chart screen)

/**
 * Two-way converter, seeded at 1 KAS.
 *
 * Only the field the user is typing in drives the other. Compose helps here - a programmatic
 * value change does not fire `onValueChange` - but [editing] is still tracked so a price refresh
 * or a currency switch moves the derived side rather than overwriting what was typed.
 */
@Composable
private fun KasConverterCard(price: Double?, currencyCode: String) {
    val colors = LocalAppColors.current
    var kasText by remember { mutableStateOf("1") }
    var fiatText by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf("kas") }

    fun recompute(from: String) {
        val rate = price?.takeIf { it > 0 } ?: return
        if (from == "kas") {
            val kas = parseAmount(kasText)
            fiatText = kas?.let { String.format(Locale.US, "%.2f", it * rate) } ?: ""
        } else {
            val fiat = parseAmount(fiatText)
            kasText = fiat?.let { String.format(Locale.US, "%.4f", it / rate) } ?: ""
        }
    }

    // Seeds the first value, and keeps the derived side honest when the price or currency moves.
    LaunchedEffect(price, currencyCode) { recompute(editing) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Converter", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)

        OutlinedTextField(
            value = kasText,
            onValueChange = { kasText = it; editing = "kas"; recompute("kas") },
            label = { Text("KAS", color = colors.textSecondary) },
            trailingIcon = { Text("KAS", color = colors.textSecondary, fontSize = 13.sp) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = fiatText,
            onValueChange = { fiatText = it; editing = "fiat"; recompute("fiat") },
            label = { Text(currencyCode.uppercase(Locale.US), color = colors.textSecondary) },
            trailingIcon = { Text(currencySymbolFor(currencyCode), color = colors.textSecondary, fontSize = 13.sp) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = if (price != null) "1 KAS = ${formatUsdPrice(price, currencyCode)}" else "Waiting for a price...",
            color = colors.textSecondary,
            fontSize = 12.sp
        )
    }
}

/**
 * Accepts either separator: a decimal keypad emits the device locale's, which is a comma in much
 * of the world, and parsing that as an integer silently multiplied the amount.
 */
private fun parseAmount(text: String): Double? {
    val normalized = text.replace(',', '.')
    if (normalized.isBlank()) return null
    return normalized.toDoubleOrNull()
}

// MARK: network hashrate card + chart

/** A tiny line, no axes or labels - just the shape of the recent window. */
@Composable
private fun HashrateSparkline(points: List<Pair<Long, Double>>, modifier: Modifier = Modifier) {
    if (points.size < 2) return
    val minV = points.minOf { it.second }
    val maxV = points.maxOf { it.second }
    val range = (maxV - minV).takeIf { it > 0 } ?: 1.0
    Canvas(modifier = modifier) {
        val stepX = size.width / (points.size - 1)
        val path = Path()
        points.forEachIndexed { index, (_, value) ->
            val x = stepX * index
            val y = size.height * (1f - ((value - minV) / range).toFloat())
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = KaspaTeal, style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun NetworkHashrateCard(
    hashrate: Double?,
    history: List<Pair<Long, Double>>,
    onOpen: () -> Unit
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .clickable { onOpen() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Bolt, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Network Hashrate", color = colors.textSecondary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(
                text = hashrate?.let { formatHashrate(it) } ?: "—",
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                maxLines = 1
            )
        }
        // A sparkline of the recent window, so the card says which way it is going without the
        // user having to open it.
        if (history.size >= 2) {
            HashrateSparkline(
                points = history.takeLast(90),
                modifier = Modifier.width(96.dp).height(34.dp)
            )
            Spacer(Modifier.width(8.dp))
        }
        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = colors.textSecondary)
    }
}

/**
 * The full hashrate history, with a range control of its own.
 *
 * "All" is a real option here in a way it is not for price: the series starts at effectively zero
 * in 2021 and the whole shape of the network's growth is the interesting part.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioHashrateChartScreen(
    navController: NavController,
    viewModel: PortfolioViewModel = hiltViewModel()
) {
    val colors = LocalAppColors.current
    val history by viewModel.hashrateHistory.collectAsState()
    val current by viewModel.currentHashrate.collectAsState()
    var scrubbed by remember { mutableStateOf<Pair<Long, Double>?>(null) }
    var rangeDays by remember { mutableStateOf(90) }

    LaunchedEffect(Unit) { viewModel.refreshHashrate() }

    val visible = remember(history, rangeDays) {
        if (rangeDays <= 0) history
        else {
            val cutoff = System.currentTimeMillis() - rangeDays.toLong() * 86_400_000L
            val windowed = history.filter { it.first >= cutoff }
            // A short window with nothing in it would draw an empty chart; fall back rather than that.
            if (windowed.size >= 2) windowed else history
        }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Network Hashrate", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bolt, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Kaspa Network", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                }
                scrubbed?.let {
                    Text(formatDateTime(it.first), color = colors.textSecondary, fontSize = 13.sp)
                }
                Text(
                    text = (scrubbed?.second ?: current)?.let { formatHashrate(it) } ?: "—",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp,
                    maxLines = 1
                )
            }

            if (visible.size >= 2) {
                PortfolioBigChart(points = visible, lineColor = KaspaTeal, onScrub = { scrubbed = it })
            } else {
                Box(modifier = Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    Text("Loading…", color = colors.textSecondary)
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30 to "1M", 90 to "3M", 365 to "1Y", 0 to "All").forEach { (days, label) ->
                    val active = days == rangeDays
                    Text(
                        text = label,
                        color = if (active) KaspaTeal else colors.textSecondary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) KaspaTeal.copy(alpha = 0.15f) else Color.Transparent)
                            .clickable { scrubbed = null; rangeDays = days }
                            .padding(vertical = 8.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface)
                    .padding(14.dp)
            ) {
                Text("About Hashrate", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Hashrate is how much computing power miners are pointing at Kaspa. A higher " +
                        "hashrate means more work securing the chain, and it moves with mining " +
                        "profitability rather than with the price directly. Figures come from the " +
                        "Kaspa REST API set in Connection Settings, at one sample per day.",
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            }
        }
    }
}
