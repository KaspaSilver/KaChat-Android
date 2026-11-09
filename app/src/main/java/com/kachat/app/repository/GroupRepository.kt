@file:OptIn(ExperimentalStdlibApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.kachat.app.repository

import com.kachat.app.services.GroupControlSinceBody
import com.kachat.app.services.GroupMessagesSinceBody
import android.content.Context
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
import com.kachat.app.models.PendingGroupInvite
import com.kachat.app.models.ReactionEntity
import com.kachat.app.services.FormerGroupMember
import com.kachat.app.services.GroupBag
import com.kachat.app.services.GroupControlIndexerResponse
import com.kachat.app.services.GroupMessageIndexerResponse
import com.kachat.app.services.GroupSecretStore
import com.kachat.app.services.GroupSeenMessageStore
import com.kachat.app.services.IndexerHealth
import com.kachat.app.services.KasiaIndexerApi
import com.kachat.app.services.NetworkService
import com.kachat.app.services.NotificationHelper
import com.kachat.app.services.PushState
import com.kachat.app.services.SenderProof
import com.kachat.app.services.WalletManager
import com.kachat.app.services.WalletService
import com.kachat.app.services.database.KaChatDatabase
import com.kachat.app.util.GroupCipher
import com.kachat.app.util.GroupMemberStatus
import com.kachat.app.util.ImageMessage
import com.kachat.app.util.KasiaCipher
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.redactedForLog
import com.kachat.app.util.MessageReaction
import com.kachat.app.util.MessageReply
import com.kachat.app.util.Schnorr
import com.kachat.app.util.VoiceMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/**
 * How long a membership/rename/photo line stays in the thread.
 *
 * These lines report a change, and a change is only news while it is recent. Left forever they
 * pile up between the actual conversation - a group that adds and removes a few people ends up
 * with more bookkeeping than messages. They are dropped from the thread after this, and pruned
 * from the database so they do not accumulate there either.
 */
const val GROUP_SYSTEM_MESSAGE_LIFETIME_MS = 10 * 60 * 1000L

/** True when this line has outlived [GROUP_SYSTEM_MESSAGE_LIFETIME_MS] and should not be shown. */
fun GroupMessage.isExpiredSystemMessage(now: Long = System.currentTimeMillis()): Boolean =
    isSystemMessage() && now - blockTimestamp > GROUP_SYSTEM_MESSAGE_LIFETIME_MS

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
 *  - `kchat:1:gcomm:...` - a group message (the legacy `ciph_msg:1:` root is read too).
 *  - `kchat:1:gctl:...` - a control message (`gctl_root`/`gctl_epoch`), ECIES-encrypted (via
 *    [KasiaCipher], the same crypto 1:1 messages use) to one specific recipient.
 *
 * Deliberately no invite-link/beacon join path: every member is added directly by the admin, who
 * already knows who they are (see `addMember`/`createGroup`). A prior revision had a
 * publicly-joinable invite beacon (KaChat extension, not in the reference spec) - removed once
 * group chats route through indexers, since a way for anyone to discover and join a group's
 * *encrypted* chat is exactly the kind of thing that could be used to infer something bad is
 * happening inside it and pressure an indexer operator into censoring it.
 *
 * Being added is only a request (iOS bfe9f022, MESSAGING.md "Group invites"): a `gctl_root` for a
 * group this wallet doesn't hold waits in Message Requests until it is answered. See
 * [holdGroupInvite].
 */
@Singleton
class GroupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: KaChatDatabase,
    private val walletManager: WalletManager,
    private val walletService: WalletService,
    private val groupSecretStore: GroupSecretStore,
    private val groupSeenMessages: GroupSeenMessageStore,
    private val networkService: NetworkService,
    private val notificationHelper: NotificationHelper,
    private val settings: AppSettingsRepository,
    private val notificationCenter: com.kachat.app.services.GlobalNotificationCenterStore,
    private val knsService: com.kachat.app.services.KnsService,
    private val chatRequestStore: ChatRequestStore,
    private val pushState: PushState,
) {
    private val gson = Gson()
    /** For a block-scan root's sender check, which waits on the REST API off the scan's own path. */
    private val senderProofScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.kachat.app.util.StartupSafety.logOnlyHandler("GroupRepository"))
    private val membersListType = object : TypeToken<List<GroupMember>>() {}.type

    /** Block time of the newest group photo control applied, per wallet and group (iOS
     *  groupPhotoUpdatedAt): a re-walked older one must neither change the photo back nor add a
     *  line. */
    private val photoAppliedAtPrefs = context.getSharedPreferences("kachat_group_photo_applied_at", Context.MODE_PRIVATE)

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

    /** "wallet|txId" of gcomms fully handled this session - stored, or applied as a reaction or
     *  edit (iOS 6b2f20e). Catch-up re-serves its rewind window every pass and the live poll
     *  repeats it, and each re-serve paid the signature check, the decrypt and a database read
     *  again before the insert's own dedupe said "already have it". Only successes are
     *  remembered: a message rejected for a missing epoch key, or an edit whose target has not
     *  arrived, must be tried again. Keyed by wallet so a switch needs no reset; cleared when a
     *  group is deleted, so a re-join can store its messages again. */
    private val completedGroupTxIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun markGroupTxCompleted(walletAddress: String, txId: String) {
        if (completedGroupTxIds.size >= 20_000) completedGroupTxIds.clear()
        completedGroupTxIds.add("$walletAddress|$txId")
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
        val mentionsOnly = settings.groupMentionsOnlyApplied.first()
        return groups.all { it.groupId in silent || it.groupId in mentionsOnly }
    }

    /** Whether this group is silenced outright - no banner, mentioned or not. */
    suspend fun isGroupSilent(groupId: String): Boolean =
        groupId in settings.groupSilent.first()

    /** Whether the per-group "Only Notify if I'm Mentioned" toggle is on for [groupId]. */
    suspend fun isGroupMentionsOnly(groupId: String): Boolean =
        groupId in settings.groupMentionsOnlyApplied.first()

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

    /** The active account's address - the live group poll keys its cursors to it. */
    val activeWalletAddress: Flow<String?> = walletManager.activeAddressFlow

    /** "kaspa" or "kaspatest", read off the active wallet's own address - used to reconstruct a sender's address from a raw pubkey/script for the active network instead of assuming mainnet. */
    fun addressPrefix(): String = walletManager.getAddress().substringBefore(":")

    fun membersOf(group: GroupEntity): List<GroupMember> =
        try { gson.fromJson<List<GroupMember>>(group.membersJson, membersListType) ?: emptyList() } catch (e: Exception) { emptyList() }

    /** Best display name for a membership system line: live contact alias → Address Book name → roster snapshot name → short address. */
    private suspend fun groupMemberLabel(address: String, walletAddress: String, fallbackDisplayName: String?): String {
        val alias = try { database.contactDao().getContact(address, walletAddress)?.alias?.trim() } catch (e: Exception) { null }
        if (!alias.isNullOrEmpty()) return alias
        // A name saved in the Address Book, right after the contact alias (iOS 9ba6c8c).
        com.kachat.app.services.AddressBookManager.nameFor(address)?.let { return it }
        val snap = fallbackDisplayName?.trim()
        if (!snap.isNullOrEmpty()) return snap
        return KaspaAddress.shortDisplay(address)
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
        val domain = try {
            // A banner waits on this, and a roster name will do (see NAME_LOOKUP_BUDGET_MS).
            kotlinx.coroutines.withTimeoutOrNull(NAME_LOOKUP_BUDGET_MS) { knsService.identityName(address) }
                ?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
        senderKnsCache[address] = now to domain
        return domain
    }

    /** Insert an iMessage-style membership line into the group thread, stored as a negative-epoch
     *  plaintext row keyed on the reserved [GROUP_SYSTEM_SENDER] so the UI renders it centered.
     *  Stamped when it arrives, not when the change was mined, so each line shows for its full
     *  [GROUP_SYSTEM_MESSAGE_LIFETIME_MS] however late it got here (iOS insertGroupSystemLine).
     *  [stableKey] makes the row's id deterministic, so re-processing the same control can't
     *  insert a second line; without one the id comes from the clock. */
    private suspend fun insertGroupSystemMessage(
        groupId: String, walletAddress: String, text: String, stableKey: String? = null, at: Long = System.currentTimeMillis()
    ) {
        val txId = stableKey?.let { "sys_${groupId.take(8)}_$it" } ?: "sys_${groupId.take(8)}_${at}_${text.hashCode()}"
        database.groupDao().insertMessage(
            GroupMessageEntity(
                txId = txId, walletAddress = walletAddress, groupId = groupId,
                senderAddress = GROUP_SYSTEM_SENDER, senderIdHex = "", epoch = -1L,
                msgIdHex = "", contentEncryptedHex = text.toByteArray(Charsets.UTF_8).toHexString(),
                blockTimestamp = at, isOutgoing = false, deliveryStatus = "sent"
            )
        )
    }

    /** Emit "X was invited" / "Y was removed" lines for a roster change (old → new), for both the
     *  admin who made the change and any member who receives the rotated root, and record who that
     *  leaves waiting on their invite (iOS insertMembershipSystemMessages). */
    private suspend fun emitMembershipSystemMessages(
        groupId: String, walletAddress: String, oldMembers: List<GroupMember>, newMembers: List<GroupMember>
    ) {
        val oldAddrs = oldMembers.map { it.address }.toSet()
        val newAddrs = newMembers.map { it.address }.toSet()
        var t = System.currentTimeMillis()
        for (m in newMembers) if (m.address !in oldAddrs) {
            setMemberStatus(GroupMemberStatus.INVITED, m.address, groupId, walletAddress)
            insertGroupSystemMessage(groupId, walletAddress, "${groupMemberLabel(m.address, walletAddress, m.displayName)} was invited to the group chat", at = t++)
        }
        for (m in oldMembers) if (m.address !in newAddrs) {
            setMemberStatus(null, m.address, groupId, walletAddress)
            insertGroupSystemMessage(groupId, walletAddress, "${groupMemberLabel(m.address, walletAddress, m.displayName)} was removed from the group chat", at = t++)
        }
    }

    /** Deletes expired system lines for one group, so they do not accumulate in the database. */
    suspend fun pruneExpiredSystemMessages(groupId: String) {
        val walletAddress = walletManager.getAddress()
        if (walletAddress.isEmpty()) return
        val now = System.currentTimeMillis()
        val cutoff = now - GROUP_SYSTEM_MESSAGE_LIFETIME_MS
        val bag = groupSecretStore.loadBag(walletAddress, groupId) ?: return
        val groupIdBytes = runCatching { groupId.hexToByteArray() }.getOrNull() ?: return
        database.groupDao().getMessagesOnce(groupId, walletAddress)
            .filter { it.blockTimestamp < cutoff }
            .forEach { entity ->
                val decoded = decryptCached(entity, bag, groupIdBytes) ?: return@forEach
                if (decoded.isSystemMessage()) {
                    database.groupDao().deleteMessage(entity.txId, walletAddress)
                }
            }
    }

    /** Decrypted messages for a group, oldest first - decryption happens here, on read, from stored ciphertext. */
    fun getMessages(groupId: String): Flow<List<GroupMessage>> {
        return walletManager.activeAddressFlow.flatMapLatest { address ->
            if (address == null) flowOf(emptyList())
            else database.groupDao().getMessages(groupId, address).map { entities ->
                val bag = groupSecretStore.loadBag(address, groupId) ?: return@map emptyList()
                val groupIdBytes = groupId.hexToByteArray()
                // A row an older build stored before it knew the type: never a bubble.
                entities.mapNotNull { decryptCached(it, bag, groupIdBytes) }
                    .filterNot { GroupMemberStatus.isHiddenEnvelope(it.content) }
            }
            // Room delivers on its own executor, but the map above ran wherever the collector
            // lives - the main thread for a StateFlow in a ViewModel - so a sync that inserted a
            // message re-decrypted the whole thread on the UI thread and dropped frames.
            .flowOn(Dispatchers.Default)
        }
    }

    /**
     * Whether any of this group's messages contains [query] (case-insensitive) - the message half
     * of the chat list's group search (iOS ChatListView.displayedGroups). Messages over 4 KB are
     * skipped, as iOS skips them: a photo's or voice note's content is a long base64 blob nobody
     * searches for, and scanning those is what made the search slow.
     */
    suspend fun anyMessageContains(groupId: String, query: String): Boolean = kotlinx.coroutines.withContext(Dispatchers.Default) {
        val walletAddress = walletManager.getAddress()
        if (walletAddress.isEmpty() || query.isEmpty()) return@withContext false
        val bag = groupSecretStore.loadBag(walletAddress, groupId) ?: return@withContext false
        val groupIdBytes = runCatching { groupId.hexToByteArray() }.getOrNull() ?: return@withContext false
        database.groupDao().getMessagesOnce(groupId, walletAddress).any { entity ->
            val content = decryptCached(entity, bag, groupIdBytes)?.content ?: return@any false
            content.toByteArray(Charsets.UTF_8).size <= 4096 && content.contains(query, ignoreCase = true)
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
                    }?.takeUnless { GroupMemberStatus.isHiddenEnvelope(it.content) }
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

    /** The newest edit per message in this group - see [com.kachat.app.util.MessageEdit]. */
    fun getEdits(groupId: String): Flow<List<com.kachat.app.models.MessageEditEntity>> {
        return walletManager.activeAddressFlow.flatMapLatest { address ->
            if (address == null) flowOf(emptyList())
            else database.messageEditDao().getEditsForGroup(groupId, address)
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
        for (member in roster) {
            if (!member.isAdmin) setMemberStatus(GroupMemberStatus.INVITED, member.address, groupId.toHexString(), walletAddress)
        }

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
            insertGroupSystemMessage(groupId, walletAddress, "You changed the group name to \"$newName\"")
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
            if (photoHex.isEmpty()) "You removed the group photo" else "You changed the group photo")
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
        // Catch up on our OWN control stream before reading the roster.
        //
        // The same account can be admin on two devices. Each holds its own copy of the roster,
        // and a rotation rebuilds the roster from that copy - so a device that had not yet seen
        // the other's change would send out a roster without it, and everyone would treat that
        // as a removal. Reported exactly that way: a member added on one phone vanished when the
        // other phone added someone else.
        //
        // The self-addressed root each rotation sends is what carries the change between our own
        // devices; this is the read side of it. Best effort - offline, we proceed on what we
        // have, which is no worse than before.
        runCatching {
            networkService.indexerApi.value?.let { syncGroupControlByRecipient(it, walletAddress) }
        }
        val entity = database.groupDao().getGroup(groupId, walletAddress) ?: throw IllegalStateException("Unknown group.")
        if (!entity.isAdmin) throw IllegalStateException("Only the group admin can change membership.")
        val bag = groupSecretStore.loadBag(walletAddress, groupId) ?: throw IllegalStateException("Missing admin group secrets.")
        val groupSeed = bag.groupSeed?.hexToByteArray() ?: throw IllegalStateException("Missing admin group secrets.")
        val groupIdBytes = groupId.hexToByteArray()
        val privateKey = walletManager.getPrivateKeyBytes()

        val previousRoster = membersOf(entity)
        val roster = previousRoster.toMutableList()
        mutateRoster(roster)

        // Keep the outgoing root so this epoch's messages stay readable afterwards - the same
        // archive every non-admin member's bag relies on, so both sides behave identically.
        // Rotated on the bag as stored, in one step, so a send racing this keeps its counter.
        val newBag = groupSecretStore.updateBag(walletAddress, groupId) { current ->
            current?.let {
                val nextEpoch = it.currentEpoch + 1
                it.copy(
                    currentEpoch = nextEpoch,
                    groupRootEpoch = GroupCipher.deriveGroupRootEpoch(groupSeed, groupIdBytes, nextEpoch).toHexString(),
                    previousRoots = it.previousRoots.orEmpty() + (it.currentEpoch to it.groupRootEpoch),
                )
            }
        } ?: throw IllegalStateException("Missing admin group secrets.")
        val newEpoch = newBag.currentEpoch

        val updatedEntity = entity.copy(currentEpoch = newEpoch, membersJson = gson.toJson(roster))
        database.groupDao().upsertGroup(updatedEntity)

        // iMessage-style membership lines for the admin who made the change (other members get
        // theirs when they receive the rotated root — see completeJoin).
        recordFormerMembers(walletAddress, groupId, previousRoster, roster, lastEpoch = newEpoch - 1, removedAt = 0L)
        emitMembershipSystemMessages(groupId, walletAddress, previousRoster, roster)

        // FIRST, before any member delivery: the self-addressed root is how this account's other
        // devices learn what just changed. It used to be sent only by the catch-up backfill,
        // which meant the other phone stayed on a stale roster until its next sync - long enough
        // for it to make its own change and overwrite this one.
        try { sendSelfRootControlMessage(updatedEntity, roster, newBag, privateKey) } catch (e: Exception) {}

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
        val adminSigningPub = database.groupDao().getGroup(groupId, walletAddress)?.adminXOnlyPubKeyHex?.takeIf { it.isNotEmpty() }
        groupSecretStore.deleteBag(walletAddress, groupId)
        completedGroupTxIds.clear()
        database.groupDao().deleteMessagesForGroup(groupId, walletAddress)
        database.reactionDao().deleteAllForGroup(groupId, walletAddress)
        database.messageEditDao().deleteAllForGroup(groupId, walletAddress)
        database.groupDao().deleteGroup(groupId, walletAddress)
        forgetGroupHistoryRecords(walletAddress, groupId)
        // Tombstone it so discovery/recovery never re-adds it, and publish an on-chain delete
        // marker (best-effort; the sync backfill retries) so the delete survives a seedless
        // re-import too. Local intent is recorded now; the chain write is best-effort.
        recordGroupTombstone(walletAddress, groupId, adminSigningPub, published = false)
        try { publishGroupTombstone(walletAddress, groupId, adminSigningPub) } catch (e: Exception) {
            Log.w("GroupRepository", "Group tombstone publish failed for ${groupId.take(12)}", e)
        }
    }

    /** The delete marker, and a deleted group's invite goes with it, unless it's only waiting to
     *  say you declined (iOS recordGroupTombstone). */
    private fun recordGroupTombstone(walletAddress: String, groupId: String, adminSigningPub: String?, published: Boolean) {
        val entry = adminSigningPub?.let { PendingGroupInvite.key(groupId, it) } ?: groupId
        groupSecretStore.recordTombstone(walletAddress, entry, published)
        groupSecretStore.updateInvites(walletAddress) { invites ->
            invites.filterNot { it.groupId == groupId && it.rejected != true && (adminSigningPub == null || it.id == entry) }
        }
        inviteStateChanged()
    }

    /** Self-addressed, self-signed delete marker — only our key can produce one, only our key
     *  can read it (see the signing_pub == self + verify check in handleIncomingControlMessage). */
    private suspend fun publishGroupTombstone(walletAddress: String, groupId: String, adminSigningPub: String?) {
        val privateKey = walletManager.getPrivateKeyBytes()
        val signingPub = Schnorr.publicKeyXOnly(privateKey)
        val selfXOnlyPub = xOnlyPubKeyOrNull(walletAddress) ?: return
        val payload = GroupCipher.buildSignedTombstonePayload(groupId.hexToByteArray(), adminSigningPub?.hexToByteArray(), signingPub, privateKey)
        val json = GroupCipher.tombstonePayloadToJson(payload)
        sendControlPayload(json, selfXOnlyPub, privateKey)
        groupSecretStore.markTombstonePublished(walletAddress, adminSigningPub?.let { PendingGroupInvite.key(groupId, it) } ?: groupId)
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

    /**
     * [retryOfTxId]: the failed row a Retry tap is re-sending. That row is flipped back to
     * pending and reused as the placeholder, then swapped for the real row on success or
     * flipped to failed again - so a retry never leaves the failed bubble behind next to a new
     * one. The message is re-encrypted under a fresh msg_id either way; a msg_id is never
     * reused. Mirrors the 1:1 retry (ChatViewModel.retrySendMessage).
     */
    suspend fun sendGroupMessage(text: String, groupId: String, retryOfTxId: String? = null): String {
        val walletAddress = walletManager.getAddress()
        database.groupDao().getGroup(groupId, walletAddress) ?: throw IllegalStateException("Unknown group.")
        // Not confirmed may have landed: a retry would post it twice (iOS `retryGroupMessage`
        // retries only a failed message).
        if (retryOfTxId != null && database.groupDao().getMessage(retryOfTxId, walletAddress)?.deliveryStatus == "warning") {
            return retryOfTxId
        }
        // Reserve the next counter BEFORE building/sending, atomically against every other send
        // (GroupSecretStore.reserveNextCounter): a msg_id must never be reused, even if the send
        // itself later fails, and the bag it returns is the one to encrypt under.
        val (bag, counter) = groupSecretStore.reserveNextCounter(walletAddress, groupId)
            ?: throw IllegalStateException("Missing group secrets - try rejoining this group.")

        val groupIdBytes = groupId.hexToByteArray()
        val groupRootEpoch = bag.groupRootEpoch.hexToByteArray()
        val blindingKey = bag.blindingKey.hexToByteArray()
        val deviceId = bag.deviceId.hexToByteArray()
        val privateKey = walletManager.getPrivateKeyBytes()
        val senderXOnlyPub = Schnorr.publicKeyXOnly(privateKey)
        val senderId = GroupCipher.deriveSenderId(walletAddress)

        val msgId = GroupCipher.buildMsgId(deviceId, counter)
        val ciphertext = GroupCipher.encryptMessage(text, groupRootEpoch, groupIdBytes, bag.currentEpoch, senderId, msgId)
        val aad = GroupCipher.buildMessageAAD(groupIdBytes, bag.currentEpoch, senderId, msgId)
        val signature = GroupCipher.sign(GroupCipher.buildMessageSigningPayload(aad, ciphertext), privateKey)
        val blindedGroupId = GroupCipher.deriveBlindedGroupId(blindingKey, senderXOnlyPub)
        val payloadString = GroupCipher.buildGroupMessagePayload(blindedGroupId, bag.currentEpoch, senderId, senderXOnlyPub, msgId, ciphertext, signature)

        val nowMs = System.currentTimeMillis()
        val pendingId: String
        if (retryOfTxId != null && database.groupDao().getMessage(retryOfTxId, walletAddress) != null) {
            // Retry: the failed row becomes the pending placeholder, in place.
            pendingId = retryOfTxId
            database.groupDao().updateMessageStatus(pendingId, walletAddress, "pending")
        } else {
            pendingId = "pending_${UUID.randomUUID()}"
            database.groupDao().insertMessage(
                GroupMessageEntity(
                    txId = pendingId, walletAddress = walletAddress, groupId = groupId, senderAddress = walletAddress,
                    senderIdHex = senderId.toHexString(), epoch = bag.currentEpoch, msgIdHex = msgId.toHexString(),
                    contentEncryptedHex = ciphertext.toHexString(), blockTimestamp = nowMs, isOutgoing = true, deliveryStatus = "pending"
                )
            )
        }
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
            // An unknown outcome may have landed: Not confirmed ("warning"), no Retry, until its
            // copy arrives and [GroupDao.deletePlaceholders] replaces it (iOS d23e6f7a
            // `markMessageFailed(status: .warning)`).
            val status = if (e is com.kachat.app.services.SendOutcomeUnknownException) "warning" else "failed"
            database.groupDao().updateMessageStatus(pendingId, walletAddress, status)
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
        // Reserve the next counter BEFORE building/sending, atomically against every other send
        // (GroupSecretStore.reserveNextCounter): a msg_id must never be reused, even if the send
        // itself later fails, and the bag it returns is the one to encrypt under.
        val (bag, counter) = groupSecretStore.reserveNextCounter(walletAddress, groupId)
            ?: throw IllegalStateException("Missing group secrets - try rejoining this group.")

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

    /**
     * Edits one of this wallet's own text messages in a group: applied locally at once (pending),
     * then sent as an edit envelope in a group-encrypted message exactly like a reaction - one
     * transaction, no new bubble. Sent or failed follow.
     */
    suspend fun sendGroupEdit(targetTxId: String, groupId: String, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val walletAddress = walletManager.getAddress()
        database.groupDao().getGroup(groupId, walletAddress) ?: throw IllegalStateException("Unknown group.")
        // Reserve the next counter BEFORE building/sending, atomically against every other send
        // (GroupSecretStore.reserveNextCounter): a msg_id must never be reused, even if the send
        // itself later fails, and the bag it returns is the one to encrypt under.
        val (bag, counter) = groupSecretStore.reserveNextCounter(walletAddress, groupId)
            ?: throw IllegalStateException("Missing group secrets - try rejoining this group.")

        val groupIdBytes = groupId.hexToByteArray()
        val groupRootEpoch = bag.groupRootEpoch.hexToByteArray()
        val blindingKey = bag.blindingKey.hexToByteArray()
        val deviceId = bag.deviceId.hexToByteArray()
        val privateKey = walletManager.getPrivateKeyBytes()
        val senderXOnlyPub = Schnorr.publicKeyXOnly(privateKey)
        val senderId = GroupCipher.deriveSenderId(walletAddress)
        val payload = com.kachat.app.util.MessageEdit.encode(targetTxId, clean)
        val now = System.currentTimeMillis()

        suspend fun storeEdit(editTxId: String?, deliveryStatus: String) {
            database.messageEditDao().upsertEdit(
                com.kachat.app.models.MessageEditEntity(
                    targetTxId = targetTxId, walletAddress = walletAddress, editorAddress = walletAddress,
                    text = clean.take(com.kachat.app.util.MessageEdit.MAX_LENGTH), editTxId = editTxId,
                    blockTimestamp = now, groupId = groupId, deliveryStatus = deliveryStatus
                )
            )
        }
        storeEdit(null, "pending")

        val msgId = GroupCipher.buildMsgId(deviceId, counter)
        val ciphertext = GroupCipher.encryptMessage(payload, groupRootEpoch, groupIdBytes, bag.currentEpoch, senderId, msgId)
        val aad = GroupCipher.buildMessageAAD(groupIdBytes, bag.currentEpoch, senderId, msgId)
        val signature = GroupCipher.sign(GroupCipher.buildMessageSigningPayload(aad, ciphertext), privateKey)
        val blindedGroupId = GroupCipher.deriveBlindedGroupId(blindingKey, senderXOnlyPub)
        val payloadString = GroupCipher.buildGroupMessagePayload(blindedGroupId, bag.currentEpoch, senderId, senderXOnlyPub, msgId, ciphertext, signature)

        try {
            val txId = walletService.sendKaspa(toAddress = walletAddress, amountSompi = 0, payloadBytes = payloadString.toByteArray(Charsets.UTF_8))
            storeEdit(txId, "sent")
        } catch (e: Exception) {
            storeEdit(null, "failed")
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
            members = roster.map { it.address }, name = entity.name,
            rosterVersion = nextRosterVersion(entity.walletAddress, entity.groupId), adminPrivateKey = adminPrivateKey
        )
        val json = GroupCipher.rootPayloadToJson(rootPayload)
        sendControlPayload(json, recipientXOnlyPub, adminPrivateKey)
    }

    /**
     * The version for the next signed roster: the time in milliseconds, so the admin's devices agree
     * without sharing a counter, and always above the last one this device saw, so a clock set back
     * can't get the admin's own changes refused. Recorded from a fresh read of the bag, because the
     * one a caller holds may be older than its message counter (iOS `nextRosterVersion`).
     */
    private fun nextRosterVersion(walletAddress: String, groupId: String): Long {
        val now = System.currentTimeMillis()
        var version = now
        groupSecretStore.updateBag(walletAddress, groupId) { bag ->
            bag?.let {
                version = maxOf(now, (it.rosterVersion ?: 0L) + 1)
                it.copy(rosterVersion = version)
            }
        }
        return version
    }

    /** Self-addressed recovery copy carrying the group seed (ECIES to our own key, so members
     *  never see it). A seedless re-import rediscovers the group via the by-recipient scan and
     *  rebuilds it as admin. Marks selfInviteEpoch on the bag. */
    private suspend fun sendSelfRootControlMessage(entity: GroupEntity, roster: List<GroupMember>, bag: GroupBag, adminPrivateKey: ByteArray) {
        val walletAddress = walletManager.getAddress()
        val seedHex = bag.groupSeed ?: return
        val selfXOnlyPub = xOnlyPubKeyOrNull(walletAddress) ?: return
        val rosterVersion = nextRosterVersion(walletAddress, entity.groupId)
        val rootPayload = GroupCipher.buildSignedRootPayload(
            groupId = entity.groupId.hexToByteArray(), epoch = bag.currentEpoch, groupRootEpoch = bag.groupRootEpoch.hexToByteArray(),
            blindingKey = bag.blindingKey.hexToByteArray(), adminSigningPub = entity.adminXOnlyPubKeyHex.hexToByteArray(),
            members = roster.map { it.address }, name = entity.name, rosterVersion = rosterVersion,
            adminPrivateKey = adminPrivateKey, groupSeed = seedHex.hexToByteArray()
        )
        val json = GroupCipher.rootPayloadToJson(rootPayload)
        sendControlPayload(json, selfXOnlyPub, adminPrivateKey)
        // Onto the bag as stored now, not [bag]: the send above suspended, and a message sent
        // meanwhile has moved the counter on (or a rotation the epoch).
        groupSecretStore.updateBag(walletAddress, entity.groupId) { current ->
            current?.let {
                it.copy(
                    selfInviteEpoch = maxOf(it.selfInviteEpoch ?: bag.currentEpoch, bag.currentEpoch),
                    rosterVersion = maxOf(it.rosterVersion ?: 0L, rosterVersion),
                )
            }
        }
    }

    private suspend fun sendEpochControlMessage(groupIdBytes: ByteArray, epoch: Long, reason: String, recipientAddress: String, adminPrivateKey: ByteArray) {
        val recipientXOnlyPub = xOnlyPubKeyOrNull(recipientAddress) ?: throw IllegalArgumentException("Invalid address")
        val epochPayload = GroupCipher.buildSignedEpochPayload(groupIdBytes, epoch, reason, adminPrivateKey)
        val json = GroupCipher.epochPayloadToJson(epochPayload)
        sendControlPayload(json, recipientXOnlyPub, adminPrivateKey)
    }

    /**
     * Wire format is recipient-addressed (`kchat:1:gctl:{recipient_xonly}:{encrypted}`), not
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
        if ("$walletAddress|$txId" in completedGroupTxIds) return
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
            // Someone since removed still counts for what they sent while they were in: a device
            // that catches up late would otherwise lose it (iOS 4f8e14bb).
            if (roster.none { it.address == senderAddress } && !wasMember(senderAddress, walletAddress, group.groupId, parsed.epoch, blockTimestamp)) {
                refreshRejections?.let { it.senderNotInRoster++ }
                Log.w("GroupRepository", "Rejected gcomm for group ${group.groupId.take(12)}: sender ${senderAddress.redactedForLog()} not in roster ${roster.map { it.address.redactedForLog() }}")
                return
            }
            if (!GroupCipher.deriveSenderId(senderAddress).contentEquals(parsed.senderId)) {
                Log.w("GroupRepository", "Rejected gcomm for group ${group.groupId.take(12)}: senderId mismatch for ${senderAddress.redactedForLog()}")
                return
            }

            val aad = GroupCipher.buildMessageAAD(groupIdBytes, parsed.epoch, parsed.senderId, parsed.msgId)
            val signingPayload = GroupCipher.buildMessageSigningPayload(aad, parsed.ciphertext)
            if (!GroupCipher.verify(parsed.signature, signingPayload, parsed.senderPubKey)) {
                Log.w("GroupRepository", "Rejected gcomm for group ${group.groupId.take(12)}: bad signature from ${senderAddress.redactedForLog()}")
                return
            }

            val root = groupRootEpochFor(parsed.epoch, bag, groupIdBytes)
            if (root == null) {
                refreshRejections?.let { it.noRootForEpoch++ }
                Log.w("GroupRepository", "Rejected gcomm for group ${group.groupId.take(12)}: no root for epoch ${parsed.epoch} (local currentEpoch=${bag.currentEpoch})")
                return
            }
            val plaintext = GroupCipher.decryptMessage(parsed.ciphertext, root, groupIdBytes, parsed.epoch, parsed.senderId, parsed.msgId)
            if (plaintext == null) {
                refreshRejections?.let { it.decryptFailed++ }
                Log.w("GroupRepository", "Rejected gcomm for group ${group.groupId.take(12)}: decrypt failed from ${senderAddress.redactedForLog()}")
                return
            }

            // Everything above passes for a copy of someone's payload in a new transaction too:
            // the signature doesn't cover the transaction. Each message id counts once, and it's
            // written down here, before any of the branches below acts on it (iOS 4f8e14bb).
            if (!claimMessageId(walletAddress, group.groupId, parsed.senderId.toHexString(), parsed.epoch, parsed.msgId.toHexString(), txId, blockTimestamp)) {
                Log.i("GroupRepository", "Dropped a replay in group ${group.groupId.take(12)}: tx ${txId.take(12)}")
                markGroupTxCompleted(walletAddress, txId)
                return
            }

            // Someone answering their invite: updates the member list, never a bubble and never a
            // notification (iOS bfe9f022). The sender is already proven and in the roster above.
            // Deleting the row clears one an older build stored as a message, when catch-up
            // re-serves it.
            val memberStatus = GroupMemberStatus.parseOrNull(plaintext)
            if (memberStatus != null) {
                applyMemberStatus(memberStatus, senderAddress, group.groupId, walletAddress, txId, blockTimestamp)
                database.groupDao().deleteMessage(txId, walletAddress)
                markGroupTxCompleted(walletAddress, txId)
                return
            }
            // Anything else they send means they're in, whether or not their answer got here.
            if (groupSecretStore.loadMemberStatuses(walletAddress)[group.groupId]?.get(senderAddress) != null) {
                setMemberStatus(GroupMemberStatus.JOINED, senderAddress, group.groupId, walletAddress)
            }
            // A member's answer that isn't a valid one: still never a bubble (MESSAGING.md).
            if (GroupMemberStatus.isHiddenEnvelope(plaintext)) {
                database.groupDao().deleteMessage(txId, walletAddress)
                markGroupTxCompleted(walletAddress, txId)
                return
            }

            // An edit is never a bubble either: it changes the message it names - if the editor
            // sent that message - and is otherwise dropped. Checked before reactions, exactly
            // like iOS's handleIncomingGroupMessage.
            val edit = com.kachat.app.util.MessageEdit.parseOrNull(plaintext)
            if (edit != null) {
                val target = database.groupDao().getMessage(edit.targetTxId, walletAddress)
                if (target != null && target.groupId == group.groupId && target.senderAddress == senderAddress &&
                    com.kachat.app.util.MessageEdit.isEditable(decryptEntity(target, bag, groupIdBytes)?.content)) {
                    val existing = database.messageEditDao().getEdit(edit.targetTxId, walletAddress)
                    // Newest by block time wins; our own in-flight edit is replaced by its outcome.
                    if (existing == null || existing.deliveryStatus != "sent" ||
                        existing.blockTimestamp <= blockTimestamp || existing.editTxId == txId) {
                        database.messageEditDao().upsertEdit(
                            com.kachat.app.models.MessageEditEntity(
                                targetTxId = edit.targetTxId, walletAddress = walletAddress, editorAddress = senderAddress,
                                text = edit.text.take(com.kachat.app.util.MessageEdit.MAX_LENGTH), editTxId = txId,
                                blockTimestamp = blockTimestamp, groupId = group.groupId
                            )
                        )
                    }
                    markGroupTxCompleted(walletAddress, txId)
                }
                return
            }

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
                    // The remote push is the only banner source while push is active, foreground
                    // or background (iOS ChatService.localBannersEnabled): the FCM handler words
                    // this one with readGroupMessagePush. Only a device with no push banners here.
                    // NotificationHelper's txId dedupe additionally collapses a racing FCM push
                    // for the same reaction.
                    if (!alreadyApplied && senderAddress != walletAddress && !isBackfill(blockTimestamp) && !pushState.isActive) {
                        val bannerText = groupReactionBannerText(group, reaction, senderAddress, walletAddress)
                        if (bannerText != null) {
                            notificationHelper.showGroup(
                                groupId = group.groupId,
                                title = group.name,
                                text = bannerText,
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
                markGroupTxCompleted(walletAddress, txId)
                return
            }

            // Past the Message retention setting it isn't kept, even if a walk of the whole stream
            // (a repair, a restore) brings it back (iOS 4f8e14bb).
            val retentionCutoff = settings.backupRetention.first().cutoffMillis(System.currentTimeMillis())
            if (blockTimestamp > 0 && retentionCutoff != null && blockTimestamp < retentionCutoff) {
                markGroupTxCompleted(walletAddress, txId)
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
            // Stored now, or already stored: either way this tx never needs handling again.
            markGroupTxCompleted(walletAddress, txId)
            // Our own send, back from the chain: a row marked failed after the last run ended, or
            // still pending, has its delivered copy now.
            if (isOutgoing) database.groupDao().deletePlaceholders(walletAddress, group.groupId, parsed.msgId.toHexString())
            // rowId == -1 means insertMessage's IGNORE conflict strategy dropped it as an
            // already-seen txId (e.g. catch-up re-fetching something the live scan already
            // processed) - only notify for a genuinely new, incoming (not our own) message.
            if (rowId != -1L && !isOutgoing) {
                // Two floors, because they catch different things. The wallet-level baseline
                // covers history backfilled right after an account import. The per-group one
                // covers being invited to a group that already has a life: its earlier messages
                // are decryptable here (completeJoin archives older epochs rather than dropping
                // them), arrive on an ordinary sync well after the wallet baseline, and would
                // otherwise fire a banner each. group.createdAt is when THIS device learned about
                // the group, preserved across roster and epoch updates by upsertGroup's copy(),
                // so an add or remove cannot re-arm the flood. Two minutes of slack for clock skew
                // between devices. Matches iOS's floor in maybePostGroupLocalNotification.
                if (isBackfill(blockTimestamp) || blockTimestamp < group.createdAt - 120_000L) {
                    // History being backfilled: keep the group read and silent - only live
                    // traffic notifies or counts unread.
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
                // The remote push is the only banner source while push is active, foreground or
                // background (iOS ChatService.localBannersEnabled): the FCM handler words this one
                // with readGroupMessagePush. Only a device with no push banners from here.
                if (!pushState.isActive) {
                    val bannerText = groupMessageBannerText(group, senderAddress, walletAddress, plaintext)
                    if (bannerText != null) {
                        notificationHelper.showGroup(group.groupId, group.name, bannerText, dedupeTxId = txId)
                    } else {
                        // Deliberate silence (muted sender, or mentions-only group and this message
                        // neither mentions us nor replies to us). Claim the txId anyway so a racing
                        // FCM push for this same message can't post the banner this path just
                        // suppressed.
                        notificationHelper.claimWithoutNotifying(txId)
                    }
                }
            }
            return
        }
        Log.w("GroupRepository", "Rejected gcomm: no local group matched blindedGroupId ${parsed.blindedGroupId.toHexString()}")
    }

    /**
     * What a group message's banner says, or null when this group's notification rules keep it
     * quiet - one rule for both banner sources, the FCM handler and (with no push) the sync.
     */
    private suspend fun groupMessageBannerText(group: GroupEntity, senderAddress: String, walletAddress: String, plaintext: String): String? {
        // Muting still lets the message show up in the thread (getMessages isn't filtered on mute,
        // only on hide) - it only skips the notification, same as iOS's mute (enforced there via
        // push-registration exclusion, which a push already on its way can outrun).
        val isMuted = "${group.groupId}|$senderAddress" in settings.groupMutedMembers.first()
        val mentionsOnly = group.groupId in settings.groupMentionsOnlyApplied.first()
        val silent = group.groupId in settings.groupSilent.first()
        // "Mentions me" uses the SAME definition as the composer's @mention feature: members are
        // mentioned by their primary KNS domain (insertMention writes "@domain"), so match the
        // shared @token grammar against our own reverse-resolved domain via
        // GlobalNotificationCenterStore.mentionsMyDomain — never a naive substring scan. A raw
        // "@<full address>" paste still counts as a mention. Only evaluated when the group is
        // mentions-only (short-circuit).
        val mentionsMe = mentionsOnly && (
            plaintext.contains("@$walletAddress") ||
                kotlinx.coroutines.withTimeoutOrNull(NAME_LOOKUP_BUDGET_MS) { notificationCenter.mentionsMyDomain(plaintext) } == true
            )
        val isReplyToMe = MessageReply.parseOrNull(plaintext)?.replyToSender == walletAddress
        // Silent wins over everything else.
        if (silent || isMuted || (mentionsOnly && !mentionsMe && !isReplyToMe)) return null
        // alias > KNS primary domain > roster snapshot > short address - see groupSenderLabel;
        // same chain the reaction banner and the chat cards use.
        val senderLabel = groupSenderLabel(senderAddress, walletAddress, group)
        return when {
            VoiceMessage.parseOrNull(plaintext) != null -> context.getString(com.kachat.app.R.string.group_notif_sent_voice, senderLabel)
            ImageMessage.parseOrNull(plaintext) != null -> context.getString(com.kachat.app.R.string.sent_a_photo, senderLabel)
            // A reply says its own words, a payment request link is a card in the chat, and any
            // other envelope gets its placeholder: the same rule as a 1:1 banner (iOS
            // NotificationService.unwrapReplyText).
            else -> "$senderLabel: ${com.kachat.app.util.dmNotificationText(plaintext, notificationHelper.dmNotificationLabels())}"
        }
    }

    /**
     * What a group reaction's banner says, or null when the rules keep it quiet: "Alice reacted 👍
     * to your message" / "... to a message" (iOS: title is the group name, emoji omitted when
     * absent). Muted members stay silent. In a mentions-only group, only a reaction to YOUR OWN
     * message notifies - it's as personal as a reply to you - while reactions to others' messages
     * stay silent there.
     */
    private suspend fun groupReactionBannerText(
        group: GroupEntity, reaction: com.kachat.app.util.MessageReactionContent, senderAddress: String, walletAddress: String
    ): String? {
        val target = database.groupDao().getMessage(reaction.targetTxId, walletAddress)
        val targetIsMine = target?.isOutgoing == true
        val isMuted = "${group.groupId}|$senderAddress" in settings.groupMutedMembers.first()
        val silent = group.groupId in settings.groupSilent.first()
        val mentionsOnly = group.groupId in settings.groupMentionsOnlyApplied.first()
        if (silent || isMuted || (mentionsOnly && !targetIsMine)) return null
        // alias > KNS primary domain > roster snapshot > short address - see groupSenderLabel;
        // decodes the reactor the same way the chat cards and thread do.
        val senderLabel = groupSenderLabel(senderAddress, walletAddress, group)
        val emoji = reaction.emoji.trim().takeIf { it.isNotEmpty() }
        if (reaction.action != "add") {
            return if (emoji != null) context.getString(com.kachat.app.R.string.group_notif_removed_reaction_emoji, senderLabel, emoji)
            else context.getString(com.kachat.app.R.string.group_notif_removed_reaction, senderLabel)
        }
        return when {
            targetIsMine && emoji != null -> context.getString(com.kachat.app.R.string.group_notif_reacted_emoji_yours, senderLabel, emoji)
            targetIsMine -> context.getString(com.kachat.app.R.string.reacted_to_your_message_2, senderLabel)
            emoji != null -> context.getString(com.kachat.app.R.string.group_notif_reacted_emoji_other, senderLabel, emoji)
            else -> context.getString(com.kachat.app.R.string.reacted_to_a_message, senderLabel)
        }
    }

    /** What the FCM handler learned from a `group_message` push by reading it (see [readGroupMessagePush]). */
    sealed class GroupMessagePush {
        data class Banner(val groupId: String, val title: String, val text: String) : GroupMessagePush()
        /** Read, and nothing to announce: an edit, a member's answer, or this group's rules. */
        object Silent : GroupMessagePush()
        /** No payload, a group this device doesn't hold yet, or it failed its checks. */
        object Unreadable : GroupMessagePush()
    }

    /**
     * Reads a `group_message` push off its own payload with the group secrets held here, with no
     * network (iOS NotificationService.decryptGroupMessage). The push is the only banner source
     * while push is active, and it can't wait on a catch-up: that may run out of time, or have
     * already stored this very message on an earlier pass. [payloadString] is the whole
     * `kchat:1:gcomm:` payload; storing the message stays with the sync.
     */
    suspend fun readGroupMessagePush(payloadString: String?): GroupMessagePush {
        if (payloadString.isNullOrBlank()) return GroupMessagePush.Unreadable
        return try {
            val walletAddress = walletManager.getActiveAccount()?.address ?: return GroupMessagePush.Unreadable
            val parsed = GroupCipher.parseGroupMessagePayload(payloadString) ?: return GroupMessagePush.Unreadable
            val hrp = walletAddress.substringBefore(":")
            for (group in database.groupDao().getGroupsOnce(walletAddress)) {
                val bag = groupSecretStore.loadBag(walletAddress, group.groupId) ?: continue
                val blindingKey = try { bag.blindingKey.hexToByteArray() } catch (e: Exception) { continue }
                val groupIdBytes = try { group.groupId.hexToByteArray() } catch (e: Exception) { continue }
                if (!GroupCipher.deriveBlindedGroupId(blindingKey, parsed.senderPubKey).contentEquals(parsed.blindedGroupId)) continue

                // The same checks as handleIncomingGroupMessage: a push is no more trusted than a row.
                val senderAddress = KaspaAddress.encode(hrp, 0x00, parsed.senderPubKey)
                if (membersOf(group).none { it.address == senderAddress }) return GroupMessagePush.Unreadable
                if (!GroupCipher.deriveSenderId(senderAddress).contentEquals(parsed.senderId)) return GroupMessagePush.Unreadable
                val aad = GroupCipher.buildMessageAAD(groupIdBytes, parsed.epoch, parsed.senderId, parsed.msgId)
                val signingPayload = GroupCipher.buildMessageSigningPayload(aad, parsed.ciphertext)
                if (!GroupCipher.verify(parsed.signature, signingPayload, parsed.senderPubKey)) return GroupMessagePush.Unreadable
                val root = groupRootEpochFor(parsed.epoch, bag, groupIdBytes) ?: return GroupMessagePush.Unreadable
                val plaintext = GroupCipher.decryptMessage(parsed.ciphertext, root, groupIdBytes, parsed.epoch, parsed.senderId, parsed.msgId)
                    ?: return GroupMessagePush.Unreadable

                if (senderAddress == walletAddress) return GroupMessagePush.Silent
                // An edit changes an earlier bubble in place, and a member's answer only the list.
                if (GroupMemberStatus.isHiddenEnvelope(plaintext) || com.kachat.app.util.MessageEdit.isEditEnvelope(plaintext)) {
                    return GroupMessagePush.Silent
                }
                val reaction = MessageReaction.parseOrNull(plaintext)
                val text = if (reaction != null) {
                    groupReactionBannerText(group, reaction, senderAddress, walletAddress)
                } else {
                    groupMessageBannerText(group, senderAddress, walletAddress, plaintext)
                }
                return if (text != null) GroupMessagePush.Banner(group.groupId, group.name, text) else GroupMessagePush.Silent
            }
            GroupMessagePush.Unreadable
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("GroupRepository", "Group push read failed: ${e.message}")
            GroupMessagePush.Unreadable
        }
    }

    /** A transaction's whole payload as text, read off chain (iOS fetchGroupChainPayload): a group
     *  photo or a big roster's root is over the push's 3.5 KB, so its push carries none. */
    suspend fun readChainPayload(txId: String): String? {
        val api = networkService.kaspaRestApi.value ?: return null
        val payloadHex = api.getTransactionPayload(txId).payload?.takeIf { it.isNotEmpty() } ?: return null
        return String(payloadHex.hexToByteArray(), Charsets.UTF_8)
    }

    /** [readChainPayload] for a control: its encrypted part, in the form a push's own payload has. */
    suspend fun readChainControlPayload(txId: String): String? {
        val prefix = "kchat:1:gctl:"
        val normalized = GroupCipher.normalizeControlPayload(readChainPayload(txId) ?: return null)
        return normalized.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
    }

    /** What the FCM handler learned from a `group_control` push by reading it (see [readGroupControlPush]). */
    sealed class GroupControlPush {
        /** An invite nobody has been told about: "You were invited". */
        data class Invite(val inviteKey: String, val groupName: String) : GroupControlPush()
        /** A root with nothing to announce: for a group held here (it just rotates its keys), a
         *  later one of an invite already waiting, or one you rejected. */
        object Quiet : GroupControlPush()
        /** Read, and not a root (an epoch notice, a photo, a marker of your own): "Group update",
         *  as iOS says for whatever decryptGroupControlForName doesn't return. */
        object Update : GroupControlPush()
        /** No payload, or one this key can't open: it could be anything. */
        object Unreadable : GroupControlPush()
    }

    /**
     * Reads a `group_control` push off its own payload with the wallet key, with no network (iOS
     * NotificationService.decryptGroupControlForName), and files it the way the sync would - the
     * same bytes, so an invite is waiting in Message Requests by the time its banner is tapped.
     * [payloadHex] is the push's `enc_payload`: the hex of the sealed control.
     *
     * Once per invite, per admin: [NotificationHelper.showGroupInvite] holds that part, since an
     * invite a poll already held has not been announced yet and this push is what announces it.
     */
    suspend fun readGroupControlPush(payloadHex: String?, senderAddress: String, blockTime: Long, txId: String?): GroupControlPush {
        if (payloadHex.isNullOrBlank()) return GroupControlPush.Unreadable
        return try {
            val walletAddress = walletManager.getActiveAccount()?.address ?: return GroupControlPush.Unreadable
            val encrypted = KasiaCipher.EncryptedMessage.fromBytes(payloadHex.hexToByteArray()) ?: return GroupControlPush.Unreadable
            val plaintext = KasiaCipher.decrypt(encrypted, walletManager.getPrivateKeyBytes())
            // Lowercased as handleIncomingControlMessage does; a control that isn't a root fails here.
            val root = GroupCipher.rootPayloadFromJson(plaintext)?.let { candidate ->
                runCatching { candidate.copy(groupId = candidate.groupId.lowercase(), adminSigningPub = candidate.adminSigningPub.lowercase()) }.getOrNull()
            }
            // Before it's filed, which would change the answer.
            val inviteKey = root?.let { PendingGroupInvite.key(it.groupId, it.adminSigningPub) }
            val waitingBefore = inviteKey?.let { key -> groupSecretStore.loadInvites(walletAddress).firstOrNull { it.id == key } }

            handleIncomingControlMessage("kchat:1:gctl:$payloadHex", senderAddress, blockTime, txId)

            if (root == null || root.type != "gctl_root") return GroupControlPush.Update
            if (!GroupCipher.verifyRootPayload(root)) return GroupControlPush.Unreadable
            // Filing it applied every rule about who gets to invite you: if it isn't waiting now,
            // it's a group you hold, your own, one you rejected, or it left you out.
            val waiting = groupSecretStore.loadInvites(walletAddress).firstOrNull { it.id == inviteKey && it.rejected != true }
                ?: return GroupControlPush.Quiet
            if (database.groupDao().getGroup(root.groupId, walletAddress) != null) return GroupControlPush.Quiet
            // A later root of an invite already waiting: its first one was the announcement.
            if (waitingBefore != null && waitingBefore.roots.none { it.epoch == root.epoch }) return GroupControlPush.Quiet
            GroupControlPush.Invite(waiting.id, waiting.name)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("GroupRepository", "Group control push not readable: ${e.message}")
            GroupControlPush.Unreadable
        }
    }

    /** "wallet|txId" -> the invite a sync held from that control while push is active, unannounced,
     *  for the push that couldn't be read itself (see [inviteHeldByTx]). */
    private val invitesHeldByTx = java.util.concurrent.ConcurrentHashMap<String, GroupControlPush.Invite>()

    /** "wallet|txId" of verified roots read this process. */
    private val rootTxIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Whether a sync has read [txId] as a root: a push it couldn't read then has no "Group update"
     *  to give, the root having rotated a held group's keys or brought an invite. */
    fun wasRootTx(txId: String): Boolean {
        val walletAddress = walletManager.getActiveAccount()?.address ?: return false
        return "$walletAddress|$txId" in rootTxIds
    }

    /** The invite [txId] brought in, if a sync has just held one from it and no banner has gone out. */
    fun inviteHeldByTx(txId: String): GroupControlPush.Invite? {
        val walletAddress = walletManager.getActiveAccount()?.address ?: return null
        return invitesHeldByTx["$walletAddress|$txId"]
    }

    /** [fromBlockScan]: [senderAddress] is only what the transaction's first output claims. */
    suspend fun handleIncomingControlMessage(payloadString: String, senderAddress: String, blockTime: Long? = null, txId: String? = null, fromBlockScan: Boolean = false) {
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
                recordGroupTombstone(walletAddress, tomb.groupId, tomb.adminSigningPub, published = true)
                // A marker about another admin's offer of this id says nothing about the group held here.
                val held = database.groupDao().getGroup(tomb.groupId, walletAddress)
                if (held != null && (tomb.adminSigningPub == null || tomb.adminSigningPub.equals(held.adminXOnlyPubKeyHex, ignoreCase = true))) {
                    // Remove locally WITHOUT re-publishing (record already done above).
                    groupSecretStore.deleteBag(walletAddress, tomb.groupId)
                    completedGroupTxIds.clear()
                    database.groupDao().deleteMessagesForGroup(tomb.groupId, walletAddress)
                    database.reactionDao().deleteAllForGroup(tomb.groupId, walletAddress)
                    database.messageEditDao().deleteAllForGroup(tomb.groupId, walletAddress)
                    database.groupDao().deleteGroup(tomb.groupId, walletAddress)
                    forgetGroupHistoryRecords(walletAddress, tomb.groupId)
                }
            }
            return
        }
        // You accepted this group on another device, or before restoring these words. Honoured only
        // when signed by + addressed to us, like the delete marker above; the root may arrive
        // before or after it (iOS bfe9f022).
        val accept = GroupCipher.inviteAcceptPayloadFromJson(plaintext)
        if (accept != null && accept.type == "gctl_invite_accept") {
            val myPub = try { Schnorr.publicKeyXOnly(privateKey).toHexString() } catch (e: Exception) { null }
            if (accept.signingPub != myPub || !GroupCipher.verifyInviteAcceptPayload(accept)) return
            val key = PendingGroupInvite.key(accept.groupId, accept.adminSigningPub ?: return)
            groupSecretStore.updateInviteAnswers(walletAddress) { answers ->
                answers.copy(
                    accepted = if (key in answers.accepted) answers.accepted else answers.accepted + key,
                    published = if (key in answers.published) answers.published else answers.published + key,
                )
            }
            groupSecretStore.loadInvites(walletAddress).firstOrNull { it.id == key }
                ?.let { joinGroupInvite(walletAddress, it) }
            return
        }
        // An admin-set group photo — apply ONLY if signed by THIS group's known admin.
        val photo = GroupCipher.photoPayloadFromJson(plaintext)
        if (photo != null && photo.type == "gctl_photo") {
            val entity = database.groupDao().getGroup(photo.groupId, walletAddress) ?: return
            if (photo.signingPub == entity.adminXOnlyPubKeyHex && GroupCipher.verifyPhotoPayload(photo)) {
                // Ignore any photo control strictly older than the last one applied: re-walking
                // the control stream is a no-op, and can't bring back a line (iOS groupPhotoUpdatedAt).
                val appliedAtKey = "$walletAddress|${photo.groupId}"
                if (blockTime != null && blockTime > 0 && blockTime < photoAppliedAtPrefs.getLong(appliedAtKey, 0L)) return
                val newHex = photo.photo.ifEmpty { null }
                val changed = entity.photoHex != newHex
                database.groupDao().upsertGroup(entity.copy(photoHex = newHex))
                if (blockTime != null && blockTime > 0) photoAppliedAtPrefs.edit().putLong(appliedAtKey, blockTime).apply()
                // iMessage-style line for members (the admin emits its own in setGroupPhoto).
                if (changed) {
                    val who = if (entity.isAdmin) "You" else groupMemberLabel(entity.adminAddress, walletAddress, null)
                    insertGroupSystemMessage(photo.groupId, walletAddress,
                        if (newHex == null) "$who removed the group photo" else "$who changed the group photo",
                        stableKey = blockTime?.let { "photo_$it" })
                }
            }
            return
        }
        // The signature covers bytes, while everything remembered about a group is keyed by this
        // text: an uppercase copy of an id would read as one you never answered or deleted. A root
        // missing either field fails here rather than at verify.
        val rootPayload = GroupCipher.rootPayloadFromJson(plaintext)?.let { root ->
            runCatching { root.copy(groupId = root.groupId.lowercase(), adminSigningPub = root.adminSigningPub.lowercase()) }.getOrNull()
        }
        if (rootPayload != null && rootPayload.type == "gctl_root" && GroupCipher.verifyRootPayload(rootPayload)) {
            if (rootPayload.rosterSig != null || rootPayload.rosterVersion != null) {
                // The admin signed the name and members, so it doesn't matter who delivered them. One
                // that fails has been edited: it never falls back to the sender rule below.
                if (!GroupCipher.verifyRoster(rootPayload)) {
                    Log.w("GroupRepository", "Refused a root for ${rootPayload.groupId.take(12)} with a bad roster signature")
                    return
                }
            } else {
                if (!GroupCipher.rootSenderAllowed(senderAddress, rootPayload.adminSigningPub, com.kachat.app.util.KaspaNetwork.hrp)) {
                    Log.w("GroupRepository", "Refused a root for ${rootPayload.groupId.take(12)} sent by an address other than its admin")
                    return
                }
                // From the block scan that sender is only the first output, which whoever built
                // the transaction picks, and this root's roster rests on it. The inputs settle it;
                // if they can't yet, catch-up delivers the root again (iOS 4f8e14bb).
                if (fromBlockScan && !isSelfSent && txId != null) {
                    senderProofScope.launch {
                        if (networkService.chainHistory.provenSender(txId, senderAddress, walletAddress) != SenderProof.PROVEN) return@launch
                        if (walletManager.getActiveAccount()?.address != walletAddress) return@launch
                        ingestRow("gctl", txId) { handleIncomingControlMessage(payloadString, senderAddress, blockTime, txId) }
                    }
                    return
                }
            }
            // For the FCM handler, when a sync got to read a root its push couldn't (see wasRootTx).
            if (txId != null) {
                if (rootTxIds.size >= 256) rootTxIds.clear()
                rootTxIds.add("$walletAddress|$txId")
            }
            if (isSelfSent && rootPayload.groupSeed == null) return // our own member-copy echo
            // Nobody joins a group by being added to it: a group this device doesn't hold waits in
            // Message Requests. Your own (the admin's recovery copy) just comes back, and so does one
            // you already said yes to: that admin's, not whoever else signs a root for this id.
            val isNewGroup = database.groupDao().getGroup(rootPayload.groupId, walletAddress) == null
            val myPub = try { Schnorr.publicKeyXOnly(privateKey).toHexString().lowercase() } catch (e: Exception) { null }
            val inviteKey = PendingGroupInvite.key(rootPayload.groupId, rootPayload.adminSigningPub)
            if (isNewGroup && rootPayload.adminSigningPub != myPub &&
                inviteKey !in groupSecretStore.loadInviteAnswers(walletAddress).accepted
            ) {
                holdGroupInvite(rootPayload, blockTime, txId)
                return
            }
            completeJoin(rootPayload, blockTime)
        }
        // gctl_epoch is an advance-notice heads-up only (state updates on gctl_root arrival,
        // not on gctl_epoch) - no local state change needed here in the data layer.
    }

    // -------------------------------------------------------------------------
    // Group invites (Message Requests) - iOS bfe9f022, MESSAGING.md "Group invites"
    // -------------------------------------------------------------------------

    /** Bumped on every invite or member-status change, so the flows below re-read the store. */
    private val inviteRevision = MutableStateFlow(0)

    private fun inviteStateChanged() {
        inviteRevision.update { it + 1 }
    }

    /** Only one pass of sending answers at a time: each is a transaction. */
    private val groupAnswersMutex = kotlinx.coroutines.sync.Mutex()

    /** Invites waiting for an answer, newest first, leaving out any group this device has since
     *  come to hold (a backup restore brings joined groups back on its own). */
    fun getPendingGroupInvites(): Flow<List<PendingGroupInvite>> {
        return walletManager.activeAddressFlow.flatMapLatest { address ->
            if (address == null) {
                flowOf(emptyList())
            } else {
                combine(inviteRevision, database.groupDao().getGroups(address)) { _, groups ->
                    val held = groups.map { it.groupId }.toSet()
                    groupSecretStore.loadInvites(address)
                        .filter { it.rejected != true && it.groupId !in held }
                        .sortedByDescending { it.invitedAt }
                }.flowOn(Dispatchers.Default)
            }
        }
    }

    /** Member address -> "invited" or "declined", when there's something to say: someone who
     *  joined is just a member, and so is someone with no invite on record (iOS inviteStatus). */
    fun getGroupInviteStatuses(groupId: String): Flow<Map<String, String>> {
        return walletManager.activeAddressFlow.flatMapLatest { address ->
            if (address == null) {
                flowOf(emptyMap())
            } else {
                inviteRevision.map {
                    groupSecretStore.loadMemberStatuses(address)[groupId].orEmpty()
                        .filterValues { it != GroupMemberStatus.JOINED }
                }
            }
        }
    }

    private suspend fun holdGroupInvite(payload: GroupCipher.GroupRootPayload, blockTime: Long?, txId: String?) {
        val walletAddress = walletManager.getAddress()
        if (groupSecretStore.isTombstoned(walletAddress, payload.groupId, payload.adminSigningPub)) return
        val me = walletAddress.lowercase()
        val includesMe = payload.members.any { it.lowercase() == me }
        val adminAddress = try {
            KaspaAddress.encode(walletAddress.substringBefore(":"), 0x00, payload.adminSigningPub.hexToByteArray())
        } catch (e: Exception) {
            ""
        }
        val key = PendingGroupInvite.key(payload.groupId, payload.adminSigningPub)
        var isNewInvite = false
        var takenBack = false
        groupSecretStore.updateInvites(walletAddress) { invites ->
            val existing = invites.firstOrNull { it.id == key }
            if (existing != null) {
                // Later roots of the same invite, from the same admin: the group's history if you
                // join. One that leaves you out takes the invite back. A different admin's root for
                // this id is its own invite, so whoever got here first can't shut the real one out.
                // A signed roster isn't replaced by an unsigned or older one for the same epoch.
                val heldVersion = existing.roots.firstOrNull { it.epoch == payload.epoch }?.rosterVersion
                if (heldVersion != null && (payload.rosterVersion ?: 0L) < heldVersion) {
                    invites
                } else if (payload.epoch >= existing.latest.epoch && !includesMe) {
                    takenBack = true
                    invites - existing
                } else {
                    val roots = (existing.roots.filterNot { it.epoch == payload.epoch } + payload).sortedBy { it.epoch }
                    val updated = existing.copy(roots = roots, invitedAt = maxOf(existing.invitedAt, blockTime ?: existing.invitedAt))
                    invites.map { if (it === existing) updated else it }
                }
            } else if (includesMe && adminAddress.isNotEmpty() && !chatRequestStore.isBlocked(adminAddress)) {
                // Someone you rejected stays ignored, in groups too. Anyone can name you in a root
                // for the price of a transaction, and each one sits in this store and in Message
                // Requests until it's answered (iOS 4f8e14bb).
                val waiting = invites.filter { it.rejected != true }
                if (waiting.size >= MAX_PENDING_GROUP_INVITES ||
                    waiting.count { it.adminAddress == adminAddress } >= MAX_PENDING_GROUP_INVITES_PER_ADMIN
                ) {
                    Log.w("GroupRepository", "Too many group invites waiting; dropped one from ${adminAddress.takeLast(10)}")
                    invites
                } else {
                    isNewInvite = true
                    invites + PendingGroupInvite(
                        adminAddress = adminAddress,
                        invitedAt = blockTime ?: System.currentTimeMillis(),
                        roots = listOf(payload),
                    )
                }
            } else {
                invites
            }
        }
        inviteStateChanged()
        // Invited again later, it's news again.
        if (takenBack) notificationHelper.forgetGroupInviteAnnouncement(key)
        // Announced once, like the first message from someone new; history an import walks through
        // waits in Message Requests quietly. While push is active the push is what announces it
        // (iOS ChatService.localBannersEnabled), whichever of the two got here first.
        if (isNewInvite) {
            when {
                isBackfill(blockTime) -> notificationHelper.markGroupInviteAnnounced(key)
                !pushState.isActive -> notificationHelper.showGroupInvite(key, payload.name, dedupeTxId = txId)
                txId != null -> {
                    if (invitesHeldByTx.size >= 256) invitesHeldByTx.clear()
                    invitesHeldByTx["$walletAddress|$txId"] = GroupControlPush.Invite(key, payload.name)
                }
            }
        }
    }

    /** Accept: the group is joined now and its history comes in with the catch-up. The answer goes
     *  on chain, encrypted: to your own devices, and to the group as a status message. */
    suspend fun acceptGroupInvite(invite: PendingGroupInvite) {
        val walletAddress = walletManager.getAddress()
        joinGroupInvite(walletAddress, invite)
        groupSecretStore.updateInviteAnswers(walletAddress) { answers ->
            answers.copy(
                accepted = if (invite.id in answers.accepted) answers.accepted else answers.accepted + invite.id,
                statusOutbox = if (invite.groupId in answers.statusOutbox) answers.statusOutbox else answers.statusOutbox + invite.groupId,
            )
        }
        publishGroupAnswers()
        runGroupCatchUp()
    }

    /** Newest root first, so the older ones only fill in history and nothing reads as a membership
     *  change. The invite goes last: until the join is stored, it is what holds the keys. A device
     *  holds one group per id, so another admin's offer of it goes too, unless it's only waiting to
     *  say you declined. */
    private suspend fun joinGroupInvite(walletAddress: String, invite: PendingGroupInvite) {
        for (root in invite.roots.sortedByDescending { it.epoch }) {
            completeJoin(root, invite.invitedAt)
        }
        groupSecretStore.updateInvites(walletAddress) { invites ->
            invites.filterNot { it.groupId == invite.groupId && (it.rejected != true || it.id == invite.id) }
        }
        inviteStateChanged()
    }

    /** Reject: this admin's offer is never made again, here or after a restore (the same on-chain
     *  delete marker a deleted group publishes), and its members see you declined. The invite's keys
     *  stay only until that message has gone out, since they're what it's sent with. */
    suspend fun rejectGroupInvite(invite: PendingGroupInvite) {
        val walletAddress = walletManager.getAddress()
        if (groupSecretStore.loadInvites(walletAddress).none { it.id == invite.id }) return
        groupSecretStore.updateInvites(walletAddress) { invites ->
            invites.map { if (it.id == invite.id) it.copy(rejected = true) else it }
        }
        recordGroupTombstone(walletAddress, invite.groupId, invite.adminSigningPub, published = false)
        try { publishGroupTombstone(walletAddress, invite.groupId, invite.adminSigningPub) } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w("GroupRepository", "Group tombstone publish failed for ${invite.groupId.take(12)}", e)
        }
        publishGroupAnswers()
    }

    /** Sends every answer still owed. Each is a transaction, so one that can't go yet (no funds, no
     *  connection) waits for the next catch-up, like a delete marker. */
    private suspend fun publishGroupAnswers() {
        // Signed out: no spending (iOS has no current wallet then). Owed answers stay owed.
        if (walletManager.isLoggedOut) return
        if (!groupAnswersMutex.tryLock()) return
        try {
            val walletAddress = walletManager.getActiveAccount()?.address ?: return
            val privateKey = try { walletManager.getPrivateKeyBytes() } catch (e: Exception) { return }
            val selfXOnlyPub = xOnlyPubKeyOrNull(walletAddress) ?: return
            val signingPub = Schnorr.publicKeyXOnly(privateKey)

            val answers = groupSecretStore.loadInviteAnswers(walletAddress)
            for (key in answers.accepted) {
                if (key in groupSecretStore.loadInviteAnswers(walletAddress).published) continue
                val parts = key.split(":")
                if (parts.size != 2) continue
                try {
                    val payload = GroupCipher.buildSignedInviteAcceptPayload(parts[0].hexToByteArray(), parts[1].hexToByteArray(), signingPub, privateKey)
                    sendControlPayload(GroupCipher.inviteAcceptPayloadToJson(payload), selfXOnlyPub, privateKey)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w("GroupRepository", "Group invite accept failed for ${key.take(12)}", e)
                    continue
                }
                groupSecretStore.updateInviteAnswers(walletAddress) { it.copy(published = it.published + key) }
            }
            for (groupId in groupSecretStore.loadInviteAnswers(walletAddress).statusOutbox) {
                // Reserved BEFORE building/sending, atomically (see sendGroupMessage) - a msg_id
                // must never be reused even if the send itself later fails.
                val reserved = groupSecretStore.reserveNextCounter(walletAddress, groupId)
                if (reserved != null) {
                    val (bag, counter) = reserved
                    try {
                        sendMemberStatus(
                            GroupMemberStatus.JOINED, groupId, bag.groupRootEpoch, bag.blindingKey, bag.currentEpoch,
                            GroupCipher.buildMsgId(bag.deviceId.hexToByteArray(), counter),
                        )
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.w("GroupRepository", "Group joined status failed for ${groupId.take(12)}", e)
                        continue
                    }
                }
                groupSecretStore.updateInviteAnswers(walletAddress) { current ->
                    current.copy(statusOutbox = current.statusOutbox.filterNot { it == groupId })
                }
            }
            for (invite in groupSecretStore.loadInvites(walletAddress).filter { it.rejected == true }) {
                val root = invite.latest
                try {
                    sendMemberStatus(
                        GroupMemberStatus.DECLINED, invite.groupId, root.groupRootEpoch, root.blindingKey, root.epoch,
                        GroupCipher.buildMsgId(GroupCipher.generateDeviceId(), 1),
                    )
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w("GroupRepository", "Group declined status failed for ${invite.groupId.take(12)}", e)
                    continue
                }
                groupSecretStore.updateInvites(walletAddress) { invites -> invites.filterNot { it.id == invite.id } }
                inviteStateChanged()
            }
        } finally {
            groupAnswersMutex.unlock()
        }
    }

    /** One group message saying you joined or declined. Encrypted to the group like any message,
     *  and signed by you, so only members can read it and nobody can say it for you. The same
     *  sealing as [sendGroupMessage], with the keys passed in: a declined invite has no bag. */
    private suspend fun sendMemberStatus(
        status: String, groupId: String, rootHex: String, blindingKeyHex: String, epoch: Long, msgId: ByteArray,
    ) {
        val walletAddress = walletManager.getAddress()
        val groupIdBytes = groupId.hexToByteArray()
        val privateKey = walletManager.getPrivateKeyBytes()
        val senderXOnlyPub = Schnorr.publicKeyXOnly(privateKey)
        val senderId = GroupCipher.deriveSenderId(walletAddress)
        val ciphertext = GroupCipher.encryptMessage(GroupMemberStatus.encode(status), rootHex.hexToByteArray(), groupIdBytes, epoch, senderId, msgId)
        val aad = GroupCipher.buildMessageAAD(groupIdBytes, epoch, senderId, msgId)
        val signature = GroupCipher.sign(GroupCipher.buildMessageSigningPayload(aad, ciphertext), privateKey)
        val blindedGroupId = GroupCipher.deriveBlindedGroupId(blindingKeyHex.hexToByteArray(), senderXOnlyPub)
        val payloadString = GroupCipher.buildGroupMessagePayload(blindedGroupId, epoch, senderId, senderXOnlyPub, msgId, ciphertext, signature)
        walletService.sendKaspa(toAddress = walletAddress, amountSompi = 0, payloadBytes = payloadString.toByteArray(Charsets.UTF_8))
    }

    private fun setMemberStatus(status: String?, address: String, groupId: String, walletAddress: String) {
        if (groupSecretStore.setMemberStatus(walletAddress, groupId, address, status)) inviteStateChanged()
    }

    /** A status message from [address]. "X joined" only reads as news while it's recent: history
     *  loaded by joining later updates the list and adds no lines. */
    private suspend fun applyMemberStatus(status: String, address: String, groupId: String, walletAddress: String, txId: String, blockTime: Long) {
        val changed = groupSecretStore.setMemberStatus(walletAddress, groupId, address, status)
        if (changed) inviteStateChanged()
        val isRecent = blockTime > 0 && System.currentTimeMillis() - blockTime < GROUP_SYSTEM_MESSAGE_LIFETIME_MS
        if (!changed || !isRecent || address == walletAddress) return
        val label = groupMemberLabel(address, walletAddress, null)
        insertGroupSystemMessage(
            groupId, walletAddress,
            if (status == GroupMemberStatus.JOINED) "$label joined the group" else "$label declined the invite",
            stableKey = "status_${txId.take(12)}",
        )
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
        if (groupSecretStore.isTombstoned(walletAddress, payload.groupId, payload.adminSigningPub)) return
        // The signature only proves the payload agrees with itself (see verifyRootPayload): anyone
        // who knows a group id can sign a root naming themselves admin. A group this device holds
        // keeps the admin it has, or a removed member could take it over with a higher epoch
        // (iOS bfe9f022).
        val knownAdmin = database.groupDao().getGroup(payload.groupId, walletAddress)?.adminXOnlyPubKeyHex
        if (!knownAdmin.isNullOrEmpty() && !knownAdmin.equals(payload.adminSigningPub, ignoreCase = true)) {
            Log.w("GroupRepository", "Refused a root for ${payload.groupId.take(12)} signed by a different admin")
            return
        }
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
                groupSecretStore.updateBag(walletAddress, payload.groupId) { current ->
                    current?.takeIf { it.currentEpoch > payload.epoch }?.let {
                        it.copy(previousRoots = it.previousRoots.orEmpty() + (payload.epoch to payload.groupRootEpoch))
                    }
                }
                refreshRejections?.let { it.epochRootsArchived++ }
                Log.i("GroupRepository", "Archived epoch ${payload.epoch} root for a group whose bag is newer")
            }
            return
        }
        // After a signed roster, an unsigned or older one can't change the name or members. A newer
        // epoch's keys still count, since only the admin can sign those: an admin device on an older
        // app would otherwise cut every member off from the group's new messages.
        val pinned = existingBag?.rosterVersion
        if (existingBag != null && pinned != null && (payload.rosterVersion ?: 0L) < pinned) {
            val held = database.groupDao().getGroup(payload.groupId, walletAddress)
            if (payload.epoch <= existingBag.currentEpoch || held == null) {
                Log.w("GroupRepository", "Refused a root for ${payload.groupId.take(12)} with no newer signed roster")
                return
            }
            applyRootPayload(
                payload.copy(members = membersOf(held).map { it.address }, name = held.name, rosterVersion = null, rosterSig = null),
                blockTime,
            )
            return
        }
        applyRootPayload(payload, blockTime)
    }

    /** Stores a root [completeJoin] has let through: the group's secrets, roster and name. */
    private suspend fun applyRootPayload(payload: GroupCipher.GroupRootPayload, blockTime: Long?) {
        val walletAddress = walletManager.getAddress()

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
        val seedToKeep = recoveredSeedHex

        // Built from the bag as stored at the moment of writing, in one step: one read earlier
        // could carry an older msgCounter back over a send that reserved a newer one.
        var existingBag: GroupBag? = null
        var superseded = false
        groupSecretStore.updateBag(walletAddress, payload.groupId) { previous ->
            existingBag = previous
            // completeJoin checked the epoch against an earlier read; never downgrade on this one.
            if (previous != null && previous.currentEpoch > payload.epoch) {
                superseded = true
                return@updateBag null
            }
            // device_id is persistent per device - preserve it across epoch-rotation updates to an
            // already-joined group; only a genuinely first-time join mints a new one. msgCounter
            // resets to 0 only when the epoch actually advances - a same-epoch re-send of the root
            // (e.g. `renameGroup`, which doesn't rotate the epoch, or any other duplicate delivery)
            // must NOT reset it, since a msg_id must never be reused - resetting here would let this
            // device's next send collide with a counter value it already used earlier in the same
            // epoch. groupSeed is preserved defensively in case this device somehow already held
            // admin secrets for this group.
            val deviceId = previous?.deviceId ?: GroupCipher.generateDeviceId().toHexString()
            val preservedCounter = if (previous != null && previous.currentEpoch == payload.epoch) previous.msgCounter else 0L
            // Archive the root being replaced. This is THE line that keeps a non-admin member's
            // history: without it, a rotated root made every message from the previous epoch
            // undecryptable and the thread rendered empty from that moment on.
            val carriedRoots = previous?.let {
                if (it.currentEpoch != payload.epoch && it.groupRootEpoch.isNotBlank()) {
                    it.previousRoots.orEmpty() + (it.currentEpoch to it.groupRootEpoch)
                } else {
                    it.previousRoots.orEmpty()
                }
            } ?: emptyMap()
            GroupBag(
                groupId = payload.groupId, groupSeed = seedToKeep ?: previous?.groupSeed, groupRootEpoch = payload.groupRootEpoch,
                blindingKey = payload.blindingKey, currentEpoch = payload.epoch, deviceId = deviceId, msgCounter = preservedCounter,
                selfInviteEpoch = if (seedToKeep != null) payload.epoch else previous?.selfInviteEpoch,
                previousRoots = carriedRoots.ifEmpty { null },
                rosterVersion = payload.rosterVersion ?: previous?.rosterVersion,
            )
        }
        if (superseded) return

        val members = payload.members.mapNotNull { address ->
            val xOnlyPub = xOnlyPubKeyOrNull(address) ?: return@mapNotNull null
            GroupMember(address = address, xOnlyPubKeyHex = xOnlyPub.toHexString(), isAdmin = xOnlyPub.toHexString() == payload.adminSigningPub)
        }
        val adminAddress = try {
            KaspaAddress.encode(com.kachat.app.util.KaspaNetwork.hrp, 0x00, payload.adminSigningPub.hexToByteArray())
        } catch (e: Exception) {
            members.firstOrNull { it.isAdmin }?.address ?: ""
        }

        // Membership diff for iMessage-style system lines: capture the roster we held BEFORE this
        // root updates it, so a receiving member sees "X was added"/"Y was removed" when the admin
        // rotates the key. Skipped on a first-time join (no false "added" storm for the members who
        // were already there when we joined), and on a group with no keys (iOS applyRootPayload):
        // it has lost them, and the first root back may be an old one from the walk that restores
        // them, so what's stored is no baseline to report changes from.
        val existingEntity = database.groupDao().getGroup(payload.groupId, walletAddress)
        val backfill = isBackfill(blockTime)
        val previousForDiff = existingEntity?.takeIf { existingBag != null }
        val previousRosterForDiff: List<GroupMember>? = previousForDiff?.let(::membersOf)

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
            recordFormerMembers(walletAddress, payload.groupId, prev, members, lastEpoch = if (payload.epoch > 0) payload.epoch - 1 else 0L, removedAt = blockTime ?: 0L)
            emitMembershipSystemMessages(payload.groupId, walletAddress, prev, members)
        }
        // iMessage-style rename line for members (the admin emits its own in renameGroup).
        if (previousForDiff != null && previousForDiff.name != payload.name) {
            val who = if (walletAddress == adminAddress) "You" else groupMemberLabel(adminAddress, walletAddress, null)
            val text = "$who changed the group name to \"${payload.name}\""
            insertGroupSystemMessage(payload.groupId, walletAddress, text, stableKey = blockTime?.let { "${it}_${text.hashCode()}" })
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
    private val catchUpMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile
    private var catchUpQueued = false

    /**
     * [syncGroups], one at a time: a request during one queues exactly one more, and any further
     * request while that one waits is already covered by it. Opening a group runs this at once
     * (iOS 360e5d2 runGroupCatchUp), so a thread opened from a tapped notification shows what
     * arrived while the app was away without two full syncs running side by side.
     */
    suspend fun runGroupCatchUp() {
        if (catchUpMutex.isLocked) {
            if (catchUpQueued) return
            catchUpQueued = true
        }
        catchUpMutex.lock()
        try {
            catchUpQueued = false
            syncGroups()
        } finally {
            catchUpMutex.unlock()
        }
    }

    /** What one live pass against the indexer's `since` reads learned. */
    sealed class LiveGroupPoll {
        /** The indexer has no `since` reads (404/405/501): stop asking this session, keep the stream. */
        object Unsupported : LiveGroupPoll()
        /** Answered; [newest] is where the next pass on this cursor reads from. */
        data class Answered(val newest: Long) : LiveGroupPoll()
        /** Nothing learned (no indexer yet, a network error): the cursor stays where it was. */
        object Failed : LiveGroupPoll()
    }

    /**
     * One pass of live group delivery (iOS a56fb98 pollIndexerForLiveGroupTraffic): the messages of
     * [onlyGroupId] - or of every group when null - newer than [cursor], and control too when
     * [includeControl]. Rows go through the same ingest as the catch-up sync and the block scan,
     * which dedupe by txId, so the deliberate overlap between passes is harmless.
     *
     * Each pass starts [LIVE_POLL_REWIND_MS] before its cursor: the indexer's clock and a reorg both
     * move. A full page is followed straight away from its `latestBlockTime`.
     */
    suspend fun pollLiveGroupTraffic(onlyGroupId: String?, includeControl: Boolean, cursor: Long): LiveGroupPoll {
        if (walletManager.isLoggedOut) return LiveGroupPoll.Failed
        val api = networkService.indexerApi.value ?: return LiveGroupPoll.Failed
        val walletAddress = walletManager.getActiveAccount()?.address ?: return LiveGroupPoll.Failed
        val since = if (cursor > LIVE_POLL_REWIND_MS) cursor - LIVE_POLL_REWIND_MS else 0L
        val groups = database.groupDao().getGroupsOnce(walletAddress)
            .filter { onlyGroupId == null || it.groupId == onlyGroupId }
        val blindedIds = buildList {
            for (group in groups) {
                val bag = groupSecretStore.loadBag(walletAddress, group.groupId) ?: continue
                val blindingKey = try { bag.blindingKey.hexToByteArray() } catch (e: Exception) { continue }
                for (member in membersOf(group)) {
                    val memberPub = try { member.xOnlyPubKeyHex.hexToByteArray() } catch (e: Exception) { continue }
                    add(GroupCipher.deriveBlindedGroupId(blindingKey, memberPub).toHexString())
                }
            }
        }
        if (blindedIds.isEmpty()) return LiveGroupPoll.Answered(cursor)
        var newest = cursor
        return try {
            var page = since
            for (pass in 0 until 10) {
                val response = api.getGroupMessagesSince(
                    GroupMessagesSinceBody(blindedIds.take(256), page, LIVE_POLL_PAGE)
                )
                val rows = response.messages.orEmpty()
                for (msg in rows) {
                    val payloadString = reconstructPayloadString("kchat:1:gcomm:", msg.messagePayload) ?: continue
                    val parsed = GroupCipher.parseGroupMessagePayload(payloadString) ?: continue
                    ingestRow("gcomm", msg.txId) { handleIncomingGroupMessage(parsed, msg.txId, msg.blockTime) }
                    newest = maxOf(newest, msg.blockTime)
                }
                val latest = response.latestBlockTime ?: rows.maxOfOrNull { it.blockTime } ?: page
                newest = maxOf(newest, latest)
                if (rows.size < LIVE_POLL_PAGE || latest <= page) break
                page = latest
            }
            if (includeControl) {
                val senders = groups.map { it.adminAddress }.filter { it.isNotEmpty() }.distinct()
                val response = api.getGroupControlSince(
                    GroupControlSinceBody(senders, walletAddress, since, LIVE_POLL_PAGE)
                )
                for (msg in response.controls.orEmpty()) {
                    val payloadString = reconstructPayloadString("kchat:1:gctl:", msg.messagePayload) ?: continue
                    ingestRow("gctl", msg.txId) { handleIncomingControlMessage(payloadString, msg.sender, msg.blockTime, msg.txId) }
                    newest = maxOf(newest, msg.blockTime)
                }
            }
            if (onlyGroupId == null) allGroupsCursors[walletAddress] = newest
            LiveGroupPoll.Answered(newest)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 404 || e.code() == 405 || e.code() == 501) LiveGroupPoll.Unsupported else LiveGroupPoll.Failed
        } catch (e: Exception) {
            Log.w("GroupRepository", "Live group poll failed", e)
            LiveGroupPoll.Failed
        }
    }

    suspend fun syncGroups() {
        // Signed out: nothing runs for this wallet, above all not the tombstones, recovery invites
        // and invite answers below, each a transaction (iOS has no current wallet then, and its
        // catch-up guards on that; getActiveAccount survives logout here).
        if (walletManager.isLoggedOut) return
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
        for (entry in tombState.deleted) {
            if (entry in tombState.published) continue
            val parts = entry.split(":")
            try { publishGroupTombstone(walletAddress, parts[0], parts.getOrNull(1)) } catch (e: Exception) {
                Log.w("GroupRepository", "Group tombstone backfill failed for ${entry.take(12)}", e)
            }
        }
        // Invite answers that couldn't go when they were given.
        publishGroupAnswers()

        applyGroupRetentionOnce(walletAddress)

        // Not while the indexer is down: what's missed is read from the chain instead (iOS 4f8e14bb).
        if (IndexerHealth.isDown.value) {
            catchUpGroupsFromChain(walletAddress)
            return
        }

        val restoringKeys = prepareToRestoreMissingGroupKeys(walletAddress)

        syncGroupControlByRecipient(api, walletAddress)
        // After the catch-up: an admin's second device must not sign a roster it hasn't updated.
        resendSignedRootsToLegacyGroups(walletAddress)

        // The admin's stream for a group being restored too: with no keys yet, the loop below skips it.
        for (group in restoringKeys) {
            if (group.adminAddress.isNotEmpty()) syncGroupControlBySender(api, walletAddress, group.adminAddress)
        }

        val groups = database.groupDao().getGroupsOnce(walletAddress)
        for (group in groups) {
            val bag = groupSecretStore.loadBag(walletAddress, group.groupId) ?: continue
            val blindingKey = try { bag.blindingKey.hexToByteArray() } catch (e: Exception) { continue }

            for (pubKeyHex in catchUpLanePubKeys(walletAddress, group)) {
                val memberPubKey = try { pubKeyHex.hexToByteArray() } catch (e: Exception) { continue }
                val blindedGroupIdHex = GroupCipher.deriveBlindedGroupId(blindingKey, memberPubKey).toHexString()
                syncGroupMessages(api, walletAddress, group.groupId, blindedGroupIdHex)
            }

            if (group.adminAddress.isNotEmpty()) {
                syncGroupControlBySender(api, walletAddress, group.adminAddress)
            }
        }

        // The indexer went down during this pass.
        if (IndexerHealth.isDown.value) {
            catchUpGroupsFromChain(walletAddress)
            return
        }

        settlePendingSendsFromBeforeThisRun(walletAddress)

        // A restored group went off the push watch list while its keys were missing.
        if (restoringKeys.any { groupSecretStore.loadBag(walletAddress, it.groupId) != null }) {
            _groupKeysRestored.update { it + 1 }
        }
    }

    // -------------------------------------------------------------------------
    // Catch-up from the chain, while the indexer is down (iOS 4f8e14bb)
    // -------------------------------------------------------------------------

    /** "wallet|address" -> newest row read from that address's chain history. In memory only:
     *  when the indexer is back it serves the outage window anyway. */
    private val chainCatchUpCursors = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** `gctl` txid -> whether its sender was proven, once known. */
    private val chainControlProofs = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val isCatchingUpFromChain = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Wallet -> where the live all-groups read has reached. */
    private val allGroupsCursors = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * The catch-up with the indexer down: every `gcomm` and `gctl` is a transaction from its author
     * to themselves, so each admin's and member's own history holds them. Control first, for the
     * same reason as [syncGroups]. The indexer's cursors stay where they are, so it serves the same
     * window again when it's back, and the ingest dedupes by txId. Invites to groups this wallet
     * doesn't know yet can't be found this way: those come from the block stream.
     *
     * Each address keeps its own cursor at the newest row seen, so a pass during an outage asks
     * only for what's new, instead of re-reading days of history for every member, every time.
     */
    private suspend fun catchUpGroupsFromChain(walletAddress: String) {
        if (!isCatchingUpFromChain.compareAndSet(false, true)) return
        try {
            val nowMs = System.currentTimeMillis()
            val cursor = allGroupsCursors[walletAddress] ?: 0L
            val rewound = if (cursor > LIVE_POLL_REWIND_MS) cursor - LIVE_POLL_REWIND_MS else 0L
            val since = maxOf(rewound, nowMs - CHAIN_CATCH_UP_LOOKBACK_MS)
            val groups = database.groupDao().getGroupsOnce(walletAddress)
            val admins = groups.map { it.adminAddress }.filter { it.isNotEmpty() }.toSet()
            val members = groups.flatMap { group ->
                membersOf(group).map { it.address } + recentFormerMembers(walletAddress, group.groupId).map { it.address }
            }.toSet() - admins
            for (address in admins.toList() + members.toList()) {
                if (walletManager.getActiveAccount()?.address != walletAddress) return
                val cursorKey = "$walletAddress|$address"
                val from = maxOf(since, chainCatchUpCursors[cursorKey] ?: 0L)
                val history = try {
                    networkService.chainHistory.chainTransactions(address, from)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("GroupRepository", "Group chain history read failed for ${address.takeLast(10)}: ${e.message}")
                    continue
                }
                var newestSeen = from
                for (tx in history) {
                    val isControl = tx.payloadHex.startsWith(GCTL_PREFIX_HEX) || tx.payloadHex.startsWith(LEGACY_GCTL_PREFIX_HEX)
                    val isMessage = tx.payloadHex.startsWith(GCOMM_PREFIX_HEX) || tx.payloadHex.startsWith(LEGACY_GCOMM_PREFIX_HEX)
                    if (address.lowercase() in tx.senders && (isControl || isMessage)) {
                        val payload = runCatching { String(tx.payloadHex.hexToByteArray(), Charsets.UTF_8) }.getOrNull()
                        if (payload != null && isControl) {
                            val proof = chainControlProof(tx.txId, address, walletAddress)
                            if (walletManager.getActiveAccount()?.address != walletAddress) return
                            // Not listed yet, or the REST API is struggling: stop this address here,
                            // so the next pass starts from this row. Order matters for control.
                            if (proof == SenderProof.UNKNOWN) break
                            if (proof == SenderProof.PROVEN) {
                                ingestRow("gctl", tx.txId) {
                                    handleIncomingControlMessage(GroupCipher.normalizeControlPayload(payload), address, tx.blockTime, tx.txId)
                                }
                            }
                        } else if (payload != null) {
                            val parsed = GroupCipher.parseGroupMessagePayload(payload)
                            if (parsed != null) {
                                ingestRow("gcomm", tx.txId) { handleIncomingGroupMessage(parsed, tx.txId, tx.blockTime) }
                            }
                        }
                    }
                    // Capped at now: one row claiming a far-future time would freeze the cursor.
                    newestSeen = maxOf(newestSeen, minOf(tx.blockTime, nowMs))
                }
                chainCatchUpCursors[cursorKey] = newestSeen
            }
        } finally {
            isCatchingUpFromChain.set(false)
        }
    }

    /** Whether a `gctl` row really came from [sender]: the history's sender list is only what the
     *  REST API reports. Remembered per transaction, so a pass doesn't check a row twice. Your own
     *  rows aren't asked about: those authenticate themselves (a recovery root re-derives its seed,
     *  the rest are signed). */
    private suspend fun chainControlProof(txId: String, sender: String, walletAddress: String): SenderProof {
        if (sender.equals(walletAddress, ignoreCase = true)) return SenderProof.PROVEN
        chainControlProofs[txId]?.let { return if (it) SenderProof.PROVEN else SenderProof.FORGED }
        val proof = networkService.chainHistory.provenSender(txId, sender, walletAddress)
        if (proof != SenderProof.UNKNOWN) {
            if (chainControlProofs.size >= 20_000) chainControlProofs.clear()
            chainControlProofs[txId] = proof == SenderProof.PROVEN
        }
        return proof
    }

    // -------------------------------------------------------------------------
    // Replay protection and retention (iOS 4f8e14bb)
    // -------------------------------------------------------------------------

    /**
     * Claims (group, sender, epoch, msg_id) for [txId] before its message, edit, reaction or status
     * is applied. The signature covers those four and not the transaction, so anyone can put the
     * same bytes in a new transaction, for a fresh block time and txId. False when the id already
     * belongs to another transaction: that's the replay. A local `pending_` copy is no claim, so a
     * send that went out after all still replaces its failed row.
     */
    private suspend fun claimMessageId(
        walletAddress: String, groupId: String, senderIdHex: String, epoch: Long, msgIdHex: String, txId: String, blockTime: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        val key = "$groupId|$senderIdHex|$epoch|$msgIdHex"
        try {
            val claimed = groupSeenMessages.claimedTxId(walletAddress, key)
            if (claimed != null) return@withContext claimed == txId
            // Messages stored before this table existed have no entry of their own.
            val stored = storedMessageTxId(walletAddress, groupId, senderIdHex, epoch, msgIdHex)
            if (stored != null && stored != txId) return@withContext false
            groupSeenMessages.claim(walletAddress, key, groupId, txId, blockTime)
        } catch (e: android.database.SQLException) {
            // A store that can't be read mustn't cost every incoming message.
            Log.w("GroupRepository", "Seen message id store failed: ${e.message}")
            true
        }
    }

    private fun storedMessageTxId(walletAddress: String, groupId: String, senderIdHex: String, epoch: Long, msgIdHex: String): String? {
        val query = SimpleSQLiteQuery(
            "SELECT txId FROM group_messages WHERE walletAddress = ? AND groupId = ? AND senderIdHex = ? AND epoch = ? AND msgIdHex = ? " +
                "AND txId NOT LIKE 'pending\\_%' ESCAPE '\\' LIMIT 1",
            arrayOf<Any?>(walletAddress, groupId, senderIdHex, epoch, msgIdHex),
        )
        return database.query(query).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }

    private val retentionAppliedWallets: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Deletes this wallet's group messages, edits and reactions older than the Message retention
     * setting, once per launch, the same cutoff the 1:1 side trims to. The groups themselves and
     * the seen message ids stay (see [claimMessageId]).
     */
    private suspend fun applyGroupRetentionOnce(walletAddress: String) {
        if (!retentionAppliedWallets.add(walletAddress)) return
        val cutoff = settings.backupRetention.first().cutoffMillis(System.currentTimeMillis()) ?: return
        withContext(Dispatchers.IO) {
            database.runInTransaction(Runnable {
                val db = database.openHelper.writableDatabase
                val args = arrayOf<Any?>(walletAddress, cutoff)
                db.execSQL("DELETE FROM group_messages WHERE walletAddress = ? AND blockTimestamp < ?", args)
                db.execSQL("DELETE FROM reactions WHERE walletAddress = ? AND blockTimestamp < ? AND groupId IS NOT NULL", args)
                db.execSQL("DELETE FROM message_edits WHERE walletAddress = ? AND blockTimestamp < ? AND groupId IS NOT NULL", args)
            })
        }
    }

    // -------------------------------------------------------------------------
    // Former members (iOS 4f8e14bb)
    // -------------------------------------------------------------------------

    /**
     * Keeps who a root (or this admin) just took out of a group, so a device catching up late can
     * still accept what they said while they were in. A rejoiner's entry stays: the roster check
     * passes first, and removing them again replaces it.
     */
    private fun recordFormerMembers(
        walletAddress: String, groupId: String, oldMembers: List<GroupMember>, newMembers: List<GroupMember>, lastEpoch: Long, removedAt: Long,
    ) {
        val current = newMembers.map { it.address }.toSet()
        val removed = oldMembers.filter { it.address !in current }
        if (removed.isEmpty()) return
        val nowMs = System.currentTimeMillis()
        val removedAddresses = removed.map { it.address }.toSet()
        groupSecretStore.updateFormerMembers(walletAddress, groupId) { former ->
            (former.filterNot { it.address in removedAddresses } + removed.map {
                FormerGroupMember(it.address, it.xOnlyPubKeyHex, lastEpoch, if (removedAt > 0) removedAt else nowMs, nowMs)
            }).takeLast(MAX_FORMER_MEMBERS_PER_GROUP)
        }
    }

    /** Whether [address] was in this group for [epoch] when this block was made. A block time at or
     *  after the removal doesn't count: they keep the old epoch's key, and could still sign with it. */
    private fun wasMember(address: String, walletAddress: String, groupId: String, epoch: Long, blockTime: Long): Boolean {
        if (blockTime <= 0) return false
        val former = groupSecretStore.loadFormerMembers(walletAddress)[groupId]?.firstOrNull { it.address == address } ?: return false
        return epoch <= former.lastEpoch && blockTime < former.removedAt
    }

    private fun recentFormerMembers(walletAddress: String, groupId: String): List<FormerGroupMember> {
        val nowMs = System.currentTimeMillis()
        return groupSecretStore.loadFormerMembers(walletAddress)[groupId].orEmpty()
            .filter { nowMs < it.recordedAt + FORMER_MEMBER_LANE_MS }
    }

    /** The people whose lanes a catch-up reads: the roster, and anyone only lately gone from it. */
    private fun catchUpLanePubKeys(walletAddress: String, group: GroupEntity): List<String> =
        membersOf(group).map { it.xOnlyPubKeyHex } + recentFormerMembers(walletAddress, group.groupId).map { it.xOnlyPubKeyHex }

    /** A deleted group's former members and seen message ids go with it: a re-join must be able
     *  to store its messages again. */
    private suspend fun forgetGroupHistoryRecords(walletAddress: String, groupId: String) {
        groupSecretStore.updateFormerMembers(walletAddress, groupId) { emptyList() }
        groupSecretStore.setSignedRootOutbox(walletAddress, groupId, null)
        withContext(Dispatchers.IO) { groupSeenMessages.forgetGroup(walletAddress, groupId) }
    }

    // -------------------------------------------------------------------------
    // Signed rosters for older groups (iOS 4f8e14bb)
    // -------------------------------------------------------------------------

    private val isResendingSignedRoots = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Rosters on groups made before signed rosters are only as good as the delivery that brought
     * them. Once per such group, the admin sends every member a root with a signed roster
     * ([completeJoin] then holds them to it). A send that fails is tried again by the next
     * catch-up, for that member only: the outbox is written before the first send, because the send
     * itself sets `rosterVersion`, which is what marks a group as already done.
     */
    private suspend fun resendSignedRootsToLegacyGroups(walletAddress: String) {
        if (!isResendingSignedRoots.compareAndSet(false, true)) return
        try {
            val privateKey = try { walletManager.getPrivateKeyBytes() } catch (e: Exception) { return }
            val groups = database.groupDao().getGroupsOnce(walletAddress)
            for (group in groups) {
                if (!group.isAdmin) continue
                val bag = groupSecretStore.loadBag(walletAddress, group.groupId) ?: continue
                if (bag.groupSeed == null || bag.rosterVersion != null ||
                    groupSecretStore.loadSignedRootOutbox(walletAddress)[group.groupId] != null
                ) continue
                val roster = membersOf(group)
                groupSecretStore.setSignedRootOutbox(walletAddress, group.groupId, roster.map { it.address }.filter { it != walletAddress })
                // The same account's other devices are held to it too.
                try {
                    sendSelfRootControlMessage(group, roster, bag, privateKey)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("GroupRepository", "Signed root to own devices failed for ${group.groupId.take(12)}", e)
                }
                if (walletManager.getActiveAccount()?.address != walletAddress) return
            }

            for ((groupId, owed) in groupSecretStore.loadSignedRootOutbox(walletAddress)) {
                val group = database.groupDao().getGroup(groupId, walletAddress)
                val bag = groupSecretStore.loadBag(walletAddress, groupId)
                if (group == null || !group.isAdmin || bag == null) {
                    groupSecretStore.setSignedRootOutbox(walletAddress, groupId, null)
                    continue
                }
                val roster = membersOf(group)
                // Someone removed since then isn't owed anything.
                val remaining = owed.filter { address -> roster.any { it.address == address } }.toMutableList()
                while (remaining.isNotEmpty()) {
                    try {
                        sendRootControlMessage(group, roster, bag, remaining.first(), privateKey)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("GroupRepository", "Signed root to a member failed for ${groupId.take(12)}", e)
                        break
                    }
                    if (walletManager.getActiveAccount()?.address != walletAddress) return
                    remaining.removeAt(0)
                    groupSecretStore.setSignedRootOutbox(walletAddress, groupId, remaining.toList())
                }
                if (remaining.isEmpty()) groupSecretStore.setSignedRootOutbox(walletAddress, groupId, null)
            }
        } finally {
            isResendingSignedRoots.set(false)
        }
    }

    private val runStartedAtMs = System.currentTimeMillis()
    private val pendingSendsSettled: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * A send still pending from before this run started stays on Sending for good, with nothing left
     * working on it. Once per account, after its first catch-up has brought in any copy that did get
     * delivered: that copy replaces it, and anything else is marked failed so Retry appears (iOS
     * `settlePendingSendsFromBeforeThisRun`).
     */
    private suspend fun settlePendingSendsFromBeforeThisRun(walletAddress: String) {
        if (!pendingSendsSettled.add(walletAddress)) return
        for (row in database.groupDao().getPendingSentBefore(walletAddress, runStartedAtMs)) {
            if (database.groupDao().hasDeliveredCopy(walletAddress, row.groupId, row.msgIdHex)) {
                database.groupDao().deleteMessage(row.txId, walletAddress)
            } else {
                database.groupDao().updateMessageStatus(row.txId, walletAddress, "failed")
                Log.i("GroupRepository", "Marked ${row.txId.take(24)} failed: the last run ended before it was sent")
            }
        }
    }

    /** "wallet|groupId" of the key restores tried this launch: see [prepareToRestoreMissingGroupKeys]. */
    private val groupKeyRestoreAttempts: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private val _groupKeysRestored = MutableStateFlow(0)

    /** Moves when a catch-up has brought a group's lost keys back: its members' ids can be watched
     *  for push again, and no group row changes to say so. */
    val groupKeysRestored: StateFlow<Int> = _groupKeysRestored.asStateFlow()

    /**
     * A listed group with no keys reads as an empty thread, and catch-up alone never brings them
     * back: its cursors are already past the root that carried them. So those streams are walked
     * again, and the root rewrites the keys through [completeJoin], still from the admin this
     * device has on record. Once per launch for a group, since one whose root can't be found would
     * otherwise walk everything on every catch-up (iOS 4314e17e). Returns the groups being restored.
     */
    private suspend fun prepareToRestoreMissingGroupKeys(walletAddress: String): List<GroupEntity> {
        val missing = database.groupDao().getGroupsOnce(walletAddress).filter { group ->
            groupSecretStore.loadBag(walletAddress, group.groupId) == null &&
                groupKeyRestoreAttempts.add("$walletAddress|${group.groupId}")
        }
        for (group in missing) {
            Log.i("GroupRepository", "No keys for ${group.groupId.take(12)} - walking its streams again to restore them")
            resetCatchUpCursors(walletAddress, group)
        }
        return missing
    }

    /**
     * Drops every cursor a group's repair depends on, so each stream is walked from the start - and
     * the CONTROL streams matter more than the message ones. Dropping only "gcomm" re-downloaded
     * pre-rotation ciphertext while the control cursors stayed put, so the epoch roots needed to
     * read it were never re-acquired: the refresh did real work and changed nothing you could see.
     */
    private suspend fun resetCatchUpCursors(walletAddress: String, group: GroupEntity) {
        val controlKeys = buildList {
            add("gctl-recipient|${walletAddress.lowercase()}")
            if (group.adminAddress.isNotBlank()) add("gctl|${group.adminAddress.lowercase()}")
        }
        database.groupDao().deleteGroupSyncCursorsWithPrefix(walletAddress, "gcomm|${group.groupId}|")
        for (key in controlKeys) database.groupDao().deleteGroupSyncCursorsWithPrefix(walletAddress, key)
        // Clear the one-shot deep-backfill marks too, or each walk stops at the first page
        // instead of going back through the whole stream.
        deepBackfilledGroupKeys.removeAll { it.startsWith("gcomm|${group.groupId}|") || it in controlKeys }
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
        val rejections = GroupRefreshRejections()
        refreshRejections = rejections

        resetCatchUpCursors(walletAddress, group)

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
        // Everyone ever removed too: this is the walk for finding what was missed (iOS 4f8e14bb).
        val lanePubKeys = members.map { it.xOnlyPubKeyHex } +
            groupSecretStore.loadFormerMembers(walletAddress)[groupId].orEmpty().map { it.xOnlyPubKeyHex }
        lanePubKeys.forEachIndexed { index, pubKeyHex ->
            onProgress(GroupRefreshPhase.Messages(index, lanePubKeys.size))
            val memberPub = runCatching { pubKeyHex.hexToByteArray() }.getOrNull()
            if (memberPub != null) {
                val blinded = GroupCipher.deriveBlindedGroupId(blindingKey, memberPub).toHexString()
                runCatching { syncGroupMessages(api, walletAddress, groupId, blinded) }
            }
        }
        onProgress(GroupRefreshPhase.Rebuilding)
        val recovered = database.groupDao().getMessagesOnce(groupId, walletAddress).size - messagesBefore
        refreshRejections = null
        Log.i(
            "GroupRepository",
            "Refresh of ${groupId.take(12)} finished: recovered $recovered, archived ${rejections.epochRootsArchived} epoch roots, " +
                "rejected ${rejections.noRootForEpoch} (no root) / ${rejections.senderNotInRoster} (sender not in roster) / ${rejections.decryptFailed} (decrypt failed)"
        )
        lastRefreshRejections = rejections
        return recovered
    }

    /**
     * Why the last repair could not keep a message. Live only while [forceRefreshGroup] runs, so
     * a refresh that recovers nothing can say WHICH wall it hit instead of just "0".
     */
    data class GroupRefreshRejections(
        var noRootForEpoch: Int = 0,
        var senderNotInRoster: Int = 0,
        var decryptFailed: Int = 0,
        var epochRootsArchived: Int = 0,
    ) {
        val isEmpty: Boolean get() = noRootForEpoch == 0 && senderNotInRoster == 0 && decryptFailed == 0
    }

    @Volatile
    private var refreshRejections: GroupRefreshRejections? = null

    /** The counts from the most recent [forceRefreshGroup], for its completion sheet. */
    @Volatile
    var lastRefreshRejections: GroupRefreshRejections? = null
        private set

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
            for (msg in messages) {
                val payloadString = reconstructPayloadString("kchat:1:gcomm:", msg.messagePayload) ?: continue
                val parsed = GroupCipher.parseGroupMessagePayload(payloadString) ?: continue
                ingestRow("gcomm", msg.txId) { handleIncomingGroupMessage(parsed, msg.txId, msg.blockTime) }
            }
            // Cursor AFTER the page is ingested, never before. Written first, an interruption
            // between the write and the ingest left the cursor sitting past messages that were
            // never stored, and ordinary sync only ever walks forward, so that page was gone for
            // good.
            advanceGroupSyncCursor(syncKey, walletAddress, cursor)
            // A short page is the last page. A FAILED fetch returns without marking, so a run
            // the indexer cut short retries from the beginning rather than leaving a hole behind.
            if (messages.size < 50) {
                deepBackfilledGroupKeys.add(syncKey)
                return
            }
        }
        // Page budget spent mid-stream. The walk from the start up to `cursor` was contiguous,
        // so resuming from it next run loses nothing - and marking the key is the only thing that
        // makes that resume happen. Without this, a stream longer than 2000 items restarted from
        // nothing every run and could never reach its own newest end.
        deepBackfilledGroupKeys.add(syncKey)
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
            for (msg in messages) {
                val payloadString = reconstructPayloadString("kchat:1:gctl:", msg.messagePayload) ?: continue
                ingestRow("gctl", msg.txId) { handleIncomingControlMessage(payloadString, msg.sender, msg.blockTime, msg.txId) }
            }
            // Cursor AFTER the page is ingested, never before. Written first, an interruption
            // between the write and the ingest left the cursor sitting past messages that were
            // never stored, and ordinary sync only ever walks forward, so that page was gone for
            // good.
            advanceGroupSyncCursor(syncKey, walletAddress, cursor)
            if (messages.size < 50) {
                deepBackfilledGroupKeys.add(syncKey)
                return
            }
        }
        // Page budget spent mid-stream. The walk from the start up to `cursor` was contiguous,
        // so resuming from it next run loses nothing - and marking the key is the only thing that
        // makes that resume happen. Without this, a stream longer than 2000 items restarted from
        // nothing every run and could never reach its own newest end.
        deepBackfilledGroupKeys.add(syncKey)
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
            for (msg in messages) {
                val payloadString = reconstructPayloadString("kchat:1:gctl:", msg.messagePayload) ?: continue
                ingestRow("gctl", msg.txId) { handleIncomingControlMessage(payloadString, msg.sender, msg.blockTime, msg.txId) }
            }
            // Cursor AFTER the page is ingested, never before. Written first, an interruption
            // between the write and the ingest left the cursor sitting past messages that were
            // never stored, and ordinary sync only ever walks forward, so that page was gone for
            // good.
            advanceGroupSyncCursor(syncKey, walletAddress, cursor)
            if (messages.size < 50) {
                deepBackfilledGroupKeys.add(syncKey)
                return
            }
        }
        // Page budget spent mid-stream. The walk from the start up to `cursor` was contiguous,
        // so resuming from it next run loses nothing - and marking the key is the only thing that
        // makes that resume happen. Without this, a stream longer than 2000 items restarted from
        // nothing every run and could never reach its own newest end.
        deepBackfilledGroupKeys.add(syncKey)
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

    /**
     * One fetched row's ingest, on its own: a row that throws is logged and skipped. Without this
     * a single bad message - a stranger's malformed root, say - threw out of the page before its
     * cursor was written, so every later catch-up re-read it, threw again, and never got past it.
     */
    private suspend fun ingestRow(kind: String, txId: String?, ingest: suspend () -> Unit) {
        try {
            ingest()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nullable on purpose: Gson fills a missing tx_id with null, and this must not throw.
            Log.w("GroupRepository", "Skipped $kind ${txId?.take(12)}: it failed to apply", e)
        }
    }

    private suspend fun advanceGroupSyncCursor(syncKey: String, walletAddress: String, cursor: String?) {
        if (cursor == null) return
        database.groupDao().setGroupSyncCursor(GroupSyncCursorEntity(syncKey = syncKey, walletAddress = walletAddress, cursor = cursor))
    }

    /**
     * Reverses the indexer's double-hex-encoding of `message_payload` (it hex-encodes the raw
     * on-chain sealed hex text as stored) back into the original `kchat:1:<type>:<hex>`
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
            if (groupSecretStore.isTombstoned(walletAddress, g.groupId, g.adminSigningPub)) continue
            val groupRootEpoch = g.groupRootEpoch ?: continue
            val blindingKey = g.blindingKey ?: continue
            // In one step against the bag as stored (see applyRootPayload), so a send racing the
            // restore keeps the counter it reserved.
            var newerHeld = false
            groupSecretStore.updateBag(walletAddress, g.groupId) { existingBag ->
                if (existingBag != null && existingBag.currentEpoch > g.currentEpoch) {
                    newerHeld = true
                    return@updateBag null
                }
                val deviceId = existingBag?.deviceId ?: GroupCipher.generateDeviceId().toHexString()
                val msgCounter = if (existingBag != null && existingBag.currentEpoch == g.currentEpoch) existingBag.msgCounter else 0L
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
                GroupBag(
                    groupId = g.groupId, groupSeed = g.groupSeed ?: existingBag?.groupSeed, groupRootEpoch = groupRootEpoch,
                    blindingKey = blindingKey, currentEpoch = g.currentEpoch, deviceId = deviceId, msgCounter = msgCounter,
                    selfInviteEpoch = existingBag?.selfInviteEpoch,
                    previousRoots = mergedPreviousRoots.ifEmpty { null },
                    rosterVersion = existingBag?.rosterVersion,
                )
            }
            if (newerHeld) continue
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
            completedGroupTxIds.clear()
        }
        database.groupDao().deleteAllGroups(walletAddress)
        database.groupDao().deleteAllMessages(walletAddress)
        database.groupDao().deleteGroupSyncCursorsForWallet(walletAddress)
        groupSecretStore.clearInviteData(walletAddress)
        withContext(Dispatchers.IO) { groupSeenMessages.forgetWallet(walletAddress) }
        chainCatchUpCursors.keys.removeAll { it.startsWith("$walletAddress|") }
        allGroupsCursors.remove(walletAddress)
        inviteStateChanged()
    }
}

/** A live group pass starts this far before its cursor, for indexer clock skew and reorgs (iOS). */
private const val LIVE_POLL_REWIND_MS = 10_000L

