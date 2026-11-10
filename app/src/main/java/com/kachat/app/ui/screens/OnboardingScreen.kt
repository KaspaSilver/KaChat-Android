package com.kachat.app.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kachat.app.R
import com.kachat.app.models.ImportAccountCandidate
import com.kachat.app.models.ImportAccountChoice
import com.kachat.app.services.WalletManager
import com.kachat.app.ui.theme.KaspaSubtext
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.DarkAppColors
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.CreateAccountSteps
import com.kachat.app.util.authenticateWithDeviceCredential
import com.kachat.app.viewmodels.WalletViewModel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Main entry point for the onboarding flow.
 */
@Composable
fun OnboardingScreen(viewModel: WalletViewModel) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "welcome") {
        composable("welcome") {
            WelcomeScreen(
                viewModel,
                onCreateWithPassphrase = {
                    viewModel.beginCreateWithPassphrase()
                    navController.navigate("passphrase_create")
                },
                onOpenAppSettings = { navController.navigate("app_settings") },
                onNavigateToImport = {
                    // A stale SUCCESS/FAILED status left over from a previous import would
                    // otherwise fire ImportWalletScreen's success LaunchedEffect immediately on
                    // entry (this state lives in the singleton WalletViewModel, not the screen),
                    // silently logging into whatever account is currently active instead of
                    // letting the user type a new phrase.
                    viewModel.resetImportWalletState()
                    // The name comes first: the wallet the words belong to is found on the
                    // passphrase step, so there is no source-wallet list (iOS da467165).
                    navController.navigate("import_name")
                }
            )
        }
        // Import Account (iOS dd0aab1, da467165): the name (the same screen as Create Account's
        // first step), the seed length, the words, then the passphrase, which finds the wallet.
        composable("import_name") {
            ImportNameScreen(
                onBack = { navController.popBackStack() },
                onRestoreFromCard = {
                    viewModel.resetImportWalletState()
                    navController.navigate("nfc_card_restore")
                },
                onNext = { name -> navController.navigate("import_length?name=${android.net.Uri.encode(name)}") }
            )
        }
        composable(
            "import_length?name={name}",
            arguments = listOf(androidx.navigation.navArgument("name") { defaultValue = "" })
        ) { backStackEntry ->
            val name = backStackEntry.arguments?.getString("name").orEmpty()
            ImportLengthScreen(
                onBack = { navController.popBackStack() },
                onNext = { count -> navController.navigate("import_wallet?name=${android.net.Uri.encode(name)}&count=$count") }
            )
        }
        composable(
            "import_wallet?name={name}&count={count}",
            arguments = listOf(
                androidx.navigation.navArgument("name") { defaultValue = "Imported Account" },
                androidx.navigation.navArgument("count") { type = androidx.navigation.NavType.IntType; defaultValue = 24 }
            )
        ) { backStackEntry ->
            ImportWalletScreen(
                viewModel,
                accountName = backStackEntry.arguments?.getString("name") ?: "Imported Account",
                initialWordCount = backStackEntry.arguments?.getInt("count") ?: 24,
                onBack = { navController.popBackStack() },
                // Seed validated + stashed by prepareImport(); the actual import happens on the
                // passphrase screen (which arms the Welcome Guide + logs in on success).
                onProceed = { navController.navigate("passphrase_import") }
            )
        }
        composable("nfc_card_restore") {
            NfcCardRestoreScreen(viewModel, onBack = { navController.popBackStack() })
        }
        composable("passphrase_create") {
            val createError by viewModel.createWalletError.collectAsState()
            // Nothing is shown or saved before the passphrase: leaving forgets the words, and a
            // step restored after the process was recreated starts from new ones.
            LaunchedEffect(Unit) { viewModel.ensureCreateWithPassphrase() }
            val abandon: () -> Unit = {
                navController.popBackStack()
                viewModel.abandonCreatedWallet()
            }
            BackHandler(onBack = abandon)
            PassphraseSetupScreen(
                mode = PassphraseMode.CREATE,
                errorMessage = createError,
                onDismissError = { viewModel.clearCreateWalletError() },
                onBack = abandon,
                previewAddress = { viewModel.previewChattingAddress(it) },
                previewBalance = { viewModel.previewAddressBalanceSompi(it) },
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
                previewBalance = { viewModel.previewAddressBalanceSompi(it) },
                findAccounts = { viewModel.findPendingImportAccounts(it) },
                addressesAtZero = { viewModel.pendingImportAddressesAtZero(it) },
                onImportChoiceChange = { viewModel.setPendingImportChoice(it) },
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
                walletViewModel = viewModel
            )
        }
        // The same Connection page as in-account Settings (iOS ConnectionHubPage): Connection
        // Settings, the Testnet switch, Kaspa Explorer.
        composable("app_settings_connection") {
            AppConnectionScreen(
                onBack = { navController.popBackStack() },
                onOpenConnectionSettings = { navController.navigate("app_settings_connection_settings") },
                onOpenExplorer = { navController.navigate("app_settings_explorer") },
            )
        }
        composable("app_settings_connection_settings") {
            ConnectionSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable("app_settings_explorer") {
            KaspaExplorerSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable("app_settings_diagnostics") {
            AppDiagnosticsScreen(onBack = { navController.popBackStack() })
        }
    }
}

@Composable
fun WelcomeScreen(
    viewModel: WalletViewModel,
    onCreateWithPassphrase: () -> Unit,
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
        SheetBarIconButton(
            Icons.Outlined.Settings,
            stringResource(R.string.app_settings),
            onClick = onOpenAppSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 8.dp, end = 20.dp),
            tint = LocalAppColors.current.textPrimary,
            iconSize = 22.dp,
        )
        // iOS keeps Create and Import 40 above the safe area: the navigation bar (gesture handle
        // or 3-button bar) is cleared first, then the 40 - a fixed chain, nothing swapped in.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(start = 32.dp, end = 32.dp, top = 48.dp, bottom = 40.dp),
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

            // Shown whether or not there are accounts saved: it is what the screen is, and
            // hiding it on a returning device left the wordmark sitting on its own with nothing
            // saying what it belongs to.
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.secure_messaging_on_kaspa_blockdag),
                style = MaterialTheme.typography.bodyLarge,
                color = KaspaSubtext,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.weight(1f))

            val biometricAccountLoginEnabled by viewModel.biometricAccountLoginEnabled.collectAsState()
            val isCreatingAccount by viewModel.isCreatingAccount.collectAsState()
            val isAccountActionRunning by viewModel.isAccountActionRunning.collectAsState()
            val createAccountError by viewModel.createAccountError.collectAsState()
            val backupPending by viewModel.accountBackupStatus.pendingAddresses.collectAsState()

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
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                    
                    // Four at a time, then this scrolls inside itself - the fifth account is
                    // where scrolling starts. A dozen saved accounts used to just make the page
                    // taller, pushing Create and Import off the bottom of the screen.
                    // heightIn(max) rather than a fixed height so three accounts still take
                    // three rows' worth of space, not four.
                    val visibleRows = minOf(accounts.size, 4)
                    // Whatever is left after the rest of the page, floored at two rows, so a short
                    // phone keeps Create, Import and the Advanced link on screen (iOS d063b897).
                    val spareHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp - 400).coerceAtLeast(2 * 74)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = minOf(visibleRows * 74, spareHeight).dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        accounts.forEach { account ->
                            SavedAccountCard(
                                account = account,
                                requireBiometricLogin = biometricAccountLoginEnabled,
                                notBackedUp = account.address in backupPending,
                                // one account action at a time: each replaces the active account
                                enabled = !isAccountActionRunning,
                                onLogin = { viewModel.login(account.address) },
                                onRename = { newName -> viewModel.renameAccount(account.address, newName) },
                                onDelete = { viewModel.deleteWallet(account.address) },
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }
                }
            }

            // Create new wallet button: one tap, straight into the new account (iOS d063b897).
            Button(
                onClick = { viewModel.createAccountInOneTap() },
                enabled = !isCreatingAccount,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                // iOS: white body text on the accent, the icon plus.circle.fill.
                colors = ButtonDefaults.buttonColors(
                    containerColor = KaspaTeal,
                    contentColor = Color.White,
                    disabledContainerColor = KaspaTeal,
                    disabledContentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isCreatingAccount) {
                        com.kachat.app.ui.theme.IosActivityIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Default.AddCircle,
                            contentDescription = null,
                            tint = Color.White
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.create_new_account),
                        fontSize = 17.sp,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Import existing wallet button
            Button(
                onClick = onNavigateToImport,
                enabled = !isCreatingAccount,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                // iOS systemGray5.
                colors = ButtonDefaults.buttonColors(containerColor = LocalAppColors.current.incomingBubble),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.SaveAlt,
                        contentDescription = null,
                        tint = LocalAppColors.current.textPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.import_existing_account),
                        fontSize = 17.sp,
                        color = LocalAppColors.current.textPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // A passphrase is the only thing Create New Account doesn't do: it moved here.
            Text(
                text = stringResource(R.string.advanced_create_with_a_passphrase),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = KaspaTeal,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = !isCreatingAccount, onClick = onCreateWithPassphrase)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )

            createAccountError?.let { message ->
                com.kachat.app.ui.theme.IosAlertDialog(
                    onDismissRequest = { viewModel.clearCreateAccountError() },
                    containerColor = LocalAppColors.current.surface,
                    title = { Text(stringResource(R.string.couldnt_create_account), color = LocalAppColors.current.textPrimary) },
                    text = { Text(message, color = LocalAppColors.current.textSecondary) },
                    confirmButton = {
                        TextButton(onClick = { viewModel.clearCreateAccountError() }) {
                            Text(stringResource(R.string.ok), color = KaspaTeal, fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }
        }
        // Which build this phone runs, right where sign-in problems get reported from (iOS
        // e8f4617). Drawn inside the column's 40dp bottom padding, 10 above the navigation bar
        // (iOS: 10pt above the safe area), so the height budget that keeps Create and Import on
        // screen is untouched and a 3-button bar never covers it.
        Text(
            text = "KaChat ${com.kachat.app.util.AppVersion.display}",
            fontSize = 11.sp,
            color = LocalAppColors.current.textSecondary,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 10.dp)
        )
        }
    }
}
}

