package com.kachat.app.util

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Wire codec for the fresh-address payment pool protocol (MESSAGING.md, "Fresh-Address Payment
 * Pools") - three invisible JSON envelope types embedded in the normal encrypted contextual
 * content, exactly like reactions ([MessageReaction]). Field names/types must match the iOS
 * reference (`PaymentPoolCodec` in Models.swift) exactly - this is a cross-platform contract.
 *
 * All three are intercepted before rendering (see ChatRepository.processContextualMessage) and
 * never appear as chat bubbles; a `payment_notice` *produces* a payment bubble but the envelope
 * itself is not shown. Unknown `type` values fall through to the normal message pipeline, and
 * unknown extra fields inside these envelopes are ignored (Gson does both naturally).
 */
object PaymentPoolProtocol {

    /** A batch of the SENDER's own fresh receive addresses. `replace == true`: discard the
     *  previous pool, this list is authoritative; false/absent: append, deduped. An empty
     *  `replace:true` list is the revocation primitive - it clears the stored pool entirely. */
    data class AddressPoolContent(
        val type: String = "addr_pool",
        val addresses: List<String>,
        val replace: Boolean?
    )

    /** "Please send me a fresh pool" - sent when the stored pool for a contact runs low. */
    data class AddressPoolRequestContent(
        val type: String = "addr_pool_request"
    )

    /** Sent by the PAYER alongside a pool-address payment - payment detection only watches the
     *  chatting address, so without this the recipient's chat would show nothing. */
    data class PaymentNoticeContent(
        val type: String = "payment_notice",
        val txId: String,
        val amountSompi: Long,
        val address: String
    )

    sealed class Envelope {
        data class Pool(val content: AddressPoolContent) : Envelope()
        data class Request(val content: AddressPoolRequestContent) : Envelope()
        data class Notice(val content: PaymentNoticeContent) : Envelope()
    }

    private val gson = Gson()

    fun encode(content: AddressPoolContent): String = gson.toJson(content)
    fun encode(content: AddressPoolRequestContent): String = gson.toJson(content)
    fun encode(content: PaymentNoticeContent): String = gson.toJson(content)

    /** Same `{`-prefix + size guard as the iOS codec, since this runs on every intercepted
     *  message's plaintext. Returns null for anything that isn't a well-formed pool envelope. */
    fun parse(text: String?): Envelope? {
        if (text == null || text.length > 100_000) return null
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return null
        val obj: JsonObject = try {
            JsonParser.parseString(trimmed).asJsonObject
        } catch (e: Exception) {
            return null
        }
        val type = try { obj.get("type")?.asString } catch (e: Exception) { null } ?: return null
        return try {
            when (type) {
                "addr_pool" -> {
                    val content = gson.fromJson(obj, AddressPoolContent::class.java)
                    // Gson leaves a missing required list null rather than failing - reject it.
                    @Suppress("SENSELESS_COMPARISON")
                    if (content.addresses == null) null else Envelope.Pool(content)
                }
                "addr_pool_request" -> Envelope.Request(gson.fromJson(obj, AddressPoolRequestContent::class.java))
                "payment_notice" -> {
                    val content = gson.fromJson(obj, PaymentNoticeContent::class.java)
                    @Suppress("SENSELESS_COMPARISON")
                    if (content.txId == null || content.address == null) null else Envelope.Notice(content)
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}
