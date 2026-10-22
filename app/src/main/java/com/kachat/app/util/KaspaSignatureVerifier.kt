package com.kachat.app.util

import com.kachat.app.services.Outpoint
import com.kachat.app.services.RawInput
import com.kachat.app.services.RawOutputWithVersion
import com.kachat.app.services.RawTransaction
import com.kachat.app.services.ScriptPublicKeyWithVersion
import com.kachat.app.services.TransactionInput
import com.kachat.app.services.TransactionOutput
import com.kachat.app.services.TransactionResponse
import com.kachat.app.services.kachatnames.KachatNames
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
 * can't be checked yet - a spent output not resolved, data missing): ask again later. Checked
 * inputs are standard P2PK and KIP-22 quantum-address P2SH spends (the signature and key from the
 * signature script, the key bound to the address by the redeem's hash, the sighash over the P2SH
 * script - the same the signer uses, iOS 54265bf). Other P2SH spends (covenants) are not
 * signature-checked; such a transaction is a contract and never files as a payment. The sighash
 * is the signer's own ([KaspaTransactionSigner.calculateSighash]), the curve maths [Schnorr.verify].
 *
 * The verdict rule, the same on every client (audits AND-042, XP-033, iOS 5a78d06): a standard
 * key-address (P2PK Schnorr) input must carry a well-formed signature - a blank, short or wrongly
 * pushed one cannot be on chain, and neither can a P2SH script that isn't hex or a KIP-22 redeem
 * that doesn't hash to its address: such a malformed script is Invalid outright (AND-046). A
 * well-formed signature that fails makes the payment Invalid only when the REST fields rebuild
 * the claimed transaction id ([restTransactionId]), i.e. the API described the transaction
 * completely; otherwise a field the signature covers is missing (lock time, sequences) and the
 * answer is Unknown. A sighash type other than SIGHASH_ALL is Unknown too, never a silent pass.
 * Every input is examined before any Unknown, and a transaction where not one input was actually
 * checked (no inputs, all ECDSA, unmodelled kinds) is Unknown, never Valid (AND-054).
 */
object KaspaSignatureVerifier {

    sealed class SignatureCheck {
        object Valid : SignatureCheck()
        /** The signature of input [inputIndex] (the first that failed) is wrong or malformed. */
        data class Invalid(val inputIndex: Int) : SignatureCheck()
        object Unknown : SignatureCheck()
    }

    /** A received transaction's verdict and, when [check] is [SignatureCheck.Valid], its proven
     *  sender: the first input address that isn't ours among the inputs whose signature VERIFIED
     *  (iOS 2343e84 `verifyIncomingPaymentSignatures`, IOS-094). Null otherwise - including a
     *  Valid verdict with no verified input that isn't ours, which the payment sync then treats
     *  as Unknown, never filing it under the REST label. */
    data class ReceivedCheck(val check: SignatureCheck, val sender: String?)

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

