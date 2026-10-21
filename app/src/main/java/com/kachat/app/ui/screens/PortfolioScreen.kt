package com.kachat.app.ui.screens

import androidx.compose.foundation.layout.statusBars

import androidx.compose.foundation.layout.navigationBarsPadding

import com.kachat.app.util.KaspaUnit
import com.kachat.app.util.UserFacingError
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.CurrencyBitcoin
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.rotate
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.kachat.app.R
import com.kachat.app.models.PortfolioTransactionEntity
import com.kachat.app.repository.isPricePending
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.ui.theme.iosGlass
import com.kachat.app.services.KaspaNetworkStatsService
import com.kachat.app.services.formatHashrate
import com.kachat.app.util.currencySymbolFor
import com.kachat.app.util.formatFiatAmount
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import com.kachat.app.util.formatKasAmount
import com.kachat.app.util.formatKasAmountGrouped
import com.kachat.app.viewmodels.PortfolioSummary
import com.kachat.app.viewmodels.PortfolioViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.SouthEast
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.text.BasicTextField

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
) {
    val currentPriceUsd by viewModel.currentPriceUsd.collectAsState()
    val priceChange24h by viewModel.priceChange24h.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val currencyCode by viewModel.currency.collectAsState()
    val portfolios by viewModel.portfolios.collectAsState()
    val activePortfolioId by viewModel.activePortfolioId.collectAsState()
    val realizedPL by viewModel.realizedPLThisYear.collectAsState()
    val feeSummary by viewModel.feeSummary.collectAsState()
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
    // Coming back to the app with the Portfolio already on screen: a price left sitting for
    // minutes is refetched, so the number being read is not one from before lunch (iOS 2c50823).
    val priceLifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(priceLifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.refreshSpotPriceIfStale()
        }
        priceLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { priceLifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(isRefreshing) {
        if (!isRefreshing && pullRefreshState.isRefreshing) {
            pullRefreshState.endRefresh()
        }
    }

    val valuesHidden by viewModel.valuesHidden.collectAsState()

    val dockInset = LocalDockInset.current
    Scaffold(
        containerColor = LocalAppColors.current.background,
        // The page runs on under the dock; its scroll ends with room to clear it.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            MainPageHeader(
                title = stringResource(R.string.portfolio),
                actions = {
                    // One tap turns every amount on this screen into dots - for reading the
                    // Portfolio somewhere with people around. The KAS price and the percentages
                    // stay: those say nothing about what is held.
                    // A 44 item on the header's glass disc (a Material IconButton is 48).
                    // iOS: "eye" / "eye.slash" (outlined) in the accent, as a bar button is tinted.
                    ToolbarIconButton(
                        if (valuesHidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (valuesHidden) "Show amounts" else "Hide amounts",
                        onClick = { viewModel.toggleValuesHidden() },
                    )
                },
            )
        }
    ) { padding ->
      CompositionLocalProvider(LocalPortfolioValuesHidden provides valuesHidden) {
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // PullToRefreshContainer translates itself fully above its own position when
                    // idle (verticalOffset 0) via a graphicsLayer translation rather than actually
                    // hiding - with no clip here, that translated-away circle draws outside this
                    // Box's bounds and bleeds into the header above it instead of disappearing.
                    .clipToBounds()
                    .nestedScroll(pullRefreshState.nestedScrollConnection)
            ) {
                // 4.0 (matches iOS): one continuous page - no Data/Transactions tabs, no
                // horizontal paging. Cards first, then the transaction ledger (it keeps its
                // own internal list, sized to roughly a screenful at the end of the scroll).
                // iOS builds it as one inset-grouped List, so the picker cards scroll away
                // with the rest and every card sits in a section of its own: 16 of section
                // margin plus 12 of row inset from each screen edge (28), and between two cards
                // the 5 + 5 row insets around iOS's ~36 of section spacing (46).
                val portfolioScreenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        // The last card scrolls clear of the dock it runs under.
                        .padding(bottom = dockInset)
                ) {
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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            // The picker's own 8 of bottom padding + 36 + 5 = 49 to the squares.
                            .padding(start = 28.dp, end = 28.dp, top = 41.dp),
                        verticalArrangement = Arrangement.spacedBy(46.dp)
                    ) {
                        // Two tappable squares (KAS price | portfolio value) - each opens its own
                        // full-screen chart destination. The inline summary/price/value cards were
                        // replaced by these squares (matches iOS/desktop).
                        PortfolioLauncherSquares(
                            currentPriceUsd = currentPriceUsd,
                            priceChange24h = priceChange24h,
                            summary = summary,
                            valueChange24hPercent = activePortfolioId
                                ?.let { cardSummaries[it]?.todayChangePercent },
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
                        // Realized P&L for the year and the network fees paid (iOS 61eff0f).
                        RealizedPLCard(
                            pl = realizedPL,
                            currencyCode = currencyCode
                        )
                        FeesSpentCard(
                            fees = feeSummary,
                            currencyCode = currencyCode
                        )
                    }
                    // The Transactions section: its header 5 + 36 under the Fees card.
                    PortfolioTransactionsContent(
                        viewModel = viewModel,
                        modifier = Modifier
                            .padding(top = 41.dp)
                            .height(portfolioScreenHeight * 0.8f)
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
}


/**
 * The transaction ledger content — list, CSV import/export, and the add/edit/delete dialog — with
 * no Scaffold/top bar of its own, embedded as PortfolioScreen's Transactions tab. Shares whichever
 * PortfolioViewModel instance the caller passes in rather than creating its own, so a transaction
 * added/edited/deleted here is immediately reflected in the summary card and charts elsewhere.
 */
@Composable
private fun PortfolioTransactionsContent(
    viewModel: PortfolioViewModel,
    showTitle: Boolean = true,
    modifier: Modifier = Modifier
) {
    val transactions by viewModel.transactions.collectAsState()
    val currentPriceUsd by viewModel.currentPriceUsd.collectAsState()
    val currencyCode by viewModel.currency.collectAsState()
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var editingTransaction by remember { mutableStateOf<PortfolioTransactionEntity?>(null) }
    /** The transaction a long press is moving to another portfolio, while its sheet is up. */
    var movingTransaction by remember { mutableStateOf<PortfolioTransactionEntity?>(null) }
    val portfolios by viewModel.portfolios.collectAsState()
    var showCsvMenu by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showAddAddressDialog by remember { mutableStateOf(false) }
    /** Set for "Add Chatting Address": the address sheet imports this one straight away. */
    var addAddressPreset by remember { mutableStateOf<String?>(null) }
    // Multi-select over the ledger, matching iOS's EditMode on PortfolioTransactionsView. Deleting
    // one row has always been the row's own bin icon; deleting the forty rows a bad CSV import left
    // behind was forty taps and forty confirmations.
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showDeleteSelected by remember { mutableStateOf(false) }
    var isImportingAddress by remember { mutableStateOf(false) }
    var importProgressText by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    // A row deleted underneath us (edit dialog, CSV re-import) must not leave a phantom in the
    // count, and an empty ledger has nothing to select.
    LaunchedEffect(transactions) {
        val live = transactions.map { it.id }.toSet()
        if (selectedIds.any { it !in live }) selectedIds = selectedIds intersect live
        if (transactions.isEmpty() && selecting) { selecting = false; selectedIds = emptySet() }
    }

    var showNextcloudImporter by remember { mutableStateOf(false) }
    val nextcloudScope = rememberCoroutineScope()
    if (showNextcloudImporter) {
        NextcloudFileSelectDialog(
            service = viewModel.nextcloud,
            allowedExtensions = setOf("csv", "txt"),
            onDismiss = { showNextcloudImporter = false },
            onPick = { file ->
                showNextcloudImporter = false
                // Downloaded to a temporary copy, then the same CSV import as a file from storage.
                nextcloudScope.launch {
                    try {
                        val bytes = viewModel.nextcloud.downloadFile(file.path)
                        val local = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val dir = java.io.File(context.cacheDir, "portfolio_imports").apply { mkdirs() }
                            java.io.File(dir, java.util.UUID.randomUUID().toString() + "-" + file.name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                                .apply { writeBytes(bytes) }
                        }
                        viewModel.importCsv(android.net.Uri.fromFile(local)) { result ->
                            local.delete()
                            val count = result.getOrNull() ?: 0
                            if (count > 0) IosToasts.show("Imported $count transaction${if (count == 1) "" else "s"}")
                            else IosToasts.error("Import failed. Check the CSV format")
                        }
                    } catch (e: Exception) {
                        IosToasts.error(context.getString(R.string.pnc_import_failed, com.kachat.app.util.UserFacingError.message(e, "Please try again")))
                    }
                }
            },
        )
    }

    val importCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.importCsv(uri) { result ->
                // iOS: none imported reads as the format error, in red.
                val count = result.getOrNull() ?: 0
                if (count > 0) IosToasts.show("Imported $count transaction${if (count == 1) "" else "s"}")
                else IosToasts.error("Import failed. Check the CSV format")
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // iOS-style section header: "Transactions" title with the add and import/export menus
        // on the same row (replaces the old floating action buttons at the bottom).
        // iOS: the inset-grouped section's header - HStack(spacing: 16) of "Transactions"
        // (headline), then Select (subheadline semibold, accent), the 22pt plus.circle.fill and
        // the 17pt semibold square.and.arrow.up.on.square; while selecting, Select All (a 22pt
        // checkmark circle), the bin with the count, and Done. Lined up with the rows' text: 16
        // of section margin plus 20 of row inset.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 36.dp)
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (showTitle) {
                Text(
                    stringResource(R.string.transactions),
                    color = LocalAppColors.current.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp
                )
            }
            Spacer(Modifier.weight(1f))
            if (selecting) {
                // Add and Import/Export step aside: while selecting, the only things worth doing to
                // this list are picking rows, binning them, or leaving.
                val allPicked = selectedIds.size == transactions.size && transactions.isNotEmpty()
                Icon(
                    if (allPicked) Icons.Default.CheckCircle else Icons.Outlined.CheckCircle,
                    contentDescription = if (allPicked) "Deselect all" else "Select all",
                    tint = KaspaTeal,
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .clickable {
                            selectedIds = if (selectedIds.size == transactions.size) emptySet()
                            else transactions.map { it.id }.toSet()
                        }
                )
                val binTint = if (selectedIds.isEmpty()) LocalAppColors.current.textSecondary else LocalAppColors.current.danger
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier
                        .clickable(enabled = selectedIds.isNotEmpty()) { showDeleteSelected = true }
                        .semantics { contentDescription = "Delete selected" }
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, tint = binTint, modifier = Modifier.size(22.dp))
                    if (selectedIds.isNotEmpty()) {
                        Text("${selectedIds.size}", color = binTint, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    }
                }
                Text(
                    stringResource(R.string.done),
                    color = KaspaTeal,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    modifier = Modifier.clickable { selecting = false; selectedIds = emptySet() }
                )
            } else {
                Text(
                    "Select",
                    color = if (transactions.isEmpty()) LocalAppColors.current.textSecondary else KaspaTeal,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    modifier = Modifier.clickable(enabled = transactions.isNotEmpty()) { selecting = true }
                )
                Box {
                    Icon(
                        Icons.Default.AddCircle,
                        contentDescription = stringResource(R.string.add_transaction),
                        tint = KaspaTeal,
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .clickable { showAddMenu = true }
                    )
                    if (showAddMenu) {
                        // A sheet, not a popup: each option gets a line saying what it does - "Add
                        // Kaspa Address" reads as a contact until you learn otherwise.
                        ActionSheetContainer(
                            title = "Add to Portfolio",
                            subtitle = null,
                            onDismiss = { showAddMenu = false },
                        ) {
                            ActionSheetRow(
                                icon = Icons.Default.MonetizationOn,
                                title = stringResource(R.string.add_transaction),
                                subtitle = "Record a buy or a sell by hand.",
                            ) {
                                showAddMenu = false
                                showAddDialog = true
                            }
                            ActionSheetRow(
                                icon = Icons.Default.QrCodeScanner,
                                title = stringResource(R.string.add_kaspa_address),
                                subtitle = "Track an address's balance as part of this portfolio.",
                            ) {
                                showAddMenu = false
                                addAddressPreset = null
                                showAddAddressDialog = true
                            }
                            // One tap for the address KaChat itself spends from: its buys and
                            // sells, and every network fee it paid (messages, handshakes,
                            // payments) for the Fees Spent card.
                            viewModel.chattingAddress?.let { chattingAddress ->
                                ActionSheetRow(
                                    icon = Icons.Default.Forum,
                                    title = stringResource(R.string.portfolio_add_chatting_address),
                                    subtitle = stringResource(R.string.portfolio_add_chatting_address_sub),
                                ) {
                                    showAddMenu = false
                                    addAddressPreset = chattingAddress
                                    showAddAddressDialog = true
                                }
                            }
                        }
                    }
                }
                Box {
                    // square.and.arrow.up.on.square: the nearest Material glyph is the share box.
                    Icon(
                        Icons.Default.IosShare,
                        contentDescription = "Import or export CSV",
                        tint = KaspaTeal,
                        modifier = Modifier
                            .size(22.dp)
                            .clickable { showCsvMenu = true }
                    )
                    if (showCsvMenu) {
                        ActionSheetContainer(
                            title = "Import or Export",
                            subtitle = null,
                            onDismiss = { showCsvMenu = false },
                        ) {
                            ActionSheetRow(
                                icon = Icons.Default.FileUpload,
                                title = stringResource(R.string.export_csv),
                                subtitle = "Write this portfolio's transactions out to a file.",
                            ) {
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
                                            IosToasts.error("No app available to share the CSV")
                                        }
                                    },
                                    onUnavailable = { message -> IosToasts.error(message) }
                                )
                            }
                            ActionSheetRow(
                                icon = Icons.Default.FileDownload,
                                title = stringResource(R.string.import_csv),
                                subtitle = "Read transactions in from a file.",
                            ) {
                                showCsvMenu = false
                                importCsvLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*"))
                            }
                            // Export to / Import from Nextcloud - while an account is connected (iOS aa5d783).
                            if (viewModel.nextcloud.isConnected) {
                                ActionSheetRow(
                                    icon = Icons.Default.CloudDownload,
                                    title = stringResource(R.string.pnc_import),
                                    subtitle = stringResource(R.string.pnc_import_sub),
                                ) {
                                    showCsvMenu = false
                                    showNextcloudImporter = true
                                }
                                ActionSheetRow(
                                    icon = Icons.Default.CloudUpload,
                                    title = stringResource(R.string.pnc_export),
                                    subtitle = stringResource(R.string.pnc_export_sub),
                                ) {
                                    showCsvMenu = false
                                    // Same CSV as Export CSV, uploaded into the KaChat folder.
                                    viewModel.exportCsv(
                                        onReady = { uri ->
                                            nextcloudScope.launch {
                                                val (message, failed) = try {
                                                    val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                                    } ?: throw java.io.IOException("Couldn't write the CSV file")
                                                    val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.endsWith(".csv") }
                                                        ?: "KaChat Portfolio ${System.currentTimeMillis()}.csv"
                                                    // keepSpaces: the portfolio's name stays readable in Nextcloud (iOS 87b2a0b).
                                                    val path = viewModel.nextcloud.uploadToKaChatFolder(bytes, name, "text/csv", keepSpaces = true)
                                                    context.getString(R.string.pnc_saved_to, path) to false
                                                } catch (e: Exception) {
                                                    context.getString(R.string.pnc_export_failed, com.kachat.app.util.UserFacingError.message(e, "Please try again")) to true
                                                }
                                                if (failed) IosToasts.error(message) else IosToasts.show(message)
                                            }
                                        },
                                        onUnavailable = { message -> IosToasts.error(message) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            // Horizontal inset is per-item below (not here).
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            if (transactions.isEmpty()) {
                // iOS emptyState: no card - the 44pt chart glyph, "No Transactions Yet" (title3
                // semibold) and the hint (subheadline, secondary), centred, 40 above and below.
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 36.dp)
                            .padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        UptrendChartGlyph(size = 48.dp, color = LocalAppColors.current.textSecondary)
                        Text(
                            stringResource(R.string.portfolio_no_transactions_title),
                            color = LocalAppColors.current.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 20.sp,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            stringResource(R.string.portfolio_no_transactions_sub),
                            color = LocalAppColors.current.textSecondary,
                            fontSize = 15.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                // iOS: one inset-grouped section - the rows share a card (16 in from each screen
                // edge, 26pt corners at its top and bottom) with hairlines between them that
                // start at the title, not one floating card per row.
                // asReversed() is an O(1) view (no per-recomposition list copy like reversed()),
                // and a stable key lets Compose reuse item state / animate list changes.
                val rows = transactions.asReversed()
                itemsIndexed(rows, key = { _, tx -> tx.id }) { index, tx ->
                    val first = index == 0
                    val last = index == rows.lastIndex
                    val shape = RoundedCornerShape(
                        topStart = if (first) 26.dp else 0.dp,
                        topEnd = if (first) 26.dp else 0.dp,
                        bottomStart = if (last) 26.dp else 0.dp,
                        bottomEnd = if (last) 26.dp else 0.dp,
                    )
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .clip(shape)
                            .background(LocalAppColors.current.surface)
                    ) {
                        TransactionRow(
                            tx = tx,
                            onClick = { editingTransaction = tx },
                            // Hold to move it to another portfolio (iOS b438f2d).
                            onLongClick = { movingTransaction = tx },
                            onDelete = { viewModel.deleteTransaction(tx.id) },
                            currencyCode = currencyCode,
                            selecting = selecting,
                            picked = tx.id in selectedIds,
                            onToggle = {
                                selectedIds = if (tx.id in selectedIds) selectedIds - tx.id else selectedIds + tx.id
                            }
                        )
                        if (!last) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = if (selecting) 90.dp else 56.dp),
                                color = LocalAppColors.current.divider,
                                thickness = 0.5.dp
                            )
                        }
                    }
                }
            }
        }
    }

    // Hold a transaction: move it to another portfolio. The row itself sits on top so it's clear
    // what is moving; a portfolio that already holds the same on-chain transaction is offered but
    // disabled, since moving it there would count it twice (iOS b438f2d).
    movingTransaction?.let { tx ->
        val others = portfolios.filter { it.id != tx.portfolioId }
        val alreadyHolding = remember(tx.id) { viewModel.portfolioIdsContaining(tx.sourceTxId ?: "") }
        ActionSheetContainer(
            title = stringResource(R.string.portfolio_move_title),
            subtitle = null,
            onDismiss = { movingTransaction = null },
        ) {
            // iOS: the row padded 14 on the frosted card (16pt corners, hairline, no shadow).
            TransactionRow(
                tx = tx,
                onClick = {},
                onDelete = null,
                currencyCode = currencyCode,
                modifier = Modifier.iosGlass(16.dp, shadowAlpha = 0f),
                contentPadding = PaddingValues(14.dp),
            )
            if (others.isEmpty()) {
                Text(
                    stringResource(R.string.portfolio_move_need_another),
                    color = LocalAppColors.current.textSecondary,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            } else {
                others.forEach { portfolio ->
                    val duplicate = portfolio.id in alreadyHolding
                    ActionSheetRow(
                        icon = Icons.Default.Folder,
                        title = portfolio.name,
                        subtitle = stringResource(if (duplicate) R.string.portfolio_move_already_has else R.string.portfolio_move_here),
                        enabled = !duplicate,
                    ) {
                        viewModel.moveTransaction(tx.id, portfolio.id)
                        movingTransaction = null
                        IosToasts.show(context.getString(R.string.portfolio_moved_to, portfolio.name))
                    }
                }
            }
        }
    }

    if (showDeleteSelected) {
        val count = selectedIds.size
        com.kachat.app.ui.theme.IosAlertDialog(
            onDismissRequest = { showDeleteSelected = false },
            containerColor = LocalAppColors.current.surface,
            title = {
                Text(
                    "Delete $count transaction${if (count == 1) "" else "s"}?",
                    color = LocalAppColors.current.textPrimary
                )
            },
            text = { Text("This can't be undone.", color = LocalAppColors.current.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    selectedIds.forEach { viewModel.deleteTransaction(it) }
                    selectedIds = emptySet()
                    selecting = false
                    showDeleteSelected = false
                }) { Text(stringResource(R.string.delete), color = LocalAppColors.current.danger) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelected = false }) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }

    if (showAddDialog || editingTransaction != null) {
        val existing = editingTransaction
        TransactionDialog(
            existing = existing,
            currentPriceUsd = currentPriceUsd,
            currencyCode = currencyCode,
            onDismiss = {
                showAddDialog = false
                editingTransaction = null
            },
            onSave = { type, amountKas, fiatValue, timestampMillis, notes ->
                if (existing != null) {
                    viewModel.updateTransaction(existing.id, type, amountKas, fiatValue, timestampMillis, notes)
                } else {
                    viewModel.addTransaction(type, amountKas, fiatValue, timestampMillis, notes)
                }
                showAddDialog = false
                editingTransaction = null
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
            presetAddress = addAddressPreset,
            onDismiss = { showAddAddressDialog = false },
            isImporting = isImportingAddress,
            progressText = importProgressText,
            onConfirm = { address ->
                // The sheet stays up and shows progress in place, rather than closing and
                // handing off to a second dialog.
                isImportingAddress = true
                importProgressText = "Starting…"
                coroutineScope.launch {
                    val result = viewModel.importAddress(address) { text -> importProgressText = text }
                    isImportingAddress = false
                    showAddAddressDialog = false
                    result.fold(
                        onSuccess = { imported ->
                            IosToasts.show(
                                com.kachat.app.repository.addressImportToastMessage(imported) { count ->
                                    context.getString(R.string.portfolio_fees_counted, count)
                                }
                            )
                        },
                        onFailure = { IosToasts.error(it.message ?: "Import failed.") }
                    )
                }
            },
            resolveKns = { input -> viewModel.resolveEverywhere(input) }
        )
    }
}

/** "kaspa:qrabc...wxyz" — enough of each end to recognize the address without wrapping the dialog. */
private fun shortenKaspaAddress(address: String): String =
    if (address.length <= 24) address else "${address.take(14)}...${address.takeLast(6)}"

@Composable
private fun AddressEntryDialog(
    /** "Add Chatting Address": no field to fill - the import of this address starts as the
     *  sheet opens, and the sheet shows only its progress. */
    presetAddress: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    /** Every service's answer for a typed name, .kachat first (iOS 6ac48a7). */
    resolveKns: suspend (String) -> List<com.kachat.app.services.NameResolution>,
    /** True while the import this sheet started is running - it stays open and shows progress. */
    isImporting: Boolean = false,
    progressText: String = "",
) {
    var addressText by remember { mutableStateOf("") }
    var showScanner by remember { mutableStateOf(false) }
    var isResolvingKns by remember { mutableStateOf(false) }
    var knsResolvedAddress by remember { mutableStateOf<String?>(null) }
    var knsNotFound by remember { mutableStateOf(false) }
    // The shared not-found message for the typed name (iOS 5a5122d `notFoundText`).
    var notFoundText by remember { mutableStateOf("") }
    val notFoundContext = androidx.compose.ui.platform.LocalContext.current
    // The name it resolved as, and every service's answer (`OtherDomainsDropdown`).
    var knsResolvedDomain by remember { mutableStateOf<String?>(null) }
    var nameResolutions by remember { mutableStateOf<List<com.kachat.app.services.NameResolution>>(emptyList()) }
    var selectedTld by remember { mutableStateOf<com.kachat.app.services.NameServiceTLD?>(null) }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    // Debounced live KNS resolution — the same 500ms pattern the send flows' address fields use
    // (see ColdStorageScreens' recipient field): restart on every keystroke, resolve only input
    // that looks like a domain rather than a raw address.
    LaunchedEffect(addressText) {
        knsResolvedAddress = null
        knsResolvedDomain = null
        nameResolutions = emptyList()
        selectedTld = null
        knsNotFound = false
        val input = addressText.trim()
        if (input.isEmpty() || input.startsWith("kaspa:", ignoreCase = true) ||
            input.startsWith("kaspatest:", ignoreCase = true) ||
            !com.kachat.app.services.NameServicesClient.looksLikeName(input)
        ) {
            isResolvingKns = false
            return@LaunchedEffect
        }
        isResolvingKns = true
        kotlinx.coroutines.delay(500)
        val results = resolveKns(input)
        nameResolutions = results
        isResolvingKns = false
        val resolution = com.kachat.app.services.NameServicesClient.primary(results, input)
        val resolved = resolution?.address
        if (resolved != null) {
            knsResolvedAddress = resolved
            knsResolvedDomain = resolution.display
            selectedTld = resolution.tld
        } else {
            notFoundText = com.kachat.app.services.NameServicesClient.notFoundMessage(notFoundContext, input, results)
            knsNotFound = true
        }
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

    // A sheet over the address sheet, as iOS's PortfolioTransactionsView presents QRScannerView.
    if (showScanner) {
        QrScannerSheet(
            onScanned = { scanned -> addressText = com.kachat.app.util.KaspaAddress.fromScanned(scanned) },
            onDismiss = { showScanner = false }
        )
    }

    LaunchedEffect(presetAddress) {
        if (presetAddress != null && !isImporting) onConfirm(presetAddress)
    }

    ActionSheetContainer(
        title = if (presetAddress == null) "Add Kaspa Address" else stringResource(R.string.portfolio_add_chatting_address),
        subtitle = null,
        // Held open while the import runs - dismissing mid-import would abandon the only
        // progress readout while the work carried on regardless.
        onDismiss = { if (!isImporting) onDismiss() },
    ) {
            if (isImporting || presetAddress != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.kachat.app.ui.theme.IosActivityIndicator(
                        color = KaspaTeal,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(progressText, color = LocalAppColors.current.textSecondary)
                }
                Spacer(Modifier.height(16.dp))
                return@ActionSheetContainer
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                com.kachat.app.ui.theme.IosTextField(
                    value = addressText,
                    onValueChange = { addressText = it },
                    placeholder = { Text(stringResource(R.string.kaspa_qr_or_domain)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // Who is behind it: the domain typed, else the address's own .kachat name.
                AddressResolutionCard(
                    address = knsResolvedAddress ?: addressText.trim().takeIf { com.kachat.app.util.KaspaAddress.isValid(it) },
                    domain = knsResolvedDomain,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (addressText.trim().isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    when {
                        isResolvingKns -> Row(verticalAlignment = Alignment.CenterVertically) {
                            com.kachat.app.ui.theme.IosActivityIndicator(modifier = Modifier.size(14.dp), color = KaspaTeal, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.looking_up_domain), color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                        }
                        knsResolvedAddress != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = LocalAppColors.current.success, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.resolved_name, knsResolvedDomain ?: ""), color = LocalAppColors.current.success, fontSize = 12.sp, maxLines = 1)
                            Spacer(Modifier.width(6.dp))
                            Text(shortenKaspaAddress(knsResolvedAddress ?: ""), color = LocalAppColors.current.textSecondary, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 1)
                        }
                        // Quiet by design — an unfinished domain isn't an error worth shouting about.
                        knsNotFound -> Text(notFoundText, color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                        isRawValid -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = LocalAppColors.current.success, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.valid_address), color = LocalAppColors.current.success, fontSize = 12.sp)
                        }
                        else -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Cancel, null, tint = LocalAppColors.current.danger, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.invalid_address_format), color = LocalAppColors.current.danger, fontSize = 12.sp)
                        }
                    }
                    if (!isResolvingKns) {
                        OtherDomainsDropdown(nameResolutions, selectedTld) { resolution ->
                            resolution.address?.let {
                                knsResolvedAddress = it
                                knsResolvedDomain = resolution.display
                                selectedTld = resolution.tld
                                knsNotFound = false
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { clipboardManager.getText()?.text?.let { addressText = com.kachat.app.util.KaspaAddress.fromScanned(it) } }) {
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
                    stringResource(R.string.portfolio_add_address_footer_kachat),
                    color = LocalAppColors.current.textSecondary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onConfirm(effectiveAddress) },
                    enabled = isValid,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, disabledContainerColor = LocalAppColors.current.surfaceVariant),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text("Import", color = if (isValid) Color.Black else LocalAppColors.current.textSecondary, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
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
    val plColor = if (summary.totalPL >= 0) LocalAppColors.current.success else LocalAppColors.current.danger
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
                        tint = if (isPositive) LocalAppColors.current.success else LocalAppColors.current.danger,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        "${String.format(Locale.US, "%.2f", kotlin.math.abs(priceChange24h))}%",
                        color = if (isPositive) LocalAppColors.current.success else LocalAppColors.current.danger,
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
                Text(kas(summary.holdingsKas), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.current_value), color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                Text(money(summary.currentValue, currencyCode), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(10.dp))
        androidx.compose.material3.HorizontalDivider(color = LocalAppColors.current.divider)
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(stringResource(R.string.total_invested), color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                Text(money(summary.totalInvested, currencyCode), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
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
                        "${money(summary.totalPL, currencyCode)} (${String.format(Locale.US, "%.1f", summary.totalPLPercent)}%)",
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
        Text(money(headerValue, currencyCode), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
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
    /** Last 24 hours for the active portfolio; null until there is a sample that old. */
    valueChange24hPercent: Double?,
    currencyCode: String,
    onOpenPrice: () -> Unit,
    onOpenValue: () -> Unit
) {
    // iOS launcherSquares: HStack(spacing: 10).
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LauncherSquare(
            modifier = Modifier.weight(1f),
            onClick = onOpenPrice,
            title = "Kaspa",
            value = currentPriceUsd?.let { formatUsdPrice(it, currencyCode) } ?: "—",
            changePercent = priceChange24h,
            headerIcon = {
                // The KaspaLogo asset: the mark in its filled accent circle, 26pt.
                Image(
                    painter = painterResource(R.drawable.ic_kaspa_logo),
                    contentDescription = null,
                    modifier = Modifier.size(26.dp).clip(CircleShape)
                )
            }
        )
        LauncherSquare(
            modifier = Modifier.weight(1f),
            onClick = onOpenValue,
            title = "Value",
            value = money(summary.currentValue, currencyCode),
            // The last 24 hours, not all-time P&L. A number that only ever grows over the life of
            // the portfolio says nothing about today, and it sat beside the Kaspa square's 24h
            // figure reading as though the two were comparable.
            changePercent = valueChange24hPercent,
            emptyChangeLabel = "24h change not available yet",
            headerIcon = {
                // chart.line.uptrend.xyaxis in subheadline, secondary.
                UptrendChartGlyph(size = 19.dp, color = LocalAppColors.current.textSecondary)
            }
        )
    }
}

/**
 * iOS priceSquare / valueSquare: VStack(alignment: .leading, spacing: 8) in a 128pt-tall frame,
 * padded 14 (156 in all) on the 18pt glass card - the header row (HStack(spacing: 8): icon, the
 * subheadline-semibold secondary title, a caption chevron), then the value at the bottom (22
 * bold), then the 24h badge.
 */
@Composable
private fun LauncherSquare(
    modifier: Modifier,
    onClick: () -> Unit,
    title: String,
    value: String,
    changePercent: Double?,
    headerIcon: @Composable () -> Unit,
    /** Shown in place of the badge when there is no change to report. A fresh portfolio has no
     *  24h move, and 0.00% would be a claim rather than an absence. */
    emptyChangeLabel: String? = null,
) {
    Column(
        modifier = modifier
            .height(156.dp)
            .iosGlass(18.dp)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            headerIcon()
            Text(
                title,
                color = LocalAppColors.current.textSecondary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            PortfolioChevron()
        }
        Spacer(Modifier.weight(1f))
        Text(
            value,
            color = LocalAppColors.current.textPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
        if (changePercent != null) {
            PortfolioChangeBadge(changePercent)
        } else if (emptyChangeLabel != null) {
            Text(
                emptyChangeLabel,
                color = LocalAppColors.current.textSecondary,
                fontSize = 11.sp,
                maxLines = 1
            )
        }
    }
}

/** iOS changeBadge: arrow.up / arrow.down (caption2) and the percentage (footnote semibold), 2
 *  apart, green or red. */
@Composable
private fun PortfolioChangeBadge(percent: Double) {
    val positive = percent >= 0
    val color = if (positive) LocalAppColors.current.success else LocalAppColors.current.danger
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(
            if (positive) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(15.dp)
        )
        Text(
            "${String.format(Locale.US, "%.2f", kotlin.math.abs(percent))}%",
            color = color,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp
        )
    }
}

/** SF Symbols' chevron.right at caption size (~11pt tall, thin), in secondary grey. */
@Composable
private fun PortfolioChevron() {
    val color = LocalAppColors.current.textSecondary
    Canvas(modifier = Modifier.size(width = 6.dp, height = 11.dp)) {
        val inset = 1.6.dp.toPx() / 2f
        val path = Path().apply {
            moveTo(inset, inset)
            lineTo(size.width - inset, size.height / 2f)
            lineTo(inset, size.height - inset)
        }
        drawPath(path, color = color, style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * SF Symbols' chart.line.uptrend.xyaxis, drawn: an L of axes with a line zig-zagging up to an
 * arrowhead at the top right. Material has no glyph with both the axes and the rising line.
 */
@Composable
private fun UptrendChartGlyph(size: Dp, color: Color) {
    Canvas(modifier = Modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val w = 1.7f * u
        val style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val axes = Path().apply {
            moveTo(2.5f * u, 3f * u)
            lineTo(2.5f * u, 21.5f * u)
            lineTo(22f * u, 21.5f * u)
        }
        drawPath(axes, color = color, style = style)
        val line = Path().apply {
            moveTo(6f * u, 16.5f * u)
            lineTo(10.5f * u, 10.5f * u)
            lineTo(14f * u, 14f * u)
            lineTo(20.5f * u, 6f * u)
        }
        drawPath(line, color = color, style = style)
        val head = Path().apply {
            moveTo(16f * u, 6f * u)
            lineTo(20.5f * u, 6f * u)
            lineTo(20.5f * u, 10.5f * u)
        }
        drawPath(head, color = color, style = style)
    }
}

// MARK: shared big chart (iOS PortfolioAreaChart) and range selector

/** One axis mark: where it sits (a time in millis, or a value) and what it reads. */
private data class ChartAxisTick(val at: Double, val label: String)

/**
 * The value marks iOS's Swift Charts lays down for `.automatic(desiredCount: 4)` over the
 * padded domain: multiples of a 1-2-5 step, written with as many decimals as the step needs
 * ("0.036", "0.037", "0.038"). [precise] is iOS's `preciseAxis` - three significant digits,
 * for bitcoin-scale numbers the step's decimals would round to one figure.
 */
private fun chartValueTicks(lower: Double, upper: Double, precise: Boolean): List<ChartAxisTick> {
    val span = upper - lower
    if (!span.isFinite() || span <= 0) return emptyList()
    val raw = span / 4
    val magnitude = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(raw)))
    val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * magnitude }.firstOrNull { it >= raw * 0.999999 } ?: (10 * magnitude)
    val decimals = if (step >= 1) 0 else kotlin.math.ceil(-kotlin.math.log10(step) - 1e-9).toInt().coerceIn(0, 12)
    val ticks = mutableListOf<ChartAxisTick>()
    var index = kotlin.math.ceil(lower / step - 1e-9)
    while (index * step <= upper + step * 1e-9 && ticks.size < 12) {
        val value = (index * step).let { if (kotlin.math.abs(it) < step * 1e-6) 0.0 else it }
        val label = if (precise) {
            if (value == 0.0) "0"
            else java.math.BigDecimal(value).round(java.math.MathContext(3)).stripTrailingZeros().toPlainString()
        } else {
            String.format(Locale.getDefault(), "%,.${decimals}f", value)
        }
        ticks += ChartAxisTick(value, label)
        index += 1
    }
    return ticks
}

/**
 * The time marks Swift Charts picks for `.automatic(desiredCount: 4)`: the shortest stride that
 * gives five marks or fewer. A span of two days or less is marked in hours ("5 AM", counted
 * from the epoch as Swift Charts counts them), anything longer at local midnights, week, month
 * or year starts, labelled "Oct 7" - iOS's `.month(.abbreviated).day()`.
 */
private fun chartTimeTicks(start: Long, end: Long): List<ChartAxisTick> {
    if (end <= start) return emptyList()
    val locale = Locale.getDefault()
    val intraday = end - start <= 2L * 86_400_000L
    val format = SimpleDateFormat(
        android.text.format.DateFormat.getBestDateTimePattern(locale, if (intraday) "j" else "MMMd"),
        locale
    )
    fun ticks(times: List<Long>) = times.map { ChartAxisTick(it.toDouble(), format.format(Date(it))) }

    if (intraday) {
        val hour = 3_600_000L
        for (hours in listOf(1, 2, 3, 6, 12)) {
            val step = hours * hour
            val first = ((start + step - 1) / step) * step
            val times = generateSequence(first) { it + step }.takeWhile { it <= end }.toList()
            if (times.size <= 5) return ticks(times)
        }
    }

    val calendar = Calendar.getInstance()
    fun startOfDay(millis: Long): Calendar = (calendar.clone() as Calendar).apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    // Local midnights inside the span - at most a couple of thousand, for All.
    val midnights = mutableListOf<Calendar>()
    val day = startOfDay(start)
    if (day.timeInMillis < start) day.add(Calendar.DAY_OF_MONTH, 1)
    while (day.timeInMillis <= end && midnights.size < 20_000) {
        midnights += day.clone() as Calendar
        day.add(Calendar.DAY_OF_MONTH, 1)
    }
    fun dayNumber(c: Calendar): Long = (c.timeInMillis + c.get(Calendar.ZONE_OFFSET) + c.get(Calendar.DST_OFFSET)) / 86_400_000L

    for (days in listOf(1, 2)) {
        val times = midnights.filter { dayNumber(it) % days == 0L }
        if (times.size <= 5) return ticks(times.map { it.timeInMillis })
    }
    val weekStarts = midnights.filter { it.get(Calendar.DAY_OF_WEEK) == it.firstDayOfWeek }
    if (weekStarts.size <= 5) return ticks(weekStarts.map { it.timeInMillis })
    val fortnights = weekStarts.filter { (dayNumber(it) / 7) % 2 == 0L }
    if (fortnights.size <= 5) return ticks(fortnights.map { it.timeInMillis })
    val monthStarts = midnights.filter { it.get(Calendar.DAY_OF_MONTH) == 1 }
    for (months in listOf(1, 2, 3, 6)) {
        val times = monthStarts.filter { it.get(Calendar.MONTH) % months == 0 }
        if (times.size <= 5) return ticks(times.map { it.timeInMillis })
    }
    val yearStarts = monthStarts.filter { it.get(Calendar.MONTH) == Calendar.JANUARY }
    for (years in listOf(1, 2, 5, 10, 20, 50)) {
        val times = yearStarts.filter { it.get(Calendar.YEAR) % years == 0 }
        if (times.size <= 5) return ticks(times.map { it.timeInMillis })
    }
    return emptyList()
}

/** The point nearest [time] in a series sorted oldest first. */
private fun nearestChartIndex(points: List<Pair<Long, Double>>, time: Long): Int {
    var lo = 0
    var hi = points.size - 1
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (points[mid].first < time) lo = mid + 1 else hi = mid
    }
    if (lo > 0 && kotlin.math.abs(points[lo - 1].first - time) <= kotlin.math.abs(points[lo].first - time)) return lo - 1
    return lo
}

/**
 * iOS PortfolioAreaChart: the value marks down the leading edge (caption2, secondary) with a
 * hairline across the plot at each, dashed hairlines up from each time mark with its label just
 * right of it under the plot, a 2pt accent line through the points (Catmull-Rom, as Swift
 * Charts' `.catmullRom`), and under it the accent from 28% at the top of the line to 3% at the
 * bottom. The values are padded 10% above and below. [height] is the whole chart, labels
 * included, as iOS's `.frame(height:)` is. [hideValues] is the eye button: the value labels go,
 * the grid stays.
 */
@Composable
private fun PortfolioBigChart(
    points: List<Pair<Long, Double>>,
    lineColor: Color,
    onScrub: (Pair<Long, Double>?) -> Unit,
    /** Two fingers on the chart: the span between them, oldest first, or null when they lift. */
    onRange: ((Pair<Pair<Long, Double>, Pair<Long, Double>>?) -> Unit)? = null,
    height: Dp = 260.dp,
    hideValues: Boolean = false,
    preciseAxis: Boolean = false,
) {
    val colors = LocalAppColors.current
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var rangeIndices by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val upColor = colors.success
    val downColor = colors.danger

    val minV = points.minOf { it.second }
    val maxV = points.maxOf { it.second }
    val valueSpan = if (maxV - minV > 0) maxV - minV else maxOf(kotlin.math.abs(maxV), 1.0)
    val lower = minV - valueSpan * 0.10
    val upper = maxV + valueSpan * 0.10
    val startTime = points.first().first
    val endTime = points.last().first
    val timeSpan = (endTime - startTime).coerceAtLeast(1L)

    val labelStyle = TextStyle(color = colors.textSecondary, fontSize = 11.sp)
    val measurer = rememberTextMeasurer()
    val yTicks = remember(lower, upper, preciseAxis) { chartValueTicks(lower, upper, preciseAxis) }
    val xTicks = remember(startTime, endTime) { chartTimeTicks(startTime, endTime) }
    val yLabels = remember(yTicks, hideValues, labelStyle) {
        if (hideValues) emptyList() else yTicks.map { it to measurer.measure(it.label, labelStyle) }
    }
    val xLabels = remember(xTicks, labelStyle) { xTicks.map { it to measurer.measure(it.label, labelStyle) } }
    val labelHeight = remember(labelStyle) { measurer.measure("0", labelStyle).size.height }
    val density = LocalDensity.current
    val labelGap = with(density) { 12.dp.toPx() }
    val axisGap = with(density) { 5.dp.toPx() }
    // The plot starts after the widest value label, as Swift Charts' leading axis does.
    val plotLeft = (yLabels.maxOfOrNull { it.second.size.width } ?: 0).let { if (it > 0) it + labelGap else 0f }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(points, plotLeft) {
                fun indexAt(x: Float): Int {
                    val plotWidth = (size.width - plotLeft).coerceAtLeast(1f)
                    val fraction = ((x - plotLeft) / plotWidth).coerceIn(0f, 1f)
                    return nearestChartIndex(points, startTime + (fraction * timeSpan).toLong())
                }
                fun scrubAt(x: Float) {
                    if (size.width <= 0) return
                    rangeIndices = null
                    onRange?.invoke(null)
                    val idx = indexAt(x)
                    selectedIndex = idx
                    onScrub(points[idx])
                }
                fun rangeAt(x1: Float, x2: Float) {
                    if (size.width <= 0) return
                    selectedIndex = null
                    onScrub(null)
                    val a = indexAt(minOf(x1, x2))
                    val b = indexAt(maxOf(x1, x2))
                    rangeIndices = a to b
                    onRange?.invoke(points[a] to points[b])
                }
                fun clear() {
                    selectedIndex = null
                    rangeIndices = null
                    onScrub(null)
                    onRange?.invoke(null)
                }
                // Touches are tracked by hand rather than with a drag detector, for two
                // reasons: two fingers select the span between them, and a chart being read
                // has to hold the page still underneath it. A single finger moving mostly up
                // or down is left alone, so the page still scrolls past the chart.
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    val start = first.position
                    var owned = false
                    var abandoned = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            owned = true
                            abandoned = false
                            rangeAt(pressed[0].position.x, pressed[1].position.x)
                            pressed.forEach { it.consume() }
                            continue
                        }
                        if (abandoned) continue
                        val change = pressed.first()
                        if (!owned) {
                            val dx = kotlin.math.abs(change.position.x - start.x)
                            val dy = kotlin.math.abs(change.position.y - start.y)
                            val slop = viewConfiguration.touchSlop
                            if (dy > slop && dy > dx) { abandoned = true; continue }
                            if (dx > slop) { owned = true; scrubAt(start.x) }
                        }
                        if (owned) {
                            scrubAt(change.position.x)
                            change.consume()
                        }
                    }
                    clear()
                }
            }
    ) {
        val plotRight = size.width
        val plotBottom = (size.height - labelHeight - axisGap).coerceAtLeast(1f)
        val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
        fun xFor(time: Long): Float = plotLeft + ((time - startTime).toDouble() / timeSpan).toFloat() * plotWidth
        fun yFor(value: Double): Float = ((upper - value) / (upper - lower)).toFloat() * plotBottom
        val hairline = 0.5.dp.toPx().coerceAtLeast(1f)
        val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))

        // Grid: a solid hairline at each value mark, a dashed one up from each time mark.
        for (tick in yTicks) {
            val y = yFor(tick.at)
            if (y < 0f || y > plotBottom) continue
            drawLine(colors.textSecondary.copy(alpha = 0.22f), Offset(plotLeft, y), Offset(plotRight, y), strokeWidth = hairline)
        }
        for ((tick, layout) in xLabels) {
            val x = xFor(tick.at.toLong())
            if (x < plotLeft || x > plotRight) continue
            drawLine(colors.textSecondary.copy(alpha = 0.3f), Offset(x, 0f), Offset(x, plotBottom), strokeWidth = hairline, pathEffect = dash)
            // Leading-aligned at its mark; one with no room before the edge is left out.
            val labelX = x + 4.dp.toPx()
            if (labelX + layout.size.width <= size.width) {
                drawText(layout, topLeft = Offset(labelX, plotBottom + axisGap))
            }
        }
        for ((tick, layout) in yLabels) {
            val y = yFor(tick.at)
            if (y < 0f || y > plotBottom) continue
            drawText(layout, topLeft = Offset(plotLeft - labelGap - layout.size.width, y - layout.size.height / 2f))
        }

        val xs = FloatArray(points.size) { xFor(points[it].first) }
        val ys = FloatArray(points.size) { yFor(points[it].second) }
        val last = points.size - 1
        val linePath = Path().apply {
            moveTo(xs[0], ys[0])
            for (i in 0 until last) {
                val before = (i - 1).coerceAtLeast(0)
                val after = (i + 2).coerceAtMost(last)
                cubicTo(
                    xs[i] + (xs[i + 1] - xs[before]) / 6f, ys[i] + (ys[i + 1] - ys[before]) / 6f,
                    xs[i + 1] - (xs[after] - xs[i]) / 6f, ys[i + 1] - (ys[after] - ys[i]) / 6f,
                    xs[i + 1], ys[i + 1],
                )
            }
        }
        val areaPath = Path().apply {
            addPath(linePath)
            lineTo(xs[last], plotBottom)
            lineTo(xs[0], plotBottom)
            close()
        }
        drawPath(
            path = areaPath,
            brush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.28f), lineColor.copy(alpha = 0.03f)),
                startY = yFor(maxV),
                endY = plotBottom
            )
        )
        drawPath(
            path = linePath,
            color = lineColor,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        val markRadius = 6.dp.toPx()
        val ruleWidth = 1.dp.toPx()
        selectedIndex?.takeIf { it <= last }?.let { idx ->
            val x = xs[idx]
            drawLine(colors.textSecondary.copy(alpha = 0.35f), Offset(x, 0f), Offset(x, plotBottom), strokeWidth = ruleWidth)
            drawCircle(color = lineColor, radius = markRadius, center = Offset(x, ys[idx]))
        }
        // The span between two fingers: shaded across the plot, green when it ends higher than
        // it started and red when it ends lower, with both ends marked.
        rangeIndices?.takeIf { it.first <= last && it.second <= last }?.let { (a, b) ->
            val up = points[b].second >= points[a].second
            val shade = if (up) upColor else downColor
            drawRect(
                color = shade.copy(alpha = 0.10f),
                topLeft = Offset(minOf(xs[a], xs[b]), 0f),
                size = androidx.compose.ui.geometry.Size(kotlin.math.abs(xs[b] - xs[a]), plotBottom),
            )
            for (idx in listOf(a, b)) {
                drawLine(shade.copy(alpha = 0.6f), Offset(xs[idx], 0f), Offset(xs[idx], plotBottom), strokeWidth = ruleWidth)
                drawCircle(color = shade, radius = markRadius, center = Offset(xs[idx], ys[idx]))
            }
        }
    }
}

