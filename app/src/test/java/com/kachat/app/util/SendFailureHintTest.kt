package com.kachat.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS ChatDetailView.shouldShowRetryHint. */
class SendFailureHintTest {
    @Test
    fun `a network failure gets the hint`() {
        assertTrue(SendFailureHint.showsNetworkHint("The connection timed out. Check your connection and try again."))
    }

    @Test
    fun `iOS's insufficient balance message goes without it`() {
        assertFalse(
            SendFailureHint.showsNetworkHint("Planned spend 0.2 KAS, but available balance 0.1 KAS is less than required.")
        )
    }

    @Test
    fun `the template's pieces must appear in order`() {
        assertTrue(SendFailureHint.showsNetworkHint("is less than required. Planned spend 1 KAS, but available balance 0 KAS"))
    }

    @Test
    fun `Android's insufficient funds wording goes without it`() {
        assertFalse(SendFailureHint.showsNetworkHint("Insufficient funds: Needed 100, have 10"))
        assertFalse(SendFailureHint.showsNetworkHint("Insufficient funds to cover network fee"))
    }

    @Test
    fun `an empty template always shows it`() {
        assertTrue(SendFailureHint.showsNetworkHint("anything", template = "%s"))
    }
}
