package com.kachat.app.services

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * A post scheduled for later (KAPOSTS_INDEXER.md section 5.10): built and signed when the author
 * picked the time, submitted by the indexer at that time - or by this phone, if the indexer could
 * not be reached, the next time the app runs after it.
 *
 * [txId] is the id the transaction will have on chain, computed before it is submitted (see
 * [com.kachat.app.util.KaspaTransactionId]) - the indexer keys its entry, its status and its
 * cancellation on it.
 */
data class KaPostScheduledEntry(
    val txId: String,
    val text: String,
    val notBeforeMs: Long,
    val createdAtMs: Long,
    /** "txid:index" of every coin this transaction spends - kept out of every other send until
     *  the post is on chain, or it would be invalid by the time its moment came. */
    val spentOutpoints: List<String>,
    /** The signed transaction itself, for the phone's own fallback submission. */
    val transaction: RawTransaction,
    /** Whether the indexer took it. False means this phone is the only thing that will send it. */
    val onServer: Boolean = false,
    val status: String = STATUS_SCHEDULED,
    val error: String? = null,
    val submittedAtMs: Long? = null,
) {
    companion object {
        const val STATUS_SCHEDULED = "scheduled"
        const val STATUS_SUBMITTED = "submitted"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"
    }
}

/**
 * This wallet's scheduled posts, and the keeper of the coins they will spend.
 *
 * [reservedOutpoints] is read by the wallet engine before it chooses inputs, so a coin a
 * scheduled post depends on is never spent underneath it. The reservation lasts exactly as long
 * as the entry is `scheduled`. Mirrors iOS's `KaPostsScheduledStore`.
 */
