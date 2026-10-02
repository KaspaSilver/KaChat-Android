package com.kachat.app.services.kachatnames

import com.kachat.app.util.Blake3
import org.bouncycastle.crypto.digests.Blake2bDigest
import java.io.ByteArrayOutputStream

/**
 * `.kachat` names on Kaspa covenants: the transaction core (design: iOS KACHAT_NAMES.md, byte-level
 * reference: kachat-domains/README.md, source of truth: the kachat-domains Rust harness and CLI).
 * A one-to-one port of iOS `KaChat/Services/KachatNames/KachatNamesCodec.swift` (KaChat 4c2c45d).
 *
 * Everything in this package is pure value code (Kotlin, [Blake3], BouncyCastle's BLAKE2b): codecs,
 * the manifest, the version-1 transaction with its hashes and masses, and the builders. No Android
 * framework, no network. It is checked byte for byte against `KachatNamesVectors.json` (test
 * resources), written by the kachat-domains `kachat-names-vectors` generator from the CLI's own
 * builders.
 *
 * Kotlin shapes for Swift's: `Data` is [ByteArray] (classes holding one compare by content),
 * `UInt64` amounts, scores, lock times and sequences are [Long] (every value the registry uses fits
 * below 2^63), `UInt16`/`UInt32` budgets, script versions and indices are [Int].
 */
object KachatNames {

    /** Errors the core throws. Messages are for logs and developer UI; the screens map them. */
    class Failure(message: String) : Exception(message) {
        override fun equals(other: Any?): Boolean = other is Failure && other.message == message
        override fun hashCode(): Int = message.hashCode()
    }

    // Constants (rusty-kaspa a41a333, kachat-domains params)

    const val SOMPI_PER_KAS: Long = 100_000_000L
    const val YEAR_MS: Long = 31_536_000_000L
    /** rusty-kaspa `LOCK_TIME_THRESHOLD`: lock times below it are DAA scores, above unix ms. */
    const val LOCK_TIME_THRESHOLD: Long = 500_000_000_000L
    /** `"kachat-commit:v1"`, the commit hash domain. */
    val COMMIT_DOMAIN: ByteArray get() = "kachat-commit:v1".toByteArray(Charsets.UTF_8)
    /** Value of a commit UTXO (returned at registration). */
    const val COMMIT_VALUE: Long = 20_000_000L
    /** Change below this is not worth a UTXO (its storage mass alone would outweigh it). */
    const val MIN_CHANGE: Long = 20_000_000L
    /** The builders aim for at least this much change. */
    const val TARGET_CHANGE: Long = 100_000_000L
    /** Relay floor after Toccata: 100 sompi per gram of max(compute, normalized transient). */
    const val MIN_FEERATE: Double = 100.0
    /** register and renew sum at most 8 inputs and 8 outputs (the contracts' bounded loops). */
    const val MAX_INPUTS_FEE_ENTRY = 8
    /** Every other operation: keep transactions small anyway. */
    const val MAX_INPUTS = 24
    /** The highest listing price the name contract accepts. */
    const val MAX_LIST_PRICE: Long = 2_900_000_000_000_000_000L
    val ZERO32: ByteArray get() = ByteArray(32)
    val FF32: ByteArray get() = ByteArray(32) { 0xff.toByte() }
    const val SIGHASH_ALL: Byte = 0x01

    // Hex

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    fun hex(data: ByteArray): String {
        val out = CharArray(data.size * 2)
        for (i in data.indices) {
            val b = data[i].toInt() and 0xff
            out[2 * i] = HEX_DIGITS[b ushr 4]
            out[2 * i + 1] = HEX_DIGITS[b and 0x0f]
        }
        return String(out)
    }

    fun unhex(string: String): ByteArray {
        if (string.length % 2 != 0) throw Failure("odd-length hex")
        fun nibble(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> throw Failure("bad hex digit")
        }
        return ByteArray(string.length / 2) { i -> ((nibble(string[2 * i]) shl 4) or nibble(string[2 * i + 1])).toByte() }
    }

    fun unhex32(string: String): ByteArray {
        val d = unhex(string)
        if (d.size != 32) throw Failure("expected 32 hex bytes")
        return d
    }

    // Little-endian helpers

