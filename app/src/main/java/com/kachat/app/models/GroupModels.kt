package com.kachat.app.models

import androidx.room.Entity
import androidx.room.Index

/**
 * Group chat metadata - non-secret. Secret key material (group seed/root epoch/blinding key/
 * device id/msg counter) lives in [com.kachat.app.services.GroupSecretStore] (Keystore-backed
 * EncryptedSharedPreferences), never in Room - mirrors how [MessageEntity] stores only encrypted
 * payloads while the wallet's own private key lives in WalletManager's own encrypted prefs, not
 * the database.
 *
 * Primary key is (groupId, walletAddress), same reasoning as [ContactEntity]/[MessageEntity]:
 * more than one local account could theoretically be a member of the same group.
 */
@Entity(tableName = "groups", primaryKeys = ["groupId", "walletAddress"])
data class GroupEntity(
    val groupId: String,               // hex
    val walletAddress: String,         // which local account this group belongs to
    val name: String,
    val adminAddress: String,
    val adminXOnlyPubKeyHex: String,
    val currentEpoch: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val isAdmin: Boolean,
    val membersJson: String,           // JSON-encoded List<GroupMember>, see GroupRepository
    /** Null until the group's thread has been opened at least once - drives the Group Chats tab
     * badge (unread = messages newer than this, or the group itself if never opened at all and
     * we're not the one who created it). Set via a dedicated UPDATE query (markGroupRead), not
     * upsertGroup's REPLACE - upsertGroup call sites use entity.copy() for existing groups, so
     * this is naturally preserved across roster/epoch updates and naturally null for new groups. */
    val lastReadAt: Long? = null,
    /** Hex of the admin-set group photo (compressed JPEG), distributed to all members via
     * gctl_photo. Null = no photo. Preserved across roster/epoch updates like lastReadAt. */
    val photoHex: String? = null
)

/**
 * A member of a group chat - embedded as JSON inside [GroupEntity.membersJson] rather than its
 * own table: rosters are small and always read/written as a whole unit together (a membership
 * change already means rewriting the whole roster + rotating the epoch).
 */
data class GroupMember(
    val address: String,
    val xOnlyPubKeyHex: String,
    val isAdmin: Boolean,
    val displayName: String? = null
)

/**
 * A group chat message - content stored as raw gcomm ciphertext (hex), NOT plaintext, decrypted
 * on read using the group's Keystore-held root key (see GroupRepository). Same posture as
 * [MessageEntity.encryptedPayload] - compromising this database alone doesn't reveal content.
 */
// Same story as `messages`: the group list and every thread read filter on
// (walletAddress, groupId) and order by blockTimestamp, none of which the (txId, walletAddress)
// primary key can serve.
@Entity(
    tableName = "group_messages",
    primaryKeys = ["txId", "walletAddress"],
    indices = [Index(value = ["walletAddress", "groupId", "blockTimestamp"])]
)
data class GroupMessageEntity(
    val txId: String,                  // Kaspa transaction ID, or a synthetic "pending_<uuid>" while a send is in flight
    val walletAddress: String,
    val groupId: String,
    val senderAddress: String?,        // resolved from senderId against the roster; null if unknown
    val senderIdHex: String,
    val epoch: Long,
    val msgIdHex: String,
    val contentEncryptedHex: String,
    val blockTimestamp: Long,
    val isOutgoing: Boolean,
    val deliveryStatus: String = "sent" // "pending" | "sent" | "failed"
)

/**
 * How far into one group catch-up sync object's indexer stream this wallet has already synced -
 * applied to group chat's three sync object shapes: `gcomm|<groupId>|<blindedGroupIdHex>`
 * (queried once per known member, since `blinded_group_id` is per-sender not per-group - see
 * GroupCipher's protocol notes), `gctl|<adminAddressLowercased>` (queried once per group's admin
 * address, for groups already joined), and `gctl-recipient|<walletAddressLowercased>`
 * (recipient-addressed controls - the only path that discovers "you were added to a group"
 * without already knowing an admin address, see GroupRepository.syncGroupControlByRecipient).
 * [syncKey] folds all three shapes into one disjoint string key rather than a separate table,
 * since none maps cleanly onto (contactId, aliasHex).
 *
 * [cursor] is the indexer's opaque lossless pagination cursor (unlike a plain `block_time`, which
 * can collide across items sharing a timestamp) - see docs/GROUP_CHAT_API.md. `lastBlockTime` is
 * unused as of the cursor-based rewrite but the column is left in place rather than dropped
 * (nothing was ever shipped against the old block_time-only shape).
 */
@Entity(tableName = "group_sync_cursors", primaryKeys = ["syncKey", "walletAddress"])
data class GroupSyncCursorEntity(
    val syncKey: String,
    val walletAddress: String,
    val lastBlockTime: Long = 0L,
    val cursor: String? = null
)
