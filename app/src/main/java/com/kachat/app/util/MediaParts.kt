package com.kachat.app.util

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.security.SecureRandom

/**
 * One part of a long on-chain voice note, or one chunk of a multi-part photo (iOS 7c5c22e3
 * `VoicePartTag`, MESSAGING.md "Long voice notes (multi-part)" and "Photos: quality tiers and
 * multi-part photos"): `{"id":"<32 hex>","index":<1-based>,"count":<2...12>}`, under `voicePart`
 * on audio and `mediaPart` on an image, in the media frame's header and in the media-file JSON
 * rebuilt from it. Every voice part is a complete WebM-Opus file, so a client that ignores the
 * tag shows the parts as separate voice notes, in order.
 *
 * Read defensively: anything outside the rules below is no tag, and the message is then a plain
 * single voice note or photo.
 */
class VoicePartTag private constructor(val id: String, val index: Int, val count: Int) {

    /** The JSON object as it goes into the media-file JSON, fixed key order (iOS `json`). */
    val json: String get() = "{\"id\":\"$id\",\"index\":$index,\"count\":$count}"

    /** The part file's name, e.g. "voice-2of12.webm". */
    val fileName: String get() = "voice-${index}of$count.webm"

    /** A photo chunk's file name, e.g. "photo-2of6.jpg". */
    val photoFileName: String get() = "photo-${index}of$count.jpg"

    override fun equals(other: Any?): Boolean =
        other is VoicePartTag && other.id == id && other.index == index && other.count == count

    override fun hashCode(): Int = (id.hashCode() * 31 + index) * 31 + count

    override fun toString(): String = "VoicePartTag(index=$index, count=$count)"

