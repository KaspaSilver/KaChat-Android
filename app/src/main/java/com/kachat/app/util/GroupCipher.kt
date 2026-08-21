@file:OptIn(ExperimentalStdlibApi::class)

package com.kachat.app.util

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Group messaging crypto for KaChat's group chat feature.
 *
 * Kotlin port of the iOS `GroupCipher.swift` implementation — mirrors it byte-for-byte (key
 * derivation formulas, AAD construction, on-chain payload string format, JSON field names via
 * [SerializedName]) so an Android and iOS user can be in the same group and read each other's
 * messages. Protocol ported from the Kasia web client's `spike/groupchats` reference
 * implementation (GROUP_MESSAGING_SPEC.md). A prior revision also had a KaChat-specific
 * invite-beacon extension (join a group deterministically from a shared code, no prior 1:1
 * handshake needed) - removed once group chats route through indexers: a publicly-joinable
 * beacon would let anyone discover and join a group's *encrypted* chat, which is exactly the
 * kind of thing that could be used to infer something bad is happening inside it and pressure
 * an indexer operator into censoring it. Every member is now added directly by the admin.
 *
 * Trust model: a single admin controls membership and key rotation. All members share a
 * symmetric epoch root key; forward secrecy is at epoch granularity (bumped on membership
 * change), not per-message. Same primitives as 1:1 messaging ([KasiaCipher]): ChaCha20-Poly1305
 * AEAD, HKDF-SHA256, Schnorr/secp256k1 signatures ([Schnorr]) - reused here for consistency.
 *
 * Pure crypto/codec - no transaction building, no persistence, no network I/O.
 */
object GroupCipher {

    private const val NONCE_SIZE = 12
    private const val TAG_SIZE = 16

    // -------------------------------------------------------------------------
    // Key Derivation
    // -------------------------------------------------------------------------

    /** group_id = SHA256("ciph_msg:groupid" || group_seed) */
    fun deriveGroupId(groupSeed: ByteArray): ByteArray =
        sha256("ciph_msg:groupid".toByteArray(Charsets.UTF_8) + groupSeed)

    /** group_root_epoch_N = HKDF(group_seed, salt = group_id || epoch_le, info = "kasia:groot") */
    fun deriveGroupRootEpoch(groupSeed: ByteArray, groupId: ByteArray, epoch: Long): ByteArray =
        hkdf(groupSeed, groupId + leBytes(epoch), "kasia:groot".toByteArray(Charsets.UTF_8))

    /** blinding_key = HKDF(group_seed, salt = group_id, info = "kasia:blinding_key") */
    fun deriveBlindingKey(groupSeed: ByteArray, groupId: ByteArray): ByteArray =
        hkdf(groupSeed, groupId, "kasia:blinding_key".toByteArray(Charsets.UTF_8))

    /** blinded_group_id_user = HKDF(blinding_key, salt = member's x-only pubkey, info = "kasia:blinded_gid") */
    fun deriveBlindedGroupId(blindingKey: ByteArray, memberXOnlyPubKey: ByteArray): ByteArray =
        hkdf(blindingKey, memberXOnlyPubKey, "kasia:blinded_gid".toByteArray(Charsets.UTF_8))

    /** sender_id = SHA256(sender_address_string_bytes) */
    fun deriveSenderId(senderAddress: String): ByteArray =
        sha256(senderAddress.toByteArray(Charsets.UTF_8))

    /** sender_key = HKDF(group_root, salt = group_id || epoch_le, info = "kasia:gcomm:key" || sender_id) */
    fun deriveSenderKey(groupRootEpoch: ByteArray, groupId: ByteArray, epoch: Long, senderId: ByteArray): ByteArray =
        hkdf(groupRootEpoch, groupId + leBytes(epoch), "kasia:gcomm:key".toByteArray(Charsets.UTF_8) + senderId)

    /** sender_nonce_key = HKDF(group_root, salt = group_id || epoch_le, info = "kasia:gcomm:nonce" || sender_id) */
    fun deriveSenderNonceKey(groupRootEpoch: ByteArray, groupId: ByteArray, epoch: Long, senderId: ByteArray): ByteArray =
        hkdf(groupRootEpoch, groupId + leBytes(epoch), "kasia:gcomm:nonce".toByteArray(Charsets.UTF_8) + senderId)