/** How to name the selected range beside the change figure (iOS `priceRangeLabel`). */
private fun rangeLabelFor(days: Int, ytdDays: Int): String = when {
    days == ytdDays -> "YTD"
    days == com.kachat.app.repository.PortfolioRepository.ALL_TIME_DAYS -> "All"
    days == 1 -> "24h"
    days == 7 -> "1W"
    days == 30 -> "1M"
    days == 90 -> "3M"
    days == 365 -> "1Y"
    else -> "${days}d"
}

/** A chart header's date: iOS `.dateTime.month().day().year()`, with `.hour().minute()` for price. */
private fun formatChartDate(millis: Long, withTime: Boolean): String {
    val locale = Locale.getDefault()
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, if (withTime) "MMMdyyyyjmm" else "MMMdyyyy")
    return SimpleDateFormat(pattern, locale).format(Date(millis))
}

/**
 * iOS ChartRangeSummary - "Mar 3 → Mar 9" over "↗ 12.34% (+$1.23)": the return across a
 * two-finger range. Times instead of years when both ends are on one day. The screen says how a
 * value is written, so the money charts mask when the eye is on.
 */
@Composable
private fun ChartRangeSummary(
    from: Pair<Long, Double>,
    to: Pair<Long, Double>,
    valueText: @Composable (Double) -> String,
) {
    val colors = LocalAppColors.current
    val amount = to.second - from.second
    val up = amount >= 0
    val percent = if (from.second != 0.0) amount / kotlin.math.abs(from.second) * 100 else null
    val sameDay = Calendar.getInstance().let { c ->
        c.timeInMillis = from.first
        val day = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
        c.timeInMillis = to.first
        day == c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }
    val locale = Locale.getDefault()
    val edgeFormat = SimpleDateFormat(
        android.text.format.DateFormat.getBestDateTimePattern(locale, if (sameDay) "MMMdjmm" else "MMMdyyyy"),
        locale
    )
    val tint = if (up) colors.success else colors.danger
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(edgeFormat.format(Date(from.first)), color = colors.textSecondary, fontSize = 15.sp)
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(11.dp))
            Text(edgeFormat.format(Date(to.first)), color = colors.textSecondary, fontSize = 15.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(if (up) Icons.Default.NorthEast else Icons.Default.SouthEast, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
            if (percent != null) {
                Text("${String.format(Locale.US, "%.2f", kotlin.math.abs(percent))}%", color = tint, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            Text("(${if (up) "+" else "-"}${valueText(kotlin.math.abs(amount))})", color = tint, fontSize = 15.sp)
        }
    }
}

/** Where a chart's line goes while its series is still on the way: iOS's ProgressView. */
@Composable
private fun ChartLoading(height: Dp) {
    Box(modifier = Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
        com.kachat.app.ui.theme.IosActivityIndicator()
    }
}

/**
 * Large money in the form people actually quote it: $2.4B, not $2,412,880,314.00. A market cap
 * written out in full is a wall of digits that has to be counted to be understood.
 */
private fun formatCompactFiat(value: Double, currencyCode: String): String {
    val magnitude = kotlin.math.abs(value)
    val (scaled, suffix) = when {
        magnitude >= 1_000_000_000_000.0 -> magnitude / 1_000_000_000_000.0 to "T"
        magnitude >= 1_000_000_000.0 -> magnitude / 1_000_000_000.0 to "B"
        magnitude >= 1_000_000.0 -> magnitude / 1_000_000.0 to "M"
        magnitude >= 1_000.0 -> magnitude / 1_000.0 to "K"
        else -> magnitude to ""
    }
    val decimals = if (scaled < 10) 2 else if (scaled < 100) 1 else 0
    val sign = if (value < 0) "-" else ""
    return sign + currencySymbolFor(currencyCode) + String.format(Locale.US, "%.${decimals}f", scaled) + suffix
}

/**
 * Where Kaspa sits against every other coin, and what the whole supply is worth at the price
 * above. Both come from the same keyless CoinGecko client the chart already uses; CoinMarketCap's
 * own API needs a key, and the two ranks agree.
 */
@Composable
private fun MarketStatsCard(marketCap: Double?, rank: Int?, currencyCode: String) {
    if (marketCap == null && rank == null) return
    val colors = LocalAppColors.current
    // iOS marketStatsCard: VStack(spacing: 10) of subheadline rows - the label secondary, the
    // figure semibold - with a Divider between, padded 14 on the 18pt glass card.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .iosGlass(18.dp)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (rank != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Rank", color = colors.textSecondary, fontSize = 15.sp)
                Text("#$rank", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        }
        if (rank != null && marketCap != null) {
            HorizontalDivider(color = colors.divider)
        }
        if (marketCap != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Market Cap", color = colors.textSecondary, fontSize = 15.sp)
                Text(
                    formatCompactFiat(marketCap, currencyCode),
                    color = colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
            }
        }
    }
}

