package com.kachat.app.util

/**
 * The formatting KaPosts understands, and the single place its rules are written down.
 *
 * This is a deliberately SMALL subset - not CommonMark. Keep it byte-identical to the iOS parser
 * (`KaPostsMarkdown.swift`); a post is written on one platform and read on the others, so a rule
 * that differs shows up as one reader seeing asterisks where another sees bold.
 *
 * | Feature       | Syntax                           |
 * |---------------|----------------------------------|
 * | Bold          | `**text**`                       |
 * | Italic        | `*text*`                         |
 * | Underline     | `__text__`                       |
 * | Strikethrough | `~~text~~`                       |
 * | Subtext       | `-# text` (whole line)           |
 * | Ordered list  | `1. text` (whole line)           |
 * | Bullet list   | `* text` / `- text` (whole line) |
 * | Link          | `[label](url)`                   |
 *
 * Deliberately ABSENT: headings and spoilers. Headings would let one post shout over a feed of
 * body text, and a spoiler needs a tap-to-reveal control that the plain-text bubble has nowhere to
 * put.
 *
 * Two ambiguities the rules resolve, in this order:
 *  - `__` is UNDERLINE here, not CommonMark's second spelling of bold. The table above is the
 *    contract users are shown, so it wins.
 *  - A line-leading `* ` is a bullet, never the start of italic; `-# ` is checked before `- `, so
 *    a subtext line is not read as a bullet whose text begins with `#`.
 *
 * The parser emits the text as it should READ (markers removed, `• ` and `1. ` prefixes
 * materialised) plus style spans addressed by character offset into that output, so callers can
 * run the existing URL/@mention linkifier over the same string and layer these on top.
 */
object KaPostsMarkdown {

    data class Style(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strikethrough: Boolean = false,
        /** Whole-line de-emphasis (`-# `), rendered smaller and secondary. */
        val subtext: Boolean = false,
        /** Set for `[label](url)` spans; bare URLs are left to the caller's linkifier. */
        val link: String? = null,
    ) {
        val isPlain: Boolean get() = this == PLAIN

        companion object {
            val PLAIN = Style()
        }
    }

    /** [start], [end] are character offsets into [Rendered.text]. */
    data class Span(val start: Int, val end: Int, val style: Style)

    data class Rendered(val text: String, val spans: List<Span>) {
        /**
         * True when the source actually contained formatting. Callers use it to skip the whole
         * styling path for the overwhelmingly common plain post.
         */
        val hasFormatting: Boolean get() = spans.isNotEmpty()
    }

    fun render(source: String): Rendered {
        val out = StringBuilder()
        val spans = mutableListOf<Span>()
        source.split("\n").forEachIndexed { index, line ->
            if (index > 0) out.append('\n')
            renderLine(line, out, spans)
        }
        return Rendered(out.toString(), mergeAdjacent(spans))
    }

    // MARK: - Block level

    private fun renderLine(line: String, out: StringBuilder, spans: MutableList<Span>) {
        var lineStyle = Style.PLAIN
        var prefix = ""

        // Leading spaces are allowed before a marker so an indented list still reads as one.
        val indent = line.takeWhile { it == ' ' }
        val body = line.substring(indent.length)
        var content = line

        when {
            body.startsWith("-# ") -> {
                // Checked before the "- " bullet, or "-# x" would become a bullet reading "# x".
                lineStyle = lineStyle.copy(subtext = true)
                content = body.substring(3)
                prefix = indent
            }
            body.startsWith("* ") || body.startsWith("- ") -> {
                content = body.substring(2)
                prefix = "$indent• "
            }
            else -> {
                val marker = orderedMarker(body)
                if (marker != null) {
                    content = body.substring(marker.consumed)
                    prefix = "$indent${marker.number}. "
                }
            }
        }

        out.append(prefix)
        val lineStart = out.length
        parseInline(content, lineStyle, out, spans)
        // The bullet/number prefix carries the line's own style too, so a subtext line is
        // uniformly small rather than starting at body size.
        if (!lineStyle.isPlain && prefix.isNotEmpty()) {
            spans += Span(lineStart - prefix.length, lineStart, lineStyle)
        }
    }

