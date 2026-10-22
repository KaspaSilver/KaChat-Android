package com.kachat.app.util

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.math.BigInteger
import java.security.SecureRandom

/**
 * Kasia message encryption: ECDH (secp256k1) -> HKDF-SHA256 -> ChaCha20-Poly1305.
 *
 * Mirrors the iOS `KaChatCipher.swift` implementation exactly (byte-for-byte wire
 * format) so a real KaChat iOS user can decrypt messages sent from this app.
 * Deliberately does NOT use AES-GCM despite MESSAGING.md's description — that doc
 * is stale; the real iOS implementation uses ChaCha20-Poly1305 via CryptoKit.
 */
object KasiaCipher {

    private const val NONCE_SIZE = 12
    private const val TAG_SIZE = 16
    private const val COMPRESSED_KEY_SIZE = 33

    /**
     * Wire layout: nonce(12) || ephemeralPubKey(33, SEC1-compressed) || ciphertext || tag(16).
     */
    data class EncryptedMessage(
        val nonce: ByteArray,
        val ephemeralPublicKey: ByteArray,
        val ciphertext: ByteArray // ciphertext with the 16-byte Poly1305 tag appended
    ) {
        fun toBytes(): ByteArray = nonce + ephemeralPublicKey + ciphertext

        companion object {
            fun fromBytes(bytes: ByteArray): EncryptedMessage? {
                val minSize = NONCE_SIZE + 32 + TAG_SIZE
                if (bytes.size < minSize) return null

                val nonce = bytes.copyOfRange(0, NONCE_SIZE)
                val isCompressedPoint = bytes[NONCE_SIZE] == 0x02.toByte() || bytes[NONCE_SIZE] == 0x03.toByte()
                val keySize = if (isCompressedPoint) COMPRESSED_KEY_SIZE else 32
                val keyEnd = NONCE_SIZE + keySize
                if (bytes.size < keyEnd + TAG_SIZE) return null

                val ephemeralPublicKey = bytes.copyOfRange(NONCE_SIZE, keyEnd)
                val ciphertext = bytes.copyOfRange(keyEnd, bytes.size)
                return EncryptedMessage(nonce, ephemeralPublicKey, ciphertext)
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EncryptedMessage) return false
            return nonce.contentEquals(other.nonce) &&
                ephemeralPublicKey.contentEquals(other.ephemeralPublicKey) &&
                ciphertext.contentEquals(other.ciphertext)
        }

        override fun hashCode(): Int {
            var result = nonce.contentHashCode()
            result = 31 * result + ephemeralPublicKey.contentHashCode()
            result = 31 * result + ciphertext.contentHashCode()
            return result
        }
    }

    /**
     * Encrypts [plaintext] for a recipient identified by their 32-byte x-only
     * secp256k1 public key (this is exactly the payload of their Kaspa address).
     */
    fun encrypt(plaintext: String, recipientXOnlyPubKey: ByteArray): EncryptedMessage =
        encrypt(plaintext.toByteArray(Charsets.UTF_8), recipientXOnlyPubKey)

    /**
     * Encrypts raw bytes - the binary media frame ([ContextualMediaFrame]) of a photo or voice
     * note travels this way, without a base64 layer inside the encryption (iOS `encrypt(data:)`).
     */
    fun encrypt(data: ByteArray, recipientXOnlyPubKey: ByteArray): EncryptedMessage {
        val ephemeralPrivate = randomScalar()
        val ephemeralPublic = Secp256k1.G.multiply(ephemeralPrivate).normalize().getEncoded(true)

        val recipientPoint = decodePoint(recipientXOnlyPubKey)
        val sharedX = ecdhX(ephemeralPrivate, recipientPoint)
        val key = hkdfSha256(sharedX, ByteArray(0), 32)

        val nonce = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }
        val ciphertext = chaChaPolyEncrypt(key, nonce, data)