    /** nonce = HKDF(sender_nonce_key, salt = msg_id, info = "kasia:gcomm:nonce")[0:12] */
    fun deriveNonce(senderNonceKey: ByteArray, msgId: ByteArray): ByteArray =
        hkdf(senderNonceKey, msgId, "kasia:gcomm:nonce".toByteArray(Charsets.UTF_8), NONCE_SIZE)

    data class MessageKeys(val senderKey: ByteArray, val nonce: ByteArray)

    fun deriveMessageKeys(groupRootEpoch: ByteArray, groupId: ByteArray, epoch: Long, senderId: ByteArray, msgId: ByteArray): MessageKeys {
        val senderKey = deriveSenderKey(groupRootEpoch, groupId, epoch, senderId)
        val senderNonceKey = deriveSenderNonceKey(groupRootEpoch, groupId, epoch, senderId)
        val nonce = deriveNonce(senderNonceKey, msgId)
        return MessageKeys(senderKey, nonce)
    }

    // -------------------------------------------------------------------------
    // Message AEAD (gcomm)
    // -------------------------------------------------------------------------

    /** AAD = version(1) || "gcomm" || group_id || epoch_le(8) || sender_id || msg_id */
    fun buildMessageAAD(groupId: ByteArray, epoch: Long, senderId: ByteArray, msgId: ByteArray): ByteArray {
        return byteArrayOf(0x01) + "gcomm".toByteArray(Charsets.UTF_8) + groupId + leBytes(epoch) + senderId + msgId
    }

    /** Encrypts [plaintext] for the group message envelope. Returns ciphertext+tag (nonce is deterministic, recomputed on decrypt). */
    fun encryptMessage(plaintext: String, groupRootEpoch: ByteArray, groupId: ByteArray, epoch: Long, senderId: ByteArray, msgId: ByteArray): ByteArray {
        val keys = deriveMessageKeys(groupRootEpoch, groupId, epoch, senderId, msgId)
        val aad = buildMessageAAD(groupId, epoch, senderId, msgId)
        return chaChaPolyEncrypt(keys.senderKey, keys.nonce, plaintext.toByteArray(Charsets.UTF_8), aad)
    }

    fun decryptMessage(ciphertextWithTag: ByteArray, groupRootEpoch: ByteArray, groupId: ByteArray, epoch: Long, senderId: ByteArray, msgId: ByteArray): String? {
        if (ciphertextWithTag.size < TAG_SIZE) return null
        val keys = deriveMessageKeys(groupRootEpoch, groupId, epoch, senderId, msgId)
        val aad = buildMessageAAD(groupId, epoch, senderId, msgId)
        val plaintext = try {
            chaChaPolyDecrypt(keys.senderKey, keys.nonce, ciphertextWithTag, aad)
        } catch (e: Exception) {
            return null
        }
        return String(plaintext, Charsets.UTF_8)
    }

    // -------------------------------------------------------------------------
    // Signing (gctl_root / gctl_epoch control messages, and gcomm message authenticity)
    // -------------------------------------------------------------------------

    /** Raw Schnorr sign over arbitrary bytes. */
    fun sign(message: ByteArray, privateKey: ByteArray): ByteArray = Schnorr.sign(message, privateKey)

    /** Raw Schnorr verify. */
    fun verify(signature: ByteArray, message: ByteArray, xOnlyPublicKey: ByteArray): Boolean =
        Schnorr.verify(message, signature, xOnlyPublicKey)

    /** Signing payload for gcomm: sign(AAD || ciphertext+tag). */
    fun buildMessageSigningPayload(aad: ByteArray, ciphertextWithTag: ByteArray): ByteArray = aad + ciphertextWithTag

    /** Signing payload for gctl_root: v || "gctl_root" || group_id || epoch_le || group_root_epoch || blinding_key || admin_signing_pub */
    fun buildRootSigningPayload(v: Int, groupId: ByteArray, epoch: Long, groupRootEpoch: ByteArray, blindingKey: ByteArray, adminSigningPub: ByteArray): ByteArray {
        return byteArrayOf(v.toByte()) + "gctl_root".toByteArray(Charsets.UTF_8) + groupId + leBytes(epoch) + groupRootEpoch + blindingKey + adminSigningPub
    }

