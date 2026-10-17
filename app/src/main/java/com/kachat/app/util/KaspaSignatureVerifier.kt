package com.kachat.app.util

import com.kachat.app.services.Outpoint
import com.kachat.app.services.RawInput
import com.kachat.app.services.RawOutputWithVersion
import com.kachat.app.services.RawTransaction
import com.kachat.app.services.ScriptPublicKeyWithVersion
import com.kachat.app.services.TransactionInput
import com.kachat.app.services.TransactionOutput
import com.kachat.app.services.TransactionResponse

/**
 * Schnorr signature check of a transaction read from the REST API - the payment sync's guard on
 * incoming payments, a port of iOS `KasiaTransactionBuilder.verifyTransactionSignatures`
 * (KaChatTransactionBuilder.swift, "Schnorr Signature Verification (REST API path)") and of
 * `ChatService.verifyIncomingPaymentSignatures` (ChatService+Fetching.swift), as of iOS 5806362.
 *
 * Versions 0 and 1. The answer is [SignatureCheck.Valid] (every standard P2PK signature checks
 * out), [SignatureCheck.Invalid] (one is definitively wrong) or [SignatureCheck.Unknown] (it
 * can't be checked yet - a spent output not resolved, data missing): ask again later. Inputs that
 * are not the standard Schnorr P2PK form (P2SH covenant spends and the like) are not
 * signature-checked. The sighash is the signer's own ([KaspaTransactionSigner.calculateSighash]),
 * the curve maths [Schnorr.verify].
 */
object KaspaSignatureVerifier {

    sealed class SignatureCheck {
        object Valid : SignatureCheck()
        /** The signature of input [inputIndex] is wrong. */
        data class Invalid(val inputIndex: Int) : SignatureCheck()
        object Unknown : SignatureCheck()
    }

    /** One input as the REST API resolves it (iOS `VerificationInput`). The spent output's
     *  address and amount are null when neither the API nor the previous transaction gave them:
     *  only this input's own signature needs them, every other input contributes just its
     *  outpoint and sequence. */
    data class VerificationInput(
        val previousOutpointHash: String,
        val previousOutpointIndex: Int,
        val signatureScript: String,
        val previousOutpointAddress: String?,
        val previousOutpointAmount: Long?,
        val sequence: Long,
        val sigOpCount: Int,
    )

    /** One output (iOS `VerificationOutput`), with its version-1 covenant binding if any. */
    data class VerificationOutput(
        val amount: Long,
        val scriptPublicKey: String,
        val covenantAuthorizingInput: Int? = null,
        val covenantId: String? = null,
    )

