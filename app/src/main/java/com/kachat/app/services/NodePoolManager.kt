package com.kachat.app.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.kachat.app.repository.AppSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import com.kachat.app.services.grpc.KaspadConnection
import com.kachat.app.services.grpc.NodeProbeResult
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
import kotlinx.coroutines.flow.combine
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
    @ApplicationContext private val context: Context,
    private val settings: AppSettingsRepository,
    // For the probe-loop background gate only (same rule as ChatRepository's 2s sync loop):
    // while the app is backgrounded AND native FCM push is active, probing pauses instead of
    // dialing nodes every 5-30s for the whole life of a battery-exempted background process.
    // Both are dependency-cycle-safe here: NotificationHelper needs only Context + settings,
    // and PushState is deliberately dependency-free.
    private val notificationHelper: NotificationHelper,
    private val pushState: PushState,
    // Metered-network (cellular) gate for discovery-mode probing — see probeCycle() and
    // startProbing(): a healthy pool probes only its primary node at a slower cadence, and
    // the other pooled connections are closed between cycles so their HTTP/2 keepalive pings
    // stop. Pinned trusted-node mode is untouched (it is already a single cheap connection).
    private val meteredNetwork: MeteredNetwork
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

    // Bundled bootstrap-of-last-resort node IPs. These exist for networks where the DNS
    // seeders below are blocked or poisoned (captive portals, restrictive firewalls, hostile
    // resolvers) — with DNS dead and nothing persisted yet, they are the only way into the
    // network. All addresses come from live-resolving the same community DNS seeders listed
    // in [dnsSeedHostnames] (the source the whole Kaspa ecosystem already trusts for
    // bootstrap) and keeping only the ones that actually answered on the public gRPC port.
    // Last refreshed 2026-08-25; the previous six entries had ALL gone stale. Public node
    // IPs churn, which is why these are a last resort: the persisted last-known-good list
    // (see [persistKnownGoodNodes]) and the DNS seeders are always preferred, and this list
    // should be re-verified against the seeders' current answers whenever it is touched.
    private val seeds = listOf(
        "212.227.144.45:16110",
        "87.236.31.226:16110",
        "82.66.82.52:16110",
        "72.28.135.10:16110",
        "23.118.8.164:16110",
        "218.81.36.168:16110"
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

    // Steady-state cycle cadence on a METERED network (automatic mode, pool healthy) — twice
    // the WiFi 30s. Combined with the primary-only probing in probeCycle() this is most of the
    // node pool's cellular saving; WiFi cadence is unchanged.
    private val meteredHealthyDelayMillis = 60_000L

    // Per-hostname bound for resolveDnsSeedsIfNeeded()'s parallel lookups.
    private val dnsLookupTimeoutMillis = 5_000L

    // Per-candidate RPC timeout while racing discovery probes in parallel — tight (vs the 5s
    // RPC default the pinned trusted node keeps) because the first responder is published
    // immediately (see probeCycle) and a dead candidate should not hold a cycle open long.
    private val discoveryProbeTimeoutMillis = 3_000L

    // Consecutive completed automatic-mode cycles in which not a single probed node was
    // reachable — drives both the exponential probe backoff in startProbing() (a fully
    // blocked network must not burn battery on a 5s retry loop forever) and, from the
    // second such cycle on, the nodeConnectionsBlocked flag below.
    private var consecutiveDeadCycles = 0
    private val maxDeadCycleBackoffMillis = 120_000L

    // True once probing has concluded that no Kaspa gRPC node is reachable at all on the
    // current network (every candidate dead for 2+ consecutive automatic-mode cycles, or the
    // pinned trusted node failing 2+ probes in a row). Cleared by the very first successful
    // probe and on any network-path change. Surfaced on the connection screen so a user on a
    // firewalled network gets an honest "gRPC is blocked here" explanation instead of an
    // eternally red dot: indexer-based receiving still works over HTTPS, but sending cannot
    // (payload-carrying transactions only submit over gRPC — see KaspaWalletEngine.sendKaspa).
    private val _nodeConnectionsBlocked = MutableStateFlow(false)
    val nodeConnectionsBlocked: StateFlow<Boolean> = _nodeConnectionsBlocked.asStateFlow()

    // Persisted-known-good bookkeeping — see persistKnownGoodNodes().
    private var lastPersistedKnownGood: Set<String> = emptySet()
    private val maxPersistedKnownGood = 8

    // Network-path-change tracking (WiFi <-> cellular, VPN up/down) — see the
    // ConnectivityManager callback registered in init and onNetworkPathChanged().
    @Volatile
    private var lastNetworkHandle: Long? = null
    @Volatile
    private var lastNetworkResetAt = 0L

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
            } else {
                // Cold start in automatic mode: dial the persisted last-known-good nodes from
                // the previous run alongside the bundled seeds on the very first cycle —
                // recently-proven addresses connect near-instantly, where the bundled seeds
                // may have gone stale and DNS resolution takes a round trip.
                seedFromPersistedKnownGood()
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
                    // Pinned -> automatic must connect near-instantly: race the persisted
                    // last-known-good nodes from previous automatic sessions in the immediate
                    // refreshNow() below instead of starting from the bundled seeds alone.
                    seedFromPersistedKnownGood()
                }
                consecutiveDeadCycles = 0
                _nodeConnectionsBlocked.value = false
                publish()
                refreshNow()
            }
        }

        registerNetworkCallback()
    }

    /**
     * Loads the persisted last-known-good node addresses (written by [persistKnownGoodNodes]
     * at the end of every healthy automatic-mode cycle) into [discoveredEndpoints], so the next
     * probe cycle dials them immediately. They intentionally enter as ordinary "Discovered"
     * entries: if one has died since the last run it racks up failures like any other candidate
     * and gets pruned, so a stale persisted list can never wedge the pool.
     */
    private suspend fun seedFromPersistedKnownGood() {
        val persisted = try {
            settings.knownGoodNodeAddresses.first()
        } catch (e: Exception) {
            Log.w("NodePoolManager", "Could not load persisted known-good nodes: ${e.message}")
            emptyList()
        }
        lastPersistedKnownGood = persisted.toSet()
        for (address in persisted) {
            if (discoveredEndpoints.size >= maxDiscoveredEndpoints) break
            if (address !in seeds) discoveredEndpoints.add(address)
        }
        if (persisted.isNotEmpty()) {
            Log.i("NodePoolManager", "Seeded pool with ${persisted.size} persisted known-good node(s)")
        }
    }

    /**
     * Persists the pool's current best Active nodes (best-latency first, capped at
     * [maxPersistedKnownGood]) whenever the set actually changed — the other half of
     * [seedFromPersistedKnownGood]'s instant-reconnect path. Automatic mode only; the pinned
     * trusted node is its own persisted setting.
     */
    private fun persistKnownGoodNodes() {
        if (trustedNodeAddress.value != null) return
        val best = registry.snapshot()
            .filter { registry.statusOf(it) == "Active" }
            .sortedBy { it.lastProbe?.latencyMs ?: Long.MAX_VALUE }
            .take(maxPersistedKnownGood)
            .map { it.address }
        if (best.isEmpty() || best.toSet() == lastPersistedKnownGood) return
        lastPersistedKnownGood = best.toSet()
        scope.launch {
            try {
                settings.setKnownGoodNodeAddresses(best)
            } catch (e: Exception) {
                Log.w("NodePoolManager", "Could not persist known-good nodes: ${e.message}")
            }
        }
    }

    /**
     * Watches the device's default network (WiFi <-> cellular handoffs, VPN up/down) and resets
     * node health the moment the path changes. Without this, verdicts formed on the OLD path
     * poison the new one: nodes quarantined behind a captive WiFi portal stayed quarantined
     * after switching to working cellular, connections silently bound to a dead VPN tunnel ate
     * full RPC timeouts, and a fully-blocked verdict ([nodeConnectionsBlocked]) outlived the
     * network that earned it. Mirrors the "network epoch" idea from iOS's POOLS_v2.
     */
    private fun registerNetworkCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val handle = network.networkHandle
                    val prior = lastNetworkHandle
                    lastNetworkHandle = handle
                    // First sighting after process start is not a change; same handle re-announced
                    // (capability churn on one network) is not a change either.
                    if (prior == null || prior == handle) return
                    onNetworkPathChanged()
                }
            })
        } catch (e: Exception) {
            // Missing permission or an OEM quirk — the pool still works, just without
            // instant path-change recovery (the probe loop eventually re-probes everything).
            Log.w("NodePoolManager", "Could not register network callback: ${e.message}")
        }
    }

    /**
     * The default network actually changed — scrap per-path state and re-probe immediately.
     * Debounced so a flapping VPN (rapid up/down/up) collapses into one reset every 2s instead
     * of a teardown storm that would itself wedge discovery.
     */
    private fun onNetworkPathChanged() {
        val now = System.currentTimeMillis()
        if (now - lastNetworkResetAt < 2_000L) return
        lastNetworkResetAt = now
        Log.i("NodePoolManager", "Default network changed - resetting node health and reconnecting")
        // In-flight probe results belong to the old path; discard them.
        probeEpoch++
        // Failure verdicts are per-network: a node dead on WiFi gets a fresh 3-strike
        // allowance on the VPN/cellular path instead of staying Quarantined.
        registry.forgiveFailures()
        // Sockets are bound to the old path; dial fresh on the new one.
        connections.values.forEach { it.close() }
        connections.clear()
        // Re-resolve DNS on the new path (a poisoned/blocked resolver may now work, and
        // split-horizon answers can differ per network), and restart backoff from hot.
        lastDnsResolveAt = 0L
        consecutiveDeadCycles = 0
        _nodeConnectionsBlocked.value = false
        publish()
        refreshNow()
    }

    private fun startProbing() {
        probeJob?.cancel()
        probeJob = scope.launch {
            while (true) {
                // Background gate, mirroring ChatRepository's poll-loop gate: while the app is
                // BACKGROUNDED and native FCM push is active, stop dialing nodes entirely
                // (suspend on the combined flow — no keepalive traffic at all) and resume
                // instantly on foreground or the moment push stops being reliable. On resume a
                // probe cycle runs immediately, and the on-foreground
                // reconnectStaleConnections() call in KaChatApplication independently revives
                // any connections the OS quietly killed during the pause. Without FCM
                // (pushActive can never turn true) this gate never engages, so the loop
                // behaves exactly as before.
                if (!notificationHelper.isAppInForeground && pushState.isActive) {
                    combine(notificationHelper.appForegroundFlow, pushState.pushActive) { foreground, pushActive ->
                        foreground || !pushActive
                    }.first { it }
                }
                val anyReachable = probeCycle()
                if (anyReachable) consecutiveDeadCycles = 0 else consecutiveDeadCycles++
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
                    //
                    // BUT: if entire cycles are coming back with zero reachable nodes (gRPC blocked
                    // outright on this network, airplane mode, dead uplink), that aggressive 5s
                    // cadence must not run forever — dialing ~26 dead endpoints every 5s is pure
                    // battery burn that cannot succeed. Back off exponentially (10s, 20s, 40s, 80s,
                    // capped at 2 min) until something answers; a network-path change resets to hot
                    // immediately (see onNetworkPathChanged), as does refreshNow() via the user.
                    val activeCount = registry.snapshot().count { registry.statusOf(it) == "Active" }
                    when {
                        consecutiveDeadCycles > 0 -> {
                            val shifts = minOf(consecutiveDeadCycles, 5)
                            minOf(unhealthyRetryDelayMillis shl shifts, maxDeadCycleBackoffMillis)
                        }
                        activeCount < targetActiveNodes -> unhealthyRetryDelayMillis
                        // Healthy pool on cellular: probe half as often (and probeCycle itself
                        // shrinks to a primary-only probe — see the metered branch there).
                        meteredNetwork.isMetered -> meteredHealthyDelayMillis
                        else -> 30_000L
                    }
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

    /** Classification for registry rows — depends on which tracked set the address lives in. */
    private fun typeOf(address: String): String = when {
        seeds.contains(address) -> "Seed"
        manualEndpoints.contains(address) -> "Manual"
        dnsResolvedEndpoints.contains(address) -> "DNS"
        else -> "Discovered"
    }

    /**
     * Races probes of [addresses] in parallel (each bounded by [discoveryProbeTimeoutMillis])
     * and publishes every SUCCESS the moment it lands — the first responder starts serving
     * status/broadcast duty immediately while the slower candidates are still being profiled,
     * instead of the old await-everything-then-publish shape where one dead candidate's full
     * timeout held the whole pool at "Disconnected". Failures are only written by the caller
     * after the wave completes (and only if the epoch still matches), keeping the stale-cycle
     * discard semantics for everything except early good news.
     */
    private suspend fun probeWave(addresses: List<String>, epoch: Int): List<NodeProbeResult> =
        addresses.map { address ->
            scope.async {
                val result = probeExisting(address, connectionFor(address), discoveryProbeTimeoutMillis)
                if (!result.reachable) {
                    // The underlying stream likely died — drop it so the next cycle
                    // opens a fresh connection instead of retrying a broken one forever.
                    connections.remove(address)?.close()
                } else if (epoch == probeEpoch) {
                    registry.update(address, typeOf(address), result)
                    _nodeConnectionsBlocked.value = false
                    publish()
                }
                result
            }
        }.awaitAll()

    /** Returns true when at least one probed node was reachable this cycle (epoch-discarded
     *  cycles report true so they stay neutral for the dead-cycle backoff in startProbing). */
    private suspend fun probeCycle(): Boolean {
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
            if (epoch != probeEpoch) return true
            if (!result.reachable) {
                connections.remove(trusted)?.close()
            }
            registry.update(trusted, "Trusted", result)
            // With discovery off, the pinned node is the only reachability signal there is:
            // 2+ consecutive failed probes is the honest "node connections look blocked or
            // down here" threshold for the connection screen (one failure could be a blip).
            val failures = registry.snapshot().firstOrNull { it.address == trusted }?.consecutiveFailures ?: 0
            _nodeConnectionsBlocked.value = !result.reachable && failures >= 2
            publish()
            return result.reachable
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
        //
        // Launched CONCURRENTLY with the first probe wave below (it used to be awaited first),
        // so a slow resolver never delays dialing the seeds/persisted known-good nodes we
        // already have in hand — fresh DNS answers get their own probe wave later this cycle.
        val activeCount = registry.snapshot().count { registry.statusOf(it) == "Active" }
        val dnsJob = if (activeCount < targetActiveNodes) scope.async { resolveDnsSeedsIfNeeded() } else null

        // Metered steady state: with a healthy pool on cellular, probing all ~26 endpoints
        // every cycle — and keeping a persistent gRPC channel (20s HTTP/2 keepalive pings each)
        // open to every one of them — is pure background cost. Probe ONLY the current primary
        // (the same best-active node getBroadcastConnection() serves); the untracked-connection
        // sweep below then closes every other idle channel, which is the "close idle discovered
        // channels between probe cycles" option rather than per-connection keepalive tuning:
        // each KaspadConnection holds its MessageStream open for its whole life, so
        // keepAliveWithoutCalls(false) would change nothing — the stream IS an active call.
        // There is no reopen churn while the state holds (closed channels stay closed; nothing
        // redials them until the pool turns unhealthy or the primary fails over). Registry
        // verdicts for the unprobed nodes just go stale, which a status display tolerates; if
        // the primary dies, the next cycle promotes the next stale-Active node, redials it, and
        // failures decay the truly dead ones until the pool drops below target and full
        // discovery resumes automatically. WiFi keeps the full wave exactly as before.
        val fullWave = (seeds + manualEndpoints + discoveredEndpoints + dnsResolvedEndpoints).distinct()
        val firstWave = if (meteredNetwork.isMetered && activeCount >= targetActiveNodes) {
            registry.snapshot()
                .filter { registry.statusOf(it) == "Active" }
                .minByOrNull { it.lastProbe?.latencyMs ?: Long.MAX_VALUE }
                ?.let { listOf(it.address) }
                ?: fullWave
        } else {
            fullWave
        }
        val firstResults = probeWave(firstWave, epoch)

        // Probe whatever the concurrent DNS resolution just added in this same cycle rather
        // than sitting on it until the next tick — on a cold start where the bundled seeds
        // have all gone stale, these fresh answers ARE the pool.
        dnsJob?.await()
        val secondWave = dnsResolvedEndpoints.filter { it !in firstWave }
        val secondResults = if (secondWave.isNotEmpty()) probeWave(secondWave, epoch) else emptyList()

        // The user may have switched into trusted-node mode while these probes were still in
        // flight - resetTo("Trusted") already ran for that switch, so writing this normal-mode
        // cycle's results now would just re-populate the registry with stale seed/discovered
        // entries right after they were supposed to be cleared.
        if (epoch != probeEpoch) return true

        val results = firstResults + secondResults
        val probedAddresses = firstWave + secondWave

        // Drop connections for addresses no longer tracked (e.g. after clearPool()).
        connections.keys.filter { it !in probedAddresses }.forEach { addr -> connections.remove(addr)?.close() }

        results.forEach { result ->
            registry.update(result.address, typeOf(result.address), result)
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

        // Remember today's best Active nodes for tomorrow's cold start / pinned->automatic
        // switch — the other half of the instant-connect path (see seedFromPersistedKnownGood).
        persistKnownGoodNodes()

        val anyReachable = results.any { it.reachable }
        if (anyReachable) {
            _nodeConnectionsBlocked.value = false
        } else if (results.size >= 3 && consecutiveDeadCycles >= 1) {
            // A meaningful candidate set (seeds + persisted + DNS answers) came back 100% dead
            // for the second-plus consecutive cycle: the gRPC port is almost certainly blocked
            // on this network (or the uplink is gone). Surfaced on the connection screen; any
            // single success or a network-path change clears it instantly.
            _nodeConnectionsBlocked.value = true
        }
        return anyReachable
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
