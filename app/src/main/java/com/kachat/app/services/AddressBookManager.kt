package com.kachat.app.services

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kachat.app.services.database.KaChatDatabase
import com.kachat.app.util.KaspaAddress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One saved Kaspa address in the Address Book (Kaspa Hub > Address Book). Kept per wallet, on
 * this device and in the chat backup - never in the phone's Contacts (iOS `AddressBookEntry`).
 * [createdAt] / [updatedAt] are epoch milliseconds here; the backup writes them as ISO 8601.
 */
data class AddressBookEntry(
    val id: String = UUID.randomUUID().toString().uppercase(),
    val address: String,
    val name: String,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** A deleted Address Book entry, kept so a backup merge from another device never brings it back. */
data class AddressBookTombstone(val address: String, val deletedAt: Long)

/**
 * The Address Book (Kaspa Hub > Address Book): saved Kaspa addresses with a name and a note
 * (iOS `AddressBookManager`, 00767a4).
 *
 * It replaces syncing with the phone's Contacts (removed 2026-10-08). That linked Kaspa addresses
 * to phone numbers in people's address books, which sync to Google/iCloud and which any app with
 * Contacts access can read. The Address Book lives only in KaChat:
 * - one per wallet, like chats and contacts, so switching wallets never shows who another wallet
 *   knows;
 * - on this device (SharedPreferences, the same per-wallet keys iOS keeps in UserDefaults) and in
 *   the wallet's chat backup (`addressBook` + `addressBookDeleted` in the archive,
 *   NEXTCLOUD_SYNC.md §5), so a restore or another device brings it back.
 *
 * A saved name is also how KaChat shows that address when you haven't named the chat contact
 * yourself ([com.kachat.app.models.addressDisplayName]).
 */
@Singleton
class AddressBookManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val walletManager: WalletManager,
    private val database: KaChatDatabase,
    // Lazy: NextcloudSyncService reaches this class back through the export service.
    private val nextcloudSyncService: dagger.Lazy<NextcloudSyncService>,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Book(
        val wallet: String?,
        /** Sorted by name. */
        val entries: List<AddressBookEntry>,
        /** Normalized address -> when it was deleted. */
        val deleted: Map<String, Long>,
    ) {
        val byAddress: Map<String, AddressBookEntry> = entries.associateBy { normalize(it.address) }
    }

    /** Snapshot state, so a composable that reads a name through [nameFor] re-renders on edits. */
    private var book by mutableStateOf(Book(null, emptyList(), emptyMap()))

    private val _entries = MutableStateFlow<List<AddressBookEntry>>(emptyList())
    /** This wallet's entries, sorted by name. */
    val entries: StateFlow<List<AddressBookEntry>> = _entries.asStateFlow()

    init {
        instance = this
        scope.launch {
            walletManager.activeAddressFlow.collect { setActiveWalletAddress(it) }
        }
    }

    // MARK: - Wallet

    /** Loads the book of [walletAddress] (null: no wallet, an empty book). */
    @Synchronized
    private fun loadBook(walletAddress: String?): String? {
        val wallet = walletAddress?.let(::normalize)?.takeIf { it.isNotEmpty() }
        if (wallet == null) {
            publish(Book(null, emptyList(), emptyMap()))
            return null
        }
        val stored = readEntries(wallet)
        val tombstones = readTombstones(wallet)
        val deleted = HashMap<String, Long>()
        for (t in tombstones) {
            val a = normalize(t.address)
            deleted[a] = maxOf(deleted[a] ?: Long.MIN_VALUE, t.deletedAt)
        }
        publish(Book(wallet, sorted(stored), deleted))
        return wallet
    }

    private suspend fun setActiveWalletAddress(walletAddress: String?) {
        val wallet = loadBook(walletAddress) ?: return
        migrateLinkedNames(wallet, walletAddress!!)
    }

    /**
     * Once per wallet: every chat contact that was linked to a phone contact becomes an Address
     * Book entry with that phone contact's name and the Kaspa address only (no phone number, no
     * link). The old link is read from the contact row's legacy columns, never written back.
     */
    private suspend fun migrateLinkedNames(wallet: String, rawWallet: String) {
        val flag = MIGRATED_KEY_PREFIX + wallet
        if (prefs.getBoolean(flag, false)) return
        val contacts = runCatching { database.contactDao().getContacts(rawWallet).first() }.getOrElse {
            Log.w(TAG, "Address Book migration could not read contacts", it)
            return
        }
        synchronized(this) {
            val current = book
            if (current.wallet != wallet) return
            val added = ArrayList<AddressBookEntry>()
            val seen = HashSet(current.byAddress.keys)
            for (contact in contacts) {
                val name = contact.systemContactName?.trim()
                if (contact.systemContactId == null || name.isNullOrEmpty()) continue
                val address = normalize(contact.id)
                if (!seen.add(address)) continue
                added.add(AddressBookEntry(address = address, name = name))
            }
            prefs.edit().putBoolean(flag, true).apply()
            if (added.isNotEmpty()) {
                publish(Book(wallet, sorted(current.entries + added), current.deleted))
                persist()
                Log.i(TAG, "moved linked phone-contact names into the Address Book")
            }
        }
    }

    // MARK: - Reading

    fun entry(address: String?): AddressBookEntry? {
        if (address.isNullOrBlank()) return null
        return book.byAddress[normalize(address)]
    }

    fun search(query: String): List<AddressBookEntry> {
        val q = query.trim().lowercase()
        val all = book.entries
        if (q.isEmpty()) return all
        return all.filter {
            it.name.lowercase().contains(q) || it.address.lowercase().contains(q) || it.note.lowercase().contains(q)
        }
    }

    // MARK: - Writing

    /** Why a save was refused; [messageKey] is the iOS English string the UI localizes. */
    class SaveException(val messageKey: SaveError) : Exception(messageKey.name)

    enum class SaveError { NO_WALLET, EMPTY_NAME, INVALID_ADDRESS }

    /** Adds [address], or updates its entry when it is already saved. */
    @Synchronized
    @Throws(SaveException::class)
    fun save(address: String, name: String, note: String = ""): AddressBookEntry {
        val current = book
        val wallet = current.wallet ?: throw SaveException(SaveError.NO_WALLET)
        val normalized = normalize(address)
        val cleanName = name.trim()
        if (cleanName.isEmpty()) throw SaveException(SaveError.EMPTY_NAME)
        if (!KaspaAddress.isValid(normalized)) throw SaveException(SaveError.INVALID_ADDRESS)
        val cleanNote = note.trim()
        val now = System.currentTimeMillis()
        val existing = current.byAddress[normalized]
        val saved = existing?.copy(name = cleanName, note = cleanNote, updatedAt = now)
            ?: AddressBookEntry(address = normalized, name = cleanName, note = cleanNote, createdAt = now, updatedAt = now)
        val list = current.entries.filterNot { normalize(it.address) == normalized } + saved
        publish(Book(wallet, sorted(list), current.deleted - normalized))
        persist()
        didChange()
        return saved
    }

    @Synchronized
    fun remove(address: String) {
        val current = book
        val wallet = current.wallet ?: return
        val normalized = normalize(address)
        if (current.byAddress[normalized] == null) return
        publish(
            Book(
                wallet,
                current.entries.filterNot { normalize(it.address) == normalized },
                current.deleted + (normalized to System.currentTimeMillis()),
            )
        )
        persist()
        didChange()
    }

    // MARK: - Backup

    /** What the chat backup carries for this wallet. */
    fun archiveEntries(): List<ArchiveAddressBookEntry> = book.entries.map { it.toArchive() }

    fun archiveTombstones(): List<ArchiveAddressBookTombstone> =
        book.deleted.map { ArchiveAddressBookTombstone(it.key, isoSeconds(it.value)) }.sortedBy { it.address }

    /**
     * A restore: per address the newest event wins - an entry edited after it was deleted
     * elsewhere comes back, one deleted after its last edit stays deleted.
     */
    @Synchronized
    fun importFromArchive(entries: List<ArchiveAddressBookEntry>, tombstones: List<ArchiveAddressBookTombstone>) {
        val current = book
        val wallet = current.wallet ?: return
        if (entries.isEmpty() && tombstones.isEmpty()) return
        val incoming = entries.mapNotNull { it.toEntry() }
        val incomingTombs = tombstones.mapNotNull { t -> parseDate(t.deletedAt)?.let { AddressBookTombstone(t.address, it) } }
        val localTombs = current.deleted.map { AddressBookTombstone(it.key, it.value) }
        val merged = merge(listOf(current.entries, incoming), listOf(localTombs, incomingTombs))
        publish(Book(wallet, merged.first, merged.second.associate { normalize(it.address) to it.deletedAt }))
        persist()
    }

    // MARK: - Helpers

    private fun publish(next: Book) {
        book = next
        _entries.value = next.entries
    }

    /** A user edit: the backup owes an upload. */
    private fun didChange() {
        runCatching { nextcloudSyncService.get().noteMessageActivity() }
    }

    private fun persist() {
        val current = book
        val wallet = current.wallet ?: return
        prefs.edit()
            .putString(ENTRIES_KEY_PREFIX + wallet, gson.toJson(current.entries))
            .putString(
                DELETED_KEY_PREFIX + wallet,
                gson.toJson(current.deleted.map { AddressBookTombstone(it.key, it.value) }.sortedBy { it.address })
            )
            .apply()
    }

    private fun readEntries(wallet: String): List<AddressBookEntry> =
        prefs.getString(ENTRIES_KEY_PREFIX + wallet, null)?.let { raw ->
            runCatching {
                gson.fromJson<List<AddressBookEntry>>(raw, object : TypeToken<List<AddressBookEntry>>() {}.type)
            }.getOrNull()
        }.orEmpty().filter { !it.address.isNullOrBlank() && !it.name.isNullOrBlank() }

    private fun readTombstones(wallet: String): List<AddressBookTombstone> =
        prefs.getString(DELETED_KEY_PREFIX + wallet, null)?.let { raw ->
            runCatching {
                gson.fromJson<List<AddressBookTombstone>>(raw, object : TypeToken<List<AddressBookTombstone>>() {}.type)
            }.getOrNull()
        }.orEmpty().filter { !it.address.isNullOrBlank() }

    companion object {
        private const val TAG = "AddressBook"
        private const val PREFS = "kachat_address_book"
        private const val ENTRIES_KEY_PREFIX = "kachat_address_book_wallet_"
        private const val DELETED_KEY_PREFIX = "kachat_address_book_deleted_wallet_"
        private const val MIGRATED_KEY_PREFIX = "kachat_address_book_migrated_v1_wallet_"

        @Volatile private var instance: AddressBookManager? = null

        /** The app-wide manager once Hilt has built it (KaChatApplication does, at startup). */
        val shared: AddressBookManager? get() = instance

        /** The Address Book name of [address] in the active wallet's book, if it is saved. */
        fun nameFor(address: String?): String? = instance?.entry(address)?.name

        /** Kaspa addresses are lowercase; a pasted or scanned one may carry spaces or a `?amount=` query. */
        fun normalize(address: String): String {
            var a = address.trim().lowercase()
            val q = a.indexOf('?')
            if (q >= 0) a = a.substring(0, q)
            return a
        }

        private fun sorted(list: List<AddressBookEntry>): List<AddressBookEntry> {
            val collator = java.text.Collator.getInstance().apply { strength = java.text.Collator.SECONDARY }
            return list.sortedWith { a, b ->
                val order = collator.compare(a.name, b.name)
                if (order != 0) order else a.address.compareTo(b.address)
            }
        }

        /**
         * The merge both restore and the shared-backup upload use: per address the newest
         * `updatedAt` of either side's entries, unless a tombstone's `deletedAt` is at or after
         * it - then it's deleted and only the tombstone is kept (NEXTCLOUD_SYNC.md §5).
         */
        fun merge(
            sides: List<List<AddressBookEntry>>,
            tombSides: List<List<AddressBookTombstone>>,
        ): Pair<List<AddressBookEntry>, List<AddressBookTombstone>> {
            val latest = LinkedHashMap<String, AddressBookEntry>()
            for (entry in sides.flatten()) {
                val e = entry.copy(address = normalize(entry.address))
                val have = latest[e.address]
                if (have != null && have.updatedAt >= e.updatedAt) continue
                latest[e.address] = e
            }
            val deletedAt = HashMap<String, Long>()
            for (t in tombSides.flatten()) {
                val a = normalize(t.address)
                deletedAt[a] = maxOf(deletedAt[a] ?: Long.MIN_VALUE, t.deletedAt)
            }
            val kept = ArrayList<AddressBookEntry>()
            for ((address, e) in latest) {
                val d = deletedAt[address]
                if (d != null && d >= e.updatedAt) continue
                kept.add(e)
                deletedAt.remove(address)
            }
            val tombs = deletedAt.map { AddressBookTombstone(it.key, it.value) }.sortedBy { it.address }
            return sorted(kept) to tombs
        }

        // MARK: - Archive wire format (NEXTCLOUD_SYNC.md §5)

        /** ISO 8601 with whole seconds: iOS's `.iso8601` decoder refuses fractional seconds. */
        fun isoSeconds(epochMs: Long): String =
            DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMs).truncatedTo(ChronoUnit.SECONDS))

        /** ISO 8601 (with or without fractions), or a bare number - iOS's default Date encoding,
         *  seconds since 2001-01-01, which its decoder also falls back to. */
        fun parseDate(raw: String?): Long? {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return null
            runCatching { return Instant.parse(value).toEpochMilli() }
            runCatching { return java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            return value.toDoubleOrNull()?.let { ((it + APPLE_REFERENCE_EPOCH_S) * 1000).toLong() }
        }

        private const val APPLE_REFERENCE_EPOCH_S = 978_307_200.0

        fun AddressBookEntry.toArchive(): ArchiveAddressBookEntry = ArchiveAddressBookEntry(
            id = id,
            address = normalize(address),
            name = name,
            note = note,
            createdAt = isoSeconds(createdAt),
            updatedAt = isoSeconds(updatedAt),
        )

        fun ArchiveAddressBookEntry.toEntry(): AddressBookEntry? {
            val a = address?.let(::normalize)?.takeIf { it.isNotEmpty() } ?: return null
            val n = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val updated = parseDate(updatedAt) ?: return null
            return AddressBookEntry(
                id = id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().uppercase(),
                address = a,
                name = n,
                note = note.orEmpty(),
                createdAt = parseDate(createdAt) ?: updated,
                updatedAt = updated,
            )
        }
    }
}

/**
 * An Address Book entry as the chat backup carries it: `{id, address, name, note, createdAt,
 * updatedAt}`, dates in ISO 8601 (iOS `AddressBookEntry` in `ChatHistoryArchive.addressBook`).
 */
data class ArchiveAddressBookEntry(
    val id: String?,
    val address: String?,
    val name: String?,
    val note: String?,
    val createdAt: String?,
    val updatedAt: String?,
)

/** `{address, deletedAt}` in the archive's `addressBookDeleted`. */
data class ArchiveAddressBookTombstone(
    val address: String,
    val deletedAt: String,
)