    /** Signing payload for gctl_epoch: v || "gctl_epoch" || group_id || epoch_le || reason */
    fun buildEpochSigningPayload(v: Int, groupId: ByteArray, epoch: Long, reason: String): ByteArray {
        return byteArrayOf(v.toByte()) + "gctl_epoch".toByteArray(Charsets.UTF_8) + groupId + leBytes(epoch) + reason.toByteArray(Charsets.UTF_8)
    }

    fun buildTombstoneSigningPayload(v: Int, groupId: ByteArray): ByteArray {
        return byteArrayOf(v.toByte()) + "gctl_tombstone".toByteArray(Charsets.UTF_8) + groupId
    }

    // -------------------------------------------------------------------------
    // Control payloads (sent over the existing 1:1 encrypted COMM channel, JSON)
    // -------------------------------------------------------------------------

    /** gctl_root - admin distributes the epoch root + blinding key + roster to a member. */
    data class GroupRootPayload(
        val type: String = "gctl_root",
        val v: Int = 1,
        @SerializedName("group_id") val groupId: String,
        val epoch: Long,
        @SerializedName("group_root_epoch") val groupRootEpoch: String,
        @SerializedName("blinding_key") val blindingKey: String,
        @SerializedName("admin_signing_pub") val adminSigningPub: String,
        val members: List<String>,
        val name: String,
        val sig: String,
        // Present ONLY in the admin's self-addressed recovery copy (Gson omits null fields, so
        // members' copies never carry it). Not signed; authenticated by re-deriving group_id +
        // blinding_key from it (see GroupRepository.completeJoin). Lets a seedless re-import
        // rebuild the group as admin.
        @SerializedName("group_seed") val groupSeed: String? = null
    )

    /** gctl_epoch - admin announces a membership/epoch change ahead of sending a fresh root. */
    data class GroupEpochPayload(
        val type: String = "gctl_epoch",
        val v: Int = 1,
        @SerializedName("group_id") val groupId: String,
        val epoch: Long,
        val reason: String, // "add" | "remove" | "rotate"
        val sig: String
    )

    private val gson = Gson()

    fun buildSignedRootPayload(
        groupId: ByteArray,
        epoch: Long,
        groupRootEpoch: ByteArray,
        blindingKey: ByteArray,
        adminSigningPub: ByteArray,
        members: List<String>,
        name: String,
        adminPrivateKey: ByteArray,
        groupSeed: ByteArray? = null
    ): GroupRootPayload {
        val signingPayload = buildRootSigningPayload(1, groupId, epoch, groupRootEpoch, blindingKey, adminSigningPub)
        val sig = sign(signingPayload, adminPrivateKey)
        return GroupRootPayload(
            groupId = groupId.toHexString(),
            epoch = epoch,
            groupRootEpoch = groupRootEpoch.toHexString(),
            blindingKey = blindingKey.toHexString(),
            adminSigningPub = adminSigningPub.toHexString(),
            members = members,
            name = name,
            sig = sig.toHexString(),
            groupSeed = groupSeed?.toHexString()
        )
    }

    /**
     * Verifies a received gctl_root payload's signature against its own claimed admin key.
     * Callers are responsible for separately checking `adminSigningPub` is the group's known
     * trusted admin - this only proves internal consistency of the payload's own signature.
     */
    fun verifyRootPayload(payload: GroupRootPayload): Boolean {
        return try {
            val groupId = payload.groupId.hexToByteArray()
            val groupRootEpoch = payload.groupRootEpoch.hexToByteArray()
            val blindingKey = payload.blindingKey.hexToByteArray()
            val adminSigningPub = payload.adminSigningPub.hexToByteArray()
            val sig = payload.sig.hexToByteArray()
            val signingPayload = buildRootSigningPayload(payload.v, groupId, payload.epoch, groupRootEpoch, blindingKey, adminSigningPub)
            verify(sig, signingPayload, adminSigningPub)
        } catch (e: Exception) {
            false
        }
    }

