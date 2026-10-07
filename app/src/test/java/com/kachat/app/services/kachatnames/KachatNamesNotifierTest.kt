package com.kachat.app.services.kachatnames

import com.kachat.app.services.kachatnames.KachatNamesNotifier.News
import com.kachat.app.services.kachatnames.KachatNamesNotifier.Snapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Profile bell's `.kachat` news (iOS `KachatNamesNotifier`, 86471dd): the registry compared
 * with the last check - the first check only takes a baseline; renewal, expiry and lapse once
 * per paid period; names that left (sold, sold by offer, reclaimed - never a transfer or
 * release); new offers on your names; your offers accepted, declined, expired or returned, and
 * never the ones you closed yourself.
 */
class KachatNamesNotifierTest {
    private val me = ByteArray(32) { 1 }
    private val other = ByteArray(32) { 2 }
    private val period = 600_000L
    private val grace = 300_000L
    private val window = 120_000L
    private val params = Params(
        bond = 100_000_000L, gapValue = 100_000_000L, tCommit = 10, maxYears = 5, periodMs = period,
        graceMs = grace, renewWindowMs = window, registerPrices = listOf(1L, 1L, 1L, 1L, 1L),
        renewPrices = listOf(1L, 1L, 1L, 1L, 1L), offerMaxFee = 1
    )
    private val expires = 10_000_000L

    private fun outpoint(i: Int) = Outpoint(ByteArray(32) { i.toByte() }, 0)
    private fun name(n: String, expiresAt: Long = expires) =
        NameInfo(n, ByteArray(32), me, 0, expiresAt, outpoint(n.length))
    private fun offer(i: Int, n: String, buyer: ByteArray, seller: ByteArray, amount: Long = 500_000_000L) =
        OfferInfo(outpoint(i), ByteArray(32), n, buyer, seller, amount, 0)

    private fun diff(
        old: Snapshot?,
        owned: List<NameInfo> = emptyList(),
        onMine: List<OfferInfo> = emptyList(),
        myOpen: List<OfferInfo> = emptyList(),
        now: Long = expires - period / 2,
        selfClosed: Set<String> = emptySet(),
        history: Map<String, List<Event>> = emptyMap(),
    ) = runBlocking {
        KachatNamesNotifier.diff(old, owned, onMine, myOpen, params, now, selfClosed) { history[it] ?: emptyList() }
    }

    @Test
    fun firstCheckOnlyTakesABaseline() {
        val o = offer(9, "alice", other, me)
        val (snap, news) = diff(null, owned = listOf(name("alice")), onMine = listOf(o), now = expires - 1)
        assertTrue(news.isEmpty())
        assertEquals(setOf(o.id), snap.offersOnMine)
        assertTrue(snap.names.getValue("alice").renewNoted)
    }

    @Test
    fun newOfferOnYourNameOnce() {
        val (base, _) = diff(null, owned = listOf(name("alice")))
        val o = offer(9, "alice", other, me, amount = 700_000_000L)
        val (snap, news) = diff(base, owned = listOf(name("alice")), onMine = listOf(o))
        assertEquals(listOf<News>(News.NewOffer("alice", o.id, 700_000_000L)), news)
        assertEquals("offer-${o.id}", news.single().id)
        val (_, again) = diff(snap, owned = listOf(name("alice")), onMine = listOf(o))
        assertTrue(again.isEmpty())
    }

