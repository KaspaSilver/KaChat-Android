package com.kachat.app.util

import com.kachat.app.services.RawTransaction
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * KSPT ("Kaspa Signable Partial Transaction") — the compact binary transport format KasSigner's
 * firmware scans/displays for air-gapped signing. Verified against KasSigner's own source
 * (`bootloader/src/wallet/pskt.rs`, `kassee/src/kspt.rs`) — field order and widths below must
 * match exactly, or the device either rejects the QR outright or (worse) silently misparses it.
 *
 * Wire layout (all multi-byte integers little-endian):
 * ```
 * Header:  magic(4)="KSPT"  version(1)=0x01  flags(1) [bit0: signed, bit1: has redeem script]
 * Global:  txVersion(2)  numInputs(1)  numOutputs(1)  lockTime(8)  subnetworkId(20)  gas(8)
 *          payloadLen(2)  payload(payloadLen)
 * Input:   prevTxId(32)  prevIndex(4)  amount(8)  sequence(8)  sigOpCount(1)
 *          spkVersion(2)  spkLen(1)  spkScript(spkLen)
 *          [if signed: sigLen(1)  signature(sigLen)  sighashType(1)]
 * Output:  value(8)  spkVersion(2)  spkLen(1)  spkScript(spkLen)
 * ```
 * KaChat only ever produces/consumes plain single-sig (flags bit1 unset) — the multisig/redeem-
 * script variant (KSPT v2) exists in KasSigner but isn't something this app builds or needs to
 * parse.
 */
object KsptCodec {
    val MAGIC = byteArrayOf('K'.code.toByte(), 'S'.code.toByte(), 'P'.code.toByte(), 'T'.code.toByte())
    private const val VERSION: Int = 0x01
    private const val FLAG_SIGNED = 0x01
    private const val FLAG_HAS_REDEEM_SCRIPT = 0x02

    // Matches firmware MAX_INPUTS=32 as of KasSigner v1.0.5 (was 8; devices on older
    // firmware reject >8-input — and pre-1.0.5, even >5-input — transactions).
    const val MAX_INPUTS = 32
    const val MAX_OUTPUTS = 4

