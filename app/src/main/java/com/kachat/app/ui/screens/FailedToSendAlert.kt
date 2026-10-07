package com.kachat.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.SendFailureHint

/**
 * iOS ChatDetailView's "Failed to Send" alert: the reason, and under it - unless the reason is a
 * balance shortfall - "Please check your network connection and try again." in the secondary
 * colour. OK closes it.
 */
@Composable
fun FailedToSendAlert(reason: String, onDismiss: () -> Unit) {
    val colors = LocalAppColors.current
    com.kachat.app.ui.theme.IosAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.failed_to_send_title), color = colors.textPrimary) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(reason, color = colors.textPrimary, textAlign = TextAlign.Center)
                if (SendFailureHint.showsNetworkHint(reason)) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.send_failure_network_hint),
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok), color = KaspaTeal) }
        },
    )
}
