package com.kachat.app.util

import android.view.HapticFeedbackConstants
import org.junit.Assert.assertEquals
import org.junit.Test

/** The iOS-haptic to Android-constant table, on each side of the API levels it changes at. */
class HapticsTest {

    @Test
    fun `impacts map to the same click on every version`() {
        for (sdk in listOf(26, 29, 30, 33, 34, 36)) {
            assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, Haptics.constantFor(IosHaptic.IMPACT_LIGHT, sdk))
            assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, Haptics.constantFor(IosHaptic.IMPACT_MEDIUM, sdk))
            assertEquals(HapticFeedbackConstants.LONG_PRESS, Haptics.constantFor(IosHaptic.IMPACT_HEAVY, sdk))
        }
    }

    @Test
    fun `success is CONFIRM from Android 11, a long press before`() {
        assertEquals(HapticFeedbackConstants.LONG_PRESS, Haptics.constantFor(IosHaptic.SUCCESS, 29))
        assertEquals(HapticFeedbackConstants.CONFIRM, Haptics.constantFor(IosHaptic.SUCCESS, 30))
        assertEquals(HapticFeedbackConstants.CONFIRM, Haptics.constantFor(IosHaptic.SUCCESS, 36))
    }

    @Test
    fun `warning and error are REJECT from Android 11, a long press before`() {
        for (haptic in listOf(IosHaptic.WARNING, IosHaptic.ERROR)) {
            assertEquals(HapticFeedbackConstants.LONG_PRESS, Haptics.constantFor(haptic, 26))
            assertEquals(HapticFeedbackConstants.LONG_PRESS, Haptics.constantFor(haptic, 29))
            assertEquals(HapticFeedbackConstants.REJECT, Haptics.constantFor(haptic, 30))
            assertEquals(HapticFeedbackConstants.REJECT, Haptics.constantFor(haptic, 36))
        }
    }

    @Test
    fun `selection is SEGMENT_TICK from Android 14, a clock tick before`() {
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, Haptics.constantFor(IosHaptic.SELECTION, 26))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, Haptics.constantFor(IosHaptic.SELECTION, 33))
        assertEquals(HapticFeedbackConstants.SEGMENT_TICK, Haptics.constantFor(IosHaptic.SELECTION, 34))
        assertEquals(HapticFeedbackConstants.SEGMENT_TICK, Haptics.constantFor(IosHaptic.SELECTION, 36))
    }

    @Test
    fun `every haptic has a mapping`() {
        for (haptic in IosHaptic.values()) Haptics.constantFor(haptic, 26)
    }
}
