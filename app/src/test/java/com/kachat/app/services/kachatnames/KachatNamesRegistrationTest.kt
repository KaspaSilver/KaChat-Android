package com.kachat.app.services.kachatnames

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One registration at a time (iOS 61fb0fc): a second one is refused while one is still in
 * progress, and the progress half sheet shows the open one.
 */
class KachatNamesRegistrationTest {
    private fun reg(id: String, stage: PendingRegistration.Stage) = PendingRegistration(
        id = id, name = "name$id", years = 1, owner = "00", commitTxId = "00", commitScript = "00",
        stage = stage, createdAt = 0, updatedAt = 0, maxPrice = 100
    )

    @Test
    fun aSecondRegistrationIsRefusedWhileOneIsInProgress() {
        assertFalse(KachatNamesActions.blocksNewRegistration(emptyList()))
        for (stage in listOf(
            PendingRegistration.Stage.COMMITTING, PendingRegistration.Stage.WAITING, PendingRegistration.Stage.REGISTERING,
            PendingRegistration.Stage.TAKEN, PendingRegistration.Stage.PRICE_CHANGED, PendingRegistration.Stage.FAILED,
            PendingRegistration.Stage.CANCELLING
        )) {
            assertTrue("$stage blocks", KachatNamesActions.blocksNewRegistration(listOf(reg("1", stage))))
        }
        // registered (waiting for Done) or cancelled: a new one may start
        assertFalse(KachatNamesActions.blocksNewRegistration(listOf(reg("1", PendingRegistration.Stage.REGISTERED))))
        assertFalse(KachatNamesActions.blocksNewRegistration(listOf(reg("1", PendingRegistration.Stage.CANCELLED))))
        assertTrue(KachatNamesActions.blocksNewRegistration(listOf(reg("1", PendingRegistration.Stage.REGISTERED), reg("2", PendingRegistration.Stage.WAITING))))
        assertEquals("Finish the name you're claiming first.", KachatNamesActions.FINISH_CLAIMING_FIRST)
    }

    @Test
    fun theProgressSheetShowsTheOpenRegistration() {
        assertNull(KachatNamesActions.openRegistration(emptyList()))
        assertNull(KachatNamesActions.openRegistration(listOf(reg("1", PendingRegistration.Stage.CANCELLED))))
        assertEquals("2", KachatNamesActions.openRegistration(listOf(reg("1", PendingRegistration.Stage.CANCELLED), reg("2", PendingRegistration.Stage.WAITING)))?.id)
        // a registered one stays up until Done
        assertEquals("1", KachatNamesActions.openRegistration(listOf(reg("1", PendingRegistration.Stage.REGISTERED)))?.id)
    }
}
