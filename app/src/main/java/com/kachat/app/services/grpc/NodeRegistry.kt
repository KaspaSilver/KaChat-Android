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
}
