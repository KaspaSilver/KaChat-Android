package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spent-coin holds (iOS 83806b3d): 10 minutes, taken before a submit, given back when it fails. */
class OutpointReservationsTest {

    private var clock = 1_000_000L
    private val store = OutpointReservations(now = { clock })
    private val a = Outpoint("AA".repeat(32), 0)
    private val b = Outpoint("bb".repeat(32), 1)

    @Test
    fun `a spent coin stays out of picks for ten minutes`() {
        assertEquals(10 * 60 * 1000L, OutpointReservations.TTL_MS)
        store.reserve(listOf(a))
        clock += 9 * 60 * 1000L
        assertTrue(store.isReserved(a))
        // the node and REST spell an id in either case
        assertTrue(store.isReserved(Outpoint("aa".repeat(32), 0)))
        clock += 2 * 60 * 1000L
        assertFalse(store.isReserved(a))
    }

    @Test
    fun `a hold keeps the coins out while the submit runs, and a release gives them back`() {
        val hold = store.hold(listOf(a, b, a))
        assertTrue(store.isReserved(a))
        assertTrue(store.isReserved(b))
        store.release(hold)
        assertFalse(store.isReserved(a))
        assertFalse(store.isReserved(b))
    }

    @Test
    fun `a release puts back a coin's earlier reservation, not nothing`() {
        store.reserve(listOf(a))
        clock += 60_000L
        val hold = store.hold(listOf(a))
        store.release(hold)
        clock += 8 * 60 * 1000L
        assertTrue("its own 10 minutes still run", store.isReserved(a))
        clock += 2 * 60 * 1000L
        assertFalse(store.isReserved(a))
    }

    @Test
    fun `a submit that went keeps its hold as a reservation`() {
        store.hold(listOf(a))
        store.reserve(listOf(a))
        clock += 9 * 60 * 1000L
        assertTrue(store.isReserved(a))
    }
}
