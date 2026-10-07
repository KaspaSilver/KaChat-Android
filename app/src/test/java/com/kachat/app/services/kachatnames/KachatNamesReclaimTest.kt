package com.kachat.app.services.kachatnames

import com.kachat.app.services.kachatnames.KachatNames.Codec
import com.kachat.app.ui.screens.KachatManageMenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lapsed name (iOS ba1a734, eea52b2): an expired name past grace is available to anyone - a
 * lookup shows it free to claim, in the gap its reclaim reopens; a registration may start over the
 * lapsed record, and its driver frees the old record first (a reclaim) instead of counting it as
 * already yours; an expired name's old listing is not for sale.
 */
class KachatNamesReclaimTest {
    private val grace = 864_000_000L
    private val now = 2_000_000_000_000L
    private val me = ByteArray(32) { 7 }
    private val other = ByteArray(32) { 9 }

    private fun name(exp: Long, owner: ByteArray = me, price: Long = 0, n: String = "alice") =
        NameInfo(n, Codec.key(n), owner, price, exp, Outpoint(KachatNames.ZERO32, 0))

    private val active = name(now + 1)
    private val inGrace = name(now - 1)
    private val lapsed = name(now - grace - 1)

    @Test
    fun anExpiredNamePastGraceIsAvailableToClaim() {
        // only a lapsed record counts as free to claim: active and in-grace names stay registered
        assertSame(lapsed, KachatNamesRegistry.lapsedRecord(Lookup.Registered(lapsed), grace, now))
        assertNull(KachatNamesRegistry.lapsedRecord(Lookup.Registered(active), grace, now))
        assertNull(KachatNamesRegistry.lapsedRecord(Lookup.Registered(inGrace), grace, now))
        assertNull(KachatNamesRegistry.lapsedRecord(Lookup.Free("alice", null), grace, now))
        // the grace period's last moment is still the owner's; the next one it is available
        assertNull(KachatNamesRegistry.lapsedRecord(Lookup.Registered(name(now - grace + 1)), grace, now))
        assertTrue(KachatNamesRegistry.lapsedRecord(Lookup.Registered(name(now - grace)), grace, now) != null)
        // and its status pill / badge reads Available, not "lapsed"
        assertEquals(Status.LAPSED, lapsed.status(grace, now))
    }

    @Test
    fun claimingReopensTheGapAroundTheName() {
        val lo = ByteArray(32) { 0 }
        val hi = ByteArray(32) { -1 }
        val key = Codec.key("alice")
        val below = GapInfo(lo, key, Outpoint(ByteArray(32) { 4 }, 0))
        val above = GapInfo(key, hi, Outpoint(ByteArray(32) { 5 }, 1))
        val merged = KachatNamesRegistry.mergedGap(below, above)
        assertTrue(merged.lo.contentEquals(lo))
        assertTrue(merged.hi.contentEquals(hi))
        assertEquals(below.outpoint, merged.outpoint)
        assertTrue(merged.contains(key))
    }

    @Test
    fun aLiveNamesManageSheetKeepsItsActions() {
        assertEquals(
            listOf(KachatManageMenu.Action.LIST, KachatManageMenu.Action.TRANSFER, KachatManageMenu.Action.PRIMARY, KachatManageMenu.Action.RELEASE),
            KachatManageMenu.actions(active, Status.ACTIVE, null, mine = true)
        )
        // listed, on a spending address: no Set as Primary
        assertEquals(
            listOf(KachatManageMenu.Action.CHANGE_PRICE, KachatManageMenu.Action.DELIST, KachatManageMenu.Action.TRANSFER, KachatManageMenu.Action.RELEASE),
            KachatManageMenu.actions(name(now + 1, price = 5), Status.ACTIVE, null, mine = false)
        )
        // in grace: still the owner's to manage
        assertEquals(
            listOf(KachatManageMenu.Action.LIST, KachatManageMenu.Action.TRANSFER, KachatManageMenu.Action.PRIMARY, KachatManageMenu.Action.RELEASE),
            KachatManageMenu.actions(inGrace, Status.GRACE, null, mine = true)
        )
    }

