package com.kachat.app.services.database

import androidx.room.*
import com.kachat.app.models.ContactEntity
import com.kachat.app.models.DeletedContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts WHERE walletAddress = :walletAddress")
    fun getContacts(walletAddress: String): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE id = :id AND walletAddress = :walletAddress")
    suspend fun getContact(id: String, walletAddress: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE conversationStatus = :status AND walletAddress = :walletAddress")
    suspend fun getContactsByStatus(status: String, walletAddress: String): List<ContactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contact: ContactEntity)

    @Delete
    suspend fun delete(contact: ContactEntity)

    /** Deletes a single contact by key — used by ChatRepository.deleteChat's full-removal flow, where only the id is on hand, not a loaded entity. */
    @Query("DELETE FROM contacts WHERE id = :id AND walletAddress = :walletAddress")
    suspend fun deleteContact(id: String, walletAddress: String)

    /** Every contact for this wallet, gone — used when wiping an entire account. */
    @Query("DELETE FROM contacts WHERE walletAddress = :walletAddress")
    suspend fun deleteAllForWallet(walletAddress: String)

    /**
     * Records that [contactId] was deleted at [DeletedContactEntity.deletedAt] — see
     * [DeletedContactEntity]'s doc comment for why this has to outlive the contact row itself.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markContactDeleted(marker: DeletedContactEntity)

    /** Full tombstone row (not just [DeletedContactEntity.deletedAt]) — callers need [DeletedContactEntity.deletedAtTxIds] too, see ChatRepository.isTombstoned. */
    @Query("SELECT * FROM deleted_contacts WHERE contactId = :contactId AND walletAddress = :walletAddress")
    suspend fun getDeletedContact(contactId: String, walletAddress: String): DeletedContactEntity?

    /** Every tombstoned contact address for this wallet — exported with chat-history backups so a restore anywhere skips deleted chats. */
    @Query("SELECT contactId FROM deleted_contacts WHERE walletAddress = :walletAddress")
    suspend fun getAllDeletedContactIds(walletAddress: String): List<String>

    /** Every deletion tombstone for this wallet, gone — used when wiping an entire account, so a same-address re-import later starts genuinely clean. */
    @Query("DELETE FROM deleted_contacts WHERE walletAddress = :walletAddress")
    suspend fun deleteTombstonesForWallet(walletAddress: String)
}
