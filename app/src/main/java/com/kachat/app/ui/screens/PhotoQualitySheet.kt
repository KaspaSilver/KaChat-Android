package com.kachat.app.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kachat.app.R
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.util.KaspaUnit
import com.kachat.app.util.PhotoTier
import java.text.NumberFormat

/**
 * Send-time quality for an on-chain photo, 1:1 and groups (iOS 7c5c22e3 `PhotoQualitySheet`):
 * High (up to 1 KAS), Medium (up to 0.5 KAS), Low (up to 0.1 KAS) - [PhotoTier], MESSAGING.md
 * "Photos: quality tiers and multi-part photos". Each row shows its budget until the photo has
 * been compressed for it, then the fee that photo will cost ([fees]). The Nextcloud route never
 * shows this.
 */
@Composable
fun PhotoQualitySheet(
    fees: Map<PhotoTier, Long>,
    onChoose: (PhotoTier) -> Unit,
    onDismiss: () -> Unit,
) {
    ActionSheetContainer(title = stringResource(R.string.photo_quality), subtitle = null, onDismiss = onDismiss) {
        for (tier in PhotoTier.values()) {
            ActionSheetRow(
                icon = when (tier) {
                    PhotoTier.HIGH -> Icons.Filled.Photo
                    PhotoTier.MEDIUM -> Icons.Outlined.Photo
                    PhotoTier.LOW -> Icons.Filled.Image
                },
                title = stringResource(
                    when (tier) {
                        PhotoTier.HIGH -> R.string.photo_quality_high
                        PhotoTier.MEDIUM -> R.string.photo_quality_medium
                        PhotoTier.LOW -> R.string.photo_quality_low
                    }
                ),
                subtitle = photoTierFeeLabel(fees[tier] ?: tier.budgetSompi),
                onClick = { onChoose(tier) },
            )
        }
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(stringResource(R.string.cancel), color = KaspaTeal, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * "(~1 KAS)": two decimals (0.9912 -> 0.99, 0.4975 -> 0.5, 0.0977 -> 0.1); below 0.01, two
 * significant digits, so a tiny fee never reads as 0. Language-neutral, as on iOS.
 */
fun photoTierFeeLabel(sompi: Long): String {
    val kas = sompi / 100_000_000.0
    val formatter = NumberFormat.getNumberInstance()
    val text = if (kas > 0 && kas < 0.01) {
        val rounded = java.math.BigDecimal(kas).round(java.math.MathContext(2))
        formatter.maximumFractionDigits = maxOf(0, rounded.scale())
        formatter.format(rounded.toDouble())
    } else {
        formatter.minimumFractionDigits = 0
        formatter.maximumFractionDigits = 2
        formatter.format(kas)
    }
    return KaspaUnit.label("(~$text KAS)")
}
