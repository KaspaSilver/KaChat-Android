package com.kachat.app.services.kachatnames

import com.kachat.app.services.kachatnames.KachatNames.Codec
import com.kachat.app.services.kachatnames.KachatNames.Failure
import java.util.Locale

// The builders, ported from iOS KaChat/Services/KachatNames/KachatNamesBuilder.swift (KaChat
// 4c2c45d, cancelCommit from 2989ea1, registry v2 extend / renew window from 3ef2ec2; registry v3
// seller-bound offers and decline from e1e3455; registry v4 fixed prices, no price shard, from
// 0ed15e9).

// Compute budgets

/** Which budget an input commits, by what it runs. [raw] is the vectors' / CLI's role name. */
enum class BudgetRole(val raw: String) {
    P2PK("p2pk"),
    COMMIT("commit"),
    GAP_REGISTER("gap.register"),
    GAP_MERGE("gap.merge"),
    GAP_ABSORBED("gap.absorbed"),
    /** registry v5 only: the CLI's sponsor imports; listed so the table matches the vectors (iOS 6f18475) */
    GAP_IMPORT("gap.import"),
    NAME_TRANSFER("name.transfer"),
    NAME_LIST("name.list"),
    NAME_BUY("name.buy"),
    NAME_EXTEND("name.extend"),
    NAME_RENEW("name.renew"),
    NAME_RELEASE("name.release"),
    NAME_RECLAIM("name.reclaim"),
    OFFER_ACCEPT("offer.accept"),
    OFFER_DECLINE("offer.decline"),
    OFFER_WITHDRAW("offer.withdraw"),
    OFFER_REFUND("offer.refund");