        return EncryptedMessage(nonce, ephemeralPublic, ciphertext)
    }

    /**
     * Decrypts a message using our own secp256k1 private key scalar.
     */
    fun decrypt(message: EncryptedMessage, privateKey: ByteArray): String {
        val ephemeralPoint = decodePoint(message.ephemeralPublicKey)
        val sharedX = ecdhX(BigInteger(1, privateKey), ephemeralPoint)
        val key = hkdfSha256(sharedX, ByteArray(0), 32)

        val plaintext = chaChaPolyDecrypt(key, message.nonce, message.ciphertext)
        // A binary media frame comes back as the same media-file JSON a text-encoded one always
        // was, so everything after decryption is unchanged (iOS KasiaCipher.decrypt).
        ContextualMediaFrame.envelopeText(plaintext)?.let { return it }
        // 0xFF never begins UTF-8: a frame that does not parse (cut short, a header length past
        // the end, a newer version) is unreadable, as on iOS - never text full of U+FFFD.
        if (ContextualMediaFrame.startsWithFrameByte(plaintext)) throw UnreadablePlaintextException()
        return String(plaintext, Charsets.UTF_8)
    }

    /** The plaintext opened but is neither text nor a well-formed binary media frame. */
    class UnreadablePlaintextException : IllegalStateException("Decrypted bytes are not text or a media frame")

    /**
     * Derives the raw 32-byte symmetric key (post-HKDF) for a given peer's
     * x-only public key — exposed for [com.kachat.app.services.WalletManager.deriveSharedSecret].
     */
    fun deriveSymmetricKey(privateKey: ByteArray, peerXOnlyPubKey: ByteArray): ByteArray {
        val peerPoint = decodePoint(peerXOnlyPubKey)
        val sharedX = ecdhX(BigInteger(1, privateKey), peerPoint)
        return hkdfSha256(sharedX, ByteArray(0), 32)
    }

    /**
     * Deterministic per-conversation alias — derived purely from both parties' public
     * keys via ECDH + HKDF-SHA256, so two contacts can find each other's self-stashed
     * messages without ever exchanging a handshake. Matches iOS KaChat's
     * DeterministicAlias.swift byte-for-byte: info = "chat" || sharedX(32) || contextPubKey(32).
     * [contextXOnlyPubKey] picks which side of the pair this alias is "for" — pass your
     * own pubkey to get the alias you watch for incoming, or the peer's to get the alias
     * you tag outgoing messages with (see WalletManager.myDeterministicAlias/theirDeterministicAlias).
     */
    fun deriveDeterministicAlias(myPrivateKey: ByteArray, theirXOnlyPubKey: ByteArray, contextXOnlyPubKey: ByteArray): String {
        val theirPoint = decodePoint(theirXOnlyPubKey)
        val sharedX = ecdhX(BigInteger(1, myPrivateKey), theirPoint)
        val info = "chat".toByteArray(Charsets.US_ASCII) + sharedX + contextXOnlyPubKey
        val aliasBytes = hkdfSha256(sharedX, info, 6)
        return aliasBytes.joinToString("") { "%02x".format(it) }
    }

    // -------------------------------------------------------------------------
    // ECDH
    // -------------------------------------------------------------------------

    /** Reconstructs a full curve point from either a 32-byte x-only key (assumes even Y) or a 33-byte SEC1-compressed key. */
    private fun decodePoint(pubKey: ByteArray): org.bouncycastle.math.ec.ECPoint {
        val compressed = if (pubKey.size == 32) byteArrayOf(0x02) + pubKey else pubKey
        return Secp256k1.CURVE.decodePoint(compressed)
    }

    private fun ecdhX(privateScalar: BigInteger, peerPoint: org.bouncycastle.math.ec.ECPoint): ByteArray {
        val shared = peerPoint.multiply(privateScalar).normalize()
        val compressed = shared.getEncoded(true) // 33 bytes: 0x02/0x03 || x(32)
        return compressed.copyOfRange(1, 33)
    }

    private fun randomScalar(): BigInteger {
        val random = SecureRandom()
        while (true) {
            val candidate = ByteArray(32).also { random.nextBytes(it) }
            val d = BigInteger(1, candidate)
            if (d != BigInteger.ZERO && d < Secp256k1.N) return d
        }
    }

    // -------------------------------------------------------------------------
    // HKDF-SHA256 (empty salt — matches CryptoKit's HKDF<SHA256> defaults; message
    // encryption uses empty info too, deterministic alias derivation passes a real one)
    // -------------------------------------------------------------------------

    private fun hkdfSha256(ikm: ByteArray, info: ByteArray, outputLength: Int): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(ikm, ByteArray(0), info))
        val out = ByteArray(outputLength)
        generator.generateBytes(out, 0, outputLength)
        return out
    }

    // -------------------------------------------------------------------------
    // ChaCha20-Poly1305 (BouncyCastle lightweight AEAD engine, not javax.crypto —
    // Android's native ChaCha20-Poly1305 requires API 28+ while minSdk is 26)
    // -------------------------------------------------------------------------

    private fun chaChaPolyEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), TAG_SIZE * 8, nonce, null))
        val output = ByteArray(cipher.getOutputSize(plaintext.size))
        var len = cipher.processBytes(plaintext, 0, plaintext.size, output, 0)
        len += cipher.doFinal(output, len)
        return output.copyOf(len)
    }

    private fun chaChaPolyDecrypt(key: ByteArray, nonce: ByteArray, ciphertextWithTag: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), TAG_SIZE * 8, nonce, null))
        val output = ByteArray(cipher.getOutputSize(ciphertextWithTag.size))
        var len = cipher.processBytes(ciphertextWithTag, 0, ciphertextWithTag.size, output, 0)
        len += cipher.doFinal(output, len) // throws InvalidCipherTextException on tag mismatch
        return output.copyOf(len)
    }
}
