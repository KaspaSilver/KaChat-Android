package com.kachat.app.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * iOS writes a scheduled post's time with `.medium` date and `.short` time in the app's locale
 * (KaPostsView's "Schedule for ..."), never a fixed pattern.
 */
class IosDateStyleTest {
    private lateinit var savedZone: TimeZone
    private lateinit var savedLocale: Locale

    // 2026-09-24 14:27 UTC.
    private val moment = 1_790_260_020_000L

    @Before
    fun pinZone() {
        savedZone = TimeZone.getDefault()
        savedLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restore() {
        TimeZone.setDefault(savedZone)
        Locale.setDefault(savedLocale)
    }

    @Test
    fun `English US reads month first with a 12-hour time`() {
        val text = IosDateStyle.mediumDateShortTime(moment, is24Hour = false, locale = Locale.US)
        assertTrue(text, text.startsWith("Sep 24, 2026"))
        assertTrue(text, text.contains("2:27"))
        assertTrue(text, text.contains("PM"))
        assertFalse(text, text.contains("14:27"))
    }

    @Test
    fun `German reads day first with a 24-hour time`() {
        val text = IosDateStyle.mediumDateShortTime(moment, is24Hour = true, locale = Locale.GERMANY)
        assertTrue(text, text.startsWith("24.09.2026"))
        assertTrue(text, text.contains("14:27"))
    }

    @Test
    fun `it is the platform's medium and short styles, not a fixed pattern`() {
        for (locale in listOf(Locale.US, Locale.FRANCE, Locale.JAPAN, Locale("ru"), Locale("ar"))) {
            val platform = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale) as SimpleDateFormat
            // On the locale's own clock, nothing is changed.
            val localeIs24 = platform.toPattern().contains('H')
            assertEquals(
                platform.format(Date(moment)),
                IosDateStyle.mediumDateShortTime(moment, localeIs24, locale),
            )
        }
    }

    @Test
    fun `the default is the app's locale`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals(IosDateStyle.mediumDateShortTime(moment, is24Hour = true, locale = Locale.GERMANY), IosDateStyle.mediumDateShortTime(moment, is24Hour = true))
        assertEquals(IosDateStyle.mediumDate(moment, Locale.GERMANY), IosDateStyle.mediumDate(moment))
    }

    @Test
    fun `the date alone has no time`() {
        val text = IosDateStyle.mediumDate(moment, Locale.US)
        assertEquals("Sep 24, 2026", text)
    }

    @Test
    fun `the long date spells the month out, as SwiftUI's date style does`() {
        assertEquals("September 24, 2026", IosDateStyle.longDate(moment, Locale.US))
    }

    // iOS follows the device's 24-Hour Time switch over the locale's habit.

    @Test
    fun `English US with the 24-hour switch on reads 14 27 and no PM`() {
        val text = IosDateStyle.mediumDateShortTime(moment, is24Hour = true, locale = Locale.US)
        assertTrue(text, text.startsWith("Sep 24, 2026"))
        assertTrue(text, text.endsWith("14:27"))
        assertFalse(text, text.contains("PM"))
    }

    @Test
    fun `a morning in 24 hours keeps iOS's two-digit hour`() {
        val nineAm = moment - 5 * 3_600_000L // 09:27 UTC
        val text = IosDateStyle.mediumDateShortTime(nineAm, is24Hour = true, locale = Locale.US)
        assertTrue(text, text.endsWith("09:27"))
    }

    @Test
    fun `German with the 24-hour switch off reads 2 27 PM`() {
        val text = IosDateStyle.mediumDateShortTime(moment, is24Hour = false, locale = Locale.GERMANY)
        assertTrue(text, text.startsWith("24.09.2026"))
        assertTrue(text, text.contains("2:27"))
        assertFalse(text, text.contains("14:27"))
        assertTrue(text, text.endsWith("PM"))
    }

    @Test
    fun `Japanese 12-hour puts the marker before the hour`() {
        assertEquals("y/MM/dd ah:mm", IosDateStyle.withHourCycle("y/MM/dd H:mm", is24Hour = false, locale = Locale.JAPAN))
    }

    @Test
    fun `Korean 12-hour puts the marker and a space before the hour`() {
        assertEquals("y. M. d. a h:mm", IosDateStyle.withHourCycle("y. M. d. HH:mm", is24Hour = false, locale = Locale.KOREA))
    }

    @Test
    fun `24 hours drops the marker with the space beside it, either side`() {
        assertEquals("MMM d, y, HH:mm", IosDateStyle.withHourCycle("MMM d, y, h:mm a", is24Hour = true, locale = Locale.US))
        assertEquals("MMM d, y, HH:mm", IosDateStyle.withHourCycle("MMM d, y, h:mm\u202Fa", is24Hour = true, locale = Locale.US))
        assertEquals("y/MM/dd HH:mm", IosDateStyle.withHourCycle("y/MM/dd ah:mm", is24Hour = true, locale = Locale.JAPAN))
        assertEquals("y. M. d. HH:mm", IosDateStyle.withHourCycle("y. M. d. a h:mm", is24Hour = true, locale = Locale.KOREA))
    }

    @Test
    fun `quoted text is left alone`() {
        assertEquals("d MMM y 'г'., h:mm a", IosDateStyle.withHourCycle("d MMM y 'г'., HH:mm", is24Hour = false, locale = Locale("ru")))
        assertEquals("d MMM y 'à' HH:mm", IosDateStyle.withHourCycle("d MMM y 'à' h:mm a", is24Hour = true, locale = Locale.FRANCE))
    }

    @Test
    fun `a pattern already on the asked-for clock is unchanged`() {
        assertEquals("MMM d, y, h:mm a", IosDateStyle.withHourCycle("MMM d, y, h:mm a", is24Hour = false, locale = Locale.US))
        assertEquals("dd.MM.y, HH:mm", IosDateStyle.withHourCycle("dd.MM.y, HH:mm", is24Hour = true, locale = Locale.GERMANY))
        assertEquals("MMM d, y", IosDateStyle.withHourCycle("MMM d, y", is24Hour = true, locale = Locale.US))
    }
}
