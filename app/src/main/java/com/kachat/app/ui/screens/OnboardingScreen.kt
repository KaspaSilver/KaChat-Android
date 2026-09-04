package com.kachat.app.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kachat.app.R
import com.kachat.app.models.SourceWalletOption
import com.kachat.app.models.WalletSourceFamily
import com.kachat.app.services.WalletManager
import com.kachat.app.ui.theme.KaspaSubtext
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.DarkAppColors
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.authenticateWithDeviceCredential
import com.kachat.app.viewmodels.WalletViewModel

/**
 * Main entry point for the onboarding flow.
 */
@Composable
fun OnboardingScreen(viewModel: WalletViewModel) {
    val navController = rememberNavController()
    val generatedMnemonic by viewModel.onMnemonicGenerated.collectAsState()

    LaunchedEffect(generatedMnemonic) {
        if (generatedMnemonic != null) {
            navController.navigate("backup_mnemonic/$generatedMnemonic")
        }
    }

    NavHost(navController = navController, startDestination = "welcome") {
        composable("welcome") {
            WelcomeScreen(
                viewModel,
                onNavigateToCreate = { navController.navigate("create_account") },
                onOpenAppSettings = { navController.navigate("app_settings") },
                onNavigateToImport = {
                    // A stale SUCCESS/FAILED status left over from a previous import would
                    // otherwise fire ImportWalletScreen's success LaunchedEffect immediately on
                    // entry (this state lives in the singleton WalletViewModel, not the screen),
                    // silently logging into whatever account is currently active instead of
                    // letting the user type a new phrase.
                    viewModel.resetImportWalletState()
                    // KasWare-style source-wallet chooser comes FIRST, before seed entry — the
                    // choice sets the identity derivation family the seed is read with.
                    navController.navigate("import_source")
                }
            )
        }
        composable("create_account") {
            CreateAccountScreen(viewModel, onBack = { navController.popBackStack() })
        }
        composable("import_source") {
            ImportSourceWalletScreen(
                onBack = { navController.popBackStack() },
                onContinue = { family ->
                    viewModel.setPendingSourceFamily(family)
                    navController.navigate("import_wallet")
                }
            )
        }
        composable("import_wallet") {
            ImportWalletScreen(
                viewModel,
                onBack = { navController.popBackStack() },
                // Seed validated + stashed by prepareImport(); the actual import happens on the
                // passphrase screen (which arms the Welcome Guide + logs in on success).
                onProceed = { navController.navigate("passphrase_import") }
            )
        }
        composable("backup_mnemonic/{words}") { backStackEntry ->
            val words = backStackEntry.arguments?.getString("words") ?: ""
            BackupMnemonicScreen(
                mnemonic = words,
                // Wallet isn't committed yet — go collect the optional passphrase, then commit.
                onComplete = { navController.navigate("passphrase_create") }
            )
        }
        composable("passphrase_create") {
            PassphraseSetupScreen(
                mode = PassphraseMode.CREATE,
                onBack = { navController.popBackStack() },
                previewAddress = { viewModel.previewChattingAddress(it) },
                // commitCreatedWallet() derives + saves with the passphrase, arms the guide, and
                // logs in (which swaps onboarding for the main shell).
                onProceed = { passphrase -> viewModel.commitCreatedWallet(passphrase) }
            )
        }
        composable("passphrase_import") {
            val importState by viewModel.importWalletState.collectAsState()
            PassphraseSetupScreen(
                mode = PassphraseMode.IMPORT,
                isBusy = importState.status == WalletViewModel.ImportWalletStatus.IMPORTING,
                errorMessage = importState.errorMessage.takeIf {
                    importState.status == WalletViewModel.ImportWalletStatus.FAILED
                },
                onDismissError = { viewModel.resetImportWalletState() },
                onBack = { navController.popBackStack() },
                previewAddress = { viewModel.previewChattingAddress(it) },
                // commitImport() imports with the passphrase, arms the guide, and logs in on success.
                onProceed = { passphrase -> viewModel.commitImport(passphrase) }
            )
        }

        // App Settings (the gear on the accounts screen, matching iOS's gearshape sheet on
        // OnboardingView): ONLY the app-wide settings tier - Customization/Security/Connection/
        // Diagnostics - every leaf reusing the exact screens the in-account Settings uses.
        composable("app_settings") {
            AppSettingsHubScreen(
                onBack = { navController.popBackStack() },
                onOpenCustomization = { navController.navigate("app_settings_customization") },
                onOpenSecurity = { navController.navigate("app_settings_security") },
                onOpenConnection = { navController.navigate("app_settings_connection") },
                onOpenDiagnostics = { navController.navigate("app_settings_diagnostics") }
            )
        }
        composable("app_settings_customization") {
            AppCustomizationScreen(
                onBack = { navController.popBackStack() },
                onNavigateToLanguage = { navController.navigate("app_settings_language") },
                onNavigateToCurrency = { navController.navigate("app_settings_currency") },
                walletViewModel = viewModel
            )
        }
        composable("app_settings_language") {
            LanguageSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable("app_settings_currency") {
            CurrencySettingsScreen(onBack = { navController.popBackStack() }, walletViewModel = viewModel)
        }
        composable("app_settings_security") {
            AppSecurityScreen(
                onBack = { navController.popBackStack() },
                onNavigateToChildMode = { navController.navigate("app_settings_child_mode") },
                walletViewModel = viewModel
            )
        }
        composable("app_settings_child_mode") {
            // Fully functional with no account active - the password record and enabled flag
            // are device-global (ChildModeService's EncryptedSharedPreferences + DataStore).
            ChildModeSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable("app_settings_connection") {
            ConnectionSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable("app_settings_diagnostics") {
            AppDiagnosticsScreen(onBack = { navController.popBackStack() })
        }
    }
}

@Composable
fun WelcomeScreen(
    viewModel: WalletViewModel,
    onNavigateToCreate: () -> Unit,
    onNavigateToImport: () -> Unit,
    onOpenAppSettings: () -> Unit = {}
) {
    // The background is dark in both themes, so the colors drawn on top of it have to be the
    // dark set too - otherwise textPrimary renders near-black on near-black in light mode.
    // Scoped to this screen.
    CompositionLocalProvider(LocalAppColors provides DarkAppColors) {
    Surface(
        color = KaChatSignInBackground,
        modifier = Modifier.fillMaxSize()
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
        // Top-right gear (matches iOS OnboardingView's gearshape toolbar button): app-wide
        // settings that make sense with no account active - see AppSettingsHubScreen.
        IconButton(
            onClick = onOpenAppSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 8.dp, end = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = stringResource(R.string.app_settings),
                tint = LocalAppColors.current.textSecondary
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top spacer to help center the middle content
            Spacer(modifier = Modifier.weight(1f))

            val hasWallet by viewModel.hasWallet.collectAsState()
            val accounts by viewModel.accounts.collectAsState()

            // Wordmark with the app mark beside it - the launcher icon itself, so the two never
            // drift apart. Replaces the 160dp logo above the title: on a device with accounts
            // saved, that block was most of why the list below had nowhere to go.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.kachat),
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 36.sp
                    ),
                    color = LocalAppColors.current.textPrimary
                )
                Image(
                    // A PNG copy of the launcher artwork, NOT R.mipmap.ic_launcher: on API 26+
                    // that id resolves to the adaptive-icon XML, which painterResource cannot
                    // load - it throws "Only VectorDrawables and rasterized asset types are
                    // supported" and takes the whole welcome screen down with it.
                    painter = painterResource(id = R.drawable.kachat_app_mark),
                    contentDescription = stringResource(R.string.kachat_logo),
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(9.dp))
                )
            }

            // A welcome for a first run only - on a returning device it is more of the space the
            // accounts list needs.
            if (!hasWallet) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.secure_messaging_on_kaspa_blockdag),
                    style = MaterialTheme.typography.bodyLarge,
                    color = KaspaSubtext,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            val biometricAccountLoginEnabled by viewModel.biometricAccountLoginEnabled.collectAsState()

            if (hasWallet) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = stringResource(R.string.saved_accounts),
                        color = LocalAppColors.current.textPrimary,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    // Four at a time, then this scrolls inside itself - the fifth account is
                    // where scrolling starts. A dozen saved accounts used to just make the page
                    // taller, pushing Create and Import off the bottom of the screen.
                    // heightIn(max) rather than a fixed height so three accounts still take
                    // three rows' worth of space, not four.
                    val visibleRows = minOf(accounts.size, 4)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = (visibleRows * 84).dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        accounts.forEach { account ->
                            SavedAccountCard(
                                account = account,
                                requireBiometricLogin = biometricAccountLoginEnabled,
                                onLogin = { viewModel.login(account.address) },
                                onRename = { newName -> viewModel.renameAccount(account.address, newName) },
                                onDelete = { viewModel.deleteWallet(account.address) }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }
            }

            // Create new wallet button
            Button(
                onClick = onNavigateToCreate,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = KaspaTeal,
                    contentColor = Color.Black // iOS uses black text on teal
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AddCircleOutline,
                        contentDescription = null,
                        tint = Color.Black
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.create_new_account),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.Black
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Import existing wallet button
            Button(
                onClick = onNavigateToImport,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LocalAppColors.current.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = null,
                        tint = LocalAppColors.current.textPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.import_existing_account),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = LocalAppColors.current.textPrimary
                    )
                }
            }
        }
        }
    }
}
}

