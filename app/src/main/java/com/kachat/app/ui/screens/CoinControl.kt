package com.kachat.app.ui.screens

import com.kachat.app.R
import androidx.compose.ui.text.font.FontFamily
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.kachat.app.services.UtxoEntry
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.KaspaUnit
import kotlinx.coroutines.launch
import java.util.Locale

/** Normal/Fast/Priority multiplier system, matching iOS's `WithdrawFeeTier` exactly - shared by
 *  [ColdSendFlow] and the spending-address send flow (`SpendingAddressSendFlow` in Screens.kt). */
enum class FeeTier(val label: String, val multiplier: Long) {
    NORMAL("Normal", 1),
    FAST("Fast", 2),
    PRIORITY("Priority", 5)
}

/**
 * [CoinControlScreen] as iOS presents its `CoinControlView`: a sheet over the send that asked for
 * it, Cancel leading its bar. Confirm hands the pick to [onDone] and slides the sheet down; Cancel,
 * Back or a swipe down leave the pick as it was.
 */
@Composable
fun CoinControlSheet(
    fromAddress: String,
    fetchUtxos: suspend (String) -> List<UtxoEntry>,
    initialSelection: List<UtxoEntry>?,
    onDone: (List<UtxoEntry>?) -> Unit,
    onDismiss: () -> Unit,
    maxSelection: Int = com.kachat.app.util.KaspaUtxoSelector.MAX_INPUTS_PER_TRANSACTION,
    /** Coin labels for [fromAddress] (outpoint key "txId:index" -> label), shown over each
     *  coin's amount. */
    utxoLabels: Map<String, String> = emptyMap(),
) {
    IosFullSheet(onDismissed = onDismiss, swipeToDismiss = true, grouped = true) { close ->
        BackHandler(onBack = close)
        CoinControlScreen(
            fromAddress = fromAddress,
            fetchUtxos = fetchUtxos,
            initialSelection = initialSelection,
            onDone = { selection -> onDone(selection); close() },
            onCancel = close,
            maxSelection = maxSelection,
            utxoLabels = utxoLabels,
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
 * re-resolving against a fresh fetch at build time anyway (see [KaspaWalletEngine]'s
 * `manualUtxos` handling).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoinControlScreen(
    fromAddress: String,
    fetchUtxos: suspend (String) -> List<UtxoEntry>,
    initialSelection: List<UtxoEntry>?,
    onDone: (List<UtxoEntry>?) -> Unit,
    onCancel: () -> Unit,
    /** The most coins one transaction from here may spend: the wallet's input cap. A pick never
     *  grows past it (see [com.kachat.app.util.CoinControlSelection]). */
    maxSelection: Int = com.kachat.app.util.KaspaUtxoSelector.MAX_INPUTS_PER_TRANSACTION,
    utxoLabels: Map<String, String> = emptyMap(),
) {
    var utxos by remember { mutableStateOf<List<UtxoEntry>>(emptyList()) }
    var selectedKeys by remember { mutableStateOf<Set<com.kachat.app.services.Outpoint>>(emptySet()) }
    var isLoading by remember { mutableStateOf(false) }
    // The node could not be asked. Shown in place of "no coins": an outage must never read as an
    // empty address when the coins here are about to be spent (iOS CoinControlView.loadError).
    var loadError by remember { mutableStateOf<String?>(null) }
    val loadScope = rememberCoroutineScope()

    suspend fun loadUtxos() {
        isLoading = true
        try {
            utxos = fetchUtxos(fromAddress)
            loadError = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = com.kachat.app.util.UserFacingError.message(e, "Couldn't load the coins.")
        }
        isLoading = false
    }

    LaunchedEffect(fromAddress) {
        loadUtxos()
        if (!initialSelection.isNullOrEmpty()) {
            selectedKeys = com.kachat.app.util.CoinControlSelection
                .resolve(utxos, initialSelection.map { it.outpoint }.toSet(), maxSelection)
                .map { it.outpoint }.toSet()
        }
    }
    val atCap = com.kachat.app.util.CoinControlSelection.isAtCap(selectedKeys.size, utxos.size, maxSelection)

    val selectedTotalSompi = utxos.filter { selectedKeys.contains(it.outpoint) }.sumOf { it.utxoEntry.amount }

    // iOS's CoinControlView: an inline bar (Cancel, "Coin Control", the ellipsis.circle menu), an
    // inset grouped list of the coins with the running total as its footer, and a capsule button
    // pinned under it.
    val colors = LocalAppColors.current
    Scaffold(
        containerColor = colors.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CenterAlignedTopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(stringResource(R.string.coin_control), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
                navigationIcon = {
                    SheetBarButton(stringResource(R.string.cancel), onClick = onCancel, modifier = Modifier.padding(start = 12.dp))
                },
                actions = {
                    var showMenu by remember { mutableStateOf(false) }
                    // iOS 26: the menu on a glass disc, 16 in from the edge.
                    SheetBarIconButton(
                        Icons.Default.MoreHoriz,
                        "More",
                        onClick = { showMenu = true },
                        modifier = Modifier.padding(end = 12.dp),
                        iconSize = 22.dp,
                    )
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.select_all)) },
                            onClick = { selectedKeys = com.kachat.app.util.CoinControlSelection.selectAll(utxos, maxSelection); showMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.automatic_clear_selection)) },
                            onClick = { selectedKeys = emptySet(); showMenu = false }
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background)
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(KaspaTeal)
                    .clickable {
                        val selected = com.kachat.app.util.CoinControlSelection.resolve(utxos, selectedKeys, maxSelection)
                        onDone(selected.ifEmpty { null })
                    }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (selectedKeys.isEmpty()) stringResource(R.string.use_automatic_selection) else stringResource(R.string.confirm_selection),
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        ) {
            when (coinListContent(isLoading, utxos.isNotEmpty(), loadError)) {
                CoinListContent.LOADING -> item {
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surface).padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        com.kachat.app.ui.theme.IosActivityIndicator(color = colors.textSecondary)
                    }
                }
                CoinListContent.LOAD_FAILED -> item {
                    UtxoLoadFailureRow(
                        detail = loadError.orEmpty(),
                        retry = { loadScope.launch { loadUtxos() } },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surface),
                    )
                }
                CoinListContent.EMPTY -> item {
                    Text(
                        stringResource(R.string.no_utxos_found_at_address),
                        color = colors.textSecondary,
                        fontSize = 17.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surface).padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                CoinListContent.COINS -> {
                    if (atCap) {
                        item {
                            Text(
                                stringResource(R.string.coin_control_max_selection, maxSelection),
                                color = colors.textSecondary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                            )
                        }
                    }
                    itemsIndexed(utxos, key = { _, it -> "${it.outpoint.transactionId}:${it.outpoint.index}" }) { index, utxo ->
                        val isSelected = selectedKeys.contains(utxo.outpoint)
                        val first = index == 0
                        val last = index == utxos.lastIndex
                        val shape = RoundedCornerShape(
                            topStart = if (first) 10.dp else 0.dp, topEnd = if (first) 10.dp else 0.dp,
                            bottomStart = if (last) 10.dp else 0.dp, bottomEnd = if (last) 10.dp else 0.dp,
                        )
                        Column(Modifier.fillMaxWidth().clip(shape).background(colors.surface)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        // Past the cap the tap is refused; the note above says why.
                                        com.kachat.app.util.CoinControlSelection
                                            .toggle(selectedKeys, utxo.outpoint, maxSelection)
                                            ?.let { selectedKeys = it }
                                    }
                                    .alpha(if (!isSelected && atCap) 0.5f else 1f)
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (isSelected) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                                    null,
                                    tint = if (isSelected) KaspaTeal else colors.textSecondary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    utxoLabels["${utxo.outpoint.transactionId}:${utxo.outpoint.index}"]?.takeIf { it.isNotEmpty() }?.let { label ->
                                        Text(label, color = KaspaTeal, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                    Text(
                                        "%.8f ${KaspaUnit.symbol}".format(Locale.US, utxo.utxoEntry.amount / 100_000_000.0),
                                        color = colors.textPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 15.sp,
                                    )
                                    Text(
                                        "${utxo.outpoint.transactionId.take(10)}...:${utxo.outpoint.index}",
                                        color = colors.textSecondary,
                                        fontSize = 11.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    )
                                }
                            }
                            if (!last) HorizontalDivider(color = colors.divider, thickness = 0.5.dp, modifier = Modifier.padding(start = 50.dp))
                        }
                    }
                    // iOS's section footer, word for word (it is not translated there either).
                    if (selectedKeys.isNotEmpty()) {
                        item {
                            val count = selectedKeys.size
                            Text(
                                "Selected: %.8f ${KaspaUnit.symbol} (%d UTXO%s)".format(
                                    Locale.US, selectedTotalSompi / 100_000_000.0, count, if (count == 1) "" else "s"
                                ),
                                color = colors.textSecondary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "Fee 0.0001 KAS" for a history row, or null when the fee cannot be known (a coinbase, or an
 *  input the API did not resolve). Every transaction pays one, whoever sent it; on a received
 *  one it is what the sender paid. Mirrors iOS 9839906. */
fun transactionFeeText(feeSompi: Long?): String? {
    val fee = feeSompi ?: return null
    val kas = fee / 100_000_000.0
    val text = if (kas >= 0.001) "%.4f".format(kas) else "%.8f".format(kas)
    return "Fee $text ${KaspaUnit.symbol}"
}

/**
 * In place of an empty coin list when the node could not be asked - iOS's `UtxoLoadFailureRow`:
 * "Couldn't load the coins at this address" in red beside the warning triangle, what went wrong
 * under it, and Try Again. An outage used to render as "no coins", which on an address about to
 * be spent from is the wrong thing to believe.
 */
@Composable
fun UtxoLoadFailureRow(detail: String, retry: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.WarningAmber, contentDescription = null, tint = colors.danger, modifier = Modifier.size(17.dp))
            Text(stringResource(R.string.coins_couldnt_load), color = colors.danger, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(detail, color = colors.textSecondary, fontSize = 12.sp)
        Text(
            stringResource(R.string.try_again),
            color = KaspaTeal,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = retry),
        )
    }
}

/** What coin control's list shows - iOS CoinControlView's branches. */
internal enum class CoinListContent { LOADING, LOAD_FAILED, EMPTY, COINS }

/** The spinner while the first fetch runs, the failure row when it could not be asked, "No
 *  UTXOs found" only when it answered with nothing, the coins otherwise. */
internal fun coinListContent(isLoading: Boolean, hasCoins: Boolean, loadError: String?): CoinListContent = when {
    hasCoins -> CoinListContent.COINS
    isLoading -> CoinListContent.LOADING
    loadError != null -> CoinListContent.LOAD_FAILED
    else -> CoinListContent.EMPTY
}
