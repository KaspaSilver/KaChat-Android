package com.kachat.app.models

import androidx.room.Entity

/**
 * Message stored locally in Room.
 *
 * Maps to the ciph_msg protocol payloads:
 *   - type = "handshake" → handshake
 *   - type = "comm"      → contextual message
 *   - type = "pay"       → payment with memo
 *
 * Primary key is (id, walletAddress) rather than just id — a transaction id is unique
 * on-chain, but if two of the user's own accounts are ever both party to the same tx (e.g.
 * sending between two of their own wallets), each account still needs its own independent
 * row (different direction/plaintext framing) instead of one clobbering the other.
 */
@Entity(tableName = "messages", primaryKeys = ["id", "walletAddress"])
data class MessageEntity(
    val id: String,                         // Kaspa transaction ID
    val contactId: String,                  // Foreign key → ContactEntity.id
    val walletAddress: String,              // Which wallet this belongs to
    val type: String,                       // "handshake" | "comm" | "pay"
    val direction: String,                  // "sent" | "received"
    val plaintextBody: String?,             // Decrypted message text (null if not yet decrypted)
    val encryptedPayload: String,           // Raw ciph_msg payload from chain
    val amountSompi: Long?,                 // For "pay" type: amount in sompi (1 KAS = 1e8 sompi)
    val blockTimestamp: Long,               // Block time in epoch ms
    val isRead: Boolean = false,
    val syncedAt: Long = System.currentTimeMillis(),
    val deliveryStatus: String = "sent"     // "pending" | "sent" | "failed" — only meaningful for direction="sent"
) {
    /** See [isSentPlaceholder]. */
    val isSentPlaceholder: Boolean
        get() = isSentPlaceholder(plaintextBody)

    companion object {
        /**
         * Exact content of the cross-device fill-in row iOS creates when a device discovers this
         * wallet's own outgoing message on-chain but cannot decrypt it (own sends are encrypted
         * for the recipient). Android never creates these itself, but archives written by iOS can
         * carry them, and builds before the import-time skip (see
         * ChatHistoryExportImportService.importArchive) inserted them as real rows. They must
         * NEVER be visible in any UI. Mirrors iOS's ChatMessage.sentViaOtherDevicePlaceholder -
         * single source of truth for the literal; do not duplicate the string.
         */
        const val SENT_VIA_OTHER_DEVICE_PLACEHOLDER = "📤 Sent via another device"

        /** True if [content] is exactly the cross-device placeholder above. Use this everywhere
         *  the placeholder is matched or hidden - mirrors iOS's ChatMessage.isSentPlaceholder. */
        fun isSentPlaceholder(content: String?): Boolean = content == SENT_VIA_OTHER_DEVICE_PLACEHOLDER

        /**
         * Prefix of the synthetic local id the optimistic send paths give a row BEFORE the
         * broadcast returns ("pending_<uuid>", see ChatViewModel.sendMessageAwait/sendPayment).
         * Such an id is transient device-local state, never an on-chain identity: it must never
         * be exported into a shared backup archive, never be accepted from one at import, and is
         * what the stuck-send repair sweep looks for. Single source of truth for the literal.
         */
        const val PROVISIONAL_ID_PREFIX = "pending_"

        /** True if [id] is a synthetic pre-broadcast placeholder id rather than a real txId. */
        fun isProvisionalId(id: String): Boolean = id.startsWith(PROVISIONAL_ID_PREFIX)
    }
}

/**
 * A reaction (tapback) sent or received on a message — 1:1 ([contactId] set) or group ([groupId]
 * set), never both. One row per (message, reactor): picking a new emoji replaces the previous
 * row's [emoji] rather than adding a second row, and removing a reaction deletes the row outright
 * (see [com.kachat.app.services.database.ReactionDao]). [targetTxId] is the reacted-to message's
 * transaction id — the only identifier both parties/platforms agree on, since a local row id
 * isn't shared cross-platform. [reactionTxId] is the reaction message's own transaction id, kept
 * for reference/debugging (not used for dedup — the primary key already prevents duplicates).
 */
