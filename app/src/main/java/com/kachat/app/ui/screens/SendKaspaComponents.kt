package com.kachat.app.ui.screens

import com.kachat.app.services.AddressBookManager
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.outlined.Book
import androidx.compose.foundation.layout.statusBars

import androidx.compose.foundation.layout.navigationBarsPadding

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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.collectAsState
import com.kachat.app.viewmodels.WalletViewModel
import com.kachat.app.services.UtxoEntry
import com.kachat.app.ui.theme.IosRoundedFontFamily
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
// chat's Send KAS sheet, Send Kaspa (Chats > + > Send Kaspa, Donate, the Address Book, an
// address's Send, paying a payment request), KaPosts tips, and KasSigner's send. Every send but
// KasSigner's starts with From (SendFromSection): Chatting Address or Choose an Address. Then the
// recipient (paste, scan a QR, names resolve), the big amount (KAS or your currency, Max), the
// fee speed and coin control, and a slide-to-send button. Mirrors iOS's
// Views/Shared/SendKaspaComponents.swift (4d0324f, 6333dd3). Change these pieces rather than one
// screen, so the screens stay matched.

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
    /** Every service's answer for a typed name (`NameServicesClient.resolveEverywhere`, .kachat
     *  first) and the one in use: the rest are offered under "Other domains" (iOS 6ac48a7). */
    nameResolutions: List<com.kachat.app.services.NameResolution> = emptyList(),
    selectedTld: com.kachat.app.services.NameServiceTLD? = null,
    onSelectResolution: (com.kachat.app.services.NameResolution) -> Unit = {},
) {
    val colors = LocalAppColors.current
    val clipboard = LocalClipboardManager.current
    val trimmed = input.trim()
    val addressBook = rememberAddressBookEntries()
    var showAddressBook by remember { mutableStateOf(false) }
    if (showAddressBook) {
        AddressBookPickerSheet(onDismiss = { showAddressBook = false }, onSelect = { onInputChange(it.address) })
    }
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
                if (addressBook.isNotEmpty()) {
                    Icon(
                        Icons.Outlined.Book,
                        contentDescription = stringResource(R.string.ab_address_book),
                        tint = KaspaTeal,
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .clickable(enabled = enabled, role = Role.Button) { showAddressBook = true },
                    )
                }
            }
            // Who the coins are going to - the card Create chat shows (iOS ac0ef19): the domain
            // typed, else the address's own .kachat name (iOS 6ac48a7).
            AddressResolutionCard(address = resolvedAddress ?: trimmed.takeIf { isValidAddress }, domain = resolvedName)
            // The name it has in your Address Book (iOS 00767a4).
            // Only a name you gave it - an entry without one shows nothing here (iOS 2831fab).
            val savedName = remember(addressBook, resolvedAddress, trimmed) {
                AddressBookManager.shared?.entry(resolvedAddress ?: trimmed)?.customName
            }
            if (savedName != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.Book, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(14.dp))
                    Text(savedName, color = KaspaTeal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (trimmed.isNotEmpty()) {
                SendRecipientStatusLine(
                    input = trimmed,
                    isResolving = isResolving,
                    resolvedAddress = resolvedAddress,
                    resolvedName = resolvedName,
                    lookupError = lookupError,
                    isValidAddress = isValidAddress,
                )
                if (!isResolving) {
                    OtherDomainsDropdown(nameResolutions, selectedTld, onSelectResolution)
                }
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
    /** False leaves Max out (asking for an amount, not sending one: Request Payment, iOS c6851d6). */
    showsMax: Boolean = true,
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
    // Sized by what's shown, thousands commas included (iOS 40c5258).
    val shown = KaspaAmountGrouping.grouped(display)
    val fontSize = when {
        shown.length <= 7 -> 52.sp
        shown.length <= 10 -> 40.sp
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
                // Shown with thousands commas ("12,345.5"); what the screen gets stays plain (iOS 40c5258).
                onValueChange = { raw ->
                    fiatAmountState.onDisplayTextChange(sanitize(KaspaAmountGrouping.ungrouped(raw, previous = display)), priceInCurrency)
                },
                visualTransformation = KaspaAmountGrouping.visualTransformation,
                singleLine = true,
                textStyle = TextStyle(
                    color = colors.textPrimary,
                    fontSize = fontSize,
                    fontWeight = FontWeight.Bold,
                    fontFamily = IosRoundedFontFamily,
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
                            Text("0", color = colors.textTertiary, fontSize = fontSize, fontWeight = FontWeight.Bold, fontFamily = IosRoundedFontFamily)
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
                fontFamily = IosRoundedFontFamily,
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
            if (showsMax) {
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
}

/**
 * Thousands separators on an amount field while it's shown ("12,345.5", or "12 345,5" where "," is
 * the decimal point), the plain number kept as the field's value (iOS 40c5258 `KaspaAmountEntry.grouped` / `ungrouped`, `Binding.groupedAmount`).
 * Used by [KaspaAmountEntry], the .kachat offer and list price, Add to Portfolio and a portfolio
 * transaction's quantity: put [visualTransformation] on the field and pass what's typed through
 * [ungrouped] before it's stored. The separators are only drawn, so the value never contains them.
 */
object KaspaAmountGrouping {
    /**
     * Where the device writes the decimal point as "," (de, fr, es, it, pt, ru, tr, id, vi...), a
     * comma is ALWAYS the decimal point - "0,001" pasted from a chat is 0.001, never 1 (iOS 73af28a,
     * IOS-078) - and thousands show as a thin space ("2 500"), which no reader takes for a decimal.
     * Elsewhere thousands show as "," ("12,345.5").
     */
    fun usesCommaDecimal(locale: java.util.Locale = java.util.Locale.getDefault()): Boolean =
        java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator == ','

    fun groupingSeparator(locale: java.util.Locale = java.util.Locale.getDefault()): Char =
        if (usesCommaDecimal(locale)) '\u202F' else ','

    /** The decimal point as the device writes it: "," where [usesCommaDecimal], else ".". */
    fun decimalPoint(locale: java.util.Locale = java.util.Locale.getDefault()): Char =
        if (usesCommaDecimal(locale)) ',' else '.'

    /** "12345.5" -> "12,345.5", or "12 345,5" where "," is the decimal point: a separator every
     *  three digits of the whole part, the decimal point the device's own (so the person sees the
     *  "," they typed and doesn't type it again - iOS b383ff0, IOS-082), the rest as typed. */
    fun grouped(plain: String, locale: java.util.Locale = java.util.Locale.getDefault()): String {
        val dot = plain.indexOf('.')
        val whole = if (dot >= 0) plain.substring(0, dot) else plain
        if (!whole.all { it in '0'..'9' }) return plain
        val out = StringBuilder()
        if (whole.length > 3) {
            val separator = groupingSeparator(locale)
            for ((i, ch) in whole.withIndex()) {
                if (i > 0 && (whole.length - i) % 3 == 0) out.append(separator)
                out.append(ch)
            }
        } else {
            out.append(whole)
        }
        return if (dot >= 0) out.toString() + decimalPoint(locale) + plain.substring(dot + 1) else out.toString()
    }

    /**
     * The edited field back to a plain number ("." the decimal point). [edited] is the field's
     * value after the edit and [previous] its value before (both without the drawn separators),
     * so every comma in [edited] was typed or pasted.
     * - Comma-decimal device: the first "," is the decimal point and any further one is dropped,
     *   the value's own point (drawn as ",") counting as one - so a second tap on the key at the
     *   end never moves the point (iOS b383ff0, IOS-082), and a "," typed before the point becomes
     *   the point, as on iOS, Desktop and the extension (XP-027). A pasted "1.234,5" (dots as
     *   grouping, a comma decimal) is 1234.5. Pasted spaces go.
     * - Otherwise: a comma TYPED as the decimal point stays one: typed at the end, or a single
     *   comma not three digits from the end in a number that showed no grouping ("0,5" is 0.5),
     *   or after a lone leading zero ("0,001" is 0.001, as no grouped number starts "0,"). Any
     *   other comma goes.
     */
    fun ungrouped(edited: String, previous: String, locale: java.util.Locale = java.util.Locale.getDefault()): String {
        if (usesCommaDecimal(locale)) {
            var text = edited.replace("\u202F", "").replace(" ", "").replace("\u00A0", "")
            // The field keeps its point as "." but draws it as ",": that point is the "," the
            // reader sees, so it counts as one here - exactly the text iOS edits (XP-027).
            if (previous.contains('.') && text.count { it == '.' } == 1) text = text.replace('.', ',')
            val first = text.indexOf(',')
            if (first >= 0) {
                // a comma is the decimal point; dots beside it can only be grouping
                val head = text.substring(0, first).replace(".", "")
                val tail = text.substring(first + 1).replace(".", "").replace(",", "")
                text = "$head.$tail"
            }
            return text
        }
        if (edited == "$previous,") {
            return previous.replace(",", "") + "."
        }
        val comma = edited.indexOf(',')
        if (!grouped(previous, locale).contains(',') && !edited.contains('.') &&
            edited.count { it == ',' } == 1 &&
            (edited.substring(0, comma) == "0" || comma == 0 || edited.length - (comma + 1) != 3)
        ) {
            return edited.replace(',', '.')
        }
        return edited.replace(",", "")
    }

    /** Draws [grouped] over the field's plain text, the cursor kept on the same digit. */
    val visualTransformation: VisualTransformation = GroupedAmountTransformation
}

private object GroupedAmountTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val plain = text.text
        val shown = KaspaAmountGrouping.grouped(plain)
        val point = KaspaAmountGrouping.decimalPoint()
        if (shown == plain) return TransformedText(text, OffsetMapping.Identity)
        // Each plain character's position in the shown text; a cursor just before a digit that
        // has a comma in front of it sits after that comma.
        val toShown = IntArray(plain.length + 1)
        val toPlain = IntArray(shown.length + 1)
        var o = 0
        for (t in shown.indices) {
            toPlain[t] = o
            // Only the drawn commas are extra: grouped() inserts them, and a groupable whole part
            // has none of its own (the fraction is copied as is, so it lines up 1:1 again). The
            // plain "." is drawn as the device's own decimal point.
            if (o < plain.length && (shown[t] == plain[o] || (plain[o] == '.' && shown[t] == point))) {
                toShown[o] = t
                o++
            }
        }
        toPlain[shown.length] = plain.length
        toShown[plain.length] = shown.length
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = toShown[offset.coerceIn(0, plain.length)]
            override fun transformedToOriginal(offset: Int): Int = toPlain[offset.coerceIn(0, shown.length)]
        }
        return TransformedText(AnnotatedString(shown), mapping)
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
                    // Not focused on its own: as on iOS, the keyboard comes up when the field is
                    // tapped, not the moment it appears.
                    BasicTextField(
                        value = customFeeText,
                        onValueChange = onCustomFeeTextChange,
                        singleLine = true,
                        textStyle = TextStyle(color = colors.textPrimary, fontSize = 15.sp, textAlign = TextAlign.End),
                        cursorBrush = SolidColor(KaspaTeal),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onCommit() }),
                        modifier = Modifier.widthIn(max = 110.dp),
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

// MARK: - From (which of your addresses a send comes out of)

/** A spending address as the Send screens name it: its nickname, else "Address #N", numbered on
 *  its own chain (iOS `SpendingAddressEntry.displayLabel`, English like iOS). */
fun spendingAddressDisplayLabel(index: Int, label: String?): String =
    label?.trim()?.takeIf { it.isNotEmpty() } ?: com.kachat.app.services.WalletManager.spendingAddressTitle(index)

/**
 * Where a send comes from (iOS 6333dd3 `SendSource`): the chatting address (every send screen's
 * default), or one of the spending addresses picked under Choose an Address.
 */
sealed class SendSource {
    object Chatting : SendSource()
    data class Spending(val index: Int, val address: String, val label: String) : SendSource()

    /** The spending index the send signs with, or null for the chatting address. */
    val spendingIndex: Int? get() = (this as? Spending)?.index

    /** The address the Kaspa leaves from - [chattingAddress] for the chatting address. */
    fun address(chattingAddress: String?): String? = when (this) {
        Chatting -> chattingAddress
        is Spending -> address
    }
}

/**
 * A pill to pick one of a few choices, like the amount's currency switch: the chosen one filled
 * in teal (iOS 6333dd3 `SelectablePill`). Send screens' From, Request Payment's address.
 */
@Composable
fun SelectablePill(
    title: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    val haptic = com.kachat.app.util.rememberHaptics()
    val shape = RoundedCornerShape(50)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(shape)
            .then(
                if (selected) Modifier.background(KaspaTeal)
                else Modifier.background(colors.surface).border(1.dp, KaspaTeal.copy(alpha = 0.35f), shape)
            )
            .clickable(role = Role.Tab) {
                haptic(com.kachat.app.util.IosHaptic.IMPACT_LIGHT)
                onClick()
            }
            .padding(vertical = 10.dp, horizontal = 6.dp),
    ) {
        Text(
            title,
            color = if (selected) Color.Black else colors.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The From section every send screen starts with (iOS 6333dd3 `SendFromSection`), so it is
 * always plain which address the Kaspa leaves from: Chatting Address (the default) or Choose an
 * Address (any of your addresses, each marked used or unused), and the chosen address with its
 * balance. The balance is loaded here ([onAvailableSompiChange], null while it loads) - the
 * screen's Available.
 */
@Composable
fun SendFromSection(
    source: SendSource,
    onSourceChange: (SendSource) -> Unit,
    availableSompi: Long?,
    onAvailableSompiChange: (Long?) -> Unit,
    walletViewModel: WalletViewModel,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Opened from one address's own page (its history's Send, iOS 7a8102b): that address only,
     *  no Chatting Address / Choose an Address choice. */
    isLocked: Boolean = false,
) {
    val colors = LocalAppColors.current
    val chattingAddress by walletViewModel.address.collectAsState()
    var showPicker by remember { mutableStateOf(false) }
    val sourceAddress = source.address(chattingAddress)
    val currentOnAvailable by rememberUpdatedState(onAvailableSompiChange)
    LaunchedEffect(source, chattingAddress) {
        currentOnAvailable(null)
        val address = sourceAddress ?: return@LaunchedEffect
        currentOnAvailable(walletViewModel.addressBalanceSompi(address))
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.from).uppercase(),
            color = colors.textSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (!isLocked) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SelectablePill(
                    title = stringResource(R.string.chatting_address),
                    selected = source is SendSource.Chatting,
                    modifier = Modifier.weight(1f),
                ) { if (enabled) onSourceChange(SendSource.Chatting) }
                SelectablePill(
                    title = stringResource(R.string.pr_choose_an_address),
                    selected = source !is SendSource.Chatting,
                    modifier = Modifier.weight(1f),
                ) { if (enabled) showPicker = true }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .sendKaspaGlass(14.dp)
                .padding(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    when (source) {
                        SendSource.Chatting -> stringResource(R.string.chatting_address)
                        is SendSource.Spending -> source.label
                    },
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sourceAddress != null) {
                    Text(
                        "${sourceAddress.take(14)}...${sourceAddress.takeLast(6)}",
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
            }
            if (availableSompi != null) {
                Text(
                    "${KaspaUnit.plain(availableSompi)} ${KaspaUnit.symbol}",
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                )
            } else {
                com.kachat.app.ui.theme.IosActivityIndicator(modifier = Modifier.size(18.dp), color = colors.textSecondary, strokeWidth = 2.dp)
            }
        }
    }

    if (showPicker) {
        OwnAddressPicker(
            title = stringResource(R.string.send_from),
            selected = sourceAddress,
            walletViewModel = walletViewModel,
            onPick = onSourceChange,
            onDismiss = { showPicker = false },
        )
    }
}

/**
 * Your addresses to pick from (iOS 6333dd3 `OwnAddressPicker`) - the chatting address, then the
 * spending addresses (the ones Manage Addresses shows, plus hidden ones holding Kaspa) - each with
 * its name, balance and whether it was ever used: a balance means used, else the cached answer,
 * else a history lookup, eight at a time; "Checking" until answered, never a possibly-wrong
 * "Unused". A send's Choose an Address, and Request Payment's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OwnAddressPicker(
    title: String,
    selected: String?,
    walletViewModel: WalletViewModel,
    onPick: (SendSource) -> Unit,
    onDismiss: () -> Unit,
    /** The .kachat market signs its funding inputs as plain P2PK, which a quantum-safe (KIP-22)
     *  address can't be - its picker leaves them out (iOS `excludeQuantum`). */
    excludeQuantum: Boolean = false,
) {
    val halfScreen = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp / 2).dp
    val chattingAddress by walletViewModel.address.collectAsState()
    val chattingBalance by walletViewModel.balanceSompi.collectAsState()
    val allEntries by walletViewModel.manageAddresses.collectAsState()
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        walletViewModel.loadManageAddressesAndAwait()
        loaded = true
    }
    val entries = remember(allEntries, excludeQuantum) {
        allEntries.filter { !it.hidden || it.balanceSompi > 0 }
            .filter { !excludeQuantum || !it.isQuantum }
            .sortedBy { it.index }
    }
    /** Used / unused per address; missing while its lookup is out (or failed). */
    val used = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val items = buildList {
        chattingAddress?.let { add(it to chattingBalance) }
        entries.forEach { add(it.address to it.balanceSompi) }
    }
    LaunchedEffect(items) {
        val toProbe = mutableListOf<String>()
        for ((address, balance) in items) {
            if (used[address] == true) continue
            if (balance > 0L) {
                used[address] = true
                continue
            }
            val cached = walletViewModel.cachedSpendingAddressUsedState(address)
            if (cached != null) used[address] = cached else toProbe.add(address)
        }
        // a few at a time, like Manage Addresses (a burst gets rate-limited)
        val gate = kotlinx.coroutines.sync.Semaphore(8)
        kotlinx.coroutines.coroutineScope {
            toProbe.forEach { address ->
                launch {
                    gate.acquire()
                    try {
                        // a failed lookup leaves it Checking, never a guessed Unused
                        walletViewModel.spendingAddressUsedState(address)?.let { used[address] = it }
                    } finally {
                        gate.release()
                    }
                }
            }
        }
    }

    val pick: (SendSource) -> Unit = { source ->
        onPick(source)
        onDismiss()
    }

    com.kachat.app.ui.theme.IosSheetColors(grouped = true) {
        val colors = LocalAppColors.current
        // Floats on its height detent, as iOS 26's does, settling onto the edges as it grows.
        var sheetFloat by remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
        ModalBottomSheet(
            scrimColor = com.kachat.app.ui.theme.IosSheetScrim,
            modifier = Modifier.iosFloatingSheet(sheetFloat),
            shape = iosFloatingSheetShape(sheetFloat),
            tonalElevation = com.kachat.app.ui.theme.IosSheetTonalElevation,
            windowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars,
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.background,
            dragHandle = null,
        ) {
            Column(Modifier.navigationBarsPadding()) {
                IosSheetDetents(height = halfScreen, largeDetent = true, onFloat = { sheetFloat = it }) {
                    Column(Modifier.fillMaxWidth().fillMaxHeight()) {
                        SheetNavBar(
                            title = title,
                            leading = { SheetBarButton(stringResource(R.string.cancel), onClick = onDismiss) },
                        )
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight()
                                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val chatting = chattingAddress
                            if (chatting != null) {
                                OwnAddressPickerSectionHeader(stringResource(R.string.chatting_address))
                                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(colors.surface)) {
                                    OwnAddressPickerRow(
                                        title = stringResource(R.string.chatting_address),
                                        address = chatting,
                                        balance = chattingBalance,
                                        used = used[chatting],
                                        isSelected = chatting == selected,
                                        onClick = { pick(SendSource.Chatting) },
                                    )
                                }
                                Box(Modifier.size(12.dp))
                            }
                            OwnAddressPickerSectionHeader(stringResource(R.string.pr_spending_addresses))
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(colors.surface)) {
                                if (entries.isEmpty() && !loaded) {
                                    Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                                        com.kachat.app.ui.theme.IosActivityIndicator(color = colors.textSecondary)
                                    }
                                } else {
                                    entries.forEachIndexed { i, entry ->
                                        if (i > 0) IosListRowDivider()
                                        val label = spendingAddressDisplayLabel(entry.index, entry.label)
                                        OwnAddressPickerRow(
                                            title = label,
                                            address = entry.address,
                                            balance = entry.balanceSompi,
                                            used = used[entry.address],
                                            isSelected = entry.address == selected,
                                            onClick = { pick(SendSource.Spending(entry.index, entry.address, label)) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    IosSheetGrabber(Modifier.align(Alignment.TopCenter))
                }
            }
        }
    }
}

@Composable
private fun OwnAddressPickerSectionHeader(title: String) {
    Text(
        title.uppercase(),
        color = LocalAppColors.current.textSecondary,
        fontSize = 13.sp,
        modifier = Modifier.padding(start = 16.dp, top = 4.dp),
    )
}

@Composable
private fun OwnAddressPickerRow(
    title: String,
    address: String,
    balance: Long?,
    used: Boolean?,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    title,
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                OwnAddressUsedTag(used)
            }
            Text(
                if (address.length > 20) "${address.take(14)}...${address.takeLast(6)}" else address,
                color = colors.textSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
        if (balance != null) {
            Text(
                "${KaspaUnit.plain(balance)} ${KaspaUnit.symbol}",
                color = if (balance > 0L) colors.textPrimary else colors.textSecondary,
                fontSize = 15.sp,
                maxLines = 1,
                style = TextStyle(fontFeatureSettings = "tnum"),
            )
        }
        if (isSelected) {
            Icon(Icons.Default.Check, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
        }
    }
}

/** Used / Unused, or Checking while the lookup is out (iOS `usedTag`, 338d9f5). */
@Composable
private fun OwnAddressUsedTag(used: Boolean?) {
    val colors = LocalAppColors.current
    when (used) {
        true -> Text(
            stringResource(R.string.pr_used),
            color = colors.textSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(colors.textSecondary.copy(alpha = 0.15f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        false -> Text(
            stringResource(R.string.pr_unused),
            color = colors.success,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(colors.success.copy(alpha = 0.15f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        null -> Text(
            stringResource(R.string.pr_checking),
            color = colors.textSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
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
