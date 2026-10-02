package com.kachat.app.services.kachatnames

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kachat.app.services.kachatnames.KachatNames.Codec
import com.kachat.app.services.kachatnames.KachatNames.hex
import com.kachat.app.util.Blake3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The `.kachat` name core against the kachat-domains test vectors (test resource
 * `KachatNamesVectors.json`, copied from iOS KaChatTests/, written by `kachat-names-vectors` from
 * the CLI's own builders and validated by rusty-kaspa a41a333's consensus validator). A port of
 * iOS scripts/test_kachat_names_core.swift (KaChat a6cf1f6, 2989ea1; registry v2 - extend, the
 * renewal window, the period rules - from 3ef2ec2): every vector transaction is
 * rebuilt with the recorded signatures fed in and must be byte-identical (inputs, sequences,
 * budgets, outputs, covenant bindings, lock time, payload, masses, fee, rest and full preimages,
 * every sighash, every signature script, txid and tx hash). Also codecs, BLAKE3 against Rust
 * `blake3::hash`, and the manifest checks. The official BLAKE3 vectors are in `Blake3Test`.
 */
class KachatNamesCoreTest {

    private class Report {
        var pass = 0
        var fail = 0
        val failures = ArrayList<String>()

        fun check(ok: Boolean, what: () -> String) {
            if (ok) pass++ else { fail++; failures.add(what()) }
        }

        fun <T> eq(a: T, b: T, what: String) = check(a == b) { "$what: got $a expected $b" }

        fun eqHex(a: ByteArray?, b: String?, what: String) {
            val h = a?.let { hex(it) }
            check(h == b) { "$what: got ${h?.take(160)} expected ${b?.take(160)}" }
        }

        fun assertClean() {
            assertTrue("${failures.size} failures:\n" + failures.take(40).joinToString("\n"), fail == 0)
        }
    }

