package com.kachat.app.services

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.kachat.app.repository.AppSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Simple Mode password management (Settings > Security > Simple Mode, and the onboarding
 * "Who will use KaChat?" step). Direct port of iOS's `ChildModeService`.
 *
 * Storage design:
 * - The password itself is NEVER stored. A random 16-byte salt plus
 *   PBKDF2-HMAC-SHA256(password, salt) over [PBKDF2_ITERATIONS] rounds is kept as a JSON record
 *   in its own [EncryptedSharedPreferences] file (the same Keystore-backed pattern
 *   [WalletManager]/[ColdStorageManager] use for their secrets).
 * - The ON/OFF flag lives in [AppSettingsRepository.childModeEnabled] (fast to observe from
 *   every gate: dock, deep links, notification paths) - but turning Simple Mode OFF is only ever
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

    /** The stored record: random salt + PBKDF2-HMAC-SHA256 over [iterations] rounds, hex-encoded
     *  via Gson — mirroring iOS's JSON-encoded Keychain payload. Records written before the work
     *  factor existed carry no [iterations] and were a single SHA-256; they verify the old way
     *  once and are rewritten. */
    private data class PasswordRecord(
        val saltHex: String,
        val hashHex: String,
        val iterations: Int? = null,
    )

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

    /** A password has been set at some point (wizard "Simple" choice, or Settings flow) —
     *  drives whether the Simple Mode screen shows "set a password" or "change password". */
    fun hasPassword(): Boolean = sharedPrefs.getString(PREF_RECORD, null) != null

    // MARK: - Attempt limiting

    /** Seconds left before another attempt is accepted, null when attempts are open. Five wrong
     *  answers earn 30 seconds; each one after doubles it, up to an hour. Matches iOS. */
    fun lockoutRemainingSeconds(): Int? {
        val until = sharedPrefs.getLong(PREF_LOCKED_UNTIL, 0L)
        val remaining = until - System.currentTimeMillis()
        return if (remaining > 0) ((remaining + 999) / 1000).toInt() else null
    }

    private fun recordFailedAttempt() {
        val attempts = sharedPrefs.getInt(PREF_FAILED_ATTEMPTS, 0) + 1
        val editor = sharedPrefs.edit().putInt(PREF_FAILED_ATTEMPTS, attempts)
        if (attempts >= FREE_ATTEMPTS) {
            val penaltySeconds = minOf(3600.0, 30.0 * Math.pow(2.0, (attempts - FREE_ATTEMPTS).toDouble()))
            editor.putLong(PREF_LOCKED_UNTIL, System.currentTimeMillis() + (penaltySeconds * 1000).toLong())
        }
        editor.apply()
    }

    private fun clearFailedAttempts() {
        sharedPrefs.edit().remove(PREF_FAILED_ATTEMPTS).remove(PREF_LOCKED_UNTIL).apply()
    }

    // MARK: - Password lifecycle

    /**
     * Hashes and stores [password] (free-form: 4 digits, 8 digits, or anything non-empty —
     * the UI enforces non-empty + confirmation, this just refuses the degenerate empty case).
     */
    @Throws(IllegalArgumentException::class)
    suspend fun setPassword(password: String) {
        require(password.isNotEmpty()) { "Simple Mode password cannot be empty" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val record = PasswordRecord(
            saltHex = salt.toHex(),
            hashHex = derive(password, salt, PBKDF2_ITERATIONS).toHex(),
            iterations = PBKDF2_ITERATIONS,
        )
        sharedPrefs.edit().putString(PREF_RECORD, gson.toJson(record)).apply()
        clearFailedAttempts()
    }

    /**
     * Constant-shape check of [password] against the stored record. False when no record
     * exists (nothing to verify against — callers gate on [hasPassword] first).
     */
    suspend fun verifyPassword(password: String): Boolean {
        if (lockoutRemainingSeconds() != null) return false
        val record = try {
            gson.fromJson(sharedPrefs.getString(PREF_RECORD, null) ?: return false, PasswordRecord::class.java)
        } catch (_: Exception) {
            return false
        } ?: return false
        val salt = record.saltHex.hexToBytes() ?: return false
        val expected = record.hashHex.hexToBytes() ?: return false
        val candidate = record.iterations?.let { derive(password, salt, it) } ?: legacyHash(password, salt)
        // Constant-time comparison — not strictly required for a parental-control PIN, but free.
        if (candidate.size != expected.size) {
            recordFailedAttempt()
            return false
        }
        var difference = 0
        for (i in candidate.indices) difference = difference or (candidate[i].toInt() xor expected[i].toInt())
        if (difference != 0) {
            recordFailedAttempt()
            return false
        }
        clearFailedAttempts()
        if (record.iterations == null) {
            // A record from before the work factor: the password is known good right now, so
            // rewrite it with one.
            runCatching { setPassword(password) }
        }
        return true
    }

    /**
     * Traditional change flow: current password must verify, then the new one replaces the
     * record (fresh salt). Returns false (and changes nothing) on a wrong current password.
     */
    suspend fun changePassword(current: String, newPassword: String): Boolean {
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

    /**
     * PBKDF2-HMAC-SHA256, 32 bytes out. About 60 ms on a recent iPhone and rather more on a slow
     * Android phone, so unlike iOS's synchronous call this runs off the main thread: a password
     * screen that freezes for a third of a second on every tap is not what the same feature feels
     * like here. Every caller is already a coroutine.
     */
    private suspend fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray =
        withContext(Dispatchers.Default) {
            val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
            try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }

    /** The pre-work-factor record: a single SHA-256(salt || password). */
    private fun legacyHash(password: String, salt: ByteArray): ByteArray =
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
        private const val PREF_FAILED_ATTEMPTS = "simple_mode_failed_attempts"
        private const val PREF_LOCKED_UNTIL = "simple_mode_locked_until"

        /** Five wrong answers before the wait starts. */
        private const val FREE_ATTEMPTS = 5

        /** Nothing at the lock, an eternity for a brute force of an extracted record. */
        private const val PBKDF2_ITERATIONS = 120_000
    }
}
