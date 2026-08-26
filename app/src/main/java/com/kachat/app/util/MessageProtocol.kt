package com.kachat.app.util

import java.util.Base64

/**
 * KaChat/Kasia "ciph_msg" wire protocol — encode/decode of transaction payloads.
 *
 * Verified against the actual iOS KaChat implementation (`KaChatTransactionBuilder.swift`,
 * `ChatService+Decryption.swift`), NOT the stale MESSAGING.md doc: real tags are
 * `comm`/`handshake` (not `msg`/`hs`), colon-delimited (not pipe-delimited), and
 * encryption is ECDH+HKDF+ChaCha20-Poly1305 (see [KasiaCipher]), not AES-GCM.
 */
object MessageProtocol {

    // `kchat:` migration: write the new root; still READ the legacy `ciph_msg:` root so old
    // history and not-yet-migrated peers keep rendering (everything after the root is identical).
    const val PREFIX          = "kchat"        // write + read
    const val LEGACY_PREFIX   = "ciph_msg"     // read-only
    const val VERSION         = "1"
    const val TYPE_HANDSHAKE  = "handshake"
    const val TYPE_COMM       = "comm"
    const val TYPE_PAY        = "pay"
    const val TYPE_BCAST      = "bcast"

    const val MAX_BROADCAST_CHANNEL_NAME_LENGTH = 36

    private val HANDSHAKE_PREFIX_BYTES = "$PREFIX:$VERSION:$TYPE_HANDSHAKE:".toByteArray(Charsets.US_ASCII)
    private val LEGACY_HANDSHAKE_PREFIX_BYTES = "$LEGACY_PREFIX:$VERSION:$TYPE_HANDSHAKE:".toByteArray(Charsets.US_ASCII)

    /** Length of whichever handshake root [rawBytes] carries (new or legacy), or null. */
    private fun handshakePrefixLength(rawBytes: ByteArray): Int? {
        if (rawBytes.size > HANDSHAKE_PREFIX_BYTES.size &&
            rawBytes.copyOfRange(0, HANDSHAKE_PREFIX_BYTES.size).contentEquals(HANDSHAKE_PREFIX_BYTES)) return HANDSHAKE_PREFIX_BYTES.size
        if (rawBytes.size > LEGACY_HANDSHAKE_PREFIX_BYTES.size &&
            rawBytes.copyOfRange(0, LEGACY_HANDSHAKE_PREFIX_BYTES.size).contentEquals(LEGACY_HANDSHAKE_PREFIX_BYTES)) return LEGACY_HANDSHAKE_PREFIX_BYTES.size
        return null
    }

    /**
     * Builds "ciph_msg:1:comm:<alias>:<base64>" — alias is plaintext, colon-delimited
     * ahead of the base64-encoded [KasiaCipher.EncryptedMessage] bytes.
     */
    fun buildCommPayload(alias: String, encrypted: KasiaCipher.EncryptedMessage): ByteArray {
        val safeAlias = alias.replace(":", "_").take(32)
        val base64 = Base64.getEncoder().encodeToString(encrypted.toBytes())
        return "$PREFIX:$VERSION:$TYPE_COMM:$safeAlias:$base64".toByteArray(Charsets.UTF_8)
    }

    /**
     * Builds "ciph_msg:1:handshake:<raw bytes>" — the encrypted bytes are appended
     * directly (NOT base64-encoded) after the ASCII prefix, matching iOS exactly.
     * The result must be treated as opaque binary end-to-end (hex-encode it directly
     * for the transaction payload field — never round-trip it through a UTF-8 String).
     */
    fun buildHandshakePayload(encrypted: KasiaCipher.EncryptedMessage): ByteArray {
        return HANDSHAKE_PREFIX_BYTES + encrypted.toBytes()
    }

    fun isHandshakePayload(rawBytes: ByteArray): Boolean = handshakePrefixLength(rawBytes) != null

