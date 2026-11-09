package com.kachat.app.ui.screens

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import com.kachat.app.services.NfcCard
import com.kachat.app.services.NfcCardBackupFormat
import com.kachat.app.services.NfcCardBackupPayload
import com.kachat.app.services.NfcCardConnection
import com.kachat.app.services.NfcCardException
import com.kachat.app.services.NfcCardStatus
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.findFragmentActivity
import com.kachat.app.viewmodels.WalletViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class NfcBackupStep { INTRO, DETAILS, ERASE, SAVED, ERASED }

/**
 * Settings > Back Up to NFC Card (iOS 8d93d7ab `NFCCardBackupView`), behind the seed phrase's
 * biometric gate and only offered for an account with a passphrase. Two taps: the first reads the
 * card to learn whether it needs a new PIN or already holds a backup, the second saves. The
 * passphrase is never written to the card. It never clears the "not backed up" card on Profile: the
 * written seed phrase stays the main copy.
 */
@Composable
fun NfcCardBackupScreen(viewModel: WalletViewModel, onClose: () -> Unit) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val activity: Activity? = remember(context) { context.findFragmentActivity() }
    val scope = rememberCoroutineScope()
    SecureWindowWhileShown()

    var step by remember { mutableStateOf(NfcBackupStep.INTRO) }
    var card by remember { mutableStateOf<NfcCardStatus?>(null) }
    var pin by remember { mutableStateOf("") }
    // A new PIN, typed again: a typo here would lock the backup behind a PIN nobody knows.
    var confirmPin by remember { mutableStateOf("") }
    var pinCleared by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showEraseConfirmation by remember { mutableStateOf(false) }
    var showReplaceConfirmation by remember { mutableStateOf(false) }
    // Set while a tap is awaited: the prompt on screen, as iOS's system sheet shows it.
    var scanMessage by remember { mutableStateOf<String?>(null) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    val pinValid = NfcCard.isValidPin(pin)
    // The PIN can go to the card: a valid one, typed twice when it's a new one (iOS d23e6f7a).
    val canSave = card?.let { pinValid && (it.pinSet || pin == confirmPin) } ?: false

    fun show(error: Throwable) {
        if (error is CancellationException) throw error
        errorMessage = (error as? NfcCardException)?.userMessage(context) ?: error.message
    }

    fun backToStart() {
        pin = ""
        confirmPin = ""
        card = null
        step = NfcBackupStep.INTRO
    }

    fun launchScan(work: suspend () -> Unit) {
        scanJob = scope.launch {
            busy = true
            try {
                work()
            } finally {
                busy = false
                scanMessage = null
                scanJob = null
            }
        }
    }

    fun scanCard() {
        val host = activity ?: return
        launchScan {
            try {
                scanMessage = context.getString(R.string.nfc_tap_prompt)
                val status = NfcCard.run(host) { it.select() }
                if (status.pinBlocked) throw NfcCardException.PinBlocked()
                card = status
                step = NfcBackupStep.DETAILS
            } catch (e: Exception) {
                show(e)
            }
        }
    }

    fun save() {
        val host = activity ?: return
        val scanned = card ?: return
        if (!pinValid || busy) return
        if (!scanned.pinSet && pin != confirmPin) return
        val enteredPin = pin
        launchScan {
            try {
                val payload = viewModel.nfcBackupPayload()
                val backup = withContext(Dispatchers.Default) { NfcCardBackupFormat.seal(payload, enteredPin) }
                if (backup.size > scanned.maxBackup) throw NfcCardException.BackupTooLarge()
                scanMessage = context.getString(R.string.nfc_tap_prompt)
                NfcCard.run(host) { connection: NfcCardConnection ->
                    scanMessage = context.getString(R.string.saving_keep_the_card_still)
                    val current = connection.select()
                    if (current.pinBlocked) throw NfcCardException.PinBlocked()
                    // The confirmations were for the card scanned first. An interrupted save can
                    // legitimately leave that card with its new PIN and no backup yet.
                    // A PIN where the scanned card had none is another card too (this save sets the
                    // PIN it then finds, and records that below): stopped before verifyPin, which
                    // would cost that card a try with a PIN that was never its own.
                    if ((current.hasBackup && !scanned.hasBackup) || (scanned.pinSet && !current.pinSet) ||
                        (!scanned.pinSet && current.pinSet)
                    ) {
                        throw NfcCardException.CardChanged()
                    }
                    if (!current.pinSet) {
                        connection.setPin(enteredPin)
                        // The card has this PIN now, whatever happens next: a retry after an
                        // interrupted save finds it set and asks for it as the card's PIN.
                        card = scanned.copy(pinSet = true, pinBlocked = false, triesRemaining = current.triesRemaining)
                        confirmPin = ""
                    }
                    connection.verifyPin(enteredPin)
                    connection.writeBackup(backup)
                    if (!connection.readBackup().contentEquals(backup)) throw NfcCardException.SaveNotConfirmed()
                }
                // What's on the card has to open with this PIN, not just match what was sent.
                withContext(Dispatchers.Default) { NfcCardBackupFormat.open(backup, enteredPin) }
                pin = ""
                confirmPin = ""
                step = NfcBackupStep.SAVED
            } catch (e: NfcCardException.WrongPin) {
                pin = ""
                confirmPin = ""
                // Keeps a PIN this save set (above): the card asks for it from now on.
                card = scanned.copy(
                    pinSet = card?.pinSet ?: scanned.pinSet,
                    pinBlocked = false,
                    triesRemaining = e.triesRemaining,
                )
                show(e)
            } catch (e: Exception) {
                if (e is NfcCardException.PinBlocked || e is NfcCardException.CardChanged) backToStart()
                show(e)
            }
        }
    }

    fun erase() {
        val host = activity ?: return
        if (!pinValid) return
        val enteredPin = pin
        launchScan {
            try {
                scanMessage = context.getString(R.string.nfc_tap_prompt)
                pinCleared = NfcCard.run(host) { connection: NfcCardConnection ->
                    val current = connection.select()
                    if (current.pinBlocked) throw NfcCardException.PinBlocked()
                    if (!current.pinSet) throw NfcCardException.NoPin()
                    if (!current.hasBackup) throw NfcCardException.NoBackup()
                    connection.verifyPin(enteredPin)
                    connection.wipe()
                    // Cards loaded with older software keep their PIN. The erase has already
                    // worked, so a card pulled away here only costs the wording.
                    runCatching { connection.select() }.getOrNull()?.pinSet == false
                }
                pin = ""
                step = NfcBackupStep.ERASED
            } catch (e: Exception) {
                pin = ""
                show(e)
            }
        }
    }

    val finished = step == NfcBackupStep.SAVED || step == NfcBackupStep.ERASED
    BackHandler(enabled = !busy) { onClose() }

    NfcCardScaffold(title = stringResource(R.string.nfc_card_backup), cancelEnabled = !busy, onCancel = if (finished) null else onClose) {
        when (step) {
            NfcBackupStep.INTRO -> {
                NfcHeading(stringResource(R.string.back_up_to_an_nfc_card))
                Text(stringResource(R.string.saves_this_accounts_12_or_24_words), color = colors.textSecondary, fontSize = 15.sp)
                NfcCardNote(Icons.Filled.Key, KaspaTeal, stringResource(R.string.your_passphrase_is_never_saved_to_the))
                NfcCardNote(Icons.Filled.Warning, colors.warning, stringResource(R.string.this_isnt_cold_storage_the_card_only))
                NfcCardNote(Icons.Filled.SdCard, KaspaTeal, stringResource(R.string.your_card_needs_the_kachat_nfc_software))
                CreateWalletNextButton(stringResource(R.string.scan_card), enabled = !busy, busy = busy) { scanCard() }
                NfcTextButton(stringResource(R.string.erase_a_backup_on_a_card), enabled = !busy) { step = NfcBackupStep.ERASE }
            }
            NfcBackupStep.DETAILS -> card?.let { scanned ->
                if (scanned.hasBackup) {
                    NfcCardNote(Icons.Filled.Warning, colors.warning, stringResource(R.string.this_card_already_holds_a_backup_saving))
                }
                if (scanned.pinSet) {
                    NfcCardSecretField(stringResource(R.string.card_pin), pin, { pin = it }, isNumeric = true)
                    if (scanned.triesRemaining < 5) {
                        Text(stringResource(R.string.pin_tries_left_on_this_card, scanned.triesRemaining), color = colors.warning, fontSize = 13.sp)
                    }
                } else {
                    NfcCardSecretField(stringResource(R.string.new_6_digit_pin), pin, { pin = it }, isNumeric = true)
                    NfcCardSecretField(stringResource(R.string.nfc_reenter_pin), confirmPin, { confirmPin = it }, isNumeric = true)
                    if (NfcCard.isValidPin(confirmPin) && pin != confirmPin) {
                        Text(stringResource(R.string.nfc_pins_dont_match), color = colors.danger, fontSize = 13.sp)
                    }
                    Text(
                        stringResource(R.string.pick_one_youll_remember_after_5_wrong),
                        color = colors.textSecondary,
                        fontSize = 13.sp,
                    )
                }
                CreateWalletNextButton(
                    if (scanned.hasBackup) stringResource(R.string.replace_backup) else stringResource(R.string.save_to_card),
                    enabled = canSave && !busy,
                    busy = busy,
                ) {
                    if (scanned.hasBackup) showReplaceConfirmation = true else save()
                }
            }
            NfcBackupStep.ERASE -> {
                NfcHeading(stringResource(R.string.erase_a_backup))
                Text(stringResource(R.string.enter_the_cards_pin_then_tap_the), color = colors.textSecondary, fontSize = 15.sp)
                NfcCardSecretField(stringResource(R.string.card_pin), pin, { pin = it }, isNumeric = true)
                CreateWalletNextButton(stringResource(R.string.erase_backup), enabled = pinValid && !busy, busy = busy) { showEraseConfirmation = true }
                NfcTextButton(stringResource(R.string.back), enabled = !busy) {
                    pin = ""
                    step = NfcBackupStep.INTRO
                }
            }
            NfcBackupStep.SAVED -> NfcFinished(
                stringResource(R.string.backup_saved),
                stringResource(R.string.it_was_read_back_from_the_card),
                onClose,
            )
            NfcBackupStep.ERASED -> NfcFinished(
                stringResource(R.string.backup_erased),
                if (pinCleared) stringResource(R.string.the_backup_and_pin_are_gone_from)
                else stringResource(R.string.the_backup_is_gone_from_this_card),
                onClose,
            )
        }
    }

    if (showEraseConfirmation) {
        ConfirmActionSheet(
            title = stringResource(R.string.erase_the_backup_on_this_card),
            confirmTitle = stringResource(R.string.erase_backup),
            confirmSubtitle = stringResource(R.string.this_cant_be_undone),
            confirmIcon = Icons.Filled.Delete,
            onConfirm = { erase() },
            onDismiss = { showEraseConfirmation = false },
        )
    }
    if (showReplaceConfirmation) {
        ConfirmActionSheet(
            title = stringResource(R.string.replace_the_backup_on_this_card),
            confirmTitle = stringResource(R.string.replace_backup),
            confirmSubtitle = stringResource(R.string.the_old_backup_is_removed_once_the),
            confirmIcon = Icons.Filled.Nfc,
            onConfirm = { save() },
            onDismiss = { showReplaceConfirmation = false },
        )
    }
    NfcScanPrompt(scanMessage) { scanJob?.cancel() }
    NfcErrorAlert(errorMessage) { errorMessage = null }
}

