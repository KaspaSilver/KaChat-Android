package com.kachat.app.services.kachatnames

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The app's own rules on what a `.kachat` action may spend (the iOS audit fixes): never more than
 * the price the person confirmed (iOS 4f5d95e, IOS-054); no offers on, and no accepting them for,
 * a name that isn't active, and no renewal that leaves a name still expired (iOS 71128c4, IOS-055,
 * IOS-056); and when a name counts as expiring soon (iOS 24d673a, IOS-060).
 */
class KachatNamesSpendRulesTest {

    // The price cap (IOS-054)

    @Test
    fun aHigherPriceThanConfirmedIsRefused() {
        try {
            KachatNamesActions.checkPriceCap(priceFee = 2_000_00000001L, maxPrice = 2_000_00000000L)
            fail("a price above the confirmed one passed")
        } catch (e: KachatNamesActions.ActionError.PriceChanged) {
            assertEquals(2_000_00000001L, e.price)
        }
    }

    @Test
    fun theConfirmedOrALowerPricePasses() {
        KachatNamesActions.checkPriceCap(priceFee = 2_000_00000000L, maxPrice = 2_000_00000000L)
        KachatNamesActions.checkPriceCap(priceFee = 1_500_00000000L, maxPrice = 2_000_00000000L)
        KachatNamesActions.checkPriceCap(priceFee = 0L, maxPrice = 0L)
        // no cap: the operations without a price (and the background returns)
        KachatNamesActions.checkPriceCap(priceFee = 5_000_00000000L, maxPrice = null)
    }

    // The pending registration's price-changed stage (IOS-054)

    private fun pending(stage: PendingRegistration.Stage = PendingRegistration.Stage.WAITING, maxPrice: Long? = 700_00000000L) =
        PendingRegistration(
            id = "ID", name = "ab", years = 1, owner = "00".repeat(32), commitTxId = "11".repeat(32),
            commitScript = "22", commitDaa = 1_000, stage = stage, createdAt = 1, updatedAt = 1,
            lastError = "an earlier hiccup", maxPrice = maxPrice
        )

    @Test
    fun aRegistrationStopsWhenThePriceRises() {
        val stopped = pending().stoppedAtPrice(700_00000001L)
        requireNotNull(stopped)
        assertEquals(PendingRegistration.Stage.PRICE_CHANGED, stopped.stage)
        assertEquals(700_00000001L, stopped.priceChangedTo)
        // the cap is not raised until the person confirms the new price
        assertEquals(700_00000000L, stopped.maxPrice)
        assertNull(stopped.lastError)
        // a stopped registration is not driven: nothing is sent
        assertTrue(!stopped.needsDriving)
        assertTrue(stopped.isOpen)
    }

    @Test
    fun aRegistrationGoesOnAtTheConfirmedOrALowerPrice() {
        assertNull(pending().stoppedAtPrice(700_00000000L))
        assertNull(pending().stoppedAtPrice(350_00000000L))
        // one started before the cap existed has none
        assertNull(pending(maxPrice = null).stoppedAtPrice(8_000_00000000L))
    }

    @Test
    fun confirmingTheNewPriceRaisesTheCapToIt() {
        val stopped = requireNotNull(pending().stoppedAtPrice(900_00000000L))
        val going = requireNotNull(stopped.acceptingNewPrice())
        assertEquals(PendingRegistration.Stage.WAITING, going.stage)
        assertEquals(900_00000000L, going.maxPrice)
        assertNull(going.priceChangedTo)
        assertTrue(going.needsDriving)
        // and it stops again if the price goes up further
        assertEquals(PendingRegistration.Stage.PRICE_CHANGED, going.stoppedAtPrice(900_00000001L)?.stage)
        assertNull(going.stoppedAtPrice(900_00000000L))
    }

    @Test
    fun onlyAStoppedRegistrationTakesANewPrice() {
        assertNull(pending().acceptingNewPrice())
        assertNull(pending(PendingRegistration.Stage.FAILED).acceptingNewPrice())
        // stopped but without the price it stopped at: nothing to confirm
        assertNull(pending(PendingRegistration.Stage.PRICE_CHANGED).acceptingNewPrice())
    }

