package com.kachat.app.models

import com.kachat.app.util.GroupCipher
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaUnit
import java.security.MessageDigest

/**
 * A request to be paid, carried in a link ([com.kachat.app.ui.screens.KaChatLinkRef.PaymentRequest]):
 * who to pay, the exact amount and what it's for. Made in Chats > + > Request Payment (or a 1:1
 * chat's + sheet); pasting one anywhere in KaChat, or tapping one, opens Send Kaspa with it filled
 * in (iOS `PaymentRequest`, c6851d6 / 7c71a98 / 427f19b).
 *
 * `https://kachat.app/pay/<address>?amount=<KAS>&memo=<text>&chat=1&by=<address>&sig=<hex>` (or
 * `kachat://pay/...`):
 * - the address without `kaspa:` on mainnet (like profile links), with `kaspatest:` on testnet;
 * - `amount` in KAS, at most 8 decimals, "." as the separator;
 * - `memo` optional, at most [MEMO_LIMIT] characters;
 * - `chat=1` when the address is the requester's chatting address: then the payment goes as a
 *   chat payment, with the memo encrypted to them in it. A spending address can't read a memo, so
 *   there the memo only tells the payer what it is for;
 * - `by` + `sig`: who made it - the maker's chatting address - and a Schnorr signature by that
 *   address's key over everything above ([signingDigest]). A maker is shown only when the
 *   signature checks out; a link whose signature doesn't is refused outright, since that is a link
 *   someone changed. Requests without them show "Not signed".
 *
 * Everything in it is attacker-controlled: the address must be valid on the network the app runs
 * on, the amount positive and exact, and the memo is cleaned of control and direction marks. The
 * byte layout of the signed digest is iOS's exactly, so a request made on one platform verifies on
 * the other.
 */
