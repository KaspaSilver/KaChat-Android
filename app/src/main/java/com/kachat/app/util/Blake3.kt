package com.kachat.app.util

/**
 * BLAKE3 in pure Kotlin: the default hash and the keyed hash, any input length, one-shot or
 * incremental, any output length (the extendable output). A straight port of the BLAKE3
 * reference implementation (`reference_impl.rs` in github.com/BLAKE3-team/BLAKE3), as iOS
 * `KaChat/Utilities/Blake3.swift` (KaChat 5e7e49e) is: chunks of 1024 bytes, a stack of chaining
 * values merged as the tree grows, ROOT on the last compression. iOS only ever reads 32 bytes;
 * [finalize] also takes a longer length (output blocks with an incrementing counter, as in the
 * reference) so this stays a complete BLAKE3.
 *
 * Used by the `.kachat` name core: `key = blake3(name)`, the commit hash, contract template
 * hashes, and rusty-kaspa's keyed BLAKE3 hashers (`PayloadDigest`, `TransactionRest`,
 * `TransactionV1Id`, keyed with their domain string zero-padded to 32 bytes) for v1 tx ids.
 * Checked against the official BLAKE3 test vectors (`Blake3Test`).
 *
 * Not thread safe: one hasher per thread (the one-shot helpers make their own).
 */
class Blake3 private constructor(private val key: IntArray, private val flags: Int) {

    private var chunk = ChunkState(key, 0L, flags)
    private val cvStack = ArrayList<IntArray>()

    /** The default (unkeyed) hash. */
    constructor() : this(IV.copyOf(), 0)

    private fun addChunkCV(newCV: IntArray, totalChunks: Long) {
        var cv = newCV
        var total = totalChunks
        while (total and 1L == 0L) {
            val left = cvStack.removeAt(cvStack.size - 1)
            cv = parentOutput(left, cv, key, flags).chainingValue()
            total = total ushr 1
        }
        cvStack.add(cv)
    }

    fun update(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Blake3 {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) { "range outside the input" }
        var pos = offset
        val end = offset + length
        while (pos < end) {
            if (chunk.length() == CHUNK_LEN) {
                val cv = chunk.output().chainingValue()
                val total = chunk.chunkCounter + 1
                addChunkCV(cv, total)
                chunk = ChunkState(key, total, flags)
            }
            val take = minOf(CHUNK_LEN - chunk.length(), end - pos)
            chunk.update(data, pos, take)
            pos += take
        }
        return this
    }

    /**
     * The digest, [outLength] bytes (32 by default). The hasher can keep being updated
     * afterwards (as in the reference).
     */
    fun finalize(outLength: Int = OUT_LENGTH): ByteArray {
        require(outLength >= 0) { "negative output length" }
        var output = chunk.output()
        var remaining = cvStack.size
        while (remaining > 0) {
            remaining -= 1
            output = parentOutput(cvStack[remaining], output.chainingValue(), key, flags)
        }
        return output.rootBytes(outLength)
    }

    // Output and chunk state

    private class Output(
        val inputCV: IntArray,
        val blockWords: IntArray,
        val counter: Long,
        val blockLen: Int,
        val flags: Int
    ) {
        fun chainingValue(): IntArray = compress(inputCV, blockWords, counter, blockLen, flags).copyOf(8)

        fun rootBytes(outLength: Int): ByteArray {
            val out = ByteArray(outLength)
            var pos = 0
            var blockCounter = 0L
            while (pos < outLength) {
                val w = compress(inputCV, blockWords, blockCounter, blockLen, flags or ROOT)
                for (word in w) {
                    for (shift in 0 until 32 step 8) {
                        if (pos == outLength) return out
                        out[pos++] = (word ushr shift).toByte()
                    }
                }
                blockCounter += 1
            }
            return out
        }
    }

    private class ChunkState(key: IntArray, val chunkCounter: Long, private val flags: Int) {
        var cv: IntArray = key
        private val block = ByteArray(BLOCK_LEN)
        private var blockLength = 0
        private var blocksCompressed = 0

        fun length(): Int = BLOCK_LEN * blocksCompressed + blockLength

        private fun startFlag(): Int = if (blocksCompressed == 0) CHUNK_START else 0

        fun update(input: ByteArray, offset: Int, length: Int) {
            var pos = offset
            val end = offset + length
            while (pos < end) {
                if (blockLength == BLOCK_LEN) {
                    val w = words(block, BLOCK_LEN)
                    cv = compress(cv, w, chunkCounter, BLOCK_LEN, flags or startFlag()).copyOf(8)
                    blocksCompressed += 1
                    blockLength = 0
                }
                val take = minOf(BLOCK_LEN - blockLength, end - pos)
                System.arraycopy(input, pos, block, blockLength, take)
                blockLength += take
                pos += take
            }
        }