    fun le16(v: Int): ByteArray = byteArrayOf(v.toByte(), (v ushr 8).toByte())
    fun le32(v: Int): ByteArray = ByteArray(4) { (v ushr (8 * it)).toByte() }
    fun le64(v: Long): ByteArray = ByteArray(8) { (v ushr (8 * it)).toByte() }

    /** Unsigned lexicographic order of byte strings (Swift's `Data.lexicographicallyPrecedes`). */
    fun precedes(a: ByteArray, b: ByteArray): Boolean {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a[i].toInt() and 0xff
            val y = b[i].toInt() and 0xff
            if (x != y) return x < y
        }
        return a.size < b.size
    }

    /** Whether [needle] occurs in [haystack] (Swift's `Data.range(of:) != nil`). */
    fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    // Hashes

    fun blake3(data: ByteArray): ByteArray = Blake3.hash(data)

    /** Unkeyed BLAKE2b-256 (the P2SH script hash). */
    fun blake2b256(data: ByteArray): ByteArray {
        val d = Blake2bDigest(null, 32, null, null)
        d.update(data, 0, data.size)
        return ByteArray(32).also { d.doFinal(it, 0) }
    }

    /** rusty-kaspa `blake2b_hasher!` (keyed BLAKE2b-256, the domain string as the key). */
    fun blake2bKeyed(domain: String, data: ByteArray): ByteArray {
        val d = Blake2bDigest(domain.toByteArray(Charsets.UTF_8), 32, null, null)
        d.update(data, 0, data.size)
        return ByteArray(32).also { d.doFinal(it, 0) }
    }

    /** rusty-kaspa `blake3_hasher!` (keyed BLAKE3, the domain string zero padded to 32 bytes). */
    fun blake3Keyed(domain: String, data: ByteArray): ByteArray = Blake3.domain(domain).update(data).finalize()

    /** A little-endian byte writer for preimages and scripts. */
    internal class Writer {
        private val out = ByteArrayOutputStream()
        fun u8(v: Int): Writer { out.write(v); return this }
        fun le16(v: Int): Writer { out.write(KachatNames.le16(v)); return this }
        fun le32(v: Int): Writer { out.write(KachatNames.le32(v)); return this }
        fun le64(v: Long): Writer { out.write(KachatNames.le64(v)); return this }
        fun bytes(b: ByteArray): Writer { out.write(b); return this }
        fun toByteArray(): ByteArray = out.toByteArray()
    }

    // Names

    object Codec {
        /** What a person types, made canonical: trimmed, lowercased, `.kachat` dropped. */
        fun normalize(raw: String): String {
            var s = raw.trim().lowercase()
            if (s.endsWith(".kachat")) s = s.dropLast(".kachat".length)
            return s
        }

        /** The gap's rule: `a-z 0-9 -`, 1..32 bytes, no hyphen at either end. */
        fun validate(name: String) {
            val b = name.toByteArray(Charsets.UTF_8)
            if (b.isEmpty() || b.size > 32) throw Failure("a name is 1..32 characters")
            for (c in b) {
                val x = c.toInt() and 0xff
                val ok = (x in 0x61..0x7a) || (x in 0x30..0x39) || x == 0x2d
                if (!ok) throw Failure("a name is a-z, 0-9 and '-' only")
            }
            if (b.first().toInt() == 0x2d || b.last().toInt() == 0x2d) throw Failure("a name cannot start or end with '-'")
        }

        fun isValid(name: String): Boolean = runCatching { validate(name) }.isSuccess

        /** `key = blake3(name)`. */
        fun key(name: String): ByteArray = blake3(name.toByteArray(Charsets.UTF_8))

        /** The name zero padded to 32 bytes (the name state field). */
        fun padded(name: String): ByteArray = name.toByteArray(Charsets.UTF_8).copyOf(32)

        /** The name in a padded field (bytes up to the first zero). */
        fun unpadded(field: ByteArray): String {
            val end = field.indexOfFirst { it.toInt() == 0 }.let { if (it < 0) field.size else it }
            return String(field, 0, end, Charsets.UTF_8)
        }

        /** Price tier index for a name of [length] bytes: 1, 2, 3, 4, 5+. */
        fun tier(length: Int): Int = minOf(maxOf(length, 1), 5) - 1

        // Commit

        /** `blake3("kachat-commit:v1" || name || ownerKey || salt)`. */
        fun commitment(name: String, owner: ByteArray, salt: ByteArray): ByteArray =
            Blake3()
                .update(COMMIT_DOMAIN)
                .update(name.toByteArray(Charsets.UTF_8))
                .update(owner)
                .update(salt)
                .finalize()

        /** `0x20 <c> OP_DROP 0x20 <ownerKey> OP_CHECKSIG` (68 bytes). */
        fun commitRedeem(commitment: ByteArray, owner: ByteArray): ByteArray =
            Writer().u8(0x20).bytes(commitment).u8(0x75).u8(0x20).bytes(owner).u8(0xac).toByteArray()

        // Integers

        /** 8-byte little-endian sign-magnitude (state ints, template part lengths). */
        fun num8(v: Long): ByteArray {
            require(v != Long.MIN_VALUE) { "num8 of Long.MIN_VALUE" }
            val out = le64(if (v < 0) -v else v)
            if (v < 0) out[7] = (out[7].toInt() or 0x80).toByte()
            return out
        }

        fun decodeNum8(d: ByteArray): Long {
            if (d.size != 8) throw Failure("state int must be 8 bytes")
            return scriptNum(d)
        }

        /** Minimal script number bytes (rusty-kaspa `serialize_i64(v, None)`); empty for 0. */
        fun minimalNumber(v: Long): ByteArray {
            require(v != Long.MIN_VALUE)
            var magnitude = if (v < 0) -v else v
            val out = ByteArrayOutputStream()
            while (magnitude > 0) {
                out.write((magnitude and 0xff).toInt())
                magnitude = magnitude ushr 8
            }
            val b = out.toByteArray()
            if (b.isEmpty()) return b
            return if (b.last().toInt() and 0x80 != 0) {
                b + byteArrayOf(if (v < 0) 0x80.toByte() else 0x00)
            } else {
                if (v < 0) b[b.size - 1] = (b[b.size - 1].toInt() or 0x80).toByte()
                b
            }
        }

        /** Little-endian sign-magnitude bytes -> Long. */
        fun scriptNum(b: ByteArray): Long {
            if (b.isEmpty()) return 0
            if (b.size > 8) throw Failure("script number longer than 8 bytes")
            var v = 0L
            for (k in b.indices) {
                val x = if (k == b.size - 1) b[k].toInt() and 0x7f else b[k].toInt() and 0xff
                v = v or (x.toLong() shl (8 * k))
            }
            val negative = b[b.size - 1].toInt() and 0x80 != 0
            return if (negative) -v else v
        }

        // States

        /** Gap state, 66 bytes: `0x20 lo 0x20 hi`. */
        fun gapState(lo: ByteArray, hi: ByteArray): ByteArray =
            Writer().u8(0x20).bytes(lo).u8(0x20).bytes(hi).toByteArray()

        /** Name state, 117 bytes: `0x20 key 0x20 name 0x20 owner 0x08 price 0x08 expiresAt`. */
        fun nameState(f: NameFields): ByteArray =
            Writer()
                .u8(0x20).bytes(f.key)
                .u8(0x20).bytes(f.paddedName)
                .u8(0x20).bytes(f.owner)
                .u8(0x08).bytes(num8(f.price))
                .u8(0x08).bytes(num8(f.expiresAt))
                .toByteArray()

        /** Offer state, 75 bytes: `0x20 key 0x20 buyer 0x08 refundAfter`. */
        fun offerState(f: OfferFields): ByteArray =
            Writer()
                .u8(0x20).bytes(f.key)
                .u8(0x20).bytes(f.buyer)
                .u8(0x08).bytes(num8(f.refundAfter))
                .toByteArray()

        /** `(lo, hi)` of a gap state. */
        fun decodeGapState(s: ByteArray): Pair<ByteArray, ByteArray> {
            if (s.size != 66 || s[0].toInt() != 0x20 || s[33].toInt() != 0x20) throw Failure("not a gap state")
            return s.copyOfRange(1, 33) to s.copyOfRange(34, 66)
        }

        fun decodeNameState(s: ByteArray): NameFields {
            if (s.size != 117 || s[0].toInt() != 0x20 || s[33].toInt() != 0x20 || s[66].toInt() != 0x20 ||
                s[99].toInt() != 0x08 || s[108].toInt() != 0x08
            ) {
                throw Failure("not a name state")
            }
            return NameFields(
                key = s.copyOfRange(1, 33), paddedName = s.copyOfRange(34, 66), owner = s.copyOfRange(67, 99),
                price = decodeNum8(s.copyOfRange(100, 108)), expiresAt = decodeNum8(s.copyOfRange(109, 117))
            )
        }

        fun decodeOfferState(s: ByteArray): OfferFields {
            if (s.size != 75 || s[0].toInt() != 0x20 || s[33].toInt() != 0x20 || s[66].toInt() != 0x08) {
                throw Failure("not an offer state")
            }
            return OfferFields(key = s.copyOfRange(1, 33), buyer = s.copyOfRange(34, 66), refundAfter = decodeNum8(s.copyOfRange(67, 75)))
        }

        // Scripts

        /** `OP_BLAKE2B <blake2b-256(redeem)> OP_EQUAL` (rusty-kaspa `pay_to_script_hash_script`). */
        fun p2shScript(redeem: ByteArray): ByteArray =
            Writer().u8(0xaa).u8(0x20).bytes(blake2b256(redeem)).u8(0x87).toByteArray()

        /** Schnorr P2PK: `0x20 <x-only key> OP_CHECKSIG`. */
        fun p2pkScript(xonly: ByteArray): ByteArray = Writer().u8(0x20).bytes(xonly).u8(0xac).toByteArray()

        /** The x-only key of a Schnorr P2PK script, null for anything else. */
        fun p2pkKey(script: ByteArray): ByteArray? {
            if (script.size != 34 || script[0].toInt() != 0x20 || (script[33].toInt() and 0xff) != 0xac) return null
            return script.copyOfRange(1, 33)
        }

        /** Canonical minimal push (rusty-kaspa `ScriptBuilder::add_data`). */
        fun pushData(data: ByteArray): ByteArray {
            val n = data.size
            if (n == 0) return byteArrayOf(0x00)
            if (n == 1) {
                val v = data[0].toInt() and 0xff
                if (v in 1..16) return byteArrayOf((0x50 + v).toByte())
                if (v == 0x81) return byteArrayOf(0x4f)
            }
            val w = Writer()
            when {
                n <= 75 -> w.u8(n)
                n <= 0xff -> w.u8(0x4c).u8(n)
                n <= 0xffff -> w.u8(0x4d).le16(n)
                else -> w.u8(0x4e).le32(n)
            }
            return w.bytes(data).toByteArray()
        }

        /**
         * A script integer (rusty-kaspa `ScriptBuilder::add_i64`): OP_0, OP_1NEGATE, OP_1..OP_16,
         * else a minimal sign-magnitude push.
         */
        fun pushInt(v: Long): ByteArray {
            if (v == 0L) return byteArrayOf(0x00)
            if (v == -1L) return byteArrayOf(0x4f)
            if (v in 1..16) return byteArrayOf((0x50 + v).toByte())
            return pushData(minimalNumber(v))
        }

        /** Every push of a push-only script, as bytes (OP_0 -> [], OP_n -> [n], OP_1NEGATE -> [0x81]). */
        fun parsePushes(script: ByteArray): List<ByteArray> {
            val out = ArrayList<ByteArray>()
            var i = 0
            fun take(n: Int): ByteArray {
                if (n < 0 || i + n > script.size) throw Failure("truncated push")
                val r = script.copyOfRange(i, i + n)
                i += n
                return r
            }
            while (i < script.size) {
                val op = script[i].toInt() and 0xff
                i += 1
                when (op) {
                    0x00 -> out.add(ByteArray(0))
                    in 0x01..0x4b -> out.add(take(op))
                    0x4c -> {
                        val n = take(1)[0].toInt() and 0xff
                        out.add(take(n))
                    }
                    0x4d -> {
                        val l = take(2)
                        out.add(take((l[0].toInt() and 0xff) or ((l[1].toInt() and 0xff) shl 8)))
                    }
                    0x4e -> {
                        val l = take(4)
                        val n = (l[0].toInt() and 0xff) or ((l[1].toInt() and 0xff) shl 8) or
                            ((l[2].toInt() and 0xff) shl 16) or ((l[3].toInt() and 0xff) shl 24)
                        out.add(take(n))
                    }
                    0x4f -> out.add(byteArrayOf(0x81.toByte()))
                    in 0x51..0x60 -> out.add(byteArrayOf((op - 0x50).toByte()))
                    else -> throw Failure("not a push-only script (opcode 0x%02x)".format(op))
                }
            }
            return out
        }

        // Templates and covenant ids

        /** silverscript `template_hash`: `blake3(num8(|prefix|) || prefix || num8(|suffix|) || suffix)`. */
        fun templateHash(prefix: ByteArray, suffix: ByteArray): ByteArray =
            Blake3()
                .update(num8(prefix.size.toLong()))
                .update(prefix)
                .update(num8(suffix.size.toLong()))
                .update(suffix)
                .finalize()

        /** rusty-kaspa `covenant_id(outpoint, authorized outputs)` (KIP-20); pairs are (output index, output). */
        fun covenantId(outpoint: Outpoint, authorized: List<Pair<Int, TxOutput>>): ByteArray {
            val w = Writer().bytes(outpoint.txid).le32(outpoint.index).le64(authorized.size.toLong())
            for ((index, o) in authorized) {
                w.le32(index).le64(o.value).le16(o.scriptVersion).le64(o.script.size.toLong()).bytes(o.script)
            }
            return blake2bKeyed("CovenantID", w.toByteArray())
        }

        // Payload markers (KACHAT_NAMES_INDEXER.md B4)

        /** `kchat:1:name:<op>:<name>`: informational, on every name transaction except commits. */
        fun namePayload(op: String, name: String): ByteArray = "kchat:1:name:$op:$name".toByteArray(Charsets.UTF_8)

        /** `kchat:1:offer:<keyHex>:<buyerXonlyHex>:<refundAfterDaa>`: how an indexer finds offers. */
        fun offerPayload(f: OfferFields): ByteArray =
            "kchat:1:offer:${hex(f.key)}:${hex(f.buyer)}:${f.refundAfter}".toByteArray(Charsets.UTF_8)

        /** `kchat:1:profile:<json>`: an address profile record (KACHAT_NAMES.md section 7). */
        fun profilePayload(json: ByteArray): ByteArray = "kchat:1:profile:".toByteArray(Charsets.UTF_8) + json

        const val MAX_PROFILE_JSON_BYTES = 2048
    }
}