@Composable
fun SavedAccountCard(
    account: WalletManager.Account,
    requireBiometricLogin: Boolean = false,
    /** This account hasn't finished the backup steps: the Delete warning says so. */
    notBackedUp: Boolean = false,
    /** False while another account action runs: sign-in and Rename/Delete wait for it (iOS
     *  3942a5b7 `isAccountActionRunning`). */
    enabled: Boolean = true,
    onLogin: () -> Unit,
    onRename: (String) -> Unit,
    /** Removes the account and its local data. The archive on Nextcloud is never touched: it is
     *  what carries the contacts to the account's other devices (iOS d23e6f7a). */
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf(account.name) }
    var signInErrorMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    // Read when the lock prompt returns: it may have let another account action start meanwhile
    // (iOS performSignIn and removeSavedAccount re-check).
    val isEnabled by rememberUpdatedState(enabled)
    // iOS displayAlias and shortPublicAddress (the first 10 and last 6 characters).
    val displayAlias = account.name.trim().ifEmpty { stringResource(R.string.saved_account) }
    val shortAddress = account.address.let { if (it.length > 16) "${it.take(10)}...${it.takeLast(6)}" else it }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(KaChatSignInCard)
            .clickable(enabled = enabled) {
                if (requireBiometricLogin) {
                    context.authenticateWithDeviceCredential(
                        title = context.getString(R.string.unlock_to_sign_in_to, displayAlias),
                        onSuccess = { if (isEnabled) onLogin() },
                        onFailure = { signInErrorMessage = context.getString(R.string.authentication_failed) },
                    )
                } else {
                    onLogin()
                }
            }
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // iOS person.crop.circle.badge.checkmark.
        Box(modifier = Modifier.size(24.dp)) {
            Icon(
                imageVector = Icons.Outlined.AccountCircle,
                contentDescription = null,
                tint = KaspaTeal,
                modifier = Modifier.size(22.dp).align(Alignment.TopEnd)
            )
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = KaspaTeal,
                modifier = Modifier
                    .size(11.dp)
                    .align(Alignment.BottomStart)
                    .background(KaChatSignInCard, CircleShape)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = account.name,
                color = LocalAppColors.current.textPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Truncated in the middle, as on iOS: both ends of an address are what tell two apart.
            MiddleEllipsisText(
                account.address,
                color = LocalAppColors.current.textSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(enabled = enabled) { showMenu = true },
        ) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = stringResource(R.string.edit_account),
                tint = KaspaTeal,
                modifier = Modifier.size(17.dp)
            )
        }
    }

    // Half sheet rather than a dropdown, like every other chooser in the app - and Delete gets a
    // line saying what it takes with it, which a menu of bare verbs cannot.
    if (showMenu) {
        ActionSheetContainer(
            title = displayAlias,
            subtitle = shortAddress,
            onDismiss = { showMenu = false },
        ) {
            ActionSheetRow(
                icon = Icons.Default.Edit,
                title = stringResource(R.string.rename),
                subtitle = stringResource(R.string.gives_this_account_a_name_of_your),
            ) {
                showMenu = false
                nameInput = account.name
                showRenameDialog = true
            }
            ActionSheetRow(
                icon = Icons.Default.Delete,
                title = stringResource(R.string.delete),
                subtitle = stringResource(R.string.removes_this_account_and_its_local_data),
                tint = LocalAppColors.current.danger,
            ) {
                showMenu = false
                showDeleteConfirm = true
            }
        }
    }

    if (showRenameDialog) {
        com.kachat.app.ui.theme.IosAlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.rename_account), color = LocalAppColors.current.textPrimary) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.enter_a_new_name_for_this_saved), color = LocalAppColors.current.textPrimary)
                    Spacer(Modifier.height(12.dp))
                    com.kachat.app.ui.theme.IosTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.settings_account_name_placeholder), color = LocalAppColors.current.textSecondary) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = LocalAppColors.current.textPrimary,
                            unfocusedTextColor = LocalAppColors.current.textPrimary,
                            focusedBorderColor = KaspaTeal,
                            unfocusedBorderColor = LocalAppColors.current.textSecondary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
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

    signInErrorMessage?.let { message ->
        com.kachat.app.ui.theme.IosAlertDialog(
            onDismissRequest = { signInErrorMessage = null },
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.sign_in_failed), color = LocalAppColors.current.textPrimary) },
            text = { Text(message, color = LocalAppColors.current.textSecondary) },
            confirmButton = {
                TextButton(onClick = { signInErrorMessage = null }) {
                    Text(stringResource(R.string.ok), color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showDeleteConfirm) {
        // The warning Settings' Delete Account shows, without its seed phrase and backup rows: this
        // account is signed out (iOS d063b897). The row names the account and its short address.
        DeleteAccountSheet(
            confirmSubtitle = stringResource(R.string.deletes_and_its_local_data_from_this, displayAlias, shortAddress),
            notBackedUp = notBackedUp,
            // Deleting asks the device's lock first (iOS d1844582).
            onConfirm = {
                context.authenticateWithDeviceCredential(
                    title = context.getString(R.string.confirm_deleting_account),
                    onSuccess = { if (isEnabled) onDelete() },
                )
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

/**
 * Import Account's first screen (iOS dd0aab1 `ImportNameStep`): the account's name, local to this
 * device - the same screen as Create Account's first step. Then the seed length
 * ([ImportLengthScreen]), the words ([ImportWalletScreen]), and the passphrase step, which finds
 * the wallet those words belong to. Restore from NFC Card sits at its foot, moved here with the
 * source-wallet list gone (iOS da467165).
 */
@Composable
fun ImportNameScreen(onBack: () -> Unit, onRestoreFromCard: () -> Unit, onNext: (String) -> Unit) {
    val nfcAvailable = com.kachat.app.services.NfcCard.isAvailable(LocalContext.current)
    val restoreFromCard: @Composable ColumnScope.() -> Unit = {
        Row(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(vertical = 6.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onRestoreFromCard)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Default.Nfc, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.restore_from_nfc_card), color = KaspaTeal, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    AccountNameForm(
        title = stringResource(R.string.import_account),
        onBack = onBack,
        onNext = onNext,
        bottomBar = if (nfcAvailable) restoreFromCard else null,
    )
}

/**
 * The account name step of Create Account and Import Account (iOS dd0aab1 `AccountNameForm`): a
 * name local to this device. Next stays off until there is a name; [onNext] gets it trimmed.
 */
@Composable
private fun AccountNameForm(
    title: String,
    onBack: () -> Unit,
    onNext: (String) -> Unit,
    /** Pinned under the form, outside its scroll ([CreateAccountStepScaffold]). */
    bottomBar: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    // Starts empty on purpose: the name is the user's choice. Kept across the trip to the next
    // step and back, as iOS keeps it.
    var accountName by rememberSaveable { mutableStateOf("") }
    val canContinue = CreateAccountSteps.canContinue(accountName)
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val next = {
        if (canContinue) {
            focusManager.clearFocus()
            onNext(CreateAccountSteps.accountName(accountName))
        }
    }

    CreateAccountStepScaffold(title = title, onBack = onBack, bottomBar = bottomBar) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.create_wallet_name_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary
            )
            Text(
                text = stringResource(R.string.create_wallet_name_detail),
                fontSize = 15.sp,
                color = colors.textSecondary
            )
        }

        // iOS `.roundedBorder`: a short field with a hairline, not a filled block.
        androidx.compose.foundation.text.BasicTextField(
            value = accountName,
            onValueChange = { accountName = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .border(0.5.dp, colors.textSecondary.copy(alpha = 0.35f), RoundedCornerShape(5.dp))
                .padding(horizontal = 7.dp)
                .focusRequester(focus),
            textStyle = androidx.compose.ui.text.TextStyle(color = colors.textPrimary, fontSize = 17.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(KaspaTeal),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { next() }),
            singleLine = true,
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.fillMaxSize()) {
                    if (accountName.isEmpty()) {
                        Text(stringResource(R.string.enter_account_name), color = colors.textTertiary, fontSize = 17.sp)
                    }
                    field()
                }
            },
        )

        CreateWalletNextButton(title = stringResource(R.string.next), enabled = canContinue, onClick = next)
    }
}

/**
 * Import Account: how many words the seed phrase being imported has (iOS dd0aab1
 * `ImportLengthStep`) - the same 12 / 24 buttons as Create Account, nothing chosen until the
 * user taps one. Next goes to the words ([ImportWalletScreen]).
 */
@Composable
fun ImportLengthScreen(onBack: () -> Unit, onNext: (Int) -> Unit) {
    val colors = LocalAppColors.current
    var wordCount by rememberSaveable { mutableStateOf<Int?>(null) }

    CreateAccountStepScaffold(title = stringResource(R.string.import_account), onBack = onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.choose_seed_phrase_length),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary
            )
            Text(
                text = stringResource(R.string.import_seed_length_detail),
                fontSize = 15.sp,
                color = colors.textSecondary
            )
            CreateAccountSteps.WORD_COUNTS.forEach { count ->
                SeedLengthButton(
                    title = stringResource(if (count == 12) R.string.n_12_words else R.string.n_24_words),
                    chosen = wordCount == count,
                    enabled = true,
                    onClick = { wordCount = count },
                )
            }
        }

        CreateWalletNextButton(
            title = stringResource(R.string.next),
            enabled = CreateAccountSteps.canChooseImportLength(wordCount),
        ) {
            val count = wordCount
            if (count != null && CreateAccountSteps.canChooseImportLength(count)) onNext(count)
        }
    }
}