@Entity(tableName = "reactions", primaryKeys = ["targetTxId", "walletAddress", "reactorAddress"])
data class ReactionEntity(
    val targetTxId: String,
    val walletAddress: String,
    val reactorAddress: String,
    val emoji: String,
    val reactionTxId: String?,
    val blockTimestamp: Long,
    val contactId: String? = null,
    val groupId: String? = null,
    // Send status of the local user's own reaction: "sent" = delivered (also the value for every
    // received reaction), "failed" = the reaction tx never sent. Drives the error icon on the pill
    // and the Retry under the message.
    val deliveryStatus: String = "sent",
    // When "failed", whether the failed change was an "add" or "remove" — so Retry re-attempts the
    // correct action.
    val failedAction: String? = null
)

/**
 * Tracks how far into one contact's `contextual-messages/by-sender` stream this wallet has
 * already synced, per alias (a contact may be messaging under more than one — see
 * `ChatRepository.syncContextualMessages`'s legacy/deterministic alias loop). The indexer's
 * `block_time` query param lets a sync only ask for what's genuinely new since [lastBlockTime]
 * instead of re-fetching the same recent window every time — this is that cursor, persisted so it
 * survives process death. Safe to advance even if the indexer's block_time boundary is inclusive
 * (returns the same last item again): callers already dedup by txId against local storage.
 */
@Entity(tableName = "message_sync_cursors", primaryKeys = ["contactId", "walletAddress", "aliasHex"])
data class MessageSyncCursorEntity(
    val contactId: String,
    val walletAddress: String,
    val aliasHex: String,
    val lastBlockTime: Long
)

/**
 * Contact stored locally.
 * Matches the iOS contact model with alias and KNS support.
 *
 * Primary key is (id, walletAddress) rather than just id — the same third-party address can
 * legitimately be a contact under more than one of the user's own accounts, each with its own
 * independent alias/handshake state, not one shared/overwritten row.
 */
@Entity(tableName = "contacts", primaryKeys = ["id", "walletAddress"])
data class ContactEntity(
    val id: String,                         // Kaspa address (kaspa:q...)
    val walletAddress: String,              // Which of the user's own accounts this contact belongs to
    val alias: String?,                     // User-given nickname
    val knsName: String?,                   // KNS domain e.g. "alice.kas"
    val publicKeyHex: String?,              // Secp256k1 public key (after handshake)
    val handshakeComplete: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    val conversationStatus: String = "active", // "pending" | "active" | "rejected"
    val theirAlias: String? = null,         // Alias THEY sent us in their handshake — required to query their self-stashed messages
    val myAlias: String? = null,            // OUR protocol alias for THIS contact — 12 lowercase hex chars, NOT our display name (see WalletService.generateAlias)
    val knsAvatarUrl: String? = null,       // Cached from the KNS profile of `knsName`, so the chat list can render an avatar without a live fetch per row
    val systemContactId: String? = null,    // Phone contact's LOOKUP_KEY, once linked via "Link from Contacts" — takes priority over KNS auto-rename
    val systemContactName: String? = null,  // Name snapshot at link time, for the "Linked: X" row
    val systemContactPhotoUri: String? = null, // Device address-book photo (content:// URI) of the linked phone contact — the fallback every avatar uses when there's no KNS avatar. Stored (not resolved per-render) so the chat list never touches ContactsContract on the main thread; refreshed by ChatViewModel.syncSystemContacts.
    val systemContactLinkSource: String? = null, // "manual" | "autoCreated" — only "autoCreated" shadow contacts get deleted if Autocreate is turned off
    val photoAutoDisplayOverride: String? = null, // PhotoAutoDisplayMode.name, null = automatic (see ChatRepository.shouldAutoDisplayPhotos)
    val notificationOverride: String? = null, // ContactNotificationMode.name, null = follow Settings > Notifications (see NotificationHelper.show)
    val backupPhotoBase64: String? = null // Base64 JPEG carried in the cross-platform backup; avatar fallback when there is no KNS or system-contact photo (e.g. a photo set on desktop)
)

/**
 * What to call a contact: their own alias first, then the KNS domain, then the shortened address.
 *
 * The chat list and thread header used `alias ?: shortDisplay(id)` and never looked at [knsName],
 * so a contact added BY domain - which stores the domain at creation - still showed as an address
 * until some later path happened to copy it into [alias]. The name was already there; nothing was
 * reading it.
 */
val ContactEntity.displayName: String
    get() = alias?.takeIf { it.isNotBlank() }
        ?: knsName?.takeIf { it.isNotBlank() }
        ?: com.kachat.app.util.KaspaAddress.shortDisplay(id)

/** Avatar initial source - same order, but the raw tail rather than the formatted short address. */
val ContactEntity.avatarFallbackText: String
    get() = alias?.takeIf { it.isNotBlank() }
        ?: knsName?.takeIf { it.isNotBlank() }
        ?: id.takeLast(8)