// Typed states

class NameFields(
    val key: ByteArray,
    val paddedName: ByteArray,
    val owner: ByteArray,
    val price: Long,
    val expiresAt: Long
) {
    constructor(name: String, owner: ByteArray, price: Long, expiresAt: Long) :
        this(KachatNames.Codec.key(name), KachatNames.Codec.padded(name), owner, price, expiresAt)

    val name: String get() = KachatNames.Codec.unpadded(paddedName)
    val encoded: ByteArray get() = KachatNames.Codec.nameState(this)

    /** transfer / buy: new owner, listing cleared, expiry kept. */
    fun withOwner(owner: ByteArray): NameFields = NameFields(key, paddedName, owner, 0, expiresAt)

    fun withPrice(price: Long): NameFields = NameFields(key, paddedName, owner, price, expiresAt)

    fun withExpiry(expiresAt: Long): NameFields = NameFields(key, paddedName, owner, price, expiresAt)

    override fun equals(other: Any?): Boolean =
        other is NameFields && key.contentEquals(other.key) && paddedName.contentEquals(other.paddedName) &&
            owner.contentEquals(other.owner) && price == other.price && expiresAt == other.expiresAt

    override fun hashCode(): Int =
        listOf(key.contentHashCode(), paddedName.contentHashCode(), owner.contentHashCode(), price.hashCode(), expiresAt.hashCode()).hashCode()

    override fun toString(): String =
        "NameFields(name=$name, owner=${KachatNames.hex(owner)}, price=$price, expiresAt=$expiresAt)"
}

class OfferFields(val key: ByteArray, val buyer: ByteArray, val refundAfter: Long) {
    val encoded: ByteArray get() = KachatNames.Codec.offerState(this)

    override fun equals(other: Any?): Boolean =
        other is OfferFields && key.contentEquals(other.key) && buyer.contentEquals(other.buyer) && refundAfter == other.refundAfter

    override fun hashCode(): Int = listOf(key.contentHashCode(), buyer.contentHashCode(), refundAfter.hashCode()).hashCode()

    override fun toString(): String =
        "OfferFields(key=${KachatNames.hex(key)}, buyer=${KachatNames.hex(buyer)}, refundAfter=$refundAfter)"
}
