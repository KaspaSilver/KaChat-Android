package com.kachat.app.services.database

import androidx.room.*
import com.kachat.app.models.BroadcastChannelEntity
import com.kachat.app.models.BroadcastMessageEntity
import com.kachat.app.models.HiddenBroadcastSenderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BroadcastDao {

    @Query("SELECT * FROM broadcast_channels WHERE walletAddress = :walletAddress ORDER BY joinedAt DESC")
    fun getJoinedChannels(walletAddress: String): Flow<List<BroadcastChannelEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun joinChannel(channel: BroadcastChannelEntity)

    @Query("DELETE FROM broadcast_channels WHERE channelName = :channelName AND walletAddress = :walletAddress")
    suspend fun leaveChannel(channelName: String, walletAddress: String)

    @Query("UPDATE broadcast_channels SET alwaysListen = :alwaysListen WHERE channelName = :channelName AND walletAddress = :walletAddress")
    suspend fun setAlwaysListen(channelName: String, walletAddress: String, alwaysListen: Boolean)

    /** Also forces alwaysListen off — a channel that isn't being listened to can't sensibly still notify. */
    @Query("UPDATE broadcast_channels SET alwaysListen = 0, notifyEnabled = 0 WHERE channelName = :channelName AND walletAddress = :walletAddress")
    suspend fun disableAlwaysListenAndNotify(channelName: String, walletAddress: String)

    /** Also forces alwaysListen on — notifications need the channel to actually be scanned. */
    @Query("UPDATE broadcast_channels SET alwaysListen = 1, notifyEnabled = 1 WHERE channelName = :channelName AND walletAddress = :walletAddress")
    suspend fun enableNotifyAndAlwaysListen(channelName: String, walletAddress: String)

    @Query("UPDATE broadcast_channels SET notifyEnabled = 0 WHERE channelName = :channelName AND walletAddress = :walletAddress")
    suspend fun disableNotify(channelName: String, walletAddress: String)

    /** Per-channel override of local message retention, set via the settings icon next to a channel — see BroadcastRepository.setRetentionMillis for the 3-day cap enforcement. */
    @Query("UPDATE broadcast_channels SET retentionMillis = :retentionMillis WHERE channelName = :channelName AND walletAddress = :walletAddress")
    suspend fun setRetentionMillis(channelName: String, walletAddress: String, retentionMillis: Long)

    /**
     * One row per distinct channel name with the *longest* retention any local account has asked
     * for it — messages aren't scoped per wallet, so if two accounts on this device joined the
     * same channel with different retention settings, pruning must honor whichever is longer
     * rather than deleting data a different account still wants to keep.
     */
    @Query("SELECT channelName, MAX(retentionMillis) AS retentionMillis FROM broadcast_channels GROUP BY channelName")
    suspend fun getChannelRetentions(): List<ChannelRetention>

    /** Drives both whether background scanning should run at all (non-empty) and which channels' messages actually get cached while it's running — see BroadcastScanningService. */
    @Query("SELECT channelName FROM broadcast_channels WHERE walletAddress = :walletAddress AND alwaysListen = 1")
    fun getAlwaysListenChannelNames(walletAddress: String): Flow<List<String>>

    /** Which channels should fire a system notification for new messages — see BroadcastScanningService. */
    @Query("SELECT channelName FROM broadcast_channels WHERE walletAddress = :walletAddress AND notifyEnabled = 1")
    fun getNotifyEnabledChannelNames(walletAddress: String): Flow<List<String>>

    @Query("SELECT * FROM broadcast_messages WHERE channelName = :channelName ORDER BY blockTimestamp ASC")
    fun getMessagesForChannel(channelName: String): Flow<List<BroadcastMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: BroadcastMessageEntity)

    /** One message row by id — used to look a failed reaction's own message row back up for retry (see BroadcastRepository.retryReactionMessage). */
    @Query("SELECT * FROM broadcast_messages WHERE id = :id LIMIT 1")
    suspend fun getMessage(id: String): BroadcastMessageEntity?

    /** Removes a single message by id — used to drop a "pending_<uuid>" placeholder once its real send resolves (success swaps it for the real-txId row; see BroadcastRepository.sendBroadcast). */
    @Query("DELETE FROM broadcast_messages WHERE id = :id")
    suspend fun deleteMessage(id: String)

    /** Flips a message's own delivery status in place (pending -> sent/failed) — used by sendBroadcast/retryBroadcast, mirroring ChatRepository.updateMessageStatus for 1:1 chats. */
    @Query("UPDATE broadcast_messages SET deliveryStatus = :status WHERE id = :id")
    suspend fun updateMessageStatus(id: String, status: String)

    @Query("DELETE FROM broadcast_messages WHERE channelName = :channelName AND blockTimestamp < :cutoffMillis")
    suspend fun deleteOlderThan(channelName: String, cutoffMillis: Long)

    /** Wipes every cached message for a channel — used when leaving, since the user is explicitly warned that leaving deletes them (unlike the normal per-channel rolling retention, which is passive). */
    @Query("DELETE FROM broadcast_messages WHERE channelName = :channelName")
    suspend fun deleteMessagesForChannel(channelName: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun hideSender(entity: HiddenBroadcastSenderEntity)

    /** Removes both the room-scoped hide AND any legacy every-room ("") hide for the sender. */
    @Query("DELETE FROM broadcast_hidden_senders WHERE senderAddress = :senderAddress AND walletAddress = :walletAddress AND (channelName = :channelName OR channelName = '')")
    suspend fun unhideSender(senderAddress: String, walletAddress: String, channelName: String)

    /** Reactive so a fresh hide/unhide immediately re-filters any already-open channel screen and the scanning service's insert-time check (see BroadcastScanningService). */
    @Query("SELECT * FROM broadcast_hidden_senders WHERE walletAddress = :walletAddress")
    fun getHiddenSenders(walletAddress: String): Flow<List<HiddenBroadcastSenderEntity>>
}

data class ChannelRetention(val channelName: String, val retentionMillis: Long)
