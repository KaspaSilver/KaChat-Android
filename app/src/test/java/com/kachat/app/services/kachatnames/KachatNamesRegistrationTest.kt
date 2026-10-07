package com.kachat.app.services.kachatnames

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claims you can step away from, and commits a busy network dropped (iOS b219bb0): every open
 * claim is listed (they run side by side), and a commit that isn't on chain is given time, waited
 * on while a mempool holds it, and sent again - up to three times - once it was dropped.
 */
class KachatNamesRegistrationTest {
    private val now = 2_000_000_000_000L

    private fun reg(id: String, stage: PendingRegistration.Stage) = PendingRegistration(
        id = id, name = "name$id", years = 1, owner = "00", commitTxId = "00", commitScript = "00",
        stage = stage, createdAt = 0, updatedAt = 0, maxPrice = 100
    )

    private fun waiting(sentAt: Long?, createdAt: Long = now - 600_000, resends: Int? = null, lastError: String? = null) = PendingRegistration(
        id = "1", name = "alice", years = 1, owner = "00", commitTxId = "aa", commitScript = "00",
        stage = PendingRegistration.Stage.WAITING, createdAt = createdAt, updatedAt = createdAt, maxPrice = 100,
        commitSentAt = sentAt, commitResends = resends, lastError = lastError
    )

    @Test
    fun theClaimsButtonListsEveryOpenClaim() {
        assertEquals(emptyList<String>(), KachatNamesActions.openRegistrations(emptyList()).map { it.id })
        assertEquals(emptyList<String>(), KachatNamesActions.openRegistrations(listOf(reg("1", PendingRegistration.Stage.CANCELLED))).map { it.id })
        // in progress, finished and not yet dismissed, stopped: all listed, side by side, in order
        val all = listOf(
            reg("1", PendingRegistration.Stage.WAITING), reg("2", PendingRegistration.Stage.CANCELLED),
            reg("3", PendingRegistration.Stage.REGISTERED), reg("4", PendingRegistration.Stage.COMMITTING),
            reg("5", PendingRegistration.Stage.FAILED)
        )
        assertEquals(listOf("1", "3", "4", "5"), KachatNamesActions.openRegistrations(all).map { it.id })
    }

    @Test
    fun aJustSentCommitIsGivenTimeToShowUp() {
        assertTrue(KachatNamesActions.commitJustSent(waiting(sentAt = now - 1_000), now))
        assertTrue(KachatNamesActions.commitJustSent(waiting(sentAt = now - 29_999), now))
        assertFalse(KachatNamesActions.commitJustSent(waiting(sentAt = now - 30_000), now))
        // never sent again and no send time (a record from before): timed from its start
        assertTrue(KachatNamesActions.commitJustSent(waiting(sentAt = null, createdAt = now - 10_000), now))
        assertFalse(KachatNamesActions.commitJustSent(waiting(sentAt = null, createdAt = now - 600_000), now))
    }

    @Test
    fun aCommitStillInAMempoolSaysTheNetworkIsBusyOnce() {
        assertFalse(KachatNamesActions.commitBusyNoteDue(waiting(sentAt = now - 45_000), now))
        assertFalse(KachatNamesActions.commitBusyNoteDue(waiting(sentAt = now - 60_000), now))
        assertTrue(KachatNamesActions.commitBusyNoteDue(waiting(sentAt = now - 60_001), now))
        // the card already has a note: left as it is
        assertFalse(KachatNamesActions.commitBusyNoteDue(waiting(sentAt = now - 90_000, lastError = KachatNamesActions.COMMIT_WAITING_BUSY), now))
        assertEquals("The network is busy. Your commit is waiting for a block.", KachatNamesActions.COMMIT_WAITING_BUSY)
    }

    @Test
    fun aDroppedCommitIsSentAgainUpToThreeTimes() {
        assertTrue(KachatNamesActions.mayResendCommit(waiting(sentAt = now, resends = null)))
        assertTrue(KachatNamesActions.mayResendCommit(waiting(sentAt = now, resends = 2)))
        assertFalse(KachatNamesActions.mayResendCommit(waiting(sentAt = now, resends = 3)))

        // sent again: tracked by the new txid, timed from now, counted, and the card says so
        var p = waiting(sentAt = now - 120_000)
        p = KachatNamesActions.commitResent(p, "bb", now)
        assertEquals("bb", p.commitTxId)
        assertEquals(now, p.commitSentAt)
        assertEquals(1, p.commitResends)
        assertEquals(KachatNamesActions.COMMIT_SENT_AGAIN, p.lastError)
        assertEquals("The network is busy, so the commit was sent again.", p.lastError)
        // the same commit (name, salt, script) - only the txid moves
        assertEquals("00", p.commitScript)
        assertTrue(KachatNamesActions.commitJustSent(p, now + 1_000))

        // a failed resend still counts, and the next try waits again
        val failed = KachatNamesActions.commitResendFailed(p, "insufficient funds", now + 60_000)
        assertEquals("bb", failed.commitTxId)
        assertEquals(2, failed.commitResends)
        assertEquals(now + 60_000, failed.commitSentAt)
        assertEquals("insufficient funds", failed.lastError)

        // the third resend is the last
        val third = KachatNamesActions.commitResent(failed, "cc", now + 120_000)
        assertEquals(3, third.commitResends)
        assertFalse(KachatNamesActions.mayResendCommit(third))
    }

    @Test
    fun anUnreadableFeeEstimateIsTenTimesTheFloor() {
        assertEquals(KachatNames.MIN_FEERATE * 10, KachatNamesActions.UNKNOWN_FEERATE, 0.0)
    }

    @Test
    fun anOldRecordWithoutTheNewFieldsStillReads() {
        val p = reg("1", PendingRegistration.Stage.WAITING)
        assertNull(p.commitSentAt)
        assertNull(p.commitResends)
        assertTrue(KachatNamesActions.mayResendCommit(p))
    }
}
