package com.kachat.app.ui.screens

import android.app.Activity
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.R
import com.kachat.app.models.BackupRetention
import com.kachat.app.services.BackupRestoreCoordinator
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.ChatViewModel

/**
 * Settings > Storage sub-pages. The Storage entry itself is a hub of category rows (rendered by
 * [SettingsScreen] under `sectionKey == "storage"`), like iOS's Settings > Storage. Nextcloud
 * ([NextcloudStorageScreen]) is the one cloud option on Android - the same self-hosted
 * option as on iOS, and the only sync that works across both platforms.
 *
 * Local (on-device) storage has no settings of its own — just the "Local storage used" readout —
 * so it stays inline on the hub instead of becoming a third row that would open a page holding a
 * single read-only line.
 */

/**
 * Device-level message retention, iOS's "Message retention" menu Picker on the Storage hub
 * (Keep forever / 30 days / 90 days / 1 year). This governs how long messages live on THIS device
 * — older messages are permanently deleted regardless of whether any backup is connected — so it
 * is intentionally separate from (and not gated by) Nextcloud.
 */
@Composable
fun MessageRetentionSetting(chatViewModel: ChatViewModel) {
    val backupRetention by chatViewModel.backupRetention.collectAsState()
    SettingsPickerItem(
        label = stringResource(R.string.message_retention),
        options = BackupRetention.entries,
        selected = backupRetention,
        optionLabel = {
            when (it) {
                BackupRetention.FOREVER -> stringResource(R.string.retention_keep_forever)
                BackupRetention.DAYS_30 -> stringResource(R.string.retention_30_days)
                BackupRetention.DAYS_90 -> stringResource(R.string.retention_90_days)
                BackupRetention.YEAR_1 -> stringResource(R.string.retention_1_year)
            }
        },
        onSelect = {
            chatViewModel.setBackupRetention(it)
            // iOS re-measures the store when the choice changes.
            chatViewModel.refreshLocalStorageSize()
        },
    )
}

/**
 * Settings > Storage > Nextcloud — the self-hosted cloud backup, iOS's NextcloudSettingsView: an
 * inset-grouped Form page ([SettingsFormPage]) whose sections (connect form, or the account,
 * start/backup folders, automatic + manual backup, restore and disconnect) are
 * [NextcloudSettingsSection]'s.
 */
@Composable
fun NextcloudStorageScreen(
    onBack: () -> Unit,
    chatViewModel: ChatViewModel = hiltViewModel()
) {
    Box(modifier = Modifier.fillMaxSize()) {
        SettingsFormPage(title = stringResource(R.string.nextcloud), onBack = onBack) {
            NextcloudSettingsSection(chatViewModel)
        }
        ChatRestoreProgressOverlay(chatViewModel.restoreCoordinator)
    }
}

/** "Re-syncing... 3 of 7 chats", the one [BackupRestoreCoordinator] stage that carries numbers. */
private val RESYNC_STAGE = Regex("""Re-syncing\.\.\. (\d+) of (\d+) chats""")

/** A [BackupRestoreCoordinator] stage, written in English, looked up for the screen as iOS does
 *  (4f8e14bb). A stage with no translation shows as written. */
@Composable
private fun restoreStageText(stage: String): String {
    val progress = RESYNC_STAGE.matchEntire(stage)
    if (progress != null) {
        val done = progress.groupValues[1].toIntOrNull()
        val total = progress.groupValues[2].toIntOrNull()
        if (done != null && total != null) return stringResource(R.string.re_syncing_chats_progress, done, total)
    }
    return when (stage) {
        "Wiping incoming messages..." -> stringResource(R.string.wiping_incoming_messages)
        "Finishing up..." -> stringResource(R.string.finishing_up)
        "Done" -> stringResource(R.string.done)
        else -> stage
    }
}

