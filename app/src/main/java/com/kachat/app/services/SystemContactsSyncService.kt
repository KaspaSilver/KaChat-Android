package com.kachat.app.services

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.Website
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class SystemContactLinkTarget(val lookupKey: String, val displayName: String, val photoUri: String? = null)

internal data class ScannedRow(
    val contactId: Long,
    val lookupKey: String,
    val displayName: String,
    val value: String,
    /** `Contacts.PHOTO_URI` for this contact, if it has a photo — the device-address-book avatar. */
    val photoUri: String? = null
)

/**
 * Automatic system-contacts sync, matching iOS's real algorithm (ContactsManager.swift's
 * SystemContactsService): scan every system contact's website/email/phone fields as raw text
 * for an embedded Kaspa address, exact-match against known chat addresses, and — if "Autocreate"
 * is on — create a real new phone contact for any chat that has no match. Auto-created contacts
 * are tagged with a `kachat:auto:<address>` marker (as a second Website row) so they're never
 * re-matched as if they were a real user contact, and can be found again for deletion if
 * Autocreate is later turned off.
 */
@Singleton
class SystemContactsSyncService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun hasReadPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun hasWritePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** One-pass scan of every system contact's website/email/phone fields; returns real address -> link target for exact matches. */
    fun findMatches(addresses: Set<String>): Map<String, SystemContactLinkTarget> {
        if (!hasReadPermission() || addresses.isEmpty()) return emptyMap()

        val rows = mutableListOf<ScannedRow>()
        // PHOTO_URI comes along for the ride on the same scan (the Data table joins the Contacts
        // columns, so it costs nothing extra) — it's what backs the device-contact avatar fallback.
        val projection = arrayOf(
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Data.LOOKUP_KEY,
            ContactsContract.Data.DISPLAY_NAME_PRIMARY,
            ContactsContract.Data.PHOTO_URI,
            ContactsContract.Data.DATA1
        )
        val selection = "${ContactsContract.Data.MIMETYPE} IN (?, ?, ?)"
        val selectionArgs = arrayOf(Website.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE, Phone.CONTENT_ITEM_TYPE)

        try {
            context.contentResolver.query(ContactsContract.Data.CONTENT_URI, projection, selection, selectionArgs, null)?.use { cursor ->
                val contactIdIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
                val lookupKeyIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.LOOKUP_KEY)
                val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.DISPLAY_NAME_PRIMARY)
                val photoIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.PHOTO_URI)
                val valueIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA1)
                while (cursor.moveToNext()) {
                    val value = cursor.getString(valueIdx) ?: continue
                    val lookupKey = cursor.getString(lookupKeyIdx) ?: continue
                    val displayName = cursor.getString(nameIdx) ?: continue
                    rows.add(ScannedRow(cursor.getLong(contactIdIdx), lookupKey, displayName, value, cursor.getString(photoIdx)))
                }
            }
        } catch (e: Exception) {
            Log.e("SystemContactsSyncService", "Failed to scan system contacts", e)
            return emptyMap()
        }

        return matchScannedRows(rows, addresses)
    }

    /**
     * The device address-book photo of an already-linked contact, re-read by its LOOKUP_KEY.
     * Lets contacts that were linked before photos were stored — and contacts whose photo changed
     * in the phone's address book since — pick the image up on the next sync. Null when the
     * contact has no photo, has been deleted, or READ_CONTACTS isn't granted.
     *
     * Hits the ContentResolver, so callers must stay off the main thread.
     */
    fun photoUriForLookupKey(lookupKey: String): String? {
        if (!hasReadPermission()) return null
        return try {
            val lookupUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookupKey)
            val contactUri = ContactsContract.Contacts.lookupContact(context.contentResolver, lookupUri) ?: return null
            context.contentResolver.query(
                contactUri,
                arrayOf(ContactsContract.Contacts.PHOTO_URI),
                null,
                null,
                null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } catch (e: Exception) {
            Log.e("SystemContactsSyncService", "Failed to read system contact photo", e)
            null
        }
    }

    /** Creates a new local phone contact embedding [address] and an auto-marker. Returns its LOOKUP_KEY, or null on failure. */
    fun createShadowContact(address: String, alias: String): String? {
        if (!hasWritePermission()) return null

        val ops = ArrayList<ContentProviderOperation>()
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                .withValue(StructuredName.GIVEN_NAME, alias)
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, Organization.CONTENT_ITEM_TYPE)
                .withValue(Organization.COMPANY, "KaChat")
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, Note.CONTENT_ITEM_TYPE)
                .withValue(Note.NOTE, "Auto-managed by KaChat")
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, Website.CONTENT_ITEM_TYPE)
                .withValue(Website.URL, address)
                .withValue(Website.TYPE, Website.TYPE_OTHER)
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, Website.CONTENT_ITEM_TYPE)
                .withValue(Website.URL, "$AUTO_MARKER_PREFIX$address")
                .withValue(Website.TYPE, Website.TYPE_OTHER)
                .withValue(Website.LABEL, "KaChat")
                .build()
        )

        return try {
            val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            val rawContactUri = results.firstOrNull()?.uri ?: return null
            lookupKeyForRawContact(ContentUris.parseId(rawContactUri))
        } catch (e: Exception) {
            Log.e("SystemContactsSyncService", "Failed to create shadow contact", e)
            null
        }
    }

    /** Deletes a previously auto-created shadow contact by lookup key — a no-op if it's already gone. */
    fun deleteShadowContact(lookupKey: String) {
        if (!hasWritePermission()) return
        try {
            val lookupUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookupKey)
            val contactUri = ContactsContract.Contacts.lookupContact(context.contentResolver, lookupUri) ?: return
            context.contentResolver.delete(contactUri, null, null)
        } catch (e: Exception) {
            Log.e("SystemContactsSyncService", "Failed to delete shadow contact", e)
        }
    }

    private fun lookupKeyForRawContact(rawContactId: Long): String? {
        val contactId = context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.CONTACT_ID),
            "${ContactsContract.RawContacts._ID} = ?",
            arrayOf(rawContactId.toString()),
            null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.CONTACT_ID)) else null }
            ?: return null

        return context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.LOOKUP_KEY),
            "${ContactsContract.Contacts._ID} = ?",
            arrayOf(contactId.toString()),
            null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)) else null }
    }

    companion object {
        internal const val AUTO_MARKER_PREFIX = "kachat:auto:"

        private val ADDRESS_REGEX = Regex("(kaspa:[a-z0-9]{20,}|kaspatest:[a-z0-9]{20,})", RegexOption.IGNORE_CASE)

        /** Every Kaspa-address-shaped substring found anywhere in [text] (a URL, email, or phone-number field's raw text). */
        internal fun extractKaspaAddresses(text: String): List<String> =
            ADDRESS_REGEX.findAll(text).map { it.value }.toList()

        /**
         * Pure matching logic (no ContentResolver dependency, directly unit-testable): exact,
         * case-insensitive match between a scanned address and a known chat address. A system
         * contact tagged with our own auto-marker is never matched — it's an app-managed shadow
         * contact, not a real user contact, even though its embedded address would otherwise
         * match too. When the same address matches multiple real contacts, keeps whichever has
         * the longer display name — mirrors iOS's identical dedup rule.
         */
        internal fun matchScannedRows(rows: List<ScannedRow>, addresses: Set<String>): Map<String, SystemContactLinkTarget> {
            val normalizedTargets = addresses.associateBy { it.lowercase() }
            val shadowContactIds = rows.filter { it.value.startsWith(AUTO_MARKER_PREFIX) }.map { it.contactId }.toSet()

            val found = mutableMapOf<String, SystemContactLinkTarget>()
            for (row in rows) {
                if (row.contactId in shadowContactIds) continue
                for (candidate in extractKaspaAddresses(row.value)) {
                    val realAddress = normalizedTargets[candidate.lowercase()] ?: continue
                    val existing = found[realAddress]
                    if (existing == null || row.displayName.length > existing.displayName.length) {
                        found[realAddress] = SystemContactLinkTarget(row.lookupKey, row.displayName, row.photoUri)
                    }
                }
            }
            return found
        }
    }
}
