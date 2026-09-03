package com.kachat.app.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            LazyVerticalGrid(
                columns = GridCells.Fixed(8),
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val sections = buildList {
                    if (recents.isNotEmpty()) add("Recents" to recents)
                    addAll(EmojiSections)
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
