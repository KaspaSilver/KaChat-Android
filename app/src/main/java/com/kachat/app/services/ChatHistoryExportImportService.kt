package com.kachat.app.services

import android.content.Context
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
    private val walletManager: WalletManager
) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    data class ImportResult(val importedMessageCount: Int, val conversationCount: Int)

    /**
     * Builds the archive JSON for the active account — the shared payload for every backup
     * transport (local file share, Google Drive, ...). Callers that need a shareable file use
     * [exportChatHistory]; callers that just need the bytes (e.g. a Drive upload) call this
     * directly. Backup transports that write the SHARED `kachat-backup.json` must go through
     * [buildBackupJson] instead, so they merge rather than overwrite.
     */
    suspend fun buildArchiveJson(): String = gson.toJson(buildLocalArchive())

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
                val exportable = contactMessages.filter { it.deliveryStatus != "pending" }
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

        // Groups are no longer backed up: member groups (their on-chain invite) and admin groups
        // (the self-addressed recovery invite) both rediscover from chain on a seedless import, so
        // shipping their secret keys to Google Drive/the shared archive is unnecessary. Old
        // archives carrying a groups array are still imported (below), so nobody loses data.
        return ChatHistoryArchive(
            exportedAt = isoSeconds(System.currentTimeMillis()),
            walletAddress = myAddress,
            conversations = conversations,
            groups = null,
            deletedContactAddresses = deletedIds.sorted().takeIf { it.isNotEmpty() }
        )
    }

    /**
     * The upload body for the SHARED `kachat-backup.json` — this device's history UNIONED with
     * whatever is already on the server, so a backup can only ever add to the shared history and
     * no device can delete another's. [existingRemoteJson] is what the transport just downloaded
     * (null when there is no backup yet — then this is simply the local archive).
     *
     * Every validation failure THROWS: the caller must abort the upload, leaving a foreign or
     * unreadable file exactly as it was rather than destroying it.
     */
    suspend fun buildBackupJson(existingRemoteJson: String?): String {
        val local = gson.toJsonTree(buildLocalArchive()).asJsonObject
        val remoteJson = existingRemoteJson?.takeIf { it.isNotBlank() } ?: return gson.toJson(local)
        val remote = parseRemoteArchive(remoteJson, walletManager.getAddress())
        return gson.toJson(mergeArchives(remote, local))
    }

    /**
     * A cross-platform base64 JPEG for the contact's photo, or null. A photo already carried in
     * from a backup wins; otherwise the linked device-contact photo is decoded, downscaled to a
     * small thumbnail, and re-encoded so it travels in the shared file without bloating it.
     * Best-effort: any failure (no photo, unreadable URI) just omits the photo.
     */
    private fun contactPhotoForArchive(contact: ContactEntity?): String? {
        if (contact == null) return null
        if (!contact.backupPhotoBase64.isNullOrBlank()) return contact.backupPhotoBase64
        val uriString = contact.systemContactPhotoUri ?: return null
        return try {
            val bytes = context.contentResolver.openInputStream(Uri.parse(uriString))?.use { it.readBytes() }
                ?: return null
            val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val maxDimension = 256
            val scale = minOf(1f, maxDimension.toFloat() / maxOf(source.width, source.height))
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true)
            } else source
            val out = java.io.ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    /** Builds the archive for the active account, writes it to app-private cache, and returns a content:// URI ready to hand to a share sheet. */
    suspend fun exportChatHistory(): Uri {
        val exportDir = File(context.cacheDir, "chat_exports").apply { mkdirs() }
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
     * core used by both the local file-picker import and Google Drive restore. Throws with a
     * user-facing message on any validation failure.
     */
    suspend fun importChatHistory(json: String): ImportResult {
        val archive = try {
            gson.fromJson(json, ChatHistoryArchive::class.java) ?: throw IllegalStateException("empty")
        } catch (e: Exception) {
            throw IllegalStateException("This file isn't a valid chat history export")
        }
        if (archive.schemaVersion != ChatHistoryArchive.CURRENT_SCHEMA_VERSION) {
            throw IllegalStateException("This export was made with an incompatible app version")
        }
        if (archive.conversations.all { it.messages.isEmpty() }) {
            throw IllegalStateException("This file has no chat history to import")
        }

        val myAddress = walletManager.getAddress()
        var importedCount = 0
        var conversationCount = 0

        val archivedTombstones = archive.deletedContactAddresses.orEmpty().toSet()
        for (conversation in archive.conversations) {
            if (conversation.messages.isEmpty()) continue
            val contactAddress = conversation.contactAddress
            // Never resurrect a deleted chat: honor this device's tombstones AND the ones the
            // archive itself carries (covers restoring onto a fresh install).
            if (contactAddress in archivedTombstones) continue
            if (chatRepository.hasDeletionTombstone(contactAddress)) continue

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
                // Adopt a backed-up photo only when this device has no photo of its own for the
                // contact (no linked device photo and no prior backup photo).
                if (updated.backupPhotoBase64.isNullOrBlank() && updated.systemContactPhotoUri.isNullOrBlank() && importedPhoto != null) {
                    updated = updated.copy(backupPhotoBase64 = importedPhoto)
                }
                if (updated != existingContact) chatRepository.addContact(updated)
            }

            var addedAny = false
            for (archiveMessage in conversation.messages) {
                // "\ud83d\udce4 Sent via another device" placeholders never surface on any platform -
                // skip them at import; an archive that later carries the real body inserts it then.
                if (archiveMessage.content == "\ud83d\udce4 Sent via another device") continue
                val entity = toMessageEntity(archiveMessage, contactAddress, myAddress)
                if (chatRepository.messageExists(entity.id)) continue
                chatRepository.insertMessage(entity)
                importedCount++
                addedAny = true
            }
            if (addedAny) conversationCount++
        }

        // Groups (cross-platform recovery): restore full group key material so this device
        // recovers admin groups it created elsewhere as well as member ones. Optional - older
        // archives omit it.
        archive.groups?.takeIf { it.isNotEmpty() }?.let { groupRepository.importArchiveGroups(it) }

        return ImportResult(importedMessageCount = importedCount, conversationCount = conversationCount)
    }

    companion object {
        private val VALID_DELIVERY_STATUSES = setOf("pending", "sent", "failed", "warning")
        private val VALID_MESSAGE_TYPES = setOf("handshake", "contextual", "payment", "audio")

        /** iOS's `DeliveryStatus.priority` — the tiebreak when the same txId turns up on both sides of a merge. */
        private val STATUS_PRIORITY = mapOf("pending" to 0, "warning" to 1, "failed" to 2, "sent" to 3)

        /** Bodies that mean "we know a message exists but not what it says" — a real body always wins over these. */
        private val PLACEHOLDER_BODIES = setOf("📤 Sent via another device", "[Encrypted message]")

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
                id = archiveMessage.txId,
                contactId = contactId,
                walletAddress = myAddress,
                type = entityMessageType(archiveMessage.messageType),
                direction = if (archiveMessage.isOutgoing) "sent" else "received",
                plaintextBody = archiveMessage.content,
                encryptedPayload = "",
                amountSompi = null,
                blockTimestamp = archiveMessage.blockTime,
                isRead = true,
                deliveryStatus = archiveMessage.deliveryStatus.takeIf { it in VALID_DELIVERY_STATUSES } ?: "sent"
            )
        }

        // -----------------------------------------------------------------------------
        // Shared-file merge (upload side) — mirrors desktop's parseRemoteChatArchive +
        // mergeChatArchives, including its abort-rather-than-destroy contract.
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
         * Validates the archive already sitting on the server. Every failure path THROWS — the
         * caller aborts BEFORE uploading, so an unreadable or foreign backup is left exactly as it
         * was rather than being overwritten with this device's history.
         */
        internal fun parseRemoteArchive(json: String, myAddress: String): JsonObject {
            val parsed = runCatching { JsonParser.parseString(json) }.getOrNull()
                ?: throw IllegalStateException(
                    "The backup already on the server isn't readable JSON — nothing was uploaded and that file was left untouched. Move it aside (or pick another backup folder) to start a fresh backup."
                )
            val remote = parsed as? JsonObject
                ?: throw IllegalStateException(
                    "The file already on the server isn't a KaChat backup — nothing was uploaded and it was left untouched. Pick a different backup folder."
                )
            if (remote.get("conversations")?.isJsonArray != true) {
                throw IllegalStateException(
                    "The file already on the server isn't a KaChat backup — nothing was uploaded and it was left untouched. Pick a different backup folder."
                )
            }
            val schemaVersion = remote.long("schemaVersion")
            if (schemaVersion != ChatHistoryArchive.CURRENT_SCHEMA_VERSION.toLong()) {
                throw IllegalStateException(
                    "The backup already on the server uses schema version $schemaVersion, which this version can't merge — nothing was uploaded and it was left untouched."
                )
            }
            val remoteWallet = remote.string("walletAddress").trim()
            if (remoteWallet.isNotEmpty() && myAddress.isNotEmpty() && remoteWallet != myAddress) {
                throw IllegalStateException(
                    "The backup already on the server belongs to a different wallet — nothing was uploaded. Choose a separate backup folder for this account."
                )
            }
            return remote
        }

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
         *   * conversation metadata (alias / unreadCount) comes from whichever archive was
         *     exported more recently, and an empty value never overwrites a real one;
         *     `conversationId` keeps the already-published value for stability.
         *
         * CRITICAL: the result starts as a deep copy of the REMOTE object, so every top-level key
         * Android doesn't model — desktop keeps its whole state in an additive `desktopState` key —
         * survives verbatim. Round-tripping through the typed [ChatHistoryArchive] model would
         * silently drop it and wipe desktop's state on the next Android backup. The same applies
         * per-conversation and per-message: unknown keys there are carried through too.
         */
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
                    val unreadCount = conversation.long("unreadCount").coerceAtLeast(0L)

                    var entry = merged[contactAddress]
                    if (entry == null) {
                        entry = ConversationMerge(contactAddress, conversation.deepCopy()).also {
                            it.conversationId = conversationId
                            it.contactAlias = alias
                            it.unreadCount = unreadCount
                            merged[contactAddress] = it
                        }
                    } else {
                        if (alias.isNotEmpty() && (metadataWins || entry.contactAlias.isEmpty())) entry.contactAlias = alias
                        if (conversationId.isNotEmpty() && entry.conversationId.isEmpty()) entry.conversationId = conversationId
                        if (metadataWins) entry.unreadCount = unreadCount
                    }

                    for (messageElement in conversation.array("messages")) {
                        val message = messageElement as? JsonObject ?: continue
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
