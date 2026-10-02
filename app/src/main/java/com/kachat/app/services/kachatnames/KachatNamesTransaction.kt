package com.kachat.app.services.kachatnames

import com.kachat.app.services.kachatnames.KachatNames.Writer
import com.kachat.app.services.kachatnames.KachatNames.blake2bKeyed
import com.kachat.app.services.kachatnames.KachatNames.blake3Keyed
import com.kachat.app.services.kachatnames.KachatNames.hex
import kotlin.math.ceil

// Version-1 transaction model (rusty-kaspa a41a333, consensus/core/src/tx.rs), ported from iOS
// KaChat/Services/KachatNames/KachatNamesTransaction.swift (KaChat 4c2c45d).

class Outpoint(
    /** Transaction id, 32 bytes in hashing order (the hex string as written by the node). */
    val txid: ByteArray,
    val index: Int
) {
    override fun equals(other: Any?): Boolean = other is Outpoint && index == other.index && txid.contentEquals(other.txid)
    override fun hashCode(): Int = 31 * txid.contentHashCode() + index
    override fun toString(): String = "${hex(txid)}:$index"
}

class UtxoEntry(
    val amount: Long,
    val scriptVersion: Int = 0,
    val script: ByteArray,
    val blockDaaScore: Long,
    val isCoinbase: Boolean = false,
    /** KIP-20 covenant id the UTXO carries (gaps and names: the registry id). */
    val covenantId: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean =
        other is UtxoEntry && amount == other.amount && scriptVersion == other.scriptVersion &&
            script.contentEquals(other.script) && blockDaaScore == other.blockDaaScore &&
            isCoinbase == other.isCoinbase && covenantId.contentEquals(other.covenantId)

    override fun hashCode(): Int =
        listOf(amount, scriptVersion, script.contentHashCode(), blockDaaScore, isCoinbase, covenantId.contentHashCode()).hashCode()

    override fun toString(): String =
        "UtxoEntry(amount=$amount, scriptVersion=$scriptVersion, script=${hex(script)}, blockDaaScore=$blockDaaScore, " +
            "isCoinbase=$isCoinbase, covenantId=${covenantId?.let { hex(it) }})"
}

data class Utxo(val outpoint: Outpoint, val entry: UtxoEntry)

class CovenantBinding(val authorizingInput: Int, val covenantId: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is CovenantBinding && authorizingInput == other.authorizingInput && covenantId.contentEquals(other.covenantId)
    override fun hashCode(): Int = 31 * authorizingInput + covenantId.contentHashCode()
    override fun toString(): String = "CovenantBinding($authorizingInput, ${hex(covenantId)})"
}

class TxInput(
    val outpoint: Outpoint,
    val signatureScript: ByteArray,
    val sequence: Long,
    /** Version-1 compute budget (1 unit = 10,000 script units; 9,999 free per input), a u16. */
    val computeBudget: Int
) {
    fun withSignatureScript(script: ByteArray): TxInput = TxInput(outpoint, script, sequence, computeBudget)

    override fun equals(other: Any?): Boolean =
        other is TxInput && outpoint == other.outpoint && signatureScript.contentEquals(other.signatureScript) &&
            sequence == other.sequence && computeBudget == other.computeBudget
    override fun hashCode(): Int = listOf(outpoint, signatureScript.contentHashCode(), sequence, computeBudget).hashCode()
}

class TxOutput(
    val value: Long,
    val scriptVersion: Int = 0,
    val script: ByteArray,
    val covenant: CovenantBinding? = null
) {
    fun withValue(value: Long): TxOutput = TxOutput(value, scriptVersion, script, covenant)

    override fun equals(other: Any?): Boolean =
        other is TxOutput && value == other.value && scriptVersion == other.scriptVersion &&
            script.contentEquals(other.script) && covenant == other.covenant
    override fun hashCode(): Int = listOf(value, scriptVersion, script.contentHashCode(), covenant).hashCode()
    override fun toString(): String = "TxOutput(value=$value, scriptVersion=$scriptVersion, script=${hex(script)}, covenant=$covenant)"
}

/**
 * A version-1 (Toccata) transaction: output covenant bindings, per-input compute budgets,
 * native subnetwork, no gas, and the KIP-9 storage-mass commitment.
 */
