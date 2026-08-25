package com.kachat.app.services.grpc

import android.util.Log

/**
 * Result of probing one Kaspa node via real gRPC calls (GetInfo + GetBlockDagInfo).
 */
data class NodeProbeResult(
    val address: String,
    val reachable: Boolean,
    val latencyMs: Long?,
    val isSynced: Boolean?,
    val isUtxoIndexed: Boolean?,
    val serverVersion: String?,
    val networkName: String?,
    val virtualDaaScore: Long?,
    val error: String? = null
)

/**
 * Probes an already-connected [connection] via GetInfo + GetBlockDagInfo. Does NOT
 * open or close the connection — gRPC channels are meant to be long-lived and reused
 * across many calls, not opened/closed per probe. (An earlier version of this
 * function opened and tore down a fresh channel on every call; on-device testing
 * found that churn accumulated native/thread resources fast enough to get the app
 * OOM-killed by the OS every ~90-140 seconds. Connection lifecycle is now owned by
 * whoever holds the persistent connection — see NodePoolManager.)
 */
suspend fun probeExisting(
    address: String,
    connection: KaspadConnection,
    // Discovery races many candidates in parallel and serves the first responder (see
    // NodePoolManager.probeCycle) — a tighter bound there keeps one dead candidate from
    // holding the "all probed" verdict open for the full default RPC timeout. The pinned
    // trusted node keeps the default: it is the only node, often TLS behind slower home
    // links, and a spuriously failed probe there flips the whole app's status dot.
    timeoutMs: Long = 5000
): NodeProbeResult {
    val start = System.currentTimeMillis()
    val info = try {
        connection.getInfo(timeoutMs)
    } catch (e: Exception) {
        // Logged (not just captured in NodeProbeResult.error, which nothing currently reads) —
        // every prior "why is the pool empty" investigation had to add this ad hoc since probe
        // failures were otherwise completely silent.
        Log.w("NodeProfiler", "Probe failed for $address: ${e.javaClass.simpleName}: ${e.message}")
        return NodeProbeResult(
            address = address,
            reachable = false,
            latencyMs = null,
            isSynced = null,
            isUtxoIndexed = null,
            serverVersion = null,
            networkName = null,
            virtualDaaScore = null,
            error = e.message
        )
    }
    val latency = System.currentTimeMillis() - start

    // GetBlockDagInfo is a separate, heavier RPC (walks DAG-tip/blue-score state) - a node
    // that just answered GetInfo is unambiguously reachable and synced regardless of whether
    // this second call succeeds, so don't let a flaky/slow GetBlockDagInfo alone discard a
    // real success and mark an otherwise-healthy node "Suspect"/unreachable. Its fields
    // (networkName/virtualDaaScore) just come back null if it fails.
    val dagInfo = try {
        connection.getBlockDagInfo(timeoutMs)
    } catch (e: Exception) {
        Log.w("NodeProfiler", "GetBlockDagInfo failed for $address (GetInfo still OK): ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    return NodeProbeResult(
        address = address,
        reachable = true,
        latencyMs = latency,
        isSynced = info.isSynced,
        isUtxoIndexed = info.isUtxoIndexed,
        serverVersion = info.serverVersion,
        networkName = dagInfo?.networkName,
        virtualDaaScore = dagInfo?.virtualDaaScore
    )
}
