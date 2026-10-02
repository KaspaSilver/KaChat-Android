package com.kachat.app.util

import org.junit.Assert.assertEquals
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
}