class Tx(
    val version: Int = 1,
    val inputs: List<TxInput>,
    val outputs: List<TxOutput>,
    val lockTime: Long,
    val subnetworkId: ByteArray = ByteArray(20),
    val gas: Long = 0,
    val payload: ByteArray,
    val storageMass: Long = 0
) {
    val isNativeSubnetwork: Boolean get() = subnetworkId.contentEquals(ByteArray(20))

    fun copy(
        inputs: List<TxInput> = this.inputs,
        outputs: List<TxOutput> = this.outputs,
        lockTime: Long = this.lockTime,
        payload: ByteArray = this.payload,
        storageMass: Long = this.storageMass
    ): Tx = Tx(version, inputs, outputs, lockTime, subnetworkId, gas, payload, storageMass)

    // Serialization (rusty-kaspa consensus/core/src/hashing/tx.rs `write_transaction`)

    private fun write(excludeSignatureScripts: Boolean, excludeMassCommit: Boolean, excludePayload: Boolean): ByteArray {
        check(version >= 1) { "the name core builds version-1 transactions only" }
        val d = Writer()
        d.le16(version)
        d.le64(inputs.size.toLong())
        for (i in inputs) {
            d.bytes(i.outpoint.txid)
            d.le32(i.outpoint.index)
            if (excludeSignatureScripts) {
                d.le64(0)
            } else {
                d.le64(i.signatureScript.size.toLong())
                d.bytes(i.signatureScript)
            }
            d.le64(i.sequence)
            if (!excludeMassCommit) d.le16(i.computeBudget)
        }
        d.le64(outputs.size.toLong())
        for (o in outputs) appendOutput(o, d)
        d.le64(lockTime)
        d.bytes(subnetworkId)
        d.le64(gas)
        if (excludePayload) {
            d.le64(0)
        } else {
            d.le64(payload.size.toLong())
            d.bytes(payload)
        }
        if (!excludeMassCommit) d.le64(storageMass)
        return d.toByteArray()
    }

    /** The TransactionHash preimage (`write_transaction(tx, FULL)`). */
    val fullPreimage: ByteArray get() = write(excludeSignatureScripts = false, excludeMassCommit = false, excludePayload = false)

    /** `transaction_v1_rest_preimage`: no payload, signature scripts or mass commitments. */
    val restPreimage: ByteArray get() = write(excludeSignatureScripts = true, excludeMassCommit = true, excludePayload = true)

    /** The v1 transaction id: `TransactionV1Id(PayloadDigest(payload) || TransactionRest(rest))`. */
    val id: ByteArray
        get() = blake3Keyed("TransactionV1Id", blake3Keyed("PayloadDigest", payload) + blake3Keyed("TransactionRest", restPreimage))

    val idHex: String get() = hex(id)

    /** The transaction hash (commits to signature scripts, budgets and the storage mass). */
    val hash: ByteArray get() = blake2bKeyed("TransactionHash", fullPreimage)

    // Sighash (consensus/core/src/hashing/sighash.rs, SIGHASH_ALL, version >= 1)

    /**
     * The Schnorr signature hash of input [inputIndex] for SIGHASH_ALL. [entries] are the spent
     * UTXOs in input order. Version-1 sighashes cover no sig-op counts and no budgets.
     */
    fun sighash(inputIndex: Int, entries: List<UtxoEntry>): ByteArray {
        val domain = "TransactionSigningHash"
        val prev = Writer()
        val seqs = Writer()
        for (i in inputs) {
            prev.bytes(i.outpoint.txid).le32(i.outpoint.index)
            seqs.le64(i.sequence)
        }
        val outs = Writer()
        for (o in outputs) appendOutput(o, outs)
        val payloadHash = if (isNativeSubnetwork && payload.isEmpty()) {
            ByteArray(32)
        } else {
            blake2bKeyed(domain, Writer().le64(payload.size.toLong()).bytes(payload).toByteArray())
        }
        val input = inputs[inputIndex]
        val entry = entries[inputIndex]
        val d = Writer()
            .le16(version)
            .bytes(blake2bKeyed(domain, prev.toByteArray()))
            .bytes(blake2bKeyed(domain, seqs.toByteArray()))
            .bytes(input.outpoint.txid)
            .le32(input.outpoint.index)
            .le16(entry.scriptVersion)
            .le64(entry.script.size.toLong())
            .bytes(entry.script)
            .le64(entry.amount)
            .le64(input.sequence)
            .bytes(blake2bKeyed(domain, outs.toByteArray()))
            .le64(lockTime)
            .bytes(subnetworkId)
            .le64(gas)
            .bytes(payloadHash)
            .u8(KachatNames.SIGHASH_ALL.toInt())
        return blake2bKeyed(domain, d.toByteArray())
    }

    override fun equals(other: Any?): Boolean =
        other is Tx && version == other.version && inputs == other.inputs && outputs == other.outputs &&
            lockTime == other.lockTime && subnetworkId.contentEquals(other.subnetworkId) && gas == other.gas &&
            payload.contentEquals(other.payload) && storageMass == other.storageMass

    override fun hashCode(): Int = fullPreimage.contentHashCode()

    private companion object {
        fun appendOutput(o: TxOutput, d: Writer) {
            d.le64(o.value)
            d.le16(o.scriptVersion)
            d.le64(o.script.size.toLong())
            d.bytes(o.script)
            val c = o.covenant
            if (c == null) {
                d.u8(0)
            } else {
                d.u8(1)
                d.le16(c.authorizingInput)
                d.bytes(c.covenantId)
            }
        }
    }
}

// Mass (consensus/core/src/mass/mod.rs) and fee

