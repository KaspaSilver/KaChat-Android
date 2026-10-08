package com.kachat.app.services

import android.content.Context
import android.util.Log
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kachat.app.models.ChatHistoryArchive
import com.kachat.app.models.ChatHistoryArchiveConversation
import com.kachat.app.models.ChatHistoryArchiveMessage
import com.kachat.app.models.ContactEntity
import com.kachat.app.models.MessageEntity
import com.kachat.app.repository.ChatRepository
import com.kachat.app.repository.GroupRepository
import com.kachat.app.util.MessageProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chat-history export/import — file format deliberately matches iOS's `ChatHistoryArchive`
 * JSON schema field-for-field (see [ChatHistoryArchive]), so a file exported from one platform
 * imports cleanly on the other. Scoped to whichever account is active: export pulls only that
 * account's contacts/messages, import attaches everything to that same active account (an
 * archive's own `walletAddress` field is informational only, never used to route data — matches
 * iOS). Import always merges, never wipes — messages that already exist locally (same id) are
 * skipped, not overwritten.
 *
 * Wire constraints that are NOT negotiable (verified against the real decoders on the other
 * platforms — desktop's `ui/app.js` documents the same list):
 *   * iOS decodes `id` as `UUID` and `conversationId` as `UUID?`. A non-UUID string there throws
 *     and takes the WHOLE archive down, so an Android backup would not restore on an iPhone at
 *     all. Android has no UUIDs of its own (a message IS its txId), so every id is a
 *     DETERMINISTIC UUID derived from the txId — same message, same id, every export.
 *   * iOS's JSONDecoder uses `.iso8601`, i.e. `[.withInternetDateTime]` with NO fractional
 *     seconds — `…T12:34:56.789Z` fails to parse. `DateTimeFormatter.ISO_INSTANT` emits exactly
 *     that whenever the millis are non-zero, so every timestamp is truncated to whole seconds.
 *   * `messageType` decodes as a strict enum: handshake | contextual | payment | audio;
 *     `deliveryStatus` as pending | sent | failed | warning. Android's internal spellings
 *     ("comm"/"pay") must never leak into the file.
 *
 * `txId` is written verbatim — it is the real cross-platform identity, and Android's own import
 * keys its rows by it ([toMessageEntity] ignores `id` and `timestamp` entirely).
 */
@Singleton
class ChatHistoryExportImportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chatRepository: ChatRepository,
    private val groupRepository: GroupRepository,
    private val walletManager: WalletManager,
    private val addressBookManager: AddressBookManager
) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    data class ImportResult(val importedMessageCount: Int, val conversationCount: Int)

    /**
     * Builds the archive for the active account, ENCRYPTED into the cross-platform v1 backup
     * envelope ([BackupCrypto]) — the shared payload for every backup transport (local file
     * share, Nextcloud backup and automatic sync, ...). Callers that need a shareable
     * file use [exportChatHistory]; callers that just need the bytes (e.g. a Nextcloud upload) call
     * this directly. Backup transports that write the SHARED `kachat-backup.json` must go
     * through [buildBackupJson] instead, so they merge rather than overwrite.
     */
    suspend fun buildArchiveJson(): String {
        val archive = buildLocalArchive()
        return BackupCrypto.encrypt(gson.toJson(archive), backupEncryptionKey(), walletManager.getAddress())
    }

    /**
     * The envelope key for the active account: derived from the identity/chatting-address
     * private key via [WalletManager.getPrivateKeyBytes] — the exact accessor every other
     * identity consumer (ECIES, handshakes, message signing) already funnels through — per the
     * cross-platform derivation in [BackupCrypto.deriveKey].
     */
    private fun backupEncryptionKey(): ByteArray = BackupCrypto.deriveKey(walletManager.getPrivateKeyBytes())

    private suspend fun buildLocalArchive(): ChatHistoryArchive {
        val myAddress = walletManager.getAddress()
        val contactsById = chatRepository.getContacts().first().associateBy { it.id }
        val messages = chatRepository.getAllMessages()
        // Deleted chats are excluded from the export AND their tombstones travel with the
        // archive, so restoring anywhere (fresh install included) never brings them back.
        val deletedIds = chatRepository.getAllDeletedContactIds().toSet()

        val conversations = messages
            .groupBy { it.contactId }
            .mapNotNull { (contactId, contactMessages) ->
                if (contactId in deletedIds) return@mapNotNull null
                // Pending placeholders are transient local-only state, not confirmed history.
                // The provisional-id check matters just as much as the status check: a FAILED
                // optimistic send still carries its synthetic "pending_<uuid>" id, and exporting
                // it published that id as the row's txId in the shared archive. The union merge
                // then kept that entry forever, and once a successful Retry replaced the local
                // row with the real txId, the txId dedupe could no longer see the pair, so every
                // later mirror import resurrected a stalled twin next to the delivered message.
                val exportable = contactMessages.filter {
                    it.deliveryStatus != "pending" && !MessageEntity.isProvisionalId(it.id)
                }
                if (exportable.isEmpty()) return@mapNotNull null
                ChatHistoryArchiveConversation(
                    // Android has no conversation identity of its own; the contact address is the
                    // identity, so the id is derived from it (stable across exports, and a real
                    // UUID because iOS decodes this field as `UUID?`).
                    conversationId = archiveUuid("", "conversation:$contactId"),
                    contactAddress = contactId,
                    contactAlias = contactsById[contactId]?.alias,
                    contactPhoto = contactPhotoForArchive(contactsById[contactId]),
                    unreadCount = exportable.count { it.direction == "received" && !it.isRead },
                    messages = exportable.map { toArchiveMessage(it, myAddress) }
                )
            }

        // Groups ARE backed up again — now including decrypted message history (not just keys),
        // so message history survives even if the indexer has pruned old messages.
        return ChatHistoryArchive(
            exportedAt = isoSeconds(System.currentTimeMillis()),
            walletAddress = myAddress,
            conversations = conversations,
            groups = groupRepository.exportArchiveGroups(),
            deletedContactAddresses = deletedIds.sorted().takeIf { it.isNotEmpty() },
            addressBook = addressBookManager.archiveEntries().takeIf { it.isNotEmpty() },
            addressBookDeleted = addressBookManager.archiveTombstones().takeIf { it.isNotEmpty() }
        )
    }

    /**
     * The upload body for the SHARED `kachat-backup.json` — this device's history UNIONED with
     * whatever is already on the server, so a backup can only ever add to the shared history and
     * no device can delete another's. [existingRemoteJson] is what the transport just downloaded
     * (null when there is no backup yet — then this is simply the local archive).
     *
     * What happens to the server copy is [planRemoteBackup]'s call (NEXTCLOUD_SYNC.md §7, iOS
     * d57019a): another wallet's file, a hint-less envelope that won't decrypt and a newer
     * schema THROW, so the caller aborts before any PUT and that file is never touched; THIS
     * wallet's file that can't be read (a failed decrypt under our own walletHint, or content
     * that isn't a valid archive, e.g. cut off at rest) is replaced in place with this device's
     * history and no copy is made — Nextcloud's version history keeps the old content, and every
     * other device unions its own history back in on its next sync. It used to abort instead,
     * which left automatic sync stuck for good.
     *
     * The upload body is ALWAYS a fresh v1 envelope ([BackupCrypto]).
     */
    suspend fun buildBackupJson(existingRemoteJson: String?): String {
        val myAddress = walletManager.getAddress()
        val key = backupEncryptionKey()
        val plan = planRemoteBackup(existingRemoteJson, myAddress) { BackupCrypto.decrypt(it, key) }
        val local = gson.toJsonTree(buildLocalArchive()).asJsonObject
        val body = when (plan) {
            is RemoteBackupPlan.Merge -> mergeArchives(plan.remote, local)
            is RemoteBackupPlan.WriteLocal -> {
                plan.replacedBecause?.let {
                    Log.w(TAG, "The backup on the server is this account's but $it; replacing it in place (Nextcloud keeps the old version)")
                }
                local
            }
        }
        return BackupCrypto.encrypt(gson.toJson(body), key, myAddress)
    }

    /** The contact's cross-platform photo (one restored from another device), or null. KaChat no
     *  longer reads the phone's Contacts, so no Contacts-app photo is rendered (iOS 00767a4). */
    private fun contactPhotoForArchive(contact: ContactEntity?): String? =
        contact?.backupPhotoBase64?.takeIf { it.isNotBlank() }

    /** Builds the archive for the active account (encrypted into the v1 backup envelope, like every
     *  cloud copy), writes it to app-private cache, and returns a content:// URI ready to hand to a
     *  share sheet. [importChatHistory] accepts both this format and legacy plaintext exports. */
    suspend fun exportChatHistory(): Uri {
        val exportDir = File(context.cacheDir, "chat_exports").apply { mkdirs() }
        // The archive carries the permanent group decryption keys (sealed, but still), and
        // nothing needs an earlier export once its share sheet is gone - so previous exports
        // are removed rather than left in the cache directory indefinitely (iOS deletes the
        // temp file when the share sheet dismisses).
        exportDir.listFiles()?.forEach { runCatching { it.delete() } }
        val fileTimestamp = isoSeconds(System.currentTimeMillis()).replace(":", "-")
        val file = File(exportDir, "kachat-history-$fileTimestamp.json")
        file.writeText(buildArchiveJson())

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Reads a file URI (from the local file picker) and delegates to [importChatHistory]. */
    suspend fun importChatHistory(uri: Uri): ImportResult {
        val json = context.contentResolver.openInputStream(uri)?.use { it.reader().readText() }
            ?: throw IllegalStateException("Could not read the selected file")
        return importChatHistory(json)
    }

    /**
     * Parses and merges an archive JSON string into the active account's local data — the shared
     * core used by the local file-picker import and the Nextcloud restore. Throws
     * with a user-facing message on any validation failure.
     *
     * [onConversationProgress] (optional) fires as `(done, total)` after each conversation's
     * messages land (real work, not simulated) — total counts the archive's non-empty
     * conversations. Drives the blocking restore modal's determinate bar
     * (see [BackupRestoreCoordinator]).
     *
     * Accepts BOTH file formats: a v1 encrypted envelope ([BackupCrypto.isEnvelope]) is
     * walletHint-checked (a foreign wallet's file is refused without decrypting) and decrypted
     * first — a wrong-key or corrupt file throws [BackupCrypto.DECRYPT_FAILED_MESSAGE] — while
     * a legacy plaintext archive parses exactly as before, so old backups restore forever.
     */
    suspend fun importChatHistory(
        json: String,
        onConversationProgress: ((done: Int, total: Int) -> Unit)? = null
    ): ImportResult {
        // Every "this file can't be imported" verdict below is an [UnreadableBackupException]
        // (messages unchanged for the restore screens), so the change watcher can tell a file it
        // has judged - and should not download again until it changes (NEXTCLOUD_SYNC.md §6) -
        // from a failure worth retrying.
        val archiveJson = if (BackupCrypto.isEnvelope(json)) {
            val hint = BackupCrypto.envelopeWalletHint(json)
            if (hint != null && hint != BackupCrypto.walletHint(walletManager.getAddress())) {
                throw UnreadableBackupException("This backup belongs to a different account.")
            }
            // Key first, outside the try: a key that can't be loaded is this device's problem,
            // not a verdict on the file.
            val key = backupEncryptionKey()
            try {
                BackupCrypto.decrypt(json, key)
            } catch (e: IllegalStateException) {
                throw UnreadableBackupException(e.message ?: BackupCrypto.DECRYPT_FAILED_MESSAGE)
            }
        } else {
            json
        }
        val archive = try {
            gson.fromJson(archiveJson, ChatHistoryArchive::class.java) ?: throw IllegalStateException("empty")
        } catch (e: Exception) {
            throw UnreadableBackupException("This file isn't a valid chat history export")
        }
        if (archive.schemaVersion != ChatHistoryArchive.CURRENT_SCHEMA_VERSION) {
            throw UnreadableBackupException("This export was made with an incompatible app version")
        }

        // The Address Book is per wallet: only this wallet's archive (or an unstamped one) fills
        // it (iOS 00767a4).
        val archiveWallet = archive.walletAddress.orEmpty().trim().lowercase()
        if (archiveWallet.isEmpty() || archiveWallet == walletManager.getAddress().lowercase()) {
            addressBookManager.importFromArchive(
                entries = archive.addressBook.orEmpty(),
                tombstones = archive.addressBookDeleted.orEmpty()
            )
        }
        if (archive.conversations.all { it.messages.isEmpty() }) {
            throw UnreadableBackupException("This file has no chat history to import")
        }

        val myAddress = walletManager.getAddress()
        var importedCount = 0
        var conversationCount = 0
        val restoredTxIds = HashSet<String>()

        val archivedTombstones = archive.deletedContactAddresses.orEmpty().toSet()
        val progressTotal = archive.conversations.count { it.messages.isNotEmpty() }
        var progressDone = 0
        for (conversation in archive.conversations) {
            if (conversation.messages.isEmpty()) continue
            progressDone++
            val contactAddress = conversation.contactAddress
            // Never resurrect a deleted chat: honor this device's tombstones AND the ones the
            // archive itself carries (covers restoring onto a fresh install). Skipped
            // conversations still count as progress — done/total must reach total.
            if (contactAddress in archivedTombstones || chatRepository.hasDeletionTombstone(contactAddress)) {
                onConversationProgress?.invoke(progressDone, progressTotal)
                continue
            }

            val importedPhoto = conversation.contactPhoto?.takeIf { it.isNotBlank() }
            val existingContact = chatRepository.getContact(contactAddress)
            if (existingContact == null) {
                chatRepository.addContact(
                    ContactEntity(
                        id = contactAddress,
                        walletAddress = myAddress,
                        alias = conversation.contactAlias,
                        knsName = null,
                        publicKeyHex = null,
                        backupPhotoBase64 = importedPhoto
                    )
                )
            } else {
                var updated = existingContact
                if (existingContact.alias.isNullOrBlank() && !conversation.contactAlias.isNullOrBlank()) {
                    updated = updated.copy(alias = conversation.contactAlias)
                }
                // Adopt a backed-up photo only when this device has none for the contact yet.
                if (updated.backupPhotoBase64.isNullOrBlank() && importedPhoto != null) {
                    updated = updated.copy(backupPhotoBase64 = importedPhoto)
                }
                if (updated != existingContact) chatRepository.addContact(updated)
            }

            // iOS-style counts: the summary reports what the archive RESTORED (distinct txIds
            // across this conversation, whether or not the rows already existed locally), so a
            // re-restore of the same backup still reads "Restored 812 messages from 12 chats"
            // instead of zeros.
            var restoredAny = false
            for (archiveMessage in conversation.messages) {
                // "\ud83d\udce4 Sent via another device" placeholders never surface on any platform -
                // skip them at import; an archive that later carries the real body inserts it then.
                if (com.kachat.app.models.MessageEntity.isSentPlaceholder(archiveMessage.content)) continue
                // Phantom rows never import: a provisional "pending_<uuid>" txId (a failed
                // optimistic send an older build exported) or a blank txId is not an on-chain
                // identity, and inserting such a row is exactly what materialized the stalled
                // "still sending" twins next to their delivered copies.
                val archiveTxId = archiveMessage.txId.trim()
                if (archiveTxId.isEmpty() || com.kachat.app.models.MessageEntity.isProvisionalId(archiveTxId)) {
                    Log.i(TAG, "Import skipped phantom archive row (txId=${archiveTxId.take(20).ifEmpty { "<blank>" }})")
                    continue
                }
                if (restoredTxIds.add(archiveTxId)) importedCount++
                restoredAny = true
                val entity = toMessageEntity(archiveMessage, contactAddress, myAddress)
                val existing = chatRepository.getMessage(entity.id)
                if (existing != null) {
                    // Healer: the archive proves this txId was broadcast, so a local copy still
                    // stuck at pending/failed (a finalize that never ran) flips to sent in place.
                    if (existing.direction == "sent" && existing.deliveryStatus != "sent" && entity.deliveryStatus == "sent") {
                        chatRepository.updateMessageStatus(entity.id, "sent")
                        Log.i(TAG, "Import healed stuck delivery status to sent (txId=${entity.id.take(16)})")
                    }
                    continue
                }
                // Outgoing archive rows can be THIS device's own send coming back around under
                // its real txId (another device saw it on-chain and uploaded it) while the local
                // copy still sits under its provisional "pending_<uuid>" placeholder id — the
                // send flow's finalize step never ran (process death / cancelled coroutine /
                // local timeout on a broadcast that actually landed). A plain insert would
                // create a delivered twin next to a forever-"sending" placeholder. Instead the
                // placeholder is upgraded in place to the real txId + "sent".
                if (archiveMessage.isOutgoing) {
                    when (val match = chatRepository.matchProvisionalOutgoing(contactAddress, entity.plaintextBody, entity.blockTimestamp)) {
                        is ChatRepository.ProvisionalMatch.Exact -> {
                            chatRepository.upgradeProvisionalMessage(match.row, entity)
                            continue
                        }
                        is ChatRepository.ProvisionalMatch.Sole -> {
                            // Content drifted (metadata, formatting) but there is exactly one
                            // in-flight candidate in the window and the archive row is not older
                            // than it: the archive's delivered copy wins, the placeholder goes
                            // away. If the placeholder was a genuinely different in-flight send,
                            // its own finalize re-inserts the real row moments later, so nothing
                            // is lost either way.
                            chatRepository.collapseProvisionalInto(match.row, entity)
                            continue
                        }
                        is ChatRepository.ProvisionalMatch.Ambiguous -> {
                            Log.i(TAG, "Import: multiple provisional candidates for txId=${entity.id.take(16)}; inserted without collapsing")
                        }
                        ChatRepository.ProvisionalMatch.None -> Unit
                    }
                }
                chatRepository.insertMessage(entity)
            }
            if (restoredAny) conversationCount++
            onConversationProgress?.invoke(progressDone, progressTotal)
        }

        // Groups (cross-platform recovery): restore full group key material so this device
        // recovers admin groups it created elsewhere as well as member ones. Optional - older
        // archives omit it.
        archive.groups?.takeIf { it.isNotEmpty() }?.let { groupRepository.importArchiveGroups(it) }

        return ImportResult(importedMessageCount = importedCount, conversationCount = conversationCount)
    }

    companion object {
        private const val TAG = "ChatHistoryImport"
        private val VALID_DELIVERY_STATUSES = setOf("pending", "sent", "failed", "warning")
        private val VALID_MESSAGE_TYPES = setOf("handshake", "contextual", "payment", "audio")

        /** iOS's `DeliveryStatus.priority` — the tiebreak when the same txId turns up on both sides of a merge. */
        private val STATUS_PRIORITY = mapOf("pending" to 0, "warning" to 1, "failed" to 2, "sent" to 3)

        /** Bodies that mean "we know a message exists but not what it says" — a real body always wins over these. */
        private val PLACEHOLDER_BODIES =
            setOf(com.kachat.app.models.MessageEntity.SENT_VIA_OTHER_DEVICE_PLACEHOLDER, "[Encrypted message]")

        private val UUID_PATTERN =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        // -----------------------------------------------------------------------------
        // Wire-format helpers (kept byte-identical to desktop's ui/app.js so the two
        // platforms derive the SAME id for the same message and never fight over it)
        // -----------------------------------------------------------------------------

        /**
         * Deterministic RFC-4122 UUID from an arbitrary string (xmur3 seed -> 4 words), so
         * re-exporting the same message always produces the same `id` and the other platforms'
         * id-keyed dedupe keeps working across backups. Ported verbatim from desktop's
         * `derivedArchiveUuid` (JS `Math.imul`/`>>>` map exactly onto Kotlin `Int` arithmetic).
         */
        internal fun derivedArchiveUuid(seed: String): String {
            var h = 1779033703 xor seed.length
            for (element in seed) {
                h = (h xor element.code) * 3432918353L.toInt()
                h = (h shl 13) or (h ushr 19)
            }
            val builder = StringBuilder(32)
            repeat(4) {
                h = (h xor (h ushr 16)) * 2246822507L.toInt()
                h = (h xor (h ushr 13)) * 3266489909L.toInt()
                h = h xor (h ushr 16)
                builder.append((h.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0'))
            }
            val nibbles = builder.toString().toCharArray()
            nibbles[12] = '4'                                                           // RFC 4122 version
            nibbles[16] = "89ab"[Character.digit(nibbles[16], 16) and 3]                // RFC 4122 variant
            val flat = String(nibbles)
            return "${flat.substring(0, 8)}-${flat.substring(8, 12)}-${flat.substring(12, 16)}-" +
                "${flat.substring(16, 20)}-${flat.substring(20, 32)}"
        }

        /** Passes a real UUID through untouched, otherwise derives one (from [value] when it has one, else [seed]). */
        internal fun archiveUuid(value: String?, seed: String): String {
            val raw = value?.trim().orEmpty()
            if (UUID_PATTERN.matches(raw)) return raw.lowercase()
            return derivedArchiveUuid(raw.ifEmpty { seed })
        }

        /**
         * Whole-second ISO8601 (`2026-08-17T12:34:56Z`). `DateTimeFormatter.ISO_INSTANT` alone is
         * NOT safe here: it appends `.123` whenever the millis are non-zero, and iOS's `.iso8601`
         * decoding strategy rejects fractional seconds outright.
         */
        internal fun isoSeconds(epochMs: Long): String {
            val instant = Instant.ofEpochMilli(if (epochMs > 0) epochMs else System.currentTimeMillis())
            return DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS))
        }

        private fun parseIsoMs(value: String): Long? {
            val raw = value.trim()
            if (raw.isEmpty()) return null
            return runCatching { Instant.parse(raw).toEpochMilli() }
                .recoverCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
                .getOrNull()
        }

        internal fun archiveMessageType(entityType: String): String = when (entityType) {
            MessageProtocol.TYPE_HANDSHAKE -> "handshake"
            MessageProtocol.TYPE_COMM -> "contextual"
            MessageProtocol.TYPE_PAY -> "payment"
            // Anything else would be an Android-internal spelling leaking into a file iOS decodes
            // as a strict enum — that throws and takes the whole archive down, so never emit it.
            else -> if (entityType in VALID_MESSAGE_TYPES) entityType else "contextual"
        }

        /** Android has no distinct "audio message" type yet — those import as a regular contextual message. */
        internal fun entityMessageType(archiveType: String): String = when (archiveType) {
            "handshake" -> MessageProtocol.TYPE_HANDSHAKE
            "contextual" -> MessageProtocol.TYPE_COMM
            "payment" -> MessageProtocol.TYPE_PAY
            "audio" -> MessageProtocol.TYPE_COMM
            else -> MessageProtocol.TYPE_COMM
        }

        private fun archiveDeliveryStatus(status: String): String =
            if (status in VALID_DELIVERY_STATUSES) status else "sent"

        internal fun toArchiveMessage(entity: MessageEntity, myAddress: String): ChatHistoryArchiveMessage {
            val isOutgoing = entity.direction == "sent"
            return ChatHistoryArchiveMessage(
                // entity.id IS the txId; iOS needs a UUID here, so publish a deterministic one.
                id = archiveUuid(entity.id, "${entity.contactId}:${entity.id}"),
                txId = entity.id,
                senderAddress = if (isOutgoing) myAddress else entity.contactId,
                receiverAddress = if (isOutgoing) entity.contactId else myAddress,
                content = entity.plaintextBody ?: "",
                timestamp = isoSeconds(entity.blockTimestamp),
                blockTime = entity.blockTimestamp,
                isOutgoing = isOutgoing,
                messageType = archiveMessageType(entity.type),
                deliveryStatus = archiveDeliveryStatus(entity.deliveryStatus)
            )
        }

        /** Imported history is never marked unread — the archive format tracks unread only as a per-conversation count, not per message, so there's nothing meaningful to restore. */
        internal fun toMessageEntity(archiveMessage: ChatHistoryArchiveMessage, contactId: String, myAddress: String): MessageEntity {
            return MessageEntity(
                id = archiveMessage.txId.trim(),
                contactId = contactId,
                walletAddress = myAddress,
                type = entityMessageType(archiveMessage.messageType),
                direction = if (archiveMessage.isOutgoing) "sent" else "received",
                plaintextBody = archiveMessage.content,
                encryptedPayload = "",
                amountSompi = null,
                blockTimestamp = archiveMessage.blockTime,
                isRead = true,
                // The importer only ever reaches this for rows with a real txId, and a real txId
                // means the send WAS broadcast: another device's stale "pending"/"failed"
                // snapshot must not materialize here as an eternally-waiting row this device
                // has no send flow to ever resolve. Only "warning" (payment verification) is a
                // real terminal state worth carrying over.
                deliveryStatus = if (archiveMessage.deliveryStatus == "warning") "warning" else "sent"
            )
        }

        // -----------------------------------------------------------------------------
        // Shared-file merge (upload side) — mirrors iOS's parseRemoteArchive +
        // mergeBackupArchives, with NEXTCLOUD_SYNC.md §7 deciding what is never touched and
        // what is replaced in place ([planRemoteBackup]).
        // -----------------------------------------------------------------------------

        private fun JsonObject.string(key: String): String {
            val element = get(key) ?: return ""
            return if (element.isJsonPrimitive) element.asString.orEmpty() else ""
        }

        private fun JsonObject.long(key: String): Long {
            val element = get(key) ?: return 0L
            if (!element.isJsonPrimitive) return 0L
            return runCatching { element.asLong }.getOrElse { element.asString.toLongOrNull() ?: 0L }
        }

        private fun JsonObject.bool(key: String): Boolean {
            val element = get(key) ?: return false
            return element.isJsonPrimitive && runCatching { element.asBoolean }.getOrDefault(false)
        }

        private fun JsonObject.array(key: String): JsonArray {
            val element = get(key) ?: return JsonArray()
            return if (element.isJsonArray) element.asJsonArray else JsonArray()
        }

        private fun exportedAtMs(archive: JsonObject): Long = parseIsoMs(archive.string("exportedAt")) ?: 0L

        /**
         * What a sync does with the file already on the server, per NEXTCLOUD_SYNC.md §4.3 and §7
         * (the same verdicts as iOS d57019a `performBackup`). Pure, so the cases are unit-tested:
         *
         *   * null (a genuine 404) - no backup yet: write this device's history;
         *   * an empty file - nothing anyone could lose: write this device's history in place;
         *   * an envelope with ANOTHER wallet's walletHint - THROWS, never touched;
         *   * an envelope this client can't read because it is a newer envelope version - THROWS
         *     (a newer app wrote it);
         *   * an envelope that won't decrypt: with THIS wallet's walletHint it is our own damaged
         *     file - write this device's history in place, no copy; with no walletHint it can't be
         *     attributed to anyone - THROWS;
         *   * archive JSON (decrypted, or legacy plaintext) that is a newer schema or another
         *     wallet's (plaintext `walletAddress`) - THROWS;
         *   * content that isn't a valid archive at all (e.g. cut off at rest) - write this
         *     device's history in place.
         *
         * A download that failed or stopped early never gets here: the transport throws first
         * (see NextcloudService.readBackupBody), because a transfer problem says nothing about
         * the file. [decrypt] opens a v1 envelope with this wallet's key and throws on failure.
         */
        internal fun planRemoteBackup(
            existingRemoteJson: String?,
            myAddress: String,
            decrypt: (String) -> String
        ): RemoteBackupPlan {
            if (existingRemoteJson == null) return RemoteBackupPlan.WriteLocal(replacedBecause = null)
            if (existingRemoteJson.isBlank()) return RemoteBackupPlan.WriteLocal("the file is empty")
            val plaintext = if (BackupCrypto.isEnvelope(existingRemoteJson)) {
                val hint = BackupCrypto.envelopeWalletHint(existingRemoteJson)
                if (hint != null && hint != BackupCrypto.walletHint(myAddress)) {
                    throw IllegalStateException(FOREIGN_WALLET_MESSAGE)
                }
                try {
                    decrypt(existingRemoteJson)
                } catch (e: Exception) {
                    // Only our own walletHint makes a failed decrypt OUR damaged file. An envelope
                    // without one can't be attributed and is never treated as ours (iOS
                    // BackupEnvelope.isOwnUnreadableEnvelope).
                    if (hint == null) throw e
                    return RemoteBackupPlan.WriteLocal("it can't be decrypted")
                }
            } else {
                val envelopeVersion = BackupCrypto.envelopeVersion(existingRemoteJson)
                if (envelopeVersion != null && envelopeVersion > BackupCrypto.ENVELOPE_VERSION) {
                    throw IllegalStateException(
                        "The backup already on the server uses a newer encrypted format (version $envelopeVersion), which this version can't merge — nothing was uploaded and it was left untouched. Update the app."
                    )
                }
                existingRemoteJson
            }
            return try {
                RemoteBackupPlan.Merge(parseRemoteArchive(plaintext, myAddress))
            } catch (e: RemoteBackupUnreadableException) {
                RemoteBackupPlan.WriteLocal("it isn't a readable archive (damaged or cut off)")
            }
        }

        private const val FOREIGN_WALLET_MESSAGE =
            "The backup already on the server belongs to a different account. Nothing was uploaded and it was left untouched. Choose a separate backup folder for this account."

        /**
         * Validates the archive JSON already sitting on the server (decrypted, or legacy
         * plaintext). Throws [RemoteBackupUnreadableException] for content that isn't a KaChat
         * archive at all - [planRemoteBackup] replaces that in place - and a plain
         * [IllegalStateException] for a file that must never be touched: a newer (or otherwise
         * different) schema, or another wallet's archive.
         *
         * A newer schemaVersion is checked BEFORE the shape, so a future archive that reshapes
         * its body is still recognised as newer and left alone rather than taken for damage.
         */
        internal fun parseRemoteArchive(json: String, myAddress: String): JsonObject {
            val parsed = runCatching { JsonParser.parseString(json) }.getOrNull()
                ?: throw RemoteBackupUnreadableException("The backup already on the server isn't readable JSON.")
            val remote = parsed as? JsonObject
                ?: throw RemoteBackupUnreadableException("The file already on the server isn't a KaChat backup.")
            val schemaVersion = remote.long("schemaVersion")
            if (schemaVersion > ChatHistoryArchive.CURRENT_SCHEMA_VERSION.toLong()) {
                throw IllegalStateException(incompatibleSchemaMessage(schemaVersion))
            }
            if (remote.get("conversations")?.isJsonArray != true) {
                throw RemoteBackupUnreadableException("The file already on the server isn't a KaChat backup.")
            }
            if (schemaVersion != ChatHistoryArchive.CURRENT_SCHEMA_VERSION.toLong()) {
                throw IllegalStateException(incompatibleSchemaMessage(schemaVersion))
            }
            val remoteWallet = remote.string("walletAddress").trim()
            if (remoteWallet.isNotEmpty() && myAddress.isNotEmpty() && remoteWallet != myAddress) {
                throw IllegalStateException(
                    "The backup already on the server belongs to a different wallet — nothing was uploaded. Choose a separate backup folder for this account."
                )
            }
            return remote
        }

        private fun incompatibleSchemaMessage(schemaVersion: Long) =
            "The backup already on the server uses schema version $schemaVersion, which this version can't merge — nothing was uploaded and it was left untouched."

        /** A body that carries no real content — a real one always beats it in [preferArchiveMessage]. */
        private fun isPlaceholderBody(content: String): Boolean = content.isEmpty() || content in PLACEHOLDER_BODIES

        /**
         * Mirrors iOS's `ChatService.preferMessage`: a real body beats a placeholder, then the
         * further-along delivery status wins, then the later blockTime.
         */
        internal fun preferArchiveMessage(existing: JsonObject, candidate: JsonObject): JsonObject {
            val existingPlaceholder = isPlaceholderBody(existing.string("content"))
            val candidatePlaceholder = isPlaceholderBody(candidate.string("content"))
            if (existingPlaceholder != candidatePlaceholder) return if (candidatePlaceholder) existing else candidate

            val existingPriority = STATUS_PRIORITY[existing.string("deliveryStatus")] ?: 3
            val candidatePriority = STATUS_PRIORITY[candidate.string("deliveryStatus")] ?: 3
            if (existingPriority != candidatePriority) {
                return if (candidatePriority > existingPriority) candidate else existing
            }
            return if (candidate.long("blockTime") > existing.long("blockTime")) candidate else existing
        }

        /** txId is the real identity; `id` is only the fallback for a message that never made it on-chain. */
        private fun messageKey(message: JsonObject): String {
            val txId = message.string("txId").trim()
            return if (txId.isNotEmpty()) "tx:$txId" else "id:${message.string("id").trim()}"
        }

        /**
         * True for archive entries that never had (or do not have) a real on-chain identity:
         * a provisional "pending_<uuid>" txId, or no txId while still marked in-flight
         * (pending/failed). These are device-local optimistic-send snapshots, not history —
         * see the scrub in [mergeArchives] and the matching skip in the importer.
         */
        internal fun isPhantomArchiveEntry(message: JsonObject): Boolean {
            val txId = message.string("txId").trim()
            if (MessageEntity.isProvisionalId(txId)) return true
            val status = message.string("deliveryStatus").trim().lowercase()
            return txId.isEmpty() && (status == "pending" || status == "failed")
        }

        /**
         * Coerces any archive message — including one another device wrote — into the strictest
         * shape every decoder accepts, without changing what it says. Keys this schema doesn't
         * model are carried through untouched.
         */
        private fun normalizeArchiveMessage(message: JsonObject): JsonObject {
            val out = message.deepCopy()
            val txId = message.string("txId").trim()
            val rawId = message.string("id").trim()
            val blockTime = message.long("blockTime").coerceAtLeast(0L)
            val timestampMs = if (blockTime > 0) blockTime else parseIsoMs(message.string("timestamp")) ?: System.currentTimeMillis()

            out.addProperty("id", archiveUuid(rawId, "$txId:$rawId"))
            out.addProperty("txId", txId)
            out.addProperty("senderAddress", message.string("senderAddress"))
            out.addProperty("receiverAddress", message.string("receiverAddress"))
            out.addProperty("content", message.string("content"))
            out.addProperty("timestamp", isoSeconds(timestampMs))
            out.addProperty("blockTime", blockTime)
            out.addProperty("isOutgoing", message.bool("isOutgoing"))
            out.addProperty("messageType", archiveMessageType(message.string("messageType").trim().lowercase()))
            out.addProperty("deliveryStatus", archiveDeliveryStatus(message.string("deliveryStatus").trim().lowercase()))
            // Both phone encoders drop nil optionals rather than emitting null, and neither reads
            // this back — omit it unless it actually carries a value.
            if (message.string("acceptingBlock").trim().isEmpty()) out.remove("acceptingBlock")
            return out
        }

        private fun sortedMessages(messages: Collection<JsonObject>): JsonArray {
            val sorted = messages.sortedWith(
                compareBy<JsonObject> { it.long("blockTime") }
                    .thenBy { it.string("txId").ifEmpty { it.string("id") } }
            )
            return JsonArray().apply { sorted.forEach { add(it) } }
        }

        private class ConversationMerge(val contactAddress: String, val base: JsonObject) {
            var conversationId: String = ""
            var contactAlias: String = ""
            var contactPhoto: String = ""
            var unreadCount: Long = 0
            val messages = LinkedHashMap<String, JsonObject>()
        }

        /**
         * Union of the archive on the server and this device's archive — this is what makes the
         * shared file a sync point rather than last-writer-wins:
         *   * a conversation present on only ONE side is kept whole;
         *   * messages dedupe by txId (falling back to `id`), keeping the better copy per iOS's
         *     preferMessage ordering — so a remote `pending` message, which this device would
         *     never export itself, survives the union (and is upgraded rather than dropped if the
         *     same txId is confirmed locally);
         *   * conversation metadata (alias / photo / unreadCount) comes from whichever archive was
         *     exported more recently, and an empty value never overwrites a real one;
         *     `conversationId` keeps the already-published value for stability.
         *
         * CRITICAL: the result starts as a deep copy of the REMOTE object, so every top-level key
         * Android doesn't model — desktop keeps its whole state in an additive `desktopState` key —
         * survives verbatim. Round-tripping through the typed [ChatHistoryArchive] model would
         * silently drop it and wipe desktop's state on the next Android backup. The same applies
         * per-conversation and per-message: unknown keys there are carried through too.
         */
        /**
         * The two sides' `addressBook` / `addressBookDeleted`, merged ([AddressBookManager.merge])
         * and re-encoded in the archive's own shape (dates ISO 8601). An unreadable side counts as
         * empty (iOS `mergeArchiveAddressBooks`).
         */
        internal fun mergeArchiveAddressBooks(local: JsonObject, remote: JsonObject): Pair<JsonArray, JsonArray> {
            val plain = Gson()
            fun entries(side: JsonObject): List<AddressBookEntry> = runCatching {
                (side.get("addressBook") as? JsonArray)?.mapNotNull { el ->
                    runCatching {
                        with(AddressBookManager) { plain.fromJson(el, ArchiveAddressBookEntry::class.java)?.toEntry() }
                    }.getOrNull()
                }.orEmpty()
            }.getOrDefault(emptyList())
            fun tombstones(side: JsonObject): List<AddressBookTombstone> = runCatching {
                (side.get("addressBookDeleted") as? JsonArray)?.mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    val address = obj.string("address").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val deletedAt = AddressBookManager.parseDate(runCatching { obj.get("deletedAt")?.asString }.getOrNull())
                        ?: return@mapNotNull null
                    AddressBookTombstone(address, deletedAt)
                }.orEmpty()
            }.getOrDefault(emptyList())
            val (kept, tombs) = AddressBookManager.merge(
                listOf(entries(local), entries(remote)),
                listOf(tombstones(local), tombstones(remote))
            )
            val book = JsonArray()
            kept.forEach { book.add(plain.toJsonTree(with(AddressBookManager) { it.toArchive() })) }
            val deleted = JsonArray()
            tombs.forEach {
                deleted.add(JsonObject().apply {
                    addProperty("address", it.address)
                    addProperty("deletedAt", AddressBookManager.isoSeconds(it.deletedAt))
                })
            }
            return book to deleted
        }

        internal fun mergeArchives(remote: JsonObject, local: JsonObject): JsonObject {
            val remoteIsNewer = exportedAtMs(remote) > exportedAtMs(local)
            val merged = LinkedHashMap<String, ConversationMerge>()

            fun absorb(archive: JsonObject, isRemote: Boolean) {
                for (element in archive.array("conversations")) {
                    val conversation = element as? JsonObject ?: continue
                    val contactAddress = conversation.string("contactAddress").trim()
                    if (contactAddress.isEmpty()) continue
                    val metadataWins = if (isRemote) remoteIsNewer else !remoteIsNewer
                    val alias = conversation.string("contactAlias").trim()
                    val conversationId = conversation.string("conversationId").trim()
                    val photo = conversation.string("contactPhoto")
                    val unreadCount = conversation.long("unreadCount").coerceAtLeast(0L)

                    var entry = merged[contactAddress]
                    if (entry == null) {
                        entry = ConversationMerge(contactAddress, conversation.deepCopy()).also {
                            it.conversationId = conversationId
                            it.contactAlias = alias
                            it.contactPhoto = photo
                            it.unreadCount = unreadCount
                            merged[contactAddress] = it
                        }
                    } else {
                        if (alias.isNotEmpty() && (metadataWins || entry.contactAlias.isEmpty())) entry.contactAlias = alias
                        if (conversationId.isNotEmpty() && entry.conversationId.isEmpty()) entry.conversationId = conversationId
                        // The photo is per-conversation metadata like the alias (NEXTCLOUD_SYNC.md
                        // §5: the newer exportedAt wins, and an empty value never overwrites a real
                        // one). The remote-seeded base used to keep the server's photo always and
                        // drop this device's whenever the server copy had none.
                        if (photo.isNotEmpty() && (metadataWins || entry.contactPhoto.isEmpty())) entry.contactPhoto = photo
                        if (metadataWins) entry.unreadCount = unreadCount
                    }

                    for (messageElement in conversation.array("messages")) {
                        val message = messageElement as? JsonObject ?: continue
                        // Phantom scrub: an entry whose txId is a synthetic provisional id, or an
                        // in-flight (pending/failed) entry with no txId at all, is transient
                        // device-local state that leaked into the shared file (older Android
                        // builds exported failed placeholders; iOS can export a not-yet-broadcast
                        // row with an empty txId). Once the send confirms it re-enters the union
                        // under its real txId as a DIFFERENT key, so the stale entry would live
                        // forever and re-import as a stalled twin on every device. Dropping it
                        // here heals the server copy on this device's next upload.
                        if (isPhantomArchiveEntry(message)) continue
                        val key = messageKey(message)
                        val existing = entry.messages[key]
                        entry.messages[key] = if (existing == null) message else preferArchiveMessage(existing, message)
                    }
                }
            }

            // Remote first so it seeds identity; local second so this device's newer view can win
            // the per-field metadata contest when it is in fact newer.
            absorb(remote, isRemote = true)
            absorb(local, isRemote = false)

            // Deletion tombstones: union of both sides, and any tombstoned conversation is
            // dropped from the merged backup - a chat deleted on one device stays deleted in
            // the shared history instead of resurrecting from the other side's copy.
            val tombstones = sortedSetOf<String>()
            for (side in listOf(local, remote)) {
                (side.get("deletedContactAddresses") as? JsonArray)?.forEach { el ->
                    runCatching { el.asString }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { tombstones.add(it) }
                }
            }

            val conversations = JsonArray()
            for (entry in merged.values.sortedBy { it.contactAddress }) {
                if (entry.contactAddress in tombstones) continue
                val conversation = entry.base   // already a deep copy — keeps any unknown keys
                // conversationId is normalized too: iOS decodes it as UUID?, so a non-UUID one
                // written by another client would throw on its restore.
                conversation.addProperty(
                    "conversationId",
                    archiveUuid(entry.conversationId, "conversation:${entry.contactAddress}")
                )
                conversation.addProperty("contactAddress", entry.contactAddress)
                if (entry.contactAlias.isEmpty()) conversation.remove("contactAlias")
                else conversation.addProperty("contactAlias", entry.contactAlias)
                if (entry.contactPhoto.isEmpty()) conversation.remove("contactPhoto")
                else conversation.addProperty("contactPhoto", entry.contactPhoto)
                conversation.addProperty("unreadCount", entry.unreadCount)
                conversation.add("messages", sortedMessages(entry.messages.values.map { normalizeArchiveMessage(it) }))
                conversations.add(conversation)
            }

            val result = remote.deepCopy()  // preserves desktopState and every other foreign key
            result.addProperty("schemaVersion", ChatHistoryArchive.CURRENT_SCHEMA_VERSION)
            result.addProperty("exportedAt", local.string("exportedAt"))
            val walletAddress = local.string("walletAddress").ifEmpty { remote.string("walletAddress") }
            if (walletAddress.isEmpty()) result.remove("walletAddress") else result.addProperty("walletAddress", walletAddress)
            result.add("conversations", conversations)

            // Groups: union by groupId so the shared backup accumulates every device's groups.
            // Local is listed first, so for a group both hold the just-exported local copy wins.
            val mergedGroups = com.google.gson.JsonArray()
            val seenGroupIds = HashSet<String>()
            for (arr in listOf(local.getAsJsonArray("groups"), remote.getAsJsonArray("groups"))) {
                arr?.forEach { el ->
                    if (el.isJsonObject) {
                        val gid = el.asJsonObject.string("groupId")
                        if (gid.isNotEmpty() && seenGroupIds.add(gid)) mergedGroups.add(el)
                    }
                }
            }
            if (mergedGroups.size() > 0) result.add("groups", mergedGroups) else result.remove("groups")

            // Address Book: per address the newest edit or deletion wins (AddressBookManager.merge).
            val (book, bookDeleted) = mergeArchiveAddressBooks(local, remote)
            if (book.size() > 0) result.add("addressBook", book) else result.remove("addressBook")
            if (bookDeleted.size() > 0) result.add("addressBookDeleted", bookDeleted) else result.remove("addressBookDeleted")
            if (tombstones.isNotEmpty()) {
                val tombstoneArray = JsonArray()
                tombstones.forEach { tombstoneArray.add(it) }
                result.add("deletedContactAddresses", tombstoneArray)
            } else {
                result.remove("deletedContactAddresses")
            }
            return result
        }
    }
}

