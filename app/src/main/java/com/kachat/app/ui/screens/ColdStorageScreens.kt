package com.kachat.app.ui.screens

import com.kachat.app.R
import android.widget.Toast
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.Image
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.kachat.app.services.ColdStorageAddressDiscovery
import com.kachat.app.services.ColdStorageManager
import com.kachat.app.services.ColdStorageSendEngine
import com.kachat.app.services.KnsService
import com.kachat.app.services.UtxoEntry
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KsptCodec
import com.kachat.app.viewmodels.ColdStorageViewModel
import com.kachat.app.viewmodels.WalletViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cold Storage — a fully separate area of the app for watching/spending funds held on an
 * air-gapped KasSigner device. Everything here is watch-only (public keys only); signing always
 * happens on the physical device via QR exchange, never inside KaChat. See the KasSigner project
 * README for the device's own safety disclaimers: experimental, unaudited, no secure element.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColdStorageListScreen(
    navController: NavController,
    viewModel: ColdStorageViewModel = hiltViewModel(),
    walletViewModel: WalletViewModel = hiltViewModel()
) {
    val accounts by viewModel.accounts.collectAsState()
    val importState by viewModel.importState.collectAsState()
    var showScanner by remember { mutableStateOf(false) }
    var showManualEntry by remember { mutableStateOf(false) }
    var manualKpubInput by remember { mutableStateOf("") }
    var pendingKpub by remember { mutableStateOf<String?>(null) }
    var nameInput by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current
    var renamingAccount by remember { mutableStateOf<ColdStorageManager.ColdAccount?>(null) }
    /** Account whose kpub QR is on screen. */
    var kpubQrAccount by remember { mutableStateOf<ColdStorageManager.ColdAccount?>(null) }
    var renameInput by remember { mutableStateOf("") }

    LaunchedEffect(importState.status) {
        if (importState.status == ColdStorageViewModel.ImportStatus.SUCCESS) {
            pendingKpub = null
            nameInput = ""
            viewModel.resetImportState()
        }
    }

    // Cold Storage is a tab route, so the floating bottom nav bar is normally always shown on
    // top of it — this is a genuinely full-screen camera view, not a "pushed" detail screen, so
    // it has to explicitly ask the shell to hide the bar rather than that happening for free.
    LaunchedEffect(showScanner) { walletViewModel.setHideBottomBar(showScanner) }
    DisposableEffect(Unit) { onDispose { walletViewModel.setHideBottomBar(false) } }

    if (showScanner) {
        BackHandler { showScanner = false }
        QrScannerOverlay(
            onScanned = { scanned ->
                showScanner = false
                pendingKpub = scanned
                nameInput = "Cold Storage ${accounts.size + 1}"
            },
            onDismiss = { showScanner = false }
        )
        return
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            Column(modifier = Modifier.background(LocalAppColors.current.background)) {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.cold_storage), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold, fontSize = 26.sp) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
                )
                // Total (chatting-address) balance under the title, same as every other main
                // page's header (iOS ColdStorageView's centered BalanceToolbarLabel).
                BalanceTopBarLabel(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 4.dp)
                )
            }
        },
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            // Matches iOS's ColdStorageListView: "Paste kpub" (outlined, secondary) and "Scan"
            // (filled, primary) side by side at the bottom, instead of a single centered FAB.
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            ) {
                Button(
                    onClick = {
                        manualKpubInput = ""
                        showManualEntry = true
                    },
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, contentColor = Color.Black)
                ) {
                    Icon(Icons.Default.ContentPaste, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.paste_kpub), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = { showScanner = true },
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, contentColor = Color.Black)
                ) {
                    val scanContentDescription = stringResource(R.string.scan_kpub_from_kassigner)
                    Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.scan),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { contentDescription = scanContentDescription }
                    )
                }
            }
        }
    ) { padding ->
        if (accounts.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Security, null, tint = LocalAppColors.current.textSecondary, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.no_cold_storage_accounts_yet_scan),
                    color = LocalAppColors.current.textSecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(accounts) { account ->
                    var showMenu by remember { mutableStateOf(false) }
                    var menuAnchor by remember { mutableStateOf(Offset.Zero) }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(LocalAppColors.current.surface)
                            .clickable { navController.navigate("cold_storage_detail/${account.id}") }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Security, null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(account.name, color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
                            Text(
                                account.kpub,
                                color = LocalAppColors.current.textSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier
                                .size(32.dp)
                                .onGloballyPositioned { coords ->
                                    menuAnchor = coords.positionInWindow() + Offset(0f, coords.size.height.toFloat())
                                }
                        ) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.account_actions), tint = LocalAppColors.current.textSecondary, modifier = Modifier.size(20.dp))
                        }
                    }

                    if (showMenu) {
                        CenteredOptionsMenu(onDismissRequest = { showMenu = false }, anchor = menuAnchor) {
                            PopupMenuRow(Icons.Default.ContentCopy, stringResource(R.string.copy_kpub)) {
                                showMenu = false
                                clipboardManager.setText(AnnotatedString(account.kpub))
                            }
                            HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                            PopupMenuRow(Icons.Default.QrCode, "Show kpub QR") {
                                showMenu = false
                                kpubQrAccount = account
                            }
                            HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                            PopupMenuRow(Icons.Default.Edit, stringResource(R.string.rename)) {
                                showMenu = false
                                renamingAccount = account
                                renameInput = account.name
                            }
                        }
                    }
                }
            }
        }
    }

    kpubQrAccount?.let { account ->
        KpubQrDialog(account = account, onDismiss = { kpubQrAccount = null })
    }

    if (showManualEntry) {
        AlertDialog(
            onDismissRequest = { showManualEntry = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.enter_kpub), color = LocalAppColors.current.textPrimary) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.paste_the_kpub_exported_from_your),
                        color = LocalAppColors.current.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = manualKpubInput,
                        onValueChange = { manualKpubInput = it },
                        label = { Text(stringResource(R.string.kpub_2)) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = LocalAppColors.current.textPrimary,
                            unfocusedTextColor = LocalAppColors.current.textPrimary,
                            focusedBorderColor = KaspaTeal,
                            unfocusedBorderColor = LocalAppColors.current.textSecondary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = manualKpubInput.trim().isNotEmpty(),
                    onClick = {
                        val trimmed = manualKpubInput.trim()
                        showManualEntry = false
                        // Feeds the same pendingKpub/nameInput -> import AlertDialog flow the QR
                        // scanner already uses below - scan vs. paste only differ in how the raw
                        // kpub string is obtained, not in how it's validated/named/imported.
                        pendingKpub = trimmed
                        nameInput = "Cold Storage ${accounts.size + 1}"
                    }
                ) {
                    Text(stringResource(R.string.next), color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualEntry = false }) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }

    if (pendingKpub != null) {
        val kpub = pendingKpub!!
        val isInvalid = importState.status == ColdStorageViewModel.ImportStatus.INVALID_KPUB
        AlertDialog(
            onDismissRequest = { pendingKpub = null; viewModel.resetImportState() },
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.import_cold_storage_account), color = LocalAppColors.current.textPrimary) },
            text = {
                Column {
                    Text(
                        "kpub: ${kpub.take(24)}…",
                        color = LocalAppColors.current.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text(stringResource(R.string.name)) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = LocalAppColors.current.textPrimary,
                            unfocusedTextColor = LocalAppColors.current.textPrimary,
                            focusedBorderColor = KaspaTeal,
                            unfocusedBorderColor = LocalAppColors.current.textSecondary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (isInvalid) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            importState.errorMessage ?: "Not a valid kpub",
                            color = Color(0xFFFF3B30),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = nameInput.isNotBlank(),
                    onClick = { viewModel.importKpub(kpub, nameInput) }
                ) {
                    Text(stringResource(R.string.import_action), color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingKpub = null; viewModel.resetImportState() }) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }

    renamingAccount?.let { account ->
        AlertDialog(
            onDismissRequest = { renamingAccount = null },
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.rename_cold_storage_account), color = LocalAppColors.current.textPrimary) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text(stringResource(R.string.name)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = LocalAppColors.current.textPrimary,
                        unfocusedTextColor = LocalAppColors.current.textPrimary,
                        focusedBorderColor = KaspaTeal,
                        unfocusedBorderColor = LocalAppColors.current.textSecondary,
                        focusedLabelColor = KaspaTeal,
                        unfocusedLabelColor = LocalAppColors.current.textSecondary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameInput.isNotBlank(),
                    onClick = {
                        viewModel.renameAccount(account.id, renameInput.trim())
                        renamingAccount = null
                    }
                ) {
                    Text(stringResource(R.string.save), color = if (renameInput.isNotBlank()) KaspaTeal else Color.Gray, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingAccount = null }) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColdStorageDetailScreen(accountId: String, navController: NavController, viewModel: ColdStorageViewModel = hiltViewModel()) {
    val accounts by viewModel.accounts.collectAsState()
    val account = accounts.find { it.id == accountId }
    val addresses by viewModel.addresses.collectAsState()
    val isDiscovering by viewModel.isDiscovering.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var sendFromRow by remember { mutableStateOf<ColdStorageViewModel.AddressRow?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInput by remember { mutableStateOf("") }
    var labelingRow by remember { mutableStateOf<ColdStorageViewModel.AddressRow?>(null) }
    var labelInput by remember { mutableStateOf("") }
    var qrRow by remember { mutableStateOf<ColdStorageViewModel.AddressRow?>(null) }
    var showActionsMenu by remember { mutableStateOf(false) }
    var actionsMenuAnchor by remember { mutableStateOf(Offset.Zero) }
    val pullRefreshState = rememberPullToRefreshState()

    LaunchedEffect(accountId) {
        viewModel.refreshAddresses(accountId)
    }

    LaunchedEffect(pullRefreshState.isRefreshing) {
        if (pullRefreshState.isRefreshing) {
            viewModel.refreshAddresses(accountId)
        }
    }

    LaunchedEffect(isDiscovering) {
        if (!isDiscovering && pullRefreshState.isRefreshing) {
            pullRefreshState.endRefresh()
        }
    }

    // Ordering (matches iOS ColdStorageView / Manage Addresses): addresses with a balance OR an
    // owned KNS domain first (funded before domain-only, newest index first within each group),
    // then fresh addresses. Domain knowledge fills in asynchronously after the batched lookups.
    val domainOwningAddresses by viewModel.domainOwningAddresses.collectAsState()
    val visibleAddresses = remember(addresses, domainOwningAddresses) {
        val visible = addresses.filterNot { it.hidden }
        val rest = visible
            .sortedWith(compareByDescending<ColdStorageViewModel.AddressRow> { it.balanceSompi > 0 }.thenByDescending { it.index })
        val active = rest.filter { it.balanceSompi > 0 || it.address in domainOwningAddresses }
        val fresh = rest.filter { it.balanceSompi == 0L && it.address !in domainOwningAddresses }
        active + fresh
    }
    // A hidden address is excluded on purpose (a "put this aside" gesture) — it shouldn't keep
    // inflating the balance you actually think of as available.
    val totalBalanceKas = visibleAddresses.sumOf { it.balanceSompi } / 100_000_000.0

    sendFromRow?.let { row ->
        ColdSendFlow(
            fromAddress = row.address,
            availableBalanceSompi = row.balanceSompi,
            viewModel = viewModel,
            onDone = { sendFromRow = null; viewModel.refreshAddressesSoonAfterSend(accountId) }
        )
        return
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(account?.name ?: "Cold Storage", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                actions = {
                    // Bulk visibility manager: compact checkmark list of EVERY address, so dozens
                    // can be toggled off the main list in one sitting (same tool as Manage Addresses).
                    IconButton(onClick = { navController.navigate("cold_storage_visibility/$accountId") }) {
                        Icon(Icons.Default.Checklist, "Manage address visibility", tint = KaspaTeal)
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.Delete, "Remove account", tint = Color.Red)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        },
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            // Hidden while the QR overlay is up — its Dialog window doesn't fully cover the
            // screen, so the FAB would otherwise still show through around the QR card.
            if (qrRow == null) {
            FloatingActionButton(
                onClick = { showActionsMenu = true },
                containerColor = KaspaTeal,
                contentColor = Color.Black,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .height(56.dp)
                    .onGloballyPositioned { coords -> actionsMenuAnchor = coords.positionInWindow() }
            ) {
                val addressActionsContentDescription = stringResource(R.string.address_actions_2)
                Text(
                    stringResource(R.string.address_actions),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .semantics { contentDescription = addressActionsContentDescription }
                )
            }
            }
            // Anchored to the FAB's top edge and horizontally centered (this FAB is itself
            // screen-centered, so the usual left/right-edge-hugging anchor math doesn't apply) —
            // see ManageAddressesScreen's identical Address Actions menu for the full rationale.
            if (showActionsMenu) {
                CenteredOptionsMenu(
                    onDismissRequest = { showActionsMenu = false },
                    anchor = actionsMenuAnchor,
                    centerHorizontally = true
                ) {
                    PopupMenuRow(Icons.Default.AddCircleOutline, stringResource(R.string.generate_more_addresses)) {
                        showActionsMenu = false
                        if (!isDiscovering) viewModel.generateMoreAddresses(accountId) { index ->
                            Toast.makeText(
                                context,
                                if (index != null) "Address #$index is ready."
                                else "Could not derive a new address.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                    PopupMenuRow(Icons.Default.Search, stringResource(R.string.discover_addresses)) {
                        showActionsMenu = false
                        if (!isDiscovering) {
                            viewModel.refreshAddresses(accountId) { count ->
                                Toast.makeText(
                                    context,
                                    if (count > 0) "Found $count used address${if (count == 1) "" else "es"}" else "No additional used addresses found",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).nestedScroll(pullRefreshState.nestedScrollConnection)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(LocalAppColors.current.surface)
                        .padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.name), color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                            Text(
                                account?.name ?: "Cold Storage",
                                color = LocalAppColors.current.textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        IconButton(
                            onClick = {
                                renameInput = account?.name ?: ""
                                showRenameDialog = true
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Edit, "Rename", tint = KaspaTeal, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.kpub), color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        account?.kpub ?: "",
                        color = LocalAppColors.current.textPrimary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.clickable {
                            account?.kpub?.let { clipboardManager.setText(AnnotatedString(it)) }
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ContentCopy, null, tint = KaspaTeal, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.copy_kpub), color = KaspaTeal, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(LocalAppColors.current.surface)
                        .padding(20.dp)
                ) {
                    Text(stringResource(R.string.total_balance), color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                    Text(
                        "%.8f KAS".format(java.util.Locale.US, totalBalanceKas),
                        color = LocalAppColors.current.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                }
            }

            item {
                Text(
                    stringResource(R.string.addresses),
                    color = LocalAppColors.current.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            if (isDiscovering && addresses.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = KaspaTeal)
                    }
                }
            } else if (visibleAddresses.isEmpty()) {
                item {
                    Text(
                        if (addresses.isEmpty()) "No addresses discovered yet." else "All addresses are hidden.",
                        color = LocalAppColors.current.textSecondary
                    )
                }
            } else {
                items(visibleAddresses, key = { it.index }) { row ->
                    ColdAddressRow(
                        row = row,
                        showsDomainTag = row.address in domainOwningAddresses,
                        onAddressClick = { navController.navigate("cold_storage_tx_history/${row.address}") },
                        onLabelClick = { labelingRow = row; labelInput = row.label ?: "" },
                        onCopyClick = {
                            clipboardManager.setText(AnnotatedString(row.address))
                            com.kachat.app.util.showAddressCopiedToast(context, row.address)
                        },
                        onSendClick = { if (row.balanceSompi > 0) sendFromRow = row },
                        onShowQrClick = { qrRow = row },
                        onHideClick = {
                            // Same guards + copy as the Address Visibility checklist toggle. The
                            // toast waits for the real result: rows this session live-confirmed
                            // commit instantly, anything else runs the fail-closed live check and
                            // may refuse.
                            if (row.balanceSompi > 0) {
                                Toast.makeText(context, "Addresses holding a balance stay visible.", Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.setColdVisibilityHidden(accountId, row.index, true) { ok ->
                                    Toast.makeText(
                                        context,
                                        if (ok) "Address hidden. Re-enable it in Address Visibility."
                                        else "This address stays visible. It holds a balance or its balance could not be confirmed.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    )
                }
            }

            item {
                // Leaves room so the last address row isn't hidden behind the FAB.
                Spacer(Modifier.height(64.dp))
            }
        }
        PullToRefreshContainer(
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
        qrRow?.let { row ->
            QrCodeOverlay(value = row.address, onDismiss = { qrRow = null })
        }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.remove_cold_storage_account), color = LocalAppColors.current.textPrimary) },
            text = {
                Text(
                    stringResource(R.string.this_only_removes_it_from_kachat),
                    color = LocalAppColors.current.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAccount(accountId)
                    showDeleteConfirm = false
                    navController.popBackStack()
                }) {
                    Text(stringResource(R.string.remove), color = Color.Red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.rename_cold_storage_account), color = LocalAppColors.current.textPrimary) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text(stringResource(R.string.name)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = LocalAppColors.current.textPrimary,
                        unfocusedTextColor = LocalAppColors.current.textPrimary,
                        focusedBorderColor = KaspaTeal,
                        unfocusedBorderColor = LocalAppColors.current.textSecondary,
                        focusedLabelColor = KaspaTeal,
                        unfocusedLabelColor = LocalAppColors.current.textSecondary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameInput.isNotBlank(),
                    onClick = {
                        viewModel.renameAccount(accountId, renameInput.trim())
                        showRenameDialog = false
                    }
                ) {
                    Text(stringResource(R.string.save), color = if (renameInput.isNotBlank()) KaspaTeal else Color.Gray, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }

    labelingRow?.let { row ->
        AlertDialog(
            onDismissRequest = { labelingRow = null },
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.name_this_address), color = LocalAppColors.current.textPrimary) },
            text = {
                Column {
                    Text(row.address, color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = labelInput,
                        onValueChange = { labelInput = it },
                        label = { Text(stringResource(R.string.name)) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = LocalAppColors.current.textPrimary,
                            unfocusedTextColor = LocalAppColors.current.textPrimary,
                            focusedBorderColor = KaspaTeal,
                            unfocusedBorderColor = LocalAppColors.current.textSecondary,
                            focusedLabelColor = KaspaTeal,
                            unfocusedLabelColor = LocalAppColors.current.textSecondary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setAddressLabel(accountId, row.index, labelInput)
                        labelingRow = null
                    }
                ) {
                    Text(stringResource(R.string.save), color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { labelingRow = null }) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }
}

/**
 * Address Visibility for one Cold Storage account (the same tool Manage Addresses has) — a
 * compact checkmark list of EVERY derived address, paged 50 at a time, so dozens can be toggled
 * off the main list in one sitting. The right arrow never runs out: future pages derive
 * addresses beyond the derived bound on the fly, and toggling one on raises the bound while
 * keeping the intermediate indices hidden. Funded addresses are locked visible. Shares
 * [ColdStorageDetailScreen]'s ViewModel instance (see KaChatApp.kt) so edits show on the detail
 * list the moment this screen pops.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColdStorageAddressVisibilityScreen(
    accountId: String,
    viewModel: ColdStorageViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val addresses by viewModel.addresses.collectAsState()
    var page by remember { mutableStateOf(0) }
    val pageSize = 50
    val byIndex = remember(addresses) { addresses.associateBy { it.index } }
    val listMax = remember(addresses) { addresses.maxOfOrNull { it.index } ?: -1 }
    // Lazily filled Used/Unused results for rows derived beyond the loaded list.
    val usedCache = remember { mutableStateMapOf<Int, Boolean>() }

    // Batch-load fresh balances on entry (one round trip for the whole derived list): the toggle
    // rule trusts rows THIS load confirms, letting every checkmark flip instantly with no
    // per-toggle network wait. Rows the load could not confirm fall back to the fail-closed
    // per-address check. Mirrors the spending checklist's same refresh-on-entry.
    LaunchedEffect(Unit) { viewModel.refreshAddresses(accountId) }

    val start = page * pageSize
    val end = start + pageSize - 1
    val pageEntries = remember(byIndex, page) {
        (start..end).map { index ->
            byIndex[index] ?: ColdStorageViewModel.AddressRow(
                index = index,
                address = viewModel.coldAddressAt(accountId, index) ?: "",
                balanceSompi = 0L,
                hasHistory = false,
                label = null,
                hidden = true
            )
        }
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Address Visibility", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                actions = {
                    TextButton(onClick = onBack) {
                        Text("Done", color = KaspaTeal, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LocalAppColors.current.background)
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = { if (page > 0) page -= 1 }, enabled = page > 0) {
                    Icon(
                        Icons.Default.ChevronLeft,
                        "Previous page",
                        tint = if (page > 0) KaspaTeal else LocalAppColors.current.textSecondary.copy(alpha = 0.4f)
                    )
                }
                Text(
                    "#$start - #$end",
                    color = LocalAppColors.current.textSecondary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodySmall
                )
                IconButton(onClick = { page += 1 }) {
                    Icon(Icons.Default.ChevronRight, "Next page", tint = KaspaTeal)
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(pageEntries, key = { it.index }) { entry ->
                val visible = entry.index <= listMax && !entry.hidden
                val funded = entry.balanceSompi > 0
                // Used-state for derived rows the list loader has never seen.
                if (entry.index > listMax && entry.address.isNotEmpty() && entry.index !in usedCache) {
                    LaunchedEffect(entry.index) {
                        usedCache[entry.index] = viewModel.hasColdAddressBeenUsed(accountId, entry.index)
                    }
                }
                val used = if (entry.index <= listMax) entry.hasHistory else usedCache[entry.index]
                // The WHOLE row toggles, not just the checkmark. No primary-address rule here —
                // a watch-only kpub account has no primary; only "funded stays visible" applies.
                val toggleVisibility: () -> Unit = {
                    when {
                        funded && visible ->
                            Toast.makeText(context, "Addresses holding a balance stay visible.", Toast.LENGTH_SHORT).show()
                        entry.index > listMax ->
                            viewModel.revealColdAddress(accountId, entry.index)
                        else -> {
                            val hiding = !entry.hidden
                            viewModel.setColdVisibilityHidden(accountId, entry.index, hiding) { ok ->
                                if (hiding && !ok) {
                                    Toast.makeText(
                                        context,
                                        "This address stays visible. It holds a balance or its balance could not be confirmed.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(LocalAppColors.current.surface)
                        .clickable(onClick = toggleVisibility)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .alpha(if (visible) 1f else 0.55f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = toggleVisibility,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            if (visible) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            if (visible) "Visible" else "Hidden",
                            tint = if (visible) KaspaTeal else LocalAppColors.current.textSecondary
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "#${entry.index}",
                                color = LocalAppColors.current.textSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            entry.label?.takeIf { it.isNotBlank() }?.let { label ->
                                Text(label, color = LocalAppColors.current.textSecondary, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                        Text(
                            if (entry.address.isNotEmpty()) "${entry.address.take(14)}...${entry.address.takeLast(6)}" else "deriving...",
                            color = LocalAppColors.current.textPrimary,
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    when {
                        funded -> Text(
                            "%.4f KAS".format(java.util.Locale.US, entry.balanceSompi / 100_000_000.0),
                            color = KaspaTeal,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        used != null -> Text(
                            if (used) "Used" else "Unused",
                            color = if (used) Color(0xFFF39C12) else Color(0xFF4CD964),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/**
 * One address row on [ColdStorageDetailScreen]. Swipe-to-hide was retired when the Address
 * Visibility checklist ([ColdStorageAddressVisibilityScreen]) became the one place to manage
 * visibility — the same redesign Manage Addresses' spending rows got. A quick "Hide Address"
 * lives in the row's overflow menu (same as spending rows), writing the same hidden flag the
 * checklist edits.
 */
@Composable
private fun ColdAddressRow(
    row: ColdStorageViewModel.AddressRow,
    onAddressClick: () -> Unit,
    onLabelClick: () -> Unit,
    onCopyClick: () -> Unit,
    onSendClick: () -> Unit,
    onShowQrClick: () -> Unit,
    /** Row-menu "Hide Address" (same as spending rows) — same flag the Address Visibility
     *  checklist edits. Null hides the menu entry. */
    onHideClick: (() -> Unit)? = null,
    /** "Contains domain" tag — this address owns at least one KNS domain (batched lookup). */
    showsDomainTag: Boolean = false
) {
    val kas = row.balanceSompi / 100_000_000.0
    var showMenu by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf(Offset.Zero) }

    Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(LocalAppColors.current.surface)
                .clickable(onClick = onAddressClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.label?.takeIf { it.isNotBlank() } ?: "Address #${row.index}",
                    color = KaspaTeal,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${row.address.take(14)}...${row.address.takeLast(6)}",
                    color = LocalAppColors.current.textPrimary,
                    fontSize = 14.sp,
                    maxLines = 1
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "%.8f KAS".format(java.util.Locale.US, kas),
                    color = LocalAppColors.current.textPrimary,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Same three-state rule as the spending list: "Used" is monotonic, "Unused"
                    // only when this session's live pass confirmed the row, and a neutral
                    // "Unverified" for snapshot-painted or failed-check rows — a failed check
                    // must not masquerade as a fresh address.
                    val (usedTagText, usedTagColor) = when {
                        row.hasHistory -> "Used" to Color(0xFFF39C12)
                        row.liveChecked -> "Unused" to Color(0xFF4CD964)
                        else -> "Unverified" to LocalAppColors.current.textSecondary
                    }
                    Text(
                        text = usedTagText,
                        color = usedTagColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (showsDomainTag) {
                        com.kachat.app.ui.screens.ContainsDomainTag()
                    }
                }
            }
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier
                    .size(44.dp)
                    .onGloballyPositioned { coords ->
                        menuAnchor = coords.positionInWindow() + Offset(0f, coords.size.height.toFloat())
                    }
            ) {
                Icon(Icons.Default.MoreVert, "Address actions", tint = LocalAppColors.current.textSecondary, modifier = Modifier.size(28.dp))
            }
        }

    if (showMenu) {
        CenteredOptionsMenu(onDismissRequest = { showMenu = false }, anchor = menuAnchor) {
            PopupMenuRow(Icons.Default.Edit, stringResource(R.string.rename_address)) {
                showMenu = false
                onLabelClick()
            }
            HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
            PopupMenuRow(Icons.Default.ContentCopy, stringResource(R.string.copy_address)) {
                showMenu = false
                onCopyClick()
            }
            HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
            PopupMenuRow(Icons.Default.QrCode, stringResource(R.string.show_qr_code)) {
                showMenu = false
                onShowQrClick()
            }
            // Hide straight from the row (same as spending rows) — no primary-address rule for a
            // watch-only kpub account; the funded guard lives in the caller so it can toast the
            // reason.
            if (onHideClick != null) {
                HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                PopupMenuRow(Icons.Default.VisibilityOff, "Hide Address") {
                    showMenu = false
                    onHideClick()
                }
            }
        }
    }
}

/** Normal/Fast/Priority multiplier system, matching iOS's `WithdrawFeeTier` exactly - shared by
 *  [ColdSendFlow] and the spending-address send flow (`SpendingAddressSendFlow` in Screens.kt). */
enum class ColdFeeTier(val label: String, val multiplier: Long) {
    NORMAL("Normal", 1),
    FAST("Fast", 2),
    PRIORITY("Priority", 5)
}

/**
 * The whole "send" round trip for one Cold Storage address: enter recipient/amount, build an
 * unsigned tx, display it as an animated KSPT QR for the KasSigner device to scan/sign, scan the
 * signed response back, then broadcast. Takes over the full screen (like [ColdStorageListScreen]'s
 * scanner) rather than living in a dialog — the animated QR needs real room to be scannable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColdSendFlow(
    fromAddress: String,
    availableBalanceSompi: Long,
    viewModel: ColdStorageViewModel,
    onDone: () -> Unit,
    // Pre-fills the recipient with fromAddress itself (a self-send) and auto-fills Max, for the
    // "Compound UTXOs" entry point — merges every UTXO at this address into one. Locks the
    // recipient field instead of just pre-filling it, since editing it away from fromAddress
    // would defeat the point of a compound send.
    isCompoundMode: Boolean = false,
    portfolioViewModel: com.kachat.app.viewmodels.PortfolioViewModel = hiltViewModel()
) {
    val sendState by viewModel.sendState.collectAsState()
    val kaspaExplorer by viewModel.kaspaExplorer.collectAsState()
    val fiatPriceInCurrency by portfolioViewModel.currentPriceUsd.collectAsState()
    val fiatCurrencyCode by portfolioViewModel.currency.collectAsState()
    var toAddress by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    val fiatAmountState = com.kachat.app.util.rememberKaspaFiatAmountState(onKasTextChange = { amountText = it })
    var showSignedScanner by remember { mutableStateOf(false) }
    var showRecipientScanner by remember { mutableStateOf(false) }
    var isEstimatingMax by remember { mutableStateOf(false) }
    // Coin control — null means automatic (greedy, largest-first) selection; non-null fixes the
    // exact input set the user picked instead.
    var manualUtxos by remember { mutableStateOf<List<UtxoEntry>?>(null) }
    var showCoinControl by remember { mutableStateOf(false) }
    // Compound mode only: true when this address holds more UTXOs than one KasSigner transaction
    // can merge at once (KsptCodec.MAX_INPUTS), so the user must run Compound again after this
    // round. Drives the "repeat to finish" note.
    var compoundHasMoreRounds by remember { mutableStateOf(false) }
    var feeTier by remember { mutableStateOf(ColdFeeTier.NORMAL) }
    var customExtraFeeSompi by remember { mutableStateOf<Long?>(null) }
    var showFeeEditor by remember { mutableStateOf(false) }
    var feeEditorInput by remember { mutableStateOf("") }
    val feeEditorFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val clipboardManager = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val coroutineScope = rememberCoroutineScope()
    // Fetched once and reused for both the preview below and the actual build/estimate calls
    // (via feeRateOverrideSompi) — letting each of those independently fetch their own quote was
    // exactly why the fee shown before Build didn't match what the real transaction ended up
    // costing, whenever the live quote drifted between two separate fetches.
    var liveFeeRateSompiPerGram by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        liveFeeRateSompiPerGram = viewModel.fetchQuotedFeeRateSompiPerGram()
        if (isCompoundMode) {
            toAddress = fromAddress
            isEstimatingMax = true
            try {
                // Fix the input set to the largest <=8 UTXOs — the most one KasSigner-signable
                // transaction can hold — then Max the amount for exactly that set. Without this
                // cap, Max spans every UTXO at the address and the build fails with "too many
                // inputs" the moment it holds more than 8. `compoundHasMoreRounds` records whether
                // another round is needed afterward.
                val compound = viewModel.compoundInputs(fromAddress)
                manualUtxos = compound.utxos
                compoundHasMoreRounds = compound.hasMore
                val maxSompi = viewModel.estimateMaxAmount(fromAddress, liveFeeRateSompiPerGram, compound.utxos)
                fiatAmountState.setMaxKas(maxSompi / 100_000_000.0, fiatPriceInCurrency)
            } catch (e: Exception) {
                // Leave the field untouched on failure — same as the Max button itself.
            } finally {
                isEstimatingMax = false
            }
        }
    }

    // Keyboard pops up the moment the fee editor opens, instead of requiring a second tap into
    // the field after the dialog appears.
    LaunchedEffect(showFeeEditor) {
        if (showFeeEditor) {
            feeEditorFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.resetColdSendState() }
    }

    val inFlight = sendState.step in listOf(
        ColdStorageViewModel.ColdSendStep.BUILDING,
        ColdStorageViewModel.ColdSendStep.BROADCASTING
    )
    val availableKas = availableBalanceSompi / 100_000_000.0
    val amountSompi = amountText.toDoubleOrNull()?.let { Math.round(it * 100_000_000.0) }
    val isValidRecipient = remember(toAddress) { KaspaAddress.isValid(toAddress) }

    // Debounced live preview of what automatic selection would pick for the current amount/fee —
    // see the LaunchedEffect below. Non-null only while still fresh for the current amount/fee;
    // cleared immediately on any relevant change so a stale preview is never shown or built with.
    var previewSelection by remember { mutableStateOf<ColdStorageSendEngine.AutomaticSelectionPreview?>(null) }

    val referenceMass1Input = remember {
        com.kachat.app.util.KaspaMass.calculateMass(numInputs = 1, outputScriptLens = listOf(34, 34), payloadSize = 0)
    }
    // Same live-or-minimum rate the engine's own nil-override default falls back to — using it
    // here too (instead of always assuming the bare protocol minimum) means the "Normal" preview
    // matches what a nil-override build would actually charge whenever the network's live quote
    // is currently above the minimum.
    val baseFeeRateSompiPerGram = liveFeeRateSompiPerGram ?: com.kachat.app.util.KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM

    // Per-gram rate for the chosen tier/custom fee — deliberately derived only from the fixed
    // 1-input reference mass, never from `previewSelection` or the real input count below. Both
    // the real build and the live preview fetch need this exact rate to ask "how many inputs will
    // this take"; if it depended on the preview's own result, fetching a preview and computing the
    // rate to fetch it with would be circular.
    val feeRateOverrideSompi: Long = if (feeTier == ColdFeeTier.NORMAL && customExtraFeeSompi == null) {
        baseFeeRateSompiPerGram
    } else {
        val referenceFeeSompi = com.kachat.app.util.KaspaMass.calculateFee(referenceMass1Input, baseFeeRateSompiPerGram)
        val extra = customExtraFeeSompi ?: (referenceFeeSompi * (feeTier.multiplier - 1))
        kotlin.math.ceil((referenceFeeSompi + extra).toDouble() / referenceMass1Input).toLong()
    }

    // Real input count when known (coin control, or a fresh automatic-selection preview) instead
    // of always guessing 1 — otherwise the fee shown here could understate what a multi-UTXO send
    // actually costs, only becoming visible after tapping Build.
    val estimatedMass = when {
        !manualUtxos.isNullOrEmpty() ->
            com.kachat.app.util.KaspaMass.calculateMass(numInputs = manualUtxos!!.size, outputScriptLens = listOf(34, 34), payloadSize = 0)
        previewSelection != null ->
            com.kachat.app.util.KaspaMass.calculateMass(numInputs = previewSelection!!.utxos.size, outputScriptLens = listOf(34, 34), payloadSize = 0)
        else -> referenceMass1Input
    }
    val defaultFeeSompi = com.kachat.app.util.KaspaMass.calculateFee(estimatedMass, baseFeeRateSompiPerGram)
    // Normal/Fast/Priority multiplier system, same inline (no separate screen) pattern as iOS's
    // ColdSendFlowView and this app's own chatting-address withdraw flow's WithdrawFeeTier.
    val extraFeeSompi = customExtraFeeSompi ?: (defaultFeeSompi * (feeTier.multiplier - 1))
    // The live preview (when fresh and automatic selection is in play — coin control already
    // knows its exact count another way) wins over the mass-derived number for display: it's the
    // actual result of running the real selector at feeRateOverrideSompi, not a recomputation.
    val effectiveFeeSompi = if (manualUtxos == null && previewSelection != null) {
        previewSelection!!.feeSompi
    } else {
        defaultFeeSompi + extraFeeSompi
    }

    // Debounced (400ms) — cancels and restarts automatically whenever any key changes, so a
    // burst of typing doesn't fire a network call per keystroke. No-ops entirely once coin
    // control is active (manualUtxos != null) since that already knows its exact count.
    LaunchedEffect(amountSompi, feeTier, customExtraFeeSompi, liveFeeRateSompiPerGram, manualUtxos) {
        previewSelection = null
        if (manualUtxos == null && amountSompi != null && amountSompi > 0) {
            kotlinx.coroutines.delay(400)
            previewSelection = viewModel.previewAutomaticSelection(fromAddress, amountSompi, feeRateOverrideSompi)
        }
    }

    var isResolvingKns by remember { mutableStateOf(false) }
    var knsResolvedAddress by remember { mutableStateOf<String?>(null) }
    var knsError by remember { mutableStateOf<String?>(null) }
    // Debounced KNS domain resolution - lets typing "name.kas" here resolve the same way Create
    // Chat's own address field already does. Skipped entirely in compound mode, where the
    // recipient is always the locked self-address, never user-typed.
    LaunchedEffect(toAddress) {
        knsResolvedAddress = null
        knsError = null
        if (isCompoundMode) {
            isResolvingKns = false
            return@LaunchedEffect
        }
        val trimmed = toAddress.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("kaspa:", ignoreCase = true) ||
            trimmed.startsWith("kaspatest:", ignoreCase = true) || !KnsService.looksLikeDomain(trimmed)
        ) {
            isResolvingKns = false
            return@LaunchedEffect
        }
        isResolvingKns = true
        kotlinx.coroutines.delay(500)
        val resolved = viewModel.resolveKnsDomain(trimmed)
        isResolvingKns = false
        if (resolved != null) knsResolvedAddress = resolved else knsError = "KNS domain not found"
    }
    // The actual address to use (resolved from a KNS domain, or the direct input) - same
    // precedence as Create Chat's own address field.
    val effectiveAddress = knsResolvedAddress ?: toAddress
    val hasValidRecipient = if (knsResolvedAddress != null) true else (isValidRecipient && !isResolvingKns)

    BackHandler(enabled = !inFlight) { onDone() }

    if (showRecipientScanner) {
        BackHandler { showRecipientScanner = false }
        QrScannerOverlay(
            onScanned = { scanned -> toAddress = scanned.trim(); showRecipientScanner = false },
            onDismiss = { showRecipientScanner = false }
        )
        return
    }

    if (showCoinControl) {
        BackHandler { showCoinControl = false }
        CoinControlScreen(
            fromAddress = fromAddress,
            fetchUtxos = { addr -> viewModel.fetchUtxosForCoinControl(addr) },
            initialSelection = manualUtxos,
            onDone = { selection -> manualUtxos = selection; showCoinControl = false },
            onCancel = { showCoinControl = false }
        )
        return
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.send_from_cold_storage), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { if (!inFlight) onDone() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = if (inFlight) Color.Gray else KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(LocalAppColors.current.surface)
                    .padding(16.dp)
            ) {
                Text(stringResource(R.string.from), color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                Text(fromAddress, color = LocalAppColors.current.textPrimary, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text("Available: %.8f KAS".format(java.util.Locale.US, availableKas), color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodySmall)
            }

            when (sendState.step) {
                ColdStorageViewModel.ColdSendStep.IDLE, ColdStorageViewModel.ColdSendStep.FAILED -> {
                    // Section order/grouping below mirrors iOS's ColdSendFlowView Form exactly:
                    // Recipient Address (field, validity indicator, Paste/Scan) -> Amount (field,
                    // fiat toggle, Max) -> Network Fee (tappable row + footer note).
                    Text(
                        (if (isCompoundMode) stringResource(R.string.consolidating_this_address) else stringResource(R.string.recipient_address)).uppercase(),
                        color = LocalAppColors.current.textSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (isCompoundMode) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CallMerge, null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                fromAddress,
                                color = LocalAppColors.current.textPrimary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (compoundHasMoreRounds)
                                stringResource(R.string.compound_more_rounds_note, KsptCodec.MAX_INPUTS)
                            else
                                stringResource(R.string.compound_single_round_note),
                            color = LocalAppColors.current.textSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        OutlinedTextField(
                            value = toAddress,
                            onValueChange = { toAddress = it },
                            placeholder = { Text("kaspa:qr... or name.kas") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = LocalAppColors.current.textPrimary,
                                unfocusedTextColor = LocalAppColors.current.textPrimary,
                                focusedBorderColor = KaspaTeal,
                                unfocusedBorderColor = LocalAppColors.current.textSecondary,
                                focusedLabelColor = KaspaTeal,
                                unfocusedLabelColor = LocalAppColors.current.textSecondary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (toAddress.isNotEmpty()) {
                            if (isResolvingKns) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = KaspaTeal, strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.resolving_domain), color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodySmall)
                                }
                            } else if (knsError != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, null, tint = Color(0xFFFF3B30), modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(knsError ?: "", color = Color(0xFFFF3B30), style = MaterialTheme.typography.bodySmall)
                                }
                            } else if (knsResolvedAddress != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CD964), modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Resolved to ${knsResolvedAddress?.takeLast(12)}", color = Color(0xFF4CD964), style = MaterialTheme.typography.bodySmall)
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (isValidRecipient) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                        null,
                                        tint = if (isValidRecipient) Color(0xFF4CD964) else Color(0xFFFF3B30),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        stringResource(if (isValidRecipient) R.string.valid_address else R.string.invalid_address_format),
                                        color = if (isValidRecipient) Color(0xFF4CD964) else Color(0xFFFF3B30),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { clipboardManager.getText()?.text?.let { toAddress = it.trim() } }) {
                                Icon(Icons.Default.ContentPaste, null, tint = KaspaTeal, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.paste_from_clipboard), color = KaspaTeal, style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { showRecipientScanner = true }) {
                                Icon(Icons.Default.QrCodeScanner, null, tint = KaspaTeal, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.scan_qr_code), color = KaspaTeal, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.amount_kas).uppercase(),
                        color = LocalAppColors.current.textSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedTextField(
                        value = fiatAmountState.displayText,
                        onValueChange = { fiatAmountState.onDisplayTextChange(it, fiatPriceInCurrency) },
                        placeholder = { Text(if (fiatAmountState.isFiatMode) fiatCurrencyCode.uppercase() else stringResource(R.string.amount_kas)) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                        ),
                        leadingIcon = {
                            IconButton(onClick = { fiatAmountState.toggleMode(fiatPriceInCurrency) }) {
                                if (fiatAmountState.isFiatMode) {
                                    Text(
                                        com.kachat.app.util.currencySymbolFor(fiatCurrencyCode),
                                        color = KaspaTeal,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                } else {
                                    Icon(
                                        painterResource(R.drawable.ic_kaspa_logo),
                                        stringResource(R.string.switch_between_kas_and_fiat),
                                        tint = Color.Unspecified,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                fiatAmountState.conversionLabelText(fiatPriceInCurrency, fiatCurrencyCode)?.let { label ->
                                    Text(
                                        label,
                                        color = LocalAppColors.current.textSecondary,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                }
                                if (isEstimatingMax) {
                                    CircularProgressIndicator(
                                        color = KaspaTeal,
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    TextButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                isEstimatingMax = true
                                                try {
                                                    val maxSompi = viewModel.estimateMaxAmount(fromAddress, feeRateOverrideSompi, manualUtxos)
                                                    fiatAmountState.setMaxKas(maxSompi / 100_000_000.0, fiatPriceInCurrency)
                                                } catch (e: Exception) {
                                                    // Leave the field untouched on failure — same as iOS.
                                                } finally {
                                                    isEstimatingMax = false
                                                }
                                            }
                                        }
                                    ) {
                                        Text(stringResource(R.string.max), color = KaspaTeal)
                                    }
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = LocalAppColors.current.textPrimary,
                            unfocusedTextColor = LocalAppColors.current.textPrimary,
                            focusedBorderColor = KaspaTeal,
                            unfocusedBorderColor = LocalAppColors.current.textSecondary,
                            focusedLabelColor = KaspaTeal,
                            unfocusedLabelColor = LocalAppColors.current.textSecondary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Compound auto-manages its own input set (largest <=8, KasSigner's per-tx
                    // limit), so manual coin control is hidden there — it only applies to a normal
                    // send.
                    if (!isCompoundMode) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { showCoinControl = true },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.coin_control), color = LocalAppColors.current.textPrimary)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    manualUtxos?.let { "${it.size} ${if (it.size == 1) stringResource(R.string.utxo) else stringResource(R.string.utxos)}" }
                                        ?: stringResource(R.string.automatic),
                                    color = LocalAppColors.current.textSecondary
                                )
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    null,
                                    tint = LocalAppColors.current.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    // Inline Normal/Fast/Priority picker, right here on the send form — matching
                    // SpendingAddressWithdrawView's WithdrawFeeTier segmented control, not a
                    // separate screen/dialog.
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        ColdFeeTier.entries.forEachIndexed { index, tier ->
                            SegmentedButton(
                                selected = feeTier == tier,
                                onClick = {
                                    feeTier = tier
                                    customExtraFeeSompi = null
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = ColdFeeTier.entries.size),
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = LocalAppColors.current.surfaceVariant,
                                    activeContentColor = LocalAppColors.current.textPrimary,
                                    inactiveContainerColor = LocalAppColors.current.surface,
                                    inactiveContentColor = LocalAppColors.current.textSecondary
                                )
                            ) {
                                Text(tier.label, fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            feeEditorInput = "%.8f".format(java.util.Locale.US, effectiveFeeSompi / 100_000_000.0)
                            showFeeEditor = true
                        },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.network_fee), color = LocalAppColors.current.textPrimary)
                        Text(
                            "%.8f KAS".format(java.util.Locale.US, effectiveFeeSompi / 100_000_000.0),
                            color = KaspaTeal,
                            fontWeight = FontWeight.Bold,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                        )
                    }
                    Text(
                        stringResource(R.string.if_the_network_is_busy_a),
                        color = LocalAppColors.current.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (sendState.step == ColdStorageViewModel.ColdSendStep.FAILED) {
                        Text(sendState.errorMessage ?: "Something went wrong", color = Color(0xFFFF3B30), style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            amountSompi?.let {
                                // Real coin control (explicit user selection) wins if set;
                                // otherwise, if a fresh automatic-selection preview is available,
                                // pass its exact UTXO set through too — guaranteeing the fee just
                                // shown on this screen and the fee the real build produces are
                                // the same number, not just close. Re-resolved against a fresh
                                // fetch inside buildUnsignedTransaction either way, so this is
                                // never stale-unsafe.
                                val utxosForBuild = manualUtxos ?: previewSelection?.utxos
                                viewModel.startColdSend(fromAddress, effectiveAddress.trim(), it, feeRateOverrideSompi, utxosForBuild)
                            }
                        },
                        enabled = hasValidRecipient && (amountSompi ?: 0) > 0,
                        colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, disabledContainerColor = LocalAppColors.current.surfaceVariant),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text(
                            stringResource(R.string.build_unsigned_transaction),
                            color = if (hasValidRecipient && (amountSompi ?: 0) > 0) Color.Black else Color.Gray,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                ColdStorageViewModel.ColdSendStep.BUILDING -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) {
                        CircularProgressIndicator(color = KaspaTeal)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.building_transaction), color = LocalAppColors.current.textSecondary)
                    }
                }

                ColdStorageViewModel.ColdSendStep.SHOWING_QR -> {
                    if (!showSignedScanner) {
                        // A bright, high-contrast quiet zone around the code — same reasoning as
                        // the full-screen "big mode" QR overlay elsewhere — gets a more reliable
                        // scan on the KasSigner device's camera than the app's own dark theme.
                        Surface(
                            color = Color.White,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
                                Text(
                                    stringResource(R.string.scan_this_with_your_kassigner_device),
                                    color = Color(0xFF6B6B70),
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(16.dp))
                                AnimatedQrDisplay(frames = sendState.qrFrames, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Network fee: ~%.8f KAS".format(java.util.Locale.US, sendState.feeSompi / 100_000_000.0),
                            color = LocalAppColors.current.textSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(
                            onClick = { showSignedScanner = true },
                            colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text(stringResource(R.string.scan_signed_transaction), color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        MultiFrameQrScannerOverlay(
                            isComplete = { KsptCodec.looksLikeKspt(it) },
                            onComplete = { bytes ->
                                showSignedScanner = false
                                viewModel.onSignedKsptScanned(bytes)
                            },
                            onCancel = { showSignedScanner = false }
                        )
                    }
                }

                ColdStorageViewModel.ColdSendStep.BROADCASTING -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) {
                        CircularProgressIndicator(color = KaspaTeal)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.broadcasting), color = LocalAppColors.current.textSecondary)
                    }
                }

                ColdStorageViewModel.ColdSendStep.SUCCESS -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CD964), modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.sent), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.height(20.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(LocalAppColors.current.surface)
                                .padding(16.dp)
                        ) {
                            Text(stringResource(R.string.to), color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                            Text(effectiveAddress, color = LocalAppColors.current.textPrimary, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(12.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(LocalAppColors.current.surface)
                                .clickable {
                                    sendState.txId?.let { uriHandler.openUri(kaspaExplorer.txUrl(it)) }
                                }
                                .padding(16.dp)
                        ) {
                            Text("Transaction ID · tap to view in ${kaspaExplorer.displayName}", color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                            Text(sendState.txId ?: "", color = KaspaTeal, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = onDone,
                            colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text(stringResource(R.string.done), color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (showFeeEditor) {
        AlertDialog(
            onDismissRequest = { showFeeEditor = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.adjust_network_fee), color = LocalAppColors.current.textPrimary) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.if_the_network_is_busy_a),
                        color = LocalAppColors.current.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = feeEditorInput,
                        onValueChange = { feeEditorInput = it },
                        label = { Text(stringResource(R.string.fee_kas)) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = LocalAppColors.current.textPrimary,
                            unfocusedTextColor = LocalAppColors.current.textPrimary,
                            focusedBorderColor = KaspaTeal,
                            unfocusedBorderColor = LocalAppColors.current.textSecondary,
                            focusedLabelColor = KaspaTeal,
                            unfocusedLabelColor = LocalAppColors.current.textSecondary
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(feeEditorFocusRequester)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Default: %.8f KAS".format(java.util.Locale.US, defaultFeeSompi / 100_000_000.0),
                        color = LocalAppColors.current.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val kas = feeEditorInput.toDoubleOrNull()
                    customExtraFeeSompi = if (kas != null && kas >= 0) {
                        val totalSompi = Math.round(kas * 100_000_000.0)
                        (totalSompi - defaultFeeSompi).coerceAtLeast(0L)
                    } else {
                        null
                    }
                    showFeeEditor = false
                }) {
                    Text(stringResource(R.string.save), color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        feeTier = ColdFeeTier.NORMAL
                        customExtraFeeSompi = null
                        showFeeEditor = false
                    }) {
                        Text(stringResource(R.string.use_default), color = LocalAppColors.current.textSecondary)
                    }
                    TextButton(onClick = { showFeeEditor = false }) {
                        Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                    }
                }
            }
        )
    }
}

/**
 * Coin control — lets the user fix the exact UTXO set a send spends from instead of the
 * automatic largest-first selector. Shared by [ColdSendFlow] and the spending-address send flow
 * (`SpendingAddressSendFlow` in Screens.kt) — not Cold-Storage-specific: parameterized by
 * [fetchUtxos] rather than a concrete ViewModel, since the only thing this screen needs is a way
 * to fetch UTXOs for an address. Passes the selection back as a plain `List<UtxoEntry>?` (null =
 * automatic) rather than owning any state itself, since the actual spendable set needs
 * re-resolving against a fresh fetch at build time anyway (see [ColdStorageSendEngine]'s/
 * [KaspaWalletEngine]'s `manualUtxos` handling).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoinControlScreen(
    fromAddress: String,
    fetchUtxos: suspend (String) -> List<UtxoEntry>,
    initialSelection: List<UtxoEntry>?,
    onDone: (List<UtxoEntry>?) -> Unit,
    onCancel: () -> Unit
) {
    var utxos by remember { mutableStateOf<List<UtxoEntry>>(emptyList()) }
    var selectedKeys by remember { mutableStateOf<Set<com.kachat.app.services.Outpoint>>(emptySet()) }
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(fromAddress) {
        isLoading = true
        utxos = fetchUtxos(fromAddress)
        if (!initialSelection.isNullOrEmpty()) {
            selectedKeys = initialSelection.map { it.outpoint }.toSet()
        }
        isLoading = false
    }

    val selectedTotalSompi = utxos.filter { selectedKeys.contains(it.outpoint) }.sumOf { it.utxoEntry.amount }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.coin_control), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                actions = {
                    var showMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, null, tint = KaspaTeal)
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.select_all)) },
                            onClick = { selectedKeys = utxos.map { it.outpoint }.toSet(); showMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.automatic_clear_selection)) },
                            onClick = { selectedKeys = emptySet(); showMenu = false }
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        },
        bottomBar = {
            Button(
                onClick = {
                    val selected = utxos.filter { selectedKeys.contains(it.outpoint) }
                    onDone(selected.ifEmpty { null })
                },
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal)
            ) {
                Text(
                    if (selectedKeys.isEmpty()) stringResource(R.string.use_automatic_selection) else stringResource(R.string.confirm_selection),
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    ) { padding ->
        when {
            isLoading && utxos.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = KaspaTeal)
                }
            }
            utxos.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.no_utxos), color = LocalAppColors.current.textSecondary, textAlign = TextAlign.Center)
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (selectedKeys.isNotEmpty()) {
                        item {
                            Text(
                                "%s: %.8f KAS (%d)".format(
                                    Locale.US,
                                    stringResource(R.string.selected),
                                    selectedTotalSompi / 100_000_000.0,
                                    selectedKeys.size
                                ),
                                color = LocalAppColors.current.textSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    items(utxos, key = { "${it.outpoint.transactionId}:${it.outpoint.index}" }) { utxo ->
                        val isSelected = selectedKeys.contains(utxo.outpoint)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(LocalAppColors.current.surface)
                                .clickable {
                                    selectedKeys = if (isSelected) selectedKeys - utxo.outpoint else selectedKeys + utxo.outpoint
                                }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (isSelected) Icons.Default.CheckCircle else Icons.Default.Circle,
                                null,
                                tint = if (isSelected) KaspaTeal else LocalAppColors.current.textSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    "%.8f KAS".format(Locale.US, utxo.utxoEntry.amount / 100_000_000.0),
                                    color = LocalAppColors.current.textPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "${utxo.outpoint.transactionId.take(10)}...:${utxo.outpoint.index}",
                                    color = LocalAppColors.current.textSecondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** On-chain transaction history for one Cold Storage address — reached by tapping an address row in [ColdStorageDetailScreen]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColdStorageTxHistoryScreen(
    address: String,
    onBack: () -> Unit,
    viewModel: ColdStorageViewModel = hiltViewModel(),
    portfolioViewModel: com.kachat.app.viewmodels.PortfolioViewModel = hiltViewModel(),
) {
    val txHistory by viewModel.txHistory.collectAsState()
    val isLoading by viewModel.isLoadingTxHistory.collectAsState()
    val utxos by viewModel.utxos.collectAsState()
    val isLoadingUtxos by viewModel.isLoadingUtxos.collectAsState()
    val kaspaExplorer by viewModel.kaspaExplorer.collectAsState()
    val addresses by viewModel.addresses.collectAsState()
    val uriHandler = LocalUriHandler.current
    // The tapped transaction, while its action chooser is up.
    var transactionActionTarget by remember { mutableStateOf<ColdStorageAddressDiscovery.AddressTransaction?>(null) }
    // The transaction being filed into a portfolio, if any - see [AddToPortfolioSheet].
    var portfolioCandidate by remember { mutableStateOf<ColdStorageAddressDiscovery.AddressTransaction?>(null) }
    var addedPortfolioName by remember { mutableStateOf<String?>(null) }

    // Looked up from the already-loaded address list (shared with the account detail screen)
    // rather than a new route param — avoids widening the nav route just for a display name.
    val addressRow = remember(addresses, address) { addresses.firstOrNull { it.address == address } }
    val displayName = addressRow?.label?.takeIf { it.isNotBlank() }
        ?: addressRow?.let { "Address #${it.index}" }
        ?: address

    var selectedTab by remember { mutableStateOf(0) }
    var showQr by remember { mutableStateOf(false) }
    var showSendFlow by remember { mutableStateOf(false) }
    var showCompoundFlow by remember { mutableStateOf(false) }
    var utxoLabels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var labelingUtxoKey by remember { mutableStateOf<String?>(null) }
    var labelInput by remember { mutableStateOf("") }
    val knsDomains by viewModel.addressKnsDomains.collectAsState()
    val isLoadingKnsDomains by viewModel.addressKnsDomainsLoading.collectAsState()

    LaunchedEffect(address) {
        viewModel.loadTxHistory(address)
        viewModel.loadUtxos(address)
        viewModel.loadAddressKnsDomains(address)
        utxoLabels = viewModel.getUtxoLabels(address)
    }

    if (showSendFlow) {
        ColdSendFlow(
            fromAddress = address,
            availableBalanceSompi = addressRow?.balanceSompi ?: 0L,
            viewModel = viewModel,
            onDone = {
                showSendFlow = false
                viewModel.loadTxHistory(address)
                viewModel.loadUtxos(address)
            }
        )
        return
    }

    if (showCompoundFlow) {
        ColdSendFlow(
            fromAddress = address,
            availableBalanceSompi = addressRow?.balanceSompi ?: 0L,
            viewModel = viewModel,
            isCompoundMode = true,
            onDone = {
                showCompoundFlow = false
                viewModel.loadTxHistory(address)
                viewModel.loadUtxos(address)
            }
        )
        return
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(displayName, color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                actions = {
                    IconButton(onClick = { uriHandler.openUri(kaspaExplorer.addressUrl(address)) }) {
                        Icon(Icons.Default.Public, stringResource(R.string.view_in_explorer), tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { showQr = true },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, contentColor = Color.Black)
                ) {
                    Icon(Icons.Default.QrCode, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.receive), fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = { showSendFlow = true },
                    enabled = (addressRow?.balanceSompi ?: 0L) > 0L,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, contentColor = Color.Black)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.send), fontWeight = FontWeight.Bold)
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.balance),
                    color = LocalAppColors.current.textSecondary,
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    "%.8f KAS".format(Locale.US, (addressRow?.balanceSompi ?: 0L) / 100_000_000.0),
                    color = LocalAppColors.current.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = LocalAppColors.current.background,
                contentColor = KaspaTeal
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("History") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("${stringResource(R.string.utxos)} (${utxos.size})") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("KNS Domains (${knsDomains.size})") }
                )
            }
            when (selectedTab) {
                // LIST-ONLY, no send flow: a KNS transfer's reveal input spends a P2SH redeem
                // script, and the KSPT QR format only carries plain single-sig Schnorr inputs —
                // KasSigner can't sign inscription transactions (matches iOS's cold KNS tab).
                2 -> when {
                    isLoadingKnsDomains && knsDomains.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = KaspaTeal)
                        }
                    }
                    knsDomains.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("No KNS domains on this address.", color = LocalAppColors.current.textSecondary, textAlign = TextAlign.Center)
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(knsDomains, key = { it.assetId ?: it.asset ?: it.hashCode().toString() }) { domain ->
                                KnsDomainCard(domain = domain)
                            }
                            item {
                                Text(
                                    "Sending domains from a cold storage address requires signing on the KasSigner, which doesn't support inscription transactions yet.",
                                    color = LocalAppColors.current.textSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                        }
                    }
                }
                0 -> when {
                    isLoading && txHistory.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = KaspaTeal)
                        }
                    }
                    txHistory.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.no_transactions_yet), color = LocalAppColors.current.textSecondary, textAlign = TextAlign.Center)
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(txHistory, key = { it.txId }) { tx ->
                                ColdTxHistoryRow(
                                    tx = tx,
                                    // Tapping a transaction asks what to do with it. It used to
                                    // open the explorer outright, which left "Add to Portfolio"
                                    // on a button most people never looked for.
                                    onClick = { transactionActionTarget = tx }
                                )
                            }
                        }
                    }
                }
                else -> when {
                    isLoadingUtxos && utxos.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = KaspaTeal)
                        }
                    }
                    utxos.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.no_utxos), color = LocalAppColors.current.textSecondary, textAlign = TextAlign.Center)
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (utxos.size > 1) {
                                item {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(LocalAppColors.current.surface)
                                            .clickable { showCompoundFlow = true }
                                            .padding(16.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.CallMerge, null, tint = KaspaTeal)
                                            Spacer(Modifier.width(12.dp))
                                            Text(
                                                stringResource(R.string.compound_utxos),
                                                color = LocalAppColors.current.textPrimary,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            stringResource(R.string.compound_utxos_description),
                                            color = LocalAppColors.current.textSecondary,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                            items(utxos, key = { "${it.transactionId}:${it.index}" }) { utxo ->
                                val key = "${utxo.transactionId}:${utxo.index}"
                                ColdUtxoRow(
                                    utxo = utxo,
                                    label = utxoLabels[key],
                                    onRenameClick = {
                                        labelingUtxoKey = key
                                        labelInput = utxoLabels[key] ?: ""
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    labelingUtxoKey?.let { key ->
        AlertDialog(
            onDismissRequest = { labelingUtxoKey = null },
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.rename_utxo), color = LocalAppColors.current.textPrimary) },
            text = {
                OutlinedTextField(
                    value = labelInput,
                    onValueChange = { labelInput = it },
                    label = { Text(stringResource(R.string.name)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = LocalAppColors.current.textPrimary,
                        unfocusedTextColor = LocalAppColors.current.textPrimary,
                        focusedBorderColor = KaspaTeal,
                        unfocusedBorderColor = LocalAppColors.current.textSecondary,
                        focusedLabelColor = KaspaTeal,
                        unfocusedLabelColor = LocalAppColors.current.textSecondary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setUtxoLabel(address, key, labelInput)
                    utxoLabels = viewModel.getUtxoLabels(address)
                    labelingUtxoKey = null
                }) {
                    Text(stringResource(R.string.save), color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { labelingUtxoKey = null }) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }

    if (showQr) {
        QrCodeOverlay(value = address, onDismiss = { showQr = false })
    }

    transactionActionTarget?.let { tapped ->
        CenteredOptionsMenu(onDismissRequest = { transactionActionTarget = null }, centerHorizontally = true) {
            PopupMenuRow(Icons.Default.OpenInNew, "Open in Explorer") {
                transactionActionTarget = null
                uriHandler.openUri(kaspaExplorer.txUrl(tapped.txId))
            }
            PopupMenuRow(Icons.Default.PieChart, "Add to Portfolio") {
                transactionActionTarget = null
                portfolioCandidate = tapped
            }
        }
    }

    portfolioCandidate?.let { candidate ->
        AddToPortfolioSheet(
            tx = candidate,
            address = address,
            viewModel = portfolioViewModel,
            onDismiss = { portfolioCandidate = null },
            onAdded = { addedPortfolioName = it },
        )
    }
    addedPortfolioName?.let { name ->
        PortfolioAddedSnackbar(name) { addedPortfolioName = null }
    }
}

@Composable
private fun ColdUtxoRow(utxo: ColdStorageAddressDiscovery.AddressUtxo, label: String? = null, onRenameClick: () -> Unit = {}) {
    val kas = utxo.amountSompi / 100_000_000.0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LocalAppColors.current.surface)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (!label.isNullOrBlank()) {
                Text(
                    label,
                    color = KaspaTeal,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                "%.8f KAS".format(Locale.US, kas),
                color = LocalAppColors.current.textPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                "${utxo.transactionId}:${utxo.index}",
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (utxo.isCoinbase) {
            Text(
                stringResource(R.string.coinbase),
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
        IconButton(onClick = onRenameClick, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Edit, stringResource(R.string.rename), tint = KaspaTeal, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ColdTxHistoryRow(tx: ColdStorageAddressDiscovery.AddressTransaction, onClick: () -> Unit) {
    val kas = tx.amountSompi / 100_000_000.0
    val dateStr = tx.blockTimeMillis?.let {
        SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.US).format(Date(it))
    } ?: "Pending"
    val directionColor = if (tx.sent) Color(0xFFFF3B30) else Color(0xFF4CD964)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LocalAppColors.current.surface)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(directionColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (tx.sent) Icons.AutoMirrored.Filled.Send else Icons.AutoMirrored.Filled.CallReceived,
                null,
                tint = directionColor,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(if (tx.sent) "Sent" else "Received", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
            Text(dateStr, color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodySmall)
            Text(
                tx.txId,
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "${if (tx.sent) "-" else "+"}%.8f KAS".format(java.util.Locale.US, kas),
            color = directionColor,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * The kpub of one cold-storage account, as a QR for moving it to another device or a watch-only
 * wallet elsewhere. Mirrors iOS's ColdStorageKpubQRView.
 *
 * Deliberately says what a kpub is. It holds no private key and cannot spend anything, which is
 * why it is safe to display at all - but it derives EVERY address in the account, so whoever scans
 * it can watch the whole balance and history forever. That is a privacy decision the person
 * holding the phone should get to make knowingly, not discover later.
 */
@Composable
private fun KpubQrDialog(account: ColdStorageManager.ColdAccount, onDismiss: () -> Unit) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .clickable {
                    clipboardManager.setText(AnnotatedString(account.kpub))
                    Toast.makeText(context, "kpub copied to clipboard.", Toast.LENGTH_SHORT).show()
                }
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(account.name, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Image(
                painter = rememberQrBitmapPainter(account.kpub, size = 240),
                contentDescription = "kpub QR code",
                modifier = Modifier.size(240.dp)
            )
            Text(
                account.kpub,
                color = Color.Black.copy(alpha = 0.7f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center
            )
            Text(
                "Watch-only. This cannot spend, but it reveals every address in this account.",
                color = Color.Black.copy(alpha = 0.45f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
            Text("Tap anywhere to copy", color = Color.Black.copy(alpha = 0.4f), fontSize = 12.sp)
        }
    }
}
