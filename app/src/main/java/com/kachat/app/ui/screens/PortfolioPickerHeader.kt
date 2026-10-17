package com.kachat.app.ui.screens

import androidx.compose.foundation.layout.statusBars

import androidx.compose.foundation.layout.navigationBarsPadding

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.offset
import com.kachat.app.models.PortfolioEntity
import com.kachat.app.services.PortfolioManager
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.formatFiatAmount
import com.kachat.app.viewmodels.PortfolioCardData
import java.util.Locale

/**
 * Robinhood-style portfolio switcher: a horizontally-scrollable row of always-visible cards
 * (name, total balance, today's % change), one per portfolio. Also owns the add/rename/delete UI
 * for the up-to-5 portfolio list — small enough to not need a separate management screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PortfolioPickerHeader(
    portfolios: List<PortfolioEntity>,
    activePortfolioId: String?,
    cardSummaries: Map<String, PortfolioCardData>,
    currencyCode: String,
    onSelect: (String) -> Unit,
    onAdd: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    /** Commits a reorder; the argument is the full list of ids in their new order. */
    onReorder: (List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddDialog by remember { mutableStateOf(false) }
    // The card whose long-press sheet is open. Everything that sheet offers - rename, reorder,
    // delete - happens inside it, so this is the only presentation the long press starts, and it
    // is built FROM the pressed card so it can never act on a different one.
    var sheetTarget by remember { mutableStateOf<PortfolioEntity?>(null) }

    val canAddMore = portfolios.size < PortfolioManager.MAX_PORTFOLIOS

    // iOS: a horizontal ScrollView (HStack(spacing: 12), padded 16 x 8) in a row of the
    // inset-grouped List, whose section margin keeps it - and clips it - 16 in from each edge.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        portfolios.forEach { portfolio ->
            PortfolioCard(
                portfolio = portfolio,
                isActive = portfolio.id == activePortfolioId,
                // Stays lifted while ITS sheet is open, the way a home-screen icon stays raised
                // under its menu - the sheet only covers the lower half, so the card it belongs
                // to is still on screen and worth identifying.
                isSheetTarget = sheetTarget?.id == portfolio.id,
                cardData = cardSummaries[portfolio.id],
                currencyCode = currencyCode,
                onClick = { onSelect(portfolio.id) },
                onEdit = { sheetTarget = portfolio }
            )
        }
        if (canAddMore) {
            AddPortfolioCard(onClick = { showAddDialog = true })
        }
    }

    if (showAddDialog) {
        PortfolioNameDialog(
            title = "New Portfolio",
            initialText = "",
            confirmLabel = "Create",
            onConfirm = { onAdd(it); showAddDialog = false },
            onDismiss = { showAddDialog = false }
        )
    }

    sheetTarget?.let { target ->
        PortfolioActionsSheet(
            target = target,
            portfolios = portfolios,
            cardSummaries = cardSummaries,
            currencyCode = currencyCode,
            onRename = onRename,
            onDelete = onDelete,
            onReorder = onReorder,
            onDismiss = { sheetTarget = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PortfolioCard(
    portfolio: PortfolioEntity,
    isActive: Boolean,
    isSheetTarget: Boolean,
    cardData: PortfolioCardData?,
    currencyCode: String,
    onClick: () -> Unit,
    onEdit: () -> Unit
) {
    val isPositive = (cardData?.todayChangeAmount ?: 0.0) >= 0.0
    // Lifts while ITS sheet is open - the sheet covers only the lower half, so the card it belongs
    // to is still on screen and worth identifying.
    val scale by animateFloatAsState(
        targetValue = if (isSheetTarget) 1.04f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "portfolioCardScale"
    )

    // iOS PortfolioCardView: VStack(alignment: .leading, spacing: 6) padded 12 in a 140-wide
    // card - the name (subheadline bold) with the caption gearshape.fill on the right in a 26pt
    // target (pulled 6 right and 4 up so the name sits where it would without it), the value
    // (title3 bold), and today's change (caption2 arrow, caption semibold) or a dash.
    Column(
        modifier = Modifier
            .width(140.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(16.dp))
            .background(LocalAppColors.current.surface)
            .border(
                width = if (isSheetTarget) 2.dp else if (isActive) 1.5.dp else 0.8.dp,
                color = if (isSheetTarget || isActive) KaspaTeal else Color.White.copy(alpha = 0.15f),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // The gear shares the title row rather than floating over it, so a long portfolio
        // name is truncated by the layout instead of running underneath the button.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    // iOS .padding(.trailing, -6).padding(.top, -4).
                    val end = 6.dp.roundToPx()
                    val top = 4.dp.roundToPx()
                    val placeable = measurable.measure(constraints.offset(horizontal = end))
                    layout((placeable.width - end).coerceAtLeast(0), (placeable.height - top).coerceAtLeast(0)) {
                        placeable.place(0, -top)
                    }
                }
        ) {
            Text(
                portfolio.name,
                color = if (isActive) LocalAppColors.current.textPrimary else LocalAppColors.current.textSecondary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    // A deliberately generous target: the glyph alone is far below the
                    // comfortable minimum on a card this size.
                    .size(26.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .clickable { onEdit() }
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Edit ${portfolio.name}",
                    tint = LocalAppColors.current.textSecondary,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
        Text(
            formatFiatAmount(cardData?.currentValue ?: 0.0, currencyCode),
            color = LocalAppColors.current.textPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (cardData?.todayChangePercent != null) {
            val changeColor = if (isPositive) LocalAppColors.current.success else LocalAppColors.current.danger
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Icon(
                    if (isPositive) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                    contentDescription = null,
                    tint = changeColor,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    "${String.format(Locale.US, "%.2f", kotlin.math.abs(cardData.todayChangePercent))}%",
                    color = changeColor,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
            }
        } else {
            Text("—", color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
        }
    }
}

/**
 * iOS addCard: an 80 x 84 tile outlined by a dashed 1pt line (dashes of 4, secondary at 40%,
 * 16pt corners), holding the accent plus.circle.fill (title2) over "Add" (caption, secondary).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AddPortfolioCard(onClick: () -> Unit) {
    val dashColor = LocalAppColors.current.textSecondary.copy(alpha = 0.4f)
    Column(
        modifier = Modifier
            .width(80.dp)
            .height(84.dp)
            .drawBehind {
                // strokeBorder: the line drawn inside the tile's edge.
                val stroke = 1.dp.toPx()
                val dash = 4.dp.toPx()
                drawRoundRect(
                    color = dashColor,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(16.dp.toPx() - stroke / 2f),
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)))
                )
            }
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
    ) {
        Icon(Icons.Default.AddCircle, contentDescription = "Add Portfolio", tint = KaspaTeal, modifier = Modifier.size(26.dp))
        Text("Add", color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun PortfolioNameDialog(
    title: String,
    initialText: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialText) }
    com.kachat.app.ui.theme.IosAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LocalAppColors.current.surface,
        title = { Text(title, color = LocalAppColors.current.textPrimary) },
        text = {
            com.kachat.app.ui.theme.IosTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Portfolio Name") },
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
            TextButton(onClick = { onConfirm(text) }, enabled = text.trim().isNotEmpty()) {
                Text(confirmLabel, color = KaspaTeal, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = LocalAppColors.current.textSecondary)
            }
        }
    )
}

/**
 * Everything a long press on a portfolio card offers, in one half-height sheet.
 *
 * Rename, reorder and delete all happen HERE rather than each opening its own dialog. That is
 * partly the shape iOS uses and partly a correctness property: the sheet is built from the pressed
 * card, so every action inside it acts on the portfolio that was held.
 *
 * `skipPartiallyExpanded = false` is the half-height stop - the Compose equivalent of iOS's medium
 * detent. The sheet can still be dragged up to full height for the reorder list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortfolioActionsSheet(
    target: PortfolioEntity,
    portfolios: List<PortfolioEntity>,
    cardSummaries: Map<String, PortfolioCardData>,
    currencyCode: String,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalAppColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    var mode by remember { mutableStateOf(SheetMode.MENU) }
    var renameText by remember { mutableStateOf(target.name) }
    var reorderDraft by remember { mutableStateOf(portfolios) }

    // The last portfolio cannot be deleted - every wallet keeps at least one - and there is nothing
    // to reorder with only one card.
    val hasOthers = portfolios.size > 1

    com.kachat.app.ui.theme.IosSheetColors {
        val colors = LocalAppColors.current
        ModalBottomSheet(
            scrimColor = com.kachat.app.ui.theme.IosSheetScrim,
            shape = com.kachat.app.ui.theme.IosSheetShape,
            tonalElevation = com.kachat.app.ui.theme.IosSheetTonalElevation,
            windowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars,
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = colors.background
        ) {
            // The sheet runs down behind the navigation bar, as iOS's does behind the home indicator;
            // its content stays above it.
            Column(Modifier.navigationBarsPadding()) {
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                    SheetHeader(
                        title = when (mode) {
                            SheetMode.MENU -> target.name
                            SheetMode.RENAME -> "Rename"
                            SheetMode.REORDER -> "Reorder"
                            SheetMode.CONFIRM_DELETE -> "Delete Portfolio"
                        },
                        // From a sub-mode this steps back to the menu rather than closing outright, so a
                        // mis-tap costs one tap instead of the whole long press.
                        leadingLabel = if (mode == SheetMode.MENU) "Cancel" else "Back",
                        onLeading = { if (mode == SheetMode.MENU) onDismiss() else mode = SheetMode.MENU },
                        trailingLabel = when (mode) {
                            SheetMode.RENAME -> "Save"
                            SheetMode.REORDER -> "Done"
                            else -> null
                        },
                        trailingEnabled = mode != SheetMode.RENAME || renameText.trim().isNotEmpty(),
                        onTrailing = {
                            when (mode) {
                                SheetMode.RENAME -> onRename(target.id, renameText.trim())
                                SheetMode.REORDER -> onReorder(reorderDraft.map { it.id })
                                else -> Unit
                            }
                            onDismiss()
                        }
                    )

                    when (mode) {
                        SheetMode.MENU -> {
                            SheetActionRow("Rename", Icons.Default.Edit) {
                                renameText = target.name
                                mode = SheetMode.RENAME
                            }
                            if (hasOthers) {
                                SheetActionRow("Reorder Portfolios", Icons.Default.SwapVert) {
                                    reorderDraft = portfolios
                                    mode = SheetMode.REORDER
                                }
                                SheetActionRow("Delete '${target.name}'", Icons.Default.Delete, destructive = true) {
                                    mode = SheetMode.CONFIRM_DELETE
                                }
                            } else {
                                Text(
                                    "This is your only portfolio, so it can't be deleted or reordered.",
                                    color = colors.textSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                                )
                            }
                        }

                        SheetMode.RENAME -> {
                            com.kachat.app.ui.theme.IosTextField(
                                value = renameText,
                                onValueChange = { renameText = it },
                                label = { Text("Portfolio Name") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = colors.textPrimary,
                                    unfocusedTextColor = colors.textPrimary,
                                    focusedBorderColor = KaspaTeal,
                                    unfocusedBorderColor = colors.textSecondary,
                                    focusedLabelColor = KaspaTeal,
                                    unfocusedLabelColor = colors.textSecondary
                                ),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                            )
                            Text(
                                "Only the name changes. Transactions stay where they are.",
                                color = colors.textSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                            )
                        }

                        SheetMode.REORDER -> {
                            // Up/down controls rather than a drag: Compose has no equivalent of the
                            // native reorderable list iOS gets for free, and a hand-rolled drag was
                            // removed from the cards on iOS for being unreliable. With at most five
                            // portfolios these are quicker anyway, and they work with TalkBack.
                            reorderDraft.forEachIndexed { index, portfolio ->
                                ReorderRow(
                                    name = portfolio.name,
                                    value = formatFiatAmount(
                                        cardSummaries[portfolio.id]?.currentValue ?: 0.0,
                                        currencyCode
                                    ),
                                    isTarget = portfolio.id == target.id,
                                    canMoveUp = index > 0,
                                    canMoveDown = index < reorderDraft.lastIndex,
                                    onMoveUp = { reorderDraft = reorderDraft.swapped(index, index - 1) },
                                    onMoveDown = { reorderDraft = reorderDraft.swapped(index, index + 1) }
                                )
                            }
                            Text(
                                "Move a portfolio to change the order its card appears in.",
                                color = colors.textSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                            )
                        }

                        SheetMode.CONFIRM_DELETE -> {
                            SheetActionRow("Delete '${target.name}'", Icons.Default.Delete, destructive = true) {
                                onDelete(target.id)
                                onDismiss()
                            }
                            Text(
                                "'${target.name}' and its transactions will be deleted. This can't be undone.",
                                color = colors.textSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class SheetMode { MENU, RENAME, REORDER, CONFIRM_DELETE }

private fun <T> List<T>.swapped(a: Int, b: Int): List<T> =
    toMutableList().also { it[a] = this[b]; it[b] = this[a] }

@Composable
private fun SheetHeader(
    title: String,
    leadingLabel: String,
    onLeading: () -> Unit,
    trailingLabel: String?,
    trailingEnabled: Boolean,
    onTrailing: () -> Unit
) {
    // iOS 26's sheet bar: the title in headline, both buttons on glass capsules 16 in.
    SheetNavBar(
        title = title,
        leading = { SheetBarButton(leadingLabel, onClick = onLeading) },
        trailing = {
            if (trailingLabel != null) {
                SheetBarButton(trailingLabel, onClick = onTrailing, enabled = trailingEnabled)
            }
        },
    )
}

@Composable
private fun SheetActionRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val colors = LocalAppColors.current
    val tint = if (destructive) LocalAppColors.current.danger else colors.textPrimary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ReorderRow(
    name: String,
    value: String,
    isTarget: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            name,
            color = colors.textPrimary,
            fontWeight = if (isTarget) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            color = colors.textSecondary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(
                Icons.Default.ArrowUpward,
                contentDescription = "Move $name up",
                tint = if (canMoveUp) KaspaTeal else colors.textSecondary.copy(alpha = 0.4f)
            )
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(
                Icons.Default.ArrowDownward,
                contentDescription = "Move $name down",
                tint = if (canMoveDown) KaspaTeal else colors.textSecondary.copy(alpha = 0.4f)
            )
        }
    }
}
