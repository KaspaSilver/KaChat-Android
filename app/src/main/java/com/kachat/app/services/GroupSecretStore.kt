package com.kachat.app.services

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
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
    val previousRoots: Map<Long, String>? = null
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

    fun saveBag(walletAddress: String, bag: GroupBag) {
        sharedPrefs.edit().putString(key(walletAddress, bag.groupId), gson.toJson(bag)).apply()
    }

    fun loadBag(walletAddress: String, groupId: String): GroupBag? {
        val json = sharedPrefs.getString(key(walletAddress, groupId), null) ?: return null
        return try { gson.fromJson(json, GroupBag::class.java) } catch (e: Exception) { null }
    }

    fun deleteBag(walletAddress: String, groupId: String) {
        sharedPrefs.edit().remove(key(walletAddress, groupId)).apply()
    }

    /** Every group id this wallet holds secrets for - used to restore in-memory scanning state on cold start/wallet switch. */
    fun allGroupIds(walletAddress: String): List<String> {
        val prefix = "bag_${walletAddress}_"
        return sharedPrefs.all.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
    }

    // --- deletion tombstones: which groups this wallet deleted, and which delete markers are
    // already on chain (so a delete survives a seedless re-import). ---
    data class TombstoneState(val deleted: List<String> = emptyList(), val published: List<String> = emptyList())
    private fun tombstoneKey(walletAddress: String) = "tombstones_$walletAddress"
    fun loadTombstones(walletAddress: String): TombstoneState {
        val json = sharedPrefs.getString(tombstoneKey(walletAddress), null) ?: return TombstoneState()
        return try { gson.fromJson(json, TombstoneState::class.java) } catch (e: Exception) { TombstoneState() }
    }
    private fun saveTombstones(walletAddress: String, state: TombstoneState) {
        sharedPrefs.edit().putString(tombstoneKey(walletAddress), gson.toJson(state)).apply()
    }
    fun isTombstoned(walletAddress: String, groupId: String) = loadTombstones(walletAddress).deleted.contains(groupId)
    fun recordTombstone(walletAddress: String, groupId: String, published: Boolean) {
        val s = loadTombstones(walletAddress)
        val deleted = if (groupId in s.deleted) s.deleted else s.deleted + groupId
        val pub = if (published && groupId !in s.published) s.published + groupId else s.published
        saveTombstones(walletAddress, TombstoneState(deleted, pub))
    }
    fun markTombstonePublished(walletAddress: String, groupId: String) {
        val s = loadTombstones(walletAddress)
        if (groupId !in s.published) saveTombstones(walletAddress, TombstoneState(s.deleted, s.published + groupId))
    }
}
