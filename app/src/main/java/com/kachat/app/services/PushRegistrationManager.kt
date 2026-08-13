package com.kachat.app.services

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.repository.BroadcastRepository
import com.kachat.app.repository.ChatRepository
import com.kachat.app.util.KaspaMessageSigner
import com.kachat.app.util.Schnorr
import com.kachat.app.util.Secp256k1
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import retrofit2.HttpException
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
 * Idempotent and safe to call repeatedly — the server upserts by device token, and an unchanged
 * registration snapshot (see [lastRegisteredFingerprint]) short-circuits before any network I/O.
 *
 * Beyond the explicit [registerAsync]/[onTokenRefreshed] entry points, the init block observes
 * everything the registration payload is built from — active account, active-contact set,
 * bell-enabled broadcast channels, hidden broadcast senders, and the notifications setting — and
 * re-registers (debounced 2s, so an edit burst is one round-trip) whenever any of it changes,
 * mirroring iOS's updateWatchedAddresses triggers (PUSH_NOTIFICATIONS.md) and the "bell toggles
 * re-send registration immediately" contract (PUSH_EXTENSIONS.md §1). The same observer
 * unregisters when notifications are switched off or the last account disappears.
 *
 * [PushState.setActive] is flipped true only after a registration round-trip succeeds while
 * system notifications are deliverable, and false on failure/unregister — the pollers consult it
 * to suppress their duplicate local banners for push-covered types (PUSH_EXTENSIONS.md §4).
 */