/**
 * Import Account > Restore from NFC Card (iOS 8d93d7ab `NFCCardRestoreView`): the PIN and a tap read
 * the backup, then the passphrase, which was never on the card, opens the account. A wrong
 * passphrase needs no second tap, since the backup is in memory. Plain [remember] throughout, never
 * saved state (audit AND-031).
 */
@Composable
fun NfcCardRestoreScreen(viewModel: WalletViewModel, onBack: () -> Unit) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val activity: Activity? = remember(context) { context.findFragmentActivity() }
    val scope = rememberCoroutineScope()
    SecureWindowWhileShown()

    var pin by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var payload by remember { mutableStateOf<NfcCardBackupPayload?>(null) }
    var busy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var scanMessage by remember { mutableStateOf<String?>(null) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    val pinValid = NfcCard.isValidPin(pin)
    val importState by viewModel.importWalletState.collectAsState()

    fun show(error: Throwable) {
        if (error is CancellationException) throw error
        errorMessage = (error as? NfcCardException)?.userMessage(context) ?: error.message
    }

    fun readCard() {
        val host = activity ?: return
        if (!pinValid) return
        val enteredPin = pin
        scanJob = scope.launch {
            busy = true
            try {
                scanMessage = context.getString(R.string.nfc_tap_prompt)
                val backup = NfcCard.run(host) { connection: NfcCardConnection ->
                    scanMessage = context.getString(R.string.reading_keep_the_card_still)
                    val current = connection.select()
                    if (current.pinBlocked) throw NfcCardException.PinBlocked()
                    if (!current.pinSet) throw NfcCardException.NoPin()
                    if (!current.hasBackup) throw NfcCardException.NoBackup()
                    connection.verifyPin(enteredPin)
                    connection.readBackup()
                }
                scanMessage = null
                payload = withContext(Dispatchers.Default) { NfcCardBackupFormat.open(backup, enteredPin) }
                pin = ""
            } catch (e: Exception) {
                pin = ""
                show(e)
            } finally {
                busy = false
                scanMessage = null
                scanJob = null
            }
        }
    }

    fun restore() {
        val read = payload ?: return
        scope.launch {
            busy = true
            try {
                val family = read.sourceFamily ?: throw NfcCardException.UnreadableBackup()
                val address = withContext(Dispatchers.Default) {
                    viewModel.previewAddress(read.words, passphrase, family, read.chattingAddressIndex)
                } ?: throw NfcCardException.UnreadableBackup()
                // A wrong passphrase still derives a valid account, just not this one.
                // Compared as an account, not a string: a card saved on mainnet holds a `kaspa:`
                // address, and this phone may be on testnet (or the other way round).
                if (!com.kachat.app.util.KaspaNetwork.isSameAccount(address, read.chattingAddress)) throw NfcCardException.WrongPassphrase()
                // An account already saved here keeps the name it has, not the one on the card
                // (iOS d23e6f7a NFCCardRestoreView).
                val savedName = viewModel.accounts.value
                    .firstOrNull { com.kachat.app.util.KaspaNetwork.isSameAccount(it.address, read.chattingAddress) }
                    ?.name?.trim()?.takeIf { it.isNotEmpty() }
                viewModel.commitCardRestore(read.copy(alias = savedName ?: read.alias), family, passphrase)
            } catch (e: Exception) {
                show(e)
            } finally {
                busy = false
            }
        }
    }

    BackHandler(enabled = !busy) { onBack() }

    NfcCardScaffold(title = stringResource(R.string.import_account), cancelEnabled = !busy, onCancel = onBack, cancelLabel = stringResource(R.string.back)) {
        val read = payload
        if (read == null) {
            NfcHeading(stringResource(R.string.restore_from_an_nfc_card))
            Text(stringResource(R.string.enter_the_cards_pin_then_tap_the_2), color = colors.textSecondary, fontSize = 15.sp)
            NfcCardSecretField(stringResource(R.string.card_pin), pin, { pin = it }, isNumeric = true)
            NfcCardNote(Icons.Filled.Warning, colors.warning, stringResource(R.string.after_5_wrong_tries_the_pin_is))
            CreateWalletNextButton(stringResource(R.string.scan_card), enabled = pinValid && !busy, busy = busy) { readCard() }
        } else {
            NfcHeading(stringResource(R.string.enter_your_passphrase))
            Text(
                stringResource(R.string.the_backup_was_read_from_your_card),
                color = colors.textSecondary,
                fontSize = 15.sp,
            )
            NfcCardSecretField(stringResource(R.string.seed_phrase_passphrase_label), passphrase, { passphrase = it }, isNumeric = false)
            val family = read.sourceFamily
            if (passphrase.isNotEmpty() && family != null) {
                PassphraseAddressPreview(
                    passphrase = passphrase,
                    previewAddress = { viewModel.previewAddress(read.words, it, family, read.chattingAddressIndex) },
                    previewBalance = { viewModel.previewAddressBalanceSompi(it) },
                    showsFundsHint = true,
                )
            }
            CreateWalletNextButton(stringResource(R.string.restore_account), enabled = passphrase.isNotEmpty() && !busy, busy = busy) { restore() }
            importState.errorMessage?.let { Text(it, color = colors.danger, fontSize = 13.sp) }
        }
    }

    NfcScanPrompt(scanMessage) { scanJob?.cancel() }
    NfcErrorAlert(errorMessage) { errorMessage = null }
}

