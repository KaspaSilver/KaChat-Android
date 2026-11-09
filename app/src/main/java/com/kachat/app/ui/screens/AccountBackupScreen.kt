package com.kachat.app.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.IosHaptic
import com.kachat.app.util.authenticateWithDeviceCredential
import com.kachat.app.util.rememberHaptics
import com.kachat.app.viewmodels.WalletViewModel

// Backing up an account, in plain steps, and checking on it every six months (iOS d063b897
// AccountBackupView.swift). The text is English only for now, as on iOS.

/** Opens the backup steps behind the seed phrase's biometric gate, when enabled. */
internal fun unlockAccountBackup(context: Context, biometricEnabled: Boolean, show: () -> Unit) {
    if (biometricEnabled) {
        context.authenticateWithDeviceCredential(title = context.getString(R.string.unlock_to_view_your_12_or_24), onSuccess = show)
    } else {
        show()
    }
}

/**
 * The strip under the balance on every main screen while an account isn't backed up (iOS d1844582
 * `AccountBackupBanner`). It stays until the backup steps are finished, and turns red once the
 * chatting address holds funds. [onOpenBackup] runs behind the seed phrase's biometric gate.
 */
@Composable
internal fun AccountBackupBanner(viewModel: WalletViewModel, onOpenBackup: () -> Unit) {
    val colors = LocalAppColors.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val address by viewModel.address.collectAsState()
    val pendingAddresses by viewModel.accountBackupStatus.pendingAddresses.collectAsState()
    val balanceSompi by viewModel.balanceSompi.collectAsState()
    val biometricEnabled by viewModel.biometricSeedPhraseEnabled.collectAsState()
    val current = address
    if (current == null || current !in pendingAddresses) return
    val hasFunds = balanceSompi > 0L
    val tint = if (hasFunds) colors.danger else colors.warning
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(tint.copy(alpha = 0.12f))
            .clickable { unlockAccountBackup(context, biometricEnabled, onOpenBackup) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.GppMaybe, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.backup_your_account_to_prevent_loss_of),
                color = colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (hasFunds) stringResource(R.string.you_now_have_funds_that_arent_backed)
                else stringResource(R.string.if_you_lose_this_phone_your_12),
                color = if (hasFunds) colors.danger else colors.textSecondary,
                fontSize = 11.sp,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Wallet's card every six months after a finished backup (iOS bfe9f022). */
@Composable
internal fun BackupHygieneCard(onClick: () -> Unit) {
    BackupCardFrame(tint = KaspaTeal, icon = Icons.Filled.VerifiedUser, onClick = onClick) {
        Text(
            stringResource(R.string.backup_hygiene),
            color = LocalAppColors.current.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun BackupCardFrame(
    tint: Color,
    icon: ImageVector,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tint.copy(alpha = 0.1f))
            // iOS strokes 1pt on the edge and clips it to the shape: half shows.
            .border(0.5.dp, tint.copy(alpha = 0.5f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier.size(48.dp).clip(CircleShape).background(tint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        }
        Box(Modifier.weight(1f)) { content() }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = LocalAppColors.current.textSecondary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** The backup screens' frame: the inline title, an optional Back, and Close. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    onClose: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val colors = LocalAppColors.current
    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
                navigationIcon = {
                    if (onBack != null) {
                        SheetBarButton(stringResource(R.string.back), onClick = onBack, modifier = Modifier.padding(start = 12.dp))
                    }
                },
                actions = {
                    SheetBarButton(stringResource(R.string.close), onClick = onClose, modifier = Modifier.padding(end = 12.dp))
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background),
            )
        },
        content = content,
    )
}

/** A passphrase being typed back: the Password keyboard even while revealed, so no keyboard
 *  learns it, with an eye to show or hide it. */
@Composable
private fun BackupPassphraseField(value: String, onValueChange: (String) -> Unit) {
    val colors = LocalAppColors.current
    var reveal by remember { mutableStateOf(false) }
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)),
        placeholder = { Text(stringResource(R.string.seed_phrase_passphrase_label), color = colors.textSecondary) },
        singleLine = true,
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            capitalization = KeyboardCapitalization.None,
            autoCorrect = false,
        ),
        trailingIcon = {
            IconButton(onClick = { reveal = !reveal }) {
                Icon(
                    if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (reveal) stringResource(R.string.hide) else stringResource(R.string.show),
                    tint = colors.textSecondary,
                )
            }
        },
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

