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
 * Reclaim to Own and the lapsed name (iOS ba1a734): a lapsed name's Manage sheet offers only
 * Reclaim to Own; a registration may start over a lapsed record, and its driver waits ("waiting
 * for the old name to be cleared") instead of counting the old record as already yours; an
 * expired name's old listing is not for sale.
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
    fun aLapsedNamesManageSheetOffersOnlyReclaimToOwn() {
        assertEquals(listOf(KachatManageMenu.Action.RECLAIM_TO_OWN), KachatManageMenu.actions(lapsed, Status.LAPSED, null, mine = true))
        assertEquals(listOf(KachatManageMenu.Action.RECLAIM_TO_OWN), KachatManageMenu.actions(name(now - grace - 1, price = 5), Status.LAPSED, null, mine = false))
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
        // in grace: still the owner's to manage, never Reclaim to Own
        assertFalse(KachatManageMenu.actions(inGrace, Status.GRACE, null, mine = true).contains(KachatManageMenu.Action.RECLAIM_TO_OWN))
    }

    @Test
    fun aRegistrationMayStartOverALapsedRecordOnly() {
        assertTrue(KachatNamesActions.isRegisterable(Lookup.Free("alice", null), grace, now))
        assertTrue(KachatNamesActions.isRegisterable(Lookup.Registered(lapsed), grace, now))
        assertFalse(KachatNamesActions.isRegisterable(Lookup.Registered(active), grace, now))
        assertFalse(KachatNamesActions.isRegisterable(Lookup.Registered(inGrace), grace, now))
    }

    @Test
    fun theDriverWaitsForTheOldNameToBeCleared() {
        // the old record, lapsed - yours or anyone's - is not a finished registration nor a taken name
        assertSame(KachatNamesActions.RegisterStep.WaitForOldName, KachatNamesActions.registerStep(Lookup.Registered(lapsed), me, grace, now))
        assertSame(KachatNamesActions.RegisterStep.WaitForOldName,
            KachatNamesActions.registerStep(Lookup.Registered(name(now - grace - 1, owner = other)), me, grace, now))
        assertSame(KachatNamesActions.RegisterStep.Mine, KachatNamesActions.registerStep(Lookup.Registered(active), me, grace, now))
        assertSame(KachatNamesActions.RegisterStep.Taken,
            KachatNamesActions.registerStep(Lookup.Registered(name(now + 1, owner = other)), me, grace, now))
        val free = KachatNamesActions.registerStep(Lookup.Free("alice", null), me, grace, now)
        assertTrue(free is KachatNamesActions.RegisterStep.Claim)
        assertNull((free as KachatNamesActions.RegisterStep.Claim).gap)
        assertEquals("Waiting for the old alice.kachat to be cleared from the registry.", KachatNamesActions.waitingForOldName("alice"))
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