    companion object {
        fun fromRaw(raw: String): BudgetRole? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * Per-input compute budgets (u16). The CLI measures each input in the script engine; the app has
 * no engine, so it commits a fixed budget per entry that covers every case (README "Cost per
 * operation"; the vector generator checks every measured budget fits this table, the vectors'
 * `recommendedBudgets`). An input that needs more than it committed fails, so these only ever err
 * on the side of a slightly higher fee (100 grams per unit). Registry v4 (iOS 0ed15e9).
 */
data class Budgets(val table: Map<BudgetRole, Int>) {
    operator fun get(role: BudgetRole): Int = table[role] ?: RECOMMENDED.table[role] ?: 0

    /** A copy with [role]'s budget set (Swift's mutating subscript). */
    fun with(role: BudgetRole, budget: Int): Budgets = Budgets(table + (role to budget))

    companion object {
        val RECOMMENDED = Budgets(
            mapOf(
                BudgetRole.P2PK to 10, BudgetRole.COMMIT to 10,
                BudgetRole.GAP_REGISTER to 8, BudgetRole.GAP_MERGE to 4, BudgetRole.GAP_ABSORBED to 0, BudgetRole.GAP_IMPORT to 0,
                BudgetRole.NAME_TRANSFER to 12, BudgetRole.NAME_LIST to 12, BudgetRole.NAME_BUY to 2,
                BudgetRole.NAME_EXTEND to 2, BudgetRole.NAME_RENEW to 2, BudgetRole.NAME_RELEASE to 10,
                BudgetRole.NAME_RECLAIM to 0,
                BudgetRole.OFFER_ACCEPT to 17, BudgetRole.OFFER_DECLINE to 10, BudgetRole.OFFER_WITHDRAW to 10,
                BudgetRole.OFFER_REFUND to 0
            )
        )

        /**
         * Registry v5: the gap is the v5 gap (7.7 kB: the 20-level import proof loop and a second
         * name check), and every gap spend reveals and runs it. kachat-domains 6eddc7a measured the
         * worst cases: register 122,889 script units (12), merge 69,090 (6), absorbed 15,782 (1),
         * import ~234,700 (23); the vectors' `recommendedBudgets`. Name and offer as v4 (iOS 6f18475).
         */
        val RECOMMENDED_V5: Budgets = RECOMMENDED
            .with(BudgetRole.GAP_REGISTER, 13)
            .with(BudgetRole.GAP_MERGE, 7)
            .with(BudgetRole.GAP_ABSORBED, 1)
            .with(BudgetRole.GAP_IMPORT, 24)

        /** The fixed table for a registry version (v5's gap is bigger). */
        fun recommended(registryVersion: Int): Budgets = if (registryVersion >= 5) RECOMMENDED_V5 else RECOMMENDED
    }
}

// Builder inputs

/**
 * Where and how a transaction is built: the signer's x-only key (owner, buyer and payer),
 * the virtual's DAA score and past median time, the wall clock, the fee rate.
 */
class Env(
    val me: ByteArray,
    val blockDaa: Long,
    /** The virtual's past median time, unix ms. */
    val blockTimeMs: Long,
    val wallMs: Long,
    val feerate: Double = KachatNames.MIN_FEERATE,
    val budgets: Budgets = Budgets.RECOMMENDED
)

class GapRecord(val lo: ByteArray, val hi: ByteArray, val value: Long, val utxo: Utxo) {
    override fun equals(other: Any?): Boolean =
        other is GapRecord && lo.contentEquals(other.lo) && hi.contentEquals(other.hi) && value == other.value && utxo == other.utxo
    override fun hashCode(): Int = listOf(lo.contentHashCode(), hi.contentHashCode(), value, utxo).hashCode()
}

data class NameRecord(val fields: NameFields, val value: Long, val utxo: Utxo) {
    val name: String get() = fields.name
}

data class OfferRecord(
    val fields: OfferFields,
    val value: Long,
    val utxo: Utxo,
    /** The wanted name when known (the state holds only its key). */
    val name: String? = null
)

/** A salted commit. [utxo] is null until the commit transaction is accepted. */
class CommitRecord(
    val name: String,
    val owner: ByteArray,
    val salt: ByteArray,
    val value: Long,
    val utxo: Utxo?
) {
    override fun equals(other: Any?): Boolean =
        other is CommitRecord && name == other.name && owner.contentEquals(other.owner) &&
            salt.contentEquals(other.salt) && value == other.value && utxo == other.utxo
    override fun hashCode(): Int = listOf(name, owner.contentHashCode(), salt.contentHashCode(), value, utxo).hashCode()
}

/** The three registry UTXOs of an exit: gap (lo, key), the name, gap (key, hi). */
data class ExitParts(val below: GapRecord, val name: NameRecord, val above: GapRecord)

// Unsigned plan

sealed class Arg {
    class Bytes(val bytes: ByteArray) : Arg() {
        override fun equals(other: Any?): Boolean = other is Bytes && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    data class Num(val value: Long) : Arg()

    /** A SIGHASH_ALL Schnorr signature by `Env.me` over the input it sits in. */
    object Signature : Arg()
}

sealed class Unlock {
    object P2pk : Unlock()

    class Commit(val redeem: ByteArray) : Unlock() {
        override fun equals(other: Any?): Boolean = other is Commit && redeem.contentEquals(other.redeem)
        override fun hashCode(): Int = redeem.contentHashCode()
    }

    /** `<args> <dispatch tag> <push(redeem)>` */
    class Contract(val redeem: ByteArray, val tag: ByteArray, val args: List<Arg>) : Unlock() {
        override fun equals(other: Any?): Boolean =
            other is Contract && redeem.contentEquals(other.redeem) && tag.contentEquals(other.tag) && args == other.args
        override fun hashCode(): Int = listOf(redeem.contentHashCode(), tag.contentHashCode(), args).hashCode()
    }

    val needsSignature: Boolean
        get() = when (this) {
            is P2pk, is Commit -> true
            is Contract -> args.contains(Arg.Signature)
        }

    /** The signature script with [signature] (65 bytes: 64-byte Schnorr + sighash type). */
    fun signatureScript(signature: ByteArray): ByteArray = when (this) {
        is P2pk -> Codec.pushData(signature)
        is Commit -> Codec.pushData(signature) + Codec.pushData(redeem)
        is Contract -> {
            val s = KachatNames.Writer()
            for (a in args) {
                when (a) {
                    is Arg.Bytes -> s.bytes(Codec.pushData(a.bytes))
                    is Arg.Num -> s.bytes(Codec.pushInt(a.value))
                    is Arg.Signature -> s.bytes(Codec.pushData(signature))
                }
            }
            s.bytes(Codec.pushData(tag))
            s.bytes(Codec.pushData(redeem))
            s.toByteArray()
        }
    }
}

data class PlannedInput(
    val utxo: Utxo,
    val sequence: Long = 0,
    val unlock: Unlock,
    val role: BudgetRole,
    val label: String
)

data class PlannedOutput(val output: TxOutput, val label: String)

data class Costs(
    val size: Long,
    val computeMass: Long,
    val transientMass: Long,
    val normalizedTransient: Long,
    val storageMass: Long,
    /** 100 sompi/gram over max(compute, normalized transient) */
    val minFee: Long
)

/**
 * A built, not yet signed transaction: placeholder signatures of the right length sit in the
 * signature scripts, so sizes, masses and the fee are final. [signed] fills them.
 */
data class Plan(
    val op: String,
    val inputs: List<PlannedInput>,
    val outputs: List<PlannedOutput>,
    /** The transaction with placeholder signatures. */
    val unsignedTx: Tx,
    val entries: List<UtxoEntry>,
    val costs: Costs,
    /** Price paid as miner fee (register / extend / renew). */
    val priceFee: Long,
    val networkFee: Long,
    val notes: List<String>,
    /** `commit`: the record to keep (with its salt!) once the transaction is accepted. */
    val newCommit: CommitRecord? = null,
    /** `offer`: the offer to track once accepted. */
    val newOffer: OfferRecord? = null
) {
    val fee: Long get() = priceFee + networkFee
    val txid: ByteArray get() = unsignedTx.id

    /** Every input's SIGHASH_ALL Schnorr sighash (signature scripts do not enter it). */
    val sighashes: List<ByteArray> get() = inputs.indices.map { unsignedTx.sighash(it, entries) }

    /**
     * The signed transaction. [sign] returns the 64-byte BIP-340 Schnorr signature of a 32-byte
     * sighash by `Env.me`'s key; it is called once per input that needs one.
     */
    fun signed(sign: (ByteArray) -> ByteArray): Tx {
        val txInputs = unsignedTx.inputs.toMutableList()
        for ((i, input) in inputs.withIndex()) {
            if (!input.unlock.needsSignature) continue
            val sig = sign(unsignedTx.sighash(i, entries))
            if (sig.size != 64) throw Failure("a Schnorr signature is 64 bytes")
            txInputs[i] = txInputs[i].withSignatureScript(input.unlock.signatureScript(sig + byteArrayOf(KachatNames.SIGHASH_ALL)))
        }
        return unsignedTx.copy(inputs = txInputs)
    }

    /**
     * The signed transaction from ready 65-byte signatures (signature + sighash type) by input
     * index (test vectors, external signers).
     */
    fun signed(signatures: Map<Int, ByteArray>): Tx {
        val txInputs = unsignedTx.inputs.toMutableList()
        for ((i, input) in inputs.withIndex()) {
            if (!input.unlock.needsSignature) continue
            val sig = signatures[i]
            if (sig == null || sig.size != 65) throw Failure("no 65-byte signature for input $i")
            txInputs[i] = txInputs[i].withSignatureScript(input.unlock.signatureScript(sig))
        }
        return unsignedTx.copy(inputs = txInputs)
    }
}

// Builder

/**
 * The transaction builders of the kachat-domains CLI (`tools/kachat-names-cli/src/ops.rs`),
 * ported one to one (through iOS): same shapes, payloads, lock times, sequences, coin selection,
 * change and fee rule. Pure: they read decoded registry records with their live UTXOs and the
 * signer's spendable P2PK UTXOs, and never touch the network.
 *
 * Only over a verified testnet-10 manifest ([Manifest.verify], run by the constructor): the
 * builders never run against an unverified registry or another network.
 */
class Builder(val manifest: Manifest) {

    init {
        manifest.verify()
    }

    val params: Params get() = manifest.params
    val registryId: ByteArray get() = manifest.registryCovenantId

    // Shared assembly

    private sealed class FeeMode {
        /** add the signer's funding inputs and a change output back to the signer */
        data class Funded(val maxInputs: Int) : FeeMode()

        /** no funding: take the network fee out of output [index], which must keep [floor] */
        data class FromOutput(val index: Int, val cap: Long?, val floor: Long = KachatNames.MIN_CHANGE) : FeeMode()
    }

    private class Draft(
        val op: String,
        var inputs: List<PlannedInput>,
        var outputs: List<PlannedOutput>,
        var lockTime: Long = 0,
        var priceFee: Long = 0,
        var notes: MutableList<String> = mutableListOf(),
        var payload: ByteArray = ByteArray(0)
    )

    private fun assemble(
        inputs: List<PlannedInput>,
        outputs: List<PlannedOutput>,
        lockTime: Long,
        payload: ByteArray,
        env: Env
    ): Pair<Tx, List<UtxoEntry>> {
        val entries = inputs.map { it.utxo.entry }
        val tx = Tx(
            inputs = inputs.map {
                TxInput(
                    outpoint = it.utxo.outpoint,
                    signatureScript = it.unlock.signatureScript(PLACEHOLDER_SIGNATURE),
                    sequence = it.sequence,
                    computeBudget = env.budgets[it.role]
                )
            },
            outputs = outputs.map { it.output },
            lockTime = lockTime,
            payload = payload
        )
        // KIP-9 storage-mass commitment (independent of signature scripts)
        val m = Mass.storageMass(tx, entries) ?: return tx to entries
        return tx.copy(storageMass = m) to entries
    }

    private fun finish(draftIn: Draft, wallet: List<Utxo>, fee: FeeMode, env: Env): Plan {
        val d = draftIn
        val networkFee: Long
        when (fee) {
            is FeeMode.Funded -> {
                val fixedIn = d.inputs.sumOf { it.utxo.entry.amount }
                val fixedOut = d.outputs.sumOf { it.output.value }
                val used = d.inputs.map { it.utxo.outpoint }
                val slots = maxOf(0, fee.maxInputs - d.inputs.size)
                var est = 0L
                var last: Last? = null
                for (round in 0 until 5) {
                    val required = fixedOut + d.priceFee + est
                    val need = if (required > fixedIn) required - fixedIn else 0
                    var picked = select(wallet, used, need + KachatNames.TARGET_CHANGE, slots)
                    var have = picked.sumOf { it.entry.amount }
                    if (have < need + KachatNames.MIN_CHANGE && need > 0) {
                        picked = select(wallet, used, need + KachatNames.MIN_CHANGE, slots)
                    }
                    have = picked.sumOf { it.entry.amount }
                    if (fixedIn + have < required) {
                        val all = wallet.filter { it.outpoint !in used }.sumOf { it.entry.amount }
                        val bound = if (slots < wallet.size) " (at most $slots funding inputs fit)" else ""
                        throw Failure(
                            "${d.op}: insufficient funds: need ${kas(required - fixedIn)} more (outputs ${kas(fixedOut)} + price " +
                                "${kas(d.priceFee)} + network fee ~${kas(est)}), ${kas(all)} spendable$bound"
                        )
                    }
                    val inputs = d.inputs.toMutableList()
                    for (u in picked) {
                        inputs.add(PlannedInput(utxo = u, unlock = Unlock.P2pk, role = BudgetRole.P2PK, label = "funding (P2PK)"))
                    }
                    val change = fixedIn + have - required
                    val outputs = d.outputs.toMutableList()
                    val withChange = change >= KachatNames.MIN_CHANGE
                    if (withChange) {
                        outputs.add(PlannedOutput(TxOutput(value = change, script = Codec.p2pkScript(env.me)), "change"))
                    }
                    val (tx, _) = assemble(inputs, outputs, d.lockTime, d.payload, env)
                    val feeNow = Mass.networkFee(tx, env.feerate)
                    if (feeNow <= est) {
                        if (!withChange && change > 0) {
                            d.notes.add("no change output: the ${kas(change)} left over goes to the miner")
                        }
                        last = Last(inputs, outputs, change, withChange)
                        break
                    }
                    est = feeNow
                }
                val l = last ?: throw Failure("${d.op}: fee did not converge")
                d.inputs = l.inputs
                d.outputs = l.outputs
                networkFee = if (l.withChange) est else est + l.change
            }
            is FeeMode.FromOutput -> {
                val index = fee.index
                val totalIn = d.inputs.sumOf { it.utxo.entry.amount }
                val others = d.outputs.withIndex().filter { it.index != index }.sumOf { it.value.output.value }
                // provisional value (zero would break the KIP-9 storage-mass formula)
                val taken = others + d.priceFee
                d.outputs = d.outputs.toMutableList().also {
                    it[index] = it[index].copy(output = it[index].output.withValue(maxOf(if (totalIn > taken) totalIn - taken else 0, 1)))
                }
                val (tx, _) = assemble(d.inputs, d.outputs, d.lockTime, d.payload, env)
                val f = Mass.networkFee(tx, env.feerate)
                if (fee.cap != null && f > fee.cap) {
                    throw Failure("${d.op}: network fee ${kas(f)} exceeds the contract's maxFee ${kas(fee.cap)}")
                }
                if (totalIn < taken + f) throw Failure("${d.op}: inputs do not cover the outputs and the fee")
                val v = totalIn - taken - f
                if (v < fee.floor) throw Failure("${d.op}: output $index would be only ${kas(v)}")
                d.outputs = d.outputs.toMutableList().also { it[index] = it[index].copy(output = it[index].output.withValue(v)) }
                networkFee = f
            }
        }
        if (d.inputs.size > 255 || d.outputs.size > 255) throw Failure("too many inputs/outputs")
        val (tx, entries) = assemble(d.inputs, d.outputs, d.lockTime, d.payload, env)
        val totalIn = entries.sumOf { it.amount }
        val totalOut = tx.outputs.sumOf { it.value }
        if (totalIn < totalOut || totalIn - totalOut != d.priceFee + networkFee) {
            throw Failure("${d.op}: fee bookkeeping does not balance")
        }
        return Plan(
            op = d.op, inputs = d.inputs, outputs = d.outputs, unsignedTx = tx, entries = entries,
            costs = costs(tx), priceFee = d.priceFee, networkFee = networkFee, notes = d.notes.toList(),
            newCommit = null, newOffer = null
        )
    }

    private class Last(val inputs: List<PlannedInput>, val outputs: List<PlannedOutput>, val change: Long, val withChange: Boolean)

    // Checks

    private fun checkLive(label: String, utxo: Utxo, value: Long, covenant: ByteArray?) {
        if (utxo.entry.amount != value) {
            throw Failure("$label: live UTXO holds ${kas(utxo.entry.amount)}, not ${kas(value)}")
        }
        if (!utxo.entry.covenantId.contentEquals(covenant)) throw Failure("$label: live UTXO has the wrong covenant id")
    }

    private fun requireOwner(env: Env, n: NameRecord) {
        if (!n.fields.owner.contentEquals(env.me)) throw Failure("${n.name} is owned by another key")
    }

    private fun registryOutput(value: Long, script: ByteArray): TxOutput =
        TxOutput(value = value, script = script, covenant = CovenantBinding(authorizingInput = 0, covenantId = registryId))

    private fun gapOutput(lo: ByteArray, hi: ByteArray): TxOutput =
        registryOutput(params.gapValue, manifest.gap.script(Codec.gapState(lo, hi)))

    private fun nameOutput(f: NameFields): TxOutput = registryOutput(params.bond, manifest.name.script(f.encoded))

    private fun nameInput(n: NameRecord, entry: String, args: List<Arg>, role: BudgetRole, label: String): PlannedInput {
        val unlock = Unlock.Contract(manifest.name.redeem(n.fields.encoded), manifest.name.tag(entry), args)
        return PlannedInput(utxo = n.utxo, unlock = unlock, role = role, label = label)
    }

    private fun gapInput(g: GapRecord, entry: String, args: List<Arg>, role: BudgetRole, label: String): PlannedInput {
        val unlock = Unlock.Contract(manifest.gap.redeem(Codec.gapState(g.lo, g.hi)), manifest.gap.tag(entry), args)
        return PlannedInput(utxo = g.utxo, unlock = unlock, role = role, label = label)
    }

    private fun offerInput(o: OfferRecord, entry: String, args: List<Arg>, role: BudgetRole, label: String): PlannedInput {
        val unlock = Unlock.Contract(manifest.offer.redeem(o.fields.encoded), manifest.offer.tag(entry), args)
        return PlannedInput(utxo = o.utxo, unlock = unlock, role = role, label = label)
    }

    private fun yearsCheck(years: Long) {
        if (years < 1 || years > params.maxYears) throw Failure("years must be 1..${params.maxYears}")
    }

    // Commit / register

    /**
     * A salted commit: `P2SH(0x20 c 0x75 0x20 ownerKey 0xac)` worth 0.2 KAS, no payload (a
     * payload would reveal the name). Keep `newCommit` (the salt) until the registration.
     */
    fun commit(env: Env, wallet: List<Utxo>, name: String, salt: ByteArray): Plan {
        Codec.validate(name)
        if (salt.size != 32) throw Failure("the salt is 32 bytes")
        val c = Codec.commitment(name, env.me, salt)
        val redeem = Codec.commitRedeem(c, env.me)
        val out = TxOutput(value = KachatNames.COMMIT_VALUE, script = Codec.p2shScript(redeem))
        val d = Draft(op = "commit $name", inputs = emptyList(), outputs = listOf(PlannedOutput(out, "commit P2SH")))
        val plan = finish(d, wallet, FeeMode.Funded(KachatNames.MAX_INPUTS), env)
        val entry = UtxoEntry(amount = KachatNames.COMMIT_VALUE, script = out.script, blockDaaScore = 0, covenantId = null)
        return plan.copy(
            newCommit = CommitRecord(
                name = name, owner = env.me, salt = salt, value = KachatNames.COMMIT_VALUE,
                utxo = Utxo(Outpoint(plan.txid, 0), entry)
            )
        )
    }

    /**
     * Register `commit.name` for [years] periods: [gap.register, commit, funding] -> [gap
     * (lo,key), gap (key,hi), name (periodStart = now), change]; lock time [now], commit sequence
     * `tCommit`. The price is the baked one (registry v4, iOS 0ed15e9): the registration price for
     * the first period, the renewal price for each further one.
     */
    fun register(env: Env, wallet: List<Utxo>, gap: GapRecord, commit: CommitRecord, years: Long, now: Long): Plan {
        val name = commit.name
        Codec.validate(name)
        if (!commit.owner.contentEquals(env.me)) throw Failure("the commit for $name is for another owner")
        val commitUtxo = commit.utxo ?: throw Failure("the commit for $name is not on chain yet")
        yearsCheck(years)
        val key = Codec.key(name)
        if (!KachatNames.precedes(gap.lo, key) || !KachatNames.precedes(key, gap.hi)) {
            throw Failure("$name is not inside that gap")
        }
        checkLive("gap", gap.utxo, params.gapValue, registryId)
        val redeem = Codec.commitRedeem(Codec.commitment(name, env.me, commit.salt), env.me)
        if (!commitUtxo.entry.script.contentEquals(Codec.p2shScript(redeem))) throw Failure("commit UTXO script does not match the salt")
        if (now <= 0 || now < KachatNames.LOCK_TIME_THRESHOLD) throw Failure("now must be a unix-ms timestamp")
        // registry v5: closed until the migration deadline (the contract refuses it; iOS 6f18475)
        if (!params.registerOpen(now)) throw Failure.registrationNotOpen(params.migration?.deadlineMs ?: 0L)

        val nameLength = name.toByteArray(Charsets.UTF_8).size
        val price = params.registerCost(nameLength, years)
        val expires = now + years * params.periodMs
        val fields = NameFields(name = name, owner = env.me, price = 0, periodStart = now, expiresAt = expires)
        val notes = mutableListOf<String>()
        val matureAt = commitUtxo.entry.blockDaaScore + params.tCommit
        if (env.blockDaa < matureAt) {
            notes.add("commit not mature yet: valid from DAA $matureAt (now ${env.blockDaa})")
        }
        if (expires + params.graceMs < env.wallMs) {
            notes.add("backdated: this name is already past expiresAt + grace (reclaimable at once)")
        } else if (expires < env.wallMs) {
            notes.add("backdated: this name is already expired (in grace)")
        }
        val gapIn = gapInput(
            gap, "register",
            listOf(
                Arg.Bytes(name.toByteArray(Charsets.UTF_8)), Arg.Bytes(env.me), Arg.Bytes(commit.salt), Arg.Num(now), Arg.Num(years),
                Arg.Bytes(manifest.name.prefix), Arg.Bytes(manifest.name.suffix)
            ),
            BudgetRole.GAP_REGISTER, "gap register"
        )
        val commitIn = PlannedInput(
            utxo = commitUtxo, sequence = params.tCommit, unlock = Unlock.Commit(redeem), role = BudgetRole.COMMIT,
            label = "commit for $name"
        )
        val d = Draft(
            op = "register $name ($years period(s))",
            inputs = listOf(gapIn, commitIn),
            outputs = listOf(
                PlannedOutput(gapOutput(gap.lo, key), "gap (lo, key)"),
                PlannedOutput(gapOutput(key, gap.hi), "gap (key, hi)"),
                PlannedOutput(nameOutput(fields), "name $name")
            )
        )
        d.lockTime = now
        d.priceFee = price
        d.notes = notes
        d.payload = Codec.namePayload("register", name)
        return finish(d, wallet, FeeMode.Funded(KachatNames.MAX_INPUTS_FEE_ENTRY), env)
    }

    /**
     * Spend an unused commit back to its owner (the name was taken meanwhile, or the owner
     * changed their mind): [commit (owner sig + redeem)] -> [P2PK(owner), the commit's value
     * less the network fee]. No funding, no payload (the name stays hidden), sequence 0.
     */
    fun cancelCommit(env: Env, commit: CommitRecord): Plan {
        if (!commit.owner.contentEquals(env.me)) throw Failure("the commit for ${commit.name} is for another owner")
        val u = commit.utxo ?: throw Failure("the commit for ${commit.name} is not on chain")
        if (commit.salt.size != 32) throw Failure("the salt is 32 bytes")
        val redeem = Codec.commitRedeem(Codec.commitment(commit.name, env.me, commit.salt), env.me)
        if (!u.entry.script.contentEquals(Codec.p2shScript(redeem))) throw Failure("commit UTXO script does not match the salt")
        if (u.entry.covenantId != null) throw Failure("a commit carries no covenant id")
        val d = Draft(
            op = "cancel commit ${commit.name}",
            inputs = listOf(
                PlannedInput(utxo = u, unlock = Unlock.Commit(redeem), role = BudgetRole.COMMIT, label = "commit for ${commit.name} (owner sig)")
            ),
            outputs = listOf(PlannedOutput(TxOutput(value = 0, script = Codec.p2pkScript(env.me)), "back to the owner"))
        )
        return finish(d, emptyList(), FeeMode.FromOutput(index = 0, cap = null, floor = CANCEL_FLOOR_VALUE), env)
    }

    // Name entries

    /**
     * Anyone extends the current period (a gift needs no signature): [name.extend(years),
     * funding] -> [continuation (periodStart kept, expiresAt + years periods), change]. Lock time
     * 0, every sequence 0. Valid any time while `expiresAt + years <= periodStart + maxYears` (in
     * periods). Pays the renewal price per period (iOS 3ef2ec2, 0ed15e9, ops.rs).
     */
    fun extend(env: Env, wallet: List<Utxo>, name: NameRecord, years: Long): Plan {
        val n = name
        yearsCheck(years)
        checkLive(n.name, n.utxo, params.bond, registryId)
        val f = n.fields
        val room = params.extendableYears(f)
        if (years > room) {
            throw Failure(
                "extend ${n.name} by $years period(s) refused: it may be paid at most ${params.maxYears} periods past ${f.periodStart} and it is " +
                    "paid until ${f.expiresAt}, so $room can be added now; renew opens at ${params.renewOpens(f.expiresAt)}"
            )
        }
        val price = params.renewPrice(n.name.toByteArray(Charsets.UTF_8).size) * years
        val nf = f.extended(years, params.periodMs)
        val d = Draft(
            op = "extend ${n.name} ($years period(s))",
            inputs = listOf(nameInput(n, "extend", listOf(Arg.Num(years)), BudgetRole.NAME_EXTEND, "name extend($years)")),
            outputs = listOf(PlannedOutput(nameOutput(nf), "name ${n.name}"))
        )
        d.priceFee = price
        d.notes = mutableListOf(
            "extension price ${kas(price)} left as miner fee",
            "expiresAt ${f.expiresAt} -> ${nf.expiresAt}; periodStart ${f.periodStart} kept (at most ${params.maxYears} periods past it)"
        )
        d.payload = Codec.namePayload("extend", n.name)
        return finish(d, wallet, FeeMode.Funded(KachatNames.MAX_INPUTS_FEE_ENTRY), env)
    }

    /**
     * Anyone renews once the renewal window opened: [name.renew(years), funding] ->
     * [continuation (periodStart = old expiresAt, expiresAt + years periods), change]. Pays the
     * renewal price per period. Lock time = [renewLockTime] (timestamp domain), every input
     * sequence 0 (not final, as the CLTV needs). Before the window opens the plan is built but not
     * valid (a note says so); the actions refuse to submit it (iOS 3ef2ec2, 0ed15e9, ops.rs).
     */
    fun renew(env: Env, wallet: List<Utxo>, name: NameRecord, years: Long): Plan {
        val n = name
        yearsCheck(years)
        checkLive(n.name, n.utxo, params.bond, registryId)
        val f = n.fields
        val opens = params.renewOpens(f.expiresAt)
        if (opens < KachatNames.LOCK_TIME_THRESHOLD) throw Failure("${n.name}: expiresAt - renewWindowMs is not a timestamp")
        val lock = renewLockTime(env, params, f.expiresAt)
        val price = params.renewPrice(n.name.toByteArray(Charsets.UTF_8).size) * years
        val nf = f.renewed(years, params.periodMs)
        val d = Draft(
            op = "renew ${n.name} ($years period(s))",
            inputs = listOf(nameInput(n, "renew", listOf(Arg.Num(years)), BudgetRole.NAME_RENEW, "name renew($years)")),
            outputs = listOf(PlannedOutput(nameOutput(nf), "name ${n.name}"))
        )
        d.lockTime = lock
        d.priceFee = price
        d.notes = mutableListOf(
            "renewal price ${kas(price)} left as miner fee",
            "new period: periodStart ${f.periodStart} -> ${nf.periodStart} (the old expiry), expiresAt -> ${nf.expiresAt}",
            "lock time $lock >= window opening expiresAt - renewWindowMs = $opens"
        )
        if (!renewWindowOpen(env, params, f.expiresAt)) {
            d.notes.add(
                "renewal window not open: it opens at $opens (the network median time ${env.blockTimeMs} must pass it); " +
                    "use extend to add periods before"
            )
        }
        d.payload = Codec.namePayload("renew", n.name)
        return finish(d, wallet, FeeMode.Funded(KachatNames.MAX_INPUTS_FEE_ENTRY), env)
    }

    /** The owner transfers: new owner, listing cleared, period and expiry kept. */
    fun transfer(env: Env, wallet: List<Utxo>, name: NameRecord, newOwner: ByteArray): Plan {
        val n = name
        requireOwner(env, n)
        checkKey(newOwner, "the new owner")
        checkLive(n.name, n.utxo, params.bond, registryId)
        val d = Draft(
            op = "transfer ${n.name}",
            inputs = listOf(
                nameInput(n, "transfer", listOf(Arg.Bytes(newOwner), Arg.Signature), BudgetRole.NAME_TRANSFER, "name transfer (owner sig)")
            ),
            outputs = listOf(PlannedOutput(nameOutput(n.fields.withOwner(newOwner)), "name ${n.name}"))
        )
        d.payload = Codec.namePayload("transfer", n.name)
        return finish(d, wallet, FeeMode.Funded(KachatNames.MAX_INPUTS), env)
    }

    /** The owner lists at [price] sompi (0 = delist). */
    fun list(env: Env, wallet: List<Utxo>, name: NameRecord, price: Long): Plan {
        val n = name
        requireOwner(env, n)
        checkLive(n.name, n.utxo, params.bond, registryId)
        if (price < 0 || price > KachatNames.MAX_LIST_PRICE) throw Failure("price above the supply")
        val d = Draft(
            op = if (price == 0L) "delist ${n.name}" else "list ${n.name} at ${kas(price)}",
            inputs = listOf(nameInput(n, "list", listOf(Arg.Num(price), Arg.Signature), BudgetRole.NAME_LIST, "name list (owner sig)")),
            outputs = listOf(PlannedOutput(nameOutput(n.fields.withPrice(price)), "name ${n.name}"))
        )
        if (n.fields.expiresAt <= env.wallMs) {
            d.notes.add("the name is expired: the app refuses to list a name in grace")
        }
        d.payload = Codec.namePayload("list", n.name)
        return finish(d, wallet, FeeMode.Funded(KachatNames.MAX_INPUTS), env)
    }

    /**
     * The signer buys a listed name: [name.buy(me), funding] -> [continuation, payout of the
     * price to P2PK(owner) right after it, change].
     */
    fun buy(env: Env, wallet: List<Utxo>, name: NameRecord): Plan {
        val n = name
        checkLive(n.name, n.utxo, params.bond, registryId)
        if (n.fields.price <= 0) throw Failure("${n.name} is not listed")
        val d = Draft(
            op = "buy ${n.name} for ${kas(n.fields.price)}",
            inputs = listOf(nameInput(n, "buy", listOf(Arg.Bytes(env.me)), BudgetRole.NAME_BUY, "name buy(me)")),
            outputs = listOf(
                PlannedOutput(nameOutput(n.fields.withOwner(env.me)), "name ${n.name}"),
                PlannedOutput(TxOutput(value = n.fields.price, script = Codec.p2pkScript(n.fields.owner)), "payout to the seller")
            )
        )
        if (n.fields.expiresAt - params.expiresSoonMs < env.wallMs) {
            d.notes.add("expires soon: the buyer will have to renew it")
        }
        d.payload = Codec.namePayload("buy", n.name)
        return finish(d, wallet, FeeMode.Funded(KachatNames.MAX_INPUTS), env)
    }

    // Offers

    /**
     * Lock [amount] sompi for the registered name [target], made to its current owner (registry
     * v3, iOS e1e3455: only that owner can accept or decline it, so a change of owner ends it),
     * refundable by anyone from DAA [refundAfter]; the transaction carries the `kchat:1:offer:`
     * marker (with the seller).
     */
    fun offer(env: Env, wallet: List<Utxo>, target: NameRecord, amount: Long, refundAfter: Long): Plan {
        val name = target.name
        Codec.validate(name)
        if (amount <= params.offerMaxFee + KachatNames.MIN_CHANGE) throw Failure("offer too small")
        if (refundAfter < 0 || refundAfter >= KachatNames.LOCK_TIME_THRESHOLD) throw Failure("refundAfter is a DAA score")
        val fields = OfferFields(key = Codec.key(name), buyer = env.me, seller = target.fields.owner, refundAfter = refundAfter)
        val out = TxOutput(value = amount, script = manifest.offer.script(fields.encoded))
        val d = Draft(op = "offer ${kas(amount)} on $name", inputs = emptyList(), outputs = listOf(PlannedOutput(out, "offer P2SH")))
        if (target.fields.price > 0 && target.fields.price <= amount) {
            d.notes.add("$name is listed at or below this offer: buying it may be cheaper")
        }
        d.payload = Codec.offerPayload(fields)
        val plan = finish(d, wallet, FeeMode.Funded(KachatNames.MAX_INPUTS), env)
        val entry = UtxoEntry(amount = amount, script = out.script, blockDaaScore = 0, covenantId = null)
        return plan.copy(newOffer = OfferRecord(fields, amount, Utxo(Outpoint(plan.txid, 0), entry), name))
    }

    /**
     * The owner accepts: [name.transfer(buyer, sig), offer.accept(0, sellerSig)] -> [continuation
     * to the buyer, payout to the owner = offer - fee (fee <= maxFee)]. Only an offer made to this
     * owner (registry v3, iOS e1e3455).
     */
    fun acceptOffer(env: Env, name: NameRecord, offer: OfferRecord): Plan {
        val n = name
        val o = offer
        requireOwner(env, n)
        if (!o.fields.seller.contentEquals(env.me)) throw Failure("that offer was made to an earlier owner of ${n.name}")
        checkLive(n.name, n.utxo, params.bond, registryId)
        checkLive("offer", o.utxo, o.value, null)
        if (!o.fields.key.contentEquals(n.fields.key)) throw Failure("that offer is for another name")
        val d = Draft(
            op = "accept offer ${kas(o.value)} on ${n.name}",
            inputs = listOf(
                nameInput(n, "transfer", listOf(Arg.Bytes(o.fields.buyer), Arg.Signature), BudgetRole.NAME_TRANSFER, "name transfer(buyer) (owner sig)"),
                offerInput(o, "accept", listOf(Arg.Num(0), Arg.Signature), BudgetRole.OFFER_ACCEPT, "offer accept(0) (seller sig)")
            ),
            outputs = listOf(
                PlannedOutput(nameOutput(n.fields.withOwner(o.fields.buyer)), "name ${n.name} -> buyer"),
                PlannedOutput(TxOutput(value = 0, script = Codec.p2pkScript(n.fields.owner)), "payout to the owner")
            )
        )
        d.payload = Codec.namePayload("accept", n.name)
        return finish(d, emptyList(), FeeMode.FromOutput(index = 1, cap = params.offerMaxFee), env)
    }

    /**
     * The seller turns an offer down (registry v3, iOS e1e3455): [offer.decline(sellerSig)] alone
     * -> [back to the buyer, the offer less the network fee (<= maxFee)].
     */
    fun declineOffer(env: Env, offer: OfferRecord): Plan {
        val o = offer
        if (!o.fields.seller.contentEquals(env.me)) throw Failure("only the seller can decline this offer")
        checkLive("offer", o.utxo, o.value, null)
        val d = Draft(
            op = "decline offer ${kas(o.value)}",
            inputs = listOf(offerInput(o, "decline", listOf(Arg.Signature), BudgetRole.OFFER_DECLINE, "offer decline (seller sig)")),
            outputs = listOf(PlannedOutput(TxOutput(value = 0, script = Codec.p2pkScript(o.fields.buyer)), "back to the buyer"))
        )
        return finish(d, emptyList(), FeeMode.FromOutput(index = 0, cap = params.offerMaxFee), env)
    }

    /** The buyer takes the offer back. */
    fun withdrawOffer(env: Env, offer: OfferRecord): Plan {
        val o = offer
        if (!o.fields.buyer.contentEquals(env.me)) throw Failure("only the buyer can withdraw this offer")
        checkLive("offer", o.utxo, o.value, null)
        val d = Draft(
            op = "withdraw offer ${kas(o.value)}",
            inputs = listOf(offerInput(o, "withdraw", listOf(Arg.Signature), BudgetRole.OFFER_WITHDRAW, "offer withdraw (buyer sig)")),
            outputs = listOf(PlannedOutput(TxOutput(value = 0, script = Codec.p2pkScript(o.fields.buyer)), "back to the buyer"))
        )
        return finish(d, emptyList(), FeeMode.FromOutput(index = 0, cap = null), env)
    }

    /** Anyone refunds once DAA > refundAfter: 1 input, 1 output, lock time = refundAfter. */
    fun refundOffer(env: Env, offer: OfferRecord): Plan {
        val o = offer
        checkLive("offer", o.utxo, o.value, null)
        val d = Draft(
            op = "refund offer ${kas(o.value)}",
            inputs = listOf(offerInput(o, "refund", emptyList(), BudgetRole.OFFER_REFUND, "offer refund()")),
            outputs = listOf(PlannedOutput(TxOutput(value = 0, script = Codec.p2pkScript(o.fields.buyer)), "refund to the buyer"))
        )
        d.lockTime = o.fields.refundAfter
        if (env.blockDaa <= o.fields.refundAfter) {
            d.notes.add("not refundable yet: the virtual DAA must pass ${o.fields.refundAfter} (now ${env.blockDaa})")
        }
        return finish(d, emptyList(), FeeMode.FromOutput(index = 0, cap = params.offerMaxFee), env)
    }

    // The exit

    private fun exitChecks(x: ExitParts) {
        val key = x.name.fields.key
        if (!x.below.hi.contentEquals(key) || !x.above.lo.contentEquals(key)) throw Failure("the gaps do not sit on ${x.name.name}")
        checkLive("lower gap", x.below.utxo, params.gapValue, registryId)
        checkLive(x.name.name, x.name.utxo, params.bond, registryId)
        checkLive("upper gap", x.above.utxo, params.gapValue, registryId)
    }

    /** The owner releases: [merge, release(sig), absorbed] -> [merged gap, bond + gap value - fee]. */
    fun release(env: Env, parts: ExitParts): Plan {
        val x = parts
        requireOwner(env, x.name)
        exitChecks(x)
        val d = Draft(
            op = "release ${x.name.name}",
            inputs = listOf(
                gapInput(x.below, "merge", emptyList(), BudgetRole.GAP_MERGE, "gap merge"),
                nameInput(x.name, "release", listOf(Arg.Signature), BudgetRole.NAME_RELEASE, "name release (owner sig)"),
                gapInput(x.above, "absorbed", emptyList(), BudgetRole.GAP_ABSORBED, "gap absorbed")
            ),
            outputs = listOf(
                PlannedOutput(gapOutput(x.below.lo, x.above.hi), "merged gap"),
                PlannedOutput(TxOutput(value = 0, script = Codec.p2pkScript(env.me)), "bond + freed gap value - fee")
            )
        )
        d.payload = Codec.namePayload("release", x.name.name)
        return finish(d, emptyList(), FeeMode.FromOutput(index = 1, cap = null), env)
    }

    /**
     * Anyone reclaims a lapsed name: [merge, reclaim(), absorbed] -> [merged gap, the bond to
     * the last owner, the caller's bounty]; lock time = expiresAt + grace (unix ms).
     */
    fun reclaim(env: Env, parts: ExitParts): Plan {
        val x = parts
        exitChecks(x)
        val unlock = x.name.fields.expiresAt + params.graceMs
        if (unlock <= 0 || unlock < KachatNames.LOCK_TIME_THRESHOLD) throw Failure("expiresAt + grace is not a timestamp")
        val d = Draft(
            op = "reclaim ${x.name.name}",
            inputs = listOf(
                gapInput(x.below, "merge", emptyList(), BudgetRole.GAP_MERGE, "gap merge"),
                nameInput(x.name, "reclaim", emptyList(), BudgetRole.NAME_RECLAIM, "name reclaim()"),
                gapInput(x.above, "absorbed", emptyList(), BudgetRole.GAP_ABSORBED, "gap absorbed")
            ),
            outputs = listOf(
                PlannedOutput(gapOutput(x.below.lo, x.above.hi), "merged gap"),
                PlannedOutput(TxOutput(value = params.bond, script = Codec.p2pkScript(x.name.fields.owner)), "bond to the last owner"),
                PlannedOutput(TxOutput(value = 0, script = Codec.p2pkScript(env.me)), "bounty (caller)")
            )
        )
        d.lockTime = unlock
        if (env.blockTimeMs <= unlock) {
            d.notes.add("not reclaimable yet: the virtual median time must pass expiresAt + grace")
        }
        d.payload = Codec.namePayload("reclaim", x.name.name)
        return finish(d, emptyList(), FeeMode.FromOutput(index = 2, cap = null), env)
    }

    companion object {
        private val PLACEHOLDER_SIGNATURE: ByteArray = ByteArray(64) + byteArrayOf(KachatNames.SIGHASH_ALL)

        /**
         * The least a cancelled commit may return (its storage mass stays small: one 0.2 KAS
         * input, one output just under it).
         */
        const val CANCEL_FLOOR_VALUE: Long = 10_000_000L

        fun costs(tx: Tx): Costs {
            val compute = Mass.computeMass(tx)
            val normalized = Mass.normalizedTransient(tx)
            return Costs(
                size = Mass.size(tx), computeMass = compute, transientMass = Mass.transientMass(tx),
                normalizedTransient = normalized, storageMass = tx.storageMass,
                minFee = maxOf(compute, normalized) * 100
            )
        }

        /** `now` for a registration: wall clock - 3 min (the median time lags ~2.2 min), never at
         * or past the virtual's median time. */
        fun registerNow(env: Env): Long = minOf(env.wallMs - 180_000L, env.blockTimeMs - 1_000L)

        /**
         * The lock time of a renewal (ops.rs `renew_lock_time`, iOS 3ef2ec2): the
         * registration-style `now`, but never before the window opens -
         * `max(min(wall - 3 min, median time - 1 s), expiresAt - renewWindowMs)` (unix ms).
         * Final (and so valid) only while it is below the median time, i.e. once the window opened.
         */
        fun renewLockTime(env: Env, params: Params, expiresAt: Long): Long =
            maxOf(registerNow(env), params.renewOpens(expiresAt))

        /**
         * Whether the renewal window is open at [env] (ops.rs `renew_window_open`): the virtual's
         * past median time is past `expiresAt - renewWindowMs`. Before that no renewal is valid
         * (the mempool keeps no future-dated transactions), so the app refuses to submit one.
         */
        fun renewWindowOpen(env: Env, params: Params, expiresAt: Long): Boolean =
            env.blockTimeMs > params.renewOpens(expiresAt)

        /**
         * Pick funding UTXOs (largest first, then lowest output index, skipping [used]) worth at
         * least [target], at most [slots] of them. Returns what it found even if short.
         */
        private fun select(wallet: List<Utxo>, used: List<Outpoint>, target: Long, slots: Int): List<Utxo> {
            val sorted = wallet.withIndex()
                .filter { it.value.outpoint !in used }
                .sortedWith(
                    compareByDescending<IndexedValue<Utxo>> { it.value.entry.amount }
                        .thenBy { it.value.outpoint.index.toLong() and 0xffff_ffffL }
                        .thenBy { it.index }
                )
            val out = ArrayList<Utxo>()
            var sum = 0L
            for ((_, u) in sorted) {
                if (sum >= target || out.size >= slots) break
                sum += u.entry.amount
                out.add(u)
            }
            return out
        }

        private fun checkKey(key: ByteArray, what: String) {
            if (key.size != 32 || key.contentEquals(KachatNames.ZERO32)) throw Failure("$what must be a non-zero 32-byte x-only key")
        }

        /** `12.34000000 TKAS` (testnet-10 only, like iOS). */
        internal fun kas(sompi: Long): String =
            String.format(Locale.US, "%d.%08d TKAS", sompi / KachatNames.SOMPI_PER_KAS, sompi % KachatNames.SOMPI_PER_KAS)
    }
}