/**
 * What a seed phrase is, in plain words (iOS 712bb4d `SeedPhraseExplainerPage`): the page behind
 * "What is this?" on the seed length step. A full page on the plain background, pushed like
 * "What is a passphrase?" and in the same headline-and-text sections, so it reads easily.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SeedPhraseExplainerPage(onBack: () -> Unit) {
    val colors = LocalAppColors.current
    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.what_is_a_seed_phrase), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
                navigationIcon = { SheetBarBackButton(onBack, Modifier.padding(start = 12.dp)) },
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
                stringResource(R.string.seed_phrase_explainer_short_version),
                stringResource(R.string.seed_phrase_explainer_master_key)
            )
            PassphraseExplainerSection(
                stringResource(R.string.seed_phrase_explainer_anyone_title),
                stringResource(R.string.seed_phrase_explainer_anyone)
            )
            PassphraseExplainerSection(
                stringResource(R.string.seed_phrase_explainer_safe_title),
                stringResource(R.string.seed_phrase_explainer_paper)
            )
        }
    }
}

/** The Create Account and Import Account steps' frame: Back, the large title ("Create Account"
 *  or "Import Account"), then the step's content 24dp apart, scrolling above the keyboard. */
@Composable
private fun CreateAccountStepScaffold(
    title: String = stringResource(R.string.create_account),
    onBack: () -> Unit,
    /** Pinned at the foot, outside the scroll and above the keyboard (iOS `safeAreaInset(edge:
     *  .bottom)`, da467165's Restore from NFC Card). */
    bottomBar: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalAppColors.current
    Surface(color = colors.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                // Edge-to-edge: Compose reacts to the keyboard inset itself, so the field and the
                // button stay above it.
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
            ) {
                // iOS 26: the back chevron on a glass disc.
                SheetBarIconButton(Icons.Filled.ArrowBackIosNew, stringResource(R.string.back), onClick = onBack, modifier = Modifier.padding(start = 4.dp), tint = LocalAppColors.current.textPrimary)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = title,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(start = 4.dp)
                )
                Spacer(modifier = Modifier.height(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(24.dp), content = content)
                Spacer(modifier = Modifier.height(24.dp))
            }
            bottomBar?.invoke(this)
        }
    }
}

