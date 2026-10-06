package com.kachat.app.services

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.kachat.app.models.ContactEntity
import com.kachat.app.repository.ChatRepository
import com.kachat.app.services.database.KaChatDatabase
import com.kachat.app.util.KasiaCipher
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.SavedHandshakeNote
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Contact notes for chats that never had a handshake (iOS fe45704, MESSAGING.md "Saved-handshake
 * notes").
 *
 * A chat with no handshake uses deterministic aliases, derived from both keys, and an alias
 * cannot be turned back into an address - so nothing on chain names the partner, and importing
 * the seed on a new device never finds the chat. The first time you message such a contact, a
 * contact-only `saved_handshake` note is written: the partner's address, encrypted to yourself,
 * in a fee-only transaction to yourself. Reading the notes back ([syncNotes]) re-creates the
 * contact, and the ordinary sync then fetches its deterministic messages.
 *
 * A note is written once per contact, and only after one complete read-back of the notes
 * already on chain, so it never duplicates one - the known set and the read-back flag are kept
 * per wallet. Notes that cannot be sent yet (no spare coins) stay queued for the next sync.
 *
 * Every handshake we send also writes a **handshake** note (iOS buildHandshakeSelfStashTx, audit
 * XP-003): our alias, their alias when known and the partner's address, so a reinstall finds the
 * chat and the alias it was on. Those are written straight after the handshake, without the
 * read-back gate (each handshake gets its own, as on iOS), and queued like the rest when no coin
 * is spare yet.
 */
@Singleton
class ContactNoteService @Inject constructor(
    @ApplicationContext context: Context,
    private val database: KaChatDatabase,
    private val walletManager: WalletManager,
    private val walletService: WalletService,
    private val chatRepository: ChatRepository,
    private val networkService: NetworkService,
    private val peerAliasStore: PeerAliasStore,
) {
    private val prefs = context.getSharedPreferences("kachat_contact_notes", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val gson = Gson()

    private fun knownKey(wallet: String) = "known_$wallet"
    private fun completeKey(wallet: String) = "complete_$wallet"
    private fun pendingKey(wallet: String) = "pending_$wallet"
    private fun highWaterKey(wallet: String) = "high_water_$wallet"
    private fun fullScanKey(wallet: String) = "full_scan_at_$wallet"
    private fun pendingHandshakeKey(wallet: String) = "pending_handshake_notes_$wallet"

    /** A handshake note waiting for a spare coin. */
    private data class PendingHandshakeNote(
        val partnerAddress: String,
        val alias: String,
        val theirAlias: String?,
        val isResponse: Boolean,
    )

    private fun pendingHandshakeNotes(wallet: String): List<PendingHandshakeNote> =
        prefs.getString(pendingHandshakeKey(wallet), null)?.let { raw ->
            runCatching {
                gson.fromJson<List<PendingHandshakeNote>>(raw, object : com.google.gson.reflect.TypeToken<List<PendingHandshakeNote>>() {}.type)
            }.getOrNull()
        }.orEmpty()

    private fun savePendingHandshakeNotes(wallet: String, notes: List<PendingHandshakeNote>) {
        prefs.edit().putString(pendingHandshakeKey(wallet), gson.toJson(notes)).apply()
    }

    private fun known(wallet: String): Set<String> = prefs.getStringSet(knownKey(wallet), emptySet()).orEmpty()
    private fun pending(wallet: String): Set<String> = prefs.getStringSet(pendingKey(wallet), emptySet()).orEmpty()
    private fun indexComplete(wallet: String) = prefs.getBoolean(completeKey(wallet), false)

    /**
     * Reads your `saved_handshake` notes back, re-creates the chats they name, and - after a
     * complete read-back - writes the notes still missing. Incremental from a high-water mark
     * with a 10-minute reorg rewind; from zero once a day, until the first complete read-back,
     * and after any failure (same schedule as iOS fetchSavedHandshakes).
     */
    suspend fun syncNotes() = mutex.withLock {
        val wallet = runCatching { walletManager.getAddress() }.getOrNull() ?: return@withLock
        val api = networkService.indexerApi.value ?: return@withLock
        val privateKey = runCatching { walletManager.getPrivateKeyBytes() }.getOrNull() ?: return@withLock

        val highWater = prefs.getLong(highWaterKey(wallet), 0L)
        val lastFullScanAt = prefs.getLong(fullScanKey(wallet), 0L)
        val now = System.currentTimeMillis()
        val fullScanDue = !indexComplete(wallet) || highWater == 0L || lastFullScanAt <= 0L ||
            now - lastFullScanAt >= FULL_SCAN_INTERVAL_MS
        val start = if (fullScanDue) 0L else (highWater - REORG_REWIND_MS).coerceAtLeast(0L)

        val notes = mutableListOf<SelfStashIndexerResponse>()
        // Whether the read reached the end of the stash - a short last page - rather than
        // stopping at the page cap or on a page that made no progress (iOS a1b89bd).
        var reachedEnd = false
        try {
            var cursor = start
            for (page in 0 until MAX_PAGES) {
                val rows = api.getSelfStashByOwner(wallet, SCOPE_HEX, PAGE_SIZE, cursor)
                notes += rows
                if (rows.size < PAGE_SIZE) { reachedEnd = true; break }
                val next = rows.mapNotNull { it.blockTime }.maxOrNull() ?: break
                if (next <= cursor) break
                cursor = next
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Dropping the mark makes the next attempt a from-zero scan, so a partial read can
            // never leave a gap behind the cursor.
            prefs.edit().remove(highWaterKey(wallet)).apply()
            Log.w(TAG, "Reading saved-handshake notes failed", e)
            return@withLock
        }

        notes.mapNotNull { it.blockTime }.maxOrNull()?.takeIf { it > highWater }?.let {
            prefs.edit().putLong(highWaterKey(wallet), it).apply()
        }
        if (fullScanDue) prefs.edit().putLong(fullScanKey(wallet), now).apply()

        val noted = mutableSetOf<String>()
        for (note in notes) {
            val data = note.stashedData ?: continue
            val content = decrypt(data, privateKey) ?: continue
            val contact = content.contactAddress
            if (contact.isEmpty()) continue
            noted += contact
            // Every note re-creates its chat (a contact note is the only trace of a deterministic
            // chat). A handshake note also hands back the alias they write under, so their
            // messages are read again; our own side always restores on the deterministic alias -
            // a random alias is never taken up again for a chat this device does not hold
            // (XP-003), and the sync derives that alias from the address.
            val handshakeNote = !content.alias.isNullOrEmpty()
            if (handshakeNote) content.theirAlias?.let { peerAliasStore.add(wallet, contact, it) }
            restoreContact(wallet, contact, fromHandshake = handshakeNote)
        }
        // A wallet switch during the read must not credit this wallet's notes to the next one.
        if (!isActive(wallet)) return@withLock
        // "Complete" means from block time 0 AND to the end: a read stopped at the page cap
        // treated as complete would write a second note for every contact past the cut-off.
        val completeScan = fullScanDue && reachedEnd
        recordNoted(wallet, noted, completeScan = completeScan)
        if (completeScan) backfill(wallet)
        sendPending(wallet)
    }

    /** [syncNotes] off the caller's path - a sync or a pull to refresh never waits on it. */
    fun syncNotesInBackground() {
        scope.launch {
            try { syncNotes() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                Log.w(TAG, "Contact note sync failed", e)
            }
        }
    }

    /** Your message went out in [contactAddress]'s chat: make sure a fresh import of this seed
     *  can find the chat again. A cheap no-op for every chat already noted. */
    fun onMessageDelivered(contactAddress: String) {
        scope.launch {
            mutex.withLock {
                val wallet = runCatching { walletManager.getAddress() }.getOrNull() ?: return@withLock
                if (queueIfNeeded(wallet, contactAddress)) sendPending(wallet)
            }
        }
    }

    /** Queues a note for [contactAddress] when its chat has no handshake and no note yet.
     *  Never before a complete read-back: until then a missing note cannot be told apart from
     *  one that is on chain but not read yet. */
    private suspend fun queueIfNeeded(wallet: String, contactAddress: String): Boolean {
        if (contactAddress.isEmpty() || contactAddress.equals(wallet, ignoreCase = true)) return false
        if (!indexComplete(wallet)) return false
        if (contactAddress in known(wallet) || contactAddress in pending(wallet)) return false
        if (!needsNote(wallet, contactAddress)) return false
        // Marked noted when the note is submitted (sendPending), not here: the pending entry is
        // what keeps it from being queued twice meanwhile (iOS a1b89bd).
        prefs.edit().putStringSet(pendingKey(wallet), pending(wallet) + contactAddress).apply()
        Log.i(TAG, "Queued contact note for …${contactAddress.takeLast(10)}")
        return true
    }

    /** Only chats with no handshake need one: a handshake chat is found again by its handshake. */
    private suspend fun needsNote(wallet: String, contactAddress: String): Boolean {
        val contact = database.contactDao().getContact(contactAddress, wallet) ?: return false
        if (contact.handshakeComplete || !contact.theirAlias.isNullOrEmpty()) return false
        return !database.messageDao().hasHandshake(contactAddress, wallet)
    }

    /** Old chats, from before notes were written: every chat without a handshake that you have
     *  sent a message in and that has no note gets one. Only ever writes what is missing. */
    private suspend fun backfill(wallet: String) {
        if (!indexComplete(wallet)) return
        for (contact in database.messageDao().contactsWithDeliveredSends(wallet)) {
            queueIfNeeded(wallet, contact)
        }
    }

    private fun recordNoted(wallet: String, addresses: Set<String>, completeScan: Boolean) {
        val merged = known(wallet) + addresses
        val editor = prefs.edit().putStringSet(knownKey(wallet), merged)
        if (completeScan) editor.putBoolean(completeKey(wallet), true)
        editor.apply()
    }

    /**
     * A handshake to [partnerAddress] went out on [ourAlias]: write its `saved_handshake` note
     * (iOS sendOrQueueSelfStash after sendHandshake) - straight away, or queued until a coin is
     * spare. Never blocks or fails the handshake itself.
     */
    fun onHandshakeSent(partnerAddress: String, ourAlias: String, theirAlias: String?, isResponse: Boolean) {
        scope.launch {
            mutex.withLock {
                val wallet = runCatching { walletManager.getAddress() }.getOrNull() ?: return@withLock
                if (partnerAddress.isEmpty() || partnerAddress.equals(wallet, ignoreCase = true)) return@withLock
                val job = PendingHandshakeNote(partnerAddress, ourAlias, theirAlias?.takeIf { it.isNotEmpty() }, isResponse)
                val queued = pendingHandshakeNotes(wallet)
                if (queued.none { it.partnerAddress == job.partnerAddress && it.alias == job.alias }) {
                    savePendingHandshakeNotes(wallet, queued + job)
                }
                sendPending(wallet)
            }
        }
    }

    /** Sends queued notes, one small transaction each, until one fails (usually no spare coin
     *  yet); the rest wait for the next sync. Handshake notes go first. */
    private suspend fun sendPending(wallet: String) {
        for (job in pendingHandshakeNotes(wallet)) {
            if (!isActive(wallet)) return
            try {
                val json = SavedHandshakeNote.handshakeJson(job.alias, job.theirAlias, job.partnerAddress, job.isResponse, System.currentTimeMillis())
                val payload = SavedHandshakeNote.payload(json, KaspaAddress.decode(wallet).second)
                val txId = walletService.sendKaspa(toAddress = wallet, amountSompi = 0, payloadBytes = payload)
                savePendingHandshakeNotes(wallet, pendingHandshakeNotes(wallet) - job)
                prefs.edit().putStringSet(knownKey(wallet), known(wallet) + job.partnerAddress).apply()
                Log.i(TAG, "Handshake note written for …${job.partnerAddress.takeLast(10)}: ${txId.take(16)}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Handshake note for …${job.partnerAddress.takeLast(10)} not sent yet", e)
                return
            }
        }
        for (contact in pending(wallet)) {
            // sendKaspa spends from the active account: never send this wallet's note from another.
            if (!isActive(wallet)) return
            try {
                val payload = buildNotePayload(wallet, contact)
                val txId = walletService.sendKaspa(toAddress = wallet, amountSompi = 0, payloadBytes = payload)
                prefs.edit()
                    .putStringSet(pendingKey(wallet), pending(wallet) - contact)
                    .putStringSet(knownKey(wallet), known(wallet) + contact)
                    .apply()
                Log.i(TAG, "Contact note written for …${contact.takeLast(10)}: ${txId.take(16)}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Contact note for …${contact.takeLast(10)} not sent yet", e)
                return
            }
        }
    }

    private fun isActive(wallet: String) = runCatching { walletManager.getAddress() }.getOrNull() == wallet

    /** Re-creates a chat a note names, unless it is here already or was deleted. A handshake
     *  note's chat had a handshake, so it comes back active with it (as the outgoing-handshake
     *  restore does), with no alias of ours: it writes on the deterministic alias. */
    private suspend fun restoreContact(wallet: String, contactAddress: String, fromHandshake: Boolean = false) {
        if (database.contactDao().getContact(contactAddress, wallet) != null) return
        if (chatRepository.hasDeletionTombstone(contactAddress)) return
        chatRepository.addContact(
            ContactEntity(
                id = contactAddress, walletAddress = wallet, alias = null, knsName = null, publicKeyHex = null,
                handshakeComplete = fromHandshake,
            )
        )
        Log.i(TAG, "Restored chat …${contactAddress.takeLast(10)} from its ${if (fromHandshake) "handshake" else "contact"} note")
    }

    /** `kchat:1:self_stash:saved_handshake:` + the contact note encrypted to our own key. */
    private fun buildNotePayload(wallet: String, partnerAddress: String): ByteArray =
        SavedHandshakeNote.payload(
            SavedHandshakeNote.contactJson(partnerAddress, System.currentTimeMillis()),
            KaspaAddress.decode(wallet).second,
        )

    private fun decrypt(stashedHex: String, privateKey: ByteArray): SavedHandshakeNote.Content? = try {
        val bytes = hexToBytes(stashedHex) ?: throw IllegalArgumentException("not hex")
        val message = KasiaCipher.EncryptedMessage.fromBytes(bytes) ?: throw IllegalArgumentException("short")
        SavedHandshakeNote.parse(KasiaCipher.decrypt(message, privateKey))
    } catch (e: Exception) {
        null
    }

    private fun hexToBytes(hex: String): ByteArray? {
        val clean = hex.trim()
        if (clean.length % 2 != 0) return null
        return try {
            ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            null
        }
    }

    private companion object {
        const val TAG = "ContactNoteService"
        const val SCOPE = SavedHandshakeNote.SCOPE
        val SCOPE_HEX = SCOPE.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        const val PAGE_SIZE = 50
        const val MAX_PAGES = 200
        const val FULL_SCAN_INTERVAL_MS = 24L * 60 * 60 * 1000
        const val REORG_REWIND_MS = 10L * 60 * 1000
    }
}
