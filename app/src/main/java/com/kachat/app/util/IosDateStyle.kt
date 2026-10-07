package com.kachat.app.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foundation's localized date styles, in the app's locale - what iOS shows wherever it uses
 * `DateFormatter.localizedString(from:dateStyle:timeStyle:)` or `Date.formatted(date:time:)`.
 * The locale is the app's (Settings > Language sets the process default), as iOS's
 * `.autoupdatingCurrent` is.
 *
 * iOS's times also follow the device's 24-Hour Time switch, whatever the locale's own habit:
 * English (US) with it on reads "Sep 24, 2026, 14:27", German with it off "24.09.2026, 2:27 PM".
 * Android's counterpart is the system's "Use 24-hour format" ([is24HourClock]), passed in as
 * `is24Hour`.
 */
object IosDateStyle {
    /** `.medium` date, `.short` time (= `.abbreviated`/`.shortened`): "Sep 24, 2026, 9:27 AM"
     *  in English (US), "24.09.2026, 09:27" in German - each in the hours [is24Hour] asks for. */
    fun mediumDateShortTime(ms: Long, is24Hour: Boolean, locale: Locale = Locale.getDefault()): String {
        val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
        if (format !is SimpleDateFormat) return format.format(Date(ms))
        val pattern = withHourCycle(format.toPattern(), is24Hour, locale)
        return SimpleDateFormat(pattern, locale).format(Date(ms))
    }

    /** `.medium` date, no time (= `.abbreviated`/`.omitted`): "Sep 24, 2026". */
    fun mediumDate(ms: Long, locale: Locale = Locale.getDefault()): String =
        DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(ms))

    private val dayPeriodLetters = setOf('a', 'b', 'B')
    private val spaces = setOf(' ', ' ', ' ')

    /**
     * The locale's [pattern] with its hours turned to the clock [is24Hour] asks for, as iOS's
     * formatter does when the 24-Hour Time switch differs from the locale's habit. To 24 hours:
     * the hour reads two-digit "HH" (iOS's "Hm" skeleton) and the AM/PM marker goes, with the
     * space beside it. To 12 hours: the hour reads "h" and the marker is added where the
     * language puts it - before the hour in Japanese, Chinese and Korean, after the time
     * elsewhere. A pattern already on the asked-for clock is returned unchanged; quoted text
     * ('г.', 'à') is never touched.
     */
    fun withHourCycle(pattern: String, is24Hour: Boolean, locale: Locale): String {
        val tokens = tokenize(pattern)
        val hourIndex = tokens.indexOfFirst { it.isField && it.text[0] in "hHkK" }
        if (hourIndex < 0) return pattern
        val hourLetter = tokens[hourIndex].text[0]
        val is24Now = hourLetter == 'H' || hourLetter == 'k'
        if (is24Now == is24Hour) return pattern
        val out = tokens.toMutableList()
        if (is24Hour) {
            out[hourIndex] = Token("HH", isField = true)
            var i = out.indexOfFirst { it.isField && it.text[0] in dayPeriodLetters }
            while (i >= 0) {
                // The space between the marker and the time goes with it: after a leading marker
                // ("a h:mm"), before a trailing one ("h:mm a"); one that is glued on ("ah:mm")
                // goes alone, so the date keeps its own space.
                val leading = i < out.indexOfFirst { it.isField && it.text[0] in "hHkK" }
                val before = out.getOrNull(i - 1)
                val after = out.getOrNull(i + 1)
                when {
                    leading && after != null && after.isSpace -> { out.removeAt(i + 1); out.removeAt(i) }
                    !leading && before != null && before.isSpace -> { out.removeAt(i); out.removeAt(i - 1) }
                    else -> out.removeAt(i)
                }
                i = out.indexOfFirst { it.isField && it.text[0] in dayPeriodLetters }
            }
        } else {
            out[hourIndex] = Token("h", isField = true)
            if (out.none { it.isField && it.text[0] in dayPeriodLetters }) {
                when (locale.language) {
                    "ja", "zh" -> out.add(hourIndex, Token("a", isField = true))
                    "ko" -> out.addAll(hourIndex, listOf(Token("a", isField = true), Token(" ", isField = false)))
                    else -> {
                        val lastTime = out.indexOfLast { it.isField && it.text[0] in "hmsS" }
                        out.addAll(lastTime + 1, listOf(Token(" ", isField = false), Token("a", isField = true)))
                    }
                }
            }
        }
        return out.joinToString("") { it.text }
    }

    private data class Token(val text: String, val isField: Boolean) {
        val isSpace: Boolean get() = !isField && text.isNotEmpty() && text.all { it in spaces }
    }

    /** Splits a pattern into runs of one field letter, quoted literals, and plain literal text
     *  (each space its own token, so the marker's neighbouring space can go with it). */
    private fun tokenize(pattern: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\'' -> {
                    var j = i + 1
                    while (j < pattern.length) {
                        if (pattern[j] == '\'') {
                            if (j + 1 < pattern.length && pattern[j + 1] == '\'') j += 2 else break
                        } else j++
                    }
                    val end = minOf(j + 1, pattern.length)
                    tokens += Token(pattern.substring(i, end), isField = false)
                    i = end
                }
                c in 'a'..'z' || c in 'A'..'Z' -> {
                    var j = i
                    while (j < pattern.length && pattern[j] == c) j++
                    tokens += Token(pattern.substring(i, j), isField = true)
                    i = j
                }
                else -> {
                    tokens += Token(c.toString(), isField = false)
                    i++
                }
            }
        }
        return tokens
    }
}

/** The system's "Use 24-hour format" - Android's counterpart to iOS's 24-Hour Time switch (it
 *  falls back to the locale's own habit when never set). */
@Composable
fun is24HourClock(): Boolean = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
