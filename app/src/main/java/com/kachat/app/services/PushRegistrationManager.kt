package com.kachat.app.services

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.repository.BroadcastRepository
import com.kachat.app.repository.ChatRepository
import com.kachat.app.repository.GroupRepository
import com.kachat.app.util.GroupCipher
import com.kachat.app.util.KaspaMessageSigner
import com.kachat.app.util.Schnorr
import com.kachat.app.util.Secp256k1
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers this device's FCM token with the KaChat indexer's `/v1/push` API so the server can
 * deliver native push notifications (DMs, KaPosts pings, broadcast channels) while the app is
 * backgrounded or killed.
 *
 * The registration is authenticated exactly like the iOS client: a BIP-340 Schnorr signature over
 * a canonical, newline-joined preimage. With no groups we emit the **LegacyV1** auth shape; once
 * the device has group memberships we register their blinded group ids and switch to the
 * **TransitionalGroups** shape (extra `watched_group_ids_hash` line, forced `group_v1` capability),
 * both matched by the Rust server (`kasia-indexer`) in `build_auth_preimage`.
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
    private val groupRepository: GroupRepository,
    private val groupSecretStore: GroupSecretStore,
    private val settings: AppSettingsRepository,
    private val pushState: PushState,
    private val peerAliasStore: PeerAliasStore,
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
     * [childModeEnabled] is the sixth observed input: toggling Child Mode changes what
     * [register] sends (broadcast channels / KaPosts pubkey dropped), so it must re-trigger a
     * registration automatically, exactly like iOS's .settingsDidChange → updateWatchedAddresses.
     */
    private data class Snapshot(
        val activeAddress: String?,
        val notificationsEnabled: Boolean,
        val activeContacts: Set<String>,
        val notifyChannels: Set<String>,
        val hiddenSenderRows: Set<Pair<String, String>>, // (channelName, senderAddress)
        val childModeEnabled: Boolean,
        /** The five KaPosts switches. A flip re-registers at once: the server reads them from
         *  the registration, and nothing on the device can stop a background KaPosts push. */
        val kaPostsKinds: KaPostsNotifyKinds,
        /** Groups silenced on this device. They are dropped from the watched ids, so silencing
         *  one has to reach the service the moment the switch flips. */
        val silentGroups: Set<String>,
    )

    /** The five per-kind KaPosts switches as one flow, so a flip of any of them is one input. */
    private val kaPostsKindsFlow: Flow<KaPostsNotifyKinds> = combine(
        settings.kaPostsNotifyLikes,
        settings.kaPostsNotifyDislikes,
        settings.kaPostsNotifyComments,
        settings.kaPostsNotifyReposts,
        settings.kaPostsNotifyFollows,
    ) { likes, dislikes, comments, reposts, follows ->
        KaPostsNotifyKinds(likes = likes, dislikes = dislikes, comments = comments, reposts = reposts, follows = follows)
    }

    init {
        scope.launch {
            // A combine of this many flows only exists as the vararg overload (the typed ones
            // stop at five), hence the positional Array<Any?> casts.
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
                settings.childModeEnabled,
                kaPostsKindsFlow,
                settings.groupSilent,
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                Snapshot(
                    activeAddress = values[0] as String?,
                    notificationsEnabled = values[1] as Boolean,
                    activeContacts = values[2] as Set<String>,
                    notifyChannels = values[3] as Set<String>,
                    hiddenSenderRows = values[4] as Set<Pair<String, String>>,
                    childModeEnabled = values[5] as Boolean,
                    kaPostsKinds = values[6] as KaPostsNotifyKinds,
                    silentGroups = values[7] as Set<String>,
                )
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
     * Asks the push service to ring [toAddress]'s devices for a call this device is placing
     * (PUSH_EXTENSIONS.md §5). The request is signed like every other push call, which is how the
     * service knows the sender it names in the push; [payloadHex] is the opening call message
     * encrypted to the contact, exactly as it went on chain.
     *
     * Best effort by design: a phone with the app open rings off the chain message anyway, so a
     * failure here costs nothing but the ring on a sleeping phone. Throws so the caller can log it.
     */
    suspend fun requestRing(toAddress: String, callId: String, video: Boolean, kind: String, payloadHex: String) {
        val api = pushApiOrNull()
            ?: throw IllegalStateException("push API unavailable")
        val token = FirebaseMessaging.getInstance().token.await().trim()
        if (token.isEmpty()) throw IllegalStateException("no FCM token")
        val material = signingMaterial()
        // The service checks a ring in its legacy shape (PUSH_EXTENSIONS.md §5, push.rs ring_call
        // -> LegacyV1): no watched_group_ids_hash line, and the caller's own wallet address as
        // primary_address - the same preimage iOS signs since 7182b4a/82eb7f7 (audit XP-010).
        val auth = buildAuth(
            material,
            method = "POST",
            path = PushAuthPreimage.RING_PATH,
            deviceToken = token,
            watchedAddresses = emptyList(),
            aliases = emptyList(),
            watchedGroupIds = emptyList(),
            primaryAddress = material.walletAddress,
            includeWatchedGroupIds = false,
        )
        api.ring(
            PushRingRequest(
                deviceToken = token,
                toAddress = toAddress,
                callId = callId,
                video = video,
                kind = kind,
                payload = payloadHex,
                timestampMs = System.currentTimeMillis(),
                auth = auth,
            )
        )
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

        val api = pushApiOrNull() ?: return@withLock
        val token = try { FirebaseMessaging.getInstance().token.await().trim() } catch (e: Exception) {
            pushState.recordAttempt("unregister", succeeded = false, error = "FCM token unavailable: ${e.message}", fcmTokenPresent = false)
            return@withLock
        }
        if (token.isEmpty()) return@withLock

        val auth = material?.let {
            runCatching { buildAuth(it, "DELETE", "/v1/push/unregister", token, watchedAddresses = emptyList(), primaryAddress = "") }
                // Best-effort unsigned fallback, same as iOS's unregister when auth can't be built.
                .getOrNull()
        }
        try {
            api.unregister(PushUnregisterRequest(deviceToken = token, auth = auth))
            pushState.recordAttempt("unregister", succeeded = true, error = null, fcmTokenPresent = true)
            Log.i(TAG, "push unregistered")
        } catch (e: HttpException) {
            // A signed attempt the server rejects (e.g. token bound to a wallet we no longer
            // hold the key for) still deserves the unsigned best-effort try before giving up.
            val recovered = auth != null && runCatching {
                api.unregister(PushUnregisterRequest(deviceToken = token, auth = null))
            }.isSuccess
            if (recovered) {
                pushState.recordAttempt("unregister", succeeded = true, error = null, fcmTokenPresent = true)
                Log.i(TAG, "push unregistered (unsigned fallback)")
            } else {
                pushState.recordAttempt("unregister", succeeded = false, error = describeError(e), fcmTokenPresent = true)
                Log.w(TAG, "push unregister rejected: ${describeError(e)}")
            }
        }
    }

    // MARK: - No push service on this network (testnet) - iOS df52425

    private val gson = com.google.gson.Gson()
    private val pushPrefs by lazy { context.getSharedPreferences("kachat_push_registration", Context.MODE_PRIVATE) }

    /** The push API, or null when this network has no push service (a blank URL - testnet). It
     *  used to wait for a client that a blank URL never builds. */
    private suspend fun pushApiOrNull(): PushApi? {
        if (settings.pushIndexerUrl.first().isBlank()) return null
        return networkService.pushApi.first { it != null }
    }

    /**
     * No push service on this network: the registration made on mainnet is withdrawn with one
     * unsigned DELETE to the service the device last registered with (unsigned because the signed
     * form binds the address of the network being left), and this device counts as unregistered -
     * otherwise the mainnet service kept pushing mainnet messages into the testnet account.
     */
    private suspend fun leavePushServiceOfOtherNetworkIfNeeded() {
        pushState.setActive(false)
        lastRegisteredFingerprint = null
        // Registrations made before this was recorded went to the shipped default service.
        val registeredBase = pushPrefs.getString(KEY_REGISTERED_BASE_URL, null)?.takeIf { it.isNotBlank() }
            ?: com.kachat.app.repository.AppSettingsRepository.DEFAULT_PUSH_INDEXER_URL.takeIf { !pushPrefs.getBoolean(KEY_LEFT, false) }
            ?: return
        pushPrefs.edit().remove(KEY_REGISTERED_BASE_URL).putBoolean(KEY_LEFT, true).apply()
        val token = try { FirebaseMessaging.getInstance().token.await().trim() } catch (e: Exception) { return }
        if (token.isEmpty()) return
        try {
            val body = gson.toJson(PushUnregisterRequest(deviceToken = token, auth = null))
                .toRequestBody("application/json".toMediaType())
            val request = okhttp3.Request.Builder()
                .url(registeredBase.trimEnd('/') + "/v1/push/unregister")
                .delete(body)
                .build()
            leaveClient.newCall(request).execute().close()
            Log.i(TAG, "No push service on this network; sent unregister to $registeredBase")
        } catch (e: Exception) {
            Log.w(TAG, "Unregister from the other network's push service failed: ${e.message}")
        }
    }

    private val leaveClient by lazy {
        okhttp3.OkHttpClient.Builder().callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()
    }

    private suspend fun register(tokenOverride: String?) = mutex.withLock {
        if (!walletManager.hasWallet()) return@withLock
        if (!settings.notificationsEnabled.first()) return@withLock
        if (settings.pushIndexerUrl.first().isBlank()) {
            withContext(Dispatchers.IO) { leavePushServiceOfOtherNetworkIfNeeded() }
            return@withLock
        }

        val api = pushApiOrNull() ?: return@withLock
        val token = try {
            (tokenOverride ?: FirebaseMessaging.getInstance().token.await()).trim()
        } catch (e: Exception) {
            // No google-services.json / no Play services / Firebase not initialized.
            pushState.recordAttempt("register", succeeded = false, error = "FCM token unavailable: ${e.message}", fcmTokenPresent = false)
            throw e
        }
        if (token.isEmpty()) {
            pushState.recordAttempt("register", succeeded = false, error = "FCM returned an empty token", fcmTokenPresent = false)
            return@withLock
        }

        val privateKey = walletManager.getPrivateKeyBytes()
        // Kaspa addresses are canonical lowercase bech32, so this already matches the server's
        // RpcAddress round-trip (normalize_wallet_address / derive_wallet_address).
        val walletAddress = walletManager.getAddress().trim()
        // Child Mode: no broadcast channels and no KaPosts identity registered with the push
        // service at all (mirrors iOS's collectWatchedBroadcastChannels/collectKaPostsPubkey
        // guards). Both feed the fingerprint below AND the init observer watches the flag, so
        // toggling the mode re-registers automatically in either direction.
        val childMode = settings.childModeEnabled.first()
        val kaPostsPubkey = if (childMode) null else compressedPubkeyHex(privateKey)
        // The reader's per-kind KaPosts switches, so the server can skip a push at the source.
        // Part of the fingerprint AND an init-observer input, so a flip re-registers at once.
        val kaPostsKinds = kaPostsKindsFlow.first()

        // Own address included alongside active contacts, matching iOS's collectWatchedAddresses
        // — the server routes by SENDER (find_devices_watching), so without it a handshake from a
        // not-yet-known sender could never be pushed.
        val activeContacts = chatRepository.getContacts().first()
            .filter { it.conversationStatus == "active" }
        val watchedAddresses = (activeContacts.map { it.id } + walletAddress).distinct()
        // The aliases INCOMING messages from these contacts carry (same aliases the message sync
        // fetches with). Registering them narrows the server's alias filter to OUR conversations,
        // so a contact's messages to OTHER people don't match us (iOS: knownIncomingAliases).
        val aliases = collectIncomingAliases(activeContacts)
        val broadcastChannels = if (childMode) emptyList() else broadcastRepository.getNotifyEnabledChannelNames().first().toList()
        val hiddenSenders = collectHiddenBroadcastSenders(broadcastChannels)
        // Blinded group ids to be pushed for (per group, per other non-muted member) — iOS parity.
        // Non-empty flips the registration to the TransitionalGroups auth shape.
        val watchedGroupIds = collectWatchedGroupIds(walletAddress)

        // Skip the network round-trip entirely when the server-accepted snapshot is unchanged —
        // this is what collapses the startup trigger + init-observer + foreground triggers into
        // one actual registration.
        val fingerprint = registrationFingerprint(
            token, walletAddress, watchedAddresses, aliases, broadcastChannels, hiddenSenders, kaPostsPubkey,
            watchedGroupIds, kaPostsKinds,
        )
        if (fingerprint == lastRegisteredFingerprint) return@withLock

        try {
            submitRegistration(
                api, token, privateKey, walletAddress, kaPostsPubkey,
                watchedAddresses, aliases, broadcastChannels, hiddenSenders, watchedGroupIds, kaPostsKinds,
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
                watchedAddresses, aliases, broadcastChannels, hiddenSenders, watchedGroupIds, kaPostsKinds,
            )
        }

        lastRegisteredFingerprint = fingerprint
        // Remote-push mode is only real if the system will actually show what FCM delivers;
        // with POST_NOTIFICATIONS denied the pollers' (equally invisible) banners are moot
        // anyway, but keeping the flag honest costs nothing.
        pushState.setActive(NotificationManagerCompat.from(context).areNotificationsEnabled())
        pushState.recordAttempt("register", succeeded = true, error = null, fcmTokenPresent = true)
        // The service this device is registered with - withdrawn from on a network without one.
        pushPrefs.edit().putString(KEY_REGISTERED_BASE_URL, settings.pushIndexerUrl.first()).putBoolean(KEY_LEFT, false).apply()
        Log.i(
            TAG,
            "push registered (watched=${watchedAddresses.size}, aliases=${aliases.size}, groups=${watchedGroupIds.size}, " +
                "channels=${broadcastChannels.size}, hiddenRooms=${hiddenSenders.size}, active=${pushState.isActive})"
        )
    }

    private suspend fun submitRegistration(
        api: PushApi,
        token: String,
        privateKey: ByteArray,
        walletAddress: String,
        // null while Child Mode is on — the server then sends no KaPosts pings to this device.
        kaPostsPubkey: String?,
        watchedAddresses: List<String>,
        aliases: List<String>,
        broadcastChannels: List<String>,
        hiddenSenders: Map<String, List<String>>,
        watchedGroupIds: List<String>,
        kaPostsKinds: KaPostsNotifyKinds,
    ) {
        try {
            val auth = buildAuth(
                SigningMaterial(privateKey, walletAddress),
                method = "POST",
                path = "/v1/push/register",
                deviceToken = token,
                watchedAddresses = watchedAddresses,
                aliases = aliases,
                watchedGroupIds = watchedGroupIds,
                primaryAddress = walletAddress,
            )
            api.register(
                PushRegistrationRequest(
                    deviceToken = token,
                    platform = "android",
                    watchedAddresses = watchedAddresses,
                    // Non-null (even empty) would force TransitionalGroups; send null when there
                    // are no groups so DM-only devices stay on the simpler LegacyV1 shape.
                    watchedGroupIds = watchedGroupIds.ifEmpty { null },
                    primaryAddress = walletAddress,
                    aliases = aliases,
                    watchedBroadcastChannels = broadcastChannels,
                    hiddenBroadcastSenders = hiddenSenders,
                    kaPostsPubkey = kaPostsPubkey,
                    kaPostsNotifyLikes = kaPostsKinds.likes,
                    kaPostsNotifyDislikes = kaPostsKinds.dislikes,
                    kaPostsNotifyComments = kaPostsKinds.comments,
                    kaPostsNotifyReposts = kaPostsKinds.reposts,
                    kaPostsNotifyFollows = kaPostsKinds.follows,
                    auth = auth,
                )
            )
        } catch (e: Exception) {
            // Any failed attempt drops us out of remote-push mode so the pollers keep notifying,
            // and forgets the fingerprint so the next trigger retries for real.
            pushState.setActive(false)
            lastRegisteredFingerprint = null
            pushState.recordAttempt(
                "register",
                succeeded = false,
                error = describeError(e),
                fcmTokenPresent = true,
            )
            Log.w(TAG, "register attempt failed: ${describeError(e)}")
            throw e
        }
    }

    /** HTTP failures with status + server error body when available; else the exception message. */
    private fun describeError(e: Exception): String = when (e) {
        is HttpException -> {
            val body = runCatching { e.response()?.errorBody()?.string()?.take(200) }.getOrNull()
            "HTTP ${e.code()}${if (body.isNullOrBlank()) "" else ": $body"}"
        }
        else -> e.message ?: e.javaClass.simpleName
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
        aliases: List<String>,
        broadcastChannels: List<String>,
        hiddenSenders: Map<String, List<String>>,
        kaPostsPubkey: String?,
        watchedGroupIds: List<String>,
        kaPostsKinds: KaPostsNotifyKinds,
    ): String = sha256Hex(
        listOf(
            token,
            walletAddress,
            canonicalizeAddresses(watchedAddresses).joinToString(","),
            canonicalizeAliases(aliases).joinToString(","),
            broadcastChannels.sorted().joinToString(","),
            hiddenSenders.toSortedMap().entries.joinToString(";") { "${it.key}=${it.value.joinToString(",")}" },
            kaPostsPubkey.orEmpty(),
            canonicalizeAddresses(watchedGroupIds).joinToString(","),
            kaPostsKinds.fingerprint(),
        ).joinToString("\n")
    )

    /**
     * The aliases INCOMING messages from these contacts carry — the same set the message sync
     * queries with (contact.theirAlias + the deterministic alias derivable from both addresses).
     * Registering them makes the server's alias filter match only OUR conversations, not the
     * contact's messages to third parties. Mirrors iOS's knownIncomingAliases. Case-preserved.
     */
    private fun collectIncomingAliases(activeContacts: List<com.kachat.app.models.ContactEntity>): List<String> {
        val aliases = mutableSetOf<String>()
        for (contact in activeContacts) {
            contact.theirAlias?.trim()?.takeIf { it.isNotEmpty() }?.let { aliases += it }
            runCatching { walletManager.myDeterministicAlias(contact.id) }
                .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { aliases += it }
            // Every alias they have been seen sending to us under (PeerAliasStore), so a push is
            // sent for those messages too.
            aliases += peerAliasStore.aliases(contact.walletAddress, contact.id)
        }
        return aliases.toList()
    }

    /**
     * Blinded group ids to receive group-message push for — one per (group, other non-muted member),
     * matching iOS's collectWatchedGroupIds. `deriveBlindedGroupId(blindingKey, memberXOnlyPubKey)`
     * is a byte-for-byte port of the sender's on-chain derivation, so the resulting hex equals the
     * `blinded_group_id` the indexer puts on the push event. Lowercase 64-hex, deduped.
     */
    private suspend fun collectWatchedGroupIds(walletAddress: String): List<String> {
        val muted = runCatching { settings.groupMutedMembers.first() }.getOrDefault(emptySet())
        val silent = runCatching { settings.groupSilent.first() }.getOrDefault(emptySet())
        val groups = runCatching { groupRepository.getGroups().first() }.getOrDefault(emptyList())
        val ids = mutableSetOf<String>()
        for (group in groups) {
            // A silenced group is not watched at all, so no push is ever sent for it. It used to
            // stay on the list and rely on this app to swallow the push on arrival, which leaves
            // a generic banner behind whenever the message cannot be read - no payload, keys not
            // reachable, group secrets not yet resynced after an update (iOS 62664af).
            if (group.groupId in silent) continue
            val bag = groupSecretStore.loadBag(walletAddress, group.groupId) ?: continue
            val blindingKey = runCatching { bag.blindingKey.hexToBytes() }.getOrNull() ?: continue
            for (member in groupRepository.membersOf(group)) {
                if (member.address == walletAddress) continue                     // exclude self
                if ("${group.groupId}|${member.address}" in muted) continue       // exclude muted
                val pub = runCatching { member.xOnlyPubKeyHex.hexToBytes() }.getOrNull() ?: continue
                ids += GroupCipher.deriveBlindedGroupId(blindingKey, pub).toHex()
            }
        }
        return ids.toList()
    }

    private fun String.hexToBytes(): ByteArray {
        val clean = trim()
        require(clean.length % 2 == 0) { "odd-length hex" }
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

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
        aliases: List<String> = emptyList(),
        watchedGroupIds: List<String> = emptyList(),
        primaryAddress: String,
        /** The group line rides only when group ids are registered (TransitionalGroups); the
         *  ring passes false explicitly - see [PushAuthPreimage.build]. */
        includeWatchedGroupIds: Boolean = watchedGroupIds.isNotEmpty(),
    ): PushAuthRequest {
        val api = pushApiOrNull()
            ?: throw IllegalStateException("push API unavailable")
        val walletPubkey = Schnorr.publicKeyXOnly(material.privateKey).toHex()
        val challenge = api.challenge()
        // The signed request's validity window must sit inside [issued_at_ms, expires_at_ms].
        val timestampMs = System.currentTimeMillis()
            .coerceIn(challenge.issuedAtMs, challenge.expiresAtMs)
        val preimage = PushAuthPreimage.build(
            method = method,
            path = path,
            deviceToken = deviceToken,
            watchedAddresses = watchedAddresses,
            watchedGroupIds = watchedGroupIds,
            primaryAddress = primaryAddress,
            aliases = aliases,
            walletPubkey = walletPubkey,
            walletAddress = material.walletAddress,
            nonce = challenge.nonce,
            timestampMs = timestampMs,
            expiresAtMs = challenge.expiresAtMs,
            includeWatchedGroupIds = includeWatchedGroupIds,
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

    private fun sha256Hex(value: String): String = PushAuthPreimage.sha256Hex(value)

    private fun canonicalizeAddresses(values: List<String>): List<String> = PushAuthPreimage.canonicalizeAddresses(values)

    private fun canonicalizeAliases(values: List<String>): List<String> = PushAuthPreimage.canonicalizeAliases(values)

    /** 66-hex compressed secp256k1 pubkey — the KaPosts "K" identity (same as KaPostsService). */
    private fun compressedPubkeyHex(privateKey: ByteArray): String {
        val priv = BigInteger(1, privateKey)
        return Secp256k1.G.multiply(priv).normalize().getEncoded(true).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val KEY_REGISTERED_BASE_URL = "registered_base_url"
        private const val KEY_LEFT = "left_push_service"
        // Shared with KaChatFirebaseMessagingService so `adb logcat -s KaChatPush` shows the
        // whole story: registration attempts, their outcomes, token rotations, and every
        // received push.
        internal const val TAG = "KaChatPush"
    }
}
