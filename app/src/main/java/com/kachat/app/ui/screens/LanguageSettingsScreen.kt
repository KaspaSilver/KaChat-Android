package com.kachat.app.ui.screens

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.kachat.app.R
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors

/**
 * In-app language override, independent of the device's system language. `SYSTEM` (tag = null)
 * means "follow the device setting" - selecting it clears any override via an empty
 * [LocaleListCompat] instead of pinning to a specific tag. Mirrors the iOS `AppLanguage` enum's
 * shape and the same 19 already-translated languages (`values-<tag>/strings.xml`; note Indonesian
 * uses the BCP 47 tag "id" here but Android's legacy resource qualifier `values-in/`).
 */
enum class AppLanguage(val tag: String?, val displayName: String) {
    SYSTEM(null, "System"),
    AR("ar", "العربية"),
    AR_EG("ar-EG", "العربية (مصر)"),
    BN("bn", "বাংলা"),
    DE("de", "Deutsch"),
    EN("en", "English"),
    ES("es", "Español"),
    FA("fa", "فارسی"),
    FR("fr", "Français"),
    HE("he", "עברית"),
    HI("hi", "हिन्दी"),
    ID("id", "Bahasa Indonesia"),
    IT("it", "Italiano"),
    JA("ja", "日本語"),
    KO("ko", "한국어"),
    PT("pt", "Português"),
    RU("ru", "Русский"),
    TR("tr", "Türkçe"),
    VI("vi", "Tiếng Việt"),
    ZH_HANS("zh-Hans", "简体中文")
}

/** Reads the currently-applied per-app language (unlike iOS, this reflects immediately - no
 *  restart needed - since `AppCompatDelegate.setApplicationLocales` recreates the Activity
 *  itself). Falls back to [AppLanguage.SYSTEM] if the current locale list is empty or doesn't
 *  match one of our supported tags (e.g. right after a fresh install). */
fun currentAppLanguage(): AppLanguage {
    val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags().takeIf { it.isNotBlank() }
        ?: return AppLanguage.SYSTEM
    return AppLanguage.entries.firstOrNull { it.tag == tag } ?: AppLanguage.SYSTEM
}

/** Applies (or clears, for [AppLanguage.SYSTEM]) the per-app language override. Triggers an
 *  immediate `Activity` recreation via AppCompat - callers that need to survive/resume after that
 *  (e.g. the Welcome Guide's language step) must persist their own "where was I" state through a
 *  ViewModel rather than local `remember` state, which does not survive recreation. */
fun applyAppLanguage(language: AppLanguage) {
    val locales = if (language == AppLanguage.SYSTEM) {
        LocaleListCompat.getEmptyLocaleList()
    } else {
        LocaleListCompat.forLanguageTags(language.tag)
    }
    AppCompatDelegate.setApplicationLocales(locales)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSettingsScreen(onBack: () -> Unit) {
    var current by remember { mutableStateOf(currentAppLanguage()) }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.language), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBackIos, null, tint = LocalAppColors.current.textPrimary, modifier = Modifier.size(20.dp))
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
            SettingsSection(title = stringResource(R.string.language)) {
                AppLanguage.entries.forEachIndexed { index, language ->
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    current = language
                                    applyAppLanguage(language)
                                }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (language == AppLanguage.SYSTEM) stringResource(R.string.system_default) else language.displayName,
                                color = LocalAppColors.current.textPrimary,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            if (current == language) {
                                Icon(Icons.Default.Check, null, tint = KaspaTeal)
                            }
                        }
                        if (index < AppLanguage.entries.lastIndex) {
                            SettingsDivider()
                        }
                    }
                }
            }
        }
    }
}
