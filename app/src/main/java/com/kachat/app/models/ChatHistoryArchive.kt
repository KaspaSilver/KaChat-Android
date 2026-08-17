package com.kachat.app.models

/**
 * Portable chat-history export/import format — field names deliberately match iOS's
 * `ChatHistoryArchive`/`ChatHistoryArchiveConversation`/`ChatMessage` JSON exactly
 * (`ChatService+Decryption.swift:315-328`, `Models.swift:245-283`), so a file exported from
 * one platform can be imported on the other. Already-decrypted plaintext, not re-encrypted —
 * matches iOS; this file is not safe to share outside a trusted transfer.
 */
data class ChatHistoryArchive(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val exportedAt: String,
    val walletAddress: String?,
    val conversations: List<ChatHistoryArchiveConversation>
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

data class ChatHistoryArchiveConversation(
    val conversationId: String? = null,
    val contactAddress: String,
    val contactAlias: String?,
    // Cross-platform base64 JPEG contact photo (optional; older archives omit it).
    val contactPhoto: String? = null,
    val unreadCount: Int,
    val messages: List<ChatHistoryArchiveMessage>
)

data class ChatHistoryArchiveMessage(
    val id: String,
    val txId: String,
    val senderAddress: String,
    val receiverAddress: String,
    val content: String,
    val timestamp: String,
    val blockTime: Long,
    val acceptingBlock: String? = null,
    val isOutgoing: Boolean,
    val messageType: String,   // "handshake" | "contextual" | "payment" | "audio"
    val deliveryStatus: String // "pending" | "sent" | "failed" | "warning"
)
