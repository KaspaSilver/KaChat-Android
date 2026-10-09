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
    /** The assigned photo as base64 JPEG - only on the way to or from the backup. On the device it
     *  is a file ([AddressBookManager.photo]), so this stays null everywhere else. */
    val photo: String? = null,
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
 *
 * An entry's picture is either a photo you assign to it, or else exactly the avatar that address
 * set on its own profile (iOS cda0d99). Assigned photos are your data, not cache: they live in the
 * app's files (not the cache directory, so the system never purges them) under
 * AddressBookPhotos/<wallet hash>/<address hash>.jpg, travel in the backup as `photo`, and
 * Settings > Storage shows the space they take and can remove them.
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

    /** Bumped whenever an assigned photo changes, so pictures re-read it. */
    var photoVersion by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    private val photoCache = android.util.LruCache<String, android.graphics.Bitmap>(200)

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
        photoCache.evictAll()
        photoVersion++
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

    /** [OTHER_NETWORK]: a valid address of the network the app isn't on; the UI shows
     *  [KaspaAddress.otherNetworkMessageRes] for it (iOS `SaveError.otherNetwork`, IOS-063). */
    enum class SaveError { NO_WALLET, EMPTY_NAME, INVALID_ADDRESS, OTHER_NETWORK }

    /** What a save does to the entry's assigned photo. */
    sealed class PhotoChange {
        object Unchanged : PhotoChange()
        /** JPEG bytes, already scaled down ([preparedPhoto]). */
        class Set(val jpeg: ByteArray) : PhotoChange()
        object Removed : PhotoChange()
    }

    /** Adds [address], or updates its entry when it is already saved. */
    @Synchronized
    @Throws(SaveException::class)
    fun save(address: String, name: String, note: String = "", photo: PhotoChange = PhotoChange.Unchanged): AddressBookEntry {
        val current = book
        val wallet = current.wallet ?: throw SaveException(SaveError.NO_WALLET)
        val normalized = normalize(address)
        val cleanName = name.trim()
        if (cleanName.isEmpty()) throw SaveException(SaveError.EMPTY_NAME)
        // The network the app runs on only: the other network's address is the same key on the
        // other chain, a chat with it is never read and is dropped on the next launch (iOS 218dc42, IOS-063).
        if (KaspaAddress.otherNetwork(normalized) != null) throw SaveException(SaveError.OTHER_NETWORK)
        if (!KaspaAddress.isValidOnActiveNetwork(normalized)) throw SaveException(SaveError.INVALID_ADDRESS)
        val cleanNote = note.trim()
        val now = System.currentTimeMillis()
        val existing = current.byAddress[normalized]
        val saved = existing?.copy(name = cleanName, note = cleanNote, updatedAt = now)
            ?: AddressBookEntry(address = normalized, name = cleanName, note = cleanNote, createdAt = now, updatedAt = now)
        val list = current.entries.filterNot { normalize(it.address) == normalized } + saved
        when (photo) {
            PhotoChange.Unchanged -> Unit
            is PhotoChange.Set -> writePhoto(photo.jpeg, normalized)
            PhotoChange.Removed -> deletePhoto(normalized)
        }
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
        deletePhoto(normalized)
        persist()
        didChange()
    }

    // MARK: - Assigned photos

    /** The photo you assigned to [address], if any. */
    fun photo(address: String?): android.graphics.Bitmap? {
        if (address.isNullOrBlank()) return null
        val file = photoFile(normalize(address)) ?: return null
        photoCache.get(file.path)?.let { return it }
        if (!file.exists()) return null
        val bitmap = runCatching { android.graphics.BitmapFactory.decodeFile(file.path) }.getOrNull() ?: return null
        photoCache.put(file.path, bitmap)
        return bitmap
    }

    fun hasPhoto(address: String?): Boolean {
        if (address.isNullOrBlank()) return false
        return photoFile(normalize(address))?.exists() == true
    }

    /** Space the assigned photos of every wallet on this device take (Settings > Storage). */
    fun photosBytesOnDevice(): Long =
        photosRoot().walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * Settings > Storage > Remove: deletes the assigned photos of every wallet on this device.
     * Each affected entry counts as edited, so the removal also reaches the backup instead of the
     * photo coming back from it.
     */
    @Synchronized
    fun removeAllPhotos() {
        val now = System.currentTimeMillis()
        val current = book
        val wallets = prefs.all.keys.filter { it.startsWith(ENTRIES_KEY_PREFIX) }.map { it.removePrefix(ENTRIES_KEY_PREFIX) }
        for (wallet in wallets) {
            if (wallet == current.wallet) {
                val touched = current.entries.map { if (hasPhoto(it.address)) it.copy(updatedAt = now) else it }
                publish(Book(wallet, touched, current.deleted))
                persist()
                continue
            }
            val dir = photosDirectory(wallet)
            val stored = readEntries(wallet)
            if (stored.isEmpty()) continue
            val touched = stored.map {
                if (java.io.File(dir, photoFileName(it.address)).exists()) it.copy(updatedAt = now) else it
            }
            prefs.edit().putString(ENTRIES_KEY_PREFIX + wallet, gson.toJson(touched)).apply()
        }
        photosRoot().deleteRecursively()
        photoCache.evictAll()
        photoVersion++
        if (current.wallet != null) didChange()
    }

    private fun photosRoot(): java.io.File = java.io.File(context.filesDir, "AddressBookPhotos")

    /** One folder per wallet, named by a hash of its address (no address in a file path). */
    private fun photosDirectory(wallet: String): java.io.File = java.io.File(photosRoot(), hashName(wallet))

    private fun photoFile(normalizedAddress: String): java.io.File? {
        val wallet = book.wallet ?: return null
        return java.io.File(photosDirectory(wallet), photoFileName(normalizedAddress))
    }

    private fun writePhoto(jpeg: ByteArray, normalizedAddress: String) {
        val file = photoFile(normalizedAddress) ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = java.io.File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(jpeg)
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }.onFailure { Log.w(TAG, "Could not save an Address Book photo", it) }
        photoCache.remove(file.path)
        photoVersion++
    }

    private fun deletePhoto(normalizedAddress: String) {
        val file = photoFile(normalizedAddress) ?: return
        if (!file.exists()) return
        file.delete()
        photoCache.remove(file.path)
        photoVersion++
    }

    // MARK: - Export / import (Address Book > import-export sheet, iOS 87b2a0b)

    /** Why an import was refused; the UI localizes it (iOS `AddressBookManager.ImportError`).
     *  [OTHER_NETWORK]: every address in the file is the other network's (iOS 218dc42, IOS-063). */
    enum class ImportError { NOT_AN_ADDRESS_BOOK, EMPTY, OTHER_NETWORK }

    /** What an import did: entries added, entries updated, and other-network entries skipped. */
    data class ImportResult(val added: Int, val updated: Int, val skipped: Int)

    class ImportException(val reason: ImportError) : Exception(reason.name)

    /**
     * This wallet's Address Book as an export file: plain JSON, readable by any KaChat (iOS,
     * Android, Desktop) and any wallet - `{type: "kachat-address-book", version: 1, exportedAt,
     * walletAddress, entries}`, every entry with its assigned photo (base64 JPEG) attached. Keys
     * sorted and dates in ISO 8601 whole seconds, as iOS's encoder writes it (NEXTCLOUD_SYNC.md §1).
     */
    fun exportData(): ByteArray {
        val entries = com.google.gson.JsonArray()
        for (e in archiveEntries()) {
            entries.add(com.google.gson.JsonObject().apply {
                addProperty("address", e.address)
                addProperty("createdAt", e.createdAt)
                addProperty("id", e.id)
                addProperty("name", e.name)
                addProperty("note", e.note.orEmpty())
                e.photo?.let { addProperty("photo", it) }
                addProperty("updatedAt", e.updatedAt)
            })
        }
        val file = com.google.gson.JsonObject().apply {
            add("entries", entries)
            addProperty("exportedAt", isoSeconds(System.currentTimeMillis()))
            addProperty("type", EXPORT_KIND)
            addProperty("version", 1)
            book.wallet?.let { addProperty("walletAddress", it) }
        }
        return com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
            .toJson(file).toByteArray(Charsets.UTF_8)
    }

    /**
     * Imports an export file into this wallet's book: an address not saved here is added (even
     * one deleted since - importing is asking for it back); one already saved takes the file's
     * version only when that is newer. Photos come with their entry. Addresses of the other
     * network are skipped (iOS 218dc42, IOS-063). Returns (added, updated, skipped).
     */
    @Synchronized
    @Throws(SaveException::class, ImportException::class)
    fun importExport(data: ByteArray): ImportResult {
        val current = book
        val wallet = current.wallet ?: throw SaveException(SaveError.NO_WALLET)
        val root = runCatching {
            com.google.gson.JsonParser.parseString(String(data, Charsets.UTF_8)).asJsonObject
        }.getOrNull() ?: throw ImportException(ImportError.NOT_AN_ADDRESS_BOOK)
        val type = runCatching { root.get("type")?.asString }.getOrNull()
        val rawEntries = runCatching { root.getAsJsonArray("entries") }.getOrNull()
        if (type != EXPORT_KIND || rawEntries == null) throw ImportException(ImportError.NOT_AN_ADDRESS_BOOK)
        // Like iOS's Codable decode, one entry that can't be read makes the whole file "not an
        // Address Book export"; readable entries with no name or a bad address are then skipped.
        val decoded = rawEntries.map { element ->
            runCatching { gson.fromJson(element, ArchiveAddressBookEntry::class.java)?.toEntry() }.getOrNull()
                ?: throw ImportException(ImportError.NOT_AN_ADDRESS_BOOK)
        }
        val named = decoded.filter { it.name.isNotBlank() && KaspaAddress.isValid(normalize(it.address)) }
        val valid = named.filter { KaspaAddress.isValidOnActiveNetwork(normalize(it.address)) }
        val skipped = named.size - valid.size
        if (named.isEmpty()) throw ImportException(ImportError.EMPTY)
        if (valid.isEmpty()) throw ImportException(ImportError.OTHER_NETWORK)

        val list = current.entries.toMutableList()
        val index = HashMap<String, Int>()
        list.forEachIndexed { i, e -> index[normalize(e.address)] = i }
        val deleted = current.deleted.toMutableMap()
        var added = 0
        var updated = 0
        for (raw in valid) {
            val address = normalize(raw.address)
            val photo = raw.photo?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }.getOrNull() }
            var incoming = raw.copy(address = address, photo = null)
            val i = index[address]
            if (i != null) {
                val have = list[i]
                if (incoming.updatedAt <= have.updatedAt) continue
                list[i] = have.copy(name = incoming.name, note = incoming.note, updatedAt = incoming.updatedAt)
                if (photo != null) writePhoto(photo, address) else deletePhoto(address)
                updated++
            } else {
                if (list.any { it.id == incoming.id }) incoming = incoming.copy(id = UUID.randomUUID().toString().uppercase())
                list.add(incoming)
                index[address] = list.size - 1
                if (photo != null) writePhoto(photo, address)
                added++
            }
            deleted.remove(address)
        }
        publish(Book(wallet, sorted(list), deleted))
        persist()
        if (added + updated > 0) didChange()
        return ImportResult(added, updated, skipped)
    }

    // MARK: - Backup

    /** What the chat backup carries for this wallet: each entry with its assigned photo (base64
     *  JPEG) attached. */
    fun archiveEntries(): List<ArchiveAddressBookEntry> = book.entries.map { entry ->
        val photo = photoFile(normalize(entry.address))?.takeIf { it.exists() }
            ?.let { runCatching { android.util.Base64.encodeToString(it.readBytes(), android.util.Base64.NO_WRAP) }.getOrNull() }
        entry.copy(photo = photo).toArchive()
    }

    fun archiveTombstones(): List<ArchiveAddressBookTombstone> =
        book.deleted.map { ArchiveAddressBookTombstone(it.key, isoSeconds(it.value)) }.sortedBy { it.address }

    /**
     * A restore: per address the newest event wins - an entry edited after it was deleted
     * elsewhere comes back, one deleted after its last edit stays deleted.
     *
     * [walletAddress] is the wallet the archive was matched against (the active one). The book
     * of a newly active wallet is loaded on this manager's own coroutine, so a restore that runs
     * first - the automatic one at wallet activation - loads it here rather than filling the
     * previous wallet's book, or none (iOS loads it synchronously on the wallet switch).
     */
    @Synchronized
    fun importFromArchive(
        walletAddress: String,
        entries: List<ArchiveAddressBookEntry>,
        tombstones: List<ArchiveAddressBookTombstone>,
    ) {
        if (entries.isEmpty() && tombstones.isEmpty()) return
        val target = normalize(walletAddress)
        if (target.isEmpty()) return
        if (book.wallet != target) loadBook(walletAddress)
        val current = book
        val wallet = current.wallet ?: return
        val incoming = entries.mapNotNull { it.toEntry() }
        val incomingTombs = tombstones.mapNotNull { t -> parseDate(t.deletedAt)?.let { AddressBookTombstone(t.address, it) } }
        val localTombs = current.deleted.map { AddressBookTombstone(it.key, it.value) }
        val merged = merge(listOf(current.entries, incoming), listOf(localTombs, incomingTombs))
        // The winning entry decides the photo: one that came with a photo writes it; an incoming
        // winner without one removes ours (it was removed where that edit was made).
        val before = current.byAddress
        val kept = merged.first.map { e ->
            val address = normalize(e.address)
            val data = e.photo?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }.getOrNull() }
            val local = before[address]
            when {
                data != null -> writePhoto(data, address)
                local != null && local.updatedAt < e.updatedAt -> deletePhoto(address)
                local == null -> deletePhoto(address)
            }
            e.copy(photo = null)
        }
        for (t in merged.second) deletePhoto(normalize(t.address))
        publish(Book(wallet, kept, merged.second.associate { normalize(it.address) to it.deletedAt }))
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

        /** The export file's `type` (iOS `AddressBookManager.ExportFile.kind`). */
        const val EXPORT_KIND = "kachat-address-book"

        /** The export's file name, with the time so several exports don't overwrite each other:
         *  "KaChat Address Book 2026-10-08T18-37-50Z.json", exactly as iOS names it. */
        fun exportFileName(epochMs: Long = System.currentTimeMillis()): String =
            "KaChat Address Book ${isoSeconds(epochMs).replace(":", "-")}.json"

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

        private fun hashName(s: String): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
                .take(16).joinToString("") { "%02x".format(it) }

        private fun photoFileName(address: String): String = hashName(normalize(address)) + ".jpg"

        /**
         * A picked image as the JPEG an entry keeps: at most 384 px on its longer side (an avatar
         * is never drawn bigger), quality 0.8 - tens of KB, so the backup stays small. Null when
         * it can't be read.
         */
        fun preparedPhoto(context: Context, uri: android.net.Uri): ByteArray? = runCatching {
            val maxSide = 384
            val source: android.graphics.Bitmap = if (android.os.Build.VERSION.SDK_INT >= 28) {
                // ImageDecoder applies the photo's EXIF orientation.
                val src = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
                android.graphics.ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val scale = minOf(1f, maxSide.toFloat() / maxOf(w, h))
                    decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
                com.kachat.app.util.SafeBitmapDecode.decode(bytes, maxDimension = maxSide * 2) ?: return null
            }
            if (source.width <= 0 || source.height <= 0) return null
            val scale = minOf(1f, maxSide.toFloat() / maxOf(source.width, source.height))
            val scaled = if (scale < 1f) {
                android.graphics.Bitmap.createScaledBitmap(
                    source,
                    Math.round(source.width * scale).coerceAtLeast(1),
                    Math.round(source.height * scale).coerceAtLeast(1),
                    true
                )
            } else source
            val out = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        }.getOrNull()

        private val UUID_PATTERN =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        /**
         * The entry's `id` as iOS decodes it: a UUID (iOS `AddressBookEntry.id: UUID`), written
         * uppercase as iOS's encoder writes it. One entry whose id isn't a UUID makes iOS drop
         * the whole `addressBook` array - its merge then uploads the file without this device's
         * entries, and its restore refuses the archive - so an id read from an export file or an
         * older build that isn't one is replaced by one derived from the address (stable, and
         * the merge keys by address anyway).
         */
        internal fun archiveEntryId(id: String?, address: String): String {
            val raw = id?.trim().orEmpty()
            if (UUID_PATTERN.matches(raw)) return raw.uppercase()
            return UUID.nameUUIDFromBytes("kachat-address-book:${normalize(address)}".toByteArray(Charsets.UTF_8))
                .toString().uppercase()
        }

        fun AddressBookEntry.toArchive(): ArchiveAddressBookEntry = ArchiveAddressBookEntry(
            id = archiveEntryId(id, address),
            address = normalize(address),
            name = name,
            note = note,
            createdAt = isoSeconds(createdAt),
            updatedAt = isoSeconds(updatedAt),
            photo = photo?.takeIf { it.isNotBlank() },
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
                photo = photo?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/**
 * An Address Book entry as the chat backup carries it: `{id, address, name, note, createdAt,
 * updatedAt, photo?}`, dates in ISO 8601, `photo` the assigned photo as base64 JPEG (absent when
 * the entry shows the address's own avatar) - iOS `AddressBookEntry` in `ChatHistoryArchive`.
 */
data class ArchiveAddressBookEntry(
    val id: String?,
    val address: String?,
    val name: String?,
    val note: String?,
    val createdAt: String?,
    val updatedAt: String?,
    val photo: String? = null,
)

/** `{address, deletedAt}` in the archive's `addressBookDeleted`. */
data class ArchiveAddressBookTombstone(
    val address: String,
    val deletedAt: String,
)