@Singleton
class KaPostsScheduledStore @Inject constructor(
    @ApplicationContext context: Context,
    private val walletManager: WalletManager,
    /** Lazy: the service is what talks to the indexer, and it must not need this store to exist. */
    private val kaPostsService: Provider<KaPostsService>,
) {
    private val prefs = context.getSharedPreferences("kaposts_scheduled", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _entries = MutableStateFlow<List<KaPostScheduledEntry>>(emptyList())
    val entries: StateFlow<List<KaPostScheduledEntry>> = _entries.asStateFlow()

    private var loadedWallet: String? = null

    private fun walletAddressOrNull(): String? =
        try { walletManager.getAddress() } catch (_: Exception) { null }

    private fun key(wallet: String) = "scheduled_${wallet.replace(":", "_")}"

    /** Loads the active wallet's entries; call before reading and on account switches. */
    @Synchronized
    fun reloadIfNeeded() {
        val wallet = walletAddressOrNull()
        if (wallet == null) {
            _entries.value = emptyList()
            loadedWallet = null
            publishReserved()
            return
        }
        if (loadedWallet == wallet) return
        loadedWallet = wallet
        _entries.value = read(wallet)
        publishReserved()
    }

    private fun read(wallet: String): List<KaPostScheduledEntry> {
        val raw = prefs.getString(key(wallet), null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<KaPostScheduledEntry>>() {}.type
            gson.fromJson<List<KaPostScheduledEntry>>(raw, type).orEmpty().sortedBy { it.notBeforeMs }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read scheduled posts", e)
            emptyList()
        }
    }

    @Synchronized
    private fun persist(entries: List<KaPostScheduledEntry>) {
        val wallet = walletAddressOrNull() ?: return
        val sorted = entries.sortedBy { it.notBeforeMs }
        _entries.value = sorted
        prefs.edit().putString(key(wallet), gson.toJson(sorted)).apply()
        publishReserved()
    }

    private fun publishReserved() {
        reservedOutpoints = _entries.value
            .filter { it.status == KaPostScheduledEntry.STATUS_SCHEDULED }
            .flatMap { it.spentOutpoints }
            .toSet()
    }

    // MARK: - Scheduling

    /**
     * Records a signed post for [notBeforeMs], hands it to the indexer, and - when the indexer
     * cannot be reached - keeps it here for this phone to submit once the time has passed.
     */
    suspend fun add(scheduled: KaPostsService.ScheduledTransaction, text: String, notBeforeMs: Long): KaPostScheduledEntry {
        reloadIfNeeded()
        var entry = KaPostScheduledEntry(
            txId = scheduled.txId,
            text = text,
            notBeforeMs = notBeforeMs,
            createdAtMs = System.currentTimeMillis(),
            spentOutpoints = scheduled.spentOutpoints,
            transaction = scheduled.transaction,
        )
        persist(_entries.value.filterNot { it.txId == entry.txId } + entry)
        try {
            kaPostsService.get().scheduleOnServer(entry.txId, entry.transaction, notBeforeMs)
            entry = entry.copy(onServer = true)
            update(entry)
        } catch (e: Exception) {
            Log.w(TAG, "Scheduling on the indexer failed, keeping it on the phone", e)
        }
        return entry
    }

    @Synchronized
    private fun update(entry: KaPostScheduledEntry) {
        val without = _entries.value.filterNot { it.txId == entry.txId }
        persist(without + entry)
    }

    /** Cancels a post that has not gone out: told to the indexer if it holds it, dropped here,
     *  and its coins released. */
    suspend fun cancel(entry: KaPostScheduledEntry) {
        if (entry.onServer && entry.status == KaPostScheduledEntry.STATUS_SCHEDULED) {
            try { kaPostsService.get().cancelScheduledOnServer(entry.txId) } catch (e: Exception) {
                Log.w(TAG, "Cancelling on the indexer failed; dropping it locally anyway", e)
            }
        }
        persist(_entries.value.filterNot { it.txId == entry.txId })
    }

    /** Forgets a submitted/failed row - nothing is reserved by it any more. */
    fun remove(entry: KaPostScheduledEntry) {
        persist(_entries.value.filterNot { it.txId == entry.txId })
    }

    /**
     * Submits, from this phone, every post the indexer never took whose time has come, and tries
     * the indexer again for the ones still in the future. Called when KaPosts opens.
     */
    suspend fun sendDueLocally() {
        reloadIfNeeded()
        val now = System.currentTimeMillis()
        for (entry in _entries.value) {
            if (entry.status != KaPostScheduledEntry.STATUS_SCHEDULED || entry.onServer) continue
            if (entry.notBeforeMs <= now) {
                try {
                    val txId = kaPostsService.get().submitScheduledLocally(entry.transaction)
                    Log.i(TAG, "Scheduled post submitted from the phone: ${txId.take(12)}")
                    update(entry.copy(status = KaPostScheduledEntry.STATUS_SUBMITTED, submittedAtMs = System.currentTimeMillis()))
                } catch (e: Exception) {
                    Log.w(TAG, "Scheduled post failed to submit", e)
                    update(entry.copy(status = KaPostScheduledEntry.STATUS_FAILED, error = e.message))
                }
            } else {
                // Still in the future and still only here: offer it to the indexer again.
                try {
                    kaPostsService.get().scheduleOnServer(entry.txId, entry.transaction, entry.notBeforeMs)
                    update(entry.copy(onServer = true))
                } catch (_: Exception) {
                    // Still unreachable; this phone remains the fallback.
                }
            }
        }
    }

    /** Takes the indexer's view of what it holds: submitted and failed outcomes land here. */
    suspend fun refreshFromServer() {
        reloadIfNeeded()
        val remote = try { kaPostsService.get().fetchScheduledPosts() } catch (_: Exception) { return }
        if (remote.isEmpty()) return
        val byId = remote.associateBy { it.txId }
        var changed = false
        val updated = _entries.value.map { entry ->
            if (!entry.onServer) return@map entry
            val server = byId[entry.txId] ?: return@map entry
            val status = server.status ?: entry.status
            if (status == entry.status && server.error == entry.error) return@map entry
            changed = true
            entry.copy(status = status, error = server.error, submittedAtMs = server.submittedAt ?: entry.submittedAtMs)
        }
        if (changed) persist(updated)
    }

    companion object {
        private const val TAG = "KaPostsScheduled"

        /**
         * The coins no other transaction may spend right now - every scheduled post's inputs.
         * Read by [KaspaWalletEngine] before it selects inputs; written only here.
         */
        @Volatile
        var reservedOutpoints: Set<String> = emptySet()
            private set

        /** Drops every coin a scheduled post is waiting to spend. */
        fun filterReserved(utxos: List<UtxoEntry>): List<UtxoEntry> {
            val reserved = reservedOutpoints
            if (reserved.isEmpty()) return utxos
            return utxos.filterNot { "${it.outpoint.transactionId}:${it.outpoint.index}" in reserved }
        }
    }
}
