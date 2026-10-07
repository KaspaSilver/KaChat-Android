package com.kachat.app.ui.screens

import androidx.compose.foundation.layout.statusBars

import androidx.compose.foundation.layout.navigationBarsPadding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.kachat.app.R
import com.kachat.app.services.GlobalNotificationCenterStore
import com.kachat.app.ui.Screen
import com.kachat.app.ui.resolveDock
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.WalletViewModel
import kotlinx.coroutines.launch

/**
 * The Profile bell's sheet (iOS `GlobalNotificationListView`): a full-height sheet with Done on the
 * left, "Notifications" in the middle and Clear All on the right, then the newest-first feed - each
 * row its source's icon, the title, up to two lines of body and the source with how long ago - or,
 * with nothing in it, the bell and "No notifications yet". Opening it marks everything seen (clears
 * the bell's dot), as iOS's onAppear does.
 *
 * Every row opens what it is about, the same way the matching notification's tap does: a wallet
 * receipt the wallet screen (Portfolio when it is in the dock, otherwise Profile - iOS
 * `.openPortfolio` / `routeToWalletTab(.portfolio)`; a receipt row carries no address), a .kachat
 * row the name. Rows of the sources an older build recorded (KaPosts, group mentions, public
 * chats) are dropped on load, but keep their routes as iOS does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalNotificationSheet(
    store: GlobalNotificationCenterStore,
    walletViewModel: WalletViewModel,
    navController: NavController,
    onDismiss: () -> Unit,
) {
    val entries by store.entries.collectAsState()
    val dockRoutes by walletViewModel.dockTabs.collectAsState()
    val hiddenTabs by walletViewModel.hiddenTabs.collectAsState()
    val childMode by walletViewModel.childModeEnabled.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { store.markAllSeen() }

    /** Closes the sheet (animated, like iOS's dismiss), then runs [then]. */
    fun dismiss(then: () -> Unit = {}) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            then()
        }
    }

    fun open(entry: GlobalNotificationCenterStore.Entry) {
        val target = entry.targetId.orEmpty()
        when (entry.source) {
            "kaposts" -> {
                if (childMode || target.isEmpty()) return
                dismiss {
                    KaPostsDeepLink.pendingOpenNotifications.value = false
                    KaPostsDeepLink.pendingPostTxId.value = target
                }
            }
            "group" -> {
                if (target.isBlank()) return
                dismiss { navController.navigate("group_chat/$target") }
            }
            "broadcast" -> {
                if (childMode) return
                val channel = KaChatLink.sanitizeChannelName(target) ?: return
                dismiss { navController.navigate("broadcast_channel/$channel") }
            }
            // Receipts carry no target of their own - the wallet screen is the subject.
            "wallet" -> dismiss {
                val inDock = resolveDock(dockRoutes, hiddenTabs, childMode).any { it == Screen.Portfolio }
                if (inDock) {
                    val popped = navController.popBackStack(route = Screen.Portfolio.route, inclusive = false, saveState = true)
                    if (!popped) {
                        navController.navigate(Screen.Portfolio.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
                // Otherwise Profile, which is where the bell already is.
            }
            // The name, the same way a tapped .kachat push opens it.
            com.kachat.app.services.kachatnames.KachatNamesNotifier.SOURCE -> {
                if (target.isBlank()) return
                dismiss {
                    KachatDeepLink.pendingName.value = com.kachat.app.services.kachatnames.KachatNames.Codec.normalize(target)
                }
            }
        }
    }

    com.kachat.app.ui.theme.IosSheetColors {
        val colors = LocalAppColors.current
        // A plain list: the sheet's own background (white in light mode, the raised sheet grey in dark).
        val background = colors.background
        ModalBottomSheet(
            shape = com.kachat.app.ui.theme.IosSheetShape,
            tonalElevation = com.kachat.app.ui.theme.IosSheetTonalElevation,
            windowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars,
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = background,
            dragHandle = null,
        ) {
            // The sheet runs down behind the navigation bar, as iOS's does behind the home indicator;
            // its content stays above it.
            Column(Modifier.navigationBarsPadding()) {
                Column(Modifier.fillMaxSize()) {
                    // The inline navigation bar: Done (cancellation, left), the title, Clear All (right).
                    Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp)) {
                        TextButton(onClick = { dismiss() }, modifier = Modifier.align(Alignment.CenterStart)) {
                            Text(stringResource(R.string.done), color = KaspaTeal, fontSize = 17.sp)
                        }
                        Text(
                            stringResource(R.string.notifications),
                            color = colors.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.Center),
                        )
                        TextButton(
                            onClick = { store.clearAll() },
                            enabled = entries.isNotEmpty(),
                            modifier = Modifier.align(Alignment.CenterEnd),
                        ) {
                            Text(
                                stringResource(R.string.notification_center_clear_all),
                                color = if (entries.isNotEmpty()) KaspaTeal else colors.textTertiary,
                                fontSize = 17.sp,
                            )
                        }
                    }
                    if (entries.isEmpty()) {
                        Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                        ) {
                            Icon(
                                Icons.Outlined.Notifications,
                                contentDescription = null,
                                tint = colors.textSecondary,
                                modifier = Modifier.size(44.dp),
                            )
                            Text(
                                stringResource(R.string.notification_center_empty_title),
                                color = colors.textPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 17.sp,
                            )
                            Text(
                                stringResource(R.string.notification_center_empty),
                                color = colors.textSecondary,
                                fontSize = 15.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 40.dp),
                            )
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(entries, key = { it.id }) { entry ->
                                GlobalNotificationRow(entry, onClick = { open(entry) })
                                // Plain-list separator: from where the text starts to the trailing edge.
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 50.dp),
                                    color = colors.divider,
                                    thickness = 0.5.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One row: the source's icon, then the title, the body (2 lines) and "source  time ago". */
@Composable
private fun GlobalNotificationRow(entry: GlobalNotificationCenterStore.Entry, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(24.dp).padding(top = 1.dp), contentAlignment = Alignment.Center) {
            Icon(sourceIcon(entry.source), contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(entry.title, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            // Broadcast rows (an older build's) carry the raw on-chain body, which is a JSON
            // envelope for anything but plain text.
            val body = remember(entry.id, entry.body, entry.source) {
                if (entry.source == "broadcast") broadcastCenterBody(entry.body) else entry.body
            }
            if (body.isNotEmpty()) {
                Text(body, color = colors.textSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(GlobalNotificationCenterStore.sourceLabel(entry.source), color = colors.textSecondary, fontSize = 11.sp)
                Text(
                    android.text.format.DateUtils.getRelativeTimeSpanString(entry.timestampMs).toString(),
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/**
 * A row's icon (iOS `Entry.Source.icon`): arrow.down.circle for a wallet receipt, "at" for .kachat;
 * megaphone, person.3 and dot.radiowaves.left.and.right for the sources older builds recorded.
 */
private fun sourceIcon(source: String): ImageVector = when (source) {
    "wallet" -> Icons.Outlined.ArrowCircleDown
    com.kachat.app.services.kachatnames.KachatNamesNotifier.SOURCE -> Icons.Default.AlternateEmail
    "kaposts" -> Icons.Outlined.Campaign
    "group" -> Icons.Outlined.Groups
    else -> Icons.Outlined.Sensors
}