/** The accent-filled continue button of the Import Account and backup steps, dimmed while disabled
 *  (iOS `CreateWalletNextButton`). */
@Composable
internal fun CreateWalletNextButton(title: String, enabled: Boolean, busy: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = KaspaTeal,
            // Not dimmed while busy, as on iOS.
            disabledContainerColor = if (busy) KaspaTeal else KaspaTeal.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                com.kachat.app.ui.theme.IosActivityIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            // iOS: white body text on the accent, dimmed along with the button.
            Text(
                text = title,
                fontSize = 17.sp,
                color = if (enabled || busy) Color.White else Color.White.copy(alpha = 0.4f)
            )
        }
    }
}

/** One of the two seed length choices (12 or 24 words): full width, filled with the accent when
 *  chosen, outlined otherwise (iOS dd0aab1 `SeedLengthButton`, shared by Create Account and
 *  Import Account). */
@Composable
private fun SeedLengthButton(title: String, chosen: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    // The whole card is the button, its padding and outline included (iOS ceca9fa), and it dims
    // while held instead of rippling (iOS `.buttonStyle(.plain)`).
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (pressed) IOS_PLAIN_PRESSED_ALPHA else 1f }
            .clip(shape)
            .background(if (chosen) KaspaTeal else Color.Transparent)
            .border(1.5.dp, KaspaTeal, shape)
            .selectable(
                selected = chosen,
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(16.dp)
    ) {
        Icon(
            imageVector = if (chosen) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (chosen) Color.White else KaspaTeal,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            color = if (chosen) Color.White else KaspaTeal,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp
        )
    }
}

/**
 * Import Account: the seed phrase's words (in-app keyboard, or Paste), then the passphrase step
 * (iOS `ImportWalletView`). The name ([ImportNameScreen]) and the length ([ImportLengthScreen])
 * come before it (iOS dd0aab1); a pasted phrase of the other length switches the length.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportWalletScreen(
    viewModel: WalletViewModel,
    accountName: String,
    initialWordCount: Int,
    onBack: () -> Unit,
    onProceed: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var wordCount by remember { mutableIntStateOf(CreateAccountSteps.importWordCount(initialWordCount)) }
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
    // on dispose like CreateAccountSeedScreen/SeedPhraseScreen's identical guards.
    SecureWindowWhileShown()

    // iOS ImportWalletView: "Import Account" as an inline title with Back, then the label, Paste
    // and the count on one line, the keyboard, and Continue - 12 apart, 16 from the edges.
    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.import_account), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
                navigationIcon = { SheetBarBackButton(onBack, Modifier.padding(start = 12.dp)) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        }
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .padding(16.dp)
                .imePadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.import_words_enter_recovery_phrase),
                    color = LocalAppColors.current.textSecondary,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                // A phrase on the clipboard fills the slots in one tap, and the word count
                // follows whichever length was pasted (iOS f9beba1).
                val pasteContext = LocalContext.current
                val pasteHaptic = com.kachat.app.util.rememberHaptics()
                var pasteError by remember { mutableStateOf<String?>(null) }
                pasteError?.let { message ->
                    com.kachat.app.ui.theme.IosAlertDialog(
                        onDismissRequest = { pasteError = null },
                        containerColor = LocalAppColors.current.surface,
                        title = { Text(stringResource(R.string.error), color = LocalAppColors.current.textPrimary) },
                        text = { Text(message, color = LocalAppColors.current.textSecondary) },
                        confirmButton = {
                            TextButton(onClick = { pasteError = null }) {
                                Text(stringResource(R.string.ok), color = KaspaTeal, fontWeight = FontWeight.Bold)
                            }
                        }
                    )
                }
                val pasteClipboard = pasteContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                // iOS `.bordered`, small: a filled capsule in the accent's text, not an outline.
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = LocalAppColors.current.incomingBubble),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(50),
                    onClick = {
                        // iOS's pasteSeedPhrase: a refusal goes up in the "Error" alert, a
                        // success is a haptic and the filled slots.
                        val raw = pasteClipboard?.primaryClip?.getItemAt(0)?.coerceToText(pasteContext)?.toString()
                        if (raw == null) {
                            pasteError = context.getString(R.string.nothing_to_paste)
                            return@Button
                        }
                        // Cleared as soon as it is read, whether or not the words are accepted:
                        // secret words don't belong on the clipboard either way (iOS 3942a5b7).
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            pasteClipboard?.clearPrimaryClip()
                        } else {
                            pasteClipboard?.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
                        }
                        val pasted = raw.lowercase()
                            .split(Regex("[\\s,]+"))
                            .map { it.trim('.', ')') }
                            .filter { it.isNotEmpty() && !it.all { c -> c.isDigit() } }
                        when {
                            pasted.size != 12 && pasted.size != 24 ->
                                pasteError = context.getString(R.string.you_need_12_or_24_words_the, pasted.size)
                            else -> {
                                val unknown = pasted.filterNot { wordList.contains(it) }
                                if (unknown.isNotEmpty()) {
                                    // only a count: the message must never repeat what was pasted
                                    pasteError = context.resources.getQuantityString(R.plurals.pasted_words_invalid, unknown.size, unknown.size)
                                } else {
                                    wordCount = pasted.size
                                    words = List(24) { index -> pasted.getOrElse(index) { "" } }
                                    activeSlot = (pasted.size - 1).coerceAtLeast(0)
                                    pasteHaptic(com.kachat.app.util.IosHaptic.SUCCESS)
                                }
                            }
                        }
                    },
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.paste), color = KaspaTeal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "$filled/$wordCount",
                    color = if (allValid) LocalAppColors.current.success else LocalAppColors.current.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

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

            // Continue to the passphrase step: the arrow and "Continue", gray until every word is
            // in (iOS ImportWalletView).
            Button(
                onClick = { if (viewModel.prepareImport(accountName, slots)) onProceed() },
                enabled = canImport,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, disabledContainerColor = IosGray),
                shape = RoundedCornerShape(12.dp)
            ) {
                // white on the accent and on gray alike (iOS `.foregroundColor(.white)`)
                val contentColor = Color.White
                Icon(Icons.Default.ArrowCircleRight, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.import_words_continue),
                    color = contentColor,
                    fontSize = 17.sp
                )
            }
        }
    }

    if (importState.status == WalletViewModel.ImportWalletStatus.FAILED) {
        com.kachat.app.ui.theme.IosAlertDialog(
            onDismissRequest = { viewModel.resetImportWalletState() },
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.error), color = LocalAppColors.current.textPrimary) },
            text = { Text(importState.errorMessage ?: stringResource(R.string.something_went_wrong), color = LocalAppColors.current.textSecondary) },
            confirmButton = {
                TextButton(onClick = { viewModel.resetImportWalletState() }) {
                    Text(stringResource(R.string.ok), color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

/** How far a control in iOS's plain button style fades while it is held. */
private const val IOS_PLAIN_PRESSED_ALPHA = 0.4f

