package com.kachat.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.SubcomposeAsyncImage
import com.kachat.app.R
import com.kachat.app.services.ShareCompose
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.KaspaAddress
import com.kachat.app.viewmodels.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * In-place compose for a system share, ported from iOS's `ShareViewController`: once a
 * conversation has been chosen (a direct-share pick on the system share sheet, or the chat the
 * user opened for an untargeted share) the shared text/URL and any shared image are shown in an
 * editable compose sheet with the contact's name and a Send button — rather than dumping the user
 * into a chat with the composer silently pre-filled.
 *
 * Unlike iOS's out-of-process extension, Android runs this in-process with full wallet access, so
 * Send goes out immediately with progress and a success state. If the send fails the sheet closes
 * into that chat with the text staged as a draft, so nothing the user typed is lost.
 */
@Composable
fun ShareComposeSheet(
    share: ShareCompose,
    /** Opens the conversation — used for the draft fallback after a failed send. */
    onOpenChat: (String) -> Unit,
    onDismiss: () -> Unit,
    // Must be the SAME instance the chat thread uses (the shell's, passed down), or the draft
    // staged on a failed send would land in a composer nobody is reading.
    chatViewModel: ChatViewModel = hiltViewModel()
) {
    val conversations by chatViewModel.conversations.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val contactLabel = remember(conversations, share.contactId) {
        val contact = conversations.firstOrNull { it.contact.id == share.contactId }?.contact
        contact?.alias?.takeIf { it.isNotBlank() }
            ?: contact?.knsName?.takeIf { it.isNotBlank() }
            ?: KaspaAddress.shortDisplay(share.contactId)
    }

    var text by remember(share) { mutableStateOf(share.text.orEmpty()) }
    var isSending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    val canSend = !isSending && !sent && (text.isNotBlank() || share.imageUris.isNotEmpty())

    // Success state lingers just long enough to read, then the sheet gets out of the way.
    LaunchedEffect(sent) {
        if (sent) {
            delay(900)
            onDismiss()
        }
    }

    Dialog(onDismissRequest = { if (!isSending) onDismiss() }) {
        Surface(
            color = LocalAppColors.current.surface,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.send_to),
                            color = LocalAppColors.current.textSecondary,
                            fontSize = 12.sp
                        )
                        Text(
                            contactLabel,
                            color = LocalAppColors.current.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            maxLines = 1
                        )
                    }
                    IconButton(onClick = { if (!isSending) onDismiss() }, enabled = !isSending) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.cancel),
                            tint = LocalAppColors.current.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                if (share.imageUris.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        share.imageUris.take(3).forEach { uri ->
                            SubcomposeAsyncImage(
                                model = uri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(LocalAppColors.current.surfaceVariant)
                            )
                        }
                        if (share.imageUris.size > 3) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(LocalAppColors.current.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "+${share.imageUris.size - 3}",
                                    color = LocalAppColors.current.textSecondary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    enabled = !isSending && !sent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 90.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = LocalAppColors.current.surfaceVariant,
                        unfocusedContainerColor = LocalAppColors.current.surfaceVariant,
                        disabledContainerColor = LocalAppColors.current.surfaceVariant,
                        focusedTextColor = LocalAppColors.current.textPrimary,
                        unfocusedTextColor = LocalAppColors.current.textPrimary,
                        disabledTextColor = LocalAppColors.current.textSecondary,
                        cursorColor = KaspaTeal,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    )
                )

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (!canSend) return@Button
                        isSending = true
                        val payloadText = text
                        scope.launch {
                            val ok = try {
                                chatViewModel.sendSharedContent(share.contactId, payloadText, share.imageUris)
                            } catch (e: Exception) {
                                false
                            }
                            isSending = false
                            if (ok) {
                                sent = true
                            } else {
                                // Nothing typed is ever lost: stage the text in that chat's
                                // composer and drop the user there to retry manually.
                                chatViewModel.setMessageText(payloadText)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.couldnt_send_saved_as_draft),
                                    Toast.LENGTH_LONG
                                ).show()
                                onDismiss()
                                onOpenChat(share.contactId)
                            }
                        }
                    },
                    enabled = canSend || sent,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = KaspaTeal,
                        disabledContainerColor = LocalAppColors.current.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    when {
                        sent -> {
                            Icon(Icons.Default.CheckCircle, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.sent), color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                        isSending -> {
                            CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.sending), color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                        else -> {
                            Icon(Icons.AutoMirrored.Filled.Send, null, tint = if (canSend) Color.Black else LocalAppColors.current.textSecondary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.send),
                                color = if (canSend) Color.Black else LocalAppColors.current.textSecondary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.share_to_kachat),
                    color = LocalAppColors.current.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
