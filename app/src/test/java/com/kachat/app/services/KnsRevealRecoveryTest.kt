package com.kachat.app.services

import com.kachat.app.services.KnsInscriptionEngine.Companion.MISSING_COMMIT_LIFETIME_MS
import com.kachat.app.services.KnsInscriptionEngine.Companion.missingCommitVerdict
import com.kachat.app.services.KnsInscriptionEngine.Companion.MissingCommit
import org.junit.Assert.assertEquals
import org.junit.Test

/** The pending commit holds the only copy of its redeem script: it is dropped only when surely
 *  spent (iOS 83806b3d `KNSRevealRecovery.resume`). */
class KnsRevealRecoveryTest {
    @Test
    fun `a commit seen unspent is dropped after two empty looks in a row`() {
        assertEquals(MissingCommit.KEEP, missingCommitVerdict(seen = true, emptyChecks = 1, ageMs = 0))
        assertEquals(MissingCommit.DROP, missingCommitVerdict(seen = true, emptyChecks = 2, ageMs = 0))
    }

    @Test
    fun `a commit never seen is kept for 30 days and two empty looks`() {
        val old = MISSING_COMMIT_LIFETIME_MS + 1
        assertEquals(MissingCommit.KEEP, missingCommitVerdict(seen = false, emptyChecks = 5, ageMs = 24 * 60 * 60 * 1000L + 1))
        assertEquals(MissingCommit.KEEP, missingCommitVerdict(seen = false, emptyChecks = 1, ageMs = old))
        assertEquals(MissingCommit.DROP, missingCommitVerdict(seen = false, emptyChecks = 2, ageMs = old))
        assertEquals(30L * 24 * 60 * 60 * 1000, MISSING_COMMIT_LIFETIME_MS)
    }
}
