package com.kachat.app.util

import java.util.Base64

/**
 * KaChat wire protocol — encode/decode of transaction payloads. The root written is
 * `kchat:1:`; the legacy `ciph_msg:1:` root is still read (see PREFIX/LEGACY_PREFIX below).
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
    /** A first-contact message: `kchat:1:dm:<inbox tag>:<alias>:<sealed>` - a `comm` message plus
     *  the recipient's [InboxTag], so they can find it without knowing the sender
     *  (NO_HANDSHAKE_MESSAGING.md). Only ever written, never stored as its own type. */
    const val TYPE_DM         = "dm"
    const val DM_PREFIX       = "$PREFIX:$VERSION:$TYPE_DM:"
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
     * Builds "kchat:1:comm:<alias>:<base64>" — alias is plaintext, colon-delimited
     * ahead of the base64-encoded [KasiaCipher.EncryptedMessage] bytes.
     */
    fun buildCommPayload(alias: String, encrypted: KasiaCipher.EncryptedMessage, inboxTag: String? = null): ByteArray {
        val safeAlias = alias.replace(":", "_").take(32)
        val base64 = Base64.getEncoder().encodeToString(encrypted.toBytes())
        // With [inboxTag] it is the first-contact form, `kchat:1:dm:<tag>:<alias>:<sealed>` - the
        // same message, also filed by the recipient's inbox tag (NO_HANDSHAKE_MESSAGING.md).
        val root = if (inboxTag != null) "$DM_PREFIX$inboxTag:" else "$PREFIX:$VERSION:$TYPE_COMM:"
        return "$root$safeAlias:$base64".toByteArray(Charsets.UTF_8)
    }

    /**
     * Reading first-contact messages: `kchat:1:dm:<tag>:<alias>:<sealed>` is a contextual message
     * plus the recipient's inbox tag, so every parser reads it as `kchat:1:comm:<alias>:<sealed>`.
     * Mirrors iOS `ContextualPayloadFormat.normalized`.
     */
    fun normalizeFirstContact(payload: String): String {
        if (!payload.startsWith(DM_PREFIX)) return payload
        val rest = payload.substring(DM_PREFIX.length)
        val colon = rest.indexOf(':')
        if (colon < 0) return payload
        return "$PREFIX:$VERSION:$TYPE_COMM:" + rest.substring(colon + 1)
    }

    /**
     * Builds "kchat:1:handshake:<raw bytes>" — the encrypted bytes are appended
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
        // A first-contact `dm` message reads as `comm`.
        val text = try { normalizeFirstContact(String(rawBytes, Charsets.UTF_8)) } catch (e: Exception) { return null }
        // ["kchat", "1", "comm", alias, base64] (or the legacy "ciph_msg" root) — Kotlin limit=5 matches Swift's maxSplits:4
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

    // -------------------------------------------------------------------------
    // Payment memo: `kchat:1:pay:<sealed>` (iOS KasiaTransactionBuilder.buildPaymentPayload)
    // -------------------------------------------------------------------------

    /**
     * The encrypted JSON a payment's payload carries - field for field iOS's `PaymentPayload`
     * (declaration order is the wire order Gson writes). [message] is the memo typed in the Send
     * KAS sheet (iOS 8d208b2); [amount] is in sompi.
     */
    data class PaymentPayload(
        val type: String = "payment",
        val message: String,
        val amount: Long,
        val timestamp: Long,
        val version: Int = 1,
    )

    private val PAY_PREFIX_BYTES = "$PREFIX:$VERSION:$TYPE_PAY:".toByteArray(Charsets.US_ASCII)
    /** Read-only roots an older iOS build wrote (iOS decryptPaymentPayloadFromRawPayloadSync). */
    private val LEGACY_PAY_PREFIXES = listOf("$LEGACY_PREFIX:$VERSION:$TYPE_PAY:", "$LEGACY_PREFIX:$TYPE_PAY:")
        .map { it.toByteArray(Charsets.US_ASCII) }

    private fun paymentPrefixLength(rawBytes: ByteArray): Int? =
        (listOf(PAY_PREFIX_BYTES) + LEGACY_PAY_PREFIXES).firstOrNull { prefix ->
            rawBytes.size > prefix.size && rawBytes.copyOfRange(0, prefix.size).contentEquals(prefix)
        }?.size

    /**
     * Builds `kchat:1:pay:<raw sealed bytes>` - the [PaymentPayload] JSON encrypted to the
     * recipient's chatting key, appended after the ASCII prefix exactly as a handshake is (iOS hex
     * encodes the prefix and the sealed bytes and decodes the whole back into raw bytes). Only the
     * recipient can read the memo, so the sender's own bubble keeps it from send time (iOS 2be75ed).
     */
    fun buildPaymentPayload(note: String, amountSompi: Long, recipientXOnlyPubKey: ByteArray): ByteArray {
        val json = com.google.gson.Gson().toJson(
            PaymentPayload(message = note, amount = amountSompi, timestamp = System.currentTimeMillis())
        )
        return PAY_PREFIX_BYTES + encrypt(json, recipientXOnlyPubKey).toBytes()
    }

    /** True for a payment payload of any root (new or legacy). */
    fun isPaymentPayload(rawBytes: ByteArray): Boolean = paymentPrefixLength(rawBytes) != null

    /** The decrypted [PaymentPayload] of a payment addressed to [privateKey]'s owner, or null
     *  when it isn't one or can't be opened (sent to someone else, corrupt). */
    fun decryptPaymentPayload(rawBytes: ByteArray, privateKey: ByteArray): PaymentPayload? {
        val prefixLen = paymentPrefixLength(rawBytes) ?: return null
        val sealed = KasiaCipher.EncryptedMessage.fromBytes(rawBytes.copyOfRange(prefixLen, rawBytes.size)) ?: return null
        return try {
            val payload = com.google.gson.Gson().fromJson(decrypt(sealed, privateKey), PaymentPayload::class.java)
            // Gson leaves a missing field null rather than failing - a payload without its memo
            // field is treated as unreadable, not as a crash later on.
            @Suppress("SENSELESS_COMPARISON")
            if (payload == null || payload.message == null) null else payload
        } catch (e: Exception) {
            null
        }
    }

    /** The on-chain payload size of a memo-carrying payment, for the Send KAS sheet's fee pill
     *  (iOS estimatePaymentFee prices the memo in). Exact up to the timestamp's digit count. */
    fun estimatedPaymentPayloadSize(note: String, amountSompi: Long): Int {
        val json = com.google.gson.Gson().toJson(
            PaymentPayload(message = note, amount = amountSompi, timestamp = System.currentTimeMillis())
        )
        // nonce(12) + compressed ephemeral key(33) + ciphertext + Poly1305 tag(16)
        return PAY_PREFIX_BYTES.size + 12 + 33 + json.toByteArray(Charsets.UTF_8).size + 16
    }

    fun encrypt(plaintext: String, recipientXOnlyPubKey: ByteArray): KasiaCipher.EncryptedMessage =
        KasiaCipher.encrypt(plaintext, recipientXOnlyPubKey)

    fun decrypt(encrypted: KasiaCipher.EncryptedMessage, privateKey: ByteArray): String =
        KasiaCipher.decrypt(encrypted, privateKey)

    data class BroadcastMessage(val channel: String, val content: String)

    /**
     * Builds "kchat:1:bcast:<channel>:<content>" — broadcasts are never encrypted (matches
     * Kasia: a broadcast is a public, one-to-many channel, so pairwise ECDH encryption doesn't
     * apply the same way it does to a 1:1 message). [channel] should already be normalized via
     * [normalizeChannelName]/[isValidChannelName] before calling this.
     */
    fun buildBcastPayload(channel: String, content: String): ByteArray =
        "$PREFIX:$VERSION:$TYPE_BCAST:$channel:$content".toByteArray(Charsets.UTF_8)

    /** Parses a "bcast" payload, returning the channel name and plaintext content. */
    fun parseBcastPayload(rawBytes: ByteArray): BroadcastMessage? {
        val text = try { String(rawBytes, Charsets.UTF_8) } catch (e: Exception) { return null }
        // ["kchat", "1", "bcast", channel, content] (or the legacy root) — same shape as parseCommPayload.
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
