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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface)
                    .padding(20.dp),
            ) {
                Text("Total", color = colors.textSecondary, fontSize = 12.sp)
                if (measuring && total == 0L) {
                    Spacer(Modifier.height(6.dp))
                    CircularProgressIndicator(color = KaspaTeal, strokeWidth = 2.dp, modifier = Modifier.height(22.dp).width(22.dp))
                } else {
                    Text(
                        CacheManager.formatted(total),
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                    )
                }
            }
            Text(
                "Everything here is downloaded or generated again when it is needed, so clearing it costs a little data and nothing else.",
                color = colors.textSecondary,
                style = MaterialTheme.typography.bodySmall,
            )

            SettingsSection(title = "What's Cached") {
                CacheManager.Category.entries.forEachIndexed { index, category ->
                    if (index > 0) SettingsDivider()
                    val bytes = sizes[category] ?: 0L
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = bytes > 0) { pendingClear = category }
                            .padding(16.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(category.title, color = colors.textPrimary, style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(2.dp))
                            Text(category.detail, color = colors.textSecondary, fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            CacheManager.formatted(bytes),
                            color = colors.textSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            SettingsSection(title = null) {
                SettingsActionItem(
                    label = "Clear All Cache",
                    icon = Icons.Default.DeleteSweep,
                    color = if (total > 0) Color(0xFFFF3B30) else Color.Gray,
                ) {
                    if (total > 0) showClearAll = true
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    pendingClear?.let { category ->
        ConfirmActionSheet(
            title = "Clear ${category.title}?",
            confirmTitle = "Clear",
            confirmSubtitle = "${CacheManager.formatted(sizes[category] ?: 0L)} freed. ${category.detail}",
            confirmIcon = Icons.Default.DeleteSweep,
            onConfirm = { scope.launch { cacheManager.clear(category); refresh() } },
            onDismiss = { pendingClear = null },
        )
    }

    if (showClearAll) {
        ConfirmActionSheet(
            title = "Clear All Cache?",
            confirmTitle = "Clear All",
            confirmSubtitle = "Frees ${CacheManager.formatted(total)}. Your messages, contacts and keys are not touched.",
            confirmIcon = Icons.Default.DeleteSweep,
            onConfirm = { scope.launch { cacheManager.clearAll(); refresh() } },
            onDismiss = { showClearAll = false },
        )
    }
}
