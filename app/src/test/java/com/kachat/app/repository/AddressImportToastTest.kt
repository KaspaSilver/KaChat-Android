package com.kachat.app.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/** iOS PortfolioTransactionsView's address-import toast. */
class AddressImportToastTest {
    private val fees: (Int) -> String = { ". Network fees counted: $it" }

    @Test
    fun `a complete import reads as before`() {
        assertEquals(
            "Imported 1 transaction",
            addressImportToastMessage(AddressImportResult(importedCount = 1, pendingPriceCount = 0), fees),
        )
    }

    @Test
    fun `fees and pending prices are added a sentence at a time`() {
        assertEquals(
            "Imported 3 transactions. Network fees counted: 2. Prices for 1 are still loading and will fill in automatically",
            addressImportToastMessage(AddressImportResult(importedCount = 3, pendingPriceCount = 1, feeCount = 2), fees),
        )
    }

    @Test
    fun `incomplete history adds iOS's re-add suffix last`() {
        assertEquals(
            "Imported 2 transactions. Prices for 2 are still loading and will fill in automatically" +
                ". Some history couldn't be fetched, re-add this address later to import the rest",
            addressImportToastMessage(
                AddressImportResult(importedCount = 2, pendingPriceCount = 2, historyComplete = false),
                fees,
            ),
        )
    }
}
