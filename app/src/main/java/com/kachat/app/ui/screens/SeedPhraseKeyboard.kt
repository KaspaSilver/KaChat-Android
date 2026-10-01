package com.kachat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors

/**
 * In-app seed-phrase entry: a numbered 12/24-slot grid filled via a custom on-screen QWERTY
 * keyboard with BIP39 word autocomplete. The OS keyboard never appears for the recovery words, so
 * no third-party keyboard, autocorrect/learning dictionary, or clipboard ever sees them. Only
 * letters that can extend the active word into a real BIP39 word are tappable, and a word
 * auto-commits (advancing to the next slot) once it uniquely matches a single wordlist entry.
 *
 * Stateless — the parent owns [words] (first [wordCount] entries used) and [activeSlot].
 */
@Composable
fun SeedPhraseKeyboard(
    wordCount: Int,
    words: List<String>,
    activeSlot: Int,
    wordList: List<String>,
    modifier: Modifier = Modifier,
    onWordsChange: (List<String>) -> Unit,
    onActiveSlotChange: (Int) -> Unit
) {
    val current = (words.getOrNull(activeSlot) ?: "").lowercase()

    val matches = remember(current, wordList) {
        if (current.isEmpty()) emptyList() else wordList.filter { it.startsWith(current) }
    }
    val enabledKeys = remember(current, matches, wordList) {
        val source = if (current.isEmpty()) wordList else matches
        val idx = current.length
        source.mapNotNull { if (it.length > idx) it[idx] else null }.toSet()
    }

    fun setActiveWord(value: String) {
        onWordsChange(words.toMutableList().also { it[activeSlot] = value })
    }

    fun commit(word: String) {
        setActiveWord(word)
        if (activeSlot < wordCount - 1) onActiveSlotChange(activeSlot + 1)
    }

    fun press(c: Char) {
        val newWord = (words.getOrNull(activeSlot) ?: "") + c
        val lower = newWord.lowercase()
        val m = wordList.filter { it.startsWith(lower) }
        val autoCommit = m.size == 1 && m[0] == lower
        onWordsChange(words.toMutableList().also { it[activeSlot] = if (autoCommit) m[0] else newWord })
        if (autoCommit && activeSlot < wordCount - 1) onActiveSlotChange(activeSlot + 1)
    }

    fun backspace() {
        val cur = words.getOrNull(activeSlot) ?: ""
        if (cur.isNotEmpty()) {
            setActiveWord(cur.dropLast(1))
        } else if (activeSlot > 0) {
            onActiveSlotChange(activeSlot - 1)
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Numbered slot grid (scrolls if tall)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            words.take(wordCount).chunked(3).forEachIndexed { rowIdx, rowWords ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowWords.forEachIndexed { colIdx, w ->
                        val index = rowIdx * 3 + colIdx
                        SeedSlot(
                            index = index,
                            word = w,
                            active = index == activeSlot,
                            isValidWord = w.isNotEmpty() && wordList.contains(w.lowercase()),
                            modifier = Modifier.weight(1f),
                            onClick = { onActiveSlotChange(index) }
                        )
                    }
                }
            }
        }

        // Suggestion chips (prefix bolded)
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(matches.take(30)) { w ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(KaspaTeal.copy(alpha = 0.12f))
                        .clickable { commit(w) }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(current) }
                            append(w.substring(current.length))
                        },
                        color = KaspaTeal
                    )
                }
            }
        }

        // Custom QWERTY keyboard
        val keyRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            keyRows.forEachIndexed { r, row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (r == 1) Spacer(Modifier.width(14.dp))
                    row.forEach { c ->
                        val enabled = enabledKeys.contains(c)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (enabled) LocalAppColors.current.surfaceVariant else LocalAppColors.current.surface)
                                .then(if (enabled) Modifier.clickable { press(c) } else Modifier),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = c.toString(),
                                color = if (enabled) LocalAppColors.current.textPrimary else LocalAppColors.current.textSecondary,
                                fontSize = 20.sp
                            )
                        }
                    }
                    if (r == 1) Spacer(Modifier.width(14.dp))
                    if (r == 2) {
                        Box(
                            modifier = Modifier
                                .width(54.dp)
                                .height(46.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(LocalAppColors.current.surfaceVariant)
                                .clickable { backspace() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("⌫", color = LocalAppColors.current.textPrimary, fontSize = 20.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeedSlot(
    index: Int,
    word: String,
    active: Boolean,
    isValidWord: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) KaspaTeal.copy(alpha = 0.15f) else LocalAppColors.current.surface)
            .then(if (active) Modifier.border(1.5.dp, KaspaTeal, RoundedCornerShape(8.dp)) else Modifier)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${index + 1}",
            color = LocalAppColors.current.textSecondary,
            fontSize = 11.sp,
            modifier = Modifier.width(18.dp)
        )
        Text(
            text = word.ifEmpty { " " },
            color = when {
                word.isEmpty() -> LocalAppColors.current.textSecondary
                isValidWord || active -> LocalAppColors.current.textPrimary
                else -> LocalAppColors.current.danger
            },
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            maxLines = 1
        )
    }
}
