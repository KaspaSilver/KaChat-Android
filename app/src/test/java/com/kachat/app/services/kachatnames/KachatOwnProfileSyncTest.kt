package com.kachat.app.services.kachatnames

import com.kachat.app.services.kachatnames.KachatNamesRegistry.OwnProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * This device's own profile follows the chain (iOS 5d4ce87 `syncOwnProfile`): the indexer's
 * record is adopted on a fresh import, or when it is a different and newer record (saved on
 * another device); the local copy stays when it is the same record or newer (indexer lag right
 * after a save here).
 */
class KachatOwnProfileSyncTest {
    private val me = "kaspatest:qqmine"
    private val chainProfile = Profile(avatar = "https://x.com/kaspa", bio = "https://x.com/kaspa", linktree = "kaspa")
    private val localProfile = Profile(avatar = "https://github.com/kaspanet")

    private fun remote(txId: String? = "bb", updatedAt: Long? = 2_000L, profile: Profile? = chainProfile, address: String = me) =
        IndexerApi.ProfileJson(address, profile, updatedAt, txId)

    private fun local(txId: String = "aa", at: Long = 1_000L) = OwnProfile(me, localProfile, txId, at)

    @Test
    fun freshImportAdoptsTheChainRecord() {
        val adopted = KachatNamesRegistry.adoptedOwnProfile(null, me, remote())
        assertEquals(OwnProfile(me, chainProfile.sanitized(), "bb", 2_000L), adopted)
    }

    @Test
    fun freshImportWithoutUpdatedAtIsStampedNow() {
        val adopted = KachatNamesRegistry.adoptedOwnProfile(null, me, remote(updatedAt = null), nowMs = 9_999L)
        assertEquals(9_999L, adopted?.at)
    }

    @Test
    fun aNewerRecordFromAnotherDeviceReplacesTheLocalCopy() {
        val adopted = KachatNamesRegistry.adoptedOwnProfile(local(at = 1_000L), me, remote(updatedAt = 2_000L))
        assertEquals("bb", adopted?.txId)
        assertEquals(chainProfile.sanitized(), adopted?.profile)
    }

    @Test
    fun theSameRecordKeepsTheLocalCopy() {
        assertNull(KachatNamesRegistry.adoptedOwnProfile(local(txId = "bb", at = 1_000L), me, remote(txId = "bb", updatedAt = 5_000L)))
    }

    @Test
    fun anOlderRecordKeepsTheLocalCopy() {
        // saved here a moment ago; the indexer still has the previous record
        assertNull(KachatNamesRegistry.adoptedOwnProfile(local(at = 3_000L), me, remote(updatedAt = 2_000L)))
        assertNull(KachatNamesRegistry.adoptedOwnProfile(local(at = 2_000L), me, remote(updatedAt = 2_000L)))
    }

    @Test
    fun aDifferentRecordWithoutATimeKeepsTheLocalCopy() {
        assertNull(KachatNamesRegistry.adoptedOwnProfile(local(), me, remote(updatedAt = null)))
    }

    @Test
    fun incompleteOrForeignRecordsAreNeverAdopted() {
        assertNull(KachatNamesRegistry.adoptedOwnProfile(null, me, remote(txId = null)))
        assertNull(KachatNamesRegistry.adoptedOwnProfile(null, me, remote(profile = null)))
        assertNull(KachatNamesRegistry.adoptedOwnProfile(null, me, remote(address = "kaspatest:qqsomeoneelse")))
    }

    @Test
    fun addressCaseDoesNotMatter() {
        val adopted = KachatNamesRegistry.adoptedOwnProfile(null, me.uppercase(), remote(address = me.uppercase()))
        assertEquals(me, adopted?.address)
    }
}
