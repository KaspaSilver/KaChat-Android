package com.kachat.app.util

import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * `saved_handshake` self-stash notes (MESSAGING.md "Saved-handshake notes"): JSON encrypted to our
 * own key, in a fee-only transaction to ourselves, under
 * `kchat:1:self_stash:saved_handshake:`. Two shapes, both iOS's exactly:
 * - a **handshake** note, written after every handshake we send (iOS
 *   `KasiaTransactionBuilder.buildHandshakeSelfStashTx`): our alias, their alias when known, the
 *   partner's address, and `isResponse` only when true - so a reinstall learns the chat and its
 *   routing back (audit XP-003);
 * - a **contact** note for a chat that never had a handshake (iOS `buildContactSelfStashTx`).
 */
object SavedHandshakeNote {

    const val SCOPE = "saved_handshake"
    const val PREFIX = "kchat:1:self_stash:$SCOPE:"

    private val gson = Gson()

    /** iOS buildHandshakeSelfStashTx's payload: nil values (theirAlias, a false isResponse) are
     *  left out, as its `compactMapValues` does. */
    fun handshakeJson(ourAlias: String, theirAlias: String?, partnerAddress: String, isResponse: Boolean, timestampMs: Long): String {
        val json = JsonObject().apply {
            addProperty("type", "handshake")
            addProperty("alias", ourAlias)
            addProperty("timestamp", timestampMs)
            addProperty("version", 1)
            if (theirAlias != null) addProperty("theirAlias", theirAlias)
            addProperty("partnerAddress", partnerAddress)
            addProperty("recipientAddress", partnerAddress)
            if (isResponse) addProperty("isResponse", true)
        }
        return gson.toJson(json)
    }

    /** iOS buildContactSelfStashTx's payload: the partner's address only, no alias fields. */
    fun contactJson(partnerAddress: String, timestampMs: Long): String {
        val json = JsonObject().apply {
            addProperty("type", "contact")
            addProperty("timestamp", timestampMs)
            addProperty("version", 1)
            addProperty("partnerAddress", partnerAddress)
            addProperty("recipientAddress", partnerAddress)
        }
        return gson.toJson(json)
    }

    /** [PREFIX] + [json] sealed to [ownXOnlyPubKey] (raw bytes, as iOS builds it). */
    fun payload(json: String, ownXOnlyPubKey: ByteArray): ByteArray =
        PREFIX.toByteArray(Charsets.US_ASCII) + KasiaCipher.encrypt(json, ownXOnlyPubKey).toBytes()

    /** What a read-back note says: our alias (null on a contact note), their alias, the partner. */
    data class Content(val alias: String?, val theirAlias: String?, val contactAddress: String)

    /** Parses a decrypted note; field names as iOS decryptSelfStash accepts them. */
    fun parse(plaintext: String): Content? = try {
        val json = gson.fromJson(plaintext, JsonObject::class.java)
        fun field(vararg names: String) = names.firstNotNullOfOrNull { name ->
            json.get(name)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }
        }
        Content(
            alias = field("alias"),
            theirAlias = field("theirAlias", "their_alias"),
            contactAddress = field("partnerAddress", "recipientAddress", "partner_address", "recipient_address").orEmpty(),
        )
    } catch (e: Exception) {
        null
    }
}
