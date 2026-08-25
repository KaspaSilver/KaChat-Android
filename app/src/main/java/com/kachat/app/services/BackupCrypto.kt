package com.kachat.app.services

import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The cross-platform "Encrypted Backup Envelope (v1)" codec (see MESSAGING.md in the iOS repo) —
 * the ONE place every backup transport encrypts and decrypts the chat archive. Nextcloud's shared
 * `kachat-backup.json`, the per-account Google Drive files, and the local file-picker export all
 * carry this envelope as the file's entire content:
 *
 * ```json
 * {
 *   "kachatEncryptedBackup": 1,
 *   "cipher": "aes-256-gcm",
 *   "nonce": "<base64, 12 random bytes, fresh per write>",
 *   "ciphertext": "<base64, AES-256-GCM ciphertext with the 16-byte tag appended>",
 *   "walletHint": "<first 8 bytes of SHA-256(walletAddress), hex>"
 * }
 * ```
 *
 * The key is derived identically on every platform:
 * `SHA-256(identity_private_key_bytes || UTF8("kachat-backup-v1"))`, so any device holding the
 * seed can read the archive and nothing else can. The plaintext is the unchanged
 * [com.kachat.app.models.ChatHistoryArchive] JSON. Readers detect the envelope via [isEnvelope]
 * and fall back to parsing legacy plaintext archives as-is — old files stay restorable
 * indefinitely; writers ALWAYS encrypt. [envelopeWalletHint] lets a reader skip a foreign
 * wallet's file without decrypting anything.
 */
object BackupCrypto {

    /** The exact user-facing error for a ciphertext this account's key cannot open. */
    const val DECRYPT_FAILED_MESSAGE =
        "Could not decrypt the backup. It may belong to a different account."

    private const val MARKER = "kachatEncryptedBackup"
    private const val CIPHER_NAME = "aes-256-gcm"
    private const val KEY_CONTEXT = "kachat-backup-v1"
    private const val NONCE_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128

    /**
     * `key = SHA-256(identity private key raw 32 bytes || UTF8("kachat-backup-v1"))` — the
     * private key is the chatting/identity address key ([WalletManager.getPrivateKeyBytes], the
     * same accessor every ECIES/signing consumer funnels through).
     */
    fun deriveKey(identityPrivateKeyBytes: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(identityPrivateKeyBytes)
        digest.update(KEY_CONTEXT.toByteArray(Charsets.UTF_8))
        return digest.digest()
    }

    /** First 8 bytes of SHA-256(walletAddress) as lowercase hex — byte-identical to
     *  [NextcloudService.walletHashSuffix] and iOS's `KeychainService.walletHashSuffix`. */
    fun walletHint(walletAddress: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(walletAddress.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }

    /**
     * True when [content] is a v1 envelope (`kachatEncryptedBackup == 1`). The cheap `contains`
     * pre-filter keeps the common legacy-plaintext path free of a second full JSON parse (a
     * legacy archive can be megabytes).
     */
    fun isEnvelope(content: String): Boolean {
        if (!content.contains("\"$MARKER\"")) return false
        return runCatching { JSONObject(content).optInt(MARKER, 0) == 1 }.getOrDefault(false)
    }

    /** The envelope's `walletHint`, or null when absent or unreadable — lets a reader skip a
     *  foreign wallet's file without paying for a decrypt. */
    fun envelopeWalletHint(content: String): String? = runCatching {
        JSONObject(content).optString("walletHint").trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Encrypts the archive JSON into a fresh v1 envelope (new random nonce every call). */
    fun encrypt(plaintextJson: String, key: ByteArray, walletAddress: String): String {
        val nonce = ByteArray(NONCE_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH_BITS, nonce))
        // Java's GCM output is ciphertext || 16-byte tag — exactly the envelope's wire form.
        val ciphertext = cipher.doFinal(plaintextJson.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put(MARKER, 1)
            .put("cipher", CIPHER_NAME)
            .put("nonce", Base64.encodeToString(nonce, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .put("walletHint", walletHint(walletAddress))
            .toString()
    }

    /**
     * Decrypts a v1 envelope back to the archive JSON. ANY failure — wrong key (different seed),
     * corrupt ciphertext, malformed envelope — throws `IllegalStateException(DECRYPT_FAILED_MESSAGE)`;
     * callers on an upload path must let that propagate BEFORE anything uploads, so a file that
     * cannot be read is never overwritten.
     */
    fun decrypt(envelopeJson: String, key: ByteArray): String {
        try {
            val envelope = JSONObject(envelopeJson)
            check(envelope.optInt(MARKER, 0) == 1)
            check(envelope.optString("cipher").equals(CIPHER_NAME, ignoreCase = true))
            val nonce = Base64.decode(envelope.getString("nonce"), Base64.DEFAULT)
            val ciphertext = Base64.decode(envelope.getString("ciphertext"), Base64.DEFAULT)
            check(nonce.size == NONCE_LENGTH)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH_BITS, nonce))
            return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            throw IllegalStateException(DECRYPT_FAILED_MESSAGE)
        }
    }
}
