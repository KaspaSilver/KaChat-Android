package com.kachat.app.services.kachatnames

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.kachat.app.services.kachatnames.KachatNames.Codec
import com.kachat.app.services.kachatnames.KachatNames.Failure
import com.kachat.app.services.kachatnames.KachatNames.hex
import com.kachat.app.services.kachatnames.KachatNames.unhex
import com.kachat.app.services.kachatnames.KachatNames.unhex32

// The deployment manifest, ported from iOS KaChat/Services/KachatNames/KachatNamesManifest.swift
// (KaChat 4c2c45d; registry v2 from 3ef2ec2; registry v3 from e1e3455; registry v4 - fixed price tables,
// no price record - from 0ed15e9; registry v5 - migration, per-version pins - from 6f18475). JSON is
// read with Gson (pure Java) instead of JSONSerialization.

/** A compiled contract: `redeem = prefix || state || suffix`. */
class Template(
    val contract: String,
    val prefix: ByteArray,
    val suffix: ByteArray,
    val stateLength: Int,
    val templateHash: ByteArray,
    /** entry name -> 4-byte dispatch tag */
    val dispatchTags: Map<String, ByteArray>
) {
    fun redeem(state: ByteArray): ByteArray = prefix + state + suffix

    /** The P2SH script of `prefix || state || suffix`. */
    fun script(state: ByteArray): ByteArray = Codec.p2shScript(redeem(state))

    fun tag(entry: String): ByteArray = dispatchTags[entry] ?: throw Failure("$contract has no entry $entry")

    /** The state of a redeem script of this template (what a spend reveals). */
    fun stateOfRedeem(redeem: ByteArray): ByteArray {
        if (redeem.size != prefix.size + stateLength + suffix.size ||
            !redeem.copyOfRange(0, prefix.size).contentEquals(prefix) ||
            !redeem.copyOfRange(redeem.size - suffix.size, redeem.size).contentEquals(suffix)
        ) {
            throw Failure("not a $contract redeem script")
        }
        return redeem.copyOfRange(prefix.size, prefix.size + stateLength)
    }

    override fun equals(other: Any?): Boolean =
        other is Template && contract == other.contract && prefix.contentEquals(other.prefix) &&
            suffix.contentEquals(other.suffix) && stateLength == other.stateLength &&
            templateHash.contentEquals(other.templateHash) &&
            dispatchTags.keys == other.dispatchTags.keys &&
            dispatchTags.all { (k, v) -> v.contentEquals(other.dispatchTags[k]) }

    override fun hashCode(): Int = 31 * contract.hashCode() + templateHash.contentHashCode()
}

/**
 * The registry parameters (kachat-domains params/<network>.json, registry v4, iOS 0ed15e9). The
 * prices are fixed: baked into the gap and name templates (whose hashes are pinned), so these
 * tables are what the contracts charge.
 */