/**
 * Whether Portfolio's eye button is masking amounts. A composition local rather than a parameter
 * threaded through a dozen private composables: every amount on this screen already goes through
 * [money], and this is what that reads.
 */
private val LocalPortfolioValuesHidden = compositionLocalOf { false }

/** What the eye shows instead of a number. */
private const val MASKED_AMOUNT = "••••••"

/** iOS chartMoney: an amount in whatever the chart counts in, masked by the eye. */
private fun chartMoneyText(
    value: Double,
    hidden: Boolean,
    flipped: Boolean,
    unitCode: String,
    pair: com.kachat.app.services.ChartPair?,
    currencyCode: String,
): String = when {
    hidden -> MASKED_AMOUNT
    flipped -> formatChartAmount(value, unitCode, pair)
    else -> formatFiatAmount(value, currencyCode)
}

/** Every amount on the Portfolio goes through here, so the eye masks all of them at once. */
@Composable
private fun money(value: Double, currencyCode: String): String =
    if (LocalPortfolioValuesHidden.current) MASKED_AMOUNT else formatFiatAmount(value, currencyCode)

/** A held quantity, masked by the same eye: how much is held says as much as what it is worth. */
@Composable
private fun kas(value: Double, grouped: Boolean = false): String = when {
    LocalPortfolioValuesHidden.current -> MASKED_AMOUNT
    grouped -> "${formatKasAmountGrouped(value)} ${KaspaUnit.symbol}"
    else -> "${formatKasAmount(value)} ${KaspaUnit.symbol}"
}

