package com.kachat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.kachat.app.ui.theme.LocalAppColors

/** Curated common-emoji categories - a hand-picked subset (not the full Unicode range) since
 *  there's no Android SDK equivalent of iOS's `Unicode.Scalar.properties.isEmoji` scan to
 *  auto-generate one from. Purely for [EmojiPickerDialog]'s grid; unrelated to any protocol-level
 *  emoji validation elsewhere in the app. */
private object CommonEmojis {
    val smileys = listOf(
        "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "🙃", "😉", "😊", "😇", "🥰", "😍",
        "🤩", "😘", "😗", "😚", "😙", "😋", "😛", "😜", "🤪", "😝", "🤑", "🤗", "🤭", "🤫", "🤔",
        "😐", "😑", "😶", "😏", "😒", "🙄", "😬", "🤥", "😌", "😔", "😪", "🤤", "😴", "😷", "🤒",
        "🤕", "🤢", "🤮", "🥵", "🥶", "🥴", "😵", "🤯", "🤠", "🥳", "😎", "🤓", "🧐", "😕", "😟",
        "🙁", "☹️", "😮", "😯", "😲", "😳", "🥺", "😦", "😧", "😨", "😰", "😥", "😢", "😭", "😱",
        "😖", "😣", "😞", "😓", "😩", "😫", "🥱", "😤", "😡", "😠", "🤬", "😈", "👿", "💀", "☠️"
    )
    val gestures = listOf(
        "👋", "🤚", "🖐️", "✋", "🖖", "👌", "🤌", "🤏", "✌️", "🤞", "🤟", "🤘", "🤙", "👈", "👉",
        "👆", "🖕", "👇", "☝️", "👍", "👎", "✊", "👊", "🤛", "🤜", "👏", "🙌", "👐", "🤲", "🤝",
        "🙏", "✍️", "💅", "🤳", "💪", "🦾", "🦵", "🦿", "👂", "🦻", "👃", "🧠", "🫀", "🦷", "👀",
        "👁️", "👅", "👄"
    )
    val animals = listOf(
        "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯", "🦁", "🐮", "🐷", "🐸", "🐵",
        "🙈", "🙉", "🙊", "🐔", "🐧", "🐦", "🐤", "🦆", "🦅", "🦉", "🦇", "🐺", "🐗", "🐴", "🦄",
        "🐝", "🐛", "🦋", "🐌", "🐞", "🐜", "🦗", "🕷️", "🦂", "🐢", "🐍", "🦎", "🦖", "🦕", "🐙",
        "🦑", "🦐", "🦞", "🦀", "🐡", "🐠", "🐟", "🐬", "🐳", "🐋", "🦈", "🐊", "🐅", "🐆", "🦓",
        "🦍", "🦧", "🐘", "🦛", "🦏", "🐪", "🐫", "🦒", "🦘", "🐃", "🐂", "🐄", "🐎", "🐖", "🐏",
        "🐑", "🦙", "🐐", "🦌", "🐕", "🐩", "🐈", "🐓", "🦃", "🦚", "🦜", "🦢", "🦩", "🕊️", "🐇"
    )
    val food = listOf(
        "🍏", "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓", "🫐", "🍈", "🍒", "🍑", "🥭", "🍍",
        "🥥", "🥝", "🍅", "🍆", "🥑", "🥦", "🥬", "🥒", "🌶️", "🫑", "🌽", "🥕", "🫒", "🧄", "🧅",
        "🥔", "🍠", "🥐", "🥯", "🍞", "🥖", "🥨", "🧀", "🥚", "🍳", "🧈", "🥞", "🧇", "🥓", "🥩",
        "🍗", "🍖", "🌭", "🍔", "🍟", "🍕", "🫓", "🥪", "🥙", "🧆", "🌮", "🌯", "🫔", "🥗", "🥘",
        "🍝", "🍜", "🍲", "🍛", "🍣", "🍱", "🥟", "🍤", "🍙", "🍚", "🍘", "🍢", "🍡", "🍧", "🍨",
        "🍦", "🥧", "🧁", "🍰", "🎂", "🍮", "🍭", "🍬", "🍫", "🍿", "🍩", "🍪", "🌰", "🥜", "🍯"
    )
    val activities = listOf(
        "⚽", "🏀", "🏈", "⚾", "🥎", "🎾", "🏐", "🏉", "🥏", "🎱", "🪀", "🏓", "🏸", "🏒", "🏑",
        "🥍", "🏏", "🥅", "⛳", "🪁", "🏹", "🎣", "🤿", "🥊", "🥋", "🎽", "🛹", "🛼", "🛷", "⛸️",
        "🥌", "🎿", "⛷️", "🏂", "🪂", "🏋️", "🤸", "🤺", "🤾", "🏌️", "🏇", "🧘", "🏄", "🏊", "🤽",
        "🚣", "🧗", "🚵", "🚴", "🏆", "🥇", "🥈", "🥉", "🏅", "🎖️", "🎗️", "🎫", "🎟️", "🎪", "🤹",
        "🎭", "🎨", "🎬", "🎤", "🎧", "🎼", "🎹", "🥁", "🎷", "🎺", "🎸", "🪕", "🎻", "🎲", "♟️"
    )
    val objects = listOf(
        "⌚", "📱", "💻", "⌨️", "🖥️", "🖨️", "🖱️", "💽", "💾", "💿", "📀", "📷", "📸", "📹", "🎥",
        "📞", "☎️", "📟", "📠", "📺", "📻", "🎙️", "⏱️", "⏲️", "⏰", "🕰️", "⌛", "⏳", "📡", "🔋",
        "🔌", "💡", "🔦", "🕯️", "🪔", "🧯", "🛢️", "💵", "💴", "💶", "💷", "💰", "💳", "💎", "⚖️",
        "🧰", "🔧", "🔨", "⚒️", "🛠️", "⛏️", "🔩", "⚙️", "🧱", "⛓️", "🧲", "🔫", "💣", "🧨", "🪓",
        "🔪", "🗡️", "⚔️", "🛡️", "🚬", "⚰️", "🪦", "⚱️", "🏺", "🔮", "📿", "🧿", "💈", "⚗️", "🔭"
    )
    val symbols = listOf(
        "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔", "❣️", "💕", "💞", "💓", "💗",
        "💖", "💘", "💝", "💟", "☮️", "✝️", "☪️", "🕉️", "☸️", "✡️", "🔯", "🕎", "☯️", "☦️", "🛐",
        "⛎", "♈", "♉", "♊", "♋", "♌", "♍", "♎", "♏", "♐", "♑", "♒", "♓", "🆔", "⚛️",
        "✅", "❌", "❓", "❗", "‼️", "⁉️", "💯", "🔥", "✨", "🎉", "🎊", "⭐", "🌟", "💫", "⚡"
    )