/** The screen's frame: an inline title, and Cancel unless the flow is finished. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NfcCardScaffold(
    title: String,
    cancelEnabled: Boolean,
    onCancel: (() -> Unit)?,
    cancelLabel: String = stringResource(R.string.cancel),
    content: @Composable () -> Unit,
) {
    val colors = LocalAppColors.current
    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
                navigationIcon = {
                    if (onCancel != null) {
                        SheetBarButton(cancelLabel, onClick = onCancel, enabled = cancelEnabled, modifier = Modifier.padding(start = 12.dp))
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) { content() }
    }
}

@Composable
private fun NfcHeading(text: String) {
    Text(text, color = LocalAppColors.current.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun NfcTextButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (enabled) KaspaTeal else KaspaTeal.copy(alpha = 0.4f),
        fontSize = 15.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
    )
}

@Composable
private fun NfcFinished(title: String, detail: String, onDone: () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = colors.success, modifier = Modifier.size(38.dp))
        NfcHeading(title)
        Text(detail, color = colors.textSecondary, fontSize = 15.sp, modifier = Modifier.fillMaxWidth())
        CreateWalletNextButton(stringResource(R.string.done), enabled = true, onClick = onDone)
    }
}

/**
 * A PIN or passphrase entry, hidden until its eye is tapped, which still lets a mistake be caught
 * before it costs a PIN try (iOS 4f8e14bb). It never offers to save the value or autofill it. A
 * PIN field only takes digits, up to the PIN's length.
 */
