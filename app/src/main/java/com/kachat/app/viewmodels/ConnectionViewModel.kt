package com.kachat.app.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kachat.app.repository.AppSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

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
internal fun deriveConnectionStatus(activeNodes: List<NodeInfo>): ConnectionStatus {
    val bestLatencyMs = activeNodes.firstOrNull()?.latency?.removeSuffix("ms")?.toIntOrNull()
    return when {
        activeNodes.isEmpty() || bestLatencyMs == null -> ConnectionStatus.DISCONNECTED
        bestLatencyMs < 300 -> ConnectionStatus.CONNECTED
        else -> ConnectionStatus.DEGRADED
    }
}

/** Connection dot color — delegates to [deriveConnectionStatus] so it can never diverge from the status text. */
internal fun deriveDotColorHex(activeNodes: List<NodeInfo>): Long = when (deriveConnectionStatus(activeNodes)) {
    ConnectionStatus.CONNECTED -> 0xFF4CD964
    ConnectionStatus.DEGRADED -> 0xFFF39C12
    ConnectionStatus.DISCONNECTED -> 0xFFFF3B30
}

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

    /** Real status derived from the live node pool — no more hardcoded CONNECTED. */
    val status: StateFlow<ConnectionStatus> = activeNodes
        .map(::deriveConnectionStatus)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionStatus.DISCONNECTED)

    /** Connection dot color: green under 300ms, orange at 300ms+, red when disconnected. */
    val dotColorHex: StateFlow<Long> = activeNodes
        .map(::deriveDotColorHex)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0xFFFF3B30)

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
    val broadcastIndexerUrl: StateFlow<String> = settings.broadcastIndexerUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")
    val trustedNodeAddress: StateFlow<String> = settings.trustedNodeAddress.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")
    val savedNodeAddresses: StateFlow<List<com.kachat.app.models.SavedNodeAddress>> =
        settings.savedNodeAddresses.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    private val _discoverNewPeers = MutableStateFlow(true)
    val discoverNewPeers: StateFlow<Boolean> = _discoverNewPeers

    fun setNetwork(value: String) { viewModelScope.launch { settings.setNetwork(value) } }
    fun setIndexerUrl(value: String) { viewModelScope.launch { settings.setIndexerUrl(value) } }
    fun setKnsApiUrl(value: String) { viewModelScope.launch { settings.setKnsApiUrl(value) } }
    fun setKaspaRestApiUrl(value: String) { viewModelScope.launch { settings.setKaspaRestUrl(value) } }
    fun setTrustedNodeAddress(value: String) { viewModelScope.launch { settings.setTrustedNodeAddress(value) } }
    fun setDiscoverNewPeers(value: Boolean) { _discoverNewPeers.value = value }
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