@OptIn(FlowPreview::class)
@Singleton
class PushRegistrationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val walletManager: WalletManager,
    private val networkService: NetworkService,
    private val chatRepository: ChatRepository,
    private val broadcastRepository: BroadcastRepository,
    private val settings: AppSettingsRepository,
    private val pushState: PushState,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Serialize registrations so an account switch + a token refresh can't race.
    private val mutex = Mutex()

    /**
     * SHA-256 of the last payload the server ACCEPTED (token + wallet + watched sets). A register
     * whose snapshot hashes identically is skipped entirely — this is what lets the startup
     * trigger from WalletViewModel and the init observer's initial emission coalesce into a
     * single network round-trip. Cleared on failure and on unregister so the next trigger, even
     * with unchanged inputs, genuinely re-registers (e.g. notifications toggled off then on).
     */
    @Volatile
    private var lastRegisteredFingerprint: String? = null

    /**
     * Everything the registration payload is derived from, observed as one combined flow.
     * [notificationsEnabled] rides along so flipping the setting re-triggers [onSnapshot]
     * (where it decides register vs unregister) — it is NOT part of the fingerprint.
     */
    private data class Snapshot(
        val activeAddress: String?,
        val notificationsEnabled: Boolean,
        val activeContacts: Set<String>,
        val notifyChannels: Set<String>,
        val hiddenSenderRows: Set<Pair<String, String>>, // (channelName, senderAddress)
    )

    init {
        scope.launch {
            combine(
                walletManager.activeAddressFlow,
                settings.notificationsEnabled,
                chatRepository.getContacts().map { contacts ->
                    contacts.filter { it.conversationStatus == "active" }.map { it.id }.toSet()
                },
                broadcastRepository.getNotifyEnabledChannelNames(),
                broadcastRepository.getHiddenSenders().map { rows ->
                    rows.map { it.channelName to it.senderAddress }.toSet()
                },
            ) { address, notify, contacts, channels, hidden ->
                Snapshot(address, notify, contacts, channels, hidden)
            }
                .distinctUntilChanged()
                // Coalesce bursts (accepting a handshake touches contacts repeatedly, hiding a
                // few spammers in a row, etc.) into one challenge/register round-trip.
                .debounce(2_000)
                .collect { snapshot ->
                    runCatching { onSnapshot(snapshot) }
                        .onFailure { Log.w(TAG, "push (re)registration failed: ${it.message}") }
                }
        }
    }

    private suspend fun onSnapshot(snapshot: Snapshot) {
        when {
            // Last account deleted/wiped: the signing key is already gone, so the best we can do
            // is an unsigned unregister (the same fallback iOS uses when auth can't be built).
            // The signed path for a deliberate deletion is WalletViewModel calling
            // [unregisterAsync] BEFORE the wallet is removed.
            snapshot.activeAddress == null -> unregister(signed = false)
            // Notifications switched off in Settings: mirror iOS's disablePushNotifications(),
            // which unregisters the device outright rather than letting the server keep pushing.
            !snapshot.notificationsEnabled -> unregister(signed = true)
            else -> register(null)
        }
    }

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

    /**
     * Fire-and-forget unregister that snapshots the signing material SYNCHRONOUSLY, so callers
     * about to destroy the wallet (account deletion, logout) can invoke it first and the
     * challenge/sign round-trip still has the key even though the wallet is gone by the time the
     * coroutine runs — the same ordering iOS uses (unregister BEFORE clearing the wallet).
     */
    fun unregisterAsync() {
        val material = try { signingMaterial() } catch (_: Exception) { null }
        scope.launch {
            runCatching { unregisterInternal(material) }
                .onFailure { Log.w(TAG, "push unregister failed: ${it.message}") }
        }
    }

    private suspend fun unregister(signed: Boolean) {
        val material = if (signed) {
            try { signingMaterial() } catch (_: Exception) { null }
        } else null
        unregisterInternal(material)
    }

    private data class SigningMaterial(val privateKey: ByteArray, val walletAddress: String)

    private fun signingMaterial(): SigningMaterial =
        SigningMaterial(walletManager.getPrivateKeyBytes(), walletManager.getAddress().trim())

    private suspend fun unregisterInternal(material: SigningMaterial?) = mutex.withLock {
        // Whatever happens below, this device is no longer in remote-push mode: the pollers must
        // resume posting notifications, and the next register must not be fingerprint-skipped.
        pushState.setActive(false)
        lastRegisteredFingerprint = null

        val api = networkService.pushApi.first { it != null } ?: return@withLock
        val token = try { FirebaseMessaging.getInstance().token.await().trim() } catch (_: Exception) { return@withLock }
        if (token.isEmpty()) return@withLock

        val auth = material?.let {
            runCatching { buildAuth(it, "DELETE", "/v1/push/unregister", token, watchedAddresses = emptyList(), primaryAddress = "") }
                // Best-effort unsigned fallback, same as iOS's unregister when auth can't be built.
                .getOrNull()
        }
        try {
            api.unregister(PushUnregisterRequest(deviceToken = token, auth = auth))
            Log.i(TAG, "push unregistered")
        } catch (e: HttpException) {
            // A signed attempt the server rejects (e.g. token bound to a wallet we no longer
            // hold the key for) still deserves the unsigned best-effort try before giving up.
            if (auth != null) {
                runCatching { api.unregister(PushUnregisterRequest(deviceToken = token, auth = null)) }
                    .onSuccess { Log.i(TAG, "push unregistered (unsigned fallback)") }
                    .onFailure { Log.w(TAG, "push unregister rejected: ${e.code()}") }
            } else {
                Log.w(TAG, "push unregister rejected: ${e.code()}")
            }
        }
    }

    private suspend fun register(tokenOverride: String?) = mutex.withLock {
        if (!walletManager.hasWallet()) return@withLock
        if (!settings.notificationsEnabled.first()) return@withLock

        val api = networkService.pushApi.first { it != null } ?: return@withLock
        val token = (tokenOverride ?: FirebaseMessaging.getInstance().token.await()).trim()
        if (token.isEmpty()) return@withLock

        val privateKey = walletManager.getPrivateKeyBytes()
        // Kaspa addresses are canonical lowercase bech32, so this already matches the server's
        // RpcAddress round-trip (normalize_wallet_address / derive_wallet_address).
        val walletAddress = walletManager.getAddress().trim()
        val kaPostsPubkey = compressedPubkeyHex(privateKey)

        // Own address included alongside active contacts, matching iOS's collectWatchedAddresses
        // — the server routes by SENDER (find_devices_watching), so without it a handshake from a
        // not-yet-known sender could never be pushed.
        val watchedAddresses = (chatRepository.getContacts().first()
            .filter { it.conversationStatus == "active" }
            .map { it.id } + walletAddress).distinct()
        val broadcastChannels = broadcastRepository.getNotifyEnabledChannelNames().first().toList()
        val hiddenSenders = collectHiddenBroadcastSenders(broadcastChannels)

        // Skip the network round-trip entirely when the server-accepted snapshot is unchanged —
        // this is what collapses the startup trigger + init-observer + foreground triggers into
        // one actual registration.
        val fingerprint = registrationFingerprint(
            token, walletAddress, watchedAddresses, broadcastChannels, hiddenSenders, kaPostsPubkey
        )
        if (fingerprint == lastRegisteredFingerprint) return@withLock

        try {
            submitRegistration(
                api, token, privateKey, walletAddress, kaPostsPubkey,
                watchedAddresses, broadcastChannels, hiddenSenders,
            )
        } catch (e: HttpException) {
            // Wallet-binding conflict: this token is still bound to a previously active wallet
            // (server keys registrations by token but binds them to a wallet). Mirror iOS's
            // recovery: unregister the stale binding, then retry the registration ONCE with a
            // fresh challenge.
            if (!isWalletBindingConflict(e)) throw e
            Log.i(TAG, "push register hit wallet-binding conflict, attempting recovery unregister")
            unregisterForRecovery(api, token, privateKey, walletAddress)
            submitRegistration(
                api, token, privateKey, walletAddress, kaPostsPubkey,
                watchedAddresses, broadcastChannels, hiddenSenders,
            )
        }

        lastRegisteredFingerprint = fingerprint
        // Remote-push mode is only real if the system will actually show what FCM delivers;
        // with POST_NOTIFICATIONS denied the pollers' (equally invisible) banners are moot
        // anyway, but keeping the flag honest costs nothing.
        pushState.setActive(NotificationManagerCompat.from(context).areNotificationsEnabled())
        Log.i(
            TAG,
            "push registered (watched=${watchedAddresses.size}, channels=${broadcastChannels.size}, " +
                "hiddenRooms=${hiddenSenders.size}, active=${pushState.isActive})"
        )
    }

    private suspend fun submitRegistration(
        api: PushApi,
        token: String,
        privateKey: ByteArray,
        walletAddress: String,
        kaPostsPubkey: String,
        watchedAddresses: List<String>,
        broadcastChannels: List<String>,
        hiddenSenders: Map<String, List<String>>,
    ) {
        try {
            val auth = buildAuth(
                SigningMaterial(privateKey, walletAddress),
                method = "POST",
                path = "/v1/push/register",
                deviceToken = token,
                watchedAddresses = watchedAddresses,
                primaryAddress = walletAddress,
            )
            api.register(
                PushRegistrationRequest(
                    deviceToken = token,
                    platform = "android",
                    watchedAddresses = watchedAddresses,
                    primaryAddress = walletAddress,
                    aliases = emptyList(),
                    watchedBroadcastChannels = broadcastChannels,
                    hiddenBroadcastSenders = hiddenSenders,
                    kaPostsPubkey = kaPostsPubkey,
                    auth = auth,
                )
            )
        } catch (e: Exception) {
            // Any failed attempt drops us out of remote-push mode so the pollers keep notifying,
            // and forgets the fingerprint so the next trigger retries for real.
            pushState.setActive(false)
            lastRegisteredFingerprint = null
            throw e
        }
    }

    /** Server contract (iOS parity): 401 + "bound to another wallet" in the error body. */
    private fun isWalletBindingConflict(e: HttpException): Boolean {
        if (e.code() != 401) return false
        val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull() ?: return false
        return body.lowercase().contains("bound to another wallet")
    }

    /** Signed unregister (unsigned fallback) used only inside the binding-conflict recovery path — deliberately does NOT touch pushState/fingerprint like [unregisterInternal] does, since the caller immediately re-registers. */
    private suspend fun unregisterForRecovery(
        api: PushApi,
        token: String,
        privateKey: ByteArray,
        walletAddress: String,
    ) {
        val auth = runCatching {
            buildAuth(
                SigningMaterial(privateKey, walletAddress),
                "DELETE", "/v1/push/unregister", token,
                watchedAddresses = emptyList(), primaryAddress = "",
            )
        }.getOrNull()
        try {
            api.unregister(PushUnregisterRequest(deviceToken = token, auth = auth))
        } catch (_: HttpException) {
            // The binding belongs to a wallet we can't sign for — try the unsigned form; if the
            // server refuses that too, let the retried register surface the real error.
            runCatching { api.unregister(PushUnregisterRequest(deviceToken = token, auth = null)) }
        }
    }

    /**
     * iOS's collectHiddenBroadcastSenders, exactly: for each watched (bell-enabled) channel,
     * legacy every-room rows (channelName "") union that room's own rows, sorted; rooms with
     * nothing hidden are omitted.
     */
    private suspend fun collectHiddenBroadcastSenders(
        watchedChannels: List<String>,
    ): Map<String, List<String>> {
        val rows = broadcastRepository.getHiddenSenders().first()
        if (rows.isEmpty() || watchedChannels.isEmpty()) return emptyMap()
        val global = rows.filter { it.channelName.isEmpty() }.map { it.senderAddress }
        val perChannel = rows.filter { it.channelName.isNotEmpty() }
            .groupBy({ it.channelName }, { it.senderAddress })
        return watchedChannels.mapNotNull { channel ->
            val combined = (global + perChannel[channel].orEmpty()).toSortedSet()
            if (combined.isEmpty()) null else channel to combined.toList()
        }.toMap()
    }

    private fun registrationFingerprint(
        token: String,
        walletAddress: String,
        watchedAddresses: List<String>,
        broadcastChannels: List<String>,
        hiddenSenders: Map<String, List<String>>,
        kaPostsPubkey: String,
    ): String = sha256Hex(
        listOf(
            token,
            walletAddress,
            canonicalizeAddresses(watchedAddresses).joinToString(","),
            broadcastChannels.sorted().joinToString(","),
            hiddenSenders.toSortedMap().entries.joinToString(";") { "${it.key}=${it.value.joinToString(",")}" },
            kaPostsPubkey,
        ).joinToString("\n")
    )

    /**
     * Fetches a fresh single-use challenge and signs the canonical preimage for [method]/[path]
     * with [material]'s key. Works from the captured [SigningMaterial] alone (never re-reads the
     * wallet), so [unregisterAsync]'s snapshot stays signable after the wallet is deleted.
     */
    private suspend fun buildAuth(
        material: SigningMaterial,
        method: String,
        path: String,
        deviceToken: String,
        watchedAddresses: List<String>,
        primaryAddress: String,
    ): PushAuthRequest {
        val api = networkService.pushApi.first { it != null }
            ?: throw IllegalStateException("push API unavailable")
        val walletPubkey = Schnorr.publicKeyXOnly(material.privateKey).toHex()
        val challenge = api.challenge()
        // The signed request's validity window must sit inside [issued_at_ms, expires_at_ms].
        val timestampMs = System.currentTimeMillis()
            .coerceIn(challenge.issuedAtMs, challenge.expiresAtMs)
        val preimage = buildAuthPreimage(
            method = method,
            path = path,
            deviceToken = deviceToken,
            watchedAddresses = watchedAddresses,
            primaryAddress = primaryAddress,
            aliases = emptyList(),
            walletPubkey = walletPubkey,
            walletAddress = material.walletAddress,
            nonce = challenge.nonce,
            timestampMs = timestampMs,
            expiresAtMs = challenge.expiresAtMs,
        )
        val signature = KaspaMessageSigner.sign(
            preimage,
            material.privateKey,
            KaspaMessageSigner.SigningMode.SHA256_DIGEST,
        )
        return PushAuthRequest(
            authVersion = 1,
            walletPubkey = walletPubkey,
            walletAddress = material.walletAddress,
            nonce = challenge.nonce,
            timestampMs = timestampMs,
            expiresAtMs = challenge.expiresAtMs,
            signature = signature,
        )
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

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHex()

    // Addresses: trim, drop empty, lowercase, dedupe, sort ascending (matches canonicalize_set).
    private fun canonicalizeAddresses(values: List<String>): List<String> =
        values.map { it.trim() }.filter { it.isNotEmpty() }.map { it.lowercase() }
            .toSet().sorted()

    // Aliases: trim, drop empty, case-preserved, dedupe, sort ascending.
    private fun canonicalizeAliases(values: List<String>): List<String> =
        values.map { it.trim() }.filter { it.isNotEmpty() }.toSet().sorted()

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