/** How long a group banner may wait on a name lookup. The push handler has seconds, and a roster
 *  name (or "not mentioned") is a fine answer. */
private const val NAME_LOOKUP_BUDGET_MS = 3_000L

/** Rows per `since` page; a full page is followed straight away. */
private const val LIVE_POLL_PAGE = 200

/** How far back the chain is read for groups while the indexer is down (iOS chainCatchUpLookbackMs). */
private const val CHAIN_CATCH_UP_LOOKBACK_MS = 3L * 24 * 60 * 60 * 1000

private val GCOMM_PREFIX_HEX = "kchat:1:gcomm:".toByteArray(Charsets.US_ASCII).toHexString()

private val GCTL_PREFIX_HEX = "kchat:1:gctl:".toByteArray(Charsets.US_ASCII).toHexString()

private val LEGACY_GCOMM_PREFIX_HEX = "ciph_msg:1:gcomm:".toByteArray(Charsets.US_ASCII).toHexString()

private val LEGACY_GCTL_PREFIX_HEX = "ciph_msg:1:gctl:".toByteArray(Charsets.US_ASCII).toHexString()

private const val MAX_FORMER_MEMBERS_PER_GROUP = 64

/** A removal's lane is read for this long after this device learned of it: long enough for the
 *  catch-up that found the removal to have read that lane past it (iOS formerMemberLaneMs). */
private const val FORMER_MEMBER_LANE_MS = 30L * 24 * 60 * 60 * 1000

private const val MAX_PENDING_GROUP_INVITES = 50

private const val MAX_PENDING_GROUP_INVITES_PER_ADMIN = 5
