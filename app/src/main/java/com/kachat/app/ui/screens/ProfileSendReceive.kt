package com.kachat.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.outlined.ArrowCircleUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.WalletViewModel
import kotlinx.coroutines.launch

/** What Profile's Send and Receive sheet opens (iOS 7abc06e `ProfileView.WalletAction`). */
internal enum class ProfileWalletAction { SEND, RECEIVE, REQUEST }

/**
 * Profile's floating up/down-arrow button and everything it opens (iOS 7abc06e): a half sheet of
 * tiles - Send Kaspa, Receive Kaspa, Request Payment, which left the Chats + menu for it - and,
 * once that sheet is down, the screen the tapped tile names.
 */
@Composable
internal fun ProfileSendReceiveButton(walletViewModel: WalletViewModel, modifier: Modifier = Modifier) {
    val haptic = com.kachat.app.util.rememberHaptics()
    var showSheet by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf<ProfileWalletAction?>(null) }
    // Receive Kaspa's fresh address: decided as the sheet opens (the check is a network round
    // trip) and re-confirmed when the QR page comes up (iOS 24c2094).
    var receiveAddress by remember { mutableStateOf<String?>(null) }
    var receiveResolving by remember { mutableStateOf(true) }
    LaunchedEffect(showSheet, action) {
        if (showSheet || action == ProfileWalletAction.RECEIVE) {
            if (receiveAddress == null) receiveResolving = true
            // Revealed (on Manage Addresses, watched) only once the Receive QR is shown; the sheet
            // opening only warms the pick.
            walletViewModel.resolveFreshReceiveAddress(revealing = action == ProfileWalletAction.RECEIVE) { address ->
                if (address != null) receiveAddress = address
                receiveResolving = false
            }
        }
    }

    com.kachat.app.ui.theme.IosGlassFab(
        modifier = modifier,
        onClick = {
            haptic(com.kachat.app.util.IosHaptic.IMPACT_LIGHT)
            showSheet = true
        },
        icon = SendReceiveArrowsIcon,
        contentDescription = stringResource(R.string.send_and_receive),
    )

    if (showSheet) {
        SendReceiveSheet(
            onDismiss = { showSheet = false },
            onAction = { action = it },
        )
    }
    when (action) {
        ProfileWalletAction.SEND -> ChatsSpendingSendLauncher(walletViewModel = walletViewModel, onDone = { action = null })
        ProfileWalletAction.RECEIVE -> QrPageSheet(onDismiss = { action = null }, white = receiveAddress != null) {
            ReceiveKaspaQrPage(
                address = receiveAddress,
                resolving = receiveResolving,
                dismiss = QrPageDismiss.DONE,
                onDismiss = { action = null },
                walletViewModel = walletViewModel,
                title = stringResource(R.string.receive_kaspa),
            )
        }
        ProfileWalletAction.REQUEST -> CreatePaymentRequestSheet(
            walletViewModel = walletViewModel,
            onDismissed = { action = null },
        )
        null -> Unit
    }
}

/** The button's half sheet: the three wallet actions as tiles, like the Chats New sheet. A tile
 *  closes the sheet first and then opens its screen (a sheet can't come up while another is
 *  still going down). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendReceiveSheet(onDismiss: () -> Unit, onAction: (ProfileWalletAction) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun close(then: ProfileWalletAction) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            onAction(then)
        }
    }
    com.kachat.app.ui.theme.IosSheetColors {
        val colors = LocalAppColors.current
        ModalBottomSheet(
            scrimColor = com.kachat.app.ui.theme.IosSheetScrim,
            modifier = Modifier.iosFloatingSheet(),
            shape = IosFloatingSheetShape,
            tonalElevation = com.kachat.app.ui.theme.IosSheetTonalElevation,
            windowInsets = WindowInsets.statusBars,
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = colors.background,
            dragHandle = null,
        ) {
            Column(Modifier.navigationBarsPadding()) {
                Box {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ChatsNewSheetHeight)
                            .padding(horizontal = 20.dp)
                            .padding(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.send_and_receive),
                            color = colors.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp,
                            modifier = Modifier.padding(top = 20.dp),
                        )
                        BoxWithConstraints(contentAlignment = Alignment.Center) {
                            val tileSize = minOf(ChatsNewTileSize, (maxWidth - ChatsNewTileSpacing * 2) / 3)
                            Row(horizontalArrangement = Arrangement.spacedBy(ChatsNewTileSpacing)) {
                                ChatsNewTile(
                                    size = tileSize,
                                    icon = Icons.Outlined.ArrowCircleUp,
                                    title = stringResource(R.string.chats_send_kaspa),
                                    hint = stringResource(R.string.chats_send_kaspa_hint),
                                ) { close(ProfileWalletAction.SEND) }
                                ChatsNewTile(
                                    size = tileSize,
                                    icon = Icons.Outlined.ArrowCircleDown,
                                    title = stringResource(R.string.receive_kaspa),
                                    hint = stringResource(R.string.chats_receive_subtitle),
                                ) { close(ProfileWalletAction.RECEIVE) }
                                // A link asking for an exact amount (iOS c6851d6).
                                ChatsNewTile(
                                    size = tileSize,
                                    icon = RequestPaymentIcon,
                                    title = stringResource(R.string.pr_request_payment),
                                    hint = stringResource(R.string.pr_request_payment_hint),
                                ) { close(ProfileWalletAction.REQUEST) }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                    }
                    IosSheetGrabber(Modifier.align(Alignment.TopCenter))
                }
            }
        }
    }
}

/** iOS's arrow.up.arrow.down: an up arrow and a down arrow side by side. */
private val SendReceiveArrowsIcon = Icons.Filled.SwapVert
