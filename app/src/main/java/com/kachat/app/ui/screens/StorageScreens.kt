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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
 * [SettingsScreen] under `sectionKey == "storage"`), one per backup provider, exactly like iOS's
 * Settings > Storage — which lists iCloud and Nextcloud rows that each push their own page. On
 * Android the counterpart of iOS's iCloud row is Google Drive ([GoogleDriveStorageScreen]);
 * Nextcloud ([NextcloudStorageScreen]) is the same self-hosted option as on iOS.
 *
 * Local (on-device) storage has no settings of its own — just the "Local storage used" readout —
 * so it stays inline on the hub instead of becoming a third row that would open a page holding a
 * single read-only line.
 */

/** Unwraps a Compose [android.content.Context] (often a ContextWrapper) to find the real hosting Activity — needed for Credential Manager / Drive authorization, which require an Activity, not just any Context. */
internal tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Settings > Storage > Google Drive — sign-in/authorization toggle, backup size, manual
 * backup/restore and the retention picker. Drive backup is `drive.appdata`-scoped (hidden
 * per-app folder); see [com.kachat.app.services.GoogleDriveBackupService].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoogleDriveStorageScreen(
    onBack: () -> Unit,
    chatViewModel: ChatViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val googleBackupEnabled by chatViewModel.googleBackupEnabled.collectAsState()
    val googleBackupOpState by chatViewModel.googleBackupOpState.collectAsState()
    val restorePhase by chatViewModel.restoreCoordinator.phase.collectAsState()
    val pendingConsentIntent by chatViewModel.pendingConsentIntent.collectAsState()
    val driveBackupSizeState by chatViewModel.driveBackupSizeState.collectAsState()
    val activity = context.findActivity()

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        chatViewModel.consentIntentLaunched()
        result.data?.let { chatViewModel.completeGoogleDriveAuthorization(it) }
    }

    LaunchedEffect(pendingConsentIntent) {
        pendingConsentIntent?.let { pendingIntent ->
            consentLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
        }
    }

    val driveAutoSyncEnabled by chatViewModel.driveAutoSyncEnabled.collectAsState()
    val driveLastAutoSyncMs by chatViewModel.driveLastAutoSyncMs.collectAsState()
    var showDeleteBackupDialog by remember { mutableStateOf(false) }

    val backupInFlight = googleBackupOpState.status == ChatViewModel.GoogleBackupOpStatus.IN_PROGRESS
    val restoreInFlight = restorePhase is BackupRestoreCoordinator.Phase.Running

    Box(modifier = Modifier.fillMaxSize()) {
    StoragePageScaffold(title = stringResource(R.string.google_drive), onBack = onBack) {
        SettingsSection(title = null) {
            SettingsSwitchItem(
                stringResource(R.string.back_up_to_google_drive),
                checked = googleBackupEnabled,
                onCheckedChange = { checked ->
                    if (checked) {
                        activity?.let { chatViewModel.enableGoogleDriveBackup(it) }
                    } else {
                        chatViewModel.disableGoogleDriveBackup()
                    }
                }
            )
            val backupFooterText = when {
                backupInFlight -> "Working..."
                googleBackupOpState.status == ChatViewModel.GoogleBackupOpStatus.FAILED -> googleBackupOpState.message ?: "Something went wrong"
                googleBackupEnabled && googleBackupOpState.signedInEmail != null -> "Signed in as ${googleBackupOpState.signedInEmail}"
                else -> "Off by default. Backs up chat history to your own Google Drive as hidden storage, not visible in your regular Drive files."
            }
            SettingsFooter(backupFooterText)

            // Google Drive backup is one flat JSON file per account (no live per-record cloud
            // sync like iOS's CloudKit), so "cloud storage used" here is just that file's size.
            SettingsDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(stringResource(R.string.google_drive_backup_used), color = LocalAppColors.current.textPrimary, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = when (driveBackupSizeState.status) {
                            ChatViewModel.DriveSizeStatus.IDLE -> "Not checked"
                            ChatViewModel.DriveSizeStatus.LOADING -> "Checking..."
                            ChatViewModel.DriveSizeStatus.LOADED -> driveBackupSizeState.bytes?.let {
                                android.text.format.Formatter.formatShortFileSize(context, it)
                            } ?: "No backup found"
                            ChatViewModel.DriveSizeStatus.FAILED -> "Unavailable"
                        },
                        color = LocalAppColors.current.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (driveBackupSizeState.status == ChatViewModel.DriveSizeStatus.LOADING) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = KaspaTeal, strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { chatViewModel.refreshDriveBackupSize() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.check_drive_backup_size), tint = KaspaTeal)
                    }
                }
            }

            if (googleBackupEnabled) {
                // Automatic sync: the Android counterpart of iOS's invisible iCloud persistence.
                // On keeps the wallet's Drive file current after message activity (debounced,
                // with a periodic fallback) and restores it automatically on wallet activation.
                SettingsDivider()
                SettingsSwitchItem(
                    "Automatic Drive Sync",
                    checked = driveAutoSyncEnabled,
                    onCheckedChange = { chatViewModel.setDriveAutoSyncEnabled(it) }
                )
                SettingsFooter(
                    if (driveAutoSyncEnabled) {
                        val lastSync = driveLastAutoSyncMs
                        if (lastSync != null) {
                            "Chat history syncs to Drive automatically. Last synced " +
                                android.text.format.DateUtils.getRelativeTimeSpanString(lastSync).toString().lowercase() + "."
                        } else {
                            "Chat history syncs to Drive automatically after new messages. Not synced yet."
                        }
                    } else {
                        "Automatic sync is off. Only manual backups update the Drive file."
                    }
                )
                SettingsDivider()
                SettingsActionItem(
                    label = if (backupInFlight) "Backing Up..." else "Back Up Now",
                    icon = Icons.Default.CloudUpload,
                    color = if (backupInFlight) Color.Gray else KaspaTeal
                ) {
                    if (!backupInFlight) chatViewModel.backupNow()
                }
                SettingsDivider()
                SettingsActionItem(
                    label = if (restoreInFlight) "Restoring..." else "Restore from Google Drive",
                    icon = Icons.Default.CloudDownload,
                    color = if (restoreInFlight) Color.Gray else KaspaTeal
                ) {
                    // Terminal states (success/failure) show in the blocking restore modal, not
                    // as footer rows here.
                    if (!restoreInFlight) chatViewModel.restoreFromGoogleDrive()
                }
                // Purge: mirrors iOS's "delete this wallet's CloudKit data". Only the current
                // wallet's Drive file; local messages and other wallets' files are untouched.
                SettingsDivider()
                SettingsActionItem(
                    label = "Delete Drive Backup",
                    icon = Icons.Default.Delete,
                    color = if (backupInFlight) Color.Gray else Color(0xFFFF3B30)
                ) {
                    if (!backupInFlight) showDeleteBackupDialog = true
                }
            }
        }
    }

    if (showDeleteBackupDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteBackupDialog = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Delete Drive Backup?", color = LocalAppColors.current.textPrimary) },
            text = {
                Text(
                    "This permanently deletes this wallet's chat history backup from your Google Drive. " +
                        "Messages on this device are not affected. If Automatic Drive Sync stays on, a new backup will be created after the next message.",
                    color = LocalAppColors.current.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteBackupDialog = false
                    chatViewModel.deleteDriveBackup()
                }) {
                    Text("Delete", color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteBackupDialog = false }) {
                    Text("Cancel", color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }
    ChatRestoreProgressOverlay(chatViewModel.restoreCoordinator)
    }
}