/**
 * A tombstone marking that [contactId] was deleted (by this wallet), keyed by [deletedAt] —
 * survives the contact's own row being deleted, unlike the old "archive" flag. `syncContextualMessages`/
 * `processHandshake`/`processPayment` check this (via `ChatRepository.isTombstoned`) before ever
 * re-inserting a message or recreating a contact, so a full re-sync of that sender's on-chain
 * history (which the indexer/REST API always returns in full, not just "since last seen" —
 * `syncPayments` in particular has no per-contact cursor and re-fetches the same recent
 * transactions on every ~2s poll) can't silently resurrect a deleted conversation. A genuinely
 * new handshake/message/payment sent *after* [deletedAt] still creates a fresh contact/conversation
 * normally.
 *
 * [deletedAt] MUST be in the indexer's block_time clock domain (the max blockTimestamp already
 * seen for this contact — see ChatRepository.deleteChat), NOT the device's wall-clock time. Using
 * wall-clock time here caused a real bug: reject a handshake, then immediately re-handshake the
 * same person, and the brand new (legitimately later) handshake/reply could still get silently
 * filtered out if the device clock was even slightly ahead of the indexer's block_time — the two
 * clocks aren't the same and aren't guaranteed to agree on ordering close to the cutoff.
 *
 * [deletedAtTxIds] — comma-joined transaction/message ids that had `blockTimestamp == deletedAt`
 * at the moment of deletion (usually just one). Needed because a plain `blockTime <= deletedAt`
 * comparison isn't safe on its own: Kaspa's DAG-based block_time isn't strictly monotonic per
 * sender, so a genuinely new, different transaction sent shortly after deletion can legitimately
 * land at the exact same block_time as the tombstoned one and would otherwise be wrongly filtered
 * out forever. `isTombstoned` only treats an exact `blockTime == deletedAt` match as "old" when the
 * transaction id is also one of these — a different id at that same timestamp is treated as new.
 */
@Entity(tableName = "deleted_contacts", primaryKeys = ["contactId", "walletAddress"])
data class DeletedContactEntity(
    val contactId: String,
    val walletAddress: String,
    val deletedAt: Long = System.currentTimeMillis(),
    val deletedAtTxIds: String = ""
)

/**
 * In-memory model for a conversation (not persisted directly — derived from messages).
 */
data class Conversation(
    val contact: ContactEntity,
    val lastMessage: MessageEntity?,
    val unreadCount: Int
)

/** Room query projection — one row per contact with at least one unread received message. */
data class UnreadCount(
    val contactId: String,
    val count: Int
)

/**
 * Inner JSON plaintext of a "handshake" ciph_msg payload, encrypted before transmission.
 * Field names match the iOS `HandshakePayload` struct so a real KaChat iOS user can decode it.
 */
data class HandshakePayload(
    val type: String = "handshake",
    val alias: String?,
    val timestamp: Long,
    val conversationId: String?,
    val version: Int = 1,
    val recipientAddress: String?,
    val sendToRecipient: Boolean = true,
    val isResponse: Boolean? = null,
    val theirAlias: String? = null // Real Kasia web client's field, used on a response to confirm both sides' aliases
)

/**
 * A KNS commit transaction that broadcast successfully but whose reveal hasn't completed yet —
 * persisted the moment commit succeeds, cleared the moment reveal succeeds, so a crash or failure
 * between the two doesn't strand the commit amount with no way to recover it. iOS has no
 * equivalent safety net; this is intentionally more careful given real KAS is on the line.
 */
data class PendingKnsCommit(
    val commitTxId: String,
    val redeemScriptHex: String,
    val commitScriptPubKeyHex: String,
    val commitAmountSompi: Long,
    val revealAmountSompi: Long,
    val revealTargetAddress: String,
    val operationType: String, // "domain" | "profile" — for the recovery prompt's wording only
    // Nullable so Gson leaves it null (falls back to the identity address) when deserializing a
    // commit persisted before this field existed, rather than failing to parse it entirely.
    val changeAddress: String? = null
)

/**
 * A user-saved "host:port" node address, kept purely for quick copy/paste into the
 * trusted-node field in Connection Settings - not itself used for connections.
 */
data class SavedNodeAddress(
    val id: String = java.util.UUID.randomUUID().toString(),
    val label: String,
    val address: String
)