data class Params(
    val bond: Long,
    val gapValue: Long,
    val tCommit: Long,
    /** most periods a name may be paid ahead */
    val maxYears: Long,
    /** one paid period, ms: a year on mainnet, 24 hours on the testnet-10 clock */
    val periodMs: Long,
    val graceMs: Long,
    /** `renew` is valid from `expiresAt - renewWindowMs` on */
    val renewWindowMs: Long,
    /** sompi for a name's first period, by length 1, 2, 3, 4, 5+ bytes */
    val registerPrices: List<Long>,
    /** sompi for every further period (extend, renew, registering past one period) */
    val renewPrices: List<Long>,
    val offerMaxFee: Long,
    /** Registry v5: the predecessor snapshot this registry imports; null on v4 (and on a v5
     *  registry with no predecessor, whose root and deadline are 0; iOS 6f18475). */
    val migration: Migration? = null
) {
    /** `register` is refused while `now < migration.deadlineMs` (registry v5): the sponsor imports
     *  every snapshot name first. */
    fun registerOpen(now: Long): Boolean = now >= (migration?.deadlineMs ?: 0L)

    // Prices (registry v4: KachatGap.priceFor, KachatName.renewPrice; iOS 0ed15e9)

    fun registerPrice(forLength: Int): Long = registerPrices[Codec.tier(forLength)]
    fun renewPrice(forLength: Int): Long = renewPrices[Codec.tier(forLength)]

    /**
     * What `register` charges for [years] periods: the first at the registration price, every
     * further one at the renewal price.
     */
    fun registerCost(forLength: Int, years: Long): Long =
        Math.addExact(registerPrice(forLength), Math.multiplyExact(renewPrice(forLength), maxOf(years - 1, 0L)))

    // The paid period (KACHAT_NAMES.md 4.1; ops.rs; iOS 3ef2ec2, e1e3455)

    /**
     * The most periods `extend` can add now: a name (from [periodStart]) holds at most [maxYears]
     * periods (ops.rs `extendable_years`).
     */
    fun extendableYears(periodStart: Long, expiresAt: Long): Long {
        val room = periodStart + maxYears * periodMs - expiresAt
        return if (room < 0) 0 else minOf(room / periodMs, maxYears)
    }

    fun extendableYears(f: NameFields): Long = extendableYears(f.periodStart, f.expiresAt)

    /**
     * When `renew` becomes valid: `expiresAt - renewWindowMs` (unix ms). The transaction is final
     * once the network's past median time passes its lock time, which is at least this.
     */
    fun renewOpens(expiresAt: Long): Long = expiresAt - renewWindowMs

    /**
     * How close to expiry a name counts as "expires soon" (a buyer would have to renew it): 30
     * days on a yearly clock, the renewal window on a short one (testnet's 2 hours), where 30
     * days would cover every name (iOS 24d673a, IOS-060).
     */
    val expiresSoonMs: Long get() = maxOf(renewWindowMs, minOf(30L * 86_400_000L, periodMs / 12))
}

/**
 * Registry v5's `params.migration` (kachat-domains docs/REGISTRY_V5.md section 4): the old
 * registry's names, frozen in a Merkle snapshot the new gap imports. Baked into the v5 gap (so its
 * template hash is per deployment; iOS 6f18475).
 */
class Migration(
    val predecessorRegistryId: ByteArray,
    val root: ByteArray,
    val deadlineMs: Long,
    /** x-only key that may import for the snapshot owners (zero: owners only) */
    val sponsor: ByteArray
) {
    override fun equals(other: Any?): Boolean =
        other is Migration && predecessorRegistryId.contentEquals(other.predecessorRegistryId) &&
            root.contentEquals(other.root) && deadlineMs == other.deadlineMs && sponsor.contentEquals(other.sponsor)

    override fun hashCode(): Int = listOf(predecessorRegistryId.contentHashCode(), root.contentHashCode(), deadlineMs, sponsor.contentHashCode()).hashCode()
}

/**
 * The deployment manifest `kachat-names-<network>.json` (written by the kachat-domains CLI's
 * `genesis`, served by the indexer at `GET /names/manifest`): params, every contract's prefix,
 * suffix, template hash and dispatch tags, the registry covenant id and the genesis binding
 * (registry v4: no price covenant). [verify] must pass before anything trusts it.
 */