/** What [ChatHistoryExportImportService.planRemoteBackup] decided to do with the server copy. */
internal sealed class RemoteBackupPlan {
    /** Merge this device's history into [remote] and write the union. */
    class Merge(val remote: JsonObject) : RemoteBackupPlan()

    /**
     * Write this device's history as the whole file. [replacedBecause] is null for the plain
     * "no backup yet" case, and otherwise says why THIS wallet's file on the server is being
     * replaced in place (logged; Nextcloud's version history keeps the old content).
     */
    class WriteLocal(val replacedBecause: String?) : RemoteBackupPlan()
}

/** The server copy isn't a KaChat archive at all (not JSON, not an object, no conversations):
 *  the one archive-level verdict that [ChatHistoryExportImportService.planRemoteBackup] turns
 *  into "replace in place" rather than "never touch". */
internal class RemoteBackupUnreadableException(message: String) : IllegalStateException(message)

/**
 * A backup file [ChatHistoryExportImportService.importChatHistory] judged and refused - another
 * account's, undecryptable, not an archive, an incompatible schema, or empty. Still an
 * [IllegalStateException] with the same user-facing message as before; the type is what lets the
 * change watcher record that version's ETag instead of downloading it on every poll
 * (NEXTCLOUD_SYNC.md §6).
 */
class UnreadableBackupException(message: String) : IllegalStateException(message)
