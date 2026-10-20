package com.kachat.app.util

import com.kachat.app.services.RawTransaction
import com.kachat.app.services.RawInput
import com.kachat.app.services.RawOutputWithVersion
import com.kachat.app.services.UtxoEntry
import org.bouncycastle.crypto.digests.Blake2bDigest
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handles Kaspa transaction signing (Sighash calculation + Schnorr).
 *
 * Sighash layout and the keyed-Blake2b construction must match rusty-kaspa's
 * `sighash.rs` exactly (verified against the KaChat iOS reference implementation) —
 * any deviation produces a signature the network will reject.
 */
object KaspaTransactionSigner {

    // Kaspa hashes every sighash sub-component with the SAME Blake2b-256 MAC,
    // keyed with this ASCII string (not used as a personalization tag).
    private val SIGNING_KEY = "TransactionSigningHash".toByteArray(Charsets.US_ASCII)

    /**
     * [redeemScript]: a KIP-22 (quantum-safe P2SH) sender's redeem script, pushed after each
     * signature exactly as the KNS reveal spends P2SH ([signRevealInput]; iOS
     * `signTransaction(redeemScript:)`). The sighash still hashes each UTXO's own
     * scriptPublicKey - the P2SH script - never the redeem script.
     */
    fun signTransaction(
        rawTx: RawTransaction,
        utxos: List<UtxoEntry>,
        privateKey: ByteArray,
        redeemScript: ByteArray? = null
    ): RawTransaction {
        val redeemPush = redeemScript?.let { KnsInscriptionScript.canonicalPush(it) } ?: ByteArray(0)
        val signedInputs = rawTx.inputs.mapIndexed { index, input ->
            val utxo = utxos[index]
            val hash = calculateSighash(rawTx, index, utxo.utxoEntry.amount, utxo.utxoEntry.scriptPublicKey.scriptPublicKey)
            val signature = Schnorr.sign(hash, privateKey) // 64 bytes

            // signatureScript = push-65-bytes opcode (0x41) || 64-byte signature || SIGHASH_ALL type byte (0x01)
            // [|| canonicalPush(redeemScript) for a quantum-safe sender]
            val sigScript = ByteArray(66 + redeemPush.size)
            sigScript[0] = 0x41
            signature.copyInto(sigScript, 1)
            sigScript[65] = 0x01
            redeemPush.copyInto(sigScript, 66)
            input.copy(signatureScript = sigScript.toHexString())
        }

        return rawTx.copy(inputs = signedInputs)
    }

    /**
     * Signs a KNS reveal transaction's single input, which spends a P2SH commit output. The
     * sighash still hashes the commit output's real scriptPublicKey (the P2SH hash-script) — same
     * as [signTransaction] — the redeem script is never part of the sighash, only of the
     * signature script that reveals it. Verified against the iOS reference's
     * `signKNSRevealTransaction` (`KaChatTransactionBuilder.swift:1088-1131`).
     */
    fun signRevealInput(
        rawTx: RawTransaction,
        commitUtxo: UtxoEntry,
        redeemScript: ByteArray,
        privateKey: ByteArray
    ): RawTransaction {
        val hash = calculateSighash(rawTx, 0, commitUtxo.utxoEntry.amount, commitUtxo.utxoEntry.scriptPublicKey.scriptPublicKey)
        val signature = Schnorr.sign(hash, privateKey) // 64 bytes

        // signatureScript = push(sig+sighashType) || canonicalPush(redeemScript)
        val redeemPush = KnsInscriptionScript.canonicalPush(redeemScript)
        val sigScript = ByteArray(66 + redeemPush.size)
        sigScript[0] = 0x41
        signature.copyInto(sigScript, 1)
        sigScript[65] = 0x01
        redeemPush.copyInto(sigScript, 66)

        val signedInput = rawTx.inputs[0].copy(signatureScript = sigScript.toHexString())
        return rawTx.copy(inputs = listOf(signedInput))
    }

    private fun keyedBlake2b(data: ByteArray): ByteArray {
        val digest = Blake2bDigest(SIGNING_KEY, 32, null, null)
        digest.update(data, 0, data.size)
        val out = ByteArray(32)
        digest.doFinal(out, 0)
        return out
    }

    /** An output's covenant binding (transaction version 1, iOS `OutputCovenant`): which input
     *  authorizes it (u16) and the covenant it continues (32 bytes). Part of the v1 sighash; the
     *  app's own payments carry none. */
    class OutputCovenant(val authorizingInput: Int, val covenantId: ByteArray)

