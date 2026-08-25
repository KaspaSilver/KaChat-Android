package com.kachat.app.services.database

import androidx.room.*
import com.kachat.app.models.MessageEntity
import com.kachat.app.models.MessageSyncCursorEntity
import com.kachat.app.models.UnreadCount
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE contactId = :contactId AND walletAddress = :walletAddress ORDER BY blockTimestamp ASC")
    fun getMessagesForContact(contactId: String, walletAddress: String): Flow<List<MessageEntity>>

    /** Every message for this wallet, across all contacts — used by chat-history export, not the live UI. */
    @Query("SELECT * FROM messages WHERE walletAddress = :walletAddress ORDER BY blockTimestamp ASC")
    suspend fun getAllMessagesForWallet(walletAddress: String): List<MessageEntity>

    /** One row per contactId (within this wallet) — whichever message has the most recent blockTimestamp. */
    @Query(
        """
        SELECT * FROM messages
        WHERE walletAddress = :walletAddress AND blockTimestamp IN (
            SELECT MAX(blockTimestamp) FROM messages WHERE walletAddress = :walletAddress GROUP BY contactId
        )
        """
    )
    fun getLatestMessagePerContact(walletAddress: String): Flow<List<MessageEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE id = :id AND walletAddress = :walletAddress)")
    suspend fun exists(id: String, walletAddress: String): Boolean

    @Query("SELECT * FROM messages WHERE id = :id AND walletAddress = :walletAddress")
    suspend fun getById(id: String, walletAddress: String): MessageEntity?

    /**
     * Every outgoing row still carrying a provisional local id ("pending_<uuid>", the optimistic
     * placeholder the send flow inserts before the broadcast returns) for this conversation —
     * pending AND failed, since a timed-out broadcast marks the row failed even when the tx
     * actually landed on-chain. Ordered oldest first so an archive row upgrades the earliest
     * matching placeholder. See ChatRepository.findProvisionalOutgoingMatch.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE contactId = :contactId AND walletAddress = :walletAddress
          AND direction = 'sent' AND id LIKE 'pending\_%' ESCAPE '\'
        ORDER BY blockTimestamp ASC
        """
    )
    suspend fun getProvisionalOutgoingForContact(contactId: String, walletAddress: String): List<MessageEntity>

    /** Wallet-wide variant of [getProvisionalOutgoingForContact] — the one-time stuck-pair repair sweep. */
    @Query(
        """
        SELECT * FROM messages
        WHERE walletAddress = :walletAddress
          AND direction = 'sent' AND id LIKE 'pending\_%' ESCAPE '\'
        ORDER BY blockTimestamp ASC
        """
    )
    suspend fun getProvisionalOutgoingForWallet(walletAddress: String): List<MessageEntity>

    /**
     * Whether a DELIVERED copy of the same logical outgoing message already exists in this
     * conversation: same trimmed content (pass [body] pre-trimmed), real (non-provisional) id,
     * delivery status "sent", and a blockTimestamp within [windowMs] of the provisional row's —
     * the send and its confirmed sibling are minutes apart in the same clock domain, so the
     * window keeps an old identical text from being mistaken for the pair. Backs the
     * stuck-provisional repair sweep.
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM messages
            WHERE contactId = :contactId AND walletAddress = :walletAddress
              AND direction = 'sent' AND deliveryStatus = 'sent'
              AND TRIM(plaintextBody) = :body
              AND id NOT LIKE 'pending\_%' ESCAPE '\'
              AND ABS(blockTimestamp - :nearTimestamp) <= :windowMs
        )
        """
    )
    suspend fun hasDeliveredDuplicate(
        contactId: String,
        walletAddress: String,
        body: String,
        nearTimestamp: Long,
        windowMs: Long
    ): Boolean

    /** Whether any message with [direction] ("sent"/"received") exists in this conversation —
     *  backs the payment pool feature's established-conversation check (one of each required). */
    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE contactId = :contactId AND walletAddress = :walletAddress AND direction = :direction)")
    suspend fun hasMessageWithDirection(contactId: String, walletAddress: String, direction: String): Boolean

    /** Rewrites an incoming payment bubble after on-chain verification of a payment_notice —
     *  corrects the amount from chain data or flags it with a warning delivery status. */
    @Query("UPDATE messages SET plaintextBody = :body, amountSompi = :amountSompi, deliveryStatus = :status WHERE id = :id AND walletAddress = :walletAddress")
    suspend fun updatePaymentVerification(id: String, walletAddress: String, body: String, amountSompi: Long, status: String)

    @Query(
        """
        SELECT contactId, COUNT(*) as count FROM messages
        WHERE isRead = 0 AND direction = 'received' AND walletAddress = :walletAddress
        GROUP BY contactId
        """
    )
    fun getUnreadCounts(walletAddress: String): Flow<List<UnreadCount>>

    @Query("UPDATE messages SET isRead = 1 WHERE contactId = :contactId AND walletAddress = :walletAddress AND isRead = 0")
    suspend fun markAllAsRead(contactId: String, walletAddress: String)

    /** Chat-list "mark as unread" (swipe/bulk action) — flips just the single latest received
     * message back to unread, which is enough to make the contact reappear with a nonzero badge
     * in [getUnreadCounts] (a derived COUNT), without needing a separate stored unread flag. */
    @Query(
        """
        UPDATE messages SET isRead = 0 WHERE id = (
            SELECT id FROM messages WHERE contactId = :contactId AND walletAddress = :walletAddress AND direction = 'received'
            ORDER BY blockTimestamp DESC LIMIT 1
        )
        """
    )
    suspend fun markLatestAsUnread(contactId: String, walletAddress: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query("UPDATE messages SET deliveryStatus = :status WHERE id = :id AND walletAddress = :walletAddress")
    suspend fun updateStatus(id: String, walletAddress: String, status: String)

    @Query("DELETE FROM messages WHERE id = :id AND walletAddress = :walletAddress")
    suspend fun deleteById(id: String, walletAddress: String)

    /** "Wipe and re-sync incoming messages" — sent messages, contacts, and the wallet itself are untouched. */
    @Query("DELETE FROM messages WHERE walletAddress = :walletAddress AND direction = 'received'")
    suspend fun deleteReceivedForWallet(walletAddress: String)

    /** Scoped variant of [deleteReceivedForWallet] — only the selected conversations' received messages. */
    @Query("DELETE FROM messages WHERE walletAddress = :walletAddress AND direction = 'received' AND contactId IN (:contactIds)")
    suspend fun deleteReceivedForContacts(walletAddress: String, contactIds: List<String>)

    /** Post-resync count for the success summary ("Re-synced N messages"). */
    @Query("SELECT COUNT(*) FROM messages WHERE walletAddress = :walletAddress AND direction = 'received'")
    suspend fun countReceivedForWallet(walletAddress: String): Int

    /** Scoped variant of [countReceivedForWallet] for a chat-scoped resync. */
    @Query("SELECT COUNT(*) FROM messages WHERE walletAddress = :walletAddress AND direction = 'received' AND contactId IN (:contactIds)")
    suspend fun countReceivedForContacts(walletAddress: String, contactIds: List<String>): Int

    /** Every message with one specific contact, gone — used by ChatRepository.deleteChat's full-removal flow. */
    @Query("DELETE FROM messages WHERE contactId = :contactId AND walletAddress = :walletAddress")
    suspend fun deleteAllForContact(contactId: String, walletAddress: String)

    /**
     * The latest blockTimestamp already seen for this contact, if any — read by ChatRepository.deleteChat
     * BEFORE deleting their messages, so the resulting tombstone's cutoff lives in the indexer's
     * block_time clock domain rather than the device's wall clock. See DeletedContactEntity's doc comment.
     */
    @Query("SELECT MAX(blockTimestamp) FROM messages WHERE contactId = :contactId AND walletAddress = :walletAddress")
    suspend fun getMaxBlockTimestampForContact(contactId: String, walletAddress: String): Long?

    /**
     * Every message id sharing this contact's exact latest blockTimestamp — read alongside
     * [getMaxBlockTimestampForContact] by ChatRepository.deleteChat to build the tombstone's
     * [com.kachat.app.models.DeletedContactEntity.deletedAtTxIds] tie-breaker set. See that field's
     * doc comment for why an exact-timestamp match alone isn't a safe "this is the old tx" signal.
     */
    @Query("SELECT id FROM messages WHERE contactId = :contactId AND walletAddress = :walletAddress AND blockTimestamp = :blockTimestamp")
    suspend fun getMessageIdsAtBlockTimestamp(contactId: String, walletAddress: String, blockTimestamp: Long): List<String>

    /** Every message for this wallet, gone — used when wiping an entire account. */
    @Query("DELETE FROM messages WHERE walletAddress = :walletAddress")
    suspend fun deleteAllForWallet(walletAddress: String)

    /** Backup retention pruning — permanently deletes messages older than [cutoffMillis] for this wallet. */
    @Query("DELETE FROM messages WHERE walletAddress = :walletAddress AND blockTimestamp < :cutoffMillis")
    suspend fun deleteOlderThan(walletAddress: String, cutoffMillis: Long)

    @Query("DELETE FROM messages")
    suspend fun clearAll()

    /** How far into this (contact, alias) message stream we've already synced — see [MessageSyncCursorEntity]. */
    @Query("SELECT lastBlockTime FROM message_sync_cursors WHERE contactId = :contactId AND walletAddress = :walletAddress AND aliasHex = :aliasHex")
    suspend fun getMessageSyncCursor(contactId: String, walletAddress: String, aliasHex: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setMessageSyncCursor(cursor: MessageSyncCursorEntity)

    /** Resets every per-contact sync cursor for this wallet — used by "wipe and re-sync" so it actually re-fetches full history again instead of picking up where the (now-deleted) cache left off. */
    @Query("DELETE FROM message_sync_cursors WHERE walletAddress = :walletAddress")
    suspend fun deleteSyncCursorsForWallet(walletAddress: String)

    /** Clears this one contact's sync cursors — used by ChatRepository.deleteChat so a later re-handshake with the same address starts its indexer sync clean instead of resuming from a stale cursor left over from before the deletion. */
    @Query("DELETE FROM message_sync_cursors WHERE contactId = :contactId AND walletAddress = :walletAddress")
    suspend fun deleteSyncCursorsForContact(contactId: String, walletAddress: String)

    /** Multi-contact variant of [deleteSyncCursorsForContact] — a chat-scoped "wipe and re-sync" resets only the selected conversations' cursors so only their history re-fetches. */
    @Query("DELETE FROM message_sync_cursors WHERE walletAddress = :walletAddress AND contactId IN (:contactIds)")
    suspend fun deleteSyncCursorsForContacts(walletAddress: String, contactIds: List<String>)
}