/**
 * Device-level message retention (Forever / 30 / 90 days), shown on the Storage hub before any
 * cloud provider is chosen. This governs how long messages live on THIS device — older messages
 * are permanently deleted regardless of whether any backup is connected — so it is intentionally
 * separate from (and not gated by) Google Drive / Nextcloud.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageRetentionSetting(chatViewModel: ChatViewModel) {
    val backupRetention by chatViewModel.backupRetention.collectAsState()
    Column(modifier = Modifier.padding(16.dp)) {
        Text(stringResource(R.string.retention), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val options = listOf(
                Triple("Forever", BackupRetention.FOREVER, 0),
                Triple("30 Days", BackupRetention.DAYS_30, 1),
                Triple("90 Days", BackupRetention.DAYS_90, 2)
            )
            options.forEach { (label, value, index) ->
                SegmentedButton(
                    selected = backupRetention == value,
                    onClick = { chatViewModel.setBackupRetention(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = LocalAppColors.current.surfaceVariant,
                        activeContentColor = LocalAppColors.current.textPrimary,
                        inactiveContainerColor = LocalAppColors.current.surface,
                        inactiveContentColor = LocalAppColors.current.textSecondary
                    )
                ) {
                    Text(label, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (backupRetention == BackupRetention.FOREVER) {
                "Chat history is kept forever on this device."
            } else {
                "Messages older than ${backupRetention.days} days are permanently deleted from this device. This cannot be undone."
            },
            color = if (backupRetention == BackupRetention.FOREVER) Color.Gray else Color(0xFFFF3B30),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * Settings > Storage > Nextcloud — the self-hosted alternative to Google Drive. All of the
 * behaviour (connect form, start/backup folders, media-send toggle, automatic + manual backup,
 * restore, disconnect) lives in [NextcloudSettingsSection], unchanged; this screen only supplies
 * the sub-page chrome around it.
 */
