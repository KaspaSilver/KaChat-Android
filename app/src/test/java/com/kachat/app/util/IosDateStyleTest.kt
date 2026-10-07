package com.kachat.app.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.text.DateFormat
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
        val text = IosDateStyle.mediumDateShortTime(moment, Locale.US)
        assertTrue(text, text.startsWith("Sep 24, 2026"))
        assertTrue(text, text.contains("2:27"))
        assertTrue(text, text.contains("PM"))
        assertFalse(text, text.contains("14:27"))
    }

    @Test
    fun `German reads day first with a 24-hour time`() {
        val text = IosDateStyle.mediumDateShortTime(moment, Locale.GERMANY)
        assertTrue(text, text.startsWith("24.09.2026"))
        assertTrue(text, text.contains("14:27"))
    }

    @Test
    fun `it is the platform's medium and short styles, not a fixed pattern`() {
        for (locale in listOf(Locale.US, Locale.FRANCE, Locale.JAPAN, Locale("ru"), Locale("ar"))) {
            assertEquals(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(moment)),
                IosDateStyle.mediumDateShortTime(moment, locale),
            )
        }
    }

    @Test
    fun `the default is the app's locale`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals(IosDateStyle.mediumDateShortTime(moment, Locale.GERMANY), IosDateStyle.mediumDateShortTime(moment))
        assertEquals(IosDateStyle.mediumDate(moment, Locale.GERMANY), IosDateStyle.mediumDate(moment))
    }

    @Test
    fun `the date alone has no time`() {
        val text = IosDateStyle.mediumDate(moment, Locale.US)
        assertEquals("Sep 24, 2026", text)
    }
}
