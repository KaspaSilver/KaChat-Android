package com.kachat.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.R
import com.kachat.app.services.WalletService
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.CHATTING_ADDRESS_SCAN_BATCH
import com.kachat.app.viewmodels.ChatViewModel
import com.kachat.app.viewmodels.WalletViewModel

/**
 * "Change Chatting Address" scanner, reachable from the Welcome Guide's funding step on IMPORT
 * onboarding runs only (never on a fresh create, never on a Help replay — see the gating in
 * [WelcomeGuideScreen]). Ported from iOS's `ChattingAddressPickerView`.
 *
 * Scans the identity derivation chain of the wallet's chosen source family (standard
 * m/44'/111111'/0'/0/{index}, or legacy 972 / OneKey, per the import chooser) in batches of
 * [CHATTING_ADDRESS_SCAN_BATCH], checking the whole batch for KAS balance (one batched balances
 * call) and KNS domains (the existing cached batch lookups) — never 50 raw requests. Only
 * interesting slots are listed: nonzero balance or at least one domain, plus always index 0 and
 * the current index. Tapping a row opens a detail screen with the full address, balance, its KNS
 * domain cards, and the prominent "Set as Chatting Address" action.
 */
@Composable
fun ChattingAddressPickerScreen(
    walletViewModel: WalletViewModel,
    onBack: () -> Unit,
    /** Called after the identity actually switched — the funding step re-renders with the new address. */
    onSwitched: () -> Unit
) {
    val scan by walletViewModel.chattingAddressScan.collectAsState()
    val currentIndex = remember(scan.candidates) { walletViewModel.currentChattingAddressIndex() }
    var selected by remember { mutableStateOf<WalletService.ChattingAddressCandidate?>(null) }

    // Existing conversations on the CURRENT identity: an imported seed's index-0 chats can sync
    // within seconds of import. Switching is still allowed (nothing is deleted), but the detail
    // screen confirms first instead of switching silently.
    val chatViewModel: ChatViewModel = hiltViewModel()
    val conversations by chatViewModel.conversations.collectAsState()
    val conversationsExist = conversations.isNotEmpty()

    LaunchedEffect(Unit) {
        if (scan.scannedCount == 0 && !scan.isScanning) walletViewModel.scanNextChattingAddressBatch()
    }

    selected?.let { candidate ->
        ChattingAddressDetailScreen(
            candidate = candidate,
            isCurrent = candidate.index == currentIndex,
            conversationsExist = conversationsExist,
            walletViewModel = walletViewModel,
            onBack = { selected = null },
            onSwitched = {
                selected = null
                onSwitched()
            }
        )
        return
    }

    val visible = scan.candidates.filter { it.isInteresting || it.index == 0 || it.index == currentIndex }

    Surface(color = LocalAppColors.current.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(40.dp)
                    .background(LocalAppColors.current.surface, CircleShape)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = LocalAppColors.current.textPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.height(16.dp))
            Icon(
                Icons.Default.PersonSearch,
                contentDescription = null,
                tint = KaspaTeal,
                modifier = Modifier
                    .size(48.dp)
                    .align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.choose_your_chatting_address),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = LocalAppColors.current.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.if_this_seed_already_holds_your),
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(20.dp))

            visible.forEach { candidate ->
                ChattingAddressRow(
                    candidate = candidate,
                    isCurrent = candidate.index == currentIndex,
                    onClick = { selected = candidate }
                )
                Spacer(Modifier.height(10.dp))
            }

            if (scan.failed) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.could_not_derive_addresses),
                    color = Color(0xFFFF3B30),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(12.dp))
            if (scan.isScanning) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(color = KaspaTeal, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(
                            R.string.scanning_addresses,
                            scan.scannedCount + 1,
                            scan.scannedCount + CHATTING_ADDRESS_SCAN_BATCH
                        ),
                        color = LocalAppColors.current.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else if (scan.scannedCount > 0) {
                Text(
                    stringResource(R.string.scanned_the_first_addresses, scan.scannedCount),
                    color = LocalAppColors.current.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { walletViewModel.scanNextChattingAddressBatch() },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Icon(Icons.Default.Search, null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.scan_further), color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ChattingAddressRow(
    candidate: WalletService.ChattingAddressCandidate,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = LocalAppColors.current.surface,
        shape = RoundedCornerShape(12.dp),
        border = if (isCurrent) BorderStroke(1.5.dp, KaspaTeal) else null,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "#${candidate.index}",
                color = KaspaTeal,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(44.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    shortAddress(candidate.address),
                    color = LocalAppColors.current.textPrimary,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${formatKas(candidate.balanceSompi)} KAS",
                        color = LocalAppColors.current.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (candidate.domains.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(KaspaTeal)
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                if (candidate.domains.size == 1) candidate.domains.first().asset ?: ""
                                else stringResource(R.string.domains_count, candidate.domains.size),
                                color = Color.Black,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
            if (isCurrent) {
                Text(
                    stringResource(R.string.current),
                    color = KaspaTeal,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            } else if (candidate.index == 0) {
                Text(
                    stringResource(R.string.default_label),
                    color = LocalAppColors.current.textSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = LocalAppColors.current.textSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Detail for one scanned identity slot: full address (tap to copy), balance, its KNS domain cards,
 * and the prominent "Set as Chatting Address" action. When the current identity already has synced
 * conversations the action confirms first — the user's intent is a fresh start with this seed, and
 * switching only parks the old address's history (it lives on-chain and in that address's own
 * storage scope); it deletes nothing.
 */
@Composable
private fun ChattingAddressDetailScreen(
    candidate: WalletService.ChattingAddressCandidate,
    isCurrent: Boolean,
    conversationsExist: Boolean,
    walletViewModel: WalletViewModel,
    onBack: () -> Unit,
    onSwitched: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var isSwitching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var showConfirmation by remember { mutableStateOf(false) }
    val canSet = !isCurrent && !isSwitching

    fun performSwitch() {
        if (!canSet) return
        isSwitching = true
        failed = false
        walletViewModel.switchChattingAddress(candidate.index) { ok ->
            isSwitching = false
            if (ok) onSwitched() else failed = true
        }
    }

    Surface(color = LocalAppColors.current.background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                IconButton(
                    onClick = onBack,
                    enabled = !isSwitching,
                    modifier = Modifier
                        .size(40.dp)
                        .background(LocalAppColors.current.surface, CircleShape)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = LocalAppColors.current.textPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.address_number, candidate.index),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = LocalAppColors.current.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = LocalAppColors.current.surface,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboard.setText(AnnotatedString(candidate.address))
                            Toast.makeText(context, context.getString(R.string.address_copied), Toast.LENGTH_SHORT).show()
                        }
                ) {
                    Text(
                        candidate.address,
                        color = LocalAppColors.current.textPrimary,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.tap_the_address_to_copy_it),
                    color = LocalAppColors.current.textSecondary,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))
                Surface(
                    color = LocalAppColors.current.surface,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            stringResource(R.string.balance),
                            color = LocalAppColors.current.textPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "${formatKas(candidate.balanceSompi)} KAS",
                            color = LocalAppColors.current.textSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                if (candidate.domains.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    Text(
                        stringResource(R.string.kns_domains_count, candidate.domains.size),
                        color = LocalAppColors.current.textPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(10.dp))
                    candidate.domains.forEach { domain ->
                        KnsDomainCard(
                            domain = domain,
                            isPrimary = candidate.primaryDomain != null &&
                                domain.asset?.equals(candidate.primaryDomain, ignoreCase = true) == true
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }

                if (failed) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.could_not_derive_addresses),
                        color = Color(0xFFFF3B30),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(24.dp))
            }

            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                Button(
                    onClick = { if (conversationsExist) showConfirmation = true else performSwitch() },
                    enabled = canSet,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = KaspaTeal,
                        disabledContainerColor = LocalAppColors.current.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isSwitching) {
                        CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        stringResource(if (isCurrent) R.string.current_chatting_address else R.string.set_as_chatting_address),
                        color = if (canSet) Color.Black else LocalAppColors.current.textSecondary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            containerColor = LocalAppColors.current.surface,
            title = {
                Text(stringResource(R.string.switch_chatting_address_question), color = LocalAppColors.current.textPrimary)
            },
            text = {
                Text(stringResource(R.string.this_seed_already_has_conversations), color = LocalAppColors.current.textSecondary)
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmation = false
                    performSwitch()
                }) {
                    Text(stringResource(R.string.switch_action), color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmation = false }) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }
}

private fun shortAddress(address: String): String =
    if (address.length > 16) "${address.take(10)}...${address.takeLast(6)}" else address

private fun formatKas(sompi: Long): String {
    var text = String.format(java.util.Locale.US, "%.8f", sompi / 100_000_000.0)
    while (text.endsWith("0")) text = text.dropLast(1)
    if (text.endsWith(".")) text = text.dropLast(1)
    return text.ifEmpty { "0" }
}
