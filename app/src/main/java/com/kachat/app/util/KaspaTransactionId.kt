package com.kachat.app.util

import com.kachat.app.services.RawTransaction
import org.bouncycastle.crypto.digests.Blake2bDigest
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A transaction's id, computed the way the chain computes it - before the transaction is
 * submitted anywhere.
 *
 * Needed by scheduled posts (KAPOSTS_INDEXER.md section 5.10): the post is signed now and handed
 * to the indexer to submit later, and everything after that - the entry the server stores, the
 * cancellation, the status it reports - is keyed on the id the transaction WILL have on chain.
 *
 * rusty-kaspa `consensus/core/src/hashing/tx.rs` (`id_v0`): blake2b-256 keyed with
 * `"TransactionID"` over the consensus encoding with each signature script replaced by an empty
 * var-bytes (and no sig-op byte) and no mass commitment; the payload IS included. Integers are
 * little-endian and `var_bytes` is a u64 length followed by the bytes. The id is the digest's
 * bytes in order - not reversed, which is a Bitcoin convention rather than Kaspa's.
 *
 * Checked against real on-chain transactions in KaspaTransactionIdTest, and on every KaPosts
 * submit (see KaPostsService.submitPayloadTx), which compares this against the id the node
 * hands back for free.
 */
object KaspaTransactionId {

    private val TRANSACTION_ID_KEY = "TransactionID".toByteArray(Charsets.US_ASCII)

    fun compute(tx: RawTransaction): String {
        val out = ByteArrayOutputStream()
        out.write(le(tx.version.toShort()))
        out.write(le(tx.inputs.size.toLong()))
        for (input in tx.inputs) {
            out.write(outpointTxIdBytes(input.previousOutpoint.transactionId))
            out.write(le(input.previousOutpoint.index))
            // The signature script is excluded from the ID - an empty var-bytes stands in its
            // place, and no sig-op byte follows it.
            out.write(varBytes(ByteArray(0)))
            out.write(le(input.sequence))
        }
        out.write(le(tx.outputs.size.toLong()))
        for (output in tx.outputs) {
            out.write(le(output.amount))
            out.write(le(output.scriptPublicKey.version.toShort()))
            out.write(varBytes(output.scriptPublicKey.scriptPublicKey.hexToBytes()))
            // Transaction versions from 1 carry a covenant flag per output; every transaction
            // this app builds is version 0.
            if (tx.version >= 1) out.write(0)
        }
        out.write(le(tx.lockTime))
        out.write(subnetworkBytes(tx.subnetworkId))
        out.write(le(tx.gas))
        out.write(varBytes(tx.payload?.hexToBytes() ?: ByteArray(0)))

        val digest = Blake2bDigest(TRANSACTION_ID_KEY, 32, null, null)
        val bytes = out.toByteArray()
        digest.update(bytes, 0, bytes.size)
        val id = ByteArray(32)
        digest.doFinal(id, 0)
        return id.joinToString("") { "%02x".format(it) }
    }

    private fun le(value: Short): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array()

    private fun le(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun le(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    private fun varBytes(bytes: ByteArray): ByteArray = le(bytes.size.toLong()) + bytes

    /** An outpoint's transaction id is 32 bytes; anything shorter is left-padded, as iOS does. */
    private fun outpointTxIdBytes(hex: String): ByteArray {
        val raw = hex.hexToBytes()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(0, 32)
            else -> ByteArray(32 - raw.size) + raw
        }
    }

    /** The subnetwork id is exactly 20 bytes. */
    private fun subnetworkBytes(hex: String): ByteArray {
        val raw = hex.hexToBytes()
        return when {
            raw.size == 20 -> raw
            raw.size > 20 -> raw.copyOfRange(0, 20)
            else -> raw + ByteArray(20 - raw.size)
        }
    }

    private fun String.hexToBytes(): ByteArray {
        val clean = if (length % 2 == 0) this else "0$this"
        return ByteArray(clean.length / 2) { i ->
            ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
    }
}
