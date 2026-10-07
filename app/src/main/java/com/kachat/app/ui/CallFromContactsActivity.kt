package com.kachat.app.ui

import androidx.compose.foundation.layout.statusBars

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.kachat.app.MainActivity
import com.kachat.app.R
import com.kachat.app.models.ContactEntity
import com.kachat.app.models.displayName
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.repository.ChatRepository
import com.kachat.app.services.CallableContactsExporter
import com.kachat.app.services.CallService
import com.kachat.app.ui.theme.KaChatTheme
import com.kachat.app.ui.theme.LocalAppColors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * "KaChat call" tapped on a phone contact's card. The Contacts app hands over the row it was
 * tapped on; this reads who that row is for and asks "Call <name>?" - Voice, Video or Cancel -
 * before anything is placed. Then it asks for the microphone (and camera) if it has to, starts
 * the call and shows the call screen in [MainActivity].
 *
 * The question is not optional. This activity is exported (the Contacts app has to be able to
 * launch it), so any installed app can start it, and contacts Data row ids are small sequential
 * numbers that KaChat - not the caller - reads with its own READ_CONTACTS. Without a tap here a
 * zero-permission app could walk `content://com.android.contacts/data/<n>` until it hit a KaChat
 * call row and have KaChat place a call, camera and microphone live, to that contact (audit
 * AND-002). One tap on the sheet costs the real Contacts flow nothing.
 *
 * Voice or video comes from the row's own MIMETYPE (the Contacts app shows one row of each), not
 * from the intent's type, which the caller chooses: it decides which button is the highlighted
 * one, and the user can still pick the other.
 */
@AndroidEntryPoint
class CallFromContactsActivity : AppCompatActivity() {

    @Inject lateinit var chatRepository: ChatRepository
    @Inject lateinit var callService: CallService
    @Inject lateinit var settings: AppSettingsRepository

    private var pending: Pair<ContactEntity, Boolean>? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val target = pending
        if (target != null && granted[Manifest.permission.RECORD_AUDIO] == true) {
            place(target.first, target.second)
        } else {
            toast(getString(R.string.contacts_call_needs_microphone))
            finish()
        }
    }

    /** What the tapped row says: whose address, and whether it is the video row. */
    private data class CallRow(val address: String, val video: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dataUri = intent?.data
        if (dataUri == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            val row = withContext(Dispatchers.IO) { callRow(dataUri) }
            val contact = row?.let { withContext(Dispatchers.IO) { chatRepository.getContact(it.address) } }
            if (row == null || contact == null) {
                toast(getString(R.string.contacts_call_contact_missing))
                finish()
                return@launch
            }
            // The switch can have been turned off since the row was written; the row is taken
            // away when that happens, but a contact card can be stale on screen.
            if (!callService.canCall(contact)) {
                toast(getString(R.string.contacts_call_not_enabled))
                finish()
                return@launch
            }
            val dark = runCatching { settings.darkModeEnabled.first() }.getOrDefault(true)
            setContent {
                KaChatTheme(darkTheme = dark) {
                    ConfirmCallSheet(
                        name = contact.displayName,
                        suggestVideo = row.video,
                        onCall = { video -> confirm(contact, video) },
                        onCancel = { finish() },
                    )
                }
            }
        }
    }

    /** The user chose [video] on the sheet: permissions first, then the call. */
    private fun confirm(contact: ContactEntity, video: Boolean) {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (video) add(Manifest.permission.CAMERA)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) {
            place(contact, video)
        } else {
            pending = contact to video
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    /** Starts the call and brings the call screen up. */
    private fun place(contact: ContactEntity, video: Boolean) {
        callService.startCall(contact, video)
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        )
        finish()
    }

    /**
     * The KaChat address the tapped row was written for (DATA1), and whether it is the video row
     * (its MIMETYPE) - only for a row KaChat itself wrote. Any app can start this activity with
     * any content URI: one pointing at its own provider could name one of your contacts. So the
     * URI must be the contacts provider's, the row must carry KaChat's call mimetype, and its raw
     * contact must belong to KaChat's account. The confirmation sheet covers the rest (see the
     * class comment).
     */
    private fun callRow(uri: android.net.Uri): CallRow? = runCatching {
        if (uri.authority != ContactsContract.AUTHORITY) return@runCatching null
        val (row, rawContactId) = contentResolver.query(
            uri,
            arrayOf(ContactsContract.Data.DATA1, ContactsContract.Data.MIMETYPE, ContactsContract.Data.RAW_CONTACT_ID),
            null, null, null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val mime = cursor.getString(1)
            if (mime != CallableContactsExporter.MIME_VOICE_CALL && mime != CallableContactsExporter.MIME_VIDEO_CALL) return@use null
            val address = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: return@use null
            CallRow(address, video = mime == CallableContactsExporter.MIME_VIDEO_CALL) to cursor.getLong(2)
        } ?: return@runCatching null
        val accountType = contentResolver.query(
            android.content.ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawContactId),
            arrayOf(ContactsContract.RawContacts.ACCOUNT_TYPE),
            null, null, null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        if (accountType != CallableContactsExporter.ACCOUNT_TYPE) return@runCatching null
        row
    }.onFailure { Log.w("CallFromContacts", "Could not read the contact row: ${it.message}") }.getOrNull()

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}

/** "Call <name>?" with Voice, Video and Cancel. The kind of row tapped is the filled button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmCallSheet(
    name: String,
    suggestVideo: Boolean,
    onCall: (video: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = LocalAppColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    com.kachat.app.ui.theme.IosSheetColors {
        val colors = LocalAppColors.current
        ModalBottomSheet(
            shape = com.kachat.app.ui.theme.IosSheetShape,
            tonalElevation = com.kachat.app.ui.theme.IosSheetTonalElevation,
            windowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars,
            onDismissRequest = onCancel,
            sheetState = sheetState,
            containerColor = colors.background,
        ) {
            // The sheet runs down behind the navigation bar, as iOS's does behind the home indicator;
            // its content stays above it.
            Column(Modifier.navigationBarsPadding()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.contacts_call_confirm_title, name),
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        CallChoice(
                            label = stringResource(R.string.contacts_call_confirm_voice),
                            icon = Icons.Filled.Call,
                            primary = !suggestVideo,
                            onClick = { onCall(false) },
                            modifier = Modifier.weight(1f),
                        )
                        CallChoice(
                            label = stringResource(R.string.contacts_call_confirm_video),
                            icon = Icons.Filled.Videocam,
                            primary = suggestVideo,
                            onClick = { onCall(true) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cancel), color = colors.textSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun CallChoice(label: String, icon: ImageVector, primary: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = LocalAppColors.current
    if (primary) {
        Button(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.textOnAccent),
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.padding(start = 8.dp))
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Icon(icon, contentDescription = null, tint = colors.accent)
            Spacer(Modifier.padding(start = 8.dp))
            Text(label, color = colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
