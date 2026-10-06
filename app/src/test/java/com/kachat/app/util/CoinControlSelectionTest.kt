package com.kachat.app.util

import com.kachat.app.services.Outpoint
import com.kachat.app.services.ScriptPublicKey
import com.kachat.app.services.UtxoData
import com.kachat.app.services.UtxoEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoinControlSelectionTest {

    private fun utxo(amount: Long, index: Int) = UtxoEntry(
        address = "kaspa:test",
        outpoint = Outpoint(transactionId = "tx$index", index = index),
        utxoEntry = UtxoData(amount = amount, scriptPublicKey = ScriptPublicKey("aa"), blockDaaScore = 1, isCoinbase = false)
    )

    private val cap = KaspaUtxoSelector.MAX_INPUTS_PER_TRANSACTION

    /** 150 coins, amounts 1..150 KAS-ish, shuffled so "largest" is not fetch order. */
    private val many = (1..150).map { utxo(it * 1_000_000L, it) }.shuffled(java.util.Random(7))

    @Test
    fun `select all takes the largest coins up to the cap`() {
        val picked = CoinControlSelection.selectAll(many, cap)
        assertEquals(cap, picked.size)
        val expected = many.sortedByDescending { it.utxoEntry.amount }.take(cap).map { it.outpoint }.toSet()
        assertEquals(expected, picked)
    }

    @Test
    fun `select all under the cap takes every coin`() {
        val few = many.take(10)
        assertEquals(few.map { it.outpoint }.toSet(), CoinControlSelection.selectAll(few, cap))
    }

    @Test
    fun `a coin past the cap is refused, and removing still works`() {
        val full = many.take(cap).map { it.outpoint }.toSet()
        val extra = many[cap].outpoint
        assertNull(CoinControlSelection.toggle(full, extra, cap))
        val one = full.first()
        assertEquals(full - one, CoinControlSelection.toggle(full, one, cap))
        // Under the cap a coin is added.
        val fewer = full - one
        assertEquals(fewer + extra, CoinControlSelection.toggle(fewer, extra, cap))
    }

    @Test
    fun `an oversized carried-in selection is brought under the cap, largest first`() {
        val keys = many.map { it.outpoint }.toSet()
        val resolved = CoinControlSelection.resolve(many, keys, cap)
        assertEquals(cap, resolved.size)
        assertEquals(many.sortedByDescending { it.utxoEntry.amount }.take(cap), resolved)
    }

    @Test
    fun `resolve drops coins that are no longer listed`() {
        val keys = setOf(many[0].outpoint, Outpoint("gone", 0))
        assertEquals(listOf(many[0]), CoinControlSelection.resolve(many, keys, cap))
    }

    @Test
    fun `the cap note shows only when the pick is full and more coins exist`() {
        assertTrue(CoinControlSelection.isAtCap(cap, 150, cap))
        assertFalse(CoinControlSelection.isAtCap(cap - 1, 150, cap))
        assertFalse(CoinControlSelection.isAtCap(cap, cap, cap))
    }

    @Test
    fun `cold storage uses KasSigner's own input cap`() {
        val picked = CoinControlSelection.selectAll(many, KsptCodec.MAX_INPUTS)
        assertEquals(KsptCodec.MAX_INPUTS, picked.size)
    }
}
