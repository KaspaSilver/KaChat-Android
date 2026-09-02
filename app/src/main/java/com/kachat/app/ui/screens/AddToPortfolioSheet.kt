package com.kachat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.services.ColdStorageAddressDiscovery
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.formatFiatAmount
import com.kachat.app.util.formatKasAmount
import com.kachat.app.viewmodels.PortfolioViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Adds an on-chain transaction to a portfolio: pick the portfolio, then check over what is being
 * recorded, then confirm. Mirrors iOS's `AddToPortfolioSheet`.
 *
 * Two steps rather than one long form. The portfolio is the decision that changes what everything
 * after it belongs to, and a half sheet cannot hold a picker AND five fields without becoming a
 * scroll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPortfolioSheet(
    tx: ColdStorageAddressDiscovery.AddressTransaction,
    /** The address the transaction was viewed under - recorded with the row. */
    address: String,
    viewModel: PortfolioViewModel,
    onDismiss: () -> Unit,
    onAdded: (String) -> Unit,
) {
    val colors = LocalAppColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val portfolios by viewModel.portfolios.collectAsState()
    val activePortfolioId by viewModel.activePortfolioId.collectAsState()
    val currencyCode by viewModel.currency.collectAsState()

    val amountKas = tx.amountSompi / 100_000_000.0
    // Editable, like iOS: the block time is when the chain saw it, which is not always when the
    // trade you are recording actually happened.
    var timestamp by remember { mutableStateOf(tx.blockTimeMillis ?: System.currentTimeMillis()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    var selectedId by remember { mutableStateOf<String?>(null) }
    var selectedName by remember { mutableStateOf("") }
    // Money leaving the address is a sale, money arriving is a buy. Both editable: a transfer
    // between your own addresses is neither, and only you know which it was.
    var type by remember { mutableStateOf(if (tx.sent) "sell" else "buy") }
    var amountText by remember { mutableStateOf(trimmedAmount(amountKas)) }
    var priceText by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var isLookingUpPrice by remember { mutableStateOf(false) }
    var alreadyAdded by remember { mutableStateOf(false) }

    // The price ON THAT DAY, not today's: today's price on a transaction from last year would
    // quietly misstate every figure the portfolio derives from it.
    LaunchedEffect(timestamp) {
        isLookingUpPrice = true
        val dayStart = Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val price = viewModel.historicalPrice(dayStart)
        isLookingUpPrice = false
        // Never overwrite something already typed - the lookup can land late.
        if (priceText.isEmpty() && price != null) priceText = trimmedAmount(price, 6)
    }

    val amount = amountText.replace(',', '.').toDoubleOrNull()
    val price = priceText.replace(',', '.').toDoubleOrNull()
    val total = if (amount != null && price != null) amount * price else null

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = colors.background) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (selectedId == null) {
                Text("Add to Portfolio", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(
                    "${if (tx.sent) "Sent" else "Received"} ${formatKasAmount(amountKas)} on " +
                        SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.US).format(Date(timestamp)),
                    color = colors.textSecondary,
                    fontSize = 13.sp
                )
                // Which portfolios already hold this transaction, so the duplicate is visible
                // while the choice is being made rather than only after it - same as iOS, and the
                // same question the swap chooser asks.
                val duplicateIds = viewModel.portfolioIdsContaining(tx.txId)
                portfolios.forEach { portfolio ->
                    val isDuplicate = portfolio.id in duplicateIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.surface)
                            .clickable {
                                selectedId = portfolio.id
                                selectedName = portfolio.name
                                alreadyAdded = isDuplicate
                            }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(portfolio.name, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                            when {
                                isDuplicate -> Text(
                                    "Already added",
                                    color = Color_Warning,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                portfolio.id == activePortfolioId -> Text(
                                    "Current",
                                    color = KaspaTeal,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        Icon(Icons.Default.KeyboardArrowRight, null, tint = colors.textSecondary)
                    }
                }
            } else {
                // Back / title / Confirm on one row, the way iOS's navigation bar carries them -
                // rather than a full-width button the form has to be scrolled past to reach.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selectedId = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                    Text(
                        selectedName,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            val finalAmount = amount ?: return@TextButton
                            viewModel.addTransaction(
                                type = type,
                                amountKas = finalAmount,
                                fiatValue = total ?: 0.0,
                                timestampMillis = timestamp,
                                notes = notes.trim().ifBlank { null },
                                portfolioId = selectedId,
                                sourceAddress = address,
                                sourceTxId = tx.txId,
                            )
                            onAdded(selectedName)
                            onDismiss()
                        },
                        enabled = amount != null && amount > 0,
                    ) {
                        Text(
                            "Confirm",
                            color = if (amount != null && amount > 0) KaspaTeal else colors.textSecondary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (alreadyAdded) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color_Warning.copy(alpha = 0.12f))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, null, tint = Color_Warning, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "This transaction is already in $selectedName. Adding it again will double-count it.",
                            color = colors.textSecondary,
                            fontSize = 12.sp
                        )
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("buy" to "Buy", "sell" to "Sell").forEach { (value, label) ->
                        val active = type == value
                        Text(
                            text = label,
                            color = if (active) KaspaTeal else colors.textSecondary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) KaspaTeal.copy(alpha = 0.15f) else colors.surface)
                                .clickable { type = value }
                                .padding(vertical = 10.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount (KAS)", color = colors.textSecondary) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = it },
                    label = { Text("Price per KAS", color = colors.textSecondary) },
                    trailingIcon = {
                        if (isLookingUpPrice) {
                            CircularProgressIndicator(strokeWidth = 1.5.dp, modifier = Modifier.size(16.dp), color = colors.textSecondary)
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (total != null) {
                    Text("Total ${formatFiatAmount(total, currencyCode)}", color = colors.textSecondary, fontSize = 13.sp)
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Note (optional)", color = colors.textSecondary) },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Date", color = colors.textSecondary, fontSize = 12.sp)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showDatePicker = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timestamp)),
                            color = colors.textPrimary,
                            fontSize = 13.sp
                        )
                    }
                    OutlinedButton(
                        onClick = { showTimePicker = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp)),
                            color = colors.textPrimary,
                            fontSize = 13.sp
                        )
                    }
                }

                Text("Transaction", color = colors.textSecondary, fontSize = 12.sp)
                Text(
                    tx.txId,
                    color = colors.textPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // Recording the txid is what lets a later add of the same transaction warn
                    // instead of silently double-counting it.
                    "Recorded with the row, so this transaction is recognised if you add it again.",
                    color = colors.textSecondary,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = timestamp)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    // Keep the time of day: the picker returns midnight UTC for the chosen day,
                    // and replacing the whole timestamp with it would silently move the entry.
                    state.selectedDateMillis?.let { picked ->
                        val old = Calendar.getInstance().apply { timeInMillis = timestamp }
                        timestamp = Calendar.getInstance().apply {
                            timeInMillis = picked
                            set(Calendar.HOUR_OF_DAY, old.get(Calendar.HOUR_OF_DAY))
                            set(Calendar.MINUTE, old.get(Calendar.MINUTE))
                        }.timeInMillis
                    }
                    showDatePicker = false
                }) { Text("OK", color = KaspaTeal, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(com.kachat.app.R.string.cancel), color = colors.textSecondary)
                }
            },
        ) {
            DatePicker(state = state)
        }
    }

    if (showTimePicker) {
        val now = Calendar.getInstance().apply { timeInMillis = timestamp }
        val state = rememberTimePickerState(
            initialHour = now.get(Calendar.HOUR_OF_DAY),
            initialMinute = now.get(Calendar.MINUTE),
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            containerColor = colors.surface,
            title = { Text("Time", color = colors.textPrimary) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    timestamp = Calendar.getInstance().apply {
                        timeInMillis = timestamp
                        set(Calendar.HOUR_OF_DAY, state.hour)
                        set(Calendar.MINUTE, state.minute)
                    }.timeInMillis
                    showTimePicker = false
                }) { Text("OK", color = KaspaTeal, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text(stringResource(com.kachat.app.R.string.cancel), color = colors.textSecondary)
                }
            },
        )
    }
}

private val Color_Warning = androidx.compose.ui.graphics.Color(0xFFFFA000)

private fun trimmedAmount(value: Double, maxDecimals: Int = 8): String {
    var text = String.format(Locale.US, "%.${maxDecimals}f", value)
    while (text.endsWith("0") && text.substringAfter('.').length > 2) text = text.dropLast(1)
    return text
}

/**
 * Brief confirmation that a transaction landed in a portfolio.
 *
 * The sheet closing is not on its own proof that anything happened - it closes on dismiss too -
 * so this says which portfolio got the row, then takes itself away.
 */
@Composable
fun PortfolioAddedSnackbar(portfolioName: String, onDismiss: () -> Unit) {
    val colors = LocalAppColors.current
    LaunchedEffect(portfolioName) {
        kotlinx.coroutines.delay(2_200)
        onDismiss()
    }
    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surface)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color(0xFF4CD964),
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("Added to $portfolioName", color = colors.textPrimary, fontSize = 14.sp)
        }
    }
}
