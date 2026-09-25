package com.kachat.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A pasted amount arrives in whatever shape it was copied from - a price with a currency symbol,
 * a European decimal comma, thousands separators. Every one of them has to add (iOS 266131a).
 */
class PortfolioNumberTest {

    @Test
    fun `plain numbers parse`() {
        assertEquals(12.0, parseAmount("12")!!, 0.0)
        assertEquals(9.6, parseAmount(" 9.60 ")!!, 1e-9)
        assertEquals(-3.5, parseAmount("-3.5")!!, 1e-9)
    }

    @Test
    fun `grouping commas are not decimals`() {
        assertEquals(1234.56, parseAmount("1,234.56")!!, 1e-9)
        assertEquals(1234567.0, parseAmount("1,234,567")!!, 1e-9)
    }

    @Test
    fun `a decimal comma is a decimal`() {
        assertEquals(1.5, parseAmount("1,5")!!, 1e-9)
        assertEquals(1234.56, parseAmount("1.234,56")!!, 1e-9)
        assertEquals(0.75, parseAmount("0,75")!!, 1e-9)
    }

    @Test
    fun `grouping dots are not decimals`() {
        assertEquals(1234567.0, parseAmount("1.234.567")!!, 1e-9)
    }

    @Test
    fun `currency symbols and spaces are ignored`() {
        assertEquals(9.6, parseAmount("$ 9.60")!!, 1e-9)
        assertEquals(1234.56, parseAmount("€1,234.56")!!, 1e-9)
    }

    @Test
    fun `nothing numeric is nothing`() {
        assertNull(parseAmount(""))
        assertNull(parseAmount("   "))
        assertNull(parseAmount("abc"))
    }
}
