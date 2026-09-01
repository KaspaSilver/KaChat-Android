package com.kachat.app.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kachat.app.repository.AppSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/** How long the pool must stay empty before the dot is allowed to turn red. */
private const val DISCONNECT_GRACE_MS = 8_000L

enum class ConnectionStatus {
    CONNECTED, DEGRADED, DISCONNECTED
}

/**
 * Pure threshold logic, extracted so it's directly unit-testable without needing
 * a real NodePoolManager/AppSettingsRepository — see ConnectionViewModel.status.
 * Same latency threshold as [deriveDotColorHex] (which derives from this) so the
 * "Status"/"Pool Health" text on screen can never say something that contradicts
 * the dot's color: red whenever there's no active node at all (regardless of
 * latency), otherwise green under 300ms and orange at 300ms or above.
 */
internal fun deriveConnectionStatus(
    activeNodes: List<NodeInfo>,
    /** Red is reserved for SUSTAINED disconnection: an empty pool during startup probing,
     *  failover, or a WiFi-cellular handoff reads DEGRADED (orange) until the emptiness has
     *  persisted past the grace window. Defaults true so direct calls keep the strict
     *  "empty means disconnected" semantics the unit tests pin. */
    disconnectedGraceElapsed: Boolean = true,
): ConnectionStatus {
    val bestLatencyMs = activeNodes.firstOrNull()?.latency?.removeSuffix("ms")?.toIntOrNull()
    return when {
        activeNodes.isEmpty() || bestLatencyMs == null ->
            if (disconnectedGraceElapsed) ConnectionStatus.DISCONNECTED else ConnectionStatus.DEGRADED
        bestLatencyMs < 300 -> ConnectionStatus.CONNECTED
        else -> ConnectionStatus.DEGRADED
    }
}

internal fun dotColorHexFor(status: ConnectionStatus): Long = when (status) {
    ConnectionStatus.CONNECTED -> 0xFF4CD964
    ConnectionStatus.DEGRADED -> 0xFFF39C12
    ConnectionStatus.DISCONNECTED -> 0xFFFF3B30
}

/** Connection dot color — delegates to [deriveConnectionStatus] so it can never diverge from the status text. */
internal fun deriveDotColorHex(activeNodes: List<NodeInfo>, disconnectedGraceElapsed: Boolean = true): Long =
    dotColorHexFor(deriveConnectionStatus(activeNodes, disconnectedGraceElapsed))