    @Test
    fun renewalExpiryAndLapseOncePerPeriod() {
        val (base, _) = diff(null, owned = listOf(name("alice")))
        // the renewal window opens
        val (s1, n1) = diff(base, owned = listOf(name("alice")), now = expires - window + 1)
        assertEquals(listOf<News>(News.RenewOpen("alice", expires)), n1)
        val (s2, n2) = diff(s1, owned = listOf(name("alice")), now = expires - 1)
        assertTrue(n2.isEmpty())
        // expired: in grace until expires + grace
        val (s3, n3) = diff(s2, owned = listOf(name("alice")), now = expires + 1)
        assertEquals(listOf<News>(News.Expired("alice", expires, expires + grace)), n3)
        // lapsed
        val (s4, n4) = diff(s3, owned = listOf(name("alice")), now = expires + grace + 1)
        assertEquals(listOf<News>(News.Lapsed("alice", expires)), n4)
        assertTrue(diff(s4, owned = listOf(name("alice")), now = expires + grace + 2).second.isEmpty())
        // renewed: a new paid period is news again when its window opens
        val renewed = expires + period
        val (s5, n5) = diff(s2, owned = listOf(name("alice", renewed)), now = expires)
        assertTrue(n5.isEmpty())
        assertEquals(listOf<News>(News.RenewOpen("alice", renewed)), diff(s5, owned = listOf(name("alice", renewed)), now = renewed - window).second)
    }

    @Test
    fun expiredWithoutRenewNoticeSkipsIt() {
        val (base, _) = diff(null, owned = listOf(name("alice")))
        val (_, news) = diff(base, owned = listOf(name("alice")), now = expires + grace + 1)
        assertEquals(listOf<News>(News.Lapsed("alice", expires)), news)
    }

    @Test
    fun namesThatLeft() {
        val (base, _) = diff(null, owned = listOf(name("a"), name("bb"), name("ccc"), name("dddd"), name("eeeee")))
        val history = mapOf(
            "a" to listOf(Event("t1", "sale", "a", price = 3_500_000_000L), Event("t0", "list", "a")),
            "bb" to listOf(Event("t2", "offer_accepted", "bb")),
            "ccc" to listOf(Event("t3", "reclaim", "ccc")),
            "dddd" to listOf(Event("t4", "transfer", "dddd"), Event("t0", "sale", "dddd")),
            "eeeee" to listOf(Event("t5", "release", "eeeee")),
        )
        val (snap, news) = diff(base, history = history)
        assertEquals(
            listOf(News.Sold("a", "t1", 3_500_000_000L), News.SoldByOffer("bb", "t2"), News.Reclaimed("ccc", "t3")),
            news
        )
        assertEquals("sold-t1", news[0].id)
        assertTrue(snap.names.isEmpty())
    }

    @Test
    fun yourOffersAcceptedDeclinedExpiredReturned() {
        val mine = listOf(
            offer(1, "won", me, other), offer(2, "dec", me, other), offer(3, "exp", me, other),
            offer(4, "ret", me, other), offer(5, "self", me, other), offer(6, "open", me, other)
        )
        val (base, _) = diff(null, myOpen = mine)
        val history = mapOf(
            "dec" to listOf(Event("x", "offer_decline", "dec")),
            "exp" to listOf(Event("y", "offer_refund", "exp")),
            "self" to listOf(Event("z", "offer_withdraw", "self")),
        )
        val (snap, news) = diff(
            base, owned = listOf(name("won")), myOpen = listOf(mine[5]),
            selfClosed = setOf(mine[4].id), history = history
        )
        assertEquals(
            listOf(
                News.MyOfferAccepted("won", mine[0].id), News.MyOfferDeclined("dec", mine[1].id),
                News.MyOfferExpired("exp", mine[2].id), News.MyOfferReturned("ret", mine[3].id)
            ),
            news
        )
        assertEquals(mapOf(mine[5].id to "open"), snap.myOffers)
    }

    @Test
    fun snapshotRoundTrips() {
        val s = Snapshot(
            names = mapOf("alice" to KachatNamesNotifier.NameNote(expires, renewNoted = true), "bob" to KachatNamesNotifier.NameNote(5, true, true, true)),
            offersOnMine = setOf("aa:0", "bb:1"),
            myOffers = mapOf("cc:0" to "carol", "dd:2" to ""),
        )
        assertEquals(s, Snapshot.decode(s.encode()))
        assertEquals(null, Snapshot.decode("not json"))
    }
}