class Manifest(
    val network: String,
    val status: String,
    /** 4 or 5 ([SUPPORTED_VERSIONS]; iOS 6f18475) */
    val registryVersion: Int,
    val params: Params,
    val gap: Template,
    val name: Template,
    val offer: Template,
    val registryCovenantId: ByteArray,
    val genesisTxid: ByteArray,
    val genesisOutpoint: Outpoint,
    val genesisOutput: TxOutput,
    /** (lo, hi) of the genesis gap */
    val genesisState: Pair<ByteArray, ByteArray>
) {
    /** A manifest from a dry run describes a registry that does not exist. */
    val isDryRun: Boolean get() = status.startsWith("dry run")

    /** Where a manifest came from: the app bundle (shipped with the build) or an indexer (iOS e1e3455). */
    enum class Source { BUNDLE, INDEXER }

    // Verification

    /**
     * Checks everything the app relies on (KACHAT_NAMES_INDEXER.md B2, kachat-domains
     * `manifest::load`): testnet-10 only; every template's hash recomputed from its prefix and
     * suffix and equal to the pinned build where pinned (an indexer-served manifest needs every
     * hash pinned); every dispatch tag present; the gap baked for this name template, the offer
     * for this registry id and name template; both price tables complete, in range and the pinned
     * ones; the genesis output is the genesis gap `(00..00, ff..ff)` worth `gapValue`; and
     * `registryCovenantId == covenant_id(genesis outpoint, [(0, genesis gap)])`.
     *
     * [pinned] is the app's [PINNED_TEMPLATE_HASHES] for this registry version; tests pass their own.
     */
    fun verify(source: Source = Source.BUNDLE, pinned: Map<String, String> = PINNED_TEMPLATE_HASHES[registryVersion].orEmpty()) {
        if (network != SUPPORTED_NETWORK) {
            throw Failure("manifest is for $network; only $SUPPORTED_NETWORK is enabled (mainnet waits for an audit)")
        }
        // The offer (and on v5 the gap) build this registry was deployed with, if it is one of ours
        // (iOS 32b7b32, d82dfb2, 6f18475); [pinned] wins where both name a contract.
        val deployedPins = DEPLOYED_TEMPLATE_HASHES[hex(registryCovenantId)].orEmpty()
        for (t in listOf(gap, name, offer)) {
            if (!Codec.templateHash(t.prefix, t.suffix).contentEquals(t.templateHash)) {
                throw Failure("manifest: ${t.contract} template hash does not match its prefix and suffix")
            }
            val pin = pinned[t.contract] ?: deployedPins[t.contract]
            if (pin != null) {
                if (hex(t.templateHash) != pin) throw Failure("manifest: ${t.contract} is not the pinned build")
            } else if (source == Source.INDEXER) {
                // the offer too: an unpinned offer template could hold buyers' funds in a script
                // the indexer controls (iOS 1d81a1a, IOS-059)
                throw Failure("manifest: ${t.contract} is not pinned in this app; only a bundled manifest is trusted")
            }
            for (e in entries(t.contract, registryVersion)) {
                if (t.dispatchTags[e] == null) throw Failure("manifest: ${t.contract} dispatch tag for $e missing")
            }
        }
        if (!KachatNames.contains(gap.suffix, name.templateHash)) throw Failure("manifest: the gap is not built for this name template")
        params.migration?.let { mig ->
            // the v5 gap bakes its snapshot root and sponsor (the deadline is a number)
            if (!KachatNames.contains(gap.suffix, mig.root) ||
                !(mig.sponsor.contentEquals(KachatNames.ZERO32) || KachatNames.contains(gap.suffix, mig.sponsor))
            ) {
                throw Failure("manifest: the gap is not built for this migration snapshot")
            }
            if (mig.predecessorRegistryId.contentEquals(registryCovenantId)) throw Failure("manifest: a registry can't import itself")
        }
        if (!KachatNames.contains(offer.suffix, registryCovenantId) || !KachatNames.contains(offer.suffix, name.templateHash)) {
            throw Failure("manifest: the offer is not built for this registry id and name template")
        }
        val priceCap = 100_000_000_000_000_000L // scripts/build.py
        if (params.registerPrices.size != 5 || params.renewPrices.size != 5 ||
            !(params.registerPrices + params.renewPrices).all { it <= priceCap } ||
            params.maxYears < 1 || params.maxYears > 31 ||
            params.periodMs < 60_000 || params.periodMs > KachatNames.YEAR_MS || params.maxYears * params.periodMs >= 1_000_000_000_000L ||
            params.renewWindowMs <= 0 || params.renewWindowMs > params.periodMs
        ) {
            throw Failure("manifest: params out of range")
        }
        if (params.registerPrices != PINNED_REGISTER_PRICES || params.renewPrices != PINNED_RENEW_PRICES) {
            throw Failure("manifest: the price tables are not the ones the pinned gap and name bake")
        }
        if (!genesisState.first.contentEquals(KachatNames.ZERO32) || !genesisState.second.contentEquals(KachatNames.FF32)) {
            throw Failure("manifest: genesis gap is not (00..00, ff..ff)")
        }
        val gapScript = gap.script(Codec.gapState(KachatNames.ZERO32, KachatNames.FF32))
        if (!genesisOutput.script.contentEquals(gapScript) || genesisOutput.scriptVersion != 0) {
            throw Failure("manifest: genesis output is not the genesis gap of these templates")
        }
        if (genesisOutput.value != params.gapValue) throw Failure("manifest: genesis gap value")
        val id = Codec.covenantId(genesisOutpoint, listOf(0 to genesisOutput))
        if (!id.contentEquals(registryCovenantId)) {
            throw Failure("manifest: registry id ${hex(registryCovenantId)} != covenant_id(genesis) ${hex(id)}")
        }
    }

    companion object {
        const val SUPPORTED_NETWORK = "testnet-10"
        const val BUNDLE_RESOURCE = "kachat-names-testnet-10"
        /** The bundled manifest under app/src/main/assets. */
        const val ASSET_NAME = "$BUNDLE_RESOURCE.json"

        /** Registry versions this app builds for: v4, and v5 (v4 plus `import` from a migration
         *  snapshot, kachat-domains docs/REGISTRY_V5.md; iOS 6f18475). */
        val SUPPORTED_VERSIONS: Set<Int> = setOf(4, 5)

        /**
         * Template hashes of the pinned build by registry version (silverc v1.0.0 @ 3ed9733),
         * testnet-10 params on the day clock: 24-hour periods, 6-hour grace, 2-hour renewal window
         * (kachat-domains artifacts/testnet10/build-info.json; iOS 0ed15e9, 08107e1, 6f18475). The
         * v4 gap and the name bake only the params - their fixed prices included - so they are
         * pinned before any genesis. The v5 gap also bakes its migration (snapshot root, deadline,
         * sponsor) and the offer the registry id, so their hashes exist per deployment:
         * [DEPLOYED_TEMPLATE_HASHES]. Until every template is pinned only a bundled manifest is
         * trusted (`verify(Source.BUNDLE)`), never one an indexer serves - an unpinned offer
         * template could hold buyers' funds in a script the indexer controls (iOS 1d81a1a, IOS-059).
         */
        val PINNED_TEMPLATE_HASHES: Map<Int, Map<String, String>> = mapOf(
            4 to mapOf(
                "KachatGap" to "9f057f406361583eb2b94956825f86a2d8cc47d3c8800f05855a3e75b39d8bf5",
                "KachatName" to "c263a8c2cb4bdfac3234675114fc3ce4ba5a1d26c12e887c3d3b2ca89460b56b"
            ),
            5 to mapOf(
                "KachatName" to "c263a8c2cb4bdfac3234675114fc3ce4ba5a1d26c12e887c3d3b2ca89460b56b"
            )
        )

        /**
         * The price tables the pinned gap and name bake (kachat-domains params/testnet10.json, iOS
         * 0ed15e9): a manifest whose params say otherwise would show and charge prices the
         * contracts don't.
         */
        val PINNED_REGISTER_PRICES: List<Long> = listOf(4_000_000_000L, 2_000_000_000L, 1_000_000_000L, 250_000_000L, 35_000_000L)
        val PINNED_RENEW_PRICES: List<Long> = listOf(1_000_000_000L, 500_000_000L, 250_000_000L, 62_500_000L, 8_750_000L)

        /**
         * The per-deployment builds (the offer; on v5 also the gap) each deployed registry was
         * launched with, by registry covenant id (iOS 32b7b32, 0ed15e9, 6f18475). A manifest for one
         * of these registries must carry exactly this; any other registry (a dry run, the test
         * vectors) has no such pin, so only a bundled manifest of it is trusted.
         */
        val DEPLOYED_TEMPLATE_HASHES: Map<String, Map<String, String>> = mapOf(
            // testnet-10 registry v5, the migration drill of 2026-10-09: genesis 408682e6..dfda5,
            // imports the day-clock v4 registry e6b72448..7f0d (snapshot of 6 names)
            "fdc403f5ef76ea7c71dcb5305d09daf7ab7fd68dc1d274a314fc8ca9111e571d" to mapOf(
                "KachatGap" to "afce97e05a6341ea7768252a264c65882b92105f8d7158a3ac63f68fbe1615cb",
                "KachatOffer" to "9d6e666481ea80e27565e68c01e6de51b32660d2f91368d9b80e4ee00b981d6d"
            ),
            // testnet-10 registry v4 on the day clock, 2026-10-07: genesis 5ffdd006..a777 (the
            // 10-minute deployment bff18554..0e2f before it is retired: its gap and name aren't
            // pinned; iOS 08107e1)
            "e6b7244831004e1db928458bce570347317b50ff124c010d342d73a6c2017f0d" to mapOf(
                "KachatOffer" to "5a7e22af319bac406769563b6b4b39b05c3aac145375ccaaada4095960372a7a"
            ),
        )
        val STATE_LENGTHS: Map<String, Int> = mapOf("KachatGap" to 66, "KachatName" to 126, "KachatOffer" to 108)
        /** The entries a contract must carry a dispatch tag for: the v5 gap adds `import`. */
        fun entries(contract: String, version: Int): List<String> {
            if (contract == "KachatGap" && version >= 5) return listOf("register", "merge", "absorbed", "import")
            return ENTRIES[contract].orEmpty()
        }
        val ENTRIES: Map<String, List<String>> = mapOf(
            "KachatGap" to listOf("register", "merge", "absorbed"),
            "KachatName" to listOf("transfer", "list", "buy", "extend", "renew", "release", "reclaim"),
            "KachatOffer" to listOf("accept", "decline", "withdraw", "refund")
        )

        // Decoding

        fun decode(json: String): Manifest {
            val root = try {
                JsonParser.parseString(json)
            } catch (e: Exception) {
                throw Failure("manifest: not JSON (${e.message})")
            }
            if (!root.isJsonObject) throw Failure("manifest: not a JSON object")
            return fromJson(root.asJsonObject)
        }

        fun decode(data: ByteArray): Manifest = decode(String(data, Charsets.UTF_8))

        private fun obj(v: JsonElement?): JsonObject? = if (v != null && v.isJsonObject) v.asJsonObject else null

        private fun num(v: JsonElement?): JsonPrimitive? =
            if (v != null && v.isJsonPrimitive && v.asJsonPrimitive.isNumber) v.asJsonPrimitive else null

        private fun str(v: JsonElement?, what: String): String {
            if (v == null || !v.isJsonPrimitive || !v.asJsonPrimitive.isString) throw Failure("manifest: $what missing")
            return v.asString
        }

        private fun u64(v: JsonElement?, what: String): Long {
            val n = num(v) ?: throw Failure("manifest: $what missing")
            val l = n.asLong
            if (l < 0) throw Failure("manifest: $what missing")
            return l
        }

        private fun tiers(v: JsonElement?, what: String): List<Long> {
            val o = obj(v) ?: throw Failure("manifest: $what missing")
            return listOf("len1", "len2", "len3", "len4", "len5plus").map { u64(o.get(it), "$what.$it") }
        }

        private fun template(artifacts: JsonObject, contract: String): Template {
            val a = obj(artifacts.get(contract)) ?: throw Failure("manifest: $contract missing")
            val prefix = unhex(str(a.get("prefixHex"), "$contract.prefixHex"))
            val suffix = unhex(str(a.get("suffixHex"), "$contract.suffixHex"))
            val hash = unhex32(str(a.get("templateHash"), "$contract.templateHash"))
            val tagsJson = obj(a.get("dispatchTags")) ?: throw Failure("manifest: $contract.dispatchTags missing")
            val tags = LinkedHashMap<String, ByteArray>()
            for ((k, v) in tagsJson.entrySet()) {
                val t = unhex(str(v, "$contract.dispatchTags.$k"))
                if (t.size != 4) throw Failure("manifest: $contract dispatch tag $k is not 4 bytes")
                tags[k] = t
            }
            val stateLength = STATE_LENGTHS[contract] ?: 0
            // the declared lengths and state span must agree with the bytes
            num(a.get("prefixLen"))?.let { if (it.asInt != prefix.size) throw Failure("manifest: $contract.prefixLen") }
            num(a.get("suffixLen"))?.let { if (it.asInt != suffix.size) throw Failure("manifest: $contract.suffixLen") }
            obj(a.get("stateSpan"))?.let { span ->
                if (num(span.get("offset"))?.asInt != prefix.size || num(span.get("len"))?.asInt != stateLength) {
                    throw Failure("manifest: $contract.stateSpan")
                }
            }
            num(a.get("bytecodeLen"))?.let {
                if (it.asInt != prefix.size + stateLength + suffix.size) throw Failure("manifest: $contract.bytecodeLen")
            }
            return Template(contract, prefix, suffix, stateLength, hash, tags)
        }

        private fun outpoint(v: JsonElement?, what: String): Outpoint {
            val op = str(v, what).split(":")
            val idx = op.getOrNull(1)?.toLongOrNull()?.takeIf { it in 0..0xffff_ffffL }
            if (op.size != 2 || idx == null) throw Failure("manifest: $what")
            return Outpoint(unhex32(op[0]), idx.toInt())
        }

        fun fromJson(root: JsonObject): Manifest {
            val network = str(root.get("network"), "network")
            val statusEl = root.get("status")
            val status = if (statusEl != null && statusEl.isJsonPrimitive && statusEl.asJsonPrimitive.isString) statusEl.asString else ""
            // registry v1 - v3 manifests describe contracts this app no longer builds for; a later
            // version needs a newer app (iOS 0ed15e9, 6f18475)
            val version = num(root.get("registryVersion"))?.let { n -> n.asDouble.takeIf { it == Math.floor(it) && it in 0.0..1e9 }?.toInt() } ?: 0
            if (version !in SUPPORTED_VERSIONS) {
                throw if (version > SUPPORTED_VERSIONS.max()) Failure.NEWER_REGISTRY else Failure.OUTDATED_REGISTRY
            }
            val p = obj(root.get("params")) ?: throw Failure("manifest: params missing")
            var migration: Migration? = null
            if (version >= 5) {
                val mj = obj(p.get("migration")) ?: throw Failure("manifest: params.migration missing (registry v5)")
                val mig = Migration(
                    predecessorRegistryId = unhex32(str(mj.get("predecessorRegistryId"), "migration.predecessorRegistryId")),
                    root = unhex32(str(mj.get("root"), "migration.root")),
                    deadlineMs = u64(mj.get("deadlineMs"), "migration.deadlineMs"),
                    sponsor = unhex32(str(mj.get("sponsor"), "migration.sponsor"))
                )
                // root 0 and deadline 0: a v5 registry with no predecessor (register works as on v4)
                migration = if (mig.root.contentEquals(KachatNames.ZERO32) && mig.deadlineMs == 0L) null else mig
            }
            val params = Params(
                bond = u64(p.get("bond"), "bond"),
                gapValue = u64(p.get("gapValue"), "gapValue"),
                tCommit = u64(p.get("tCommit"), "tCommit"),
                maxYears = u64(p.get("maxYears"), "maxYears"),
                periodMs = u64(p.get("periodMs"), "periodMs"),
                graceMs = u64(p.get("graceMs"), "graceMs"),
                renewWindowMs = u64(p.get("renewWindowMs"), "renewWindowMs"),
                registerPrices = tiers(obj(p.get("prices"))?.get("register"), "prices.register"),
                renewPrices = tiers(obj(p.get("prices"))?.get("renew"), "prices.renew"),
                offerMaxFee = u64(p.get("offerMaxFee"), "offerMaxFee"),
                migration = migration
            )
            val artifacts = obj(root.get("artifacts")) ?: throw Failure("manifest: artifacts missing")
            val gap = template(artifacts, "KachatGap")
            val name = template(artifacts, "KachatName")
            val offer = template(artifacts, "KachatOffer")
            val registryCovenantId = unhex32(str(root.get("registryCovenantId"), "registryCovenantId"))
            val g = obj(root.get("genesis")) ?: throw Failure("manifest: genesis missing")
            val genesisTxid = unhex32(str(g.get("txid"), "genesis.txid"))
            val genesisOutpoint = outpoint(g.get("outpoint"), "genesis.outpoint")
            val outsEl = g.get("authorizedOutputs")
            if (outsEl == null || !outsEl.isJsonArray || outsEl.asJsonArray.size() != 1) {
                throw Failure("manifest: the genesis must authorize exactly one output")
            }
            val o = obj(outsEl.asJsonArray[0]) ?: throw Failure("manifest: the genesis must authorize exactly one output")
            if (num(o.get("index"))?.asInt != 0) throw Failure("manifest: the genesis gap is not output 0")
            val genesisOutput = TxOutput(
                value = u64(o.get("value"), "genesis value"),
                scriptVersion = u64(o.get("scriptPublicKeyVersion"), "genesis spk version").toInt(),
                script = unhex(str(o.get("scriptPublicKey"), "genesis spk")),
                covenant = null
            )
            val st = obj(o.get("state")) ?: throw Failure("manifest: genesis state missing")
            val genesisState = unhex32(str(st.get("lo"), "genesis lo")) to unhex32(str(st.get("hi"), "genesis hi"))
            return Manifest(
                network, status, version, params, gap, name, offer, registryCovenantId, genesisTxid, genesisOutpoint, genesisOutput, genesisState
            )
        }
    }
}