data class NodeInfo(
    val ip: String,
    val type: String, // Seed, Manual
    val latency: String,
    val daaScore: String,
    val status: String, // Active, Quarantined, Suspect, Candidate
    val color: Long // Hex color for status
)

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val settings: AppSettingsRepository,
    private val nodePoolManager: com.kachat.app.services.NodePoolManager
) : ViewModel() {

    val activeNodes: StateFlow<List<NodeInfo>> = nodePoolManager.activeNodes
    val allNodes: StateFlow<List<NodeInfo>> = nodePoolManager.allNodes

    /** True when probing has concluded no Kaspa gRPC node is reachable at all on this network
     *  (port blocked, poisoned DNS with dead seeds, or no uplink) — drives the honest
     *  explanation on the connection status screen. See NodePoolManager.nodeConnectionsBlocked. */
    val nodeConnectionsBlocked: StateFlow<Boolean> = nodePoolManager.nodeConnectionsBlocked

    /** Wall-clock ms when the active-node list last became empty; null while any node is
     *  active. Tracked by an always-live collector, NOT inside the WhileSubscribed pipelines,
     *  so leaving and re-entering a screen can never reset the disconnection stopwatch. */
    private val emptySinceMs = MutableStateFlow<Long?>(
        if (nodePoolManager.activeNodes.value.isEmpty()) System.currentTimeMillis() else null
    )

    init {
        viewModelScope.launch {
            nodePoolManager.activeNodes.collect { nodes ->
                emptySinceMs.value =
                    if (nodes.isEmpty()) (emptySinceMs.value ?: System.currentTimeMillis()) else null
            }
        }
    }

    /** Emits the graced status: orange immediately when the pool empties (startup probing,
     *  failover, network handoff), red only once the emptiness has persisted for the grace
     *  window - the dot must never show red unless the user is truly disconnected. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun statusFlow(): Flow<ConnectionStatus> =
        combine(activeNodes, emptySinceMs) { nodes, since -> nodes to since }
            .transformLatest { (nodes, since) ->
                val elapsedMs = since?.let { System.currentTimeMillis() - it }
                val graceElapsed = elapsedMs != null && elapsedMs >= DISCONNECT_GRACE_MS
                emit(deriveConnectionStatus(nodes, graceElapsed))
                if (since != null && !graceElapsed) {
                    delay(DISCONNECT_GRACE_MS - (elapsedMs ?: 0L))
                    emit(deriveConnectionStatus(nodes, disconnectedGraceElapsed = true))
                }
            }

    private fun initialGracedStatus(): ConnectionStatus {
        val since = emptySinceMs.value
        val graceElapsed = since != null && System.currentTimeMillis() - since >= DISCONNECT_GRACE_MS
        return deriveConnectionStatus(nodePoolManager.activeNodes.value, graceElapsed)
    }

    /** Real status derived from the live node pool — no more hardcoded CONNECTED. */
    val status: StateFlow<ConnectionStatus> = statusFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initialGracedStatus())

    /** Connection dot color: green under 300ms, orange at 300ms+, red only after sustained disconnection. */
    val dotColorHex: StateFlow<Long> = statusFlow()
        .map(::dotColorHexFor)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), dotColorHexFor(initialGracedStatus()))

    /** Real "Xs/Xm/Xh ago" string, ticking every second, sourced from the pool's most recent successful probe. */
    val lastSyncAt: StateFlow<String> = flow {
        while (true) {
            val ts = nodePoolManager.lastSuccessAt()
            emit(
                if (ts == null) {
                    "Never"
                } else {
                    val secondsAgo = (System.currentTimeMillis() - ts) / 1000
                    when {
                        secondsAgo < 60 -> "${secondsAgo}s ago"
                        secondsAgo < 3600 -> "${secondsAgo / 60}m ago"
                        else -> "${secondsAgo / 3600}h ago"
                    }
                }
            )
            delay(1000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Never")

    val network: StateFlow<String> = settings.network.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "Mainnet")
    val indexerUrl: StateFlow<String> = settings.indexerUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")
    val knsApiUrl: StateFlow<String> = settings.knsApiUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")
    val kaspaRestApiUrl: StateFlow<String> = settings.kaspaRestUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")
    val kapostIndexerUrl: StateFlow<String> = settings.kapostIndexerUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")
    val translationServiceUrl: StateFlow<String> = settings.translationServiceUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")
    val broadcastIndexerUrl: StateFlow<String> = settings.broadcastIndexerUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")
    val pushIndexerUrl: StateFlow<String> = settings.pushIndexerUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")
    val trustedNodeAddress: StateFlow<String> = settings.trustedNodeAddress.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")
    /**
     * True when node selection is AUTOMATIC (no pinned node, the pool discovers and picks),
     * false when pinned to a specific node (the shipped default or the user's own) - see
     * [AppSettingsRepository.trustedNodeAddress]: blank means discovery, non-blank means pinned.
     * Null until the stored value has actually been read, so the connection status screen can
     * keep pool-only sections hidden for that first frame instead of flashing them at a pinned
     * user (or vice versa) while DataStore loads.
     */
    val nodeSelectionIsAutomatic: StateFlow<Boolean?> = settings.trustedNodeAddress
        .map { it.trim().isBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val savedNodeAddresses: StateFlow<List<com.kachat.app.models.SavedNodeAddress>> =
        settings.savedNodeAddresses.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    /** Opt-in per-request HTTP logging (Diagnostics section), default OFF. */
    val verboseApiLogging: StateFlow<Boolean> =
        settings.verboseApiLogging.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _discoverNewPeers = MutableStateFlow(true)
    val discoverNewPeers: StateFlow<Boolean> = _discoverNewPeers

    fun setNetwork(value: String) { viewModelScope.launch { settings.setNetwork(value) } }
    fun setIndexerUrl(value: String) { viewModelScope.launch { settings.setIndexerUrl(value) } }
    fun setKaspaRestApiUrl(value: String) { viewModelScope.launch { settings.setKaspaRestUrl(value) } }

    /**
     * Blank resets to the default rather than being written through: an empty base URL builds a
     * request that fails and reads as the server being down.
     */
    fun setTranslationServiceUrl(value: String) {
        viewModelScope.launch {
            val trimmed = value.trim().trimEnd('/')
            settings.setTranslationServiceUrl(
                trimmed.ifBlank { AppSettingsRepository.DEFAULT_TRANSLATION_SERVICE_URL }
            )
        }
    }
    fun setTrustedNodeAddress(value: String) { viewModelScope.launch { settings.setTrustedNodeAddress(value) } }
    fun setDiscoverNewPeers(value: Boolean) { _discoverNewPeers.value = value }
    fun setVerboseApiLogging(value: Boolean) { viewModelScope.launch { settings.setVerboseApiLogging(value) } }
    fun addSavedNodeAddress(label: String, address: String) {
        viewModelScope.launch {
            settings.addSavedNodeAddress(com.kachat.app.models.SavedNodeAddress(label = label, address = address))
        }
    }
    fun removeSavedNodeAddress(id: String) { viewModelScope.launch { settings.removeSavedNodeAddress(id) } }

    fun refreshPool() { nodePoolManager.refreshNow() }
    fun clearPool() { nodePoolManager.clearPool() }
    fun reconnect() { nodePoolManager.reconnect() }
    fun addManualEndpoint(address: String) { nodePoolManager.addManualEndpoint(address) }
}
