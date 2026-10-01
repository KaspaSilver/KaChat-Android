package com.kachat.app.viewmodels

import com.kachat.app.models.PortfolioTransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class PortfolioViewModelTest {

    private fun tx(type: String, amountKas: Double, fiatValue: Double, timestampMillis: Long = 0L) =
        PortfolioTransactionEntity(
            id = "$type-$amountKas-$timestampMillis",
            type = type,
            amountSompi = (amountKas * 100_000_000).toLong(),
            fiatValue = fiatValue,
            timestampMillis = timestampMillis
        )

    @Test
    fun `no transactions is all zero`() {
        val summary = PortfolioViewModel.computeSummary(emptyList(), currentPriceUsd = 0.15)
        assertEquals(0.0, summary.holdingsKas, 1e-9)
        assertEquals(0.0, summary.totalInvested, 1e-9)
        assertEquals(0.0, summary.totalPL, 1e-9)
        assertEquals(0.0, summary.totalPLPercent, 1e-9)
    }

    @Test
    fun `single buy at a loss when price drops`() {
        // Bought 100 KAS for $20 (i.e. $0.20/KAS); price is now $0.10/KAS.
        val summary = PortfolioViewModel.computeSummary(listOf(tx("buy", 100.0, 20.0)), currentPriceUsd = 0.10)
        assertEquals(100.0, summary.holdingsKas, 1e-9)
        assertEquals(20.0, summary.totalInvested, 1e-9)
        assertEquals(10.0, summary.currentValue, 1e-9) // 100 KAS * $0.10
        assertEquals(-10.0, summary.totalPL, 1e-9)      // $10 value - $20 invested
        assertEquals(-50.0, summary.totalPLPercent, 1e-9)
    }

    @Test
    fun `single buy in profit when price rises`() {
        // Bought 100 KAS for $10 ($0.10/KAS); price is now $0.25/KAS.
        val summary = PortfolioViewModel.computeSummary(listOf(tx("buy", 100.0, 10.0)), currentPriceUsd = 0.25)
        assertEquals(25.0, summary.currentValue, 1e-9)
        assertEquals(15.0, summary.totalPL, 1e-9)
        assertEquals(150.0, summary.totalPLPercent, 1e-9)
    }

    @Test
    fun `buy then full sell realizes profit independent of current price`() {
        // Bought 100 KAS for $10, sold all 100 KAS for $30. No KAS left, so current price
        // shouldn't move the result at all — the $20 gain is fully realized.
        val summary = PortfolioViewModel.computeSummary(
            listOf(tx("buy", 100.0, 10.0, timestampMillis = 1), tx("sell", 100.0, 30.0, timestampMillis = 2)),
            currentPriceUsd = 999.0 // deliberately absurd — must not affect the result
        )
        assertEquals(0.0, summary.holdingsKas, 1e-9)
        assertEquals(0.0, summary.currentValue, 1e-9)
        assertEquals(20.0, summary.totalPL, 1e-9)
        assertEquals(200.0, summary.totalPLPercent, 1e-9)
    }

    @Test
    fun `partial sell splits realized and unrealized correctly`() {
        // Bought 100 KAS for $10. Sold 50 KAS for $20 (realized +$15 vs its $5 cost basis).
        // Remaining 50 KAS (cost basis $5) now worth 50 * $0.30 = $15 (unrealized +$10).
        // Total P&L should be +$25 either way it's sliced.
        val summary = PortfolioViewModel.computeSummary(
            listOf(tx("buy", 100.0, 10.0, timestampMillis = 1), tx("sell", 50.0, 20.0, timestampMillis = 2)),
            currentPriceUsd = 0.30
        )
        assertEquals(50.0, summary.holdingsKas, 1e-9)
        assertEquals(15.0, summary.currentValue, 1e-9)
        assertEquals(25.0, summary.totalPL, 1e-9)
    }

    @Test
    fun `value history is zero before any purchase and reflects holdings after`() {
        // Bought 100 KAS at t=50. Price history has points before and after that purchase.
        val priceHistory = listOf(10L to 0.10, 50L to 0.10, 100L to 0.20)
        val history = PortfolioViewModel.computeValueHistory(
            listOf(tx("buy", 100.0, 10.0, timestampMillis = 50)),
            priceHistory
        )
        assertEquals(0.0, history[0].second, 1e-9)   // t=10, before the buy — no holdings yet
        assertEquals(10.0, history[1].second, 1e-9)  // t=50, buy included (tx at exactly this time counts)
        assertEquals(20.0, history[2].second, 1e-9)  // t=100, same 100 KAS now worth $0.20 each
    }

    @Test
    fun `value history reflects a later sell reducing holdings`() {
        // Buy 100 KAS at t=10, sell 40 KAS at t=60. Price is constant $0.10 throughout.
        val priceHistory = listOf(0L to 0.10, 30L to 0.10, 90L to 0.10)
        val history = PortfolioViewModel.computeValueHistory(
            listOf(tx("buy", 100.0, 5.0, timestampMillis = 10), tx("sell", 40.0, 4.0, timestampMillis = 60)),
            priceHistory
        )
        assertEquals(0.0, history[0].second, 1e-9)   // t=0, before any transaction
        assertEquals(10.0, history[1].second, 1e-9)  // t=30, 100 KAS * $0.10
        assertEquals(6.0, history[2].second, 1e-9)   // t=90, 60 KAS remaining * $0.10
    }

    @Test
    fun `transfers change neither holdings nor cost nor value history`() {
        val txs = listOf(
            tx("buy", 100.0, 10.0, 0L),
            tx("transfer", 100.0, 12.0, 50L),
            tx("transfer", 100.0, 12.0, 60L),
        )
        val summary = PortfolioViewModel.computeSummary(txs, 0.2)
        assertEquals(100.0, summary.holdingsKas, 1e-9)
        assertEquals(10.0, summary.totalInvested, 1e-9)
        assertEquals(0.0, summary.totalProceeds, 1e-9)
        val history = PortfolioViewModel.computeValueHistory(txs, listOf(70L to 0.1))
        assertEquals(10.0, history[0].second, 1e-9)
    }

    private val utc = java.util.TimeZone.getTimeZone("UTC")
    private fun utcMillis(year: Int, month: Int, day: Int): Long =
        java.util.Calendar.getInstance(utc).apply { clear(); set(year, month - 1, day) }.timeInMillis

    @Test
    fun `realized PL takes the oldest buys first and counts only that year's sells`() {
        val txs = listOf(
            tx("buy", 100.0, 10.0, utcMillis(2025, 1, 1)),   // $0.10
            tx("buy", 100.0, 30.0, utcMillis(2025, 6, 1)),   // $0.30
            tx("sell", 50.0, 25.0, utcMillis(2025, 12, 1)),  // last year: uses 50 of lot 1
            tx("sell", 100.0, 50.0, utcMillis(2026, 3, 1)),  // 50 of lot 1 ($5) + 50 of lot 2 ($15)
            tx("transfer", 100.0, 40.0, utcMillis(2026, 4, 1)),
        )
        val pl = PortfolioViewModel.computeRealizedPL(txs, 2026, utc)
        assertEquals(1, pl.sellCount)
        assertEquals(50.0, pl.proceeds, 1e-9)
        assertEquals(20.0, pl.costBasis, 1e-9)
        assertEquals(30.0, pl.amount, 1e-9)
        assertEquals(0.0, pl.uncoveredKas, 1e-9)
    }

    @Test
    fun `realized PL counts KAS sold beyond every buy at zero cost`() {
        val txs = listOf(
            tx("buy", 10.0, 1.0, utcMillis(2026, 1, 1)),
            tx("sell", 30.0, 6.0, utcMillis(2026, 2, 1)),
        )
        val pl = PortfolioViewModel.computeRealizedPL(txs, 2026, utc)
        assertEquals(1.0, pl.costBasis, 1e-9)
        assertEquals(20.0, pl.uncoveredKas, 1e-9)
        assertEquals(5.0, pl.amount, 1e-9)
    }
}