        fun output(): Output = Output(
            inputCV = cv,
            blockWords = words(block, blockLength),
            counter = chunkCounter,
            blockLen = blockLength,
            flags = flags or startFlag() or CHUNK_END
        )
    }

    companion object {
        const val OUT_LENGTH = 32

        private val IV = intArrayOf(
            0x6A09E667, 0xBB67AE85.toInt(), 0x3C6EF372, 0xA54FF53A.toInt(),
            0x510E527F, 0x9B05688C.toInt(), 0x1F83D9AB, 0x5BE0CD19
        )
        private val MSG_PERMUTATION = intArrayOf(2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8)

        private const val CHUNK_START = 1 shl 0
        private const val CHUNK_END = 1 shl 1
        private const val PARENT = 1 shl 2
        private const val ROOT = 1 shl 3
        private const val KEYED_HASH = 1 shl 4

        private const val BLOCK_LEN = 64
        private const val CHUNK_LEN = 1024

        /** The keyed hash (`blake3::Hasher::new_keyed`); [key] is exactly 32 bytes. */
        fun keyed(key: ByteArray): Blake3 {
            val k = keyWords(key)
            return Blake3(k, KEYED_HASH)
        }

        /** rusty-kaspa's `blake3_hasher!` domains: the domain string as the key, zero padded to 32. */
        fun domain(domain: String): Blake3 {
            val d = domain.toByteArray(Charsets.UTF_8)
            require(d.size <= 32) { "BLAKE3 domain longer than its key" }
            return keyed(d.copyOf(32))
        }

        fun hash(data: ByteArray, outLength: Int = OUT_LENGTH): ByteArray = Blake3().update(data).finalize(outLength)

        fun keyedHash(key: ByteArray, data: ByteArray, outLength: Int = OUT_LENGTH): ByteArray =
            keyed(key).update(data).finalize(outLength)

        // Compression

        private fun g(s: IntArray, a: Int, b: Int, c: Int, d: Int, mx: Int, my: Int) {
            s[a] = s[a] + s[b] + mx
            s[d] = (s[d] xor s[a]).rotateRight(16)
            s[c] = s[c] + s[d]
            s[b] = (s[b] xor s[c]).rotateRight(12)
            s[a] = s[a] + s[b] + my
            s[d] = (s[d] xor s[a]).rotateRight(8)
            s[c] = s[c] + s[d]
            s[b] = (s[b] xor s[c]).rotateRight(7)
        }

        private fun round(s: IntArray, m: IntArray) {
            g(s, 0, 4, 8, 12, m[0], m[1])
            g(s, 1, 5, 9, 13, m[2], m[3])
            g(s, 2, 6, 10, 14, m[4], m[5])
            g(s, 3, 7, 11, 15, m[6], m[7])
            g(s, 0, 5, 10, 15, m[8], m[9])
            g(s, 1, 6, 11, 12, m[10], m[11])
            g(s, 2, 7, 8, 13, m[12], m[13])
            g(s, 3, 4, 9, 14, m[14], m[15])
        }

        /** The full 16-word output of one compression. */
        private fun compress(cv: IntArray, blockWords: IntArray, counter: Long, blockLen: Int, flags: Int): IntArray {
            val s = intArrayOf(
                cv[0], cv[1], cv[2], cv[3], cv[4], cv[5], cv[6], cv[7],
                IV[0], IV[1], IV[2], IV[3],
                counter.toInt(), (counter ushr 32).toInt(), blockLen, flags
            )
            var m = blockWords
            for (r in 0 until 7) {
                round(s, m)
                if (r < 6) {
                    val p = IntArray(16)
                    for (i in 0 until 16) p[i] = m[MSG_PERMUTATION[i]]
                    m = p
                }
            }
            for (i in 0 until 8) {
                s[i] = s[i] xor s[i + 8]
                s[i + 8] = s[i + 8] xor cv[i]
            }
            return s
        }

        /** The first [length] bytes of [bytes] as 16 little-endian words, zero padded. */
        private fun words(bytes: ByteArray, length: Int): IntArray {
            val out = IntArray(16)
            for (i in 0 until length) {
                out[i / 4] = out[i / 4] or ((bytes[i].toInt() and 0xff) shl (8 * (i % 4)))
            }
            return out
        }

        private fun keyWords(key: ByteArray): IntArray {
            require(key.size == 32) { "BLAKE3 key must be 32 bytes" }
            return words(key, 32).copyOf(8)
        }

        private fun parentOutput(left: IntArray, right: IntArray, key: IntArray, flags: Int): Output =
            Output(inputCV = key, blockWords = left + right, counter = 0L, blockLen = BLOCK_LEN, flags = PARENT or flags)
    }
}
