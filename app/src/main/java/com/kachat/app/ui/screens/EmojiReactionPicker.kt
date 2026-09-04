package com.kachat.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors

/**
 * Emoji you have actually reacted with, most recent first.
 *
 * Local and shared across every chat type: a reaction is a reaction whether it lands on a 1:1
 * message, a group message or a broadcast, so recents built in one place are there in the others
 * too. Mirrors iOS's `EmojiRecentsStore`.
 */
object EmojiRecents {
    private const val PREFS = "kachat_emoji_reactions"
    private const val KEY = "recents"
    private const val LIMIT = 24

    fun load(context: android.content.Context): List<String> =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?.split(" ")
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    fun record(context: android.content.Context, emoji: String) {
        if (emoji.isEmpty()) return
        val updated = (listOf(emoji) + load(context).filter { it != emoji }).take(LIMIT)
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, updated.joinToString(" "))
            .apply()
    }
}

/**
 * Curated emoji sections behind the quick bar's "+".
 *
 * Deliberately curated rather than every codepoint Unicode defines: the long tail is unsearchable
 * by eye and would only inflate the sheet. Kept in step with iOS so the same reaction sits in the
 * same place on both.
 */
private val EmojiSections: List<Pair<String, List<String>>> = listOf(
    "Smileys" to listOf("😀","😃","😄","😁","😆","😅","🤣","😂","🙂","🙃","😉","😊","😇","🥰","😍","🤩","😘","😚","😋","😛","😜","🤪","🤗","🤭","🤫","🤔","🤐","😐","😑","😶","😏","😒","🙄","😬","😮","😪","😴","😌","😔","😕","🙁","😣","😖","😫","😩","🥺","😢","😭","😤","😠","😡","🤬","🤯","😳","🥵","🥶","😱","😨","😰","🤥","💩"),
    "Gestures" to listOf("👍","👎","👌","🤌","🤞","🤟","🤘","🤙","👈","👉","👆","👇","👋","🤝","🙏","💪","🫶","👏","🙌","👐","🤲","🤛"),
    "Hearts" to listOf("❤️","🧡","💛","💚","💙","💜","🖤","🤍","🤎","💔","💕","💞","💓","💗","💖","💘","💝"),
    "Celebration" to listOf("🔥","✨","🎉","🎊","🥳","🏆","🥇","💯","⭐","🌟","💫","⚡","💥","🚀","🎯","🎁"),
    "Objects" to listOf("👀","🧠","💡","💰","💸","💎","📈","📉","🔒","🔑","⏰","📌","✅","❌","⚠️","❓","❗"),
    "Animals & Nature" to listOf("🐶","🐱","🦊","🐻","🐼","🐨","🦁","🐮","🐷","🐸","🐵","🐔","🐧","🦄","🐝","🦋","🌸","🌻","🌈","🌊","🌙","☀️")
)

/**
 * An emoji's Unicode name, lowercased ("fire", "heavy black heart", "grinning face"), so search
 * can match what an emoji IS rather than the character itself - typing "fire" only finds it if
 * something looks up a name. Taken from the first codepoint, which is the emoji proper; any
 * variation selector after it carries no name worth searching.
 *
 * Cached: the whole grid is filtered on every keystroke.
 */
private val emojiNameCache = mutableMapOf<String, String>()

private fun emojiName(emoji: String): String = emojiNameCache.getOrPut(emoji) {
    try {
        Character.getName(emoji.codePointAt(0))?.lowercase().orEmpty()
    } catch (e: Exception) {
        ""
    }
}

/** The full emoji list behind the quick bar's "+", as a half sheet with a Recents section. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiReactionPickerSheet(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var recents by remember { mutableStateOf(EmojiRecents.load(context)) }
    var query by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.background,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Text(
                "React",
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
            TextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search emoji", color = colors.textSecondary) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = colors.textSecondary) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp)),
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
            LazyVerticalGrid(
                columns = GridCells.Fixed(8),
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val trimmed = query.trim().lowercase()
                val sections = buildList {
                    if (trimmed.isEmpty()) {
                        if (recents.isNotEmpty()) add("Recents" to recents)
                        addAll(EmojiSections)
                    } else {
                        EmojiSections.forEach { (title, emojis) ->
                            val matches = emojis.filter { it.contains(trimmed) || emojiName(it).contains(trimmed) }
                            if (matches.isNotEmpty()) add(title to matches)
                        }
                    }
                }
                if (sections.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "No emoji match that.",
                            color = colors.textSecondary,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        )
                    }
                }
                sections.forEach { (title, emojis) ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            title,
                            color = colors.textSecondary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp, start = 4.dp),
                        )
                    }
                    items(emojis) { emoji ->
                        Box(
                            modifier = Modifier
                                .heightIn(min = 40.dp)
                                .clickable {
                                    EmojiRecents.record(context, emoji)
                                    recents = EmojiRecents.load(context)
                                    onPick(emoji)
                                    onDismiss()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(emoji, fontSize = 26.sp)
                        }
                    }
                }
            }
        }
    }
}