class PaymentRequest private constructor(
    val address: String,
    val amountSompi: Long,
    /** Already cleaned ([cleanMemo]). */
    val memo: String,
    val toChattingAddress: Boolean,
    /** The chatting address that signed the request - verified, or null. */
    val creator: String?,
    /** The creator's 64-byte Schnorr signature over [signingDigest]. */
    val signature: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (other !is PaymentRequest) return false
        val sameSignature = when {
            signature == null -> other.signature == null
            other.signature == null -> false
            else -> signature.contentEquals(other.signature)
        }
        return address == other.address && amountSompi == other.amountSompi && memo == other.memo &&
            toChattingAddress == other.toChattingAddress && creator == other.creator && sameSignature
    }

    override fun hashCode(): Int {
        var result = address.hashCode()
        result = 31 * result + amountSompi.hashCode()
        result = 31 * result + memo.hashCode()
        result = 31 * result + toChattingAddress.hashCode()
        result = 31 * result + (creator?.hashCode() ?: 0)
        result = 31 * result + (signature?.contentHashCode() ?: 0)
        return result
    }

    /**
     * What the maker signs: every field of the request and the maker's own address, under a
     * domain tag so the signature can't be passed off as anything else. SHA-256 of
     * "kachat-payment-request:v1" and the fields, one per line: address, amount in sompi, memo,
     * "1"/"0" for chat, creator (iOS `signingDigest(creator:)`, byte for byte).
     */
    fun signingDigest(creator: String): ByteArray {
        val text = listOf(
            "kachat-payment-request:v1",
            address.lowercase(),
            amountSompi.toString(),
            memo,
            if (toChattingAddress) "1" else "0",
            creator.lowercase(),
        ).joinToString("\n")
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    }

    /** This request signed by [creator] (the wallet's chatting address) with its key, or null. */
    fun signed(creator: String, privateKey: ByteArray): PaymentRequest? {
        val signature = runCatching { GroupCipher.sign(signingDigest(creator), privateKey) }.getOrNull() ?: return null
        if (signature.size != 64) return null
        return PaymentRequest(address, amountSompi, memo, toChattingAddress, creator.lowercase(), signature)
    }

    /** The amount in KAS, exact: "12.5", "0.00000001". */
    val amountText: String get() = KaspaUnit.plain(amountSompi)

    /** The link's address segment and query, shared by both forms. */
    val linkTail: String
        get() {
            val path = if (address.startsWith("kaspa:")) address.removePrefix("kaspa:") else address
            val query = StringBuilder("amount=").append(amountText)
            // Only unreserved characters stay as they are: "&", "=", "+", "#" and spaces in a memo
            // are all escaped, so it reads back exactly as written whatever decodes it.
            if (memo.isNotEmpty()) query.append("&memo=").append(percentEncodeUnreserved(memo))
            if (toChattingAddress) query.append("&chat=1")
            val creator = creator
            val signature = signature
            if (creator != null && signature != null) {
                val by = if (creator.startsWith("kaspa:")) creator.removePrefix("kaspa:") else creator
                query.append("&by=").append(by).append("&sig=").append(signature.joinToString("") { "%02x".format(it) })
            }
            return "$path?$query"
        }

    /** `kachat://pay/...` */
    val appLinkString: String get() = "kachat://pay/$linkTail"

    /** The link the Copy button gives out: `https://kachat.app/pay/...`. */
    val shareUrlString: String get() = "https://kachat.app/pay/$linkTail"

    /**
     * True when the address is one of this wallet's: your own request pastes as text (to send it
     * to someone) and its card shows it rather than opening Send. One you signed is yours at once;
     * otherwise the wallet's addresses are worked out once per account and address count
     * (iOS 427f19b) - see [com.kachat.app.services.PaymentRequestWallet.ownAddresses].
     */
    val isOwn: Boolean
        get() {
            val wallet = com.kachat.app.services.PaymentRequestWallet.shared ?: return false
            val chatting = wallet.chattingAddress()?.lowercase()
            if (creator != null && chatting != null && creator.lowercase() == chatting) return true
            return address.lowercase() in wallet.ownAddresses()
        }

    companion object {
        const val MEMO_LIMIT = 140
        private const val UNRESERVED = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~"

        /** A request with its memo cleaned ([cleanMemo]). */
        fun create(
            address: String,
            amountSompi: Long,
            memo: String,
            toChattingAddress: Boolean,
            creator: String? = null,
            signature: ByteArray? = null,
        ): PaymentRequest = PaymentRequest(address, amountSompi, cleanMemo(memo), toChattingAddress, creator, signature)

        /** True when [signature] is [creator]'s over [request]. */
        fun verify(signature: ByteArray, creator: String, request: PaymentRequest): Boolean {
            val key = runCatching {
                val (version, payload) = KaspaAddress.decode(creator)
                payload.takeIf { version.toInt() == 0 && it.size == 32 }
            }.getOrNull() ?: return false
            return runCatching { GroupCipher.verify(signature, request.signingDigest(creator), key) }.getOrDefault(false)
        }

        /**
         * From a link's address segment (percent-decoded) and its raw query (still encoded, no
         * leading "?"); null unless every part is valid - and, when it carries `by` and `sig`,
         * unless the signature checks out (iOS `init?(rawAddress:url:)`).
         */
        fun fromLink(rawAddress: String, rawQuery: String?): PaymentRequest? {
            val raw = rawAddress.trim().lowercase()
            if (raw.length > 100) return null
            val full = if (raw.contains(":")) raw else "kaspa:$raw"
            // the request names an address on one network: a payment can't cross networks
            if (!KaspaAddress.isValidOnActiveNetwork(full)) return null
            val items = queryItems(rawQuery)
            fun value(name: String): String? = items.firstOrNull { it.first.lowercase() == name }?.second
            val amountText = value("amount") ?: return null
            if (!amountText.all { it in '0'..'9' || it == '.' }) return null
            val sompi = KaspaUnit.sompiFromUserText(amountText)?.takeIf { it > 0 } ?: return null
            val unsigned = create(full, sompi, value("memo") ?: "", value("chat") == "1")
            val by = value("by")
            val sig = value("sig")
            return when {
                by == null && sig == null -> unsigned
                by != null && sig != null -> {
                    val rawBy = by.trim().lowercase()
                    val creator = if (rawBy.contains(":")) rawBy else "kaspa:$rawBy"
                    // a maker is shown only when the signature is theirs; one that isn't is a changed link
                    if (rawBy.length > 100 || !KaspaAddress.isValidOnActiveNetwork(creator)) return null
                    val signature = hexBytes(sig)?.takeIf { it.size == 64 } ?: return null
                    if (!verify(signature, creator, unsigned)) return null
                    PaymentRequest(full, sompi, unsigned.memo, unsigned.toChattingAddress, creator, signature)
                }
                else -> null
            }
        }

        /** Name/value pairs of a raw query, each percent-decoded ("+" stays "+", as iOS's
         *  URLComponents reads it); a pair without "=" has no value. */
        private fun queryItems(rawQuery: String?): List<Pair<String, String?>> {
            if (rawQuery.isNullOrEmpty()) return emptyList()
            return rawQuery.split('&').map { part ->
                val eq = part.indexOf('=')
                if (eq < 0) android.net.Uri.decode(part) to null
                else android.net.Uri.decode(part.substring(0, eq)) to android.net.Uri.decode(part.substring(eq + 1))
            }
        }

        private fun hexBytes(hex: String): ByteArray? {
            val h = hex.lowercase()
            val out = ByteArray(h.length / 2)
            for (i in out.indices) {
                val hi = Character.digit(h[2 * i], 16)
                val lo = Character.digit(h[2 * i + 1], 16)
                if (hi < 0 || lo < 0) return null
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }

        private fun percentEncodeUnreserved(text: String): String {
            val out = StringBuilder()
            for (byte in text.toByteArray(Charsets.UTF_8)) {
                val c = (byte.toInt() and 0xFF).toChar()
                if (byte >= 0 && c in UNRESERVED) out.append(c) else out.append("%%%02X".format(byte.toInt() and 0xFF))
            }
            return out.toString()
        }

        /**
         * The memo without control and format characters (bidi and zero-width marks among them),
         * trimmed, and cut to [MEMO_LIMIT] characters - user-perceived characters, as iOS counts
         * them, so a memo cut on one platform reads back the same on the other.
         */
        fun cleanMemo(memo: String): String {
            val filtered = StringBuilder()
            var i = 0
            while (i < memo.length) {
                val cp = memo.codePointAt(i)
                i += Character.charCount(cp)
                val type = Character.getType(cp)
                if (type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()) continue
                if (cp in 0x200B..0x200F || cp in 0x202A..0x202E || cp in 0x2066..0x2069 || cp == 0xFEFF) continue
                if (type == Character.SURROGATE.toInt()) continue
                filtered.appendCodePoint(cp)
            }
            val cleaned = filtered.toString().trim()
            return takeCharacters(cleaned, MEMO_LIMIT)
        }

        /** How many user-perceived characters [text] has (Swift's `String.count`). */
        fun characterCount(text: String): Int {
            if (text.isEmpty()) return 0
            val it = android.icu.text.BreakIterator.getCharacterInstance()
            it.setText(text)
            var count = 0
            while (it.next() != android.icu.text.BreakIterator.DONE) count++
            return count
        }

        /** The first [limit] user-perceived characters of [text] (Swift's `prefix`). */
        fun takeCharacters(text: String, limit: Int): String {
            if (text.length <= limit) return text
            val it = android.icu.text.BreakIterator.getCharacterInstance()
            it.setText(text)
            var end = 0
            var count = 0
            while (count < limit) {
                val next = it.next()
                if (next == android.icu.text.BreakIterator.DONE) return text
                end = next
                count++
            }
            return text.substring(0, end)
        }
    }
}
