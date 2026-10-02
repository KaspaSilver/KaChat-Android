package com.kachat.app.services.kachatnames

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kachat.app.services.kachatnames.KachatNames.Codec
import com.kachat.app.services.kachatnames.KachatNames.hex
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.Schnorr
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `.kachat` registry data layer's pure part (KachatNamesRegistryState.kt) - a port of iOS
 * scripts/test_kachat_names_registry.swift (KaChat 27edcd5; registry v2 - extend, the new renew,
 * periodStart, cache format 2 - from a1e38d6) without its `--live` mode: the
 * walker's transition decoder against the kachat-domains vectors (every e2e transaction applied in
 * order, every later step's records found in the walked state), the edge cases, refusals, the
 * walk loop over a simulated chain, the status / label / profile rules, the REST transaction
 * parser and the indexer shapes. Plus the Android-side pure pieces: the `.kachat` resolution
 * rule (active names only), signing and the protowire conversion, funding UTXO selection and
 * the curve check.
 */
class KachatNamesRegistryTest {

    private class Report {
        var pass = 0
        var fail = 0
        val failures = ArrayList<String>()

        fun check(ok: Boolean, what: () -> String) {
            if (ok) pass++ else { fail++; failures.add(what()) }
        }

        fun <T> eq(a: T, b: T, what: String) = check(a == b) { "$what: got $a expected $b" }

        fun assertClean() {
            assertTrue("${failures.size} failures:\n" + failures.take(40).joinToString("\n"), fail == 0)
        }
    }

