package com.kachat.app.ui.screens

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.collectAsState
import com.kachat.app.repository.ChatRepository
import com.kachat.app.services.WalletService
import com.kachat.app.viewmodels.WalletViewModel
import com.kachat.app.services.UtxoEntry
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaFiatAmountState
import com.kachat.app.util.KaspaUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// The pieces every Send Kaspa screen is built from, so they look and behave the same: the 1:1
// chat's Send KAS sheet, Profile's Send Kaspa, a spending address's Send, and KasSigner's send.
// Recipient on top (paste, scan a QR, names resolve), then the big amount (KAS or your currency,
// Max), the fee and balance, the fee speed and coin control, and a slide-to-send button.
// Mirrors iOS's Views/Shared/SendKaspaComponents.swift (4d0324f). Change these pieces rather than
// one screen, so the screens stay matched.

/** The frosted card the Send Kaspa pieces sit on (iOS `sendKaspaGlass`). */
@Composable
fun Modifier.sendKaspaGlass(cornerRadius: Dp): Modifier {
    val shape = RoundedCornerShape(cornerRadius)
    return this
        .clip(shape)
        .background(LocalAppColors.current.surface)
        .border(0.8.dp, Color.White.copy(alpha = 0.18f), shape)
}

// MARK: - Recipient

/**
 * Who the Kaspa goes to: an address or a name, with Paste and Scan QR beside the field and a line
 * saying what the input resolved to. The screen keeps its own lookup logic (it watches [input]);
 * this only shows it. With [lockedAddress] (Compound UTXOs) the recipient is fixed.
 */
@Composable
fun SendRecipientCard(
    input: String,
    onInputChange: (String) -> Unit,
    isResolving: Boolean,
    resolvedAddress: String?,
    resolvedName: String?,
    lookupError: String?,
    isValidAddress: Boolean,
    onScan: () -> Unit,
    lockedAddress: String? = null,
    enabled: Boolean = true,
) {
    val colors = LocalAppColors.current
    val clipboard = LocalClipboardManager.current
    val trimmed = input.trim()
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .sendKaspaGlass(20.dp)
            .padding(14.dp),
    ) {
        Text(stringResource(R.string.to), color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        if (lockedAddress != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.AutoMirrored.Filled.CallMerge, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                MiddleEllipsisText(
                    lockedAddress,
                    color = colors.textPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
            Text(stringResource(R.string.consolidating_this_address), color = colors.textSecondary, fontSize = 12.sp)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val placeholder = stringResource(R.string.kaspa_qr_or_domain)
                BasicTextField(
                    value = input,
                    onValueChange = onInputChange,
                    singleLine = true,
                    enabled = enabled,
                    textStyle = TextStyle(color = colors.textPrimary, fontSize = 15.sp, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(KaspaTeal),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrect = false,
                        keyboardType = KeyboardType.Uri,
                    ),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box {
                            if (input.isEmpty()) {
                                Text(placeholder, color = colors.textTertiary, fontSize = 15.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                            }
                            inner()
                        }
                    },
                )
                Icon(
                    Icons.Default.ContentPaste,
                    contentDescription = stringResource(R.string.paste),
                    tint = KaspaTeal,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .clickable(enabled = enabled, role = Role.Button) {
                            clipboard.getText()?.text?.let { onInputChange(KaspaAddress.fromScanned(it)) }
                        },
                )
                Icon(
                    Icons.Default.QrCodeScanner,
                    contentDescription = stringResource(R.string.scan_qr),
                    tint = KaspaTeal,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .clickable(enabled = enabled, role = Role.Button) { onScan() },
                )
            }
            // Who the coins are going to - the card Create chat shows (iOS ac0ef19). Android's
            // card resolves the typed input on its own.
            AddressResolutionCard(input = trimmed)
            if (trimmed.isNotEmpty()) {
                SendRecipientStatusLine(
                    input = trimmed,
                    isResolving = isResolving,
                    resolvedAddress = resolvedAddress,
                    resolvedName = resolvedName,
                    lookupError = lookupError,
                    isValidAddress = isValidAddress,
                )
            }
        }
    }
}

