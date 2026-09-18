package com.kachat.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.kachat.app.MainActivity
import com.kachat.app.models.ContactEntity
import com.kachat.app.repository.ChatRepository
import com.kachat.app.services.CallableContactsExporter
import com.kachat.app.services.CallService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * "KaChat call" tapped on a phone contact's card. The Contacts app hands over the row it was
 * tapped on; this reads who that row is for, asks for the microphone if it has to, starts the
 * call and shows the call screen. No window of its own - the call screen in [MainActivity] is
 * the only thing the user should see.
 */
@AndroidEntryPoint
class CallFromContactsActivity : AppCompatActivity() {

    @Inject lateinit var chatRepository: ChatRepository
    @Inject lateinit var callService: CallService

    private var pending: Pair<ContactEntity, Boolean>? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val target = pending
        if (target != null && granted[Manifest.permission.RECORD_AUDIO] == true) {
            place(target.first, target.second)
        } else {
            toast(getString(com.kachat.app.R.string.contacts_call_needs_microphone))
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val video = intent?.type == CallableContactsExporter.MIME_VIDEO_CALL
        val dataUri = intent?.data
        if (dataUri == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            val address = withContext(Dispatchers.IO) { addressFromRow(dataUri) }
            val contact = address?.let { withContext(Dispatchers.IO) { chatRepository.getContact(it) } }
            if (contact == null) {
                toast(getString(com.kachat.app.R.string.contacts_call_contact_missing))
                finish()
                return@launch
            }
            // The switch can have been turned off since the row was written; the row is taken
            // away when that happens, but a contact card can be stale on screen.
            if (!callService.canCall(contact)) {
                toast(getString(com.kachat.app.R.string.contacts_call_not_enabled))
                finish()
                return@launch
            }
            val needed = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (video) add(Manifest.permission.CAMERA)
            }.filter { ContextCompat.checkSelfPermission(this@CallFromContactsActivity, it) != PackageManager.PERMISSION_GRANTED }
            if (needed.isEmpty()) {
                place(contact, video)
            } else {
                pending = contact to video
                permissionLauncher.launch(needed.toTypedArray())
            }
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

    /** The KaChat address the tapped row was written for (DATA1). */
    private fun addressFromRow(uri: android.net.Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(ContactsContract.Data.DATA1), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }.onFailure { Log.w("CallFromContacts", "Could not read the contact row: ${it.message}") }.getOrNull()

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
