package com.kachat.app.viewmodels

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kachat.app.models.BroadcastChannelEntity
import com.kachat.app.models.BroadcastMessageEntity
import com.kachat.app.models.ContactEntity
import com.kachat.app.models.ReactionEntity
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.repository.BroadcastRepository
import com.kachat.app.repository.ChatRepository
import com.kachat.app.services.BroadcastScanningService
import com.kachat.app.services.KnsService
import com.kachat.app.services.LinkPreviewService
import com.kachat.app.services.NetworkService
import com.kachat.app.services.NextcloudService
import com.kachat.app.services.NotificationHelper
import com.kachat.app.services.UtxoEntry
import com.kachat.app.services.VoiceRecorderService
import com.kachat.app.services.WalletManager
import com.kachat.app.util.MessageReaction
import com.kachat.app.util.MessageReply
import com.kachat.app.util.KaspaMass
import com.kachat.app.util.MessageProtocol
import com.kachat.app.util.VoiceMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class BroadcastViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val broadcastRepository: BroadcastRepository,
    private val broadcastScanningService: BroadcastScanningService,
    private val voiceRecorderService: VoiceRecorderService,
    private val networkService: NetworkService,
    private val walletManager: WalletManager,
    private val settings: AppSettingsRepository,
    private val knsService: KnsService,
    private val chatRepository: ChatRepository,
    private val notificationHelper: NotificationHelper,
    private val nextcloudService: NextcloudService
) : ViewModel() {

    // Address -> KNS avatar URL (or null if fetched but no avatar/domain exists) for whoever's
    // posted in a channel — broadcast senders usually aren't saved contacts, so this can't reuse
    // ChatRepository's per-contact knsAvatarUrl caching and instead looks up arbitrary addresses
    // directly via KnsService. A key's mere presence means "already fetched" (avoids re-fetching
    // on every recomposition), regardless of whether the value itself is null.
    private val _senderProfiles = MutableStateFlow<Map<String, String?>>(emptyMap())
    val senderProfiles: StateFlow<Map<String, String?>> = _senderProfiles.asStateFlow()

    // Address -> active KNS domain name (or null if fetched but the address owns no domain),
    // fetched alongside the avatar above — used as a fallback name label for senders the active
    // account hasn't saved a contact name for (see contactAliases below, which always wins first).
    private val _senderKnsNames = MutableStateFlow<Map<String, String?>>(emptyMap())
    val senderKnsNames: StateFlow<Map<String, String?>> = _senderKnsNames.asStateFlow()

    /**
     * Address -> locally-set contact alias, for whichever senders the active account has
     * renamed via "View Profile" — reactive (not a one-shot fetch like the KNS lookups above) so
     * editing a name on the chat-info screen and coming back to the broadcast reflects it
     * immediately. Takes priority over a sender's KNS name wherever both are shown, since a name
     * you deliberately set for someone should win over their public on-chain domain name.
     */
    val contactAliases: StateFlow<Map<String, String>> = chatRepository.getContacts()
        .map { contacts -> contacts.mapNotNull { c -> c.alias?.takeIf { it.isNotBlank() }?.let { c.id to it } }.toMap() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun ensureSenderProfileFetched(address: String) {
        if (_senderProfiles.value.containsKey(address)) return
        _senderProfiles.value = _senderProfiles.value + (address to null)
        viewModelScope.launch {
            try {
                val ownedAssets = knsService.getOwnedDomains(address)
                if (ownedAssets.isEmpty()) return@launch
                val ownedNames = ownedAssets.mapNotNull { it.asset }
                val primary = knsService.reverseResolve(address)
                val activeName = KnsService.pickActiveDomain(ownedNames, null, primary)
                _senderKnsNames.value = _senderKnsNames.value + (address to activeName)

                // Avatar lookup is gated by showKnsAvatarsEnabled — an avatarUrl is an arbitrary
                // attacker-controlled string, and fetching it just from a message rendering on
                // screen (no user action) is a real tracking risk. Name resolution above carries
                // no such risk (it only ever talks to KNS's own indexer, never an
                // attacker-supplied URL), so it stays unconditional. The viewer's own address is
                // always exempt from the gate — it's your own profile, so there's no attacker or
                // tracking risk in fetching it, and your own picture should keep showing normally
                // in broadcast rooms regardless of this setting.
                if (!showKnsAvatarsEnabled.value && address != walletManager.getAddress()) return@launch

                // The active/primary domain's own profile might have no avatar even though a
                // different domain the same address owns does — Edit Profile always writes
                // avatar/bio fields to the address's first owned domain regardless of which one
                // is separately marked "primary", so anyone whose primary differs from their
                // first owned domain would otherwise never show a picture here. Check the
                // active domain first, then fall through the rest of their owned domains for
                // the first one that actually has an avatar set.
                val activeAsset = ownedAssets.firstOrNull { it.asset == activeName }
                val checkOrder = listOfNotNull(activeAsset) + ownedAssets.filterNot { it.asset == activeName }
                val avatarUrl = checkOrder.firstNotNullOfOrNull { asset ->
                    asset.assetId?.let { knsService.getProfile(it) }?.avatarUrl
                }
                if (avatarUrl != null) {
                    _senderProfiles.value = _senderProfiles.value + (address to avatarUrl)
                }
            } catch (e: Exception) {
                Log.w("BroadcastViewModel", "Could not fetch KNS profile for $address", e)
            }
        }
    }

    /** Broadcast senders often aren't saved contacts yet — creates a minimal one first (same as how an unknown address is handled elsewhere) so the existing chat-info screen has something to show. */
    fun openSenderProfile(address: String, onReady: (String) -> Unit) {
        viewModelScope.launch {
            if (chatRepository.getContact(address) == null) {
                chatRepository.addContact(
                    ContactEntity(id = address, walletAddress = walletManager.getAddress(), alias = null, knsName = null, publicKeyHex = null)
                )
            }
            onReady(address)
        }
    }

    private val _messageText = MutableStateFlow("")
    val messageText: StateFlow<String> = _messageText.asStateFlow()
    fun setMessageText(text: String) {
        _messageText.value = text
        // The red failure line above the composer would otherwise sit there for the entire next
        // message being typed (it only cleared on the next send attempt). The failed bubble keeps
        // its own red icon + Retry, so once the user starts typing again the banner has done its
        // job — drop it rather than alarm them mid-composition.
        if (_sendBroadcastState.value.status == SendBroadcastStatus.FAILED) {
            _sendBroadcastState.value = SendBroadcastUiState()
        }
    }

    private val _currentUtxos = MutableStateFlow<List<UtxoEntry>>(emptyList())
    private val _networkFeeRate = MutableStateFlow(KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM.toDouble())
    val networkFeeRate: StateFlow<Double> = _networkFeeRate.asStateFlow()

    // User-adjustable override for a busy fee market — set via the composer's clickable fee pill.
    // Applies to the next broadcast/voice send and clears itself afterward.
    private val _feeRateOverride = MutableStateFlow<Long?>(null)
    val feeRateOverride: StateFlow<Long?> = _feeRateOverride.asStateFlow()

    fun setFeeRateOverride(rate: Long?) {
        _feeRateOverride.value = rate
    }

    // Declared here (ahead of the combine() below that references it) rather than down in the
    // "Voice messages" section further down — combine()'s flow arguments are evaluated
    // immediately when the enclosing val initializes, in textual property-declaration order, so
    // voiceRecordingState must already exist by the time previewPayloadSize is constructed.
    enum class VoiceRecordingStatus { IDLE, RECORDING }
    data class VoiceRecordingState(val status: VoiceRecordingStatus = VoiceRecordingStatus.IDLE, val elapsedMs: Long = 0L)

    private val _voiceRecordingState = MutableStateFlow(VoiceRecordingState())
    val voiceRecordingState: StateFlow<VoiceRecordingState> = _voiceRecordingState.asStateFlow()

    /**
     * The payload byte count to price the live fee preview off of: the real typed-text length
     * while composing, or a rough elapsed-time-based estimate of the final encoded size while
     * recording a voice message — same approach as 1:1 chats, applies equally to both since a
     * broadcast's content is never encrypted (no extra encryption overhead to account for).
     * Via Nextcloud, the chain only carries the ~100-byte share link regardless of recording
     * length — mirrors ChatViewModel.previewPayloadSize's identical branch.
     */
    private val previewPayloadSize = combine(_messageText, voiceRecordingState, nextcloudService.mediaSendEnabled) { text, recording, mediaSend ->
        if (recording.status == VoiceRecordingStatus.RECORDING) {
            if (mediaSend && nextcloudService.isConnected) NEXTCLOUD_LINK_PREVIEW_BYTES else VoiceMessage.estimatedWirePayloadSize(recording.elapsedMs)
        } else {
            text.toByteArray().size
        }
    }

    /**
     * A broadcast is always a zero-amount self-stash send, same shape as a 1:1 "comm" message —
     * so this is the same local KaspaMass calculation, just without any payment-amount branch.
     * Preview only, assuming a single 34-byte P2PK change output — KaspaWalletEngine skips the
     * zero-value recipient output for zero-amount sends (matches iOS's
     * estimateContextualMessageFee, which also prices off one output); the actual send path
     * computes this precisely against the real scriptPublicKey length.
     */
    val estimatedFeeSompi: StateFlow<Long?> = combine(previewPayloadSize, _currentUtxos, _networkFeeRate, _feeRateOverride) { payloadSize, utxos, networkRate, overrideRate ->
        val rate = overrideRate?.toDouble() ?: networkRate
        if (payloadSize == 0) return@combine null

        var total = 0L
        var count = 0
        for (utxo in utxos) {
            total += utxo.utxoEntry.amount
            count++
            if (total >= 1000) break
        }

        val mass = KaspaMass.calculateMass(
            numInputs = count.coerceAtLeast(1),
            outputScriptLens = listOf(34),
            payloadSize = payloadSize
        )
        KaspaMass.calculateFee(mass, rate.toLong())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    fun refreshUtxos() {
        viewModelScope.launch {
            try {
                val address = walletManager.getAddress()
                val api = networkService.kaspaRestApi.value ?: return@launch
                try {
                    val feeInfo = api.getFeeEstimate()
                    _networkFeeRate.value = feeInfo.normalBuckets.firstOrNull()?.feerate
                        ?: KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM.toDouble()
                } catch (e: Exception) {
                    Log.w("BroadcastViewModel", "Could not fetch fee estimate, using network minimum")
                }
                _currentUtxos.value = api.getUtxos(address)
            } catch (e: Exception) {
                Log.w("BroadcastViewModel", "Could not refresh UTXOs for fee estimate", e)
            }
        }
    }

    // Held only while a channel screen is actually on screen — see startLiveViewing(). A fresh
    // BroadcastViewModel instance is created per nav back-stack entry, so this never leaks across
    // different channel screens; onCleared() is the safety net if a screen never explicitly
    // calls stopLiveViewing() (e.g. process death skipping the DisposableEffect's onDispose).
    private var liveViewingHandle: AutoCloseable? = null

    /** Lets messages appear live in a broadcast channel screen without requiring that channel to be marked always-listen — call from a DisposableEffect keyed on the channel, paired with [stopLiveViewing]. */
    fun startLiveViewing(channelName: String) {
        liveViewingHandle?.close()
        liveViewingHandle = broadcastScanningService.startLiveViewing(channelName)
        notificationHelper.setActiveChannel(channelName) // suppress a notification for the channel already on screen
    }

    fun stopLiveViewing() {
        liveViewingHandle?.close()
        liveViewingHandle = null
        notificationHelper.setActiveChannel(null)
    }

    override fun onCleared() {
        stopLiveViewing()
        // Avoid leaking a live MediaRecorder if the screen/ViewModel is torn down mid-recording.
        if (_voiceRecordingState.value.status == VoiceRecordingStatus.RECORDING) {
            voiceRecorderService.cancelRecording()
        }
    }

    val joinedChannels: StateFlow<List<BroadcastChannelEntity>> = broadcastRepository.getJoinedChannels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Whether the Popular tab shows at all — toggled from the gear icon next to the join button. */
    val popularTabEnabled: StateFlow<Boolean> = settings.broadcastPopularEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setPopularTabEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setBroadcastPopularEnabled(enabled) }
    }

    /**
     * Whether senders' KNS profile pictures render in broadcast rooms, and whether
     * [ensureSenderProfileFetched] is allowed to look up a sender's avatar at all — toggled from
     * the same gear icon; off shows fallback initials for everyone and never fetches an avatar.
     */
    val showKnsAvatarsEnabled: StateFlow<Boolean> = settings.broadcastShowKnsAvatars
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setShowKnsAvatarsEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setBroadcastShowKnsAvatars(enabled) }
    }

    /** Which block explorer website "Go to Explorer" opens — shared preference, set in Settings > Kaspa Explorer. */
    val kaspaExplorer: StateFlow<com.kachat.app.models.KaspaExplorer> = settings.kaspaExplorer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.kachat.app.models.KaspaExplorer.default)

    /** The active account's hidden-sender rows - PER ROOM since 4.0 ("" = legacy every-room hide). */
    val hiddenSenders: StateFlow<List<com.kachat.app.models.HiddenBroadcastSenderEntity>> =
        broadcastRepository.getHiddenSenders()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Senders hidden in [channelName] - room-scoped rows plus legacy every-room rows. */
    fun hiddenAddressesIn(channelName: String): Set<String> =
        com.kachat.app.repository.BroadcastRepository.hiddenAddressesIn(channelName, hiddenSenders.value)

    /** Hides a sender in ONE room: their messages and notifications from that room disappear; other rooms are unaffected. */
    fun hideSender(senderAddress: String, channelName: String) {
        viewModelScope.launch { broadcastRepository.hideSender(senderAddress, channelName) }
    }

    fun unhideSender(senderAddress: String, channelName: String) {
        viewModelScope.launch { broadcastRepository.unhideSender(senderAddress, channelName) }
    }

    // MARK: - Indexer backfill (featured rooms): once on open, then every 8s while the room
    // stays open - messages sent while the app was closed appear, and the room stays fresh
    // even when live block scanning lags. Dedupe by txId happens in the DAO's REPLACE insert.

    init {
        // The curated #kaspa/#kachat-bugs rooms are always present (4.0): auto-joined with
        // fixed 3-day retention, backed by the broadcast indexer.
        viewModelScope.launch { broadcastRepository.ensureFeaturedChannelsJoined() }
    }

    private var indexerPollJob: kotlinx.coroutines.Job? = null

    fun startIndexerBackfill(channelName: String) {
        if (channelName !in com.kachat.app.models.FeaturedBroadcastChannels.NAMES) return
        stopIndexerBackfill()
        indexerPollJob = viewModelScope.launch {
            while (true) {
                broadcastRepository.backfillFromIndexer(channelName)
                kotlinx.coroutines.delay(8_000)
            }
        }
    }

    fun stopIndexerBackfill() {
        indexerPollJob?.cancel()
        indexerPollJob = null
    }

    /** Per-channel opt-in to background scanning — toggled via the speaker icon next to a channel. */
    fun setAlwaysListen(channelName: String, alwaysListen: Boolean) {
        viewModelScope.launch { broadcastRepository.setAlwaysListen(channelName, alwaysListen) }
    }

    /** Per-channel opt-in to a system notification for new messages — toggled via the bell icon next to a channel. */
    fun setNotifyEnabled(channelName: String, notifyEnabled: Boolean) {
        viewModelScope.launch { broadcastRepository.setNotifyEnabled(channelName, notifyEnabled) }
    }

    /** Per-channel local message retention override — set via the settings icon next to a channel, capped at 3 days. */
    fun setRetentionMillis(channelName: String, retentionMillis: Long) {
        viewModelScope.launch { broadcastRepository.setRetentionMillis(channelName, retentionMillis) }
    }

    // SUCCESS is distinct from the initial IDLE so a LaunchedEffect watching for "just joined"
    // can tell "nothing attempted yet" apart from "just succeeded" — collapsing both into IDLE
    // made the join dialog close itself the instant it opened, before the user typed anything.
    enum class JoinChannelStatus { IDLE, SUCCESS, FAILED }
    data class JoinChannelUiState(val status: JoinChannelStatus = JoinChannelStatus.IDLE, val message: String? = null)

    private val _joinChannelState = MutableStateFlow(JoinChannelUiState())
    val joinChannelState: StateFlow<JoinChannelUiState> = _joinChannelState.asStateFlow()

    fun joinChannel(rawName: String) {
        val name = MessageProtocol.normalizeChannelName(rawName)
        if (!MessageProtocol.isValidChannelName(name)) {
            _joinChannelState.value = JoinChannelUiState(
                status = JoinChannelStatus.FAILED,
                message = "Channel names can't be blank, contain spaces or colons, or exceed ${MessageProtocol.MAX_BROADCAST_CHANNEL_NAME_LENGTH} characters."
            )
            return
        }
        viewModelScope.launch {
            broadcastRepository.joinChannel(name)
            _joinChannelState.value = JoinChannelUiState(status = JoinChannelStatus.SUCCESS)
        }
    }

    fun resetJoinChannelState() {
        _joinChannelState.value = JoinChannelUiState()
    }

    fun leaveChannel(channelName: String) {
        viewModelScope.launch { broadcastRepository.leaveChannel(channelName) }
    }

    fun getMessages(channelName: String) = broadcastRepository.getMessages(channelName)

    // The message currently being replied to (double-tap on its bubble to set this), shown as a
    // banner above the compose field — cleared automatically once the reply actually sends.
    private val _replyingTo = MutableStateFlow<BroadcastMessageEntity?>(null)
    val replyingTo: StateFlow<BroadcastMessageEntity?> = _replyingTo.asStateFlow()

    fun startReplyTo(message: BroadcastMessageEntity) {
        _replyingTo.value = message
    }

    fun cancelReply() {
        _replyingTo.value = null
    }

    enum class SendBroadcastStatus { IDLE, SENDING, FAILED }
    data class SendBroadcastUiState(val status: SendBroadcastStatus = SendBroadcastStatus.IDLE, val message: String? = null)

    private val _sendBroadcastState = MutableStateFlow(SendBroadcastUiState())
    val sendBroadcastState: StateFlow<SendBroadcastUiState> = _sendBroadcastState.asStateFlow()

    fun sendBroadcast(channelName: String, content: String) {
        if (content.isBlank()) return
        if (_sendBroadcastState.value.status == SendBroadcastStatus.SENDING) return
        val reply = _replyingTo.value
        val feeRate = _feeRateOverride.value
        _feeRateOverride.value = null
        val payload = if (reply != null) {
            // Replying to a message that's itself a reply — unwrap to its actual text rather than
            // showing the inner reply's raw JSON as the preview.
            val preview = VoiceMessage.parseOrNull(reply.content)?.let { "🎤 Audio message" }
                ?: MessageReply.parseOrNull(reply.content)?.text
                ?: reply.content
            MessageReply.encode(replyToId = reply.id, replyToSender = reply.senderAddress, replyToPreview = preview, text = content)
        } else {
            content
        }
        viewModelScope.launch {
            _sendBroadcastState.value = SendBroadcastUiState(status = SendBroadcastStatus.SENDING)
            try {
                broadcastRepository.sendBroadcast(channelName, payload, feeRateOverride = feeRate)
                _replyingTo.value = null
                _sendBroadcastState.value = SendBroadcastUiState()
            } catch (e: Exception) {
                Log.e("BroadcastViewModel", "Error sending broadcast", e)
                _sendBroadcastState.value = SendBroadcastUiState(
                    status = SendBroadcastStatus.FAILED,
                    message = humanizeSendError(e)
                )
            }
        }
    }

    /**
     * Node-level rejection text ("transaction ... is an orphan, where orphan is disallowed",
     * "already spent", ...) means "the network hasn't caught up with your previous send yet" —
     * meaningless and alarming rendered raw above the composer. Map it (after the engine's own
     * orphan retry is exhausted) to plain language; anything unrecognized passes through as-is.
     */
    private fun humanizeSendError(e: Exception): String {
        val raw = e.message ?: return "Failed to send"
        val lower = raw.lowercase()
        return if (lower.contains("orphan") || lower.contains("already spent") || lower.contains("double spend")) {
            "The network is still confirming your previous send — please try again in a few seconds."
        } else {
            raw
        }
    }

    /** Re-attempts a failed broadcast — shown via a "Retry Send" option on a failed message's own dropdown menu, matching 1:1 chat's retry. */
    fun retryBroadcast(message: BroadcastMessageEntity) {
        viewModelScope.launch {
            try {
                broadcastRepository.retryBroadcast(message)
            } catch (e: Exception) {
                Log.e("BroadcastViewModel", "Error retrying broadcast", e)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Voice messages — same VoiceMessage codec and VoiceRecorderService as 1:1 chats, embedded
    // directly as the broadcast's plaintext content (never encrypted, matching how broadcasts
    // work generally) rather than needing a separate transport. VoiceRecordingStatus/State and
    // _voiceRecordingState itself are declared further up, ahead of the previewPayloadSize
    // combine() that needs them.
    // -------------------------------------------------------------------------

    private var recordingTickerJob: Job? = null

    /** Recording (not playback) needs Android 10+ — the mic button should be disabled below that. */
    val voiceRecordingSupported: Boolean get() = voiceRecorderService.isSupported

    fun startVoiceRecording(channelName: String) {
        if (_voiceRecordingState.value.status == VoiceRecordingStatus.RECORDING) return
        try {
            voiceRecorderService.startRecording()
        } catch (e: Exception) {
            Log.e("BroadcastViewModel", "Could not start voice recording", e)
            return
        }
        _voiceRecordingState.value = VoiceRecordingState(status = VoiceRecordingStatus.RECORDING)
        val startedAt = System.currentTimeMillis()
        // Same dynamic ceiling as 1:1/group chats: on-chain broadcast notes are payload-capped
        // at 10s, but a Nextcloud-uploaded note only needs to fit the server, so the cap
        // relaxes to 10 minutes while the toggle is on.
        val maxDurationMs = if (nextcloudService.mediaSendEnabled.value && nextcloudService.isConnected) {
            VoiceRecorderService.MAX_NEXTCLOUD_RECORDING_DURATION_MS
        } else {
            VoiceRecorderService.MAX_RECORDING_DURATION_MS
        }
        recordingTickerJob = viewModelScope.launch {
            while (isActive && _voiceRecordingState.value.status == VoiceRecordingStatus.RECORDING) {
                val elapsed = System.currentTimeMillis() - startedAt
                _voiceRecordingState.value = _voiceRecordingState.value.copy(elapsedMs = elapsed)
                if (elapsed >= maxDurationMs) {
                    stopAndSendVoiceRecording(channelName)
                    break
                }
                delay(200)
            }
        }
    }

    /** Stops recording and sends it — unless it was too short to be a real message (a stray tap), in which case it's discarded silently, same as a cancel. */
    fun stopAndSendVoiceRecording(channelName: String) {
        if (_voiceRecordingState.value.status != VoiceRecordingStatus.RECORDING) return
        val elapsed = _voiceRecordingState.value.elapsedMs
        recordingTickerJob?.cancel()
        recordingTickerJob = null
        _voiceRecordingState.value = VoiceRecordingState()

        val file = voiceRecorderService.stopRecording()
        if (file == null || elapsed < VoiceRecorderService.MIN_RECORDING_DURATION_MS) {
            file?.delete()
            return
        }
        sendVoiceMessage(channelName, file)
    }

    fun cancelVoiceRecording() {
        recordingTickerJob?.cancel()
        recordingTickerJob = null
        _voiceRecordingState.value = VoiceRecordingState()
        voiceRecorderService.cancelRecording()
    }

    /** With "Send Media via Nextcloud" on (and an account connected), the recorded file (exactly
     *  as captured — no re-encode, so the full relaxed-cap length ships byte-for-byte) uploads to
     *  the server and the broadcast is just the public share link, sent as plain text through the
     *  normal [sendBroadcast] pipeline; any upload/share failure falls back to the embedded
     *  on-chain audio envelope below, with a toast so the sender knows. Mirrors ChatViewModel's
     *  1:1/group Nextcloud voice gates exactly. */
    private fun sendVoiceMessage(channelName: String, file: java.io.File) {
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                if (nextcloudService.mediaSendEnabled.value && nextcloudService.isConnected) {
                    try {
                        val extension = file.extension.ifEmpty { "webm" }
                        val mimeType = when (extension.lowercase()) {
                            "webm" -> "audio/webm"
                            "ogg", "opus" -> "audio/ogg"
                            "m4a", "mp4" -> "audio/mp4"
                            else -> "application/octet-stream"
                        }
                        // The recorder already names files voice_<timestamp>.<ext> — keep that name.
                        val url = nextcloudService.uploadMediaAndShare(bytes, file.name, mimeType)
                        sendBroadcast(channelName, url)
                        // Warm the preview cache in the background so the sender's own bubble
                        // renders the audio attachment card immediately instead of after its
                        // LinkPreviewCard's lazy fetch round-trips.
                        launch { LinkPreviewService.fetchPreview(url) }
                        return@launch
                    } catch (e: Exception) {
                        Log.w("BroadcastViewModel", "Nextcloud broadcast voice upload failed, falling back to on-chain send", e)
                        android.widget.Toast.makeText(appContext, "Nextcloud upload failed — sending on-chain instead", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val json = VoiceMessage.encode(fileName = file.name, sizeBytes = bytes.size.toLong(), base64Audio = base64)
                sendBroadcast(channelName, json)
            } catch (e: Exception) {
                Log.e("BroadcastViewModel", "Error preparing voice message", e)
            } finally {
                file.delete()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Reactions — same MessageReaction JSON codec and pill/picker UI as 1:1/group chats, sent as
    // a normal plain-text broadcast through the existing sendBroadcast pipeline (never wrapped in
    // a reply envelope). Aggregation/persistence is derived from the cached broadcast message
    // rows themselves — see BroadcastRepository.getReactions.
    // -------------------------------------------------------------------------

    /** Reactions in [channelName], aggregated one-per-(target, reactor) — group by `targetTxId` at the call site, same as group chat's screen does. */
    fun getReactions(channelName: String) = broadcastRepository.getReactions(channelName)

    /**
     * Reacts to [targetTxId] with [emoji] ("add"), or removes this wallet's existing reaction on
     * it ("remove"). The reaction is just a broadcast whose content is the [MessageReaction]
     * JSON — the optimistic pending row sendBroadcast inserts is what makes the pill appear
     * immediately (it aggregates like any other cached reaction row), mirroring group chat's
     * optimistic apply.
     */
    fun sendReaction(channelName: String, targetTxId: String, emoji: String, action: String) {
        // A still-pending target has no real txId yet — reacting to it would put a useless
        // "pending_<uuid>" target on-chain that no other client could ever resolve.
        if (targetTxId.startsWith("pending_")) return
        viewModelScope.launch {
            try {
                broadcastRepository.sendBroadcast(channelName, MessageReaction.encode(targetTxId, emoji, action))
            } catch (e: Exception) {
                // The pending reaction row flips to "failed" inside sendBroadcast — the pill shows
                // the red error icon and a Retry appears under the message, same as groups.
                Log.e("BroadcastViewModel", "Error sending broadcast reaction", e)
            }
        }
    }

    /** Retries a reaction whose send previously failed — re-attempts the stored reaction message row (see the pill's error icon + Retry, matching groups). */
    fun retryReaction(reaction: ReactionEntity) {
        viewModelScope.launch {
            try {
                broadcastRepository.retryReactionMessage(reaction.reactionTxId)
            } catch (e: Exception) {
                Log.e("BroadcastViewModel", "Error retrying broadcast reaction", e)
            }
        }
    }

    companion object {
        /** Rough on-chain payload size of a Nextcloud share link — the message is just the URL.
         *  Same figure as ChatViewModel's identically-named constant. */
        private const val NEXTCLOUD_LINK_PREVIEW_BYTES = 100
    }
}
