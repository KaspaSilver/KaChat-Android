package com.kachat.app.viewmodels

import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import com.kachat.app.util.redactedForLog
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import com.kachat.app.models.FeaturedBroadcastChannels
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class BroadcastViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val draftStore: com.kachat.app.services.DraftStore,
    private val broadcastRepository: BroadcastRepository,
    private val broadcastScanningService: BroadcastScanningService,
    private val voiceRecorderService: VoiceRecorderService,
    private val networkService: NetworkService,
    private val walletManager: WalletManager,
    private val settings: AppSettingsRepository,
    private val knsService: KnsService,
    private val chatRepository: ChatRepository,
    private val notificationHelper: NotificationHelper,
    private val nextcloudService: NextcloudService,
    /** What has been read in each room, per wallet - the Public Chats list's unread counts. */
    private val readState: com.kachat.app.services.BroadcastReadStateStore,
    /** The KaPosts translation service, reused as-is for public chat messages (iOS 03e5128). */
    private val translationService: com.kachat.app.services.PostTranslationService,
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
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun ensureSenderProfileFetched(address: String) {
        if (_senderProfiles.value.containsKey(address)) return
        _senderProfiles.value = _senderProfiles.value + (address to null)
        viewModelScope.launch {
            try {
                val ownedAssets = knsService.getOwnedDomains(address)
                if (ownedAssets.isEmpty()) return@launch
                val ownedNames = ownedAssets.mapNotNull { it.asset }
                val primary = knsService.identityName(address)
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
                Log.w("BroadcastViewModel", "Could not fetch KNS profile for ${address.redactedForLog()}", e)
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
    /** The room whose draft the composer is editing - see [openDraft]. */
    @Volatile
    private var activeDraftRoom: String? = null

    /** A room was entered: its composer shows what was typed there last time, and keeps it as it
     *  changes (iOS 360e5d2, keyed "room:<name>"). Replaced outright, so nothing carries over. */
    fun openDraft(channelName: String) {
        activeDraftRoom = channelName
        _messageText.value = draftStore.draft(com.kachat.app.services.DraftStore.roomKey(channelName))
    }

    fun closeDraft(channelName: String) {
        if (activeDraftRoom == channelName) activeDraftRoom = null
    }

    fun setMessageText(text: String) {
        _messageText.value = text
        activeDraftRoom?.let { draftStore.setDraft(com.kachat.app.services.DraftStore.roomKey(it), text) }
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
    /** This recording was started "via Nextcloud" from the mic's route step - the room then
     *  carries only the share link. Replaced the "Send Media via Nextcloud" setting (iOS 8b13460).
     *  Declared ahead of [previewPayloadSize], which reads it. */
    private val _nextcloudVoiceRequested = MutableStateFlow(false)
    /** The connected Nextcloud account, or null - with one, the mic asks on chain or via
     *  Nextcloud first. */
    val nextcloudAccount get() = nextcloudService.account
    private val voiceViaNextcloud: Boolean get() = _nextcloudVoiceRequested.value && nextcloudService.isConnected

    private val previewPayloadSize = combine(_messageText, voiceRecordingState, _nextcloudVoiceRequested) { text, recording, voiceNextcloud ->
        if (recording.status == VoiceRecordingStatus.RECORDING) {
            if (voiceNextcloud && nextcloudService.isConnected) NEXTCLOUD_LINK_PREVIEW_BYTES else VoiceMessage.estimatedWirePayloadSize(recording.elapsedMs)
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

    // Rooms the app uses as machinery (the chess arena) are never chats: out of the list, the
    // unread counts and every room summary (iOS BroadcastService.serviceChannels).
    val joinedChannels: StateFlow<List<BroadcastChannelEntity>> = broadcastRepository.getJoinedChannels()
        .map { channels -> channels.filter { it.channelName !in com.kachat.app.services.ChessTournamentService.SERVICE_CHANNELS } }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Each joined room's newest message and unread count, for the Public Chats list. Rebuilt when
     * the joined set or the read markers change; each room's own flow then follows its messages.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val roomSummaries: StateFlow<Map<String, BroadcastRepository.RoomSummary>> =
        kotlinx.coroutines.flow.combine(joinedChannels, readState.state) { channels, read -> channels to read }
            .flatMapLatest { (channels, read) ->
                val me = runCatching { walletManager.getAddress() }.getOrNull()
                if (channels.isEmpty()) {
                    kotlinx.coroutines.flow.flowOf(emptyMap())
                } else {
                    kotlinx.coroutines.flow.combine(
                        channels.map { channel ->
                            val name = channel.channelName
                            broadcastRepository.roomSummary(name, me, read.lastReadByChannel[name])
                                .map { summary ->
                                    // A default room switched off in Public Chats settings is
                                    // never counted; one marked unread by hand shows at least one.
                                    val hidden = name in read.hiddenCurated
                                    val manual = name in read.manuallyUnread
                                    name to summary.copy(
                                        unreadCount = if (hidden) 0 else maxOf(summary.unreadCount, if (manual) 1 else 0)
                                    )
                                }
                        }
                    ) { pairs -> pairs.toMap() }
                }
            }
            .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /**
     * The rooms the Public Chats list shows, in its order: the two Popular rooms on top, then every
     * other joined room by latest activity. Default rooms switched off in settings are left out.
     * Shared by the list and by its Select All (iOS e08c4cc, PublicChatService.listedChannels).
     */
    val listedChannels: StateFlow<List<BroadcastChannelEntity>> =
        kotlinx.coroutines.flow.combine(joinedChannels, roomSummaries, readState.state) { channels, summaries, read ->
            val featured = FeaturedBroadcastChannels.NAMES
            val shown = channels.filter { it.channelName !in read.hiddenCurated }
            fun lastActivity(channel: BroadcastChannelEntity): Long =
                summaries[channel.channelName]?.lastMessage?.blockTimestamp ?: channel.joinedAt
            featured.mapNotNull { name -> shown.firstOrNull { it.channelName == name } } +
                shown.filter { it.channelName !in featured }.sortedByDescending(::lastActivity)
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * "Delete" from the room list. A room you added is left for good ([leaveChannel], its messages
     * go). A default room cannot really be deleted - it is simply switched off, the same as its
     * toggle in Public Chats settings, and that toggle brings it back (iOS e08c4cc).
     */
    fun removeFromList(channelName: String) {
        if (channelName in FeaturedBroadcastChannels.INDEXED_NAMES) setCuratedRoomShown(channelName, false)
        else leaveChannel(channelName)
    }

    /** This wallet's own address, for "You" on the rows it sent. Null while there is none. */
    fun myAddress(): String? = runCatching { walletManager.getAddress() }.getOrNull()

    /** Opening a room is reading it: everything up to now counts as seen. */
    fun markRoomRead(channelName: String) {
        readState.markRead(channelName, System.currentTimeMillis())
    }

    fun markRoomUnread(channelName: String) {
        readState.markUnread(channelName)
    }

    /** Default rooms switched off in Public Chats settings, for this wallet. */
    val hiddenCuratedRooms: StateFlow<Set<String>> = readState.state
        .map { it.hiddenCurated }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /**
     * Public Chats settings: shows or hides one of the default rooms. Off takes the room out of
     * the list and turns its notifications off, which also takes it off the push service's watch
     * list. On brings it back - #kaspa and #kachat-bugs with their bell on again, as they start,
     * and a language room as it was (iOS d5c7613).
     */
    fun setCuratedRoomShown(channelName: String, shown: Boolean) {
        if (channelName !in FeaturedBroadcastChannels.INDEXED_NAMES) return
        readState.setCuratedShown(channelName, shown)
        val joined = joinedChannels.value.any { it.channelName == channelName }
        if (!joined) return
        if (!shown) setNotifyEnabled(channelName, false)
        else if (channelName in FeaturedBroadcastChannels.NAMES) setNotifyEnabled(channelName, true)
    }

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
        // Read state follows the account, and every joined room gets a marker the first time it
        // is seen, so it counts from now rather than from the start of its history.
        viewModelScope.launch {
            walletManager.activeAddressFlow.collect { readState.setCurrentWallet(it) }
        }
        viewModelScope.launch {
            joinedChannels.collect { channels ->
                readState.seedIfMissing(channels.map { it.channelName })
            }
        }
    }

    // Rooms join with notifications OFF and stay off until the user turns a bell on - the
    // curated rooms included. (#kaspa and #kachat-bugs used to be switched on once per wallet
    // here; iOS 1ea3c45.)

    private var indexerPollJob: kotlinx.coroutines.Job? = null

    fun startIndexerBackfill(channelName: String) {
        if (channelName !in com.kachat.app.models.FeaturedBroadcastChannels.INDEXED_NAMES) return
        stopIndexerBackfill()
        indexerPollJob = viewModelScope.launch {
            while (true) {
                // The room stays composed (and this loop alive) while the app is in the
                // background, but there is nobody to show a fresh row to - skip the network work
                // until the app is active again (iOS gates the same poll on applicationState).
                if (notificationHelper.isAppInForeground) {
                    broadcastRepository.backfillFromIndexer(channelName)
                }
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

    /**
     * Creates a curated room's row if it has none. The language rooms are not auto-joined (see
     * [com.kachat.app.models.FeaturedBroadcastChannels.LANGUAGE_NAMES]), so opening one has to
     * materialize its row for the bell/retention state to exist. Deliberately NOT [joinChannel]:
     * that one drives the join DIALOG's state machine and would flash a success result.
     */
    fun ensureCuratedRoomJoined(channelName: String) {
        viewModelScope.launch { broadcastRepository.joinChannel(channelName) }
    }

    /**
     * Bell for a curated room that may have no row yet: join and toggle in ONE coroutine, so the
     * write can't land before the row exists (the two calls are separate coroutines otherwise,
     * and the toggle would silently no-op on a missing row). The join is idempotent.
     */
    fun setNotifyEnabledEnsuringJoined(channelName: String, notifyEnabled: Boolean) {
        viewModelScope.launch {
            broadcastRepository.joinChannel(channelName)
            broadcastRepository.setNotifyEnabled(channelName, notifyEnabled)
        }
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
        // A room left takes its read state with it; rejoining starts counting from then.
        readState.forget(channelName)
    }

    /** This room's own indexer, or "" when it follows the app-wide broadcast indexer. */
    fun indexerOverrideFor(channelName: String): Flow<String> =
        settings.broadcastIndexerOverrides.map { it[channelName.trim().lowercase()].orEmpty() }

    /** The app-wide broadcast indexer, shown as the placeholder a blank override falls back to. */
    val appWideBroadcastIndexer: StateFlow<String> =
        settings.broadcastIndexerUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")

    /** Points one room at its own indexer. Blank clears the override. */
    fun setIndexerOverride(channelName: String, url: String) {
        viewModelScope.launch { settings.setBroadcastIndexerOverride(channelName, url) }
    }

    fun getMessages(channelName: String) = broadcastRepository.getMessages(channelName)

    // ------------------------------------------------------------------
    // Translation - someone else's text in another language, from its long-press menu (iOS 03e5128)
    // ------------------------------------------------------------------
    //
    // The same service, language detection and states KaPosts uses. Public chats only: 1:1 and
    // group messages are encrypted, and translating one would hand its plaintext to a server. A
    // message goes as bare text with no txid - the server caches by KaPost id and checks the text
    // against its own copy of the post, which a public chat message is not - so it is translated
    // and not cached. Nothing shows until Translate is picked; states live in memory only.

    private val _translations = MutableStateFlow<Map<String, com.kachat.app.services.PostTranslationService.TranslationState>>(emptyMap())
    val translations: StateFlow<Map<String, com.kachat.app.services.PostTranslationService.TranslationState>> = _translations.asStateFlow()
    private val _showingOriginal = MutableStateFlow<Set<String>>(emptySet())
    val showingOriginal: StateFlow<Set<String>> = _showingOriginal.asStateFlow()
    /** Messages worth offering Translate for: someone else's text, in a pair the service serves. */
    private val _translatable = MutableStateFlow<Set<String>>(emptySet())
    val translatable: StateFlow<Set<String>> = _translatable.asStateFlow()
    private val consideredTranslations = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var consideredLanguage: String? = null

    /** Per message AND per text: an edit changes the text, and a translation of the old text must
     *  not show over the new one. */
    fun translationKey(messageId: String, text: String): String = "publicchat:$messageId:${text.hashCode()}"

    /** What the service can serve, so Translate is offered only for a pair that can succeed. */
    fun refreshTranslationLanguages() {
        viewModelScope.launch { runCatching { translationService.refreshSupportedLanguages() } }
    }

    /** The reader's language by name ("English"), for "Shows this message in English." */
    fun readerLanguageName(): String =
        translationService.targetLanguage()?.let { translationService.displayName(it) } ?: "your language"

    /** Identifies the message's language once and records whether Translate is worth offering. */
    fun considerTranslation(key: String, text: String) {
        val language = translationService.targetLanguage()
        if (language != consideredLanguage) {
            consideredLanguage = language
            consideredTranslations.clear()
            _translatable.value = emptySet()
        }
        if (!consideredTranslations.add(key)) return
        viewModelScope.launch {
            val source = translationService.detectLanguage(text) ?: return@launch
            if (!translationService.canOfferTranslation(text, source)) return@launch
            _translatable.value = _translatable.value + key
        }
    }

    fun translateMessage(key: String, text: String) {
        _showingOriginal.value = _showingOriginal.value - key
        if (_translations.value[key] == com.kachat.app.services.PostTranslationService.TranslationState.Translating) return
        _translations.value = _translations.value + (key to com.kachat.app.services.PostTranslationService.TranslationState.Translating)
        viewModelScope.launch {
            val next = try {
                val result = translationService.translate(text, postId = null)
                com.kachat.app.services.PostTranslationService.TranslationState.Translated(
                    text = result.text,
                    sourceName = result.sourceLanguage?.let { translationService.displayName(it) } ?: "another language",
                )
            } catch (e: com.kachat.app.services.PostTranslationService.TranslationException) {
                if (e.terminal) com.kachat.app.services.PostTranslationService.TranslationState.Unavailable(e.readerMessage)
                else com.kachat.app.services.PostTranslationService.TranslationState.Failed
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("BroadcastViewModel", "Translation failed", e)
                com.kachat.app.services.PostTranslationService.TranslationState.Failed
            }
            _translations.value = _translations.value + (key to next)
        }
    }

    fun showOriginal(key: String) { _showingOriginal.value = _showingOriginal.value + key }
    fun showTranslation(key: String) { _showingOriginal.value = _showingOriginal.value - key }

    /**
     * A tapped public-chat notification asks the indexer for the room's newest rows at once, in
     * parallel with the navigation, instead of waiting for the room to appear and start its own
     * backfill - so the message the notification announced is already there when the room opens
     * (iOS 609ade0). Runs in this view model's scope, not the caller's effect, so clearing the
     * pending channel as the navigation completes does not cancel it. The rows land in Room and
     * the room's own Flow picks them up.
     */
    fun prefetchNewest(channelName: String) {
        viewModelScope.launch {
            runCatching { broadcastRepository.fetchNewestFromIndexer(channelName) }
                .onFailure { android.util.Log.w("BroadcastViewModel", "Notification prefetch failed", it) }
        }
    }

    // ------------------------------------------------------------------
    // Room window — "Load earlier messages"
    // ------------------------------------------------------------------
    //
    // A curated room holds thirty days of history and the thread screen read all of it, on open
    // and again on every change (iOS 02a4f58). It reads the newest ROOM_WINDOW_SIZE now, and the
    // control at the top of the room adds another window's worth. Per channel, and deliberately
    // NOT persisted: reopening a room starts from the newest again, as iOS does.
    private val roomWindows = MutableStateFlow<Map<String, Int>>(emptyMap())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun getMessageWindow(channelName: String): Flow<BroadcastRepository.RoomWindow> =
        roomWindows
            .map { it[channelName] ?: BroadcastRepository.ROOM_WINDOW_SIZE }
            .distinctUntilChanged()
            .flatMapLatest { limit -> broadcastRepository.getMessageWindow(channelName, limit) }

    /** Widens [channelName]'s window by another [BroadcastRepository.ROOM_WINDOW_SIZE]. */
    fun loadEarlier(channelName: String) {
        roomWindows.value = roomWindows.value.toMutableMap().apply {
            this[channelName] = (this[channelName] ?: BroadcastRepository.ROOM_WINDOW_SIZE) +
                BroadcastRepository.ROOM_WINDOW_SIZE
        }
    }

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

    // The message whose text the composer is editing (one of your own), shown as a banner above
    // the compose field. Mutually exclusive with [_replyingTo], as on iOS.
    private val _editingMessage = MutableStateFlow<BroadcastMessageEntity?>(null)
    val editingMessage: StateFlow<BroadcastMessageEntity?> = _editingMessage.asStateFlow()

    fun startEditing(message: BroadcastMessageEntity) {
        _replyingTo.value = null
        _editingMessage.value = message
    }

    fun cancelEditing() {
        _editingMessage.value = null
    }

    /** The newest edit per message in [channelName], keyed by the edited message's txId - see
     *  [BroadcastRepository.getEdits]. */
    fun getEdits(channelName: String) = broadcastRepository.getEdits(channelName)

    /**
     * Edits one of this wallet's own text messages in a room: sent as an edit envelope exactly
     * like a reaction - one broadcast, no message row of its own (the envelope is filtered out of
     * the room's messages and applied to the message it names).
     */
    fun sendEdit(channelName: String, targetTxId: String, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        // A still-pending target has no real txId yet - an edit naming it could never be resolved
        // by anyone else (same guard as [sendReaction]).
        if (targetTxId.startsWith("pending_")) return
        _editingMessage.value = null
        viewModelScope.launch {
            try {
                broadcastRepository.sendBroadcast(channelName, com.kachat.app.util.MessageEdit.encode(targetTxId, clean))
            } catch (e: Exception) {
                Log.e("BroadcastViewModel", "Error sending broadcast edit", e)
                _sendBroadcastState.value = SendBroadcastUiState(
                    status = SendBroadcastStatus.FAILED,
                    message = humanizeSendError(e)
                )
            }
        }
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

    fun startVoiceRecording(channelName: String, viaNextcloud: Boolean = false) {
        if (_voiceRecordingState.value.status == VoiceRecordingStatus.RECORDING) return
        _nextcloudVoiceRequested.value = viaNextcloud
        try {
            voiceRecorderService.startRecording()
        } catch (e: Exception) {
            Log.e("BroadcastViewModel", "Could not start voice recording", e)
            return
        }
        _voiceRecordingState.value = VoiceRecordingState(status = VoiceRecordingStatus.RECORDING)
        val startedAt = System.currentTimeMillis()
        // Same dynamic ceiling as 1:1/group chats: on-chain broadcast notes are payload-capped
        // at 10s, but a "Record via Nextcloud" note only needs to fit the server, so it runs to
        // the app-wide Nextcloud ceiling.
        val maxDurationMs = if (voiceViaNextcloud) {
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
        // Snapshot once, so a disconnect mid-send can't change the route (iOS
        // PublicChatChannelView.stopAndSendRecording).
        val viaNextcloud = voiceViaNextcloud
        _nextcloudVoiceRequested.value = false
        val elapsed = _voiceRecordingState.value.elapsedMs
        recordingTickerJob?.cancel()
        recordingTickerJob = null
        _voiceRecordingState.value = VoiceRecordingState()

        val file = voiceRecorderService.stopRecording()
        if (file == null || elapsed < VoiceRecorderService.MIN_RECORDING_DURATION_MS) {
            file?.delete()
            return
        }
        sendVoiceMessage(channelName, file, viaNextcloud)
    }

    fun cancelVoiceRecording() {
        _nextcloudVoiceRequested.value = false
        recordingTickerJob?.cancel()
        recordingTickerJob = null
        _voiceRecordingState.value = VoiceRecordingState()
        voiceRecorderService.cancelRecording()
    }

    /** Recorded "via Nextcloud" (the mic's on-chain-or-Nextcloud step, iOS 8b13460) and still
     *  connected, the recorded file (exactly as captured — no re-encode, so the full relaxed-cap length ships byte-for-byte) uploads to
     *  the server and the broadcast is just the public share link, sent as plain text through the
     *  normal [sendBroadcast] pipeline; any upload/share failure falls back to the embedded
     *  on-chain audio envelope below, with a toast so the sender knows. Mirrors ChatViewModel's
     *  1:1/group Nextcloud voice gates exactly. */
    private fun sendVoiceMessage(channelName: String, file: java.io.File, viaNextcloud: Boolean) {
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                if (viaNextcloud) {
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