/** iOS `Color.gray`, a disabled button's fill. */
private val IosGray = Color(0xFF8E8E93)

/**
 * The whole reason the passphrase screen shows an address: a passphrase does not protect one
 * account, it opens a different one. Watching it change with every character is what makes that
 * concrete, and on import it is how someone confirms they typed the right thing before committing
 * to an account that would otherwise look simply empty (iOS 6746b5fb `PassphraseAddressPreview`).
 *
 * [findAccounts] is set when the account isn't known yet: the preview searches every wallet type
 * for the words, picks the likeliest account, and lets the user pick another; [addressesAtZero]
 * gives "See Every Wallet Type" its rows for words the search can't place (iOS da467165).
 * [onSearchingChange] says while that search runs, so the caller can hold its Import until the
 * pick is made (iOS 3942a5b7 `isSearching`).
 */
@Composable
internal fun PassphraseAddressPreview(
    passphrase: String,
    previewAddress: (String) -> String?,
    previewBalance: suspend (String) -> Long?,
    showsFundsHint: Boolean,
    findAccounts: (suspend (String) -> List<ImportAccountCandidate>?)? = null,
    addressesAtZero: (String) -> List<ImportAccountCandidate> = { emptyList() },
    onSelectionChange: (ImportAccountChoice) -> Unit = {},
    onSearchingChange: (Boolean) -> Unit = {},
    /** False while the account is being made: the pick can't change under it. */
    enabled: Boolean = true,
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    // Debounced: the derivation is PBKDF2 over 2048 rounds, quick but not free, and running it on
    // every keystroke would be felt.
    var shownAddress by remember { mutableStateOf<String?>(null) }
    // What [shownAddress] holds: null while looking it up, or when the lookup failed.
    var balanceSompi by remember { mutableStateOf<Long?>(null) }
    var balanceFailed by remember { mutableStateOf(false) }
    var hasHistory by remember { mutableStateOf(false) }
    // What the search found, likeliest first.
    var found by remember { mutableStateOf(emptyList<ImportAccountCandidate>()) }
    // Address #0 of every wallet type, for words the search can't place (iOS da467165).
    var everyWalletType by remember { mutableStateOf(emptyList<ImportAccountCandidate>()) }
    var showsEveryWalletType by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(ImportAccountChoice()) }
    var searching by remember { mutableStateOf(false) }
    // The funds search failed or ran out of time: Import goes to #0, and the screen says so
    // (iOS d23e6f7a `searchIncomplete`).
    var searchIncomplete by remember { mutableStateOf(false) }
    // Bumped by Search Again, which runs the lookup over for the same passphrase.
    var searchAttempt by remember { mutableStateOf(0) }

    fun setSearching(value: Boolean) {
        searching = value
        onSearchingChange(value)
    }

    fun select(candidate: ImportAccountCandidate) {
        selection = candidate.choice
        onSelectionChange(candidate.choice)
        shownAddress = candidate.address
        balanceSompi = candidate.balanceSompi
        hasHistory = candidate.hasHistory
    }

    // A search cut off by leaving the screen must not leave the caller's Import held.
    DisposableEffect(Unit) { onDispose { onSearchingChange(false) } }

    LaunchedEffect(passphrase, searchAttempt) {
        val effect = this
        balanceSompi = null
        balanceFailed = false
        hasHistory = false
        searchIncomplete = false
        found = emptyList()
        everyWalletType = emptyList()
        showsEveryWalletType = false
        selection = ImportAccountChoice()
        onSelectionChange(ImportAccountChoice())
        // Held from the first keystroke, not only once the lookup starts: an Import tapped in the
        // pause would otherwise go to #0.
        setSearching(findAccounts != null)
        kotlinx.coroutines.delay(250)
        val address = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { previewAddress(passphrase) }
        shownAddress = address
        if (address == null) {
            balanceFailed = true
            setSearching(false)
            return@LaunchedEffect
        }
        // a further pause, so only the passphrase someone stopped typing is looked up
        kotlinx.coroutines.delay(450)
        if (findAccounts != null) {
            everyWalletType = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { addressesAtZero(passphrase) }
            // A slow or unreachable node lets Import go after the timeout, onto #0, and says so;
            // an answer that comes later is ignored (iOS d23e6f7a).
            var timedOut = false
            val timeout = launch {
                kotlinx.coroutines.delay(PASSPHRASE_SEARCH_TIMEOUT_MS)
                timedOut = true
                searchIncomplete = true
                balanceFailed = true
                setSearching(false)
                // a search without the batch endpoint falls back to one REST call per address:
                // stop those (iOS da467165)
                effect.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            }
            val accounts = findAccounts(passphrase)
            timeout.cancel()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (timedOut) return@LaunchedEffect
            setSearching(false)
            if (accounts == null) {
                searchIncomplete = true
                balanceFailed = true
            } else {
                found = accounts
                // Nothing found anywhere: Standard #0 stays the pick, and it holds nothing.
                val first = accounts.firstOrNull()
                if (first != null) {
                    select(first)
                } else {
                    balanceSompi = 0L
                }
            }
            return@LaunchedEffect
        }
        val sompi = previewBalance(address)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        balanceSompi = sompi
        balanceFailed = sompi == null
    }

    fun amountText(sompi: Long) = "${com.kachat.app.util.formatKasAmount(sompi / 100_000_000.0)} ${com.kachat.app.util.KaspaUnit.symbol}"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.your_chatting_address), color = colors.textSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        // Every character, wrapped over as many lines as it takes. This is the one place the
        // address has to be readable in full: truncating it would hide exactly the part that
        // changes when the passphrase does, which is why it is on screen.
        Text(
            shownAddress ?: stringResource(R.string.checking),
            color = if (shownAddress == null) colors.textSecondary else colors.textPrimary,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontSize = 13.sp,
            softWrap = true,
            overflow = TextOverflow.Visible,
        )
        // Only the chatting address's: funds can also sit on the account's other addresses, so an
        // empty one suggests a wrong passphrase rather than proving it.
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.balance), color = colors.textSecondary, fontSize = 12.sp)
            val sompi = balanceSompi
            when {
                sompi != null -> Text(
                    amountText(sompi),
                    color = if (sompi > 0) colors.success else colors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                balanceFailed -> Text(stringResource(R.string.couldnt_check), color = colors.textSecondary, fontSize = 13.sp)
                else -> com.kachat.app.ui.theme.IosActivityIndicator(color = colors.textSecondary, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            }
        }
        val checkedSompi = balanceSompi
        if (showsFundsHint && checkedSompi != null) {
            Spacer(Modifier.height(4.dp))
            // History counts as much as funds: a chat identity or an emptied wallet holds no coins
            // (iOS da467165 `fundsHint`).
            val (hint, hintColor) = when {
                checkedSompi > 0 -> stringResource(R.string.this_address_holds_funds_so_this_looks) to colors.success
                hasHistory -> stringResource(R.string.this_address_has_history_so_this_looks) to colors.success
                passphrase.isEmpty() -> stringResource(R.string.no_funds_or_history_on_this_address) to colors.textSecondary
                else -> stringResource(R.string.no_funds_on_this_address_if_you) to colors.warning
            }
            Text(hint, color = hintColor, fontSize = 12.sp)
        }
        if (found.size > 1 || showsEveryWalletType) {
            // Every wallet type's #0 after what the search found, without repeating one.
            val rows = if (!showsEveryWalletType) found else {
                val foundIds = found.map { it.id }.toSet()
                found + everyWalletType.filter { it.id !in foundIds }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.we_picked_the_most_likely_account),
                color = colors.textSecondary,
                fontSize = 12.sp,
            )
            for (candidate in rows) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled) { select(candidate) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        if (selection == candidate.choice) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = KaspaTeal,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(candidate.family.displayName(context), color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.address_number, candidate.index), color = colors.textSecondary, fontSize = 11.sp)
                        Text(
                            "${candidate.address.take(14)}...${candidate.address.takeLast(6)}",
                            color = colors.textSecondary,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                    }
                    if (candidate.balanceSompi > 0) {
                        Text(
                            amountText(candidate.balanceSompi),
                            color = colors.success,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else if (candidate.hasHistory) {
                        Text(stringResource(R.string.has_history), color = colors.textSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
        // A manual pick, for words the search placed wrongly or not at all (iOS da467165).
        if (findAccounts != null && !searching && everyWalletType.isNotEmpty() && !showsEveryWalletType) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.see_every_wallet_type),
                color = if (enabled) KaspaTeal else KaspaTeal.copy(alpha = 0.4f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(enabled = enabled) { showsEveryWalletType = true },
            )
        }
        if (searchIncomplete) {
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.passphrase_search_unfinished),
                color = colors.warning,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.search_again),
                color = if (enabled) KaspaTeal else KaspaTeal.copy(alpha = 0.4f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(enabled = enabled) { searchAttempt++ },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (passphrase.isEmpty())
                stringResource(R.string.this_is_the_account_your_12_or)
            else
                stringResource(R.string.a_different_passphrase_gives_a_different_address),
            color = colors.textSecondary,
            fontSize = 12.sp,
        )
    }
}