    private data class OrderedMarker(val number: Int, val consumed: Int)

    /** `12. ` at the start of a line: the number and how many characters it consumed. */
    private fun orderedMarker(line: String): OrderedMarker? {
        val digits = line.takeWhile { it.isDigit() }
        if (digits.isEmpty() || digits.length > 3) return null
        if (!line.substring(digits.length).startsWith(". ")) return null
        val number = digits.toIntOrNull() ?: return null
        return OrderedMarker(number, digits.length + 2)
    }

    // MARK: - Inline level

    /**
     * Delimiters, longest first: `**` must be tried before `*`, or bold would parse as an italic
     * run whose text starts with `*`.
     */
    private val INLINE_DELIMITERS: List<Pair<String, (Style) -> Style>> = listOf(
        "**" to { s: Style -> s.copy(bold = true) },
        "__" to { s: Style -> s.copy(underline = true) },
        "~~" to { s: Style -> s.copy(strikethrough = true) },
        "*" to { s: Style -> s.copy(italic = true) },
    )

    private fun parseInline(
        source: String,
        style: Style,
        out: StringBuilder,
        spans: MutableList<Span>,
    ) {
        var i = 0
        var runStart = out.length

        fun flushPlainRun() {
            if (!style.isPlain && out.length > runStart) spans += Span(runStart, out.length, style)
        }

        while (i < source.length) {
            val link = matchLink(source, i)
            if (link != null) {
                flushPlainRun()
                parseInline(link.label, style.copy(link = link.url), out, spans)
                i = link.next
                runStart = out.length
                continue
            }
            val emphasis = matchEmphasis(source, i)
            if (emphasis != null) {
                flushPlainRun()
                parseInline(emphasis.content, emphasis.apply(style), out, spans)
                i = emphasis.next
                runStart = out.length
                continue
            }
            out.append(source[i])
            i++
        }
        flushPlainRun()
    }

    private data class LinkMatch(val label: String, val url: String, val next: Int)

    /**
     * `[label](url)` at [i]. The target must carry a scheme we are willing to open, so a post
     * cannot dress `javascript:` up as friendly link text.
     */
    private fun matchLink(source: String, i: Int): LinkMatch? {
        if (source[i] != '[') return null
        val labelEnd = source.indexOf(']', i + 1)
        if (labelEnd <= i + 1) return null
        if (labelEnd + 1 >= source.length || source[labelEnd + 1] != '(') return null
        val urlEnd = source.indexOf(')', labelEnd + 2)
        if (urlEnd <= labelEnd + 2) return null

        val label = source.substring(i + 1, labelEnd)
        val target = resolveLinkTarget(source.substring(labelEnd + 2, urlEnd)) ?: return null
        return LinkMatch(label, target, urlEnd + 1)
    }

    /** Schemes a post is allowed to send a reader to. */
    private val ALLOWED_LINK_SCHEMES = setOf("http", "https", "kachat")

    private val SCHEME_PREFIX = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*:")

    /**
     * Turns a link target into one we are willing to open, or null.
     *
     * The scheme is decided BEFORE anything is prepended. An earlier version prepended `https://`
     * to any target without `://` and then checked the scheme, which meant `javascript:alert(1)`
     * became `https://javascript:alert(1)` and sailed through the allowlist - the check was
     * inspecting a string it had just rewritten. Anything that already names a scheme must name an
     * allowed one; only a genuinely bare host gets https assumed for it.
     */
    fun resolveLinkTarget(raw: String): String? {
        val target = raw.trim()
        if (target.isEmpty() || target.any { it.isWhitespace() || it.code < 0x20 }) return null
        // Scheme-relative ("//host/path") keeps its host and just gains https.
        if (target.startsWith("//")) return "https:$target"
        val scheme = SCHEME_PREFIX.find(target)?.value?.dropLast(1)?.lowercase()
        if (scheme != null) return if (scheme in ALLOWED_LINK_SCHEMES) target else null
        // No scheme at all: a bare host, which is the common case in a social post.
        return "https://$target"
    }