    @Test
    fun aRegistrationMayStartOverALapsedRecordOnly() {
        assertTrue(KachatNamesActions.isRegisterable(Lookup.Free("alice", null), grace, now))
        assertTrue(KachatNamesActions.isRegisterable(Lookup.Registered(lapsed), grace, now))
        assertFalse(KachatNamesActions.isRegisterable(Lookup.Registered(active), grace, now))
        assertFalse(KachatNamesActions.isRegisterable(Lookup.Registered(inGrace), grace, now))
    }

    @Test
    fun theDriverFreesTheExpiredOldRecordFirst() {
        // the old record, lapsed - yours or anyone's - is not a finished registration nor a taken
        // name: the driver frees it (a reclaim of that record), then registers
        val mineLapsed = KachatNamesActions.registerStep(Lookup.Registered(lapsed), me, grace, now)
        assertTrue(mineLapsed is KachatNamesActions.RegisterStep.FreeOldName)
        assertSame(lapsed, (mineLapsed as KachatNamesActions.RegisterStep.FreeOldName).name)
        val othersLapsed = name(now - grace - 1, owner = other)
        val step = KachatNamesActions.registerStep(Lookup.Registered(othersLapsed), me, grace, now)
        assertTrue(step is KachatNamesActions.RegisterStep.FreeOldName)
        assertSame(othersLapsed, (step as KachatNamesActions.RegisterStep.FreeOldName).name)
        assertSame(KachatNamesActions.RegisterStep.Mine, KachatNamesActions.registerStep(Lookup.Registered(active), me, grace, now))
        assertSame(KachatNamesActions.RegisterStep.Taken,
            KachatNamesActions.registerStep(Lookup.Registered(name(now + 1, owner = other)), me, grace, now))
        val free = KachatNamesActions.registerStep(Lookup.Free("alice", null), me, grace, now)
        assertTrue(free is KachatNamesActions.RegisterStep.Claim)
        assertNull((free as KachatNamesActions.RegisterStep.Claim).gap)
        assertEquals("Freeing alice.kachat for you...", KachatNamesActions.freeingName("alice"))
    }

    /** iOS 4f0bd33: the reclaim retry measures from when the reclaim was sent. */
    @Test
    fun theReclaimIsSentAgainTwoMinutesAfterItWentOut() {
        val sentAt = now
        val waiting = PendingRegistration(
            id = "1", name = "alice", years = 1, owner = "00", commitTxId = "00", commitScript = "00",
            stage = PendingRegistration.Stage.WAITING, createdAt = sentAt - 600_000, updatedAt = sentAt,
            reclaimTxId = "ab", lastError = KachatNamesActions.freeingName("alice")
        )
        assertFalse(KachatNamesActions.reclaimRetryDue(waiting, sentAt + 5_000))
        assertFalse(KachatNamesActions.reclaimRetryDue(waiting, sentAt + KachatNamesActions.RECLAIM_RETRY_MS))
        assertTrue(KachatNamesActions.reclaimRetryDue(waiting, sentAt + KachatNamesActions.RECLAIM_RETRY_MS + 1))
        // nothing sent yet: nothing to send again (the driver sends the first one)
        assertFalse(KachatNamesActions.reclaimRetryDue(waiting.copy(reclaimTxId = null), sentAt + 10 * 60_000))
    }

    @Test
    fun aLapsedRecordIsNotHeldLive() {
        assertTrue(KachatNamesActions.holdsLive(Lookup.Registered(active), me, grace, now))
        assertTrue(KachatNamesActions.holdsLive(Lookup.Registered(inGrace), me, grace, now))
        assertFalse(KachatNamesActions.holdsLive(Lookup.Registered(lapsed), me, grace, now))
        assertFalse(KachatNamesActions.holdsLive(Lookup.Registered(active), other, grace, now))
        assertFalse(KachatNamesActions.holdsLive(Lookup.Free("alice", null), me, grace, now))
    }

    @Test
    fun anExpiredNamesListingIsNotForSale() {
        val listed = name(now + 1, price = 5, n = "bob")
        val expiredListed = name(now - 1, price = 5, n = "carol")
        val lapsedListed = name(now - grace - 1, price = 5, n = "dave")
        assertEquals(listOf("bob"), KachatNamesRegistry.forSale(listOf(listed, expiredListed, lapsedListed, active), grace, now).map { it.name })
    }
}
