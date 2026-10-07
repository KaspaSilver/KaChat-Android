package com.kachat.app.util

/**
 * iOS's `.truncationMode(.middle)`: when a string does not fit, its middle gives way to "…" and
 * both ends stay. An address or a transaction id is recognised by its start and its end, so an
 * end-truncated one is unrecognisable. Compose's own `TextOverflow.MiddleEllipsis` is newer than
 * the Compose this app builds with; [com.kachat.app.ui.screens.MiddleEllipsisText] draws with this.
 */
object MiddleTruncation {
    const val ELLIPSIS = "…"

    /**
     * [text] keeping [keep] of its characters - the first half (one more when [keep] is odd) and
     * the last half - with "…" between. Whole code points, so an emoji is never split. [text]
     * itself when it has no more than [keep].
     */
    fun truncated(text: String, keep: Int): String {
        val count = text.codePointCount(0, text.length)
        if (keep >= count) return text
        val kept = keep.coerceAtLeast(0)
        val head = (kept + 1) / 2
        val tail = kept / 2
        val headEnd = text.offsetByCodePoints(0, head)
        val tailStart = text.offsetByCodePoints(text.length, -tail)
        return text.substring(0, headEnd) + ELLIPSIS + text.substring(tailStart)
    }

    /**
     * The longest middle-truncated form of [text] that [fits]: [text] itself when it fits,
     * otherwise as many characters as fit around "…" (just "…" when none do).
     */
    fun fit(text: String, fits: (String) -> Boolean): String {
        if (fits(text)) return text
        val count = text.codePointCount(0, text.length)
        var low = 0
        var high = count - 1
        var best = ELLIPSIS
        while (low <= high) {
            val mid = (low + high) ushr 1
            val candidate = truncated(text, mid)
            if (fits(candidate)) {
                best = candidate
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }
}