    private data class EmphasisMatch(val content: String, val apply: (Style) -> Style, val next: Int)

    /**
     * An emphasis run opening at [i], or null. Requires a closing marker on the same line with at
     * least one character between: `****` and a lone `*` stay literal text.
     */
    private fun matchEmphasis(source: String, i: Int): EmphasisMatch? {
        for ((marker, apply) in INLINE_DELIMITERS) {
            if (!source.startsWith(marker, i)) continue
            val contentStart = i + marker.length
            // An opening marker followed by whitespace is almost always literal punctuation
            // ("2 * 3 = 6"), not formatting.
            if (contentStart >= source.length || source[contentStart].isWhitespace()) continue
            var j = contentStart
            while (j < source.length) {
                if (source.startsWith(marker, j)) {
                    if (j <= contentStart) break
                    return EmphasisMatch(source.substring(contentStart, j), apply, j + marker.length)
                }
                j++
            }
        }
        return null
    }

    /**
     * Collapses spans that touch and share a style, so a bold run split by a nested parse does not
     * become several attribute runs.
     */
    private fun mergeAdjacent(spans: List<Span>): List<Span> {
        if (spans.size < 2) return spans
        val sorted = spans.sortedBy { it.start }
        val merged = mutableListOf<Span>()
        for (span in sorted) {
            val last = merged.lastOrNull()
            if (last != null && last.end == span.start && last.style == span.style) {
                merged[merged.size - 1] = last.copy(end = span.end)
            } else {
                merged += span
            }
        }
        return merged
    }

    // MARK: - Toolbar edits

    /**
     * What the composer's formatting buttons do to the text.
     *
     * Kept beside the parser, and mirrored exactly in `KaPostsMarkdown.swift`, because these are
     * the rules that decide what gets WRITTEN - a platform that wraps a selection differently
     * produces posts the other platform renders differently.
     *
     * Every action toggles: applying it to text that already has that formatting removes it, which
     * is what a person expects from a Bold button and what stops a double tap producing
     * `****text****`.
     */
    enum class ToolbarAction { BOLD, ITALIC, UNDERLINE, STRIKETHROUGH, BULLET_LIST, NUMBERED_LIST, SUBTEXT, LINK }

    /** Text plus where the selection should sit afterwards, both in character offsets. */
    data class Edit(val text: String, val selectionStart: Int, val selectionEnd: Int)

    /**
     * Placeholder inserted when a link is added with nothing selected, and the target that is
     * pre-selected so typing replaces it.
     */
    const val LINK_LABEL_PLACEHOLDER = "link text"
    const val LINK_TARGET_PLACEHOLDER = "https://"

    fun inlineMarker(action: ToolbarAction): String? = when (action) {
        ToolbarAction.BOLD -> "**"
        ToolbarAction.ITALIC -> "*"
        ToolbarAction.UNDERLINE -> "__"
        ToolbarAction.STRIKETHROUGH -> "~~"
        else -> null
    }

    private fun linePrefix(action: ToolbarAction): String? = when (action) {
        ToolbarAction.BULLET_LIST -> "* "
        ToolbarAction.SUBTEXT -> "-# "
        else -> null
    }

    fun apply(action: ToolbarAction, text: String, selectionStart: Int, selectionEnd: Int): Edit {
        val start = selectionStart.coerceIn(0, text.length)
        val end = selectionEnd.coerceIn(start, text.length)
        return when {
            action == ToolbarAction.LINK -> applyLink(text, start, end)
            inlineMarker(action) != null -> applyInline(inlineMarker(action)!!, text, start, end)
            else -> applyLine(action, text, start, end)
        }
    }