    /**
     * Versions 0 and 1 (rusty-kaspa consensus/core/src/hashing/sighash.rs,
     * `calc_schnorr_signature_hash`; iOS `computeSighash`, 5806362): from version 1 the sig-op
     * counts are left out (both the hash of all of them and the input's own byte), and each
     * output adds whether it carries a covenant, then its authorizing input (u16 LE) and
     * covenant id (32 bytes) when it does. [covenants] is per output (missing entries = none);
     * only the verification of received v1 transactions passes it. Every transaction the app
     * builds is version 0, so signing takes the version-0 branch, unchanged.
     */
    internal fun calculateSighash(
        tx: RawTransaction,
        inputIndex: Int,
        amount: Long,
        scriptPublicKey: String,
        covenants: List<OutputCovenant?> = emptyList(),
    ): ByteArray {
        val isV1 = tx.version >= 1
        val buffer = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)

        // version (u16 LE)
        buffer.putShort(tx.version.toShort())

        // prevOutputsHash: keyedBlake2b(txid(32) + index(u32 LE) for every input)
        val prevOutputsData = ByteArrayOutputStream()
        tx.inputs.forEach { input ->
            prevOutputsData.write(input.previousOutpoint.transactionId.hexToBytes())
            prevOutputsData.write(intToLeBytes(input.previousOutpoint.index))
        }
        buffer.put(keyedBlake2b(prevOutputsData.toByteArray()))

        // sequencesHash
        val sequencesData = ByteArrayOutputStream()
        tx.inputs.forEach { input -> sequencesData.write(longToLeBytes(input.sequence)) }
        buffer.put(keyedBlake2b(sequencesData.toByteArray()))

        // sigOpCountsHash (version 0 only)
        if (!isV1) {
            val sigOpCountsData = ByteArrayOutputStream()
            tx.inputs.forEach { input -> sigOpCountsData.write(byteArrayOf(input.sigOpCount.toByte())) }
            buffer.put(keyedBlake2b(sigOpCountsData.toByteArray()))
        }

        // This input's outpoint
        val currentInput = tx.inputs[inputIndex]
        buffer.put(currentInput.previousOutpoint.transactionId.hexToBytes())
        buffer.put(intToLeBytes(currentInput.previousOutpoint.index))

        // UTXO scriptVersion(u16 LE, always 0) + scriptLen(u64 LE) + scriptBytes
        buffer.putShort(0)
        val scriptBytes = scriptPublicKey.hexToBytes()
        buffer.putLong(scriptBytes.size.toLong())
        buffer.put(scriptBytes)

        // UTXO amount, this input's sequence, this input's sigOpCount
        buffer.putLong(amount)
        buffer.putLong(currentInput.sequence)
        if (!isV1) buffer.put(currentInput.sigOpCount.toByte()) // version 0 only

        // outputsHash: keyedBlake2b(value(u64 LE) + scriptVersion(u16 LE) + scriptLen(u64 LE) + scriptBytes, per output)
        val outputsData = ByteArrayOutputStream()
        tx.outputs.forEachIndexed { outputIndex, output ->
            outputsData.write(longToLeBytes(output.amount))
            outputsData.write(shortToLeBytes(output.scriptPublicKey.version.toShort()))
            val outScriptBytes = output.scriptPublicKey.scriptPublicKey.hexToBytes()
            outputsData.write(longToLeBytes(outScriptBytes.size.toLong()))
            outputsData.write(outScriptBytes)
            if (isV1) {
                val covenant = covenants.getOrNull(outputIndex)
                if (covenant != null) {
                    outputsData.write(1)
                    outputsData.write(shortToLeBytes(covenant.authorizingInput.toShort()))
                    outputsData.write(covenant.covenantId)
                } else {
                    outputsData.write(0)
                }
            }
        }
        buffer.put(keyedBlake2b(outputsData.toByteArray()))

        buffer.putLong(tx.lockTime)
        val subnetworkBytes = tx.subnetworkId.hexToBytes()
        buffer.put(subnetworkBytes)
        buffer.putLong(tx.gas)

        // payloadHash: zero hash iff native subnetwork with empty payload, else keyedBlake2b(len(u64 LE) + bytes)
        val payloadBytes = tx.payload?.hexToBytes() ?: ByteArray(0)
        val isNativeSubnetwork = subnetworkBytes.all { it == 0.toByte() }
        val payloadHash = if (isNativeSubnetwork && payloadBytes.isEmpty()) {
            ByteArray(32)
        } else {
            val toHash = ByteArrayOutputStream()
            toHash.write(longToLeBytes(payloadBytes.size.toLong()))
            toHash.write(payloadBytes)
            keyedBlake2b(toHash.toByteArray())
        }
        buffer.put(payloadHash)

        buffer.put(0x01.toByte()) // SIGHASH_ALL

        return keyedBlake2b(buffer.array().copyOf(buffer.position()))
    }

    private fun String.hexToBytes(): ByteArray {
        if (isEmpty()) return ByteArray(0)
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }

    private fun intToLeBytes(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }

    private fun longToLeBytes(value: Long): ByteArray {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
    }

    private fun shortToLeBytes(value: Short): ByteArray {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array()
    }
}