    @Test
    fun thePriceChangedStageSurvivesTheStore() {
        val gson = com.google.gson.Gson()
        val stopped = requireNotNull(pending().stoppedAtPrice(900_00000000L))
        val json = gson.toJson(stopped)
        assertTrue(json.contains("\"stage\":\"priceChanged\""))
        val back = gson.fromJson(json, PendingRegistration::class.java)
        assertEquals(PendingRegistration.Stage.PRICE_CHANGED, back.stage)
        assertEquals(900_00000000L, back.priceChangedTo)
        assertEquals(700_00000000L, back.maxPrice)
    }

    // Expired names: offers and renewals (IOS-055, IOS-056)

    private val period = 600_000L // testnet's 10-minute clock
    private val grace = 600_000L

    private fun name(expiresAt: Long) =
        NameInfo("ab", ByteArray(32), ByteArray(32) { 7 }, 0L, expiresAt, Outpoint(ByteArray(32), 0))

    @Test
    fun aRenewalThatStillEndsInThePastIsRefused() {
        val now = 10_000_000L
        // expired an hour ago: 2 periods of 10 minutes still end 40 minutes ago
        val expired = now - 3_600_000L
        for (years in 1L..2L) {
            try {
                KachatNamesActions.checkRenewEndsAhead(expired, years, period, now)
                fail("a renewal ending in the past passed ($years period(s))")
            } catch (_: KachatNamesActions.ActionError.ExpiredTooLongToRenew) {
            }
        }
        // ending exactly now is still not ahead
        try {
            KachatNamesActions.checkRenewEndsAhead(now - period, 1, period, now)
            fail("a renewal ending exactly now passed")
        } catch (_: KachatNamesActions.ActionError.ExpiredTooLongToRenew) {
        }
    }

    @Test
    fun aRenewalThatEndsAheadPasses() {
        val now = 10_000_000L
        // in the renewal window, not yet expired
        KachatNamesActions.checkRenewEndsAhead(now + 60_000L, 1, period, now)
        // expired 15 minutes ago: 1 period isn't enough, 2 are
        try {
            KachatNamesActions.checkRenewEndsAhead(now - 900_000L, 1, period, now)
            fail("1 period from 15 minutes ago passed")
        } catch (_: KachatNamesActions.ActionError.ExpiredTooLongToRenew) {
        }
        KachatNamesActions.checkRenewEndsAhead(now - 900_000L, 2, period, now)
    }

    @Test
    fun offersOnlyOnActiveNames() {
        val now = 10_000_000L
        KachatNamesActions.checkActiveForOffer(name(now + 1), grace, now)
        for (expiresAt in listOf(now, now - grace + 1, now - grace, now - 10 * grace)) {
            try {
                KachatNamesActions.checkActiveForOffer(name(expiresAt), grace, now)
                fail("an offer on a name past its expiry passed (${name(expiresAt).status(grace, now)})")
            } catch (_: KachatNamesActions.ActionError.OfferNameNotActive) {
            }
        }
    }

    @Test
    fun acceptOnlyWhileTheNameIsActive() {
        val now = 10_000_000L
        KachatNamesActions.checkActiveForAccept(name(now + 1), grace, now)
        // in grace and lapsed: the buyer would get a name anyone can reclaim
        for (expiresAt in listOf(now, now - grace + 1, now - grace, now - 10 * grace)) {
            try {
                KachatNamesActions.checkActiveForAccept(name(expiresAt), grace, now)
                fail("accepting an offer on a name past its expiry passed")
            } catch (_: KachatNamesActions.ActionError.AcceptNameExpired) {
            }
        }
    }

    // Expires soon follows the period (IOS-060)

    private fun params(periodMs: Long, renewWindowMs: Long) = Params(
        bond = 0, gapValue = 0, tCommit = 600, maxYears = 2, periodMs = periodMs, graceMs = periodMs,
        renewWindowMs = renewWindowMs, genesisPrices = List(5) { 0L }, priceShards = 1, priceValue = 0, offerMaxFee = 0
    )

    @Test
    fun expiresSoonIsThirtyDaysOnAYearlyClockAndTheRenewalWindowOnAShortOne() {
        // mainnet: a year, a 10-day renewal window -> 30 days
        assertEquals(30L * 86_400_000L, params(KachatNames.YEAR_MS, 10L * 86_400_000L).expiresSoonMs)
        // testnet-10: 10-minute periods and window -> the window, not 30 days that cover every name
        assertEquals(600_000L, params(600_000L, 600_000L).expiresSoonMs)
        // a short window on a short clock: a twelfth of the period
        assertEquals(50_000L, params(600_000L, 10_000L).expiresSoonMs)
    }
}
