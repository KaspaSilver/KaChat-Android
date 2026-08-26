package com.kachat.app.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One shared answer to "is the device on a metered (cellular / mobile-hotspot) network right
 * now?" — the switch every cellular-data-reduction behavior in the app keys off: slower poll
 * cadences (ChatRepository), no block-added streaming (GroupScanningService, and
 * BroadcastScanningService except for channels with no other delivery path), relaxed cloud-sync
 * debounces (NextcloudSyncService / GoogleDriveSyncService), and a calmer node pool
 * (NodePoolManager). WiFi behavior everywhere stays exactly as before.
 *
 * Backed by [ConnectivityManager.isActiveNetworkMetered] with a default-network callback keeping
 * [isMeteredFlow] live, so reactive consumers re-evaluate on WiFi <-> cellular handoffs without
 * polling. If the callback can't register (OEM quirk / missing permission), the flow simply
 * keeps the value read at startup and [isMetered] refreshes it on each read — degraded but safe.
 */
@Singleton
class MeteredNetwork @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val metered = MutableStateFlow(readIsMetered())

    /** Reactive form — drives lifecycle-scoped gates that must react to WiFi <-> cellular handoffs. */
    val isMeteredFlow: StateFlow<Boolean> = metered.asStateFlow()

    /** Snapshot form for one-shot reads (poll-loop tick cadence, debounce tier picks). Re-reads
     *  the system value so it stays correct even when the callback below failed to register. */
    val isMetered: Boolean
        get() = readIsMetered().also { metered.value = it }

    init {
        try {
            connectivityManager?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    metered.value = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                }

                override fun onAvailable(network: Network) {
                    metered.value = readIsMetered()
                }

                override fun onLost(network: Network) {
                    metered.value = readIsMetered()
                }
            })
        } catch (e: Exception) {
            Log.w("MeteredNetwork", "Could not register network callback: ${e.message}")
        }
    }

    /** No ConnectivityManager (shouldn't happen on a real device) counts as unmetered — the
     *  data-saving behaviors are an optimization, so the safe default is normal behavior. */
    private fun readIsMetered(): Boolean = connectivityManager?.isActiveNetworkMetered ?: false
}
