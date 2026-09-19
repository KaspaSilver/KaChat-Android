package com.kachat.app.services

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts this account deleted, by transaction id, per wallet.
 *
 * The chain keeps the bytes it always had, and the indexer only stops serving a deleted post
 * once it honours the `delete` action (KAPOSTS_INDEXER.md section 5.8). Until then every refetch
 * handed the post straight back, so a delete looked like it had not worked. This is the device's
 * own memory of the deletion: the one funnel from indexer rows into the feeds drops anything
 * listed here.
 *
 * Read synchronously, because that funnel maps a page wherever it happens to run.
 */
@Singleton
class KaPostsDeletedPostsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val lock = Any()
    private var walletKey: String? = null
    private var ids: MutableSet<String> = mutableSetOf()

    /** Points the store at the active wallet's list, loading it. Null clears it. */
    fun setCurrentWallet(address: String?) = synchronized(lock) {
        walletKey = address?.takeIf { it.isNotEmpty() }?.let { "deleted_" + it.replace(":", "_") }
        ids = walletKey?.let { prefs.getStringSet(it, emptySet())?.toMutableSet() } ?: mutableSetOf()
    }

    fun contains(txId: String): Boolean = synchronized(lock) { txId in ids }

    fun insert(txId: String) = synchronized(lock) {
        if (txId.isEmpty()) return@synchronized
        val key = walletKey ?: return@synchronized
        ids.add(txId)
        // Bounded: a deletion older than the newest few thousand is one the indexer has long
        // since honoured, so remembering it buys nothing.
        if (ids.size > MAX_ENTRIES) ids = ids.toList().takeLast(MAX_ENTRIES).toMutableSet()
        prefs.edit().putStringSet(key, ids.toSet()).apply()
    }

    private companion object {
        const val PREFS = "kachat_kaposts_deleted"
        const val MAX_ENTRIES = 2_000
    }
}
