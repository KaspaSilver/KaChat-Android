package com.kachat.app.util

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * The haptics iOS's `Haptics` helper plays (KaChat/Utilities/Haptics.swift): the three impact
 * weights, the three notification outcomes and the selection tick.
 */
enum class IosHaptic {
    /** `Haptics.impact(.light)` */
    IMPACT_LIGHT,
    /** `Haptics.impact(.medium)` */
    IMPACT_MEDIUM,
    /** `Haptics.impact(.heavy)` */
    IMPACT_HEAVY,
    /** `Haptics.success()` */
    SUCCESS,
    /** `UINotificationFeedbackGenerator().notificationOccurred(.warning)` */
    WARNING,
    /** `Haptics.error()` */
    ERROR,
    /** `Haptics.selection()` */
    SELECTION,
}

/**
 * One place that turns an iOS haptic into the closest Android one, so every screen plays the same
 * feedback for the same moment. Android has no impact weights or notification patterns, so each
 * maps to the [HapticFeedbackConstants] constant that feels nearest, with a fallback where that
 * constant is newer than the device:
 * - light impact: KEYBOARD_TAP, the lightest click every Android version has;
 * - medium impact: VIRTUAL_KEY, a firmer click;
 * - heavy impact: LONG_PRESS, the strongest standard pulse;
 * - success: CONFIRM on Android 11+ (its own "done" pattern), LONG_PRESS before;
 * - warning and error: REJECT on Android 11+ (its own "refused" pattern), LONG_PRESS before;
 * - selection: SEGMENT_TICK on Android 14+ (the tick of a picker moving), CLOCK_TICK before.
 */
object Haptics {

    /** The [HapticFeedbackConstants] value [haptic] plays on a device running [sdkInt]. */
    fun constantFor(haptic: IosHaptic, sdkInt: Int = Build.VERSION.SDK_INT): Int = when (haptic) {
        IosHaptic.IMPACT_LIGHT -> HapticFeedbackConstants.KEYBOARD_TAP
        IosHaptic.IMPACT_MEDIUM -> HapticFeedbackConstants.VIRTUAL_KEY
        IosHaptic.IMPACT_HEAVY -> HapticFeedbackConstants.LONG_PRESS
        IosHaptic.SUCCESS ->
            if (sdkInt >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
        IosHaptic.WARNING, IosHaptic.ERROR ->
            if (sdkInt >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        IosHaptic.SELECTION ->
            if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
    }

    /** Plays [haptic] on [view]. */
    fun perform(view: View, haptic: IosHaptic) {
        view.performHapticFeedback(constantFor(haptic))
    }
}

/** [Haptics.perform] on this view. */
fun View.playHaptic(haptic: IosHaptic) = Haptics.perform(this, haptic)

/** A player bound to the current view, for composables: `val haptic = rememberHaptics()`, then
 *  `haptic(IosHaptic.IMPACT_LIGHT)`. */
@Composable
fun rememberHaptics(): (IosHaptic) -> Unit {
    val view = LocalView.current
    return remember(view) { { haptic: IosHaptic -> Haptics.perform(view, haptic) } }
}
