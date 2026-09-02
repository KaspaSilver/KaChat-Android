package com.kachat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.repository.ChatRepository
import com.kachat.app.services.WalletService
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.WalletViewModel

/**
 * Where a ChangeNOW swap should pay its KAS out. Mirrors iOS's `SwapAddressPickerView`.
 *
 * This used to be Manage Addresses, which is a management screen: it offers rename, hide, QR,
 * consolidate, activate - none of which are the question being asked here, and one wrong tap
 * there changes your primary address. This asks one question and offers Generate for when the
 * answer is "none of these".
 *
 * Only UNUSED addresses are offered. A swap payout landing on an address you have already used
 * ties the payout to everything that address has done before, which is exactly what a fresh
 * address avoids. Each row still says used or unused: the used flag comes from a network check
 * that can fail, and an unlabelled row would be a claim this screen cannot always stand behind.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwapAddressPickerScreen(
    viewModel: WalletViewModel,
    onBack: () -> Unit,
    onAddressPicked: (WalletService.SpendingAddressEntry) -> Unit,
) {
    val colors = LocalAppColors.current
    val addresses by viewModel.manageAddresses.collectAsState()
    val loading by viewModel.manageAddressesLoading.collectAsState()
    val explorer by viewModel.kaspaExplorer.collectAsState()
    val uriHandler = LocalUriHandler.current

    var isGenerating by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<WalletService.SpendingAddressEntry?>(null) }

    LaunchedEffect(Unit) { viewModel.loadManageAddresses() }

    val selectable = remember(addresses) { addresses.filter { !it.hidden && !it.everUsed } }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Choose Address", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background),
            )
        },
    ) { padding ->
        if (loading && addresses.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = KaspaTeal)
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Generate sits at the top, before the list - it is the answer whenever nothing
            // below is free, which on a well-used wallet is most of the time.
            item {
                Spacer(Modifier.height(8.dp))
                ActionSheetRow(
                    icon = Icons.Default.AddCircleOutline,
                    title = "Generate New Address",
                    subtitle = "Reveals the next unused address in this wallet.",
                ) {
                    if (!isGenerating) {
                        isGenerating = true
                        viewModel.generateNewSpendingAddress { isGenerating = false }
                    }
                }
                if (isGenerating) {
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = KaspaTeal, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "A swap pays out to an address you have not used before, so the payout cannot " +
                        "be tied to your earlier activity. Generate one if none are free.",
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "UNUSED ADDRESSES",
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (selectable.isEmpty()) {
                item {
                    Text(
                        "Every address here has been used. Generate a new one above.",
                        color = colors.textSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    )
                }
            }

            items(selectable, key = { it.index }) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        // Tapping asks what to do with the address rather than committing
                        // outright - iOS hid the explorer behind an ellipsis nobody would find.
                        .clickable { actionTarget = entry }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                entry.label?.takeIf { it.isNotBlank() } ?: "Address #${entry.index}",
                                color = colors.textSecondary,
                                fontSize = 11.sp,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (entry.everUsed) "Used" else "Unused",
                                color = if (entry.everUsed) androidx.compose.ui.graphics.Color(0xFFFFA000) else KaspaTeal,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text(
                            entry.address.take(14) + "..." + entry.address.takeLast(6),
                            color = colors.textPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        "${ChatRepository.formatKas(entry.balanceSompi)} KAS",
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    actionTarget?.let { entry ->
        ActionSheetContainer(
            title = entry.label?.takeIf { it.isNotBlank() } ?: "Address #${entry.index}",
            subtitle = entry.address.take(14) + "..." + entry.address.takeLast(6),
            onDismiss = { actionTarget = null },
        ) {
            ActionSheetRow(
                icon = Icons.Default.Public,
                title = "View in Explorer",
                subtitle = "Check its history before you send a swap to it.",
            ) {
                actionTarget = null
                uriHandler.openUri(explorer.addressUrl(entry.address))
            }
            ActionSheetRow(
                icon = Icons.Default.ArrowDownward,
                title = "Use for This Swap",
                subtitle = "ChangeNOW pays the swapped KAS out to this address.",
            ) {
                actionTarget = null
                onAddressPicked(entry)
            }
        }
    }
}
