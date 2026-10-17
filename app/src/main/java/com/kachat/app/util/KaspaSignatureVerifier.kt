package com.kachat.app.util

import com.kachat.app.services.Outpoint
import com.kachat.app.services.RawInput
import com.kachat.app.services.RawOutputWithVersion
import com.kachat.app.services.RawTransaction
import com.kachat.app.services.ScriptPublicKeyWithVersion
import com.kachat.app.services.TransactionResponse

/**
 * Schnorr signature check of a transaction read from the REST API - the payment sync's guard on
 * incoming payments, a port of iOS `KasiaTransactionBuilder.verifyTransactionSignatures`
 * (KaChatTransactionBuilder.swift, "Schnorr Signature Verification (REST API path)") and of the
 * caller's input mapping in `fetchPaymentsFromKaspaAPI`.
 *
 * Like iOS it answers "definitively invalid" only: a transaction it cannot check (a field
 * missing, a script it cannot rebuild) passes, an input whose signature script or spent script
 * is not the standard Schnorr P2PK form is skipped, and only a signature that does not verify
 * against the rebuilt sighash fails the transaction. The sighash is the signer's own
 * ([KaspaTransactionSigner.calculateSighash]), the curve maths [Schnorr.verify].
 */
object KaspaSignatureVerifier {

    /** One input as the REST API resolves it (iOS `VerificationInput`). */
    data class VerificationInput(
        val previousOutpointHash: String,
        val previousOutpointIndex: Int,
        val signatureScript: String,
        val previousOutpointAddress: String,
        val previousOutpointAmount: Long,
        val sequence: Long,
        val sigOpCount: Int,
    )

    /** One output (iOS `VerificationOutput`). */
    data class VerificationOutput(
        val amount: Long,
        val scriptPublicKey: String,
    )

    /**
     * The index of the first input whose signature is definitively invalid, or null when every
     * checkable signature verifies or the transaction cannot be checked at all (iOS
     * `verifyTransactionSignatures` returning true).
     */
    fun invalidInputIndex(
        inputs: List<VerificationInput>,
        outputs: List<VerificationOutput>,
        version: Int,
        lockTime: Long,
        subnetworkId: ByteArray,
        gas: Long,
        payload: ByteArray,
    ): Int? {
        if (inputs.isEmpty()) return null

        // The transaction as signed: empty signature scripts (the sighash never covers them).
        val rawInputs = inputs.map { input ->
            RawInput(
                previousOutpoint = Outpoint(input.previousOutpointHash, input.previousOutpointIndex),
                signatureScript = "",
                sequence = input.sequence,
                sigOpCount = input.sigOpCount,
            )
        }
        val rawOutputs = ArrayList<RawOutputWithVersion>(outputs.size)
        for (output in outputs) {
            val script = hexToBytes(output.scriptPublicKey)
            if (script == null || script.isEmpty()) return null // cannot verify without script data
            rawOutputs += RawOutputWithVersion(
                amount = output.amount,
                scriptPublicKey = ScriptPublicKeyWithVersion(scriptPublicKey = toHex(script), version = 0),
            )
        }
        val rawTx = RawTransaction(
            version = version,
            inputs = rawInputs,
            outputs = rawOutputs,
            lockTime = lockTime,
            subnetworkId = toHex(subnetworkId),
            gas = gas,
            payload = toHex(payload),
        )

        for ((index, input) in inputs.withIndex()) {
            // 1. 0x41 (push 65 bytes) + 64-byte Schnorr signature + 0x01 (SIGHASH_ALL)
            val sigScript = hexToBytes(input.signatureScript)
            if (sigScript == null || sigScript.size != 66 ||
                sigScript[0] != 0x41.toByte() || sigScript[65] != 0x01.toByte()
            ) continue // non-standard signature script; this input is not checked
            val signature = sigScript.copyOfRange(1, 65)

            // 2. The spent output's script, rebuilt from its resolved address.
            val utxoScript = try {
                hexToBytes(KaspaAddress.getScriptPublicKey(input.previousOutpointAddress))
            } catch (e: Exception) {
                null
            } ?: return null // cannot derive the script: skip the whole check

            // 3. P2PK: <push-32> <32-byte x-only key> OP_CHECKSIG
            if (utxoScript.size < 34 || utxoScript[0] != 32.toByte() ||
                utxoScript[utxoScript.size - 1] != 0xAC.toByte()
            ) continue
            val xOnlyPubKey = utxoScript.copyOfRange(1, 33)

            // 4. The sighash this input signed.
            val sighash = try {
                KaspaTransactionSigner.calculateSighash(rawTx, index, input.previousOutpointAmount, toHex(utxoScript))
            } catch (e: Exception) {
                return null
            }

            // 5. Verify.
            val valid = Schnorr.verify(sighash, signature, xOnlyPubKey)
            sighash.fill(0)
            if (!valid) return index
        }
        return null
    }