enum class PassphraseMode { CREATE, IMPORT }

/** How long Import waits for the funds search before letting go and importing onto #0 (iOS
 *  d23e6f7a `searchTimeoutNanoseconds`). */
private const val PASSPHRASE_SEARCH_TIMEOUT_MS = 12_000L

/**
 * Optional BIP39 passphrase ("25th word") step, shown after seed backup (create) or seed entry
 * (import) and before the Welcome Guide. Explains what a passphrase does, how it helps, and the
 * risk of forgetting it, then lets the user proceed with a passphrase or skip. The caller performs
 * the actual create/import commit via [onProceed], which receives the chosen passphrase ("" when
 * skipped). Copy is English-only for now — a follow-up can move these literals into strings.xml.
 * Import with no passphrase goes to [PassphraseStep.FOUND]: the wallet these words were found in
 * (iOS da467165).
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
    /** What an address holds, for the balance under the live preview; null when it couldn't be
     *  checked. */
    previewBalance: suspend (String) -> Long? = { null },
    /** Set on import: the preview searches every wallet type for the words (iOS da467165). */
    findAccounts: (suspend (String) -> List<ImportAccountCandidate>?)? = null,
    /** Address #0 of every wallet type, for "See Every Wallet Type". */
    addressesAtZero: (String) -> List<ImportAccountCandidate> = { emptyList() },
    /** The wallet type and address the search found or the user picked. */
    onImportChoiceChange: (ImportAccountChoice) -> Unit = {},
    onProceed: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Creating only reaches here through "Advanced: Create with a Passphrase", which has already
    // answered the question (iOS d1844582).
    var step by remember { mutableStateOf(if (mode == PassphraseMode.CREATE) PassphraseStep.ENTRY else PassphraseStep.QUESTION) }
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    // The import's search for the address holding funds is running: Import waits for it, or it
    // would import onto #0 while the funds sit on another address (iOS 3942a5b7).
    var isSearching by remember { mutableStateOf(false) }
    // Where the explainer's Back returns: the question (import) or the entry step, which links it
    // too since creating opens past the question.
    var explainerReturnStep by remember { mutableStateOf(PassphraseStep.QUESTION) }
    val colors = LocalAppColors.current

    /** [skippingSearch]: Import on address #0 without waiting for the funds search (iOS d23e6f7a). */
    fun submitFromEntry(skippingSearch: Boolean = false) {
        if (isBusy || (isSearching && !skippingSearch)) return
        when {
            passphrase.isEmpty() ->
                localError = context.getString(R.string.enter_a_passphrase_or_go_back_and)
            mode == PassphraseMode.CREATE && passphrase != confirm ->
                localError = context.getString(R.string.the_passphrases_do_not_match_please_re)
            else -> {
                if (skippingSearch) onImportChoiceChange(ImportAccountChoice())
                onProceed(passphrase)
            }
        }
    }

    // Back always lands one step nearer the question, and only leaves the flow from the question
    // itself - reading the explainer must return to the choice, never skip past it. Creating has
    // no question, so Back from its entry step leaves.
    fun backFromEntry() {
        passphrase = ""
        confirm = ""
        if (mode == PassphraseMode.CREATE) onBack() else step = PassphraseStep.QUESTION
    }
    BackHandler(enabled = step != PassphraseStep.QUESTION) {
        when (step) {
            PassphraseStep.EXPLAINER -> step = explainerReturnStep
            PassphraseStep.FOUND -> if (!isBusy) step = PassphraseStep.QUESTION
            else -> backFromEntry()
        }
    }

    Surface(color = colors.background, modifier = Modifier.fillMaxSize()) {
        when (step) {
            PassphraseStep.QUESTION -> PassphraseQuestionStep(
                mode = mode,
                isBusy = isBusy,
                onYes = { step = PassphraseStep.ENTRY },
                onNo = {
                    if (!isBusy) {
                        // Held from here, not only once the search starts: Import must not be
                        // tappable before the search has run (iOS da467165).
                        isSearching = true
                        step = PassphraseStep.FOUND
                    }
                },
                onExplain = {
                    explainerReturnStep = PassphraseStep.QUESTION
                    step = PassphraseStep.EXPLAINER
                },
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
                previewBalance = previewBalance,
                findAccounts = findAccounts,
                addressesAtZero = addressesAtZero,
                onImportChoiceChange = onImportChoiceChange,
                isSearching = isSearching,
                onSearchingChange = { isSearching = it },
                onSubmit = { submitFromEntry() },
                onSkipSearch = { submitFromEntry(skippingSearch = true) },
                onExplain = {
                    explainerReturnStep = PassphraseStep.ENTRY
                    step = PassphraseStep.EXPLAINER
                },
                onBack = { backFromEntry() },
            )
            PassphraseStep.FOUND -> PassphraseFoundStep(
                isBusy = isBusy,
                previewAddress = previewAddress,
                previewBalance = previewBalance,
                findAccounts = findAccounts,
                addressesAtZero = addressesAtZero,
                onImportChoiceChange = onImportChoiceChange,
                isSearching = isSearching,
                onSearchingChange = { isSearching = it },
                onImport = { if (!isBusy && !isSearching) onProceed("") },
                onSkipSearch = {
                    if (!isBusy) {
                        onImportChoiceChange(ImportAccountChoice())
                        onProceed("")
                    }
                },
                onBack = { step = PassphraseStep.QUESTION },
            )
            PassphraseStep.EXPLAINER -> PassphraseExplainerStep(
                mode = mode,
                onBack = { step = explainerReturnStep },
            )
        }
    }

    val shownError = localError ?: errorMessage
    if (shownError != null) {
        com.kachat.app.ui.theme.IosAlertDialog(
            onDismissRequest = { localError = null; onDismissError() },
            containerColor = colors.surface,
            title = { Text(stringResource(R.string.seed_phrase_passphrase_label), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
            text = { Text(shownError, color = colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = { localError = null; onDismissError() }) {
                    Text(stringResource(R.string.ok), color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

private enum class PassphraseStep { QUESTION, ENTRY, FOUND, EXPLAINER }

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
                title = { Text(stringResource(R.string.seed_phrase_passphrase_label), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
                navigationIcon = {
                    SheetBarIconButton(Icons.Filled.ArrowBackIosNew, stringResource(R.string.back), onClick = onBack, modifier = Modifier.padding(start = 12.dp), enabled = !isBusy, tint = LocalAppColors.current.textPrimary)
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
            // iOS lock.shield.fill.
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Shield, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(64.dp))
                Icon(Icons.Default.Lock, contentDescription = null, tint = colors.background, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.did_you_create_these_words_with_a),
                color = colors.textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            // Import keeps a line of steer, because someone who has never heard of a passphrase
            // still has to answer a question about their own past. Create needs none: the choice
            // is theirs to make, and "What is a passphrase?" is right there if they want it.
            if (mode == PassphraseMode.IMPORT) {
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(R.string.if_you_are_not_sure_the_answer),
                    color = colors.textSecondary,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.weight(1f))

            Button(
                onClick = onYes,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, contentColor = Color.White),
                shape = RoundedCornerShape(14.dp),
            ) { Text(stringResource(R.string.yes), fontWeight = FontWeight.SemiBold, fontSize = 17.sp) }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onNo,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = KaspaTeal.copy(alpha = 0.15f),
                    contentColor = KaspaTeal,
                ),
                shape = RoundedCornerShape(14.dp),
            ) {
                // No spinner: No goes on to the found step, which imports (iOS da467165).
                Text(stringResource(R.string.no), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            }

            Spacer(Modifier.height(12.dp))

            TextButton(onClick = onExplain, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.what_is_a_passphrase), color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
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
    previewBalance: suspend (String) -> Long?,
    findAccounts: (suspend (String) -> List<ImportAccountCandidate>?)?,
    addressesAtZero: (String) -> List<ImportAccountCandidate>,
    onImportChoiceChange: (ImportAccountChoice) -> Unit,
    isSearching: Boolean,
    onSearchingChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    /** Imports on address #0 without waiting for the funds search. */
    onSkipSearch: () -> Unit,
    onExplain: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalAppColors.current
    // The passphrase is as secret as the seed words, and "Show" puts it on screen in plain text:
    // no screenshots, screen recording or Recents thumbnail while this step is up, like the seed
    // screens (audit AND-030).
    SecureWindowWhileShown()
    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.seed_phrase_passphrase_label), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
                navigationIcon = {
                    SheetBarButton(stringResource(R.string.back), onClick = onBack, modifier = Modifier.padding(start = 12.dp), enabled = !isBusy, color = colors.textPrimary)
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
                if (mode == PassphraseMode.CREATE) stringResource(R.string.choose_your_passphrase) else stringResource(R.string.enter_your_passphrase),
                color = colors.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                if (mode == PassphraseMode.CREATE)
                    stringResource(R.string.write_it_down_somewhere_safe_without_it)
                else
                    stringResource(R.string.it_has_to_be_exactly_what_you),
                color = colors.textSecondary,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(20.dp))

            // Nothing changes the account once it is being made (iOS 3942a5b7).
            PassphraseInputField(
                value = passphrase,
                onValueChange = onPassphraseChange,
                reveal = reveal,
                onToggleReveal = onToggleReveal,
                placeholder = stringResource(R.string.seed_phrase_passphrase_label),
                enabled = !isBusy,
            )

            if (mode == PassphraseMode.CREATE) {
                Spacer(Modifier.height(12.dp))
                PassphraseInputField(
                    value = confirm,
                    onValueChange = onConfirmChange,
                    reveal = reveal,
                    onToggleReveal = null,
                    placeholder = stringResource(R.string.re_enter_passphrase),
                    enabled = !isBusy,
                )
            }

            Spacer(Modifier.height(20.dp))

            PassphraseAddressPreview(
                passphrase = passphrase,
                previewAddress = previewAddress,
                previewBalance = previewBalance,
                showsFundsHint = mode == PassphraseMode.IMPORT,
                findAccounts = findAccounts,
                addressesAtZero = addressesAtZero,
                onSelectionChange = onImportChoiceChange,
                onSearchingChange = onSearchingChange,
                enabled = !isBusy,
            )

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = onSubmit,
                enabled = passphrase.isNotEmpty() && !isSearching && !isBusy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                // iOS: Color.gray while empty, at the half opacity of a disabled plain button.
                colors = ButtonDefaults.buttonColors(
                    containerColor = KaspaTeal,
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFF8E8E93).copy(alpha = 0.5f),
                    disabledContentColor = Color.White.copy(alpha = 0.5f),
                ),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (isBusy) {
                    com.kachat.app.ui.theme.IosActivityIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else if (isSearching && passphrase.isNotEmpty()) {
                    // Import waits for the search to pick the address holding funds.
                    com.kachat.app.ui.theme.IosActivityIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.finding_your_address), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                } else {
                    Text(
                        if (mode == PassphraseMode.CREATE) stringResource(R.string.import_words_continue) else stringResource(R.string.import_label),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                    )
                }
            }

            // The search is a network round trip: never the only way forward (iOS d23e6f7a).
            if (mode == PassphraseMode.IMPORT && isSearching && passphrase.isNotEmpty() && !isBusy) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onSkipSearch, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.skip_search_import_address_0),
                        color = KaspaTeal,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Creating opens here, past the question, so this is its only way to the explainer.
            TextButton(onClick = onExplain, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.what_is_a_passphrase),
                    color = if (isBusy) KaspaTeal.copy(alpha = 0.4f) else KaspaTeal,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * Import with no passphrase: the wallet these words were found in (iOS da467165 `foundScreen`).
 * The search runs every wallet type at once, so nobody has to know which one the words came
 * from; Import waits for it, and Skip imports onto the standard wallet's #0 without it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PassphraseFoundStep(
    isBusy: Boolean,
    previewAddress: (String) -> String?,
    previewBalance: suspend (String) -> Long?,
    findAccounts: (suspend (String) -> List<ImportAccountCandidate>?)?,
    addressesAtZero: (String) -> List<ImportAccountCandidate>,
    onImportChoiceChange: (ImportAccountChoice) -> Unit,
    isSearching: Boolean,
    onSearchingChange: (Boolean) -> Unit,
    onImport: () -> Unit,
    /** Imports on the standard wallet's address #0 without waiting for the search. */
    onSkipSearch: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalAppColors.current
    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.import_account), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
                navigationIcon = {
                    SheetBarButton(stringResource(R.string.back), onClick = onBack, modifier = Modifier.padding(start = 12.dp), enabled = !isBusy, color = colors.textPrimary)
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
                stringResource(R.string.your_account),
                color = colors.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.kachat_checks_your_words_against_every),
                color = colors.textSecondary,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(20.dp))

            PassphraseAddressPreview(
                passphrase = "",
                previewAddress = previewAddress,
                previewBalance = previewBalance,
                showsFundsHint = true,
                findAccounts = findAccounts,
                addressesAtZero = addressesAtZero,
                onSelectionChange = onImportChoiceChange,
                onSearchingChange = onSearchingChange,
                enabled = !isBusy,
            )

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = onImport,
                enabled = !isSearching && !isBusy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                // iOS: Color.gray while searching, at the half opacity of a disabled plain button.
                colors = ButtonDefaults.buttonColors(
                    containerColor = KaspaTeal,
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFF8E8E93).copy(alpha = 0.5f),
                    disabledContentColor = Color.White.copy(alpha = 0.5f),
                ),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (isBusy) {
                    com.kachat.app.ui.theme.IosActivityIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else if (isSearching) {
                    // Import waits for the search to pick the account.
                    com.kachat.app.ui.theme.IosActivityIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.finding_your_address), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                } else {
                    Text(stringResource(R.string.import_label), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                }
            }

            // The search is a network round trip: never the only way forward.
            if (isSearching && !isBusy) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onSkipSearch, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.skip_search_import_address_0),
                        color = KaspaTeal,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Plain-language explainer. Back only, so reading about it always returns to the step it was
 *  opened from: the question, or the entry step, which links it too. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PassphraseExplainerStep(mode: PassphraseMode, onBack: () -> Unit) {
    val colors = LocalAppColors.current
    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.what_is_a_passphrase), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
                navigationIcon = { SheetBarButton(stringResource(R.string.back), onClick = onBack, modifier = Modifier.padding(start = 12.dp), color = colors.textPrimary) },
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
                stringResource(R.string.seed_phrase_explainer_short_version),
                stringResource(R.string.a_passphrase_is_an_extra_word_or)
            )
            PassphraseExplainerSection(
                stringResource(R.string.it_does_not_lock_your_account),
                stringResource(R.string.this_is_the_part_people_get_wrong)
            )
            PassphraseExplainerSection(
                stringResource(R.string.why_anyone_bothers),
                stringResource(R.string.if_someone_finds_your_written_12_or)
            )
            PassphraseExplainerSection(
                stringResource(R.string.the_catch),
                stringResource(R.string.there_is_no_reset_and_no_recovery)
            )
            PassphraseExplainerSection(
                stringResource(R.string.one_more_thing_to_know),
                stringResource(R.string.if_you_type_the_wrong_passphrase_nothing)
            )
            PassphraseExplainerSection(
                stringResource(R.string.so_do_you_need_one),
                if (mode == PassphraseMode.CREATE)
                    stringResource(R.string.if_you_are_not_sure_choose_no)
                else
                    stringResource(R.string.if_you_never_set_one_up_choose)
            )
        }
    }
}

