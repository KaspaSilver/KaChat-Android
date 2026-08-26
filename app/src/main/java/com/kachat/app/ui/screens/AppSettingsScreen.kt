package com.kachat.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.R
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.WalletViewModel

/**
 * "App Settings" reached from the accounts screen's top-right gear (no active account) — Android
 * port of iOS's `AppSettingsView` (OnboardingView's gearshape sheet). Contains ONLY the app-wide
 * settings tier: Customization (appearance/language/currency — NOT Customize Dock), Security
 * (biometric toggles + Child Mode, all device-global), Connection settings, and Diagnostics.
 * Everything account-specific (dock, chats, contacts, storage, chat history, notifications,
 * danger zone) is deliberately absent.
 *
 * Every leaf page is the SAME composable the in-account Settings navigates to
 * ([LanguageSettingsScreen], [CurrencySettingsScreen], [ConnectionSettingsScreen],
 * [ChildModeSettingsScreen]) or a shared item-row composable extracted from the in-account
 * screen ([SecuritySettingsItems], [DiagnosticsSettingsItems]) — one source of truth. The
 * category pages here only add the hub chrome around them.
 */
@Composable
fun AppSettingsHubScreen(
    onBack: () -> Unit,
    onOpenCustomization: () -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenConnection: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    AppSettingsPageScaffold(title = stringResource(R.string.app_settings), onBack = onBack) {
        Surface(
            color = LocalAppColors.current.surface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                SettingsNavigationItem(stringResource(R.string.customization), Icons.Default.Palette, onClick = onOpenCustomization)
                HorizontalDivider(color = LocalAppColors.current.divider)
                SettingsNavigationItem(stringResource(R.string.security), Icons.Default.Shield, onClick = onOpenSecurity)
                HorizontalDivider(color = LocalAppColors.current.divider)
                SettingsNavigationItem(stringResource(R.string.connection), Icons.Default.Language, onClick = onOpenConnection)
                HorizontalDivider(color = LocalAppColors.current.divider)
                SettingsNavigationItem(stringResource(R.string.diagnostics), Icons.Default.MonitorHeart, onClick = onOpenDiagnostics)
            }
        }
    }
}

/**
 * App-wide Customization page: Dark Mode + Language + Currency (all global settings) — the
 * app-wide subset of the in-account Customization section, minus the account-tier rows
 * (Customize Dock, Show Setup Guides). The Language/Currency rows push the exact screens the
 * in-account settings use.
 */
@Composable
fun AppCustomizationScreen(
    onBack: () -> Unit,
    onNavigateToLanguage: () -> Unit,
    onNavigateToCurrency: () -> Unit,
    walletViewModel: WalletViewModel = hiltViewModel(),
) {
    val darkModeEnabled by walletViewModel.darkModeEnabled.collectAsState()
    val currencyCode by walletViewModel.currency.collectAsState()

    AppSettingsPageScaffold(title = stringResource(R.string.customization), onBack = onBack) {
        SettingsSection(title = stringResource(R.string.customization)) {
            SettingsSwitchItem(stringResource(R.string.dark_mode), darkModeEnabled) { enabled ->
                walletViewModel.setDarkModeEnabled(enabled)
            }
            SettingsDivider()
            SettingsNavigationItem(stringResource(R.string.language), Icons.Default.Translate, onClick = onNavigateToLanguage)
            SettingsDivider()
            SettingsNavigationItem(stringResource(R.string.currency), Icons.Default.AttachMoney, currencyCode.uppercase(), onClick = onNavigateToCurrency)
        }
    }
}

/** App-wide Security page — same rows as in-account Settings > Security via [SecuritySettingsItems]. */
@Composable
fun AppSecurityScreen(
    onBack: () -> Unit,
    onNavigateToChildMode: () -> Unit,
    walletViewModel: WalletViewModel = hiltViewModel(),
) {
    AppSettingsPageScaffold(title = stringResource(R.string.security), onBack = onBack) {
        SettingsSection(title = stringResource(R.string.security)) {
            SecuritySettingsItems(
                walletViewModel = walletViewModel,
                onNavigateToChildMode = onNavigateToChildMode
            )
        }
    }
}

/** App-wide Diagnostics page — same rows as in-account Settings > Diagnostics via [DiagnosticsSettingsItems]. */
@Composable
fun AppDiagnosticsScreen(onBack: () -> Unit) {
    AppSettingsPageScaffold(title = stringResource(R.string.diagnostics), onBack = onBack) {
        SettingsSection(title = stringResource(R.string.diagnostics)) {
            DiagnosticsSettingsItems()
        }
    }
}

/** Shared chrome for the App Settings hub and its category pages — the app's standard settings
 *  sub-screen look (centered bold title, teal back arrow, scrolling 16dp-padded column). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppSettingsPageScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            content()
        }
    }
}