    /** Starts with the 4-byte "KSPT" magic — the same check KasSigner's own frame-0 detector uses. */
    fun looksLikeKspt(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(MAGIC)

    data class UnsignedInput(
        val prevTxId: String, // 64 hex chars
        val prevIndex: Int,
        val amountSompi: Long,
        val sequence: Long,
        val sigOpCount: Int,
        val spkVersion: Int,
        val spkScriptHex: String
    )

    data class UnsignedOutput(
        val valueSompi: Long,
        val spkVersion: Int,
        val spkScriptHex: String
    )

    /**
     * Builds the unsigned KSPT bytes for the given transaction shape. Doesn't take a
     * [RawTransaction] directly — [RawTransaction.inputs] alone has no UTXO amount or
     * scriptPublicKey (those live on the selected [com.kachat.app.services.UtxoEntry] list, not
     * the tx itself), so callers assemble [UnsignedInput]/[UnsignedOutput] from their selection
     * result instead of this function re-deriving it.
     */
    fun encodeUnsigned(
        txVersion: Int,
        lockTime: Long,
        subnetworkIdHex: String,
        gas: Long,
        payloadHex: String?,
        inputs: List<UnsignedInput>,
        outputs: List<UnsignedOutput>
    ): ByteArray {
        require(inputs.size in 1..MAX_INPUTS) { "KSPT supports 1-$MAX_INPUTS inputs, got ${inputs.size}" }
        require(outputs.size in 1..MAX_OUTPUTS) { "KSPT supports 1-$MAX_OUTPUTS outputs, got ${outputs.size}" }

        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        out.write(VERSION)
        out.write(0x00) // unsigned, no redeem script

        val payloadBytes = payloadHex?.hexToBytes() ?: ByteArray(0)
        require(payloadBytes.size <= 128) { "KSPT payload must be <=128 bytes, got ${payloadBytes.size}" }

        out.writeU16Le(txVersion)
        out.write(inputs.size)
        out.write(outputs.size)
        out.writeU64Le(lockTime)
        val subnetworkBytes = subnetworkIdHex.hexToBytes()
        require(subnetworkBytes.size == 20) { "subnetworkId must be 20 bytes, got ${subnetworkBytes.size}" }
        out.write(subnetworkBytes)
        out.writeU64Le(gas)
        out.writeU16Le(payloadBytes.size)
        out.write(payloadBytes)

        inputs.forEach { input ->
            val txIdBytes = input.prevTxId.hexToBytes()
            require(txIdBytes.size == 32) { "prevTxId must be 32 bytes" }
            out.write(txIdBytes)
            out.writeU32Le(input.prevIndex)
            out.writeU64Le(input.amountSompi)
            out.writeU64Le(input.sequence)
            out.write(input.sigOpCount)
            out.writeU16Le(input.spkVersion)
            val spkBytes = input.spkScriptHex.hexToBytes()
            require(spkBytes.size <= 64) { "input scriptPublicKey must be <=64 bytes, got ${spkBytes.size}" }
            out.write(spkBytes.size)
            out.write(spkBytes)
        }

        outputs.forEach { output ->
            out.writeU64Le(output.valueSompi)
            out.writeU16Le(output.spkVersion)
            val spkBytes = output.spkScriptHex.hexToBytes()
            require(spkBytes.size <= 64) { "output scriptPublicKey must be <=64 bytes, got ${spkBytes.size}" }
            out.write(spkBytes.size)
            out.write(spkBytes)
        }

        return out.toByteArray()
    }

    data class DecodedInput(
        val prevTxId: String,
        val prevIndex: Int,
        val amountSompi: Long,
        val sequence: Long,
        val sigOpCount: Int,
        val spkVersion: Int,
        val spkScriptHex: String,
        // Present only when the payload is a signed response (flags bit0 set) and this input was
        // actually signed (sigLen > 0) — a still-unsigned input in a partially-signed response
        // round-trips with signatureHex=null.
        val signatureHex: String?,
        val sighashType: Int?
    )

    data class DecodedOutput(
        val valueSompi: Long,
        val spkVersion: Int,
        val spkScriptHex: String
    )

    data class Decoded(
        val signed: Boolean,
        val txVersion: Int,
        val lockTime: Long,
        val subnetworkIdHex: String,
        val gas: Long,
        val payloadHex: String,
        val inputs: List<DecodedInput>,
        val outputs: List<DecodedOutput>
    )

    fun decode(bytes: ByteArray): Result<Decoded> {
        return try {
            require(looksLikeKspt(bytes)) { "Not a KSPT payload (bad magic)" }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buf.position(4) // skip magic
            val version = buf.get().toInt() and 0xFF
            require(version == VERSION) { "Unsupported KSPT version $version" }
            val flags = buf.get().toInt() and 0xFF
            val signed = flags and FLAG_SIGNED != 0
            require(flags and FLAG_HAS_REDEEM_SCRIPT == 0) { "Multisig/redeem-script KSPT isn't supported" }

            val txVersion = buf.readU16()
            val numInputs = buf.get().toInt() and 0xFF
            val numOutputs = buf.get().toInt() and 0xFF
            require(numInputs in 1..MAX_INPUTS) { "Invalid input count $numInputs" }
            require(numOutputs in 1..MAX_OUTPUTS) { "Invalid output count $numOutputs" }
            val lockTime = buf.long
            val subnetworkBytes = ByteArray(20).also { buf.get(it) }
            val gas = buf.long
            val payloadLen = buf.readU16()
            require(payloadLen <= 128) { "Invalid payload length $payloadLen" }
            val payloadBytes = ByteArray(payloadLen).also { buf.get(it) }

            val inputs = (0 until numInputs).map {
                val txIdBytes = ByteArray(32).also { arr -> buf.get(arr) }
                val prevIndex = buf.int
                val amount = buf.long
                val sequence = buf.long
                val sigOpCount = buf.get().toInt() and 0xFF
                val spkVersion = buf.readU16()
                val spkLen = buf.get().toInt() and 0xFF
                require(spkLen <= 64) { "Invalid input scriptPublicKey length $spkLen" }
                val spkBytes = ByteArray(spkLen).also { arr -> buf.get(arr) }
                var sigHex: String? = null
                var sighashType: Int? = null
                if (signed) {
                    val sigLen = buf.get().toInt() and 0xFF
                    require(sigLen <= 64) { "Invalid signature length $sigLen" }
                    if (sigLen > 0) {
                        val sigBytes = ByteArray(sigLen).also { arr -> buf.get(arr) }
                        sigHex = sigBytes.toHexString()
                        sighashType = buf.get().toInt() and 0xFF
                    }
                }
                DecodedInput(
                    prevTxId = txIdBytes.toHexString(),
                    prevIndex = prevIndex,
                    amountSompi = amount,
                    sequence = sequence,
                    sigOpCount = sigOpCount,
                    spkVersion = spkVersion,
                    spkScriptHex = spkBytes.toHexString(),
                    signatureHex = sigHex,
                    sighashType = sighashType
                )
            }

            val outputs = (0 until numOutputs).map {
                val value = buf.long
                val spkVersion = buf.readU16()
                val spkLen = buf.get().toInt() and 0xFF
                require(spkLen <= 64) { "Invalid output scriptPublicKey length $spkLen" }
                val spkBytes = ByteArray(spkLen).also { arr -> buf.get(arr) }
                DecodedOutput(valueSompi = value, spkVersion = spkVersion, spkScriptHex = spkBytes.toHexString())
            }

            Result.success(
                Decoded(
                    signed = signed,
                    txVersion = txVersion,
                    lockTime = lockTime,
                    subnetworkIdHex = subnetworkBytes.toHexString(),
                    gas = gas,
                    payloadHex = payloadBytes.toHexString(),
                    inputs = inputs,
                    outputs = outputs
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun ByteBuffer.readU16(): Int = short.toInt() and 0xFFFF

    private fun ByteArrayOutputStream.writeU16Le(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeU32Le(value: Int) {
        for (i in 0 until 4) write((value shr (8 * i)) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeU64Le(value: Long) {
        for (i in 0 until 8) write(((value shr (8 * i)) and 0xFF).toInt())
    }

    private fun String.hexToBytes(): ByteArray {
        if (isEmpty()) return ByteArray(0)
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
}
