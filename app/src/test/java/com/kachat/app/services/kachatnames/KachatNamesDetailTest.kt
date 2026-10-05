package com.kachat.app.services.kachatnames

import com.kachat.app.services.kachatnames.KachatNamesActions.TimeLeftUnit.DAY
import com.kachat.app.services.kachatnames.KachatNamesActions.TimeLeftUnit.HOUR
import com.kachat.app.services.kachatnames.KachatNamesActions.TimeLeftUnit.MINUTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The name detail's pure pieces: the Owner card's one-line address (iOS 71448d8
 * `KachatNamesRegistry.compactAddress`), and the offers' rules (iOS ba07975, registry v3 49c0baa):
 * which offers are declined (made to another owner than the current one), a submit that lost its
 * price shard, and what's left of an offer's time.
 */
class KachatNamesDetailTest {

    @Test
    fun compactAddressKeepsPrefixAndBothEnds() {
        val a = "kaspatest:qr4x7kabcdefghijklmnopqrstuvwxyz0123456789a9z2pq"
        assertEquals("kaspatest:qr4x7k...a9z2pq", KachatNamesRegistry.compactAddress(a))
        assertEquals("kaspa:qypq12...89abcd", KachatNamesRegistry.compactAddress("kaspa:qypq12xxxxxxxxxxxxxxx89abcd"))
    }

    @Test
    fun compactAddressLeavesShortOrPrefixlessAlone() {
        // no network prefix: as it is
        assertEquals("qr4x7kabcdefghijklmnop", KachatNamesRegistry.compactAddress("qr4x7kabcdefghijklmnop"))
        // a body of 14 characters or fewer: nothing to cut
        assertEquals("kaspatest:abcdefghijklmn", KachatNamesRegistry.compactAddress("kaspatest:abcdefghijklmn"))
        // 15: cut
        assertEquals("kaspatest:abcdef...jklmno", KachatNamesRegistry.compactAddress("kaspatest:abcdefghijklmno"))
        assertEquals("", KachatNamesRegistry.compactAddress(""))
    }

    private val seller = ByteArray(32) { 3 }

    private fun offer(createdAt: Long?, refundAfter: Long = 1_000L) =
        OfferInfo(Outpoint(ByteArray(32), 0), ByteArray(32), "alice", ByteArray(32), seller, 100_000_000L, refundAfter, createdAt)

    @Test
    fun offersMadeToAnotherOwnerAreDeclined() {
        // registry v3: the offer names its seller; only that owner can accept or decline it
        assertFalse(offer(createdAt = 100).isDeclined(currentOwner = seller))
        assertTrue(offer(createdAt = 100).isDeclined(currentOwner = ByteArray(32) { 4 }))
        // the time it was made no longer matters
        assertFalse(offer(createdAt = null).isDeclined(currentOwner = seller))
    }

    @Test
    fun aSubmitThatLostItsShardIsASpentConflict() {
        assertTrue(KachatNamesActions.isSpentConflict(Exception("transaction rejected: input already spent by another transaction")))
        assertTrue(KachatNamesActions.isSpentConflict(Exception("Rejected: double spend in mempool")))
        assertTrue(KachatNamesActions.isSpentConflict(Exception("transaction is an orphan")))
        assertFalse(KachatNamesActions.isSpentConflict(Exception("insufficient funds")))
        assertEquals(7L, KachatNamesActions.MAX_OFFER_DAYS)
    }

    @Test
    fun expiredOffersAreRefundable() {
        // refundable strictly after refundAfter (the contract's check)
        assertFalse(offer(0, refundAfter = 1_000).refundable(1_000))
        assertTrue(offer(0, refundAfter = 1_000).refundable(1_001))
        assertTrue(offer(0, refundAfter = -5).refundable(1))
    }

    @Test
    fun offerTimeLeftReadsLikeIos() {
        val perDay = 86_400L * 10
        val perHour = 3_600L * 10
        val perMinute = 60L * 10
        // 2d 4h 30m: days and hours
        assertEquals(listOf(DAY to 2L, HOUR to 4L), KachatNamesActions.offerTimeLeft(2 * perDay + 4 * perHour + 30 * perMinute, 0))
        // exactly a day: the zero hours are dropped
        assertEquals(listOf(DAY to 1L), KachatNamesActions.offerTimeLeft(perDay, 0))
        // 3h 12m
        assertEquals(listOf(HOUR to 3L, MINUTE to 12L), KachatNamesActions.offerTimeLeft(1_000 + 3 * perHour + 12 * perMinute, 1_000))
        // 5m; under a minute shows a minute; the very DAA it becomes refundable at, too
        assertEquals(listOf(MINUTE to 5L), KachatNamesActions.offerTimeLeft(5 * perMinute, 0))
        assertEquals(listOf(MINUTE to 1L), KachatNamesActions.offerTimeLeft(100, 0))
        assertEquals(listOf(MINUTE to 1L), KachatNamesActions.offerTimeLeft(1_000, 1_000))
        // refundable: nothing left
        assertNull(KachatNamesActions.offerTimeLeft(1_000, 1_001))
    }
}