    private fun applyInline(marker: String, text: String, start: Int, end: Int): Edit {
        val width = marker.length

        // Already wrapped, either inside the selection ("**bold**" highlighted) or just outside it
        // ("bold" highlighted between the markers). Both read as "this is bold" to the user, so
        // both unwrap.
        if (end - start >= 2 * width &&
            text.regionMatches(start, marker, 0, width) &&
            text.regionMatches(end - width, marker, 0, width)
        ) {
            val inner = text.substring(start + width, end - width)
            return Edit(text.substring(0, start) + inner + text.substring(end), start, start + inner.length)
        }
        if (start >= width && end + width <= text.length &&
            text.regionMatches(start - width, marker, 0, width) &&
            text.regionMatches(end, marker, 0, width)
        ) {
            val inner = text.substring(start, end)
            return Edit(
                text.substring(0, start - width) + inner + text.substring(end + width),
                start - width,
                start - width + inner.length,
            )
        }

        val inner = text.substring(start, end)
        val result = text.substring(0, start) + marker + inner + marker + text.substring(end)
        // Nothing selected: leave the caret between the markers, ready to type.
        val caret = start + width
        return Edit(result, caret, if (inner.isEmpty()) caret else caret + inner.length)
    }

    /**
     * Applies a line prefix to every line the selection touches, toggling off when they all already
     * have it. Numbered lists renumber from 1 so a re-ordered selection stays sane.
     */
    private fun applyLine(action: ToolbarAction, text: String, start: Int, end: Int): Edit {
        val lineStart = lineStartIndex(text, start)
        val lineEnd = lineEndIndex(text, end)
        val lines = text.substring(lineStart, lineEnd).split("\n")

        val stripped = lines.map(::stripLineMarkers)
        val alreadyApplied = lines.isNotEmpty() && lines.all { hasMarker(action, it) }
        val rebuilt = when {
            alreadyApplied -> stripped
            action == ToolbarAction.NUMBERED_LIST -> stripped.mapIndexed { index, line -> "${index + 1}. $line" }
            linePrefix(action) != null -> stripped.map { linePrefix(action)!! + it }
            else -> stripped
        }

        val replacement = rebuilt.joinToString("\n")
        val result = text.substring(0, lineStart) + replacement + text.substring(lineEnd)
        return Edit(result, lineStart, lineStart + replacement.length)
    }

    private fun hasMarker(action: ToolbarAction, line: String): Boolean {
        val trimmed = line.dropWhile { it == ' ' }
        return when (action) {
            ToolbarAction.BULLET_LIST -> trimmed.startsWith("* ") || trimmed.startsWith("- ")
            ToolbarAction.SUBTEXT -> trimmed.startsWith("-# ")
            ToolbarAction.NUMBERED_LIST -> orderedMarker(trimmed) != null
            else -> false
        }
    }

    /**
     * Removes whichever block marker a line already carries, so switching a bullet to a number does
     * not leave "1. * item".
     */
    private fun stripLineMarkers(line: String): String {
        val body = line.dropWhile { it == ' ' }
        if (body.startsWith("-# ")) return body.substring(3)
        if (body.startsWith("* ") || body.startsWith("- ")) return body.substring(2)
        val marker = orderedMarker(body)
        if (marker != null) return body.substring(marker.consumed)
        return body
    }

    private fun lineStartIndex(text: String, index: Int): Int {
        var i = index.coerceIn(0, text.length)
        while (i > 0 && text[i - 1] != '\n') i--
        return i
    }

    private fun lineEndIndex(text: String, index: Int): Int {
        var i = index.coerceIn(0, text.length)
        while (i < text.length && text[i] != '\n') i++
        return i
    }

    private fun applyLink(text: String, start: Int, end: Int): Edit {
        val selected = text.substring(start, end)
        val label = selected.ifEmpty { LINK_LABEL_PLACEHOLDER }
        val inserted = "[$label]($LINK_TARGET_PLACEHOLDER)"
        val result = text.substring(0, start) + inserted + text.substring(end)
        // Pre-select the part the user has to replace: the target when they highlighted their own
        // label, the label when they highlighted nothing.
        if (selected.isEmpty()) {
            return Edit(result, start + 1, start + 1 + label.length)
        }
        val targetStart = start + 1 + label.length + 2
        return Edit(result, targetStart, targetStart + LINK_TARGET_PLACEHOLDER.length)
    }
}