    /** iOS `verifyTransactionSignatures`: false only for a definitively invalid signature. */
    fun verifyTransactionSignatures(
        inputs: List<VerificationInput>,
        outputs: List<VerificationOutput>,
        version: Int,
        lockTime: Long,
        subnetworkId: ByteArray,
        gas: Long,
        payload: ByteArray,
    ): Boolean = invalidInputIndex(inputs, outputs, version, lockTime, subnetworkId, gas, payload) == null

    /**
     * The check iOS `fetchPaymentsFromKaspaAPI` runs on a REST transaction: an input lacking any
     * of its outpoint hash, signature script, resolved address or amount is left out of the
     * rebuilt transaction (iOS compactMap); a missing sequence reads 0 and sig-op count 1, the
     * version, lock time and gas 0 (iOS `?? 0`, FlexibleUInt64 0 for a value that does not
     * parse); the subnetwork id is the transaction's (20 zero bytes when it is not hex); the
     * payload its bytes. Null when nothing fails, else the failing input's index.
     */
    fun invalidInputIndex(tx: TransactionResponse): Int? {
        // Gson leaves a field the JSON lacks null whatever its Kotlin type, hence the nullable reads.
        val inputs = (tx.inputs as List<com.kachat.app.services.TransactionInput>?).orEmpty().mapNotNull { input ->
            val hash = (input.previousOutpointHash as String?) ?: return@mapNotNull null
            val sigScript = (input.signatureScript as String?) ?: return@mapNotNull null
            val address = input.previousOutpointAddress ?: return@mapNotNull null
            val amount = input.previousOutpointAmount ?: return@mapNotNull null
            VerificationInput(
                previousOutpointHash = hash,
                previousOutpointIndex = input.previousOutpointIndex,
                signatureScript = sigScript,
                previousOutpointAddress = address,
                previousOutpointAmount = amount,
                sequence = flexibleLong(input.sequence) ?: 0L,
                sigOpCount = input.sigOpCount?.let { flexibleLong(it)?.toInt() ?: 0 } ?: 1,
            )
        }
        if (inputs.isEmpty()) return null
        val outputs = (tx.outputs as List<com.kachat.app.services.TransactionOutput>?).orEmpty().map { output ->
            VerificationOutput(amount = output.amount, scriptPublicKey = (output.scriptPublicKey as String?) ?: "")
        }
        return invalidInputIndex(
            inputs = inputs,
            outputs = outputs,
            version = tx.version?.trim()?.toIntOrNull() ?: 0,
            lockTime = tx.lockTime?.let { flexibleLong(it) ?: 0L } ?: 0L,
            subnetworkId = hexToBytes(tx.subnetworkId ?: "") ?: ByteArray(20),
            gas = tx.gas?.let { flexibleLong(it) ?: 0L } ?: 0L,
            payload = hexToBytes(tx.payload ?: "") ?: ByteArray(0),
        )
    }

    /** iOS FlexibleUInt64: a number, or a string holding one; null when absent. A present value
     *  that does not parse reads as null here and the caller picks iOS's fallback. */
    private fun flexibleLong(raw: String?): Long? = raw?.trim()?.toLongOrNull()

    /** iOS CryptoUtils.hexToData: lowercased, spaces dropped, pairs of hex digits; null on a bad
     *  digit; a trailing odd digit is ignored. */
    private fun hexToBytes(hex: String): ByteArray? {
        val clean = hex.lowercase().replace(" ", "")
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[2 * i], 16)
            val lo = Character.digit(clean[2 * i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
