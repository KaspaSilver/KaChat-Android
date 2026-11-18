package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS `kasText`: switching between KAS and the currency changes only what's shown, never the
 *  amount the caller holds and sends. */
class KaspaFiatAmountStateTest {

    private val price = 0.0371234

    @Test
    fun `switching to the currency and back keeps the exact KAS amount`() {
        var held = ""
        val state = KaspaFiatAmountState { held = it }
        state.onDisplayTextChange("1.23456789", price)
        assertEquals("1.23456789", held)

        state.toggleMode(price)
        assertTrue(state.isFiatMode)
        assertEquals("0.05", state.displayText)
        // the label next to Max names the KAS that will be sent, not one worked back from 0.05
        assertEquals("1.23456789 KAS", state.conversionLabelText(price, "usd"))

        state.toggleMode(price)
        assertEquals("1.23456789", state.displayText)
        // worked back from the rounded 0.05 it would have read about 1.3469
        assertEquals("1.23456789", held)
    }

    @Test
    fun `Max and an exact amount survive a switch too`() {
        var held = ""
        val state = KaspaFiatAmountState { held = it }
        state.setMaxKas(12.3456789, price)
        state.toggleMode(price)
        state.toggleMode(price)
        assertEquals("12.3456789", state.displayText)
        assertEquals("12.3456789", held)

        state.setExactKas("0.00012345", price)
        state.toggleMode(price)
        assertEquals("0.00012345 KAS", state.conversionLabelText(price, "usd"))
        state.toggleMode(price)
        assertEquals("0.00012345", state.displayText)
    }

    @Test
    fun `an amount typed in the currency is converted once`() {
        var held = ""
        val state = KaspaFiatAmountState { held = it }
        state.toggleMode(price)
        state.onDisplayTextChange("1", price)
        assertEquals(formatKasAmount(1 / price), held)
        state.toggleMode(price)
        assertEquals(held, state.displayText)
    }

    @Test
    fun `Max from sompi is exact at any balance`() {
        var held = ""
        val state = KaspaFiatAmountState { held = it }
        // 90,000,000.12345679 KAS: past where a Double can be a sompi off (iOS 83806b3d)
        assertEquals("90000000.12345679", state.setMaxSompi(9_000_000_012_345_679L, price))
        assertEquals("90000000.12345679", held)
        assertEquals("90000000.12345679", state.displayText)
        assertEquals("0.00000001", state.setMaxSompi(1L, price))
        assertEquals("35", state.setMaxSompi(3_500_000_000L, price))
    }
}
