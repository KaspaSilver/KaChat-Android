package com.kachat.app.services

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kachat.app.models.PendingGroupInvite
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Group secret bag - groupSeed (admin-only, null for non-admin members)/groupRootEpoch/
 * blindingKey/deviceId/msgCounter, keyed by (walletAddress, groupId). Mirrors the reference
 * implementation's "GroupBag" schema exactly, and iOS KaChat's Keychain-held GroupBag.
 */
data class GroupBag(
    val groupId: String,
    val groupSeed: String?,       // hex, admin-only
    val groupRootEpoch: String,   // hex, current epoch's root key
    val blindingKey: String,      // hex
    val currentEpoch: Long,
    val deviceId: String,         // hex, 16 bytes
    val msgCounter: Long,         // monotonic per (group_id, epoch, device_id)
    // Epoch for which this admin has published its self-addressed recovery invite (null = none).
    val selfInviteEpoch: Long? = null,
    /**
     * Roots for epochs this group has already left, keyed by epoch.
     *
     * Without these a NON-ADMIN member loses the whole thread the moment membership changes.
     * [groupRootEpoch] only ever held the current epoch, and the fallback that re-derives an older
     * root needs [groupSeed], which only the admin has - so on every other device an epoch
     * rotation made every earlier message undecryptable and the thread rendered as empty. The
     * ciphertext was never lost; the key to read it was being thrown away.
     *
     * NULLABLE on purpose. These bags are stored with Gson, which allocates Kotlin data classes
     * through Unsafe rather than their constructor, so a default value never runs for a field
     * missing from stored JSON - every bag written before this build would hand back a null map
     * under a non-null type, and the first read would throw. Declaring it nullable makes that
     * absence the ordinary case it actually is. (iOS hit the same class of bug from the other
     * direction: a non-optional Codable field with a default still throws `keyNotFound`.)
     */
    val previousRoots: Map<Long, String>? = null,
    /**
     * The newest signed roster this device has applied for the group. Once set, a root with no
     * signed roster or an older one can't change the name or member list
     * ([com.kachat.app.repository.GroupRepository.completeJoin]). An admin also uses it so its next
     * version is always higher. Nullable for the same reason as [previousRoots].
     */
    val rosterVersion: Long? = null,
)

/**
 * Someone taken out of a group, for telling what they sent while they were in from what they send
 * after ([com.kachat.app.repository.GroupRepository.wasMember], iOS 4f8e14bb).
 */
data class FormerGroupMember(
    val address: String,
    val xOnlyPubKeyHex: String,
    /** The last epoch they held the key for. */
    val lastEpoch: Long,
    /** Block time (ms) of the root that took them out. */
    val removedAt: Long,
    /** When this device learned of it (ms): only how long their lane is still read. */
    val recordedAt: Long,
)

/**
 * Keystore-backed encrypted storage for group secrets - mirrors [WalletManager]'s own
 * EncryptedSharedPreferences setup (AES256-GCM master key, AES256-SIV/GCM pref encryption), but
 * deliberately its own store/file rather than folded into WalletManager's: group secrets are a
 * distinct concern from wallet/account secrets, and keeping them separate means a group can be
 * fully wiped (leave/delete) without touching anything wallet-related.
 */