@Composable
private fun PassphraseExplainerSection(title: String, body: String) {
    val colors = LocalAppColors.current
    Column {
        Text(title, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Spacer(Modifier.height(6.dp))
        Text(body, color = colors.textSecondary, fontSize = 15.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PassphraseInputField(
    value: String,
    onValueChange: (String) -> Unit,
    reveal: Boolean,
    /** Null on the second field: iOS has one eye, on the first. */
    onToggleReveal: (() -> Unit)?,
    placeholder: String,
    enabled: Boolean = true,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
        placeholder = { Text(placeholder, color = LocalAppColors.current.textTertiary, fontSize = 17.sp) },
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 17.sp),
        singleLine = true,
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        // Password keyboard even while revealed: a visual transformation doesn't change what the
        // IME is told, and only the Password type keeps keyboards from learning, suggesting or
        // syncing what is typed (audit AND-030; RevealableSecureField does the same).
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            capitalization = KeyboardCapitalization.None,
            autoCorrect = false
        ),
        trailingIcon = onToggleReveal?.let { toggle ->
            {
                IconButton(onClick = toggle, enabled = enabled) {
                    Icon(
                        if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (reveal) stringResource(R.string.hide) else stringResource(R.string.show),
                        tint = LocalAppColors.current.textSecondary,
                    )
                }
            }
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = LocalAppColors.current.surface,
            unfocusedContainerColor = LocalAppColors.current.surface,
            focusedTextColor = LocalAppColors.current.textPrimary,
            unfocusedTextColor = LocalAppColors.current.textPrimary,
            // Disabled only while the account is being made: same field, its text dimmed.
            disabledContainerColor = LocalAppColors.current.surface,
            disabledTextColor = LocalAppColors.current.textSecondary,
            cursorColor = KaspaTeal,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent
        )
    )
}

/**
 * The brand's near-black with a green cast, taken from the KaChat banner rather than the app's
 * usual true black. Scoped to the sign-in screen, and the same in both themes on purpose - it is
 * the one place the product introduces itself.
 */
internal val KaChatSignInBackground = Color(0xFF0E1614)

/** One step up from [KaChatSignInBackground], same cast, for the saved-account rows. */
internal val KaChatSignInCard = Color(0xFF1A2624)

/**
 * FLAG_SECURE (no screenshots, screen recording or Recents thumbnail) for as long as the calling
 * screen is composed. Counted per window, because the onboarding steps that use it follow one
 * another: on seed step -> passphrase step the next screen adds the flag before the previous one
 * is disposed, and a plain clear on dispose would lift it while the passphrase is on screen
 * (audit AND-030). The flag is cleared only when the last secure screen goes.
 */
@Composable
internal fun SecureWindowWhileShown() {
    val window = (LocalContext.current as? Activity)?.window
    DisposableEffect(window) {
        if (window != null) SecureWindowCount.acquire(window)
        onDispose {
            if (window != null) SecureWindowCount.release(window)
        }
    }
}

private object SecureWindowCount {
    private val counts = java.util.WeakHashMap<android.view.Window, Int>()

    fun acquire(window: android.view.Window) {
        val n = counts[window] ?: 0
        counts[window] = n + 1
        if (n == 0) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(window: android.view.Window) {
        val n = (counts[window] ?: 1) - 1
        if (n <= 0) {
            counts.remove(window)
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            counts[window] = n
        }
    }
}