    /** iOS `verifyTransactionSignatures`. */
    fun verifyTransactionSignatures(
        inputs: List<VerificationInput>,
        outputs: List<VerificationOutput>,
        version: Int,
        lockTime: Long,
        subnetworkId: ByteArray,
        gas: Long,
        payload: ByteArray,
    ): SignatureCheck {
        // no inputs (a coinbase): no signatures to check
        if (inputs.isEmpty()) return SignatureCheck.Valid

        // The transaction as signed: EVERY input (the sighash commits to all outpoints and
        // sequences), with an empty signature script.
        val rawInputs = inputs.map { input ->
            RawInput(
                previousOutpoint = Outpoint(input.previousOutpointHash, input.previousOutpointIndex),
                signatureScript = "",
                sequence = input.sequence,
                sigOpCount = input.sigOpCount,
            )
        }
        val rawOutputs = ArrayList<RawOutputWithVersion>(outputs.size)
        val covenants = ArrayList<KaspaTransactionSigner.OutputCovenant?>(outputs.size)
        for (output in outputs) {
            val script = hexToBytes(output.scriptPublicKey)
            if (script == null || script.isEmpty()) return SignatureCheck.Unknown // no script data
            rawOutputs += RawOutputWithVersion(
                amount = output.amount,
                scriptPublicKey = ScriptPublicKeyWithVersion(scriptPublicKey = toHex(script), version = 0),
            )
            val authorizing = output.covenantAuthorizingInput
            val idHex = output.covenantId
            if (authorizing != null && idHex != null) {
                val id = hexToBytes(idHex)
                if (id == null || id.size != 32) return SignatureCheck.Unknown
                covenants += KaspaTransactionSigner.OutputCovenant(authorizing, id)
            } else {
                covenants += null
            }
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
            ) continue // non-standard (a covenant spend, another sighash type): not checked here
            val signature = sigScript.copyOfRange(1, 65)

            // 2. The spent output's script and amount: without them this input can't be checked yet.
            val address = input.previousOutpointAddress ?: return SignatureCheck.Unknown
            val amount = input.previousOutpointAmount ?: return SignatureCheck.Unknown
            val utxoScript = try {
                hexToBytes(KaspaAddress.getScriptPublicKey(address))
            } catch (e: Exception) {
                null
            } ?: return SignatureCheck.Unknown

            // 3. P2PK: <push-32> <32-byte x-only key> OP_CHECKSIG
            if (utxoScript.size < 34 || utxoScript[0] != 32.toByte() ||
                utxoScript[utxoScript.size - 1] != 0xAC.toByte()
            ) continue
            val xOnlyPubKey = utxoScript.copyOfRange(1, 33)

            // 4. The sighash this input signed (v0 or v1).
            val sighash = try {
                KaspaTransactionSigner.calculateSighash(rawTx, index, amount, toHex(utxoScript), covenants)
            } catch (e: Exception) {
                return SignatureCheck.Unknown
            }

            // 5. Verify.
            val valid = Schnorr.verify(sighash, signature, xOnlyPubKey)
            sighash.fill(0)
            if (!valid) return SignatureCheck.Invalid(index)
        }
        return SignatureCheck.Valid
    }

    /**
     * The check iOS `verifyIncomingPaymentSignatures` runs on a received REST transaction: every
     * input goes in; one the API left without its spent output's address or amount gets them from
     * the previous transaction ([spentOutput] - `GET /transactions/<outpoint txid>`, its output
     * at that index); an input without its outpoint is [SignatureCheck.Unknown] (the outpoint is
     * part of every input's sighash). A missing sequence reads 0 and sig-op count 1, the version,
     * lock time and gas 0 (iOS FlexibleUInt64: a value that doesn't parse reads 0); the
     * subnetwork id is the transaction's (20 zero bytes when it isn't hex), the payload its bytes.
     * No inputs (a coinbase) is [SignatureCheck.Valid].
     */
    suspend fun checkReceivedTransaction(
        tx: TransactionResponse,
        spentOutput: suspend (previousTxId: String, index: Int) -> TransactionOutput?,
    ): SignatureCheck {
        // Gson leaves a field the JSON lacks null whatever its Kotlin type, hence the nullable reads.
        val inputs = (tx.inputs as List<TransactionInput>?).orEmpty()
        if (inputs.isEmpty()) return SignatureCheck.Valid
        val verificationInputs = ArrayList<VerificationInput>(inputs.size)
        for (input in inputs) {
            val hash = (input.previousOutpointHash as String?) ?: return SignatureCheck.Unknown
            val index = input.previousOutpointIndex
            if (index < 0) return SignatureCheck.Unknown
            var address = input.previousOutpointAddress
            var amount = input.previousOutpointAmount
            if (address == null || amount == null) {
                spentOutput(hash, index)?.let { spent ->
                    address = address ?: spent.scriptPublicKeyAddress
                    amount = amount ?: spent.amount
                }
            }
            verificationInputs += VerificationInput(
                previousOutpointHash = hash,
                previousOutpointIndex = index,
                signatureScript = (input.signatureScript as String?) ?: "",
                previousOutpointAddress = address,
                previousOutpointAmount = amount,
                sequence = flexibleLong(input.sequence) ?: 0L,
                sigOpCount = input.sigOpCount?.let { flexibleLong(it)?.toInt() ?: 0 } ?: 1,
            )
        }
        val outputs = (tx.outputs as List<TransactionOutput>?).orEmpty().map { output ->
            VerificationOutput(
                amount = output.amount,
                scriptPublicKey = (output.scriptPublicKey as String?) ?: "",
                covenantAuthorizingInput = output.covenantAuthorizingInput?.trim()?.toIntOrNull(),
                covenantId = output.covenantId,
            )
        }
        return verifyTransactionSignatures(
            inputs = verificationInputs,
            outputs = outputs,
            version = tx.version?.trim()?.toIntOrNull() ?: 0,
            lockTime = tx.lockTime?.let { flexibleLong(it) ?: 0L } ?: 0L,
            subnetworkId = hexToBytes(tx.subnetworkId ?: "") ?: ByteArray(20),
            gas = tx.gas?.let { flexibleLong(it) ?: 0L } ?: 0L,
            payload = hexToBytes(tx.payload ?: "") ?: ByteArray(0),
        )
    }

    /** The spent output [index] of a previous transaction (iOS: the output whose `index` is it,
     *  else the one at that position). */
    fun outputAt(previous: TransactionResponse, index: Int): TransactionOutput? {
        val outputs = (previous.outputs as List<TransactionOutput>?).orEmpty()
        return outputs.firstOrNull { it.index == index } ?: outputs.getOrNull(index)
    }

    /** iOS FlexibleUInt64: a number, or a string holding one; null when absent or unparsable
     *  (the caller picks iOS's fallback). */
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