    val categories: List<Pair<String, List<String>>> = listOf(
        "Smileys" to smileys,
        "Gestures" to gestures,
        "Animals" to animals,
        "Food" to food,
        "Activities" to activities,
        "Objects" to objects,
        "Symbols" to symbols
    )
}

/**
 * A native-feeling in-app emoji grid (categorized tabs + scrollable grid), shown as a [Dialog].
 * Exists so picking a reaction/quick-reaction emoji never has to bring up the full system
 * keyboard just to reach its emoji tab (which also varies by which keyboard app the user has
 * installed) - mirrors iOS's `DesktopEmojiPickerView`, just without a matching full Unicode-scan
 * data source (Android has no equivalent of `Unicode.Scalar.properties.isEmoji` to build one
 * from), so [CommonEmojis] is a hand-curated subset instead of the whole emoji range.
 */
@Composable
fun EmojiPickerDialog(onDismissRequest: () -> Unit, onSelect: (String) -> Unit) {
    var selectedCategory by remember { mutableStateOf(0) }

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
                    CommonEmojis.categories.forEachIndexed { index, (name, emojis) ->
                        val isSelected = index == selectedCategory
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clickable { selectedCategory = index }
                                .background(
                                    if (isSelected) LocalAppColors.current.textPrimary.copy(alpha = 0.1f) else androidx.compose.ui.graphics.Color.Transparent,
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(emojis.first(), fontSize = 18.sp)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(44.dp),
                    modifier = Modifier.heightIn(max = 300.dp)
                ) {
                    items(CommonEmojis.categories[selectedCategory].second) { emoji ->
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clickable {
                                    onSelect(emoji)
                                    onDismissRequest()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(emoji, fontSize = 26.sp)
                        }
                    }
                }
            }
        }
    }
}
