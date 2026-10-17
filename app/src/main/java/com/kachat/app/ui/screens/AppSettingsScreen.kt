package com.kachat.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
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
 * screen ([SecuritySettingsItems], [DiagnosticsSettingsSections]) — one source of truth. The
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
                SettingsNavigationItem(stringResource(R.string.security), Icons.Default.Security, onClick = onOpenSecurity)
                HorizontalDivider(color = LocalAppColors.current.divider)
                SettingsNavigationItem(stringResource(R.string.connection), Icons.Default.Language, onClick = onOpenConnection)
                HorizontalDivider(color = LocalAppColors.current.divider)
                SettingsNavigationItem(stringResource(R.string.diagnostics), Icons.Default.MonitorHeart, onClick = onOpenDiagnostics)
            }
        }
        Spacer(Modifier.height(IosFormSectionGap))
    }
}

/**
 * App-wide Customization page (iOS AppSettingsView's appCustomizationPage): the Appearance /
 * Language / Currency pickers alone - the in-account page's account-tier rows (Customize Dock,
 * Show Fee Estimate) are not here. Language and Currency are menus in the row now, as on iOS, so
 * the two navigation callbacks are no longer used; they stay for the callers' sake.
 */
@Composable
fun AppCustomizationScreen(
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onNavigateToLanguage: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onNavigateToCurrency: () -> Unit,
    walletViewModel: WalletViewModel = hiltViewModel(),
) {
    AppSettingsPageScaffold(title = stringResource(R.string.customization), onBack = onBack) {
        SettingsFormSection(title = stringResource(R.string.customization)) {
            AppWideCustomizationPickers(walletViewModel)
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
        SettingsFormSection(title = stringResource(R.string.security)) {
            SecuritySettingsItems(
                walletViewModel = walletViewModel,
                onNavigateToChildMode = onNavigateToChildMode
            )
        }
    }
}

/** App-wide Connection page - same sections as in-account Settings > Connection. */
@Composable
fun AppConnectionScreen(
    onBack: () -> Unit,
    onOpenConnectionSettings: () -> Unit,
    onOpenExplorer: () -> Unit,
    chatViewModel: com.kachat.app.viewmodels.ChatViewModel = hiltViewModel(),
) {
    val explorer by chatViewModel.kaspaExplorer.collectAsState()
    AppSettingsPageScaffold(title = stringResource(R.string.connection), onBack = onBack) {
        ConnectionHubSections(
            explorerName = explorer.displayName,
            onOpenConnectionSettings = onOpenConnectionSettings,
            onOpenExplorer = onOpenExplorer,
        )
    }
}

/** App-wide Diagnostics page — the same sections as in-account Settings > Diagnostics via [DiagnosticsSettingsSections]. */
@Composable
fun AppDiagnosticsScreen(onBack: () -> Unit) {
    AppSettingsPageScaffold(title = stringResource(R.string.diagnostics), onBack = onBack) {
        DiagnosticsSettingsSections()
    }
}

/** Shared chrome for the App Settings hub and its category pages — the same iOS Form page the
 *  in-account Settings pages use ([SettingsFormPage]: inline bar with the back chevron on the
 *  left, the title centred, sections spaced as iOS spaces them). */
@Composable
private fun AppSettingsPageScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    SettingsFormPage(title = title, onBack = onBack) {
        content()
    }
}
