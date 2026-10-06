package com.kachat.app.services.kachatnames

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The app's own rules on what a `.kachat` action may spend (the iOS audit fixes): never more than
 * the price the person confirmed (iOS 4f5d95e, IOS-054).
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
}