    private val vectors: JsonObject by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("KachatNamesVectors.json")
            ?: error("KachatNamesVectors.json missing from the test resources")
        JsonParser.parseString(stream.bufferedReader().use { it.readText() }).asJsonObject
    }

    private fun manifest(): Manifest = Manifest.decode(vectors.getAsJsonObject("manifest").toString())

    private val steps: List<JsonObject> by lazy { vectors.getAsJsonArray("steps").map { it.asJsonObject } }

    // JSON helpers (the Swift script's u64 / i64 / s / hx)

    private fun JsonElement?.isNull() = this == null || this.isJsonNull
    private fun JsonObject.o(k: String): JsonObject = get(k).asJsonObject
    private fun JsonObject.optO(k: String): JsonObject? = get(k)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.arr(k: String): List<JsonObject> = get(k).asJsonArray.map { it.asJsonObject }
    private fun JsonObject.l(k: String): Long = get(k).asLong
    private fun JsonObject.s(k: String): String = get(k).asString
    private fun JsonObject.optS(k: String): String? = get(k).takeUnless { it.isNull() }?.asString
    private fun JsonObject.hx(k: String): ByteArray = KachatNames.unhex(s(k))

    /** A vector step's signed transaction, as the walker sees it. */
    private fun view(st: JsonObject, at: Long): TxView {
        val e = st.o("expected")
        return TxView(
            id = e.hx("txid"),
            inputs = e.arr("inputs").map { ViewInput(Outpoint(it.hx("txid"), it.l("index").toInt()), it.hx("signatureScript")) },
            outputs = e.arr("outputs").map { o ->
                TxOutput(
                    value = o.l("value"), scriptVersion = o.l("scriptVersion").toInt(), script = o.hx("script"),
                    covenant = o.optO("covenant")?.let { CovenantBinding(it.l("authorizingInput").toInt(), it.hx("covenantId")) }
                )
            },
            payload = e.hx("payload"),
            at = at
        )
    }

    private fun outpointKey(u: JsonObject) = "${u.s("txid")}:${u.l("index")}"

    /**
     * The vectors' end-to-end plan (README "The end-to-end run", registry v2): commits, three
     * registrations, extend, renew, transfer, list, buy, three offers (accept, refund, withdraw),
     * release, reclaim. The steps after it are edge cases on their own synthetic records.
     */
    private val e2eCount = 19

    /** Every record a step was built from must be in the walked state, exactly. */
    private fun checkRecords(st: JsonObject, state: RegistryState, r: Report) {
        val label = st.s("label")
        val rec = st.o("records")
        for (k in listOf("gap", "below", "above")) {
            val g = rec.optO(k) ?: continue
            val u = g.o("utxo")
            val found = state.gaps.firstOrNull { "${it.txid}:${it.index}" == outpointKey(u) }
            r.check(found != null) { "$label: $k gap ${outpointKey(u).take(16)} not in the walked state" }
            if (found != null) {
                r.eq(found.lo, g.s("lo"), "$label: $k lo")
                r.eq(found.hi, g.s("hi"), "$label: $k hi")
                r.eq(found.value, g.l("value"), "$label: $k value")
            }
        }
        rec.optO("name")?.let { n ->
            val u = n.o("utxo")
            val found = state.names.firstOrNull { "${it.txid}:${it.index}" == outpointKey(u) }
            r.check(found != null) { "$label: name ${n.s("name")} at ${outpointKey(u).take(16)} not in the walked state" }
            if (found != null) {
                r.eq(found.name, n.s("name"), "$label: name")
                r.eq(found.key, n.s("key"), "$label: key")
                r.eq(found.owner, n.s("owner"), "$label: owner")
                r.eq(found.price, n.l("price"), "$label: price")
                r.eq(found.periodStart, n.l("periodStart"), "$label: periodStart")
                r.eq(found.expiresAt, n.l("expiresAt"), "$label: expiresAt")
                r.eq(found.value, n.l("value"), "$label: value")
            }
        }
        rec.optO("offer")?.let { o ->
            val u = o.o("utxo")
            val found = state.offers.firstOrNull { "${it.txid}:${it.index}" == outpointKey(u) }
            r.check(found != null) { "$label: offer at ${outpointKey(u).take(16)} not in the walked state" }
            if (found != null) {
                r.eq(found.key, o.s("key"), "$label: offer key")
                r.eq(found.buyer, o.s("buyer"), "$label: offer buyer")
                r.eq(found.refundAfter, o.l("refundAfter"), "$label: offer refundAfter")
                r.eq(found.value, o.l("value"), "$label: offer value")
            }
        }
    }

    /** A state holding exactly a step's records (the edge cases run on synthetic registry UTXOs). */
    private fun seeded(st: JsonObject, m: Manifest): RegistryState {
        val state = RegistryState.atGenesis(m)
        state.gaps = emptyList()
        state.applied = emptyList()
        val rec = st.o("records")
        for (k in listOf("gap", "below", "above")) {
            val g = rec.optO(k) ?: continue
            val u = g.o("utxo")
            state.gaps = state.gaps + RegistryState.Gap(u.s("txid"), u.l("index").toInt(), g.s("lo"), g.s("hi"), g.l("value"))
        }
        rec.optO("name")?.let { n ->
            val u = n.o("utxo")
            state.names = state.names + RegistryState.Name(
                u.s("txid"), u.l("index").toInt(), n.s("name"), n.s("key"), n.s("owner"), n.l("price"), n.l("periodStart"),
                n.l("expiresAt"), n.l("value")
            )
        }
        rec.optO("offer")?.let { o ->
            val u = o.o("utxo")
            state.offers = state.offers + RegistryState.Offer(
                u.s("txid"), u.l("index").toInt(), o.s("key"), o.s("buyer"), o.l("refundAfter"), o.l("value"), o.optS("name")
            )
        }
        return state
    }

    /** P2SH and P2PK scripts as `kaspatest:` addresses (iOS `KaspaAddress.address(fromScriptPublicKey:)`). */
    private fun addr(script: ByteArray): String? =
        KachatNamesService.p2shAddress(script) ?: Codec.p2pkKey(script)?.let { KaspaAddress.encode("kaspatest", 0x00, it) }

    // The decoder over the vectors

    @Test
    fun walkerOverTheVectors() {
        val r = Report()
        val m = manifest()
        val e2e = steps.take(e2eCount)
        val state = RegistryState.atGenesis(m)
        val ops = ArrayList<String>()
        for ((i, st) in e2e.withIndex()) {
            checkRecords(st, state, r)
            try {
                val events = state.apply(view(st, 1_000L + i), m)
                ops.addAll(events.map { "${it.op} ${it.name ?: "?"}" })
            } catch (e: Exception) {
                r.check(false) { "${st.s("label")}: apply threw $e" }
            }
            try { state.checkInvariants() } catch (e: Exception) { r.check(false) { "${st.s("label")}: invariants: $e" } }
        }
        r.eq(
            ops,
            listOf(
                "register alpha-tn", "register bravo-tn", "register lapse-tn", "extend alpha-tn", "renew lapse-tn", "transfer alpha-tn", "list alpha-tn",
                "sale alpha-tn", "offer bravo-tn", "offer_accepted bravo-tn", "offer_accept bravo-tn", "offer alpha-tn", "offer_refund alpha-tn",
                "offer alpha-tn", "offer_withdraw alpha-tn", "release bravo-tn", "reclaim lapse-tn"
            ),
            "e2e events"
        )
        r.eq(state.names.map { it.name }, listOf("alpha-tn"), "names left after the e2e plan")
        r.eq(state.gaps.size, 2, "gaps left after the e2e plan")
        r.eq(state.offers.size, 0, "offers left after the e2e plan")
        val accepted = state.events.firstOrNull { it.op == "offer_accepted" }
        val payout = accepted?.price ?: 0L
        r.check(payout > 9 * 100_000_000L && payout < 10 * 100_000_000L) { "accepted offer payout is the offer less the fee ($payout)" }
        val alpha = state.name("alpha-tn")
        r.check(alpha?.registeredTxId == e2e[3].o("expected").s("txid")) { "registration tx carried through every transition" }
        r.eq(alpha?.registeredAt, 1_003L, "registration time carried through every transition")
        val alphaRegister = e2e[3].o("args")
        r.eq(alpha?.periodStart, alphaRegister.l("now"), "alpha-tn: periodStart = register's now, kept by extend, transfer, list and buy")
        r.eq(alpha?.expiresAt, alphaRegister.l("now") + 2 * KachatNames.YEAR_MS, "alpha-tn: registered for 1 year, extended by 1")
        // applying again changes nothing
        val snapshot = state.copy()
        for ((i, st) in e2e.withIndex()) runCatching { state.apply(view(st, 1_000L + i), m) }
        r.eq(state, snapshot, "re-applying is a no-op")

        // the edge cases, each on a state seeded with its own records
        for (st in steps.drop(e2eCount)) {
            val seededState = seeded(st, m)
            val label = st.s("label")
            val before = st.o("records").optO("name")?.let { it.l("periodStart") to it.l("expiresAt") }
            try {
                val events = seededState.apply(view(st, 5), m)
                val op = st.s("op")
                if (op == "commit" || op == "cancelCommit") {
                    r.check(events.isEmpty()) { "$label: not a registry transaction" }
                } else {
                    r.check(events.isNotEmpty()) { "$label: no events" }
                }
                when (op) {
                    "register" -> { r.eq(seededState.names.size, 1, "$label: name created"); r.eq(seededState.gaps.size, 2, "$label: gaps split") }
                    "reclaim" -> { r.eq(seededState.names.size, 0, "$label: name gone"); r.eq(seededState.gaps.size, 1, "$label: gaps merged") }
                    "acceptOffer" -> r.eq(seededState.offers.size, 0, "$label: offer gone")
                    "extend", "renew" -> {
                        val years = st.o("args").l("years")
                        val after = seededState.names.firstOrNull()
                        r.eq(events.firstOrNull()?.op, op, "$label: event")
                        r.eq(events.firstOrNull()?.years, years, "$label: event years")
                        r.eq(after?.expiresAt, before?.let { it.second + years * KachatNames.YEAR_MS }, "$label: expiresAt + years")
                        // extend keeps the period; renew starts the next one at the old expiry
                        r.eq(after?.periodStart, if (op == "extend") before?.first else before?.second, "$label: periodStart")
                    }
                }
                if (op == "acceptOffer") {
                    val o = st.o("records").o("offer")
                    r.eq(seededState.names.firstOrNull()?.owner, o.s("buyer"), "$label: the name went to the buyer")
                }
            } catch (e: Exception) {
                r.check(false) { "$label: apply threw $e" }
            }
        }

        // refusals leave the state alone
        val reg = steps[3]
        val base = view(reg, 1)
        fun withOutput(t: TxView, i: Int, o: TxOutput) = t.copy(outputs = t.outputs.mapIndexed { k, x -> if (k == i) o else x })
        val o2 = base.outputs[2]
        val tamperedScript = o2.script.copyOf().also { it[5] = (it[5].toInt() xor 0x01).toByte() }
        val tampered = withOutput(base, 2, TxOutput(o2.value, o2.scriptVersion, tamperedScript, o2.covenant))
        val st0 = RegistryState.atGenesis(m)
        r.check(runCatching { st0.apply(tampered, m) }.isFailure) { "a register whose name output holds another state was accepted" }
        r.eq(st0, RegistryState.atGenesis(m), "refused register left the state alone")
        val extra = base.copy(outputs = base.outputs + base.outputs[0])
        r.check(runCatching { st0.apply(extra, m) }.isFailure) { "an unexplained registry output was accepted" }
        val o0 = base.outputs[0]
        val wrongAuth = withOutput(base, 0, TxOutput(o0.value, o0.scriptVersion, o0.script, CovenantBinding(1, o0.covenant!!.covenantId)))
        r.check(runCatching { st0.apply(wrongAuth, m) }.isFailure) { "an output authorized by another input was accepted" }
        val pushes = Codec.parsePushes(base.inputs[0].signatureScript).toMutableList()
        val redeem = pushes.removeAt(pushes.size - 1).copyOf()
        redeem[redeem.size - 1] = (redeem[redeem.size - 1].toInt() xor 0x01).toByte()
        val badScript = pushes.fold(ByteArray(0)) { acc, p -> acc + Codec.pushData(p) } + Codec.pushData(redeem)
        val badRedeem = base.copy(inputs = base.inputs.mapIndexed { k, x -> if (k == 0) ViewInput(x.outpoint, badScript) else x })
        r.check(runCatching { st0.apply(badRedeem, m) }.isFailure) { "a spend revealing another redeem script was accepted" }
        r.eq(st0, RegistryState.atGenesis(m), "refusals left the state alone")
        // an unrelated transaction is ignored
        r.eq(runCatching { st0.apply(view(steps[0], 1), m) }.getOrNull()?.size, 0, "a commit is not a registry transaction")
        r.assertClean()
    }

    /**
     * The walk loop over a simulated chain holding every e2e transaction: liveness from the
     * simulated UTXO set, spends found through addresses, transactions handed back newest first.
     */
    @Test
    fun walkOverASimulatedChain() = runBlocking {
        val r = Report()
        val m = manifest()
        val txs = steps.take(e2eCount).mapIndexed { i, st -> view(st, 1_000L + i) }
        val created = HashMap<String, ByteArray>() // outpoint -> script
        val spentBy = HashMap<String, String>() // outpoint -> txid
        for (t in txs) {
            for ((k, o) in t.outputs.withIndex()) created["${t.idHex}:$k"] = o.script
            for (i in t.inputs) spentBy["${hex(i.outpoint.txid)}:${i.outpoint.index}"] = t.idHex
        }
        // the genesis gap lives at the manifest's genesis outpoint
        created["${hex(m.genesisTxid)}:0"] = m.genesisOutput.script

        for (upTo in listOf(3, 6, 7, 8, 11, e2eCount)) {
            val visible = txs.take(upTo)
            val visibleIds = visible.map { it.idHex }.toSet()
            val walked = RegistryState.atGenesis(m)
            try {
                val report = walked.walk(
                    manifest = m,
                    address = ::addr,
                    live = { addresses ->
                        created.filter { (op, script) ->
                            addresses.contains(addr(script) ?: "") &&
                                (op.startsWith(hex(m.genesisTxid)) || visibleIds.contains(op.take(64))) &&
                                spentBy[op]?.let { visibleIds.contains(it) } != true
                        }.keys
                    },
                    transactions = { a ->
                        visible.reversed().filter { t ->
                            t.outputs.any { addr(it.script) == a } ||
                                t.inputs.any { i -> created["${hex(i.outpoint.txid)}:${i.outpoint.index}"]?.let { addr(it) } == a }
                        }
                    }
                )
                val reference = RegistryState.atGenesis(m)
                for (t in visible) runCatching { reference.apply(t, m) }
                r.eq(walked.gaps.map { "${it.txid}:${it.index}" }.toSet(), reference.gaps.map { "${it.txid}:${it.index}" }.toSet(), "walk to $upTo: gaps")
                r.eq(walked.names.map { it.name }.sorted(), reference.names.map { it.name }.sorted(), "walk to $upTo: names")
                r.eq(walked.names.map { "${it.txid}:${it.index}" }.toSet(), reference.names.map { "${it.txid}:${it.index}" }.toSet(), "walk to $upTo: name outpoints")
                r.check(report.unresolved.isEmpty()) { "walk to $upTo: unresolved ${report.unresolved}" }
                // the first three are commits: nothing in the registry moves until the first register
                r.check((upTo <= 3) == report.applied.isEmpty()) { "walk to $upTo: walked ${report.applied.size}" }
                try { walked.checkInvariants() } catch (e: Exception) { r.check(false) { "walk to $upTo: invariants $e" } }
            } catch (e: Exception) {
                r.check(false) { "walk to $upTo threw $e" }
            }
        }
        r.assertClean()
    }

    // The rules

    @Test
    fun rules() {
        val r = Report()
        val g = 864_000_000L
        r.eq(Status.of(1_000, g, 999), Status.ACTIVE, "status before expiry")
        r.eq(Status.of(1_000, g, 1_000), Status.GRACE, "status at expiry")
        r.eq(Status.of(1_000, g, 1_000 + g - 1), Status.GRACE, "status in grace")
        r.eq(Status.of(1_000, g, 1_000 + g), Status.LAPSED, "status at grace end")
        val me = ByteArray(32) { 7 }
        fun info(n: String, exp: Long, reg: Long?) =
            NameInfo(n, Codec.key(n), me, 0, exp, Outpoint(KachatNames.ZERO32, 0), registeredAt = reg)
        val now = 10_000_000_000_000L
        val owned = listOf(info("zeta", now + 5, 10), info("alpha", now + 5, 20), info("old", now - 5, 1))
        r.eq(KachatNames.label(owned, null, g, now), "zeta", "label: the oldest active name")
        r.eq(KachatNames.label(owned, "Alpha.kachat", g, now), "alpha", "label: the primary name")
        r.eq(KachatNames.label(owned, "old", g, now), "zeta", "label: a primary name in grace is skipped")
        r.eq(KachatNames.label(owned, "notmine", g, now), "zeta", "label: a primary name not owned is skipped")
        r.eq(KachatNames.label(listOf(owned[2]), null, g, now), null, "label: no active name")

        // The record is {avatar, banner, bio, linktree, primaryName}, each piece a social profile
        // link on a platform that can supply it (iOS c124cb3); nothing typed or uploaded is on chain.
        val p = Profile(
            avatar = " x.com/KaspaCurrency/ ", banner = "youtube.com/@KaspaCurrency", bio = "instagram.com/instagram",
            linktree = "https://www.linktr.ee/kaspa?utm=1", primaryName = "Alice.kachat"
        )
        val clean = p.sanitized()
        r.eq(clean.avatar, "https://x.com/KaspaCurrency", "profile: avatar source normalized")
        r.eq(clean.banner, "https://www.youtube.com/@KaspaCurrency", "profile: banner from another account")
        r.eq(clean.bio, null, "profile: a bio source on a platform without bios is dropped")
        r.eq(clean.linktree, "https://linktr.ee/kaspa", "profile: Linktree link normalized")
        r.eq(clean.primaryName, "alice", "profile: primary name normalized")
        r.eq(
            Profile(avatar = "https://example.com/me", banner = "instagram.com/instagram", linktree = "https://example.com/links").sanitized(), Profile(),
            "profile: unsupported social site and non-Linktree link dropped"
        )
        val json = p.recordJSON()
        r.check(json.size <= 2048) { "profile JSON within 2 KB" }
        r.eq(
            String(json, Charsets.UTF_8),
            "{\"avatar\":\"https://x.com/KaspaCurrency\",\"banner\":\"https://www.youtube.com/@KaspaCurrency\",\"linktree\":\"https://linktr.ee/kaspa\",\"primaryName\":\"alice\",\"v\":1}",
            "profile JSON compact with sorted keys"
        )
        r.eq(Profile.parse(json), clean, "profile JSON round trip")
        r.eq(
            Profile.parse("{\"v\":1,\"displayName\":\"x\",\"bio\":\"free text\",\"avatar\":\"ftp://a\"}".toByteArray()), Profile(),
            "profile: free text, display names and bad links dropped"
        )
        r.eq(Profile.parse("{\"v\":2}".toByteArray()), null, "profile: only v 1")
        r.eq(Profile.parse("{\"avatar\":\"https://x.com/a\"}".toByteArray()), null, "profile: v is required")
        r.eq(Profile.parse("{\"v\":1,\"avatar\":5}".toByteArray()), null, "profile: a field of the wrong type refuses the record")
        // 1322216's {social} and the first record's image URLs / links read as empty, primary name kept.
        r.eq(
            Profile.parse("{\"social\":\"https://x.com/a\",\"avatar\":\"https://a.b/c.png\",\"links\":{\"x\":\"k\"},\"primaryName\":\"bob\",\"v\":1}".toByteArray()),
            Profile(primaryName = "bob"),
            "profile: an older record keeps only its primary name"
        )

        // what a social link shows
        r.eq(
            SocialSource.decodeEntities("a &amp; b &#39;c&#x27; &#064;d &quot;e&quot; &amp;#39;"), "a & b 'c' @d \"e\" &#39;",
            "entities decoded one level"
        )
        val html = "<meta property=\"og:image\" content=\"https://pbs.twimg.com/profile_images/1/a_200x200.jpg\"/>" +
            "<meta property=\"og:description\" content=\"Builder &amp; miner\"/>"
        r.eq(SocialSource.openGraphImage(html)?.let { SocialSource.xAvatar(it) }, "https://pbs.twimg.com/profile_images/1/a_400x400.jpg", "X avatar upgraded to 400px")
        r.eq(SocialSource.bio(SocialSource.Platform.X, SocialSource.openGraphDescription(html)), "Builder & miner", "X bio from og:description")
        r.eq(SocialSource.bio(SocialSource.Platform.TWITCH, "Speedruns — Twitch streams live on Twitch!"), "Speedruns", "Twitch boilerplate cut")
        r.eq(SocialSource.bio(SocialSource.Platform.INSTAGRAM, "687M Followers, 305 Following"), null, "no bio from Instagram's counts")
        r.eq(SocialSource.bio(SocialSource.Platform.X, "b".repeat(400))?.length, 280, "bio cut to 280")
        val gh = SocialSource.githubProfile("{\"avatar_url\":\"https://avatars.githubusercontent.com/u/1\",\"bio\":\" hi \"}")
        r.check(gh.first == "https://avatars.githubusercontent.com/u/1" && gh.second == "hi") { "GitHub avatar and bio: $gh" }
        val fx = SocialSource.fxTwitterProfile(
            "{\"code\":200,\"user\":{\"avatar_url\":\"https://pbs.twimg.com/profile_images/1/a_normal.jpg\",\"banner_url\":\"https://pbs.twimg.com/profile_banners/9/8\",\"description\":\"hi\"}}"
        )
        r.eq(
            fx, SocialProfile("https://pbs.twimg.com/profile_images/1/a_400x400.jpg", "https://pbs.twimg.com/profile_banners/9/8/1500x500", "hi"),
            "FxTwitter: avatar 400px, banner 1500x500, bio"
        )
        r.eq(SocialSource.fxTwitterProfile("{\"code\":404,\"message\":\"NOT_FOUND\"}"), SocialProfile(), "FxTwitter: unknown account answers empty")
        r.eq(SocialSource.fxTwitterProfile("{\"code\":500}"), null, "FxTwitter: an error means fall back")
        val BIO = SocialSource.Kind.BIO
        r.eq(SocialSource.from("instagram.com/instagram", BIO), null, "no bio source on Instagram")
        r.eq(SocialSource.from(SocialSource.Platform.X, "@KaspaCurrency", SocialSource.Kind.AVATAR)?.link, "https://x.com/KaspaCurrency", "X handle with @")
        r.eq(SocialSource.from(SocialSource.Platform.YOUTUBE, "MrBeast", SocialSource.Kind.BANNER)?.link, "https://www.youtube.com/@MrBeast", "YouTube handle")
        r.eq(SocialSource.from(SocialSource.Platform.TIKTOK, "tiktok", SocialSource.Kind.AVATAR)?.link, "https://www.tiktok.com/@tiktok", "TikTok handle")
        r.eq(SocialSource.from(SocialSource.Platform.LINKEDIN, "company/linkedin", SocialSource.Kind.AVATAR)?.link, "https://www.linkedin.com/company/linkedin", "LinkedIn company path")
        r.eq(SocialSource.from(SocialSource.Platform.DISCORD, "discord-developers", BIO)?.link, "https://discord.gg/discord-developers", "Discord invite code")
        val pasted = SocialSource.from(SocialSource.Platform.X, "https://www.youtube.com/@MrBeast", SocialSource.Kind.AVATAR)
        r.check(pasted?.platform == SocialSource.Platform.YOUTUBE && pasted.displayHandle == "MrBeast") { "a pasted link switches platform: $pasted" }
        r.eq(SocialSource.from(SocialSource.Platform.INSTAGRAM, "instagram", SocialSource.Kind.BANNER), null, "no banner from Instagram")
        r.eq(SocialSource.from(SocialSource.Platform.X, "bad handle!", SocialSource.Kind.AVATAR), null, "invalid handle refused")
        r.eq(Profile.linktreeLinkFromUsername("kaspa"), "https://linktr.ee/kaspa", "Linktree from a username")
        r.eq(Profile.linktreeLinkFromUsername("@kaspa "), "https://linktr.ee/kaspa", "Linktree from @username")
        r.eq(Profile.linktreeLinkFromUsername("https://linktr.ee/kaspa"), "https://linktr.ee/kaspa", "Linktree from a pasted link")
        r.eq(Profile.linktreeLinkFromUsername("kas pa"), null, "Linktree username with a space refused")
        r.eq(Profile.linktreeUsername("https://linktr.ee/kaspa"), "kaspa", "Linktree username shown back")
        r.check(SocialSource.from("t.me/telegram", BIO) != null) { "bio source on Telegram" }
        r.eq(SocialSource.discordDescription("{\"guild\":{\"id\":\"1\",\"description\":\"Devs\"}}"), "Devs", "Discord server description")

        // the paid period on a NameInfo (registry v2)
        val params = Params(
            bond = 1, gapValue = 1, tCommit = 600, maxYears = 2, graceMs = g, renewWindowMs = 864_000_000,
            prices = listOf(1, 1, 1, 1, 1), renewPrices = listOf(1, 1, 1, 1, 1), offerMaxFee = 1
        )
        val unknown = info("period", now + KachatNames.YEAR_MS, 1)
        r.eq(unknown.extendableYears(params), 0L, "period unknown: no extend")
        r.eq(unknown.fields, null, "period unknown: no on-chain state")
        fun period(start: Long, exp: Long) =
            NameInfo("period", Codec.key("period"), me, 0, exp, Outpoint(KachatNames.ZERO32, 0), registeredAt = 1, periodStart = start)
        val known = period(now, now + KachatNames.YEAR_MS)
        r.eq(known.extendableYears(params), 1L, "1 year paid of 2: extend by 1")
        r.eq(known.fields?.periodStart, now, "fields carry periodStart")
        r.check(!known.renewOpen(params, nowMs = now)) { "renewal closed a year before expiry" }
        r.eq(known.renewOpens(params), now + KachatNames.YEAR_MS - 864_000_000, "renewal opens 10 days before expiry")
        r.check(known.renewOpen(params, nowMs = now + KachatNames.YEAR_MS - 864_000_000)) { "renewal open at the opening" }
        r.eq(period(now, now + 2 * KachatNames.YEAR_MS).extendableYears(params), 0L, "2 years paid: no extend")

        // a cache written before registry v2 (no periodStart, format 1) is dropped: Gson reads it
        // (absent fields stay 0/null, unlike Swift's Decodable, which refuses it outright), but the
        // registry keeps a cache only when it `matches` - the format version included
        val m = manifest()
        val v1Cache = "{\"version\":1,\"network\":\"testnet-10\",\"registryCovenantId\":\"${hex(m.registryCovenantId)}\",\"gaps\":[]," +
            "\"names\":[{\"txid\":\"00\",\"index\":0,\"name\":\"a\",\"key\":\"00\",\"owner\":\"00\",\"price\":0,\"expiresAt\":1,\"value\":1}]," +
            "\"offers\":[],\"applied\":[],\"events\":[]}"
        val decoded = runCatching { Gson().fromJson(v1Cache, RegistryState::class.java) }.getOrNull()
        r.check(decoded?.matches(m) != true) { "a registry v1 cache is kept" }
        r.eq(RegistryState.FORMAT_VERSION, 2, "cache format 2 (registry v2)")

        val k = ByteArray(31) { 0x10 } + byteArrayOf(0x00)
        r.eq(KachatNames.step(k, -1)?.let { hex(it) }, hex(ByteArray(30) { 0x10 } + byteArrayOf(0x0f, 0xff.toByte())), "key - 1 borrows")
        r.eq(KachatNames.step(k, 1)?.let { hex(it) }, hex(ByteArray(31) { 0x10 } + byteArrayOf(0x01)), "key + 1")
        r.eq(KachatNames.step(KachatNames.ZERO32, -1), null, "0 - 1")
        r.eq(KachatNames.step(KachatNames.FF32, 1), null, "ff..ff + 1")
        r.assertClean()
    }

    /** `.kachat` resolves to the owner of an ACTIVE name only (KACHAT_NAMES.md section 4). */
    @Test
    fun kachatResolutionIsActiveOnly() {
        val g = 864_000_000L
        val owner = ByteArray(32) { 9 }
        val now = 2_000_000_000_000L
        fun registered(exp: Long) = Lookup.Registered(NameInfo("alice", Codec.key("alice"), owner, 0, exp, Outpoint(KachatNames.ZERO32, 0)))
        assertEquals(hex(owner), KachatNames.resolvedOwner(registered(now + 1), g, now)?.let { hex(it) })
        assertNull("a name at its expiry no longer resolves", KachatNames.resolvedOwner(registered(now), g, now))
        assertNull("a name in grace does not resolve", KachatNames.resolvedOwner(registered(now - 1), g, now))
        assertNull("a lapsed name does not resolve", KachatNames.resolvedOwner(registered(now - g - 1), g, now))
        assertNull("a free name does not resolve", KachatNames.resolvedOwner(Lookup.Free("alice", null), g, now))
        // and the owner key comes back as its kaspatest: address, and back again
        val a = KachatNamesRegistry.address(owner)!!
        assertTrue(a.startsWith("kaspatest:q"))
        assertEquals(hex(owner), KachatNamesRegistry.keyOf(a)?.let { hex(it) })
        assertNull("a mainnet address has no testnet key", KachatNamesRegistry.keyOf(KaspaAddress.encode("kaspa", 0x00, owner)))
    }

    // REST and indexer shapes

    /** The REST API's shape for a version-1 transaction (the live genesis, 2026-10-02). */
    private val genesisREST = """
{"subnetwork_id":"0000000000000000000000000000000000000000","transaction_id":"cba68dd1b07f374410270f1e609a3e71deaf42d3bd3b5849ac9b0e9cc687f45f","hash":"7a9e06cd3134a0cf0d90ecfb21d953a3ab3740f36fa00c552c2c3d10e8097978","mass":"2083","payload":null,"block_hash":["67c7ad399972fa99598c3baccfb380a89c509d5634d3738ed3bbca0e344162d4"],"block_time":1790909722843,"version":1,"is_accepted":true,"accepting_block_hash":"f6f3e6b5831b88991fe6bf47b7170fa6f0699126c217cf9f11236faed4d1b41d","accepting_block_blue_score":574239955,"accepting_block_time":1790909722989,"inputs":[{"transaction_id":"cba68dd1b07f374410270f1e609a3e71deaf42d3bd3b5849ac9b0e9cc687f45f","index":0,"previous_outpoint_hash":"f12c99e6f39833515eccb9900d5b8596565751dd76cbac98374597d9fbe73dab","previous_outpoint_index":"0","previous_outpoint_address":null,"previous_outpoint_amount":null,"signature_script":"41f5af0ec9cbad01185cfb488ceed108470a40af4d488ffe00da825ab0e9f5c65469e5dd1b60e18a6d66e611300a4d6965af730ead2b404841c5090f0a520aa91401","sig_op_count":null,"compute_budget":10,"covenant_id":null}],"outputs":[{"transaction_id":"cba68dd1b07f374410270f1e609a3e71deaf42d3bd3b5849ac9b0e9cc687f45f","index":0,"amount":100000000,"script_public_key":"aa2091e1c42572eec31a4bdfab6f4fe298fe51cb0934ac6f2cdb87e1052082744cc987","script_public_key_address":"kaspatest:pzg7r3p9wthvxxjtm74k7nlznrl9rjcfxjkx7txmslss2gyzw3xvj686vxecj","script_public_key_type":"scripthash","covenant_authorizing_input":0,"covenant_id":"9444187f09a3e77450e125d448b21eb79b3c54b692a5b3f3e8af38343b9a7a51"},{"transaction_id":"cba68dd1b07f374410270f1e609a3e71deaf42d3bd3b5849ac9b0e9cc687f45f","index":1,"amount":99791700,"script_public_key":"20a866cf597e3e681324adbc115ec34ca7746813cf70f6bfe4f9c36f2c9dd30848ac","script_public_key_address":"kaspatest:qz5xdn6e0clxsyey4k7pzhkrfjnhg6qneac0d0lyl8pk7tya6vyysf8pt3r8m","script_public_key_type":"pubkey","covenant_authorizing_input":null,"covenant_id":null}]}
"""

    @Test
    fun restAndIndexerShapes() {
        val r = Report()
        val j = JsonParser.parseString(genesisREST).asJsonObject
        val t = TxView.fromREST(j)
        r.check(t != null) { "REST genesis parsed as not accepted" }
        if (t != null) {
            r.eq(t.idHex, "cba68dd1b07f374410270f1e609a3e71deaf42d3bd3b5849ac9b0e9cc687f45f", "REST txid")
            r.eq(t.inputs.size, 1, "REST inputs")
            r.eq(hex(t.inputs[0].outpoint.txid), "f12c99e6f39833515eccb9900d5b8596565751dd76cbac98374597d9fbe73dab", "REST input outpoint")
            r.eq(t.outputs.size, 2, "REST outputs")
            r.eq(t.outputs[0].covenant?.authorizingInput, 0, "REST covenant binding")
            r.eq(t.outputs[0].covenant?.let { hex(it.covenantId) }, "9444187f09a3e77450e125d448b21eb79b3c54b692a5b3f3e8af38343b9a7a51", "REST covenant id")
            r.eq(t.outputs[1].covenant, null, "REST plain output")
            r.eq(t.at, 1790909722989L, "REST acceptance time")
            r.eq(
                KachatNamesService.p2shAddress(t.outputs[0].script),
                "kaspatest:pzg7r3p9wthvxxjtm74k7nlznrl9rjcfxjkx7txmslss2gyzw3xvj686vxecj",
                "P2SH address of the genesis gap"
            )
            r.eq(addr(t.outputs[1].script), "kaspatest:qz5xdn6e0clxsyey4k7pzhkrfjnhg6qneac0d0lyl8pk7tya6vyysf8pt3r8m", "P2PK address")
        }
        val rejected = j.deepCopy().apply { addProperty("is_accepted", false) }
        r.check(runCatching { TxView.fromREST(rejected) }.let { it.isSuccess && it.getOrNull() == null }) { "REST: a transaction not accepted is skipped" }

        // indexer shapes
        val nameJson = """
            {"name":"Alice","key":"00","registered":true,"status":"active","owner":"kaspatest:x","ownerKey":"${"ab".repeat(32)}",
             "price":"5000000000","expiresAt":1822000000000,"outpoint":{"txId":"${"cd".repeat(32)}","index":2},"registeredAt":1790000000000}
        """
        val n = IndexerApi.NameJson.parse(JsonParser.parseString(nameJson)).info { null }
        r.eq(n?.name, "alice", "indexer name normalized")
        r.eq(n?.price, 5_000_000_000L, "indexer price string")
        r.eq(n?.outpoint?.index, 2, "indexer outpoint")
        r.eq(n?.key?.let { hex(it) }, hex(Codec.key("alice")), "indexer key recomputed from the name")
        r.eq(n?.periodStart, null, "indexer without periodStart: unknown")
        val withPeriod = """
            {"name":"alice","registered":true,"ownerKey":"${"ab".repeat(32)}","price":"0","periodStart":1790000000000,
             "expiresAt":1822000000000,"outpoint":{"txId":"${"cd".repeat(32)}","index":0}}
        """
        val np = IndexerApi.NameJson.parse(JsonParser.parseString(withPeriod)).info { null }
        r.eq(np?.periodStart, 1_790_000_000_000L, "indexer periodStart")
        r.eq(np?.fields?.periodStart, 1_790_000_000_000L, "indexer record spendable with its periodStart")
        val free = """
            {"name":"bob","key":"00","registered":false,"gap":{"lo":"${"00".repeat(32)}","hi":"${"ff".repeat(32)}","outpoint":{"txId":"${"ee".repeat(32)}","index":0}}}
        """
        val f = IndexerApi.NameJson.parse(JsonParser.parseString(free))
        r.check(f.info { null } == null) { "indexer free name has no record" }
        r.check(f.gap?.info?.contains(Codec.key("bob")) == true) { "indexer gap decoded" }
        r.check(runCatching { IndexerApi.NameJson.parse(JsonParser.parseString("{\"registered\":false}")) }.isFailure) {
            "an indexer name object without a name is refused"
        }
        val status = IndexerApi.StatusJson.parse(
            JsonParser.parseString("{\"network\":\"testnet-10\",\"registryCovenantId\":\"9444\",\"indexedDaa\":5,\"synced\":true}")
        )
        r.eq(status.registryCovenantId, "9444", "indexer status")
        val events = IndexerApi.EventJson.parseEvents(
            JsonParser.parseString("{\"events\":[{\"txId\":\"aa\",\"op\":\"sale\",\"name\":\"bob\",\"price\":\"12\",\"at\":7}],\"next\":null}")
        )
        r.eq(events, listOf(Event(txId = "aa", op = "sale", name = "bob", at = 7, price = 12)), "indexer events")
        r.assertClean()
    }

    // The service's pure pieces

    /** A plan signed with a fresh key verifies, and the protowire form carries the Toccata fields. */
    @Test
    fun signingAndProtowire() {
        val m = manifest()
        val b = Builder(m)
        val privateKey = ByteArray(32) { (it + 1).toByte() }
        val me = KachatNamesService.xonlyKey(privateKey)
        val funding = Utxo(
            Outpoint(ByteArray(32) { 0x42 }, 1),
            UtxoEntry(amount = 50 * KachatNames.SOMPI_PER_KAS, script = Codec.p2pkScript(me), blockDaaScore = 100)
        )
        val env = Env(me = me, blockDaa = 1_000, blockTimeMs = 2_000_000_000_000L, wallMs = 2_000_000_000_000L)
        val plan = b.commit(env, listOf(funding), "alice", KachatNamesService.newSalt())
        val tx = KachatNamesService.sign(plan, privateKey, me)
        for ((i, input) in tx.inputs.withIndex()) {
            if (!plan.inputs[i].unlock.needsSignature) continue
            val sig = Codec.parsePushes(input.signatureScript)[0]
            assertEquals(65, sig.size)
            assertEquals(KachatNames.SIGHASH_ALL, sig[64])
            assertTrue("input $i signature verifies", Schnorr.verify(plan.sighashes[i], sig.copyOf(64), me))
        }
        assertEquals("the txid does not depend on signatures", plan.unsignedTx.idHex, tx.idHex)
        val other = ByteArray(32) { 0x55 }
        assertTrue(runCatching { KachatNamesService.sign(plan, other, me) }.exceptionOrNull() is KachatNamesService.ServiceError.KeyMismatch)

        val rpc = KachatNamesService.rpcTransaction(tx)
        assertEquals(1, rpc.version)
        assertEquals(tx.storageMass, rpc.storageMass)
        assertEquals(tx.inputs.map { it.computeBudget }, rpc.inputsList.map { it.computeBudget })
        assertTrue(rpc.inputsList.all { it.sigOpCount == 0 })
        assertEquals(hex(tx.inputs[0].signatureScript), rpc.inputsList[0].signatureScript)
        assertEquals(hex(tx.payload), rpc.payload)

        // a registry transaction's covenant bindings survive the conversion
        val reg = steps[3]
        val e = reg.o("expected")
        val regTx = Tx(
            version = 1,
            inputs = e.arr("inputs").map { TxInput(Outpoint(it.hx("txid"), it.l("index").toInt()), it.hx("signatureScript"), it.l("sequence"), it.l("computeBudget").toInt()) },
            outputs = view(reg, 0).outputs,
            lockTime = e.l("lockTime"),
            payload = e.hx("payload"),
            storageMass = e.l("storageMass")
        )
        assertEquals(e.s("txid"), regTx.idHex)
        val regRpc = KachatNamesService.rpcTransaction(regTx)
        for ((k, o) in regTx.outputs.withIndex()) {
            val ro = regRpc.outputsList[k]
            assertEquals(o.covenant != null, ro.hasCovenant())
            o.covenant?.let {
                assertEquals(it.authorizingInput, ro.covenant.authorizingInput)
                assertEquals(hex(it.covenantId), ro.covenant.covenantId)
            }
        }
    }

    @Test
    fun fundingUtxosAndKeys() {
        val me = KachatNamesService.xonlyKey(ByteArray(32) { 3 })
        fun u(i: Int, script: ByteArray, daa: Long, coinbase: Boolean = false, covenant: ByteArray? = null) =
            Utxo(Outpoint(ByteArray(32) { i.toByte() }, 0), UtxoEntry(amount = 1_000, script = script, blockDaaScore = daa, isCoinbase = coinbase, covenantId = covenant))
        val mine = Codec.p2pkScript(me)
        val all = listOf(
            u(1, mine, 10),
            u(2, mine, 9_500, coinbase = true), // immature coinbase
            u(3, mine, 10, covenant = ByteArray(32) { 1 }), // carries a covenant
            u(4, Codec.p2pkScript(ByteArray(32) { 8 }), 10), // someone else's
            u(5, mine, 10, coinbase = true) // mature coinbase
        )
        val picked = KachatNamesService.fundingUtxos(all, me, virtualDaaScore = 10_000).map { it.outpoint.txid[0].toInt() }
        assertEquals(listOf(1, 5), picked)

        // keys a name or an offer will be locked to must be on the curve
        KachatNamesActions.validateKey(me, "mine")
        assertTrue(runCatching { KachatNamesActions.validateKey(KachatNames.ZERO32, "zero") }.isFailure)
        assertTrue(runCatching { KachatNamesActions.validateKey(ByteArray(31), "short") }.isFailure)
        // x = 5 has no point on secp256k1 (5^3 + 7 = 132 is not a square mod p)
        val noPoint = ByteArray(31) + byteArrayOf(5)
        assertTrue(runCatching { KachatNamesActions.validateKey(noPoint, "x=5") }.isFailure)
        assertTrue(runCatching { KachatNamesActions.validateKey(KachatNames.FF32, "ff") }.isFailure)
    }

    /**
     * The social link rules and the parsing behind [KachatSocialImageResolver] (iOS
     * `KachatNames.SocialSource`, ad32798 / 1322216 / 169f6a0) - pure, so they run here; the
     * fetches themselves are not tested.
     */
    @Test
    fun socialSource() {
        val r = Report()
        val A = SocialSource.Kind.AVATAR
        val B = SocialSource.Kind.BANNER
        fun link(s: String, kind: SocialSource.Kind = A) = SocialSource.from(s, kind)?.link
        fun platform(s: String) = SocialSource.from(s, A)?.platform

        // every platform, pasted the ways people paste them
        r.eq(link("x.com/KaspaCurrency/"), "https://x.com/KaspaCurrency", "X without scheme, trailing slash")
        r.eq(link("https://twitter.com/kaspa?lang=en"), "https://x.com/kaspa", "twitter.com becomes x.com, query dropped")
        r.eq(link("http://mobile.twitter.com/kaspa"), "https://x.com/kaspa", "mobile. stripped, http upgraded")
        r.eq(link("https://x.com/home"), null, "X's own pages are not profiles")
        r.eq(link("https://x.com/kaspa/status/1"), null, "a post is not a profile")
        r.eq(link("https://m.youtube.com/@Kaspa/videos"), "https://www.youtube.com/@Kaspa", "YouTube @handle, tab dropped")
        r.eq(link("youtube.com/channel/UC123"), "https://www.youtube.com/channel/UC123", "YouTube channel id")
        r.eq(link("youtube.com/watch?v=1"), null, "a YouTube video is not a channel")
        r.eq(link("fb.com/kaspa"), "https://www.facebook.com/kaspa", "Facebook")
        r.eq(link("facebook.com/groups"), null, "Facebook groups refused")
        r.eq(link("instagram.com/kaspa"), "https://www.instagram.com/kaspa/", "Instagram keeps its trailing slash")
        r.eq(link("instagram.com/p/abc"), null, "an Instagram post refused")
        r.eq(link("tiktok.com/@kaspa"), "https://www.tiktok.com/@kaspa", "TikTok")
        r.eq(link("tiktok.com/kaspa"), null, "TikTok needs the @")
        r.eq(link("twitch.tv/kaspa"), "https://www.twitch.tv/kaspa", "Twitch")
        r.eq(link("www.kick.com/kaspa"), "https://kick.com/kaspa", "Kick")
        r.eq(link("github.com/kaspanet"), "https://github.com/kaspanet", "GitHub")
        r.eq(link("github.com/kaspanet/rusty-kaspa"), null, "a GitHub repo is not a profile")
        r.eq(link("telegram.me/kaspa"), "https://t.me/kaspa", "Telegram")
        r.eq(link("t.me/+abcdef"), null, "a private Telegram invite refused")
        r.eq(link("linkedin.com/in/someone/"), "https://www.linkedin.com/in/someone", "LinkedIn person")
        r.eq(link("linkedin.com/company/kaspa"), "https://www.linkedin.com/company/kaspa", "LinkedIn company")
        r.eq(link("discord.gg/kaspa"), "https://discord.gg/kaspa", "Discord invite")
        r.eq(link("https://discord.com/invite/kaspa"), "https://discord.gg/kaspa", "discord.com invite normalized")
        r.eq(link("discord.com/channels/1/2"), null, "a Discord channel is not an invite")
        r.eq(link("reddit.com/u/kaspa"), null, "Reddit unsupported")
        r.eq(link("   "), null, "blank")
        r.eq(link("x.com/bad name"), null, "a space is not a handle character")
        r.eq(link("x.com/" + "a".repeat(101)), null, "handle over 100 characters")
        r.eq(platform("youtube.com/@k"), SocialSource.Platform.YOUTUBE, "platform detected")
        r.eq(SocialSource.from("youtube.com/c/kaspa", A)?.handle, "c/kaspa", "handle keeps the channel path")

        // banners only where the platform shares one
        r.eq(link("x.com/kaspa", B), "https://x.com/kaspa", "X banner")
        r.eq(link("youtube.com/@kaspa", B), "https://www.youtube.com/@kaspa", "YouTube banner")
        r.eq(link("discord.gg/kaspa", B), "https://discord.gg/kaspa", "Discord banner")
        r.eq(link("github.com/kaspanet", B), null, "no GitHub banner")
        r.eq(SocialSource.Platform.values().filter { it.hasBanner }.toSet(),
            setOf(SocialSource.Platform.X, SocialSource.Platform.YOUTUBE, SocialSource.Platform.DISCORD), "banner platforms")
        r.eq(SocialSource.Platform.values().filter { !it.hasBio }.toSet(),
            setOf(SocialSource.Platform.FACEBOOK, SocialSource.Platform.INSTAGRAM, SocialSource.Platform.TIKTOK, SocialSource.Platform.LINKEDIN),
            "platforms without bios")

        // Linktree
        r.eq(Profile.linktreeLink("linktr.ee/kaspa"), "https://linktr.ee/kaspa", "Linktree without scheme")
        r.eq(Profile.linktreeLink("https://linktr.ee/kaspa/extra"), null, "Linktree sub-path refused")
        r.eq(Profile.linktreeLink("https://linktr.ee/ka@spa"), null, "Linktree handle characters")
        r.eq(Profile.linktreeLink("https://linktr.ee/" + "a".repeat(61)), null, "Linktree handle over 60")
        r.eq(Profile.linktreeLink("https://evil.com/linktr.ee"), null, "only linktr.ee")

        // reading the pages
        r.eq(SocialSource.openGraphImage("<META NAME='twitter:image' CONTENT='https://a.b/c.png?x=1&amp;y=2'>"), null,
            "content= is matched as written (lowercase), as on iOS")
        r.eq(SocialSource.openGraphImage("<meta name='twitter:image' content='https://a.b/c.png?x=1&amp;y=2'>"), "https://a.b/c.png?x=1&y=2",
            "twitter:image fallback, single quotes, entities")
        r.eq(SocialSource.openGraphImage("<meta property=\"og:image\" content=\"http://a.b/c.png\">"), null, "an http image is refused")
        r.eq(SocialSource.openGraphDescription("<meta name=\"description\" content=\"  \"><meta property=\"twitter:description\" content=\"It's me\">"),
            "It's me", "an empty description falls through")
        r.eq(SocialSource.bio(SocialSource.Platform.TWITCH, "Just a streamer"), "Just a streamer", "Twitch without boilerplate")
        r.eq(SocialSource.bio(SocialSource.Platform.GITHUB, "from the page"), null, "GitHub's bio comes from its API, not the page")
        r.eq(SocialSource.decodeEntities("&#xD800;x&#99999999999;"), "x", "invalid scalars dropped")
        r.eq(SocialSource.xBanner("..\"https://pbs.twimg.com/profile_banners/123/456\".."), "https://pbs.twimg.com/profile_banners/123/456/1500x500", "X banner")
        r.eq(
            SocialSource.youtubeBanner("\"imageBannerViewModel\" \"imageBannerViewModel\":{\"image\":{\"sources\":[{\"url\":\"https://yt3.googleusercontent.com/abc=w1060\"}]}}"),
            "https://yt3.googleusercontent.com/abc=w1060", "YouTube banner from the object"
        )
        r.eq(SocialSource.youtubeBanner("<html>no banner</html>"), null, "no YouTube banner")
        val invite = "{\"guild\":{\"id\":\"42\",\"icon\":\"ic\",\"banner\":null,\"description\":\" \"}}"
        r.eq(SocialSource.discordImage(invite, A), "https://cdn.discordapp.com/icons/42/ic.png?size=256", "Discord server icon")
        r.eq(SocialSource.discordImage(invite, B), null, "no Discord banner")
        r.eq(SocialSource.discordDescription(invite), null, "a blank Discord description is none")
        r.eq(SocialSource.githubProfile("not json"), null to null, "GitHub garbage")
        r.check(SocialProfile().isEmpty && !SocialProfile(bio = "x").isEmpty) { "SocialProfile.isEmpty" }
        // the editor's picker and handle field (iOS c124cb3)
        r.eq(SocialSource.Platform.choices(SocialSource.Kind.BANNER), listOf(SocialSource.Platform.X, SocialSource.Platform.YOUTUBE, SocialSource.Platform.DISCORD), "banner picker")
        r.eq(SocialSource.Platform.choices(SocialSource.Kind.BIO).toSet(), SocialSource.Platform.values().filter { it.hasBio }.toSet(), "bio picker = platforms with bios")
        r.eq(SocialSource.Platform.choices(A).toSet(), SocialSource.Platform.values().toSet(), "avatar picker = every platform")
        r.check(SocialSource.Platform.values().all { p -> SocialSource.Kind.values().all { k -> p !in SocialSource.Platform.choices(k) || SocialSource.from(p, "kaspa", k) != null } }) {
            "every picker platform takes a plain handle"
        }
        r.eq(SocialSource.from("linkedin.com/in/someone", A)?.displayHandle, "someone", "LinkedIn person shown without in/")
        r.eq(SocialSource.from("linkedin.com/company/k", A)?.displayHandle, "company/k", "LinkedIn company keeps its path")
        r.eq(SocialSource.from(SocialSource.Platform.LINKEDIN, "someone", A)?.link, "https://www.linkedin.com/in/someone", "LinkedIn handle")
        r.eq(SocialSource.from("tiktok.com/@k", A)?.displayHandle, "k", "TikTok shown without @")
        r.check(SocialSource.looksLikeLink("x.com/k") && SocialSource.looksLikeLink("https://k") && !SocialSource.looksLikeLink("k.k")) { "pasted-link detection" }
        r.eq(SocialSource.from(SocialSource.Platform.X, "  ", A), null, "blank handle")
        r.eq(KachatSocialImageResolver.key(" X.com/kaspa "), "https://x.com/kaspa", "cache keyed by the normalized link")
        r.assertClean()
    }
}