    fun buildSignedEpochPayload(groupId: ByteArray, epoch: Long, reason: String, adminPrivateKey: ByteArray): GroupEpochPayload {
        val signingPayload = buildEpochSigningPayload(1, groupId, epoch, reason)
        val sig = sign(signingPayload, adminPrivateKey)
        return GroupEpochPayload(groupId = groupId.toHexString(), epoch = epoch, reason = reason, sig = sig.toHexString())
    }

    fun verifyEpochPayload(payload: GroupEpochPayload, adminSigningPub: ByteArray): Boolean {
        return try {
            val groupId = payload.groupId.hexToByteArray()
            val sig = payload.sig.hexToByteArray()
            val signingPayload = buildEpochSigningPayload(payload.v, groupId, payload.epoch, payload.reason)
            verify(sig, signingPayload, adminSigningPub)
        } catch (e: Exception) {
            false
        }
    }

    /** gctl_tombstone - a self-addressed "I deleted this group" marker so a delete survives a
     *  seedless re-import (the recovery invite would otherwise resurrect it). Signed by the
     *  deleter, honored ONLY when signed by + addressed to the reader's own key. */
    data class GroupTombstonePayload(
        val type: String = "gctl_tombstone",
        val v: Int = 1,
        @SerializedName("group_id") val groupId: String,
        @SerializedName("signing_pub") val signingPub: String,
        val sig: String
    )

    fun buildSignedTombstonePayload(groupId: ByteArray, signingPub: ByteArray, privateKey: ByteArray): GroupTombstonePayload {
        val sig = sign(buildTombstoneSigningPayload(1, groupId), privateKey)
        return GroupTombstonePayload(groupId = groupId.toHexString(), signingPub = signingPub.toHexString(), sig = sig.toHexString())
    }

    fun verifyTombstonePayload(payload: GroupTombstonePayload): Boolean {
        return try {
            val groupId = payload.groupId.hexToByteArray()
            val signingPub = payload.signingPub.hexToByteArray()
            val sig = payload.sig.hexToByteArray()
            verify(sig, buildTombstoneSigningPayload(payload.v, groupId), signingPub)
        } catch (e: Exception) { false }
    }

    fun tombstonePayloadToJson(payload: GroupTombstonePayload): String = gson.toJson(payload)
    fun tombstonePayloadFromJson(json: String): GroupTombstonePayload? =
        try { gson.fromJson(json, GroupTombstonePayload::class.java) } catch (e: Exception) { null }

    fun rootPayloadToJson(payload: GroupRootPayload): String = gson.toJson(payload)
    fun rootPayloadFromJson(json: String): GroupRootPayload? =
        try { gson.fromJson(json, GroupRootPayload::class.java) } catch (e: Exception) { null }

    fun epochPayloadToJson(payload: GroupEpochPayload): String = gson.toJson(payload)
    fun epochPayloadFromJson(json: String): GroupEpochPayload? =
        try { gson.fromJson(json, GroupEpochPayload::class.java) } catch (e: Exception) { null }

    // -------------------------------------------------------------------------
    // On-chain payload codecs
    // -------------------------------------------------------------------------

    data class ParsedGroupMessage(
        val blindedGroupId: ByteArray,
        val epoch: Long,
        val senderId: ByteArray,
        val senderPubKey: ByteArray,
        val msgId: ByteArray,
        val ciphertext: ByteArray,
        val signature: ByteArray
    )

    /** ciph_msg:1:gcomm:{blinded_group_id}:{epoch}:{sender_id}:{sender_pub}:{msg_id}:{ciphertext}:{signature} */
    fun buildGroupMessagePayload(
        blindedGroupId: ByteArray,
        epoch: Long,
        senderId: ByteArray,
        senderPubKey: ByteArray,
        msgId: ByteArray,
        ciphertext: ByteArray,
        signature: ByteArray
    ): String {
        return "kchat:1:gcomm:${blindedGroupId.toHexString()}:$epoch:${senderId.toHexString()}:" +
            "${senderPubKey.toHexString()}:${msgId.toHexString()}:${ciphertext.toHexString()}:${signature.toHexString()}"
    }