    /**
     * Returns true if [rawBytes] is a recognized KaChat payload of any type (dual-read of the
     * new `kchat:` root and the legacy `ciph_msg:` root).
     */
    fun isKaChatPayload(rawBytes: ByteArray): Boolean {
        if (isHandshakePayload(rawBytes)) return true
        val text = try { String(rawBytes, Charsets.UTF_8) } catch (e: Exception) { return false }
        return text.startsWith("$PREFIX:$VERSION:") || text.startsWith("$LEGACY_PREFIX:$VERSION:")
    }

    /**
     * Parses a "comm" payload, returning the plaintext alias and the still-encrypted message.
     */
    fun parseCommPayload(rawBytes: ByteArray): Pair<String, KasiaCipher.EncryptedMessage>? {
        val text = try { String(rawBytes, Charsets.UTF_8) } catch (e: Exception) { return null }
        // ["ciph_msg", "1", "comm", alias, base64] — Kotlin limit=5 matches Swift's maxSplits:4
        val parts = text.split(":", limit = 5)
        if (parts.size != 5 || (parts[0] != PREFIX && parts[0] != LEGACY_PREFIX) || parts[1] != VERSION || parts[2] != TYPE_COMM) return null

        val alias = parts[3]
        val encryptedBytes = try {
            Base64.getDecoder().decode(parts[4])
        } catch (e: Exception) {
            return null
        }
        val message = KasiaCipher.EncryptedMessage.fromBytes(encryptedBytes) ?: return null
        return alias to message
    }

    /**
     * Parses a "handshake" payload, returning the still-encrypted message (raw bytes, no base64).
     */
    fun parseHandshakePayload(rawBytes: ByteArray): KasiaCipher.EncryptedMessage? {
        val prefixLen = handshakePrefixLength(rawBytes) ?: return null
        val remainder = rawBytes.copyOfRange(prefixLen, rawBytes.size)
        return KasiaCipher.EncryptedMessage.fromBytes(remainder)
    }

    fun encrypt(plaintext: String, recipientXOnlyPubKey: ByteArray): KasiaCipher.EncryptedMessage =
        KasiaCipher.encrypt(plaintext, recipientXOnlyPubKey)

    fun decrypt(encrypted: KasiaCipher.EncryptedMessage, privateKey: ByteArray): String =
        KasiaCipher.decrypt(encrypted, privateKey)

    data class BroadcastMessage(val channel: String, val content: String)

    /**
     * Builds "ciph_msg:1:bcast:<channel>:<content>" — broadcasts are never encrypted (matches
     * Kasia: a broadcast is a public, one-to-many channel, so pairwise ECDH encryption doesn't
     * apply the same way it does to a 1:1 message). [channel] should already be normalized via
     * [normalizeChannelName]/[isValidChannelName] before calling this.
     */
    fun buildBcastPayload(channel: String, content: String): ByteArray =
        "$PREFIX:$VERSION:$TYPE_BCAST:$channel:$content".toByteArray(Charsets.UTF_8)

    /** Parses a "bcast" payload, returning the channel name and plaintext content. */
    fun parseBcastPayload(rawBytes: ByteArray): BroadcastMessage? {
        val text = try { String(rawBytes, Charsets.UTF_8) } catch (e: Exception) { return null }
        // ["ciph_msg", "1", "bcast", channel, content] — same shape as parseCommPayload.
        val parts = text.split(":", limit = 5)
        if (parts.size != 5 || (parts[0] != PREFIX && parts[0] != LEGACY_PREFIX) || parts[1] != VERSION || parts[2] != TYPE_BCAST) return null
        return BroadcastMessage(channel = parts[3], content = parts[4])
    }

    /** Lowercases and trims a user-entered channel name — the canonical form used for storage/comparison/on-chain payloads. */
    fun normalizeChannelName(name: String): String = name.trim().lowercase()

    /** Matches Kasia's channel-name rules: non-blank, no whitespace, no colons (the payload delimiter), within the length cap. */
    fun isValidChannelName(name: String): Boolean {
        if (name.isEmpty() || name.length > MAX_BROADCAST_CHANNEL_NAME_LENGTH) return false
        return name.none { it.isWhitespace() || it == ':' }
    }
}
