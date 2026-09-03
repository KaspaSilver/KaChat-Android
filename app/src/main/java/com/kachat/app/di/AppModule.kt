package com.kachat.app.di

import android.content.Context
import android.util.Log
import com.kachat.app.BuildConfig
import com.kachat.app.util.ApiLogging
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.services.database.KaChatDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.kachat.app.services.ChangeNowApi
import com.kachat.app.services.CoinGeckoApi
import com.kachat.app.services.KaspaRestApi
import com.kachat.app.services.KasiaIndexerApi
import com.kachat.app.services.KnsApi
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

// DataStore extension — creates a single instance per app
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kachat_settings")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * DataStore for app settings (network endpoints, preferences).
     * Replaces UserDefaults from iOS.
     */
    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.dataStore
    }

    /**
     * App settings repository — wraps DataStore with typed accessors.
     */
    @Provides
    @Singleton
    fun provideAppSettingsRepository(dataStore: DataStore<Preferences>): AppSettingsRepository {
        return AppSettingsRepository(dataStore)
    }

    /**
     * Room database — local message and contact storage.
     * Equivalent to Core Data in the iOS app.
     */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KaChatDatabase {
        return Room.databaseBuilder(
            context,
            KaChatDatabase::class.java,
            "kachat.db"
        )
            .addMigrations(KaChatDatabase.MIGRATION_15_16, KaChatDatabase.MIGRATION_16_17, KaChatDatabase.MIGRATION_17_18, KaChatDatabase.MIGRATION_18_19, KaChatDatabase.MIGRATION_19_20, KaChatDatabase.MIGRATION_20_21, KaChatDatabase.MIGRATION_21_22, KaChatDatabase.MIGRATION_22_23, KaChatDatabase.MIGRATION_23_24, KaChatDatabase.MIGRATION_24_25, KaChatDatabase.MIGRATION_25_26, KaChatDatabase.MIGRATION_26_27, KaChatDatabase.MIGRATION_27_28, KaChatDatabase.MIGRATION_28_29, KaChatDatabase.MIGRATION_29_30, KaChatDatabase.MIGRATION_30_31, KaChatDatabase.MIGRATION_31_32, KaChatDatabase.MIGRATION_32_33, KaChatDatabase.MIGRATION_33_34, KaChatDatabase.MIGRATION_34_35, KaChatDatabase.MIGRATION_35_36, KaChatDatabase.MIGRATION_36_37)
            // Safety net only, for version jumps that don't have an explicit Migration above —
            // every future schema change should get a real Migration instead of relying on this,
            // since it silently wipes every user's local contacts/messages.
            .fallbackToDestructiveMigration()
            .build()
    }

    /**
     * OkHttp client for REST API calls (Kaspa REST, Kasia Indexer, KNS API).
     * Phase 3 will add auth interceptors and node-failover logic.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        // Per-request success logging is deliberately absent: the chat sync loop fires a request
        // roughly every 2 seconds, so success lines are pure logcat noise. Two interceptors below:
        //
        // 1. An always-on line for failures (exceptions, non-2xx) and slow requests (> 2s, tagged
        //    SLOW) — the only per-request logging in normal operation, matching iOS.
        // 2. HttpLoggingInterceptor, gated behind the persisted "Verbose API Logging" toggle
        //    (Settings > Connection Settings > Diagnostics, default OFF — see ApiLogging).
        //    Request/response bodies include encrypted message payloads and signed transaction
        //    data — BODY level is only useful for local debugging and must never ship in a
        //    release build (logcat is readable by anything with log access on a rooted device,
        //    or captured in bug reports), so release verbose logging caps at BASIC (one line per
        //    request/response, no bodies).
        val verboseLogger = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val startNs = System.nanoTime()
                val response = try {
                    chain.proceed(request)
                } catch (e: Exception) {
                    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
                    Log.w(API_LOG_TAG, "FAIL ${request.method} ${request.url} after ${elapsedMs}ms: $e")
                    throw e
                }
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
                if (!response.isSuccessful) {
                    Log.w(API_LOG_TAG, "HTTP ${response.code} ${request.method} ${request.url} in ${elapsedMs}ms")
                } else if (elapsedMs > SLOW_REQUEST_THRESHOLD_MS) {
                    Log.w(API_LOG_TAG, "SLOW ${request.method} ${request.url} took ${elapsedMs}ms")
                }
                response
            }
            .addInterceptor { chain ->
                if (ApiLogging.verbose) verboseLogger.intercept(chain) else chain.proceed(chain.request())
            }
            .build()
    }

    private const val API_LOG_TAG = "ApiLog"

    /** Anything slower than this on a healthy endpoint is worth a line even on success. */
    private const val SLOW_REQUEST_THRESHOLD_MS = 2_000L

    @Provides
    @Singleton
    fun provideKaspaRestApi(okHttpClient: OkHttpClient): KaspaRestApi {
        return Retrofit.Builder()
            .baseUrl("https://api.kaspa.org/") // Default, will be updated by a wrapper
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(KaspaRestApi::class.java)
    }

    @Provides
    @Singleton
    fun provideGiftApi(okHttpClient: OkHttpClient): com.kachat.app.services.GiftApi {
        return Retrofit.Builder()
            .baseUrl("https://kachatgift.duckdns.org/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(com.kachat.app.services.GiftApi::class.java)
    }

    @Provides
    @Singleton
    fun provideKasiaIndexerApi(okHttpClient: OkHttpClient): KasiaIndexerApi {
        return Retrofit.Builder()
            .baseUrl("https://api.kasia.io/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(KasiaIndexerApi::class.java)
    }

    @Provides
    @Singleton
    fun provideKnsApi(okHttpClient: OkHttpClient): KnsApi {
        return Retrofit.Builder()
            .baseUrl("https://api.kns.kaspa.org/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(KnsApi::class.java)
    }

    @Provides
    @Singleton
    fun provideCoinGeckoApi(okHttpClient: OkHttpClient): CoinGeckoApi {
        return Retrofit.Builder()
            .baseUrl("https://api.coingecko.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(CoinGeckoApi::class.java)
    }

    @Provides
    @Singleton
    fun provideChangeNowApi(okHttpClient: OkHttpClient): ChangeNowApi {
        // The API key is per-request-header auth, not a query param, so it's added here at the
        // client level (a derived client, so ChangeNOW calls still share the base timeouts/logging
        // configured on the shared client) rather than threaded through every ChangeNowApi method.
        val authedClient = okHttpClient.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("x-changenow-api-key", BuildConfig.CHANGENOW_API_KEY)
                    .build()
                chain.proceed(request)
            }
            .build()
        return Retrofit.Builder()
            .baseUrl("https://api.changenow.io/")
            .client(authedClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ChangeNowApi::class.java)
    }
}
