package com.kachat.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import com.kachat.app.services.UtxoEntry
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaFiatAmountState
import com.kachat.app.util.KaspaUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// The pieces every Send Kaspa screen is built from, so they look and behave the same: the 1:1
// chat's Send KAS sheet, Profile's Send Kaspa, a spending address's Send, and KasSigner's send.
// Recipient on top (paste, scan a QR, names resolve), then the big amount (KAS or your currency,
// Max), the fee and balance, the fee speed and coin control, and a hold-to-send button.
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
                Text(
                    lockedAddress,
                    color = colors.textPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
            Text(
                resolvedAddress,
                color = colors.textSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
                stringResource(if (isValidAddress) R.string.valid_address else R.string.invalid_address_format),
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
 * (its own `onKasTextChange`). [sanitize] cleans what was typed before it's used (the chat sheet
 * drops stray characters); [focusOnAppear] puts the cursor in the amount when the screen appears.
 */
@Composable
fun KaspaAmountEntry(
    fiatAmountState: KaspaFiatAmountState,
    priceInCurrency: Double?,
    currencyCode: String,
    onMax: () -> Unit,
    modifier: Modifier = Modifier,
    sanitize: (String) -> String = { it },
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

// MARK: - Send button

/**
 * The send button. It fires only after a press is held (0.8 s): the fill sweeps across while
 * holding and resets if released early, so a payment can't go out on a stray touch. Accessibility
 * services get a plain click action instead. With [requiresHold] false it's an ordinary tap in the
 * same look (KasSigner's "Build Unsigned Transaction", which moves nothing by itself). Mirrors
 * iOS's `HoldToSendButton` (8d208b2, shared in 4d0324f).
 */
@Composable
fun HoldToSendButton(
    title: String,
    isBusy: Boolean,
    isEnabled: Boolean,
    onSend: () -> Unit,
    requiresHold: Boolean = true,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val currentOnSend by rememberUpdatedState(onSend)
    val shape = RoundedCornerShape(28.dp)
    val active = isEnabled && !isBusy
    BoxWithConstraints(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .background(KaspaTeal.copy(alpha = if (isEnabled || isBusy) 1f else 0.4f))
            .pointerInput(isEnabled, isBusy, requiresHold) {
                if (requiresHold) {
                    detectTapGestures(onPress = {
                        if (!active) return@detectTapGestures
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        val hold = scope.launch {
                            progress.animateTo(1f, tween(durationMillis = 800, easing = LinearEasing))
                            currentOnSend()
                        }
                        tryAwaitRelease()
                        if (hold.isActive) hold.cancel()
                        scope.launch { progress.animateTo(0f, tween(durationMillis = 200)) }
                    })
                } else {
                    detectTapGestures(onTap = { if (active) currentOnSend() })
                }
            }
            .clearAndSetSemantics {
                contentDescription = title
                role = Role.Button
                onClick(label = title) {
                    if (active) currentOnSend()
                    true
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(maxWidth * progress.value)
                .background(Color.White.copy(alpha = 0.28f)),
        )
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
            if (isBusy) {
                CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            } else {
                Text(title, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        }
    }
}