/**
 * The blocking chat-restore modal — the Android port of iOS's `ChatRestoreProgressModal`
 * (SettingsView.swift). Also fronts the Danger Zone's "Wipe and Re-sync Incoming Messages"
 * ([BackupRestoreCoordinator.startIncomingResync]) with the same phases and buttons —
 * [BackupRestoreCoordinator.kind] just swaps the copy. Rendered as an in-composition
 * full-screen Box over the storage page
 * (same pattern as the KaPosts thread overlay — a Compose Dialog would never honor MATCH_PARENT
 * here), so while a restore runs the user cannot leave:
 *   * the opaque overlay claims the hit test for the whole screen, so the page underneath
 *     (including the top bar's back arrow) is unreachable;
 *   * [BackHandler] swallows system back — [BackupRestoreCoordinator.dismiss] refuses to fire
 *     while the restore is running, and acts as Done/Close on a terminal state;
 *   * the dock steps aside while it shows ([CoverDock]), as a fullScreenCover covers iOS's tab bar.
 * The restore itself is owned by the [BackupRestoreCoordinator] singleton, so even if this
 * composition died the import would keep running to completion.
 */
@Composable
fun ChatRestoreProgressOverlay(coordinator: BackupRestoreCoordinator) {
    val phase by coordinator.phase.collectAsState()
    if (phase is BackupRestoreCoordinator.Phase.Idle) return
    CoverDock()
    val fraction by coordinator.fraction.collectAsState()
    val stageText by coordinator.stageText.collectAsState()
    val kind by coordinator.kind.collectAsState()
    val isResync = kind == BackupRestoreCoordinator.Kind.RESYNC
    val colors = LocalAppColors.current

    BackHandler { coordinator.dismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            // Claims the hit test so nothing behind the overlay is tappable; the card's own
            // buttons sit above this and keep working.
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surface)
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (val current = phase) {
                is BackupRestoreCoordinator.Phase.Idle -> {}
                is BackupRestoreCoordinator.Phase.Running -> {
                    Icon(
                        if (isResync) Icons.Default.Cached else Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = KaspaTeal,
                        modifier = Modifier.size(40.dp)
                    )
                    Text(
                        if (isResync) stringResource(R.string.re_syncing_messages) else stringResource(R.string.restoring_backup),
                        color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val animatedFraction by animateFloatAsState(fraction, label = "restoreProgress")
                        LinearProgressIndicator(
                            progress = { animatedFraction },
                            modifier = Modifier.fillMaxWidth(),
                            color = KaspaTeal,
                            trackColor = colors.surfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                restoreStageText(stageText),
                                color = colors.textSecondary,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(Modifier.size(12.dp))
                            Text(
                                "${(fraction * 100).toInt()}%",
                                color = colors.textSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Text(
                        if (isResync) {
                            stringResource(R.string.please_keep_the_app_open_leaving_now)
                        } else {
                            stringResource(R.string.please_keep_the_app_open_leaving_now_2)
                        },
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }
                is BackupRestoreCoordinator.Phase.Success -> {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = LocalAppColors.current.success,
                        modifier = Modifier.size(44.dp)
                    )
                    Text(
                        if (isResync) stringResource(R.string.re_sync_complete) else stringResource(R.string.restore_complete),
                        color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (isResync) {
                            when {
                                current.messages == 1 && current.conversations == 1 -> stringResource(R.string.resynced_one_message_one_chat)
                                current.messages == 1 -> stringResource(R.string.resynced_one_message_n_chats, current.conversations)
                                current.conversations == 1 -> stringResource(R.string.resynced_n_messages_one_chat, current.messages)
                                else -> stringResource(R.string.resynced_n_messages_n_chats, current.messages, current.conversations)
                            }
                        } else {
                            stringResource(R.string.restored_messages_from_chats, current.messages, current.conversations)
                        },
                        color = colors.textSecondary,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = { coordinator.dismiss() },
                        colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.done), color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
                is BackupRestoreCoordinator.Phase.Failure -> {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFF9500),
                        modifier = Modifier.size(44.dp)
                    )
                    Text(
                        if (isResync) stringResource(R.string.re_sync_failed) else stringResource(R.string.restore_failed),
                        color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        current.message,
                        color = colors.textSecondary,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { coordinator.retry() },
                            colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.try_again), color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                        TextButton(
                            onClick = { coordinator.dismiss() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.close), color = colors.textSecondary)
                        }
                    }
                }
            }
        }
    }
}