    private val vectors: JsonObject by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("KachatNamesVectors.json")
            ?: error("KachatNamesVectors.json missing from the test resources")
        JsonParser.parseString(stream.bufferedReader().use { it.readText() }).asJsonObject
    }

    // JSON helpers (the Swift script's u64 / i64 / s / hx)

    private fun JsonElement?.isNull() = this == null || this.isJsonNull
    private fun JsonObject.o(k: String): JsonObject = get(k).asJsonObject
    private fun JsonObject.arr(k: String): List<JsonElement> = get(k).asJsonArray.toList()
    private fun JsonObject.l(k: String): Long = get(k).asLong
    private fun JsonObject.s(k: String): String = get(k).asString
    private fun JsonObject.hx(k: String): ByteArray = KachatNames.unhex(s(k))
    private fun JsonObject.optS(k: String): String? = get(k).takeUnless { it.isNull() }?.asString

    private fun utxo(u: JsonObject) = Utxo(
        outpoint = Outpoint(u.hx("txid"), u.l("index").toInt()),
        entry = UtxoEntry(
            amount = u.l("amount"), scriptVersion = u.l("scriptVersion").toInt(), script = u.hx("script"),
            blockDaaScore = u.l("blockDaaScore"), isCoinbase = u.get("isCoinbase").asBoolean,
            covenantId = u.optS("covenantId")?.let { KachatNames.unhex(it) }
        )
    )

    private fun gapRec(g: JsonObject) = GapRecord(g.hx("lo"), g.hx("hi"), g.l("value"), utxo(g.o("utxo")))

    private fun nameRec(n: JsonObject): NameRecord {
        val f = NameFields(n.hx("key"), Codec.padded(n.s("name")), n.hx("owner"), n.l("price"), n.l("periodStart"), n.l("expiresAt"))
        return NameRecord(f, n.l("value"), utxo(n.o("utxo")))
    }

    private fun offerRec(o: JsonObject) = OfferRecord(
        OfferFields(o.hx("key"), o.hx("buyer"), o.l("refundAfter")), o.l("value"), utxo(o.o("utxo")), o.optS("name")
    )

    private fun commitRec(c: JsonObject) = CommitRecord(c.s("name"), c.hx("owner"), c.hx("salt"), c.l("value"), utxo(c.o("utxo")))

    private fun manifest(json: JsonObject = vectors.o("manifest")): Manifest = Manifest.decode(json.toString())

    // Codecs

    @Test
    fun codecs() {
        val r = Report()
        val c = vectors.o("codecs")
        for (k in c.arr("nameKeys").map { it.asJsonObject }) {
            val name = k.s("name")
            r.eqHex(Codec.key(name), k.s("key"), "key($name)")
            r.eqHex(Codec.padded(name), k.s("padded"), "padded($name)")
            r.check(Codec.isValid(name)) { "valid $name" }
        }
        for (k in c.arr("commitments").map { it.asJsonObject }) {
            val name = k.s("name")
            val cm = Codec.commitment(name, k.hx("owner"), k.hx("salt"))
            r.eqHex(cm, k.s("commitment"), "commitment($name)")
            val redeem = Codec.commitRedeem(cm, k.hx("owner"))
            r.eqHex(redeem, k.s("redeem"), "commitRedeem($name)")
            r.eqHex(Codec.p2shScript(redeem), k.s("spk"), "commit spk($name)")
        }
        for (b in c.arr("blake3").map { it.asJsonObject }) {
            val n = b.l("len").toInt()
            val input = ByteArray(n) { ((it * 7) % 256).toByte() }
            r.eqHex(Blake3.hash(input), b.s("hash"), "blake3 len $n vs Rust blake3::hash")
        }
        for (x in c.arr("num8").map { it.asJsonObject }) {
            val v = x.s("value").toLong()
            r.eqHex(Codec.num8(v), x.s("num8"), "num8($v)")
            r.eq(Codec.decodeNum8(Codec.num8(v)), v, "decodeNum8($v)")
        }
        for (x in c.arr("scriptNumbers").map { it.asJsonObject }) {
            val v = x.s("value").toLong()
            r.eqHex(Codec.pushInt(v), x.s("push"), "pushInt($v)")
        }
        for (x in c.arr("pushes").map { it.asJsonObject }) {
            val data = x.hx("data")
            r.eqHex(Codec.pushData(data), x.s("push"), "pushData(len ${data.size})")
            val parsed = Codec.parsePushes(x.hx("push"))
            r.check(parsed.size == 1 && parsed[0].contentEquals(data)) { "parsePushes(len ${data.size})" }
        }
        val st = c.o("states")
        val m = manifest()
        val g = st.o("gap")
        val gs = Codec.gapState(g.hx("lo"), g.hx("hi"))
        r.eqHex(gs, g.s("state"), "gap state")
        r.eqHex(m.gap.script(gs), g.s("spk"), "gap spk")
        val n = st.o("name")
        val nf = NameFields(n.s("name"), n.hx("owner"), n.l("price"), n.l("periodStart"), n.l("expiresAt"))
        r.eqHex(nf.encoded, n.s("state"), "name state")
        r.eq(nf.encoded.size, 126, "name state is 126 bytes (registry v2)")
        r.eqHex(m.name.script(nf.encoded), n.s("spk"), "name spk")
        r.eq(Codec.decodeNameState(nf.encoded), nf, "decode name state")
        r.eq(nf.name, n.s("name"), "unpadded name")
        val o = st.o("offer")
        val of = OfferFields(o.hx("key"), o.hx("buyer"), o.l("refundAfter"))
        r.eqHex(of.encoded, o.s("state"), "offer state")
        r.eqHex(m.offer.script(of.encoded), o.s("spk"), "offer spk")
        r.eq(Codec.decodeOfferState(of.encoded), of, "decode offer state")
        r.eq(hex(Codec.decodeGapState(gs).second), g.s("hi"), "decode gap state")
        for (cv in c.arr("covenantIds").map { it.asJsonObject }) {
            val op = cv.o("outpoint")
            val outpoint = Outpoint(op.hx("txid"), op.l("index").toInt())
            val outs = cv.arr("outputs").map { it.asJsonObject }.map {
                it.l("index").toInt() to TxOutput(it.l("value"), it.l("scriptVersion").toInt(), it.hx("script"), null)
            }
            r.eqHex(Codec.covenantId(outpoint, outs), cv.s("covenantId"), "covenant id (2 outputs)")
            r.eqHex(Codec.covenantId(outpoint, listOf(outs[0])), cv.s("covenantIdFirstOnly"), "covenant id (1 output)")
        }
        val p = c.o("p2pk")
        r.eqHex(Codec.p2pkScript(p.hx("xonly")), p.s("spk"), "p2pk spk")
        r.eqHex(Codec.p2pkKey(Codec.p2pkScript(p.hx("xonly"))), p.s("xonly"), "p2pk key")
        // silverscript template.rs golden values
        r.eqHex(Codec.templateHash(ByteArray(0), ByteArray(0)), "e572dff82304700b856a555ac3a4558d0df3646a3727816500270a93c66aac1e", "template hash golden (empty)")
        r.eqHex(
            Codec.templateHash(byteArrayOf(0x00, 0xff.toByte()), byteArrayOf(0x10, 0x00, 0x80.toByte())),
            "6616a66757315de0221cb2acba729113cebde31f8d3ca7fa93878a0584b96905", "template hash golden (classic)"
        )
        // name rules
        for (bad in listOf("", "-a", "a-", "A", "a_b", "é", "a".repeat(33), "a.b")) {
            r.check(!Codec.isValid(bad)) { "invalid name accepted: $bad" }
        }
        r.eq(Codec.normalize("  Alice.KACHAT "), "alice", "normalize")
        println("codecs: ${r.pass} checks pass, ${r.fail} fail")
        r.assertClean()
    }

    // Manifest

    @Test
    fun manifestVerification() {
        val m = manifest()
        m.verify()
        assertTrue("the vectors' manifest is a dry run", m.isDryRun)
        // tampering is caught
        val j = vectors.o("manifest").deepCopy()
        j.addProperty("registryCovenantId", "ab".repeat(32))
        assertFalse("manifest with a wrong registry id verified", runCatching { manifest(j).verify() }.isSuccess)
        val j2 = vectors.o("manifest").deepCopy()
        val gapArt = j2.o("artifacts").o("KachatGap")
        gapArt.addProperty("suffixHex", gapArt.s("suffixHex").dropLast(2) + "00")
        assertFalse("manifest with a tampered gap suffix verified", runCatching { manifest(j2).verify() }.isSuccess)
        val j3 = vectors.o("manifest").deepCopy()
        j3.addProperty("network", "mainnet")
        assertFalse("mainnet manifest verified", runCatching { manifest(j3).verify() }.isSuccess)
    }

    /**
     * The manifest bundled for phase 2 (assets/kachat-names-testnet-10.json) is the live registry:
     * registry v2 since iOS 6b6cace (genesis e20325f7...a426 at DAA 586,328,979), so it verifies
     * against the v2 pinned templates instead of being refused as the outdated v1 one.
     */
    @Test
    fun bundledManifestVerifies() {
        val m = Manifest.decode(File("src/main/assets/${Manifest.ASSET_NAME}").readBytes())
        m.verify()
        assertFalse("the bundled manifest must not be a dry run", m.isDryRun)
        assertEquals("82f4315c8f7b3e0e76fc2f77466fe7651d2c1fac4e0b5810d4da878a9cfa0f89", hex(m.registryCovenantId))
        Builder(m)
    }

    /** The set of { lock time } and every input sequence of a plan (extend: all zero). */
    private fun tx0LockAndSequences(p: Plan): Set<Long> = (listOf(p.unsignedTx.lockTime) + p.unsignedTx.inputs.map { it.sequence }).toSet()

    /**
     * The registry v2 period rules on their own (KACHAT_NAMES.md 4.1, ops.rs; the Swift script's
     * `runPeriodRules`, iOS 3ef2ec2): what extend may add, when renew opens, its lock time, the
     * refusals, the fixed budget table, and a v1 manifest recognised as outdated.
     */
    @Test
    fun periodRules() {
        val r = Report()
        val m = manifest()
        val p = m.params
        val y = KachatNames.YEAR_MS
        r.eq(p.renewWindowMs, 864_000_000L, "renewWindowMs from the manifest")
        r.eq(vectors.l("renewWindowMs"), p.renewWindowMs, "renewWindowMs matches the vectors")
        val start = 2_000_000_000_000L
        r.eq(p.extendableYears(start, start + y), 1L, "1-year registration: extend by 1")
        r.eq(p.extendableYears(start, start + 2 * y), 0L, "2-year registration: no extend")
        r.eq(p.extendableYears(start, start + y + 1), 0L, "a period holding just over a year: no extend")
        r.eq(p.extendableYears(start, start + 3 * y), 0L, "over-full period: no extend")
        r.eq(p.extendableYears(start, start), 2L, "empty period: 2 years")
        val f = NameFields("alice", ByteArray(32) { 7 }, 0, start, start + y)
        r.eq(f.extended(1).periodStart, start, "extend keeps periodStart")
        r.eq(f.extended(1).expiresAt, start + 2 * y, "extend adds a year")
        r.eq(f.renewed(2).periodStart, start + y, "renew starts at the old expiry")
        r.eq(f.renewed(2).expiresAt, start + 3 * y, "renew adds from the old expiry")
        r.eq(f.withOwner(ByteArray(32) { 9 }).periodStart, start, "transfer keeps periodStart")
        r.eq(f.withPrice(5).periodStart, start, "list keeps periodStart")
        r.eq(runCatching { Codec.decodeNameState(f.encoded) }.getOrNull(), f, "126-byte state round trip")
        r.check(runCatching { Codec.decodeNameState(f.encoded.copyOfRange(0, 117)) }.isFailure) { "a 117-byte (v1) state is refused" }
        val opens = p.renewOpens(f.expiresAt)
        r.eq(opens, f.expiresAt - 864_000_000L, "renew opens 10 days before expiry")
        val before = Env(me = f.owner, blockDaa = 1, blockTimeMs = opens - 60_000, wallMs = opens + 60_000)
        r.check(!Builder.renewWindowOpen(before, p, f.expiresAt)) { "window closed while the median time is before the opening" }
        r.eq(Builder.renewLockTime(before, p, f.expiresAt), opens, "lock time never before the opening")
        val at = Env(me = f.owner, blockDaa = 1, blockTimeMs = opens, wallMs = opens + 180_000)
        r.check(!Builder.renewWindowOpen(at, p, f.expiresAt)) { "window closed at exactly the opening (the median time must pass it)" }
        val after = Env(me = f.owner, blockDaa = 1, blockTimeMs = opens + 3_600_000, wallMs = opens + 3_700_000)
        r.check(Builder.renewWindowOpen(after, p, f.expiresAt)) { "window open an hour later" }
        r.eq(Builder.renewLockTime(after, p, f.expiresAt), opens + 3_520_000, "lock time = wall - 3 min once open")
        // the builders refuse what the contract refuses, and say so
        val b = Builder(m)
        val ext = vectors.arr("steps").map { it.asJsonObject }.firstOrNull { it.s("op") == "extend" }
        if (ext != null) {
            val env0 = ext.o("env")
            val env = Env(me = env0.hx("me"), blockDaa = env0.l("blockDaa"), blockTimeMs = env0.l("blockTimeMs"), wallMs = env0.l("wallMs"))
            var n = nameRec(ext.o("records").o("name"))
            val wallet = ext.arr("wallet").map { utxo(it.asJsonObject) }
            r.check(runCatching { b.extend(env, wallet, n, 2) }.isFailure) { "extend past 2 years from periodStart refused" }
            n = n.copy(fields = n.fields.extended(1))
            r.check(runCatching { b.extend(env, wallet, n, 1) }.isFailure) { "a second extend of a full period refused" }
            r.check(runCatching { b.extend(env, wallet, n, 0) }.isFailure) { "extend by 0 refused" }
            // renew before the window: built (a note says it is not open) with the opening as lock time
            val plan = runCatching { b.renew(env, wallet, n, 1) }.getOrNull()
            if (plan != null) {
                r.eq(plan.unsignedTx.lockTime, p.renewOpens(n.fields.expiresAt), "early renew: lock time = the window opening")
                r.check(plan.notes.any { it.startsWith("renewal window not open") }) { "early renew: noted as not open" }
                r.check(!Builder.renewWindowOpen(env, p, n.fields.expiresAt)) { "early renew: window closed" }
            } else {
                r.check(false) { "early renew plan not built" }
            }
            r.check(runCatching { b.renew(env, wallet, n, 3) }.isFailure) { "renew by 3 refused" }
        } else {
            r.check(false) { "no extend step in the vectors" }
        }
        // the fixed budgets are the vectors' table, entry for entry
        val recommended = vectors.o("recommendedBudgets")
        r.eq(recommended.keySet().toSet(), BudgetRole.entries.map { it.raw }.toSet(), "budget roles = recommendedBudgets keys")
        for (role in BudgetRole.entries) {
            r.eq(Budgets.RECOMMENDED[role].toLong(), recommended.l(role.raw), "recommended budget ${role.raw}")
        }
        // a registry v1 manifest is recognised as outdated, never trusted
        val v1 = vectors.o("manifest").deepCopy()
        v1.o("params").remove("renewWindowMs")
        val err = runCatching { manifest(v1) }.exceptionOrNull()
        r.check(err != null) { "a manifest without renewWindowMs decoded" }
        r.check((err as? KachatNames.Failure)?.isOutdatedRegistry == true) { "a manifest without renewWindowMs is the outdated registry: $err" }
        // and so is one carrying the v1 name template hash
        val v1b = vectors.o("manifest").deepCopy()
        v1b.o("artifacts").o("KachatName").addProperty("templateHash", Manifest.V1_TEMPLATE_HASHES.getValue("KachatName"))
        val errB = runCatching { manifest(v1b) }.exceptionOrNull()
        r.check((errB as? KachatNames.Failure)?.isOutdatedRegistry == true) { "a manifest with the v1 name template is the outdated registry: $errB" }
        println("period rules: ${r.pass} checks pass, ${r.fail} fail")
        r.assertClean()
    }

    /** One vector step through the builder its `op` names (the Swift script's switch). */
    private fun build(b: Builder, st: JsonObject, env: Env): Plan {
        val wallet = st.arr("wallet").map { utxo(it.asJsonObject) }
        val args = st.o("args")
        val rec = st.o("records")
        return when (st.s("op")) {
            "commit" -> b.commit(env, wallet, args.s("name"), args.hx("salt"))
            "register" -> b.register(env, wallet, gapRec(rec.o("gap")), commitRec(rec.o("commit")), args.l("years"), args.l("now"))
            "extend" -> b.extend(env, wallet, nameRec(rec.o("name")), args.l("years"))
            "renew" -> b.renew(env, wallet, nameRec(rec.o("name")), args.l("years"))
            "transfer" -> b.transfer(env, wallet, nameRec(rec.o("name")), args.hx("newOwner"))
            "list" -> b.list(env, wallet, nameRec(rec.o("name")), args.l("price"))
            "buy" -> b.buy(env, wallet, nameRec(rec.o("name")))
            "offer" -> b.offer(
                env, wallet, args.s("name"), args.l("amount"), args.l("refundAfter"),
                rec.get("target").takeUnless { it.isNull() }?.let { nameRec(it.asJsonObject) }
            )
            "acceptOffer" -> b.acceptOffer(env, nameRec(rec.o("name")), offerRec(rec.o("offer")))
            "withdrawOffer" -> b.withdrawOffer(env, offerRec(rec.o("offer")))
            "refundOffer" -> b.refundOffer(env, offerRec(rec.o("offer")))
            "release" -> b.release(env, ExitParts(gapRec(rec.o("below")), nameRec(rec.o("name")), gapRec(rec.o("above"))))
            "reclaim" -> b.reclaim(env, ExitParts(gapRec(rec.o("below")), nameRec(rec.o("name")), gapRec(rec.o("above"))))
            "cancelCommit" -> b.cancelCommit(env, commitRec(rec.o("commit")))
            else -> throw KachatNames.Failure("unknown op ${st.s("op")}")
        }
    }

    /**
     * The app commits the fixed budget table, not the measured budgets: every step still builds
     * with it (iOS checked these rebuilt transactions with `kachat-names-vectors check`; here
     * only that they build, balance, and cost at least the measured version).
     */
    @Test
    fun vectorStepsBuildWithRecommendedBudgets() {
        val b = Builder(manifest())
        for (st in vectors.arr("steps").map { it.asJsonObject }) {
            val env0 = st.o("env")
            val env = Env(me = env0.hx("me"), blockDaa = env0.l("blockDaa"), blockTimeMs = env0.l("blockTimeMs"), wallMs = env0.l("wallMs"))
            val plan = build(b, st, env)
            val label = st.s("label")
            assertEquals("$label: op", label, plan.op)
            assertTrue("$label: fee covers the relay floor", plan.networkFee >= plan.costs.minFee)
            assertTrue("$label: no cheaper than with measured budgets", plan.fee >= st.o("expected").l("fee"))
        }
    }

    // Transactions

    @Test
    fun vectorTransactionsAreByteIdentical() {
        val r = Report()
        val m = manifest()
        val b = Builder(m)
        val recommended = vectors.o("recommendedBudgets")
        val results = ArrayList<Triple<String, Boolean, String?>>()
        val steps = vectors.arr("steps").map { it.asJsonObject }
        for (st in steps) {
            val failBefore = r.fail
            val failuresBefore = r.failures.size
            val label = st.s("label")
            val env0 = st.o("env")
            val exp = st.o("expected")
            val expInputs = exp.arr("inputs").map { it.asJsonObject }
            var budgets = Budgets.RECOMMENDED
            for (i in expInputs) {
                val role = BudgetRole.fromRaw(i.s("role")) ?: error("unknown role ${i.s("role")}")
                val measured = i.l("computeBudget").toInt()
                r.check(measured <= Budgets.RECOMMENDED[role]) { "$label: measured budget $measured > recommended for $role" }
                r.eq(Budgets.RECOMMENDED[role].toLong(), recommended.l(role.raw), "recommended table ${role.raw}")
                budgets = budgets.with(role, measured)
            }
            val env = Env(
                me = env0.hx("me"), blockDaa = env0.l("blockDaa"), blockTimeMs = env0.l("blockTimeMs"),
                wallMs = env0.l("wallMs"), feerate = env0.get("feerate").asDouble, budgets = budgets
            )
            val args = st.o("args")
            val plan: Plan = try {
                if (st.s("op") == "register") {
                    r.eq(Builder.registerNow(env), args.l("now") + (if (label.contains("lapse")) 741L * 86_400_000L else 0L), "$label: registerNow")
                }
                val built = build(b, st, env)
                when (st.s("op")) {
                    "extend" -> {
                        val n = nameRec(st.o("records").o("name"))
                        r.check(args.l("years") <= m.params.extendableYears(n.fields)) { "$label: extendableYears covers the step" }
                        r.eq(tx0LockAndSequences(built), setOf(0L), "$label: lock time 0, every sequence 0")
                    }
                    "renew" -> {
                        val n = nameRec(st.o("records").o("name"))
                        val rule = maxOf(minOf(env.wallMs - 180_000L, env.blockTimeMs - 1_000L), n.fields.expiresAt - m.params.renewWindowMs)
                        r.eq(built.unsignedTx.lockTime, rule, "$label: lockTimeRules.renew")
                        r.eq(built.unsignedTx.lockTime, Builder.renewLockTime(env, m.params, n.fields.expiresAt), "$label: renewLockTime")
                        r.check(Builder.renewWindowOpen(env, m.params, n.fields.expiresAt)) { "$label: the window is open" }
                        r.eq(built.unsignedTx.inputs.map { it.sequence }.toSet(), setOf(0L), "$label: every sequence 0")
                    }
                }
                built
            } catch (e: KachatNames.Failure) {
                r.check(false) { "$label: builder threw ${e.message}" }
                results.add(Triple(label, false, e.message))
                continue
            }
            val tx0 = plan.unsignedTx
            r.eq(plan.op, label, "$label: op label")
            r.eq(tx0.inputs.size, expInputs.size, "$label: input count")
            val expOutputs = exp.arr("outputs").map { it.asJsonObject }
            r.eq(tx0.outputs.size, expOutputs.size, "$label: output count")
            r.eq(tx0.version.toLong(), exp.l("version"), "$label: version")
            r.eq(tx0.lockTime, exp.l("lockTime"), "$label: lock time")
            r.eqHex(tx0.payload, exp.s("payload"), "$label: payload")
            r.eqHex(tx0.subnetworkId, exp.s("subnetworkId"), "$label: subnetwork")
            r.eq(tx0.storageMass, exp.l("storageMass"), "$label: storage mass")
            r.eq(plan.costs.size, exp.l("size"), "$label: size")
            r.eq(plan.costs.computeMass, exp.l("computeMass"), "$label: compute mass")
            r.eq(plan.costs.transientMass, exp.l("transientMass"), "$label: transient mass")
            r.eq(plan.costs.normalizedTransient, exp.l("normalizedTransient"), "$label: normalized transient")
            r.eq(plan.costs.minFee, exp.l("minFee"), "$label: min fee")
            r.eq(plan.priceFee, exp.l("priceFee"), "$label: price fee")
            r.eq(plan.networkFee, exp.l("networkFee"), "$label: network fee")
            r.eq(plan.fee, exp.l("fee"), "$label: fee")
            r.eqHex(tx0.restPreimage, exp.s("restPreimage"), "$label: rest preimage (unsigned)")
            r.eqHex(plan.txid, exp.s("txid"), "$label: txid (unsigned)")
            val sighashes = plan.sighashes
            val sigs = HashMap<Int, ByteArray>()
            for ((i, ei) in expInputs.withIndex()) {
                if (i >= tx0.inputs.size) break
                val ti = tx0.inputs[i]
                r.eqHex(ti.outpoint.txid, ei.s("txid"), "$label: input $i txid")
                r.eq(ti.outpoint.index.toLong(), ei.l("index"), "$label: input $i index")
                r.eq(ti.sequence, ei.l("sequence"), "$label: input $i sequence")
                r.eq(ti.computeBudget.toLong(), ei.l("computeBudget"), "$label: input $i budget")
                r.eq(plan.inputs[i].role.raw, ei.s("role"), "$label: input $i role")
                r.eq(plan.entries[i], utxo(ei.o("entry")).entry, "$label: input $i entry")
                r.eqHex(sighashes[i], ei.s("sighash"), "$label: input $i sighash")
                val es = ei.arr("signatures").map { it.asString }
                r.eq(plan.inputs[i].unlock.needsSignature, es.isNotEmpty(), "$label: input $i needs a signature")
                es.firstOrNull()?.let { sigs[i] = KachatNames.unhex(it) }
            }
            for ((k, eo) in expOutputs.withIndex()) {
                if (k >= tx0.outputs.size) break
                val o = tx0.outputs[k]
                r.eq(o.value, eo.l("value"), "$label: output $k value")
                r.eqHex(o.script, eo.s("script"), "$label: output $k script")
                val c = eo.get("covenant").takeUnless { it.isNull() }?.asJsonObject
                if (c != null) {
                    r.eq(o.covenant?.authorizingInput?.toLong(), c.l("authorizingInput"), "$label: output $k authorizing input")
                    r.eq(o.covenant?.let { hex(it.covenantId) }, c.s("covenantId"), "$label: output $k covenant id")
                } else {
                    r.check(o.covenant == null) { "$label: output $k has a covenant binding" }
                }
            }
            try {
                val signed = plan.signed(sigs)
                for ((i, ei) in expInputs.withIndex()) {
                    if (i >= signed.inputs.size) break
                    r.eqHex(signed.inputs[i].signatureScript, ei.s("signatureScript"), "$label: input $i signature script")
                }
                r.eqHex(signed.fullPreimage, exp.s("fullPreimage"), "$label: full preimage (signed)")
                r.eqHex(signed.hash, exp.s("txHash"), "$label: tx hash (signed)")
                r.eqHex(signed.id, exp.s("txid"), "$label: txid (signed)")
                r.eqHex(signed.restPreimage, exp.s("restPreimage"), "$label: rest preimage (signed)")
                // the signer path calls back once per signing input with that input's sighash
                val seen = ArrayList<String>()
                val viaSigner = plan.signed { h -> seen.add(hex(h)); ByteArray(64) { 0x11 } }
                val wanted = sighashes.withIndex().filter { plan.inputs[it.index].unlock.needsSignature }.map { hex(it.value) }
                r.eq(seen, wanted, "$label: signer sees the sighashes")
                r.eq(viaSigner.inputs.map { it.signatureScript.size }, signed.inputs.map { it.signatureScript.size }, "$label: signature script lengths")
            } catch (e: KachatNames.Failure) {
                r.check(false) { "$label: signing threw ${e.message}" }
            }
            plan.newCommit?.let { r.eq(it.name, args.s("name"), "$label: new commit name") }
            val ok = r.fail == failBefore
            results.add(Triple(label, ok, if (ok) null else r.failures[failuresBefore]))
        }
        for ((label, ok, first) in results) {
            println((if (ok) "MATCH  " else "DIFFER ") + label + (first?.let { "   <- $it" } ?: ""))
        }
        val identical = results.count { it.second }
        println("vectors: ${r.pass} checks pass, ${r.fail} fail; $identical/${results.size} transactions byte-identical")
        assertEquals("vector steps", 32, steps.size)
        r.assertClean()
        assertEquals("transactions byte-identical", steps.size, identical)
    }
}