private enum class BackupStep { WHY, PREPARE, WORDS, WORD_CHECK, PASSPHRASE, PASSPHRASE_CHECK, TIPS }

private const val WORDS_PER_PAGE = 6
private const val BACKUP_WORDS_TO_CHECK = 4
private const val HYGIENE_WORDS_TO_CHECK = 5

/**
 * Backing up an account's seed phrase and passphrase: why, getting ready, the words a few at a
 * time, proving they were written down, then where to keep them. Words are written by hand: this
 * flow never offers a copy (Settings > Back Up Account > View 12 or 24 Words does). Android can stop a screenshot where
 * iOS can only say so afterwards, so every step showing a word or the passphrase is FLAG_SECURE.
 * Plain [remember] throughout: composition memory only, never saved state (audit AND-031).
 */
@Composable
fun AccountBackupScreen(viewModel: WalletViewModel, onClose: () -> Unit) {
    val colors = LocalAppColors.current
    val haptic = rememberHaptics()
    val words = remember { viewModel.getActiveMnemonic()?.split(" ")?.filter { it.isNotBlank() }.orEmpty() }
    val passphrase = remember { viewModel.getActivePassphrase() }

    if (words.isEmpty()) {
        BackHandler(onBack = onClose)
        BackupScaffold(title = stringResource(R.string.settings_back_up_account), onClose = onClose) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.this_accounts_12_or_24_words_arent),
                    color = colors.textSecondary,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
        return
    }

    var step by remember { mutableStateOf(BackupStep.WHY) }
    var showExplainer by remember { mutableStateOf(false) }
    var hasPenAndPaper by remember { mutableStateOf(false) }
    var isPrivate by remember { mutableStateOf(false) }
    var noPhotos by remember { mutableStateOf(false) }
    var wordPage by remember { mutableIntStateOf(0) }
    var checkIndexes by remember { mutableStateOf(emptyList<Int>()) }
    var answers by remember { mutableStateOf(emptyMap<Int, String>()) }
    var wrongIndexes by remember { mutableStateOf(emptySet<Int>()) }
    var focusedWord by remember { mutableStateOf<Int?>(null) }
    var passphraseAnswer by remember { mutableStateOf("") }
    var passphraseWrong by remember { mutableStateOf(false) }

    val pageCount = maxOf(1, (words.size + WORDS_PER_PAGE - 1) / WORDS_PER_PAGE)
    // This account's steps, for the progress bar: the passphrase's only when it has one.
    val steps = BackupStep.entries.filter {
        passphrase != null || (it != BackupStep.PASSPHRASE && it != BackupStep.PASSPHRASE_CHECK)
    }

    fun startWordCheck() {
        checkIndexes = words.indices.shuffled().take(BACKUP_WORDS_TO_CHECK).sorted()
        answers = emptyMap()
        wrongIndexes = emptySet()
        step = BackupStep.WORD_CHECK
    }

    fun goBack() {
        when (step) {
            BackupStep.WHY -> onClose()
            BackupStep.PREPARE -> step = BackupStep.WHY
            BackupStep.WORDS -> if (wordPage > 0) wordPage-- else step = BackupStep.PREPARE
            BackupStep.WORD_CHECK -> {
                wordPage = pageCount - 1
                step = BackupStep.WORDS
            }
            // Back to a check already passed keeps its words and answers: going back is for
            // looking again, not for being tested on new words (iOS 3942a5b7).
            BackupStep.PASSPHRASE -> step = BackupStep.WORD_CHECK
            BackupStep.PASSPHRASE_CHECK -> step = BackupStep.PASSPHRASE
            BackupStep.TIPS -> step = if (passphrase != null) BackupStep.PASSPHRASE_CHECK else BackupStep.WORD_CHECK
        }
    }

    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(step) { focusManager.clearFocus() }

    if (showExplainer) {
        BackHandler { showExplainer = false }
        SeedPhraseExplainerPage(onBack = { showExplainer = false })
        return
    }
    BackHandler(onBack = ::goBack)

    if (step == BackupStep.WORDS || step == BackupStep.WORD_CHECK || step == BackupStep.PASSPHRASE || step == BackupStep.PASSPHRASE_CHECK) {
        SecureWindowWhileShown()
    }

    BackupScaffold(
        title = stringResource(R.string.settings_back_up_account),
        onBack = if (step == BackupStep.WHY) null else ::goBack,
        onClose = onClose,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            // One step's page: progress on top, the content scrolling, its button always in reach.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                LinearProgressIndicator(
                    progress = { (steps.indexOf(step) + 1).toFloat() / steps.size },
                    modifier = Modifier.fillMaxWidth(),
                    color = KaspaTeal,
                    trackColor = colors.surface,
                )
                when (step) {
                    BackupStep.WHY -> {
                        BackupHeading(stringResource(R.string.protect_your_funds))
                        BackupPoint(Icons.Filled.Key, stringResource(R.string.your_12_or_24_words_are_your),
                            stringResource(R.string.these_secret_words_are_created_just_for))
                        BackupPoint(Icons.Filled.MobileOff, stringResource(R.string.your_phone_cant_be_your_only_copy),
                            stringResource(R.string.if_you_lose_break_or_replace_this))
                        BackupPoint(Icons.Filled.PersonOff, stringResource(R.string.nobody_can_reset_them),
                            stringResource(R.string.there_is_no_password_reset_kachat_never))
                        Row(
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { showExplainer = true },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.what_is_a_seed_phrase), color = KaspaTeal, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    BackupStep.PREPARE -> {
                        BackupHeading(stringResource(R.string.get_ready), stringResource(R.string.your_secret_words_are_next_before_you))
                        BackupCheckbox(stringResource(R.string.i_have_a_pen_and_paper), hasPenAndPaper) { hasPenAndPaper = it }
                        BackupCheckbox(stringResource(R.string.nobody_can_see_my_screen), isPrivate) { isPrivate = it }
                        BackupCheckbox(stringResource(R.string.i_wont_take_a_photo_or_screenshot), noPhotos) { noPhotos = it }
                    }
                    BackupStep.WORDS -> {
                        val first = wordPage * WORDS_PER_PAGE
                        val end = minOf(first + WORDS_PER_PAGE, words.size)
                        BackupHeading(stringResource(R.string.write_down_these_words), stringResource(R.string.write_them_on_paper_in_this_exact))
                        Text(stringResource(R.string.words_to_of, first + 1, end, words.size), color = KaspaTeal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (index in first until end) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(colors.surface)
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    Text("${index + 1}.", color = colors.textSecondary, fontSize = 15.sp, textAlign = TextAlign.End, modifier = Modifier.width(32.dp))
                                    Text(words[index], color = colors.textPrimary, fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    BackupStep.WORD_CHECK -> {
                        BackupHeading(stringResource(R.string.check_your_paper), stringResource(R.string.tap_each_box_and_type_that_word))
                        for (index in checkIndexes) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(stringResource(R.string.word, index + 1), color = colors.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                val shape = RoundedCornerShape(10.dp)
                                TextField(
                                    value = answers[index].orEmpty(),
                                    onValueChange = {
                                        answers = answers + (index to it)
                                        wrongIndexes = wrongIndexes - index
                                    },
                                    placeholder = { Text(stringResource(R.string.type_word_here, index + 1), color = colors.textSecondary) },
                                    singleLine = true,
                                    // The Password keyboard, so no keyboard learns the words.
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Password,
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
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(shape)
                                        .onFocusChanged { state ->
                                            if (state.isFocused) focusedWord = index else if (focusedWord == index) focusedWord = null
                                        }
                                        .border(
                                            1.5.dp,
                                            when {
                                                index in wrongIndexes -> colors.danger
                                                focusedWord == index -> KaspaTeal
                                                else -> colors.textSecondary.copy(alpha = 0.5f)
                                            },
                                            shape,
                                        ),
                                )
                            }
                        }
                        if (wrongIndexes.isNotEmpty()) {
                            Text(stringResource(R.string.that_doesnt_match_check_your_paper_or), color = colors.danger, fontSize = 13.sp)
                            Text(
                                stringResource(R.string.see_my_words_again),
                                color = KaspaTeal,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                                    wordPage = 0
                                    step = BackupStep.WORDS
                                },
                            )
                        }
                    }
                    BackupStep.PASSPHRASE -> {
                        BackupHeading(
                            stringResource(R.string.write_down_your_passphrase),
                            stringResource(R.string.this_account_also_needs_its_passphrase_your),
                        )
                        Text(
                            passphrase.orEmpty(),
                            color = colors.textPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.surface)
                                .padding(14.dp),
                        )
                    }
                    BackupStep.PASSPHRASE_CHECK -> {
                        BackupHeading(stringResource(R.string.check_your_passphrase), stringResource(R.string.enter_it_from_what_you_wrote_down))
                        BackupPassphraseField(passphraseAnswer) {
                            passphraseAnswer = it
                            passphraseWrong = false
                        }
                        if (passphraseWrong) {
                            Text(stringResource(R.string.that_doesnt_match_check_your_paper_or_2), color = colors.danger, fontSize = 13.sp)
                        }
                    }
                    BackupStep.TIPS -> {
                        BackupHeading(stringResource(R.string.keep_it_safe), stringResource(R.string.your_copy_is_right_now_keep_it))
                        BackupPoint(Icons.Filled.Description, stringResource(R.string.keep_it_on_paper),
                            stringResource(R.string.store_it_somewhere_safe_and_private_away))
                        BackupPoint(Icons.Filled.CloudOff, stringResource(R.string.never_keep_it_online),
                            stringResource(R.string.no_photos_screenshots_cloud_storage_notes_apps))
                        BackupPoint(Icons.Filled.PanTool, stringResource(R.string.never_share_it),
                            stringResource(R.string.nobody_needs_your_words_kachat_and_real))
                        if (passphrase != null) {
                            BackupPoint(Icons.Filled.Splitscreen, stringResource(R.string.keep_your_passphrase_apart),
                                stringResource(R.string.store_it_away_from_your_12_or))
                        }
                        BackupPoint(Icons.Filled.Settings, stringResource(R.string.you_can_see_it_again),
                            stringResource(R.string.your_12_or_24_words_stay_available))
                    }
                }
            }
            Box(Modifier.fillMaxWidth().background(colors.surface).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                when (step) {
                    BackupStep.WHY -> CreateWalletNextButton(stringResource(R.string.start_backup), enabled = true) { step = BackupStep.PREPARE }
                    BackupStep.PREPARE -> CreateWalletNextButton(stringResource(R.string.show_my_12_or_24_words), enabled = hasPenAndPaper && isPrivate && noPhotos) {
                        wordPage = 0
                        step = BackupStep.WORDS
                    }
                    BackupStep.WORDS -> CreateWalletNextButton(
                        if (wordPage + 1 < pageCount) stringResource(R.string.next_words) else stringResource(R.string.i_wrote_them_down),
                        enabled = true,
                    ) {
                        if (wordPage + 1 < pageCount) wordPage++ else startWordCheck()
                    }
                    BackupStep.WORD_CHECK -> CreateWalletNextButton(
                        stringResource(R.string.check_my_words),
                        enabled = checkIndexes.all { answers[it].orEmpty().isNotBlank() },
                    ) {
                        wrongIndexes = checkIndexes.filter { answers[it].orEmpty().trim().lowercase() != words[it] }.toSet()
                        if (wrongIndexes.isEmpty()) {
                            step = if (passphrase != null) BackupStep.PASSPHRASE else BackupStep.TIPS
                        } else {
                            haptic(IosHaptic.ERROR)
                        }
                    }
                    BackupStep.PASSPHRASE -> CreateWalletNextButton(stringResource(R.string.i_wrote_it_down), enabled = true) {
                        passphraseAnswer = ""
                        passphraseWrong = false
                        step = BackupStep.PASSPHRASE_CHECK
                    }
                    BackupStep.PASSPHRASE_CHECK -> CreateWalletNextButton(stringResource(R.string.check_my_passphrase), enabled = passphraseAnswer.isNotEmpty()) {
                        if (passphraseAnswer == passphrase) {
                            step = BackupStep.TIPS
                        } else {
                            passphraseWrong = true
                            haptic(IosHaptic.ERROR)
                        }
                    }
                    BackupStep.TIPS -> CreateWalletNextButton(stringResource(R.string.finish_backup), enabled = true) {
                        viewModel.markAccountBackupConfirmed()
                        haptic(IosHaptic.SUCCESS)
                        onClose()
                    }
                }
            }
        }
    }
}

