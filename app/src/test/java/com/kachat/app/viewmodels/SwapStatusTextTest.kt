package com.kachat.app.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Test

/** iOS writes a swap's status with Swift's `capitalized` (SwapView's history row and Swap Details). */
class SwapStatusTextTest {
    @Test
    fun `each word starts upper case and the rest is lower case`() {
        assertEquals("Finished", SwapViewModel.capitalizedStatus("finished"))
        assertEquals("Waiting", SwapViewModel.capitalizedStatus("WAITING"))
        assertEquals("Partially Refunded", SwapViewModel.capitalizedStatus("partially refunded"))
        assertEquals("", SwapViewModel.capitalizedStatus(""))
    }
}
