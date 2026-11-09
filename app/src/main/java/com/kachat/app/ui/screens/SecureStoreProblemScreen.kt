package com.kachat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.authenticateWithDeviceCredential

/**
 * Shown instead of the app when the phone's Keystore can't open the account stores, as after a
 * transfer from another phone or a Keystore fault. Nothing is removed unless the person asks: a
 * fault can pass, so trying again comes first.
 */
@Composable
fun SecureStoreProblemScreen(onTryAgain: () -> Unit, onStartOver: () -> Unit) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    var confirming by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxSize().background(colors.background).systemBarsPadding().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.secure_store_title),
                color = colors.textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.secure_store_message),
                color = colors.textSecondary,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(KaspaTeal, RoundedCornerShape(26.dp))
                    .clickable(onClick = onTryAgain),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.try_again), color = Color.Black, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            }
            TextButton(onClick = { confirming = true }) {
                Text(stringResource(R.string.secure_store_start_over), color = colors.danger)
            }
        }
    }
    if (confirming) {
        com.kachat.app.ui.theme.IosAlertDialog(
            onDismissRequest = { confirming = false },
            containerColor = colors.surface,
            title = { Text(stringResource(R.string.secure_store_confirm_title), color = colors.textPrimary) },
            text = { Text(stringResource(R.string.secure_store_confirm_message), color = colors.textSecondary) },
            confirmButton = {
                // The device's lock first, as iOS's Wallet Recovery asks Face ID before Remove This
                // Account. With no screen lock it refuses and says to set one, as iOS does with no
                // passcode: anyone holding the unlocked phone must not be able to wipe the accounts.
                TextButton(onClick = {
                    confirming = false
                    context.authenticateWithDeviceCredential(
                        title = context.getString(R.string.confirm_deleting_account),
                        onSuccess = onStartOver,
                    )
                }) {
                    Text(stringResource(R.string.secure_store_start_over), color = colors.danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.cancel), color = colors.textSecondary)
                }
            },
        )
    }
}
