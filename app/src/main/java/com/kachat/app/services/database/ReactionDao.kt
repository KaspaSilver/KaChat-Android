package com.kachat.app.services.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kachat.app.models.ReactionEntity
import kotlinx.coroutines.flow.Flow

/** One contact's newest reaction across every message in that conversation, joined against the
 *  target message's `direction` - backs the chat list's "Reacted to your/their message" preview,
 *  which otherwise has no visibility into reactions at all (they're applied as a corner pill,
 *  never inserted as a message). `targetDirection` is null if the reacted-to message can't be
 *  found (e.g. pruned by message retention while the reaction row was kept). */
data class LatestReactionRow(
    val contactId: String,
    val emoji: String,
    val reactorAddress: String,
    val blockTimestamp: Long,
    val targetDirection: String?
)

/** Group-chat sibling of [LatestReactionRow]: one group's newest reaction, joined against the
 *  target group message's `isOutgoing` - backs the Group Chats tab's "Alice reacted to a message"
 *  card preview. `targetIsOutgoing` is null if the reacted-to message can't be found. */
data class LatestGroupReactionRow(
    val groupId: String,
    val emoji: String,
    val reactorAddress: String,
    val blockTimestamp: Long,
    val targetIsOutgoing: Boolean?
)

@Dao
interface ReactionDao {

    /** Replaces any existing (targetTxId, walletAddress, reactorAddress) row - picking a new emoji on a message you've already reacted to overwrites your previous one. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReaction(reaction: ReactionEntity)

    @Query("DELETE FROM reactions WHERE targetTxId = :targetTxId AND walletAddress = :walletAddress AND reactorAddress = :reactorAddress")
    suspend fun deleteReaction(targetTxId: String, walletAddress: String, reactorAddress: String)

    @Query("SELECT * FROM reactions WHERE walletAddress = :walletAddress AND contactId = :contactId")
    fun getReactionsForContact(contactId: String, walletAddress: String): Flow<List<ReactionEntity>>

    /** One row per contactId (1:1 only - `contactId IS NOT NULL` excludes group reactions) -
     *  whichever reaction has the most recent blockTimestamp, joined to the target message's
     *  direction. Mirrors [MessageDao.getLatestMessagePerContact]'s exact "max blockTimestamp
     *  grouped by contact" shape. */
    @Query(
        """
        SELECT r.contactId AS contactId, r.emoji AS emoji, r.reactorAddress AS reactorAddress,
               r.blockTimestamp AS blockTimestamp, m.direction AS targetDirection
        FROM reactions r
        LEFT JOIN messages m ON m.id = r.targetTxId AND m.walletAddress = r.walletAddress
        WHERE r.walletAddress = :walletAddress AND r.contactId IS NOT NULL AND r.blockTimestamp IN (
            SELECT MAX(blockTimestamp) FROM reactions
            WHERE walletAddress = :walletAddress AND contactId IS NOT NULL
            GROUP BY contactId
        )
        """
    )
    fun getLatestReactionPerContact(walletAddress: String): Flow<List<LatestReactionRow>>

    @Query("SELECT * FROM reactions WHERE walletAddress = :walletAddress AND groupId = :groupId")
    fun getReactionsForGroup(groupId: String, walletAddress: String): Flow<List<ReactionEntity>>

    /** One row per groupId - whichever reaction has the most recent blockTimestamp, joined to
     *  whether the target message is one of ours. Mirrors [getLatestReactionPerContact]'s exact
     *  "max blockTimestamp grouped by owner" shape, scoped to group reactions instead. */
    @Query(
        """
        SELECT r.groupId AS groupId, r.emoji AS emoji, r.reactorAddress AS reactorAddress,
               r.blockTimestamp AS blockTimestamp, m.isOutgoing AS targetIsOutgoing
        FROM reactions r
        LEFT JOIN group_messages m ON m.txId = r.targetTxId AND m.walletAddress = r.walletAddress
        WHERE r.walletAddress = :walletAddress AND r.groupId IS NOT NULL AND r.blockTimestamp IN (
            SELECT MAX(blockTimestamp) FROM reactions
            WHERE walletAddress = :walletAddress AND groupId IS NOT NULL
            GROUP BY groupId
        )
        """
    )
    fun getLatestReactionPerGroup(walletAddress: String): Flow<List<LatestGroupReactionRow>>

    /** Existence check by the reaction's own tx id - see GroupRepository.isGroupTxIngested. */
    @Query("SELECT COUNT(*) FROM reactions WHERE reactionTxId = :txId AND walletAddress = :walletAddress")
    suspend fun countByReactionTxId(txId: String, walletAddress: String): Int

    @Query("SELECT * FROM reactions WHERE targetTxId = :targetTxId AND walletAddress = :walletAddress AND reactorAddress = :reactorAddress LIMIT 1")
    suspend fun getReaction(targetTxId: String, walletAddress: String, reactorAddress: String): ReactionEntity?

    @Query("DELETE FROM reactions WHERE walletAddress = :walletAddress AND contactId = :contactId")
    suspend fun deleteAllForContact(contactId: String, walletAddress: String)

    @Query("DELETE FROM reactions WHERE walletAddress = :walletAddress AND groupId = :groupId")
    suspend fun deleteAllForGroup(groupId: String, walletAddress: String)

    @Query("DELETE FROM reactions WHERE walletAddress = :walletAddress")
    suspend fun deleteAllForWallet(walletAddress: String)
}
