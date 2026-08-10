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

    private val _kapostApi = MutableStateFlow<KaPostApi?>(null)
    val kapostApi: StateFlow<KaPostApi?> = _kapostApi

    private val _broadcastIndexerApi = MutableStateFlow<BroadcastIndexerApi?>(null)
    val broadcastIndexerApi: StateFlow<BroadcastIndexerApi?> = _broadcastIndexerApi

    init {
        observeSettings()
    }

    private fun observeSettings() {
        scope.launch {
            settings.kaspaRestUrl.collectLatest { url ->
                createApi<KaspaRestApi>(url)?.let { _kaspaRestApi.value = it }
            }
        }
        scope.launch {
            settings.indexerUrl.collectLatest { url ->
                createApi<KasiaIndexerApi>(url)?.let { _indexerApi.value = it }
            }
        }
        scope.launch {
            settings.knsApiUrl.collectLatest { url ->
                createApi<KnsApi>(url)?.let { _knsApi.value = it }
            }
        }
        scope.launch {
            settings.kapostIndexerUrl.collectLatest { url ->
                createApi<KaPostApi>(url)?.let { _kapostApi.value = it }
            }
        }
        scope.launch {
            settings.broadcastIndexerUrl.collectLatest { url ->
                createApi<BroadcastIndexerApi>(url)?.let { _broadcastIndexerApi.value = it }
            }
        }
    }

    /**
     * A malformed URL (e.g. mistyped in Connection Settings) must not crash the app —
     * this used to throw straight out of a background coroutine with no catch anywhere
     * above it. On invalid input, log and keep whatever API client was already active.
     */
    private inline fun <reified T> createApi(baseUrl: String): T? {
        return try {
            val sanitizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            Retrofit.Builder()
                .baseUrl(sanitizedUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(T::class.java)
        } catch (e: Exception) {
            Log.w("NetworkService", "Invalid base URL, keeping previous API client: $baseUrl", e)
            null
        }
    }
}
