package com.kachat.app.ui.screens

import android.app.Activity
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.R
import com.kachat.app.models.BackupRetention
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
    val restoreState by chatViewModel.restoreState.collectAsState()
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

    val backupInFlight = googleBackupOpState.status == ChatViewModel.GoogleBackupOpStatus.IN_PROGRESS
    val restoreInFlight = restoreState.status == ChatViewModel.ChatHistoryOpStatus.IN_PROGRESS

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
                    if (!restoreInFlight) chatViewModel.restoreFromGoogleDrive()
                }
                if (restoreState.status == ChatViewModel.ChatHistoryOpStatus.SUCCESS) {
                    SettingsFooter(restoreState.message ?: "Restore complete.")
                }
                if (restoreState.status == ChatViewModel.ChatHistoryOpStatus.FAILED) {
                    SettingsFooter(restoreState.message ?: "Restore failed.")
                }

                SettingsDivider()

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
                            "Chat history is kept forever and backed up as-is."
                        } else {
                            "Messages older than ${backupRetention.days} days are permanently deleted from this device, not just excluded from the backup. This cannot be undone."
                        },
                        color = if (backupRetention == BackupRetention.FOREVER) Color.Gray else Color(0xFFFF3B30),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
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
    StoragePageScaffold(title = stringResource(R.string.nextcloud), onBack = onBack) {
        NextcloudSettingsSection(chatViewModel)
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