    companion object {
        /** The most parts one note may have - a strict bound for readers too. Both paths' longest
         *  notes ([VoiceNotePolicy]) need exactly 12: 170 s / 14.5 s and 110 s / 9.8 s. */
        const val MAX_PARTS = 12

        /** The longest one part may run when read (a crafted part can't decode past this). Real
         *  parts are shorter ([VoiceNotePolicy]); 25 s was the old single-note cap. */
        const val MAX_PART_SECONDS = 25.0

        const val VOICE_KEY = "voicePart"
        const val MEDIA_KEY = "mediaPart"

        private val random = SecureRandom()
        private val jsonElementAdapter = Gson().getAdapter(JsonElement::class.java)

        /** A tag when [id] is hex of 16 to 64 characters (kept lowercased), [count] is 2...12 and
         *  [index] is 1...count; null otherwise. */
        fun of(id: String, index: Int, count: Int): VoicePartTag? {
            val lowered = id.lowercase()
            if (lowered.length !in 16..64) return null
            if (!lowered.all { it in '0'..'9' || it in 'a'..'f' }) return null
            if (count !in 2..MAX_PARTS || index !in 1..count) return null
            return VoicePartTag(lowered, index, count)
        }

        /** A tag from a decoded JSON value: null unless it is an object with a string `id` and
         *  integer `index` / `count` (numbers, not strings or booleans) that pass the rules. */
        fun fromJson(element: JsonElement?): VoicePartTag? {
            val obj = element?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val id = obj.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
            val index = integer(obj.get("index")) ?: return null
            val count = integer(obj.get("count")) ?: return null
            return of(id, index, count)
        }

        private fun integer(element: JsonElement?): Int? {
            val primitive = element?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
            if (!primitive.isNumber) return null
            val value = runCatching { primitive.asDouble }.getOrNull() ?: return null
            if (!value.isFinite() || value != Math.rint(value) || kotlin.math.abs(value) >= 1_000) return null
            return value.toInt()
        }

        /** A fresh note id: 16 random bytes as lowercase hex. */
        fun newGroupId(): String {
            val bytes = ByteArray(16).also { random.nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }

        /** The JSON key the tag goes under: `voicePart` on audio, `mediaPart` on a photo. */
        fun keyForMimeType(mimeType: String): String? {
            val lowered = mimeType.lowercase()
            if (lowered.startsWith("audio/")) return VOICE_KEY
            if (lowered.startsWith("image/")) return MEDIA_KEY
            return null
        }

        /**
         * The tag of a media-file JSON, read from its head only (the tag sits before `content`):
         * `voicePart` on audio, `mediaPart` on a photo. Null for everything else, including
         * replies, and for a tag under the other kind's key (iOS `VoicePartTag.sniff`).
         */
        fun sniff(text: String?): VoicePartTag? {
            if (text == null || text.firstOrNull() != '{' || text.length <= 64 && text.toByteArray(Charsets.UTF_8).size <= 64) return null
            val head = text.take(2048)
            val mime = InlineMediaSniff.mimeType(head) ?: return null
            val tagKey = keyForMimeType(mime) ?: return null
            val keyAt = head.indexOf("\"$tagKey\"")
            if (keyAt < 0) return null
            val restStart = keyAt + tagKey.length + 2
            val open = head.indexOf('{', restStart)
            if (open < 0) return null
            for (i in restStart until open) {
                val c = head[i]
                if (c != ':' && !c.isWhitespace()) return null
            }
            val close = head.indexOf('}', open)
            if (close < 0) return null
            return fromJson(parseStrictObject(head.substring(open, close + 1)))
        }

        /** The tag of a frame header or media-file JSON object, under the key its mime type takes. */
        fun fromEnvelope(obj: JsonObject, mimeType: String): VoicePartTag? {
            val key = keyForMimeType(mimeType) ?: return null
            return fromJson(obj.get(key))
        }

        private fun parseStrictObject(text: String): JsonElement? = try {
            val reader = JsonReader(StringReader(text)).apply { isLenient = false }
            val element = jsonElementAdapter.read(reader)
            if (reader.peek() != JsonToken.END_DOCUMENT) null else element
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * On-chain voice notes, 1:1 and groups (iOS 7c5c22e3 `VoiceNotePolicy`, MESSAGING.md "Long voice
 * notes (multi-part)"): 16 kbps CBR Opus in 60 ms frames everywhere, parts as long as the path's
 * per-transaction cap allows, and a note only as long as 1 KAS of fees allows. The Nextcloud
 * route is separate.
 *
 * - 1:1 (cap 31,000 bytes): parts of 14.5 s (242 packets, 30,807 bytes, ~0.0833 KAS each); 12
 *   parts make the note 170 s (2:50), 0.977 KAS.
 * - Groups (cap 21,000 bytes): parts of 9.8 s (164 packets, 20,929 bytes, ~0.0857 KAS each); the
 *   note is 110 s (1:50), 12 parts, 0.964 KAS.
 */
object VoiceNotePolicy {
    const val BITRATE = 16_000
    const val FRAME_MS = 60

    data class Path(
        /** The longest one part runs; a note this short is one untagged message. */
        val partSeconds: Double,
        /** The longest note: its parts' fees add up to 1 KAS at most. */
        val maxSeconds: Double,
        /** Hard ceiling of one part's WebM file. */
        val maxBytes: Int,
    ) {
        val partMs: Long get() = Math.round(partSeconds * 1000)
        val maxMs: Long get() = Math.round(maxSeconds * 1000)
    }

    val ONE_TO_ONE = Path(partSeconds = 14.5, maxSeconds = 170.0, maxBytes = 31_000)
    val GROUP = Path(partSeconds = 9.8, maxSeconds = 110.0, maxBytes = 21_000)
}

/**
 * On-chain photo quality, chosen at send time (iOS 7c5c22e3 `PhotoTier`, MESSAGING.md "Photos:
 * quality tiers and multi-part photos"). Each tier is a fee budget; it buys the largest JPEG whose
 * parts' summed fee stays within it.
 *
 * | Tier | Budget | 1:1 bytes | parts | Group bytes | parts |
 * |---|---|---|---|---|---|
 * | High | 1 KAS | 368,000 | 12 | 243,000 | 12 |
 * | Medium | 0.5 KAS | 184,000 | 6 | 121,500 | 6 |
 * | Low | 0.1 KAS | 36,000 | 2 | 23,500 | 2 |
 */
enum class PhotoTier(val budgetSompi: Long) {
    HIGH(100_000_000L),
    MEDIUM(50_000_000L),
    LOW(10_000_000L);

    /** The largest JPEG the tier buys on a path. */
    fun maxBytes(forGroup: Boolean): Int = when (this) {
        HIGH -> if (forGroup) 243_000 else 368_000
        MEDIUM -> if (forGroup) 121_500 else 184_000
        LOW -> if (forGroup) 23_500 else 36_000
    }
}

object PhotoPolicy {
    /** One part's largest chunk of JPEG: what one transaction carries with margin. */
    const val ONE_TO_ONE_PART_BYTES = 31_000
    const val GROUP_PART_BYTES = 21_000

    fun partBytes(forGroup: Boolean): Int = if (forGroup) GROUP_PART_BYTES else ONE_TO_ONE_PART_BYTES

    /** A photo's bytes as the chunks it goes as, in order: one chunk when it fits one part. */
    fun chunks(data: ByteArray, forGroup: Boolean): List<ByteArray> {
        val size = partBytes(forGroup)
        if (data.size <= size) return listOf(data)
        val chunks = ArrayList<ByteArray>((data.size + size - 1) / size)
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + size, data.size)
            chunks.add(data.copyOfRange(offset, end))
            offset = end
        }
        return chunks
    }

    /**
     * The fee a photo of [bytes] costs on a path - the quality sheet's number (iOS
     * `PhotoPolicy.estimatedFeeSompi`). Per part of R bytes, header ~142 bytes tagged (~100 not):
     * 1:1 payload = 26 + 4 ceil((9 + header + R + 61) / 3); groups 391 + 2 (9 + header + R + 16);
     * mass 2 (payload + 272), 100 sompi a gram. The send pays what its transactions actually need.
     */
    fun estimatedFeeSompi(bytes: Int, forGroup: Boolean): Long {
        val size = partBytes(forGroup)
        val count = maxOf(1, (bytes + size - 1) / size)
        var total = 0L
        var remaining = bytes
        repeat(count) {
            val chunk = minOf(size, remaining)
            remaining -= chunk
            val header = if (count > 1) 142 else 100
            val plaintext = 9 + header + chunk
            val payload = if (forGroup) 391 + 2 * (plaintext + 16) else 26 + 4 * ((plaintext + 61 + 2) / 3)
            total += (2L * (payload + 272)) * 100 + 3
        }
        return total
    }
}

/**
 * The WebM-Opus layout the part encoder writes, for working out a recording's part sizes before
 * it is encoded (live fee estimates) - iOS `VoiceNoteLayout`. CBR makes it exact to a few bytes.
 *
 * - Packet bytes: libopus 1.5's CBR rounding, (12 x bitrate / 8 + fr12 / 2) / fr12 with
 *   fr12 = 12 x 48000 / frame samples.
 * - Packets: ceil((part samples + 312 look-ahead samples) / frame samples).
 * - Bytes: 155 of headers + (packet + 6 of SimpleBlock) per packet + 10 per Cluster of
 *   1000 / frameMs packets.
 */
object VoiceNoteLayout {
    fun partSizes(
        durationSeconds: Double,
        partSeconds: Double,
        maxParts: Int = VoicePartTag.MAX_PARTS,
        bitrate: Int = VoiceNotePolicy.BITRATE,
        frameMs: Int = VoiceNotePolicy.FRAME_MS,
        maxBytes: Int,
        sampleRate: Double = 48_000.0,
    ): List<Int> {
        var remaining = minOf(maxOf(durationSeconds, frameMs / 1000.0), partSeconds * maxParts)
        val frameSamples = maxOf(1, sampleRate.toInt() * frameMs / 1000)
        val frameRate12 = maxOf(1, 12 * sampleRate.toInt() / frameSamples)
        val packetBytes = (12 * bitrate / 8 + frameRate12 / 2) / frameRate12
        val packetsPerCluster = maxOf(1, 1000 / maxOf(1, frameMs))
        val lookahead = sampleRate.toInt() * 312 / 48_000
        val sizes = ArrayList<Int>()
        while (remaining > 0.0005 && sizes.size < maxParts) {
            val part = minOf(remaining, partSeconds)
            val samples = (part * sampleRate).toInt() + lookahead
            val packets = (samples + frameSamples - 1) / frameSamples
            val clusters = (packets + packetsPerCluster - 1) / packetsPerCluster
            sizes.add(minOf(155 + packets * (packetBytes + 6) + clusters * 10, maxBytes))
            remaining -= part
        }
        return sizes
    }
}

/**
 * The parts of one long voice note or multi-part photo found in a thread: same owner (the sender,
 * and the group in a group chat), same tag id and count (iOS `VoicePartCluster`).
 */
class VoicePartCluster<M>(
    val key: String,
    val count: Int,
    /** The parts present, one message per index, in index order. */
    val parts: List<M>,
    /** The earliest part in thread order: where the one bubble sits, and whose time it shows. */
    val anchor: M,
    /** Every message of the group by txId, the hidden duplicates of an index included. */
    val allTxIds: Set<String>,
    /** The tag index of each entry of [parts]. */
    val partIndexes: List<Int>,
    private val statusOf: (M) -> String,
    private val isOutgoing: (M) -> Boolean,
) {
    val isComplete: Boolean get() = parts.size == count

    /** Sending / Sent / Failed for the whole note: any failed part fails it, then any part still
     *  pending keeps it sending, then any unconfirmed part shows that. */
    val deliveryStatus: String get() = VoicePartGrouping.combinedStatus(parts.map(statusOf))

    /** The parts still to resend: failed, outgoing, in index order. */
    val failedOutgoingParts: List<M> get() = parts.filter { isOutgoing(it) && statusOf(it) == "failed" }
}

/**
 * Groups a thread's long voice notes and multi-part photos into one row each (iOS
 * `VoicePartGrouping`). Generic over the 1:1 row ([com.kachat.app.models.MessageEntity]) and the
 * group row ([com.kachat.app.repository.GroupMessage]) through [Accessors].
 */
object VoicePartGrouping {
    class Accessors<M>(
        val txId: (M) -> String,
        val content: (M) -> String?,
        /** Who a note belongs to, for the grouping key: the sender's address in 1:1; the group id
         *  and the sender id in a group. */
        val owner: (M) -> String,
        /** Whether this kind of message can carry a part at all. */
        val canBePart: (M) -> Boolean,
        val status: (M) -> String,
        val isOutgoing: (M) -> Boolean,
    )

    class Result<M>(
        /** Groups by the anchor's txId. */
        val groupsByAnchorTxId: Map<String, VoicePartCluster<M>> = emptyMap(),
        /** Messages that are a group's part but not its anchor: not drawn. */
        val hiddenTxIds: Set<String> = emptySet(),
        /** Each part's txId to its group's anchor txId. */
        val anchorTxIdByTxId: Map<String, String> = emptyMap(),
        /** Each anchor's txId to every txId of its group. */
        val groupTxIdsByAnchorTxId: Map<String, Set<String>> = emptyMap(),
    ) {
        /** Every message a selection stands for: a note's row brings all its parts. */
        fun expanded(txIds: Collection<String>): Set<String> {
            val out = txIds.toMutableSet()
            for (txId in txIds) groupTxIdsByAnchorTxId[txId]?.let { out.addAll(it) }
            return out
        }
    }

    fun key(owner: String, tag: VoicePartTag): String = "$owner|${tag.id}|${tag.count}"

    /** iOS `DeliveryStatus.priority`: which of two messages for one index is kept. */
    private fun priority(status: String): Int = when (status) {
        "pending" -> 0
        "warning" -> 1
        "failed" -> 2
        else -> 3
    }

    fun combinedStatus(statuses: List<String>): String = when {
        "failed" in statuses -> "failed"
        "pending" in statuses -> "pending"
        "warning" in statuses -> "warning"
        else -> "sent"
    }

    /** Groups the parts in [messages], which must be in thread order. A group with a single part
     *  present still becomes one (its bubble says it is receiving the rest). */
    fun <M> group(messages: List<M>, accessors: Accessors<M>): Result<M> {
        val tagged = LinkedHashMap<String, MutableList<Pair<M, VoicePartTag>>>()
        for (message in messages) {
            if (!accessors.canBePart(message)) continue
            val tag = VoicePartTag.sniff(accessors.content(message)) ?: continue
            tagged.getOrPut(key(accessors.owner(message), tag)) { ArrayList() }.add(message to tag)
        }
        if (tagged.isEmpty()) return Result()
        val groups = HashMap<String, VoicePartCluster<M>>()
        val hidden = HashSet<String>()
        val anchorByTxId = HashMap<String, String>()
        val txIdsByAnchor = HashMap<String, Set<String>>()
        for ((key, members) in tagged) {
            val anchor = members.first().first
            val byIndex = java.util.TreeMap<Int, M>()
            for ((message, tag) in members) {
                val existing = byIndex[tag.index]
                // Two messages for one index (a re-send that landed twice): the delivered one.
                if (existing == null || priority(accessors.status(message)) > priority(accessors.status(existing))) {
                    byIndex[tag.index] = message
                }
            }
            val anchorTxId = accessors.txId(anchor)
            val txIds = members.map { accessors.txId(it.first) }.toSet()
            groups[anchorTxId] = VoicePartCluster(
                key = key,
                count = members.first().second.count,
                parts = byIndex.values.toList(),
                anchor = anchor,
                allTxIds = txIds,
                partIndexes = byIndex.keys.toList(),
                statusOf = accessors.status,
                isOutgoing = accessors.isOutgoing,
            )
            txIdsByAnchor[anchorTxId] = txIds
            for ((message, _) in members) {
                val txId = accessors.txId(message)
                anchorByTxId[txId] = anchorTxId
                if (txId != anchorTxId) hidden.add(txId)
            }
        }
        return Result(groups, hidden, anchorByTxId, txIdsByAnchor)
    }

    /** How many unread units [messages] are: a note's parts count once. */
    fun <M> unreadUnits(messages: Iterable<M>, accessors: Accessors<M>): Int {
        var count = 0
        val seen = HashSet<String>()
        for (message in messages) {
            if (accessors.canBePart(message)) {
                val tag = VoicePartTag.sniff(accessors.content(message))
                if (tag != null && !seen.add(key(accessors.owner(message), tag))) continue
            }
            count++
        }
        return count
    }

    /** True when [message] is a part and [others] already holds another part of the same note
     *  (same owner, id and count): it is not news of its own - no unread bump, no banner. */
    fun <M> isLaterPart(message: M, others: Iterable<M>, accessors: Accessors<M>): Boolean {
        if (!accessors.canBePart(message)) return false
        val tag = VoicePartTag.sniff(accessors.content(message)) ?: return false
        val owner = accessors.owner(message)
        val wanted = key(owner, tag)
        val txId = accessors.txId(message)
        return others.any { other ->
            accessors.txId(other) != txId &&
                accessors.canBePart(other) &&
                accessors.owner(other) == owner &&
                VoicePartTag.sniff(accessors.content(other))?.let { key(owner, it) } == wanted
        }
    }

    /** A key for a decrypted part's text from [owner] ("sender|id|count"), as the notification
     *  paths match parts; null for anything that is not a tagged part (iOS NotificationService
     *  `voicePartKey`). */
    fun partKey(text: String?, owner: String): String? =
        VoicePartTag.sniff(text)?.let { key(owner.lowercase(), it) }
}

/**
 * Payload sizes of a voice note's or photo's parts on chain, in the binary media frame (iOS
 * 7c5c22e3): 9 bytes of magic and length, ~142 bytes of header JSON with a part tag (~100
 * without), then the raw bytes - for the live fee of a recording or ready note.
 */
object MediaPartPayload {
    private fun header(tagged: Boolean) = if (tagged) 142 else 100

    /** `kchat:1:comm:` - ECIES then base64 (iOS `estimateMessageFee` on the framed part). */
    fun oneToOne(rawBytes: Int, tagged: Boolean): Int =
        MessageProtocol.estimatedCommPayloadSize(9 + header(tagged) + rawBytes)

    /** `gcomm` - ChaCha20-Poly1305 (+16) as hex, plus ~391 of fixed fields (iOS
     *  `estimatedGroupVoiceWirePayloadSize`, which always counts the tagged header). */
    fun group(rawBytes: Int): Int = (9 + 142 + rawBytes + 16) * 2 + 391

    /** The fee of parts of [partSizes] bytes at [feeRate] sompi a gram, one input each. */
    fun feeSompi(partSizes: List<Int>, forGroup: Boolean, feeRate: Long?): Long {
        val tagged = partSizes.size > 1
        return partSizes.sumOf { raw ->
            val payload = if (forGroup) group(raw) else oneToOne(raw, tagged)
            KaspaMass.calculateFee(KaspaMass.calculateMass(numInputs = 1, outputScriptLens = listOf(34), payloadSize = payload), feeRate)
        }
    }
}