@Composable
fun NextcloudStorageScreen(
    onBack: () -> Unit,
    chatViewModel: ChatViewModel = hiltViewModel()
) {
    Box(modifier = Modifier.fillMaxSize()) {
        StoragePageScaffold(title = stringResource(R.string.nextcloud), onBack = onBack) {
            NextcloudSettingsSection(chatViewModel)
        }
        ChatRestoreProgressOverlay(chatViewModel.restoreCoordinator)
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
 *   * the storage pages are not tab routes, so there is no bottom bar to block.
 * The restore itself is owned by the [BackupRestoreCoordinator] singleton, so even if this
 * composition died the import would keep running to completion.
 */
@Composable
fun ChatRestoreProgressOverlay(coordinator: BackupRestoreCoordinator) {
    val phase by coordinator.phase.collectAsState()
    if (phase is BackupRestoreCoordinator.Phase.Idle) return
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
                        if (isResync) "Re-syncing Messages" else "Restoring Backup",
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
                                stageText,
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
                            "Please keep the app open. Leaving now could leave your chat history incomplete."
                        } else {
                            "Please keep the app open. Leaving now could corrupt your chat history."
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
                        tint = Color(0xFF34C759),
                        modifier = Modifier.size(44.dp)
                    )
                    Text(
                        if (isResync) "Re-sync Complete" else "Restore Complete",
                        color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (isResync) {
                            "Re-synced ${current.messages} incoming messages across ${current.conversations} chats."
                        } else {
                            "Restored ${current.messages} messages from ${current.conversations} chats."
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
                        Text("Done", color = Color.Black, fontWeight = FontWeight.Bold)
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
                        if (isResync) "Re-sync Failed" else "Restore Failed",
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
                            Text("Try Again", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                        TextButton(
                            onClick = { coordinator.dismiss() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Close", color = colors.textSecondary)
                        }
                    }
                }
            }
        }
    }
}

/** Shared chrome for the Storage sub-pages — the app's standard settings sub-screen look
 *  (centered bold title, teal back arrow, scrolling 16dp-padded column), matching
 *  AppSettingsScreen.kt's category pages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoragePageScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}