/**
 * A price in whatever the chart is counting in. Bitcoin and the pairs are written out in full -
 * eight decimals at the least for bitcoin, more for a per-KAS figure, and enough significant
 * digits for an ounce of gold - because a KAS priced in bitcoin rounded to two decimals is zero
 * (iOS c42e9a3).
 */
internal fun formatChartAmount(value: Double, unitCode: String, pair: com.kachat.app.services.ChartPair?): String {
    val fiat = unitCode.length == 3 && pair?.code != unitCode
    if (fiat && unitCode != "btc") return formatUsdPrice(value, unitCode)
    val suffix = when {
        unitCode == "btc" -> " BTC"
        else -> pair?.unitSuffix ?: " ${unitCode.uppercase()}"
    }
    val magnitude = kotlin.math.abs(value)
    val decimals = when {
        magnitude == 0.0 -> 8
        magnitude >= 1 -> 4
        else -> (kotlin.math.ceil(-kotlin.math.log10(magnitude)).toInt() + 4).coerceIn(8, 12)
    }
    return String.format(Locale.US, "%,.${decimals}f", value).trimEnd('0').trimEnd('.') + suffix
}

/**
 * Beside the big number on a chart: what it and the chart count in, and that a tap flips it.
 * Lit up while flipped to the pair; absent when no pair is picked (iOS ChartCurrencyChip).
 */
