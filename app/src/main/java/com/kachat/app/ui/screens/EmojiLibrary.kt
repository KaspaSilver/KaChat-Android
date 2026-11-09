package com.kachat.app.ui.screens

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.SportsSoccer
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.Locale

/** iOS `DesktopEmojiCategory`, in its order. The title is also a search keyword, untranslated. */
internal enum class EmojiCategory(val title: String, val icon: ImageVector) {
    SMILEYS("Smileys", Icons.Outlined.EmojiEmotions),
    PEOPLE("People", Icons.Outlined.People),
    NATURE("Nature", Icons.Outlined.Eco),
    FOOD("Food", Icons.Outlined.Restaurant),
    TRAVEL("Travel", Icons.Outlined.DirectionsCar),
    ACTIVITIES("Activities", Icons.Outlined.SportsSoccer),
    OBJECTS("Objects", Icons.Outlined.Lightbulb),
    SYMBOLS("Symbols", Icons.Outlined.FavoriteBorder),
    FLAGS("Flags", Icons.Outlined.Flag),
}

/** iOS `DesktopEmojiItem`. */
internal data class EmojiItem(val emoji: String, val category: EmojiCategory, val keywords: String)

/**
 * iOS `DesktopEmojiLibrary`: every emoji scalar, then their skin tones, the common sequences and the
 * region flags, first appearance winning. Purely for the pickers' grids; unrelated to any
 * protocol-level emoji validation elsewhere in the app.
 */
internal object EmojiLibrary {
    private const val VARIATION_SELECTOR = "️"
    private val WHITESPACE = Regex("[\\s\\p{Z}]+")

    private val SKIN_TONES = listOf(
        "🏻" to "light skin tone",
        "🏼" to "medium light skin tone",
        "🏽" to "medium skin tone",
        "🏾" to "medium dark skin tone",
        "🏿" to "dark skin tone",
    )

    val items: List<EmojiItem> by lazy { makeItems() }
    private val itemsByCategory by lazy { items.groupBy { it.category } }

    fun emoji(category: EmojiCategory): List<EmojiItem> = itemsByCategory[category].orEmpty()

    /** An item matches when every word of [query] is somewhere in its emoji or keywords. */
    fun searchItems(query: String): List<EmojiItem> {
        val tokens = query.trim().lowercase().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return items
        return items.filter { item ->
            val searchableText = "${item.emoji} ${item.keywords}"
            tokens.all { searchableText.contains(it) }
        }
    }

    private class ScalarItem(val scalar: String, val emoji: String, val category: EmojiCategory, val keywords: String, val isModifierBase: Boolean)

    private fun makeItems(): List<EmojiItem> {
        val seen = HashSet<String>()
        val output = ArrayList<EmojiItem>()
        fun append(emoji: String, category: EmojiCategory, keywords: String) {
            if (seen.add(emoji)) output.add(EmojiItem(emoji, category, keywords.lowercase()))
        }

        val scalarItems = makeScalarItems()
        for (item in scalarItems) append(item.emoji, item.category, item.keywords)
        for (item in scalarItems) {
            if (!item.isModifierBase) continue
            for ((tone, toneKeywords) in SKIN_TONES) {
                append(item.scalar + tone, EmojiCategory.PEOPLE, "${item.keywords} $toneKeywords")
            }
        }
        EMOJI_COMMON_SEQUENCES.lineSequence().filter { it.isNotEmpty() }.forEach { line ->
            val (emoji, category, keywords) = line.split(';', limit = 3)
            append(emoji, EmojiCategory.valueOf(category.uppercase()), keywords)
        }
        // The name in the app's language, as iOS's AppLocalization.locale.
        val locale = AppCompatDelegate.getApplicationLocales().get(0) ?: Locale.getDefault()
        EMOJI_FLAG_REGIONS.split(WHITESPACE).filter { it.isNotEmpty() }.forEach { code ->
            val flag = code.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
            append(flag, EmojiCategory.FLAGS, "flag ${code.lowercase()} ${Locale("", code).getDisplayCountry(locale)}")
        }
        return output
    }

    private fun makeScalarItems(): List<ScalarItem> =
        EMOJI_SCALAR_TABLES.flatMap { table ->
            table.lineSequence().filter { it.isNotEmpty() }.map { line ->
                val (hex, flags, name) = line.split(';', limit = 3)
                val value = hex.toInt(16)
                val scalar = String(Character.toChars(value))
                val category = categoryOf(value)
                ScalarItem(
                    scalar = scalar,
                    emoji = if ('P' in flags) scalar else scalar + VARIATION_SELECTOR,
                    category = category,
                    keywords = "${name.lowercase()} ${category.title.lowercase()}",
                    isModifierBase = 'B' in flags,
                )
            }.toList()
        }

    /** iOS `category(for:)`, whose ranges overlap: the first match wins, so the order matters. */
    private fun categoryOf(value: Int): EmojiCategory = when (value) {
        in 0x1F600..0x1F64F, in 0x1F970..0x1F97F -> EmojiCategory.SMILEYS
        in 0x1F300..0x1F32C, in 0x1F330..0x1F335, in 0x1F337..0x1F343, in 0x1F400..0x1F43F, in 0x1F980..0x1F9AE -> EmojiCategory.NATURE
        in 0x1F32D..0x1F37F, in 0x1F950..0x1F96F, in 0x1F9C0..0x1F9CB -> EmojiCategory.FOOD
        in 0x1F680..0x1F6FF, in 0x1F3E0..0x1F3F0 -> EmojiCategory.TRAVEL
        in 0x1F380..0x1F3DF, in 0x1F3F8..0x1F3FF, in 0x1F93C..0x1F945 -> EmojiCategory.ACTIVITIES
        in 0x1F466..0x1F487, in 0x1F590..0x1F596, in 0x1F645..0x1F64F, in 0x1F900..0x1F93B, in 0x1F9B0..0x1F9DF -> EmojiCategory.PEOPLE
        in 0x1F4A0..0x1F5FF, in 0x1F9E0..0x1FAFF -> EmojiCategory.OBJECTS
        else -> EmojiCategory.SYMBOLS
    }
}