@Composable
private fun SendRecipientStatusLine(
    input: String,
    isResolving: Boolean,
    resolvedAddress: String?,
    resolvedName: String?,
    lookupError: String?,
    isValidAddress: Boolean,
) {
    val colors = LocalAppColors.current
    when {
        isResolving -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            com.kachat.app.ui.theme.IosActivityIndicator(modifier = Modifier.size(14.dp), color = colors.textSecondary, strokeWidth = 2.dp)
            Text(stringResource(R.string.looking_up_domain), color = colors.textSecondary, fontSize = 12.sp)
        }
        lookupError != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.Cancel, contentDescription = null, tint = colors.danger, modifier = Modifier.size(14.dp))
            Text(lookupError, color = colors.danger, fontSize = 12.sp)
        }
        resolvedAddress != null -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = colors.success, modifier = Modifier.size(14.dp))
                Text(stringResource(R.string.resolved_name, resolvedName ?: ""), color = colors.success, fontSize = 12.sp)
            }
            MiddleEllipsisText(
                resolvedAddress,
                color = colors.textSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
        else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val tint = if (isValidAddress) colors.success else colors.danger
            Icon(
                if (isValidAddress) Icons.Default.CheckCircle else Icons.Default.Cancel,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(14.dp),
            )
            Text(
                addressValidityText(input, isValidAddress),
                color = tint,
                fontSize = 12.sp,
            )
        }
    }
}

// MARK: - Amount

/**
 * The big centred amount and its unit, with the KAS / your-currency switch (showing the converted
 * value) and Max under it. [fiatAmountState] hands the screen the KAS amount text after every edit
 * (its own `onKasTextChange`). [sanitize] cleans what was typed before it's used: by default
 * digits and one decimal point ("," read as "." for comma-decimal keyboards), at most 8 decimals
 * (iOS 16b64bc); [focusOnAppear] puts the cursor in the amount when the screen appears.
 */
@Composable
fun KaspaAmountEntry(
    fiatAmountState: KaspaFiatAmountState,
    priceInCurrency: Double?,
    currencyCode: String,
    onMax: () -> Unit,
    modifier: Modifier = Modifier,
    sanitize: (String) -> String = KaspaUnit::sanitizeAmountInput,
    isEstimatingMax: Boolean = false,
    maxEnabled: Boolean = true,
    focusOnAppear: Boolean = false,
) {
    val colors = LocalAppColors.current
    val focus = remember { FocusRequester() }
    if (focusOnAppear) {
        LaunchedEffect(Unit) {
            // The field doesn't exist yet on the tap that presented the screen.
            delay(300)
            runCatching { focus.requestFocus() }
        }
    }
    val display = fiatAmountState.displayText
    val fontSize = when {
        display.length <= 7 -> 52.sp
        display.length <= 10 -> 40.sp
        else -> 30.sp
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    runCatching { focus.requestFocus() }
                },
        ) {
            val amountLabel = KaspaUnit.label(stringResource(R.string.amount_kas))
            BasicTextField(
                value = display,
                onValueChange = { raw -> fiatAmountState.onDisplayTextChange(sanitize(raw), priceInCurrency) },
                singleLine = true,
                textStyle = TextStyle(
                    color = colors.textPrimary,
                    fontSize = fontSize,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                ),
                cursorBrush = SolidColor(KaspaTeal),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .widthIn(min = 40.dp, max = 260.dp)
                    .focusRequester(focus)
                    .clearAndSetSemantics { contentDescription = amountLabel },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.Center) {
                        if (display.isEmpty()) {
                            Text("0", color = colors.textTertiary, fontSize = fontSize, fontWeight = FontWeight.Bold)
                        }
                        inner()
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (fiatAmountState.isFiatMode) currencyCode.uppercase() else KaspaUnit.symbol,
                color = colors.textSecondary,
                fontSize = fontSize * 0.55f,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (priceInCurrency != null) {
                val switchLabel = stringResource(R.string.payment_switch_currency)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .sendKaspaGlass(14.dp)
                        .clickable { fiatAmountState.toggleMode(priceInCurrency) }
                        .clearAndSetSemantics {
                            contentDescription = switchLabel
                            role = Role.Button
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Icon(Icons.Default.SwapVert, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(14.dp))
                    Text(
                        fiatAmountState.conversionLabelText(priceInCurrency, currencyCode)
                            ?: if (fiatAmountState.isFiatMode) KaspaUnit.symbol else currencyCode.uppercase(),
                        color = colors.textPrimary,
                        fontSize = 12.sp,
                    )
                }
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .alpha(if (maxEnabled) 1f else 0.5f)
                    .sendKaspaGlass(14.dp)
                    .clickable(enabled = maxEnabled && !isEstimatingMax, role = Role.Button) { onMax() }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                if (isEstimatingMax) {
                    com.kachat.app.ui.theme.IosActivityIndicator(modifier = Modifier.size(14.dp), color = KaspaTeal, strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.max), color = KaspaTeal, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
            }
        }
    }
}

