package com.kachat.app.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.kachat.app.R

/**
 * iOS's `design: .rounded` (SF Rounded) for the big figures - amounts, counts, countdowns, the
 * "#" room badge. SF Rounded can't ship on Android, so this is Nunito (SIL OFL 1.1, license in
 * assets/licenses/Nunito-OFL.txt), one variable font (wght 200-1000) set to each weight iOS
 * uses. Its digits are all the same width, so it also stands in for `.monospacedDigit()`.
 * (Font(resId, variationSettings) is still marked experimental in Compose 1.6; it works from API 26.)
 */
@OptIn(ExperimentalTextApi::class)
val IosRoundedFontFamily: FontFamily = FontFamily(
    listOf(
        FontWeight.Normal,
        FontWeight.Medium,
        FontWeight.SemiBold,
        FontWeight.Bold,
        FontWeight.ExtraBold, // iOS .heavy
        FontWeight.Black,
    ).map { weight ->
        Font(
            R.font.nunito,
            weight = weight,
            variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
        )
    }
)
