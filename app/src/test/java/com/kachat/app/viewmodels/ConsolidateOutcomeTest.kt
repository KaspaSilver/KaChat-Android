package com.kachat.app.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Test

/** iOS ManageAddressesView.consolidateToPrimary's ending. */
class ConsolidateOutcomeTest {
    @Test
    fun `anything sent shows the sent sheet, even when another address failed`() {
        val state = consolidateOutcome(listOf("tx1", "tx2"), "Node unreachable")
        assertEquals(WalletViewModel.ConsolidateStatus.SUCCESS, state.status)
        assertEquals(listOf("tx1", "tx2"), state.txIds)
        assertEquals(null, state.errorMessage)
    }

    @Test
    fun `nothing sent and a failure shows the failure`() {
        val state = consolidateOutcome(emptyList(), "Node unreachable")
        assertEquals(WalletViewModel.ConsolidateStatus.FAILED, state.status)
        assertEquals("Node unreachable", state.errorMessage)
    }

    @Test
    fun `nothing sent and nothing failed just ends`() {
        assertEquals(WalletViewModel.ConsolidateUiState(), consolidateOutcome(emptyList(), null))
    }
}
