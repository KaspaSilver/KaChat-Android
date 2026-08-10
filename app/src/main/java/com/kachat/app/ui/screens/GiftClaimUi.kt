package com.kachat.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.services.GiftClaimState
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.GiftViewModel

private val GiftErrorRed = Color(0xFFFF3B30)

/**
 * Prominent full-width gift-claim button for the onboarding funding step. Mirrors iOS's
 * WelcomeGuideView.giftClaimSection: stays visible in every state and grays out when not claimable.
 * [walletAddress] is the chatting (identity) address the server funds.
 */
@Composable
fun GiftClaimWizardButton(walletAddress: String?, modifier: Modifier = Modifier) {
    val vm: GiftViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.checkEligibility() }
    val claimable = state is GiftClaimState.Eligible && walletAddress != null

    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = { if (claimable) walletAddress?.let { vm.claim(it) } },
            enabled = claimable,
            colors = ButtonDefaults.buttonColors(
                containerColor = KaspaTeal,
                disabledContainerColor = LocalAppColors.current.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            val contentColor = if (claimable) Color.Black else LocalAppColors.current.textSecondary
            if (state is GiftClaimState.Claiming) {
                CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else {
                Icon(
                    imageVector = if (state is GiftClaimState.Claimed) Icons.Default.CheckCircle else Icons.Default.CardGiftcard,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                giftTitle(state, "Claim a Gift of 3 Kaspa to Get Started"),
                color = contentColor,
                fontWeight = FontWeight.Bold
            )
        }
        (state as? GiftClaimState.Unavailable)?.let {
            Spacer(Modifier.height(6.dp))
            Text(it.reason, color = GiftErrorRed, fontSize = 12.sp)
        }
    }
}

/**
 * Profile "Kaspa Gift" section - a single claim row. Mirrors iOS ProfileView.claimGiftSection,
 * including the hidden 10-tap reset gesture when the gift is already claimed.
 */
@Composable
fun GiftClaimProfileSection(walletAddress: String?) {
    val vm: GiftViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.checkEligibility() }
    var resetTaps by remember { mutableIntStateOf(0) }
    LaunchedEffect(state) { if (state !is GiftClaimState.AlreadyClaimed) resetTaps = 0 }
    val claimable = state is GiftClaimState.Eligible

    SettingsSection(title = null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    when (state) {
                        is GiftClaimState.Eligible -> walletAddress?.let { vm.claim(it) }
                        is GiftClaimState.AlreadyClaimed -> {
                            resetTaps += 1
                            if (resetTaps >= 10) { resetTaps = 0; vm.resetForRetry() }
                        }
                        else -> {}
                    }
                }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.CardGiftcard,
                contentDescription = null,
                tint = if (claimable) KaspaTeal else LocalAppColors.current.textSecondary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(16.dp))
            Text(
                giftTitle(state, "Claim Gift"),
                color = if (claimable) LocalAppColors.current.textPrimary else LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            if (state is GiftClaimState.Claiming) {
                CircularProgressIndicator(color = KaspaTeal, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            }
        }
        (state as? GiftClaimState.Unavailable)?.let {
            Text(
                it.reason,
                color = GiftErrorRed,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
    }
}

private fun giftTitle(state: GiftClaimState, claimLabel: String): String = when (state) {
    is GiftClaimState.Checking, is GiftClaimState.Eligible -> claimLabel
    is GiftClaimState.Claiming -> "Claiming gift..."
    is GiftClaimState.Claimed -> "Gift claimed"
    is GiftClaimState.AlreadyClaimed -> "Gift already claimed"
    is GiftClaimState.Unavailable -> "Gift unavailable"
}