@Composable
internal fun ChartUnitChip(unitCode: String, flipped: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val tint = if (flipped) Color(0xFFFF9500) else colors.textSecondary
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(unitCode.uppercase(), color = tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
    }
}

/**
 * What a tap on the big number flips the chart to: bitcoin, VOO, gold or silver. One at a time -
 * turning one on turns the others off - or none, and then the number is just a number. A row per
 * pair with its own switch, as on iOS (39adefe).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChartPairSheet(
    selected: com.kachat.app.services.ChartPair?,
    onSelect: (com.kachat.app.services.ChartPair?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalAppColors.current
    com.kachat.app.ui.theme.IosSheetColors {
        val colors = LocalAppColors.current
        ModalBottomSheet(
            scrimColor = com.kachat.app.ui.theme.IosSheetScrim,
            shape = com.kachat.app.ui.theme.IosSheetShape,
            tonalElevation = com.kachat.app.ui.theme.IosSheetTonalElevation,
            windowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars,
            onDismissRequest = onDismiss,
            containerColor = colors.background,
            dragHandle = { BottomSheetDefaults.DragHandle() },
        ) {
            // The sheet runs down behind the navigation bar, as iOS's does behind the home indicator;
            // its content stays above it.
            Column(Modifier.navigationBarsPadding()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("Compare Against", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(
                        "Tap the price or your value to see it in the pair you pick here. One at a time.",
                        color = colors.textSecondary,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(colors.surface),
                    ) {
                        com.kachat.app.services.ChartPair.entries.forEachIndexed { index, pair ->
                            if (index > 0) {
                                HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 56.dp))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    chartPairIcon(pair),
                                    contentDescription = null,
                                    tint = chartPairTint(pair),
                                    modifier = Modifier.size(32.dp).padding(end = 6.dp),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(pair.title, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                    Text(pair.subtitle, color = colors.textSecondary, fontSize = 12.sp)
                                }
                                com.kachat.app.ui.theme.IosSwitch(
                                    checked = selected == pair,
                                    onCheckedChange = { on -> onSelect(if (on) pair else null) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun chartPairIcon(pair: com.kachat.app.services.ChartPair) = when (pair) {
    com.kachat.app.services.ChartPair.BITCOIN -> Icons.Default.CurrencyBitcoin
    com.kachat.app.services.ChartPair.VOO -> Icons.Default.TrendingUp
    com.kachat.app.services.ChartPair.GOLD, com.kachat.app.services.ChartPair.SILVER -> Icons.Default.Hexagon
}

private fun chartPairTint(pair: com.kachat.app.services.ChartPair) = when (pair) {
    com.kachat.app.services.ChartPair.BITCOIN -> Color(0xFFFF9500)
    com.kachat.app.services.ChartPair.VOO -> KaspaTeal
    com.kachat.app.services.ChartPair.GOLD -> Color(0xFFFFCC00)
    com.kachat.app.services.ChartPair.SILVER -> Color(0xFFB0B7BF)
}

@Composable
private fun PortfolioRangeSelector(selectedDays: Int, onSelect: (Int) -> Unit, ytdDays: Int = 1) {
    // YTD is computed when the picker is drawn, so it is right the moment midnight passes, and
    // All is everything there is - CoinGecko's year over Gate.io's history (iOS 20be932, 650b0f7).
    val ranges = listOf(
        1 to "1D",
        7 to "1W",
        30 to "1M",
        90 to "3M",
        ytdDays to "YTD",
        365 to "1Y",
        com.kachat.app.repository.PortfolioRepository.ALL_TIME_DAYS to "All",
    )
    // iOS PortfolioRangePicker: HStack(spacing: 8) of subheadline-semibold labels, each filling
    // its share, 8 above and below; the selected one in the accent on the accent at 20% in a
    // 10pt rounded rectangle, the rest secondary on nothing.
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ranges.forEach { (days, label) ->
            val active = days == selectedDays
            Text(
                text = label,
                color = if (active) KaspaTeal else LocalAppColors.current.textSecondary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) KaspaTeal.copy(alpha = 0.2f) else Color.Transparent)
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
    val priceHistory by viewModel.priceHistory.collectAsState()
    val priceRangeDays by viewModel.priceRangeDays.collectAsState()
    val marketCap by viewModel.marketCap.collectAsState()
    val marketCapRank by viewModel.marketCapRank.collectAsState()
    val currencyCode by viewModel.currency.collectAsState()
    var scrubbed by remember { mutableStateOf<Pair<Long, Double>?>(null) }
    var selectedSpan by remember { mutableStateOf<Pair<Pair<Long, Double>, Pair<Long, Double>>?>(null) }
    // Tap the price to see it in the pair picked under the gear (iOS c42e9a3).
    val chartPair by viewModel.chartPair.collectAsState()
    val chartFlipped by viewModel.chartFlipped.collectAsState()
    val alternateHistory by viewModel.alternatePriceHistory.collectAsState()
    val alternatePrice by viewModel.alternatePrice.collectAsState()
    var showPairSheet by remember { mutableStateOf(false) }
    val flipped = chartFlipped && chartPair != null
    val shownHistory = if (flipped) alternateHistory else priceHistory
    val shownPrice = if (flipped) alternatePrice else currentPriceUsd
    val shownUnit = viewModel.chartUnitCode()
    val ytdDays = viewModel.yearToDateDays()
    val colors = LocalAppColors.current

    Scaffold(
        containerColor = colors.background,
        // Runs on under the dock, as iOS's content runs on under its tab bar.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("KAS Price", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
                navigationIcon = { SheetBarBackButton({ navController.popBackStack() }, Modifier.padding(start = 12.dp)) },
                actions = {
                    // iOS ChartPairSettingsButton: SF "gearshape" - the outlined gear - in the accent.
                    SheetBarIconButton(
                        Icons.Outlined.Settings,
                        "Compare against",
                        onClick = { showPairSheet = true },
                        modifier = Modifier.padding(end = 12.dp),
                        iconSize = 22.dp,
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background)
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
                // The window is edge-to-edge, so adjustResize no longer shrinks it - the app
                // draws BEHIND the keyboard, and the converter's fields ended up under it with
                // nothing to scroll into. With the inset reserved here, the field Compose is
                // bringing into view has somewhere above the keyboard to land.
                .imePadding()
                .padding(16.dp)
                // The last card scrolls clear of the dock it runs under.
                .padding(bottom = LocalDockInset.current),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // iOS header: VStack(spacing: 6). The Kaspa logo + name stay put while scrubbing -
            // only the date + scrubbed price change.
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Image(
                        painter = painterResource(R.drawable.ic_kaspa_logo),
                        contentDescription = null,
                        modifier = Modifier.size(30.dp).clip(CircleShape)
                    )
                    Text("Kaspa", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                }
                scrubbed?.let {
                    Text(formatChartDate(it.first, withTime = true), color = colors.textSecondary, fontSize = 15.sp)
                }
                selectedSpan?.let { (from, to) ->
                    ChartRangeSummary(from, to) { formatChartAmount(it, shownUnit, chartPair) }
                }
                // The change sits UNDER the price rather than beside it. A long price and a
                // long change figure on one line had no room left at larger text sizes or in a
                // currency with a wordy symbol, and something had to shrink or clip. Stacked,
                // neither constrains the other whatever they say.
                // The number and, beside it on its baseline, what it counts in - a tap of either
                // flips the chart into the pair (iOS c42e9a3's chip + tap gesture).
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = chartPair != null) { scrubbed = null; selectedSpan = null; viewModel.flipChart() },
                ) {
                    Text(
                        text = when {
                            scrubbed != null -> formatChartAmount(scrubbed!!.second, shownUnit, chartPair)
                            shownPrice != null -> formatChartAmount(shownPrice!!, shownUnit, chartPair)
                            else -> "—"
                        },
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 38.sp,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.alignByBaseline(),
                    )
                    if (chartPair != null) ChartUnitChip(unitCode = shownUnit, flipped = flipped, modifier = Modifier.alignByBaseline())
                }
                // Read off the series the chart is drawing, so the number and the line can never
                // disagree - and so it answers whichever range button is selected rather than
                // repeating the 24h figure under every one of them. Percent only: the move in
                // currency is the price above minus itself a moment ago, which the chart already
                // draws, and a per-KAS amount at four decimal places says very little.
                val rangeChange = PortfolioViewModel.computeRangeChange(shownHistory)
                if (scrubbed == null && selectedSpan == null && rangeChange != null) {
                    val positive = rangeChange.first >= 0
                    val color = if (positive) colors.success else colors.danger
                    // iOS: arrow.up / arrow.down (footnote), the percent (subheadline semibold)
                    // and the range (caption semibold, secondary), 3 apart.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(
                            if (positive) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                            contentDescription = null,
                            tint = color,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            "${String.format(Locale.US, "%.2f", kotlin.math.abs(rangeChange.second))}%",
                            color = color,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                        Text(
                            rangeLabelFor(priceRangeDays, ytdDays),
                            color = colors.textSecondary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            if (shownHistory.size >= 2) {
                PortfolioBigChart(
                    points = shownHistory,
                    lineColor = KaspaTeal,
                    onScrub = { scrubbed = it },
                    onRange = { selectedSpan = it },
                    height = 260.dp,
                    preciseAxis = flipped,
                )
            } else {
                ChartLoading(260.dp)
            }

            PortfolioRangeSelector(
                selectedDays = priceRangeDays,
                onSelect = { scrubbed = null; selectedSpan = null; viewModel.setPriceRangeDays(it) },
                ytdDays = ytdDays,
            )
            if (showPairSheet) {
                ChartPairSheet(
                    selected = chartPair,
                    onSelect = { viewModel.setChartPair(it) },
                    onDismiss = { showPairSheet = false },
                )
            }

            KasConverterCard(price = currentPriceUsd, currencyCode = currencyCode)
            MarketStatsCard(marketCap = marketCap, rank = marketCapRank, currencyCode = currencyCode)
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
    // Tap the value to see it in the pair picked under the gear: the holdings are the same, the
    // unit changes (iOS c42e9a3).
    val chartPair by viewModel.chartPair.collectAsState()
    val chartFlipped by viewModel.chartFlipped.collectAsState()
    val alternateHistory by viewModel.alternatePriceHistory.collectAsState()
    val priceHistoryForValue by viewModel.priceHistory.collectAsState()
    val alternatePrice by viewModel.alternatePrice.collectAsState()
    var showPairSheet by remember { mutableStateOf(false) }
    val flipped = chartFlipped && chartPair != null
    val shownUnit = viewModel.chartUnitCode()
    val ytdDays = viewModel.yearToDateDays()
    // The holdings priced in the flipped unit: the same KAS, valued point by point against the
    // alternate series rather than the app currency's.
    val shownValueHistory = if (!flipped) valueHistory else {
        val byTime = alternateHistory.toMap()
        valueHistory.mapNotNull { (time, value) ->
            val ownPrice = priceHistoryForValue.lastOrNull { it.first <= time }?.second ?: return@mapNotNull null
            val alternate = byTime[time] ?: alternateHistory.lastOrNull { it.first <= time }?.second ?: return@mapNotNull null
            if (ownPrice <= 0) null else time to value / ownPrice * alternate
        }
    }
    var scrubbed by remember { mutableStateOf<Pair<Long, Double>?>(null) }
    var selectedSpan by remember { mutableStateOf<Pair<Pair<Long, Double>, Pair<Long, Double>>?>(null) }
    // This screen is all amounts, so the eye reaches it too.
    val valuesHidden by viewModel.valuesHidden.collectAsState()
    val colors = LocalAppColors.current

    /** iOS chartMoney: an amount in whatever the chart counts in, masked by the eye. */
    val moneyHidden = valuesHidden
    val moneyPair = chartPair
    val moneyCurrency = currencyCode
    val moneyFlipped = flipped
    val moneyUnit = shownUnit
    val chartMoney: (Double) -> String = { value ->
        chartMoneyText(value, moneyHidden, moneyFlipped, moneyUnit, moneyPair, moneyCurrency)
    }

    CompositionLocalProvider(LocalPortfolioValuesHidden provides valuesHidden) {
    Scaffold(
        containerColor = colors.background,
        // Runs on under the dock, as iOS's content runs on under its tab bar.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Value Over Time", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
                actions = {
                    // iOS ChartPairSettingsButton: SF "gearshape" - the outlined gear - in the accent.
                    SheetBarIconButton(
                        Icons.Outlined.Settings,
                        "Compare against",
                        onClick = { showPairSheet = true },
                        modifier = Modifier.padding(end = 12.dp),
                        iconSize = 22.dp,
                    )
                },
                navigationIcon = { SheetBarBackButton({ navController.popBackStack() }, Modifier.padding(start = 12.dp)) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background)
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
                .padding(16.dp)
                // The last card scrolls clear of the dock it runs under.
                .padding(bottom = LocalDockInset.current),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // iOS header: VStack(spacing: 4).
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Portfolio Value", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                scrubbed?.let {
                    Text(formatChartDate(it.first, withTime = false), color = colors.textSecondary, fontSize = 15.sp)
                }
                selectedSpan?.let { (from, to) ->
                    ChartRangeSummary(from, to) { chartMoney(it) }
                }
                // The change sits UNDER the value rather than beside it - see the note on the
                // price header. A six-figure portfolio and its change had nowhere to go on one
                // line. Tap the value: the same chart in the pair.
                // iOS chartCurrentValue: flipped, the holdings at the pair's latest price - nil,
                // a dash, while that price is still on its way.
                val shownValue = scrubbed?.second
                    ?: (if (flipped) alternatePrice?.let { summary.holdingsKas * it } else summary.currentValue)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = chartPair != null) { scrubbed = null; selectedSpan = null; viewModel.flipChart() },
                ) {
                    Text(
                        shownValue?.let { chartMoney(it) } ?: "—",
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 38.sp,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.alignByBaseline(),
                    )
                    if (chartPair != null) ChartUnitChip(unitCode = shownUnit, flipped = flipped, modifier = Modifier.alignByBaseline())
                }
                // The move across the SELECTED range, so pressing 1W answers "how did this do
                // this week" rather than repeating the 24h figure under every button. Hidden
                // while scrubbing: the big number is then a past value, and a range figure under
                // it would read as that point's own move.
                val rangeChange = PortfolioViewModel.computeRangeChange(shownValueHistory)
                val changeAmount = rangeChange?.first
                val changePercent = rangeChange?.second
                if (scrubbed == null && selectedSpan == null && changeAmount != null && changePercent != null) {
                    val isUp = changeAmount >= 0
                    val tint = if (isUp) colors.success else colors.danger
                    val percentText = "${String.format(Locale.US, "%.2f", kotlin.math.abs(changePercent))}%"
                    // iOS: arrow.up.right / arrow.down.right (caption bold), the move (subheadline
                    // semibold) and the range (caption semibold, secondary), 4 apart.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            if (isUp) Icons.Default.NorthEast else Icons.Default.SouthEast,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            if (valuesHidden) percentText else "${chartMoney(kotlin.math.abs(changeAmount))} ($percentText)",
                            color = tint,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                        )
                        Text(
                            rangeLabelFor(priceRangeDays, ytdDays),
                            color = colors.textSecondary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            if (shownValueHistory.size >= 2) {
                PortfolioBigChart(
                    points = shownValueHistory,
                    lineColor = KaspaTeal,
                    onScrub = { scrubbed = it },
                    onRange = { selectedSpan = it },
                    height = 240.dp,
                    hideValues = valuesHidden,
                    preciseAxis = flipped,
                )
            } else if (flipped && valueHistory.size >= 2) {
                // The pair's series for this range is still on its way.
                ChartLoading(240.dp)
            } else {
                Box(modifier = Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Not enough history yet - check back after a few days of activity.",
                        color = colors.textSecondary,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

            PortfolioRangeSelector(
                selectedDays = priceRangeDays,
                onSelect = { scrubbed = null; selectedSpan = null; viewModel.setPriceRangeDays(it) },
                ytdDays = ytdDays,
            )
            if (showPairSheet) {
                ChartPairSheet(
                    selected = chartPair,
                    onSelect = { viewModel.setChartPair(it) },
                    onDismiss = { showPairSheet = false },
                )
            }

            // The stats stay in the app currency whatever the chart is counting in - they are
            // what was paid and what it is worth, not a comparison (iOS keeps them put too).
            PortfolioValueStatsCard(summary = summary, currencyCode = currencyCode)
        }
            PullToRefreshContainer(state = pullRefreshState, modifier = Modifier.align(Alignment.TopCenter))
        }
    }
    }
}

