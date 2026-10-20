package com.kachat.app.util

import com.kachat.app.services.Outpoint
import com.kachat.app.services.RawInput
import com.kachat.app.services.RawOutputWithVersion
import com.kachat.app.services.RawTransaction
import com.kachat.app.services.ScriptPublicKeyWithVersion
import com.kachat.app.services.TransactionInput
import com.kachat.app.services.TransactionOutput
import com.kachat.app.services.TransactionResponse
import org.bouncycastle.crypto.digests.Blake2bDigest
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Schnorr signature check of a transaction read from the REST API - the payment sync's guard on
 * incoming payments, a port of iOS `KasiaTransactionBuilder.verifyTransactionSignatures`
 * (KaChatTransactionBuilder.swift, "Schnorr Signature Verification (REST API path)") and of
 * `ChatService.verifyIncomingPaymentSignatures` (ChatService+Fetching.swift), as of iOS 8116ab9.
 *
 * Versions 0 and 1. The answer is [SignatureCheck.Valid] (every standard P2PK signature checks
 * out), [SignatureCheck.Invalid] (one is definitively wrong) or [SignatureCheck.Unknown] (it
 * can't be checked yet - a spent output not resolved, data missing): ask again later. Inputs that
 * are not the standard Schnorr P2PK form (P2SH covenant spends and the like) are not
 * signature-checked. The sighash is the signer's own ([KaspaTransactionSigner.calculateSighash]),
 * the curve maths [Schnorr.verify].
 *
 * The verdict rule, the same on every client (audits AND-042, XP-033): a standard key-address
 * (P2PK Schnorr) input must carry a well-formed signature - a blank, short or wrongly pushed one
 * cannot be on chain. A signature that fails (or is malformed) makes the payment Invalid only
 * when the REST fields rebuild the claimed transaction id ([restTransactionId]), i.e. the API
 * described the transaction completely; otherwise a field the signature covers is missing (lock
 * time, sequences) and the answer is Unknown. A sighash type other than SIGHASH_ALL is Unknown
 * too, never a silent pass.
 */
object KaspaSignatureVerifier {

    sealed class SignatureCheck {
        object Valid : SignatureCheck()
        /** The signature of input [inputIndex] (the first that failed) is wrong or malformed. */
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

    /** iOS `verifyTransactionSignatures`; [claimedTxId] is the id the API gave the transaction. */
    fun verifyTransactionSignatures(
        claimedTxId: String,
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

        // Whether the REST fields describe the transaction completely: they rebuild its id.
        fun restFieldsRebuildId(): Boolean = restTransactionId(
            version = version,
            inputs = inputs.map { Triple(it.previousOutpointHash, it.previousOutpointIndex, it.sequence) },
            outputs = rawOutputs.zip(covenants).map { (output, covenant) ->
                Triple(output.amount, hexToBytes(output.scriptPublicKey.scriptPublicKey) ?: ByteArray(0), covenant)
            },
            lockTime = lockTime, subnetworkId = subnetworkId, gas = gas, payload = payload,
        )?.equals(claimedTxId, ignoreCase = true) == true
        var failedInput: Int? = null

        for ((index, input) in inputs.withIndex()) {
            // 1. The spent output's script and amount: without them this input can't be checked yet.
            val address = input.previousOutpointAddress ?: return SignatureCheck.Unknown
            val amount = input.previousOutpointAmount ?: return SignatureCheck.Unknown
            val utxoScript = try {
                hexToBytes(KaspaAddress.getScriptPublicKey(address))
            } catch (e: Exception) {
                null
            } ?: return SignatureCheck.Unknown

            // 2. Only standard P2PK Schnorr inputs are checked here: <push-32> <32-byte key> OP_CHECKSIG
            if (utxoScript.size != 34 || utxoScript[0] != 32.toByte() || utxoScript[33] != 0xAC.toByte()) {
                continue // P2SH (covenant spends), ECDSA and the like
            }
            val xOnlyPubKey = utxoScript.copyOfRange(1, 33)

            // 3. Such an input's signature script is 0x41 (push 65) + 64-byte Schnorr signature + a
            // sighash type byte - anything else (blank, short, another push) can't be on chain.
            val sigScript = hexToBytes(input.signatureScript)
            if (sigScript == null || sigScript.size != 66 || sigScript[0] != 0x41.toByte()) {
                if (failedInput == null) failedInput = index
                continue
            }
            // a sighash type this check doesn't hash: can't be decided here
            if (sigScript[65] != 0x01.toByte()) return SignatureCheck.Unknown
            val signature = sigScript.copyOfRange(1, 65)

            // 4. The sighash this input signed (v0 or v1).
            val sighash = try {
                KaspaTransactionSigner.calculateSighash(rawTx, index, amount, toHex(utxoScript), covenants)
            } catch (e: Exception) {
                return SignatureCheck.Unknown
            }

            // 5. Verify.
            val valid = Schnorr.verify(sighash, signature, xOnlyPubKey)
            sighash.fill(0)
            if (!valid && failedInput == null) failedInput = index
        }
        val failed = failedInput ?: return SignatureCheck.Valid
        // forged only when the API described the whole transaction; else something it left out
        // (that the signature covers) is the likelier reason
        return if (restFieldsRebuildId()) SignatureCheck.Invalid(failed) else SignatureCheck.Unknown
    }

    /**
     * The transaction id as the chain computes it, from REST fields (iOS `restTransactionId`;
     * rusty-kaspa consensus/core/src/hashing/tx.rs): version 0 = blake2b keyed "TransactionID"
     * over the encoding with signature scripts and mass commitments excluded, payload included;
     * version 1 = TransactionV1Id(PayloadDigest(payload) || TransactionRest(the same encoding with
     * the payload excluded too)), keyed BLAKE3. Output script version 0. Inputs are (outpoint
     * hash, index, sequence), outputs (amount, script, covenant). Null when an outpoint hash
     * isn't 32 bytes of hex. Checked against real mainnet txs (KaspaSignatureVerifierTest).
     */
    fun restTransactionId(
        version: Int,
        inputs: List<Triple<String, Int, Long>>,
        outputs: List<Triple<Long, ByteArray, KaspaTransactionSigner.OutputCovenant?>>,
        lockTime: Long,
        subnetworkId: ByteArray,
        gas: Long,
        payload: ByteArray,
    ): String? {
        val body = ByteArrayOutputStream()
        body.write(le16(version))
        body.write(le64(inputs.size.toLong()))
        for ((hashHex, index, sequence) in inputs) {
            val hash = hexToBytes(hashHex)
            if (hash == null || hash.size != 32) return null
            body.write(hash)
            body.write(le32(index))
            body.write(varBytes(ByteArray(0)))
            body.write(le64(sequence))
        }
        body.write(le64(outputs.size.toLong()))
        for ((amount, script, covenant) in outputs) {
            body.write(le64(amount))
            body.write(le16(0))
            body.write(varBytes(script))
            if (version >= 1) {
                if (covenant != null) {
                    body.write(1)
                    body.write(le16(covenant.authorizingInput))
                    body.write(covenant.covenantId)
                } else {
                    body.write(0)
                }
            }
        }
        body.write(le64(lockTime))
        body.write(subnetworkId.copyOf(20)) // zero padded / cut to 20 bytes
        body.write(le64(gas))
        val digest = if (version == 0) {
            val hasher = Blake2bDigest("TransactionID".toByteArray(Charsets.US_ASCII), 32, null, null)
            val bytes = body.toByteArray() + varBytes(payload)
            hasher.update(bytes, 0, bytes.size)
            ByteArray(32).also { hasher.doFinal(it, 0) }
        } else {
            val payloadDigest = Blake3.domain("PayloadDigest").update(payload).finalize()
            val rest = Blake3.domain("TransactionRest").update(body.toByteArray() + varBytes(ByteArray(0))).finalize()
            Blake3.domain("TransactionV1Id").update(payloadDigest + rest).finalize()
        }
        return toHex(digest)
    }

    private fun le16(value: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()

    private fun le32(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun le64(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    private fun varBytes(bytes: ByteArray): ByteArray = le64(bytes.size.toLong()) + bytes

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
            claimedTxId = tx.transactionId,
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
