package com.kachat.app.services

import android.util.Log
import com.kachat.app.repository.AppSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkService @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val settings: AppSettingsRepository,
    private val okHttpClient: OkHttpClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _kaspaRestApi = MutableStateFlow<KaspaRestApi?>(null)
    val kaspaRestApi: StateFlow<KaspaRestApi?> = _kaspaRestApi

    private val _indexerApi = MutableStateFlow<KasiaIndexerApi?>(null)
    val indexerApi: StateFlow<KasiaIndexerApi?> = _indexerApi

    private val _knsApi = MutableStateFlow<KnsApi?>(null)
    val knsApi: StateFlow<KnsApi?> = _knsApi

    // Push registration lives on the same host as the indexer (indexer.kachat.app).
    private val _pushApi = MutableStateFlow<PushApi?>(null)
    val pushApi: StateFlow<PushApi?> = _pushApi

    // Declared before init: the settings observers build the clients with it right away.
    private val restGson = com.google.gson.GsonBuilder()
        .registerTypeAdapterFactory(com.kachat.app.util.UtxoMath.RestUtxoGuard)
        .create()

    /** Every answer from the indexer counts toward or against an outage ([IndexerHealth]). */
    private val indexerClient: OkHttpClient by lazy {
        okHttpClient.newBuilder().addInterceptor(IndexerHealthInterceptor(::networkIsOnline)).build()
    }

    /** An address's history on the Kaspa REST API, for what is read from the chain while the
     *  indexer is down (iOS 4f8e14bb). */
    val chainHistory = ChainHistoryReader { _kaspaRestApi.value }

    init {
        IndexerHealth.init(context)
        observeSettings()
    }

    /** A weak signal looks like a dead server: a phone with no connection says nothing about it. */
    private fun networkIsOnline(): Boolean = try {
        val connectivity = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        connectivity == null || connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) {
        true
    }

    private fun observeSettings() {
        scope.launch {
            settings.kaspaRestUrl.collectLatest { url ->
                createApi<KaspaRestApi>(url)?.let { _kaspaRestApi.value = it }
            }
        }
        scope.launch {
            settings.indexerUrl.collectLatest { url ->
                // Blank = no server (testnet has no KaChat indexers yet) - never keep the
                // other network's client.
                // A different indexer in Settings (or none) ends what was known about the old one.
                IndexerHealth.indexerChanged(url)
                if (url.isBlank()) {
                    _indexerApi.value = null
                } else {
                    createApi<KasiaIndexerApi>(url, indexerClient)?.let {
                        _indexerApi.value = ResilientIndexerApi(it, chainHistory, scope)
                    }
                }
            }
        }
        scope.launch {
            settings.knsApiUrl.collectLatest { url ->
                createApi<KnsApi>(url)?.let { _knsApi.value = it }
            }
        }
        scope.launch {
            // Push registration has its own configurable host (mirrors iOS's pushIndexerURL),
            // defaulting to the same indexer.kachat.app.
            settings.pushIndexerUrl.collectLatest { url ->
                // Blank = no server (testnet has no KaChat indexers yet) - never keep the
                // other network's client.
                if (url.isBlank()) _pushApi.value = null else createApi<PushApi>(url)?.let { _pushApi.value = it }
            }
        }
    }

    /**
     * A malformed URL (e.g. mistyped in Connection Settings) must not crash the app —
     * this used to throw straight out of a background coroutine with no catch anywhere
     * above it. On invalid input, log and keep whatever API client was already active.
     */
    private inline fun <reified T> createApi(baseUrl: String, client: OkHttpClient = okHttpClient): T? {
        return try {
            val sanitizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            Retrofit.Builder()
                .baseUrl(sanitizedUrl)
                .client(client)
                // UTXO answers above the Kaspa supply are refused as they're decoded (iOS 283cd28, IOS-020)
                .addConverterFactory(GsonConverterFactory.create(restGson))
                .build()
                .create(T::class.java)
        } catch (e: Exception) {
            Log.w("NetworkService", "Invalid base URL, keeping previous API client: $baseUrl", e)
            null
        }
    }
}
