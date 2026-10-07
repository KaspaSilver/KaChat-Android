package com.kachat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.AccountBox
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Language
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import com.kachat.app.R
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.services.CacheManager
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import kotlinx.coroutines.launch

/**
 * What the app is holding that it could fetch again, with a way to drop any of it.
 *
 * Nothing on this screen is user data - see [CacheManager] for why each category qualifies. That
 * is the whole reason it can offer a plain "Clear" rather than the warnings the Danger Zone needs.
 */
/** iOS CacheManager's `systemImage`s: person.crop.square, globe, doc. */
private fun CacheManager.Category.icon(): ImageVector = when (this) {
    CacheManager.Category.PROFILES -> Icons.Outlined.AccountBox
    CacheManager.Category.WEB_RESPONSES -> Icons.Outlined.Language
    CacheManager.Category.TEMPORARY_FILES -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheSettingsScreen(
    onBack: () -> Unit,
    cacheManager: CacheManager,
) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    var sizes by remember { mutableStateOf<Map<CacheManager.Category, Long>>(emptyMap()) }
    var measuring by remember { mutableStateOf(true) }
    var pendingClear by remember { mutableStateOf<CacheManager.Category?>(null) }
    var showClearAll by remember { mutableStateOf(false) }

    suspend fun refresh() {
        measuring = true
        sizes = cacheManager.sizes()
        measuring = false
    }

    LaunchedEffect(Unit) { refresh() }
    val total = sizes.values.sum()

    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Cache", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBackIos, "Back", tint = KaspaTeal)
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // Total, on one row, with the footer under its section (iOS CacheSettingsPage).
            Column {
                SettingsSection(title = null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.total), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        if (measuring && total == 0L) {
                            com.kachat.app.ui.theme.IosActivityIndicator(color = colors.textSecondary, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        } else {
                            Text(
                                CacheManager.formatted(total),
                                color = colors.textPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 17.sp,
                                style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
                            )
                        }
                    }
                }
                SettingsFooter("Everything here is downloaded or generated again when it is needed, so clearing it costs a little data and nothing else.")
            }

            SettingsSection(title = "What's Cached") {
                CacheManager.Category.entries.forEachIndexed { index, category ->
                    if (index > 0) SettingsDivider(54.dp)
                    val bytes = sizes[category] ?: 0L
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = bytes > 0) { pendingClear = category }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        // the category's symbol, accent, in a 26-wide column (iOS `systemImage`)
                        Box(Modifier.width(26.dp).padding(top = 1.dp), contentAlignment = Alignment.Center) {
                            Icon(category.icon(), contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(category.title), color = colors.textPrimary, style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(category.detail), color = colors.textSecondary, fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            CacheManager.formatted(bytes),
                            color = colors.textSecondary,
                            fontSize = 15.sp,
                            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
                        )
                    }
                }
            }

            // Clear All Cache: red text, no icon; dimmed while there is nothing to clear (iOS).
            SettingsSection(title = null) {
                Text(
                    "Clear All Cache",
                    color = colors.danger,
                    fontSize = 17.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = total > 0) { showClearAll = true }
                        .alpha(if (total > 0) 1f else 0.4f)
                        .heightIn(min = 52.dp)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    pendingClear?.let { category ->
        ConfirmActionSheet(
            title = "Clear ${stringResource(category.title)}?",
            confirmTitle = stringResource(R.string.clear),
            confirmSubtitle = "${CacheManager.formatted(sizes[category] ?: 0L)} freed. ${stringResource(category.detail)}",
            confirmIcon = Icons.Outlined.Delete,
            onConfirm = { scope.launch { cacheManager.clear(category); refresh() } },
            onDismiss = { pendingClear = null },
        )
    }

    if (showClearAll) {
        ConfirmActionSheet(
            title = "Clear All Cache?",
            confirmTitle = "Clear All",
            confirmSubtitle = "Frees ${CacheManager.formatted(total)}. Your messages, contacts and keys are not touched.",
            confirmIcon = Icons.Outlined.Delete,
            onConfirm = { scope.launch { cacheManager.clearAll(); refresh() } },
            onDismiss = { showClearAll = false },
        )
    }
}
