package com.kachat.app.services

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every group message id this device has applied, and the transaction that carried it (iOS
 * 4f8e14bb, GroupStore's `CDGroupSeenMessageId`). A group message's signature covers its (group,
 * sender, epoch, msg_id) and not the transaction, so anyone can put the same bytes in a new
 * transaction, for a fresh block time and txId. [claim] is what lets each id count once.
 *
 * Kept after a message is deleted or pruned, or the same bytes could bring it back. Its own small
 * database rather than a Room table: it is written once per message, read by key, and nothing
 * observes it.
 */
@Singleton
class GroupSeenMessageStore @Inject constructor(
    @ApplicationContext context: Context,
) : SQLiteOpenHelper(context, DATABASE_NAME, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE (" +
                "walletAddress TEXT NOT NULL, messageKey TEXT NOT NULL, groupId TEXT NOT NULL, " +
                "txId TEXT NOT NULL, blockTime INTEGER NOT NULL, " +
                "PRIMARY KEY (walletAddress, messageKey))"
        )
        db.execSQL("CREATE INDEX byGroup ON $TABLE (walletAddress, groupId)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** The transaction that holds [messageKey], if one does. */
    @Synchronized
    fun claimedTxId(walletAddress: String, messageKey: String): String? =
        readableDatabase.query(
            TABLE, arrayOf("txId"), "walletAddress = ? AND messageKey = ?",
            arrayOf(walletAddress, messageKey), null, null, null, "1"
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    /** Claims [messageKey] for [txId]. False when another transaction got there first. */
    @Synchronized
    fun claim(walletAddress: String, messageKey: String, groupId: String, txId: String, blockTime: Long): Boolean {
        val values = ContentValues().apply {
            put("walletAddress", walletAddress)
            put("messageKey", messageKey)
            put("groupId", groupId)
            put("txId", txId)
            put("blockTime", blockTime)
        }
        writableDatabase.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
        return claimedTxId(walletAddress, messageKey) == txId
    }

    @Synchronized
    fun forgetGroup(walletAddress: String, groupId: String) {
        writableDatabase.delete(TABLE, "walletAddress = ? AND groupId = ?", arrayOf(walletAddress, groupId))
    }

    @Synchronized
    fun forgetWallet(walletAddress: String) {
        writableDatabase.delete(TABLE, "walletAddress = ?", arrayOf(walletAddress))
    }

    private companion object {
        const val DATABASE_NAME = "kachat_group_seen_messages.db"
        const val TABLE = "seen_message_ids"
    }
}