@Composable
private fun NfcCardSecretField(title: String, value: String, onValueChange: (String) -> Unit, isNumeric: Boolean) {
    val colors = LocalAppColors.current
    var revealed by remember { mutableStateOf(false) }
    TextField(
        value = value,
        onValueChange = { typed ->
            onValueChange(if (isNumeric) typed.filter { it in '0'..'9' }.take(NfcCard.PIN_LENGTH) else typed)
        },
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
        placeholder = { Text(title, color = colors.textSecondary) },
        singleLine = true,
        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { revealed = !revealed }) {
                Icon(
                    imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(if (revealed) R.string.hide_password else R.string.show_password),
                    tint = colors.textSecondary,
                )
            }
        },
        // The password keyboards, so no keyboard learns it.
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isNumeric) KeyboardType.NumberPassword else KeyboardType.Password,
            capitalization = KeyboardCapitalization.None,
            autoCorrect = false,
        ),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.surface,
            unfocusedContainerColor = colors.surface,
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
            cursorColor = KaspaTeal,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
private fun NfcCardNote(icon: ImageVector, tint: Color, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.1f))
            .padding(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, color = LocalAppColors.current.textSecondary, fontSize = 15.sp, modifier = Modifier.weight(1f))
    }
}

/** What iOS's system sheet shows while it waits for a card; Android has none of its own. */
@Composable
private fun NfcScanPrompt(message: String?, onCancel: () -> Unit) {
    val text = message ?: return
    val colors = LocalAppColors.current
    com.kachat.app.ui.theme.IosAlertDialog(
        onDismissRequest = {},
        containerColor = colors.surface,
        title = { Text(stringResource(R.string.ready_to_scan), color = colors.textPrimary) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Nfc, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text(text, color = colors.textSecondary, textAlign = TextAlign.Center)
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel), color = KaspaTeal, fontWeight = FontWeight.Bold)
            }
        },
    )
}

@Composable
private fun NfcErrorAlert(message: String?, onDismiss: () -> Unit) {
    val text = message ?: return
    val colors = LocalAppColors.current
    com.kachat.app.ui.theme.IosAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text(stringResource(R.string.nfc_card), color = colors.textPrimary) },
        text = { Text(text, color = colors.textSecondary) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ok), color = KaspaTeal, fontWeight = FontWeight.Bold)
            }
        },
    )
}
