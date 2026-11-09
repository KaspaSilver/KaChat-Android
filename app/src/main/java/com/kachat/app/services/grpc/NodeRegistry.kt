package com.kachat.app.services.grpc

import java.util.concurrent.ConcurrentHashMap

/**
 * Real-time health record for one known node address.
 *
 * Deliberately simpler than the EWMA/quarantine-curve scheme sketched in the
 * project's POOLS_v2.md reference doc — a plain consecutive-failure counter is
 * enough for a status *display* (this isn't a routing decision under load that
 * needs a graceful backoff curve).
 */
data class NodeRecord(
    val address: String,
    val type: String, // "Seed" | "Discovered" | "DNS" | "Manual" | "Trusted"
    val lastProbe: NodeProbeResult?,
    val consecutiveFailures: Int = 0,
    val lastSuccessAt: Long? = null
)

class NodeRegistry {
    private val records = ConcurrentHashMap<String, NodeRecord>()

    fun update(address: String, type: String, result: NodeProbeResult) {
        val prior = records[address]
        records[address] = NodeRecord(
            address = address,
            type = type,
            lastProbe = result,
            consecutiveFailures = if (result.reachable) 0 else (prior?.consecutiveFailures ?: 0) + 1,
            lastSuccessAt = if (result.reachable) System.currentTimeMillis() else prior?.lastSuccessAt
        )
    }

    fun snapshot(): List<NodeRecord> = records.values.toList()

    fun remove(address: String) {
        records.remove(address)
    }

    /** Resets the registry back to just the given addresses (used by "Clear Connection Pool"). */
    fun resetTo(addresses: List<String>, type: String) {
        records.clear()
        addresses.forEach { records[it] = NodeRecord(address = it, type = type, lastProbe = null) }
    }

    fun containsAddress(address: String): Boolean = records.containsKey(address)

    /**
     * Zeroes every record's consecutive-failure count while keeping the addresses and last
     * probe results — called when the device's network path changes (WiFi <-> cellular, VPN
     * up/down; see NodePoolManager's ConnectivityManager callback). Unreachable/Quarantined
     * verdicts are facts about the OLD network only: a node unreachable on WiFi may be
     * perfectly fine on the VPN, so every node gets a fresh 3-strike allowance on the new
     * path instead of carrying strikes across networks. Records whose last probe failed drop
     * from "Quarantined" back to "Suspect" (statusOf's failure branch) until re-probed, which
     * also protects Discovered/DNS entries from NodePoolManager's Quarantine-based pruning
     * right after a path change.
     */
    fun forgiveFailures() {
        records.replaceAll { _, r -> r.copy(consecutiveFailures = 0) }
    }

    /**
     * The Active nodes, best first: the fastest, with nodes behind the rest by more than
     * [DAA_DELTA_WINDOW] after the others (iOS 4f8e14bb NodeSelector, freshness as a soft signal).
     * The reference is the median across Active nodes, so a few lagging or lying ones can't move
     * it; each score is carried forward to now, as nodes are probed minutes apart and a score that
     * old would read as lag.
     */
    fun activeBestFirst(nowMs: Long = System.currentTimeMillis()): List<NodeRecord> {
        val active = records.values.filter { statusOf(it) == "Active" }
        val scores = active.mapNotNull { projectedDaaScore(it, nowMs) }.sorted()
        val reference = if (scores.isEmpty()) null else scores[scores.size / 2]
        return active.sortedWith(
            compareBy<NodeRecord>(
                { record ->
                    val daa = projectedDaaScore(record, nowMs)
                    reference != null && daa != null && reference - daa > DAA_DELTA_WINDOW
                },
                { it.lastProbe?.latencyMs ?: Long.MAX_VALUE },
            )
        )
    }

    /** A node's reported DAA score carried forward to now; null when the report is missing, not
     *  a real value (it comes from a node found through peer gossip) or past [PROFILE_TTL_MS]. */
    private fun projectedDaaScore(record: NodeRecord, nowMs: Long): Long? {
        val daa = record.lastProbe?.takeIf { it.reachable }?.virtualDaaScore?.takeIf { it in 0..MAX_DAA_SCORE } ?: return null
        val probedAt = record.lastSuccessAt ?: return null
        val age = nowMs - probedAt
        if (age < 0 || age > PROFILE_TTL_MS) return null
        return daa + age / 1000 * DAA_PER_SECOND
    }

    /** Most recent successful probe across every known node — drives the "Last Sync" field. */
    fun lastSuccessAt(): Long? = records.values.mapNotNull { it.lastSuccessAt }.maxOrNull()

    fun statusOf(r: NodeRecord): String {
        // A pinned trusted node is always used regardless of health (see
        // NodePoolManager.getBroadcastConnection, which bypasses this function entirely for
        // it) - the Suspect/Quarantined classification below exists to filter and rank a large
        // auto-discovered pool, and is meaningless (and misleading) for the one node the user
        // explicitly chose to always connect to.
        if (r.type == "Trusted") return "Active"
        return when {
            r.lastProbe?.reachable != true -> if (r.consecutiveFailures >= 3) "Quarantined" else "Suspect"
            r.lastProbe.isSynced == false -> "Suspect" // reachable but not synced — don't trust its data
            else -> "Active"
        }
    }

    private companion object {
        /** Behind by more than this is lag: about ten seconds of blocks (iOS `daaDeltaWindow`). */
        const val DAA_DELTA_WINDOW = 100L
        /** 10 blocks a second (iOS `daaPerSecond`). */
        const val DAA_PER_SECOND = 10L
        /** How long a node's reported score means anything: probes are 30-60 s apart. */
        const val PROFILE_TTL_MS = 5 * 60 * 1000L
        /** Far above any real score, so carrying one forward can't overflow. */
        const val MAX_DAA_SCORE = Long.MAX_VALUE / 2
    }
}