@Composable
private fun BackupHeading(title: String, detail: String? = null) {
    val colors = LocalAppColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = colors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (detail != null) Text(detail, color = colors.textSecondary, fontSize = 15.sp)
    }
}

@Composable
private fun BackupPoint(icon: ImageVector, title: String, detail: String) {
    val colors = LocalAppColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(24.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = colors.textSecondary, fontSize = 15.sp)
        }
    }
}

@Composable
private fun BackupCheckbox(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (checked) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
            contentDescription = null,
            tint = if (checked) KaspaTeal else colors.textSecondary,
            modifier = Modifier.size(24.dp),
        )
        Text(title, color = colors.textPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
    }
}

/**
 * Checks the written backup still works: five words of the seed phrase, asked in random order on
 * the app's own keyboard, then the passphrase if the account has one, since the words alone
 * wouldn't bring it back. Can't Complete This goes through the backup steps again. Either one
 * starts the six months over; closing leaves the card up.
 */
@Composable
fun BackupHygieneScreen(viewModel: WalletViewModel, onClose: () -> Unit) {
    val colors = LocalAppColors.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptic = rememberHaptics()
    val biometricEnabled by viewModel.biometricSeedPhraseEnabled.collectAsState()
    val seedWords = remember { viewModel.getActiveMnemonic()?.split(" ")?.filter { it.isNotBlank() }.orEmpty() }
    val passphrase = remember { viewModel.getActivePassphrase() }
    // Which words are asked (positions in the phrase), in the order they're asked.
    val asked = remember { seedWords.indices.shuffled().take(HYGIENE_WORDS_TO_CHECK) }
    val wordList = remember { viewModel.bip39Words }
    var answers by remember { mutableStateOf(List(asked.size) { "" }) }
    var activeSlot by remember { mutableIntStateOf(0) }
    var wrong by remember { mutableStateOf(false) }
    var checkingPassphrase by remember { mutableStateOf(false) }
    var passphraseAnswer by remember { mutableStateOf("") }
    var passed by remember { mutableStateOf(false) }
    var redoingBackup by remember { mutableStateOf(false) }

    if (redoingBackup) {
        AccountBackupScreen(viewModel, onClose)
        return
    }
    BackHandler(onBack = onClose)
    if (!passed) SecureWindowWhileShown()

    fun finish() {
        viewModel.markAccountBackupConfirmed()
        haptic(IosHaptic.SUCCESS)
        passed = true
    }

    @Composable
    fun cantComplete() {
        Text(
            stringResource(R.string.cant_complete_this),
            color = KaspaTeal,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable { unlockAccountBackup(context, biometricEnabled) { redoingBackup = true } }
                .padding(vertical = 4.dp),
        )
    }

    BackupScaffold(title = stringResource(R.string.backup_hygiene), onClose = onClose) { padding ->
        when {
            passed -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(Modifier.weight(1f))
                Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(56.dp))
                Text(stringResource(R.string.your_backup_is_good), color = colors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.well_check_again_in_6_months), color = colors.textSecondary, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                CreateWalletNextButton(stringResource(R.string.done), enabled = true, onClick = onClose)
            }
            checkingPassphrase -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.now_enter_your_passphrase), color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.your_12_or_24_words_alone_wont),
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                )
                BackupPassphraseField(passphraseAnswer) {
                    passphraseAnswer = it
                    wrong = false
                }
                if (wrong) Text(stringResource(R.string.that_doesnt_match_check_your_paper_and), color = colors.danger, fontSize = 13.sp)
                CreateWalletNextButton(stringResource(R.string.check_my_passphrase), enabled = passphraseAnswer.isNotEmpty()) {
                    if (passphraseAnswer == passphrase) {
                        finish()
                    } else {
                        wrong = true
                        haptic(IosHaptic.ERROR)
                    }
                }
                cantComplete()
            }
            else -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.lets_ensure_your_account_is_backed_up),
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.enter_these_of_your_12_or_24, asked.size),
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                )
                SeedPhraseKeyboard(
                    wordCount = asked.size,
                    words = answers,
                    activeSlot = activeSlot,
                    wordList = wordList,
                    modifier = Modifier.weight(1f),
                    slotNumbers = asked.map { it + 1 },
                    onWordsChange = {
                        answers = it
                        wrong = false
                    },
                    onActiveSlotChange = { activeSlot = it },
                )
                if (wrong) Text(stringResource(R.string.some_of_those_words_dont_match_check), color = colors.danger, fontSize = 13.sp)
                CreateWalletNextButton(
                    stringResource(R.string.check_my_words),
                    enabled = asked.isNotEmpty() && answers.all { it.lowercase() in wordList },
                ) {
                    if (asked.indices.all { seedWords[asked[it]] == answers[it].lowercase() }) {
                        wrong = false
                        if (passphrase != null) checkingPassphrase = true else finish()
                    } else {
                        wrong = true
                        haptic(IosHaptic.ERROR)
                    }
                }
                cantComplete()
            }
        }
    }
}