/**
 * iOS statsCard: a row per figure - the label (subheadline, secondary) on the left, the figure
 * (body semibold) on the right - 14 in and 12 above and below, Dividers between, 4 more above
 * and below the whole on the 18pt glass card. Holdings, Current Value, Total Invested, Total P&L
 * (green or red), then Avg. Buy Price when there is one.
 */
@Composable
private fun PortfolioValueStatsCard(summary: PortfolioSummary, currencyCode: String) {
    val colors = LocalAppColors.current
    val hidden = LocalPortfolioValuesHidden.current
    val plColor = if (summary.totalPL >= 0) colors.success else colors.danger
    val plPercent = "${String.format(Locale.US, "%.1f", summary.totalPLPercent)}%"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .iosGlass(18.dp)
            .padding(vertical = 4.dp)
    ) {
        PortfolioStatRow(
            stringResource(R.string.holdings),
            if (hidden) "$MASKED_AMOUNT ${KaspaUnit.symbol}" else formatPortfolioKas(summary.holdingsKas)
        )
        HorizontalDivider(color = colors.divider)
        PortfolioStatRow(stringResource(R.string.current_value), money(summary.currentValue, currencyCode))
        HorizontalDivider(color = colors.divider)
        PortfolioStatRow(stringResource(R.string.total_invested), money(summary.totalInvested, currencyCode))
        HorizontalDivider(color = colors.divider)
        PortfolioStatRow(
            stringResource(R.string.total_p_l),
            if (hidden) plPercent else "${formatFiatAmount(summary.totalPL, currencyCode)} ($plPercent)",
            valueColor = plColor
        )
        summary.averageBuyPriceUsd?.let { avg ->
            HorizontalDivider(color = colors.divider)
            PortfolioStatRow("Avg. Buy Price", formatUsdPrice(avg, currencyCode))
        }
    }
}

/** One label / figure line of a stats card (iOS statRow, rewardRow). */
@Composable
private fun PortfolioStatRow(label: String, value: String, valueColor: Color = LocalAppColors.current.textPrimary) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = LocalAppColors.current.textSecondary, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, textAlign = TextAlign.End)
    }
}

/** iOS PortfolioFormat.kas: grouped, up to four decimals, then the KAS symbol. */
private fun formatPortfolioKas(value: Double): String {
    val format = java.text.DecimalFormat("#,##0.####", java.text.DecimalFormatSymbols(Locale.US))
    return format.format(value) + " " + KaspaUnit.symbol
}

