package com.kachat.app.services.contacts

import android.accounts.Account
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.IBinder

/**
 * A contacts sync adapter that deliberately does nothing.
 *
 * The contacts provider requires one before an account type may own contact rows, and the
 * Contacts app reads the layout of KaChat's rows (CONTACTS_STRUCTURE in the manifest) from this
 * service. There is nothing to sync: the rows are written straight from the app the moment the
 * "Allow calls" switch changes, and nothing about a KaChat contact ever leaves the phone here.
 */
class KaChatContactsSyncAdapter(context: Context) : AbstractThreadedSyncAdapter(context, true, false) {
    override fun onPerformSync(
        account: Account?,
        extras: Bundle?,
        authority: String?,
        provider: ContentProviderClient?,
        syncResult: SyncResult?,
    ) {
        // Nothing to do, and nothing to report as an error either - a sync that does nothing has
        // succeeded.
    }
}

class KaChatContactsSyncService : Service() {
    private val adapter by lazy { KaChatContactsSyncAdapter(applicationContext) }
    override fun onBind(intent: Intent?): IBinder = adapter.syncAdapterBinder
}