// MARK: - Fee and coin control

/** The localized label for a fee speed (iOS shows `WithdrawFeeTier.rawValue` localized). */
@Composable
fun feeTierLabel(tier: ColdFeeTier): String = stringResource(
    when (tier) {
        ColdFeeTier.NORMAL -> R.string.fee_tier_normal
        ColdFeeTier.FAST -> R.string.fee_tier_fast
        ColdFeeTier.PRIORITY -> R.string.fee_tier_priority
    }
)

/**
 * Network fee (tap the amount to set a custom one, edited in place), the fee speed, and coin
 * control - one card. [feeText] is the fee as shown ("0.0001 KAS"), null while unknown.
 */
@Composable
fun SendFeeControls(
    feeTier: ColdFeeTier,
    onFeeTierChange: (ColdFeeTier) -> Unit,
    isEditingFee: Boolean,
    customFeeText: String,
    onCustomFeeTextChange: (String) -> Unit,
    isEstimatingFee: Boolean,
    feeText: String?,
    onStartEditing: () -> Unit,
    onCommit: () -> Unit,
    showsCoinControl: Boolean = true,
    coinControlSummary: String = "",
    onCoinControl: () -> Unit = {},
) {
    val colors = LocalAppColors.current
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .sendKaspaGlass(20.dp)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.network_fee), color = colors.textPrimary, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            when {
                isEditingFee -> {
                    val feeFocus = remember { FocusRequester() }
                    // Straight into the field, so the keyboard is up for the custom fee.
                    LaunchedEffect(Unit) { runCatching { feeFocus.requestFocus() } }
                    BasicTextField(
                        value = customFeeText,
                        onValueChange = onCustomFeeTextChange,
                        singleLine = true,
                        textStyle = TextStyle(color = colors.textPrimary, fontSize = 15.sp, textAlign = TextAlign.End),
                        cursorBrush = SolidColor(KaspaTeal),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onCommit() }),
                        modifier = Modifier
                            .widthIn(max = 110.dp)
                            .focusRequester(feeFocus),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterEnd) {
                                if (customFeeText.isEmpty()) {
                                    Text("0.00", color = colors.textTertiary, fontSize = 15.sp)
                                }
                                inner()
                            }
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.done),
                        tint = KaspaTeal,
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button) { onCommit() },
                    )
                }
                isEstimatingFee -> com.kachat.app.ui.theme.IosActivityIndicator(
                    modifier = Modifier.size(16.dp),
                    color = colors.textSecondary,
                    strokeWidth = 2.dp,
                )
                feeText != null -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.clickable(role = Role.Button) { onStartEditing() },
                ) {
                    Text(feeText, color = KaspaTeal, fontSize = 15.sp, textDecoration = TextDecoration.Underline)
                    Icon(Icons.Default.Edit, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(11.dp))
                }
                else -> Text("—", color = colors.textSecondary, fontSize = 15.sp)
            }
        }
        SendFeeTierPicker(feeTier, onFeeTierChange)
        Text(stringResource(R.string.send_fee_tier_footer), color = colors.textSecondary, fontSize = 12.sp)

        if (showsCoinControl) {
            HorizontalDivider(color = colors.divider)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onCoinControl() },
            ) {
                Text(stringResource(R.string.coin_control), color = colors.textPrimary, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                Text(coinControlSummary, color = colors.textSecondary, fontSize = 15.sp)
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Normal / Fast / Priority as iOS's segmented Picker draws it: a tinted track with the chosen
 *  segment on a raised thumb. */
@Composable
private fun SendFeeTierPicker(selected: ColdFeeTier, onSelect: (ColdFeeTier) -> Unit) {
    val colors = LocalAppColors.current
    val thumb = if (colors.isDark) Color(0xFF636366) else Color.White
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.textPrimary.copy(alpha = 0.08f))
            .padding(2.dp)
    ) {
        ColdFeeTier.entries.forEach { tier ->
            val isSelected = tier == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (isSelected) thumb else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(tier) }
                    .padding(vertical = 6.dp),
            ) {
                Text(
                    feeTierLabel(tier),
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

/** "Automatic" or "3 UTXOs selected", for the coin control row. The count stays English, as on
 *  iOS (`coinControlSummary`). */
@Composable
fun coinControlSummary(manualUtxos: List<UtxoEntry>?): String {
    val automatic = stringResource(R.string.automatic)
    return manualUtxos?.let { "${it.size} UTXO${if (it.size == 1) "" else "s"} selected" } ?: automatic
}

/** A small glass capsule for one line of context (available balance, fee). Text inside takes the
 *  pill's caption style and secondary colour unless it sets its own. */
@Composable
fun SendInfoPill(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = LocalAppColors.current
    CompositionLocalProvider(
        LocalContentColor provides colors.textSecondary,
        LocalTextStyle provides TextStyle(fontSize = 12.sp, color = colors.textSecondary),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = modifier
                .sendKaspaGlass(14.dp)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            content = content,
        )
    }
}

// MARK: - Send From

/** A spending address as the Send screens name it: its nickname, else "Address #N" (iOS
 *  `SpendingAddressEntry.displayLabel`, English like iOS). */
fun spendingAddressDisplayLabel(index: Int, label: String?): String =
    label?.trim()?.takeIf { it.isNotEmpty() } ?: "Address #$index"

/**
 * Which spending address a send comes from, picked from a Send screen's Available pill: every
 * spending address you can see (plus hidden ones that hold Kaspa), funded ones first, each with
 * its balance, a Primary tag, and a checkmark on the current source. Picking one changes this
 * send only - the primary stays where it is. Mirrors iOS's `SpendingSourcePicker` (e994235).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendingSourcePicker(
    currentIndex: Int,
    viewModel: WalletViewModel,
    onPick: (WalletService.SpendingAddressEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalAppColors.current
    val all by viewModel.manageAddresses.collectAsState()
    var loaded by remember { mutableStateOf(false) }
    // A live load, so the balances it lists are current (the list shows the last one meanwhile).
    LaunchedEffect(Unit) {
        viewModel.loadManageAddressesAndAwait()
        loaded = true
    }
    val entries = remember(all, currentIndex) {
        all.filter { !it.hidden || it.balanceSompi > 0 || it.index == currentIndex }
            .sortedWith(compareBy({ if (it.balanceSompi > 0) 0 else 1 }, { it.index }))
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = colors.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart)) {
                Text(stringResource(R.string.cancel), color = KaspaTeal, fontSize = 17.sp)
            }
            Text(
                stringResource(R.string.send_from),
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (entries.isEmpty()) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp)) {
                if (loaded) {
                    Text("—", color = colors.textSecondary)
                } else {
                    com.kachat.app.ui.theme.IosActivityIndicator(modifier = Modifier.size(22.dp), color = colors.textSecondary, strokeWidth = 2.dp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp, bottom = 24.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surface),
            ) {
                itemsIndexed(entries, key = { _, entry -> entry.index }) { position, entry ->
                    if (position > 0) HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onPick(entry)
                                onDismiss()
                            }
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    spendingAddressDisplayLabel(entry.index, entry.label),
                                    color = colors.textPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (entry.isCurrent) {
                                    Text(
                                        stringResource(R.string.primary),
                                        color = KaspaTeal,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(KaspaTeal.copy(alpha = 0.15f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            Text(
                                "${entry.address.take(14)}...${entry.address.takeLast(6)}",
                                color = colors.textSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                        }
                        Text(
                            "${ChatRepository.formatKas(entry.balanceSompi)} ${KaspaUnit.symbol}",
                            color = if (entry.balanceSompi > 0) colors.textPrimary else colors.textSecondary,
                            fontSize = 15.sp,
                            style = TextStyle(fontFeatureSettings = "tnum"),
                        )
                        if (entry.index == currentIndex) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Send button

/**
 * The send button: slide the white knob to the right end to send, so a payment can't go out on a
 * stray touch. Haptics on the first move and on reaching the end; letting go before the end
 * springs it back; after a send that didn't go through (an error, the small-amount question) it
 * resets by itself, and again when the send finishes. Accessibility services get a plain click
 * action. With [requiresSlide] false it's an ordinary tap in the same look (KasSigner's "Build
 * Unsigned Transaction", which moves nothing by itself). Mirrors iOS's `SendActionButton`
 * (afaad34, was HoldToSendButton).
 */
@Composable
fun SendActionButton(
    title: String,
    isBusy: Boolean,
    isEnabled: Boolean,
    onSend: () -> Unit,
    requiresSlide: Boolean = true,
) {
    // iOS: a light impact as the knob leaves the start, a medium one when it reaches the end.
    val haptic = com.kachat.app.util.rememberHaptics()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val currentOnSend by rememberUpdatedState(onSend)
    val currentBusy by rememberUpdatedState(isBusy)
    val active = isEnabled && !isBusy
    val currentActive by rememberUpdatedState(active)
    val height = 56.dp
    val inset = 4.dp
    val knob = height - inset * 2
    // How far the knob has travelled, in px.
    var offset by remember { mutableFloatStateOf(0f) }
    var reachedEnd by remember { mutableStateOf(false) }
    var resetJob by remember { mutableStateOf<Job?>(null) }

    fun reset() {
        reachedEnd = false
        resetJob?.cancel()
        resetJob = scope.launch {
            // iOS .spring(response: 0.35, dampingFraction: 0.8).
            animate(offset, 0f, animationSpec = spring(dampingRatio = 0.8f, stiffness = 322f)) { value, _ -> offset = value }
        }
    }

    // The send finished (or failed after starting): put the knob back.
    LaunchedEffect(isBusy) { if (!isBusy && offset != 0f) reset() }

    BoxWithConstraints(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(KaspaTeal.copy(alpha = if (isEnabled || isBusy) 1f else 0.4f))
            .then(
                if (requiresSlide) Modifier
                else Modifier.pointerInput(Unit) { detectTapGestures(onTap = { if (currentActive) currentOnSend() }) }
            )
            .clearAndSetSemantics {
                contentDescription = title
                role = Role.Button
                onClick(label = title) {
                    if (active) currentOnSend()
                    true
                }
            },
    ) {
        val knobPx = with(density) { knob.toPx() }
        val insetPx = with(density) { inset.toPx() }
        val maxOffset = (constraints.maxWidth - knobPx - insetPx * 2).coerceAtLeast(1f)
        val progress = if (requiresSlide) (offset / maxOffset).coerceIn(0f, 1f) else 0f

        if (requiresSlide) {
            // The trail behind the knob.
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(inset * 2 + knob + with(density) { offset.toDp() })
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.25f)),
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (isBusy) 1f else 1f - progress),
        ) {
            if (isBusy) {
                CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            } else {
                Text(title, color = Color.Black, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            }
        }
        if (requiresSlide && !isBusy) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .offset { IntOffset((insetPx + offset).roundToInt(), 0) }
                    .size(knob)
                    .shadow(4.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White)
                    .pointerInput(maxOffset) {
                        detectHorizontalDragGestures(
                            onDragStart = { if (currentActive) resetJob?.cancel() },
                            onDragEnd = {
                                if (!currentActive) return@detectHorizontalDragGestures
                                if (offset >= maxOffset * 0.95f) {
                                    offset = maxOffset
                                    currentOnSend()
                                    // A send that didn't start (dust question, a validation
                                    // error) leaves it not busy: put the knob back.
                                    scope.launch {
                                        delay(600)
                                        if (!currentBusy) reset()
                                    }
                                } else {
                                    reset()
                                }
                            },
                            onDragCancel = { reset() },
                        ) { change, dragAmount ->
                            if (!currentActive) return@detectHorizontalDragGestures
                            change.consume()
                            val next = (offset + dragAmount).coerceIn(0f, maxOffset)
                            if (offset == 0f && next > 0f) haptic(com.kachat.app.util.IosHaptic.IMPACT_LIGHT)
                            offset = next
                            if (offset >= maxOffset && !reachedEnd) {
                                reachedEnd = true
                                haptic(com.kachat.app.util.IosHaptic.IMPACT_MEDIUM)
                            } else if (offset < maxOffset) {
                                reachedEnd = false
                            }
                        }
                    },
            ) {
                Icon(
                    Icons.Default.KeyboardDoubleArrowRight,
                    contentDescription = null,
                    tint = KaspaTeal,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/**
 * The line under an address field: "Valid address", the other network's reason ("This is a
 * Testnet address. KaChat is on Mainnet."), or "Invalid address format" (iOS
 * `KaspaAddress.validityText`, ce20e87).
 */
@Composable
fun addressValidityText(address: String, isValid: Boolean): String = when {
    isValid -> stringResource(R.string.valid_address)
    else -> KaspaAddress.otherNetworkMessageRes(address)?.let { stringResource(it) }
        ?: stringResource(R.string.invalid_address_format)
}
