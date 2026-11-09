package com.kachat.app.ui.screens

import androidx.compose.ui.res.stringResource
import com.kachat.app.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors

/**
 * The emoji button inside a chat's message field (iOS `ComposerEmojiButton`, d1844582): "face.smiling"
 * at 20 in the secondary colour, in a 28 box. The sheet stays up so several can be picked in a row;
 * each lands in the message as it's tapped.
 */
@Composable
fun ComposerEmojiButton(onSelect: (String) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(28.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { showPicker = true },
    ) {
        // Material's glyph fills about 20 of its 24.
        Icon(
            Icons.Outlined.EmojiEmotions,
            contentDescription = stringResource(R.string.emoji_picker),
            tint = LocalAppColors.current.textSecondary,
            modifier = Modifier.size(24.dp),
        )
    }
    if (showPicker) {
        ComposerEmojiPickerSheet(onDismiss = { showPicker = false }, onSelect = onSelect)
    }
}

/**
 * iOS `DesktopEmojiPickerView` in a `[.medium, .large]` sheet: the category bar, "Search emoji" and
 * the grid, padded 12, spaced 10. Tapping an emoji keeps the sheet up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposerEmojiPickerSheet(onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    var selectedCategory by remember { mutableStateOf(EmojiCategory.SMILEYS) }
    var search by remember { mutableStateOf("") }
    val shown = remember(selectedCategory, search) {
        if (search.isBlank()) EmojiLibrary.emoji(selectedCategory) else EmojiLibrary.searchItems(search)
    }
    val halfScreen = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp / 2).dp
    com.kachat.app.ui.theme.IosSheetColors {
        val colors = LocalAppColors.current
        var float by remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
        ModalBottomSheet(
            scrimColor = com.kachat.app.ui.theme.IosSheetScrim,
            modifier = Modifier.iosFloatingSheet(float),
            shape = iosFloatingSheetShape(float),
            tonalElevation = com.kachat.app.ui.theme.IosSheetTonalElevation,
            windowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars,
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.background,
            dragHandle = null,
        ) {
            Column(Modifier.navigationBarsPadding()) {
                IosSheetDetents(height = halfScreen, largeDetent = true, onFloat = { float = it }) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 17.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            EmojiCategory.entries.forEach { category ->
                                val isSelected = category == selectedCategory
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .background(
                                            if (isSelected) KaspaTeal.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent,
                                            CircleShape,
                                        )
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {
                                            selectedCategory = category
                                            search = ""
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        category.icon,
                                        contentDescription = category.title,
                                        tint = if (isSelected) KaspaTeal else colors.textSecondary,
                                        modifier = Modifier.size(17.dp),
                                    )
                                }
                            }
                        }
                        IosSearchField(search, { search = it }, stringResource(R.string.search_emoji))
                        if (shown.isEmpty()) {
                            Box(Modifier.fillMaxWidth().heightIn(min = 220.dp), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.no_emoji_found), color = colors.textSecondary, fontSize = 12.sp)
                            }
                        } else {
                            // iOS: adaptive columns from 36 up, 6 apart, each emoji in a 38 frame.
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(36.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth().weight(1f),
                            ) {
                                items(shown, key = { it.emoji }) { item ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(38.dp)
                                            .semantics { contentDescription = item.keywords }
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                            ) { onSelect(item.emoji) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(item.emoji, fontSize = 26.sp)
                                    }
                                }
                            }
                        }
                    }
                    IosSheetGrabber(Modifier.align(Alignment.TopCenter))
                }
            }
        }
    }
}

/**
 * A native-feeling in-app emoji grid (categorized tabs + scrollable grid), shown as a [Dialog].
 * Exists so picking a reaction/quick-reaction emoji never has to bring up the full system
 * keyboard just to reach its emoji tab (which also varies by which keyboard app the user has
 * installed) - mirrors iOS's `DesktopEmojiPickerView`, from the same [EmojiLibrary].
 */
@Composable
fun EmojiPickerDialog(onDismissRequest: () -> Unit, onSelect: (String) -> Unit) {
    var selectedCategory by remember { mutableStateOf(EmojiCategory.SMILEYS) }

    Dialog(onDismissRequest = onDismissRequest) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .background(LocalAppColors.current.surface, RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            Column {
                Text(
                    "Choose Emoji",
                    color = LocalAppColors.current.textPrimary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    EmojiCategory.entries.forEach { category ->
                        val isSelected = category == selectedCategory
                        // A share of the width each: nine fixed tabs can overflow a dialog.
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clickable { selectedCategory = category }
                                .background(
                                    if (isSelected) LocalAppColors.current.textPrimary.copy(alpha = 0.1f) else androidx.compose.ui.graphics.Color.Transparent,
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                category.icon,
                                contentDescription = category.title,
                                tint = if (isSelected) KaspaTeal else LocalAppColors.current.textSecondary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(44.dp),
                    modifier = Modifier.heightIn(max = 300.dp)
                ) {
                    items(EmojiLibrary.emoji(selectedCategory), key = { it.emoji }) { item ->
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clickable {
                                    onSelect(item.emoji)
                                    onDismissRequest()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(item.emoji, fontSize = 26.sp)
                        }
                    }
                }
            }
        }
    }
}