object Mass {
    const val MASS_PER_TX_BYTE: Long = 1
    const val MASS_PER_SCRIPT_PUB_KEY_BYTE: Long = 10
    const val GRAMS_PER_COMPUTE_BUDGET_UNIT: Long = 100
    const val TRANSIENT_BYTE_TO_MASS_FACTOR: Long = 4
    /** KIP-9 `C` = SOMPI_PER_KASPA * 10,000. */
    const val STORAGE_MASS_PARAMETER: Long = 1_000_000_000_000L
    /**
     * Mempool block mass limits after Toccata (compute 500,000, transient 1,000,000):
     * normalized transient = transient * 500,000 / 1,000,000.
     */
    const val TRANSIENT_COFACTOR: Double = 500_000.0 / 1_000_000.0

    /** `transaction_estimated_serialized_size`. */
    fun size(tx: Tx): Long {
        var size = 2L + 8
        for (i in tx.inputs) {
            size += 32 + 4 + 8 + i.signatureScript.size.toLong() + 8
            if (tx.version >= 1) size += 2
        }
        size += 8
        for (o in tx.outputs) {
            size += 8 + 2 + 8 + o.script.size.toLong()
            if (o.covenant != null) size += 2 + 32
        }
        size += 8 + 20 + 8 + 32 + 8 + tx.payload.size.toLong()
        return size
    }

    fun computeMass(tx: Tx): Long {
        val spkBytes = tx.outputs.sumOf { 2L + it.script.size }
        val budgets = tx.inputs.sumOf { it.computeBudget.toLong() }
        return size(tx) * MASS_PER_TX_BYTE + spkBytes * MASS_PER_SCRIPT_PUB_KEY_BYTE + GRAMS_PER_COMPUTE_BUDGET_UNIT * budgets
    }

    fun transientMass(tx: Tx): Long = size(tx) * TRANSIENT_BYTE_TO_MASS_FACTOR

    fun normalizedTransient(tx: Tx): Long = ceil(transientMass(tx).toDouble() * TRANSIENT_COFACTOR).toLong()

    /** `utxo_plurality`: 100-byte storage units of a UTXO. */
    fun plurality(scriptLength: Int, hasCovenant: Boolean): Long {
        val bytes = 63 + scriptLength + (if (hasCovenant) 32 else 0)
        return ((bytes + 99) / 100).toLong()
    }

    private fun mulOrNull(a: Long, b: Long): Long? = try { Math.multiplyExact(a, b) } catch (_: ArithmeticException) { null }

    private fun addSaturating(a: Long, b: Long): Long = try { Math.addExact(a, b) } catch (_: ArithmeticException) { Long.MAX_VALUE }

    /**
     * KIP-9 storage mass (`calc_storage_mass`), null when incomputable (too high). Swift computes
     * in UInt64; the overflow bounds here are 2^63, which no registry-sized script or amount
     * reaches, and the cases Swift would trap on (an empty input set) return null.
     */
    fun storageMass(tx: Tx, entries: List<UtxoEntry>): Long? {
        val c = STORAGE_MASS_PARAMETER
        var outsPlurality = 0L
        var harmonicOuts = 0L
        for (o in tx.outputs) {
            val p = plurality(o.script.size, o.covenant != null)
            if (o.value <= 0) return null
            outsPlurality += p
            val cp = mulOrNull(c, p) ?: return null
            val cpp = mulOrNull(cp, p) ?: return null
            harmonicOuts = try { Math.addExact(harmonicOuts, cpp / o.value) } catch (_: ArithmeticException) { return null }
        }
        val ins = entries.map { plurality(it.script.size, it.covenantId != null) to it.amount }
        val relaxed = when {
            outsPlurality == 1L -> true
            ins.size > 2 -> false
            else -> {
                val insPlurality = ins.sumOf { it.first }
                insPlurality == 1L || (outsPlurality == 2L && insPlurality == 2L)
            }
        }
        if (relaxed) {
            var harmonicIns = 0L
            for ((p, amount) in ins) {
                if (amount <= 0) return null
                val term = (mulOrNull(c, p)?.let { mulOrNull(it, p) } ?: return null) / amount
                harmonicIns = addSaturating(harmonicIns, term)
            }
            return if (harmonicOuts > harmonicIns) harmonicOuts - harmonicIns else 0
        }
        val insPlurality = ins.sumOf { it.first }
        if (insPlurality == 0L) return null
        val sumIns = ins.sumOf { it.second }
        val meanIns = maxOf(sumIns / insPlurality, 1L)
        val arithmeticIns = mulOrNull(insPlurality, c / meanIns) ?: Long.MAX_VALUE
        return if (harmonicOuts > arithmeticIns) harmonicOuts - arithmeticIns else 0
    }

    /** The relay fee the CLI pays: ceil(max(compute, normalized transient) * feerate). */
    fun networkFee(tx: Tx, feerate: Double): Long {
        val feeMass = maxOf(computeMass(tx), normalizedTransient(tx))
        val rate = maxOf(feerate, KachatNames.MIN_FEERATE)
        return ceil(feeMass.toDouble() * rate).toLong()
    }
}