@Composable
fun SavedAccountCard(
    account: WalletManager.Account,
    requireBiometricLogin: Boolean = false,
    onLogin: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf(account.name) }
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(KaChatSignInCard)
            .clickable {
                if (requireBiometricLogin) {
                    context.authenticateWithDeviceCredential(
                        title = "Unlock ${account.name}",
                        onSuccess = onLogin
                    )
                } else {
                    onLogin()
                }
            }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(Color(0xFF2C3E50), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = KaspaTeal,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account.name,
                color = LocalAppColors.current.textPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = account.address,
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(onClick = { showMenu = true }) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = stringResource(R.string.edit_account),
                tint = KaspaTeal,
                modifier = Modifier.size(20.dp)
            )
        }
    }

    // Half sheet rather than a dropdown, like every other chooser in the app - and Delete gets a
    // line saying what it takes with it, which a menu of bare verbs cannot.
    if (showMenu) {
        ActionSheetContainer(
            title = account.name,
            subtitle = account.address,
            onDismiss = { showMenu = false },
        ) {
            ActionSheetRow(
                icon = Icons.Default.Edit,
                title = stringResource(R.string.rename),
                subtitle = "Gives this account a name of your own.",
            ) {
                showMenu = false
                nameInput = account.name
                showRenameDialog = true
            }
            ActionSheetRow(
                icon = Icons.Default.Delete,
                title = stringResource(R.string.delete),
                subtitle = "Removes this account and its local data from this device.",
                tint = Color(0xFFFF3B30),
            ) {
                showMenu = false
                showDeleteConfirm = true
            }
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.rename_account), color = LocalAppColors.current.textPrimary) },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = LocalAppColors.current.textPrimary,
                        unfocusedTextColor = LocalAppColors.current.textPrimary,
                        focusedBorderColor = KaspaTeal,
                        unfocusedBorderColor = LocalAppColors.current.textSecondary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = nameInput.isNotBlank(),
                    onClick = {
                        onRename(nameInput)
                        showRenameDialog = false
                    }
                ) {
                    Text(stringResource(R.string.save), color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }

    if (showDeleteConfirm) {
        ConfirmActionSheet(
            title = stringResource(R.string.delete_account),
            confirmTitle = stringResource(R.string.delete),
            confirmSubtitle = "Removes \"${account.name}\" from this device. Without its seed phrase written down, any remaining balance is unrecoverable.",
            confirmIcon = Icons.Default.Delete,
            onConfirm = onDelete,
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateAccountScreen(viewModel: WalletViewModel, onBack: () -> Unit) {
    var accountName by remember { mutableStateOf("My Account") }
    var wordCount by remember { mutableIntStateOf(24) }

    Surface(
        color = LocalAppColors.current.background,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                // Without this, the "Account Name" field and "Generate Account" button below it
                // (this Column has no scroll of its own — it relies on a weight(1f) spacer to push
                // the button to the bottom) can end up rendered behind the keyboard on devices
                // where edge-to-edge means windowSoftInputMode="adjustResize" alone doesn't shrink
                // the window — Compose has to react to the IME inset itself.
                .imePadding()
                .verticalScroll(rememberScrollState())
        ) {
            // Back button
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(40.dp)
                    .background(LocalAppColors.current.surface, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = LocalAppColors.current.textPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.create_account),
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = LocalAppColors.current.textPrimary
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Important notice box
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(LocalAppColors.current.surface)
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color(0xFFF39C12),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = stringResource(R.string.important),
                        color = Color(0xFFF39C12),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.you_will_be_shown_a_seed),
                        color = LocalAppColors.current.textSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = stringResource(R.string.seed_phrase_length),
                color = LocalAppColors.current.textPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Segmented control for word count
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                SegmentedButton(
                    selected = wordCount == 24,
                    onClick = { wordCount = 24 },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = LocalAppColors.current.surfaceVariant,
                        activeContentColor = LocalAppColors.current.textPrimary,
                        inactiveContainerColor = LocalAppColors.current.surface,
                        inactiveContentColor = LocalAppColors.current.textSecondary
                    )
                ) {
                    Text(stringResource(R.string.n_24_words_recommended), fontSize = 12.sp)
                }
                SegmentedButton(
                    selected = wordCount == 12,
                    onClick = { wordCount = 12 },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = LocalAppColors.current.surfaceVariant,
                        activeContentColor = LocalAppColors.current.textPrimary,
                        inactiveContainerColor = LocalAppColors.current.surface,
                        inactiveContentColor = LocalAppColors.current.textSecondary
                    )
                ) {
                    Text(stringResource(R.string.n_12_words), fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = stringResource(R.string.account_name),
                color = LocalAppColors.current.textPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Text field for account name
            TextField(
                value = accountName,
                onValueChange = { accountName = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = LocalAppColors.current.surface,
                    unfocusedContainerColor = LocalAppColors.current.surface,
                    focusedTextColor = LocalAppColors.current.textPrimary,
                    unfocusedTextColor = LocalAppColors.current.textPrimary,
                    cursorColor = KaspaTeal,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                singleLine = true
            )

            // A fixed gap, not weight(1f) — a scrollable Column (added above for imePadding to
            // actually help) can't host a weight()'d child, since scrolling gives it unbounded
            // height to measure against.
            Spacer(modifier = Modifier.height(32.dp))

            // Generate button
            Button(
                onClick = { viewModel.createWallet(accountName, wordCount) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AddCircle,
                        contentDescription = null,
                        tint = Color.Black
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.generate_account),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.Black
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportWalletScreen(viewModel: WalletViewModel, onBack: () -> Unit, onProceed: () -> Unit) {
    var accountName by remember { mutableStateOf("Imported Account") }
    var wordCount by remember { mutableIntStateOf(24) }
    // Fixed-capacity backing store; only the first [wordCount] entries are used.
    var words by remember { mutableStateOf(List(24) { "" }) }
    var activeSlot by remember { mutableIntStateOf(0) }
    val importState by viewModel.importWalletState.collectAsState()
    val wordList = remember { viewModel.bip39Words }

    val slots = words.take(wordCount)
    val filled = slots.count { it.isNotEmpty() && wordList.contains(it.lowercase()) }
    val allValid = slots.size == wordCount && slots.all { it.isNotEmpty() && wordList.contains(it.lowercase()) }
    val canImport = allValid && accountName.isNotBlank()

    // The typed-in phrase is fully visible in the slot grid — exactly as sensitive as the reveal
    // screens, so it gets the same screenshot/recording block. The custom keyboard already keeps
    // it away from the OS keyboard and clipboard; this closes the screen-capture side. Cleared
    // on dispose like BackupMnemonicScreen/SeedPhraseScreen's identical guards.
    val window = (LocalContext.current as? Activity)?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    Surface(color = LocalAppColors.current.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .imePadding()
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(40.dp)
                    .background(LocalAppColors.current.surface, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = LocalAppColors.current.textPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.import_account),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = LocalAppColors.current.textPrimary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Account name — the only field that uses the OS keyboard; it isn't sensitive.
            Text(
                text = stringResource(R.string.account_name),
                color = LocalAppColors.current.textPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextField(
                value = accountName,
                onValueChange = { accountName = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = LocalAppColors.current.surface,
                    unfocusedContainerColor = LocalAppColors.current.surface,
                    focusedTextColor = LocalAppColors.current.textPrimary,
                    unfocusedTextColor = LocalAppColors.current.textPrimary,
                    cursorColor = KaspaTeal,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Word-count selector
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = wordCount == 24,
                    onClick = { wordCount = 24 },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = LocalAppColors.current.surfaceVariant,
                        activeContentColor = LocalAppColors.current.textPrimary,
                        inactiveContainerColor = LocalAppColors.current.surface,
                        inactiveContentColor = LocalAppColors.current.textSecondary
                    )
                ) { Text(stringResource(R.string.n_24_words_recommended), fontSize = 12.sp) }
                SegmentedButton(
                    selected = wordCount == 12,
                    onClick = { wordCount = 12; if (activeSlot >= 12) activeSlot = 11 },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = LocalAppColors.current.surfaceVariant,
                        activeContentColor = LocalAppColors.current.textPrimary,
                        inactiveContainerColor = LocalAppColors.current.surface,
                        inactiveContentColor = LocalAppColors.current.textSecondary
                    )
                ) { Text(stringResource(R.string.n_12_words), fontSize = 12.sp) }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.seed_phrase),
                    color = LocalAppColors.current.textSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "$filled/$wordCount",
                    color = if (allValid) Color(0xFF4CD964) else LocalAppColors.current.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Custom in-app keyboard + numbered slot grid + autocomplete (no OS keyboard, no paste)
            SeedPhraseKeyboard(
                wordCount = wordCount,
                words = words,
                activeSlot = activeSlot,
                wordList = wordList,
                modifier = Modifier.weight(1f),
                onWordsChange = { words = it },
                onActiveSlotChange = { activeSlot = it }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { if (viewModel.prepareImport(accountName, slots)) onProceed() },
                enabled = canImport,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, disabledContainerColor = LocalAppColors.current.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.import_account),
                    color = if (canImport) Color.Black else Color.Gray,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }

    if (importState.status == WalletViewModel.ImportWalletStatus.FAILED) {
        AlertDialog(
            onDismissRequest = { viewModel.resetImportWalletState() },
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.error), color = LocalAppColors.current.textPrimary) },
            text = { Text(importState.errorMessage ?: "Something went wrong", color = LocalAppColors.current.textSecondary) },
            confirmButton = {
                TextButton(onClick = { viewModel.resetImportWalletState() }) {
                    Text(stringResource(R.string.ok), color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BackupMnemonicScreen(mnemonic: String, onComplete: () -> Unit) {
    val words = remember { mnemonic.split(" ") }
    var hasConfirmedBackup by remember { mutableStateOf(false) }
    // Hidden until tapped, matching iOS. The words appear the moment this screen opens otherwise,
    // which is the one moment the user has no say in who is looking at the phone.
    var showSeedPhrase by remember { mutableStateOf(false) }

    // Blocks screenshots and screen recording of the freshly-generated seed phrase for as long
    // as this screen is on-screen - see SeedPhraseScreen's identical guard in Screens.kt.
    val window = (LocalContext.current as? Activity)?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    Surface(
        color = LocalAppColors.current.background,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.width(40.dp))
                Text(stringResource(R.string.seed_phrase), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onComplete, enabled = hasConfirmedBackup) {
                    Text(
                        stringResource(R.string.done),
                        color = if (hasConfirmedBackup) KaspaTeal else LocalAppColors.current.textSecondary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Security Warning
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF2C1E1E))
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color(0xFFF39C12),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = stringResource(R.string.security_warning),
                        color = Color(0xFFF39C12),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.anyone_with_your_seed_phrase_can),
                        color = Color(0xFF948B8B),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Words Grid, behind a tap
            if (showSeedPhrase) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = 3
                ) {
                    words.forEachIndexed { index, word ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(LocalAppColors.current.surface)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${index + 1}",
                                color = LocalAppColors.current.textSecondary,
                                fontSize = 12.sp,
                                modifier = Modifier.width(20.dp)
                            )
                            Text(
                                text = word,
                                color = LocalAppColors.current.textPrimary,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(LocalAppColors.current.surface)
                        .clickable { showSeedPhrase = true }
                        .padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Default.VisibilityOff,
                        contentDescription = null,
                        tint = LocalAppColors.current.textSecondary,
                        modifier = Modifier.size(34.dp),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Tap to reveal seed phrase",
                        color = LocalAppColors.current.textSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { hasConfirmedBackup = !hasConfirmedBackup }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = hasConfirmedBackup,
                    onCheckedChange = { hasConfirmedBackup = it },
                    colors = CheckboxDefaults.colors(checkedColor = KaspaTeal, checkmarkColor = Color.Black)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.i_have_written_down_my_seed_phrase),
                    color = LocalAppColors.current.textPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onComplete,
                enabled = hasConfirmedBackup,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, disabledContainerColor = LocalAppColors.current.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.i_ve_backed_it_up),
                    color = if (hasConfirmedBackup) Color.Black else LocalAppColors.current.textSecondary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

enum class PassphraseMode { CREATE, IMPORT }

/**
 * Optional BIP39 passphrase ("25th word") step, shown after seed backup (create) or seed entry
 * (import) and before the Welcome Guide. Explains what a passphrase does, how it helps, and the
 * risk of forgetting it, then lets the user proceed with a passphrase or skip. The caller performs
 * the actual create/import commit via [onProceed], which receives the chosen passphrase ("" when
 * skipped). Copy is English-only for now — a follow-up can move these literals into strings.xml.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassphraseSetupScreen(
    mode: PassphraseMode,
    isBusy: Boolean = false,
    errorMessage: String? = null,
    onDismissError: () -> Unit = {},
    onBack: () -> Unit,
    /** Address #0 for a candidate passphrase, for the live preview on the entry step. Null when
     *  the caller has no words to derive from. */
    previewAddress: (String) -> String? = { null },
    onProceed: (String) -> Unit
) {
    var step by remember { mutableStateOf(PassphraseStep.QUESTION) }
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val colors = LocalAppColors.current

    fun submitFromEntry() {
        if (isBusy) return
        when {
            passphrase.isBlank() ->
                localError = "Enter a passphrase, or go back and choose No."
            mode == PassphraseMode.CREATE && passphrase != confirm ->
                localError = "The passphrases do not match. Please re-enter them."
            else -> onProceed(passphrase)
        }
    }

    // Back always lands one step nearer the question, and only leaves the flow from the question
    // itself - reading the explainer must return to the choice, never skip past it.
    BackHandler(enabled = step != PassphraseStep.QUESTION) {
        passphrase = ""
        confirm = ""
        step = PassphraseStep.QUESTION
    }

    Surface(color = colors.background, modifier = Modifier.fillMaxSize()) {
        when (step) {
            PassphraseStep.QUESTION -> PassphraseQuestionStep(
                mode = mode,
                isBusy = isBusy,
                onYes = { step = PassphraseStep.ENTRY },
                onNo = { if (!isBusy) onProceed("") },
                onExplain = { step = PassphraseStep.EXPLAINER },
                onBack = onBack,
            )
            PassphraseStep.ENTRY -> PassphraseEntryStep(
                mode = mode,
                isBusy = isBusy,
                passphrase = passphrase,
                onPassphraseChange = { passphrase = it },
                confirm = confirm,
                onConfirmChange = { confirm = it },
                reveal = reveal,
                onToggleReveal = { reveal = !reveal },
                previewAddress = previewAddress,
                onSubmit = { submitFromEntry() },
                onBack = { passphrase = ""; confirm = ""; step = PassphraseStep.QUESTION },
            )
            PassphraseStep.EXPLAINER -> PassphraseExplainerStep(
                mode = mode,
                onBack = { step = PassphraseStep.QUESTION },
            )
        }
    }

    val shownError = localError ?: errorMessage
    if (shownError != null) {
        AlertDialog(
            onDismissRequest = { localError = null; onDismissError() },
            containerColor = colors.surface,
            title = { Text("Passphrase", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
            text = { Text(shownError, color = colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = { localError = null; onDismissError() }) {
                    Text(stringResource(R.string.ok), color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

private enum class PassphraseStep { QUESTION, ENTRY, EXPLAINER }

/** The question, alone on its own screen, with a way to go and read about it first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PassphraseQuestionStep(
    mode: PassphraseMode,
    isBusy: Boolean,
    onYes: () -> Unit,
    onNo: () -> Unit,
    onExplain: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalAppColors.current
    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Passphrase", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !isBusy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = KaspaTeal,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                if (mode == PassphraseMode.CREATE) "Do you want to add a passphrase to your account?"
                else "Did you create this seed with a passphrase?",
                color = colors.textPrimary,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center,
            )
            // Import keeps a line of steer, because someone who has never heard of a passphrase
            // still has to answer a question about their own past. Create needs none: the choice
            // is theirs to make, and "What is a passphrase?" is right there if they want it.
            if (mode == PassphraseMode.IMPORT) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "If you are not sure, the answer is almost certainly no. A passphrase is something you would have typed in on purpose.",
                    color = colors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.weight(1f))

            Button(
                onClick = onYes,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, contentColor = Color.Black),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Yes", fontWeight = FontWeight.Bold, fontSize = 16.sp) }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onNo,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = KaspaTeal.copy(alpha = 0.15f),
                    contentColor = KaspaTeal,
                ),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (isBusy) {
                    CircularProgressIndicator(color = KaspaTeal, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else {
                    Text("No", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }

            Spacer(Modifier.height(8.dp))

            TextButton(onClick = onExplain, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
                Text("What is a passphrase?", color = colors.textSecondary, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * The field, with the chatting address it produces underneath.
 *
 * That live address is the whole point of this screen: a passphrase does not protect one account,
 * it opens a different one. Watching #0 change with every character is what makes that concrete,
 * and on import it is how someone confirms they typed the right thing before committing to an
 * account that would otherwise just look empty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PassphraseEntryStep(
    mode: PassphraseMode,
    isBusy: Boolean,
    passphrase: String,
    onPassphraseChange: (String) -> Unit,
    confirm: String,
    onConfirmChange: (String) -> Unit,
    reveal: Boolean,
    onToggleReveal: () -> Unit,
    previewAddress: (String) -> String?,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalAppColors.current
    // Debounced: the derivation is PBKDF2 over 2048 rounds, quick but not free, and running it on
    // every keystroke would be felt.
    var shownAddress by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(passphrase) {
        kotlinx.coroutines.delay(250)
        shownAddress = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { previewAddress(passphrase) }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Passphrase", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !isBusy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Text(
                if (mode == PassphraseMode.CREATE) "Choose your passphrase" else "Enter your passphrase",
                color = colors.textPrimary,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (mode == PassphraseMode.CREATE)
                    "Write it down somewhere safe. Without it this account cannot be recovered, even with your seed phrase."
                else
                    "It has to be exactly what you used before, including capital letters and spaces.",
                color = colors.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(20.dp))

            PassphraseInputField(
                value = passphrase,
                onValueChange = onPassphraseChange,
                reveal = reveal,
                onToggleReveal = onToggleReveal,
                placeholder = "Passphrase",
            )

            if (mode == PassphraseMode.CREATE) {
                Spacer(Modifier.height(12.dp))
                PassphraseInputField(
                    value = confirm,
                    onValueChange = onConfirmChange,
                    reveal = reveal,
                    onToggleReveal = onToggleReveal,
                    placeholder = "Re-enter passphrase",
                )
            }

            Spacer(Modifier.height(20.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surface)
                    .padding(14.dp),
            ) {
                Text("Your chatting address", color = colors.textSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    shownAddress ?: "Checking...",
                    color = if (shownAddress == null) colors.textSecondary else colors.textPrimary,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (passphrase.isEmpty())
                        "This is the account your seed phrase opens on its own."
                    else
                        "A different passphrase gives a different address, and a different account.",
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = onSubmit,
                enabled = passphrase.isNotBlank() && !isBusy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, contentColor = Color.Black),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (isBusy) {
                    CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else {
                    Text(
                        if (mode == PassphraseMode.CREATE) "Continue" else "Import",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                }
            }
        }
    }
}

/** Plain-language explainer. Back only, so reading about it always returns to the choice. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PassphraseExplainerStep(mode: PassphraseMode, onBack: () -> Unit) {
    val colors = LocalAppColors.current
    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("What is a passphrase?", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            PassphraseExplainerSection(
                "The short version",
                "A passphrase is an extra word or sentence you add on top of your seed phrase. It is optional, and most people do not use one."
            )
            PassphraseExplainerSection(
                "It does not lock your account",
                "This is the part people get wrong. A passphrase does not put a password on your account. It opens a completely different account. Your seed phrase with no passphrase opens one account. The same seed phrase with the word \"apple\" opens another one. With \"banana\", another one again. Every passphrase is its own separate account, with its own address and its own balance."
            )
            PassphraseExplainerSection(
                "Why anyone bothers",
                "If someone finds your written seed phrase, they get the account it opens on its own. They do not get the one behind your passphrase, because they do not know there is one, and they could not guess it anyway."
            )
            PassphraseExplainerSection(
                "The catch",
                "There is no reset and no recovery. If you forget your passphrase, the account it opened is gone for good. Your seed phrase alone will not bring it back, and nobody can help you. Treat it exactly like the seed phrase itself: written down, somewhere safe, before you rely on it."
            )
            PassphraseExplainerSection(
                "One more thing to know",
                "If you type the wrong passphrase, nothing will tell you. You will simply land in a different account, and it will look empty. That is not a bug and your money is not lost, it just means you are in the wrong account."
            )
            PassphraseExplainerSection(
                "So do you need one?",
                if (mode == PassphraseMode.CREATE)
                    "If you are not sure, choose No. Your account is still protected by your seed phrase, and you can always create another account with a passphrase later."
                else
                    "If you never set one up, choose No. A passphrase is something you would have typed in on purpose, so if this is the first you are hearing of it, you do not have one."
            )
        }
    }
}

@Composable
private fun PassphraseExplainerSection(title: String, body: String) {
    val colors = LocalAppColors.current
    Column {
        Text(title, color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        Text(body, color = colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PassphraseInputField(
    value: String,
    onValueChange: (String) -> Unit,
    reveal: Boolean,
    onToggleReveal: () -> Unit,
    placeholder: String
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
        placeholder = { Text(placeholder, color = LocalAppColors.current.textSecondary) },
        singleLine = true,
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrect = false),
        trailingIcon = {
            TextButton(onClick = onToggleReveal) {
                Text(if (reveal) "Hide" else "Show", color = KaspaTeal, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            }
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = LocalAppColors.current.surface,
            unfocusedContainerColor = LocalAppColors.current.surface,
            focusedTextColor = LocalAppColors.current.textPrimary,
            unfocusedTextColor = LocalAppColors.current.textPrimary,
            cursorColor = KaspaTeal,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        )
    )
}

/**
 * KasWare-style "which wallet is this seed from?" chooser, shown FIRST when the user taps Import
 * Existing Account (before seed entry, which then continues exactly as before). Ported from iOS's
 * `ImportSourceWalletView`. The selection maps a wallet name to its identity derivation-path
 * family ([WalletSourceFamily], rules replicated from KasWare's RESTORE_WALLETS/ADDRESS_TYPES +
 * hd-keyring derivation), so KaChat derives the chatting identity where that wallet actually kept
 * the user's funds and KNS domains. KaChat is preselected at the top; KaChat's own spending chain
 * always stays on the m/44'/111111'/1' branch regardless of this choice (see WalletManager's
 * decision comment).
 */
@Composable
fun ImportSourceWalletScreen(onBack: () -> Unit, onContinue: (WalletSourceFamily) -> Unit) {
    val options = remember { SourceWalletOption.ALL }
    var selectedName by remember { mutableStateOf(options.first().displayName) }
    val selectedFamily = options.firstOrNull { it.displayName == selectedName }?.family
        ?: WalletSourceFamily.KASPA_STANDARD

    Surface(color = LocalAppColors.current.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(40.dp)
                    .background(LocalAppColors.current.surface, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = LocalAppColors.current.textPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.height(16.dp))
            Icon(
                Icons.Default.AccountBalanceWallet,
                contentDescription = null,
                tint = KaspaTeal,
                modifier = Modifier
                    .size(48.dp)
                    .align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.where_is_this_seed_phrase_from),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = LocalAppColors.current.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.different_wallets_store_your_kaspa),
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                options.forEach { option ->
                    val isSelected = option.displayName == selectedName
                    Surface(
                        color = LocalAppColors.current.surface,
                        shape = RoundedCornerShape(12.dp),
                        border = if (isSelected) BorderStroke(1.5.dp, KaspaTeal) else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedName = option.displayName }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isSelected) KaspaTeal else LocalAppColors.current.textSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        option.displayName,
                                        color = LocalAppColors.current.textPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (option.isDefault) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            stringResource(R.string.default_label),
                                            color = KaspaTeal,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    option.family.pathDescription,
                                    color = LocalAppColors.current.textSecondary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onContinue(selectedFamily) },
                colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.continue_label), color = Color.Black, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * The brand's near-black with a green cast, taken from the KaChat banner rather than the app's
 * usual true black. Scoped to the sign-in screen, and the same in both themes on purpose - it is
 * the one place the product introduces itself.
 */
internal val KaChatSignInBackground = Color(0xFF0E1614)

/** One step up from [KaChatSignInBackground], same cast, for the saved-account rows. */
internal val KaChatSignInCard = Color(0xFF1A2624)
