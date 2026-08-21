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
    val conversations: List<ChatHistoryArchiveConversation>,
    // Cross-platform group key material (optional; older archives omit it). Carries the full
    // bag - including the admin's groupSeed - so another device of the same account recovers
    // admin groups that have no on-chain invite addressed to it.
    val groups: List<ChatHistoryArchiveGroup>? = null,
    // Deletion tombstones (optional; older archives omit it): addresses whose chats the user
    // deleted. A restore - local file, Google Drive, or Nextcloud - must never resurrect
    // them, even on a fresh install with no local tombstones. Field name matches iOS.
    val deletedContactAddresses: List<String>? = null
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

// Field names match the desktop/iOS archive schema exactly. deviceId/msgCounter are per-device
// and deliberately NOT carried (the importer mints its own).
data class ChatHistoryArchiveGroup(
    val groupId: String,
    val name: String,
    val isAdmin: Boolean,
    val adminAddress: String?,
    val adminSigningPub: String?,
    val groupSeed: String?,
    val groupRootEpoch: String?,
    val blindingKey: String?,
    val currentEpoch: Long,
    val members: List<ChatHistoryArchiveGroupMember>,
    // Decrypted message history so it survives even if the indexer has pruned old messages
    // (older archives omit it). Cross-platform shape shared with desktop/iOS.
    val messages: List<ChatHistoryArchiveGroupMessage>? = null
)

data class ChatHistoryArchiveGroupMessage(
    val msgIdHex: String?,
    val txId: String?,
    val senderAddress: String?,
    val senderIdHex: String?,
    val content: String,     // decrypted plaintext
    val blockTime: Long,
    val isOutgoing: Boolean
)

data class ChatHistoryArchiveGroupMember(
    val address: String,
    val xOnlyPubKeyHex: String?,
    val isAdmin: Boolean
)

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