/**
 * One ledger row as iOS's `transactionRow`: the type's filled circle symbol (title2, green buy /
 * red sell / accent transfer), then "Buy"/"Sell"/"Transfer" (body medium, with the yellow
 * warning while its price is pending), the date and time (caption) and the note (caption, one
 * line); on the right the KAS amount (body medium) over its value (caption). The caller draws
 * the card it sits on.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransactionRow(
    tx: PortfolioTransactionEntity,
    onClick: () -> Unit,
    /** Null hides the bin - the Move to Portfolio sheet shows the row only as what is moving. */
    onDelete: (() -> Unit)?,
    currencyCode: String,
    modifier: Modifier = Modifier,
    /** iOS's list row: 20 in from the card's sides, 4 + the row's own 8 above and below. */
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    selecting: Boolean = false,
    picked: Boolean = false,
    onToggle: () -> Unit = {},
    /** Long press - moving the row to another portfolio. Not attached in Select mode. */
    onLongClick: (() -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val isBuy = tx.type == "buy"
    // A transfer is a record only - a two-way arrow in the accent colour (iOS 7423330).
    val isTransfer = tx.type == "transfer"
    val typeTint = when {
        isTransfer -> KaspaTeal
        isBuy -> colors.success
        else -> colors.danger
    }
    val amountKas = tx.amountSompi / 100_000_000.0
    // Either marker - a row synced from an iPhone carries iOS's (iOS PortfolioTransactionsView).
    val needsPrice = isPricePending(tx.notes)
    // iOS `.formatted(date: .abbreviated, time: .shortened)`: "Oct 16, 2026 at 11:28 AM".
    val dateStr = remember(tx.timestampMillis) {
        SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.US).format(Date(tx.timestampMillis))
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            // A picked row is tinted rather than restyled, so the list stays the same list.
            .background(if (picked) KaspaTeal.copy(alpha = 0.10f) else Color.Transparent)
            .combinedClickable(
                onClick = if (selecting) onToggle else onClick,
                onLongClick = if (!selecting && onLongClick != null) {
                    {
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        onLongClick()
                    }
                } else null,
            )
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Icon(
                if (picked) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = if (picked) "Selected" else "Not selected",
                tint = if (picked) KaspaTeal else colors.textSecondary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
        }
        // arrow.down.circle.fill / arrow.up.circle.fill / arrow.left.arrow.right.circle.fill:
        // a solid circle in the type's colour with the arrow cut out of it.
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(typeTint),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                when {
                    isTransfer -> Icons.Default.SwapHoriz
                    isBuy -> Icons.Default.ArrowDownward
                    else -> Icons.Default.ArrowUpward
                },
                contentDescription = null,
                tint = colors.surface,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        isTransfer -> stringResource(R.string.portfolio_type_transfer)
                        isBuy -> "Buy"
                        else -> "Sell"
                    },
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 17.sp
                )
                if (needsPrice) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = stringResource(R.string.price_needed),
                        // iOS systemYellow (dark).
                        tint = Color(0xFFFFD60A),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Text(dateStr, color = colors.textSecondary, fontSize = 12.sp)
            tx.notes?.takeIf { it.isNotEmpty() }?.let { notes ->
                Text(notes, color = colors.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(kas(amountKas, grouped = true), color = colors.textPrimary, fontWeight = FontWeight.Medium, fontSize = 17.sp)
            Text(money(tx.fiatValue, currencyCode), color = colors.textSecondary, fontSize = 12.sp)
        }
        // iOS deletes with a swipe; there is no swipe here, so the bin stays on the row.
        if (!selecting && onDelete != null) {
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onDelete, modifier = Modifier.size(20.dp)) {
                Icon(Icons.Default.Delete, "Delete", tint = colors.danger, modifier = Modifier.size(18.dp))
            }
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
) {
    // "buy" | "sell" | "transfer" (iOS 7423330 - the editor's Buy / Sell / Transfer).
    var type by remember {
        mutableStateOf(
            existing?.type?.takeIf { it == "buy" || it == "sell" || it == "transfer" }
                ?: "buy"
        )
    }
    val isTransfer = type == "transfer"
    var quantityText by remember {
        mutableStateOf(
            existing?.let { formatKasAmount(it.amountSompi / 100_000_000.0) }
                ?: ""
        )
    }
    // Editing: derive price-per-coin from the stored total rather than the live price, so
    // reopening an old entry shows what was actually paid, not today's price. Fee isn't stored
    // separately (see PortfolioRepository), so it isn't recoverable into its own field here —
    // the derived price-per-coin already nets it out, and the total still matches exactly
    // unless the user changes quantity/price/fee themselves.
    var priceText by remember {
        mutableStateOf(
            existing?.let {
                val kas = it.amountSompi / 100_000_000.0
                if (kas > 0) String.format(Locale.US, "%.8f", it.fiatValue / kas).trimEnd('0').trimEnd('.') else ""
            } ?: currentPriceUsd?.let { String.format(Locale.US, "%.8f", it).trimEnd('0').trimEnd('.') } ?: ""
        )
    }
    var feeText by remember { mutableStateOf("") }
    var notesText by remember { mutableStateOf(existing?.notes ?: "") }
    var timestampMillis by remember { mutableStateOf(existing?.timestampMillis ?: System.currentTimeMillis()) }
    var showDatePicker by remember { mutableStateOf(false) }

    // Pasted amounts keep their commas and symbols - see parseAmount.
    val quantity = parseAmount(quantityText)
    val pricePerCoin = parseAmount(priceText)
    val fee = parseAmount(feeText) ?: 0.0
    val total = if (quantity != null && pricePerCoin != null) {
        val base = quantity * pricePerCoin
        when (type) {
            "buy" -> base + fee
            "sell" -> base - fee
            // What the KAS was worth when it moved - a note on the row, counted nowhere.
            else -> base
        }
    } else null
    // A transfer only needs its amount: it has no price that counts for anything.
    val isValid = quantity != null && quantity > 0 && (isTransfer || (pricePerCoin != null && pricePerCoin > 0))

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
                    listOf("Buy" to "buy", "Sell" to "sell", stringResource(R.string.portfolio_type_transfer) to "transfer").forEach { (label, value) ->
                        Surface(
                            color = if (type == value) KaspaTeal else LocalAppColors.current.surfaceVariant,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.weight(1f).clickable { type = value }
                        ) {
                            Text(
                                label,
                                color = if (type == value) Color.Black else LocalAppColors.current.textPrimary,
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                            )
                        }
                    }
                }
                if (isTransfer) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        com.kachat.app.util.KaspaUnit.label(stringResource(R.string.portfolio_transfer_footer)),
                        color = LocalAppColors.current.textSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
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
                    Text(KaspaUnit.label(stringResource(R.string.kas_2)), color = LocalAppColors.current.textSecondary)
                }
                Spacer(Modifier.height(12.dp))

                // Full width, stacked rather than side-by-side — a half-width field cut off KAS
                // prices with several decimal digits (e.g. "0.02874099"), which didn't fit next
                // to Quantity in a shared row and just clipped at the field's edge.
                com.kachat.app.ui.theme.IosTextField(
                    value = quantityText,
                    // Thousands commas while shown, stored plain (iOS 40c5258).
                    onValueChange = { quantityText = KaspaAmountGrouping.ungrouped(it, previous = quantityText) },
                    visualTransformation = KaspaAmountGrouping.visualTransformation,
                    label = { Text(stringResource(R.string.quantity)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                com.kachat.app.ui.theme.IosTextField(
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

                // A transfer has no fee: it is a record of your own KAS moving.
                if (!isTransfer) {
                    com.kachat.app.ui.theme.IosTextField(
                        value = feeText,
                        onValueChange = { feeText = it },
                        label = { Text(stringResource(R.string.fee_usd_optional)) },
                        leadingIcon = { Text(currencySymbolFor(currencyCode), color = LocalAppColors.current.textSecondary) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                }
                com.kachat.app.ui.theme.IosTextField(
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
                    Text(
                        when (type) {
                            "buy" -> "Total Spent"
                            "sell" -> "Total Received"
                            else -> stringResource(R.string.portfolio_value_at_the_time)
                        },
                        color = LocalAppColors.current.textSecondary,
                        fontSize = 12.sp
                    )
                    Text(
                        text = if (total != null) money(total, currencyCode) else "${currencySymbolFor(currencyCode)}0",
                        color = LocalAppColors.current.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                }
                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = {
                        // A transfer with no price is still a complete record.
                        if (isValid) onSave(type, quantity!!, total ?: 0.0, timestampMillis, notesText.ifBlank { null })
                    },
                    enabled = isValid,
                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, disabledContainerColor = LocalAppColors.current.surfaceVariant),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text(
                        if (existing != null) "Save Changes" else "Add Transaction",
                        color = if (isValid) Color.Black else LocalAppColors.current.textSecondary,
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
                        Text(stringResource(R.string.delete_transaction), color = LocalAppColors.current.danger, fontWeight = FontWeight.Bold)
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
        com.kachat.app.ui.theme.IosAlertDialog(
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
        // groupedFromCanonical, not grouped: formatFullPrecision writes a "." decimal point,
        // and in a locale where "." IS the grouping separator, grouped() would strip it and read
        // "1200000.00" as 120000000.
        if (from == "kas") {
            val kas = com.kachat.app.util.DecimalInputFormat.value(kasText)
            fiatText = kas?.let {
                com.kachat.app.util.DecimalInputFormat.groupedFromCanonical(formatFullPrecision(it * rate))
            } ?: ""
        } else {
            val fiat = com.kachat.app.util.DecimalInputFormat.value(fiatText)
            kasText = fiat?.let {
                com.kachat.app.util.DecimalInputFormat.groupedFromCanonical(formatFullPrecision(it / rate))
            } ?: ""
        }
    }

    // Seeds the first value, and keeps the derived side honest when the price or currency moves.
    LaunchedEffect(price, currencyCode) { recompute(editing) }

    // iOS KasConverterCard: "Converter" (headline), a row per side, then the rate (caption,
    // secondary) - VStack(spacing: 12), padded 14 on the 18pt glass card.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .iosGlass(18.dp)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Converter", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)

        ConverterRow(
            title = "KAS",
            text = kasText,
            // Grouped as you type - "1200000" is a number you have to count digits on.
            onTextChange = {
                kasText = com.kachat.app.util.DecimalInputFormat.grouped(it)
                editing = "kas"
                recompute("kas")
            },
            trailing = "KAS",
        )
        ConverterRow(
            title = currencyCode.uppercase(Locale.US),
            text = fiatText,
            onTextChange = {
                fiatText = com.kachat.app.util.DecimalInputFormat.grouped(it)
                editing = "fiat"
                recompute("fiat")
            },
            trailing = currencySymbolFor(currencyCode),
        )

        Text(
            // Not formatUsdPrice: that rounds to 5 decimals under a dollar, which for a
            // sub-cent coin throws away most of the rate the converter is applying.
            text = if (price != null) {
                "1 KAS = ${currencySymbolFor(currencyCode)}${formatFullPrecision(price)}"
            } else {
                "Waiting for a price..."
            },
            color = colors.textSecondary,
            fontSize = 12.sp
        )
    }
}

/**
 * One side of the converter, as iOS draws it: the currency (subheadline semibold, secondary, 44
 * wide) on the left, the amount right-aligned in 20pt semibold, and the unit's symbol
 * (subheadline, secondary, 44 wide, trailing) after it - 8 above and below and 12 in, on the
 * label colour at 6% in a 12pt rounded rectangle. No outline, no floating label.
 */
@Composable
private fun ConverterRow(title: String, text: String, onTextChange: (String) -> Unit, trailing: String) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.textPrimary.copy(alpha = 0.06f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            title,
            color = colors.textSecondary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            maxLines = 1,
            modifier = Modifier.width(44.dp),
        )
        IosNumberField(
            value = text,
            onValueChange = onTextChange,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
            // The text sits at the right, so a tap usually lands left of it and the cursor
            // started before the number ("|1"). Editing starts at the end instead (iOS c130dc1).
            cursorAtEndOnFocus = true,
        )
        Text(
            trailing,
            color = colors.textSecondary,
            fontSize = 15.sp,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(44.dp),
        )
    }
}

/**
 * A bare SwiftUI-style `TextField("0", ...)` with the decimal pad: 20pt semibold, the placeholder
 * "0" in the faint label colour while it is empty, the accent caret. No box of its own - the
 * caller draws the row it sits in. [cursorAtEndOnFocus] puts the cursor after the number when
 * editing starts, wherever the tap landed.
 */
@Composable
private fun IosNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    textAlign: TextAlign,
    modifier: Modifier = Modifier,
    cursorAtEndOnFocus: Boolean = false,
) {
    val colors = LocalAppColors.current
    val style = TextStyle(color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = textAlign)
    // The String overload's own bookkeeping, held here so the selection can be moved: the text
    // always comes from [value], the selection is the field's (clamped to the text).
    var fieldState by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(value)) }
    val fieldValue = fieldState.copy(text = value)
    val currentText by androidx.compose.runtime.rememberUpdatedState(value)
    var focused by remember { mutableStateOf(false) }
    // After the focusing tap has placed its cursor (same event, before this effect runs), as
    // iOS waits a runloop turn - so the tap can't put it back.
    LaunchedEffect(focused) {
        if (focused && cursorAtEndOnFocus) {
            fieldState = androidx.compose.ui.text.input.TextFieldValue(
                text = currentText,
                selection = androidx.compose.ui.text.TextRange(currentText.length),
            )
        }
    }
    BasicTextField(
        value = fieldValue,
        onValueChange = { next ->
            fieldState = next
            // A cursor move alone is not an edit.
            if (next.text != value) onValueChange(next.text)
        },
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(KaspaTeal),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Box(contentAlignment = if (textAlign == TextAlign.End) Alignment.CenterEnd else Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text("0", style = style.copy(color = colors.textTertiary), maxLines = 1)
                }
                inner()
            }
        },
    )
}

/**
 * Full precision, with the padding trimmed off.
 *
 * Fixed decimals do not work in either direction here: two decimals on the fiat side rounds 1 KAS
 * to "0.05" and throws the rate away, and four on the KAS side is coarser than the eight sompi
 * actually carries. So this writes eight decimals - Kaspa's own precision - and then drops the
 * trailing zeros, keeping two so a whole amount still reads as money.
 *
 * Deliberately Locale.US: the result is written straight back into a text field the user can keep
 * editing, and a grouping separator would make it unparseable on the way back in.
 */
private fun formatFullPrecision(value: Double): String {
    var text = String.format(Locale.US, "%.8f", value)
    while (text.endsWith("0") && text.substringAfter('.').length > 2) {
        text = text.dropLast(1)
    }
    return text
}

/**
 * Reads a number the way a person typed or pasted it: "1,234.56" (grouping commas), "1.234,56"
 * or "1,5" (decimal-comma locales), "$ 9.60", " 12 " - every shape a copied amount arrives in.
 * A decimal keypad emits the device locale's separator, and `toDoubleOrNull` refuses most of the
 * rest, which made a pasted amount silently fail to add. Mirrors iOS's PortfolioNumber (266131a).
 */
