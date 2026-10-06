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
     *  chatting address, so without this the recipient's chat would show nothing. [memo]
     *  (optional, absent when empty - Gson omits nulls) is the payer's note: the notice is the
     *  only part of a pool payment the recipient's chat reads, and it already travels encrypted
     *  to the contact (iOS 6b20d77, MESSAGING.md "payment_notice"). */
    data class PaymentNoticeContent(
        val type: String = "payment_notice",
        val txId: String,
        val amountSompi: Long,
        val address: String,
        val memo: String? = null
    )

    /** The longest note a notice carries or shows (iOS PaymentNoticeContent.maxMemoLength). */
    const val MAX_NOTICE_MEMO_LENGTH = 500

    /** A note as a notice carries / shows it: trimmed, at most [MAX_NOTICE_MEMO_LENGTH]
     *  characters (user-perceived characters, like Swift's `prefix`), null when empty. Used on
     *  both the send and the receive side, as iOS does. */
    fun noticeMemo(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val iterator = java.text.BreakIterator.getCharacterInstance()
        iterator.setText(trimmed)
        var end = 0
        var count = 0
        while (count < MAX_NOTICE_MEMO_LENGTH) {
            val next = iterator.next()
            if (next == java.text.BreakIterator.DONE) { end = trimmed.length; break }
            end = next
            count++
        }
        return trimmed.substring(0, end).takeIf { it.isNotEmpty() }
    }

    /** The recipient's bubble for a notice: "Received X KAS", or "Received X KAS — memo" when the
     *  notice carries one - the same stored shape as a chatting-address payment with a note. */
    fun receivedNoticeText(formattedAmount: String, symbol: String, memo: String?): String {
        val note = noticeMemo(memo)
        return "Received $formattedAmount $symbol" + (note?.let { " — $it" } ?: "")
    }

    /** The key a `kchat:1:pay:` payload is sealed to: the contact's CHAT key, whichever address
     *  the payment pays (a pool address's key belongs to the recipient's spending chain, which no
     *  reader tries) - MESSAGING.md, Fresh-Address Payment Pools, payer step 2. */
    fun paymentPayloadSealKey(contactChatAddress: String): ByteArray =
        KaspaAddress.decode(contactChatAddress).second

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
