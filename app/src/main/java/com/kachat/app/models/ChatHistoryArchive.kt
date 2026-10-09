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
    // deleted. A restore - local file or Nextcloud - must never resurrect
    // them, even on a fresh install with no local tombstones. Field name matches iOS.
    val deletedContactAddresses: List<String>? = null,
    // Address Book (optional; older archives omit it): this wallet's saved addresses, and the
    // deleted ones so a merge or restore never brings them back. NEXTCLOUD_SYNC.md §5, iOS 00767a4.
    val addressBook: List<com.kachat.app.services.ArchiveAddressBookEntry>? = null,
    val addressBookDeleted: List<com.kachat.app.services.ArchiveAddressBookTombstone>? = null,
    // Portfolios (optional; older archives omit them): this wallet's portfolio list, ledger rows,
    // recorded fees and deletions - per item the newest edit or deletion wins
    // (com.kachat.app.services.PortfolioSync). NEXTCLOUD_SYNC.md section 5, iOS 11f1548. Kept as
    // raw JSON and read one element at a time, so one element this build can't read never takes
    // the whole archive down.
    val portfolios: com.google.gson.JsonElement? = null,
    val portfolioTransactions: com.google.gson.JsonElement? = null,
    val portfolioFees: com.google.gson.JsonElement? = null,
    val portfolioDeleted: com.google.gson.JsonElement? = null
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
    // Roots for epochs the group has already left, keyed by decimal epoch. Optional so older
    // archives (and platforms that predate the field) still parse. Without these a NON-ADMIN
    // member restoring onto a device that holds no bag can decrypt only the current epoch, and
    // everything older reads as an empty thread. Cross-platform field.
    val previousRoots: Map<Long, String>? = null,
    val members: List<ChatHistoryArchiveGroupMember>,
    // Decrypted message history so it survives even if the indexer has pruned old messages
    // (older archives omit it). Cross-platform shape shared with desktop/iOS.
    val messages: List<ChatHistoryArchiveGroupMessage>? = null,
    // Admin-set group photo (hex of a compressed JPEG); null = none. Cross-platform field.
    val photo: String? = null
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