@Singleton
class GroupSecretStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPrefs = EncryptedSharedPreferences.create(
        context,
        "kachat_group_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private fun key(walletAddress: String, groupId: String) = "bag_${walletAddress}_$groupId"

    /** Decoded bags, so the group poll (one read per group per tick, plus one per incoming
     *  message) stops decrypting and parsing the same prefs entry every time (iOS 1ae9440).
     *  saveBag, reserveNextCounter and deleteBag are the only ways a bag changes, and each keeps
     *  this in step.
     *  Synchronized so a read racing a save can never put an older bag (an older msgCounter)
     *  back over the newer one. */
    private val bagCache = java.util.concurrent.ConcurrentHashMap<String, GroupBag>()

    @Synchronized
    fun saveBag(walletAddress: String, bag: GroupBag) {
        val key = key(walletAddress, bag.groupId)
        sharedPrefs.edit().putString(key, gson.toJson(bag)).apply()
        bagCache[key] = bag
    }

    @Synchronized
    fun loadBag(walletAddress: String, groupId: String): GroupBag? {
        val key = key(walletAddress, groupId)
        bagCache[key]?.let { return it }
        val json = sharedPrefs.getString(key, null) ?: return null
        val bag = try { gson.fromJson(json, GroupBag::class.java) } catch (e: Exception) { null } ?: return null
        bagCache[key] = bag
        return bag
    }

    /**
     * Read-change-write of one bag as a single step, against the bag as stored right now - never a
     * copy a caller loaded before suspending, which could put back an older msgCounter or undo an
     * epoch rotation. [change] gets null when there is no bag; returning null leaves the store as it
     * is. Returns the bag stored afterwards. [change] must not suspend or block: it runs locked.
     */
    @Synchronized
    fun updateBag(walletAddress: String, groupId: String, change: (GroupBag?) -> GroupBag?): GroupBag? {
        val current = loadBag(walletAddress, groupId)
        val next = change(current) ?: return current
        saveBag(walletAddress, next)
        return next
    }

    /**
     * Takes the next message counter for this device in the group, atomically: re-reads the bag,
     * bumps it and writes it through before returning, so two sends at once can never share a
     * msg_id - which would mean the same ChaCha20 key and nonce for two plaintexts. Returns the
     * bag as stored (its epoch and root are the ones to encrypt under) and the counter, or null
     * when there is no bag.
     */
    @Synchronized
    fun reserveNextCounter(walletAddress: String, groupId: String): Pair<GroupBag, Long>? {
        val current = loadBag(walletAddress, groupId) ?: return null
        val counter = current.msgCounter + 1
        val next = current.copy(msgCounter = counter)
        val key = key(walletAddress, groupId)
        // commit, not apply: the counter must be on disk before anything is sent under it, or a
        // process killed right after a broadcast would hand the same counter out again.
        sharedPrefs.edit().putString(key, gson.toJson(next)).commit()
        bagCache[key] = next
        return next to counter
    }

    @Synchronized
    fun deleteBag(walletAddress: String, groupId: String) {
        val key = key(walletAddress, groupId)
        sharedPrefs.edit().remove(key).apply()
        bagCache.remove(key)
    }

    /** Every group id this wallet holds secrets for - used to restore in-memory scanning state on cold start/wallet switch. */
    fun allGroupIds(walletAddress: String): List<String> {
        val prefix = "bag_${walletAddress}_"
        return sharedPrefs.all.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
    }

    // --- deletion tombstones: which groups this wallet deleted, and which delete markers are
    // already on chain (so a delete survives a seedless re-import). Entries are
    // PendingGroupInvite.key (that admin's offer of the id), or a bare group id for that id from
    // any admin, which is what a v1 marker means. ---
    data class TombstoneState(val deleted: List<String> = emptyList(), val published: List<String> = emptyList())
    private fun tombstoneKey(walletAddress: String) = "tombstones_$walletAddress"
    fun loadTombstones(walletAddress: String): TombstoneState {
        val json = sharedPrefs.getString(tombstoneKey(walletAddress), null) ?: return TombstoneState()
        return try { gson.fromJson(json, TombstoneState::class.java) } catch (e: Exception) { TombstoneState() }
    }
    private fun saveTombstones(walletAddress: String, state: TombstoneState) {
        sharedPrefs.edit().putString(tombstoneKey(walletAddress), gson.toJson(state)).apply()
    }
    /** With no admin to go on (an older backup), any marker for this id counts. */
    fun isTombstoned(walletAddress: String, groupId: String, adminSigningPub: String?): Boolean {
        val deleted = loadTombstones(walletAddress).deleted
        if (groupId in deleted) return true
        if (adminSigningPub == null) return deleted.any { it.startsWith("$groupId:") }
        return PendingGroupInvite.key(groupId, adminSigningPub) in deleted
    }
    fun recordTombstone(walletAddress: String, entry: String, published: Boolean) {
        val s = loadTombstones(walletAddress)
        val deleted = if (entry in s.deleted) s.deleted else s.deleted + entry
        val pub = if (published && entry !in s.published) s.published + entry else s.published
        saveTombstones(walletAddress, TombstoneState(deleted, pub))
    }
    fun markTombstonePublished(walletAddress: String, entry: String) {
        val s = loadTombstones(walletAddress)
        if (entry !in s.published) saveTombstones(walletAddress, TombstoneState(s.deleted, s.published + entry))
    }

    // --- group invites (iOS bfe9f022): groups this wallet was added to and hasn't answered. They
    // hold the group's keys, which is why they live here (iOS: the Keychain). ---
    private fun invitesKey(walletAddress: String) = "group_invites_$walletAddress"
    private val invitesType = object : TypeToken<List<PendingGroupInvite>>() {}.type

    @Synchronized
    fun loadInvites(walletAddress: String): List<PendingGroupInvite> {
        val json = sharedPrefs.getString(invitesKey(walletAddress), null) ?: return emptyList()
        val decoded = try { gson.fromJson<List<PendingGroupInvite>>(json, invitesType) } catch (e: Exception) { null }
        return decoded.orEmpty().filter { it.roots.isNotEmpty() }
    }

    /** Read-change-write as one step: the live scan, catch-up and a push all reach this at once. */
    @Synchronized
    fun updateInvites(walletAddress: String, change: (List<PendingGroupInvite>) -> List<PendingGroupInvite>) {
        val next = change(loadInvites(walletAddress))
        if (next.isEmpty()) {
            sharedPrefs.edit().remove(invitesKey(walletAddress)).apply()
        } else {
            sharedPrefs.edit().putString(invitesKey(walletAddress), gson.toJson(next)).apply()
        }
    }

    /** What answers this wallet has given, and which are on chain yet. [accepted] and [published]
     *  hold PendingGroupInvite.key; [statusOutbox] holds group ids. */
    data class InviteAnswers(
        val accepted: List<String> = emptyList(),
        val published: List<String> = emptyList(),
        val statusOutbox: List<String> = emptyList(),
    )
    private fun inviteAnswersKey(walletAddress: String) = "group_invite_answers_$walletAddress"

    @Synchronized
    fun loadInviteAnswers(walletAddress: String): InviteAnswers {
        val json = sharedPrefs.getString(inviteAnswersKey(walletAddress), null) ?: return InviteAnswers()
        return try { gson.fromJson(json, InviteAnswers::class.java) ?: InviteAnswers() } catch (e: Exception) { InviteAnswers() }
    }

    @Synchronized
    fun updateInviteAnswers(walletAddress: String, change: (InviteAnswers) -> InviteAnswers) {
        val next = change(loadInviteAnswers(walletAddress))
        sharedPrefs.edit().putString(inviteAnswersKey(walletAddress), gson.toJson(next)).apply()
    }

    // Group id -> member address -> "invited", "joined" or "declined" (GroupMemberStatus). A member
    // with no entry has no invite on record: groups from before invites were answered. Read for
    // every incoming group message, so it is kept decoded, as the bags are.
    private fun memberStatusesKey(walletAddress: String) = "group_member_statuses_$walletAddress"
    private val memberStatusesType = object : TypeToken<Map<String, Map<String, String>>>() {}.type
    private val memberStatusesCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, Map<String, String>>>()

    @Synchronized
    fun loadMemberStatuses(walletAddress: String): Map<String, Map<String, String>> {
        memberStatusesCache[walletAddress]?.let { return it }
        val json = sharedPrefs.getString(memberStatusesKey(walletAddress), null)
        val decoded: Map<String, Map<String, String>> = if (json == null) {
            emptyMap()
        } else {
            try { gson.fromJson<Map<String, Map<String, String>>>(json, memberStatusesType) ?: emptyMap() } catch (e: Exception) { emptyMap() }
        }
        memberStatusesCache[walletAddress] = decoded
        return decoded
    }

    /** Null removes the member's entry. False when that is what was already stored. */
    @Synchronized
    fun setMemberStatus(walletAddress: String, groupId: String, address: String, status: String?): Boolean {
        val all = loadMemberStatuses(walletAddress)
        val group = all[groupId].orEmpty()
        if (group[address] == status) return false
        val updatedGroup = if (status == null) group - address else group + (address to status)
        val updated = all + (groupId to updatedGroup)
        sharedPrefs.edit().putString(memberStatusesKey(walletAddress), gson.toJson(updated)).apply()
        memberStatusesCache[walletAddress] = updated
        return true
    }

    // Group id -> people the admin took out of it (iOS groupFormerMembers).
    private fun formerMembersKey(walletAddress: String) = "group_former_members_$walletAddress"
    private val formerMembersType = object : TypeToken<Map<String, List<FormerGroupMember>>>() {}.type

    @Synchronized
    fun loadFormerMembers(walletAddress: String): Map<String, List<FormerGroupMember>> {
        val json = sharedPrefs.getString(formerMembersKey(walletAddress), null) ?: return emptyMap()
        return try { gson.fromJson<Map<String, List<FormerGroupMember>>>(json, formerMembersType) ?: emptyMap() } catch (e: Exception) { emptyMap() }
    }

    /** Read-change-write as one step; a group left with nobody is dropped. */
    @Synchronized
    fun updateFormerMembers(walletAddress: String, groupId: String, change: (List<FormerGroupMember>) -> List<FormerGroupMember>) {
        val all = loadFormerMembers(walletAddress)
        val updatedGroup = change(all[groupId].orEmpty())
        val updated = if (updatedGroup.isEmpty()) all - groupId else all + (groupId to updatedGroup)
        sharedPrefs.edit().putString(formerMembersKey(walletAddress), gson.toJson(updated)).apply()
    }

    // Admin only. Group id -> members still owed a signed root (iOS groupSignedRootOutbox).
    private fun signedRootOutboxKey(walletAddress: String) = "group_signed_root_outbox_$walletAddress"
    private val signedRootOutboxType = object : TypeToken<Map<String, List<String>>>() {}.type

    @Synchronized
    fun loadSignedRootOutbox(walletAddress: String): Map<String, List<String>> {
        val json = sharedPrefs.getString(signedRootOutboxKey(walletAddress), null) ?: return emptyMap()
        return try { gson.fromJson<Map<String, List<String>>>(json, signedRootOutboxType) ?: emptyMap() } catch (e: Exception) { emptyMap() }
    }

    /** Null [owed] removes the group's entry. */
    @Synchronized
    fun setSignedRootOutbox(walletAddress: String, groupId: String, owed: List<String>?) {
        val all = loadSignedRootOutbox(walletAddress)
        val updated = if (owed == null) all - groupId else all + (groupId to owed)
        sharedPrefs.edit().putString(signedRootOutboxKey(walletAddress), gson.toJson(updated)).apply()
    }

    /** Everything above, for wiping an account's group data. */
    @Synchronized
    fun clearInviteData(walletAddress: String) {
        sharedPrefs.edit()
            .remove(invitesKey(walletAddress))
            .remove(inviteAnswersKey(walletAddress))
            .remove(memberStatusesKey(walletAddress))
            .remove(formerMembersKey(walletAddress))
            .remove(signedRootOutboxKey(walletAddress))
            .apply()
        memberStatusesCache.remove(walletAddress)
    }
}
