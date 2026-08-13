package com.kachat.app.services

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.repository.BroadcastRepository
import com.kachat.app.repository.ChatRepository
import com.kachat.app.util.KaspaMessageSigner
import com.kachat.app.util.Schnorr
import com.kachat.app.util.Secp256k1
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.math.BigInteger
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers this device's FCM token with the KaChat indexer's `/v1/push` API so the server can
 * deliver native push notifications (DMs, KaPosts pings, broadcast channels) while the app is
 * backgrounded or killed.
 *
 * The registration is authenticated exactly like the iOS client: a BIP-340 Schnorr signature over
 * a canonical, newline-joined preimage. We emit the **LegacyV1** auth shape (no `watched_group_ids`
 * field), which the Rust server (`kasia-indexer`) matches in `build_auth_preimage`. Group-message
 * push is out of scope for this version (it needs the TransitionalGroups shape on both sides).
 *
 * Idempotent and safe to call repeatedly — the server upserts by device token.
 */
@Singleton
class PushRegistrationManager @Inject constructor(
    private val walletManager: WalletManager,
    private val networkService: NetworkService,
    private val chatRepository: ChatRepository,
    private val broadcastRepository: BroadcastRepository,
    private val settings: AppSettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Serialize registrations so an account switch + a token refresh can't race.
    private val mutex = Mutex()

    /**
     * Fire-and-forget (re)registration. Call on wallet unlock, account switch, app foreground, or
     * when the watched set changes. No-ops when there's no wallet, notifications are off, or FCM
     * is unavailable (e.g. no google-services.json / no Play services).
     */
    fun registerAsync() {
        scope.launch {
            runCatching { register(null) }
                .onFailure { Log.w(TAG, "push registration failed: ${it.message}") }
        }
    }

    /** Called from [KaChatFirebaseMessagingService.onNewToken] when FCM rotates the token. */
    fun onTokenRefreshed(token: String) {
        scope.launch {
            runCatching { register(token) }
                .onFailure { Log.w(TAG, "push re-registration failed: ${it.message}") }
        }
    }

    private suspend fun register(tokenOverride: String?) = mutex.withLock {
        if (!walletManager.hasWallet()) return@withLock
        if (!settings.notificationsEnabled.first()) return@withLock

        val api = networkService.pushApi.first { it != null } ?: return@withLock
        val token = (tokenOverride ?: FirebaseMessaging.getInstance().token.await()).trim()
        if (token.isEmpty()) return@withLock

        val privateKey = walletManager.getPrivateKeyBytes()
        val walletPubkey = Schnorr.publicKeyXOnly(privateKey).toHex()
        // Kaspa addresses are canonical lowercase bech32, so this already matches the server's
        // RpcAddress round-trip (normalize_wallet_address / derive_wallet_address).
        val walletAddress = walletManager.getAddress().trim()
        val kaPostsPubkey = compressedPubkeyHex(privateKey)

        val watchedAddresses = chatRepository.getContacts().first()
            .filter { it.conversationStatus == "active" }
            .map { it.id }
        val broadcastChannels = broadcastRepository.getNotifyEnabledChannelNames().first().toList()

        val challenge = api.challenge()
        // The signed request's validity window must sit inside [issued_at_ms, expires_at_ms].
        val timestampMs = System.currentTimeMillis()
            .coerceIn(challenge.issuedAtMs, challenge.expiresAtMs)

        val preimage = buildAuthPreimage(
            method = "POST",
            path = "/v1/push/register",
            deviceToken = token,
            watchedAddresses = watchedAddresses,
            primaryAddress = walletAddress,
            aliases = emptyList(),
            walletPubkey = walletPubkey,
            walletAddress = walletAddress,
            nonce = challenge.nonce,
            timestampMs = timestampMs,
            expiresAtMs = challenge.expiresAtMs,
        )
        val signature = KaspaMessageSigner.sign(
            preimage,
            privateKey,
            KaspaMessageSigner.SigningMode.SHA256_DIGEST,
        )

        val request = PushRegistrationRequest(
            deviceToken = token,
            platform = "android",
            watchedAddresses = watchedAddresses,
            primaryAddress = walletAddress,
            aliases = emptyList(),
            watchedBroadcastChannels = broadcastChannels,
            kaPostsPubkey = kaPostsPubkey,
            auth = PushAuthRequest(
                authVersion = 1,
                walletPubkey = walletPubkey,
                walletAddress = walletAddress,
                nonce = challenge.nonce,
                timestampMs = timestampMs,
                expiresAtMs = challenge.expiresAtMs,
                signature = signature,
            ),
        )
        api.register(request)
        Log.i(TAG, "push registered (watched=${watchedAddresses.size}, channels=${broadcastChannels.size})")
    }

    /**
     * Byte-for-byte match of the server's `build_auth_preimage` for the LegacyV1 format:
     * newline-joined `key=value` lines, SHA-256-hex sub-hashes over canonicalized (trimmed,
     * deduped, sorted) sets. `watched_group_ids_hash`, `capabilities_hash`, and `auth_version`
     * lines are intentionally absent — those belong to the TransitionalGroups/V2 shapes.
     */
    private fun buildAuthPreimage(
        method: String,
        path: String,
        deviceToken: String,
        watchedAddresses: List<String>,
        primaryAddress: String,
        aliases: List<String>,
        walletPubkey: String,
        walletAddress: String,
        nonce: String,
        timestampMs: Long,
        expiresAtMs: Long,
    ): String = listOf(
        "domain=$AUTH_DOMAIN_V1",
        "nonce=${nonce.trim()}",
        "method=$method",
        "path=$path",
        // The server hashes the *normalized* device token; for FCM the normalized form is the
        // token verbatim (see push.rs normalize_device_token), so hash it as-is.
        "device_token_hash=${sha256Hex(deviceToken.trim())}",
        "watched_addresses_hash=${sha256Hex(canonicalizeAddresses(watchedAddresses).joinToString("\n"))}",
        "primary_address=$primaryAddress",
        "aliases_hash=${sha256Hex(canonicalizeAliases(aliases).joinToString("\n"))}",
        "wallet_pubkey=$walletPubkey",
        "wallet_address=$walletAddress",
        "timestamp_ms=$timestampMs",
        "expires_at_ms=$expiresAtMs",
    ).joinToString("\n")

    // Addresses: trim, drop empty, lowercase, dedupe, sort ascending (matches canonicalize_set).
    private fun canonicalizeAddresses(values: List<String>): List<String> =
        values.map { it.trim() }.filter { it.isNotEmpty() }.map { it.lowercase() }
            .toSet().sorted()

    // Aliases: trim, drop empty, case-preserved, dedupe, sort ascending.
    private fun canonicalizeAliases(values: List<String>): List<String> =
        values.map { it.trim() }.filter { it.isNotEmpty() }.toSet().sorted()

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHex()

    /** 66-hex compressed secp256k1 pubkey — the KaPosts "K" identity (same as KaPostsService). */
    private fun compressedPubkeyHex(privateKey: ByteArray): String {
        val priv = BigInteger(1, privateKey)
        return Secp256k1.G.multiply(priv).normalize().getEncoded(true).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "PushRegistration"
        private const val AUTH_DOMAIN_V1 = "kasia-push-auth:v1"
    }
}
