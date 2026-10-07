package com.kachat.app.util

import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Foundation's localized date styles, in the app's locale - what iOS shows wherever it uses
 * `DateFormatter.localizedString(from:dateStyle:timeStyle:)` or `Date.formatted(date:time:)`.
 * The locale is the app's (Settings > Language sets the process default), as iOS's
 * `.autoupdatingCurrent` is.
 */
object IosDateStyle {
    /** `.medium` date, `.short` time (= `.abbreviated`/`.shortened`): "Sep 24, 2026, 9:27 AM"
     *  in English (US), "24.09.2026, 09:27" in German. */
    fun mediumDateShortTime(ms: Long, locale: Locale = Locale.getDefault()): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(ms))

    /** `.medium` date, no time (= `.abbreviated`/`.omitted`): "Sep 24, 2026". */
    fun mediumDate(ms: Long, locale: Locale = Locale.getDefault()): String =
        DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(ms))
}
