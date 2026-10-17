package com.kachat.app.util

import android.content.Context
import java.util.Locale

/**
 * A byte count as iOS's `ByteCountFormatter` writes it (`.file` style, the default): decimal
 * units, "Zero KB" for nothing, whole kilobytes, one decimal for megabytes and two for gigabytes,
 * a trailing ".0" dropped - "Zero KB", "493 KB", "1.4 MB", "2.25 GB". English only: other
 * languages keep Android's own wording, which iOS's translations are not known for.
 */
object IosByteCount {
    fun format(context: Context, bytes: Long, locale: Locale = Locale.getDefault()): String {
        if (locale.language != "en") return android.text.format.Formatter.formatShortFileSize(context, bytes)
        return format(bytes)
    }

    /** The English form, for tests and callers without a Context. */
    fun format(bytes: Long): String {
        val b = bytes.coerceAtLeast(0L)
        return when {
            b == 0L -> "Zero KB"
            b == 1L -> "1 byte"
            b < 1_000L -> "$b bytes"
            b < 999_500L -> "${Math.round(b / 1_000.0)} KB"
            b < 999_950_000L -> "${trim(String.format(Locale.US, "%.1f", b / 1_000_000.0))} MB"
            else -> "${trim(String.format(Locale.US, "%.2f", b / 1_000_000_000.0))} GB"
        }
    }

    private fun trim(number: String): String =
        if (number.contains('.')) number.trimEnd('0').trimEnd('.') else number
}
