package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Test

/** Cold Storage sends record the coins they spent (audit AND-015). */
class PendingSpentOutpointsTest {

    private val address = "kaspa:qpcold0000000000000000000000000000000000000000000000000000"
    private var clock = 1_000_000L
    private val pending = PendingSpentOutpoints(ttlMs = 120_000L, now = { clock })

    private fun utxo(tx: String, index: Int = 0, amount: Long = 100_000_000L) = UtxoEntry(
        address = address,
        outpoint = Outpoint(transactionId = tx, index = index),
        utxoEntry = UtxoData(amount = amount, scriptPublicKey = ScriptPublicKey("aa"), blockDaaScore = 1, isCoinbase = false)
    )

    private val a = utxo("a")
    private val b = utxo("b")
    private val c = utxo("c", 1)

    @Test
    fun `nothing recorded leaves the fetch untouched`() {
        assertEquals(listOf(a, b), pending.filter(address, listOf(a, b)))
    }

    @Test
    fun `a broadcast's inputs are kept out of the next fetch, even a stale one`() {
        pending.record(address, listOf(a.outpoint, c.outpoint))
        assertEquals(listOf(b), pending.filter(address, listOf(a, b, c)))
        // A fetch that already dropped the coin does not end the guard: a lagging source after it
        // may still list the coin.
        assertEquals(listOf(b), pending.filter(address, listOf(b)))
        assertEquals(listOf(b), pending.filter(address, listOf(a, b, c)))
    }

    @Test
    fun `the outpoint index matters, not just the transaction`() {
        pending.record(address, listOf(Outpoint("c", 0)))
        assertEquals(listOf(c), pending.filter(address, listOf(c)))
    }

    @Test
    fun `entries expire after the TTL`() {
        pending.record(address, listOf(a.outpoint))
        clock += 119_000L
        assertEquals(listOf(b), pending.filter(address, listOf(a, b)))
        clock += 2_000L
        assertEquals(listOf(a, b), pending.filter(address, listOf(a, b)))
        assertEquals(0, pending.pendingCount(address))
    }

    @Test
    fun `recorded per address, whatever the case`() {
        pending.record(address.uppercase(), listOf(a.outpoint))
        assertEquals(listOf(b), pending.filter(address, listOf(a, b)))
        // Another address is not affected.
        assertEquals(listOf(a, b), pending.filter("kaspa:qpother", listOf(a, b)))
    }
}
