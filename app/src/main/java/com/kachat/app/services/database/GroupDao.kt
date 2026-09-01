package com.kachat.app.services.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kachat.app.models.GroupEntity
import com.kachat.app.models.GroupMessageEntity
import com.kachat.app.models.GroupSyncCursorEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {

    @Query("SELECT * FROM groups WHERE walletAddress = :walletAddress ORDER BY createdAt ASC")
    fun getGroups(walletAddress: String): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups WHERE walletAddress = :walletAddress ORDER BY createdAt ASC")
    suspend fun getGroupsOnce(walletAddress: String): List<GroupEntity>

    @Query("SELECT * FROM groups WHERE groupId = :groupId AND walletAddress = :walletAddress LIMIT 1")
    suspend fun getGroup(groupId: String, walletAddress: String): GroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroup(group: GroupEntity)

    /** Marks a group's thread as read as of now - backs the Group Chats tab's unread badge. */
    @Query("UPDATE groups SET lastReadAt = :timestamp WHERE groupId = :groupId AND walletAddress = :walletAddress")
    suspend fun markGroupRead(groupId: String, walletAddress: String, timestamp: Long)

    /** Forces a group back to "unread" - clears lastReadAt to NULL, same state as never opened. */
    @Query("UPDATE groups SET lastReadAt = NULL WHERE groupId = :groupId AND walletAddress = :walletAddress")
    suspend fun markGroupUnread(groupId: String, walletAddress: String)

    @Query("DELETE FROM groups WHERE groupId = :groupId AND walletAddress = :walletAddress")
    suspend fun deleteGroup(groupId: String, walletAddress: String)

    @Query("DELETE FROM group_messages WHERE groupId = :groupId AND walletAddress = :walletAddress")
    suspend fun deleteMessagesForGroup(groupId: String, walletAddress: String)

    @Query("SELECT * FROM group_messages WHERE groupId = :groupId AND walletAddress = :walletAddress ORDER BY blockTimestamp ASC")
    fun getMessages(groupId: String, walletAddress: String): Flow<List<GroupMessageEntity>>

    /** One-shot variant for backup export (no Flow subscription). */
    @Query("SELECT * FROM group_messages WHERE groupId = :groupId AND walletAddress = :walletAddress ORDER BY blockTimestamp ASC")
    suspend fun getMessagesOnce(groupId: String, walletAddress: String): List<GroupMessageEntity>

    /** Returns the Room-generated row id, or -1 if the insert was ignored as a duplicate (same txId+walletAddress already present) — lets callers detect "was this genuinely new" without a separate existence query. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: GroupMessageEntity): Long

    /** One message by tx id — backs the reaction notification's "does this reaction target one of OUR messages?" check. */
    @Query("SELECT * FROM group_messages WHERE txId = :txId AND walletAddress = :walletAddress LIMIT 1")
    suspend fun getMessage(txId: String, walletAddress: String): GroupMessageEntity?

    /** Existence check by tx id — see GroupRepository.isGroupTxIngested (FCM fallback decision). */
    @Query("SELECT COUNT(*) FROM group_messages WHERE txId = :txId AND walletAddress = :walletAddress")
    suspend fun countMessagesByTxId(txId: String, walletAddress: String): Int

    /** Removes a single message by id — used to drop a "pending_<uuid>" placeholder once its real send resolves. */
    @Query("DELETE FROM group_messages WHERE txId = :txId AND walletAddress = :walletAddress")
    suspend fun deleteMessage(txId: String, walletAddress: String)

    @Query("UPDATE group_messages SET deliveryStatus = :status WHERE txId = :txId AND walletAddress = :walletAddress")
    suspend fun updateMessageStatus(txId: String, walletAddress: String, status: String)

    @Query("DELETE FROM groups WHERE walletAddress = :walletAddress")
    suspend fun deleteAllGroups(walletAddress: String)

    @Query("DELETE FROM group_messages WHERE walletAddress = :walletAddress")
    suspend fun deleteAllMessages(walletAddress: String)

    /** How far into this group catch-up sync object's stream we've already synced — see [GroupSyncCursorEntity]. */
    @Query("SELECT cursor FROM group_sync_cursors WHERE syncKey = :syncKey AND walletAddress = :walletAddress")
    suspend fun getGroupSyncCursor(syncKey: String, walletAddress: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setGroupSyncCursor(cursor: GroupSyncCursorEntity)

    @Query("DELETE FROM group_sync_cursors WHERE walletAddress = :walletAddress")
    suspend fun deleteGroupSyncCursorsForWallet(walletAddress: String)

    /** Drops one group's cursors so its streams are walked from the start - see forceRefreshGroup. */
    @Query("DELETE FROM group_sync_cursors WHERE walletAddress = :walletAddress AND syncKey LIKE :prefix || '%'")
    suspend fun deleteGroupSyncCursorsWithPrefix(walletAddress: String, prefix: String)
}
