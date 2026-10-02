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
// (KaChat 4c2c45d). JSON is read with Gson (pure Java) instead of JSONSerialization.

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

/** The registry parameters (params/testnet10.json), identical on testnet-10 and mainnet. */
data class Params(
    val bond: Long,
    val gapValue: Long,
    val tCommit: Long,
    val maxYears: Long,
    val graceMs: Long,
    /** sompi per year for names of 1, 2, 3, 4, 5+ bytes */
    val prices: List<Long>,
    val renewPrices: List<Long>,
    val offerMaxFee: Long
) {
    fun price(forLength: Int): Long = prices[Codec.tier(forLength)]
    fun renewPrice(forLength: Int): Long = renewPrices[Codec.tier(forLength)]
}

/**
 * The deployment manifest `kachat-names-<network>.json` (written by the kachat-domains CLI's
 * `genesis`, served by the indexer at `GET /names/manifest`): params, every contract's prefix,
 * suffix, template hash and dispatch tags, the registry covenant id and the genesis binding.
 * [verify] must pass before anything trusts it.
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

    // Verification

    /**
     * Checks everything the app relies on (KACHAT_NAMES_INDEXER.md B2, kachat-domains
     * `manifest::load`): testnet-10 only; every template's hash recomputed from its prefix and
     * suffix, the gap and name ones equal to the pinned build; every dispatch tag present;
     * the offer baked for this registry id and name template; the genesis output is the
     * genesis gap `(00..00, ff..ff)` worth `gapValue`; and
     * `registryCovenantId == covenant_id(genesis outpoint, [(0, genesis gap)])`.
     */
    fun verify() {
        if (network != SUPPORTED_NETWORK) {
            throw Failure("manifest is for $network; only $SUPPORTED_NETWORK is enabled (mainnet waits for an audit)")
        }
        for (t in listOf(gap, name, offer)) {
            if (!Codec.templateHash(t.prefix, t.suffix).contentEquals(t.templateHash)) {
                throw Failure("manifest: ${t.contract} template hash does not match its prefix and suffix")
            }
            val pinned = PINNED_TEMPLATE_HASHES[t.contract]
            if (pinned != null && hex(t.templateHash) != pinned) {
                throw Failure("manifest: ${t.contract} is not the pinned build")
            }
            for (e in ENTRIES[t.contract].orEmpty()) {
                if (t.dispatchTags[e] == null) throw Failure("manifest: ${t.contract} dispatch tag for $e missing")
            }
        }
        if (!KachatNames.contains(offer.suffix, registryCovenantId) || !KachatNames.contains(offer.suffix, name.templateHash)) {
            throw Failure("manifest: the offer is not built for this registry id and name template")
        }
        if (params.prices.size != 5 || params.renewPrices.size != 5 || params.maxYears < 1 || params.maxYears > 31) {
            throw Failure("manifest: params out of range")
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
         * Template hashes of the pinned build (silverc v1.0.0 @ 3ed9733), the same on every
         * network (README "Sizes and template hashes"). The offer bakes the registry id, so it
         * is checked against the id instead.
         */
        val PINNED_TEMPLATE_HASHES: Map<String, String> = mapOf(
            "KachatGap" to "a182d59bbf460baff5ec99ca850b990d45fbafee4dfbe9a3a7a1afe21e7ba8ca",
            "KachatName" to "42eddf19e7ea2bc78b9aa97937f21be0505ebcf964653508f74e179dd6c7e39d"
        )
        val STATE_LENGTHS: Map<String, Int> = mapOf("KachatGap" to 66, "KachatName" to 117, "KachatOffer" to 75)
        val ENTRIES: Map<String, List<String>> = mapOf(
            "KachatGap" to listOf("register", "merge", "absorbed"),
            "KachatName" to listOf("transfer", "list", "buy", "renew", "release", "reclaim"),
            "KachatOffer" to listOf("accept", "withdraw", "refund")
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

        fun fromJson(root: JsonObject): Manifest {
            val network = str(root.get("network"), "network")
            val statusEl = root.get("status")
            val status = if (statusEl != null && statusEl.isJsonPrimitive && statusEl.asJsonPrimitive.isString) statusEl.asString else ""
            val p = obj(root.get("params")) ?: throw Failure("manifest: params missing")
            val params = Params(
                bond = u64(p.get("bond"), "bond"),
                gapValue = u64(p.get("gapValue"), "gapValue"),
                tCommit = u64(p.get("tCommit"), "tCommit"),
                maxYears = u64(p.get("maxYears"), "maxYears"),
                graceMs = u64(p.get("graceMs"), "graceMs"),
                prices = tiers(p.get("prices"), "prices"),
                renewPrices = tiers(p.get("renewPrices"), "renewPrices"),
                offerMaxFee = u64(p.get("offerMaxFee"), "offerMaxFee")
            )
            val artifacts = obj(root.get("artifacts")) ?: throw Failure("manifest: artifacts missing")
            val gap = template(artifacts, "KachatGap")
            val name = template(artifacts, "KachatName")
            val offer = template(artifacts, "KachatOffer")
            val registryCovenantId = unhex32(str(root.get("registryCovenantId"), "registryCovenantId"))
            val g = obj(root.get("genesis")) ?: throw Failure("manifest: genesis missing")
            val genesisTxid = unhex32(str(g.get("txid"), "genesis.txid"))
            val op = str(g.get("outpoint"), "genesis.outpoint").split(":")
            val idx = op.getOrNull(1)?.toLongOrNull()?.takeIf { it in 0..0xffff_ffffL }
            if (op.size != 2 || idx == null) throw Failure("manifest: genesis.outpoint")
            val genesisOutpoint = Outpoint(unhex32(op[0]), idx.toInt())
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
                network, status, params, gap, name, offer, registryCovenantId, genesisTxid, genesisOutpoint,
                genesisOutput, genesisState
            )
        }
    }
}
