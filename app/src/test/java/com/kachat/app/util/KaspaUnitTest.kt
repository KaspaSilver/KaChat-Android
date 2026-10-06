package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests never call KaspaNetwork.init, so they run on mainnet: nothing is relabelled. */
class KaspaUnitTest {

    @Test
    fun `mainnet unit is KAS`() {
        assertEquals("KAS", KaspaUnit.symbol)
    }

    @Test
    fun `mainnet label leaves text unchanged`() {
        listOf(
            "Sent 1.5 KAS",
            "Received 0.2 KAS — thanks",
            "Fee (KAS)",
            "1.5 KASを受信",
            "Kaspa",
            "TKAS",
            "",
        ).forEach { assertEquals(it, KaspaUnit.label(it)) }
    }

    // MARK: - sompiFromUserText (iOS 16b64bc, IOS-010 / AND-013)

    @Test
    fun `two point three is exactly 2,3 KAS, not truncated`() {
        // 2.3 * 1e8 is 229999999.99999997 as a Double; a toLong() paid 2.29999999 KAS.
        assertEquals(230_000_000L, KaspaUnit.sompiFromUserText("2.3"))
        assertEquals(230_000_000L, KaspaUnit.sompiFromUserText("2,3"))
        assertEquals(1_000_000L, KaspaUnit.sompiFromUserText("0.01"))
        assertEquals(29_000_000L, KaspaUnit.sompiFromUserText("0.29"))
    }

    @Test
    fun `comma and dot decimals`() {
        assertEquals(150_000_000L, KaspaUnit.sompiFromUserText("1.5"))
        assertEquals(150_000_000L, KaspaUnit.sompiFromUserText("1,5"))
        assertEquals(150_000_000L, KaspaUnit.sompiFromUserText(" 1,5 "))
        assertEquals(50_000_000L, KaspaUnit.sompiFromUserText(".5"))
        assertEquals(50_000_000L, KaspaUnit.sompiFromUserText(",5"))
        assertEquals(100_000_000L, KaspaUnit.sompiFromUserText("1."))
        assertEquals(0L, KaspaUnit.sompiFromUserText("0"))
        assertEquals(100_000_000L, KaspaUnit.sompiFromUserText("0001"))
    }

    @Test
    fun `arabic and persian digits and the arabic decimal separator`() {
        assertEquals(150_000_000L, KaspaUnit.sompiFromUserText("\u0661\u066B\u0665"))
        assertEquals(250_000_000L, KaspaUnit.sompiFromUserText("\u06F2.\u06F5"))
    }

    @Test
    fun `eight decimals, never more`() {
        assertEquals(1L, KaspaUnit.sompiFromUserText("0.00000001"))
        assertEquals(123_456_789L, KaspaUnit.sompiFromUserText("1.23456789"))
        assertEquals(123_456_789L, KaspaUnit.sompiFromUserText("1,23456789"))
        assertNull(KaspaUnit.sompiFromUserText("0.000000001"))
        assertNull(KaspaUnit.sompiFromUserText("1.234567891"))
    }

    @Test
    fun `huge amounts are refused, never overflow`() {
        assertEquals(KaspaUnit.MAX_TYPED_SOMPI, KaspaUnit.sompiFromUserText("29000000000"))
        assertNull(KaspaUnit.sompiFromUserText("29000000000.00000001"))
        assertNull(KaspaUnit.sompiFromUserText("99999999999"))
        assertNull(KaspaUnit.sompiFromUserText("999999999999"))
        assertNull(KaspaUnit.sompiFromUserText("184467440737.09551616"))
        assertNull(KaspaUnit.sompiFromUserText("9".repeat(400)))
        assertNull(KaspaUnit.sompiFromUserText("1e12"))
        assertNull(KaspaUnit.sompiFromUserText("1E3"))
        assertNull(KaspaUnit.sompiFromUserText("Infinity"))
        assertNull(KaspaUnit.sompiFromUserText("NaN"))
    }

    @Test
    fun `anything else is refused`() {
        listOf("", " ", ".", ",", "1.2.3", "1,2,3", "-1", "+1", "1 000", "abc", "1.5 KAS", "0x10").forEach {
            assertNull(it, KaspaUnit.sompiFromUserText(it))
        }
    }

    @Test
    fun `sanitize keeps digits and one decimal point, at most 8 decimals`() {
        assertEquals("1.5", KaspaUnit.sanitizeAmountInput("1,5"))
        assertEquals("1.53", KaspaUnit.sanitizeAmountInput("1.5.3"))
        assertEquals("12.12345678", KaspaUnit.sanitizeAmountInput("12.123456789"))
        assertEquals("1000", KaspaUnit.sanitizeAmountInput("1 000 KAS"))
        assertEquals("1.5", KaspaUnit.sanitizeAmountInput("\u0661\u066B\u0665"))
        assertEquals("", KaspaUnit.sanitizeAmountInput("-e+"))
    }

    @Test
    fun `plain is exact`() {
        assertEquals("2.3", KaspaUnit.plain(230_000_000L))
        assertEquals("28999999999.99999999", KaspaUnit.plain(KaspaUnit.MAX_TYPED_SOMPI - 1))
    }
}
