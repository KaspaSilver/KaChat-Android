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
// no price record - from 0ed15e9). JSON is read with Gson (pure Java) instead of JSONSerialization.

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
    /** one paid period, ms: a year on mainnet, 10 minutes on the testnet-10 clock */
    val periodMs: Long,
    val graceMs: Long,
    /** `renew` is valid from `expiresAt - renewWindowMs` on */
    val renewWindowMs: Long,
    /** sompi for a name's first period, by length 1, 2, 3, 4, 5+ bytes */
    val registerPrices: List<Long>,
    /** sompi for every further period (extend, renew, registering past one period) */
    val renewPrices: List<Long>,
    val offerMaxFee: Long
) {
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
     * days on a yearly clock, the renewal window on a short one (testnet's 10 minutes), where 30
     * days would cover every name (iOS 24d673a, IOS-060).
     */
    val expiresSoonMs: Long get() = maxOf(renewWindowMs, minOf(30L * 86_400_000L, periodMs / 12))
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
     * [pinned] is the app's [PINNED_TEMPLATE_HASHES]; tests pass their own.
     */
    fun verify(source: Source = Source.BUNDLE, pinned: Map<String, String> = PINNED_TEMPLATE_HASHES) {
        if (network != SUPPORTED_NETWORK) {
            throw Failure("manifest is for $network; only $SUPPORTED_NETWORK is enabled (mainnet waits for an audit)")
        }
        // The offer build this registry was deployed with, if it is one of ours (iOS 32b7b32,
        // d82dfb2); [pinned] wins where both name a contract.
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
            for (e in ENTRIES[t.contract].orEmpty()) {
                if (t.dispatchTags[e] == null) throw Failure("manifest: ${t.contract} dispatch tag for $e missing")
            }
        }
        if (!KachatNames.contains(gap.suffix, name.templateHash)) throw Failure("manifest: the gap is not built for this name template")
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

        /**
         * Template hashes of the pinned build - registry v4 (silverc v1.0.0 @ 3ed9733), testnet-10
         * params (kachat-domains artifacts/testnet10/build-info.json; iOS 0ed15e9). The gap and the
         * name bake only the params - their fixed prices included - so they are pinned before any
         * genesis. The offer bakes the registry id, so its hash exists once the registry genesis
         * does: the deployment adds it in [DEPLOYED_TEMPLATE_HASHES]. Until every template is
         * pinned only a bundled manifest is trusted (`verify(Source.BUNDLE)`), never one an indexer
         * serves - an unpinned offer template could hold buyers' funds in a script the indexer
         * controls (iOS 1d81a1a, IOS-059).
         */
        val PINNED_TEMPLATE_HASHES: Map<String, String> = mapOf(
            "KachatGap" to "85cf57f8d300331c2acc5191794065d60fafdd29cac90e3b82e3e1ba1c3876f0",
            "KachatName" to "394204b612f345787412156521c0aabbd36bba30311f008302964d4c4ece685a"
        )

        /**
         * The price tables the pinned gap and name bake (kachat-domains params/testnet10.json, iOS
         * 0ed15e9): a manifest whose params say otherwise would show and charge prices the
         * contracts don't.
         */
        val PINNED_REGISTER_PRICES: List<Long> = listOf(4_000_000_000L, 2_000_000_000L, 1_000_000_000L, 250_000_000L, 35_000_000L)
        val PINNED_RENEW_PRICES: List<Long> = listOf(1_000_000_000L, 500_000_000L, 250_000_000L, 62_500_000L, 8_750_000L)

        /**
         * The offer build each deployed registry was launched with, by registry covenant id (iOS
         * 32b7b32, 0ed15e9). A manifest for one of these registries must carry exactly this; any
         * other registry (a dry run, the test vectors) has no offer pin, so only a bundled
         * manifest of it is trusted.
         */
        val DEPLOYED_TEMPLATE_HASHES: Map<String, Map<String, String>> = emptyMap()
        val STATE_LENGTHS: Map<String, Int> = mapOf("KachatGap" to 66, "KachatName" to 126, "KachatOffer" to 108)
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
            // registry v1 - v3 manifests describe contracts this app no longer builds for: it waits
            // for the v4 genesis (iOS 0ed15e9)
            if (num(root.get("registryVersion"))?.asInt != 4) throw Failure.OUTDATED_REGISTRY
            val p = obj(root.get("params")) ?: throw Failure("manifest: params missing")
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
                offerMaxFee = u64(p.get("offerMaxFee"), "offerMaxFee")
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
                network, status, params, gap, name, offer, registryCovenantId, genesisTxid, genesisOutpoint, genesisOutput, genesisState
            )
        }
    }
}
