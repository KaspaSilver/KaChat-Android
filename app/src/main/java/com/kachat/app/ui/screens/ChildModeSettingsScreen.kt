package com.kachat.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.R
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.SettingsViewModel
import kotlinx.coroutines.launch

/**
 * Settings > Security > Child Mode — direct port of iOS's `ChildModeSettingsView`.
 *
 * - No password yet: set one (enter + confirm) and Child Mode turns on in the same stroke.
 * - Password set: the ON/OFF toggle lives here. Turning OFF demands the password via a dialog
 *   (wrong password = error + stays on); turning back ON needs nothing. Plus a traditional
 *   change-password flow (current -> new -> confirm; wrong current = error, nothing changes),
 *   and a destructive "Clear Password" action (password-gated; deletes the stored record and
 *   turns Child Mode off, returning the screen to its first-time state).
 *
 * Deliberately NO biometrics anywhere in this flow — the device owner (the child) can pass
 * fingerprint/face unlock, so only manual password entry counts. See `ChildModeService` for the
 * storage design (salted SHA-256 in EncryptedSharedPreferences, never plaintext).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildModeSettingsScreen(
    onBack: () -> Unit,
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val isEnabled by settingsViewModel.childModeEnabled.collectAsState()
    // Mirrors ChildModeService.hasPassword; kept in state so the screen re-renders the moment
    // the first password is set (or the record is cleared).
    var hasPassword by remember { mutableStateOf(settingsViewModel.hasChildModePassword()) }
    val scope = rememberCoroutineScope()

    // First-time setup
    var setupPassword by remember { mutableStateOf("") }
    var setupConfirm by remember { mutableStateOf("") }
    var setupError by remember { mutableStateOf<String?>(null) }

    // Turn-off prompt
    var showTurnOffPrompt by remember { mutableStateOf(false) }
    var turnOffPassword by remember { mutableStateOf("") }
    var turnOffError by remember { mutableStateOf<String?>(null) }

    // Change password
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var newPasswordConfirm by remember { mutableStateOf("") }
    var changeError by remember { mutableStateOf<String?>(null) }
    var changeSucceeded by remember { mutableStateOf(false) }

    // Clear password (full reset)
    var showClearPrompt by remember { mutableStateOf(false) }
    var clearPassword by remember { mutableStateOf("") }
    var clearError by remember { mutableStateOf<String?>(null) }

    val enterPasswordFirst = stringResource(R.string.enter_a_password_first)
    val passwordsDontMatch = stringResource(R.string.passwords_dont_match)
    val couldntSavePassword = stringResource(R.string.couldnt_save_the_password)
    val wrongPasswordStaysOn = stringResource(R.string.wrong_password_child_mode_stays_on)
    val newPasswordsDontMatch = stringResource(R.string.new_passwords_dont_match)
    val wrongCurrentPassword = stringResource(R.string.wrong_current_password_nothing_changed)
    val wrongPasswordNothingChanged = stringResource(R.string.wrong_password_nothing_changed)

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.child_mode), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
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
                .verticalScroll(rememberScrollState())
        ) {
            if (hasPassword) {
                // ---- Toggle (password already set) ----
                SettingsSection(title = null) {
                    SettingsSwitchItem(stringResource(R.string.child_mode), isEnabled) { newValue ->
                        if (newValue) {
                            // Turning ON with a password already set needs no password.
                            settingsViewModel.enableChildMode()
                        } else {
                            // Turning OFF requires the password - don't change anything yet.
                            turnOffPassword = ""
                            turnOffError = null
                            showTurnOffPrompt = true
                        }
                    }
                    SettingsFooter(
                        stringResource(
                            if (isEnabled) R.string.child_mode_is_on_footer
                            else R.string.child_mode_password_set_footer
                        )
                    )
                }
                Spacer(Modifier.height(24.dp))

                // ---- Change password (traditional current -> new -> confirm) ----
                SettingsSection(title = stringResource(R.string.change_password)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        RevealableSecureField(
                            value = currentPassword,
                            onValueChange = { currentPassword = it; changeError = null; changeSucceeded = false },
                            label = stringResource(R.string.current_password)
                        )
                        Spacer(Modifier.height(8.dp))
                        RevealableSecureField(
                            value = newPassword,
                            onValueChange = { newPassword = it; changeError = null; changeSucceeded = false },
                            label = stringResource(R.string.new_password)
                        )
                        Spacer(Modifier.height(8.dp))
                        RevealableSecureField(
                            value = newPasswordConfirm,
                            onValueChange = { newPasswordConfirm = it; changeError = null; changeSucceeded = false },
                            label = stringResource(R.string.confirm_new_password)
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                when {
                                    newPassword != newPasswordConfirm -> changeError = newPasswordsDontMatch
                                    !settingsViewModel.changeChildModePassword(currentPassword, newPassword) -> {
                                        changeError = wrongCurrentPassword
                                        currentPassword = ""
                                    }
                                    else -> {
                                        currentPassword = ""
                                        newPassword = ""
                                        newPasswordConfirm = ""
                                        changeError = null
                                        changeSucceeded = true
                                    }
                                }
                            },
                            enabled = currentPassword.isNotEmpty() && newPassword.isNotEmpty() && newPasswordConfirm.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.change_password), color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                    changeError?.let { SettingsFooter(it) }
                    if (changeSucceeded) SettingsFooter(stringResource(R.string.password_changed))
                }
                Spacer(Modifier.height(24.dp))

                // ---- Clear password (full reset to never-configured) ----
                SettingsSection(title = null) {
                    SettingsActionItem(stringResource(R.string.clear_password), Icons.Default.Lock, Color.Red) {
                        clearPassword = ""
                        clearError = null
                        showClearPrompt = true
                    }
                    SettingsFooter(stringResource(R.string.clear_password_footer))
                }
            } else {
                // ---- First-time setup (no password yet) ----
                SettingsSection(title = stringResource(R.string.set_a_password)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        RevealableSecureField(
                            value = setupPassword,
                            onValueChange = { setupPassword = it; setupError = null },
                            label = stringResource(R.string.password)
                        )
                        Spacer(Modifier.height(8.dp))
                        RevealableSecureField(
                            value = setupConfirm,
                            onValueChange = { setupConfirm = it; setupError = null },
                            label = stringResource(R.string.confirm_password)
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                when {
                                    setupPassword.isEmpty() -> setupError = enterPasswordFirst
                                    setupPassword != setupConfirm -> setupError = passwordsDontMatch
                                    !settingsViewModel.setChildModePassword(setupPassword) -> setupError = couldntSavePassword
                                    else -> {
                                        settingsViewModel.enableChildMode()
                                        setupPassword = ""
                                        setupConfirm = ""
                                        setupError = null
                                        hasPassword = true
                                    }
                                }
                            },
                            enabled = setupPassword.isNotEmpty() && setupConfirm.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.set_password_and_turn_on), color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                    SettingsFooter(setupError ?: stringResource(R.string.child_mode_password_helper))
                }
            }

            Spacer(Modifier.height(24.dp))

            // ---- What stays available ----
            SettingsSection(title = stringResource(R.string.what_stays_available)) {
                ChildModeFeatureRow(Icons.AutoMirrored.Filled.Chat, stringResource(R.string.chats_and_group_chats))
                SettingsDivider()
                ChildModeFeatureRow(Icons.Default.PieChart, stringResource(R.string.portfolio))
                SettingsDivider()
                ChildModeFeatureRow(Icons.Default.Lock, stringResource(R.string.cold_storage))
                SettingsFooter(stringResource(R.string.child_mode_about_footer))
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // Manual password entry dialog for switching Child Mode off. Wrong password = error, the
    // toggle stays on. Never biometrics.
    if (showTurnOffPrompt) {
        ChildModePasswordDialog(
            title = stringResource(R.string.turn_off_child_mode),
            helper = turnOffError ?: stringResource(R.string.enter_the_child_mode_password_off),
            helperIsError = turnOffError != null,
            confirmLabel = stringResource(R.string.turn_off),
            confirmColor = KaspaTeal,
            password = turnOffPassword,
            onPasswordChange = { turnOffPassword = it; turnOffError = null },
            onDismiss = { showTurnOffPrompt = false },
            onConfirm = {
                if (settingsViewModel.turnOffChildMode(turnOffPassword)) {
                    turnOffPassword = ""
                    showTurnOffPrompt = false
                } else {
                    turnOffError = wrongPasswordStaysOn
                    turnOffPassword = ""
                }
            }
        )
    }

    // Same manual-password-entry pattern for the full reset: one entry, wrong password = error
    // and nothing happens, never biometrics.
    if (showClearPrompt) {
        ChildModePasswordDialog(
            title = stringResource(R.string.clear_password),
            helper = clearError ?: stringResource(R.string.enter_the_child_mode_password_clear),
            helperIsError = clearError != null,
            confirmLabel = stringResource(R.string.clear_password),
            confirmColor = Color.Red,
            password = clearPassword,
            onPasswordChange = { clearPassword = it; clearError = null },
            onDismiss = { showClearPrompt = false },
            onConfirm = {
                scope.launch {
                    if (settingsViewModel.clearChildModeConfiguration(clearPassword)) {
                        // Reset every flow's scratch state - the screen drops back to
                        // first-time setup (the service already flipped the flag off).
                        currentPassword = ""
                        newPassword = ""
                        newPasswordConfirm = ""
                        changeError = null
                        changeSucceeded = false
                        clearPassword = ""
                        clearError = null
                        hasPassword = false
                        showClearPrompt = false
                    } else {
                        clearError = wrongPasswordNothingChanged
                        clearPassword = ""
                    }
                }
            }
        )
    }
}

@Composable
private fun ChildModeFeatureRow(icon: ImageVector, label: String) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
    }
}

/** One password field + confirm/cancel — shared by the turn-off and clear prompts. */
@Composable
private fun ChildModePasswordDialog(
    title: String,
    helper: String,
    helperIsError: Boolean,
    confirmLabel: String,
    confirmColor: Color,
    password: String,
    onPasswordChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LocalAppColors.current.surface,
        title = { Text(title, color = LocalAppColors.current.textPrimary) },
        text = {
            Column {
                RevealableSecureField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = stringResource(R.string.password)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    helper,
                    color = if (helperIsError) Color(0xFFFF3B30) else LocalAppColors.current.textSecondary,
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = password.isNotEmpty()) {
                Text(confirmLabel, color = confirmColor, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
            }
        }
    )
}
