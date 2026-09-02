package com.kachat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.services.AddressActivityNotifier
import com.kachat.app.services.ColdStorageAddressDiscovery
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The frame every "..." menu in the app now uses: a half sheet with a title, a line of context,
 * and a stack of [ActionSheetRow]s.
 *
 * These were popup menus of bare labels, which have room for a verb and nothing else. A sheet has
 * room for each option to say what it does, and it keeps the thing being acted on on screen while
 * you choose. Mirrors iOS's `.sheet(item:)` menus.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheetContainer(
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    /** Extra line under the subtitle - the txid on the transaction menu, for instance. */
    detail: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalAppColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = colors.background,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                textAlign = TextAlign.Center,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!detail.isNullOrBlank()) {
                Text(
                    detail,
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            content()
        }
    }
}

/** One option in an [ActionSheetContainer]. Same shape as iOS's `ActionSheetRow`. */
@Composable
fun ActionSheetRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color = KaspaTeal,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(subtitle, color = colors.textSecondary, fontSize = 12.sp)
        }
    }
}

/**
 * What to do with a transaction you tapped in an address history: look at it, or record it.
 *
 * Shared by cold storage history, the spending-address history and the chatting address, all
 * three of which used to raise their own identical popup menu.
 */
@Composable
fun TransactionActionsSheet(
    tx: ColdStorageAddressDiscovery.AddressTransaction,
    onOpenExplorer: () -> Unit,
    onAddToPortfolio: () -> Unit,
    onDismiss: () -> Unit,
) {
    ActionSheetContainer(
        title = "Transaction",
        subtitle = summary(tx),
        detail = AddressActivityNotifier.shortAddress(tx.txId),
        onDismiss = onDismiss,
    ) {
        ActionSheetRow(
            icon = Icons.AutoMirrored.Filled.OpenInNew,
            title = "Open in Explorer",
            subtitle = "Opens this transaction on the block explorer.",
            onClick = onOpenExplorer,
        )
        ActionSheetRow(
            icon = Icons.Default.PieChart,
            title = "Add to Portfolio",
            subtitle = "Records it as a buy or a sell in a portfolio of your choosing.",
            onClick = onAddToPortfolio,
        )
    }
}

/** "Sent 12.5 KAS on 3 Sep 2026, 14:02" - what is about to be acted on, in one line. */
private fun summary(tx: ColdStorageAddressDiscovery.AddressTransaction): String {
    val direction = if (tx.sent) "Sent" else "Received"
    val amount = AddressActivityNotifier.formatKas(tx.amountSompi)
    val time = tx.blockTimeMillis?.let {
        SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(it))
    }
    return if (time == null) "$direction $amount KAS" else "$direction $amount KAS on $time"
}
