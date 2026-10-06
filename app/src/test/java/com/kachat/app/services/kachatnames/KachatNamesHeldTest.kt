package com.kachat.app.services.kachatnames

import com.kachat.app.services.kachatnames.KachatNames.Codec
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Your Domains and Reclaimable (iOS e26562e): a lapsed name is no longer yours - it leaves Your
 * Domains for the marketplace's Reclaimable tab; an expired name still in grace stays, to be renewed.
 */
class KachatNamesHeldTest {
    private val grace = 864_000_000L
    private val now = 2_000_000_000_000L
    private val me = ByteArray(32) { 7 }

    private fun name(n: String, exp: Long) = NameInfo(n, Codec.key(n), me, 0, exp, Outpoint(KachatNames.ZERO32, 0))

    private val active = name("active", now + 1)
    private val inGrace = name("grace", now - 1)
    private val lapsedOld = name("lapsedold", now - grace - 5_000)
    private val lapsedNew = name("lapsednew", now - grace)

    @Test
    fun yourDomainsHoldActiveAndGraceNamesOnly() {
        assertEquals(listOf("active", "grace"), KachatNamesRegistry.held(listOf(active, lapsedNew, inGrace, lapsedOld), grace, now).map { it.name })
        assertEquals(emptyList<String>(), KachatNamesRegistry.held(listOf(lapsedOld), grace, now).map { it.name })
    }

    @Test
    fun reclaimableHoldsLapsedNamesOldestExpiryFirst() {
        assertEquals(listOf("lapsedold", "lapsednew"), KachatNamesRegistry.reclaimable(listOf(active, lapsedNew, inGrace, lapsedOld), grace, now).map { it.name })
    }

    @Test
    fun theTwoSetsSplitEveryName() {
        val all = listOf(active, inGrace, lapsedOld, lapsedNew)
        val held = KachatNamesRegistry.held(all, grace, now).map { it.name }.toSet()
        val reclaimable = KachatNamesRegistry.reclaimable(all, grace, now).map { it.name }.toSet()
        assertEquals(emptySet<String>(), held intersect reclaimable)
        assertEquals(all.map { it.name }.toSet(), held + reclaimable)
    }

    /** iOS aa36d2a `dropLapsed`: the next lapse to wait for, among the names still held. */
    @Test
    fun theNextLapseIsTheEarliestStillAhead() {
        // active: lapses at expiry + grace; in grace: at expiry + grace too; lapsed: never again
        assertEquals(inGrace.expiresAt + grace, KachatNamesRegistry.nextLapse(listOf(active, inGrace, lapsedOld), grace, now))
        assertEquals(active.expiresAt + grace, KachatNamesRegistry.nextLapse(listOf(active, lapsedOld), grace, now))
        assertEquals(null, KachatNamesRegistry.nextLapse(listOf(lapsedOld, lapsedNew), grace, now))
        assertEquals(null, KachatNamesRegistry.nextLapse(emptyList(), grace, now))
        // once that moment passes, the name is off the held set
        assertEquals(listOf("active"), KachatNamesRegistry.held(listOf(active, inGrace), grace, inGrace.expiresAt + grace).map { it.name })
    }
}