    /** iOS `verifyTransactionSignatures`; [claimedTxId] is the id the API gave the transaction.
     *  [onVerifiedInput] is called with the index of every input whose signature VERIFIED. A
     *  payment's sender may only come from these: ECDSA and covenant-shaped inputs are skipped
     *  unchecked, so the first non-mine input can be one nobody signed (IOS-094, iOS 2343e84). */
    fun verifyTransactionSignatures(
        claimedTxId: String,
        inputs: List<VerificationInput>,
        outputs: List<VerificationOutput>,
        version: Int,
        lockTime: Long,
        subnetworkId: ByteArray,
        gas: Long,
        payload: ByteArray,
        onVerifiedInput: (Int) -> Unit = {},
    ): SignatureCheck {
        // no inputs (a coinbase): nothing was checked, so nothing is vouched for (IOS-094) - the
        // received-payment path drops such a transaction before it gets here
        if (inputs.isEmpty()) return SignatureCheck.Unknown

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
        // A signature that fails to verify is forged only when the REST fields rebuild the claimed
        // id (XP-033: else a field it covers is missing). A signature script of a shape no accepted
        // transaction can carry needs no such excuse - it is invalid outright (AND-046 / IOS-097).
        // Every input is looked at before any "can't decide" answer, so a malformed input later in
        // the list is never hidden behind an earlier unknown.
        var failedInput: Int? = null
        var malformedInput: Int? = null
        var undecided = false
        // inputs whose signature was actually checked; a payment with none is not Valid (AND-054 /
        // IOS-106: an all-ECDSA or otherwise unmodelled payment had zero checks and passed)
        var checkedInputs = 0

        for ((index, input) in inputs.withIndex()) {
            // 1. The spent output's script and amount: without them this input can't be checked yet.
            val address = input.previousOutpointAddress
            val amount = input.previousOutpointAmount
            val utxoScript = if (address == null || amount == null) null else try {
                hexToBytes(KaspaAddress.getScriptPublicKey(address))
            } catch (e: Exception) {
                null
            }
            if (amount == null || utxoScript == null) {
                undecided = true
                continue
            }

            // 2. The input's kind, from the spent output's script. Standard P2PK Schnorr
            // (<push-32> <32-byte key> OP_CHECKSIG) and KIP-22 quantum-address P2SH spends are both
            // checked; any other P2SH spend is a covenant, not checked here - such a transaction is
            // a contract and never files as a payment, while a KIP-22 spend passes the contract
            // filter, so skipping it here would leave a forged all-quantum "payment" with no
            // signature checked at all.
            val xOnlyPubKey: ByteArray
            val signature: ByteArray
            if (utxoScript.size == 34 && utxoScript[0] == 32.toByte() && utxoScript[33] == 0xAC.toByte()) {
                // 3. A P2PK input's signature script is 0x41 (push 65) + 64-byte Schnorr signature +
                // a sighash type byte - anything else (blank, short, another push) can't be on chain.
                val sigScript = hexToBytes(input.signatureScript)
                if (sigScript == null || sigScript.size != 66 || sigScript[0] != 0x41.toByte()) {
                    if (malformedInput == null) malformedInput = index
                    continue
                }
                // a sighash type this check doesn't hash: can't be decided here
                if (sigScript[65] != 0x01.toByte()) {
                    undecided = true
                    continue
                }
                xOnlyPubKey = utxoScript.copyOfRange(1, 33)
                signature = sigScript.copyOfRange(1, 65)
            } else if (utxoScript.size == 35 && utxoScript[0] == 0xAA.toByte() && utxoScript[1] == 0x20.toByte() &&
                utxoScript[34] == 0x87.toByte()
            ) {
                // 3b. P2SH. A KIP-22 spend's signature script is 0x41 <64-byte signature> <sighash
                // byte> then the canonical push 0x22 of the 34-byte redeem 0x20 <x-only key> 0xac
                // (101 bytes); any other shape is a covenant spend, skipped like before. A script
                // that is not even hex can't be on chain.
                val sigScript = hexToBytes(input.signatureScript)
                if (sigScript == null) {
                    if (malformedInput == null) malformedInput = index
                    continue
                }
                if (sigScript.size != 101 || sigScript[0] != 0x41.toByte() || sigScript[66] != 0x22.toByte() ||
                    sigScript[67] != 0x20.toByte() || sigScript[100] != 0xAC.toByte()
                ) {
                    continue
                }
                // The key is bound to the address by the redeem's hash: a KIP-22-shaped script whose
                // redeem doesn't hash to this very address can't be on chain for it.
                val redeem = sigScript.copyOfRange(67, 101)
                if (!KachatNames.blake2b256(redeem).contentEquals(utxoScript.copyOfRange(2, 34))) {
                    if (malformedInput == null) malformedInput = index
                    continue
                }
                if (sigScript[65] != 0x01.toByte()) {
                    undecided = true
                    continue
                }
                xOnlyPubKey = sigScript.copyOfRange(68, 100)
                signature = sigScript.copyOfRange(1, 65)
            } else {
                continue // ECDSA and the like: not checked here
            }

            // 4. The sighash this input signed (v0 or v1).
            val sighash = try {
                KaspaTransactionSigner.calculateSighash(rawTx, index, amount, toHex(utxoScript), covenants)
            } catch (e: Exception) {
                undecided = true
                continue
            }

            // 5. Verify.
            val valid = Schnorr.verify(sighash, signature, xOnlyPubKey)
            sighash.fill(0)
            checkedInputs++
            if (!valid) {
                if (failedInput == null) failedInput = index
            } else {
                onVerifiedInput(index)
            }
        }
        malformedInput?.let { return SignatureCheck.Invalid(it) }
        failedInput?.let { failed ->
            // forged only when the API described the whole transaction; else something it left
            // out (that the signature covers) is the likelier reason
            return if (restFieldsRebuildId()) SignatureCheck.Invalid(failed) else SignatureCheck.Unknown
        }
        if (undecided || checkedInputs == 0) return SignatureCheck.Unknown
        return SignatureCheck.Valid
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
     * No inputs (a coinbase) is [SignatureCheck.Unknown] - nothing was checked (IOS-094). On
     * Valid the sender is the first VERIFIED input's address that isn't [myAddress] (null when
     * there is none - IOS-094, iOS 2343e84).
     */
    suspend fun checkReceivedTransaction(
        tx: TransactionResponse,
        myAddress: String,
        spentOutput: suspend (previousTxId: String, index: Int) -> TransactionOutput?,
    ): ReceivedCheck {
        val unknown = ReceivedCheck(SignatureCheck.Unknown, null)
        // Gson leaves a field the JSON lacks null whatever its Kotlin type, hence the nullable reads.
        val inputs = (tx.inputs as List<TransactionInput>?).orEmpty()
        if (inputs.isEmpty()) return unknown
        val verificationInputs = ArrayList<VerificationInput>(inputs.size)
        for (input in inputs) {
            val hash = (input.previousOutpointHash as String?) ?: return unknown
            val index = input.previousOutpointIndex
            if (index < 0) return unknown
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
        val verifiedInputs = ArrayList<Int>()
        val check = verifyTransactionSignatures(
            claimedTxId = tx.transactionId,
            inputs = verificationInputs,
            outputs = outputs,
            version = tx.version?.trim()?.toIntOrNull() ?: 0,
            lockTime = tx.lockTime?.let { flexibleLong(it) ?: 0L } ?: 0L,
            subnetworkId = hexToBytes(tx.subnetworkId ?: "") ?: ByteArray(20),
            gas = tx.gas?.let { flexibleLong(it) ?: 0L } ?: 0L,
            payload = hexToBytes(tx.payload ?: "") ?: ByteArray(0),
            onVerifiedInput = { verifiedInputs += it },
        )
        // The proven sender is the first input that isn't ours AMONG THOSE WHOSE SIGNATURE
        // VERIFIED - never just the first input: ECDSA and covenant-shaped inputs are skipped
        // unchecked, and a forger could list one first and sign a second input of their own
        // (IOS-094, owner's choice for the run-18 reopen, iOS 2343e84). No verified non-mine
        // input, no sender.
        val sender = if (check == SignatureCheck.Valid) {
            verifiedInputs.asSequence()
                .mapNotNull { verificationInputs[it].previousOutpointAddress }
                .firstOrNull { !it.equals(myAddress, ignoreCase = true) }
        } else null
        return ReceivedCheck(check, sender)
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
