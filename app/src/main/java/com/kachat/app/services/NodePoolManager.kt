package com.kachat.app.services

import android.util.Log
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.services.grpc.KaspadConnection
import com.kachat.app.services.grpc.NodeRecord
import com.kachat.app.services.grpc.NodeRegistry
import com.kachat.app.services.grpc.probeExisting
import com.kachat.app.viewmodels.NodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real gRPC-backed Kaspa node pool: probes seed/discovered/manual nodes over the
 * actual `protowire.RPC` MessageStream (GetInfo/GetBlockDagInfo/GetPeerAddresses),
 * replacing the previous fake always-healthy simulation.
 *
 * Scoped deliberately simpler than the full POOLS_v2.md architecture (no EWMA
 * scoring, no network-epoch tracking, no hedged requests — see the "explicit
 * non-goals" section of the implementation plan): this drives a status *display*,
 * not a live routing decision under load.
 *
 * If the user pins a "host:port" node in Connection Settings
 * ([AppSettingsRepository.trustedNodeAddress]), this switches to a Kaspium-style
 * fixed-node mode instead: all discovery (seeds/DNS/peer-gossip) stops, and every
 * connection this class hands out is that one address, with no automatic failover
 * to a different node if it goes down (see [trustedNodeAddress]'s doc comment).
 */
@Singleton
class NodePoolManager @Inject constructor(
    private val settings: AppSettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registry = NodeRegistry()

    // Non-null (and non-blank) once the user pins a "host:port" node in Connection Settings -
    // see the reactive collector in init(). When set, probeCycle()/getBroadcastConnection()
    // both short-circuit to this single address, skipping seed/DNS/peer-gossip discovery
    // entirely (Kaspium-style: one trusted node, no automatic failover to a different one).
    private val trustedNodeAddress = MutableStateFlow<String?>(null)

    // Bumped every time trusted mode is entered/left/re-targeted, so a probeCycle() whose
    // network calls were still in flight when the switch happened can tell its own results are
    // now stale and discard them instead of writing them into the just-reset registry.
    private var probeEpoch = 0

    // Persistent, reused gRPC connections — one per known node address. gRPC channels
    // are designed to be long-lived and reused across many calls; an earlier version
    // of this class opened and closed a fresh channel per node on every 30s probe
    // cycle, and on-device testing found that churn accumulated enough native/thread
    // resources to get the app OOM-killed roughly every 90-140 seconds. Connections
    // now live here and are only torn down on clearPool()/reconnect() or when a probe
    // reveals the underlying stream has died.
    private val connections = ConcurrentHashMap<String, KaspadConnection>()

    private val _activeNodes = MutableStateFlow<List<NodeInfo>>(emptyList())
    val activeNodes: StateFlow<List<NodeInfo>> = _activeNodes.asStateFlow()

    private val _allNodes = MutableStateFlow<List<NodeInfo>>(emptyList())
    val allNodes: StateFlow<List<NodeInfo>> = _allNodes.asStateFlow()

    private val seeds = listOf(
        "67.235.212.32:16110",
        "147.135.70.51:16110",
        "187.124.234.86:16110",
        "152.53.52.37:16110",
        "84.32.32.37:16110",
        "198.52.142.150:16110"
    )

    // Same DNS seeders the iOS reference app (KaChat) uses — see NodeModels.swift's
    // mainnetDNSSeeds. Each hostname resolves to multiple A records run by independent
    // Kaspa community operators, so unlike the fixed IP list above this can recover on
    // its own if some/all of those hardcoded IPs go stale or become unreachable.
    private val dnsSeedHostnames = listOf(
        "n.seeder1.kaspad.net",
        "n.seeder2.kaspad.net",
        "n.seeder3.kaspad.net",
        "n.seeder4.kaspad.net",
        "kaspadns.kaspacalc.net",
        "n-mainnet.kaspa.ws",
        "kaspa.aspectron.org"
    )
    private val dnsSeedPort = 16110

    private val manualEndpoints = mutableSetOf<String>()
    private val discoveredEndpoints = mutableSetOf<String>()
    private val dnsResolvedEndpoints = mutableSetOf<String>()
    private var lastDnsResolveAt = 0L

    // Real mainnet nodes' GetPeerAddresses responses can list dozens-to-hundreds of
    // peers, and with only 1/6 seeds typically fully "Active" the discovery trigger
    // below fires on almost every cycle. Without a cap, discoveredEndpoints (and the
    // persistent connection opened per address) grew unbounded and caused a real
    // OutOfMemoryError crash on-device after a few minutes of runtime.
    private val maxDiscoveredEndpoints = 20
    private val maxDnsResolvedEndpoints = 20

    // Roughly matches the floor of iOS's NodeProfiler (minActiveNodes = 8, maxActiveNodes = 12)
    // without importing its full EWMA/network-epoch/replacement machinery — this class is
    // deliberately simpler (see the class doc comment). Both DNS-seed refresh and peer-gossip
    // discovery below keep trying as long as the pool has fewer than this many genuinely
    // Active nodes, rather than stopping the moment a handful of the hardcoded seeds respond.
    private val targetActiveNodes = 8

    // Don't re-resolve DNS on every unhealthy 30s probe cycle — a resolver failure/slowness
    // shouldn't turn into a hot loop of lookups; matches iOS's periodic-not-per-cycle refresh.
    private val dnsResolveCooldownMillis = 60_000L

    // Cycle cadence while the pool is below targetActiveNodes — see startProbing().
    private val unhealthyRetryDelayMillis = 5_000L

    // Per-hostname bound for resolveDnsSeedsIfNeeded()'s parallel lookups.
    private val dnsLookupTimeoutMillis = 5_000L

    private var probeJob: Job? = null

    init {
        registry.resetTo(seeds, "Seed")
        scope.launch {
            // Load the persisted trusted-node setting BEFORE probing ever starts, rather than
            // racing a `collect` against `startProbing()`'s own coroutine - without this, on a
            // fresh launch with a trusted node already saved, the very first probe cycle could
            // run in normal discovery mode (DataStore's read hadn't landed yet), kick off
            // seed/DNS probes, and have those results land in the registry *after* the
            // trusted-mode reset below already ran - leaving stale entries "Other Nodes" would
            // then show despite trusted mode being active.
            val initial = settings.trustedNodeAddress.first().trim().ifBlank { null }
            trustedNodeAddress.value = initial
            probeEpoch++
            if (initial != null) {
                registry.resetTo(listOf(initial), "Trusted")
            }
            startProbing()

            // Now watch for the setting changing while the app is already running.
            settings.trustedNodeAddress.collect { raw ->
                val next = raw.trim().ifBlank { null }
                if (next == trustedNodeAddress.value) return@collect
                trustedNodeAddress.value = next
                probeEpoch++
                // Entering or leaving trusted-node mode invalidates every existing
                // connection/registry entry - same reset shape as clearPool().
                connections.values.forEach { it.close() }
                connections.clear()
                if (next != null) {
                    registry.resetTo(listOf(next), "Trusted")
                } else {
                    manualEndpoints.clear()
                    discoveredEndpoints.clear()
                    dnsResolvedEndpoints.clear()
                    lastDnsResolveAt = 0L
                    registry.resetTo(seeds, "Seed")
                }
                publish()
                refreshNow()
            }
        }
    }

    private fun startProbing() {
        probeJob?.cancel()
        probeJob = scope.launch {
            while (true) {
                probeCycle()
                val delayMillis = if (trustedNodeAddress.value != null) {
                    // Trusted mode's registry only ever holds the one pinned node, so
                    // activeCount below can never reach targetActiveNodes (8) - comparing
                    // against it would permanently pin this to the aggressive cold-launch
                    // retry rate for the node's entire lifetime, hammering it every 5s with
                    // tight per-RPC timeouts and risking spurious failures. Just use the
                    // normal steady-state cadence directly.
                    30_000L
                } else {
                    // While the pool hasn't reached a healthy active count yet (e.g. right after a
                    // fresh app launch, before any node has been confirmed reachable+synced), retry
                    // much sooner than the normal steady-state cadence — a flat 30s here meant a cold
                    // launch could sit on "Disconnected"/0 active for a full 30-90+ seconds even
                    // though a retry a few seconds later would very likely succeed.
                    val activeCount = registry.snapshot().count { registry.statusOf(it) == "Active" }
                    if (activeCount < targetActiveNodes) unhealthyRetryDelayMillis else 30_000L
                }
                delay(delayMillis)
            }
        }
    }

    private fun connectionFor(address: String): KaspadConnection =
        connections.getOrPut(address) { KaspadConnection(address, scope).also { it.connect() } }

    /**
     * Resolves [dnsSeedHostnames] to IP addresses (mirrors iOS's NodeProfiler.refreshDNSSeeds) —
     * the hardcoded [seeds] IPs above have no way to recover if they go stale/unreachable, so this
     * gives the pool an independent way to find fresh nodes without an app update. Cooldown-gated
     * so a run of unhealthy 30s probe cycles doesn't turn into a DNS-lookup hot loop.
     *
     * Looks up all hostnames in parallel, each bounded by [dnsLookupTimeoutMillis] — this used to
     * be a sequential `for` loop calling the blocking `InetAddress.getAllByName` one hostname at a
     * time with no timeout, which meant a single slow/hanging resolver lookup delayed every other
     * hostname behind it, and — since this runs unconditionally on the very first probe cycle,
     * gating node discovery on a cold launch — could stall the whole pool's first probe pass for
     * many seconds before a single node was even attempted.
     */
    private suspend fun resolveDnsSeedsIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastDnsResolveAt < dnsResolveCooldownMillis) return
        lastDnsResolveAt = now
        val resolved = coroutineScope {
            dnsSeedHostnames.map { hostname ->
                async {
                    try {
                        withTimeoutOrNull(dnsLookupTimeoutMillis) {
                            // Accept both IPv4 and IPv6 answers — on-device testing on an
                            // IPv6-heavy/NAT64 network found these hostnames resolving almost
                            // entirely to AAAA records, so an IPv4-only filter (as iOS's
                            // resolveDNSSeed does, matching hints.ai_family = AF_INET) left this
                            // fallback with zero usable endpoints whenever the hardcoded IPv4
                            // [seeds] were also down. IPv6 literals get bracketed for the gRPC
                            // target string, matching standard host:port authority syntax (RFC 3986).
                            java.net.InetAddress.getAllByName(hostname).map { addr ->
                                val host = if (addr is java.net.Inet6Address) "[${addr.hostAddress}]" else addr.hostAddress
                                "$host:$dnsSeedPort"
                            }
                        } ?: emptyList()
                    } catch (e: Exception) {
                        // This seed's DNS lookup failed (resolver down, host unreachable, etc.) —
                        // the cooldown above means all of them get retried again shortly.
                        Log.w("NodePoolManager", "DNS lookup failed for $hostname: ${e.javaClass.simpleName}: ${e.message}")
                        emptyList()
                    }
                }
            }.awaitAll()
        }.flatten()
        for (endpoint in resolved) {
            if (dnsResolvedEndpoints.size >= maxDnsResolvedEndpoints) break
            dnsResolvedEndpoints.add(endpoint)
        }
    }

    private suspend fun probeCycle() {
        // Captured up front so a mode switch (trusted <-> normal discovery) that happens
        // *while this cycle's own probes are in flight* can be detected below and the whole
        // cycle's results discarded instead of writing stale seed/discovered entries into the
        // registry after a resetTo() already ran for the new mode.
        val epoch = probeEpoch

        // Trusted-node mode: no discovery at all - just keep this one connection alive and
        // report its health. No seeds, no DNS, no peer-gossip, no latency-based selection.
        val trusted = trustedNodeAddress.value
        if (trusted != null) {
            connections.keys.filter { it != trusted }.forEach { addr -> connections.remove(addr)?.close() }
            val result = probeExisting(trusted, connectionFor(trusted))
            if (epoch != probeEpoch) return
            if (!result.reachable) {
                connections.remove(trusted)?.close()
            }
            registry.update(trusted, "Trusted", result)
            publish()
            return
        }

        // Gated on truly *Active* count, not just "not yet Quarantined" — a fresh/unprobed seed
        // starts out "Suspect" rather than Quarantined, so gating on non-Quarantined meant this
        // never fired until the hardcoded seeds above had each racked up 3 full failed cycles
        // (~90s) to formally flip to Quarantined. If those seeds are all actually dead (as they
        // periodically seem to go), that's 90 seconds of zero connectivity on every fresh launch
        // before the DNS fallback ever got a chance to run. Checking Active directly means this
        // fires on the very first cycle whenever there isn't already a healthy pool. Compared
        // against targetActiveNodes (not a small fixed number) so this keeps refreshing DNS
        // seeds until the pool is genuinely healthy, not just "a few seeds happened to respond."
        val activeCount = registry.snapshot().count { registry.statusOf(it) == "Active" }
        if (activeCount < targetActiveNodes) {
            resolveDnsSeedsIfNeeded()
        }

        val addresses = (seeds + manualEndpoints + discoveredEndpoints + dnsResolvedEndpoints).distinct()

        // Drop connections for addresses no longer tracked (e.g. after clearPool()).
        connections.keys.filter { it !in addresses }.forEach { addr -> connections.remove(addr)?.close() }

        val results = addresses.map { address ->
            scope.async {
                val result = probeExisting(address, connectionFor(address))
                if (!result.reachable) {
                    // The underlying stream likely died — drop it so the next cycle
                    // opens a fresh connection instead of retrying a broken one forever.
                    connections.remove(address)?.close()
                }
                result
            }
        }.awaitAll()

        // The user may have switched into trusted-node mode while these probes were still in
        // flight - resetTo("Trusted") already ran for that switch, so writing this normal-mode
        // cycle's results now would just re-populate the registry with stale seed/discovered
        // entries right after they were supposed to be cleared.
        if (epoch != probeEpoch) return

        results.forEach { result ->
            val type = when {
                seeds.contains(result.address) -> "Seed"
                manualEndpoints.contains(result.address) -> "Manual"
                dnsResolvedEndpoints.contains(result.address) -> "DNS"
                else -> "Discovered"
            }
            registry.update(result.address, type, result)
        }

        // Prune discovered/DNS-resolved nodes that have gone bad, freeing room for potentially
        // better ones and bounding long-term resource usage — never prune Seed/Manual,
        // those are always intentionally tracked regardless of health.
        registry.snapshot()
            .filter { (it.type == "Discovered" || it.type == "DNS") && registry.statusOf(it) == "Quarantined" }
            .forEach { record ->
                discoveredEndpoints.remove(record.address)
                dnsResolvedEndpoints.remove(record.address)
                connections.remove(record.address)?.close()
                registry.remove(record.address)
            }

        // Peer-gossip discovery: only kick in while the pool is unhealthy, reusing an existing
        // connection rather than opening a new one just for this — combined with DNS-seed
        // resolution above, this is the full v1 discovery mechanism (no aggressive/conservative
        // pacing beyond the cooldown/cap already in place).
        //
        // Bootstraps from the best *reachable* node (Active or Suspect), not just a fully
        // "Active" one — GetPeerAddresses only needs a live gRPC response, not a fully synced
        // node, and requiring Active here meant discovery could never get off the ground at
        // all if every seed was reachable-but-unsynced (Suspect) rather than cleanly Active.
        //
        // Gated on the pool's overall Active count against targetActiveNodes, not just the 6
        // hardcoded seeds specifically — the previous "fewer than 3 of the *seeds*" check meant
        // gossip discovery stopped expanding the moment 3 of those 6 fixed IPs happened to
        // respond, even if the pool's real active total was still small. iOS's equivalent keeps
        // discovering until it reaches a real active-node target (8-12), not until a handful of
        // specific bootstrap addresses look fine.
        val activeCountAfterProbe = registry.snapshot().count { registry.statusOf(it) == "Active" }
        if (activeCountAfterProbe < targetActiveNodes && discoveredEndpoints.size < maxDiscoveredEndpoints) {
            val discoverFrom = registry.snapshot()
                .filter { registry.statusOf(it) != "Quarantined" }
                .minByOrNull { it.lastProbe?.latencyMs ?: Long.MAX_VALUE }
                ?.address
            val conn = discoverFrom?.let { connections[it] }
            if (conn != null) {
                try {
                    conn.getPeerAddresses().addressesList.map { it.addr }.forEach { addr ->
                        if (addr.isNotBlank() && !seeds.contains(addr) && !manualEndpoints.contains(addr) &&
                            discoveredEndpoints.size < maxDiscoveredEndpoints
                        ) {
                            discoveredEndpoints.add(addr)
                        }
                    }
                } catch (e: Exception) {
                    // Ignore — next cycle will retry with whatever's active then.
                    Log.w("NodePoolManager", "Peer-gossip discovery via $discoverFrom failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }

        publish()
    }

    private fun publish() {
        val allInfo = registry.snapshot()
            .map(::toNodeInfo)
            .sortedWith(
                compareByDescending<NodeInfo> { it.status == "Active" }
                    .thenBy { it.latency.removeSuffix("ms").toIntOrNull() ?: Int.MAX_VALUE }
            )
        _allNodes.value = allInfo
        _activeNodes.value = allInfo.filter { it.status == "Active" }
    }

    private fun toNodeInfo(record: NodeRecord): NodeInfo {
        val status = registry.statusOf(record)
        val color = when (status) {
            "Active" -> 0xFF4CD964
            "Suspect" -> 0xFFF39C12
            else -> 0xFFFF3B30 // Quarantined / unreachable
        }
        return NodeInfo(
            ip = record.address,
            type = record.type,
            latency = record.lastProbe?.latencyMs?.let { "${it}ms" } ?: "—",
            daaScore = record.lastProbe?.virtualDaaScore?.toString() ?: "N/A",
            status = status,
            color = color
        )
    }

    /** Real "Last Sync" source — most recent successful probe across every known node. */
    fun lastSuccessAt(): Long? = registry.lastSuccessAt()

    /**
     * Returns a connection to a currently healthy node, for broadcasting a transaction
     * via gRPC SubmitTransaction — bypasses the REST gateway, which mishandles
     * payload-carrying transactions (see KaspadConnection.submitTransaction). Prefers
     * the best-known Active node; falls back to a DNS-resolved address if one's already
     * been found (more likely to actually be reachable than the static seed list if
     * those have gone stale), and only as a last resort to the first hardcoded seed.
     */
    fun getBroadcastConnection(): KaspadConnection {
        trustedNodeAddress.value?.let { return connectionFor(it) }
        val bestActive = registry.snapshot()
            .filter { registry.statusOf(it) == "Active" }
            .minByOrNull { it.lastProbe?.latencyMs ?: Long.MAX_VALUE }
        if (bestActive != null) {
            connections[bestActive.address]?.let { return it }
        }
        val fallbackAddress = dnsResolvedEndpoints.firstOrNull() ?: seeds.first()
        return connectionFor(fallbackAddress)
    }

    /**
     * Drops the cached connection the next [getBroadcastConnection] would return, so it dials
     * fresh. A silently-died gRPC stream is otherwise only reaped by the 30s probe cycle — any
     * submit in that window queued onto the dead stream and ate the full 15s timeout, failing
     * the message while the app still looked "connected" (in trusted-node mode the health
     * readout is pinned Active). The send path uses this for its one-shot reconnect retry.
     */
    fun refreshBroadcastConnection() {
        val address = trustedNodeAddress.value
            ?: registry.snapshot()
                .filter { registry.statusOf(it) == "Active" }
                .minByOrNull { it.lastProbe?.latencyMs ?: Long.MAX_VALUE }?.address
            ?: dnsResolvedEndpoints.firstOrNull() ?: seeds.firstOrNull() ?: return
        connections.remove(address)?.let { runCatching { it.close() } }
    }

    /** Triggers an immediate out-of-cycle probe pass — "Refresh Pool". */
    fun refreshNow() {
        scope.launch { probeCycle() }
    }

    /** Drops discovered/DNS-resolved/manual nodes and all connections, resets to just the seed list — "Clear Connection Pool". */
    fun clearPool() {
        manualEndpoints.clear()
        discoveredEndpoints.clear()
        dnsResolvedEndpoints.clear()
        lastDnsResolveAt = 0L
        connections.values.forEach { it.close() }
        connections.clear()
        registry.resetTo(seeds, "Seed")
        publish()
        refreshNow()
    }

    /** Drops all persistent connections so the next probe cycle opens fresh ones — "Reconnect". */
    fun reconnect() {
        connections.values.forEach { it.close() }
        connections.clear()
        refreshNow()
    }

    /**
     * Reconnects any currently-tracked connection that's dead right now — cheap, non-disruptive
     * alternative to [reconnect]/[clearPool] (which tear down every connection unconditionally),
     * meant to be called whenever the app returns to the foreground (see
     * `KaChatApplication`'s `ProcessLifecycleOwner` observer). A connection can die silently while
     * backgrounded/asleep, and each [KaspadConnection] already self-heals from that on its own
     * (see its `scheduleAutoReconnect`), but backgrounding can suspend the coroutine that would
     * notice the drop, so this gives already-tracked-but-dead connections an immediate nudge
     * instead of waiting for the next 5-30s probe cycle to notice and replace them.
     */
    fun reconnectStaleConnections() {
        connections.values.filter { !it.isConnected }.forEach { it.connect() }
    }

    /** Adds and immediately probes a user-supplied "host:port" endpoint — "Add Custom Endpoint". */
    fun addManualEndpoint(address: String) {
        val trimmed = address.trim()
        if (!trimmed.matches(Regex("^[^:\\s]+:\\d+$"))) return
        manualEndpoints.add(trimmed)
        scope.launch {
            registry.update(trimmed, "Manual", probeExisting(trimmed, connectionFor(trimmed)))
            publish()
        }
    }
}
