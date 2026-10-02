package com.kachat.app.ui.screens

import com.kachat.app.util.KaspaUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The live `.kachat` screens' amounts (iOS KachatNamesLiveViews.swift `KaspaUnit.plain` /
 * `parseSompi`, KaChat 5df42b4): exact, trailing zeros dropped, and typed amounts parsed to sompi
 * with at most 8 decimals - the price and offer fields send what these return.
 */
class KachatLiveAmountsTest {
    @Test
    fun plainDropsTrailingZeros() {
        assertEquals("35", KaspaUnit.plain(3_500_000_000L))
        assertEquals("0.2", KaspaUnit.plain(20_000_000L))
        assertEquals("1.99831", KaspaUnit.plain(199_831_000L))
        assertEquals("0.00000001", KaspaUnit.plain(1L))
        assertEquals("0", KaspaUnit.plain(0L))
    }

    @Test
    fun parsesDotOrComma() {
        assertEquals(1_250_000_000L, KaspaUnit.parseSompi("12.5"))
        assertEquals(1_250_000_000L, KaspaUnit.parseSompi(" 12,5 "))
        assertEquals(50_000_000L, KaspaUnit.parseSompi(".5"))
        assertEquals(100_000_000L, KaspaUnit.parseSompi("1."))
        assertEquals(1L, KaspaUnit.parseSompi("0.00000001"))
        assertEquals(0L, KaspaUnit.parseSompi("0"))
    }

    @Test
    fun refusesAnythingElse() {
        assertNull(KaspaUnit.parseSompi(""))
        assertNull(KaspaUnit.parseSompi("1.2.3"))
        assertNull(KaspaUnit.parseSompi("0.000000001"))
        assertNull(KaspaUnit.parseSompi("-1"))
        assertNull(KaspaUnit.parseSompi("+1"))
        assertNull(KaspaUnit.parseSompi("1e3"))
        assertNull(KaspaUnit.parseSompi("abc"))
        assertNull(KaspaUnit.parseSompi("999999999999999999"))
    }
}