internal fun parseAmount(text: String): Double? {
    var cleaned = text.filter { it.isDigit() || it == ',' || it == '.' || it == '-' }
    if (cleaned.isEmpty()) return null
    val commas = cleaned.count { it == ',' }
    val dots = cleaned.count { it == '.' }
    when {
        commas > 0 && dots > 0 -> {
            // Both present: whichever comes last is the decimal mark, the other is grouping.
            cleaned = if (cleaned.lastIndexOf(',') > cleaned.lastIndexOf('.')) {
                cleaned.replace(".", "").replace(',', '.')
            } else {
                cleaned.replace(",", "")
            }
        }
        commas > 0 -> {
            // Commas only: one comma followed by anything but exactly three digits is a decimal
            // comma ("1,5"); otherwise they are thousands separators ("1,234,567").
            val parts = cleaned.split(",")
            cleaned = if (commas == 1 && parts.size == 2 && parts[1].length != 3) {
                cleaned.replace(',', '.')
            } else {
                cleaned.replace(",", "")
            }
        }
        // "1.234.567" - dots as grouping.
        dots > 1 -> cleaned = cleaned.replace(".", "")
    }
    // "NaN" and "Infinity" parse as doubles; neither is an amount (iOS 1f129d6).
    return cleaned.toDoubleOrNull()?.takeIf { it.isFinite() }
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
        // iOS PortfolioSparkline: a 1.6pt accent line.
        drawPath(path, color = KaspaTeal, style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * A pickaxe, drawn rather than borrowed.
 *
 * The nearest Material icon for mining was `Hardware`, which is a computer chip. The figure it sits
 * beside is network hashrate, and the shorthand every miner already reads is a pick. Material has
 * none, so it is drawn on a 24x24 grid: a filled, bowed, double-pointed head over a tapered handle
 * with a rounded grip, the whole thing turned 45 degrees so the handle runs bottom-left to
 * top-right - the tilt is what makes a reader see a pick rather than an anchor. Two strokes read
 * as a hook; a filled head reads as a tool. Same geometry as the iOS and desktop marks, so all
 * three agree (iOS 62288c2).
 */
@Composable
private fun PickaxeIcon(iconSize: Dp = 24.dp, tint: Color = KaspaTeal) {
    Canvas(modifier = Modifier.size(iconSize)) {
        val unit = minOf(size.width, size.height) / 24f
        fun x(value: Float) = value * unit
        val path = Path().apply {
            // Head: outer arc up and over, inner arc back, meeting at the two tips.
            moveTo(x(1.2f), x(9.6f))
            cubicTo(x(6f), x(1f), x(18f), x(1f), x(22.8f), x(9.6f))
            cubicTo(x(18f), x(6.6f), x(6f), x(6.6f), x(1.2f), x(9.6f))
            close()
            // Handle: from under the head down to a rounded grip, tapering slightly.
            moveTo(x(10.5f), x(5.4f))
            lineTo(x(13.5f), x(5.4f))
            lineTo(x(13.1f), x(21.4f))
            arcTo(
                rect = androidx.compose.ui.geometry.Rect(
                    left = x(12f) - x(1.1f), top = x(21.4f) - x(1.1f),
                    right = x(12f) + x(1.1f), bottom = x(21.4f) + x(1.1f),
                ),
                startAngleDegrees = 0f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false,
            )
            lineTo(x(10.9f), x(21.4f))
            close()
        }
        // Turned about the centre of the grid, then placed in the icon's box.
        rotate(degrees = 45f, pivot = androidx.compose.ui.geometry.Offset(x(12f), x(12f))) {
            drawPath(path, color = tint)
        }
    }
}

/**
 * This calendar year's realized profit or loss in the active portfolio: this year's sells against
 * the cost of the KAS they sold, oldest buys first (FIFO - [PortfolioViewModel.computeRealizedPL]).
 * iOS 61eff0f `realizedPLCard`.
 */
@Composable
private fun RealizedPLCard(pl: com.kachat.app.models.RealizedPL, currencyCode: String) {
    val colors = LocalAppColors.current
    val hidden = LocalPortfolioValuesHidden.current
    val notes = buildList {
        if (pl.sellCount == 0) {
            add(stringResource(R.string.portfolio_no_sells_this_year))
        } else {
            add(stringResource(R.string.portfolio_sells_fifo, pl.sellCount))
            if (pl.uncoveredKas > 0) {
                add(stringResource(R.string.portfolio_sold_uncovered, kas(pl.uncoveredKas, grouped = true)))
            }
        }
        if (pl.pendingPriceCount > 0) add(stringResource(R.string.portfolio_prices_loading))
    }
    val valueText = if (hidden) MASKED_AMOUNT else (if (pl.amount > 0) "+" else "") + formatFiatAmount(pl.amount, currencyCode)
    val valueColor = when {
        hidden || pl.sellCount == 0 -> colors.textPrimary
        pl.amount >= 0 -> colors.success
        else -> colors.danger
    }
    PortfolioInfoCard(icon = Icons.Outlined.Verified) {
        Text(
            stringResource(R.string.portfolio_realized_pl_year, pl.year.toString()),
            color = colors.textSecondary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp
        )
        Text(valueText, color = valueColor, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1)
        Text(notes.joinToString(" "), color = colors.textSecondary, fontSize = 12.sp)
    }
}

/**
 * Network fees the active portfolio's imported addresses paid - in KAS, and in the app currency
 * at each day's price. Fed by Add Chatting Address (and Add Kaspa Address). iOS 61eff0f `feesCard`.
 */
@Composable
private fun FeesSpentCard(fees: com.kachat.app.models.PortfolioFeeSummary, currencyCode: String) {
    val colors = LocalAppColors.current
    val hidden = LocalPortfolioValuesHidden.current
    PortfolioInfoCard(icon = Icons.Outlined.LocalGasStation) {
        Text(stringResource(R.string.portfolio_fees_spent), color = colors.textSecondary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        if (fees.count == 0) {
            Text("—", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(stringResource(R.string.portfolio_fees_empty_hint), color = colors.textSecondary, fontSize = 12.sp)
        } else {
            Text(
                if (hidden) MASKED_AMOUNT else formatFeeKas(fees.totalKas),
                color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1
            )
            Text(
                if (hidden) MASKED_AMOUNT else formatFiatAmount(fees.totalFiat, currencyCode),
                color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp
            )
            Text(
                if (fees.unpricedCount > 0) stringResource(R.string.portfolio_fees_count_loading, fees.count)
                else stringResource(R.string.portfolio_fees_count_priced, fees.count),
                color = colors.textSecondary, fontSize = 12.sp
            )
        }
    }
}

/** Fees are fractions of a KAS - up to eight places, so a month of messages doesn't read 0. */
private fun formatFeeKas(value: Double): String {
    val format = java.text.DecimalFormat("#,##0.00######", java.text.DecimalFormatSymbols(Locale.US))
    return format.format(value) + " " + KaspaUnit.symbol
}

/**
 * The Realized P&L / Fees Spent card shape (iOS realizedPLCard / feesCard): HStack(alignment:
 * .top, spacing: 12) of the accent outline glyph (20pt semibold, in a 30-wide column, 2 down)
 * and a VStack(spacing: 3) of lines, padded 14 on the 18pt glass card.
 */
@Composable
private fun PortfolioInfoCard(icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .iosGlass(18.dp)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(modifier = Modifier.width(30.dp).padding(top = 2.dp), contentAlignment = Alignment.TopCenter) {
            Icon(icon, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(26.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp), content = content)
    }
}

/**
 * iOS hashrateCard: HStack(spacing: 12) of the 20pt pickaxe (30 wide), a VStack(spacing: 2) of
 * "Network Hashrate" (subheadline semibold, secondary) over the figure (20 bold), a spacer of at
 * least 8, the 96x34 sparkline and a caption chevron - padded 14 on the 18pt glass card.
 */
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
            .iosGlass(18.dp)
            .clickable { onOpen() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(modifier = Modifier.width(30.dp), contentAlignment = Alignment.Center) {
            PickaxeIcon(iconSize = 20.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // On the iPhone the HStack leaves this column about 126pt, and SF's wider
            // "Network Hashrate" breaks onto two lines there. Roboto is narrower and would
            // fit on one, so the label gets the width at which it breaks the same way.
            Text(
                "Network Hashrate",
                color = colors.textSecondary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                modifier = Modifier.widthIn(max = 112.dp)
            )
            Text(
                text = hashrate?.let { formatHashrate(it) } ?: "—",
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                maxLines = 1
            )
        }
        Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
        // A sparkline of the recent window, so the card says which way it is going without the
        // user having to open it.
        if (history.size >= 2) {
            HashrateSparkline(
                points = history.takeLast(90),
                modifier = Modifier.width(96.dp).height(34.dp)
            )
        }
        PortfolioChevron()
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
    val blockReward by viewModel.blockRewardKas.collectAsState()
    val nextBlockReward by viewModel.nextBlockRewardKas.collectAsState()
    val nextHalvingTimestamp by viewModel.nextHalvingTimestamp.collectAsState()
    val price by viewModel.currentPriceUsd.collectAsState()
    val currencyCode by viewModel.currency.collectAsState()
    var scrubbed by remember { mutableStateOf<Pair<Long, Double>?>(null) }
    var selectedSpan by remember { mutableStateOf<Pair<Pair<Long, Double>, Pair<Long, Double>>?>(null) }
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
        // Runs on under the dock, as iOS's content runs on under its tab bar.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Network Hashrate", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
                navigationIcon = { SheetBarBackButton({ navController.popBackStack() }, Modifier.padding(start = 12.dp)) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background)
            )
        }
    ) { padding ->
        // iOS: `.refreshable { await networkStats.refreshIfNeeded(force: true) }`.
        val pullRefreshState = rememberPullToRefreshState()
        LaunchedEffect(pullRefreshState.isRefreshing) {
            if (pullRefreshState.isRefreshing) {
                viewModel.refreshHashrateNow()
                pullRefreshState.endRefresh()
            }
        }
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
                // Same reason as the price screen: edge-to-edge means the window is not resized
                // for the keyboard, so the mining estimate's field needs the inset reserved here
                // to have anywhere above the keyboard to scroll into.
                .imePadding()
                .padding(16.dp)
                // The last card scrolls clear of the dock it runs under.
                .padding(bottom = LocalDockInset.current),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // iOS header: VStack(spacing: 6) - the 20pt pickaxe in the accent and "Kaspa
            // Network" (title3 semibold) 8 apart, then the date, a range's summary and the figure.
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PickaxeIcon(iconSize = 20.dp)
                    Text("Kaspa Network", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                }
                scrubbed?.let {
                    Text(formatChartDate(it.first, withTime = false), color = colors.textSecondary, fontSize = 15.sp)
                }
                selectedSpan?.let { (from, to) ->
                    ChartRangeSummary(from, to) { formatHashrate(it) }
                }
                Text(
                    text = (scrubbed?.second ?: current)?.let { formatHashrate(it) } ?: "—",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 38.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }

            if (visible.size >= 2) {
                PortfolioBigChart(
                    points = visible,
                    lineColor = KaspaTeal,
                    onScrub = { scrubbed = it },
                    onRange = { selectedSpan = it },
                    height = 260.dp,
                )
            } else {
                ChartLoading(260.dp)
            }

            // iOS rangePicker: capsules 8 apart, subheadline semibold, 7 above and below - the
            // selected one in the accent on the accent at 18%, the rest secondary on the label
            // colour at 6%.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30 to "1M", 90 to "3M", 365 to "1Y", 0 to "All").forEach { (days, label) ->
                    val active = days == rangeDays
                    Text(
                        text = label,
                        color = if (active) KaspaTeal else colors.textSecondary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .background(if (active) KaspaTeal.copy(alpha = 0.18f) else colors.textPrimary.copy(alpha = 0.06f))
                            .clickable { scrubbed = null; rangeDays = days }
                            .padding(vertical = 7.dp)
                    )
                }
            }

            // What a block pays now, and what it pays after the next step down. Kaspa's emission
            // steps every month rather than halving every four years, so "next" is usually weeks
            // away - which is what makes it worth showing beside the current figure.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .iosGlass(18.dp)
                    .padding(vertical = 4.dp)
            ) {
                PortfolioStatRow("Block Reward", blockReward?.let { formatPortfolioKas(it) } ?: "—")
                HorizontalDivider(color = colors.divider)
                PortfolioStatRow("Next Block Reward", nextBlockReward?.let { formatPortfolioKas(it) } ?: "—")
                HorizontalDivider(color = colors.divider)
                PortfolioStatRow(
                    "Next Block Reward Reduction",
                    nextHalvingTimestamp?.let {
                        java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
                            .format(java.util.Date(it * 1000L))
                    } ?: "—"
                )
            }

            MiningEstimateCard(
                networkHashratePHs = current,
                blockRewardKas = blockReward,
                price = price,
                currencyCode = currencyCode,
            )

            // iOS explanation: "About Hashrate" (headline) over the text (subheadline,
            // secondary), 8 apart, padded 14 on the 18pt glass card.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .iosGlass(18.dp)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("About Hashrate", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text(
                    "Hashrate is how much computing power miners are pointing at Kaspa. A higher " +
                        "hashrate means more work securing the chain, and it moves with mining " +
                        "profitability rather than with the price directly. Figures come from the " +
                        "Kaspa REST API set in Connection Settings, at one sample per day.",
                    color = colors.textSecondary,
                    fontSize = 15.sp,
                    lineHeight = 20.sp
                )
            }
        }
            PullToRefreshContainer(state = pullRefreshState, modifier = Modifier.align(Alignment.TopCenter))
        }
    }
}

// MARK: mining estimate

/**
 * "If I point this much hashrate at Kaspa, what do I earn?" Mirrors iOS's MiningEstimateCard.
 *
 * Straight proportional share: your hashrate over the network's, times what the network pays out.
 * Deliberately no pool fee, power cost or luck variance - those are the miner's own numbers, and
 * guessing at them would make this look more precise than it is.
 */
@Composable
private fun MiningEstimateCard(
    /** The network's hashrate in PH/s. */
    networkHashratePHs: Double?,
    blockRewardKas: Double?,
    price: Double?,
    currencyCode: String,
) {
    val colors = LocalAppColors.current
    // Miners talk in TH/s (one KS5 Pro is about 21), so that is the default. The unit is part of
    // the input because typing 21 and meaning PH/s is a thousandfold error, which is exactly the
    // mistake this screen itself was shipping.
    val units = listOf("GH/s" to 1e-6, "TH/s" to 1e-3, "PH/s" to 1.0)
    // Empty, not a sample figure. A prefilled 21 renders a full estimate the moment the card
    // appears, which reads as YOUR earnings until you notice the number is not yours.
    var amountText by remember { mutableStateOf("") }
    var unitIndex by remember { mutableStateOf(1) }

    // KAS the whole network pays out per day: reward per block times blocks per second.
    val dailyEmission = blockRewardKas?.takeIf { it > 0 }
        ?.let { it * KaspaNetworkStatsService.BLOCKS_PER_SECOND * 86_400 }
    val amount = com.kachat.app.util.DecimalInputFormat.value(amountText)
    val dailyKas = if (dailyEmission != null && networkHashratePHs != null &&
        networkHashratePHs > 0 && amount != null && amount > 0
    ) {
        dailyEmission * (amount * units[unitIndex].second / networkHashratePHs)
    } else {
        null
    }

    // iOS: "Mining Estimate" (headline), then the field and the unit's segmented picker side by
    // side, then the payouts or the hint - VStack(spacing: 12), padded 14 on the 18pt glass card.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .iosGlass(18.dp)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Mining Estimate", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IosNumberField(
                value = amountText,
                // Grouped as you type - "1200000" is a number you have to count digits on.
                onValueChange = { amountText = com.kachat.app.util.DecimalInputFormat.grouped(it) },
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.textPrimary.copy(alpha = 0.06f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            IosSegmentedPicker(
                titles = units.map { it.first },
                selected = unitIndex,
                onSelect = { unitIndex = it },
                modifier = Modifier.width(190.dp),
            )
        }

        if (dailyKas != null) {
            Column(
                modifier = Modifier.padding(top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MiningPayoutRow("Per day", dailyKas, price, currencyCode)
                HorizontalDivider(color = colors.divider)
                MiningPayoutRow("Per week", dailyKas * 7, price, currencyCode)
                HorizontalDivider(color = colors.divider)
                // 30 days, not a calendar month: the reward steps down monthly anyway, so
                // precision past "about a month" would be false.
                MiningPayoutRow("Per month", dailyKas * 30, price, currencyCode)
            }

            Text(
                text = "At ${formatHashrate(networkHashratePHs!!)} network hashrate and a " +
                    "${String.format(Locale.US, "%.4f", blockRewardKas!!)} ${KaspaUnit.symbol} block reward. " +
                    "Before pool fees, power and luck, and both figures move.",
                color = colors.textSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        } else {
            Text(
                "Enter your hashrate to estimate earnings.",
                color = colors.textSecondary,
                fontSize = 15.sp
            )
        }
    }
}

/**
 * iOS's segmented Picker: the segments on a tinted track (systemFill-like grey), the chosen one
 * on a raised thumb - white in light mode, systemGray3-dark in dark - in 13pt, semibold when
 * chosen.
 */
@Composable
private fun IosSegmentedPicker(titles: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val track = Color(0xFF767680).copy(alpha = if (colors.isDark) 0.24f else 0.12f)
    val thumb = if (colors.isDark) Color(0xFF636366) else Color.White
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(track)
            .padding(2.dp)
    ) {
        titles.forEachIndexed { index, title ->
            val active = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (active) thumb else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    title,
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

/** iOS payoutRow: the period (subheadline, secondary), and on the right the KAS (subheadline
 *  semibold) over what it is worth (caption, secondary), on the first baseline. */
@Composable
private fun MiningPayoutRow(title: String, kas: Double, price: Double?, currencyCode: String) {
    val colors = LocalAppColors.current
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(title, color = colors.textSecondary, fontSize = 15.sp, modifier = Modifier.weight(1f).alignByBaseline())
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.alignByBaseline()) {
            Text(
                formatPortfolioKas(kas),
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
            if (price != null && price > 0) {
                Text(
                    formatFiatAmount(kas * price, currencyCode),
                    color = colors.textSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }
}