    fun parseGroupMessagePayload(payloadString: String): ParsedGroupMessage? {
        // Dual-read: new `kchat:` root and legacy `ciph_msg:` root (tail identical).
        val prefix = when {
            payloadString.startsWith("kchat:1:gcomm:") -> "kchat:1:gcomm:"
            payloadString.startsWith("ciph_msg:1:gcomm:") -> "ciph_msg:1:gcomm:"
            else -> return null
        }
        val rest = payloadString.substring(prefix.length)
        val parts = rest.split(":")
        if (parts.size != 7) return null
        return try {
            ParsedGroupMessage(
                blindedGroupId = parts[0].hexToByteArray(),
                epoch = parts[1].toLong(),
                senderId = parts[2].hexToByteArray(),
                senderPubKey = parts[3].hexToByteArray(),
                msgId = parts[4].hexToByteArray(),
                ciphertext = parts[5].hexToByteArray(),
                signature = parts[6].hexToByteArray()
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Recipient-addressed gctl (`ciph_msg:1:gctl:{recipient_xonly_pubkey}:{encrypted}`) is only
     * relevant to the live block-scan path - the indexer already strips this routing prefix from
     * `message_payload` in REST catch-up responses (see docs/GROUP_CHAT_API.md), so catch-up
     * never needs this. Detects and strips an addressed-format recipient prefix, if present, so
     * the rest of the parse/decrypt path (shared with legacy gctl) always sees the uniform
     * `ciph_msg:1:gctl:{encrypted}` shape. No recipient-address filtering happens here - same as
     * legacy gctl already relied on, a mismatched recipient's ECIES decrypt just fails silently.
     */
    fun normalizeControlPayload(payloadString: String): String {
        // Dual-read: accept either root, then ALWAYS re-root to canonical `kchat:1:gctl:` so the
        // downstream strip (handleIncomingControlMessage) is correct for old and new.
        val canonical = "kchat:1:gctl:"
        val root = when {
            payloadString.startsWith(canonical) -> canonical
            payloadString.startsWith("ciph_msg:1:gctl:") -> "ciph_msg:1:gctl:"
            else -> return payloadString
        }
        val rest = payloadString.substring(root.length)
        val parts = rest.split(":")
        if (parts.size != 2 || parts[0].length != 64 || !parts[0].all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            return canonical + rest
        }
        return canonical + parts[1]
    }

    // -------------------------------------------------------------------------
    // Random generation
    // -------------------------------------------------------------------------

    fun generateGroupSeed(): ByteArray = randomBytes(32)
    fun generateDeviceId(): ByteArray = randomBytes(16)

    /** msg_id = device_id (16 bytes) || msgCounter_le (u64) -> 24 bytes */
    fun buildMsgId(deviceId: ByteArray, counter: Long): ByteArray = deviceId + leBytes(counter)

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun leBytes(value: Long): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) {
            out[i] = ((value shr (i * 8)) and 0xFF).toByte()
        }
        return out
    }

    private fun randomBytes(count: Int): ByteArray = ByteArray(count).also { SecureRandom().nextBytes(it) }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, outputLength: Int = 32): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(outputLength)
        generator.generateBytes(out, 0, outputLength)
        return out
    }

    private fun chaChaPolyEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray?): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), TAG_SIZE * 8, nonce, aad))
        val output = ByteArray(cipher.getOutputSize(plaintext.size))
        var len = cipher.processBytes(plaintext, 0, plaintext.size, output, 0)
        len += cipher.doFinal(output, len)
        return output.copyOf(len)
    }

    private fun chaChaPolyDecrypt(key: ByteArray, nonce: ByteArray, ciphertextWithTag: ByteArray, aad: ByteArray?): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), TAG_SIZE * 8, nonce, aad))
        val output = ByteArray(cipher.getOutputSize(ciphertextWithTag.size))
        var len = cipher.processBytes(ciphertextWithTag, 0, ciphertextWithTag.size, output, 0)
        len += cipher.doFinal(output, len) // throws InvalidCipherTextException on tag mismatch
        return output.copyOf(len)
    }
}
