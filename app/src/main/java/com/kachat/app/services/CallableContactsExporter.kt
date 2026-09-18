package com.kachat.app.services

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.util.Log
import androidx.core.content.ContextCompat
import com.kachat.app.R
import com.kachat.app.models.ContactEntity
import com.kachat.app.models.displayName
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts "KaChat call" and "KaChat video call" on the phone's own contact card, so someone can be
 * called from the Contacts app, from a recent call, or by voice, without opening KaChat first.
 * Android's answer to iOS donating an INStartCallIntent, and the same promise: the rows appear
 * on the card of the phone contact a KaChat contact is linked to.
 *
 * Only contacts you have allowed calls with get a row, and only those linked to a phone contact -
 * there is no card to put a row on otherwise, and inventing one would mean adding people to the
 * address book that the user never put there. Turning "Allow calls" off takes the rows away
 * again, so the contact card never offers a call the app would ignore.
 *
 * Nothing about this leaves the phone: the rows live in the local contacts database under
 * KaChat's own account, which exists only to own them (see
 * [com.kachat.app.services.contacts.KaChatAccountService]).
 */
@Singleton
class CallableContactsExporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** What one row has to say, so an unchanged contact is left alone. */
    private data class Exported(val rawContactId: Long, val fingerprint: String)

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /**
     * Brings the contact cards in line with [contacts]: a row for everyone callable and linked,
     * no row for anyone else. Safe to call as often as you like - an unchanged contact costs one
     * comparison. Touches the ContentResolver, so callers must stay off the main thread.
     */
    fun sync(contacts: List<ContactEntity>) {
        if (!hasPermission()) return
        val account = ensureAccount() ?: return
        val resolver = context.contentResolver

        val wanted = contacts
            .filter { it.callsEnabled == true && !it.systemContactId.isNullOrBlank() }
            .associateBy { it.id }
        val existing = readExported(resolver, account)

        // Gone, or no longer callable, or the name changed: drop the row and let it be rebuilt.
        val stale = existing.filterKeys { address ->
            val contact = wanted[address]
            contact == null || existing[address]?.fingerprint != fingerprint(contact)
        }
        stale.forEach { (address, exported) ->
            runCatching { deleteRawContact(resolver, exported.rawContactId) }
                .onFailure { Log.w(TAG, "Could not remove the call row for ${address.takeLast(8)}: ${it.message}") }
        }

        val alreadyGood = existing.keys - stale.keys
        wanted.filterKeys { it !in alreadyGood }.forEach { (address, contact) ->
            runCatching { export(resolver, account, contact) }
                .onFailure { Log.w(TAG, "Could not add the call row for ${address.takeLast(8)}: ${it.message}") }
        }
    }

    /** Takes every KaChat row off every contact card - for leaving the app, or dropping the wallet. */
    fun removeAll() {
        if (!hasPermission()) return
        val account = accountOrNull() ?: return
        val resolver = context.contentResolver
        readExported(resolver, account).values.forEach { exported ->
            runCatching { deleteRawContact(resolver, exported.rawContactId) }
        }
    }

    // ---- The account those rows belong to ----

    private fun accountOrNull(): Account? = runCatching {
        AccountManager.get(context).getAccountsByType(ACCOUNT_TYPE).firstOrNull()
    }.getOrNull()

    /**
     * The one KaChat account, created on first use. It carries no password and no token; the
     * contacts provider simply refuses to let an app own contact rows without one.
     */
    private fun ensureAccount(): Account? {
        accountOrNull()?.let { return it }
        val account = Account(ACCOUNT_NAME, ACCOUNT_TYPE)
        val added = runCatching { AccountManager.get(context).addAccountExplicitly(account, null, null) }.getOrDefault(false)
        if (!added && accountOrNull() == null) return null
        runCatching {
            ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, 1)
            // Nothing to sync, ever - the app writes the rows itself.
            ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, false)
        }
        return accountOrNull() ?: account
    }

    // ---- Reading what is already there ----

    /** Every row KaChat has written, by the address it was written for. */
    private fun readExported(resolver: ContentResolver, account: Account): Map<String, Exported> {
        val result = mutableMapOf<String, Exported>()
        val projection = arrayOf(
            ContactsContract.RawContacts._ID,
            ContactsContract.RawContacts.SOURCE_ID,
            ContactsContract.RawContacts.SYNC1,
        )
        val selection = "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND ${ContactsContract.RawContacts.ACCOUNT_NAME} = ?"
        runCatching {
            resolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                projection,
                selection,
                arrayOf(account.type, account.name),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val address = cursor.getString(1) ?: continue
                    result[address] = Exported(cursor.getLong(0), cursor.getString(2).orEmpty())
                }
            }
        }.onFailure { Log.w(TAG, "Could not read KaChat's contact rows: ${it.message}") }
        return result
    }

    /** Everything a row shows. Change any of it and the row is rebuilt. */
    private fun fingerprint(contact: ContactEntity): String =
        "${contact.displayName}|${contact.systemContactId.orEmpty()}|v1"

    // ---- Writing ----

    private fun export(resolver: ContentResolver, account: Account, contact: ContactEntity) {
        val lookupKey = contact.systemContactId ?: return
        // Resolved before the insert, so the query cannot pick up the row being added.
        val targetRawContactId = rawContactIdForLookupKey(resolver, lookupKey)
        val name = contact.displayName
        val ops = arrayListOf<ContentProviderOperation>()

        ops += ContentProviderOperation.newInsert(syncUri(ContactsContract.RawContacts.CONTENT_URI))
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, account.name)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type)
            .withValue(ContactsContract.RawContacts.SOURCE_ID, contact.id)
            .withValue(ContactsContract.RawContacts.SYNC1, fingerprint(contact))
            .build()

        // The name is what lets the contacts database recognise this as the same person; the
        // aggregation exception below is what makes sure of it.
        ops += ContentProviderOperation.newInsert(syncUri(ContactsContract.Data.CONTENT_URI))
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
            .withValue(StructuredName.DISPLAY_NAME, name)
            .build()

        ops += callRow(mimeType = MIME_VOICE_CALL, address = contact.id, detail = context.getString(R.string.contacts_action_voice_call))
        ops += callRow(mimeType = MIME_VIDEO_CALL, address = contact.id, detail = context.getString(R.string.contacts_action_video_call))

        val results = resolver.applyBatch(ContactsContract.AUTHORITY, ops)
        val newRawContactId = results.firstOrNull()?.uri?.let { ContentUris.parseId(it) } ?: return

        // Without this the new rows can end up on a contact of their own instead of on the card
        // the user already has for this person.
        if (targetRawContactId != null) keepTogether(resolver, newRawContactId, targetRawContactId)
    }

    private fun callRow(mimeType: String, address: String, detail: String): ContentProviderOperation =
        ContentProviderOperation.newInsert(syncUri(ContactsContract.Data.CONTENT_URI))
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, mimeType)
            // DATA1 is what comes back when the row is tapped: who to call.
            .withValue(ContactsContract.Data.DATA1, address)
            .withValue(ContactsContract.Data.DATA2, context.getString(R.string.app_name))
            .withValue(ContactsContract.Data.DATA3, detail)
            .build()

    /** Ties KaChat's rows to the phone contact they belong to, so they show on its card. */
    private fun keepTogether(resolver: ContentResolver, ours: Long, theirs: Long) {
        runCatching {
            val values = android.content.ContentValues().apply {
                put(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
                put(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, ours)
                put(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, theirs)
            }
            resolver.update(ContactsContract.AggregationExceptions.CONTENT_URI, values, null, null)
        }.onFailure { Log.w(TAG, "Could not attach the call rows to the contact: ${it.message}") }
    }

    /** One raw contact of the phone contact behind [lookupKey] - never one of KaChat's own. */
    private fun rawContactIdForLookupKey(resolver: ContentResolver, lookupKey: String): Long? = runCatching {
        val lookupUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookupKey)
        val contactUri = ContactsContract.Contacts.lookupContact(resolver, lookupUri) ?: return null
        val contactId = ContentUris.parseId(contactUri)
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID} = ? AND (${ContactsContract.RawContacts.ACCOUNT_TYPE} IS NULL OR ${ContactsContract.RawContacts.ACCOUNT_TYPE} != ?)",
            arrayOf(contactId.toString(), ACCOUNT_TYPE),
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
    }.getOrNull()

    private fun deleteRawContact(resolver: ContentResolver, rawContactId: Long) {
        val uri = ContentUris.withAppendedId(syncUri(ContactsContract.RawContacts.CONTENT_URI), rawContactId)
        resolver.delete(uri, null, null)
    }

    /** Writing as the sync adapter: the row is ours, so it is gone for good when deleted rather
     *  than being left behind as a pending change for a sync that never comes. */
    private fun syncUri(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, ACCOUNT_NAME)
        .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
        .build()

    companion object {
        private const val TAG = "CallableContacts"
        const val ACCOUNT_TYPE = "com.kachat.app"
        const val ACCOUNT_NAME = "KaChat"
        const val MIME_VOICE_CALL = "vnd.android.cursor.item/vnd.com.kachat.app.call"
        const val MIME_VIDEO_CALL = "vnd.android.cursor.item/vnd.com.kachat.app.videocall"
    }
}
