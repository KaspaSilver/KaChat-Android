package com.kachat.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/** iOS CoinControlView: a failed fetch is never shown as an empty address. */
class CoinListContentTest {
    @Test
    fun `a failed fetch shows the failure row, not no coins`() {
        assertEquals(CoinListContent.LOAD_FAILED, coinListContent(isLoading = false, hasCoins = false, loadError = "Couldn't reach the server."))
    }

    @Test
    fun `an answer with nothing is the empty row`() {
        assertEquals(CoinListContent.EMPTY, coinListContent(isLoading = false, hasCoins = false, loadError = null))
    }

    @Test
    fun `the spinner while loading with nothing yet, the coins once there are some`() {
        assertEquals(CoinListContent.LOADING, coinListContent(isLoading = true, hasCoins = false, loadError = "earlier failure"))
        assertEquals(CoinListContent.COINS, coinListContent(isLoading = true, hasCoins = true, loadError = null))
        assertEquals(CoinListContent.COINS, coinListContent(isLoading = false, hasCoins = true, loadError = null))
    }
}
