package com.kachat.app.util

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Wire codec for the payment-pool envelopes (MESSAGING.md, "Chat Payments From a Spending
 * Address" and its "Retired" note) - three invisible JSON envelope types embedded in the normal
 * encrypted contextual content, exactly like reactions ([MessageReaction]). Field names/types
 * match the iOS reference (`PaymentPoolCodec` in Models.swift).
 *
 * Chats Payment Privacy was removed (iOS 6333dd3): `addr_pool` / `addr_pool_request` are never
 * sent any more, and other clients' copies are swallowed (no bubble, no banner).
 *
 * `payment_notice` lives on for one job (iOS 927322b, XP-022): a 1:1 chat payment sent From a
 * spending address is a plain transfer the payee can't place, so the chatting address sends a
 * notice naming it right after (ChatViewModel.sendPayment), and both sides file it in their 1:1
 * chat (ChatRepository.handlePaymentNotice). A notice naming any other address - an old app
 * paying one of our former pool addresses - is dropped, and its push stays silent (XP-023).
 * Unknown `type` values fall through to the normal message pipeline, and unknown extra fields
 * inside these envelopes are ignored (Gson does both naturally).
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

    /** Sent by the PAYER's chatting address right after a chat payment From a spending address
     *  (a plain transfer the payee has never seen the source of): [txId] (lowercase hex) and
     *  [amountSompi] of that payment, [address] = the payee's chatting address it paid. [memo]
     *  (optional, absent when empty - Gson omits nulls) is the payer's note, which travels only
     *  here (iOS 927322b / 6b20d77, MESSAGING.md "payment_notice"). */
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

    /** The payer's bubble for a notice read back on another of its devices: "Sent X KAS", or
     *  "Sent X KAS — memo" - the shape ChatViewModel.sendPayment writes. */
    fun sentNoticeText(formattedAmount: String, symbol: String, memo: String?): String {
        val note = noticeMemo(memo)
        return "Sent $formattedAmount $symbol" + (note?.let { " — $it" } ?: "")
    }

    /** A notice's txId as both sides key the payment by: trimmed, lowercase, and exactly 64 hex
     *  characters - null for anything else (iOS handlePaymentNotice). */
    fun noticeTxId(raw: String?): String? {
        val txId = raw?.trim()?.lowercase() ?: return null
        if (txId.length != 64 || !txId.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return txId
    }

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
