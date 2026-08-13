package com.kachat.app.services

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.kachat.app.repository.AppSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Child Mode password management (Settings > Security > Child Mode, and the onboarding
 * "Who will use KaChat?" step). Direct port of iOS's `ChildModeService`.
 *
 * Storage design:
 * - The password itself is NEVER stored. A random 16-byte salt plus SHA-256(salt || password)
 *   is kept as a JSON record in its own [EncryptedSharedPreferences] file (the same
 *   Keystore-backed pattern [WalletManager]/[ColdStorageManager] use for their secrets).
 * - The ON/OFF flag lives in [AppSettingsRepository.childModeEnabled] (fast to observe from
 *   every gate: dock, deep links, notification paths) - but turning Child Mode OFF is only ever
 *   done after [verifyPassword] succeeds against this record, so editing DataStore alone isn't
 *   enough to silently re-enable the hidden features from the UI flows. An account wipe / settings
 *   reset never touches this prefs file, so the flag must never be silently dropped while the
 *   record survives (the flag is global, not per-account, matching iOS's device-level setting).
 * - Deliberately NO biometrics anywhere in this feature: the whole point is that the device
 *   owner (the child) can pass fingerprint/face unlock but must not know the parent's password.
 */
@Singleton
class ChildModeService @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: AppSettingsRepository,
) {

    /** The stored record: random salt + SHA-256(salt || UTF-8 password), hex-encoded via Gson —
     *  mirroring iOS's JSON-encoded Keychain payload. */
    private data class PasswordRecord(val saltHex: String, val hashHex: String)

    private val gson = Gson()

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPrefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // MARK: - Queries

    /** A password has been set at some point (wizard "Child" choice, or Settings flow) —
     *  drives whether the Child Mode screen shows "set a password" or "change password". */
    fun hasPassword(): Boolean = sharedPrefs.getString(PREF_RECORD, null) != null

    // MARK: - Password lifecycle

    /**
     * Hashes and stores [password] (free-form: 4 digits, 8 digits, or anything non-empty —
     * the UI enforces non-empty + confirmation, this just refuses the degenerate empty case).
     */
    @Throws(IllegalArgumentException::class)
    fun setPassword(password: String) {
        require(password.isNotEmpty()) { "Child Mode password cannot be empty" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val record = PasswordRecord(
            saltHex = salt.toHex(),
            hashHex = hash(password, salt).toHex(),
        )
        sharedPrefs.edit().putString(PREF_RECORD, gson.toJson(record)).apply()
    }

    /**
     * Constant-shape check of [password] against the stored record. False when no record
     * exists (nothing to verify against — callers gate on [hasPassword] first).
     */
    fun verifyPassword(password: String): Boolean {
        val record = try {
            gson.fromJson(sharedPrefs.getString(PREF_RECORD, null) ?: return false, PasswordRecord::class.java)
        } catch (_: Exception) {
            return false
        } ?: return false
        val salt = record.saltHex.hexToBytes() ?: return false
        val expected = record.hashHex.hexToBytes() ?: return false
        val candidate = hash(password, salt)
        // Constant-time comparison — not strictly required for a parental-control PIN, but free.
        if (candidate.size != expected.size) return false
        var difference = 0
        for (i in candidate.indices) difference = difference or (candidate[i].toInt() xor expected[i].toInt())
        return difference == 0
    }

    /**
     * Traditional change flow: current password must verify, then the new one replaces the
     * record (fresh salt). Returns false (and changes nothing) on a wrong current password.
     */
    fun changePassword(current: String, newPassword: String): Boolean {
        if (!verifyPassword(current)) return false
        setPassword(newPassword)
        return true
    }

    /**
     * Full reset to the never-configured state: the current password must verify, then the
     * stored record is deleted AND the `childModeEnabled` flag is switched off through the
     * standard DataStore write (so the dock gating and push re-registration react exactly as
     * they do for the normal OFF toggle). Returns false (and changes nothing) on a wrong
     * password.
     */
    suspend fun clearConfiguration(currentPassword: String): Boolean {
        if (!verifyPassword(currentPassword)) return false
        sharedPrefs.edit().remove(PREF_RECORD).apply()
        settings.setChildModeEnabled(false)
        return true
    }

    private fun hash(password: String, salt: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(salt + password.toByteArray(Charsets.UTF_8))

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray? {
        if (length % 2 != 0) return null
        return try {
            ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (_: NumberFormatException) {
            null
        }
    }

    companion object {
        private const val PREFS_NAME = "kachat_child_mode_prefs"
        private const val PREF_RECORD = "child_mode_password_record"
    }
}
