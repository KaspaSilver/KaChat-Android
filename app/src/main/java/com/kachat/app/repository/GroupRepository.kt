@file:OptIn(ExperimentalStdlibApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.kachat.app.repository

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kachat.app.models.ChatHistoryArchiveGroup
import com.kachat.app.models.ChatHistoryArchiveGroupMember
import com.kachat.app.models.ChatHistoryArchiveGroupMessage
import com.kachat.app.models.ContactEntity
import com.kachat.app.models.GroupEntity
import com.kachat.app.models.GroupMember
import com.kachat.app.models.GroupMessageEntity
import com.kachat.app.models.GroupSyncCursorEntity
import com.kachat.app.models.ReactionEntity
import com.kachat.app.services.GroupBag
import com.kachat.app.services.GroupControlIndexerResponse
import com.kachat.app.services.GroupMessageIndexerResponse
import com.kachat.app.services.GroupSecretStore
import com.kachat.app.services.KasiaIndexerApi
import com.kachat.app.services.NetworkService
import com.kachat.app.services.NotificationHelper
import com.kachat.app.services.WalletManager
import com.kachat.app.services.WalletService
import com.kachat.app.services.database.KaChatDatabase
import com.kachat.app.util.GroupCipher
import com.kachat.app.util.ImageMessage
import com.kachat.app.util.KasiaCipher
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.MessageReaction
import com.kachat.app.util.MessageReply
import com.kachat.app.util.Schnorr
import com.kachat.app.util.VoiceMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A decrypted group chat message - UI-facing, mirrors [com.kachat.app.models.GroupMessageEntity] but with plaintext content. */
data class GroupMessage(
    val txId: String,
    val groupId: String,
    val senderAddress: String?,
    val senderIdHex: String,
    val content: String,
    val blockTimestamp: Long,
    val isOutgoing: Boolean,
    val deliveryStatus: String
)

/** Reserved sender marker for iMessage-style membership system lines ("X was added/removed").
 *  Stored as a negative-epoch plaintext row; the group thread renders these centered, not as bubbles. */
const val GROUP_SYSTEM_SENDER = "system"

fun GroupMessage.isSystemMessage(): Boolean = senderAddress == GROUP_SYSTEM_SENDER

/** In-memory model for the Group Chats tab's list - mirrors [com.kachat.app.models.Conversation]'s shape for 1:1 chats. */
data class GroupConversation(
    val group: GroupEntity,
    val lastMessage: GroupMessage?,
    /** Backs the Group Chats tab's unread badge - messages newer than group.lastReadAt, or 1 if
     * the group was never opened and we didn't create it ourselves (covers "new group added"
     * with zero messages yet). See GroupEntity.lastReadAt's doc comment. */
    val unreadCount: Int = 0
)

/**
 * Group chat lifecycle (create/add/remove member, epoch rotation), sending, and message
 * decryption. Kotlin port of iOS KaChat's `GroupChatService.swift` — see that file's doc
 * comment for the full protocol rationale. Discovery (block-scan for `gcomm`/`gctl` on-chain
 * payloads) lives in [com.kachat.app.services.GroupScanningService], which calls the
 * `handleIncoming*` functions here.
 *
 * Two on-chain payload types, both self-stash (sender spends their own identity-address UTXOs,
 * output returns to their own identity address):
 *  - `ciph_msg:1:gcomm:...` - a group message.
 *  - `ciph_msg:1:gctl:...` - a control message (`gctl_root`/`gctl_epoch`), ECIES-encrypted (via
 *    [KasiaCipher], the same crypto 1:1 messages use) to one specific recipient.
 *
 * Deliberately no invite-link/beacon join path: every member is added directly by the admin, who
 * already knows who they are (see `addMember`/`createGroup`). A prior revision had a
 * publicly-joinable invite beacon (KaChat extension, not in the reference spec) - removed once
 * group chats route through indexers, since a way for anyone to discover and join a group's
 * *encrypted* chat is exactly the kind of thing that could be used to infer something bad is
 * happening inside it and pressure an indexer operator into censoring it.
 */
@Singleton
class GroupRepository @Inject constructor(
    private val database: KaChatDatabase,
    private val walletManager: WalletManager,
    private val walletService: WalletService,
    private val groupSecretStore: GroupSecretStore,
    private val networkService: NetworkService,
    private val notificationHelper: NotificationHelper,
    private val settings: AppSettingsRepository,
    private val notificationCenter: com.kachat.app.services.GlobalNotificationCenterStore,
    private val knsService: com.kachat.app.services.KnsService,
) {
    private val gson = Gson()
    private val membersListType = object : TypeToken<List<GroupMember>>() {}.type

    // -------------------------------------------------------------------------
    // Groups (reactive, scoped to whichever account is active)
    // -------------------------------------------------------------------------

    fun getGroups(): Flow<List<GroupEntity>> {
        return walletManager.activeAddressFlow.flatMapLatest { address ->
            if (address == null) flowOf(emptyList()) else database.groupDao().getGroups(address)
        }
    }

    fun getGroupCount(): Flow<Int> = getGroups().map { it.size }

    /** groupId -> that group's newest reaction (with whether it targets one of our own messages) —
     *  backs the Group Chats tab's "Alice reacted to a message" card preview, mirroring
     *  [ChatRepository.getLatestReactions] for 1:1 rows. Reactions never become message rows, so
     *  without this the card silently shows a stale last message when the truly newest activity
     *  was a reaction. */
    fun getLatestGroupReactions(): Flow<List<com.kachat.app.services.database.LatestGroupReactionRow>> {
        return walletManager.activeAddressFlow.flatMapLatest { address ->
            if (address == null) flowOf(emptyList()) else database.reactionDao().getLatestReactionPerGroup(address)
        }
    }

    // Recently-ingested gcomm txIds (messages AND reactions, including reaction removals that
    // leave no row behind) — lets the FCM group push handler ask "did the local pipeline already
    // handle this tx?" without a DB shape that covers every case. LRU-capped; synchronized
    // because the live scan, catch-up sync, and FCM-triggered sync run on different coroutines.
    private val handledGroupTxIds = object : LinkedHashMap<String, Boolean>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 256
    }

    @Synchronized
    private fun markGroupTxHandled(txId: String) {
        handledGroupTxIds[txId] = true
    }

    @Synchronized
    private fun isGroupTxHandledInMemory(txId: String): Boolean = handledGroupTxIds.containsKey(txId)

    /**
     * Whether [txId] has been ingested by the group pipeline — as a decrypted message row, an
     * applied reaction, or an in-memory record of processing it this session. The FCM handler
     * calls this after a push-triggered [syncGroups]: true means the local path already posted
     * the precise banner (or deliberately stayed silent, e.g. a reaction to someone else's
     * message), so the generic "New group message" fallback must not fire.
     */
    suspend fun isGroupTxIngested(txId: String): Boolean {
        if (txId.isBlank()) return false
        if (isGroupTxHandledInMemory(txId)) return true
        val walletAddress = walletManager.getActiveAccount()?.address ?: return false
        return database.groupDao().countMessagesByTxId(txId, walletAddress) > 0 ||
            database.reactionDao().countByReactionTxId(txId, walletAddress) > 0
    }

    /**
     * Resolves a push payload's `blinded_group_id` back to the local group it belongs to, or
     * null when no local group matches (e.g. a membership this device hasn't synced yet).
     * Blinded ids are per-(group, sender) - see PushRegistrationManager.collectWatchedGroupIds -
     * so this recomputes every known (group, member) blinded id and compares. Lets the FCM
     * generic-fallback path consult per-group notification settings (mentions-only) even though
     * the push itself only carries the blinded id.
     */
    suspend fun findGroupByBlindedId(blindedGroupIdHex: String): GroupEntity? {
        val target = blindedGroupIdHex.trim().lowercase()
        if (target.isEmpty()) return null
        val walletAddress = walletManager.getActiveAccount()?.address ?: return null
        for (group in database.groupDao().getGroupsOnce(walletAddress)) {
            val bag = groupSecretStore.loadBag(walletAddress, group.groupId) ?: continue
            val blindingKey = try { bag.blindingKey.hexToByteArray() } catch (e: Exception) { continue }
            for (member in membersOf(group)) {
                val pub = try { member.xOnlyPubKeyHex.hexToByteArray() } catch (e: Exception) { continue }
                if (GroupCipher.deriveBlindedGroupId(blindingKey, pub).toHexString() == target) return group
            }
        }
        return null
    }

    /**
     * True when EVERY group this device knows about is silenced or mentions-only.
     *
     * Used for a push that could not be ingested AND whose blinded id matches no local group: we
     * cannot tell which group it belongs to, so the only sound question is whether the answer
     * would be the same for all of them.
     */
    suspend fun allGroupsSuppressUnidentifiedPushes(): Boolean {
        val groups = database.groupDao().getGroups(walletManager.getAddress()).first()
        if (groups.isEmpty()) return false
        val silent = settings.groupSilent.first()
        val mentionsOnly = settings.groupMentionsOnly.first()
        return groups.all { it.groupId in silent || it.groupId in mentionsOnly }
    }

    /** Whether this group is silenced outright - no banner, mentioned or not. */
    suspend fun isGroupSilent(groupId: String): Boolean =
        groupId in settings.groupSilent.first()

    /** Whether the per-group "Only Notify if I'm Mentioned" toggle is on for [groupId]. */
    suspend fun isGroupMentionsOnly(groupId: String): Boolean =
        groupId in settings.groupMentionsOnly.first()

    /** Marks a group's thread as read as of now - backs the Group Chats tab's unread badge. Call when its thread screen opens. */
    suspend fun markGroupRead(groupId: String) {
        val walletAddress = walletManager.getAddress()
        database.groupDao().markGroupRead(groupId, walletAddress, System.currentTimeMillis())
    }

    /** Forces a group back to "unread" - clears lastReadAt, the same state as never opened. */
    suspend fun markGroupUnread(groupId: String) {
        val walletAddress = walletManager.getAddress()
        database.groupDao().markGroupUnread(groupId, walletAddress)
    }

    /** True whenever a wallet is active, regardless of group state - see [com.kachat.app.services.GroupScanningService] for why `gctl` scanning must key off this instead of group count. */
    val hasActiveWallet: Flow<Boolean> = walletManager.activeAddressFlow.map { it != null }

    /** "kaspa" or "kaspatest", read off the active wallet's own address - used to reconstruct a sender's address from a raw pubkey/script for the active network instead of assuming mainnet. */
    fun addressPrefix(): String = walletManager.getAddress().substringBefore(":")

    fun membersOf(group: GroupEntity): List<GroupMember> =
        try { gson.fromJson<List<GroupMember>>(group.membersJson, membersListType) ?: emptyList() } catch (e: Exception) { emptyList() }

    /** Best display name for a membership system line: live contact alias → roster snapshot name → short address. */
    private suspend fun groupMemberLabel(address: String, walletAddress: String, fallbackDisplayName: String?): String {
        val alias = try { database.contactDao().getContact(address, walletAddress)?.alias?.trim() } catch (e: Exception) { null }
        if (!alias.isNullOrEmpty()) return alias
        val snap = fallbackDisplayName?.trim()
        if (!snap.isNullOrEmpty()) return snap
        return address.takeLast(10)
    }

    /** Best display name for a group member in a notification banner: live contact alias (most
     *  likely to be current/deliberate - may have been set/changed after this person was added to
     *  the group) > their primary KNS domain > the group roster's `displayName` snapshot (set
     *  once, from whoever added them, at add-time) > a shortened address as a last resort - the
     *  same chain the chat cards and the group thread's sender labels resolve with, so a banner
     *  never regresses to a raw address for someone the UI names. The KNS step only runs when
     *  there's no alias (rare - members get a contact row when added), is cached per address for
     *  10 minutes so a chatty no-alias member can't hammer the KNS API, and any failure just
     *  falls through to the roster snapshot. */
    private suspend fun groupSenderLabel(senderAddress: String, walletAddress: String, group: GroupEntity): String {
        val alias = try { database.contactDao().getContact(senderAddress, walletAddress)?.alias?.trim() } catch (e: Exception) { null }
        if (!alias.isNullOrEmpty()) return alias
        val kns = cachedPrimaryKnsDomain(senderAddress)
        if (!kns.isNullOrEmpty()) return kns
        val snap = membersOf(group).firstOrNull { it.address == senderAddress }?.displayName?.trim()
        if (!snap.isNullOrEmpty()) return snap
        return KaspaAddress.shortDisplay(senderAddress)
    }

    /** address -> (fetchedAtMs, primary KNS domain or null) for [groupSenderLabel] - a negative
     *  ("owns no domain") answer is cached too, so it's one lookup per no-alias member per TTL
     *  window, not per message. */
    private val senderKnsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String?>>()

    private suspend fun cachedPrimaryKnsDomain(address: String): String? {
        val now = System.currentTimeMillis()
        senderKnsCache[address]?.let { (fetchedAt, domain) ->
            if (now - fetchedAt < 10 * 60_000L) return domain
        }
        val domain = try { knsService.reverseResolve(address)?.trim()?.takeIf { it.isNotEmpty() } } catch (e: Exception) { null }
        senderKnsCache[address] = now to domain
        return domain
    }

    /** Insert an iMessage-style membership line into the group thread, stored as a negative-epoch
     *  plaintext row keyed on the reserved [GROUP_SYSTEM_SENDER] so the UI renders it centered. */
    private suspend fun insertGroupSystemMessage(groupId: String, walletAddress: String, text: String, blockTime: Long) {
        val txId = "sys_${groupId.take(8)}_${blockTime}_${text.hashCode()}"
        database.groupDao().insertMessage(
            GroupMessageEntity(
                txId = txId, walletAddress = walletAddress, groupId = groupId,
                senderAddress = GROUP_SYSTEM_SENDER, senderIdHex = "", epoch = -1L,
                msgIdHex = "", contentEncryptedHex = text.toByteArray(Charsets.UTF_8).toHexString(),
                blockTimestamp = blockTime, isOutgoing = false, deliveryStatus = "sent"
            )
        )
    }

    /** Emit "X was added" / "Y was removed" lines for a roster change (old → new), for both the
     *  admin who made the change and any member who receives the rotated root. */
    private suspend fun emitMembershipSystemMessages(
        groupId: String, walletAddress: String, oldMembers: List<GroupMember>, newMembers: List<GroupMember>, blockTime: Long
    ) {
        val oldAddrs = oldMembers.map { it.address }.toSet()
        val newAddrs = newMembers.map { it.address }.toSet()
        var t = blockTime
        for (m in newMembers) if (m.address !in oldAddrs) {
            insertGroupSystemMessage(groupId, walletAddress, "${groupMemberLabel(m.address, walletAddress, m.displayName)} was added to the group chat", t++)
        }
        for (m in oldMembers) if (m.address !in newAddrs) {
            insertGroupSystemMessage(groupId, walletAddress, "${groupMemberLabel(m.address, walletAddress, m.displayName)} was removed from the group chat", t++)
        }
    }

    /** Decrypted messages for a group, oldest first - decryption happens here, on read, from stored ciphertext. */
    fun getMessages(groupId: String): Flow<List<GroupMessage>> {
        return walletManager.activeAddressFlow.flatMapLatest { address ->
            if (address == null) flowOf(emptyList())
            else database.groupDao().getMessages(groupId, address).map { entities ->
                val bag = groupSecretStore.loadBag(address, groupId) ?: return@map emptyList()
                val groupIdBytes = groupId.hexToByteArray()
                entities.mapNotNull { decryptCached(it, bag, groupIdBytes) }
            }
            // Room delivers on its own executor, but the map above ran wherever the collector
            // lives - the main thread for a StateFlow in a ViewModel - so a sync that inserted a
            // message re-decrypted the whole thread on the UI thread and dropped frames.
            .flowOn(Dispatchers.Default)
        }
    }

    /**
     * Just what the Group Chats list row needs: the newest message and an unread count.
     *
     * This exists because the list used to subscribe to [getMessages] per group, which decrypts
     * every message in every group on every database emission - during a sync, continuously, on
     * the main thread. The count comes straight from SQL (`isOutgoing`/`blockTimestamp` are
     * plaintext columns) and only the single preview message is ever decrypted.
     */
    data class GroupSummary(val latestMessage: GroupMessage?, val unreadCount: Int)

    fun getGroupSummary(groupId: String, lastReadAt: Long?, isAdmin: Boolean): Flow<GroupSummary> {
        return walletManager.activeAddressFlow.flatMapLatest { address ->
            if (address == null) {
                flowOf(GroupSummary(null, 0))
            } else {
                combine(
                    database.groupDao().getLatestMessage(groupId, address),
                    database.groupDao().countUnread(groupId, address, lastReadAt),
                ) { latest, unread ->
                    val decrypted = latest?.let {
                        val bag = groupSecretStore.loadBag(address, groupId)
                        if (bag == null) null else decryptCached(it, bag, groupId.hexToByteArray())
                    }
                    // A group never opened and not created by us counts as at least 1, covering
                    // "added to a new group, no messages yet".
                    val count = if (lastReadAt == null && !isAdmin) maxOf(unread, 1) else unread
                    GroupSummary(decrypted, count)
                }.flowOn(Dispatchers.Default)
            }
        }
    }

    /**
     * [decryptEntity] memoised by txId. A thread re-emits in full on every insert, so without
     * this an open group re-ran ChaCha over its entire history for each arriving message. Rows
     * are immutable once stored (only `deliveryStatus` changes, which is plaintext), so a hit is
     * always valid; the cache is bounded and evicts oldest-first.
     */
    private val decryptedCache = object : LinkedHashMap<String, GroupMessage>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GroupMessage>?): Boolean = size > 4000
    }

    private fun decryptCached(entity: GroupMessageEntity, bag: GroupBag, groupIdBytes: ByteArray): GroupMessage? {
        synchronized(decryptedCache) { decryptedCache[entity.txId] }?.let { cached ->
            // deliveryStatus is the one mutable field and is not part of the ciphertext.
            return if (cached.deliveryStatus == entity.deliveryStatus) cached else cached.copy(deliveryStatus = entity.deliveryStatus)
        }
        val decrypted = decryptEntity(entity, bag, groupIdBytes) ?: return null
        synchronized(decryptedCache) { decryptedCache[entity.txId] = decrypted }
        return decrypted
    }

    fun getReactions(groupId: String): Flow<List<ReactionEntity>> {
        return walletManager.activeAddressFlow.flatMapLatest { address ->
            if (address == null) flowOf(emptyList())
            else database.reactionDao().getReactionsForGroup(groupId, address)
        }
    }

    private fun decryptEntity(entity: GroupMessageEntity, bag: GroupBag, groupIdBytes: ByteArray): GroupMessage? {
        // Imported-from-backup rows carry decrypted plaintext (hex of UTF-8) under a negative
        // epoch sentinel — no group key or ciphertext involved. Preserves message history that
        // the indexer may have pruned.
        if (entity.epoch < 0) {
            val plaintext = try { String(entity.contentEncryptedHex.hexToByteArray(), Charsets.UTF_8) } catch (e: Exception) { return null }
            return GroupMessage(
                txId = entity.txId, groupId = entity.groupId, senderAddress = entity.senderAddress, senderIdHex = entity.senderIdHex,
                content = plaintext, blockTimestamp = entity.blockTimestamp, isOutgoing = entity.isOutgoing, deliveryStatus = entity.deliveryStatus
            )
        }
        val root = groupRootEpochFor(entity.epoch, bag, groupIdBytes) ?: return null
        val senderId = try { entity.senderIdHex.hexToByteArray() } catch (e: Exception) { return null }
        val msgId = try { entity.msgIdHex.hexToByteArray() } catch (e: Exception) { return null }
        val ciphertext = try { entity.contentEncryptedHex.hexToByteArray() } catch (e: Exception) { return null }
        val plaintext = GroupCipher.decryptMessage(ciphertext, root, groupIdBytes, entity.epoch, senderId, msgId) ?: return null
        return GroupMessage(
            txId = entity.txId, groupId = entity.groupId, senderAddress = entity.senderAddress, senderIdHex = entity.senderIdHex,
            content = plaintext, blockTimestamp = entity.blockTimestamp, isOutgoing = entity.isOutgoing, deliveryStatus = entity.deliveryStatus
        )
    }

    /**
     * Admins can derive any past epoch's root on demand (they hold groupSeed); everyone else
     * relies on [GroupBag.previousRoots], the archive of roots this device has already held.
     *
     * That archive is new. This used to stop at the current epoch for non-admins, described as
     * the protocol's forward-secrecy boundary - but the app keeps the CIPHERTEXT on disk forever
     * and renders it in the thread, so discarding the key bought no secrecy at all. It only made
     * a member's own history unreadable to them the moment anyone was added to the group.
     */
    private fun groupRootEpochFor(epoch: Long, bag: GroupBag, groupIdBytes: ByteArray): ByteArray? {
        if (epoch == bag.currentEpoch) return bag.groupRootEpoch.hexToByteArray()
        bag.previousRoots?.get(epoch)?.let { return it.hexToByteArray() }
        val seedHex = bag.groupSeed ?: return null
        return GroupCipher.deriveGroupRootEpoch(seedHex.hexToByteArray(), groupIdBytes, epoch)
    }

    // -------------------------------------------------------------------------
    // Group creation & membership
    // -------------------------------------------------------------------------

    suspend fun createGroup(name: String, members: List<ContactEntity>): GroupEntity {
        val walletAddress = walletManager.getAddress()
        val privateKey = walletManager.getPrivateKeyBytes()
        val adminXOnlyPub = Schnorr.publicKeyXOnly(privateKey)

        val groupSeed = GroupCipher.generateGroupSeed()
        val groupId = GroupCipher.deriveGroupId(groupSeed)
        val groupRootEpoch0 = GroupCipher.deriveGroupRootEpoch(groupSeed, groupId, 0)
        val blindingKey = GroupCipher.deriveBlindingKey(groupSeed, groupId)
        val deviceId = GroupCipher.generateDeviceId()

        val roster = mutableListOf(GroupMember(address = walletAddress, xOnlyPubKeyHex = adminXOnlyPub.toHexString(), isAdmin = true))
        for (contact in members) {
            val memberXOnlyPub = xOnlyPubKeyOrNull(contact.id) ?: continue
            roster.add(GroupMember(address = contact.id, xOnlyPubKeyHex = memberXOnlyPub.toHexString(), isAdmin = false, displayName = contact.alias))
        }

        val bag = GroupBag(
            groupId = groupId.toHexString(),
            groupSeed = groupSeed.toHexString(),
            groupRootEpoch = groupRootEpoch0.toHexString(),
            blindingKey = blindingKey.toHexString(),
            currentEpoch = 0,
            deviceId = deviceId.toHexString(),
            msgCounter = 0
        )
        groupSecretStore.saveBag(walletAddress, bag)

        val entity = GroupEntity(
            groupId = groupId.toHexString(), walletAddress = walletAddress, name = name, adminAddress = walletAddress,
            adminXOnlyPubKeyHex = adminXOnlyPub.toHexString(), currentEpoch = 0, isAdmin = true, membersJson = gson.toJson(roster)
        )
        database.groupDao().upsertGroup(entity)

        // Distribute gctl_root to each initial member directly - they must already be a 1:1
        // contact, i.e. their pubkey is resolvable from their address (every member is added
        // this way; there's no invite-link bootstrap path, see class doc). Best effort: one
        // member's send failing doesn't roll back group creation.
        for (member in roster) {
            if (member.isAdmin) continue
            try {
                sendRootControlMessage(entity, roster, bag, member.address, privateKey)
            } catch (e: Exception) {
                // Logged by the caller's own try/catch around sendKaspa; swallow here so one
                // failed member doesn't abort the rest.
            }
        }
        // Self-addressed recovery copy so a seedless re-import finds this group. Best-effort;
        // the sync backfill retries if it fails here.
        try { sendSelfRootControlMessage(entity, roster, bag, privateKey) } catch (e: Exception) {}

        return entity
    }

    /** Adds a member directly (requires an existing 1:1-resolvable address) - bumps the epoch and redistributes the new root to every member (old + new). */
    suspend fun addMember(contact: ContactEntity, groupId: String) {
        val memberXOnlyPub = xOnlyPubKeyOrNull(contact.id) ?: throw IllegalArgumentException("Invalid address")
        rotateEpoch(groupId, "add") { roster ->
            if (roster.none { it.address == contact.id }) {
                roster.add(GroupMember(address = contact.id, xOnlyPubKeyHex = memberXOnlyPub.toHexString(), isAdmin = false, displayName = contact.alias))
            }
        }
    }

    /** Removes a member and rotates the epoch so the removed member can no longer decrypt future messages (the new root is only distributed to remaining members). */
    suspend fun removeMember(member: GroupMember, groupId: String) {
        rotateEpoch(groupId, "remove") { roster ->
            roster.removeAll { it.address == member.address }
        }
    }

    /**
     * Renames a group and redistributes the updated gctl_root to every member so they all see
     * the new name - unlike add/removeMember, this does NOT rotate the epoch (a name change isn't
     * a forward-secrecy event), so it re-signs and re-sends the root at the *current* epoch.
     * `completeJoin`'s replay guard only rejects a strictly older epoch than what's already
     * stored, so a same-epoch re-send like this is accepted and simply updates the locally-cached
     * name/roster. Mirrors iOS's `GroupChatService.renameGroup` exactly.
     */
    suspend fun renameGroup(groupId: String, newName: String) {
        val walletAddress = walletManager.getAddress()
        val entity = database.groupDao().getGroup(groupId, walletAddress) ?: throw IllegalStateException("Unknown group.")
        if (!entity.isAdmin) throw IllegalStateException("Only the group admin can rename the group.")
        val bag = groupSecretStore.loadBag(walletAddress, groupId) ?: throw IllegalStateException("Missing admin group secrets.")
        val privateKey = walletManager.getPrivateKeyBytes()
        val roster = membersOf(entity)

        val updatedEntity = entity.copy(name = newName)
        database.groupDao().upsertGroup(updatedEntity)
        if (entity.name != newName) {
            insertGroupSystemMessage(groupId, walletAddress, "You changed the group name to \"$newName\"", System.currentTimeMillis())
        }
        // Self-addressed root so the SAME account's OTHER devices pick up the new name.
        try { sendSelfRootControlMessage(updatedEntity, roster, bag, privateKey) } catch (e: Exception) {}

        var failures = 0
        for (member in roster) {
            if (member.address == walletAddress) continue
            try {
                sendRootControlMessage(updatedEntity, roster, bag, member.address, privateKey)
            } catch (e: Exception) {
                failures++
            }
        }
        if (failures > 0) {
            throw IllegalStateException("Renamed, but $failures member(s) may not have received the update yet.")
        }
    }

    /** Re-broadcast the CURRENT root to every member (admin) - retries invites that failed to send,
     *  without rotating the epoch. Throws if any member still can't be reached. */
    suspend fun resendInvites(groupId: String) {
        val walletAddress = walletManager.getAddress()
        val entity = database.groupDao().getGroup(groupId, walletAddress) ?: throw IllegalStateException("Unknown group.")
        if (!entity.isAdmin) throw IllegalStateException("Only the group admin can resend invites.")
        val bag = groupSecretStore.loadBag(walletAddress, groupId) ?: throw IllegalStateException("Missing admin group secrets.")
        val privateKey = walletManager.getPrivateKeyBytes()
        val roster = membersOf(entity)
        var failures = 0
        for (member in roster) {
            if (member.address == walletAddress) continue
            try { sendRootControlMessage(entity, roster, bag, member.address, privateKey) } catch (e: Exception) { failures++ }
        }
        // Also re-push the group photo so anyone who missed it catches up.
        entity.photoHex?.let { try { distributeGroupPhoto(entity, roster, it, privateKey) } catch (e: Exception) {} }
        if (failures > 0) throw IllegalStateException("$failures invite(s) still could not be sent.")
    }

    /** Admin: set (photoHex = hex of a compressed JPEG) or clear (photoHex = "") the group photo,
     *  then push it to every member via a signed gctl_photo control message. */
    suspend fun setGroupPhoto(groupId: String, photoHex: String) {
        val walletAddress = walletManager.getAddress()
        val entity = database.groupDao().getGroup(groupId, walletAddress) ?: throw IllegalStateException("Unknown group.")
        if (!entity.isAdmin) throw IllegalStateException("Only the group admin can change the group photo.")
        val privateKey = walletManager.getPrivateKeyBytes()
        database.groupDao().upsertGroup(entity.copy(photoHex = photoHex.ifEmpty { null }))
        insertGroupSystemMessage(groupId, walletAddress,
            if (photoHex.isEmpty()) "You removed the group photo" else "You changed the group photo",
            System.currentTimeMillis())
        distributeGroupPhoto(entity, membersOf(entity), photoHex, privateKey)
    }

    /** Send the current group photo to every member (admin). Best-effort per member. */
    private suspend fun distributeGroupPhoto(entity: GroupEntity, roster: List<GroupMember>, photoHex: String, adminPrivateKey: ByteArray) {
        val walletAddress = walletManager.getAddress()
        val payload = GroupCipher.buildSignedPhotoPayload(
            entity.groupId.hexToByteArray(), photoHex, entity.adminXOnlyPubKeyHex.hexToByteArray(), adminPrivateKey
        )
        val json = GroupCipher.photoPayloadToJson(payload)
        for (member in roster) {
            if (member.address == walletAddress) continue
            val recipientXOnlyPub = xOnlyPubKeyOrNull(member.address) ?: continue
            try { sendControlPayload(json, recipientXOnlyPub, adminPrivateKey) } catch (e: Exception) {}
        }
        // Also send a self-addressed copy so the SAME account's OTHER devices sync the photo change.
        xOnlyPubKeyOrNull(walletAddress)?.let { selfPub ->
            try { sendControlPayload(json, selfPub, adminPrivateKey) } catch (e: Exception) {}
        }
    }

    /** Re-broadcast the current root to ONE member (admin) - a targeted retry of a single invite. */
    suspend fun resendInviteToMember(groupId: String, address: String) {
        val walletAddress = walletManager.getAddress()
        val entity = database.groupDao().getGroup(groupId, walletAddress) ?: throw IllegalStateException("Unknown group.")
        if (!entity.isAdmin) throw IllegalStateException("Only the group admin can resend invites.")
        if (address == walletAddress) return
        val bag = groupSecretStore.loadBag(walletAddress, groupId) ?: throw IllegalStateException("Missing admin group secrets.")
        val privateKey = walletManager.getPrivateKeyBytes()
        val roster = membersOf(entity)
        sendRootControlMessage(entity, roster, bag, address, privateKey)
    }

    private suspend fun rotateEpoch(groupId: String, reason: String, mutateRoster: (MutableList<GroupMember>) -> Unit) {
        val walletAddress = walletManager.getAddress()
        val entity = database.groupDao().getGroup(groupId, walletAddress) ?: throw IllegalStateException("Unknown group.")
        if (!entity.isAdmin) throw IllegalStateException("Only the group admin can change membership.")
        val bag = groupSecretStore.loadBag(walletAddress, groupId) ?: throw IllegalStateException("Missing admin group secrets.")
        val groupSeed = bag.groupSeed?.hexToByteArray() ?: throw IllegalStateException("Missing admin group secrets.")
        val groupIdBytes = groupId.hexToByteArray()
        val privateKey = walletManager.getPrivateKeyBytes()

        val previousRoster = membersOf(entity)
        val roster = previousRoster.toMutableList()
        mutateRoster(roster)

        val newEpoch = bag.currentEpoch + 1
        val newRoot = GroupCipher.deriveGroupRootEpoch(groupSeed, groupIdBytes, newEpoch)
        // Keep the outgoing root so this epoch's messages stay readable afterwards - the same
        // archive every non-admin member's bag relies on, so both sides behave identically.
        val newBag = bag.copy(
            currentEpoch = newEpoch,
            groupRootEpoch = newRoot.toHexString(),
            previousRoots = bag.previousRoots.orEmpty() + (bag.currentEpoch to bag.groupRootEpoch),
        )
        groupSecretStore.saveBag(walletAddress, newBag)

        val updatedEntity = entity.copy(currentEpoch = newEpoch, membersJson = gson.toJson(roster))
        database.groupDao().upsertGroup(updatedEntity)

        // iMessage-style membership lines for the admin who made the change (other members get
        // theirs when they receive the rotated root — see completeJoin).
        emitMembershipSystemMessages(groupId, walletAddress, previousRoster, roster, System.currentTimeMillis())

        for (member in roster) {
            if (member.address == walletAddress) continue
            try {
                sendEpochControlMessage(groupIdBytes, newEpoch, reason, member.address, privateKey)
                sendRootControlMessage(updatedEntity, roster, newBag, member.address, privateKey)
            } catch (e: Exception) {
                // Best effort, same as createGroup - one member's failed delivery doesn't block the rest.
            }
        }
        // A newly-added member should also receive the current group photo (root doesn't carry it).
        updatedEntity.photoHex?.let { try { distributeGroupPhoto(updatedEntity, roster, it, privateKey) } catch (e: Exception) {} }
    }

    /**
     * Deletes a group locally: its message history, Keystore-held secrets (root/seed/blinding
     * key), and roster. Local-only, like leaving/deleting a broadcast channel - there's no
     * server-side group record to delete, and other members aren't notified (the trust model
     * is single-admin push, not a shared membership ledger, so this device simply stops
     * tracking the group and can no longer decrypt or send to it).
     */
    suspend fun deleteGroup(groupId: String) {
        val walletAddress = walletManager.getAddress()
        groupSecretStore.deleteBag(walletAddress, groupId)
        database.groupDao().deleteMessagesForGroup(groupId, walletAddress)
        database.reactionDao().deleteAllForGroup(groupId, walletAddress)
        database.groupDao().deleteGroup(groupId, walletAddress)
        // Tombstone it so discovery/recovery never re-adds it, and publish an on-chain delete
        // marker (best-effort; the sync backfill retries) so the delete survives a seedless
        // re-import too. Local intent is recorded now; the chain write is best-effort.
        groupSecretStore.recordTombstone(walletAddress, groupId, published = false)
        try { publishGroupTombstone(walletAddress, groupId) } catch (e: Exception) {
            Log.w("GroupRepository", "Group tombstone publish failed for ${groupId.take(12)}", e)
        }
    }

    /** Self-addressed, self-signed delete marker — only our key can produce one, only our key
     *  can read it (see the signing_pub == self + verify check in handleIncomingControlMessage). */
    private suspend fun publishGroupTombstone(walletAddress: String, groupId: String) {
        val privateKey = walletManager.getPrivateKeyBytes()
        val signingPub = Schnorr.publicKeyXOnly(privateKey)
        val selfXOnlyPub = xOnlyPubKeyOrNull(walletAddress) ?: return
        val payload = GroupCipher.buildSignedTombstonePayload(groupId.hexToByteArray(), signingPub, privateKey)
        val json = GroupCipher.tombstonePayloadToJson(payload)
        sendControlPayload(json, selfXOnlyPub, privateKey)
        groupSecretStore.markTombstonePublished(walletAddress, groupId)
    }

    /** Deletes individual messages from this device only - local-only, never on-chain (other
     *  members still have their own copy, and the underlying transaction is still permanently on
     *  the blockchain). Used by GroupChatThreadScreen's message multi-select "Delete". */
    suspend fun deleteMessages(messageIds: Collection<String>) {
        val walletAddress = walletManager.getAddress()
        messageIds.forEach { database.groupDao().deleteMessage(it, walletAddress) }
    }

    // -------------------------------------------------------------------------
    // Sending group messages
    // -------------------------------------------------------------------------

    /**
     * Sends a photo to the group - same [ImageMessage]/[VoiceMessageContent] JSON envelope 1:1
     * chat uses, just carried as a `gcomm` message's plaintext instead of a 1:1 comm payload.
     * Reusing the exact envelope shape means [com.kachat.app.ui.screens.ImageBubble]/
     * [com.kachat.app.util.ImageMessage] render it with no changes.
     */
    suspend fun sendGroupImage(imageBytes: ByteArray, groupId: String, fileName: String = "photo.jpg", mimeType: String = "image/jpeg"): String {
        val base64 = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
        val json = com.kachat.app.util.ImageMessage.encode(fileName = fileName, sizeBytes = imageBytes.size.toLong(), base64Image = base64, mimeType = mimeType)
        return sendGroupMessage(json, groupId)
    }

    /** Sends a voice message to the group - same envelope/reuse rationale as [sendGroupImage]. */
    suspend fun sendGroupAudio(audioBytes: ByteArray, groupId: String, fileName: String = "voice.webm", mimeType: String = "audio/webm"): String {
        val base64 = android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP)
        val json = com.kachat.app.util.VoiceMessage.encode(fileName = fileName, sizeBytes = audioBytes.size.toLong(), base64Audio = base64, mimeType = mimeType)
        return sendGroupMessage(json, groupId)
    }

    suspend fun sendGroupMessage(text: String, groupId: String): String {
        val walletAddress = walletManager.getAddress()
        database.groupDao().getGroup(groupId, walletAddress) ?: throw IllegalStateException("Unknown group.")
        val bag = groupSecretStore.loadBag(walletAddress, groupId) ?: throw IllegalStateException("Missing group secrets - try rejoining this group.")

        val groupIdBytes = groupId.hexToByteArray()
        val groupRootEpoch = bag.groupRootEpoch.hexToByteArray()
        val blindingKey = bag.blindingKey.hexToByteArray()
        val deviceId = bag.deviceId.hexToByteArray()
        val privateKey = walletManager.getPrivateKeyBytes()
        val senderXOnlyPub = Schnorr.publicKeyXOnly(privateKey)
        val senderId = GroupCipher.deriveSenderId(walletAddress)

        // Persist the incremented counter BEFORE building/sending - a msg_id must never be
        // reused even if the send itself later fails.
        val counter = bag.msgCounter + 1
        groupSecretStore.saveBag(walletAddress, bag.copy(msgCounter = counter))

        val msgId = GroupCipher.buildMsgId(deviceId, counter)
        val ciphertext = GroupCipher.encryptMessage(text, groupRootEpoch, groupIdBytes, bag.currentEpoch, senderId, msgId)
        val aad = GroupCipher.buildMessageAAD(groupIdBytes, bag.currentEpoch, senderId, msgId)
        val signature = GroupCipher.sign(GroupCipher.buildMessageSigningPayload(aad, ciphertext), privateKey)
        val blindedGroupId = GroupCipher.deriveBlindedGroupId(blindingKey, senderXOnlyPub)
        val payloadString = GroupCipher.buildGroupMessagePayload(blindedGroupId, bag.currentEpoch, senderId, senderXOnlyPub, msgId, ciphertext, signature)

        val pendingId = "pending_${UUID.randomUUID()}"
        val nowMs = System.currentTimeMillis()
        database.groupDao().insertMessage(
            GroupMessageEntity(
                txId = pendingId, walletAddress = walletAddress, groupId = groupId, senderAddress = walletAddress,
                senderIdHex = senderId.toHexString(), epoch = bag.currentEpoch, msgIdHex = msgId.toHexString(),
                contentEncryptedHex = ciphertext.toHexString(), blockTimestamp = nowMs, isOutgoing = true, deliveryStatus = "pending"
            )
        )
        try {
            val txId = walletService.sendKaspa(toAddress = walletAddress, amountSompi = 0, payloadBytes = payloadString.toByteArray(Charsets.UTF_8))
            database.groupDao().deleteMessage(pendingId, walletAddress)
            database.groupDao().insertMessage(
                GroupMessageEntity(
                    txId = txId, walletAddress = walletAddress, groupId = groupId, senderAddress = walletAddress,
                    senderIdHex = senderId.toHexString(), epoch = bag.currentEpoch, msgIdHex = msgId.toHexString(),
                    contentEncryptedHex = ciphertext.toHexString(), blockTimestamp = nowMs, isOutgoing = true, deliveryStatus = "sent"
                )
            )
            return txId
        } catch (e: Exception) {
            database.groupDao().updateMessageStatus(pendingId, walletAddress, "failed")
            throw e
        }
    }

    /**
     * Reacts to [targetTxId] with [emoji] ("add"), or removes this wallet's existing reaction on
     * it ("remove"). Unlike [sendGroupMessage], this never creates a visible pending bubble - the
     * reaction is applied to the local reactions table immediately (optimistic UI) and the actual
     * send reuses the exact same single self-stash broadcast [sendGroupMessage] uses, which
     * already reaches every member via the shared group root key - no per-member fan-out needed.
     */
    suspend fun sendGroupReaction(targetTxId: String, groupId: String, emoji: String, action: String) {
        val walletAddress = walletManager.getAddress()
        database.groupDao().getGroup(groupId, walletAddress) ?: throw IllegalStateException("Unknown group.")
        val bag = groupSecretStore.loadBag(walletAddress, groupId) ?: throw IllegalStateException("Missing group secrets - try rejoining this group.")

        val groupIdBytes = groupId.hexToByteArray()
        val groupRootEpoch = bag.groupRootEpoch.hexToByteArray()
        val blindingKey = bag.blindingKey.hexToByteArray()
        val deviceId = bag.deviceId.hexToByteArray()
        val privateKey = walletManager.getPrivateKeyBytes()
        val senderXOnlyPub = Schnorr.publicKeyXOnly(privateKey)
        val senderId = GroupCipher.deriveSenderId(walletAddress)
        val payload = MessageReaction.encode(targetTxId, emoji, action)

        if (action == "add") {
            // Optimistically pending (no icon) - flips to sent (green checkmark) on submit below, or
            // failed (red error + Retry) in the catch.
            database.reactionDao().upsertReaction(
                ReactionEntity(
                    targetTxId = targetTxId, walletAddress = walletAddress, reactorAddress = walletAddress,
                    emoji = emoji, reactionTxId = null, blockTimestamp = System.currentTimeMillis(), groupId = groupId,
                    deliveryStatus = "pending"
                )
            )
        } else {
            database.reactionDao().deleteReaction(targetTxId, walletAddress, walletAddress)
        }

        // Persist the incremented counter BEFORE building/sending - a msg_id must never be
        // reused even if the send itself later fails.
        val counter = bag.msgCounter + 1
        groupSecretStore.saveBag(walletAddress, bag.copy(msgCounter = counter))

        val msgId = GroupCipher.buildMsgId(deviceId, counter)
        val ciphertext = GroupCipher.encryptMessage(payload, groupRootEpoch, groupIdBytes, bag.currentEpoch, senderId, msgId)
        val aad = GroupCipher.buildMessageAAD(groupIdBytes, bag.currentEpoch, senderId, msgId)
        val signature = GroupCipher.sign(GroupCipher.buildMessageSigningPayload(aad, ciphertext), privateKey)
        val blindedGroupId = GroupCipher.deriveBlindedGroupId(blindingKey, senderXOnlyPub)
        val payloadString = GroupCipher.buildGroupMessagePayload(blindedGroupId, bag.currentEpoch, senderId, senderXOnlyPub, msgId, ciphertext, signature)

        try {
            val txId = walletService.sendKaspa(toAddress = walletAddress, amountSompi = 0, payloadBytes = payloadString.toByteArray(Charsets.UTF_8))

            if (action == "add") {
                // Default deliveryStatus "sent" also clears any prior failed flag (e.g. this was a Retry).
                database.reactionDao().upsertReaction(
                    ReactionEntity(
                        targetTxId = targetTxId, walletAddress = walletAddress, reactorAddress = walletAddress,
                        emoji = emoji, reactionTxId = txId, blockTimestamp = System.currentTimeMillis(), groupId = groupId
                    )
                )
            }
        } catch (e: Exception) {
            // Flag failed so the pill shows the red error icon and a Retry appears under the message.
            // A failed "remove" restores the optimistically-deleted reaction (marked failed) so it
            // isn't silently lost; Retry re-attempts the change.
            database.reactionDao().upsertReaction(
                ReactionEntity(
                    targetTxId = targetTxId, walletAddress = walletAddress, reactorAddress = walletAddress,
                    emoji = emoji, reactionTxId = null, blockTimestamp = System.currentTimeMillis(), groupId = groupId,
                    deliveryStatus = "failed", failedAction = action
                )
            )
            throw e
        }
    }

    // -------------------------------------------------------------------------
    // Control message send (gctl_root / gctl_epoch)
    // -------------------------------------------------------------------------

    private suspend fun sendRootControlMessage(entity: GroupEntity, roster: List<GroupMember>, bag: GroupBag, recipientAddress: String, adminPrivateKey: ByteArray) {
        val recipientXOnlyPub = xOnlyPubKeyOrNull(recipientAddress) ?: throw IllegalArgumentException("Invalid address")
        val groupIdBytes = entity.groupId.hexToByteArray()
        val rootPayload = GroupCipher.buildSignedRootPayload(
            groupId = groupIdBytes, epoch = bag.currentEpoch, groupRootEpoch = bag.groupRootEpoch.hexToByteArray(),
            blindingKey = bag.blindingKey.hexToByteArray(), adminSigningPub = entity.adminXOnlyPubKeyHex.hexToByteArray(),
            members = roster.map { it.address }, name = entity.name, adminPrivateKey = adminPrivateKey
        )
        val json = GroupCipher.rootPayloadToJson(rootPayload)
        sendControlPayload(json, recipientXOnlyPub, adminPrivateKey)
    }

    /** Self-addressed recovery copy carrying the group seed (ECIES to our own key, so members
     *  never see it). A seedless re-import rediscovers the group via the by-recipient scan and
     *  rebuilds it as admin. Marks selfInviteEpoch on the bag. */
    private suspend fun sendSelfRootControlMessage(entity: GroupEntity, roster: List<GroupMember>, bag: GroupBag, adminPrivateKey: ByteArray) {
        val walletAddress = walletManager.getAddress()
        val seedHex = bag.groupSeed ?: return
        val selfXOnlyPub = xOnlyPubKeyOrNull(walletAddress) ?: return
        val rootPayload = GroupCipher.buildSignedRootPayload(
            groupId = entity.groupId.hexToByteArray(), epoch = bag.currentEpoch, groupRootEpoch = bag.groupRootEpoch.hexToByteArray(),
            blindingKey = bag.blindingKey.hexToByteArray(), adminSigningPub = entity.adminXOnlyPubKeyHex.hexToByteArray(),
            members = roster.map { it.address }, name = entity.name, adminPrivateKey = adminPrivateKey,
            groupSeed = seedHex.hexToByteArray()
        )
        val json = GroupCipher.rootPayloadToJson(rootPayload)
        sendControlPayload(json, selfXOnlyPub, adminPrivateKey)
        groupSecretStore.saveBag(walletAddress, bag.copy(selfInviteEpoch = bag.currentEpoch))
    }

    private suspend fun sendEpochControlMessage(groupIdBytes: ByteArray, epoch: Long, reason: String, recipientAddress: String, adminPrivateKey: ByteArray) {
        val recipientXOnlyPub = xOnlyPubKeyOrNull(recipientAddress) ?: throw IllegalArgumentException("Invalid address")
        val epochPayload = GroupCipher.buildSignedEpochPayload(groupIdBytes, epoch, reason, adminPrivateKey)
        val json = GroupCipher.epochPayloadToJson(epochPayload)
        sendControlPayload(json, recipientXOnlyPub, adminPrivateKey)
    }

    /**
     * Wire format is recipient-addressed (`ciph_msg:1:gctl:{recipient_xonly}:{encrypted}`), not
     * the legacy unaddressed shape - see docs/GROUP_CHAT_API.md. This lets a brand-new member
     * discover a "you were added" control via `GET /group-control/by-recipient` before it knows
     * the admin's address at all, and lets push route it to their device even with zero
     * locally-known groups (no more indexer-side fan-out-to-everyone fallback).
     */
    private suspend fun sendControlPayload(json: String, recipientXOnlyPub: ByteArray, privateKey: ByteArray) {
        val walletAddress = walletManager.getAddress()
        val encrypted = KasiaCipher.encrypt(json, recipientXOnlyPub)
        val payloadString = "kchat:1:gctl:" + recipientXOnlyPub.toHexString() + ":" + encrypted.toBytes().toHexString()
        // Retry to ride out UTXO contention: each member's invite is its own tx, and a
        // back-to-back send fails until the prior tx's change output settles. Without this a
        // multi-member invite can silently drop the 2nd+ members.
        val attempts = 4
        var lastError: Exception? = null
        for (i in 0 until attempts) {
            try {
                walletService.sendKaspa(toAddress = walletAddress, amountSompi = 0, payloadBytes = payloadString.toByteArray(Charsets.UTF_8))
                return
            } catch (e: Exception) {
                lastError = e
                if (i < attempts - 1) kotlinx.coroutines.delay(1800)
            }
        }
        throw lastError ?: IllegalStateException("Group invite send failed.")
    }

    // -------------------------------------------------------------------------
    // Incoming payload handlers - called from GroupScanningService
    // -------------------------------------------------------------------------

    suspend fun handleIncomingGroupMessage(parsed: GroupCipher.ParsedGroupMessage, txId: String, blockTimestamp: Long) {
        val walletAddress = walletManager.getAddress()
        val hrp = walletAddress.substringBefore(":")
        val groups = database.groupDao().getGroupsOnce(walletAddress)
        for (group in groups) {
            val bag = groupSecretStore.loadBag(walletAddress, group.groupId) ?: continue
            val blindingKey = try { bag.blindingKey.hexToByteArray() } catch (e: Exception) { continue }
            val groupIdBytes = try { group.groupId.hexToByteArray() } catch (e: Exception) { continue }

            val candidateBlindedId = GroupCipher.deriveBlindedGroupId(blindingKey, parsed.senderPubKey)
            if (!candidateBlindedId.contentEquals(parsed.blindedGroupId)) continue

            // Found the group. Verify sender identity: pubkey -> address -> in roster -> hashes to senderId.
            val senderAddress = KaspaAddress.encode(hrp, 0x00, parsed.senderPubKey)
            val roster = membersOf(group)
            if (roster.none { it.address == senderAddress }) {
                Log.w("GroupRepository", "Rejected gcomm for group ${group.groupId.take(12)}: sender $senderAddress not in roster ${roster.map { it.address }}")
                return
            }
            if (!GroupCipher.deriveSenderId(senderAddress).contentEquals(parsed.senderId)) {
                Log.w("GroupRepository", "Rejected gcomm for group ${group.groupId.take(12)}: senderId mismatch for $senderAddress")
                return
            }

            val aad = GroupCipher.buildMessageAAD(groupIdBytes, parsed.epoch, parsed.senderId, parsed.msgId)
            val signingPayload = GroupCipher.buildMessageSigningPayload(aad, parsed.ciphertext)
            if (!GroupCipher.verify(parsed.signature, signingPayload, parsed.senderPubKey)) {
                Log.w("GroupRepository", "Rejected gcomm for group ${group.groupId.take(12)}: bad signature from $senderAddress")
                return
            }

            val root = groupRootEpochFor(parsed.epoch, bag, groupIdBytes)
            if (root == null) {
                Log.w("GroupRepository", "Rejected gcomm for group ${group.groupId.take(12)}: no root for epoch ${parsed.epoch} (local currentEpoch=${bag.currentEpoch})")
                return
            }
            val plaintext = GroupCipher.decryptMessage(parsed.ciphertext, root, groupIdBytes, parsed.epoch, parsed.senderId, parsed.msgId)
            if (plaintext == null) {
                Log.w("GroupRepository", "Rejected gcomm for group ${group.groupId.take(12)}: decrypt failed from $senderAddress")
                return
            }

            // Decrypted successfully for a known group: remember the txId so the FCM group push
            // handler knows this tx was ingested locally (and can skip its generic fallback
            // banner) — see isGroupTxIngested.
            markGroupTxHandled(txId)

            // Reactions are never shown as their own chat bubble - just attached to the message
            // they target - so intercept and route to the reactions table before this ever
            // becomes a GroupMessageEntity row. Our own outgoing reactions already apply their
            // local update at send time (sendGroupReaction), so this mainly covers incoming ones.
            val reaction = MessageReaction.parseOrNull(plaintext)
            if (reaction != null) {
                if (reaction.action == "add") {
                    // Same reaction txId already applied by another delivery path (live scan vs
                    // catch-up sync vs push-triggered sync) - must not notify a second time.
                    val alreadyApplied = database.reactionDao()
                        .getReaction(reaction.targetTxId, walletAddress, senderAddress)?.reactionTxId == txId
                    database.reactionDao().upsertReaction(
                        ReactionEntity(
                            targetTxId = reaction.targetTxId, walletAddress = walletAddress, reactorAddress = senderAddress,
                            emoji = reaction.emoji, reactionTxId = txId, blockTimestamp = blockTimestamp, groupId = group.groupId
                        )
                    )
                    // "Alice reacted 👍 to your message" / "... to a message" for a live,
                    // incoming reaction (matches iOS: title is the group name, emoji omitted
                    // when absent). Muted members stay silent. In a mentions-only group, only a
                    // reaction to YOUR OWN message notifies - it's as personal as a reply to you
                    // - while reactions to others' messages stay silent there.
                    // NotificationHelper's txId dedupe additionally collapses a racing FCM push
                    // for the same reaction.
                    if (!alreadyApplied && senderAddress != walletAddress && !isBackfill(blockTimestamp)) {
                        val target = database.groupDao().getMessage(reaction.targetTxId, walletAddress)
                        val targetIsMine = target?.isOutgoing == true
                        val isMuted = "${group.groupId}|$senderAddress" in settings.groupMutedMembers.first()
                        val silent = group.groupId in settings.groupSilent.first()
                        val mentionsOnly = group.groupId in settings.groupMentionsOnly.first()
                        if (!silent && !isMuted && (targetIsMine || !mentionsOnly)) {
                            // alias > KNS primary domain > roster snapshot > short address -
                            // see groupSenderLabel; decodes the reactor the same way the chat
                            // cards and thread do.
                            val senderLabel = groupSenderLabel(senderAddress, walletAddress, group)
                            val emojiPart = reaction.emoji.trim().takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""
                            val targetPhrase = if (targetIsMine) "your message" else "a message"
                            notificationHelper.showGroup(
                                groupId = group.groupId,
                                title = group.name,
                                text = "$senderLabel reacted$emojiPart to $targetPhrase",
                                dedupeTxId = txId
                            )
                        } else {
                            // Deliberate silence (muted reactor, or mentions-only group with a
                            // reaction to someone else's message). Claim the txId anyway so a
                            // racing FCM push for this same reaction can't post the banner this
                            // path just suppressed.
                            notificationHelper.claimWithoutNotifying(txId)
                        }
                    }
                } else {
                    database.reactionDao().deleteReaction(reaction.targetTxId, walletAddress, senderAddress)
                }
                return
            }

            val isOutgoing = senderAddress == walletAddress
            val rowId = database.groupDao().insertMessage(
                GroupMessageEntity(
                    txId = txId, walletAddress = walletAddress, groupId = group.groupId, senderAddress = senderAddress,
                    senderIdHex = parsed.senderId.toHexString(), epoch = parsed.epoch, msgIdHex = parsed.msgId.toHexString(),
                    contentEncryptedHex = parsed.ciphertext.toHexString(), blockTimestamp = blockTimestamp, isOutgoing = isOutgoing, deliveryStatus = "sent"
                )
            )
            // rowId == -1 means insertMessage's IGNORE conflict strategy dropped it as an
            // already-seen txId (e.g. catch-up re-fetching something the live scan already
            // processed) - only notify for a genuinely new, incoming (not our own) message.
            if (rowId != -1L && !isOutgoing) {
                if (isBackfill(blockTimestamp)) {
                    // History being backfilled (e.g. right after an account import): keep the
                    // group read and silent - only live traffic notifies or counts unread.
                    markGroupRead(group.groupId)
                    return
                }
                // Global notification center (Profile bell): list this message when it
                // @mentions the wallet's own KNS domain (deduped by txId inside the store).
                notificationCenter.recordGroupMentionIfNeeded(
                    groupId = group.groupId, groupName = group.name,
                    senderAddress = senderAddress,
                    senderName = com.kachat.app.util.KaspaAddress.shortDisplay(senderAddress),
                    text = plaintext, txId = txId, timestampMs = blockTimestamp,
                )
                if (notificationHelper.isViewingGroup(group.groupId)) {
                    // Already looking at this group's thread right now - keep it marked read
                    // instead of letting the badge tick up for a message arriving live.
                    markGroupRead(group.groupId)
                }
                val replyContent = MessageReply.parseOrNull(plaintext)
                // Muting still lets the message show up in the thread (getMessages isn't
                // filtered on mute, only on hide) - it only skips the notification, same as
                // iOS's mute (enforced there via push-registration exclusion instead, since
                // Android has no remote push to gate the same way).
                val isMuted = "${group.groupId}|$senderAddress" in settings.groupMutedMembers.first()
                val mentionsOnly = group.groupId in settings.groupMentionsOnly.first()
                val silent = group.groupId in settings.groupSilent.first()
                // "Mentions me" uses the SAME definition as the composer's @mention feature:
                // members are mentioned by their primary KNS domain (insertMention writes
                // "@domain"), so match the shared @token grammar against our own reverse-resolved
                // domain via GlobalNotificationCenterStore.mentionsMyDomain — never a naive
                // substring scan. A raw "@<full address>" paste still counts as a mention.
                // Only evaluated when the group is mentions-only (short-circuit).
                val mentionsMe = mentionsOnly &&
                    (plaintext.contains("@$walletAddress") || notificationCenter.mentionsMyDomain(plaintext))
                val isReplyToMe = replyContent?.replyToSender == walletAddress
                // Silent wins over everything else.
                if (!silent && !isMuted && (!mentionsOnly || mentionsMe || isReplyToMe)) {
                    // alias > KNS primary domain > roster snapshot > short address - see
                    // groupSenderLabel; same chain the reaction banner and the chat cards use.
                    val senderLabel = groupSenderLabel(senderAddress, walletAddress, group)
                    val notificationText = when {
                        replyContent != null -> "$senderLabel replied to \"${replyContent.replyToPreview}\""
                        VoiceMessage.parseOrNull(plaintext) != null -> "$senderLabel sent a voice message"
                        ImageMessage.parseOrNull(plaintext) != null -> "$senderLabel sent a photo"
                        else -> "$senderLabel: $plaintext"
                    }
                    notificationHelper.showGroup(group.groupId, group.name, notificationText, dedupeTxId = txId)
                } else {
                    // Deliberate silence (muted sender, or mentions-only group and this message
                    // neither mentions us nor replies to us). Claim the txId anyway so a racing
                    // FCM push for this same message can't post the banner this path just
                    // suppressed.
                    notificationHelper.claimWithoutNotifying(txId)
                }
            }
            return
        }
        Log.w("GroupRepository", "Rejected gcomm: no local group matched blindedGroupId ${parsed.blindedGroupId.toHexString()}")
    }

    suspend fun handleIncomingControlMessage(payloadString: String, senderAddress: String, blockTime: Long? = null) {
        val walletAddress = walletManager.getAddress()
        // Normally ignore our own echoed controls — EXCEPT the self-addressed recovery root
        // (sender == us, carries group_seed), which is how a seedless import rebuilds an admin
        // group. A non-recovery self-echo is dropped in completeJoin below.
        val isSelfSent = senderAddress == walletAddress
        val privateKey = walletManager.getPrivateKeyBytes()
        // Dual-read: strip whichever gctl root the payload carries (new kchat: or legacy).
        val prefix = when {
            payloadString.startsWith("kchat:1:gctl:") -> "kchat:1:gctl:"
            payloadString.startsWith("ciph_msg:1:gctl:") -> "ciph_msg:1:gctl:"
            else -> return
        }
        val hexPayload = payloadString.substring(prefix.length)
        val encryptedBytes = try { hexPayload.hexToByteArray() } catch (e: Exception) { return }
        val encrypted = KasiaCipher.EncryptedMessage.fromBytes(encryptedBytes) ?: return
        val plaintext = try { KasiaCipher.decrypt(encrypted, privateKey) } catch (e: Exception) { return }

        // A self-addressed delete marker — honor ONLY our own (signed by + addressed to us).
        val tomb = GroupCipher.tombstonePayloadFromJson(plaintext)
        if (tomb != null && tomb.type == "gctl_tombstone") {
            val myPub = try { Schnorr.publicKeyXOnly(privateKey).toHexString() } catch (e: Exception) { null }
            if (tomb.signingPub == myPub && GroupCipher.verifyTombstonePayload(tomb)) {
                groupSecretStore.recordTombstone(walletAddress, tomb.groupId, published = true)
                // Remove locally WITHOUT re-publishing (record already done above).
                groupSecretStore.deleteBag(walletAddress, tomb.groupId)
                database.groupDao().deleteMessagesForGroup(tomb.groupId, walletAddress)
                database.reactionDao().deleteAllForGroup(tomb.groupId, walletAddress)
                database.groupDao().deleteGroup(tomb.groupId, walletAddress)
            }
            return
        }
        // An admin-set group photo — apply ONLY if signed by THIS group's known admin.
        val photo = GroupCipher.photoPayloadFromJson(plaintext)
        if (photo != null && photo.type == "gctl_photo") {
            val entity = database.groupDao().getGroup(photo.groupId, walletAddress) ?: return
            if (photo.signingPub == entity.adminXOnlyPubKeyHex && GroupCipher.verifyPhotoPayload(photo)) {
                val newHex = photo.photo.ifEmpty { null }
                val changed = entity.photoHex != newHex
                database.groupDao().upsertGroup(entity.copy(photoHex = newHex))
                // iMessage-style line for members (the admin emits its own in setGroupPhoto).
                if (changed && !isBackfill(blockTime)) {
                    val who = if (entity.isAdmin) "You" else groupMemberLabel(entity.adminAddress, walletAddress, null)
                    insertGroupSystemMessage(photo.groupId, walletAddress,
                        if (newHex == null) "$who removed the group photo" else "$who changed the group photo",
                        blockTime ?: System.currentTimeMillis())
                }
            }
            return
        }
        val rootPayload = GroupCipher.rootPayloadFromJson(plaintext)
        if (rootPayload != null && rootPayload.type == "gctl_root" && GroupCipher.verifyRootPayload(rootPayload)) {
            if (isSelfSent && rootPayload.groupSeed == null) return // our own member-copy echo
            completeJoin(rootPayload, blockTime)
        }
        // gctl_epoch is an advance-notice heads-up only (state updates on gctl_root arrival,
        // not on gctl_epoch) - no local state change needed here in the data layer.
    }

    /** See [AppSettingsRepository.liveNotificationBaseline]: history older than this account's
     *  first sync on this device is backfill — kept read and silent. A null [blockTime]
     *  (live block scan, no timestamp in hand) always counts as live. */
    private suspend fun isBackfill(blockTime: Long?): Boolean {
        val walletAddress = try { walletManager.getAddress() } catch (e: Exception) { return true }
        return (blockTime ?: Long.MAX_VALUE) < settings.liveNotificationBaseline(walletAddress)
    }

    /** Applies a verified gctl_root payload: creates or updates the local group secrets + roster. Refuses to downgrade to an older epoch than what's already stored (replay protection). */
    private suspend fun completeJoin(payload: GroupCipher.GroupRootPayload, blockTime: Long? = null) {
        val walletAddress = walletManager.getAddress()
        // A tombstoned group must never be re-added — this makes a delete survive a seedless
        // re-import against the recovery invite.
        if (groupSecretStore.isTombstoned(walletAddress, payload.groupId)) return
        val existingBag = groupSecretStore.loadBag(walletAddress, payload.groupId)
        if (existingBag != null && existingBag.currentEpoch > payload.epoch) {
            // A root for an OLDER epoch is not an attack to drop on the floor - it is history
            // this device may no longer hold. Archiving it is strictly additive (currentEpoch and
            // the current root are untouched, so there is no downgrade), and it is what makes
            // re-walking the control stream actually repair a thread: without it a refresh
            // re-downloads pre-rotation ciphertext it still has no key for, which is why
            // refreshing appeared to do nothing.
            if (payload.groupRootEpoch.isNotBlank() &&
                existingBag.previousRoots?.get(payload.epoch) != payload.groupRootEpoch
            ) {
                groupSecretStore.saveBag(
                    walletAddress,
                    existingBag.copy(
                        previousRoots = existingBag.previousRoots.orEmpty() +
                            (payload.epoch to payload.groupRootEpoch)
                    )
                )
            }
            return
        }
        val isFirstTimeJoin = existingBag == null

        // device_id is persistent per device - preserve it across epoch-rotation updates to an
        // already-joined group; only a genuinely first-time join mints a new one. msgCounter
        // resets to 0 only when the epoch actually advances - a same-epoch re-send of the root
        // (e.g. `renameGroup`, which doesn't rotate the epoch, or any other duplicate delivery)
        // must NOT reset it, since a msg_id must never be reused - resetting here would let this
        // device's next send collide with a counter value it already used earlier in the same
        // epoch. groupSeed is preserved defensively in case this device somehow already held
        // admin secrets for this group.
        val deviceId = existingBag?.deviceId ?: GroupCipher.generateDeviceId().toHexString()
        val preservedCounter = if (existingBag?.currentEpoch == payload.epoch) existingBag.msgCounter else 0
        // Admin self-recovery: a self-addressed root carries the group seed. Trust it ONLY if it
        // re-derives the SIGNED group_id + blinding_key (that binding authenticates the otherwise
        // unsigned seed). When valid, this is our own group and we hold admin secrets again.
        var recoveredSeedHex: String? = null
        val seedHex = payload.groupSeed
        val myXOnlyPubHex = try { Schnorr.publicKeyXOnly(walletManager.getPrivateKeyBytes()).toHexString() } catch (e: Exception) { null }
        if (seedHex != null && myXOnlyPubHex != null && payload.adminSigningPub == myXOnlyPubHex) {
            try {
                val seed = seedHex.hexToByteArray()
                val derivedId = GroupCipher.deriveGroupId(seed).toHexString()
                val derivedBlinding = GroupCipher.deriveBlindingKey(seed, payload.groupId.hexToByteArray()).toHexString()
                if (derivedId == payload.groupId && derivedBlinding == payload.blindingKey) recoveredSeedHex = seedHex
            } catch (e: Exception) { recoveredSeedHex = null }
        }
        // Archive the root being replaced. This is THE line that keeps a non-admin member's
        // history: without it, a rotated root made every message from the previous epoch
        // undecryptable and the thread rendered empty from that moment on.
        val carriedRoots = existingBag?.let { previous ->
            if (previous.currentEpoch != payload.epoch && previous.groupRootEpoch.isNotBlank()) {
                previous.previousRoots.orEmpty() + (previous.currentEpoch to previous.groupRootEpoch)
            } else {
                previous.previousRoots.orEmpty()
            }
        } ?: emptyMap()
        val bag = GroupBag(
            groupId = payload.groupId, groupSeed = recoveredSeedHex ?: existingBag?.groupSeed, groupRootEpoch = payload.groupRootEpoch,
            blindingKey = payload.blindingKey, currentEpoch = payload.epoch, deviceId = deviceId, msgCounter = preservedCounter,
            selfInviteEpoch = if (recoveredSeedHex != null) payload.epoch else existingBag?.selfInviteEpoch,
            previousRoots = carriedRoots.ifEmpty { null },
        )
        groupSecretStore.saveBag(walletAddress, bag)

        val members = payload.members.mapNotNull { address ->
            val xOnlyPub = xOnlyPubKeyOrNull(address) ?: return@mapNotNull null
            GroupMember(address = address, xOnlyPubKeyHex = xOnlyPub.toHexString(), isAdmin = xOnlyPub.toHexString() == payload.adminSigningPub)
        }
        val adminAddress = try {
            KaspaAddress.encode("kaspa", 0x00, payload.adminSigningPub.hexToByteArray())
        } catch (e: Exception) {
            members.firstOrNull { it.isAdmin }?.address ?: ""
        }

        // Membership diff for iMessage-style system lines: capture the roster we held BEFORE this
        // root updates it, so a receiving member sees "X was added"/"Y was removed" when the admin
        // rotates the key. Skipped on a first-time join and on backfill (no false "added" storm for
        // the members who were already there when we joined).
        val existingEntity = database.groupDao().getGroup(payload.groupId, walletAddress)
        val backfill = isBackfill(blockTime)
        val previousRosterForDiff: List<GroupMember>? =
            if (!isFirstTimeJoin && !backfill) existingEntity?.let(::membersOf) else null

        val entity = GroupEntity(
            groupId = payload.groupId, walletAddress = walletAddress, name = payload.name, adminAddress = adminAddress,
            adminXOnlyPubKeyHex = payload.adminSigningPub, currentEpoch = payload.epoch, isAdmin = walletAddress == adminAddress,
            membersJson = gson.toJson(members),
            // upsertGroup is a whole-row REPLACE and this path builds a fresh entity (it can't use
            // entity.copy() - there may be no existing row), so read state and creation time must
            // carry over explicitly. Without this, every received root (rename, roster change,
            // epoch rotation, recovery root) reset lastReadAt to null and flipped the whole
            // thread's messages back to unread. A backfilled first-time join (group re-discovered
            // during an import's history sync) stamps "now" so restored history never surfaces as
            // an unread badge - only a genuinely live invite stays null ("new group" badge).
            createdAt = existingEntity?.createdAt ?: System.currentTimeMillis(),
            lastReadAt = existingEntity?.lastReadAt ?: if (backfill) System.currentTimeMillis() else null,
            // Group photo rides a separate gctl_photo control, not the root - preserve any we hold.
            photoHex = existingEntity?.photoHex
        )
        database.groupDao().upsertGroup(entity)

        previousRosterForDiff?.let { prev ->
            emitMembershipSystemMessages(payload.groupId, walletAddress, prev, members, blockTime ?: System.currentTimeMillis())
        }
        // iMessage-style rename line for members (the admin emits its own in renameGroup).
        if (existingEntity != null && existingEntity.name != payload.name && !backfill) {
            val who = if (walletAddress == adminAddress) "You" else groupMemberLabel(adminAddress, walletAddress, null)
            insertGroupSystemMessage(payload.groupId, walletAddress, "$who changed the group name to \"${payload.name}\"", blockTime ?: System.currentTimeMillis())
        }

        if (isFirstTimeJoin && !backfill) {
            // Title is the group, like every other group banner - it was blank here, so the
            // shade showed a title-less notification.
            notificationHelper.showGroup(payload.groupId, payload.name, "You were added to this group")
        }
    }

    // -------------------------------------------------------------------------
    // Catch-up sync (indexer-backed, for when the device wasn't actively block-scanning)
    // -------------------------------------------------------------------------

    /**
     * Fetches missed `gcomm`/`gctl` history from the indexer, so a device that wasn't actively
     * block-scanning while away (backgrounded, killed, or just closed) still catches up. Runs
     * three kinds of sync object, each with its own persisted opaque cursor (see
     * [GroupSyncCursorEntity]):
     *  - `gcomm` per known group member (`blinded_group_id` is per-sender, not per-group, so this
     *    queries once per member, using their blinded id recomputed from the group's shared
     *    blindingKey).
     *  - `gctl` by admin address, for groups already joined.
     *  - `gctl` by our own wallet address (recipient-addressed) - runs unconditionally, even with
     *    zero local groups, since this is what actually discovers "you were added to a group"
     *    without needing to already know the admin. This replaced the indexer's old
     *    fan-out-to-every-device push fallback for that same case.
     */
    suspend fun syncGroups() {
        val api = networkService.indexerApi.value ?: return
        // On a cold foreground before the user has logged in/imported a wallet there's no active
        // account yet, and getAddress() would throw IllegalStateException. Bail out the same way
        // as the missing-api guard above rather than crashing the on-foreground catch-up.
        val walletAddress = walletManager.getActiveAccount()?.address ?: return

        // Backfill recovery invites for any admin group lacking one for its current epoch — i.e.
        // every group created before this feature existed. One-time per group per epoch; once on
        // chain, a seedless import of this wallet rediscovers the group with no cloud backup.
        val privateKeyForBackfill = try { walletManager.getPrivateKeyBytes() } catch (e: Exception) { null }
        if (privateKeyForBackfill != null) {
            for (group in database.groupDao().getGroupsOnce(walletAddress)) {
                if (!group.isAdmin) continue
                val bag = groupSecretStore.loadBag(walletAddress, group.groupId) ?: continue
                if (bag.groupSeed == null || bag.selfInviteEpoch == bag.currentEpoch) continue
                try { sendSelfRootControlMessage(group, membersOf(group), bag, privateKeyForBackfill) } catch (e: Exception) {
                    Log.w("GroupRepository", "Group self-invite backfill failed for ${group.groupId.take(12)}", e)
                }
            }
        }
        // Backfill delete markers for groups deleted while offline (or whose publish failed).
        val tombState = groupSecretStore.loadTombstones(walletAddress)
        for (groupId in tombState.deleted) {
            if (groupId in tombState.published) continue
            try { publishGroupTombstone(walletAddress, groupId) } catch (e: Exception) {
                Log.w("GroupRepository", "Group tombstone backfill failed for ${groupId.take(12)}", e)
            }
        }

        syncGroupControlByRecipient(api, walletAddress)

        val groups = database.groupDao().getGroupsOnce(walletAddress)
        for (group in groups) {
            val bag = groupSecretStore.loadBag(walletAddress, group.groupId) ?: continue
            val blindingKey = try { bag.blindingKey.hexToByteArray() } catch (e: Exception) { continue }

            for (member in membersOf(group)) {
                val memberPubKey = try { member.xOnlyPubKeyHex.hexToByteArray() } catch (e: Exception) { continue }
                val blindedGroupIdHex = GroupCipher.deriveBlindedGroupId(blindingKey, memberPubKey).toHexString()
                syncGroupMessages(api, walletAddress, group.groupId, blindedGroupIdHex)
            }

            if (group.adminAddress.isNotEmpty()) {
                syncGroupControlBySender(api, walletAddress, group.adminAddress)
            }
        }
    }

    /**
     * Re-fetches everything this device is entitled to see in one group, from the beginning.
     *
     * Ordinary catch-up is cursor-based: each stream remembers where it got to and asks only for
     * what is newer. That is right for routine sync and useless as a repair tool - if a cursor
     * ever advanced past a message (an epoch this device could not decrypt at the time, an ingest
     * that failed, a roster it did not yet know about), no amount of waiting goes back for it.
     * This drops those cursors and walks the streams again.
     *
     * Control first, for the same reason [syncGroups] does it in that order: the roster and the
     * current epoch's root have to be in hand before messages are decrypted, or they are rejected
     * and the fresh cursor advances past them all over again.
     */
    suspend fun forceRefreshGroup(
        groupId: String,
        onProgress: (GroupRefreshPhase) -> Unit = {},
    ): Int {
        val walletAddress = walletManager.getAddress()
        val api = networkService.indexerApi.value ?: return 0
        val group = database.groupDao().getGroup(groupId, walletAddress) ?: return 0
        val messagesBefore = database.groupDao().getMessagesOnce(groupId, walletAddress).size

        // Drop every cursor this repair depends on - and the CONTROL streams matter more than
        // the message ones. Dropping only "gcomm" re-downloaded pre-rotation ciphertext while
        // the control cursors stayed put, so the epoch roots needed to read it were never
        // re-acquired: the refresh did real work and changed nothing you could see.
        val controlKeys = buildList {
            add("gctl-recipient|${walletAddress.lowercase()}")
            if (group.adminAddress.isNotBlank()) add("gctl|${group.adminAddress.lowercase()}")
        }
        database.groupDao().deleteGroupSyncCursorsWithPrefix(walletAddress, "gcomm|$groupId|")
        for (key in controlKeys) database.groupDao().deleteGroupSyncCursorsWithPrefix(walletAddress, key)
        // Clear the one-shot deep-backfill marks too, or each walk stops at the first page
        // instead of going back through the whole stream.
        deepBackfilledGroupKeys.removeAll { it.startsWith("gcomm|$groupId|") || it in controlKeys }

        onProgress(GroupRefreshPhase.Invites)
        runCatching { syncGroupControlByRecipient(api, walletAddress) }
        onProgress(GroupRefreshPhase.Control)
        if (group.adminAddress.isNotBlank()) {
            runCatching { syncGroupControlBySender(api, walletAddress, group.adminAddress) }
        }

        // Re-read: control catch-up may have changed the roster or the epoch.
        val refreshed = database.groupDao().getGroup(groupId, walletAddress) ?: return 0
        val bag = groupSecretStore.loadBag(walletAddress, groupId) ?: return 0
        val blindingKey = bag.blindingKey.hexToByteArray()
        val members = gson.fromJson(refreshed.membersJson, Array<GroupMember>::class.java).orEmpty()
        members.forEachIndexed { index, member ->
            onProgress(GroupRefreshPhase.Messages(index, members.size))
            val memberPub = runCatching { member.xOnlyPubKeyHex.hexToByteArray() }.getOrNull()
            if (memberPub != null) {
                val blinded = GroupCipher.deriveBlindedGroupId(blindingKey, memberPub).toHexString()
                runCatching { syncGroupMessages(api, walletAddress, groupId, blinded) }
            }
        }
        onProgress(GroupRefreshPhase.Rebuilding)
        return database.groupDao().getMessagesOnce(groupId, walletAddress).size - messagesBefore
    }

    /** What [forceRefreshGroup] is doing right now, for its progress sheet. */
    sealed interface GroupRefreshPhase {
        data object Invites : GroupRefreshPhase
        data object Control : GroupRefreshPhase
        data class Messages(val done: Int, val total: Int) : GroupRefreshPhase
        data object Rebuilding : GroupRefreshPhase
    }

    /**
     * Pages until the indexer runs out, rather than taking one 50-message page per sync.
     *
     * One page per sync meant a group whose history is longer than 50 messages could only catch
     * up 50 at a time - so a freshly imported wallet joined a busy group and saw a fragment of
     * it. The cursor advances per page, so an interrupted run resumes where it stopped.
     */
    private suspend fun syncGroupMessages(api: KasiaIndexerApi, walletAddress: String, groupId: String, blindedGroupIdHex: String) {
        val syncKey = "gcomm|$groupId|$blindedGroupIdHex"
        // 40 x 50 = 2000 messages in one sync - beyond any real group, and a bound so a
        // misbehaving cursor cannot loop forever.
        // The first run per key starts from NOTHING, not the stored cursor - see
        // [deepBackfilledGroupKeys]. Everything a pre-paging sync skipped lives before that
        // cursor, and walking forward from it can never reach any of it.
        val deep = syncKey !in deepBackfilledGroupKeys
        var cursor: String? = if (deep) null else database.groupDao().getGroupSyncCursor(syncKey, walletAddress)
        var pagesLeft = 40
        while (pagesLeft > 0) {
            pagesLeft -= 1
            val messages: List<GroupMessageIndexerResponse> = try {
                api.getGroupMessagesByBlindedGroupId(blindedGroupIdHex, cursor = cursor)
            } catch (e: Exception) {
                Log.w("GroupRepository", "Catch-up gcomm fetch failed for group ${groupId.take(12)}", e)
                return
            }
            if (messages.isEmpty()) {
                deepBackfilledGroupKeys.add(syncKey)
                return
            }
            cursor = messages.lastOrNull()?.cursor
            advanceGroupSyncCursor(syncKey, walletAddress, cursor)
            for (msg in messages) {
                val payloadString = reconstructPayloadString("kchat:1:gcomm:", msg.messagePayload) ?: continue
                val parsed = GroupCipher.parseGroupMessagePayload(payloadString) ?: continue
                handleIncomingGroupMessage(parsed, msg.txId, msg.blockTime)
            }
            // A short page is the last page. Marked only on a real end, so an interrupted run
            // retries from the beginning rather than leaving a hole behind.
            if (messages.size < 50) {
                deepBackfilledGroupKeys.add(syncKey)
                return
            }
        }
    }

    /**
     * Paged for the same reason as [syncGroupMessages], and it matters more here: control carries
     * epoch advances and roster changes, and stopping halfway through them leaves this device
     * holding a stale root that later messages cannot be decrypted against.
     */
    private suspend fun syncGroupControlBySender(api: KasiaIndexerApi, walletAddress: String, adminAddress: String) {
        val syncKey = "gctl|${adminAddress.lowercase()}"
        // Deep-backfilled once, like gcomm - and it matters more here. A skipped control message
        // is a skipped epoch root, and a member holding no root for the epoch an old message was
        // sent at cannot DECRYPT it, however many times it is fetched.
        val deep = syncKey !in deepBackfilledGroupKeys
        var cursor: String? = if (deep) null else database.groupDao().getGroupSyncCursor(syncKey, walletAddress)
        var pagesLeft = 40
        while (pagesLeft > 0) {
            pagesLeft -= 1
            val messages: List<GroupControlIndexerResponse> = try {
                api.getGroupControlBySender(adminAddress, cursor = cursor)
            } catch (e: Exception) {
                Log.w("GroupRepository", "Catch-up gctl-by-sender fetch failed for admin ${adminAddress.takeLast(10)}", e)
                return
            }
            if (messages.isEmpty()) {
                deepBackfilledGroupKeys.add(syncKey)
                return
            }
            cursor = messages.lastOrNull()?.cursor
            advanceGroupSyncCursor(syncKey, walletAddress, cursor)
            for (msg in messages) {
                val payloadString = reconstructPayloadString("kchat:1:gctl:", msg.messagePayload) ?: continue
                handleIncomingControlMessage(payloadString, msg.sender, msg.blockTime)
            }
            if (messages.size < 50) {
                deepBackfilledGroupKeys.add(syncKey)
                return
            }
        }
    }

    /**
     * Discovers "you were added to a group" via recipient-addressed `gctl` - the only catch-up
     * path that works before this device knows any group exists at all. See [syncGroups].
     */
    private suspend fun syncGroupControlByRecipient(api: KasiaIndexerApi, walletAddress: String) {
        val syncKey = "gctl-recipient|${walletAddress.lowercase()}"
        // Paged like the other two. This is the path a seedless import discovers groups through,
        // so a wallet in more than 50 lifetime invites would otherwise find only some of them.
        val deep = syncKey !in deepBackfilledGroupKeys
        var cursor: String? = if (deep) null else database.groupDao().getGroupSyncCursor(syncKey, walletAddress)
        var pagesLeft = 40
        while (pagesLeft > 0) {
            pagesLeft -= 1
            val messages: List<GroupControlIndexerResponse> = try {
                api.getGroupControlByRecipient(walletAddress, cursor = cursor)
            } catch (e: Exception) {
                Log.w("GroupRepository", "Catch-up gctl-by-recipient fetch failed", e)
                return
            }
            if (messages.isEmpty()) {
                deepBackfilledGroupKeys.add(syncKey)
                return
            }
            cursor = messages.lastOrNull()?.cursor
            advanceGroupSyncCursor(syncKey, walletAddress, cursor)
            for (msg in messages) {
                val payloadString = reconstructPayloadString("kchat:1:gctl:", msg.messagePayload) ?: continue
                handleIncomingControlMessage(payloadString, msg.sender, msg.blockTime)
            }
            if (messages.size < 50) {
                deepBackfilledGroupKeys.add(syncKey)
                return
            }
        }
    }

    /**
     * Sync keys whose whole history has been walked from the beginning this launch.
     *
     * A cursor only ever moves FORWARD, and until catch-up learned to page it advanced past a
     * whole 50-row page per sync - so on a device that synced while a group already had history,
     * everything before the cursor was skipped and walking forward can never reach it. The first
     * run per key per launch starts from nothing instead, which the DAO's txId dedupe makes free
     * to repeat.
     */
    private val deepBackfilledGroupKeys = mutableSetOf<String>()

    private suspend fun advanceGroupSyncCursor(syncKey: String, walletAddress: String, cursor: String?) {
        if (cursor == null) return
        database.groupDao().setGroupSyncCursor(GroupSyncCursorEntity(syncKey = syncKey, walletAddress = walletAddress, cursor = cursor))
    }

    /**
     * Reverses the indexer's double-hex-encoding of `message_payload` (it hex-encodes the raw
     * on-chain sealed hex text as stored) back into the original `ciph_msg:1:<type>:<hex>`
     * on-chain payload string, so it can feed straight into the same parse/decrypt path the live
     * block-scan uses.
     */
    private fun reconstructPayloadString(prefix: String, messagePayloadHex: String): String? {
        val asciiBytes = try { messagePayloadHex.hexToByteArray() } catch (e: Exception) { return null }
        val hexText = try { String(asciiBytes, Charsets.UTF_8) } catch (e: Exception) { return null }
        return prefix + hexText
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** x-only pubkey directly encoded in a standard (P2PK) Kaspa address's payload - null for any other address type or malformed input. */
    private fun xOnlyPubKeyOrNull(address: String): ByteArray? {
        return try {
            val (version, payload) = KaspaAddress.decode(address)
            if (version == 0x00.toByte() && payload.size == 32) payload else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Full group key material for the shared backup archive (see ChatHistoryArchive.groups) -
     * including the admin's groupSeed, which lives ONLY on the creating device and has no
     * on-chain invite for other devices of the same account to recover from. deviceId/msgCounter
     * are per-device and deliberately omitted (the importer mints its own).
     */
    suspend fun exportArchiveGroups(): List<ChatHistoryArchiveGroup> {
        val walletAddress = walletManager.getAddress()
        if (walletAddress.isEmpty()) return emptyList()
        return database.groupDao().getGroupsOnce(walletAddress).mapNotNull { entity ->
            val bag = groupSecretStore.loadBag(walletAddress, entity.groupId) ?: return@mapNotNull null
            val members = try { gson.fromJson<List<GroupMember>>(entity.membersJson, membersListType) ?: emptyList() } catch (e: Exception) { emptyList() }
            ChatHistoryArchiveGroup(
                groupId = entity.groupId,
                name = entity.name,
                isAdmin = entity.isAdmin,
                adminAddress = entity.adminAddress,
                adminSigningPub = entity.adminXOnlyPubKeyHex,
                groupSeed = bag.groupSeed,
                groupRootEpoch = bag.groupRootEpoch,
                blindingKey = bag.blindingKey,
                currentEpoch = bag.currentEpoch,
                previousRoots = bag.previousRoots,
                members = members.map { ChatHistoryArchiveGroupMember(it.address, it.xOnlyPubKeyHex, it.isAdmin) },
                messages = run {
                    val groupIdBytes = try { entity.groupId.hexToByteArray() } catch (e: Exception) { return@run emptyList() }
                    database.groupDao().getMessagesOnce(entity.groupId, walletAddress).mapNotNull { row ->
                        val decoded = decryptEntity(row, bag, groupIdBytes) ?: return@mapNotNull null
                        // Membership system lines are re-derived from roster changes on each device —
                        // don't ship them in the backup (they'd re-appear out of context on restore).
                        if (decoded.isSystemMessage()) return@mapNotNull null
                        ChatHistoryArchiveGroupMessage(
                            msgIdHex = row.msgIdHex.ifEmpty { null }, txId = row.txId.takeUnless { it.startsWith("pending_") },
                            senderAddress = decoded.senderAddress, senderIdHex = row.senderIdHex.ifEmpty { null },
                            content = decoded.content, blockTime = decoded.blockTimestamp, isOutgoing = decoded.isOutgoing
                        )
                    }
                },
                photo = entity.photoHex
            )
        }
    }

    /**
     * Restore groups from a shared backup archive. Recovers admin groups (groupSeed present) as
     * well as member ones. Mints a fresh deviceId per group so this device's sends can't collide
     * with msg_ids the exporting device already used; never downgrades a newer epoch.
     */
    suspend fun importArchiveGroups(groups: List<ChatHistoryArchiveGroup>) {
        val walletAddress = walletManager.getAddress()
        if (walletAddress.isEmpty()) return
        for (g in groups) {
            // Never resurrect a group you deleted (tombstoned) — same rule as the on-chain path.
            if (groupSecretStore.isTombstoned(walletAddress, g.groupId)) continue
            val groupRootEpoch = g.groupRootEpoch ?: continue
            val blindingKey = g.blindingKey ?: continue
            val existingBag = groupSecretStore.loadBag(walletAddress, g.groupId)
            if (existingBag != null && existingBag.currentEpoch > g.currentEpoch) continue
            val deviceId = existingBag?.deviceId ?: GroupCipher.generateDeviceId().toHexString()
            val msgCounter = if (existingBag?.currentEpoch == g.currentEpoch) existingBag.msgCounter else 0L
            // MERGE the retired epoch roots; never replace them. [GroupBag.previousRoots] is the
            // only way a NON-ADMIN decrypts epochs the group has already left (the re-derive
            // fallback needs groupSeed, which only the admin holds). Rebuilding the bag from the
            // archive without them silently made every pre-rotation message undecryptable: the
            // ciphertext was still on the device, the key to read it had been thrown away. That
            // is how connecting a cloud account could destroy group history a seed import had
            // just rebuilt.
            val mergedPreviousRoots = buildMap {
                existingBag?.previousRoots?.let { putAll(it) }
                g.previousRoots?.forEach { (epoch, root) -> putIfAbsent(epoch, root) }
                // Moving to a newer epoch retires the root we currently hold - keep it, or this
                // restore would lose the very history it is being asked to preserve.
                if (existingBag != null && existingBag.currentEpoch < g.currentEpoch) {
                    put(existingBag.currentEpoch, existingBag.groupRootEpoch)
                }
            }
            val bag = GroupBag(
                groupId = g.groupId, groupSeed = g.groupSeed ?: existingBag?.groupSeed, groupRootEpoch = groupRootEpoch,
                blindingKey = blindingKey, currentEpoch = g.currentEpoch, deviceId = deviceId, msgCounter = msgCounter,
                selfInviteEpoch = existingBag?.selfInviteEpoch,
                previousRoots = mergedPreviousRoots.ifEmpty { null }
            )
            groupSecretStore.saveBag(walletAddress, bag)
            val roster = g.members.map { GroupMember(it.address, it.xOnlyPubKeyHex ?: "", it.isAdmin, null) }
            val existingEntity = database.groupDao().getGroup(g.groupId, walletAddress)
            val entity = GroupEntity(
                groupId = g.groupId, walletAddress = walletAddress, name = g.name,
                adminAddress = g.adminAddress ?: "", adminXOnlyPubKeyHex = g.adminSigningPub ?: "",
                currentEpoch = g.currentEpoch, isAdmin = g.isAdmin, membersJson = gson.toJson(roster),
                createdAt = existingEntity?.createdAt ?: System.currentTimeMillis(),
                // Restored history is never unread: advance lastReadAt past every message this
                // archive carries (never backward - a newer read marker this device already holds
                // wins). Without this, restoring onto a fresh install left lastReadAt null, so the
                // whole restored thread counted as unread (plus the "never opened" badge). A group
                // restored with no messages stamps "now" - a restore is history, not a new invite.
                lastReadAt = maxOf(
                    existingEntity?.lastReadAt ?: 0L,
                    g.messages.orEmpty().maxOfOrNull { it.blockTime } ?: 0L
                ).takeIf { it > 0L } ?: System.currentTimeMillis(),
                photoHex = g.photo ?: existingEntity?.photoHex
            )
            database.groupDao().upsertGroup(entity)

            // Restore decrypted message history as negative-epoch sentinel rows (content hex =
            // UTF-8 plaintext), deduped by txId via the DAO's IGNORE-on-conflict insert.
            for (m in g.messages.orEmpty()) {
                val txId = m.txId?.takeIf { it.isNotEmpty() } ?: ("imported_" + (m.msgIdHex ?: java.util.UUID.randomUUID().toString()))
                val contentHex = m.content.toByteArray(Charsets.UTF_8).toHexString()
                database.groupDao().insertMessage(
                    GroupMessageEntity(
                        txId = txId, walletAddress = walletAddress, groupId = g.groupId,
                        senderAddress = m.senderAddress, senderIdHex = m.senderIdHex ?: "", epoch = -1L,
                        msgIdHex = m.msgIdHex ?: "", contentEncryptedHex = contentHex,
                        blockTimestamp = m.blockTime, isOutgoing = m.isOutgoing, deliveryStatus = "sent"
                    )
                )
            }
        }
    }

    /**
     * Clears all local group data (Room + Keystore secrets) for [walletAddress] - mirrors
     * [ChatRepository.wipeAllLocalDataForAddress]'s signature/semantics exactly (an explicit
     * Danger Zone action, not an automatic side effect of logout/delete-wallet, and must work
     * for any address, not just whichever wallet happens to be active right now).
     */
    suspend fun clearAllLocalData(walletAddress: String) {
        val groups = database.groupDao().getGroupsOnce(walletAddress)
        for (group in groups) {
            groupSecretStore.deleteBag(walletAddress, group.groupId)
        }
        database.groupDao().deleteAllGroups(walletAddress)
        database.groupDao().deleteAllMessages(walletAddress)
        database.groupDao().deleteGroupSyncCursorsForWallet(walletAddress)
    }
}
