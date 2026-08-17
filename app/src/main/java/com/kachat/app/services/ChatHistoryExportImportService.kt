package com.kachat.app.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.kachat.app.models.ChatHistoryArchive
import com.kachat.app.models.ChatHistoryArchiveConversation
import com.kachat.app.models.ChatHistoryArchiveMessage
import com.kachat.app.models.ContactEntity
import com.kachat.app.models.MessageEntity
import com.kachat.app.repository.ChatRepository
import com.kachat.app.util.MessageProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
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
 */
@Singleton
class ChatHistoryExportImportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chatRepository: ChatRepository,
    private val walletManager: WalletManager
) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    data class ImportResult(val importedMessageCount: Int, val conversationCount: Int)

    /**
     * Builds the archive JSON for the active account — the shared payload for every backup
     * transport (local file share, Google Drive, ...). Callers that need a shareable file use
     * [exportChatHistory]; callers that just need the bytes (e.g. a Drive upload) call this
     * directly.
     */
    suspend fun buildArchiveJson(): String {
        val myAddress = walletManager.getAddress()
        val contactsById = chatRepository.getContacts().first().associateBy { it.id }
        val messages = chatRepository.getAllMessages()

        val conversations = messages
            .groupBy { it.contactId }
            .mapNotNull { (contactId, contactMessages) ->
                // Pending placeholders are transient local-only state, not confirmed history.
                val exportable = contactMessages.filter { it.deliveryStatus != "pending" }
                if (exportable.isEmpty()) return@mapNotNull null
                ChatHistoryArchiveConversation(
                    contactAddress = contactId,
                    contactAlias = contactsById[contactId]?.alias,
                    contactPhoto = contactPhotoForArchive(contactsById[contactId]),
                    unreadCount = exportable.count { it.direction == "received" && !it.isRead },
                    messages = exportable.map { toArchiveMessage(it, myAddress) }
                )
            }

        val archive = ChatHistoryArchive(
            exportedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            walletAddress = myAddress,
            conversations = conversations
        )
        return gson.toJson(archive)
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
        val fileTimestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(":", "-")
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

        for (conversation in archive.conversations) {
            if (conversation.messages.isEmpty()) continue
            val contactAddress = conversation.contactAddress

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
                val entity = toMessageEntity(archiveMessage, contactAddress, myAddress)
                if (chatRepository.messageExists(entity.id)) continue
                chatRepository.insertMessage(entity)
                importedCount++
                addedAny = true
            }
            if (addedAny) conversationCount++
        }

        return ImportResult(importedMessageCount = importedCount, conversationCount = conversationCount)
    }

    companion object {
        private val VALID_DELIVERY_STATUSES = setOf("pending", "sent", "failed", "warning")

        internal fun archiveMessageType(entityType: String): String = when (entityType) {
            MessageProtocol.TYPE_HANDSHAKE -> "handshake"
            MessageProtocol.TYPE_COMM -> "contextual"
            MessageProtocol.TYPE_PAY -> "payment"
            else -> entityType
        }

        /** Android has no distinct "audio message" type yet — those import as a regular contextual message. */
        internal fun entityMessageType(archiveType: String): String = when (archiveType) {
            "handshake" -> MessageProtocol.TYPE_HANDSHAKE
            "contextual" -> MessageProtocol.TYPE_COMM
            "payment" -> MessageProtocol.TYPE_PAY
            "audio" -> MessageProtocol.TYPE_COMM
            else -> MessageProtocol.TYPE_COMM
        }

        internal fun toArchiveMessage(entity: MessageEntity, myAddress: String): ChatHistoryArchiveMessage {
            val isOutgoing = entity.direction == "sent"
            return ChatHistoryArchiveMessage(
                id = entity.id,
                txId = entity.id,
                senderAddress = if (isOutgoing) myAddress else entity.contactId,
                receiverAddress = if (isOutgoing) entity.contactId else myAddress,
                content = entity.plaintextBody ?: "",
                timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(entity.blockTimestamp)),
                blockTime = entity.blockTimestamp,
                isOutgoing = isOutgoing,
                messageType = archiveMessageType(entity.type),
                deliveryStatus = entity.deliveryStatus
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
    }
}
