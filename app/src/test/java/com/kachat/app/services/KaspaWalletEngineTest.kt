package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KaspaWalletEngineTest {

    private fun utxo(txId: String, index: Int, amount: Long = 1_000_000L) = UtxoEntry(
        address = "kaspa:test",
        outpoint = Outpoint(transactionId = txId, index = index),
        utxoEntry = UtxoData(
            amount = amount,
            scriptPublicKey = ScriptPublicKey("aa"),
            blockDaaScore = 0,
            isCoinbase = false
        )
    )

    @Test
    fun `a UTXO we know we just spent is excluded even though the fresh fetch still has it`() {
        val stale = utxo("spent-tx", 0)
        val fresh = listOf(stale, utxo("other-tx", 0))
        val pendingSpent = mutableSetOf(KaspaWalletEngine.outpointKey(stale.outpoint))

        val result = KaspaWalletEngine.reconcilePendingUtxos(fresh, pendingSpent, mutableListOf())

        assertEquals(1, result.size)
        assertEquals("other-tx", result[0].outpoint.transactionId)
    }

    @Test
    fun `pending spend tracking is dropped once the fresh fetch no longer contains it`() {
        val pendingSpent = mutableSetOf("gone-tx:0")
        val fresh = listOf(utxo("other-tx", 0)) // "gone-tx:0" isn't in the fresh list anymore

        KaspaWalletEngine.reconcilePendingUtxos(fresh, pendingSpent, mutableListOf())

        assertTrue(pendingSpent.isEmpty())
    }

    @Test
    fun `our own pending change output is available even before the fresh fetch indexes it`() {
        val change = utxo("our-new-tx", 1, amount = 500_000L)
        val pendingChange = mutableListOf(change)
        val fresh = listOf(utxo("unrelated-tx", 0))

        val result = KaspaWalletEngine.reconcilePendingUtxos(fresh, mutableSetOf(), pendingChange)

        assertEquals(2, result.size)
        assertTrue(result.any { it.outpoint.transactionId == "our-new-tx" })
    }

    @Test
    fun `a pending change coin locked to another address is never offered`() {
        // iOS ca53cd1: a pending coin is signed with the fetched address's key, so one whose
        // script is another address's would be rejected ("false stack entry").
        val own = utxo("own-change", 1)
        val foreign = UtxoEntry(
            address = "kaspa:other",
            outpoint = Outpoint(transactionId = "foreign-change", index = 1),
            utxoEntry = UtxoData(amount = 1_000_000L, scriptPublicKey = ScriptPublicKey("bb"), blockDaaScore = 0, isCoinbase = false)
        )
        val confirmedForeign = UtxoEntry(
            address = "kaspa:test",
            outpoint = Outpoint(transactionId = "confirmed", index = 0),
            utxoEntry = UtxoData(amount = 1_000_000L, scriptPublicKey = ScriptPublicKey("bb"), blockDaaScore = 5, isCoinbase = false)
        )

        val result = KaspaWalletEngine.reconcilePendingUtxos(
            listOf(confirmedForeign), mutableSetOf(), mutableListOf(own, foreign), ownScriptHex = "AA"
        )

        // confirmed coins are not filtered; only the own pending coin joins them
        assertEquals(listOf("confirmed", "own-change"), result.map { it.outpoint.transactionId })
    }

    @Test
    fun `pending change tracking is dropped once the fresh fetch has indexed it, without duplicating it`() {
        val change = utxo("our-new-tx", 1, amount = 500_000L)
        val pendingChange = mutableListOf(change)
        val fresh = listOf(change) // the REST endpoint has now caught up

        val result = KaspaWalletEngine.reconcilePendingUtxos(fresh, mutableSetOf(), pendingChange)

        assertEquals(1, result.size) // not duplicated
        assertTrue(pendingChange.isEmpty())
    }

    @Test
    fun `the exact orphan-rejection scenario is fixed - two rapid sends never select the same input`() {
        // Simulates: send #1 spends utxo A and creates change B; send #2's fresh fetch
        // still only shows A (the REST endpoint hasn't caught up yet).
        val utxoA = utxo("shared-input", 0, amount = 20_000_000L)
        val changeB = utxo("send-1-tx", 0, amount = 19_500_000L)

        val pendingSpent = mutableSetOf<String>()
        val pendingChange = mutableListOf<UtxoEntry>()

        // Send #1 sees A fresh, spends it, records its own change.
        val send1Available = KaspaWalletEngine.reconcilePendingUtxos(listOf(utxoA), pendingSpent, pendingChange)
        assertEquals(listOf(utxoA), send1Available)
        pendingSpent.add(KaspaWalletEngine.outpointKey(utxoA.outpoint))
        pendingChange.add(changeB)

        // Send #2 fetches fresh UTXOs immediately after — REST still only shows A (stale).
        val send2Available = KaspaWalletEngine.reconcilePendingUtxos(listOf(utxoA), pendingSpent, pendingChange)

        assertEquals(listOf(changeB), send2Available) // A excluded, B (our own change) offered instead
    }

    @Test
    fun `applySpend drops a just-spent change UTXO from pending change immediately`() {
        val changeB = utxo("send-1-tx", 0, amount = 19_500_000L)
        val changeC = utxo("send-2-tx", 0, amount = 19_000_000L)
        val pendingSpent = mutableSetOf<String>()
        val pendingChange = mutableListOf(changeB)

        // Send #2 spends changeB (selected as its input) and creates changeC.
        KaspaWalletEngine.applySpend(pendingSpent, pendingChange, listOf(changeB), changeC)

        assertTrue(KaspaWalletEngine.outpointKey(changeB.outpoint) in pendingSpent)
        assertEquals(listOf(changeC), pendingChange) // changeB is gone, only changeC remains
    }

    @Test
    fun `a chain of ten rapid sends never re-offers an already-spent change UTXO`() {
        // Reproduces the live bug: a burst of sends chaining off each other's change
        // output must never let an already-spent change UTXO linger as "available".
        val pendingSpent = mutableSetOf<String>()
        val pendingChange = mutableListOf<UtxoEntry>()
        var current = utxo("genesis", 0, amount = 20_000_000L)
        val freshFromRest = listOf(current) // REST never catches up during this whole burst

        repeat(10) { i ->
            val available = KaspaWalletEngine.reconcilePendingUtxos(freshFromRest, pendingSpent, pendingChange)
            // Exactly one usable UTXO must be offered at every step — never the stale one.
            assertEquals("send #$i should see exactly one available UTXO", 1, available.size)
            assertEquals(current.outpoint.transactionId, available[0].outpoint.transactionId)

            val next = utxo("send-$i-change", 0, amount = current.utxoEntry.amount - 500_00L)
            KaspaWalletEngine.applySpend(pendingSpent, pendingChange, listOf(available[0]), next)
            current = next
        }
    }
}
