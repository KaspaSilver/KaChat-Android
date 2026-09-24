package com.kachat.app.services.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kachat.app.models.MessageEditEntity
import kotlinx.coroutines.flow.Flow

/**
 * Message edits, stored beside reactions and applied when a bubble is drawn — see
 * [com.kachat.app.models.MessageEditEntity]. Deliberately shaped like [ReactionDao]: one upsert,
 * per-conversation flows the screens collect, and the same per-contact/per-group/per-wallet wipes
 * the reaction rows get when a chat or account is deleted.
 */
@Dao
interface MessageEditDao {

    /** Replaces the stored edit for (targetTxId, walletAddress) — the newest edit is the only one kept. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEdit(edit: MessageEditEntity)

    @Query("SELECT * FROM message_edits WHERE targetTxId = :targetTxId AND walletAddress = :walletAddress LIMIT 1")
    suspend fun getEdit(targetTxId: String, walletAddress: String): MessageEditEntity?

    @Query("SELECT * FROM message_edits WHERE walletAddress = :walletAddress AND contactId = :contactId")
    fun getEditsForContact(contactId: String, walletAddress: String): Flow<List<MessageEditEntity>>

    @Query("SELECT * FROM message_edits WHERE walletAddress = :walletAddress AND groupId = :groupId")
    fun getEditsForGroup(groupId: String, walletAddress: String): Flow<List<MessageEditEntity>>

    /** Existence check by the edit's own tx id — the sibling of [ReactionDao.countByReactionTxId]. */
    @Query("SELECT COUNT(*) FROM message_edits WHERE editTxId = :txId AND walletAddress = :walletAddress")
    suspend fun countByEditTxId(txId: String, walletAddress: String): Int

    @Query("DELETE FROM message_edits WHERE walletAddress = :walletAddress AND contactId = :contactId")
    suspend fun deleteAllForContact(contactId: String, walletAddress: String)

    @Query("DELETE FROM message_edits WHERE walletAddress = :walletAddress AND groupId = :groupId")
    suspend fun deleteAllForGroup(groupId: String, walletAddress: String)

    @Query("DELETE FROM message_edits WHERE walletAddress = :walletAddress")
    suspend fun deleteAllForWallet(walletAddress: String)
}
